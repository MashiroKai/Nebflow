#!/usr/bin/env python3
import pathlib
import subprocess
import tempfile
import unittest

CHECK = pathlib.Path(__file__).resolve().parents[1] / 'check-delivery.py'


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Fixture')
        self.write('README', 'base')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True)

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value if isinstance(value, bytes) else value.encode())

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')

    def check(self):
        return subprocess.run(['python3', str(CHECK), '--base', self.base], cwd=self.root,
                              capture_output=True, text=True)

    def test_frontend_missing_and_complete(self):
        self.write('src/main/resources/web/app.js', 'change')
        self.write('delivery/batch/before.png', b'\x89PNG\r\n\x1a\nfixture')
        self.write('delivery/batch/screenshots.md', 'Viewport: 800x600\nScenario: test\nFixture or command: test')
        self.commit()
        missing = self.check()
        self.assertEqual(missing.returncode, 1)
        self.assertIn('after.png', missing.stderr)
        self.write('delivery/batch/after.png', b'\x89PNG\r\n\x1a\nfixture')
        self.commit()
        self.assertEqual(self.check().returncode, 0)

    def test_backend_and_invalid_base(self):
        self.write('src/main/scala/App.scala', 'change')
        self.commit()
        self.assertEqual(self.check().returncode, 1)
        self.write('delivery/batch/changes.md', 'What: x\nWhy: y\nImpact: z')
        self.write('delivery/batch/state-machine.md', 'Before stateDiagram-v2\nAfter stateDiagram-v2')
        self.write('delivery/batch/behavior.md', 'Before after mermaid')
        self.commit()
        self.assertEqual(self.check().returncode, 0)
        self.base = 'not-a-ref'
        self.assertEqual(self.check().returncode, 2)


if __name__ == '__main__':
    unittest.main()
