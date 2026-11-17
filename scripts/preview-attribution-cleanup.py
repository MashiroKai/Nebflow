#!/usr/bin/env python3
"""Preview message-only cleanup; --filter is for git filter-branch's msg-filter."""
import re
import subprocess
import sys

PATTERN = re.compile(rb'^Co-Authored-By:\s*Claude Opus 4\.[68](?: \(1M context\))?\s*<noreply@anthropic\.com>\s*$', re.I)


def clean(message):
    return b''.join(line for line in message.splitlines(keepends=True) if not PATTERN.match(line.rstrip(b'\r\n')))


def git(*args):
    return subprocess.check_output(['git', *args])


def main():
    if sys.argv[1:] == ['--filter']:
        sys.stdout.buffer.write(clean(sys.stdin.buffer.read()))
        return
    if len(sys.argv) != 2 or '..' not in sys.argv[1] or sys.argv[1].startswith('-'):
        sys.exit('usage: preview-attribution-cleanup.py BASE..TIP')
    commits = git('rev-list', '--reverse', '--end-of-options', sys.argv[1]).decode().splitlines()
    count = 0
    for commit in commits:
        message = git('show', '-s', '--format=%B', commit)
        if clean(message) != message:
            tree = git('rev-parse', commit + '^{tree}').decode().strip()
            print(f'would remove matching trailer: {commit} tree={tree}')
            count += 1
    print(f'preview only: {count} matching commit(s), {len(commits)} examined; no refs changed')


if __name__ == '__main__':
    main()
