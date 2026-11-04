#!/usr/bin/env python3
"""Real-root before/after comparison -> the "nothing was touched" proof.

The production engine is running throughout the rehearsal, so the data root
legitimately churns while we work: the engine appends to logs/, rewrites
sessions/, task ledgers, search index, etc. So the proof is not "zero diffs" —
it is:
  (a) NO layout-managed name appeared anywhere (no migration happened);
  (b) every diff is confined to entries the LIVE ENGINE itself writes;
  (c) sampled deep content of non-live entries is byte-identical.
This script reports all three, and lists every changed RELATIVE PATH grouped by
top-level entry so the classification is auditable rather than asserted.
"""
import sys
from collections import Counter, defaultdict

LIVE_TOP = {
    "logs", "llm-queue", "sessions", "search-index", "tool-results", "tmp",
    "general", "history", "tasks-v2.json", "tasks.json", "tasks-v2-history.jsonl",
    "tasks-history.jsonl", "usage-log.jsonl", "usage-pattern.json", "usage-records",
    "memory", "memory.md", "memory-db", "memory-archive", "mail-dedup.json",
    "mailbox-reconcile.json", "pending-asks.json", "merge-ledger.json", "chains",
    "delegates", "subagent-tasks", "flows", "bg-task-logs", "browser-use",
    "browser-data", "imgcache", "cache", "plugin-data", ".plugin-presence.json",
    ".stats.json", "base-drift.json", "gateway-sessions.json", ".pid", ".pid.meta",
    "nebflow.pid", "daemons.json", "peer-descriptions.json", "onboarding.json",
    "preferences.json", "reminders", "scheduled-tasks", "workspace-items",
    "portal-uplink-deadletter", "media", "uploads", "attach-uploads",
    "canvas_tabs.json", "input_history.jsonl", "shutdown-marker.json",
    "device.json", "profile-db", "folders", "ask", "checkin-records",
    "weekly-report", "plots", "work", "transfer", "evidence", "flows",
}
MANAGED = {"accounts", "unauthenticated", "pending-claim", "active-account",
           "layout.json", "migration-report.json"}


def load(path):
    rows = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            kind, rel, size, payload = line.rstrip("\n").split("\t", 3)
            rows[rel] = (kind, int(size), payload)
    return rows


def main() -> int:
    before_path, after_path = sys.argv[1], sys.argv[2]
    before, after = load(before_path), load(after_path)
    print(f"[real] before objects={len(before)}  after objects={len(after)}")

    managed = [r for r in list(after) if r.split("/")[0] in MANAGED]
    print(f"[real] (a) layout-managed paths present in the AFTER snapshot: {len(managed)} {managed[:5]}")

    added = [r for r in after if r not in before]
    removed = [r for r in before if r not in after]
    changed = [r for r in before if r in after and before[r] != after[r]]
    print(f"[real] added={len(added)} removed={len(removed)} changed={len(changed)}")

    by_top = Counter()
    for rel in added + removed + changed:
        by_top[rel.split("/")[0]] += 1
    live_hits = {t: n for t, n in by_top.items() if t in LIVE_TOP}
    nonlive = {t: n for t, n in by_top.items() if t not in LIVE_TOP}
    print(f"[real] (b) diffs confined to engine-live entries: {len(nonlive) == 0}")
    print(f"[real]     live-entry diffs: {len(live_hits)} entries, {sum(live_hits.values())} objects")
    for t, n in sorted(live_hits.items(), key=lambda kv: -kv[1])[:12]:
        print(f"           {n:6d}  {t}")
    if nonlive:
        print(f"[real]     !! NON-LIVE entries with diffs ({len(nonlive)}):")
        for t, n in sorted(nonlive.items(), key=lambda kv: -kv[1])[:20]:
            ex = [r for r in added + removed + changed if r.split("/")[0] == t][:3]
            print(f"           {n:6d}  {t}   e.g. {ex}")

    # (c) byte-identity of files in entries that are NOT engine-live and NOT managed
    checked = identical = 0
    mism = []
    for rel, (kind, size, payload) in before.items():
        if kind != "F":
            continue
        top = rel.split("/")[0]
        if top in LIVE_TOP or top in MANAGED:
            continue
        checked += 1
        b = after.get(rel)
        if b is not None and b[2] == payload:
            identical += 1
        else:
            mism.append(rel)
    print(f"[real] (c) non-live files compared={checked} byte-identical={identical} mismatched={len(mism)}")
    for r in mism[:10]:
        print(f"           MISMATCH: {r}")

    ok = not managed and not nonlive and not mism
    print("[real] PASS — real root shows no migration artifacts and no change outside engine-live entries"
          if ok else "[real] REVIEW — see above")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
