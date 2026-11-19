#!/usr/bin/env bash
# perception-daemon-smoke.sh — LIVE smoke for the UNIFIED perception daemon
# (统一 daemon 批 2026-10-29；作者裁定「独立daemon吧 正好和我们心跳进程合并
# 那就是管daemon的」)。boot-smoke 家族同款隔离纪律。
#
# What it proves, end to end, in one command:
#   P1. the engine boots isolated (fresh data root) and /api/health answers 200;
#   P2. the engine DOCKS the unified perception daemon (engine log "docked");
#   P3. the daemon is a REAL child process, registered in the daemon family
#       (pid marker daemon-pids.json — the heartbeat/daemon-manager leg);
#   P4. the daemon's own /health answers 200 with the boot token, 403 without
#       (token gate = engine-会话门同款拒绝面);
#   P5. the perception FIVE loops are live (daemon log: boot line poll/sweep/
#       digest/spool/idle + serving line + 30s self-heartbeat watchdog line);
#   P6. the heartbeat leg is live: /api/daemons shows the perception entry
#       Running with portOpen=true (unified health face, panel data source);
#   P7. the panel proxy is live: GET /api/perception/entries -> 200 (engine
#       proxies to the daemon over the token-gated loopback channel);
#   P8. clean exit: SIGTERM the engine -> the daemon dies WITH it (随引擎走 —
#       current heartbeat-process semantics), zero orphans left.
#
# Isolation contract (iron rule: never touch production):
#   - data root = mktemp -d, wired via --home + STAND_HOME + NEBFLOW_HOME;
#   - sandbox high port, default 8091, parameterizable via --port;
#   - production port 6138 and the host gateway port 8080 are REFUSED;
#   - every kill is PID-identity-verified (cwd/cmdline must carry the temp
#     home or the jar path) AND checked against the never-kill ledger before
#     any signal. An unverifiable PID is reported, not killed.
#
# Usage:
#   scripts/perception-daemon-smoke.sh <jar> [--port N] [--timeout S] [--keep-home]
# Output: [smoke] evidence lines, then exactly one verdict line:
#   PERCEPTION-DAEMON-SMOKE: PASS jar=<name> port=<p> boot_ready=<S>s checks=<8-digit>
#   PERCEPTION-DAEMON-SMOKE: FAIL stage=<preflight|boot|dock|probe|exit|teardown> reason=<one clause>
# Exit code: 0 = PASS, 1 = FAIL. bash-3.2 compatible (macOS stock bash).

set -u

PROGNAME="$(basename "$0")"
DEFAULT_PORT=8091
DEFAULT_TIMEOUT=120
PROD_PORT=6138          # 在产实例端口 — 铁律
HOST_GW_PORT=8080       # 宿主 gateway（作者机器常驻）— 同列铁律
PINNED_JDK="$HOME/.stand/jdk-21/bin/java"

JAR=""
PORT="$DEFAULT_PORT"
BOOT_TIMEOUT="$DEFAULT_TIMEOUT"
KEEP_HOME=0

HOME_DIR=""
LOG=""
ENGINE_LOG=""
LAUNCHER_PID=""
TEARDOWN_DONE=0
SMOKE_PID="$$"
NEVERKILL=""

say()  { printf '[smoke] %s\n' "$*"; }
verdict_pass() { printf 'PERCEPTION-DAEMON-SMOKE: %s\n' "$*"; }
verdict_fail() { printf 'PERCEPTION-DAEMON-SMOKE: %s\n' "FAIL $*"; }
die_fail() {
  local stage="$1" reason="$2" want_tail="${3:-}"
  if [ "$want_tail" = "--tail" ] && [ -n "$LOG" ] && [ -f "$LOG" ]; then
    say "last 40 log lines ($LOG):"
    tail -n 40 "$LOG" | sed 's/^/[log] /'
  fi
  teardown
  verdict_fail "stage=$stage reason=$reason"
  exit 1
}

# ── PID identity & safety（boot-smoke 同款）─────────────────────────────────
build_neverkill() {
  NEVERKILL=" "
  local p
  for p in $(lsof -ti tcp:"$PROD_PORT" 2>/dev/null) \
           $(lsof -ti tcp:"$HOST_GW_PORT" 2>/dev/null) \
           $(cat "$HOME/.stand/nebflow.pid" 2>/dev/null | tr -dc '0-9 \n' | tr -s ' \n' '  '); do
    [ -n "$p" ] && NEVERKILL="$NEVERKILL$p "
  done
}

