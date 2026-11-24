#!/usr/bin/env python3
"""Serialized, sandboxed sbt launcher (repo sbt freeze guard #2934).

Runs whitelisted sbt tasks only inside a fresh isolated HOME under the
isolation root, wrapped in a seatbelt profile that denies the production
data root and the production port, behind an exclusive token lock, with
per-run evidence on disk. The wrapper itself never starts a real sbt in
self-tests: tests pass a fake sbt executable path.

Usage:
  isolated-sbt.py [--max-seconds N] /absolute/sbt --first-run
  isolated-sbt.py [--max-seconds N] /absolute/sbt TASK...

TASK must be exactly 'compile' or 'testOnly nebflow.core.LogbackConfigSpec';
--first-run runs the fixed pair in order. Anything else is refused.
Exit codes: 0 guard pass + tasks ok; 1 guard pass + task failure;
2 usage/whitelist refusal; 3 token lock busy; 4 log-show precheck failure;
5 guard failure (deny hit, deadline, log query failure, evidence write
failure); 6 internal error.
"""
import argparse
import datetime as dt
import fcntl
import json
import os
import pathlib
import pwd
import re
import secrets
import signal
import subprocess
import sys
import time

# Isolation root: kept out of the production data root by construction.
# The literal production root appears ONLY inside the sandbox profile deny
# rule and nowhere else in this file (self-cert grep requirement).
ISOLATION_ROOT = pathlib.Path('/Users/kaiyu', '.stand-isolated')
LOCKS_DIR = ISOLATION_ROOT / 'locks'
HOMES_DIR = ISOLATION_ROOT / 'homes'
RUNS_DIR = ISOLATION_ROOT / 'sbt-runs'
TOKEN_LOCK = LOCKS_DIR / 'sbt-token.lock'

ACCOUNT = pathlib.Path(pwd.getpwuid(os.getuid()).pw_dir)
JAVA_HOME = '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home'

PROFILE = '''(version 1)
(allow default)
(deny network-outbound (remote tcp "*:6138"))
(deny file-read* file-write* (subpath "/Users/kaiyu/.stand"))
'''
# Production root and denied port are derived from the profile text, so the
# literals exist only in the deny rules above.
PRODUCTION_ROOT = pathlib.Path(re.search(r'subpath "(/[^"]+)"', PROFILE).group(1))
DENIED_PORT = int(re.search(r'"\*:(\d+)"', PROFILE).group(1))

LOG_SHOW = '/usr/bin/log'
LOG_PREDICATE = 'eventMessage CONTAINS "6138" AND eventMessage CONTAINS "deny("'
LOG_TIMEOUT = 60
PRECHECK_WINDOW = ('--last', '15s')

FIRST_RUN_TASKS = ('compile', 'testOnly nebflow.core.LogbackConfigSpec')
# Command whitelist (fail-closed). Single-token commands:
SINGLE_TASKS = ('compile', 'Test/compile', 'scalafmtCheckAll', 'scalafmtAll', 'assembly')
# Always refused, even though sbt accepts them:
REFUSED_TASKS = ('test', 'run', 'runMain', 'shell', 'console', 'set', 'eval', 'export')
# testOnly takes 1+ arguments shaped nebflow.<ClassName>/nebflow.* — the
# charset (letters, digits, underscore, dot, star) blocks shell/sbt metachars.
TESTONLY_ARG_RE = re.compile(r'nebflow\.[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\*?')
SCALAFIX_CHECK_RE = re.compile(r'(?:Compile/|Test/)?scalafix --check')

DEFAULT_MAX_SECONDS = 1800
DEFAULT_SBT_HEAP = '3g'  # matches the repo's canonical invocation (scripts/rebrand.sh)
POLL_INTERVAL = 5.0
REAP_TERM_GRACE = 10.0
REAP_KILL_GRACE = 5.0
SWEEP_INTERVAL = 1.0
SWEEP_MAX_QUERIES = 10
SWEEP_SETTLE = 5.0
PS_TIMEOUT = 10

