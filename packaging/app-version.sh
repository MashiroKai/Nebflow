#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# [FROZEN 2026-09-06] Desktop packaging sealed (script-install-release v2, batch 5)
# Zero code changes below this header - file kept byte-identical for the
# certificate-era restore. See:
#   - .nebflow/Spec/desktop-trust-and-icp-filing-plan.md  (trust chain / restore gates)
#   - .nebflow/Spec/script-install-release-v2.md section 2.7  (sealing scope)
# CI package jobs (release.yml / auto-release.yml) are disabled via `if: false`.
# Restore = certificates + CI notarytool/signtool integration, then unseal CI.
# G3 audit note: AutoStartService only branches on `.app/Contents` (jpackage
# bundle detection); under the script/jar layout it falls back to the classic
# `java -jar` template - no jpackage launcher hard-coupling, backward
# compatible, no runtime changes needed for the freeze.
# ----------------------------------------------------------------------------
# Print the numeric dotted app-version for jpackage/msi from the VERSION file.
#   date scheme  2026.08.15[-beta.N] → 2026.8.15  (leading zeros stripped —
#                                      Windows version fields reject them)
#   semver       1.4.1[-beta.N]      → 1.4.1      (suffix dropped)
set -euo pipefail
RAW=$(cat VERSION)
if [[ "$RAW" =~ ^([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(-beta\.[0-9]+)?$ ]]; then
  echo "${BASH_REMATCH[1]}.$((10#${BASH_REMATCH[2]})).$((10#${BASH_REMATCH[3]}))"
elif [[ "$RAW" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
  echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
else
  echo "1.0.0"
fi
