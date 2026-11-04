#!/bin/bash
# Rehearsal phase B — de-hazard the replica, then run the COMPLETE cycle:
#   repair -> clean baseline -> migrate (full) -> independent digest verification -> rollback -> diff
set -uo pipefail
R=/Volumes/rehearsal/root
E=/Volumes/KINGSTON/rehearsal-evidence
EV="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-24f88015/reports-evidence/20261104_root-migration-rehearsal"
L="$EV/logs"
CP=$(cat "$E/classpath.txt")
cd "$EV" || exit 1
# NOTE: $EV contains a space ("Claude code") — every java invocation must quote it.
run_java() {
  STAND_HOME=/Volumes/rehearsal/ignore-home NEBFLOW_HOME=/Volumes/rehearsal/ignore-home \
  java -Xmx10g "-Dlogback.configurationFile=$EV/logback-driver.xml" -cp "$CP" "$@"
}

echo "== [B1] drop the partial copy left by the aborted run ==" | tee "$L/06-phase-b.log"
rm -rf "$R/pending-claim"

echo "== [B2] de-hazard the REPLICA (broken/outside symlinks only; real root untouched) ==" | tee -a "$L/06-phase-b.log"
python3 03-census-repair.py "$R" --apply > "$L/06-repair.log" 2>&1
grep -E '"(total|ok_in_root_resolvable|broken|socket|outside|stat_fail)"|\[repair\]' "$L/06-repair.log" | tee -a "$L/06-phase-b.log"

echo "== [B3] clean baseline fingerprint ==" | tee -a "$L/06-phase-b.log"
python3 fingerprint.py "$R" --out "$E/replica-clean.tsv" --aggregate "$E/replica-clean-agg.json" > "$L/06-fingerprint-clean.log" 2>&1
cat "$L/06-fingerprint-clean.log" | tee -a "$L/06-phase-b.log"

echo "== [B4] FULL migrate on the de-hazarded replica ==" | tee -a "$L/06-phase-b.log"
START=$(date +%s)
run_java nebflow.core.dataroot.RehearsalDriver bigroot "$R" --skip-rollback > "$L/06-migrate.log" 2>&1
echo "[B4] driver exit=$? wall=$(( $(date +%s) - START ))s" | tee -a "$L/06-phase-b.log"
grep -E "MIGRATE finished|pendingClaim=|target=|movedEntries=|migration-report|startup path|PASS|FAIL" "$L/06-migrate.log" | tee -a "$L/06-phase-b.log"

TARGET=$(grep -o 'target=.*' "$L/06-migrate.log" | head -1 | sed 's/^target=//')
echo "[B4] target=$TARGET" | tee -a "$L/06-phase-b.log"
echo "$TARGET" > "$E/migrate-target.txt"

echo "== [B5] independent digest verification (report vs python manifest) ==" | tee -a "$L/06-phase-b.log"
python3 02-compare.py "$R/migration-report.json" "$E/replica-clean-agg.json" "$TARGET" > "$L/06-compare.log" 2>&1
echo "[B5] compare exit=$?" | tee -a "$L/06-phase-b.log"
tail -12 "$L/06-compare.log" | tee -a "$L/06-phase-b.log"

echo "== [B6] rollback (from the on-disk report) ==" | tee -a "$L/06-phase-b.log"
START=$(date +%s)
run_java nebflow.core.dataroot.RehearsalDriver rollbackonly "$R" > "$L/07-rollback.log" 2>&1
echo "[B6] driver exit=$? wall=$(( $(date +%s) - START ))s" | tee -a "$L/06-phase-b.log"
grep -E "ROLLBACK finished|PASS|FAIL|report=" "$L/07-rollback.log" | tee -a "$L/06-phase-b.log"

echo "== [B7] post-rollback fingerprint + diff against clean baseline ==" | tee -a "$L/06-phase-b.log"
python3 fingerprint.py "$R" --out "$E/replica-after-rollback.tsv" > "$L/07-fingerprint-after-rollback.log" 2>&1
cat "$L/07-fingerprint-after-rollback.log" | tee -a "$L/06-phase-b.log"
python3 04-diff-tsv.py "$E/replica-clean.tsv" "$E/replica-after-rollback.tsv" \
  pending-claim accounts migration-report.json layout.json active-account \
  > "$L/07-rollback-diff.log" 2>&1
echo "[B7] rollback diff exit=$?" | tee -a "$L/06-phase-b.log"
tail -12 "$L/07-rollback-diff.log" | tee -a "$L/06-phase-b.log"
echo "== phase B done ==" | tee -a "$L/06-phase-b.log"
