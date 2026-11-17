#!/usr/bin/env python3
"""Source-only regression: no restart entrypoint, listener, process scan or signal.

Only extracted function definitions enter a fixed shell harness. Every external
command is unavailable (empty PATH); process operations are shell recording mocks.
The lifecycle test is separate and requires explicit runtime authorization.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'ops/stand-restart.sh'


def function(source, name):
    # These functions end at a standalone top-level brace, including heredocs.
    single = re.search(r'^' + name + r'\(\) \{[^\n]*\}$', source, re.M)
    if single:
        return single.group()
    match = re.search(r'^' + name + r'\(\) \{.*?^\}', source, re.M | re.S)
    if not match:
        raise AssertionError('missing function: ' + name)
    return match.group()


HARNESS = r'''
set -u
set -o pipefail
enable -n kill
record() { printf '%s\n' "$*" >> "$TRACE"; }
kill() { record "signal:$*"; }
stage() { :; }
echo() { :; }
tr() { local line; while IFS= read -r line; do printf '%s ' "$line"; done; }
sleep() { :; }
pid_on_port() { printf '%s' "$LISTENER"; }
snapshot_interrupted() { :; }
ps_alive() { return 0; }
wait_tree_dead() { [[ "$2" == 10 || "$ESCALATE" == 0 ]]; }
wait_pids_dead() {
  if [[ "$2" == 10 || "$ESCALATE" == 0 ]]; then return 0; fi
  printf 'fixture-sbt\n'; return 1
}
wait_tw_drained() { :; }
start_new_leg() { exit 91; }
python3() { record scan; printf 'fixture-sbt\n'; }
PORT=fixture-port
NEBFLOW_DIR=fixture-cwd
SELF_PID=fixture-self
PROC_WAIT_SECS=45
TW_WAIT_SECS=45
READY_HARD_CAP=900
PID_SET=''
'''


def probe(source, root, executions, value, mode, listener='', escalate='0'):
    names = ('narrow_sweep_disabled', 'pids_of_cwd', 'kill_narrow_sbt', 'do_restart')
    definitions = '\n'.join(function(source, name) for name in names)
    trace = root / 'trace'
    trace.write_text('')
    env = {'PATH': '', 'HOME': str(root), 'TMPDIR': str(root),
           'TRACE': str(trace), 'LISTENER': listener, 'ESCALATE': escalate,
           'DRY_RUN': '1' if mode == 'dry' else '0'}
    if value is not None:
        env['NEBFLOW_RESTART_NO_NARROW_SBT'] = value
    # Call-site probes replace discovery/kill with mocks so a callee guard cannot
    # conceal a missing caller guard. Direct probes retain the real callee bodies.
    if mode in ('dry', 'restart'):
        action = r'''
pids_of_cwd() { record scan; printf 'fixture-sbt\n'; }
kill_narrow_sbt() { record "sweep:$1"; }
do_restart
'''
    elif mode == 'scan':
        action = 'pids_of_cwd\n'
    else:
        action = 'pids_of_cwd() { record scan; printf "fixture-sbt\\n"; }\nkill_narrow_sbt ' + mode + '\n'
    cp = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-c',
                         HARNESS + '\n' + definitions + '\n' + action],
                        env=env, capture_output=True, text=True, timeout=5)
    executions[0] += 1
    expected_rc = 91 if mode == 'restart' else 0
    assert cp.returncode == expected_rc, (mode, value, cp.returncode, cp.stderr)
    return trace.read_text().splitlines()


def matrix(source, root, executions):
    for value in (None, '0', '1', 'other'):
        disabled = value == '1'
        for mode in ('scan', 'TERM', 'KILL'):
            actual = probe(source, root, executions, value, mode)
            expected = [] if disabled else ['scan']
            if not disabled and mode != 'scan':
                expected += ['signal:fixture-sbt' if mode == 'TERM' else 'signal:-KILL fixture-sbt']
            assert actual == expected, (value, mode, actual, expected)
        for listener in ('', 'fixture-listener'):
            actual = probe(source, root, executions, value, 'dry', listener)
            assert actual == ([] if disabled else ['scan']), (value, 'dry', listener, actual)
            for escalate in ('0', '1'):
                actual = probe(source, root, executions, value, 'restart', listener, escalate)
                # Tree termination is unrelated to the cwd sweep: fixed recording
                # mocks must still observe it even when the narrow sweep is off.
                # kill_tree_* are provided below, never the production functions.
                if listener:
                    expected = ['tree:TERM']
                    if not disabled:
                        expected += ['sweep:TERM']
                    if escalate == '1':
                        expected += ['tree:KILL']
                        if not disabled:
                            expected += ['sweep:KILL']
                else:
                    expected = [] if disabled else ['scan', 'signal:fixture-sbt']
                    if not disabled and escalate == '1':
                        expected += ['signal:-KILL fixture-sbt']
                assert actual == expected, (value, listener, escalate, actual, expected)


HARNESS += '\nkill_tree_term() { record tree:TERM; }\nkill_tree_kill() { record tree:KILL; }\n'


def path_checks(source, root):
    names = ('CANARY_HOME', 'NF_HOME', 'NEBFLOW_DIR', 'RESTART_TMPDIR',
             'SBT_OUT', 'CANARY_OUT', 'WATCH_LOG', 'REPORT', 'PREFLIGHT_OUT')
    assignments = []
    for name in names:
        lines = re.findall(r'^' + name + r'=.*$', source, re.M)
        assert len(lines) == 1, name
        assignments.append(lines[0])
    commands = '\n'.join(assignments) + '\nprintf "%s\\n" ' + ' '.join('"$' + name + '"' for name in names)
    home = str(root / 'home')
    for isolated in (False, True):
        env = {'PATH': '', 'HOME': home}
        if isolated:
            env.update(STAND_HOME=home + '/app', NEBFLOW_RESTART_TMPDIR=str(root / 'tmp'))
        cp = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-uc', commands],
                            env=env, capture_output=True, text=True, timeout=5)
        assert cp.returncode == 0, cp.stderr
        output_root = str(root / 'tmp') if isolated else '/tmp'
        expected = [home + '/.nebflow-canary', home + ('/app' if isolated else '/.stand'),
                    home + '/Claude code/Stand', output_root]
        expected += [output_root + '/' + name for name in
                     ('nebflow-sbt-restart.log', 'nebflow-canary.log', 'stand-restart-watch.log',
                      'stand-restart-last-run.txt', 'nebflow-preflight-compile.log')]
        assert cp.stdout.splitlines() == expected, cp.stdout


def source_checks(source):
    # Every discovery/sweep reference must remain within these inspected bodies.
    bodies = {name: function(source, name) for name in
              ('pids_of_cwd', 'kill_narrow_sbt', 'do_restart')}
    remainder = source
    for body in bodies.values():
        remainder = remainder.replace(body, '', 1)
    executable = '\n'.join(line for line in remainder.splitlines()
                           if not line.lstrip().startswith('#'))
    assert not re.search(r'\b(pids_of_cwd|kill_narrow_sbt)\b', executable), 'uncovered sweep caller'
    # Retain the original T4 scope; missing git metadata is a failure, not a
    # silently narrowed check. Reading tracked shell sources cannot launch them.
    cp = subprocess.run(['git', '-C', str(ROOT), 'ls-files', '-z'],
                        check=True, capture_output=True, timeout=10)
    files = set(cp.stdout.decode().split('\0')) - {''}
    files.add('ops/stand-restart.sh')
    hits = []
    count = 0
    for rel in sorted(files):
        path = ROOT / rel
        if rel.startswith('reports-evidence/') or not path.is_file():
            continue
        data = path.read_bytes()
        if not (rel.endswith('.sh') or re.match(rb'#!(?:/bin/|/usr/bin/env )(?:ba|z)?sh\b', data)):
            continue
        count += 1
        for number, line in enumerate(data.splitlines(), 1):
            if line.lstrip().startswith(b'#') or b'T4-EXEMPT(' in line:
                continue
            if re.search(rb'\$[A-Za-z_][A-Za-z0-9_]*[\x80-\xff]', line):
                hits.append(f'{rel}:{number}')
    assert not hits, hits
    print(f'T4 source scan: files={count} hits=0')


def syntax_checks(root):
    failures = []
    env = {'PATH': '', 'HOME': str(root), 'TMPDIR': str(root)}
    for rel in ('ops/stand-restart.sh', 'ops/test/restart-abort-guard.test.sh',
                'scripts/check-no-tracked-artifacts.sh', 'scripts/term-parity-check.sh'):
        cp = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-n', str(ROOT / rel)],
                            env=env, capture_output=True, timeout=5)
        print(f'syntax bash -n: path={rel} rc={cp.returncode} '
              f'stdout={cp.stdout[:512]!r} stderr={cp.stderr[:512]!r}')
        if cp.returncode != 0:
            failures.append(rel)
    for rel in ('ops/test/restart-source-guard.test.py',
                'ops/test/fixtures/restart/listener.py',
                'ops/test/fixtures/restart/stand-setsid-exec.py',
                'ops/test/fixtures/restart/lifecycle.py',
                'ops/test/fixtures/restart/stand-daemonize.py'):
        try:
            compile((ROOT / rel).read_bytes(), str(ROOT / rel), 'exec')
        except SyntaxError as error:
            print(f'syntax Python compile: path={rel} FAIL error={str(error)[:512]!r}')
            failures.append(rel)
        else:
            print(f'syntax Python compile: path={rel} PASS (not executed)')
    assert not failures, ('syntax failures', failures)


def main():
    source = SOURCE.read_text()
    source_checks(source)
    with tempfile.TemporaryDirectory(prefix='stand-restart-source-') as tmp:
        root = Path(tmp)
        syntax_checks(root)
        path_checks(source, root)
        executions = [0]
        matrix(source, root, executions)
        print(f'matrix probes executed: {executions[0]}')
        assert executions[0] == 36, executions
        # Independently remove each guard. Even with a guarded callee, the
        # call-site recording mocks must reject the mutated source.
        mutations = (
            ('scanner guard', '  narrow_sweep_disabled && return 0\n', 0),
            ('killer guard', '  narrow_sweep_disabled && return 0\n', 1),
            ('dry-run guard', '    if ! narrow_sweep_disabled; then\n', 0),
            ('no-listener guard', '    if ! narrow_sweep_disabled; then\n', 1),
            ('listener TERM guard', 'narrow_sweep_disabled || kill_narrow_sbt TERM', 0),
            ('listener KILL guard', 'narrow_sweep_disabled || kill_narrow_sbt KILL', 0),
        )
        for label, old, index in mutations:
            positions = [m.start() for m in re.finditer(re.escape(old), source)]
            assert len(positions) > index, label
            pos = positions[index]
            replacement = '  :\n' if 'return 0' in old else (
                '    if true; then\n' if 'if !' in old else old.split(' || ')[1])
            mutated = source[:pos] + replacement + source[pos + len(old):]
            executions = [0]
            try:
                matrix(mutated, root, executions)
            except AssertionError:
                print(f'mutation rejected: {label}; probes executed={executions[0]}')
            else:
                raise AssertionError('mutation survived: ' + label)
        # T3 uses only fixed strings; this is expansion behavior, not a restart.
        for locale in ('C', 'en_US.UTF-8'):
            for expression, braced in [('x $PORT（log y', False), ('x ${PORT}（log y', True)]:
                cp = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-uc',
                                     'PORT=6138; printf "%s\\n" "' + expression + '"'],
                                    env={'PATH': '', 'HOME': tmp, 'TMPDIR': tmp,
                                         'LC_ALL': locale},
                                    capture_output=True, timeout=5)
                print(f'T3: locale={locale} expression={expression!r} rc={cp.returncode} '
                      f'stdout={cp.stdout[:512]!r} stderr={cp.stderr[:512]!r}')
                want_rc = 0 if locale == 'C' or braced else 127
                assert cp.returncode == want_rc, (locale, expression, cp.returncode, cp.stderr)
                if want_rc == 0:
                    assert cp.stdout == 'x 6138（log y\n'.encode('utf-8'), cp.stdout
                else:
                    assert b'unbound variable' in cp.stderr
    print('PASS: syntax, source guards, fixed-shell matrix, six negative mutations, T3/T4; lifecycle NOT RUN')


if __name__ == '__main__':
    main()
