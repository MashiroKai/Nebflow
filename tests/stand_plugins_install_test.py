#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CLI = Path(__file__).resolve().parents[1] / 'tools' / 'stand-plugins'
SCHEMA = 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json'

class InstallTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'root'
        self.pkg = self.base / 'package'
        (self.pkg / 'skills' / 'demo').mkdir(parents=True)
        (self.pkg / 'plugin.json').write_text(json.dumps({'$schema': SCHEMA, 'name': 'demo', 'version': '1', 'description': 'demo'}))
        (self.pkg / 'skills' / 'demo' / 'SKILL.md').write_text('---\nname: demo\ndescription: demo skill\n---\n# Demo\n')

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(CLI), *map(str, args), '--root', str(self.root)], capture_output=True, text=True)

    def test_nonexistent_environment_root_does_not_fall_back(self):
        home = self.base / 'home'
        (home / '.stand').mkdir(parents=True)
        env = {**os.environ, 'HOME': str(home), 'STAND_DATA_ROOT': str(self.root)}
        r = subprocess.run([sys.executable, str(CLI), 'install', str(self.pkg)], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue((self.root / 'plugins' / 'demo' / 'plugin.json').is_file())
        self.assertEqual(list((home / '.stand').iterdir()), [])

    def test_install_backup_and_rollback(self):
        r = self.run_cli('install', self.pkg)
        self.assertEqual(r.returncode, 0, r.stderr)
        dest = self.root / 'plugins' / 'demo'
        old = (dest / 'plugin.json').read_bytes()
        manifest = json.loads(old); manifest['version'] = '2'
        (self.pkg / 'plugin.json').write_text(json.dumps(manifest))
        r = self.run_cli('install', self.pkg)
        self.assertEqual(r.returncode, 0, r.stderr)
        backups = list((self.root / 'plugins-backups').glob('.backup-demo-*'))
        self.assertEqual(len(backups), 1)
        self.assertEqual((backups[0] / 'plugin.json').read_bytes(), old)
        r = self.run_cli('rollback', 'demo')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((dest / 'plugin.json').read_bytes(), old)
        self.assertEqual(json.loads(backups[0].joinpath('plugin.json').read_text())['version'], '1')

    def test_invalid_lists_missing_and_writes_nothing(self):
        (self.pkg / 'plugin.json').unlink()
        (self.pkg / 'skills' / 'demo' / 'SKILL.md').write_text('bad')
        r = self.run_cli('install', self.pkg)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('plugin.json', r.stderr)
        self.assertIn('frontmatter', r.stderr)
        self.assertFalse(self.root.exists())

    def test_dry_run_no_writes(self):
        r = self.run_cli('install', self.pkg, '--dry-run')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('dry-run', r.stdout)
        self.assertFalse(self.root.exists())

    def test_reject_path_traversal_and_symlinks(self):
        m = json.loads((self.pkg / 'plugin.json').read_text()); m['name'] = '../escape'
        (self.pkg / 'plugin.json').write_text(json.dumps(m))
        self.assertNotEqual(self.run_cli('install', self.pkg).returncode, 0)
        m['name'] = 'demo'; (self.pkg / 'plugin.json').write_text(json.dumps(m))
        (self.pkg / 'link').symlink_to('/tmp')
        self.assertNotEqual(self.run_cli('install', self.pkg).returncode, 0)
        self.assertFalse(self.root.exists())

if __name__ == '__main__':
    unittest.main(verbosity=2)
