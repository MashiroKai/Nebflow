#!/usr/bin/env python3
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]


class AttributionTest(unittest.TestCase):
    def test_real_commits_and_preview(self):
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', HOME=d,
                       GIT_AUTHOR_NAME='Test', GIT_AUTHOR_EMAIL='test@example.invalid',
                       GIT_COMMITTER_NAME='Test', GIT_COMMITTER_EMAIL='test@example.invalid')
            def run(*args, check=True):
                return subprocess.run(args, cwd=d, env=env, text=True, capture_output=True, check=check)
            run('git', 'init', '-q')
            run('git', 'commit', '-q', '--allow-empty', '-m', 'clean')
            clean = run('git', 'rev-parse', 'HEAD').stdout.strip()
            run('git', 'commit', '-q', '--allow-empty', '-m', 'bad', '-m', 'Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>')
            bad = run('git', 'rev-parse', 'HEAD').stdout.strip()
            check = str(SCRIPTS / 'check-commit-attribution.py')
            preview = str(SCRIPTS / 'preview-attribution-cleanup.py')
            self.assertEqual(run('python3', check, clean).returncode, 0)
            self.assertEqual(run('python3', check, bad, check=False).returncode, 1)
            self.assertEqual(run('python3', check, clean + '..HEAD', check=False).returncode, 1)
            result = run('python3', preview, clean + '..HEAD').stdout
            self.assertIn('1 matching commit(s)', result)
            self.assertEqual(run('git', 'rev-parse', 'HEAD').stdout.strip(), bad)
            self.assertEqual(run('python3', check, 'nonexistent', check=False).returncode, 2)
            run('git', 'commit', '-q', '--allow-empty', '-m', 'bad', '-m', 'Generated-by: Codex')
            self.assertEqual(run('python3', check, 'HEAD', check=False).returncode, 1)


if __name__ == '__main__':
    unittest.main()
