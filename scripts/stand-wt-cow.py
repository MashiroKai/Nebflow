#!/usr/bin/env python3
"""Copy-on-write worktree backend for `scripts/stand-wt.py` (workspace-compute R1a · M0/M1).

`stand-wt.py new --backend cow` stays the single entry point; this module is only the
backend it dispatches to. Two layers live here:

  * seed — one materialisation per Git **tree** under `<repo>/.stand/seeds/<tree>/`,
    written from the object store (`git archive`), never from an existing working copy.
    `<tree>.json` beside it is the index (file count, byte total, builder, git version).
  * population — `cp -cR` = APFS clonefile(2): the new worktree's files share the seed's
    data blocks, so a tree costs directory/inode metadata instead of the tree's bytes.

Anything that cannot be proven equivalent falls back to the native `git worktree add`
path **with an explicit reason** — never a silent degradation:

    non-darwin            clonefile(2) does not exist
    probe-failed          `cp -c` on this volume failed (not APFS, or unsupported)
    cross-volume          seed and worktree on different volumes ⇒ clone impossible
    submodule             the tree carries gitlinks `git archive` cannot materialise
    filter-attributes     .gitattributes has `filter=`/`export-ignore`/`export-subst`
    verify-failed         post-build verification disagreed with a checkout
    interrupted           a build died half way; the partial tree was discarded

Fail-closed is the contract: after a COW build, `verify()` compares HEAD tree, index
(`git ls-files --stage`), cleanliness, executable bits and symlink targets against the
tree. Any mismatch discards the tree and rebuilds it natively.

Negative-control knob (test only, never a product path): `STAND_WT_DEGRADE` accepts
`copy` (seed cloned by full copy — correctness unchanged, the disk claim dies),
`wrong-seed` (seed taken from a different tree) and `partial` (population truncated).
`scripts/check-wt-cow.mjs` uses it to prove its own criteria are defect-sensitive.

CLI (standalone use / probes):
    stand-wt-cow.py probe [PATH]            volume capability probe, JSON
    stand-wt-cow.py seed REPO TREEISH       build or report the seed for that tree, JSON
    stand-wt-cow.py new REPO NAME [--branch B] [--base SHA]   build a COW worktree, JSON
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import time

FORMAT = 1
SEEDS = Path('.stand') / 'seeds'
BACKEND_DIR = Path('.stand') / 'wt-backend'
DEGRADE_ENV = 'STAND_WT_DEGRADE'

# Reasons that make the COW path unusable for a tree/volume pair (see module docstring).
REASONS = (
    'non-darwin', 'probe-failed', 'cross-volume', 'submodule', 'filter-attributes',
    'branch-in-use', 'verify-failed', 'interrupted', 'tree-unknown',
)


class Rcow:
    """Outcome of one build: `backend` is what actually happened, not what was asked."""

    def __init__(self, backend, reason=None, **extra):
        self.backend = backend
        self.reason = reason
        self.tree = extra.get('tree')
        self.seed = extra.get('seed')
        self.elapsed_ms = extra.get('elapsed_ms')
        self.reused_seed = extra.get('reused_seed')
        # True when create() already proved equivalence for this tree (the caller must not
        # pay for a second full verification — on the real tree that is ~2s of stat work).
        self.verified = extra.get('verified', False)

    def as_dict(self):
        return {k: v for k, v in (
            ('backend', self.backend), ('reason', self.reason), ('tree', self.tree),
            ('seed', str(self.seed) if self.seed else None), ('elapsedMs', self.elapsed_ms),
            ('reusedSeed', self.reused_seed), ('verified', self.verified or None)) if v is not None}


def git(*args, cwd=None, text=True):
    out = subprocess.run(['git', *map(str, args)], cwd=cwd, check=True,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return out.stdout.decode().strip() if text else out.stdout


def degrade_mode():
    value = os.environ.get(DEGRADE_ENV, '').strip()
    return value or None


# ── volume / capability ─────────────────────────────────────────────────────


def volume_id(path):
    """Device id of the volume holding `path` (nearest existing ancestor)."""
    probe = Path(path).resolve()
    while not probe.exists():
        probe = probe.parent
    return os.stat(probe).st_dev


def _probe_dir(directory):
    """Create (if needed) a scratch dir on the volume we are asked about."""
    probe = Path(directory).resolve()
    probe.mkdir(parents=True, exist_ok=True)
    return probe


def clone_probe(directory):
    """Empirical `cp -c` check on `directory`'s volume: (ok, reason)."""
    if platform.system() != 'Darwin':
        return False, 'non-darwin'
    scratch = _probe_dir(directory)
    try:
        with tempfile.TemporaryDirectory(prefix='wt-cow-probe-', dir=scratch) as tmp:
            src = Path(tmp) / 'src.bin'
            dst = Path(tmp) / 'dst.bin'
            src.write_bytes(b'clone-probe' * 4096)
            done = subprocess.run(['cp', '-c', str(src), str(dst)],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if done.returncode != 0 or not dst.exists() or dst.read_bytes() != src.read_bytes():
                return False, 'probe-failed'
    except OSError:
        return False, 'probe-failed'
    return True, None


def tree_sha(repo, rev):
    try:
        return git('rev-parse', f'{rev}^{{tree}}', cwd=repo)
    except subprocess.CalledProcessError:
        return None


def attribute_hazards(repo, tree):
    """`.gitattributes` entries whose checkout result diverges between archive/checkout."""
    try:
        blob = subprocess.run(['git', 'cat-file', 'blob', f'{tree}:.gitattributes'], cwd=repo,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError:
        return []
    if blob.returncode != 0:
        return []
    hazards = []
    for line in blob.stdout.decode('utf-8', 'replace').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        for key in ('filter=', 'export-ignore', 'export-subst'):
            if key in line:
                hazards.append(line)
    return hazards


def preflight(repo, seed_root, target, tree):
    """(ok, reason) — every refusal is a named, reportable reason."""
    if platform.system() != 'Darwin':
        return False, 'non-darwin'
    ok, reason = clone_probe(Path(target).parent)
    if not ok:
        return False, reason
    if volume_id(seed_root) != volume_id(Path(target).parent):
        return False, 'cross-volume'
    if tree is None:
        return False, 'tree-unknown'
    gitlinks = [l for l in git('ls-tree', '-r', tree, cwd=repo).splitlines() if l.startswith('160000')]
    if gitlinks:
        return False, 'submodule'
    if attribute_hazards(repo, tree):
        return False, 'filter-attributes'
    return True, None


# ── seed ───────────────────────────────────────────────────────────────────


def seed_path(repo, tree):
    return Path(repo) / SEEDS / tree


def seed_index(repo, tree):
    return Path(repo) / SEEDS / f'{tree}.json'


def _seed_bytes(directory):
    total = 0
    for p in Path(directory).rglob('*'):
        if p.is_file() and not p.is_symlink():
            total += p.stat().st_size
    return total


def ensure_seed(repo, tree, rebuild=False):
    """Materialise (or reuse) the seed for `tree`. Returns (path, reused).

    Concurrent builders are safe without a lock: each writes into its own staging dir and
    the publish step is a rename — the first winner stays, the loser's staging is dropped
    (same tree ⇒ identical content, so either winner is correct).
    """
    final = seed_path(repo, tree)
    index_file = seed_index(repo, tree)
    if final.is_dir() and index_file.is_file() and not rebuild:
        return final, True
    seeds = Path(repo) / SEEDS
    seeds.mkdir(parents=True, exist_ok=True)
    staging = seeds / f'{tree}.tmp-{os.getpid()}-{os.urandom(4).hex()}'
    staging.mkdir()
    tmp_index = seeds / f'{staging.name}.json'
    try:
        # Object store → tar stream, then extract: no working copy is read anywhere.
        archive = subprocess.Popen(['git', 'archive', '--format=tar', tree], cwd=repo,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        untar = subprocess.Popen(['tar', '-x', '-f', '-', '-C', str(staging)],
                                 stdin=archive.stdout, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.PIPE)
        archive.stdout.close()
        _, tar_err = untar.communicate()
        archive_err = archive.stderr.read()
        archive.stderr.close()
        if archive.wait() != 0:
            raise RuntimeError(archive_err.decode().strip() or 'git archive failed')
        if untar.returncode != 0:
            raise RuntimeError(tar_err.decode().strip() or 'tar extract failed')
        files = sum(1 for p in staging.rglob('*') if p.is_file() or p.is_symlink())
        index = {
            'format': FORMAT, 'tree': tree, 'files': files, 'bytes': _seed_bytes(staging),
            'builder': 'git-archive', 'gitVersion': git('--version'),
            'platform': platform.system(), 'builtAt': dt.datetime.now().astimezone().isoformat(),
        }
        tmp_index.write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8')
        if final.exists():
            shutil.rmtree(staging)
        else:
            os.replace(staging, final)
        os.replace(tmp_index, index_file)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        tmp_index.unlink(missing_ok=True)
        raise
    return final, False


# ── population ─────────────────────────────────────────────────────────────


def populate(seed, target):
    """Seed → worktree. Clone (shared blocks) unless the degrade knob forced a copy."""
    mode = degrade_mode()
    if mode in ('copy', 'wrong-seed', 'partial'):
        if mode == 'copy':
            subprocess.run(['cp', '-R', f'{seed}/.', str(target)], check=True)
        elif mode == 'wrong-seed':
            other = seed.parent / '.wrong-seed'
            if not other.is_dir():
                other.mkdir(exist_ok=True)
                (other / 'NOT-THE-SEED.txt').write_text('wrong seed\n', encoding='utf-8')
            subprocess.run(['cp', '-cR', f'{other}/.', str(target)], check=True)
        else:
            subprocess.run(['cp', '-cR', f'{seed}/.', str(target)], check=True)
            victims = sorted(p for p in Path(target).rglob('*') if p.is_file())[:5]
            for victim in victims:
                victim.unlink()
        return mode
    subprocess.run(['cp', '-cR', f'{seed}/.', str(target)], check=True)
    return 'clone'


# ── verification ───────────────────────────────────────────────────────────


def _stage_index(entries):
    """`ls-tree -r` / `ls-files --stage` lines → {path: (mode, sha)}.

    The two formats differ: `ls-tree` is `mode SP type SP sha TAB path`, `ls-files --stage`
    is `mode SP sha SP stage TAB path`. Both are exactly three fields, so the type name
    (`blob`) is what tells them apart.
    """
    result = {}
    for line in entries.splitlines():
        if not line.strip():
            continue
        meta, path = line.split('\t', 1)
        fields = meta.split()
        sha = fields[2] if fields[1] == 'blob' else fields[1]
        result[path] = (fields[0], sha)
    return result


def verify(repo, target, tree):
    """(problems, detail) — empty problems means the tree equals a checkout of `tree`."""
    target = Path(target)
    problems = []
    try:
        head_tree = git('rev-parse', 'HEAD^{tree}', cwd=target)
    except subprocess.CalledProcessError:
        return ['HEAD unreadable'], {}
    if head_tree != tree:
        problems.append(f'HEAD tree {head_tree} != {tree}')
    status = git('status', '--porcelain', cwd=target)
    if status:
        head = '\n'.join(status.splitlines()[:5])
        problems.append(f'worktree not clean ({len(status.splitlines())} entries): {head}')
    want = _stage_index(git('ls-tree', '-r', tree, cwd=repo))
    got = _stage_index(git('ls-files', '--stage', cwd=target))
    missing = sorted(set(want) - set(got))
    extra = sorted(set(got) - set(want))
    if missing:
        problems.append(f'{len(missing)} tracked paths absent, e.g. {missing[:3]}')
    if extra:
        problems.append(f'{len(extra)} unexpected indexed paths, e.g. {extra[:3]}')
    differing = [p for p in sorted(set(want) & set(got)) if want[p] != got[p]]
    if differing:
        problems.append(f'{len(differing)} mode/blob mismatches, e.g. '
                        f'{[(p, want[p], got[p]) for p in differing[:3]]}')
    # Mode-sensitive spot checks only (executable / symlink): content and presence are
    # already proven by the index comparison + the clean-status read above, so walking all
    # ~5k paths here would buy nothing and cost seconds per build.
    for path, (mode, sha) in want.items():
        if mode not in ('120000', '100755'):
            continue
        full = target / path
        if mode == '120000':
            if not full.is_symlink():
                problems.append(f'symlink lost: {path}')
            elif os.readlink(full) != git('cat-file', 'blob', sha, cwd=repo):
                problems.append(f'symlink target wrong: {path}')
        elif not os.access(full, os.X_OK):
            problems.append(f'executable bit lost: {path}')
        if len(problems) > 20:
            problems.append('... (truncated)')
            break
    detail = {'tree': tree, 'paths': len(want), 'indexed': len(got)}
    return problems, detail


# ── records ────────────────────────────────────────────────────────────────


def record_path(repo, name):
    return Path(repo) / BACKEND_DIR / f'{name}.json'


def record(repo, name, outcome):
    path = record_path(repo, name)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = dict(outcome.as_dict())
    payload['name'] = name
    payload['at'] = dt.datetime.now().astimezone().isoformat()
    path.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    return path


def backend_of(repo, name):
    """Recorded backend for an existing worktree; `unknown` for pre-M1 trees (read-only)."""
    path = record_path(repo, name)
    if not path.is_file():
        return 'unknown', None
    try:
        payload = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return 'unknown', None
    return payload.get('backend', 'unknown'), payload.get('reason')


# ── build ──────────────────────────────────────────────────────────────────


def discard(repo, target):
    target = Path(target)
    subprocess.run(['git', 'worktree', 'remove', '--force', str(target)], cwd=repo,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if target.exists():
        shutil.rmtree(target, ignore_errors=True)
    subprocess.run(['git', 'worktree', 'prune'], cwd=repo,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def branch_exists(repo, branch):
    return subprocess.run(['git', 'show-ref', '--verify', '--quiet', 'refs/heads/' + branch],
                          cwd=repo).returncode == 0


def branch_checked_out(repo, branch):
    """True when some worktree already has `branch` checked out (native would refuse)."""
    target = f'branch refs/heads/{branch}'
    for record in git('worktree', 'list', '--porcelain', cwd=repo).split('\n\n'):
        if target in record.splitlines():
            return True
    return False


def attach_branch(repo, target, branch, base_sha, exists):
    """Point HEAD at `branch` only after the tree verified — a failed build leaves no ref.

    The branch itself is created here, not by `worktree add -b`: a COW build that later
    proves inequivalent must not leave a half-created ref behind (the native fallback then
    creates it exactly as the historic path does).
    """
    if not branch:
        return
    if not exists:
        git('branch', branch, base_sha, cwd=repo)
    git('symbolic-ref', 'HEAD', 'refs/heads/' + branch, cwd=target)


def create(repo, target, native_add, branch=None, base_sha=None, tree=None, exists=None):
    """Build a COW worktree, falling back to `native_add()` whenever equivalence is unproven.

    `native_add` is the caller's own `git worktree add` (branch/base/path discipline stays
    in stand-wt.py — this module never re-implements it).
    """
    repo = Path(repo)
    target = Path(target)
    started = time.time()
    tree = tree or tree_sha(repo, base_sha)
    if exists is None:
        exists = branch_exists(repo, branch)
    ok, reason = preflight(repo, Path(repo) / SEEDS, target, tree)
    if ok and branch and exists and branch_checked_out(repo, branch):
        ok, reason = False, 'branch-in-use'
    if not ok:
        native_add()
        return Rcow('native', reason, tree=tree, elapsed_ms=int((time.time() - started) * 1000))
    seed = None
    try:
        seed, reused = ensure_seed(repo, tree)
        # --no-checkout --detach: no files, no ref. Population + verification happen on a
        # detached HEAD; the branch is attached afterwards (see attach_branch).
        subprocess.run(['git', 'worktree', 'add', '--no-checkout', '--detach', str(target), str(base_sha)],
                       cwd=repo, check=True, stdout=subprocess.DEVNULL)
        mode = populate(seed, target)
        git('read-tree', tree, cwd=target)
        problems, detail = verify(repo, target, tree)
        if problems:
            discard(repo, target)
            native_add()
            return Rcow('native', 'verify-failed', tree=tree, seed=seed,
                        elapsed_ms=int((time.time() - started) * 1000))
        attach_branch(repo, target, branch, base_sha, exists)
    except (OSError, subprocess.CalledProcessError, RuntimeError) as exc:
        discard(repo, target)
        native_add()
        return Rcow('native', 'interrupted', tree=tree, detail=str(exc)[:200],
                    elapsed_ms=int((time.time() - started) * 1000))
    return Rcow('cow', None, tree=tree, seed=seed, reused_seed=reused, verified=True,
                elapsed_ms=int((time.time() - started) * 1000))


# ── CLI ────────────────────────────────────────────────────────────────────


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('probe'); p.add_argument('path', nargs='?', default='.')
    p = sub.add_parser('seed'); p.add_argument('repo'); p.add_argument('treeish'); p.add_argument('--rebuild', action='store_true')
    p = sub.add_parser('new'); p.add_argument('repo'); p.add_argument('name')
    p.add_argument('--branch'); p.add_argument('--base', default='feat/base')
    args = parser.parse_args(argv)
    if args.command == 'probe':
        ok, reason = clone_probe(args.path)
        print(json.dumps({'supported': ok, 'reason': reason,
                          'platform': platform.system(), 'path': str(Path(args.path).resolve())}, indent=2))
        return 0 if ok else 2
    if args.command == 'seed':
        tree = tree_sha(args.repo, args.treeish)
        if tree is None:
            print(json.dumps({'error': 'tree-unknown', 'treeish': args.treeish}), file=sys.stderr)
            return 2
        seed, reused = ensure_seed(args.repo, tree, rebuild=args.rebuild)
        print(json.dumps({'tree': tree, 'seed': str(seed), 'reused': reused,
                          'index': json.loads(seed_index(args.repo, tree).read_text())}, indent=2))
        return 0
    repo = Path(git('worktree', 'list', '--porcelain', cwd=args.repo).splitlines()[0][9:])
    branch = args.branch or 'feat/' + args.name
    target = repo / '.stand' / 'worktrees' / args.name
    exists = subprocess.run(['git', 'show-ref', '--verify', '--quiet', 'refs/heads/' + branch],
                            cwd=repo).returncode == 0
    base = git('rev-parse', '--verify', 'refs/heads/' + args.base + '^{commit}', cwd=repo)

    def native_add():
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'worktree', 'add', str(target)] +
                       ([branch] if exists else ['-b', branch, base]), cwd=repo, check=True)

    target.parent.mkdir(parents=True, exist_ok=True)
    outcome = create(repo, target, native_add, branch=branch, exists=exists, base_sha=base)
    record(repo, args.name, outcome)
    print(json.dumps(outcome.as_dict(), indent=2))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print(f'Error: {exc}', file=sys.stderr)
        sys.exit(2)
