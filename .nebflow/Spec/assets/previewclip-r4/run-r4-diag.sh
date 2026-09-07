#!/bin/zsh
# run-r4-diag.sh — R4 诊断阶段：标准配方起隔离实例（:8097, /tmp/qa-previewclip），
# 跑 previewclip-r4-probe.mjs 四层像素探针，然后 cleanup。
# pre-flight（:8097 空）+ post-flight（:8097 LISTEN 非 8080 宿主、:8080 快照不变）。
set -u
PORT=8097
HOME_DIR=/tmp/qa-previewclip
BASE="http://localhost:$PORT"
HOST_PID="${HOST_PID_ENV:-53186}"     # 环境宿主 PID —— 绝对禁杀（第一道防线）
CP_FILE=$HOME_DIR/classpath.txt
INSTANCE_PID=""
SNAP8080=""

cleanup() {
  local rc=$?
  if [ -n "$INSTANCE_PID" ]; then
    if [ "$INSTANCE_PID" = "$HOST_PID" ]; then
      echo "FATAL: instance pid == host pid ($HOST_PID) — refusing to kill"; exit 97
    fi
    kill "$INSTANCE_PID" 2>/dev/null
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$INSTANCE_PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$INSTANCE_PID" 2>/dev/null
    wait "$INSTANCE_PID" 2>/dev/null
  fi
  local still
  still=$(lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null)
  if [ -n "$still" ]; then
    for p in $(echo $still | tr '\n' ' '); do
      if [ "$p" = "$HOST_PID" ]; then
        echo "FATAL cleanup: :$PORT held by host pid — DO NOT TOUCH"
      else
        echo "cleanup: force-killing leftover :$PORT pid $p (verified ≠ host)"
        lsof -p $p 2>/dev/null | grep -m1 cwd
        kill -9 $p 2>/dev/null
      fi
    done
  fi
  local now8080
  now8080=$(lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | sort | tr '\n' ',')
  if [ "$now8080" != "$SNAP8080" ]; then
    echo "WARN cleanup: :8080 snapshot changed [$SNAP8080] -> [$now8080] (report this)"
  fi
  exit $rc
}
on_int()  { cleanup; exit 130; }
on_term() { cleanup; exit 143; }
trap cleanup EXIT
trap on_int INT
trap on_term TERM

[ -s "$CP_FILE" ] || { echo "PRE-FLIGHT FAIL: missing $CP_FILE"; exit 2; }
CP="$(cat $CP_FILE)"

if lsof -nP -tiTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1; then
  echo "PRE-FLIGHT FAIL: port $PORT already has a listener:"; lsof -nP -iTCP:$PORT -sTCP:LISTEN; exit 2
fi
SNAP8080=$(lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | sort | tr '\n' ',')
echo "pre-flight OK: :$PORT free; :8080 snapshot=[$SNAP8080] (host $HOST_PID untouched)"

rm -f "$HOME_DIR/nebflow.pid"

NEBFLOW_GATEWAY_PORT=$PORT java -cp "$CP" nebflow.Main \
  --home "$HOME_DIR" --port $PORT --no-browser start > "$HOME_DIR/instance-r4-diag.log" 2>&1 &
INSTANCE_PID=$!
echo "instance pid=$INSTANCE_PID (host=$HOST_PID must differ)"
[ "$INSTANCE_PID" != "$HOST_PID" ] || { echo "FATAL: pid collision with host"; exit 97; }

ok=0
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/health" 2>/dev/null)
  [ "$code" = "200" ] && { ok=1; echo "gateway ready (${i}s)"; break; }
  kill -0 "$INSTANCE_PID" 2>/dev/null || { echo "FAIL: instance died early"; tail -20 "$HOME_DIR/instance-r4-diag.log"; exit 3; }
  sleep 1
done
[ "$ok" = "1" ] || { echo "FAIL: gateway never became ready"; tail -20 "$HOME_DIR/instance-r4-diag.log"; exit 3; }

LISTEN=$(lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | tr '\n' ',')
[ -n "$LISTEN" ] || { echo "POST-FLIGHT FAIL: :$PORT not listening"; exit 4; }
for p in $(echo $LISTEN | tr ',' ' '); do
  [ "$p" = "$HOST_PID" ] && { echo "FATAL: :$PORT held by host pid?!"; exit 6; }
done
echo "post-flight OK: :$PORT pid=[$LISTEN]"

node "$HOME_DIR/previewclip-r4-probe.mjs" "$BASE" "diag-$(date +%H%M%S)" > "$HOME_DIR/probe-r4.json" 2> "$HOME_DIR/probe-r4.err"
H=$?
if [ $H -ne 0 ]; then echo "PROBE FAIL (exit $H)"; tail -30 "$HOME_DIR/probe-r4.err"; exit 7; fi
echo "== probe result: $HOME_DIR/probe-r4.json =="
exit 0
