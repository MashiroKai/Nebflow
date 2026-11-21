#!/usr/bin/env python3
"""Read-only scan of AgentActor creation sites (G1 single-entry gauge).

Counts, under src/main/scala (comments and definition lines excluded):
  - ctor:    `AgentActor(` construction call sites
  - factory: `agentActorBehavior(` call sites (factory mirror callers)
  - entry:   `spawnAgentActor(` call sites (the sanctioned entry)
  - direct:  `.spawn(` lines whose next 3 lines contain `AgentActor(` (raw actor spawns)

Usage: python3 scripts/agent-spawn-scan.py [--root <repo>] [--list]
Exit 0 always (gauge, not a gate).
"""
import argparse
import pathlib
import re

CTOR = re.compile(r'(?<![\w.])AgentActor\(')
FACTORY = re.compile(r'\bagentActorBehavior\(')
ENTRY = re.compile(r'\bspawnAgentActor\(')
SPAWN = re.compile(r'\.spawn\(')
DEF_LINE = re.compile(r'^\s*(def |object |class |case class |final case class |trait |private\[\w+\] def )')


def code_lines(text):
    in_block = False
    for no, raw in enumerate(text.splitlines(), 1):
        if in_block:
            if '*/' in raw:
                in_block = False
            continue
        s = raw.strip()
        if s.startswith('/*') and '*/' not in s:
            in_block = True
            continue
        if s.startswith('//') or s.startswith('*') or s.startswith('/**'):
            continue
        yield no, raw.split('//')[0]


def scan(root):
    counts = {'ctor': [], 'factory': [], 'entry': [], 'direct': []}
    base = root / 'src' / 'main' / 'scala'
    for path in sorted(base.rglob('*.scala')):
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding='utf-8', errors='replace')
        lines = text.splitlines()
        for no, code in code_lines(text):
            if DEF_LINE.match(code):
                continue
            if CTOR.search(code):
                counts['ctor'].append(f'{rel}:{no}')
            if FACTORY.search(code):
                counts['factory'].append(f'{rel}:{no}')
            if ENTRY.search(code):
                counts['entry'].append(f'{rel}:{no}')
            if SPAWN.search(code):
                window = '\n'.join(lines[no - 1:no + 3])
                if CTOR.search(window):
                    counts['direct'].append(f'{rel}:{no}')
    return counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default=str(pathlib.Path(__file__).resolve().parent.parent))
    ap.add_argument('--list', action='store_true')
    a = ap.parse_args()
    counts = scan(pathlib.Path(a.root))
    for k in ('ctor', 'factory', 'entry', 'direct'):
        print(f'{k}={len(counts[k])}')
        if a.list:
            for site in counts[k]:
                print(f'  {k}: {site}')


if __name__ == '__main__':
    main()
