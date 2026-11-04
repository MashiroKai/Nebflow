#!/bin/bash
# Rehearsal phase A — run against the PRISTINE replica:
#   1. symlink census (expect the same hazards the real root has)
#   2. baseline manifest (independent python digests of the whole replica)
#   3. migrate() on the pristine replica  => EXPECTED TO ABORT on the first broken symlink
#   4. containment diff: every ORIGINAL must still be byte-identical
set -uo pipefail
R=/Volumes/rehearsal/root
E=/Volumes/KINGSTON/rehearsal-evidence
EV="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-24f88015/reports-evidence/20261104_root-migration-rehearsal"
L="$EV/logs"
CP=$(cat "$E/classpath.txt")
cd "$EV" || exit 1

echo "== [A1] symlink census on the pristine replica ==" | tee "$L/05-phase-a.log"
python3 03-census-repair.py "$R" >> "$L/05-census-pristine.log" 2>&1
grep -E '"(total|ok_in_root_resolvable|broken|socket|outside|stat_fail)"' "$L/05-census-pristine.log" | tee -a "$L/05-phase-a.log"

echo "== [A2] baseline fingerprint (independent sha256 of the whole replica) ==" | tee -a "$L/05-phase-a.log"
python3 fingerprint.py "$R" --out "$E/replica-baseline.tsv" --aggregate "$E/replica-baseline-agg.json" >> "$L/05-fingerprint-baseline.log" 2>&1
cat "$L/05-fingerprint-baseline.log" | tee -a "$L/05-phase-a.log"

echo "== [A3] migrate() on the pristine replica (expected: abort on a hazard symlink) ==" | tee -a "$L/05-phase-a.log"
STAND_HOME=/Volumes/rehearsal/ignore-home NEBFLOW_HOME=/Volumes/rehearsal/ignore-home \
  java -Xmx8g -Dlogback.configurationFile="$EV/logback-driver.xml" -cp "$CP" \
  nebflow.core.dataroot.RehearsalDriver bigroot "$R" > "$L/05-crash-run.log" 2>&1
echo "[A3] driver exit=$?" | tee -a "$L/05-phase-a.log"
head -12 "$L/05-crash-run.log" | tee -a "$L/05-phase-a.log"
grep -E "Exception|THREW|at nebflow" "$L/05-crash-run.log" | head -6 | tee -a "$L/05-phase-a.log"

echo "== [A4] containment: re-fingerprint and diff against baseline ==" | tee -a "$L/05-phase-a.log"
python3 fingerprint.py "$R" --out "$E/replica-after-crash.tsv" >> "$L/05-fingerprint-after-crash.log" 2>&1
cat "$L/05-fingerprint-after-crash.log" | tee -a "$L/05-phase-a.log"
python3 04-diff-tsv.py "$E/replica-baseline.tsv" "$E/replica-after-crash.tsv" \
  pending-claim accounts migration-report.json layout.json active-account \
  > "$L/05-containment-diff.log" 2>&1
echo "[A4] containment diff exit=$?" | tee -a "$L/05-phase-a.log"
head -20 "$L/05-containment-diff.log" | tee -a "$L/05-phase-a.log"
echo "== phase A done ==" | tee -a "$L/05-phase-a.log"
