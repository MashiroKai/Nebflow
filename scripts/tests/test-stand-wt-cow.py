#!/usr/bin/env python3
"""Unit tests for scripts/stand-wt-cow.py; every case runs in a synthetic repo under /tmp.

Covers the pieces the M1 gate (`scripts/check-wt-cow.mjs`) does NOT own: seed index
shape and reuse, the fallback-reason enum, verify() defect sensitivity, record round-trip,
and the three degrade modes used as negative controls.
"""
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load_cow():
    spec = importlib.util.spec_from_file_location('stand_wt_cow', SCRIPTS / 'stand-wt-cow.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


COW = load_cow()


def run(cmd, cwd, expect=0):
    out = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    assert out.returncode == expect, f'{" ".join(map(str, cmd))} → {out.returncode}\n{out.stdout}'
    return out.stdout


def make_repo(root, files=6, attributes='*' + ' eol=lf\n'):
    run(['git', 'init', '-q', '-b', 'main'], root)
    run(['git', 'config', 'user.email', 'scratch@example.invalid'], root)
    run(['git', 'config', 'user.name', 'Scratch'], root)
    (Path(root) / '.gitignore').write_text('/.stand/\n')
    (Path(root) / '.gitattributes').write_text(attributes)
    (Path(root) / 'bin').mkdir()
    (Path(root) / 'bin' / 'tool.sh').write_text('#!/bin/sh\necho hi\n')
    os.chmod(Path(root) / 'bin' / 'tool.sh', 0o755)
    os.symlink('../bin/tool.sh', Path(root) / 'alias.sh')
    for i in range(files):
        (Path(root) / f'f{i}.txt').write_text(f'body {i}\n')
    run(['git', 'add', '-A'], root)
    run(['git', 'commit', '-qm', 'seed'], root)
    run(['git', 'branch', 'feat/base'], root)
    return run(['git', 'rev-parse', 'HEAD'], root).strip()


class SeedLifecycle(unittest.TestCase):
    def test_seed_index_and_reuse(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            sha = make_repo(repo)
            tree = COW.tree_sha(repo, sha)
            seed, reused = COW.ensure_seed(repo, tree)
            self.assertFalse(reused)
            self.assertTrue((seed / 'f0.txt').is_file())
            self.assertTrue((seed / 'alias.sh').is_symlink(), 'seed must keep symlinks')
            self.assertTrue(os.access(seed / 'bin' / 'tool.sh', os.X_OK), 'seed must keep exec bits')
            index = json.loads((repo / '.stand' / 'seeds' / f'{tree}.json').read_text())
            self.assertEqual(index['format'], 1)
            self.assertEqual(index['tree'], tree)
            self.assertEqual(index['builder'], 'git-archive')
            self.assertEqual(index['files'], 10)  # .gitignore/.gitattributes/alias.sh/bin/tool.sh + f0..f5
            # second call reuses: the index timestamp does not move
            stamp = (repo / '.stand' / 'seeds' / f'{tree}.json').stat().st_mtime_ns
            again, reused = COW.ensure_seed(repo, tree)
            self.assertTrue(reused)
            self.assertEqual(again, seed)
            self.assertEqual((repo / '.stand' / 'seeds' / f'{tree}.json').stat().st_mtime_ns, stamp)

    def test_concurrent_seed_build_is_safe(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            sha = make_repo(repo)
            tree = COW.tree_sha(repo, sha)
            COW.ensure_seed(repo, tree)
            COW.ensure_seed(repo, tree, rebuild=True)  # rename-based publish, loser dropped
            leftovers = [p.name for p in (repo / '.stand' / 'seeds').iterdir() if '.tmp-' in p.name]
            self.assertEqual(leftovers, [])


class FallbackReasons(unittest.TestCase):
    def test_tree_unknown_and_submodule_and_attributes(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            sha = make_repo(repo)
            tree = COW.tree_sha(repo, sha)
            ok, reason = COW.preflight(repo, repo / '.stand' / 'seeds', repo / 'wt', None)
            self.assertFalse(ok)
            self.assertEqual(reason, 'tree-unknown')
            # a gitlink (mode 160000) in the tree must refuse instead of materialising junk
            run(['git', 'update-index', '--add', '--cacheinfo', f'160000,{sha},vendor'], repo)
            run(['git', 'commit', '-qm', 'gitlink'], repo)
            submodule_tree = COW.tree_sha(repo, 'HEAD')
            ok, reason = COW.preflight(repo, repo / '.stand' / 'seeds', repo / 'wt', submodule_tree)
            self.assertFalse(ok)
            self.assertEqual(reason, 'submodule')
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            make_repo(repo, attributes='*.txt filter=derived\n')
            tree = COW.tree_sha(repo, 'HEAD')
            ok, reason = COW.preflight(repo, repo / '.stand' / 'seeds', repo / 'wt', tree)
            self.assertFalse(ok)
            self.assertEqual(reason, 'filter-attributes')
            self.assertTrue(COW.attribute_hazards(repo, tree))

    def test_cross_volume_refused_only_when_volumes_differ(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            make_repo(repo)
            tree = COW.tree_sha(repo, 'HEAD')
            if platform.system() != 'Darwin':
                self.assertEqual(COW.preflight(repo, repo / '.stand' / 'seeds', repo / 'wt', tree)[1], 'non-darwin')
                return
            ok, reason = COW.preflight(repo, repo / '.stand' / 'seeds', repo / 'wt', tree)
            self.assertTrue(ok, f'same-volume preflight should pass, got {reason}')
            other = Path('/Volumes')
            if other.is_dir() and COW.volume_id(other) != COW.volume_id(repo):
                ok, reason = COW.preflight(repo, repo / '.stand' / 'seeds', other / 'wt', tree)
                self.assertEqual(reason, 'cross-volume')


class Verification(unittest.TestCase):
    def test_verify_accepts_equal_tree_and_rejects_defects(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            sha = make_repo(repo)
            tree = COW.tree_sha(repo, sha)
            target = repo / 'wt'
            run(['git', 'worktree', 'add', '--no-checkout', str(target), sha], repo)
            seed, _ = COW.ensure_seed(repo, tree)
            COW.populate(seed, target)
            run(['git', 'read-tree', tree], target)
            problems, detail = COW.verify(repo, target, tree)
            self.assertEqual(problems, [])
            self.assertEqual(detail['paths'], detail['indexed'])
            # negative: one byte of content changed ⇒ named file reported
            (target / 'f1.txt').write_text('tampered\n')
            problems, _ = COW.verify(repo, target, tree)
            self.assertTrue(any('f1.txt' in p or 'not clean' in p for p in problems), problems)
            (target / 'f1.txt').write_text('body 1\n')
            self.assertEqual(COW.verify(repo, target, tree)[0], [])
            # negative: exec bit dropped
            os.chmod(target / 'bin' / 'tool.sh', 0o644)
            problems, _ = COW.verify(repo, target, tree)
            self.assertTrue(any('executable' in p for p in problems), problems)


class DegradeModes(unittest.TestCase):
    def _create(self, repo, name):
        target = repo / '.stand' / 'worktrees' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        base = run(['git', 'rev-parse', 'HEAD'], repo).strip()
        branch = f'feat/{name}'

        def native_add():
            run(['git', 'worktree', 'add', str(target), '-b', branch, base], repo)

        return COW.create(repo, target, native_add, branch=branch, base_sha=base), target

    def test_copy_mode_stays_correct(self):
        if platform.system() != 'Darwin':
            self.skipTest('clonefile backend is darwin-only')
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            make_repo(repo)
            os.environ[COW.DEGRADE_ENV] = 'copy'
            try:
                outcome, target = self._create(repo, 'copied')
            finally:
                os.environ.pop(COW.DEGRADE_ENV, None)
            self.assertEqual(outcome.backend, 'cow')
            self.assertEqual(COW.verify(repo, target, COW.tree_sha(repo, 'HEAD'))[0], [])

    def test_wrong_seed_and_partial_fall_back_to_native(self):
        if platform.system() != 'Darwin':
            self.skipTest('clonefile backend is darwin-only')
        for mode, reason in (('wrong-seed', 'verify-failed'), ('partial', 'verify-failed')):
            with tempfile.TemporaryDirectory() as temp:
                repo = Path(temp)
                make_repo(repo)
                os.environ[COW.DEGRADE_ENV] = mode
                try:
                    outcome, target = self._create(repo, 'degraded-' + mode)
                finally:
                    os.environ.pop(COW.DEGRADE_ENV, None)
                self.assertEqual(outcome.backend, 'native', f'{mode}: {outcome.as_dict()}')
                self.assertEqual(outcome.reason, reason)
                self.assertEqual(COW.verify(repo, target, COW.tree_sha(repo, 'HEAD'))[0], [])


class Records(unittest.TestCase):
    def test_record_round_trip_and_unknown_default(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            make_repo(repo)
            self.assertEqual(COW.backend_of(repo, 'legacy-tree'), ('unknown', None))
            COW.record(repo, 't1', COW.Rcow('cow', None, tree='abc', seed=repo / 'seed', elapsed_ms=12))
            payload = json.loads((repo / '.stand' / 'wt-backend' / 't1.json').read_text())
            self.assertEqual(payload['backend'], 'cow')
            self.assertEqual(payload['elapsedMs'], 12)
            self.assertEqual(COW.backend_of(repo, 't1'), ('cow', None))
            COW.record(repo, 't2', COW.Rcow('native', 'cross-volume', tree='abc'))
            self.assertEqual(COW.backend_of(repo, 't2'), ('native', 'cross-volume'))

    def test_probe_reports_platform_truth(self):
        with tempfile.TemporaryDirectory() as temp:
            ok, reason = COW.clone_probe(temp)
            if platform.system() == 'Darwin':
                self.assertTrue(ok, f'expected clone support on darwin, got {reason}')
            else:
                self.assertFalse(ok)
                self.assertEqual(reason, 'non-darwin')


if __name__ == '__main__':
    unittest.main()
