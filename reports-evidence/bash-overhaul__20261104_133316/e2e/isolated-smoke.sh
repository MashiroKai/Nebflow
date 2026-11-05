#!/bin/bash
# bash overhaul E2E · 隔离实例冒烟（作者令 2026-11-04）
# 纪律：绝不触碰生产实例 pid 78176；只关自己起的那个 pid（<home>/.pid + 命令行验身）。
set -u
WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-485bb4f4"
EVDIR="$WT/reports-evidence/20261104_bash-overhaul/e2e"
LOG="$EVDIR/isolated-smoke.log"
H="/tmp/bashoverhaul-$(openssl rand -hex 3)"
PORT=$((6400 + RANDOM % 90))
RUNDIR=$(mktemp -d /tmp/bashoverhaul-run-XXXXXX)

cd "$WT" || exit 1
: > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }

say "=== isolated instance smoke · $(date '+%F %T') ==="
say "worktree=$WT"
say "home=$H  port=$PORT  (production pid 78176 must stay untouched)"
say "prod-pid-before: $(ps -p 78176 -o pid=,command= 2>/dev/null | head -1)"

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  say "[abort] port $PORT already LISTEN — refusing to proceed (no foreign process is ever killed)"
  exit 2
fi

NEBFLOW_GATEWAY_PORT=$PORT sbt -batch "run --home $H --port $PORT --no-browser" >> "$LOG" 2>&1 &
SBT=$!
say "sbt shell pid=$SBT (my own process)"

READY=0
for i in $(seq 1 240); do
  code=$(curl -s -o "$RUNDIR/health.json" -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/api/health" 2>/dev/null)
  if [ "$code" = "200" ]; then READY=1; break; fi
  sleep 2
done
say "[health] ready=$READY after ~$((i*2))s"
[ -s "$RUNDIR/health.json" ] && say "[health] body: $(head -c 400 "$RUNDIR/health.json")"

TOOLS=""
if [ "$READY" = "1" ]; then
  TOKEN=""
  [ -f "$H/auth.json" ] && TOKEN=$(python3 - "$H/auth.json" <<'PY'
# auth.json 的内容是一个 JSON 字面串（`"<token>"`）——必须 json.load 取值，
# 直接 read() 会带着引号 ⇒ Bearer 校验失败（403）。
import json,sys
try:
    v=json.load(open(sys.argv[1]))
except Exception:
    print(""); sys.exit(0)
print(v.strip() if isinstance(v,str) else "")
PY
)
  if [ -z "$TOKEN" ]; then
    say "[tools] no token found at $H/auth.json — WS tool-face probe skipped (honest gap)"
  else
    TOOLS=$(python3 - "$PORT" "$TOKEN" <<'PY'
import asyncio, json, sys
import websockets
port, token = sys.argv[1], sys.argv[2]
async def main():
    url = f"ws://127.0.0.1:{port}/ws?clientId=e2e-smoke"
    try:
        async with websockets.connect(url, additional_headers={"Authorization": f"Bearer {token}"}, open_timeout=15) as ws:
            await ws.send(json.dumps({"type": "getToolsList"}))
            for _ in range(40):
                raw = await asyncio.wait_for(ws.recv(), timeout=10)
                try:
                    frame = json.loads(raw)
                except Exception:
                    continue
                if frame.get("type") == "toolsList":
                    tools = frame.get("tools") or []
                    names = [t.get("name") if isinstance(t, dict) else t for t in tools]
                    print("COUNT=%d" % len(names))
                    print("HAS_BGWAIT=%s" % ("BgWait" in names))
                    print("HAS_BASH=%s" % ("Bash" in names))
                    print("NAMES=%s" % ",".join(sorted(n for n in names if n)))
                    return
            print("NO_TOOLSLIST_FRAME")
    except Exception as e:
        print("WS_ERROR=%s" % type(e).__name__ + ": " + str(e)[:200])
asyncio.run(main())
PY
)
    say "[tools] $TOOLS"
  fi
fi

# ── 收殓：只关自己起的实例 ──────────────────────────────────────────────
PIDFILE="$H/.pid"
INSTANCE_PID=""
[ -f "$PIDFILE" ] && INSTANCE_PID=$(tr -dc '0-9' < "$PIDFILE")
say "[teardown] pidfile=$PIDFILE instance_pid=${INSTANCE_PID:-<none>}"

SAFE_TO_KILL=0
if [ -n "$INSTANCE_PID" ]; then
  CMD=$(ps -p "$INSTANCE_PID" -o command= 2>/dev/null)
  say "[teardown] cmdline=$(echo "$CMD" | head -c 300)"
  case "$CMD" in
    *"$H"*) SAFE_TO_KILL=1 ;;
    *) say "[teardown] REFUSE: cmdline does not carry my unique home marker — leaving pid $INSTANCE_PID alone" ;;
  esac
  if [ "$INSTANCE_PID" = "78176" ]; then SAFE_TO_KILL=0; say "[teardown] REFUSE: that is the production pid"; fi
fi

if [ "$SAFE_TO_KILL" = "1" ]; then
  kill "$INSTANCE_PID" 2>/dev/null
  say "[teardown] sent TERM to my instance pid $INSTANCE_PID"
  for i in $(seq 1 30); do
    ps -p "$INSTANCE_PID" >/dev/null 2>&1 || break
    sleep 1
  done
  if ps -p "$INSTANCE_PID" >/dev/null 2>&1; then
    say "[teardown] still alive after 30s — leaving it (no force kill)"
  else
    say "[teardown] instance pid $INSTANCE_PID exited"
  fi
fi

# sbt 壳：我自己起的，实例退出后应自行收敛；有界等待后若仍在则关掉这一个 pid。
for i in $(seq 1 30); do
  kill -0 "$SBT" 2>/dev/null || break
  sleep 1
done
if kill -0 "$SBT" 2>/dev/null; then
  say "[teardown] sbt shell pid $SBT still up after instance exit — terminating MY OWN shell pid only"
  kill "$SBT" 2>/dev/null
fi

say "prod-pid-after: $(ps -p 78176 -o pid=,command= 2>/dev/null | head -1)"
say "=== smoke done · $(date '+%F %T') ==="
say "RESULT ready=$READY tools=[$(echo "$TOOLS" | tr '\n' ' ')]"
exit 0
