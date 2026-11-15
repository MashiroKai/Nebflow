#!/usr/bin/env python3
"""Minimal loopback HTTP fixture with absolute request and lifetime deadlines."""
import json
import os
from pathlib import Path
import re
import selectors
import socket
import sys
import time

REQUEST_SECONDS = 1.0
POLL_SECONDS = 0.05


def main():
    assert os.environ.get('STAND_RESTART_FIXTURE') == '1'
    root = Path(os.environ['FIXTURE_ROOT'])
    assert root.is_absolute() and root.resolve() == root and root.is_dir()
    assert root.parent == Path('/tmp').resolve() and root.name.startswith('stand-restart-lifecycle-')
    assert Path(__file__).resolve() == root / 'bin/listener.py'
    assert Path.cwd() == root / 'home/Claude code/Stand'
    role = os.environ.get('FIXTURE_ROLE', 'new')
    assert role in ('old', 'new')
    args = ' '.join(sys.argv[1:])
    match = re.fullmatch(r'(?:run )?--port ([0-9]+)', args)
    assert match, 'only --port N or run --port N is accepted'
    port = int(match[1])
    assert port not in (6138, 8080, 8097) and 1024 <= port <= 65535
    stop = root / (role + '.stop')

    def stopped():
        return stop.exists() and stop.stat().st_size > 0

    identity = dict(pid=os.getpid(), ppid=os.getppid(), port=port,
                    cwd=os.getcwd(), role=role)
    with (root / (role + '-starts.jsonl')).open('a') as starts:
        starts.write(json.dumps(identity) + '\n')
    # The parent authenticates birth, ancestry, cwd and command before admitting
    # this process. A pidfile alone is never cleanup authority.
    (root / (role + '.pending')).write_text(json.dumps(identity))
    deadline = time.monotonic() + 100
    while not (root / (role + '.go')).exists():
        if stopped() or time.monotonic() >= deadline:
            return
        time.sleep(POLL_SECONDS)
    with socket.socket() as server, selectors.DefaultSelector() as selector:
        server.bind(('127.0.0.1', port))
        server.listen(8)
        server.setblocking(False)
        selector.register(server, selectors.EVENT_READ)
        (root / (role + '.json')).write_text(json.dumps(identity))
        with (root / (role + '-requests.jsonl')).open('a', buffering=1) as events:
            while not stopped() and time.monotonic() < deadline:
                if not selector.select(POLL_SECONDS):
                    continue
                connection, _ = server.accept()
                accepted = time.monotonic()
                request_deadline = min(deadline, accepted + REQUEST_SECONDS)
                reason = 'incomplete'
                with connection, selectors.DefaultSelector() as request_selector:
                    connection.setblocking(False)
                    request_selector.register(connection, selectors.EVENT_READ)
                    request = bytearray()
                    while not stopped() and time.monotonic() < request_deadline:
                        if not request_selector.select(min(POLL_SECONDS, max(0, request_deadline - time.monotonic()))):
                            continue
                        chunk = connection.recv(1024)
                        if not chunk:
                            reason = 'eof'
                            break
                        request.extend(chunk)
                        if len(request) > 8192:
                            reason = 'oversize'
                            break
                        if b'\r\n\r\n' in request:
                            valid = bytes(request).split(b'\r\n', 1)[0] in (
                                b'GET /api/sessions HTTP/1.0', b'GET /api/sessions HTTP/1.1')
                            response = (b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]'
                                        if valid else b'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                            # A tiny response must not introduce another blocking IO.
                            try:
                                connection.send(response)
                            except (BlockingIOError, BrokenPipeError, ConnectionResetError):
                                pass
                            reason = 'complete'
                            break
                    if stopped():
                        reason = 'stop'
                    elif time.monotonic() >= request_deadline:
                        reason = 'deadline'
                events.write(json.dumps(dict(reason=reason, accepted=accepted,
                                             closed=time.monotonic(), bytes=len(request))) + '\n')


if __name__ == '__main__':
    main()
