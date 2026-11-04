#!/usr/bin/env python3
"""Compare two fingerprint.tsv manifests (kind/relpath/bytes/digest per object).

Used three times in the rehearsal:
  * crash-run containment : replica baseline vs after the aborted migrate
                            (originals must be byte-identical; only layout-managed
                            paths may legitimately appear/disappear)
  * migrate integrity     : replica baseline vs after a complete migrate
                            (copy semantics => originals byte-identical)
  * rollback integrity    : replica baseline vs after rollback
                            (== baseline again, with layout-managed paths gone)

Exit 0 only when every non-ignored object is identical.
"""
import sys


def load(path):
    rows = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            kind, rel, size, payload = line.rstrip("\n").split("\t", 3)
            rows[rel] = (kind, int(size), payload)
    return rows


def main() -> int:
    before_path, after_path = sys.argv[1], sys.argv[2]
    ignore = sys.argv[3:]
    before, after = load(before_path), load(after_path)

    def ignored(rel: str) -> bool:
        return any(rel == p or rel.startswith(p.rstrip("/") + "/") for p in ignore)

    added = [r for r in after if r not in before and not ignored(r)]
    removed = [r for r in before if r not in after and not ignored(r)]
    changed = [r for r in before if r in after and before[r] != after[r] and not ignored(r)]
    ignored_added = [r for r in after if r not in before and ignored(r)]
    ignored_removed = [r for r in before if r not in after and ignored(r)]

    print(f"[diff] before={len(before)} after={len(after)}")
    print(f"[diff] ignored-prefixes={ignore}")
    print(f"[diff] changed={len(changed)} added={len(added)} removed={len(removed)}")
    print(f"[diff] layout-managed appeared={len(ignored_added)} disappeared={len(ignored_removed)}")
    for label, items in (("CHANGED", changed), ("ADDED", added), ("REMOVED", removed)):
        for rel in items[:20]:
            print(f"   {label}: {rel}")
    if ignored_added[:5]:
        print(f"   (managed, ignored) e.g. {ignored_added[:3]}")
    ok = not (added or removed or changed)
    print("[diff] PASS — every non-managed object is byte-identical" if ok else "[diff] FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
