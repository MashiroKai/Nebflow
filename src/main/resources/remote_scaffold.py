import hashlib
import json
import os
import socket
import sys
import time

# Semantics belong to feat/stand-rule-platform; align during integration.
DIRECTORIES = ('.nebflow', '.stand/worktrees', '.stand/tmp')
RULES = ('/.nebflow/', '.stand/worktrees/', '.stand/tmp/')
LOCK_TIMEOUT_SECONDS = 900
RETRY_BACKOFF_SECONDS = 0.2


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


def ensureGitignoreRules(workspace, report, before_check=lambda: None, details=None):
    target = os.path.join(workspace, '.gitignore')
    lock = os.path.join(workspace, '.nebflow', 'locks', 'gitignore.lock')
    os.makedirs(os.path.dirname(lock), exist_ok=True)
    timeout = float(os.environ.get('STAND_SCAFFOLD_LOCK_TIMEOUT_SECONDS', LOCK_TIMEOUT_SECONDS))
    if not 1 <= timeout <= 86400:
        raise ValueError('STAND_SCAFFOLD_LOCK_TIMEOUT_SECONDS must be between 1 and 86400')
    takeover = None
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        # Serialise contenders reclaiming a stale lock with an exclusive hard link.
        # Refuse rather than risk deleting a newly acquired lock on filesystems
        # that cannot provide this primitive.
        claim = lock + '.reclaim'
        try:
            os.link(lock, claim)
        except FileExistsError:
            raise RuntimeError('lock exists (%s); another stale-lock recovery is in progress' % lock)
        try:
            with open(claim, 'r', encoding='utf-8') as stream:
                owner = json.load(stream)
            timestamp = float(owner['timestamp'])
            if not all(key in owner for key in ('pid', 'host', 'operator')) or not timestamp > 0:
                raise ValueError('missing owner metadata')
            remaining = timeout - (time.time() - timestamp)
            if remaining > 0:
                raise RuntimeError('lock exists (%s); owner=%s; remaining wait %.1fs' % (lock, json.dumps(owner, ensure_ascii=True), remaining))
            if not os.path.samefile(lock, claim):
                raise RuntimeError('lock changed during stale recovery: %s' % lock)
            os.unlink(lock)
            try:
                fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                raise RuntimeError('lock reacquired by another writer during stale recovery: %s' % lock)
            takeover = owner
        finally:
            os.unlink(claim)
    if takeover is not None and details is not None:
        details['stale_lock_takeover'] = takeover
    lock_identity = os.fstat(fd)
    def owns_lock():
        try:
            current = os.stat(lock)
            return (current.st_dev, current.st_ino) == (lock_identity.st_dev, lock_identity.st_ino)
        except FileNotFoundError:
            return False
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
                    time.sleep(RETRY_BACKOFF_SECONDS * (2 ** attempt))
                    continue
                raise RuntimeError('concurrent modification detected after 3 attempts; no write performed')
            if not owns_lock():
                raise RuntimeError('lock ownership lost before append: %s' % lock)
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
        if owns_lock():
            os.unlink(lock)


def run(workspace, device, before_check=lambda: None):
    report = {item: 'not attempted' for item in DIRECTORIES + RULES}
    details = {}
    try:
        if not os.path.isdir(workspace):
            raise RuntimeError('workspace does not exist or is not a directory')
        ensureDirectories(workspace, report)
        try:
            ensureGitignoreRules(workspace, report, before_check, details)
        except Exception as exc:
            for rule in RULES:
                if report[rule] == 'not attempted':
                    report[rule] = 'failed or unconfirmed: ' + str(exc)
            raise
        return dict({'ok': True, 'device': device, 'path': workspace, 'items': report}, **details)
    except Exception as exc:
        return dict({'ok': False, 'device': device, 'path': workspace, 'reason': str(exc), 'items': report}, **details)


if __name__ == '__main__':
    result = run(sys.argv[1], sys.argv[2])
    print('REMOTE_SCAFFOLD_RESULT=' + json.dumps(result, ensure_ascii=True, separators=(',', ':')))
