#!/usr/bin/env python3
"""Read-only scan of AgentActor creation sites (G1 single-entry gauge).

Counts, under src/main/scala (comments and definition lines excluded):
  - ctor:    `AgentActor(` construction call sites
  - factory: `agentActorBehavior(` call sites (factory mirror callers)
  - entry:   `spawnAgentActor(` call sites (the sanctioned entry)
  - direct:  `.spawn(` lines whose next 3 lines contain `AgentActor(` (raw actor spawns),
             split into `internal` (inside the sanctioned factory `spawnAgentActor`
             implementation, the one permitted site) and `external-direct` (anywhere else;
             this is the acceptance gauge and must read 0)

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
FACTORY_FILE = 'src/main/scala/nebflow/agent/SharedResources.scala'
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


def factory_span(lines):
    start = next((i + 1 for i, l in enumerate(lines) if 'def spawnAgentActor(' in l), None)
    end = next((i + 1 for i, l in enumerate(lines) if 'end spawnActor' in l), None)
    return (start, end) if start and end else (None, None)


def scan(root):
    counts = {'ctor': [], 'factory': [], 'entry': [], 'direct': [], 'external-direct': []}
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
                    site = f'{rel}:{no}'
                    counts['direct'].append(site)
                    fs, fe = factory_span(lines) if rel == FACTORY_FILE else (None, None)
                    if fs and fs <= no <= fe:
                        counts.setdefault('internal', []).append(site)
                    else:
                        counts['external-direct'].append(site)
    return counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default=str(pathlib.Path(__file__).resolve().parent.parent))
    ap.add_argument('--list', action='store_true')
    a = ap.parse_args()
    counts = scan(pathlib.Path(a.root))
    keys = ('ctor', 'factory', 'entry', 'direct', 'internal', 'external-direct')
    for k in keys:
        counts.setdefault(k, [])
        print(f'{k}={len(counts[k])}')
    if a.list:
        for k in keys:
            for site in counts[k]:
                print(f'  {k}: {site}')
    print(f'ACCEPTANCE external-direct=0: {"PASS" if not counts["external-direct"] else "FAIL"}')


if __name__ == '__main__':
    main()
