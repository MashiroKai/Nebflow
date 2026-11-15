#!/usr/bin/env python3
"""No-runtime unit probes for fixture budget and direct-child signal authority."""
import importlib.util
from pathlib import Path
import signal
import unittest
from unittest import mock

PATH = Path(__file__).resolve().parent / 'fixtures/restart/lifecycle.py'
SPEC = importlib.util.spec_from_file_location('restart_lifecycle', PATH)
lifecycle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lifecycle)


class CleanupTests(unittest.TestCase):
    def test_expired_budget_fails_closed(self):
        with mock.patch.object(lifecycle.time, 'monotonic', return_value=10):
            with self.assertRaises(TimeoutError):
                lifecycle.remaining(10, 2)
            self.assertEqual(lifecycle.remaining(11, 2), 1)

    def test_unknown_protected_and_reused_pid_never_signalled(self):
        child = mock.Mock(pid=12345)
        child.poll.return_value = None
        expected = (12345, lifecycle.os.getpid(), 8, 9, 1)
        with mock.patch.object(lifecycle.os, 'kill') as kill:
            for birth, protected, current in (
                    (None, set(), expected), (expected, {12345}, expected),
                    (expected, set(), (12345, lifecycle.os.getpid(), 8, 10, 1)),
                    (expected, set(), (12345, 1, 8, 9, 1)),
                    (expected, set(), None)):
                with self.subTest(birth=birth, protected=protected, current=current):
                    with mock.patch.object(lifecycle, 'identity', return_value=current):
                        with self.assertRaises(lifecycle.Unsafe):
                            lifecycle.direct_signal(child, birth, protected, signal.SIGTERM)
            kill.assert_not_called()

    def test_inspector_timeout_and_cleanup_failure_preserves_unknown(self):
        child = mock.Mock(pid=12345, returncode=None)
        child.communicate.side_effect = [subprocess_timeout(), OSError('wait failed')]
        child.poll.return_value = None
        birth = (12345, lifecycle.os.getpid(), 8, 9, 1)
        with mock.patch.object(lifecycle.subprocess, 'Popen', return_value=child), \
             mock.patch.object(lifecycle, 'identity', return_value=birth), \
             mock.patch.object(lifecycle, 'direct_signal', return_value=True), \
             mock.patch.object(lifecycle, 'remaining', return_value=1):
            with self.assertRaises(lifecycle.CleanupUnknown) as failure:
                lifecycle.capture(['/usr/sbin/lsof'], Path('/private'), 10)
        self.assertIn('wait failed', str(failure.exception))
        self.assertIsInstance(failure.exception.__cause__, lifecycle.subprocess.TimeoutExpired)


def subprocess_timeout():
    return lifecycle.subprocess.TimeoutExpired('fixture inspector', 1)


if __name__ == '__main__':
    unittest.main()
