#!/usr/bin/env python3
"""Check committed delivery artifacts for the changes between two Git commits."""
import argparse
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='base commit, not a revision range')
    parser.add_argument('--head', default='HEAD', help='reviewed commit (default: HEAD)')
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
