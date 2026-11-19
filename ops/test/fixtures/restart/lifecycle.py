#!/usr/bin/env python3
"""One isolated fixture case. Real restart cases remain blocked pending signal mediation."""
import ctypes
import http.client
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time

FIXTURES = Path(__file__).resolve().parent
REPO = FIXTURES.parents[3]
PROTECTED = (6138, 8080, 8097)
CASES = ('normal', 'abort', 'cancel', 'slow-request')


class Unsafe(RuntimeError):
    pass


class CleanupUnknown(Unsafe):
    pass


# Same Darwin birth layout used in coordination verify-foundation.py.
class BsdInfo(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint32) for n in
                ('flags', 'status', 'xstatus', 'pid', 'ppid', 'uid', 'gid', 'ruid',
                 'rgid', 'svuid', 'svgid', 'rfu')]
    _fields_ += [('comm', ctypes.c_char * 16), ('name', ctypes.c_char * 32)]
    _fields_ += [(n, ctypes.c_uint32) for n in
                ('nfiles', 'pgid', 'pjobc', 'e_tdev', 'e_tpgid', 'nice')]
    _fields_ += [('start_sec', ctypes.c_uint64), ('start_usec', ctypes.c_uint64)]


def remaining(deadline, cap):
    left = deadline - time.monotonic()
    if left <= 0:
        raise TimeoutError('Case budget exhausted')
    return min(left, cap)


def info(pid):
    lib = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    lib.proc_pidinfo.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64,
                                ctypes.c_void_p, ctypes.c_int]
    lib.proc_pidinfo.restype = ctypes.c_int
    data = BsdInfo()
    if lib.proc_pidinfo(pid, 3, 0, ctypes.byref(data), ctypes.sizeof(data)) == ctypes.sizeof(data):
        return data
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return None
    raise Unsafe('Cannot establish birth for pid ' + str(pid))


def identity(pid):
    item = info(pid)
    if item is None:
        return None
    return (item.pid, item.ppid, item.start_sec, item.start_usec, item.status)


def read_process(pid, root, deadline):
    # These are fixed, read-only utilities; all directly created children are
    # bounded and reaped. Timeout cleanup uses registered direct-child birth.
    command = capture(['/bin/ps', '-p', str(pid), '-o', 'command='], root, deadline)
    cwd = capture(['/usr/sbin/lsof', '-a', '-p', str(pid), '-d', 'cwd', '-Fn'], root, deadline)
    paths = [line[1:] for line in cwd.splitlines() if line.startswith('n')]
    if len(paths) != 1:
        raise Unsafe('Unknown cwd for pid ' + str(pid))
    return command.strip(), paths[0]


def direct_signal(child, birth, protected, sig):
    if birth is None or child.pid in protected or child.pid in (1, os.getpid(), os.getppid()):
        raise Unsafe('Signal refused: unregistered or protected child')
    if child.poll() is not None:
        return False
    current = identity(child.pid)
    if current is None or current[:4] != birth[:4] or current[1] != os.getpid():
        raise Unsafe('Signal refused: changed birth or parent')
    os.kill(child.pid, sig)
    return True


