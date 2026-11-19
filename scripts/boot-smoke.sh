#!/usr/bin/env bash
# boot-smoke.sh — pre-release boot smoke for ANY stand assembly jar (工程化 M1 ③).
#
# What it proves, end to end, in one command:
#   1. the jar still BOOTS (isolated, fresh data root — the boot-OOM class of
#      failure dies here, not on the author's machine);
#   2. the portal page answers HTTP 200;
#   3. the gateway API surface answers (/api/health);
#   4. the WS surface completes a real RFC6455 handshake (101) using the token
#      the fresh instance minted; degraded to a second-endpoint probe when the
#      handshake cannot be tokenized (coverage is stated either way);
#   5. teardown leaves ZERO processes and ZERO bytes behind (idempotent —
#      back-to-back runs both go green).
#
# Isolation contract (iron rule: never touch production):
#   - data root = `mktemp -d`, wired via --home + STAND_HOME + NEBFLOW_HOME
#     (the jar's built-in isolation guard, EnrollGuard, auto-engages on a
#     redirected data root: the smoke instance never auto-enrolls with the
#     production neblink network);
#   - sandbox high port, default 8089, parameterizable via --port;
#   - production port 6138 and the host gateway port 8080 are REFUSED as
#     sandbox ports; an occupied sandbox port is refused, never adopted;
#   - every kill is PID-identity-verified (cwd must be the temp home, or the
#     cmdline must carry the temp home / the jar path) AND checked against a
#     never-kill ledger (prod listener, host-gateway listener, nebflow.pid)
#     before any signal is sent. An unverifiable PID is reported, not killed.
#
# Usage:
#   scripts/boot-smoke.sh <jar> [--port N] [--timeout S] [--keep-home]
#     <jar>         path to stand-assembly-*.jar (bad path = FAIL, exit 1)
#     --port N      sandbox port (default 8089; 6138/8080/<1024 refused)
#     --timeout S   boot readiness budget in seconds (default 120)
#     --keep-home   keep the isolated data root for forensics (default: removed)
#
# Output: [smoke] evidence lines, then exactly one verdict line:
#   BOOT-SMOKE: PASS jar=<name> port=<p> boot_ready=<S>s checks=<4-digit>
#   BOOT-SMOKE: FAIL stage=<preflight|boot|probe|teardown> reason=<one clause>
# Exit code: 0 = PASS, 1 = FAIL (any stage, incl. refusals) — consumable by a
# future release.sh gate as-is.
#
# bash-3.2 compatible (macOS stock bash). Tools: curl, lsof, ps, tail, mktemp,
# python3 (WS handshake probe; absent ⇒ WS check auto-degrades and says so).

set -u

PROGNAME="$(basename "$0")"
DEFAULT_PORT=8089
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
LAUNCHER_PID=""
TEARDOWN_DONE=0
SMOKE_PID="$$"
NEVERKILL=""

# ── tiny output helpers ──────────────────────────────────────────────────────
say()  { printf '[smoke] %s\n' "$*"; }
verdict_pass() { printf 'BOOT-SMOKE: %s\n' "$*"; }
verdict_fail() { printf 'BOOT-SMOKE: %s\n' "FAIL $*"; }
die_fail() { # die_fail <stage> <reason> [--tail]
  local stage="$1" reason="$2" want_tail="${3:-}"
  if [ "$want_tail" = "--tail" ] && [ -n "$LOG" ] && [ -f "$LOG" ]; then
    say "last 50 log lines ($LOG):"
    tail -n 50 "$LOG" | sed 's/^/[log] /'
  fi
  teardown
  verdict_fail "stage=$stage reason=$reason"
  exit 1
}

# ── PID identity & safety ────────────────────────────────────────────────────
# Never-kill ledger: every PID the author's live installation depends on.
build_neverkill() {
  NEVERKILL=" "
  local p
  for p in $(lsof -ti tcp:"$PROD_PORT" 2>/dev/null) \
           $(lsof -ti tcp:"$HOST_GW_PORT" 2>/dev/null) \
           $(cat "$HOME/.stand/nebflow.pid" 2>/dev/null | tr -dc '0-9 \n' | tr -s ' \n' '  '); do
    [ -n "$p" ] && NEVERKILL="$NEVERKILL$p "
  done
}

