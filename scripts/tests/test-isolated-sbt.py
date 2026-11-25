#!/usr/bin/env python3
"""Self-test battery for scripts/isolated-sbt.py (guard #2934, P3).

Every sbt executable below is a FAKE script inside a temporary directory; a
real sbt is never invoked. Production root/port are never touched: sandbox
enforcement is proven with an equivalent profile that denies a temp path.
Cases:
  A  normal exit (guard pass, task 0, evidence complete, one sbt session,
     argv carries -J-Xmx<heap> — default 3g, --sbt-heap override, bad value refused,
     heap removed from argv fails the heap predicate)
  B  timeout via --max-seconds, reaped, zero residual
  C  task nonzero exit recorded, guard still pass
  D  deny-line judge: tree PID fails, non-tree PID is info (negative control);
     log self-match lines are ignored entirely
  E  log-show query failure is fail-closed; clean query passes (negative control)
  F  paths with spaces and via symlinks
  G  real /usr/bin/log show smoke on this machine
  L  token lock refusal while held
  W  command whitelist refusal (every refused form)
  N  expanded whitelist forms run end-to-end: Test/compile, multi-token
     testOnly collapsing to one command, scalafmtCheckAll/scalafmtAll/
     assembly/compile serially in one session
Each case prints its command and exit code. Exit 0 only if all pass.
"""
import fcntl
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
WRAPPER = HERE.parent / 'isolated-sbt.py'
spec = importlib.util.spec_from_file_location('isolated_sbt', WRAPPER)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

PORT = mod.DENIED_PORT
FAKE_HEADER = '''#!/usr/bin/env python3
import json, os, sys
dump = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'env-%d.json' % os.getpid())
with open(dump, 'w') as fh:
    json.dump({'argv': sys.argv, 'env': dict(os.environ)}, fh)
'''


def make_fake(directory, name, body):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_text(FAKE_HEADER + body + '\n')
    path.chmod(0o755)
    return path


def run_wrapper(args, env_extra=None, cwd=None):
    env = dict(os.environ)
    env.update(env_extra or {})
    cmd = [sys.executable, str(WRAPPER), *args]
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env,
                          cwd=str(cwd) if cwd else None, timeout=300)
    return cmd, proc


def latest_env_dump(fake_dir):
    dumps = sorted(fake_dir.glob('env-*.json'), key=lambda p: p.stat().st_mtime)
    assert dumps, f'fake sbt wrote no env dump in {fake_dir}'
    return json.loads(dumps[-1].read_text())


def verdict_of(env_dump):
    run_id = pathlib.Path(env_dump['env']['HOME']).name
    run_dir = mod.RUNS_DIR / run_id
    return run_dir, json.loads((run_dir / 'verdict.json').read_text())


def heap_present(argv):
    """The heap assertion predicate: does this argv tell sbt its max heap?"""
    return any(token.startswith('-J-Xmx') for token in argv)


class Battery:
    def __init__(self):
        self.rows = []
        self.failures = []

    def record(self, case, cmd, proc, ok, note=''):
        row = f'{case} | exit={proc.returncode} | {"PASS" if ok else "FAIL"} | {note}'
        self.rows.append(row)
        print(row, flush=True)
        print(f'  cmd: {cmd}', flush=True)
        if not ok:
            self.failures.append(row)
            tail = (proc.stdout + proc.stderr)[-2000:]
            print(f'  output tail: {tail!r}', flush=True)

    def check(self, case, ok, note=''):
        row = f'{case} | {"PASS" if ok else "FAIL"} | {note}'
        self.rows.append(row)
        print(row, flush=True)
        if not ok:
            self.failures.append(row)
        return ok


def case_env(battery, tmp):
    from unittest.mock import patch

    keys = ('CUBU_BASELINE', 'PLAYWRIGHT_BROWSERS_PATH')
    for key in keys:
        for present in (False, True):
            parent = {'UNLISTED_ENV_PROBE': 'secret'}
            if present:
                parent[key] = 'value with spaces'
            with patch.dict(os.environ, parent, clear=True):
                env = mod.build_env(tmp / 'isolated-home')
            child = subprocess.run([sys.executable, '-c',
                                    'import json, os; print(json.dumps(dict(os.environ)))'],
                                   env=env, capture_output=True, text=True, check=True)
            actual = json.loads(child.stdout)
            ok = (actual.get(key) == 'value with spaces' if present else key not in actual)
            ok = ok and 'UNLISTED_ENV_PROBE' not in actual
            ok = ok and actual['HOME'] == str(tmp / 'isolated-home')
            battery.check(f'env-{key}-{present}', ok, 'child env; unlisted scrubbed; HOME isolated')