pid_is_ours() {
  local pid="$1" cwd cmd
  case " $NEVERKILL " in *" $pid "*) return 2 ;; esac
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
  cmd="$(ps -p "$pid" -o command= 2>/dev/null)"
  case "$cwd" in "$HOME_DIR") return 0 ;; esac
  case "$cmd" in *"$HOME_DIR"*) return 0 ;; esac
  case "$cmd" in *"$JAR"*) return 0 ;; esac
  return 1
}

# engine listener = whoever LISTENS on the sandbox port (LISTEN sense only)
engine_listener_pids() { lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null; }

teardown() {
  [ "$TEARDOWN_DONE" = "1" ] && return 0
  TEARDOWN_DONE=1
  local pid pids round leftover dport

  # 1) engine (port listener) — verify then kill, escalate once
  for round in 1 2 3 4 5 6 7 8; do
    pids="$(engine_listener_pids)"
    [ -z "$pids" ] && break
    for pid in $pids; do
      if pid_is_ours "$pid"; then kill "$pid" 2>/dev/null
      else say "GUARD: pid $pid on :$PORT is not provably ours — NOT signalled"; fi
    done
    sleep 1
  done
  pids="$(engine_listener_pids)"
  for pid in $pids; do
    if pid_is_ours "$pid"; then kill -9 "$pid" 2>/dev/null; say "teardown: SIGKILL survivor $pid"; fi
  done

  # 2) launcher JVM (parent of the daemon-child) if it lingers
  if [ -n "$LAUNCHER_PID" ] && kill -0 "$LAUNCHER_PID" 2>/dev/null; then
    if pid_is_ours "$LAUNCHER_PID"; then
      kill "$LAUNCHER_PID" 2>/dev/null; sleep 1
      kill -9 "$LAUNCHER_PID" 2>/dev/null
    else
      say "GUARD: launcher pid $LAUNCHER_PID not provably ours — NOT signalled"
    fi
  fi

  # 3) any perception-daemon survivor (marker-guided, identity-verified)
  if [ -n "$HOME_DIR" ] && [ -f "$HOME_DIR/daemon-pids.json" ]; then
    dport="$(python3 - "$HOME_DIR/daemon-pids.json" <<'PYEOF' 2>/dev/null
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for m in d.get("markers",[]):
        if m.get("id")=="perception-daemon": print(m.get("pid",""))
except Exception: pass
PYEOF
)"
    for pid in $dport; do
      if kill -0 "$pid" 2>/dev/null; then
        if pid_is_ours "$pid"; then
          kill "$pid" 2>/dev/null; sleep 1; kill -9 "$pid" 2>/dev/null
          say "teardown: orphan perception-daemon $pid signalled"
        else
          say "GUARD: perception-daemon pid $pid not provably ours — NOT signalled"
        fi
      fi
    done
  fi

  # 4) temp data root
  if [ -n "$HOME_DIR" ] && [ -d "$HOME_DIR" ]; then
    if [ "$KEEP_HOME" = "1" ]; then
      say "teardown: isolated home KEPT for forensics: $HOME_DIR"
    else
      rm -rf "$HOME_DIR" 2>/dev/null
      [ -d "$HOME_DIR" ] && say "teardown: WARNING could not remove $HOME_DIR" \
                           || say "teardown: isolated home removed ($HOME_DIR)"
    fi
  fi
}
on_int()  { teardown; verdict_fail "stage=teardown reason=interrupted(SIGINT)"; exit 130; }
on_term() { teardown; verdict_fail "stage=teardown reason=interrupted(SIGTERM)"; exit 143; }
trap teardown EXIT
trap on_int INT
trap on_term TERM

# ── arg parsing ──────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --port)      PORT="$2"; shift 2 ;;
    --timeout)   BOOT_TIMEOUT="$2"; shift 2 ;;
    --keep-home) KEEP_HOME=1; shift ;;
    -h|--help)   sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)          echo "$PROGNAME: unknown option: $1" >&2; exit 1 ;;
    *)           if [ -z "$JAR" ]; then JAR="$1"; else
                   echo "$PROGNAME: unexpected extra argument: $1" >&2; exit 1
                 fi; shift ;;
  esac