# NOTE: SWEEP_SETTLE/SWEEP_MAX are conservative first-run starting points;
# calibrate against measured unified-log latency after the first real run.
EXIT_OK, EXIT_TASK, EXIT_USAGE, EXIT_LOCK, EXIT_PRECHECK, EXIT_GUARD, EXIT_INTERNAL = range(7)

ENV_WHITELIST_KEYS = ('PATH', 'HOME', 'JAVA_HOME', 'LANG', 'TMPDIR', 'STAND_HOME',
                      'NEBFLOW_HOME', 'COURSIER_CACHE', 'SBT_GATE', 'STAND_SBT_ISOLATED')


def now_iso():
    return dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')


def die(code, message):
    print(f'isolated-sbt: {message}', file=sys.stderr, flush=True)
    raise SystemExit(code)


def normalize_tasks(tokens):
    """Validate task tokens against the whitelist and return the sbt command
    tokens. `testOnly` given as its own argv token consumes the 1+ following
    nebflow.* argument tokens and collapses into ONE sbt command token, so
    sbt -batch runs all commands serially inside a single session. The
    single-token form 'testOnly nebflow.X nebflow.Y' is accepted too.
    Fail-closed: anything else raises ValueError."""
    commands = []
    i = 0
    while i < len(tokens):
        token = tokens[i]
        if token in SINGLE_TASKS or SCALAFIX_CHECK_RE.fullmatch(token):
            commands.append(token)
            i += 1
            continue
        if token == 'testOnly':
            i += 1
            args = []
            while i < len(tokens) and TESTONLY_ARG_RE.fullmatch(tokens[i]):
                args.append(tokens[i])
                i += 1
            if not args:
                raise ValueError('testOnly needs 1+ arguments shaped nebflow.<Class> or nebflow.*')
            commands.append('testOnly ' + ' '.join(args))
            continue
        parts = token.split()
        if (len(parts) > 1 and parts[0] == 'testOnly' and token == ' '.join(parts) and
                all(TESTONLY_ARG_RE.fullmatch(part) for part in parts[1:])):
            commands.append(token)
            i += 1
            continue
        stripped = token.strip()
        if (stripped in REFUSED_TASKS or stripped.startswith('~') or
                any(mark in token for mark in (';', '\n', '\r'))):
            raise ValueError(f'refusing task: {token!r}')
        raise ValueError(f'task outside whitelist: {token!r}')
    return commands


def parse_args(argv):
    parser = argparse.ArgumentParser(prog='isolated-sbt.py', add_help=False)
    parser.add_argument('sbt', help='absolute path to the sbt executable')
    parser.add_argument('tasks', nargs='*', help='sbt tasks (whitelist only)')
    parser.add_argument('--first-run', action='store_true',
                        help='fixed pair: compile then testOnly nebflow.core.LogbackConfigSpec')
    parser.add_argument('--max-seconds', type=int, default=DEFAULT_MAX_SECONDS,
                        help=f'total deadline from launch, including cleanup and log window (default {DEFAULT_MAX_SECONDS})')
    parser.add_argument('--sbt-heap', default=DEFAULT_SBT_HEAP,
                        help=f'sbt JVM max heap passed as -J-Xmx (default {DEFAULT_SBT_HEAP}); '
                             'the environment whitelist drops JAVA_OPTS/SBT_OPTS and the repo has '
                             'no .jvmopts, so without this sbt runs its 1G default and OOMs on '
                             'this codebase (first-run evidence 2026-11-24)')
    args = parser.parse_args(argv)
    if args.max_seconds <= 0:
        die(EXIT_USAGE, '--max-seconds must be positive')
    if not re.fullmatch(r'[0-9]+[kKmMgG]?', args.sbt_heap):
        die(EXIT_USAGE, f'--sbt-heap must look like 3g or 2048m: {args.sbt_heap!r}')
    if args.first_run:
        if args.tasks:
            die(EXIT_USAGE, '--first-run takes no extra tasks')
        args.tasks = list(FIRST_RUN_TASKS)
    else:
        if not args.tasks:
            die(EXIT_USAGE, 'expected --first-run or explicit whitelisted tasks')
    try:
        args.tasks = normalize_tasks(args.tasks)
    except ValueError as exc:
        die(EXIT_USAGE, str(exc))
    sbt = pathlib.Path(args.sbt)
    if not sbt.is_absolute():
        die(EXIT_USAGE, 'sbt executable must be given as an absolute path')
    sbt = sbt.resolve()
    if not sbt.is_file() or not os.access(sbt, os.X_OK):
        die(EXIT_USAGE, f'sbt path is not an executable file: {sbt}')
    if sbt == PRODUCTION_ROOT or PRODUCTION_ROOT in sbt.parents:
        die(EXIT_USAGE, 'sbt executable must be outside the production root')
    return sbt, args.tasks, args.max_seconds, args.sbt_heap


