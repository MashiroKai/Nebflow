#!/bin/bash
# Runtime-only lifecycle acceptance; source-only checks use restart-source-guard.test.py.
set -eu
if [[ "$#" -eq 0 ]]; then
  printf '%s\n' 'NOT RUN: explicit --case required.' >&2
  exit 77
fi
if [[ "$#" -ne 2 || "$1" != '--case' ]]; then
  printf '%s\n' 'Expected --case normal|abort|cancel|slow-request.' >&2
  exit 64
fi
case "$2" in
  normal|abort|cancel|slow-request) ;;
  *) printf '%s\n' 'Unknown lifecycle case.' >&2; exit 64 ;;
esac
HERE="$(cd "$(dirname "$0")" && pwd -P)"
exec /usr/bin/python3 -B "$HERE/fixtures/restart/lifecycle.py" --case "$2"
