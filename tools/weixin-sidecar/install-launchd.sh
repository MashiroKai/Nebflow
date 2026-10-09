#!/bin/bash
# Install the Nebflow weixin side-car as an independent LaunchAgent.
# Watchdog-independent by design (the Nebflow watchdog removal does not affect
# this agent). Backs up any existing plist before overwrite (backup discipline).
#
# Config generation / platform installation are decoupled (weixin channel
# binding auto-wire batch): the environment-driven config parse face below is
# shared, `--print-config` exposes it as platform-neutral JSON (zero launchd
# touch, zero disk write — future non-macOS installers consume the same parse
# result), and the macOS launchd leg stays the only platform install face.
# The engine calls this script with injected env right after a successful bind
# scan; with every WEIXIN_*/NEBFLOW_HOME input absent the emitted plist is
# byte-for-byte the historical one (bare-run behavior unchanged). When the
# parsed config's ProgramArguments and EnvironmentVariables equal the already
# installed plist key-for-key, the install face takes a no-change fast path:
# it prints "unchanged" and skips backup/write/bootout/bootstrap entirely (a
# repeated bind with an unchanged config must not restart the sidecar).
#
# Environment inputs (a provided variable is written into the plist's
# EnvironmentVariables; absent ones are simply not written):
#   WEIXIN_SIDECAR_PORT         sidecar control port     (default 8787)
#   WEIXIN_MSGLOOP_INGRESS_URL  engine ingress full URL  (optional)
#   WEIXIN_MSGLOOP_TOKEN_FILE   msgloop ingress token file path (optional;
#                               msgloop-leg-dedicated name, v2 ruling)
#   NEBFLOW_HOME                engine data root; pins the outbound send leg's
#                               default token path to <NEBFLOW_HOME>/secrets/
#                               weixin-ilink-send-token (optional)
# Forbidden inputs (fail-closed: a set-and-non-empty value exits 2 with
# an explicit error; never written into the plist, never in --print-config
# output). They belong to the outbound send leg, which shares this process env
# with the msgloop leg — carrying one into a provision run would hijack the
# outbound leg (ingress token in the send slot → 403):
#   WEIXIN_SEND_TOKEN  WEIXIN_SEND_TOKEN_FILE
# Rehearsal-only override (offline drills; real install path unchanged):
#   WEIXIN_SIDECAR_PLIST_DIR    plist output dir (default $HOME/Library/LaunchAgents)
set -euo pipefail

LABEL="com.nebflow.weixin-sidecar"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
SHIM="$SRC_DIR/shim.mjs"
NODE_BIN="$(node -p 'process.execPath')"
DEFAULT_PORT="8787" # WeixinIlinkScanBind.SidecarDefaultBase (historical default)
PLIST_DIR="${WEIXIN_SIDECAR_PLIST_DIR:-$HOME/Library/LaunchAgents}"
PLIST="$PLIST_DIR/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/nebflow-weixin-sidecar"
UID_N="$(id -u)"

# ── flags ──
PRINT_CONFIG=0
for arg in "$@"; do
  case "$arg" in
    --print-config) PRINT_CONFIG=1 ;;
    *)
      echo "unknown argument: $arg (supported: --print-config)" >&2
      exit 2
      ;;
  esac
done

# ── forbidden-zone arm (design r2 §1.2 C1.1, v2 ruling) ──
# WEIXIN_SEND_TOKEN[_FILE] belong to the outbound send leg; the msgloop leg
# shares this process env, so a provision run carrying either — set and
# non-empty — would hijack the outbound leg (ingress token in the send slot
# → 403). Nobody may set them to a non-empty value here: the generator
# refuses instead of silently dropping them (fail-closed; the arm also guards
# --print-config, and runs before any config generation / any disk or launchd
# touch). The check expands the named variable with an explicit default-empty
# form ( ${!VAR-} ): under `set -u` a fully unset input must expand to empty —
# bash 3.2 (macOS /bin/bash) aborts the whole script with "unbound variable"
# on a bare ${!VAR} — and a set-but-blank value is treated as not-provided,
# matching the trim semantics of the real inputs below.
for FORBIDDEN_VAR in WEIXIN_SEND_TOKEN WEIXIN_SEND_TOKEN_FILE; do
  if [ -n "${!FORBIDDEN_VAR-}" ]; then
    echo "ERROR: $FORBIDDEN_VAR is set but is a forbidden input for the weixin sidecar provision (send-leg env shared with the msgloop leg — writing it would hijack the outbound leg). Unset it for this run, e.g.: env -u $FORBIDDEN_VAR -- \"$0\"" >&2
    exit 2
  fi