def case_a(battery, tmp):
    fake = make_fake(tmp / 'a', 'fake-sbt', "print('fake sbt task done')\nsys.exit(0)")
    runs_before = set(mod.RUNS_DIR.glob('*')) if mod.RUNS_DIR.exists() else set()
    cmd, proc = run_wrapper(['--first-run', str(fake)],
                            env_extra={'STAND_EVIL': '1', 'NEBFLOW_EVIL': '1'})
    ok = proc.returncode == 0
    dump = latest_env_dump(tmp / 'a') if ok else None
    run_dir = verdict = None
    if ok:
        run_dir, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] is True
        ok = ok and len(verdict['task']) == 1
        ok = ok and verdict['task'][0]['name'] == ' '.join(mod.FIRST_RUN_TASKS)
        ok = ok and verdict['task'][0]['tasks'] == list(mod.FIRST_RUN_TASKS)
        ok = ok and all(t['exit_code'] == 0 and not t['timed_out'] for t in verdict['task'])
        ok = ok and all((run_dir / name).is_file() for name in
                        ('command.json', 'timeline.jsonl', 'sbt-output.log',
                         'sandbox-deny-sweep.txt', 'process-tree-final.txt', 'verdict.json'))
        env = dump['env']
        leak = [k for k in env if k.startswith(('STAND_', 'NEBFLOW_')) and
                k not in ('STAND_HOME', 'NEBFLOW_HOME', 'STAND_SBT_ISOLATED')]
        home = pathlib.Path(env['HOME'])
        ok = ok and not leak
        ok = ok and home.parent == mod.HOMES_DIR and home.name.startswith('sbt-')
        ok = ok and env['STAND_HOME'] == str(home / '.stand')
        ok = ok and env['NEBFLOW_HOME'] == str(home / '.stand')
        ok = ok and env['STAND_SBT_ISOLATED'] == '1' and env['SBT_GATE'] == 'off'
        ok = ok and env['JAVA_HOME'] == mod.JAVA_HOME
        ok = ok and env['TMPDIR'] == str(home / 'tmp')
        ok = ok and env['COURSIER_CACHE'] == str(mod.ACCOUNT / 'Library' / 'Caches' / 'Coursier')
        ok = ok and env['PATH'] == '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin'
        # One sbt session: exactly one fake invocation, both tasks at the argv
        # tail in the given order, -batch present, and the heap flag carried.
        all_dumps = [json.loads(p.read_text()) for p in sorted((tmp / 'a').glob('env-*.json'))]
        ok = ok and len(all_dumps) == 1
        argv = all_dumps[0]['argv'] if all_dumps else []
        ok = ok and argv[-len(mod.FIRST_RUN_TASKS):] == list(mod.FIRST_RUN_TASKS)
        ok = ok and '-batch' in argv
        ok = ok and heap_present(argv)
        command = json.loads((run_dir / 'command.json').read_text())
        ok = ok and command['sbt_heap'] == '3g'
        timeline_text = (run_dir / 'timeline.jsonl').read_text()
        ok = ok and str(WRAPPER) in command['command'] and '/usr/bin/sandbox-exec' in timeline_text
    battery.record('A', cmd, proc, ok,
                   'guard pass, one sbt session [0], 6 evidence files, env whitelist + scrub verified' if ok else 'see output tail')
    if ok:
        battery.check('A-static', mod.PROFILE.count('(deny') == 2 and
                      f'"{mod.PRODUCTION_ROOT}"' in mod.PROFILE and f'"*:{PORT}"' in mod.PROFILE,
                      'profile carries exactly the two spec deny rules')
        # Sandbox enforcement with an equivalent profile over a temp path:
        # proves sandbox-exec actually denies; the wrapper's own profile is
        # never pointed at the real production root in tests.
        secret = tmp / 'a' / 'secret'
        secret.write_text('x')
        guard_profile = ('(version 1)(allow default)(deny file-read* '
                         f'(subpath "{secret.resolve()}"))')
        read_cmd = [sys.executable, '-c', f'open({str(secret)!r})']
        denied = subprocess.run(['/usr/bin/sandbox-exec', '-p', guard_profile, *read_cmd],
                                capture_output=True, text=True)
        allowed = subprocess.run(read_cmd, capture_output=True, text=True)
        battery.record('A-sandbox-neg', denied.args, denied,
                       denied.returncode != 0 and allowed.returncode == 0,
                       f'deny rule enforced (exit {denied.returncode}); same read without profile succeeds (exit {allowed.returncode})')
        # Heap parameter (A): default 3g in build_command, override honored
        # end-to-end, removal of the flag fails the heap predicate (negative
        # control), and a malformed value is refused before anything starts.
        probe = mod.build_command(fake, pathlib.Path('/tmp/probe-home'), ['compile'])
        battery.check('A-heap-default', '-J-Xmx3g' in probe and heap_present(probe),
                      'build_command carries -J-Xmx3g by default')
        override = mod.build_command(fake, pathlib.Path('/tmp/probe-home'), ['compile'], '2g')
        battery.check('A-heap-override-static', '-J-Xmx2g' in override and '-J-Xmx3g' not in override,
                      'build_command honors the heap argument verbatim')
        stripped = [t for t in probe if not t.startswith('-J-Xmx')]
        battery.check('A-heap-neg', heap_present(probe) and not heap_present(stripped),
                      'argv stripped of -J-Xmx fails the heap predicate (removal must go red)')
        fake_ovr = make_fake(tmp / 'a-heap', 'fake-sbt', 'sys.exit(0)')
        cmd, proc = run_wrapper(['--first-run', '--sbt-heap', '2g', str(fake_ovr)])
        ok_ovr = proc.returncode == 0
        if ok_ovr:
            dump_ovr = latest_env_dump(tmp / 'a-heap')
            run_dir_ovr, verdict_ovr = verdict_of(dump_ovr)
            ok_ovr = verdict_ovr['guard']['pass'] is True
            ok_ovr = ok_ovr and '-J-Xmx2g' in dump_ovr['argv']
            ok_ovr = ok_ovr and json.loads((run_dir_ovr / 'command.json').read_text())['sbt_heap'] == '2g'
        battery.record('A-heap-override', cmd, proc, ok_ovr,
                       'end-to-end --sbt-heap 2g: fake argv has -J-Xmx2g and command.json records 2g' if ok_ovr else 'see output tail')
        fake_bad = make_fake(tmp / 'a-heap-bad', 'fake-sbt', 'sys.exit(0)')
        cmd, proc = run_wrapper(['--first-run', '--sbt-heap', '3x', str(fake_bad)])
        battery.record('A-heap-bad', cmd, proc, proc.returncode == mod.EXIT_USAGE,
                       'malformed --sbt-heap value is refused, nothing started')


