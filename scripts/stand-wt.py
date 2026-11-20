#!/usr/bin/env python3
"""Manage project worktrees and audit temporary delivery artifacts."""
import argparse
import datetime as dt
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


SCRIPTS_DIR = Path(__file__).resolve().parent

# Temporary artifacts carry a seven-day TTL (author ruling 2026-11-16). One named
# constant, shared by the routine audit red and the cleaner's default window.
TTL_DAYS = 7


def git(*args, cwd=None):
    return subprocess.check_output(['git', *map(str, args)], cwd=cwd, stderr=subprocess.PIPE).decode().strip()


def root():
    # The first entry is the main checkout, including when invoked from a linked worktree.
    return Path(git('worktree', 'list', '--porcelain').splitlines()[0][9:])


def name(value):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', value) or value in ('.', '..', 'worktree-audit'):
        raise ValueError('invalid worktree name')
    return value


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inventory(directory):
    result = []
    for p in sorted(directory.rglob('*')):
        if p.is_symlink():
            raise ValueError(f'symlink forbidden: {p}')
        if p.is_file() and p != directory / 'MANIFEST.md':
            result.append(p)
    return result


def audit(project, wt, ttl_days=TTL_DAYS):
    directory = project / '.stand' / 'tmp' / name(wt)
    if directory.is_symlink() or (directory / 'MANIFEST.md').is_symlink() or not directory.is_dir():
        raise ValueError(f'not a safe tmp directory: {directory}')
    # Use the named worktree's committed HEAD, not the main checkout's branch.
    checkout = project / '.stand' / 'worktrees' / wt
    checkout = checkout if (checkout / '.git').is_file() else project
    lines = ['# Artifact manifest', '', '| Relative path | Bytes | mtime | Status |', '| --- | ---: | --- | --- |']
    counts = [0, 0]
    expired = []
    now = time.time()
    for path in inventory(directory):
        rel = path.relative_to(directory).as_posix()
        if '|' in rel or '\n' in rel or '\r' in rel:
            raise ValueError(f'unsupported filename: {rel!r}')
        status = '未入库'
        if rel.startswith('delivery/') and rel.count('/') >= 2:
            target = rel
            try:
                committed = subprocess.check_output(['git', 'show', f'HEAD:{target}'], cwd=checkout, stderr=subprocess.DEVNULL)
                if digest(path.read_bytes()) == digest(committed):
                    commit = git('log', '-1', '--format=%H', 'HEAD', '--', target, cwd=checkout)
                    stamp = git('show', '-s', '--format=%ci', commit, cwd=checkout)[:16]
                    status = f'入库 {stamp} {commit}'
            except subprocess.CalledProcessError:
                pass
        counts[status != '未入库'] += 1
        stat = path.stat()
        mtime = dt.datetime.fromtimestamp(stat.st_mtime).isoformat(timespec='seconds')
        # The routine red: an uncommitted artifact past the TTL is a delivery
        # defect, not merely old scratch (author ruling 2026-11-16).
        if status == '未入库' and now - stat.st_mtime > ttl_days * 86400:
            expired.append((rel, mtime))
        lines.append(f'| {rel} | {stat.st_size} | {mtime} | {status} |')
    (directory / 'MANIFEST.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    for rel, mtime in expired:
        print(f'RED {directory.name}: uncommitted and past the {ttl_days}-day TTL ({mtime}): {rel}')
    if expired:
        print(f'RED {directory.name}: {len(expired)} uncommitted file(s) past the {ttl_days}-day TTL')
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', '--project', type=Path, help='project checkout path (default: current working directory); resolves its primary checkout')
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('new'); p.add_argument('name', type=name); p.add_argument('--branch'); p.add_argument('--base', default='feat/base'); p.add_argument('--target', default='feat/base')
    sub.add_parser('list')
    p = sub.add_parser('audit'); p.add_argument('name', nargs='?', type=name)
    p = sub.add_parser('clean'); p.add_argument('--ttl-days', type=float, default=TTL_DAYS); p.add_argument('--apply', action='store_true'); p.add_argument('--force', action='store_true'); p.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if args.root is not None:
        os.chdir(args.root)
    project = root(); tmp = project / '.stand' / 'tmp'
    if args.command == 'new':
        target = project / '.stand' / 'worktrees' / args.name
        branch = args.branch or 'feat/' + args.name
        if not re.fullmatch(r'(feat|bug)/.+', branch) or branch == 'feat/base' or subprocess.run(['git', 'check-ref-format', 'refs/heads/' + branch], cwd=project).returncode:
            raise ValueError('new development branches must use feat/* or bug/*; feat/base is reserved')
        if (args.base, args.target) != ('feat/base', 'feat/base') and not (branch.startswith('bug/') and (args.base, args.target) == ('main', 'main')):
            raise ValueError('only bug/* hotfixes may use --base main --target main; otherwise use feat/base')
        base = git('rev-parse', '--verify', 'refs/heads/' + args.base + '^{commit}', cwd=project)
        exists = subprocess.run(['git', 'show-ref', '--verify', '--quiet', 'refs/heads/' + branch], cwd=project).returncode == 0
        if exists and git('merge-base', branch, base, cwd=project) != base:
            raise ValueError(f'merge-base({branch}, {args.base}) is not {base}')
        if exists and args.base == 'main':
            raise ValueError('hotfix creation requires a new branch to record its main baseline')
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'worktree', 'add', str(target)] + ([branch] if exists else ['-b', branch, base]), cwd=project, check=True)
        if git('merge-base', branch, base, cwd=project) != base:
            raise ValueError(f'merge-base({branch}, {args.base}) validation failed')
        print(f'Base verified: merge-base({branch}, {args.base}) = {base}')
        if args.base == 'main':
            print(f'Hotfix audit: include this line in the MR description: Hotfix-Main-Baseline: {base} (author approval required)')
        for part in ('delivery', 'evidence'):
            (tmp / args.name / part).mkdir(parents=True, exist_ok=True)
        if args.base == 'main':
            (tmp / args.name / 'evidence' / 'hotfix-main-baseline.txt').write_text(f'Hotfix-Main-Baseline: {base}\n', encoding='utf-8')
        audit(project, args.name)
        print(f'Created {target}; artifacts {tmp / args.name}')
    elif args.command == 'list':
        for record in git('worktree', 'list', '--porcelain').split('\n\n'):
            fields = record.splitlines(); path = Path(fields[0][9:]); wt = path.name
            branch = next((x[7:].removeprefix('refs/heads/') for x in fields if x.startswith('branch ')), '(detached)')
            head = next((x[5:] for x in fields if x.startswith('HEAD ')), '?')
            try:
                dirty = bool(git('status', '--porcelain', cwd=path))
            except (subprocess.CalledProcessError, FileNotFoundError):
                dirty = 'unknown'
            directory = tmp / wt
            if directory.is_dir() and not directory.is_symlink():
                uncommitted, committed = audit(project, wt)
                counts = f'artifacts={uncommitted + committed} committed={committed} uncommitted={uncommitted}'
            else:
                counts = 'artifacts=0 committed=0 uncommitted=0'
            print(f'{wt} path={path} branch={branch} HEAD={head} dirty={dirty} {counts}')
    elif args.command == 'audit':
        dirs = [tmp / args.name] if args.name else sorted(tmp.iterdir()) if tmp.exists() else []
        for directory in dirs:
            if directory.name != 'worktree-audit' and directory.is_dir() and not directory.is_symlink():
                uncommitted, committed = audit(project, directory.name)
                print(f'{directory.name}: committed={committed} uncommitted={uncommitted}')
    else:
        if args.ttl_days < 0 or (args.apply and args.dry_run):
            raise ValueError('invalid TTL or conflicting apply/dry-run')
        candidates = []
        for directory in sorted(tmp.iterdir()) if tmp.exists() else []:
            if directory.name == 'worktree-audit' or directory.is_symlink() or not directory.is_dir():
                continue
            # Exclude only the generated root manifest, not artifacts of the same name.
            entries = [directory] + [p for p in directory.rglob('*') if p != directory / 'MANIFEST.md']
            if any(p.is_symlink() for p in entries):
                print(f'WARNING unsafe symlink: {directory}'); continue
            # Only artifact timestamps age a directory. The directory's OWN mtime
            # is not one: `audit` rewrites the generated MANIFEST.md on every run,
            # and that bumps the parent directory's mtime — counting it would let
            # the routine audit reset the TTL clock and the clean would never fire
            # (author ruling 2026-11-16/11-19: past the TTL it must really delete).
            stamps = [p.stat().st_mtime for p in entries if p != directory]
            latest = max(stamps) if stamps else directory.stat().st_mtime
            if time.time() - latest <= args.ttl_days * 86400:
                continue
            uncommitted, _ = audit(project, directory.name)
            if uncommitted and not args.force:
                print(f'WARNING retained {directory}: {uncommitted} uncommitted'); continue
            candidates.append(directory)
        for directory in candidates:
            print(f'{"DELETE" if args.apply else "DRY-RUN"} {directory}')
        print(f'{"cleaned" if args.apply else "would clean"} {len(candidates)} director(ies) past the {args.ttl_days}-day TTL'
              + ('' if args.apply else ' (dry-run: pass --apply to delete)'))
        if args.apply and candidates:
            audit_base = tmp / 'worktree-audit'
            if audit_base.is_symlink():
                raise ValueError(f'unsafe audit directory: {audit_base}')
            audit_base.mkdir(exist_ok=True)
            stamp = dt.datetime.now().strftime('%Y%m%d_%H%M%S_%f')
            audit_dir = audit_base / stamp
            audit_dir.mkdir()
            listing = '\n'.join(str(p) for p in candidates)
            for directory in candidates:
                report = audit_dir / f'clean-{directory.name}.txt'
                report.write_text(f'Candidates:\n{listing}\n\nManifest for {directory}:\n' + (directory / 'MANIFEST.md').read_text(encoding='utf-8'), encoding='utf-8')
                print(f'AUDIT {report}')
            for directory in candidates:
                shutil.rmtree(directory)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print(f'Error: {exc}', file=sys.stderr)
        sys.exit(2)
