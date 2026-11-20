#!/usr/bin/env python3
import os
import pwd
import subprocess
import sys
import tempfile
from pathlib import Path

launcher = Path(__file__).resolve().parents[2] / 'scripts/isolated-java-launch.py'
production = str((Path(pwd.getpwuid(os.getuid()).pw_dir) / '.stand').resolve())
with tempfile.TemporaryDirectory() as temp:
    root = str(Path(temp) / 'data')
    home = str(Path(temp) / 'home')
    base = [sys.executable, str(launcher), root, home, production, '/usr/bin/true', '--home', root]
    clean = {'PATH': '/usr/bin:/bin'}
    ok = subprocess.run(base, env=clean, capture_output=True, text=True)
    assert ok.returncode == 0, ok.stderr
    bad = subprocess.run(base, env={**clean, 'STAND_HOME': production}, capture_output=True, text=True)
    assert bad.returncode == 2 and 'STAND_HOME conflicts' in bad.stderr
    bad = subprocess.run([sys.executable, str(launcher), production, home, production, '/usr/bin/true', '--home', production], env=clean, capture_output=True, text=True)
    assert bad.returncode == 2 and 'production root' in bad.stderr
    bad = subprocess.run(base + ['-Duser.home=/'], env=clean, capture_output=True, text=True)
    assert bad.returncode == 2 and 'conflicting JVM' in bad.stderr
    bad = subprocess.run(base[:-1] + [production], env=clean, capture_output=True, text=True)
    assert bad.returncode == 2 and 'application --home' in bad.stderr
print('PASS isolated launcher: clean root, production log/data root, JVM override, home mismatch')
