#!/usr/bin/env python3
"""Read-only inventory of Stand artifact storage; never deletes or mutates files."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pwd
import stat
import time
from pathlib import Path

CATEGORIES = {
    "source-documents": ("docs", "projects"),
    "reports": ("reports",),
    "user-delivery": ("uploads", "transfer"),
    "evidence": ("reports-evidence", "evidence"),
    "temporary-output": ("tmp", "tool-results"),
    "worktree": ("work", "worktrees"),
    "runtime-cache": ("cache", "imgcache", "search-index"),
    "runtime-state": ("sessions", "history", "delegates", "chains", "subagent-tasks"),
}


def classify(relative: str) -> str:
    first = relative.split(os.sep, 1)[0]
    for category, roots in CATEGORIES.items():
        if first in roots:
            return category
    return "other"


def inventory(root: Path, now: float | None = None) -> list[dict]:
    now = time.time() if now is None else now
    entries = []
    for base, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = [d for d in dirs if not (Path(base) / d).is_symlink()]
        for name in files:
            path = Path(base) / name
            try:
                info = path.lstat()
                if not stat.S_ISREG(info.st_mode):
                    continue
                digest = hashlib.sha256()
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                rel = path.relative_to(root).as_posix()
                entries.append({
                    "path": rel,
                    "category": classify(rel),
                    "sizeBytes": info.st_size,
                    "ageSeconds": max(0, int(now - info.st_mtime)),
                    "owner": pwd.getpwuid(info.st_uid).pw_name if hasattr(pwd, "getpwuid") else str(info.st_uid),
                    "status": "inventory-only",
                    "referenced": "unknown",
                    "sha256": digest.hexdigest(),
                })
            except (OSError, PermissionError):
                continue
    return sorted(entries, key=lambda item: item["path"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="storage root to inventory")
    parser.add_argument("--json", action="store_true", help="emit JSON manifest")
    args = parser.parse_args()
    root = args.root.expanduser().resolve(strict=True)
    entries = inventory(root)
    if args.json:
        print(json.dumps({"root": str(root), "mode": "read-only", "entries": entries}, indent=2))
    else:
        totals: dict[str, int] = {}
        for entry in entries:
            totals[entry["category"]] = totals.get(entry["category"], 0) + entry["sizeBytes"]
        print(f"Read-only inventory: {root} ({len(entries)} files)")
        for category, size in sorted(totals.items()):
            print(f"{category:20} {size:12,d} bytes")
        print("No files changed. References and lifecycle status require an external owner/index; reported as unknown.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
