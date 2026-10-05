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
  # Kill the WHOLE isolated instance, not just the sbt wrapper: `sbt run` forks
  # the gateway into a child JVM, so killing $GATEWAY_PID leaves the real listener
  # alive (实测：trap 后 :8687 仍有 java 在听 ⇒ 下一次 run 被「already running
  # (same port)」接管到**上一次的旧实例**上跑）。
  # 先按端口收（覆盖 fork 出的子 JVM），再兜底按 wrapper pid 收；两者都只针对
  # 本脚本自己起的隔离实例（端口已在前置闸限定在保留段，绝不是 :8080）。
  for _ in $(seq 1 20); do
    pids=$(lsof -ti "tcp:$PORT" 2>/dev/null || true)
    [ -z "$pids" ] && break
    kill $pids 2>/dev/null || true
    sleep 0.5
  done
  pids=$(lsof -ti "tcp:$PORT" 2>/dev/null || true)
  [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
  if [ -n "${GATEWAY_PID:-}" ] && kill -0 "$GATEWAY_PID" 2>/dev/null; then
    kill "$GATEWAY_PID" 2>/dev/null || true
  fi
  echo "── isolated home retained for inspection: $HOME_DIR"
  echo "── port $PORT after teardown: $(lsof -ti "tcp:$PORT" 2>/dev/null | tr '\n' ' ' || true)(empty = no listener outlives this run)"
}
trap cleanup EXIT

# 前置闸②：端口必须空。若已有实例在听，`sbt run` 会打印「already running」并去
# 激活**那个**实例（不是我们新建的隔离 home），于是后面的 auth.json / WS 全打到
# 别人身上 —— 必须先拒绝，绝不复用。
if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
  echo "REFUSE  port $PORT is already in use (pid $(lsof -ti "tcp:$PORT" | tr '\n' ' ')) — refusing to adopt a foreign instance"
  exit 2
fi

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

# Wait for the gateway to actually ANSWER. auth.json is written early (before the
# HTTP listener binds), so polling the file alone races: the WS handshake then fails
# with "WS connect failed" even though boot was healthy. Poll the HTTP surface like
# the other smokes do (e2e-dispatcher-context-catalog.mjs waitGateway), then read
# auth.json.
UP=0
for _ in $(seq 1 180); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/" 2>/dev/null) || code=000
  # 🔴 必须显式排除 "000"（curl 连不上时的返回值）：数字比较下 000 -lt 500 为真，
  #    不排除就会在网关**还没 listen** 时提前放行（实测：客户端随即 WS 失败）。
  if [ -n "$code" ] && [ "$code" != "000" ] && [ "$code" -lt 500 ]; then
    echo "   gateway answered http $code; HTTP listener is up"
    UP=1
    break
  fi
  sleep 1
done
if [ "$UP" != "1" ]; then
  echo "FAIL  gateway did not answer within 180s — tail of $LOG:"
  tail -30 "$LOG"
  exit 1
fi
for _ in $(seq 1 30); do
  [ -f "$HOME_DIR/auth.json" ] && break
  sleep 1
done
if [ ! -f "$HOME_DIR/auth.json" ]; then
  echo "FAIL  gateway answered but wrote no auth.json — tail of $LOG:"
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
