#!/bin/bash
# B1 root-migration rehearsal — step 1: build the replica of the REAL data root.
#
# IRON RULE: the real root /Users/kaiyu/.stand is READ-ONLY here. Nothing in this
# script writes to it (rsync source side only; no --delete, no metadata writes).
#
# Venue: /Volumes/rehearsal = APFS volume inside a sparse bundle hosted on
# /Volumes/KINGSTON (external, 630 GiB free). The internal disk has only ~11 GiB
# free at 99% capacity, so a 85 GiB replica CANNOT be placed there without
# endangering the running production instance. APFS-in-a-bundle keeps POSIX
# perms + symlinks intact, so migrate() sees the same filesystem semantics it
# would see on the real root.
set -uo pipefail

SRC=/Users/kaiyu/.stand
DST=/Volumes/rehearsal/root
EVID=/Volumes/KINGSTON/rehearsal-evidence

mkdir -p "$DST" "$EVID"

# ── Top-level entries EXCLUDED from the replica, and why ────────────────────
# (a) secret hygiene: the brief forbids tokens/keys reaching any file I create;
#     a replica is a file I create. These entries are credentials or configs that
#     embed credentials (nebflow.json: 5 key-shaped matches).
# (b) liveness: pid/lock files of the RUNNING production instance — copied copies
#     serve no rehearsal purpose (the brief also says: do not touch ~/.stand/run).
EXCLUDE_TOP=(
  secrets lt-secrets secrets-catalog.md
  auth.json device.json client-id
  feishu.json gateway-sessions.json .seed-state.json
  nebflow.json nebflow.json.bak
  directmail.env vercel-token.env vps.env
  logto-m2m-sync.env logto-registration.env logto-webapi.env logto-webapp.env
  neblink-staging-logto-app.env
  mcp plugin-data
  .pid .pid.meta nebflow.pid run shutdown-marker.json
  daemons.json daemon-pids.json.tmp.078a64c9-f79d-4845-a78a-1e9e5df95d87
  .DS_Store .gitignore .git
)
# note: daemons.json.bak-* / model-presets.json* / install-manifest.json.bak-* /
# nebflow.json.bak-* are matched by the glob excludes below.

# ── Sub-tree prunes (content volume only; every top-level ENTRY still present) ─
# Migration moves a whole top-level entry, so entry-level coverage is preserved;
# these prune leaf volumes whose content is irrelevant to copy/digest semantics
# (and which are exactly the categories the brief allows dropping).
EXCLUDE_SUB=(
  node_modules worktrees .git target .vercel .next .turbo
  playwright-report test-results .cache
)

EXCL_FILE="$EVID/rsync-excludes.txt"
: > "$EXCL_FILE"
for p in "${EXCLUDE_SUB[@]}"; do echo "$p" >> "$EXCL_FILE"; done
for p in 'nebflow.json.bak*' 'model-presets.json*' 'model-chains.json' 'models.json' \
         'daemons.json.bak*' 'install-manifest.json.bak*' '*.env' '*.sparsebundle'; do
  echo "$p" >> "$EXCL_FILE"
done

# ── Compute the entry allowlist from the real root (read-only) ──────────────
TOPLIST="$EVID/toplist.txt"
: > "$TOPLIST"
cd "$SRC" || exit 1
shopt -s nullglob dotglob
for entry in *; do
  skip=0
  for x in "${EXCLUDE_TOP[@]}"; do [ "$entry" = "$x" ] && skip=1 && break; done
  [ "$skip" = 1 ] && continue
  # glob-shaped top-level exclusions
  case "$entry" in
    nebflow.json.bak*|model-presets.json*|model-chains.json|models.json) continue;;
    daemons.json.bak*|install-manifest.json.bak*|*.env) continue;;
  esac
  echo "$entry" >> "$TOPLIST"
done
echo "[copy] replicating $(wc -l < "$TOPLIST" | tr -d ' ') top-level entries"

START=$(date +%s)
# -a preserves perms/symlinks/times; no -H/-A/-X (xattrs are host noise);
# --exclude-from applies the sub-tree prunes. No --delete of any kind.
# Sources are passed explicitly (openrsync has no --files-from), so each listed
# top-level entry lands in DST under its own name.
args=()
while IFS= read -r e; do args+=("$SRC/$e"); done < "$TOPLIST"
rsync -a --exclude-from="$EXCL_FILE" "${args[@]}" "$DST/"
RC=$?
END=$(date +%s)
echo "[copy] rsync exit=$RC elapsed=$((END-START))s"
echo "$RC" > "$EVID/rsync-rc.txt"

du -sh "$DST"
find "$DST" -type f | wc -l
find "$DST" -type l | wc -l
exit $RC
