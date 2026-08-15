#!/usr/bin/env bash
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
