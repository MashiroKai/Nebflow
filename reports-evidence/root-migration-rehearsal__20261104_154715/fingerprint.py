#!/usr/bin/env python3
"""Tree fingerprint / manifest tool for the B1 root-migration rehearsal.

Deliberately independent of the JVM code under test: it recomputes every
digest with python hashlib so a bug inside RootLayout/AccountRoot cannot
mask itself (a JVM-vs-JVM comparison would be self-referential).

Output: TSV, one line per filesystem object, sorted by relative path.

  F <relpath> <bytes> <sha256>     regular file
  L <relpath> - <target>           symlink (target recorded verbatim, not followed)
  D <relpath> - -                  directory
  S <relpath> - <type>             socket/fifo/other (skipped, recorded as-is)

Aggregate mode (--aggregate) is an *approximation* of the digest scheme that
RootLayout.migrate uses for a directory entry (RootLayout.scala:101):

  entry_digest = sha256( "\\n".join(sorted("<relpath-under-target>:<sha256>")) )

It does NOT reproduce two edge cases: a top-level FILE entry (RootLayout uses
the file's own sha256, not the digest of its "path:sha" line) and an empty
directory entry (sha256 of the empty string). Use `07-entry-digest.py` for the
authoritative entry-level comparison -- it implements the exact scheme from a
TSV, so it stays valid after the copies have been rolled back.
"""
import argparse
import hashlib
import json
import os
import stat
import sys

CHUNK = 1 << 20


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(CHUNK)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def walk(root: str):
    """Yield (relpath, kind, bytes, payload) sorted; never follows symlinks."""
    out = []
    for dirpath, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        dirnames.sort()
        rel_dir = os.path.relpath(dirpath, root)
        if rel_dir != ".":
            out.append((rel_dir.replace(os.sep, "/"), "D", 0, "-"))
        # os.walk puts symlinked dirs into dirnames; do not descend into them.
        keep = []
        for d in sorted(dirnames):
            full = os.path.join(dirpath, d)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if os.path.islink(full):
                out.append((rel, "L", 0, os.readlink(full)))
            else:
                keep.append(d)
        dirnames[:] = keep
        for f in sorted(filenames):
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            st = os.lstat(full)
            if stat.S_ISLNK(st.st_mode):
                out.append((rel, "L", 0, os.readlink(full)))
            elif stat.S_ISREG(st.st_mode):
                out.append((rel, "F", st.st_size, sha256_of(full)))
            else:
                out.append((rel, "S", 0, oct(st.st_mode)))
    out.sort(key=lambda r: r[0])
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--out", required=True)
    ap.add_argument("--aggregate", help="also write per-top-level-entry digest JSON here")
    ap.add_argument("--skip-entry", action="append", default=[],
                    help="skip this top-level entry entirely (aggregate still lists it as SKIPPED)")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    rows = walk(root)
    n_files = sum(1 for r in rows if r[1] == "F")
    n_bytes = sum(r[2] for r in rows if r[1] == "F")

    with open(args.out, "w", encoding="utf-8") as fh:
        for rel, kind, size, payload in rows:
            fh.write(f"{kind}\t{rel}\t{size}\t{payload}\n")

    print(f"[fingerprint] root={root} objects={len(rows)} files={n_files} bytes={n_bytes} -> {args.out}")

    if args.aggregate:
        # Group file/link lines by top-level entry, using RootLayout's scheme.
        skipped = set(args.skip_entry)
        groups: dict[str, list[tuple[str, str]]] = {}
        kinds: dict[str, str] = {}
        for rel, kind, size, payload in rows:
            top = rel.split("/", 1)[0]
            if top in skipped:
                kinds.setdefault(top, "SKIPPED")
                continue
            kinds.setdefault(top, kind)
            if kind == "F":
                groups.setdefault(top, []).append((rel, payload))
        agg = {}
        for top, items in sorted(groups.items()):
            lines = sorted(f"{p}:{s}" for p, s in items)
            digest = hashlib.sha256("\n".join(lines).encode()).hexdigest()
            agg[top] = {
                "kind": "file" if kinds.get(top) == "F" else "dir",
                "bytes": sum(s for r, s in [(None, 0)]),  # filled below
                "files": len(items),
                "sha256": digest,
            }
        # byte totals per entry
        for rel, kind, size, payload in rows:
            top = rel.split("/", 1)[0]
            if top in agg:
                agg[top]["bytes"] += size
        for top in sorted(kinds):
            if top not in agg:
                agg[top] = {"kind": kinds[top], "bytes": 0, "files": 0, "sha256": "n/a"}
        with open(args.aggregate, "w", encoding="utf-8") as fh:
            json.dump(agg, fh, indent=1, sort_keys=True)
        print(f"[fingerprint] aggregate entries={len(agg)} -> {args.aggregate}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
