#!/usr/bin/env python3
"""Synthetic Git integration test; no live project worktrees are touched."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class Lifecycle(unittest.TestCase):
    def test_lifecycle_and_delivery(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            def run(*cmd, cwd=repo, expect=0):
                p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                print('$', ' '.join(map(str, cmd)), '\n', p.stdout, sep='')
                self.assertEqual(p.returncode, expect)
                return p.stdout
            run('git', 'init', '-q')
            run('git', 'config', 'user.email', 'scratch@example.invalid')
            run('git', 'config', 'user.name', 'Scratch')
            (repo / '.gitignore').write_text('/.stand/\n')
            (repo / 'seed').write_text('seed\n')
            run('git', 'add', '.gitignore', 'seed')
            run('git', 'commit', '-qm', 'seed')
            base = run('git', 'rev-parse', 'HEAD').strip()
            tool = str(SCRIPTS / 'stand-wt.py')
            checker = str(SCRIPTS / 'check-delivery.py')
            run('python3', tool, 'new', 'sample', '--branch', 'feat/sample')
            wt = repo / '.stand/worktrees/sample'
            tmp = repo / '.stand/tmp/sample'
            artifact = tmp / 'delivery/batch/changes.md'
            artifact.parent.mkdir(parents=True)
            artifact.write_text('What Why Impact\n')
            run('python3', tool, 'list')
            run('python3', tool, 'audit', 'sample')
            self.assertIn('未入库', (tmp / 'MANIFEST.md').read_text())
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


if __name__ == '__main__':
    unittest.main()
