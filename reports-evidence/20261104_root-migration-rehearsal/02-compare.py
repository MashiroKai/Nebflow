#!/usr/bin/env python3
"""Cross-check migration-report.json against an INDEPENDENT python fingerprint.

Two checks, both deliberately outside the JVM:
  1. entry digests: report.entries[].sha256 must equal the aggregate digest that
     fingerprint.py computed from the ORIGINAL tree before migrate ran
     (same scheme as RootLayout.scala:100-101).
  2. file digests: every report.files[] record must exist under the migration
     target with a matching sha256, and the target must contain no file that the
     manifest does not list (completeness in both directions).
Exit 0 = all consistent.
"""
import hashlib
import json
import os
import sys

CHUNK = 1 << 20


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    report_path, agg_path, target = sys.argv[1], sys.argv[2], sys.argv[3]
    report = json.load(open(report_path))
    agg = json.load(open(agg_path))
    problems = []

    # ── 1. entry digests vs independent pre-migrate fingerprint ────────────
    entries = report.get("entries", [])
    print(f"[cmp] report entries={len(entries)} aggregate entries={len(agg)}")
    names_report = {e["name"] for e in entries}
    names_agg = set(agg)
    if names_report != names_agg:
        problems.append(f"entry name sets differ: only-in-report={sorted(names_report - names_agg)[:5]} "
                        f"only-in-agg={sorted(names_agg - names_report)[:5]}")
    for e in entries:
        a = agg.get(e["name"])
        if a is None:
            continue
        if a["kind"] != e["kind"]:
            problems.append(f"{e['name']}: kind report={e['kind']} agg={a['kind']}")
        if a["sha256"] != e["sha256"]:
            problems.append(f"{e['name']}: entry sha256 report={e['sha256'][:12]} agg={a['sha256'][:12]}")
        if a["bytes"] != e["bytes"]:
            problems.append(f"{e['name']}: bytes report={e['bytes']} agg={a['bytes']}")
    matched = len(entries) - len([p for p in problems if p[0] != "e" or ":" in p and "entry sha256" in p])
    print(f"[cmp] entry digest mismatches: {len([p for p in problems if 'entry sha256' in p or 'bytes' in p])}")

    # ── 2. file digests vs the produced tree (both directions) ─────────────
    files = report.get("files", [])
    print(f"[cmp] report files={len(files)}")
    bad = 0
    for i, f in enumerate(files):
        p = os.path.join(target, f["path"])
        if not os.path.exists(p):
            problems.append(f"missing copy: {f['path']}")
            bad += 1
        else:
            got = sha256_of(p)
            if got != f["sha256"]:
                problems.append(f"sha256 mismatch: {f['path']} manifest={f['sha256'][:12]} actual={got[:12]}")
                bad += 1
        if (i + 1) % 100000 == 0:
            print(f"[cmp]   verified {i + 1}/{len(files)} files…", flush=True)
    print(f"[cmp] file digest mismatches: {bad}")

    listed = {f["path"] for f in files}
    extra = 0
    base_paths = set()
    for rel in listed:
        top = rel.split("/", 1)[0]
        if top not in base_paths and top in names_report:
            base_paths.add(top)
    for root, _dirs, fnames in os.walk(target):
        for fn in fnames:
            rel = os.path.relpath(os.path.join(root, fn), target).replace(os.sep, "/")
            if rel not in listed:
                extra += 1
                if extra <= 5:
                    problems.append(f"unlisted file in target: {rel}")
    print(f"[cmp] unlisted files in target: {extra}")

    if problems:
        print(f"\n[cmp] FAIL — {len(problems)} problem(s):")
        for p in problems[:40]:
            print("   " + p)
        return 1
    print("\n[cmp] PASS — entry digests and every file digest match; target == manifest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
