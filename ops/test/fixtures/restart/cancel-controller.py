#!/usr/bin/env python3
"""Private negative fixture: no cancellation API, one cooperative child listener."""
import os
from pathlib import Path
import subprocess
import sys
import time

assert sys.argv[1:] == ['--fixture-controller']
assert os.environ.get('STAND_RESTART_FIXTURE') == '1'
root = Path(os.environ['FIXTURE_ROOT']).resolve()
assert Path(__file__).resolve() == root / 'bin/cancel-controller.py'
assert Path.cwd() == root / 'home/Claude code/Stand'
# Wait for birth/ancestry registration before creating a child.
while not (root / 'controller.go').exists():
    time.sleep(0.05)
child = subprocess.Popen([sys.executable, '-B', str(root / 'bin/listener.py'),
                          '--port', os.environ['NEBFLOW_RESTART_PORT']])
# The lifecycle supervisor must request private stop before exact-PID TERM.
# No fork/detach, no real Stand, JVM, sbt, or signal operation lives here.
end = time.monotonic() + 100
while time.monotonic() < end:
    child.poll()
    time.sleep(0.05)