def case_b(battery, tmp):
    fake = make_fake(tmp / 'b', 'fake-sbt', 'import time\ntime.sleep(600)')
    cmd, proc = run_wrapper(['--first-run', '--max-seconds', '8', str(fake)])
    ok = proc.returncode == mod.EXIT_GUARD
    run_dir = verdict = None
    if ok:
        dump = latest_env_dump(tmp / 'b')
        run_dir, verdict = verdict_of(dump)
        timeline = (run_dir / 'timeline.jsonl').read_text()
        pid = None
        for line in timeline.splitlines():
            rec = json.loads(line)
            if rec.get('event') == 'task_spawn':
                pid = rec['pid']
        ok = verdict['guard']['pass'] is False
        ok = ok and any('deadline' in f.get('line', '') for f in verdict['guard']['failures'])
        ok = ok and verdict['task'] and verdict['task'][0]['timed_out'] is True
        ok = ok and 'sigterm' in timeline and pid is not None
        if ok:
            ps = subprocess.run(['ps', '-p', str(pid)], capture_output=True, text=True)
            ok = str(pid) not in ps.stdout
    battery.record('B', cmd, proc, ok,
                   'deadline guard fail, task timed out, SIGTERM reaped, zero residual' if ok else 'see output tail')
    quick = make_fake(tmp / 'b-neg', 'fake-sbt', 'import time\ntime.sleep(2)\nsys.exit(0)')
    cmd, proc = run_wrapper(['--first-run', '--max-seconds', '120', str(quick)])
    dump = latest_env_dump(tmp / 'b-neg')
    _, verdict = verdict_of(dump)
    battery.record('B-neg', cmd, proc,
                   proc.returncode == 0 and not verdict['task'][0]['timed_out'],
                   'short task under generous deadline completes, no reap')


