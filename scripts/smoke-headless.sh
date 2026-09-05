#!/bin/zsh
# Headless P0 smoke (design §5 layer 2): live gateway + real LLM, three cases.
# Cost: 2-3 LLM calls ≈ tens of k tokens (GLM) — well under ¥1.
# Port: 8096 (8094 = Manager gate, 8095 = Frontend e2e — do not collide).
set -u
WORKTREE=/tmp/nb-headless
HOME_DIR=/tmp/nb-headless-smoke
PORT=8096
LOG=/tmp/nb-headless-smoke.log
BASE="http://localhost:$PORT"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "PASS: $1" }
bad() { FAIL=$((FAIL+1)); echo "FAIL: $1" }

cleanup() {
  lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | xargs kill -KILL 2>/dev/null
  pgrep -f -- "--port $PORT" | xargs kill -KILL 2>/dev/null
}
# 信号路径必须显式 exit：zsh 的 trap 处理完信号会从断点继续跑脚本，
# 只有 exit(128+N) 才真正终止（exit 又会触发 EXIT trap，cleanup 幂等无害）。
# 残留治理 2026-09-05——裸 EXIT trap 在信号退出路径不触发，隔离实例 sbt
# 进程会漏到脚本外；Ctrl+C(SIGINT)=130 / 被 kill(SIGTERM)=143。
on_int()  { cleanup; exit 130; }
on_term() { cleanup; exit 143; }
trap cleanup EXIT
trap on_int INT
trap on_term TERM

# --- isolated instance with real provider config (copy from main home) ---
rm -rf "$HOME_DIR"; mkdir -p "$HOME_DIR"
cp ~/.nebflow/nebflow.json "$HOME_DIR/nebflow.json" 2>/dev/null || { echo "no provider config in main home"; exit 1; }
cp ~/.nebflow/auth.json "$HOME_DIR/auth.json" 2>/dev/null

( cd "$WORKTREE" && NEBFLOW_HOME="$HOME_DIR" nohup sbt -batch "run --port $PORT" > "$LOG" 2>&1 & )

for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/health" 2>/dev/null)
  [ "$code" = "200" ] && { echo "gateway ready (${i}x5s)"; break; }
  sleep 5
done
[ "$code" = "200" ] || { echo "gateway never became ready"; grep -E "error|Exception" "$LOG" | head -5; exit 1; }

TOKEN=$(cat "$HOME_DIR/auth.json" | tr -d '"')
AUTH="Authorization: Bearer $TOKEN"

# --- Case 1: synchronous turn, real LLM, final message must come back ---
SID=$(curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"smoke-turn"}' "$BASE/api/sessions" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "session: $SID"
TURN=$(curl -s -w '\n%{http_code}' -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"content":"Reply with exactly the word PONG and nothing else.","timeoutSec":120}' \
  "$BASE/api/sessions/$SID/turn")
TURN_CODE=$(tail -1 <<<"$TURN"); TURN_BODY=$(sed '$d' <<<"$TURN")
echo "turn http=$TURN_CODE body=${TURN_BODY:0:300}"
STATUS=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["status"])' "$TURN_BODY" 2>/dev/null)
FINAL=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["finalMessage"])' "$TURN_BODY" 2>/dev/null)
[ "$TURN_CODE" = "200" ] && ok "1 turn endpoint 200" || bad "1 turn http=$TURN_CODE"
[ "$STATUS" = "completed" ] && ok "1b status=completed" || bad "1b status=$STATUS"
[ -n "$FINAL" ] && ok "1c finalMessage non-empty (${FINAL:0:60})" || bad "1c finalMessage empty"

# --- Case 2: userMessage chain (the CLI break — now handled) ---
SID2=$(curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"smoke-usermsg"}' "$BASE/api/sessions" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
CMD=$(curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"type\":\"userMessage\",\"content\":\"Reply with exactly the word ACK and nothing else.\",\"sessionId\":\"$SID2\"}" \
  "$BASE/api/command")
ok "2 userMessage dispatched (resp ${CMD:0:80})"
# poll history for the AI reply — proves the case actually routed (pre-fix: silent IO.unit)
FOUND=0
for i in $(seq 1 60); do
  AI=$(curl -s -H "$AUTH" "$BASE/api/sessions/$SID2/history" | python3 -c '
import json,sys
try:
  ms = json.load(sys.stdin)["messages"]
  print(any(m.get("type")=="ai" and m.get("text","").strip() for m in ms))
except Exception: print(False)' 2>/dev/null)
  [ "$AI" = "True" ] && { FOUND=1; break; }; sleep 2
done
[ "$FOUND" = "1" ] && ok "2b userMessage -> agent turn -> ai reply recorded" || bad "2b no ai reply in history"

# --- Case 3: timeout -> 504 ---
SID3=$(curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"smoke-timeout"}' "$BASE/api/sessions" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
T3=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"content":"write a very long essay about philosophy","timeoutSec":3}' \
  "$BASE/api/sessions/$SID3/turn")
[ "$T3" = "504" ] && ok "3 timeout -> 504" || bad "3 timeout http=$T3 (expected 504)"

echo "=="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ]
