#!/usr/bin/env python3
"""Synthetic Git integration test; no live project worktrees are touched."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def make_runner(case, repo):
    def run(*cmd, cwd=repo, expect=0):
        p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print('$', ' '.join(map(str, cmd)), '\n', p.stdout, sep='')
        case.assertEqual(p.returncode, expect)
        return p.stdout
    return run


def seed_repo(run):
    run('git', 'config', 'user.email', 'scratch@example.invalid')
    run('git', 'config', 'user.name', 'Scratch')


class Lifecycle(unittest.TestCase):
    def test_lifecycle_and_delivery(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            run = make_runner(self, repo)
            run('git', 'init', '-q')
            seed_repo(run)
            (repo / '.gitignore').write_text('/.stand/\n')
            (repo / 'seed').write_text('seed\n')
            run('git', 'add', '.gitignore', 'seed')
            run('git', 'commit', '-qm', 'seed')
            run('git', 'branch', 'feat/base')
            base = run('git', 'rev-parse', 'HEAD').strip()
            tool = str(SCRIPTS / 'stand-wt.py')
            checker = str(SCRIPTS / 'check-delivery.py')
            run('python3', tool, '--root', str(repo), 'new', 'sample', '--branch', 'feat/sample', cwd=repo.parent)
            wt = repo / '.stand/worktrees/sample'
            tmp = repo / '.stand/tmp/sample'
            (tmp / 'MANIFEST.md').unlink()
            output = run('python3', checker, '--root', str(wt), '--manifest-only', cwd=repo.parent, expect=1)
            self.assertIn('missing', output)
            run('python3', tool, '--project', str(wt), 'audit', 'sample', cwd=repo.parent)
            output = run('python3', checker, '--project', str(wt), '--manifest-only', cwd=repo.parent)
            self.assertIn('Temporary delivery and manifest checked:', output)
            artifact = tmp / 'delivery/batch/changes.md'
            artifact.parent.mkdir(parents=True)
            artifact.write_text('What Why Impact\n')
            run('python3', tool, 'list')
            run('python3', tool, 'audit', 'sample')
            self.assertIn('未入库', (tmp / 'MANIFEST.md').read_text())
            output = run('python3', checker, '--manifest-only', cwd=wt)
            self.assertIn('WARNING uncommitted within TTL', output)
            os.utime(artifact, (1, 1))
            run('python3', tool, 'audit', 'sample')
            output = run('python3', checker, '--manifest-only', cwd=wt, expect=1)
            self.assertIn('delivery/batch/changes.md', output)
            os.utime(artifact, None)
            run('python3', tool, 'audit', 'sample')
            (tmp / 'MANIFEST.md').write_text('bad manifest\n')
            run('python3', checker, '--base', base, '--tmp', str(tmp), cwd=wt, expect=1)
            run('python3', tool, 'audit', 'sample')
            (wt / 'openspec/changes/example').mkdir(parents=True)
            (wt / 'openspec/changes/example/spec.md').write_text('change\n')
            run('git', 'add', 'openspec', cwd=wt)
            run('git', 'commit', '-qm', 'product change', cwd=wt)
            run('python3', checker, '--base', base, '--tmp', str(tmp), cwd=wt, expect=1)
            dest = wt / 'delivery/batch'
            dest.mkdir(parents=True)
            shutil.copy2(artifact, dest / 'changes.md')
            (dest / 'behavior.md').write_text('before after mermaid\n')
            (dest / 'state-machine.md').write_text('before after statediagram\n')
            run('git', 'add', 'delivery', cwd=wt)
            run('git', 'commit', '-qm', 'delivery', cwd=wt)
            run('python3', tool, 'audit', 'sample', cwd=wt)
            self.assertIn('入库 ', (tmp / 'MANIFEST.md').read_text())
            run('python3', checker, '--base', base, '--tmp', str(tmp), cwd=wt)
            old = 1
            for p in [tmp, tmp / 'delivery', tmp / 'delivery/batch', artifact, tmp / 'evidence']:
                os.utime(p, (old, old))
            output = run('python3', tool, 'clean', '--dry-run')
            self.assertIn('DRY-RUN', output)
            self.assertTrue(tmp.exists())
            run('python3', tool, 'clean', '--apply')
            self.assertFalse(tmp.exists())
            reports = list((repo / '.stand/tmp/worktree-audit').glob('*/clean-sample.txt'))
            self.assertEqual(len(reports), 1)
            self.assertIn('入库 ', reports[0].read_text())
            self.assertIn(str(tmp.resolve()), reports[0].read_text())
            self.assertIn('SKIP temporary manifest', run('python3', checker, '--manifest-only'))


class BaseDiscipline(unittest.TestCase):
    def test_new_branch_default_and_validation(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            run = make_runner(self, repo)
            run('git', 'init', '-q', '-b', 'main')
            seed_repo(run)
            (repo / '.gitignore').write_text('/.stand/\n')
            (repo / 'seed').write_text('A\n')
            run('git', 'add', '.gitignore', 'seed')
            run('git', 'commit', '-qm', 'A')
            main_tip = run('git', 'rev-parse', 'HEAD').strip()
            run('git', 'checkout', '-q', '-b', 'feat/base')
            (repo / 'seed').write_text('B\n')
            run('git', 'add', 'seed')
            run('git', 'commit', '-qm', 'B')
            base_tip = run('git', 'rev-parse', 'HEAD').strip()
            run('git', 'checkout', '-q', 'main')
            tool = str(SCRIPTS / 'stand-wt.py')
            # default base is feat/base, with merge-base evidence on stdout
            output = run('python3', tool, '--root', str(repo), 'new', 'dev')
            self.assertIn('Base verified: merge-base(feat/dev', output)
            self.assertIn('feat/base', output)
            head = run('git', 'rev-parse', 'feat/dev', cwd=repo / '.stand/worktrees/dev').strip()
            self.assertEqual(head, base_tip)
            # an existing branch not based on feat/base fails closed and rolls the worktree back
            run('git', 'branch', 'feat/stale', main_tip)
            output = run('python3', tool, '--root', str(repo), 'new', 'stale', '--branch', 'feat/stale', expect=2)
            self.assertIn('merge-base', output)
            self.assertIn('feat/base', output)
            self.assertFalse((repo / '.stand/worktrees/stale').exists())
            run('git', 'show-ref', '--verify', '--quiet', 'refs/heads/feat/stale')
            # main is limited to a bug hotfix explicitly targeting main.
            for opts in [('--branch', 'feat/sync', '--base', 'main', '--target', 'main'),
                         ('--branch', 'bug/sync', '--base', 'main'),
                         ('--branch', 'bug/sync', '--target', 'main')]:
                output = run('python3', tool, '--root', str(repo), 'new', 'sync', *opts, expect=2)
                self.assertIn('only bug/* hotfixes', output)
            output = run('python3', tool, '--root', str(repo), 'new', 'sync', '--branch', 'bug/sync', '--base', 'main', '--target', 'main')
            self.assertIn('Base verified: merge-base(bug/sync', output)
            self.assertIn('Hotfix-Main-Baseline: ' + main_tip, output)
            self.assertEqual((repo / '.stand/tmp/sync/evidence/hotfix-main-baseline.txt').read_text().strip(), 'Hotfix-Main-Baseline: ' + main_tip)
            head = run('git', 'rev-parse', 'bug/sync', cwd=repo / '.stand/worktrees/sync').strip()
            self.assertEqual(head, main_tip)
        # a repo without feat/base fails closed instead of silently basing on HEAD
        with tempfile.TemporaryDirectory() as other:
            bare = Path(other)
            run = make_runner(self, bare)
            run('git', 'init', '-q', '-b', 'main')
            seed_repo(run)
            (bare / '.gitignore').write_text('/.stand/\n')
            (bare / 'seed').write_text('seed\n')
            run('git', 'add', '.gitignore', 'seed')
            run('git', 'commit', '-qm', 'seed')
            tool = str(SCRIPTS / 'stand-wt.py')
            output = run('python3', tool, '--root', str(bare), 'new', 'x', expect=2)
            self.assertIn('feat/base', output)


if __name__ == '__main__':
    unittest.main()
