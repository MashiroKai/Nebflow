#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# [FROZEN 2026-09-06] Desktop packaging sealed (script-install-release v2, batch 5)
# Zero code changes below this header - file kept byte-identical for the
# certificate-era restore. See:
#   - the desktop trust-chain / ICP filing design note  (trust chain / restore gates)
#   - the script-install / release v2 design note section 2.7  (sealing scope)
# CI package jobs (release.yml / auto-release.yml) are disabled via `if: false`.
# Restore = certificates + CI notarytool/signtool integration, then unseal CI.
# G3 audit note: AutoStartService only branches on `.app/Contents` (jpackage
# bundle detection); under the script/jar layout it falls back to the classic
# `java -jar` template - no jpackage launcher hard-coupling, backward
# compatible, no runtime changes needed for the freeze.
# ----------------------------------------------------------------------------
# [UNFROZEN 2026-09-17 | author ruling ①, tagfix batch] Scope of the unfreeze =
# the VERSION date branch below ONLY: it now takes the optional anchored 4th
# segment (`(\.[0-9]+)?`, placed after the date core and before `-beta`), so the
# four-segment release value 2026.9.17.1 is no longer silently truncated to
# three fields. Mapping stays numeric-dotted, one rule per branch:
#   date   YYYY.MM.DD[.S][-beta.N]  -> Y.M.D[.S]   (M/D leading zeros
#          stripped; year = exactly 4 digits; month/day = 1-2 digits each;
#          S = >=1 digit, emitted VERBATIM, no zero-stripping and no upper
#          bound; `-beta.N` only decides the branch and is not emitted)
#   semver 1.4.1[-beta.N]               -> 1.4.1       (suffix dropped)
#   neither                             -> 1.0.0       (fallback, no match)
# The three-segment date form and the `-beta.N` form keep their exact previous
# bytes. The desktop-packaging seal note above stays in force for everything
# else in this file.
# ----------------------------------------------------------------------------
# Print the numeric dotted app-version for jpackage/msi from the VERSION file.
#   date scheme  2026.08.15[-beta.N] → 2026.8.15  (leading zeros stripped —
#                                      Windows version fields reject them)
#   semver       1.4.1[-beta.N]      → 1.4.1      (suffix dropped)
set -euo pipefail
RAW=$(cat VERSION)
if [[ "$RAW" =~ ^([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(\.[0-9]+)?(-beta\.[0-9]+)?$ ]]; then
  echo "${BASH_REMATCH[1]}.$((10#${BASH_REMATCH[2]})).$((10#${BASH_REMATCH[3]}))${BASH_REMATCH[4]}"
elif [[ "$RAW" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
  echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
else
  echo "1.0.0"
fi
