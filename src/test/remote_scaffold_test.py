import importlib.util
import os
import multiprocessing
import io
import json
import time
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('remote_scaffold', Path(__file__).parents[1] / 'main/resources/remote_scaffold.py')
scaffold = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scaffold)


def lock_holder(root, ready, release, results):
    def hold():
        ready.set()
        if not release.wait(10):
            raise RuntimeError('timed out waiting for test release')
    results.put(scaffold.run(root, 'holder', hold))


def lock_contender(root, results):
    results.put(scaffold.run(root, 'contender'))


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
        attempts = []
        def concurrent_edit():
            before = target.read_bytes()
            with target.open('ab') as stream:
                stream.write(b'external\n')
            after = target.read_bytes()
            attempts.append((before, after))
            print('CONFLICT STEP %d: read=%r; external writer appended; before-check=%r' % (len(attempts), before, after))
        result = scaffold.run(str(self.root), 'test-device', concurrent_edit)
        print('CONFLICT:', result)
        print('CONFLICT BYTES:', repr(target.read_bytes()))
        print('CONFLICT OBSERVED: attempts=%d; ok=%s; rules_present=%s' % (len(attempts), result['ok'], b'/.nebflow/' in target.read_bytes()))
        self.assertFalse(result['ok'])
        self.assertIn('concurrent modification', result['reason'])
        self.assertEqual(target.read_bytes(), b'old\nexternal\nexternal\nexternal\n')

    def test_readback_mismatch_fails_without_overwrite(self):
        target = self.root / '.gitignore'
        original = b'user\r\n'
        target.write_bytes(original)
        real_open = open
        reads = 0
        def inject_readback(path, mode='r', *args, **kwargs):
            nonlocal reads
            if os.fspath(path) == str(target) and mode == 'rb':
                reads += 1
                if reads == 3:
                    with real_open(path, mode, *args, **kwargs) as stream:
                        actual = stream.read()
                    return io.BytesIO(actual + b'injected-readback-mismatch')
            return real_open(path, mode, *args, **kwargs)
        with patch('builtins.open', side_effect=inject_readback):
            result = scaffold.run(str(self.root), 'test-device')
        actual = target.read_bytes()
        print('READBACK MISMATCH:', result['reason'])
        print('READBACK ACTUAL BYTES:', repr(actual))
        self.assertFalse(result['ok'])
        self.assertIn('readback sha256 mismatch', result['reason'])
        self.assertTrue(actual.startswith(original))
        self.assertEqual(actual, original + b'/.nebflow/\n.stand/worktrees/\n.stand/tmp/\n')

    def test_cross_process_lock(self):
        target = self.root / '.gitignore'
        original = b'user\r\n'
        target.write_bytes(original)
        ctx = multiprocessing.get_context('spawn')
        ready, release, results = ctx.Event(), ctx.Event(), ctx.Queue()
        holder = ctx.Process(target=lock_holder, args=(str(self.root), ready, release, results))
        contender = ctx.Process(target=lock_contender, args=(str(self.root), results))
        holder.start()
        try:
            self.assertTrue(ready.wait(10), 'holder did not acquire lock')
            contender.start()
            contender.join(10)
            self.assertEqual(contender.exitcode, 0)
            blocked = results.get(timeout=5)
            print('LOCK CONTENDER:', blocked)
            self.assertFalse(blocked['ok'])
            self.assertIn('lock exists', blocked['reason'])
            self.assertIn('gitignore.lock', blocked['reason'])
            self.assertIn('owner=', blocked['reason'])
            self.assertIn('remaining wait', blocked['reason'])
            self.assertEqual(target.read_bytes(), original)
        finally:
            release.set()
            holder.join(10)
        self.assertEqual(holder.exitcode, 0)
        completed = results.get(timeout=5)
        print('LOCK HOLDER:', completed)
        print('LOCK FINAL BYTES:', repr(target.read_bytes()))
        self.assertTrue(completed['ok'])
        self.assertEqual(target.read_bytes(), original + b'/.nebflow/\n.stand/worktrees/\n.stand/tmp/\n')
        results.close()
        results.join_thread()

    def test_stale_lock_takeover(self):
        target = self.root / '.gitignore'
        target.write_bytes(b'user\n')
        lock = self.root / '.nebflow/locks/gitignore.lock'
        lock.parent.mkdir(parents=True)
        timestamp = time.time() - 1200
        lock.write_text(json.dumps({'pid': 123, 'host': 'old-host', 'timestamp': timestamp, 'operator': 'alice'}))
        result = scaffold.run(str(self.root), 'test-device')
        print('STALE RESULT:', result)
        print('STALE FINAL:', repr(target.read_bytes()))
        self.assertTrue(result['ok'], result)
        self.assertEqual(result['stale_lock_takeover']['host'], 'old-host')
        self.assertEqual(result['stale_lock_takeover']['timestamp'], timestamp)
        self.assertEqual(target.read_bytes(), b'user\n/.nebflow/\n.stand/worktrees/\n.stand/tmp/\n')
        self.assertFalse(lock.exists())

    def test_conflict_retry_backoff(self):
        target = self.root / '.gitignore'
        target.write_bytes(b'user\n')
        calls = []
        def change_once():
            calls.append(1)
            if len(calls) == 1:
                with target.open('ab') as stream:
                    stream.write(b'external\n')
        with patch.object(scaffold.time, 'sleep') as pause:
            result = scaffold.run(str(self.root), 'test-device', change_once)
        print('BACKOFF RESULT:', result)
        print('BACKOFF DELAYS:', [c.args[0] for c in pause.call_args_list])
        print('BACKOFF FINAL:', repr(target.read_bytes()))
        self.assertTrue(result['ok'], result)
        pause.assert_called_once_with(0.2)
        self.assertEqual(target.read_bytes(), b'user\nexternal\n/.nebflow/\n.stand/worktrees/\n.stand/tmp/\n')

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
