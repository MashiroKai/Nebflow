import hashlib
import json
import os
import socket
import sys
import time

# Semantics belong to feat/stand-rule-platform; align during integration.
DIRECTORIES = ('.nebflow', '.stand/worktrees', '.stand/tmp')
RULES = ('/.nebflow/', '.stand/worktrees/', '.stand/tmp/')
LOCK_TIMEOUT_SECONDS = 3600


def digest(data):
    return hashlib.sha256(data).hexdigest()


def ensureDirectories(workspace, report):
    for directory in DIRECTORIES:
        path = os.path.join(workspace, directory)
        existed = os.path.isdir(path)
        try:
            os.makedirs(path, exist_ok=True)
        except Exception as exc:
            report[directory] = 'failed: ' + str(exc)
            raise
        report[directory] = 'already present' if existed else 'created'


def ensureGitignoreRules(workspace, report, before_check=lambda: None):
    target = os.path.join(workspace, '.gitignore')
    lock = os.path.join(workspace, '.nebflow', 'locks', 'gitignore.lock')
    os.makedirs(os.path.dirname(lock), exist_ok=True)
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        age = time.time() - os.stat(lock).st_mtime
        raise RuntimeError('lock exists (%s; age %.0fs; stale threshold %ds); manual inspection required' % (lock, age, LOCK_TIMEOUT_SECONDS))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump({'pid': os.getpid(), 'host': socket.gethostname(), 'timestamp': time.time(), 'operator': os.environ.get('USER') or os.environ.get('USERNAME') or 'unknown'}, stream)
        for attempt in range(3):
            try:
                with open(target, 'rb') as stream:
                    original = stream.read()
                exists = True
            except FileNotFoundError:
                original, exists = b'', False
            lines = original.splitlines()
            present = {line.strip() for line in lines}
            missing = [rule for rule in RULES if rule.encode() not in present and not (rule == '/.nebflow/' and (b'.nebflow/' in present or b'.nebflow' in present))]
            if not missing:
                for rule in RULES:
                    report[rule] = 'already present'
                return
            separator = b'' if not original or original.endswith((b'\n', b'\r')) else b'\n'
            addition = separator + b''.join(rule.encode() + b'\n' for rule in missing)
            before_check()
            try:
                with open(target, 'rb') as stream:
                    current = stream.read()
                current_exists = True
            except FileNotFoundError:
                current, current_exists = b'', False
            if current_exists != exists or digest(current) != digest(original):
                if attempt < 2:
                    continue
                raise RuntimeError('concurrent modification detected after 3 attempts; no write performed')
            # Append never replaces existing bytes. An external editor can still race this check.
            with open(target, 'ab') as stream:
                stream.write(addition)
            with open(target, 'rb') as stream:
                actual = stream.read()
            expected_hash = digest(original + addition)
            if digest(actual) != expected_hash:
                raise RuntimeError('readback sha256 mismatch: expected %s actual %s' % (expected_hash, digest(actual)))
            for rule in RULES:
                report[rule] = 'appended' if rule in missing else 'already present'
            return
    finally:
        os.unlink(lock)


def run(workspace, device, before_check=lambda: None):
    report = {item: 'not attempted' for item in DIRECTORIES + RULES}
    try:
        if not os.path.isdir(workspace):
            raise RuntimeError('workspace does not exist or is not a directory')
        ensureDirectories(workspace, report)
        try:
            ensureGitignoreRules(workspace, report, before_check)
        except Exception as exc:
            for rule in RULES:
                if report[rule] == 'not attempted':
                    report[rule] = 'failed or unconfirmed: ' + str(exc)
            raise
        return {'ok': True, 'device': device, 'path': workspace, 'items': report}
    except Exception as exc:
        return {'ok': False, 'device': device, 'path': workspace, 'reason': str(exc), 'items': report}


if __name__ == '__main__':
    result = run(sys.argv[1], sys.argv[2])
    print('REMOTE_SCAFFOLD_RESULT=' + json.dumps(result, ensure_ascii=True, separators=(',', ':')))