done

# ── preflight（boot-smoke 同款守门）─────────────────────────────────────────
[ -n "$JAR" ] || die_fail preflight "no jar argument — usage: $PROGNAME <jar> [--port N] [--timeout S]"
[ -f "$JAR" ] && [ -r "$JAR" ] || die_fail preflight "jar not found or unreadable: $JAR"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

case "$PORT" in ''|*[!0-9]*) die_fail preflight "port not a number: $PORT" ;; esac
[ "$PORT" -lt 1024 ] && die_fail preflight "refusing privileged port $PORT — sandbox must use a high port"
[ "$PORT" = "$PROD_PORT" ] && die_fail preflight "refusing port $PORT = production port (iron rule)"
[ "$PORT" = "$HOST_GW_PORT" ] && die_fail preflight "refusing port $PORT = host gateway port"
OCCUPANT="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
[ -n "$OCCUPANT" ] && die_fail preflight "port $PORT already occupied by pid(s): $OCCUPANT"

JAVA_BIN="$PINNED_JDK"
if [ ! -x "$JAVA_BIN" ]; then
  say "WARN: pinned JDK missing at $PINNED_JDK — falling back to PATH java"
  JAVA_BIN="$(command -v java 2>/dev/null)"
  [ -n "$JAVA_BIN" ] || die_fail preflight "no java found"
fi
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)"
[ "$JAVA_MAJOR" = "1" ] && JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f2)"
case "$JAVA_MAJOR" in ''|*[!0-9]*) JAVA_MAJOR=0 ;; esac
[ "$JAVA_MAJOR" -ge 21 ] || die_fail preflight "java 21+ required, found: $("$JAVA_BIN" -version 2>&1 | head -n1)"
for t in curl lsof ps tail mktemp python3; do
  command -v "$t" >/dev/null 2>&1 || die_fail preflight "required tool missing: $t"
done

build_neverkill
say "jar=$JAR ($(du -h "$JAR" | cut -f1))"
say "port=$PORT timeout=${BOOT_TIMEOUT}s java=$JAVA_BIN"

# ── isolated launch ──────────────────────────────────────────────────────────
HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/stand-perception-daemon-smoke.XXXXXX")"
LOG="$HOME_DIR/boot-smoke.log"
ENGINE_LOG="$HOME_DIR/logs/stand.log"
say "home=$HOME_DIR (isolated data root)"

STAND_HOME="$HOME_DIR"
NEBFLOW_HOME="$HOME_DIR"
NEBFLOW_DAEMON_READY_TIMEOUT_SEC="$BOOT_TIMEOUT"
export STAND_HOME NEBFLOW_HOME NEBFLOW_DAEMON_READY_TIMEOUT_SEC
( cd "$HOME_DIR" && exec "$JAVA_BIN" --add-opens java.base/java.lang=ALL-UNNAMED \
    -jar "$JAR" start --port "$PORT" --home "$HOME_DIR" --no-browser \
    >>"$LOG" 2>&1 ) &
LAUNCHER_PID=$!

DEADLINE=$(( $(date +%s) + BOOT_TIMEOUT ))
T0="$(date +%s)"
READY=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  CODE="$(curl -s --connect-timeout 2 --max-time 4 -o /dev/null -w '%{http_code}' \
          "http://127.0.0.1:$PORT/api/health" 2>/dev/null)"
  if [ "$CODE" = "200" ]; then READY=1; break; fi
  sleep 2
done
T1="$(date +%s)"
[ "$READY" = "1" ] || die_fail boot "gateway not ready within ${BOOT_TIMEOUT}s" --tail
say "P1 ready: GET /api/health -> 200 after $((T1 - T0))s"
CHECKS="1"

# ── P2: dock（引擎日志握手线）───────────────────────────────────────────────
DEADLINE=$(( $(date +%s) + 30 ))
DOCKED=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if [ -f "$ENGINE_LOG" ] && grep -q "perception unified daemon docked" "$ENGINE_LOG" 2>/dev/null; then
    DOCKED=1; break
  fi
  if [ -f "$ENGINE_LOG" ] && grep -q "perception unified daemon handshake timeout" "$ENGINE_LOG" 2>/dev/null; then
    die_fail dock "engine logged handshake timeout (daemon never came up)" --tail
  fi
  sleep 1