def case_c(battery, tmp):
    fake = make_fake(tmp / 'c', 'fake-sbt', 'sys.exit(3)')
    cmd, proc = run_wrapper(['--first-run', str(fake)])
    ok = proc.returncode == mod.EXIT_TASK
    if ok:
        dump = latest_env_dump(tmp / 'c')
        _, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] is True and verdict['task'][0]['exit_code'] == 3
    battery.record('C', cmd, proc, ok,
                   'guard pass, task failure recorded with exit code 3' if ok else 'see output tail')


def case_d(battery, tmp):
    tree_pid, other_pid = 81567, 99999
    ours = (f'2026-11-24 15:00:00.000 Df Sandbox: Python({tree_pid}) deny(1) '
            f'network-outbound remote:*:{PORT}')
    other = (f'2026-11-24 15:00:00.000 Df Sandbox: Python({other_pid}) deny(1) '
             f'network-outbound remote:*:{PORT}')
    judged = mod.judge_deny(ours + '\n' + other, {tree_pid}, PORT)
    ok = len(judged['failures']) == 2 and judged['failures'][0]['pid'] == tree_pid
    battery.check('D', ok, f'tree pid {tree_pid} on denied port => guard failure')
    judged = mod.judge_deny(other, {tree_pid}, PORT)
    battery.check('D-neg', len(judged['failures']) == 1 and not judged['info'],
                  'unallowed non-tree deny also fails closed')
    # log tool self-match noise: the sweep/precheck --predicate argv embeds
    # the port and 'deny(', so log's own run records self-match and must be
    # ignored entirely (first-run 2026-11-24: all 4 info lines were these).
    log_self = (
        '2026-11-24 18:35:23.373 Df log[89707:3e538c5] [com.apple.log:] log run noninteractively, '
        "parent: 83002 (Python), args: '/usr/bin/log' 'show' '--start' '2026-11-24 18:32:12' "
        "--predicate eventMessage CONTAINS \"6138\" AND eventMessage CONTAINS \"deny(\"",
        f'2026-11-24 18:35:23.373 Df Sandbox: log({other_pid}) deny(1) '
        f'network-outbound remote:*:{PORT}',
    )
    judged = mod.judge_deny('\n'.join(log_self), {tree_pid}, PORT)
    battery.check('D-log-self', len(judged['failures']) == 1,
                  'query record ignored but actual log sandbox deny fails')
    judged = mod.judge_deny(log_self[0] + '\n' + other + '\n' + log_self[1], {tree_pid}, PORT)
    battery.check('D-log-mixed', len(judged['failures']) == 2,
                  'non-tree and log denies fail despite query noise')
    for operation in mod.WEBKIT_ALLOW:
        line = f'Sandbox: com.apple.WebKit.Networking.Deve(6138) deny(1) {operation}'
        battery.check('D-allowed', len(mod.judge_deny(line, {tree_pid}, PORT)['info']) == 1 and
                      len(mod.judge_deny(line, {6138}, PORT)['failures']) == 1,
                      f'allow observed unrelated WebKit only: {operation}')
    battery.check('D-unknown', len(mod.judge_deny('Sandbox: mystery(9) deny(1) file-read-data /x', set(), PORT)['failures']) == 1,
                  'unknown sandbox deny fails')


def case_e(battery, tmp):
    clean = subprocess.CompletedProcess([], 0, '', '')
    failing = subprocess.CompletedProcess([], 1, '', 'log: permission denied')
    mono = [10.0]

    def query_fail():
        return failing

    sweep = mod.run_sweep(query_fail, {123}, 0.0, 999.0, sleep_fn=lambda s: None,
                          mono_fn=lambda: mono[0])
    battery.check('E', sweep['passed'] is False and sweep['attempts'] == 1 and
                  sweep['failure_reason'] == 'logshow-query-failed',
                  'failing log-show query fails closed on first attempt')
    calls = []

    def query_ok():
        calls.append(1)
        return clean

    sweep = mod.run_sweep(query_ok, {123}, 0.0, 999.0, sleep_fn=lambda s: None,
                          mono_fn=lambda: mono[0])
    battery.check('E-neg', sweep['passed'] is True and sweep['attempts'] == 2,
                  'two consecutive clean queries past settle window pass')


