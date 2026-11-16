import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('remote_scaffold', Path(__file__).parents[1] / 'main/resources/remote_scaffold.py')
scaffold = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scaffold)


class RemoteScaffoldTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def test_idempotent(self):
        first = scaffold.run(str(self.root), 'test-device')
        before = sorted(str(p.relative_to(self.root)) for p in self.root.rglob('*'))
        content = (self.root / '.gitignore').read_bytes()
        second = scaffold.run(str(self.root), 'test-device')
        self.assertTrue(first['ok'], first)
        self.assertTrue(second['ok'], second)
        self.assertEqual(before, sorted(str(p.relative_to(self.root)) for p in self.root.rglob('*')))
        self.assertEqual(content, (self.root / '.gitignore').read_bytes())

    def test_existing_rules_not_duplicated(self):
        target = self.root / '.gitignore'
        target.write_bytes(b'.nebflow/\n')
        result = scaffold.run(str(self.root), 'test-device')
        self.assertTrue(result['ok'], result)
        self.assertEqual(target.read_bytes().count(b'.nebflow/'), 1)
        self.assertNotIn(b'/.nebflow/', target.read_bytes())
        self.assertEqual(result['items']['/.nebflow/'], 'already present')

    def test_preserves_user_bytes(self):
        target = self.root / '.gitignore'
        original = b'foo\r\nbar\rbaz\n\xff\x00last'
        target.write_bytes(original)
        result = scaffold.run(str(self.root), 'test-device')
        self.assertTrue(result['ok'], result)
        self.assertTrue(target.read_bytes().startswith(original))
        print('READBACK: exact prefix preserved; result sha256=' + scaffold.digest(target.read_bytes()))

    def test_conflict_never_overwrites(self):
        target = self.root / '.gitignore'
        target.write_bytes(b'old\n')
        def concurrent_edit():
            with target.open('ab') as stream:
                stream.write(b'external\n')
        result = scaffold.run(str(self.root), 'test-device', concurrent_edit)
        print('CONFLICT:', result)
        print('CONFLICT BYTES:', repr(target.read_bytes()))
        self.assertFalse(result['ok'])
        self.assertIn('concurrent modification', result['reason'])
        self.assertEqual(target.read_bytes(), b'old\nexternal\nexternal\nexternal\n')

    def test_permission_error_report(self):
        with patch.object(scaffold.os, 'makedirs', side_effect=PermissionError('permission denied')):
            result = scaffold.run(str(self.root), 'offline-or-denied-device')
        print('DENIED:', result)
        self.assertFalse(result['ok'])
        self.assertEqual(result['device'], 'offline-or-denied-device')
        self.assertEqual(result['path'], str(self.root))
        self.assertIn('permission denied', result['reason'])
        self.assertIn('failed', result['items']['.nebflow'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
