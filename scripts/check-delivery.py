#!/usr/bin/env python3
"""Check committed delivery artifacts for the changes between two Git commits."""
import argparse
import hashlib
import datetime as dt
from pathlib import Path
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='base commit, not a revision range')
    parser.add_argument('--head', default='HEAD', help='reviewed commit (default: HEAD)')
    parser.add_argument('--tmp', type=Path, help='worktree artifact directory .stand/tmp/<wtname>; validate manifest and delivery against head')
    args = parser.parse_args()
    try:
        base = git('rev-parse', '--verify', f'{args.base}^{{commit}}').decode().strip()
        head = git('rev-parse', '--verify', f'{args.head}^{{commit}}').decode().strip()
        paths = [p.decode('utf-8', 'surrogateescape') for p in git(
            'diff', '--no-renames', '--name-only', '-z', base, head, '--').split(b'\0') if p]
        files = {p.decode('utf-8', 'surrogateescape') for p in git(
            'ls-tree', '-r', '--name-only', '-z', head).split(b'\0') if p}
    except subprocess.CalledProcessError as exc:
        print(f'Cannot inspect range: {exc.stderr.decode(errors="replace").strip()}', file=sys.stderr)
        return 2

    frontend = any(p.startswith('src/main/resources/web/') for p in paths)
    backend = any(p.startswith(('src/main/scala/', 'src/main/java/', 'openspec/specs/',
                                'openspec/changes/')) for p in paths)
    print(f'Range {base}..{head}: frontend={frontend}, backend/design={backend}')
    if args.tmp:
        directory = args.tmp
        manifest = directory / 'MANIFEST.md'
        try:
            rows = manifest.read_text(encoding='utf-8').splitlines()
            if directory.is_symlink() or any(not (directory / part).is_dir() or (directory / part).is_symlink() for part in ('delivery', 'evidence')):
                raise ValueError('missing or unsafe artifact directory')
            entries = list(directory.rglob('*'))
            if any(p.is_symlink() for p in entries):
                raise ValueError('symlinks are not allowed')
            files_tmp = [p for p in entries if p.is_file() and p != manifest]
            if len([line for line in rows if line.startswith('| ')]) != len(files_tmp) + 2:
                raise ValueError('manifest inventory does not match files')
            for item in files_tmp:
                rel = item.relative_to(directory).as_posix()
                row = next((line for line in rows if line.startswith(f'| {rel} | ')), '')
                parts = [x.strip() for x in row.strip('|').split('|')]
                mtime = dt.datetime.fromtimestamp(item.stat().st_mtime).isoformat(timespec='seconds')
                if len(parts) != 4 or parts[1] != str(item.stat().st_size) or parts[2] != mtime or not re.fullmatch(r'未入库|入库 \d{4}-\d\d-\d\d \d\d:\d\d [0-9a-f]{40,64}', parts[3]):
                    raise ValueError(f'missing or malformed manifest row: {rel}')
                if parts[3].startswith('入库 '):
                    if not rel.startswith('delivery/'):
                        raise ValueError(f'evidence cannot be committed: {rel}')
                    committed = git('show', f'{head}:{rel}')
                    if hashlib.sha256(item.read_bytes()).digest() != hashlib.sha256(committed).digest():
                        raise ValueError(f'content mismatch: {rel}')
                    sha = git('log', '-1', '--format=%H', head, '--', rel).decode().strip()
                    stamp = git('show', '-s', '--format=%ci', sha).decode()[:16]
                    if parts[3] != f'入库 {stamp} {sha}':
                        raise ValueError(f'wrong commit attribution: {rel}')
        except (OSError, ValueError, subprocess.CalledProcessError) as exc:
            print(f'Missing or malformed temporary manifest: {exc}', file=sys.stderr)
            return 1
        print(f'Temporary delivery and manifest checked: {directory}')
    if not frontend and not backend:
        print('No classified product paths; human review must assess other paths.')
        return 0
    batches = sorted({p.split('/')[1] for p in paths
                      if re.match(r'^delivery/[^/]+/.+', p) and p in files})
    if len(batches) != 1:
        print('Missing: exactly one changed delivery/<batch>/ directory in the reviewed range', file=sys.stderr)
        return 1
    prefix = f'delivery/{batches[0]}/'
    errors = []

    def content(name):
        path = prefix + name
        if path not in files or path not in paths:
            errors.append(f'{path} (must exist at head and change in range)')
            return b''
        try:
            data = git('show', f'{head}:{path}')
        except subprocess.CalledProcessError:
            errors.append(f'{path} (unreadable)')
            return b''
        if not data.strip():
            errors.append(f'{path} (empty)')
        return data

    if frontend:
        for name in ('before.png', 'after.png'):
            data = content(name)
            if data and not data.startswith(b'\x89PNG\r\n\x1a\n'):
                errors.append(prefix + name + ' (not PNG)')
        text = content('screenshots.md').decode('utf-8', 'replace').lower()
        for heading in ('viewport', 'scenario', 'fixture or command'):
            if heading not in text:
                errors.append(prefix + 'screenshots.md (missing ' + heading + ')')
    if backend:
        text = content('changes.md').decode('utf-8', 'replace').lower()
        for heading in ('what', 'why', 'impact'):
            if heading not in text:
                errors.append(prefix + 'changes.md (missing ' + heading + ')')
        for name, marker in (('state-machine.md', 'statediagram'), ('behavior.md', 'mermaid')):
            text = content(name).decode('utf-8', 'replace').lower()
            if not all(word in text for word in ('before', 'after', marker)):
                errors.append(prefix + name + f' (requires before, after and {marker})')
    if errors:
        print('Missing or malformed delivery:\n- ' + '\n- '.join(errors), file=sys.stderr)
        return 1
    print('Delivery structure OK: ' + prefix + ' (semantic and privacy review still required)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
