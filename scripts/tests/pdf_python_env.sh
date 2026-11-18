#!/usr/bin/env bash
# pdf_python_env.sh — bootstrap the PDF parser's Python environment (PDF note/ask).
#
# WHY THIS FILE EXISTS
#   The parser leg (`src/main/resources/pdf/pdf_parse.py`) runs as a SUBPROCESS and
#   needs `pdfplumber` + `pypdf` (pinned in `src/main/resources/pdf/requirements.txt`).
#   Neither the JVM nor a stock `python3` carries them, and this repo never installs
#   into a system interpreter. The dependency set therefore gets its own virtualenv,
#   and the single env knob `STAND_PDF_PYTHON` tells every consumer which interpreter
#   to use. Nothing else in the feature picks an interpreter.
#
# WHO READS THE KNOB (one knob, two legs)
#   · Scala — `PdfParser.pythonBin()` — the real parse (spawned from the JVM)
#   · Node  — `scripts/tests/pdf-parse.test.mjs` — the parser contract test
#
# WHERE THE DEPS MUST BE VISIBLE
#   The Scala specs run IN the sbt JVM (`Test / testGrouping` puts every suite except
#   the two process-isolated ones in `Tests.InProcess`), so `sys.env` there IS the sbt
#   process environment. The knob must therefore be exported on the **sbt process**,
#   not only in an interactive shell that later runs the tests.
#
# USAGE
#   scripts/tests/pdf_python_env.sh                 # create/refresh the venv (idempotent)
#   scripts/tests/pdf_python_env.sh --print         # print the export line, install nothing
#   scripts/tests/pdf_python_env.sh --check         # verify an existing venv, install nothing
#
#   # then, from the repo root:
#   export STAND_PDF_PYTHON="$HOME/.cache/stand/pdf-venv/bin/python3"
#   sbt "testOnly nebflow.core.pdf.* nebflow.gateway.PdfAskHttpConcurrencySpec"
#   STAND_PDF_PYTHON=... node --test scripts/tests/pdf-parse.test.mjs
#
# OVERRIDES (both optional)
#   STAND_PDF_VENV         where the venv lives   (default: $HOME/.cache/stand/pdf-venv)
#   STAND_PDF_BASE_PYTHON  the interpreter that builds it (default: python3)
#
# The venv lives OUTSIDE the repository and OUTSIDE any instance data root: it is a
# developer-machine artifact, never a tracked file and never instance state.

set -euo pipefail

VENV="${STAND_PDF_VENV:-$HOME/.cache/stand/pdf-venv}"
BASE_PYTHON="${STAND_PDF_BASE_PYTHON:-python3}"
PY="$VENV/bin/python3"

# Repo root = two levels up from this script (scripts/tests/ → repo root).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REQS="$ROOT/src/main/resources/pdf/requirements.txt"

print_export() {
  echo "export STAND_PDF_PYTHON=\"$PY\""
}

case "${1:-}" in
  --print)
    print_export
    exit 0
    ;;
  --check)
    if [ ! -x "$PY" ]; then
      echo "MISSING: no interpreter at $PY — run scripts/tests/pdf_python_env.sh first" >&2
      exit 1
    fi
    if ! "$PY" -c 'import pdfplumber, pypdf' >/dev/null 2>&1; then
      echo "MISSING: $PY cannot import pdfplumber/pypdf — re-run scripts/tests/pdf_python_env.sh" >&2
      exit 1
    fi
    "$PY" -c 'import pdfplumber, pypdf; print("pdfplumber", pdfplumber.__version__, "| pypdf", pypdf.__version__, "|", __import__("sys").executable)'
    exit 0
    ;;
  "" ) ;;
  *) echo "usage: $0 [--print|--check]" >&2; exit 2 ;;
esac

[ -f "$REQS" ] || { echo "no requirements file at $REQS" >&2; exit 1; }

if [ ! -x "$PY" ]; then
  echo "creating venv: $VENV (base: $BASE_PYTHON)"
  "$BASE_PYTHON" -m venv "$VENV"
fi

echo "installing the pinned parser set from $REQS"
# The requirements file carries source-distribution sha256 hashes, so pip runs in
# hash-checked mode: the bytes installed are the exact pinned ones (binary packages
# are built locally from those sources).
"$PY" -m pip install --quiet --upgrade pip
"$PY" -m pip install --quiet -r "$REQS"

"$PY" -c 'import pdfplumber, pypdf; print("OK pdfplumber", pdfplumber.__version__, "| pypdf", pypdf.__version__)'
echo
echo "Done. Point every consumer at it (must be exported on the sbt process too):"
print_export
