#!/usr/bin/env python3
"""Repository-owned lifecycle fixture, not a production helper implementation."""
import os
from pathlib import Path
import sys

assert os.environ.get('STAND_RESTART_FIXTURE') == '1'
assert Path(sys.argv[1]).resolve() == Path(os.environ['FIXTURE_ROOT']) / 'bin/fake-sbt'
os.setsid()
os.execv(sys.argv[1], sys.argv[1:])
