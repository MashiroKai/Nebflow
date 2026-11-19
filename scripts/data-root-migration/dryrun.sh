#!/bin/bash
# dryrun.sh — 在 /tmp 用假数据把 precopy → finalize → verify → rollback 全流程走一遍
# 只操作 /tmp 沙箱（NEBFLOW_MIGRATE_* 全量重定向）；真实 ~/.nebflow / ~/.stand 不被触碰。
# 产物：沙箱目录 + <script_dir>/evidence/dryrun-<ts>.log
set -u
SDIR="$(cd "$(dirname "$0")" && pwd)"
EVDIR="$SDIR/evidence"
mkdir -p "$EVDIR"
TS="$(date +%Y%m%d-%H%M%S)"
ROOT="$(mktemp -d /tmp/stand-dryrun-$TS.XXXXXX)"
LOG="$ROOT/dryrun.log"
EVCOPY="$EVDIR/dryrun-$TS.log"

export NEBFLOW_MIGRATE_SRC="$ROOT/home/.nebflow"
export NEBFLOW_MIGRATE_DST="$ROOT/home/.stand"
export NEBFLOW_MIGRATE_PLIST_DIR="$ROOT/LaunchAgents"
export NEBFLOW_MIGRATE_ENGINE_GUARD=0

SRC="$NEBFLOW_MIGRATE_SRC"; DST="$NEBFLOW_MIGRATE_DST"; PL="$NEBFLOW_MIGRATE_PLIST_DIR"
FAILS=0
ok() { echo "ASSERT-PASS: $1" | tee -a "$LOG"; }
bad() { echo "ASSERT-FAIL: $1" | tee -a "$LOG"; FAILS=$((FAILS+1)); }
run() { echo "+ $*" | tee -a "$LOG"; "$@" >>"$LOG" 2>&1; local rc=$?; echo "  (exit=$rc)" | tee -a "$LOG"; return $rc; }

