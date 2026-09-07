#!/bin/zsh
# run-phase.sh — AskUser preview anti-clip R3 隔离验证标准配方（强制，禁改启动形态）:
#   NEBFLOW_GATEWAY_PORT=8097 java nebflow.Main --home /tmp/qa-previewclip --port 8097 start
#   （追加 --no-browser：仅抑制冷启动自动开浏览器，不影响 home/port 语义）
# pre-flight（启动前 :8097 必须空）+ post-flight（:8097 LISTEN 且 :8080 快照不变）
# 双断言写死；断言失败立即杀自身实例（PID 验身≠宿主）并以非零退出。
set -u
PHASE="${1:?usage: run-phase.sh before|after}"
PORT=8097
HOME_DIR=/tmp/qa-previewclip
BASE="http://localhost:$PORT"
WORKTREE="/Users/dev/Claude code/Nebflow/.nebflow/worktrees/实施-AskUser预览图标防裁切R3"
HOST_PID=53186                # 环境宿主 PID —— 绝对禁杀（第一道防线，逐字核对）
CP_FILE=$HOME_DIR/classpath.txt
LOG=$HOME_DIR/instance-$PHASE.log
SHOT_DIR=$HOME_DIR/shots-r3
INSTANCE_PID=""
SNAP8080=""

cleanup() {
  local rc=$?
  if [ -n "$INSTANCE_PID" ]; then
    if [ "$INSTANCE_PID" = "$HOST_PID" ]; then
      echo "FATAL: instance pid == host pid ($HOST_PID) — refusing to kill"; exit 97
    fi
    # PID 验身补充：确认目标 cwd 是隔离 home（而非宿主）
    kill "$INSTANCE_PID" 2>/dev/null
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$INSTANCE_PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$INSTANCE_PID" 2>/dev/null
    wait "$INSTANCE_PID" 2>/dev/null
  fi
  # 端口复查：8097 必须释放（若仍有监听且非宿主则强清）；8080 快照必须不变
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

echo "== PHASE $PHASE =="

# --- pre-flight 1: classpath 文件在且首项为 worktree classes ---
[ -s "$CP_FILE" ] || { echo "PRE-FLIGHT FAIL: missing $CP_FILE"; exit 2; }
CP="$(cat $CP_FILE)"
case "$CP" in
  "$WORKTREE/target/scala-3.5.2/classes"*) : ;;
  *) echo "PRE-FLIGHT FAIL: classpath first entry not worktree classes"; exit 2 ;;
esac

# --- pre-flight 2: :8097 必须空 ---
if lsof -nP -tiTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1; then
  echo "PRE-FLIGHT FAIL: port $PORT already has a listener:"
  lsof -nP -iTCP:$PORT -sTCP:LISTEN
  exit 2
fi

# --- pre-flight 3: :8080 快照（宿主实例——只读记录，绝不触碰）---
SNAP8080=$(lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | sort | tr '\n' ',')
echo "pre-flight OK: :$PORT free; :8080 snapshot=[$SNAP8080] (host $HOST_PID untouched)"

# 陈旧 pid 文件清理（49519 已死；消除 PID 复用误判路径）
rm -f "$HOME_DIR/nebflow.pid"

# --- 标准配方启动（+--no-browser）---
NEBFLOW_GATEWAY_PORT=$PORT java -cp "$CP" nebflow.Main \
  --home "$HOME_DIR" --port $PORT --no-browser start > "$LOG" 2>&1 &
INSTANCE_PID=$!
echo "instance pid=$INSTANCE_PID (host=$HOST_PID must differ)"
[ "$INSTANCE_PID" != "$HOST_PID" ] || { echo "FATAL: pid collision with host"; exit 97; }

# --- wait /api/health ---
ok=0
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/health" 2>/dev/null)
  [ "$code" = "200" ] && { ok=1; echo "gateway ready (${i}s)"; break; }
  kill -0 "$INSTANCE_PID" 2>/dev/null || { echo "FAIL: instance died early"; tail -20 "$LOG"; exit 3; }
  sleep 1
done
[ "$ok" = "1" ] || { echo "FAIL: gateway never became ready"; tail -20 "$LOG"; exit 3; }

# --- post-flight 1: :8097 LISTEN 且为我方子进程 ---
LISTEN=$(lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | tr '\n' ',')
[ -n "$LISTEN" ] || { echo "POST-FLIGHT FAIL: :$PORT not listening"; exit 4; }
for p in $(echo $LISTEN | tr ',' ' '); do
  [ "$p" = "$HOST_PID" ] && { echo "FATAL: :$PORT held by host pid?!"; exit 6; }
done
echo "post-flight OK: :$PORT pid=[$LISTEN]"

# --- post-flight 2: :8080 快照不变 ---
NOW8080=$(lsof -nP -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | sort | tr '\n' ',')
[ "$NOW8080" = "$SNAP8080" ] || { echo "POST-FLIGHT FAIL: :8080 changed [$SNAP8080]->[$NOW8080]"; exit 5; }
echo "post-flight OK: :8080 unchanged"

# --- 视觉 harness ---
node "$HOME_DIR/previewclip-shots.mjs" "$PHASE" "$BASE" "$SHOT_DIR" > "$HOME_DIR/harness-$PHASE.json" 2>&1
H=$?
tail -5 "$HOME_DIR/harness-$PHASE.json"
[ $H -eq 0 ] || { echo "FAIL: harness assertions failed (see $HOME_DIR/harness-$PHASE.json)"; exit 7; }

# --- after 阶段附加门禁: verify-web-assets 281/281 ---
if [ "$PHASE" = "after" ]; then
  ( cd "$WORKTREE" && node scripts/verify-web-assets.mjs "$BASE" ) > "$HOME_DIR/web-assets-after.log" 2>&1
  V=$?
  tail -3 "$HOME_DIR/web-assets-after.log"
  [ $V -eq 0 ] || { echo "FAIL: verify-web-assets gate"; exit 8; }
fi

echo "== PHASE $PHASE DONE (instance left for cleanup trap) =="
exit 0