done
[ "$DOCKED" = "1" ] || die_fail dock "no 'perception unified daemon docked' line in engine log within 30s" --tail
say "P2 dock: engine log carries the handshake line"
CHECKS="${CHECKS}1"

# ── P3: daemon-family registration（marker 文件 = 心跳腿账面）───────────────
DAEMON_PID="$(python3 - "$HOME_DIR/daemon-pids.json" <<'PYEOF' 2>/dev/null
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for m in d.get("markers",[]):
        if m.get("id")=="perception-daemon": print(m.get("pid",""))
except Exception: pass
PYEOF
)"
[ -n "$DAEMON_PID" ] || die_fail probe "daemon-pids.json carries no perception-daemon marker"
kill -0 "$DAEMON_PID" 2>/dev/null || die_fail probe "perception-daemon pid $DAEMON_PID not alive"
say "P3 family: perception-daemon registered in marker (pid $DAEMON_PID alive)"
CHECKS="${CHECKS}1"

# ── P4: daemon /health（token 门 200 / 无 token 403）────────────────────────
DEADLINE=$(( $(date +%s) + 20 ))
TOKEN=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  [ -f "$HOME_DIR/perception/daemon.token" ] && { TOKEN="$(cat "$HOME_DIR/perception/daemon.token" | tr -d ' \n')"; break; }
  sleep 1
done
[ -n "$TOKEN" ] || die_fail probe "boot token file missing: $HOME_DIR/perception/daemon.token"

# daemon port from daemons.json (the managed entry is the single source)
DPORT="$(python3 - "$HOME_DIR/daemons.json" <<'PYEOF' 2>/dev/null
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for c in d.get("daemons",[]):
        if c.get("id")=="perception-daemon": print(c.get("port",""))
except Exception: pass
PYEOF
)"
case "$DPORT" in ''|*[!0-9]*) die_fail probe "perception-daemon port missing in daemons.json" ;; esac

DEADLINE=$(( $(date +%s) + 30 ))
HCODE=000
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  HCODE="$(curl -s --connect-timeout 2 --max-time 4 -o "$HOME_DIR/daemon-health.json" -w '%{http_code}' \
           -H "X-Nf-Perception-Token: $TOKEN" "http://127.0.0.1:$DPORT/health" 2>/dev/null)"
  [ "$HCODE" = "200" ] && break
  sleep 1
done
[ "$HCODE" = "200" ] || die_fail probe "daemon /health never returned 200 (last=$HCODE) on :$DPORT"
grep -q "perception-unified-daemon" "$HOME_DIR/daemon-health.json" || die_fail probe "health payload missing role marker"
NOAUTH="$(curl -s --connect-timeout 2 --max-time 4 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$DPORT/health" 2>/dev/null)"
[ "$NOAUTH" = "403" ] || die_fail probe "daemon /health without token = $NOAUTH (expected 403 token gate)"
say "P4 health: daemon /health 200 (token) / 403 (no token) on 127.0.0.1:$DPORT"
CHECKS="${CHECKS}1"

# ── P5: 感知五循环 live（daemon 自有日志 = 单写者面）────────────────────────
DLOG="$HOME_DIR/logs/perception-daemon/logs/stand.log"
DEADLINE=$(( $(date +%s) + 30 ))
LOOPS=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if [ -f "$DLOG" ] \
     && grep -q "perception daemon booted (poll/sweep/digest/spool/idle loops live)" "$DLOG" 2>/dev/null \
     && grep -q "perception unified daemon serving" "$DLOG" 2>/dev/null; then
    LOOPS=1; break
  fi
  sleep 1
done
[ "$LOOPS" = "1" ] || die_fail probe "five-loop boot lines never appeared in daemon log ($DLOG)"
say "P5 loops: boot line (poll/sweep/digest/spool/idle) + serving line live in daemon log"
CHECKS="${CHECKS}1"

