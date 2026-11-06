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


def prune_plan(entries: list[dict], min_age_seconds: int = 30 * 86400) -> dict:
    """Build a conservative, explanatory plan. This function never mutates files.

    Only old files beneath tmp/ or tool-results/ can be proposed, and even those
    are marked review-required: no reference graph or user-open state is known.
    Duplicate hashes are evidence only, never deletion authority.
    """
    rows = []
    protected_prefixes = ("projects/", "docs/", "reports/", "reports-evidence/", "evidence/",
                          "uploads/", "transfer/", "work/", "worktrees/", "sessions/",
                          "history/", "delegates/", "chains/")
    for entry in entries:
        path = entry["path"]
        protected = path.startswith(protected_prefixes) or "/.nebflow/worktrees/" in path
        eligible_root = path.split("/", 1)[0] in {"tmp", "tool-results"}
        old = entry["ageSeconds"] >= min_age_seconds
        reason = "protected-by-policy" if protected else (
            "not-in-temporary-root" if not eligible_root else
            "too-young" if not old else "review-required-reference-and-open-state-unknown")
        rows.append({"path": path, "sizeBytes": entry["sizeBytes"], "ageSeconds": entry["ageSeconds"],
                     "sha256": entry["sha256"], "decision": "never-auto-delete", "reason": reason})
    groups: dict[str, list[str]] = {}
    for entry in entries:
        groups.setdefault(entry["sha256"], []).append(entry["path"])
    duplicates = [{"sha256": digest, "paths": paths, "copies": len(paths)}
                  for digest, paths in sorted(groups.items()) if len(paths) > 1]
    return {"mode": "read-only-plan", "defaultAction": "none", "minAgeSeconds": min_age_seconds,
            "entries": rows, "duplicateContentEvidenceOnly": duplicates,
            "warning": "No delete action exists. Unknown references, active work, Pop-open state, and ownership require human review."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="storage root to inventory")
    parser.add_argument("--json", action="store_true", help="emit JSON manifest")
    parser.add_argument("--plan-prune", action="store_true", help="emit a read-only, never-executing review plan")
    parser.add_argument("--min-age-days", type=int, default=30, help="minimum age for temporary-root review (default: 30)")
    args = parser.parse_args()
    if args.min_age_days < 0:
        parser.error("--min-age-days must be non-negative")
    root = args.root.expanduser().resolve(strict=True)
    entries = inventory(root)
    if args.plan_prune:
        plan = prune_plan(entries, args.min_age_days * 86400)
        plan["root"] = str(root)
        print(json.dumps(plan, indent=2) if args.json else
              f"Read-only plan: {root}; files={len(entries)}; proposed deletions=0; review-required candidates="
              f"{sum(row['reason'].startswith('review-required') for row in plan['entries'])}; duplicates="
              f"{len(plan['duplicateContentEvidenceOnly'])}. No files changed.")
    elif args.json:
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