# 0 = ours (safe to signal), 1 = not provably ours, 2 = ledgered never-kill.
pid_is_ours() {
  local pid="$1" cwd cmd
  case " $NEVERKILL " in *" $pid "*) return 2 ;; esac
  # NOTE: Darwin 25.4 sed mis-parses `s/^n/p` (empty replacement + p flag) as a
  # newline-in-pattern error — the empty replacement must be spelled out: `s/^n//p`.
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
  cmd="$(ps -p "$pid" -o command= 2>/dev/null)"
  case "$cwd" in "$HOME_DIR") return 0 ;; esac
  case "$cmd" in *"$HOME_DIR"*) return 0 ;; esac
  case "$cmd" in *"$JAR"*) return 0 ;; esac
  return 1
}

# Idempotent teardown: verify-then-kill the port listener, then the launcher,
# reap, recheck the port, remove the temp home. Safe to call twice / on signals.
teardown() {
  [ "$TEARDOWN_DONE" = "1" ] && return 0
  TEARDOWN_DONE=1
  local pid pids round leftover

  # 1) the real server: whoever LISTENS on the sandbox port
  # (-sTCP:LISTEN: an unrelated app holding an ESTABLISHED conn with this port
  #  on either end must never enter the kill set — over-inclusive reads are for
  #  the never-kill ledger, never for kills)
  for round in 1 2 3 4 5 6 7 8 9 10; do
    pids="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null)"
    [ -z "$pids" ] && break
    for pid in $pids; do
      if pid_is_ours "$pid"; then
        kill "$pid" 2>/dev/null
      else
        say "GUARD: pid $pid on :$PORT is not provably ours — NOT signalled (report, don't kill)"
      fi
    done
    sleep 0.5 2>/dev/null || sleep 1
  done
  pids="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null)"
  for pid in $pids; do
    if pid_is_ours "$pid"; then
      kill -9 "$pid" 2>/dev/null
      say "teardown: SIGKILL survivor $pid on :$PORT"
    fi
  done

  # 2) the launcher JVM (daemon dispatch spawns a child; parent may linger)
  if [ -n "$LAUNCHER_PID" ] && kill -0 "$LAUNCHER_PID" 2>/dev/null; then
    if pid_is_ours "$LAUNCHER_PID"; then
      kill "$LAUNCHER_PID" 2>/dev/null
      sleep 0.5 2>/dev/null || sleep 1
      kill -9 "$LAUNCHER_PID" 2>/dev/null
    else
      say "GUARD: launcher pid $LAUNCHER_PID not provably ours — NOT signalled"
    fi
  fi
  wait "$SMOKE_PID" 2>/dev/null

  # 3) port must be free again (listener sense — see kill-set note above)
  leftover="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
  if [ -n "$leftover" ]; then
    say "teardown: WARNING port $PORT still held by: $leftover (unverified owners left untouched)"
  else
    say "teardown: port $PORT free"
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

# ── preflight ────────────────────────────────────────────────────────────────
[ -n "$JAR" ] || die_fail preflight "no jar argument — usage: $PROGNAME <jar> [--port N] [--timeout S]"

if [ ! -f "$JAR" ] || [ ! -r "$JAR" ]; then
  die_fail preflight "jar not found or unreadable: $JAR"
fi
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

case "$PORT" in
  ''|*[!0-9]*) die_fail preflight "port not a number: $PORT" ;;
esac
if [ "$PORT" -lt 1024 ]; then
  die_fail preflight "refusing privileged port $PORT — sandbox must use a high port"
fi
if [ "$PORT" = "$PROD_PORT" ]; then
  die_fail preflight "refusing port $PORT = production port (iron rule: smoke never touches :6138)"
fi
if [ "$PORT" = "$HOST_GW_PORT" ]; then
  die_fail preflight "refusing port $PORT = host gateway port"
fi

OCCUPANT="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
if [ -n "$OCCUPANT" ]; then
  die_fail preflight "port $PORT already occupied by pid(s): $OCCUPANT — refusing to adopt a foreign instance"
fi

# pinned JDK first (same rule as bin/stand: a missing pinned JDK must warn loudly)
JAVA_BIN="$PINNED_JDK"
if [ ! -x "$JAVA_BIN" ]; then
  say "WARN: pinned JDK missing at $PINNED_JDK — falling back to PATH java"
  JAVA_BIN="$(command -v java 2>/dev/null)"
  [ -n "$JAVA_BIN" ] || die_fail preflight "no java found (pinned JDK missing, none on PATH)"