done

# ── config parse face (platform-neutral) ──
# trim: empty after trim == "not provided" (same env semantics as shim.mjs /
# msgloop.mjs, which trim before use).
trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

xml_escape() { # plain value → XML text content (&amp; first)
  local s="$1"
  s="${s//&/&amp;}"
  s="${s//</&lt;}"
  s="${s//>/&gt;}"
  printf '%s' "$s"
}

json_escape() { # stdin → JSON string body (unquoted); sentinel keeps trailing newlines
  local s
  s="$(cat && printf X)"
  s="${s%X}"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\r'/\\r}"
  printf '%s' "$s"
}

PORT_RAW="$(trim "${WEIXIN_SIDECAR_PORT:-}")"
INGRESS_URL="$(trim "${WEIXIN_MSGLOOP_INGRESS_URL:-}")"
MSGLOOP_TOKEN_FILE="$(trim "${WEIXIN_MSGLOOP_TOKEN_FILE:-}")"
NEBFLOW_HOME_DIR="$(trim "${NEBFLOW_HOME:-}")"

if [ -n "$PORT_RAW" ]; then
  case "$PORT_RAW" in
    *[!0-9]*)
      echo "ERROR: WEIXIN_SIDECAR_PORT must be a plain integer, got: $PORT_RAW" >&2
      exit 2
      ;;
  esac
  PORT=$((10#$PORT_RAW))
  if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo "ERROR: WEIXIN_SIDECAR_PORT out of range 1-65535: $PORT_RAW" >&2
    exit 2
  fi
else
  PORT="$DEFAULT_PORT"
fi

# EnvironmentVariables: ONLY provided variables are written (a bare run keeps
# the historical single-key dict, byte-for-byte).
ENV_EXTRA=""
if [ -n "$INGRESS_URL" ]; then
  ENV_EXTRA+=$'\n    <key>WEIXIN_MSGLOOP_INGRESS_URL</key><string>'"$(xml_escape "$INGRESS_URL")"'</string>'
fi
if [ -n "$MSGLOOP_TOKEN_FILE" ]; then
  ENV_EXTRA+=$'\n    <key>WEIXIN_MSGLOOP_TOKEN_FILE</key><string>'"$(xml_escape "$MSGLOOP_TOKEN_FILE")"'</string>'
fi
if [ -n "$NEBFLOW_HOME_DIR" ]; then
  ENV_EXTRA+=$'\n    <key>NEBFLOW_HOME</key><string>'"$(xml_escape "$NEBFLOW_HOME_DIR")"'</string>'
fi

# The exact plist text to be written (shared by both faces; the sentinel keeps
# the trailing newline so the install write stays byte-identical).
PLIST_TEXT="$(
  cat <<PLIST_TEMPLATE && printf X
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$NODE_BIN</string>
    <string>$SHIM</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$LOG_DIR/out.log</string>
  <key>StandardErrorPath</key><string>$LOG_DIR/err.log</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>WEIXIN_SIDECAR_PORT</key><string>$PORT</string>$ENV_EXTRA
  </dict>
</dict>
</plist>
PLIST_TEMPLATE
)"
PLIST_TEXT="${PLIST_TEXT%X}"

print_config() {
  printf '{\n'
  printf '  "port": %s,\n' "$PORT"
  if [ -n "$INGRESS_URL" ]; then
    printf '  "ingressUrl": "%s",\n' "$(printf '%s' "$INGRESS_URL" | json_escape)"
  else
    printf '  "ingressUrl": null,\n'
  fi
  if [ -n "$MSGLOOP_TOKEN_FILE" ]; then
    printf '  "tokenFile": "%s",\n' "$(printf '%s' "$MSGLOOP_TOKEN_FILE" | json_escape)"
  else
    printf '  "tokenFile": null,\n'
  fi
  if [ -n "$NEBFLOW_HOME_DIR" ]; then
    printf '  "nebflowHome": "%s",\n' "$(printf '%s' "$NEBFLOW_HOME_DIR" | json_escape)"
  else
    printf '  "nebflowHome": null,\n'
  fi
  printf '  "plistPath": "%s",\n' "$(printf '%s' "$PLIST" | json_escape)"
  printf '  "logDir": "%s",\n' "$(printf '%s' "$LOG_DIR" | json_escape)"
  printf '  "plistText": "%s"\n' "$(printf '%s' "$PLIST_TEXT" | json_escape)"
  printf '}\n'
}

[ -f "$SHIM" ] || { echo "shim not found: $SHIM" >&2; exit 1; }

if [ "$PRINT_CONFIG" -eq 1 ]; then
  print_config # config face only: no launchd touch, no disk write
  exit 0
fi

# ── no-change fast path (design r2 §3.3) ──
# If the existing plist already carries exactly the ProgramArguments and the
# EnvironmentVariables this run would write, skip backup/write/bootout/
# bootstrap entirely and print "unchanged": a repeated bind with an unchanged
# config must not restart the sidecar. The EnvironmentVariables comparison is
# key-by-key (same key set, same string value per key — dict order is not a
# difference). Anything not provably equal (no existing plist, malformed
# plist, comparison tooling trouble) falls through to the full install path
# below, whose behavior stays byte-for-byte the historical one.
if [ -f "$PLIST" ]; then
  FAST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/weixin-sidecar-provision.XXXXXX" 2>/dev/null)" || FAST_TMP=""
  FAST_SAME=0
  if [ -n "$FAST_TMP" ]; then
    printf '%s' "$PLIST_TEXT" > "$FAST_TMP/candidate.plist"
    if plutil -convert json -o "$FAST_TMP/old.json" "$PLIST" 2>/dev/null \
      && plutil -convert json -o "$FAST_TMP/new.json" "$FAST_TMP/candidate.plist" 2>/dev/null \
      && "$NODE_BIN" -e '
        const fs = require("fs");
        const old = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
        const next = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
        const sameArgs =
          JSON.stringify(old.ProgramArguments ?? null) ===
          JSON.stringify(next.ProgramArguments ?? null);
        const ko = old.EnvironmentVariables ?? null;
        const kn = next.EnvironmentVariables ?? null;
        let sameEnv = (ko === null) === (kn === null);
        if (ko !== null && kn !== null) {
          const a = Object.keys(ko).sort();
          const b = Object.keys(kn).sort();
          sameEnv =
            JSON.stringify(a) === JSON.stringify(b) &&
            a.every((k) => typeof ko[k] === "string" && ko[k] === kn[k]);
        }
        process.exit(sameArgs && sameEnv ? 0 : 1);
      ' "$FAST_TMP/old.json" "$FAST_TMP/new.json"
    then
      FAST_SAME=1
    fi
    rm -rf "$FAST_TMP"
  fi
  if [ "$FAST_SAME" -eq 1 ]; then
    echo "unchanged"
    exit 0
  fi
fi

# ── platform install face (macOS launchd) ──
mkdir -p "$PLIST_DIR" "$LOG_DIR"

if [ -f "$PLIST" ]; then
  BAK="$PLIST.bak-$(date +%Y%m%d-%H%M%S)"
  cp "$PLIST" "$BAK"
  echo "backup: $PLIST → $BAK"
fi

printf '%s' "$PLIST_TEXT" > "$PLIST"

# stop a manual instance first (port conflict otherwise) — NARROWED: only a
# listener whose command line contains shim.mjs may be stopped, and only on
# the RESOLVED sidecar port; any other listener is a hard refusal (a host may
# run unrelated listeners — never kill them).
PORT_PIDS="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null || true)"
if [ -n "$PORT_PIDS" ]; then
  SHIM_PIDS=""
  FOREIGN_PIDS=""
  # intentional word splitting: lsof -ti yields one pid per line
  # shellcheck disable=SC2086
  for pid in $PORT_PIDS; do
    if ps -ww -p "$pid" -o command= 2>/dev/null | grep -q 'shim\.mjs'; then
      SHIM_PIDS="$SHIM_PIDS $pid"
    else
      FOREIGN_PIDS="$FOREIGN_PIDS $pid"
    fi
  done
  if [ -n "$FOREIGN_PIDS" ]; then
    echo "ERROR: port $PORT is held by non-sidecar listener(s):$FOREIGN_PIDS —" >&2
    echo "       refusing to kill unrelated processes; free the port or set WEIXIN_SIDECAR_PORT, then re-run" >&2
    exit 1
  fi
  echo "port $PORT busy with prior sidecar shim instance(s):$SHIM_PIDS — stopping them"
  # intentional word splitting: space-separated pid list
  # shellcheck disable=SC2086
  kill $SHIM_PIDS 2>/dev/null || true
  sleep 1
fi

launchctl bootout "gui/$UID_N/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$UID_N" "$PLIST"

sleep 1
echo "installed: $PLIST"
echo "state: $(launchctl print "gui/$UID_N/$LABEL" 2>/dev/null | sed -n 's/.*state = //p' | head -1)"
echo "probe: curl -s http://127.0.0.1:$PORT/healthz"
echo "logs:  $LOG_DIR/out.log $LOG_DIR/err.log"