def capture(argv, root, deadline):
    remaining(deadline, 1)
    child = subprocess.Popen(argv, cwd=root, env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'},
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    expected = identity(child.pid)
    if expected is None or expected[1] != os.getpid():
        if child.poll() is not None:
            out, err = child.communicate()
            if child.returncode in (0, 1) and not err:
                return out
        raise CleanupUnknown('Inspector registration failed')
    try:
        out, err = child.communicate(timeout=remaining(deadline, 2))
    except BaseException as original:
        try:
            direct_signal(child, expected, set(), signal.SIGKILL)
            child.communicate(timeout=remaining(deadline, 1))
        except BaseException as cleanup:
            raise CleanupUnknown('Inspector cleanup unknown: ' + str(cleanup)) from original
        raise
    if child.returncode not in (0, 1) or err or (child.returncode == 1 and out):
        raise Unsafe('Inspector failed: ' + err)
    return out


def listeners(port, root, deadline):
    output = capture(['/usr/sbin/lsof', '-nP', '-tiTCP:' + str(port), '-sTCP:LISTEN'], root, deadline)
    if any(not value.isdecimal() for value in output.split()):
        raise Unsafe('Unexpected listener output')
    return set(map(int, output.split()))


def free_port(root, deadline):
    for _ in range(20):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        if port not in PROTECTED and not listeners(port, root, deadline):
            return port
    raise Unsafe('No free private port')


def main():
    if len(sys.argv) != 3 or sys.argv[1] != '--case' or sys.argv[2] not in CASES:
        print(json.dumps(dict(status='NOT RUN', cleanupConfirmed=True,
                              error='Explicit --case normal|abort|cancel|slow-request required')))
        return 77 if len(sys.argv) == 1 else 64
    if sys.argv[2] in ('normal', 'abort', 'cancel'):
        print(json.dumps(dict(status='NOT RUN', cleanupConfirmed=True,
                              error='Controller signal mediation and cancellation remain unverified')))
        return 77
    if sys.platform != 'darwin' or Path(__file__).absolute() != FIXTURES / 'lifecycle.py':
        print(json.dumps(dict(status='BLOCKED', cleanupConfirmed=True, error='Invalid platform or fixture path')))
        return 64
    # The inspector admission/timeout path requires review before this draft is
    # runnable. Keep even the fixture-only leg closed until world.run validates it.
    print(json.dumps(dict(status='NOT RUN', cleanupConfirmed=True,
                          error='Fixture inspector admission and timeout cleanup not yet verified')))
    return 77
    started = time.monotonic()
    deadline, work_deadline = started + 115, started + 85
    root = Path(tempfile.mkdtemp(prefix='stand-restart-lifecycle-', dir='/tmp')).resolve()
    result = dict(case='slow-request', root=str(root), status='FAIL', cleanupConfirmed=False)
    child, birth, port = None, None, None
    command, cwd = None, None
    protected = {1, os.getpid(), os.getppid()}
    baseline = {}
    unknown = False
    failures = []
    previous = {}
    next_heartbeat = started + 15
    app, bindir, tmp = root / 'home/Claude code/Stand', root / 'bin', root / 'tmp'
    for path in (app, bindir, tmp):
        path.mkdir(parents=True, mode=0o700, exist_ok=True)
    shutil.copyfile(FIXTURES / 'listener.py', bindir / 'listener.py')
    env = dict(PATH='/usr/bin:/bin', HOME=str(root / 'home'), TMPDIR=str(tmp),
               FIXTURE_ROOT=str(root), STAND_RESTART_FIXTURE='1', FIXTURE_ROLE='new')
    with (root / 'evidence.jsonl').open('w', buffering=1) as evidence, \
            (root / 'new.log').open('w') as output, (root / 'new.stop').open('wb') as stop:
        def event(label, value):
            evidence.write(json.dumps(dict(event=label, value=value)) + '\n')
            evidence.flush()

        def tick():
            nonlocal next_heartbeat
            remaining(work_deadline, 1)
            if time.monotonic() >= next_heartbeat:
                print('lifecycle heartbeat: slow-request', flush=True)
                event('heartbeat', time.monotonic() - started)
                next_heartbeat += 15
            time.sleep(0.05)

        def interrupt(signum, _frame):
            raise InterruptedError('External cancellation: ' + str(signum))

        try:
            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                previous[sig] = signal.signal(sig, interrupt)
            for p in PROTECTED:
                baseline[p] = listeners(p, root, work_deadline)
                protected.update(baseline[p])
            event('protected-before', {str(k): sorted(v) for k, v in baseline.items()})
            port = free_port(root, work_deadline)
            argv = [str(Path(sys.executable).resolve()), '-B', str(bindir / 'listener.py'), '--port', str(port)]
            child = subprocess.Popen(argv, cwd=app, env=env, stdout=output, stderr=subprocess.STDOUT)
            birth = identity(child.pid)
            if birth is None or birth[1] != os.getpid() or child.pid in protected:
                unknown = True
                raise Unsafe('Listener birth/parent registration failed')
            command, cwd = read_process(child.pid, root, work_deadline)
            if str(bindir / 'listener.py') not in command or cwd != str(app) or identity(child.pid)[:4] != birth[:4]:
                unknown = True
                raise Unsafe('Listener cwd/command registration failed')
            event('owned', dict(pid=child.pid, birth=birth, command=command, cwd=cwd))
            (root / 'new.go').touch()
            while not (root / 'new.json').exists():
                assert child.poll() is None, 'Listener exited before binding'
                tick()
            assert listeners(port, root, work_deadline) == {child.pid}
            with socket.create_connection(('127.0.0.1', port), timeout=2) as drip:
                drip.settimeout(0.1)
                drip_start = time.monotonic()
                closed = False
                while time.monotonic() - drip_start < 2.5:
                    tick()
                    try:
                        drip.sendall(b'G')
                        if drip.recv(1) == b'':
                            closed = True
                            break
                    except socket.timeout:
                        pass
                    except (BrokenPipeError, ConnectionResetError):
                        closed = True
                        break
                assert closed, 'Slow drip bypassed absolute deadline'
            records = [json.loads(line) for line in (root / 'new-requests.jsonl').read_text().splitlines()]
            assert records and records[0]['reason'] == 'deadline', records
            assert 0.8 <= records[0]['closed'] - records[0]['accepted'] < 1.5, records
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
            try:
                connection.request('GET', '/api/sessions')
                response = connection.getresponse()
                assert response.status == 200 and response.read() == b'[]'
            finally:
                connection.close()
            with socket.create_connection(('127.0.0.1', port), timeout=2) as drip:
                drip.sendall(b'GET /api/sessions HTTP/1.1\r\nX: ')
                time.sleep(0.1)
                stop.write(b'stop\n')
                stop.flush()
                stop_start = time.monotonic()
                while child.poll() is None and time.monotonic() - stop_start < 0.6:
                    tick()
                assert child.poll() == 0, 'Private stop did not interrupt request'
            records = [json.loads(line) for line in (root / 'new-requests.jsonl').read_text().splitlines()]
            assert records[-1]['reason'] == 'stop', records
            event('assertions', dict(deadline=True, privateStop=True, api='[]'))
            result['status'] = 'PASS'
        except BaseException as error:
            if isinstance(error, CleanupUnknown):
                unknown = True
            result['error'] = type(error).__name__ + ': ' + str(error)
        finally:
            for sig in previous:
                signal.signal(sig, signal.SIG_IGN)
            try:
                stop.write(b'stop\n')
                stop.flush()
            except BaseException as error:
                failures.append('stop: ' + str(error))
            if child is not None:
                try:
                    child.wait(timeout=remaining(deadline, 2))
                except subprocess.TimeoutExpired:
                    # The only persistent child has a parent-owned Popen, and
                    # must still match birth, cwd, argv and protected baseline.
                    try:
                        if unknown or birth is None or command is None or cwd is None:
                            raise Unsafe('Unknown listener identity')
                        command_now, cwd_now = read_process(child.pid, root, deadline)
                        if command_now != command or cwd_now != cwd:
                            raise Unsafe('Listener identity changed')
                        for p in PROTECTED:
                            protected.update(listeners(p, root, deadline))
                        direct_signal(child, birth, protected, signal.SIGTERM)
                        child.wait(timeout=remaining(deadline, 2))
                    except BaseException as error:
                        failures.append('listener cleanup: ' + str(error))
                        unknown = True
                except BaseException as error:
                    failures.append('listener wait: ' + str(error))
                    unknown = True
                event('child-final', dict(pid=child.pid, returncode=child.poll()))
                if child.poll() is None:
                    unknown = True
            if not unknown:
                for p in ([port] if port else []) + list(PROTECTED):
                    if unknown:
                        break
                    try:
                        actual = listeners(p, root, deadline)
                        event('listener-after', dict(port=p, pids=sorted(actual)))
                        if actual != baseline.get(p, set()):
                            failures.append('port mismatch ' + str(p))
                    except BaseException as error:
                        failures.append('port check: ' + str(error))
                        unknown = True
            result['cleanupConfirmed'] = not failures and not unknown
            if not result['cleanupConfirmed']:
                result.update(status='FAIL', nextCaseForbidden=True, cleanupErrors=failures)
            result['elapsedSeconds'] = round(time.monotonic() - started, 3)
            if result['elapsedSeconds'] >= 115:
                result.update(status='FAIL', budgetExceeded=True)
            event('result', result)
            for sig, handler in previous.items():
                signal.signal(sig, handler)
    print(json.dumps(result), flush=True)
    return 0 if result['status'] == 'PASS' and result['cleanupConfirmed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
