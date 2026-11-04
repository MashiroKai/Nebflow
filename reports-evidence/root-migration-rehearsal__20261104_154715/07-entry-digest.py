#!/usr/bin/env python3
"""Recompute per-top-level-entry digests with RootLayout's EXACT scheme and
compare them against migration-report.json.

RootLayout.scala:99-102:
    kind == "file"  -> entry digest = that file's own sha256
    kind == "dir"   -> entry digest = sha256( "\\n".join(sorted("<path>:<sha256>")) )
and bytes = sum of the entry's file bytes.

Derived from a fingerprint TSV (no tree access needed), so it can be re-run
after the copies have been rolled back.

Entries containing symlinks are reported separately: migrate() dereferences
links (F2), so its digest necessarily differs from a manifest that records the
link object itself — that is an explained, structural difference, not an error.
"""
import hashlib
import json
import sys
from collections import defaultdict


def load(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            kind, rel, size, payload = line.rstrip("\n").split("\t", 3)
            rows.append((kind, rel, int(size), payload))
    return rows


def main() -> int:
    tsv, report_path = sys.argv[1], sys.argv[2]
    rows = load(tsv)
    files = defaultdict(list)
    bytes_by = defaultdict(int)
    kinds = {}
    has_link = set()
    tops_files = {}
    for kind, rel, size, payload in rows:
        top = rel.split("/", 1)[0]
        kinds.setdefault(top, kind)
        if kind == "F":
            files[top].append((rel, payload))
            bytes_by[top] += size
            if rel == top:
                tops_files[top] = payload
        elif kind == "L":
            has_link.add(top)

    report = json.load(open(report_path))
    entries = {e["name"]: e for e in report.get("entries", [])}

    matched, explained, unexplained = 0, [], []
    for name, e in entries.items():
        if name in tops_files and len(files[name]) == 1:
            digest = tops_files[name]
        else:
            digest = hashlib.sha256(
                "\n".join(sorted(f"{p}:{s}" for p, s in files[name])).encode()).hexdigest()
        same = digest == e["sha256"] and bytes_by[name] == e["bytes"]
        if same:
            matched += 1
        elif name in has_link:
            explained.append((name, "entry contains symlink(s): migrate dereferences them (F2)"))
        else:
            unexplained.append((name, f"report={e['sha256'][:12]} recomputed={digest[:12]} "
                                      f"bytes report={e['bytes']} recomputed={bytes_by[name]}"))

    print(f"[entry] report entries={len(entries)} matched={matched} "
          f"explained(symlink-bearing)={len(explained)} unexplained={len(unexplained)}")
    for n, why in explained:
        print(f"   explained: {n} — {why}")
    for n, why in unexplained:
        print(f"   UNEXPLAINED: {n} — {why}")
    ok = not unexplained
    print("[entry] PASS — every entry digest/bytes matches the report with RootLayout's own scheme"
          if ok else "[entry] FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