{
echo "=== DRYRUN @ $TS  sandbox=$ROOT ==="
echo "SRC=$SRC"; echo "DST=$DST"; echo "PLIST_DIR=$PL"

# ---------- fixture ----------
mkdir -p "$SRC"/{delegates,sessions,chains,neblink,bin,logs} "$PL" "$ROOT/home"
cat > "$SRC/tasks-v2.json" <<'EOF'
{"version":1,"tasks":[{"id":"1","title":"t1","status":"open"},{"id":"2","title":"t2","status":"open"},{"id":"3","title":"t3","status":"done"}]}
EOF
echo "[..1..2..3..]" > "$SRC/tasks.json"
echo "# User doc" > "$SRC/User.md"
echo '{"token":"FAKE-DRYRUN"}' > "$SRC/auth.json"
echo '{}' > "$SRC/preferences.json"; echo '{}' > "$SRC/models.json"; echo '{}' > "$SRC/nebflow.json"
echo '{"seed":1}' > "$SRC/.seed-state.json"; echo '12345' > "$SRC/.pid"; echo 'meta' > "$SRC/.pid.meta"
echo "{\"daemons\":[{\"cwd\":\"$SRC/projects/demo\"}]}" > "$SRC/daemons.json"
echo "{\"installedAt\":\"x\",\"paths\":[\"$SRC/bin\"]}" > "$SRC/install-manifest.json"
printf '#!/bin/sh\nLOG="%s/logs/autostart.log"\nexec "%s/bin/nebflow" start\n' "$SRC" "$SRC" > "$SRC/bin/nebflow-autostart.sh"
echo '# memsample placeholder' > "$SRC/bin/memsample.sh"
for i in 1 2 3; do echo "{\"delegate\":\"d$i\"}" > "$SRC/delegates/delegate-d$i.json"; done
for i in 1 2 3 4 5; do echo "{\"session\":\"s$i\"}" > "$SRC/sessions/s$i.json"; done
echo 'tmp' > "$SRC/sessions/_index.json.tmp.abc"
echo '{"chain":"c1"}' > "$SRC/chains/c1.json"
echo '{"url":"https://example.invalid"}' > "$SRC/neblink/config.json"
dd if=/dev/zero of="$SRC/logs/big.bin" bs=1024 count=512 2>/dev/null
if command -v sqlite3 >/dev/null; then
  sqlite3 "$SRC/test.db" "CREATE TABLE t(x INTEGER); INSERT INTO t VALUES (1),(2),(3);" >/dev/null
fi
cat > "$PL/com.nebflow.weixin-sidecar.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
  <key>Label</key><string>com.nebflow.weixin-sidecar</string>
  <key>EnvironmentVariables</key><dict>
    <key>NEBFLOW_HOME</key><string>$SRC</string>
  </dict>
</dict></plist>
EOF
cat > "$PL/com.nebflow.gateway.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
  <key>Label</key><string>com.nebflow.gateway</string>
  <key>ProgramArguments</key><array>
    <string>/bin/bash</string><string>$SRC/bin/nebflow-autostart.sh</string>
  </array>
</dict></plist>
EOF
echo "fixture built: $(find "$SRC" | wc -l | tr -d ' ') entries"

# ---------- 1. precopy ----------
run bash "$SDIR/precopy.sh" || bad "precopy.sh exited non-zero"
[ -f "$DST/tasks-v2.json" ] && ok "precopy: tasks-v2.json present in DST" || bad "precopy: tasks-v2.json missing in DST"
[ -f "$DST/test.db" ] && ok "precopy: sqlite cloned" || bad "precopy: test.db missing"
[ -d "$SRC" ] && ok "precopy: source intact (zero-delete)" || bad "precopy: source gone?!"

# ---------- 2. simulate live delta ----------
echo "delta line" >> "$SRC/User.md"
echo '{"delegate":"d4"}' > "$SRC/delegates/delegate-d4.json"
rm "$SRC/sessions/s5.json"

# ---------- 3. finalize ----------
run bash "$SDIR/finalize.sh" || bad "finalize.sh exited non-zero"
[ ! -e "$SRC" ] && ok "finalize: source renamed away" || bad "finalize: $SRC still present"
ls -d "$SRC"-migrated-* >/dev/null 2>&1 && ok "finalize: migrated backup exists" || bad "finalize: no migrated backup"
grep -qF "$DST" "$DST/daemons.json" && ok "finalize: daemons.json patched" || bad "finalize: daemons.json not patched"
grep -qF "$SRC" "$DST/daemons.json" && bad "finalize: daemons.json still holds old path" || ok "finalize: daemons.json free of old path"
grep -qF "$DST" "$DST/bin/nebflow-autostart.sh" && ok "finalize: autostart.sh patched" || bad "finalize: autostart.sh not patched"
grep -qF "$DST" "$PL/com.nebflow.weixin-sidecar.plist" && ok "finalize: sidecar NEBFLOW_HOME patched" || bad "finalize: sidecar plist not patched"
grep -qF "$DST" "$PL/com.nebflow.gateway.plist" && ok "finalize: gateway plist patched" || bad "finalize: gateway plist not patched"
ls "$PL"/*.bak-* >/dev/null 2>&1 && ok "finalize: plist .bak kept" || bad "finalize: plist .bak missing"
ls "$DST"/*.bak-* >/dev/null 2>&1 && ok "finalize: cfg .bak kept" || bad "finalize: cfg .bak missing"
cmp -s "$DST/delegates/delegate-d4.json" <(echo '{"delegate":"d4"}') && ok "finalize: delta file d4 synced" || bad "finalize: delta d4 missing in DST"
[ ! -f "$DST/sessions/s5.json" ] && ok "finalize: --delete honored (s5 gone)" || bad "finalize: s5 still in DST"

# ---------- 4. verify (strict, reference = renamed backup) ----------
run bash "$SDIR/verify.sh" --strict; [ $? -eq 0 ] && ok "verify --strict passed" || bad "verify --strict failed"
grep -q "PASS\*" "$LOG" && ok "verify: patched-as-intended detection works" || bad "verify: no patched-as-intended seen"
grep -q "SQLITE-OK" "$LOG" && ok "verify: sqlite integrity ok" || bad "verify: sqlite not ok"

# ---------- 5. idempotency: re-run finalize ----------
run bash "$SDIR/finalize.sh" || bad "finalize re-run exited non-zero (idempotency broken)"
[ -f "$DST/tasks-v2.json" ] && ok "idempotent re-run: DST intact" || bad "idempotent re-run broke DST"

# ---------- 6. rollback ----------
run bash "$SDIR/rollback.sh" --full || bad "rollback.sh exited non-zero"
[ -d "$SRC" ] && ok "rollback: source name restored" || bad "rollback: source not restored"
grep -qF "$SRC" "$PL/com.nebflow.weixin-sidecar.plist" && ok "rollback: sidecar plist restored from .bak" || bad "rollback: sidecar plist not restored"
grep -qF "$SRC" "$DST/daemons.json" 2>/dev/null && ok "rollback --full: DST configs restored from .bak" || bad "rollback --full: DST daemons.json not restored"
[ -d "$DST" ] && ok "rollback: .stand copy preserved (zero-delete)" || bad "rollback: .stand missing"

echo "=== DRYRUN RESULT: $([ $FAILS -eq 0 ] && echo ALL-PASS || echo "$FAILS FAILURES") ==="
echo "$FAILS" > "$ROOT/fails"
} | tee -a "$LOG"

FAILS="$(cat "$ROOT/fails" 2>/dev/null || echo 999)"

cp "$LOG" "$EVCOPY" 2>/dev/null
echo "dryrun log: $LOG"
echo "evidence : $EVCOPY"
echo "sandbox kept for inspection: $ROOT (safe to delete anytime)"
[ "$FAILS" -eq 0 ] || exit 1