fi
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)"
[ "$JAVA_MAJOR" = "1" ] && JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f2)"
case "$JAVA_MAJOR" in ''|*[!0-9]*) JAVA_MAJOR=0 ;; esac
[ "$JAVA_MAJOR" -ge 21 ] || die_fail preflight "java 21+ required, found: $("$JAVA_BIN" -version 2>&1 | head -n1)"

for t in curl lsof ps tail mktemp; do
  command -v "$t" >/dev/null 2>&1 || die_fail preflight "required tool missing: $t"
done
HAVE_PY=1
command -v python3 >/dev/null 2>&1 || { HAVE_PY=0; say "WARN: python3 absent — WS handshake probe will degrade to second-endpoint probe"; }

build_neverkill
say "jar=$JAR ($(du -h "$JAR" | cut -f1) )"
say "port=$PORT timeout=${BOOT_TIMEOUT}s java=$JAVA_BIN"
[ -n "$(printf '%s' "$NEVERKILL" | tr -d ' ')" ] && say "never-kill ledger:$(printf '%s' "$NEVERKILL" | tr -s ' ' ' ')"

# ── isolated launch ──────────────────────────────────────────────────────────
HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/stand-boot-smoke.XXXXXX")"
LOG="$HOME_DIR/boot-smoke.log"
say "home=$HOME_DIR (isolated data root; cwd of the boot = the home itself)"

# NEBFLOW_DAEMON_READY_TIMEOUT_SEC: keep the launcher's internal ready-wait on
# the SAME clock as this harness (default inside the jar is 120s too). Exported
# BEFORE the launch so the spawned launcher inherits it.
NEBFLOW_DAEMON_READY_TIMEOUT_SEC="$BOOT_TIMEOUT"
export NEBFLOW_DAEMON_READY_TIMEOUT_SEC
( cd "$HOME_DIR" && exec "$JAVA_BIN" --add-opens java.base/java.lang=ALL-UNNAMED \
    -jar "$JAR" start --port "$PORT" --home "$HOME_DIR" --no-browser \
    >>"$LOG" 2>&1 ) &
LAUNCHER_PID=$!

# ── readiness poll (≤ BOOT_TIMEOUT) ─────────────────────────────────────────
DEADLINE=$(( $(date +%s) + BOOT_TIMEOUT ))
T0="$(date +%s)"
LISTENER_SEEN=0
READY=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  LPIDS="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
  if [ -n "$LPIDS" ]; then
    LISTENER_SEEN=1
  elif [ "$LISTENER_SEEN" = "1" ]; then
    die_fail boot "listener appeared on :$PORT then vanished mid-boot (boot-death/OOM class)" --tail
  fi
  CODE="$(curl -s --connect-timeout 2 --max-time 4 -o /dev/null -w '%{http_code}' \
          "http://127.0.0.1:$PORT/api/health" 2>/dev/null)"
  if [ "$CODE" = "200" ]; then READY=1; break; fi
  sleep 2
done
T1="$(date +%s)"
[ "$READY" = "1" ] || die_fail boot "gateway not ready within ${BOOT_TIMEOUT}s (listener_seen=$LISTENER_SEEN)" --tail
say "ready: GET /api/health -> 200 after $((T1 - T0))s"

# ── deep checks ──────────────────────────────────────────────────────────────
CHECKS=""

# D1: portal page (brand-injected console at /)
P_CODE="$(curl -s --connect-timeout 2 --max-time 8 -o "$HOME_DIR/portal.html" -w '%{http_code}' \
          "http://127.0.0.1:$PORT/" 2>/dev/null)"
P_BYTES="$(wc -c < "$HOME_DIR/portal.html" 2>/dev/null | tr -d ' ')"
if [ "$P_CODE" = "200" ] && [ "${P_BYTES:-0}" -gt 0 ]; then
  say "portal: GET / -> 200 ($P_BYTES bytes)"
  CHECKS="${CHECKS}1"
else
  die_fail probe "portal page check failed: GET / -> ${P_CODE:-none} (${P_BYTES:-0} bytes)" --tail
fi

# D2: health payload evidence (deviceId key = the instance's own identity)
H_SNIP="$(curl -s --connect-timeout 2 --max-time 6 "http://127.0.0.1:$PORT/api/health" 2>/dev/null | head -c 160)"
case "$H_SNIP" in *deviceId*) say "health: payload carries deviceId (own instance identity)" ;; esac

