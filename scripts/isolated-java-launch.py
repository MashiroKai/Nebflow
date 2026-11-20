#!/usr/bin/env python3
"""Launch a Java test with an explicit isolated data, log and JVM home.

Usage: isolated-java-launch.py DATA_ROOT JVM_HOME PRODUCTION_ROOT JAVA [JAVA_ARGS...]
The data root is also the log root. Conflicting inherited log roots are rejected
before Java (and therefore Logback) starts. Pass --home DATA_ROOT to the app.
"""
import os
import pwd
import sys
from pathlib import Path


def fail(message):
    print(f"isolated launch refused: {message}", file=sys.stderr)
    raise SystemExit(2)


if len(sys.argv) < 5:
    fail("expected DATA_ROOT JVM_HOME PRODUCTION_ROOT JAVA [JAVA_ARGS...]")
data, home, production = (Path(value).expanduser().resolve() for value in sys.argv[1:4])
actual_production = (Path(pwd.getpwuid(os.getuid()).pw_dir) / '.stand').resolve()
if production != actual_production:
    fail("production root does not match the OS account default")
if data == production or production in data.parents:
    fail("data/log root resolves to or is inside production root")
if home == production or production in home.parents or (home / '.stand').resolve() == production:
    fail("JVM home resolves to production root")
for key in ('STAND_HOME', 'NEBFLOW_HOME'):
    value = os.environ.get(key)
    if value and Path(value).expanduser().resolve() != data:
        fail(f"{key} conflicts with isolated data/log root")
java = sys.argv[4]
args = sys.argv[5:]
if any(arg.startswith(('-Duser.home=', '-DSTAND_HOME=', '-DNEBFLOW_HOME=', '-Dlogback.configurationFile=', '-javaagent:', '-agentlib:')) for arg in args):
    fail("conflicting JVM root or logging property supplied")
if args.count('--home') != 1 or args.index('--home') + 1 >= len(args) or Path(args[args.index('--home') + 1]).expanduser().resolve() != data:
    fail("application --home must name the isolated data root exactly once")
env = {'HOME': str(home), 'STAND_HOME': str(data), 'NEBFLOW_HOME': str(data),
       'PATH': os.environ.get('PATH', '/usr/bin:/bin')}
if 'NEBFLOW_GATEWAY_PORT' in os.environ:
    env['NEBFLOW_GATEWAY_PORT'] = os.environ['NEBFLOW_GATEWAY_PORT']
os.execvpe(java, [java, f'-Duser.home={home}', f'-DSTAND_HOME={data}',
                  f'-DNEBFLOW_HOME={data}', *args], env)
