#!/bin/zsh
# run-r4.sh — AskUser preview anti-clip R4 隔离验证标准配方（强制，禁改启动形态）:
#   NEBFLOW_GATEWAY_PORT=8097 java nebflow.Main --home /tmp/qa-previewclip --port 8097 start
# pre-flight（启动前 :8097 必须空）+ post-flight（:8097 LISTEN 非 8080 宿主 + :8080 快照不变）
# 双断言写死；断言失败立即杀自身实例（PID 验身≠宿主）并以非零退出。
# Usage: run-r4.sh before|after   （before 阶段须先由调用方把 classes 同步回 tip 代码）
set -u
PHASE="${1:?usage: run-r4.sh before|after}"
PORT=8097
HOME_DIR=/tmp/qa-previewclip
BASE="http://localhost:$PORT"
WORKTREE="/Users/dev/Claude code/Nebflow/.nebflow/worktrees/实施-AskUser预览图标防裁切R3"
HOST_PID="${HOST_PID_ENV:-53186}"       # 环境宿主 PID —— 绝对禁杀（第一道防线）
CP_FILE=$HOME_DIR/classpath.txt
LOG=$HOME_DIR/instance-r4-$PHASE.log
SHOT_DIR=$HOME_DIR/shots-r4
OUT_SHOTS=/tmp/nebflow-previewclip-r4-shots
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

echo "== R4 PHASE $PHASE =="

[ -s "$CP_FILE" ] || { echo "PRE-FLIGHT FAIL: missing $CP_FILE"; exit 2; }
CP="$(cat $CP_FILE)"
case "$CP" in
  "$WORKTREE/target/scala-3.5.2/classes"*) : ;;
  *) echo "PRE-FLIGHT FAIL: classpath first entry not worktree classes"; exit 2 ;;
esac

if lsof -nP -tiTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1; then
  echo "PRE-FLIGHT FAIL: port $PORT already has a listener:"
  lsof -nP -iTCP:$PORT -sTCP:LISTEN
  exit 2
fi
SNAP8080=$(lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | sort | tr '\n' ',')
echo "pre-flight OK: :$PORT free; :8080 snapshot=[$SNAP8080] (host $HOST_PID untouched)"

rm -f "$HOME_DIR/nebflow.pid"

NEBFLOW_GATEWAY_PORT=$PORT java -cp "$CP" nebflow.Main \
  --home "$HOME_DIR" --port $PORT --no-browser start > "$LOG" 2>&1 &
INSTANCE_PID=$!
echo "instance pid=$INSTANCE_PID (host=$HOST_PID must differ)"
[ "$INSTANCE_PID" != "$HOST_PID" ] || { echo "FATAL: pid collision with host"; exit 97; }

ok=0
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/health" 2>/dev/null)
  [ "$code" = "200" ] && { ok=1; echo "gateway ready (${i}s)"; break; }
  kill -0 "$INSTANCE_PID" 2>/dev/null || { echo "FAIL: instance died early"; tail -20 "$LOG"; exit 3; }
  sleep 1
done
[ "$ok" = "1" ] || { echo "FAIL: gateway never became ready"; tail -20 "$LOG"; exit 3; }

LISTEN=$(lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | tr '\n' ',')
[ -n "$LISTEN" ] || { echo "POST-FLIGHT FAIL: :$PORT not listening"; exit 4; }
for p in $(echo $LISTEN | tr ',' ' '); do
  [ "$p" = "$HOST_PID" ] && { echo "FATAL: :$PORT held by host pid?!"; exit 6; }
done
NOW8080=$(lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | sort | tr '\n' ',')
[ "$NOW8080" = "$SNAP8080" ] || { echo "POST-FLIGHT FAIL: :8080 changed [$SNAP8080]->[$NOW8080]"; exit 5; }
echo "post-flight OK: :$PORT pid=[$LISTEN]; :8080 unchanged"

node "$HOME_DIR/previewclip-r4.mjs" "$PHASE" "$BASE" "$SHOT_DIR" > "$HOME_DIR/harness-r4-$PHASE.json" 2>&1
H=$?
tail -3 "$HOME_DIR/harness-r4-$PHASE.json"
[ $H -eq 0 ] || { echo "FAIL: harness assertions failed (see $HOME_DIR/harness-r4-$PHASE.json)"; exit 7; }

if [ "$PHASE" = "after" ]; then
  ( cd "$WORKTREE" && node scripts/verify-web-assets.mjs "$BASE" ) > "$HOME_DIR/web-assets-r4-after.log" 2>&1
  V=$?
  tail -3 "$HOME_DIR/web-assets-r4-after.log"
  [ $V -eq 0 ] || { echo "FAIL: verify-web-assets gate"; exit 8; }
  ( cd "$WORKTREE" && node scripts/check-js-types.mjs ) > "$HOME_DIR/checkjs-r4-after.log" 2>&1
  C=$?
  tail -3 "$HOME_DIR/checkjs-r4-after.log"
  [ $C -eq 0 ] || { echo "FAIL: checkJs gate"; exit 9; }
fi

mkdir -p "$OUT_SHOTS"
cp "$SHOT_DIR/${PHASE}-"*.png "$OUT_SHOTS/"
echo "== R4 PHASE $PHASE DONE (instance left for cleanup trap; shots copied to $OUT_SHOTS) =="
exit 0