# D3: real WS handshake with the token the fresh instance minted at
# <home>/auth.json (GatewayAuth Bearer rail). Non-101-but-HTTP answers (401/
# 403) still prove the WS surface is mounted behind the auth gate — that is
# the documented degraded coverage, plus a second-endpoint liveness probe.
WS_RESULT="degraded"
if [ "$HAVE_PY" = "1" ] && [ -f "$HOME_DIR/auth.json" ]; then
  WS_OUT="$(python3 - "$PORT" "$HOME_DIR/auth.json" <<'PYEOF'
import base64, os, socket, sys
port, token_path = int(sys.argv[1]), sys.argv[2]
token = open(token_path).read().strip().strip('"')
key = base64.b64encode(os.urandom(16)).decode()
req = ("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nUpgrade: websocket\r\n"
       "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n"
       "Authorization: Bearer %s\r\n\r\n" % (port, key, token))
try:
    s = socket.create_connection(("127.0.0.1", port), timeout=6)
except Exception as e:
    print("CONNERR %s" % e); sys.exit(5)
s.sendall(req.encode())
resp = b""
try:
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(4096)
        if not chunk: break
        resp += chunk
except Exception as e:
    print("CONNERR %s" % e); sys.exit(5)
head = resp.split(b"\r\n\r\n", 1)[0].decode(errors="replace")
status = head.split("\r\n")[0] if head else "EMPTY"
print(status)
if " 101 " in (" %s " % status):
    try:  # RFC6455 close frame (masked, empty payload), then go away
        mask = os.urandom(4)
        s.sendall(bytes([0x88, 0x80]) + bytes(b ^ mask[i % 4] for i, b in enumerate(mask)))
    except Exception:
        pass
    sys.exit(0)
sys.exit(4 if status.startswith("HTTP/") else 5)
PYEOF
)"
  WS_RC=$?
  WS_LINE="$(printf '%s' "$WS_OUT" | head -n1)"
  case "$WS_RC" in
    0) say "ws: handshake $WS_LINE (RFC6455 101, bearer token)"; WS_RESULT="ws101" ;;
    4) say "ws: handshake refused by auth gate: $WS_LINE — surface mounted, token path degraded"; ;;
    *) say "ws: probe error (rc=$WS_RC): $WS_LINE" ;;
  esac
else
  [ "$HAVE_PY" = "1" ] || say "ws: no python3 — skipping handshake probe (degraded)"
  [ -f "$HOME_DIR/auth.json" ] || say "ws: no auth.json in isolated home — skipping handshake probe (degraded)"
fi
if [ "$WS_RESULT" != "ws101" ]; then
  LB_CODE="$(curl -s --connect-timeout 2 --max-time 6 -o /dev/null -w '%{http_code}' \
             "http://127.0.0.1:$PORT/api/health/loopback-info" 2>/dev/null)"
  if [ "$LB_CODE" = "200" ]; then
    say "degraded probe: GET /api/health/loopback-info -> 200 (second endpoint alive)"
    CHECKS="${CHECKS}2"
  else
    die_fail probe "WS handshake unavailable AND degraded probe failed: /api/health/loopback-info -> ${LB_CODE:-none}" --tail
  fi
else
  CHECKS="${CHECKS}1"
fi

# evidence: a couple of load-bearing boot lines, best-effort
EV="$(grep -m2 -E '([Ll]isten|bound|[Ss]tart(ing|ed)|ready|[Pp]ort)' "$LOG" 2>/dev/null | head -2)"
[ -n "$EV" ] && printf '%s\n' "$EV" | sed 's/^/[boot] /'

# ── teardown + verdict ───────────────────────────────────────────────────────
teardown
if [ -n "$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null)" ]; then
  die_fail teardown "sandbox port $PORT still occupied after teardown"
fi
if [ -d "$HOME_DIR" ] && [ "$KEEP_HOME" != "1" ]; then
  die_fail teardown "temp home $HOME_DIR survived cleanup"
fi

say "no leftover processes on :$PORT; temp root cleaned (idempotent — safe to rerun)"
verdict_pass "PASS jar=$(basename "$JAR") port=$PORT boot_ready=$((T1 - T0))s checks=$CHECKS (portal+health+ws-or-degraded+teardown)"
exit 0
