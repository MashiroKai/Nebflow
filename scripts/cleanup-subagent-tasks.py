#!/usr/bin/env python3
"""One-shot cleanup for sub-agent task records left broken by the status bug.

Bug context: BackoffSupervisor hardcoded "" as parentSessionId in its
updateStatus calls, so completion updates landed in a bogus
~/.nebflow/subagent-tasks/.json file while the real parent session's task
files kept their tasks in "running" forever.

WHEN TO RUN: only AFTER the fixed Nebflow build has been restarted. A still-
running unfixed instance keeps writing .json litter, so running this earlier
is pointless.

Actions:
 1. Delete the bogus .json litter file (if present).
 2. For every remaining <parentSessionId>.json: tasks stuck in "running"
    whose spawnedAt is older than the grace window are marked "failed" with
    lastError "stale: status tracking was broken before fix" — the parent
    agents of those sessions are long gone; surfacing them as failed keeps
    the history honest without resurrecting anything.

Dry run: pass --dry-run to print what would change without writing.
"""

import glob
import json
import os
import sys
import time

BASE = os.path.expanduser("~/.nebflow/subagent-tasks")
STALE_ERROR = "stale: status tracking was broken before fix"
# Tasks spawned within this window may belong to the fixed instance (which
# updates them correctly) — leave those alone.
GRACE_MS = 30 * 60 * 1000

DRY_RUN = "--dry-run" in sys.argv


def main() -> int:
    if not os.path.isdir(BASE):
        print(f"no task dir at {BASE}; nothing to do")
        return 0

    litter = os.path.join(BASE, ".json")
    if os.path.exists(litter):
        if DRY_RUN:
            print("would remove bogus .json litter file")
        else:
            os.remove(litter)
            print("removed bogus .json litter file")

    now = int(time.time() * 1000)
    changed = 0
    for f in sorted(glob.glob(os.path.join(BASE, "*.json"))):
        name = os.path.basename(f)
        if name == ".json":
            continue
        try:
            with open(f) as fh:
                tasks = json.load(fh)
        except Exception as e:  # corrupted file — leave untouched
            print(f"WARN: cannot parse {name}: {e} — skipped", file=sys.stderr)
            continue
        dirty = False
        for t in tasks:
            if (
                t.get("status") == "running"
                and now - int(t.get("spawnedAt") or 0) > GRACE_MS
            ):
                t["status"] = "failed"
                t["lastError"] = STALE_ERROR
                t["completedAt"] = now
                dirty = True
                changed += 1
        if dirty:
            if DRY_RUN:
                print(f"would update {name}")
            else:
                with open(f, "w") as fh:
                    json.dump(tasks, fh, ensure_ascii=False)
                print(f"updated {name}")

    suffix = " (dry run)" if DRY_RUN else ""
    print(f"done{suffix}: {changed} stale running task(s) marked failed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