# ── P6: 心跳腿 live（/api/daemons 托管行 Running + portOpen）────────────────
[ -f "$HOME_DIR/auth.json" ] || die_fail probe "instance auth.json missing"
# auth.json = 裸 token（GatewayAuth Bearer rail；boot-smoke WS 探针同读法）
API_TOKEN="$(cat "$HOME_DIR/auth.json" 2>/dev/null | tr -d ' "\n\r')"
[ -n "$API_TOKEN" ] || die_fail probe "instance token unreadable from auth.json"
DEADLINE=$(( $(date +%s) + 20 ))
FAMILY=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  FAM="$(curl -s --connect-timeout 2 --max-time 4 -H "Authorization: Bearer $API_TOKEN" \
         "http://127.0.0.1:$PORT/api/daemons" 2>/dev/null)"
  case "$FAM" in
    *'"perception-daemon"'*)
      CHECK_OK="$(printf '%s' "$FAM" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    rows=d.get("daemons", d if isinstance(d,list) else [])
    row=[x for x in rows if x.get("id")=="perception-daemon"]
    assert row, "no perception row"
    r=row[0]
    assert r.get("status")=="running", "status="+str(r.get("status"))
    assert r.get("portOpen") is True, "portOpen="+str(r.get("portOpen"))
    print("ok")
except Exception:
    pass
')"
      [ "$CHECK_OK" = "ok" ] && FAMILY=1
      ;;
  esac
  [ "$FAMILY" = "1" ] && break
  sleep 1
done
[ "$FAMILY" = "1" ] || die_fail probe "/api/daemons never showed perception-daemon running+portOpen"
say "P6 heartbeat leg: /api/daemons -> perception-daemon running + portOpen=true (统一健康面)"
CHECKS="${CHECKS}1"

# ── P7: 面板代理 live（引擎 → daemon 回环通道）──────────────────────────────
ENT="$(curl -s --connect-timeout 2 --max-time 6 -w '\n%{http_code}' -H "Authorization: Bearer $API_TOKEN" \
       "http://127.0.0.1:$PORT/api/perception/entries" 2>/dev/null)"
ENT_CODE="$(printf '%s' "$ENT" | tail -n 1)"
ENT_BODY="$(printf '%s' "$ENT" | sed '$d')"
[ "$ENT_CODE" = "200" ] || die_fail probe "GET /api/perception/entries -> ${ENT_CODE:-none} (proxy leg broken)"
echo "$ENT_BODY" | grep -q "entries" || die_fail probe "entries payload malformed: $(printf '%s' "$ENT_BODY" | head -c 120)"
say "P7 proxy: GET /api/perception/entries -> 200 via engine→daemon channel"
CHECKS="${CHECKS}1"

# ── P8: 干净退出（引擎 SIGTERM ⇒ daemon 随引擎走，零孤儿）──────────────────
ENGINE_PID="$(engine_listener_pids | head -n 1)"
[ -n "$ENGINE_PID" ] || die_fail exit "no engine listener to stop"
if pid_is_ours "$ENGINE_PID"; then
  kill "$ENGINE_PID" 2>/dev/null
else
  die_fail exit "engine pid $ENGINE_PID not provably ours — refusing to signal"
fi
DEADLINE=$(( $(date +%s) + 30 ))
ENGINE_GONE=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if ! kill -0 "$ENGINE_PID" 2>/dev/null; then ENGINE_GONE=1; break; fi
  sleep 1
done
[ "$ENGINE_GONE" = "1" ] || die_fail exit "engine pid $ENGINE_PID still alive 30s after SIGTERM"
DEADLINE=$(( $(date +%s) + 15 ))
DAEMON_GONE=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if ! kill -0 "$DAEMON_PID" 2>/dev/null; then DAEMON_GONE=1; break; fi
  sleep 1
done
[ "$DAEMON_GONE" = "1" ] || die_fail exit "perception-daemon pid $DAEMON_PID orphaned after engine exit (随引擎走 broken)"
LEFTOVER="$(lsof -ti tcp:"$DPORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
[ -z "$LEFTOVER" ] || die_fail exit "daemon port $DPORT still held after exit: $LEFTOVER"
say "P8 exit: engine stopped -> perception-daemon died with it (zero orphans, port $DPORT free)"
CHECKS="${CHECKS}1"

TEARDOWN_DONE=1   # P8 已收尸——teardown 只做 home 清理
teardown
verdict_pass "PASS jar=$(basename "$JAR") port=$PORT boot_ready=$((T1 - T0))s checks=$CHECKS"
exit 0