def case_f(battery, tmp):
    spaced = tmp / 'f with spaces'
    fake = make_fake(spaced / 'fake dir', 'fake sbt', 'sys.exit(0)')
    workdir = tmp / 'f workdir with spaces'
    workdir.mkdir()
    cmd, proc = run_wrapper(['--first-run', str(fake)], cwd=workdir)
    ok = proc.returncode == 0
    if ok:
        dump = latest_env_dump(spaced / 'fake dir')
        run_dir, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] and (run_dir / 'verdict.json').is_file()
        ok = ok and json.loads((run_dir / 'command.json').read_text())['cwd'] == str(workdir.resolve())
    battery.record('F1', cmd, proc, ok,
                   'spaces in cwd, fake sbt path and parent dir; evidence in the right run dir' if ok else 'see output tail')
    plain = tmp / 'f-plain'
    fake2 = make_fake(plain, 'fake-sbt', 'sys.exit(0)')
    link_dir = tmp / 'f-links'
    link_dir.mkdir()
    sbt_link = link_dir / 'fake-sbt-link'
    sbt_link.symlink_to(fake2)
    wrapper_link = link_dir / 'isolated-sbt-link.py'
    wrapper_link.symlink_to(WRAPPER)
    proc = subprocess.run([sys.executable, str(wrapper_link), '--first-run', str(sbt_link)],
                          capture_output=True, text=True, timeout=300)
    ok = proc.returncode == 0
    if ok:
        dump = latest_env_dump(plain)
        _, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] and all(t['exit_code'] == 0 for t in verdict['task'])
    battery.record('F2', [sys.executable, str(wrapper_link), '--first-run', str(sbt_link)],
                   proc, ok, 'wrapper and sbt reached through symlinks')
    broken = link_dir / 'broken-sbt-link'
    broken.symlink_to(tmp / 'f-plain' / 'does-not-exist')
    proc = subprocess.run([sys.executable, str(WRAPPER), '--first-run', str(broken)],
                          capture_output=True, text=True, timeout=60)
    battery.record('F-neg', [sys.executable, str(WRAPPER), '--first-run', str(broken)],
                   proc, proc.returncode == mod.EXIT_USAGE,
                   'broken symlink sbt path is refused, nothing started')


def case_g(battery, tmp):
    smoke = ['/usr/bin/log', 'show', '--last', '10s', '--info', '--debug',
             '--style', 'compact', '--predicate', 'eventMessage CONTAINS "deny("']
    proc = subprocess.run(smoke, capture_output=True, text=True, timeout=120)
    battery.record('G', smoke, proc, proc.returncode == 0,
                   'real log show smoke command works on this machine')
    bad = ['/usr/bin/log', 'show', '--last', '10s', '--predicate']
    proc = subprocess.run(bad, capture_output=True, text=True, timeout=120)
    battery.record('G-neg', bad, proc, proc.returncode != 0,
                   'malformed log show invocation exits nonzero (error detection is real)')


def case_l(battery, tmp):
    fake = make_fake(tmp / 'l', 'fake-sbt', 'sys.exit(0)')
    mod.LOCKS_DIR.mkdir(parents=True, exist_ok=True)
    with open(mod.TOKEN_LOCK, 'a+', encoding='utf-8') as fh:
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fh.seek(0)
        fh.truncate()
        fh.write('424242\n')
        fh.flush()
        cmd, proc = run_wrapper(['--first-run', str(fake)])
        ok = proc.returncode == mod.EXIT_LOCK and '424242' in (proc.stderr + proc.stdout)
        battery.record('L', cmd, proc, ok,
                       'busy token lock refuses the run and names the holder pid')
        fcntl.flock(fh, fcntl.LOCK_UN)


