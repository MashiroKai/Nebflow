#!/usr/bin/env python3
"""Manage project worktrees and audit temporary delivery artifacts."""
import argparse
import datetime as dt
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def git(*args, cwd=None):
    return subprocess.check_output(['git', *map(str, args)], cwd=cwd, stderr=subprocess.PIPE).decode().strip()


def root():
    # The first entry is the main checkout, including when invoked from a linked worktree.
    return Path(git('worktree', 'list', '--porcelain').splitlines()[0][9:])


def name(value):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', value) or value in ('.', '..', 'worktree-audit'):
        raise ValueError('invalid worktree name')
    return value


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inventory(directory):
    result = []
    for p in sorted(directory.rglob('*')):
        if p.is_symlink():
            raise ValueError(f'symlink forbidden: {p}')
        if p.is_file() and p != directory / 'MANIFEST.md':
            result.append(p)
    return result


def audit(project, wt):
    directory = project / '.stand' / 'tmp' / name(wt)
    if directory.is_symlink() or (directory / 'MANIFEST.md').is_symlink() or not directory.is_dir():
        raise ValueError(f'not a safe tmp directory: {directory}')
    # Use the named worktree's committed HEAD, not the main checkout's branch.
    checkout = project / '.stand' / 'worktrees' / wt
    checkout = checkout if (checkout / '.git').is_file() else project
    lines = ['# Artifact manifest', '', '| Relative path | Bytes | mtime | Status |', '| --- | ---: | --- | --- |']
    counts = [0, 0]
    for path in inventory(directory):
        rel = path.relative_to(directory).as_posix()
        if '|' in rel or '\n' in rel or '\r' in rel:
            raise ValueError(f'unsupported filename: {rel!r}')
        status = '未入库'
        if rel.startswith('delivery/') and rel.count('/') >= 2:
            target = rel
            try:
                committed = subprocess.check_output(['git', 'show', f'HEAD:{target}'], cwd=checkout, stderr=subprocess.DEVNULL)
                if digest(path.read_bytes()) == digest(committed):
                    commit = git('log', '-1', '--format=%H', 'HEAD', '--', target, cwd=checkout)
                    stamp = git('show', '-s', '--format=%ci', commit, cwd=checkout)[:16]
                    status = f'入库 {stamp} {commit}'
            except subprocess.CalledProcessError:
                pass
        counts[status != '未入库'] += 1
        stat = path.stat()
        mtime = dt.datetime.fromtimestamp(stat.st_mtime).isoformat(timespec='seconds')
        lines.append(f'| {rel} | {stat.st_size} | {mtime} | {status} |')
    (directory / 'MANIFEST.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', '--project', type=Path, help='project checkout path (default: current working directory); resolves its primary checkout')
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('new'); p.add_argument('name', type=name); p.add_argument('--branch')
    sub.add_parser('list')
    p = sub.add_parser('audit'); p.add_argument('name', nargs='?', type=name)
    p = sub.add_parser('clean'); p.add_argument('--ttl-days', type=float, default=7); p.add_argument('--apply', action='store_true'); p.add_argument('--force', action='store_true'); p.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if args.root is not None:
        os.chdir(args.root)
    project = root(); tmp = project / '.stand' / 'tmp'
    if args.command == 'new':
        target = project / '.stand' / 'worktrees' / args.name
        branch = args.branch or 'feat/' + args.name
        target.parent.mkdir(parents=True, exist_ok=True)
        exists = subprocess.run(['git', 'show-ref', '--verify', '--quiet', 'refs/heads/' + branch], cwd=project).returncode == 0
        subprocess.run(['git', 'worktree', 'add', str(target)] + ([branch] if exists else ['-b', branch]), cwd=project, check=True)
        for part in ('delivery', 'evidence'):
            (tmp / args.name / part).mkdir(parents=True, exist_ok=True)
        audit(project, args.name)
        print(f'Created {target}; artifacts {tmp / args.name}')
    elif args.command == 'list':
        for record in git('worktree', 'list', '--porcelain').split('\n\n'):
            fields = record.splitlines(); path = Path(fields[0][9:]); wt = path.name
            branch = next((x[7:].removeprefix('refs/heads/') for x in fields if x.startswith('branch ')), '(detached)')
            head = next((x[5:] for x in fields if x.startswith('HEAD ')), '?')
            try:
                dirty = bool(git('status', '--porcelain', cwd=path))
            except (subprocess.CalledProcessError, FileNotFoundError):
                dirty = 'unknown'
            directory = tmp / wt
            if directory.is_dir() and not directory.is_symlink():
                uncommitted, committed = audit(project, wt)
                counts = f'artifacts={uncommitted + committed} committed={committed} uncommitted={uncommitted}'
            else:
                counts = 'artifacts=0 committed=0 uncommitted=0'
            print(f'{wt} path={path} branch={branch} HEAD={head} dirty={dirty} {counts}')
    elif args.command == 'audit':
        dirs = [tmp / args.name] if args.name else sorted(tmp.iterdir()) if tmp.exists() else []
        for directory in dirs:
            if directory.name != 'worktree-audit' and directory.is_dir() and not directory.is_symlink():
                uncommitted, committed = audit(project, directory.name)
                print(f'{directory.name}: committed={committed} uncommitted={uncommitted}')
    else:
        if args.ttl_days < 0 or (args.apply and args.dry_run):
            raise ValueError('invalid TTL or conflicting apply/dry-run')
        candidates = []
        for directory in sorted(tmp.iterdir()) if tmp.exists() else []:
            if directory.name == 'worktree-audit' or directory.is_symlink() or not directory.is_dir():
                continue
            # Exclude only the generated root manifest, not artifacts of the same name.
            entries = [directory] + [p for p in directory.rglob('*') if p != directory / 'MANIFEST.md']
            if any(p.is_symlink() for p in entries):
                print(f'WARNING unsafe symlink: {directory}'); continue
            latest = max(p.stat().st_mtime for p in entries)
            if time.time() - latest <= args.ttl_days * 86400:
                continue
            uncommitted, _ = audit(project, directory.name)
            if uncommitted and not args.force:
                print(f'WARNING retained {directory}: {uncommitted} uncommitted'); continue
            candidates.append(directory)
        for directory in candidates:
            print(f'{"DELETE" if args.apply else "DRY-RUN"} {directory}')
        if args.apply and candidates:
            audit_base = tmp / 'worktree-audit'
            if audit_base.is_symlink():
                raise ValueError(f'unsafe audit directory: {audit_base}')
            audit_base.mkdir(exist_ok=True)
            stamp = dt.datetime.now().strftime('%Y%m%d_%H%M%S_%f')
            audit_dir = audit_base / stamp
            audit_dir.mkdir()
            listing = '\n'.join(str(p) for p in candidates)
            for directory in candidates:
                report = audit_dir / f'clean-{directory.name}.txt'
                report.write_text(f'Candidates:\n{listing}\n\nManifest for {directory}:\n' + (directory / 'MANIFEST.md').read_text(encoding='utf-8'), encoding='utf-8')
                print(f'AUDIT {report}')
            for directory in candidates:
                shutil.rmtree(directory)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print(f'Error: {exc}', file=sys.stderr)
        sys.exit(2)