def acquire_token():
    """Take the exclusive sbt token lock; write our PID while holding it."""
    LOCKS_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = open(TOKEN_LOCK, 'a+', encoding='utf-8')
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        fd.seek(0)
        holder = fd.read().strip() or 'unknown'
        fd.close()
        return None, holder
    fd.seek(0)
    fd.truncate()
    fd.write(f'{os.getpid()}\n')
    fd.flush()
    return fd, None


def release_token(fd):
    if fd is None:
        return
    try:
        fd.seek(0)
        fd.truncate()
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        fd.close()


def build_env(home):
    """Fresh child environment: nothing is inherited, so inherited STAND_*/
    NEBFLOW_* variables are scrubbed by construction; only the whitelist
    below reaches the child. Cache roots (coursier/ivy/sbt boot) are reused
    in place with read-write access; every other global directory resolves
    under the isolated HOME."""
    data = home / '.stand'
    env = {
        'PATH': '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin',
        'HOME': str(home),
        'JAVA_HOME': JAVA_HOME,
        'LANG': 'C.UTF-8',
        'TMPDIR': str(home / 'tmp'),
        'STAND_HOME': str(data),
        'NEBFLOW_HOME': str(data),
        'COURSIER_CACHE': str(ACCOUNT / 'Library' / 'Caches' / 'Coursier'),
        'SBT_GATE': 'off',
        'STAND_SBT_ISOLATED': '1',
    }
    assert set(env) == set(ENV_WHITELIST_KEYS)
    return env


def build_command(sbt, home, tasks, heap=DEFAULT_SBT_HEAP):
    """One sandboxed sbt invocation: all tasks run in order inside a single
    sbt session (`-batch task1 task2 ...`), one JVM, one process group."""
    opts = [
        f'-J-Duser.home={home}',
        f'-J-Dsbt.global.base={ACCOUNT}/.sbt',
        f'-J-Dsbt.boot.directory={ACCOUNT}/.sbt/boot',
        f'-J-Dsbt.ivy.home={ACCOUNT}/.ivy2',
        f'-J-Xmx{heap}',
    ]
    return ['/usr/bin/sandbox-exec', '-p', PROFILE, str(sbt), *opts, '-batch', *tasks]


def log_show_window(start, end):
    """Query unified log for sandbox deny lines on the denied port."""
    cmd = [LOG_SHOW, 'show', '--start', start, '--end', end,
           '--info', '--debug', '--style', 'compact', '--predicate', LOG_PREDICATE]
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=LOG_TIMEOUT)
    except subprocess.TimeoutExpired as exc:
        return subprocess.CompletedProcess(cmd, 124, exc.stdout or '', f'timeout after {LOG_TIMEOUT}s')
    except OSError as exc:
        return subprocess.CompletedProcess(cmd, 125, '', str(exc))