def case_w(battery, tmp):
    fake = make_fake(tmp / 'w', 'fake-sbt', 'sys.exit(0)')
    for label, extra in (('W1', ['--first-run', 'test']),
                         ('W2', ['test']),
                         ('W3', ['run']),
                         ('W4', ['~compile']),
                         ('W5', ['compile;test']),
                         ('W6', ['runMain']),
                         ('W7', ['compile\ntest']),
                         ('W8', ['shell']),
                         ('W9', ['console']),
                         ('W10', ['set']),
                         ('W11', ['eval']),
                         ('W12', ['export']),
                         ('W13', ['testOnly']),
                         ('W14', ['testOnly', 'evil.Spec']),
                         ('W15', ['testOnly', 'nebflow.core.X;run']),
                         ('W16', ['foo']),
                         ('W17', ['testOnly', 'nebflow.core.LogbackConfigSpec', 'bar.Baz']),
                         *[(f'W-extra-{i}', [value]) for i, value in enumerate((
                             'compile&&test', 'compile||test', 'compile|test',
                             'compile$(id)', 'compile`id`', 'compile>out',
                             'compile -- test', '"compile"', 'Compile', './compile',
                             'testOnly nebflow.*evil', 'testOnly nebflow.core.X\ntest',
                             'Compile/scalafix', 'scalafixAll --check', 'scalafix --check -- foo',
                             'compile; shell'))]):
        head = ['--first-run', str(fake)] if label == 'W1' else [str(fake)]
        cmd, proc = run_wrapper([*head, *extra])
        battery.record(label, cmd, proc, proc.returncode == mod.EXIT_USAGE,
                       'non-whitelisted command form refused')


def case_n(battery, tmp):
    battery.check('N-scalafix-apply',
                  mod.normalize_tasks(['scalafix', 'scalafixAll']) == ['scalafix', 'scalafixAll'],
                  'exact application tasks accepted without extra arguments')
    # Every newly allowed form, end-to-end with a fake sbt: guard green, and
    # the fake's argv proves the exact command tokens the wrapper passed.
    fake = make_fake(tmp / 'n', 'fake-sbt', 'sys.exit(0)')
    cmd, proc = run_wrapper([str(fake), 'Test/compile'])
    ok = proc.returncode == 0
    if ok:
        dump = latest_env_dump(tmp / 'n')
        run_dir, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] is True and verdict['task'][0]['tasks'] == ['Test/compile']
        ok = ok and dump['argv'][-1] == 'Test/compile' and heap_present(dump['argv'])
    battery.record('N1', cmd, proc, ok,
                   'Test/compile accepted, one session' if ok else 'see output tail')

    joined = 'testOnly nebflow.core.LogbackConfigSpec nebflow.core.Other*'
    cmd, proc = run_wrapper([str(fake), 'testOnly', 'nebflow.core.LogbackConfigSpec',
                             'nebflow.core.Other*'])
    ok = proc.returncode == 0
    if ok:
        dump = latest_env_dump(tmp / 'n')
        run_dir, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] is True and verdict['task'][0]['tasks'] == [joined]
        ok = ok and dump['argv'][-1] == joined
    battery.record('N2', cmd, proc, ok,
                   'multi-token testOnly collapses into one sbt command' if ok else 'see output tail')

    forms = ['scalafmtCheckAll', 'scalafmtAll', 'assembly', 'Test/compile']
    before = len(list((tmp / 'n').glob('env-*.json')))
    cmd, proc = run_wrapper([str(fake), *forms])
    ok = proc.returncode == 0
    if ok:
        dumps = sorted((tmp / 'n').glob('env-*.json'))
        dump = json.loads(dumps[-1].read_text())
        run_dir, verdict = verdict_of(dump)
        ok = verdict['guard']['pass'] is True and verdict['task'][0]['tasks'] == forms
        ok = ok and dump['argv'][-len(forms):] == forms
        ok = ok and len(dumps) == before + 1  # exactly one sbt session
    battery.record('N3', cmd, proc, ok,
                   'scalafmtCheckAll scalafmtAll assembly Test/compile run serially in one session' if ok else 'see output tail')


def main():
    battery = Battery()
    with tempfile.TemporaryDirectory(prefix='sbt-guard-selftest-') as name:
        tmp = pathlib.Path(name)
        case_env(battery, tmp)
        case_w(battery, tmp)   # refusals first: nothing must ever start
        case_l(battery, tmp)
        case_n(battery, tmp)
        case_d(battery, tmp)
        case_e(battery, tmp)
        case_g(battery, tmp)
        case_a(battery, tmp)
        case_b(battery, tmp)
        case_c(battery, tmp)
        case_f(battery, tmp)
    print('\n'.join(battery.rows), flush=True)
    if battery.failures:
        print(f'RESULT: {len(battery.failures)} FAILURE(S)', flush=True)
        return 1
    print('RESULT: ALL PASS', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
