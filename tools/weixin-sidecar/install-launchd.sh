#!/bin/bash
# Install the Nebflow weixin side-car as an independent LaunchAgent.
# Watchdog-independent by design (the Nebflow watchdog removal does not affect
# this agent). Backs up any existing plist before overwrite (backup discipline).
set -euo pipefail

LABEL="com.nebflow.weixin-sidecar"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
SHIM="$SRC_DIR/shim.mjs"
NODE_BIN="$(node -p 'process.execPath')"
PLIST_DIR="$HOME/Library/LaunchAgents"
PLIST="$PLIST_DIR/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/nebflow-weixin-sidecar"
UID_N="$(id -u)"

[ -f "$SHIM" ] || { echo "shim not found: $SHIM" >&2; exit 1; }
mkdir -p "$PLIST_DIR" "$LOG_DIR"

if [ -f "$PLIST" ]; then
  BAK="$PLIST.bak-$(date +%Y%m%d-%H%M%S)"
  cp "$PLIST" "$BAK"
  echo "backup: $PLIST → $BAK"
fi

cat > "$PLIST" <<EOF
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
    <key>WEIXIN_SIDECAR_PORT</key><string>8787</string>
  </dict>
</dict>
</plist>
EOF

# stop a manual instance first (port conflict otherwise)
if lsof -ti tcp:8787 >/dev/null 2>&1; then
  echo "port 8787 busy — stopping the current listener"
  lsof -ti tcp:8787 | xargs kill || true
  sleep 1
fi

launchctl bootout "gui/$UID_N/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$UID_N" "$PLIST"

sleep 1
echo "installed: $PLIST"
echo "state: $(launchctl print "gui/$UID_N/$LABEL" 2>/dev/null | sed -n 's/.*state = //p' | head -1)"
echo "probe: curl -s http://127.0.0.1:8787/healthz"
echo "logs:  $LOG_DIR/out.log $LOG_DIR/err.log"
