#!/bin/zsh
# spec-floor sweep — runs the full web spec floor sequentially and logs PASS/FAIL per spec.
# - node-runner specs: `node tests/<f>` (self-contained, own static server + mocked WS)
# - @playwright/test specs: one `npx playwright test … --workers=1` invocation (sequential;
#   many bind fixed 8100+ ports, so no parallel workers)
# - EXCLUDED (environment-gated, live-backend): specs that READ process.env.BASE_URL —
#   they default to a live instance backend port (8080 = the protected live instance,
#   8094/8096/8300/8317/18923 = other instance backends) and would create real sessions
#   on production. The exclusion is derived at runtime (grep in the sweep loop), so the
#   list cannot drift as specs are added.
# - isolated-browser-selfproof.spec.mjs also mentions BASE_URL but only ASSIGNS it for
#   the isolated instance it boots itself (own port, never the host port) — safe to run, not excluded.
# Resolve the repo root from the script's own location (tests/ -> ..), independent of the
# caller's CWD; works the same from the main checkout and from any git worktree.
cd "${0:A:h}/.." || exit 1
LOG=/tmp/spec-floor-sweep.log
RAW=/tmp/spec-floor-raw
mkdir -p "$RAW"
: > "$LOG"

NODE_SPECS=()
PW_SPECS=()
for f in tests/*.spec.mjs; do
  b=$(basename "$f")
  # env-gated: spec reads process.env.BASE_URL, i.e. it defaults to a live-instance
  # backend port — excluded at runtime so the skip list cannot drift as specs are added
  if grep -q "process\.env\.BASE_URL" "$f"; then
    echo "SKIP(env-gated) $b" >> "$LOG"
    continue
  fi
  if grep -q "@playwright/test" "$f"; then PW_SPECS+=("$b"); else NODE_SPECS+=("$b"); fi
done

echo "=== node-runner specs: ${#NODE_SPECS} ===" >> "$LOG"
i=0
for b in "${NODE_SPECS[@]}"; do
  i=$((i+1))
  if node "tests/$b" > "$RAW/$b.log" 2>&1; then
    echo "PASS node $b" >> "$LOG"
  else
    echo "FAIL node $b (exit=$?)" >> "$LOG"
  fi
  echo "progress: $i/${#NODE_SPECS} node" >&2
done

echo "=== @playwright/test specs: ${#PW_SPECS} (one invocation, workers=1) ===" >> "$LOG"
ARGS=()
for b in "${PW_SPECS[@]}"; do ARGS+=("tests/$b"); done
npx playwright test "${ARGS[@]}" --workers=1 --reporter=line > "$RAW/pw-suite.log" 2>&1
pw_rc=$?
echo "pw-suite exit=$pw_rc" >> "$LOG"
# extract per-test verdicts from the line reporter output
grep -E "^\s+(✓|✘|[0-9]+/|[0-9]+ (passed|failed|skipped|did not run))" "$RAW/pw-suite.log" >> "$LOG" 2>/dev/null

echo "=== SUMMARY ===" >> "$LOG"
grep -c "^PASS" "$LOG" | xargs echo "node PASS count:"
grep "^FAIL" "$LOG" || echo "no node FAILs"
echo "done" >> "$LOG"
