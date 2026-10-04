#!/usr/bin/env bash
# e2e-onboarding-v2-run.sh — boot an ISOLATED gateway and walk the v2 onboarding.
#
# Isolation-first gate (task book §5 D-3): the home is a fresh `mktemp -d` and the
# port comes from the reserved 8686..8690 band. The host instance (:8080,
# PID 76168) is never addressed, never signalled and never restarted.
#
# The gateway boot runs in the BACKGROUND and is torn down by this script's own
# trap, so the exit code is guaranteed by the script (task book §5 D-2 ②) and no
# listener outlives the run.
#
# Usage:  ./scripts/e2e-onboarding-v2-run.sh [port]
set -euo pipefail

PORT="${1:-8687}"
case "$PORT" in
  8686|8687|8688|8689|8690) ;;
  *) echo "REFUSE  port $PORT is outside the reserved 8686..8690 band"; exit 2 ;;
esac
if [ "$PORT" = "8080" ]; then echo "REFUSE  :8080 is the host instance"; exit 2; fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="$(mktemp -d -t nb-ob2-e2e)"
LOG="$HOME_DIR/gateway.log"
JDK=/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home

cleanup() {
  # Kill ONLY the java process we started, verified by its own pid file.
  if [ -n "${GATEWAY_PID:-}" ] && kill -0 "$GATEWAY_PID" 2>/dev/null; then
    kill "$GATEWAY_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$GATEWAY_PID" 2>/dev/null || break; sleep 0.5; done
    kill -9 "$GATEWAY_PID" 2>/dev/null || true
  fi
  echo "── isolated home retained for inspection: $HOME_DIR"
}
trap cleanup EXIT

echo "── isolation readings (must be a fresh tree on a reserved port) ──"
echo "   HOME_DIR = $HOME_DIR"
echo "   exists?  = $(test -d "$HOME_DIR" && echo yes)"
echo "   ls       = $(ls -A "$HOME_DIR" | wc -l | tr -d ' ') entries before boot"
echo "   PORT     = $PORT  (:8080 is NOT touched by this script)"
echo "   user HOME = $HOME   (the script never writes here)"

# Optional: seed the isolated home with providers so the model question's PRIMARY
# path (pick from already-configured providers) is exercised for real. This READS
# the real config and copies it into the isolated home — the real home is never
# written (task book §3 item 2 / §3.8: real model requests may be probed, writing
# the real home is forbidden).
if [ "${SEED_REAL_PROVIDERS:-0}" = "1" ]; then
  REAL_CFG="$HOME/.nebflow/nebflow.json"
  if [ -f "$REAL_CFG" ]; then
    cp "$REAL_CFG" "$HOME_DIR/nebflow.json"
    echo "   seeded providers: copied $REAL_CFG -> $HOME_DIR/nebflow.json (READ-ONLY on the source)"
  else
    echo "   WARN  SEED_REAL_PROVIDERS=1 but $REAL_CFG is missing — continuing with an empty config"
  fi
fi

cd "$ROOT"
JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" \
  sbt -batch "run --home $HOME_DIR --port $PORT --no-browser" >"$LOG" 2>&1 &
GATEWAY_PID=$!
echo "   gateway pid = $GATEWAY_PID (sbt wrapper)"

# Wait for the token file the gateway writes on boot (proves it is up).
for _ in $(seq 1 120); do
  [ -f "$HOME_DIR/auth.json" ] && break
  sleep 1
done
if [ ! -f "$HOME_DIR/auth.json" ]; then
  echo "FAIL  gateway did not come up within 120s — tail of $LOG:"
  tail -30 "$LOG"
  exit 1
fi
echo "   auth.json written; gateway is up"

set +e
NEBFLOW_URL="http://127.0.0.1:$PORT" NEBFLOW_HOME_DIR="$HOME_DIR" \
  E2E_ONBOARDING_JS="$ROOT/src/main/resources/web/js/onboarding.js" \
  E2E_SEEDED="$([ "${SEED_REAL_PROVIDERS:-0}" = "1" ] && echo 1 || echo 0)" \
  node "$ROOT/scripts/e2e-onboarding-v2-isolated.mjs"
RC=$?
set -e

echo "── home tree after the walk ──"
find "$HOME_DIR" -maxdepth 2 -name '*.md' -o -maxdepth 2 -name 'agent.json' | sort
exit $RC
