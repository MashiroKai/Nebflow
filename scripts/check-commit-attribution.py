#!/usr/bin/env python3
"""Reject AI co-author and generator lines in a commit or git revision range."""
import re
import subprocess
import sys

TOOL = re.compile(r"\b(?:claude|anthropic|codex|openai|copilot|github copilot|zcode|gemini|cursor|aider|chatgpt)\b", re.I)
TRAILER = re.compile(r"^\s*(?:co-authored-by|generated-by|generated-with|created-by|assisted-by)\s*:\s*(.*)$", re.I)
GENERATED = re.compile(r"^\s*(?:generated|created|authored|written)\s+(?:by|with|using)\s+(.+)$", re.I)


def violations(message):
    for line in message.splitlines():
        match = TRAILER.match(line) or GENERATED.match(line)
        if match and TOOL.search(match.group(1)):
            yield line


def git(*args):
    return subprocess.check_output(["git", *args], stderr=subprocess.PIPE)


def main():
    if len(sys.argv) != 2 or sys.argv[1].startswith('-'):
        sys.exit('usage: check-commit-attribution.py COMMIT|BASE..TIP')
    revision = sys.argv[1]
    try:
        if '..' in revision:
            commits = git('rev-list', '--end-of-options', revision).decode().splitlines()
        else:
            commits = [git('rev-parse', '--verify', '--end-of-options', revision + '^{commit}').decode().strip()]
        failed = False
        for commit in commits:
            message = git('show', '-s', '--format=%B', commit).decode('utf-8', 'replace')
            for line in violations(message):
                print(f'{commit}: forbidden attribution: {line}', file=sys.stderr)
                failed = True
        if failed:
            return 1
        print(f'attribution check passed: {len(commits)} commit(s) in {revision}')
        return 0
    except subprocess.CalledProcessError as e:
        print('git error: ' + e.stderr.decode('utf-8', 'replace').strip(), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