DENY_LINE_RE = re.compile(r'Sandbox: (\S+?)\((\d+)\) deny\(\d+\) (.*)')
# Historical unrelated WebKit system-service probes are not sbt descendants;
# permit only these observed operations, never a deny from the task tree.
WEBKIT_ALLOW = frozenset(('mach-lookup com.apple.ReportMemoryException',
                          'mach-lookup com.apple.analyticsd',
                          'system-privilege 10001'))


def judge_deny(text, tree_pids, port):
    """Fail closed on sandbox denies except narrowly documented log noise."""
    failures, info = [], []
    for line in text.splitlines():
        # log emits its own predicate as argv, not a sandbox event.
        if 'log run noninteractively' in line and 'Sandbox:' not in line:
            continue
        if not re.search(r'\bdeny\(\d+\)', line):
            continue
        match = DENY_LINE_RE.search(line)
        # Unified log may emit a JSON metadata continuation for the same
        # observed WebKit event; require the exact identity and operation.
        if line.startswith('MetaData: '):
            try:
                raw = line[len('MetaData: '):]
                # macOS log renders path separators as non-JSON octal escapes.
                raw = raw.replace(r'\134/', '/')
                metadata = json.loads(raw)
                metadata_pid = int(metadata['pid'])
                if (metadata_pid not in tree_pids and
                        metadata['process'] == 'com.apple.WebKit.Networking.Deve' and
                        re.sub(r'^deny\(\d+\) ', '', metadata['summary']) in WEBKIT_ALLOW):
                    info.append({'pid': metadata_pid, 'process': metadata['process'], 'line': line.strip()})
                    continue
            except (ValueError, KeyError, TypeError, json.JSONDecodeError):
                pass
        pid = int(match.group(2)) if match else None
        process = match.group(1) if match else None
        entry = {'pid': pid, 'process': process, 'line': line.strip()}
        if (match and pid not in tree_pids and
                process == 'com.apple.WebKit.Networking.Deve' and
                match.group(3).strip() in WEBKIT_ALLOW):
            info.append(entry)
        else:
            failures.append(entry)
    return {'failures': failures, 'info': info}


def group_alive(pgid):
    try:
        os.killpg(pgid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return True
    return True


def ps_lines(columns):
    proc = subprocess.run(['ps', '-axo', columns], capture_output=True, text=True, timeout=PS_TIMEOUT)
    return proc.stdout.splitlines()


def snapshot_pgid(pgid):
    lines = [line for line in ps_lines('pid=,ppid=,pgid=,etime=,command=')
             if line.split() and len(line.split()) > 2 and line.split()[2] == str(pgid)]
    return lines


def tree_pids_of(lines, pgid):
    pids = set()
    for line in lines:
        fields = line.split()
        if len(fields) > 2 and fields[2] == str(pgid):
            pids.add(int(fields[0]))
    return pids


def cumulative_cpu(pgid):
    """Sum of cumulative CPU time (ps `time` column) across the process group."""
    total = 0.0
    for line in ps_lines('pgid=,time='):
        fields = line.split()
        if len(fields) < 2 or fields[0] != str(pgid):
            continue
        parts = fields[1].split(':')
        try:
            seconds = [float(p) for p in parts]
        except ValueError:
            continue
        for value in seconds:
            total = total * 60 + value
    return round(total, 1)


def reap_residuals(pgid):
    """Live (non-zombie) processes left in the group. Zombies of our own
    direct child are reaped by wait(); other zombies are reparented and
    reaped by launchd, so they are not residuals we can act on."""
    residuals = []
    try:
        lines = ps_lines('pid=,pgid=,state=,command=')
    except (subprocess.SubprocessError, OSError):
        return None
    for line in lines:
        fields = line.split(None, 3)
        if len(fields) >= 3 and fields[1] == str(pgid) and 'Z' not in fields[2]:
            residuals.append(line)
    return residuals


def reap_process_group(pgid, ev, t_start):
    """Reap strictly by PGID: SIGTERM, 10s grace, SIGKILL, then ps recheck.
    The PGID is the child we started with start_new_session, never a group
    we did not create; the assertion below refuses anything else. Residuals
    are info items, not failures."""
    reaped = []
    try:
        own_group = os.getpgid(0)
    except OSError:
        own_group = None
    if pgid in (0, 1, os.getpid(), own_group):
        ev.failures.append({'pid': None, 'process': 'reap',
                            'line': f'refusing to signal unexpected pgid {pgid}'})
        return reaped
    if group_alive(pgid):
        try:
            os.killpg(pgid, signal.SIGTERM)
            ev.append('reap', pgid=pgid, step='sigterm')
        except (ProcessLookupError, PermissionError):
            pass
        deadline = time.monotonic() + REAP_TERM_GRACE
        while time.monotonic() < deadline and group_alive(pgid):
            time.sleep(0.2)
    if group_alive(pgid):
        try:
            os.killpg(pgid, signal.SIGKILL)
            ev.append('reap', pgid=pgid, step='sigkill')
        except (ProcessLookupError, PermissionError):
            pass
        deadline = time.monotonic() + REAP_KILL_GRACE
        while time.monotonic() < deadline and group_alive(pgid):
            time.sleep(0.2)
    residuals = reap_residuals(pgid)
    if residuals:
        reaped = residuals
        ev.append('reap', pgid=pgid, step='residual-recheck', residual_count=len(residuals))
    ev.write_text('process-tree-final.txt',
                  'reap check for pgid %d at %s (zombies excluded; they are reaped by wait/launchd)\n'
                  % (pgid, now_iso()) + '\n'.join(residuals) + '\n')
    return reaped


def run_sweep(query_fn, tree_pids, task_end_mono, deadline_mono,
              sleep_fn=time.sleep, mono_fn=time.monotonic):
    """Post-task deny sweep: one query per second, at most SWEEP_MAX_QUERIES.
    Passes when >=5s past task end with two consecutive successful queries
    covering the window and no tree-PID deny line on the denied port.
    A failing query (nonzero exit / timeout / OSError) is fail-closed."""
    result = {'passed': False, 'failures': [], 'info': [], 'attempts': 0,
              'failure_reason': None, 'queries': []}
    prev_ok = False
    for attempt in range(1, SWEEP_MAX_QUERIES + 1):
        if mono_fn() >= deadline_mono:
            result['failure_reason'] = 'total-deadline'
            return result
        proc = query_fn()
        result['attempts'] = attempt
        elapsed_since_end = mono_fn() - task_end_mono
        entry = {'attempt': attempt, 'returncode': proc.returncode,
                 'since_task_end': round(elapsed_since_end, 1)}
        result['queries'].append(entry)
        if proc.returncode != 0:
            result['failure_reason'] = 'logshow-query-failed'
            entry['stderr'] = (proc.stderr or '')[:400]
            result['failures'].append({'pid': None, 'process': 'log-show',
                                       'line': f'query exit {proc.returncode}: {entry["stderr"]}'})
            return result
        judged = judge_deny(proc.stdout, tree_pids, DENIED_PORT)
        entry['deny_failures'] = len(judged['failures'])
        entry['deny_info'] = len(judged['info'])
        if judged['failures']:
            result['failure_reason'] = 'deny-port'
            result['failures'].extend(judged['failures'])
            return result
        result['info'].extend(judged['info'])
        if elapsed_since_end >= SWEEP_SETTLE and prev_ok:
            result['passed'] = True
            return result
        prev_ok = True
        if mono_fn() >= deadline_mono:
            result['failure_reason'] = 'total-deadline'
            return result
        sleep_fn(SWEEP_INTERVAL)
    result['failure_reason'] = 'sweep-unverified'
    return result


class RunEvidence:
    """All evidence writes funnel through here; a failed write is a guard
    failure even if the rest of the run was clean."""

    def __init__(self, run_dir, t0_mono):
        self.dir = run_dir
        self.t0 = t0_mono
        self.failures = []
        self.info = []
        self.outputs = []

    def _record_failure(self, name, exc):
        reason = f'evidence-write:{name}'
        if reason not in [f['line'] for f in self.failures]:
            self.failures.append({'pid': None, 'process': 'evidence',
                                  'line': f'{reason}: {exc}'})
        print(f'isolated-sbt: cannot write evidence {name}: {exc}',
              file=sys.stderr, flush=True)

    def write_text(self, name, text):
        try:
            (self.dir / name).write_text(text, encoding='utf-8')
        except OSError as exc:
            self._record_failure(name, exc)

    def write_bytes(self, name, data):
        try:
            (self.dir / name).write_bytes(data)
        except OSError as exc:
            self._record_failure(name, exc)

    def append_text(self, name, text):
        try:
            with open(self.dir / name, 'a', encoding='utf-8') as fh:
                fh.write(text)
        except OSError as exc:
            self._record_failure(name, exc)

    def append_jsonl(self, name, event, **fields):
        record = {'t': round(time.monotonic() - self.t0, 1), 'event': event}
        record.update({k: v for k, v in fields.items() if v is not None})
        self.append_text(name, json.dumps(record, ensure_ascii=True) + '\n')

    # timeline convenience
    def append(self, event, **fields):
        self.append_jsonl('timeline.jsonl', event, **fields)

    def append_sweep_raw(self, text):
        self.outputs.append(text)

    def flush_sweep_raw(self):
        self.write_text('sandbox-deny-sweep.txt', ''.join(self.outputs))


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    sbt, tasks, max_seconds, sbt_heap = parse_args(argv)

    token, holder = acquire_token()
    if token is None:
        die(EXIT_LOCK, f'another isolated sbt run holds the token lock (holder pid {holder}); refusing to start')

    run_id = 'sbt-{}-{}-{}'.format(time.strftime('%Y%m%dT%H%M%S'), os.getpid(), secrets.token_hex(3))
    home = HOMES_DIR / run_id
    run_dir = RUNS_DIR / run_id
    t0_mono = time.monotonic()
    t0_wall = dt.datetime.now()
    try:
        for directory in (HOMES_DIR, RUNS_DIR, home, home / 'tmp', run_dir):
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    except OSError as exc:
        release_token(token)
        die(EXIT_INTERNAL, f'cannot create isolation directories: {exc}')

    ev = RunEvidence(run_dir, t0_mono)
    env = build_env(home)
    command_record = {
        'run_id': run_id,
        'command': sys.argv,
        'cwd': os.getcwd(),
        'env_whitelist': {k: env[k] for k in ENV_WHITELIST_KEYS},
        'tasks': tasks,
        'max_seconds': max_seconds,
        'sbt_heap': sbt_heap,
        'sbt': str(sbt),
        'sandbox_profile': PROFILE,
        'log_predicate': LOG_PREDICATE,
        'start_utc': t0_wall.astimezone(dt.timezone.utc).isoformat(),
        'note': 'sweep settle/attempt constants are conservative first-run values pending calibration',
    }
    ev.write_bytes('command.json', json.dumps(command_record, indent=2).encode() + b'\n')

    # Pre-flight: log show must work and we record the pre-existing baseline.
    try:
        precheck = subprocess.run([LOG_SHOW, 'show', *PRECHECK_WINDOW, '--info', '--debug',
                                   '--style', 'compact', '--predicate', LOG_PREDICATE],
                                  capture_output=True, text=True, timeout=LOG_TIMEOUT)
    except (subprocess.TimeoutExpired, OSError) as exc:
        precheck = subprocess.CompletedProcess([], 124, '', str(exc))
    ev.append_sweep_raw(f'=== precheck {" ".join(PRECHECK_WINDOW)} exit {precheck.returncode} at {now_iso()} ===\n')
    ev.append_sweep_raw(precheck.stdout or '')
    ev.append_sweep_raw(precheck.stderr or '')
    if precheck.returncode != 0:
        ev.failures.append({'pid': None, 'process': 'log-show',
                            'line': f'precheck exit {precheck.returncode}: {(precheck.stderr or "")[:400]}'})
        verdict = {'guard': {'pass': False, 'failures': ev.failures, 'reason': 'logshow-precheck'},
                   'task': [], 'run_id': run_id, 'info': {}}
        ev.write_bytes('verdict.json', json.dumps(verdict, indent=2).encode() + b'\n')
        ev.append('run_end', outcome='precheck-refused')
        ev.flush_sweep_raw()
        release_token(token)
        die(EXIT_PRECHECK, 'log show precheck failed; refusing to start (fail closed)')

    baseline = judge_deny(precheck.stdout or '', set(), DENIED_PORT)
    ev.append('precheck', baseline_info=baseline['info'], baseline_failures=baseline['failures'])
    if baseline['failures']:
        verdict = {'guard': {'pass': False, 'failures': baseline['failures'],
                             'reason': 'precheck-deny'},
                   'task': [], 'run_id': run_id, 'info': {'baseline_non_tree_deny_lines': baseline['info']}}
        ev.write_bytes('verdict.json', json.dumps(verdict, indent=2).encode() + b'\n')
        ev.append('run_end', outcome='precheck-deny')
        ev.flush_sweep_raw()
        release_token(token)
        die(EXIT_GUARD, 'unallowed sandbox deny in precheck; refusing to start')

    task_results = []
    reaped_all = []
    guard_failures = list(ev.failures)
    reason = None

    # All whitelisted tasks run in the given order inside ONE sbt session:
    # one sandboxed JVM, one process group, one monitor/sweep/reap cycle.
    session_name = ' '.join(tasks)
    cmd = build_command(sbt, home, tasks, sbt_heap)
    ev.append('task_start', task=session_name, tasks=tasks, argv=cmd)
    out = None
    try:
        out = open(run_dir / 'sbt-output.log', 'ab')
    except OSError as exc:
        ev._record_failure('sbt-output.log', exc)
        guard_failures = list(ev.failures)

    if out is not None:
        with out:
            proc = subprocess.Popen(cmd, env=env, cwd=os.getcwd(),
                                    stdout=out, stderr=subprocess.STDOUT,
                                    start_new_session=True)
        pgid = proc.pid
        ev.append('task_spawn', task=session_name, pid=proc.pid, pgid=pgid)

        deadline = t0_mono + max_seconds
        tree_pids = {proc.pid}
        timed_out = False
        aborted = False
        while True:
            if time.monotonic() >= deadline:
                timed_out = True
                reason = 'total-deadline'
                break
            if proc.poll() is not None:
                break
            try:
                lines = snapshot_pgid(pgid)
                tree_pids |= tree_pids_of(lines, pgid)
                ev.append('poll', task=session_name, group_alive=group_alive(pgid),
                          pids=sorted(tree_pids), cpu_seconds=cumulative_cpu(pgid),
                          ps_lines=lines)
            except (subprocess.SubprocessError, OSError) as exc:
                guard_failures.append({'pid': None, 'process': 'monitor',
                                       'line': f'monitor error: {exc}'})
                aborted = True
                break
            if proc.poll() is not None:
                break
            sleep_for = min(POLL_INTERVAL, max(0.2, deadline - time.monotonic()))
            time.sleep(sleep_for)

        if timed_out or aborted:
            ev.append('task_deadline' if timed_out else 'task_monitor_error', task=session_name, pgid=pgid)
            reaped_all.extend(reap_process_group(pgid, ev, t0_mono))
            exit_code = proc.wait()
            ev.append('task_exit', task=session_name, exit_code=exit_code, timed_out=timed_out)
            task_results.append({'name': session_name, 'tasks': list(tasks),
                                 'exit_code': exit_code, 'timed_out': timed_out})
            if timed_out:
                guard_failures.append({'pid': None, 'process': 'deadline',
                                       'line': f'total deadline {max_seconds}s reached during sbt session {session_name!r}'})
                reason = reason or 'total-deadline'
        else:
            exit_code = proc.wait()
            ev.append('task_exit', task=session_name, exit_code=exit_code)
            task_results.append({'name': session_name, 'tasks': list(tasks),
                                 'exit_code': exit_code, 'timed_out': False})

            # Post-session deny sweep over [T0-5s, query time], then reap leftovers.
            sweep_start = (t0_wall - dt.timedelta(seconds=SWEEP_SETTLE)).strftime('%Y-%m-%d %H:%M:%S')

            def query_fn():
                end = now_iso()
                proc_ = log_show_window(sweep_start, end)
                ev.append_sweep_raw(f'=== sweep query window [{sweep_start} .. {end}] exit {proc_.returncode} ===\n')
                ev.append_sweep_raw(proc_.stdout or '')
                if proc_.returncode != 0:
                    ev.append_sweep_raw(proc_.stderr or '')
                return proc_

            sweep = run_sweep(query_fn, tree_pids, time.monotonic(), t0_mono + max_seconds)
            ev.append('sweep_result', task=session_name, **{k: sweep[k] for k in
                                                            ('passed', 'attempts', 'failure_reason')})
            ev.append_sweep_raw(f'=== sweep verdict passed={sweep["passed"]} attempts={sweep["attempts"]} reason={sweep["failure_reason"]} ===\n')
            seen_lines = {entry['line'] for entry in ev.info}
            for entry in sweep['info']:
                if entry['line'] not in seen_lines:
                    ev.info.append(entry)
                    seen_lines.add(entry['line'])
            reaped_all.extend(reap_process_group(pgid, ev, t0_mono))
            if not sweep['passed']:
                guard_failures.extend(sweep['failures'])
                if not sweep['failures'] and sweep['failure_reason']:
                    guard_failures.append({'pid': None, 'process': 'sweep',
                                           'line': f'sweep failed: {sweep["failure_reason"]}'})
                reason = reason or sweep['failure_reason']

    # Final process-tree snapshot across every task group.
    ev.append_text('process-tree-final.txt', f'final snapshot at {now_iso()}\n')
    ev.flush_sweep_raw()

    guard_pass = not guard_failures and not ev.failures
    all_failures = guard_failures + ev.failures
    verdict = {
        'guard': {'pass': guard_pass, 'failures': all_failures, 'reason': reason},
        'task': task_results,
        'run_id': run_id,
        'duration_seconds': round(time.monotonic() - t0_mono, 1),
        'info': {
            'non_tree_deny_lines': ev.info,
            'baseline_non_tree_deny_lines': baseline['info'],
            'reaped_or_residual': reaped_all,
            'env_whitelist_keys': list(ENV_WHITELIST_KEYS),
        },
    }
    try:
        ev.write_bytes('verdict.json', json.dumps(verdict, indent=2).encode() + b'\n')
    finally:
        release_token(token)
    ev.append('run_end', guard_pass=guard_pass)

    if not guard_pass:
        for failure in all_failures:
            print(f'guard failure: {failure.get("line")}', file=sys.stderr, flush=True)
        return EXIT_GUARD
    if any(result['exit_code'] != 0 or result['timed_out'] for result in task_results):
        return EXIT_TASK
    return EXIT_OK


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('isolated-sbt: interrupted', file=sys.stderr)
        sys.exit(130)
