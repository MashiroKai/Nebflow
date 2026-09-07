#!/usr/bin/env bash
# e2e-ttl-split-archive-shots.sh — 裁定④ 真实隔离实例视觉验收驱动（种子+引导+截图+清理）。
# 产出：.nebflow/Spec/assets/archive-split-live/*.png
set -u
PORT=8098
BASE=/tmp/nb-ttl-shot
HOME_DIR=$BASE/home
WS=$BASE/ws
CP=$(cat /tmp/nb-ttl-cp.txt)
JAVA_PID=""

cleanup() {
  if [ -n "$JAVA_PID" ]; then kill -KILL "$JAVA_PID" 2>/dev/null; wait "$JAVA_PID" 2>/dev/null; fi
  lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | xargs kill -KILL 2>/dev/null
}
trap cleanup EXIT INT TERM

rm -rf "$BASE"
mkdir -p "$HOME_DIR/projects/e2e-ttl" "$WS/.nebflow"
echo '{"state":"skipped"}' > "$HOME_DIR/onboarding.json"
NOW=$(python3 -c 'import time; print(int(time.time()*1000))')
cat > "$HOME_DIR/projects/e2e-ttl/project.json" <<EOF
{"name":"e2e-ttl","workspace":"$WS","agentFile":"$WS/AGENTS.md","createdAt":$NOW}
EOF
echo "# e2e" > "$WS/AGENTS.md"
python3 - "$WS" "$NOW" <<'PYEOF'
import json, sys
ws, now = sys.argv[1], int(sys.argv[2])
def n(id, name, status, created, completed=None, result=None, extra=None):
    d = {"id": id, "name": name, "agent": "general", "status": status,
         "createdAt": created, "in": [], "out": None, "deps": [], "deliveredTo": [],
         "description": ""}
    if completed is not None: d["completedAt"] = completed
    if result is not None: d["result"] = result
    if extra: d.update(extra)
    return d
active = {
  "project": "e2e-ttl", "updatedAt": now,
  "nodes": {
    "n-sweep1": n("n-sweep1", "实施-归档分批落盘", "completed", now-3600000, now-3500000,
                  "归档落盘从单一大文件改为按派发批次切分的分文件，一批一文件。",
                  {"out": "n-sweep2", "ttlExpireAt": now+86400000, "description": "归档分文件落盘实现"}),
    "n-sweep2": n("n-sweep2", "验收-归档分批落盘", "completed", now-3540000, now-3400000,
                  "分文件落盘正确，迁移零丢失。",
                  {"out": "Nebula", "ttlExpireAt": now+86400000, "description": "分批落盘验收"}),
    "n-keep1": n("n-keep1", "实施-主图即时离场", "completed", now-1000000, now-900000,
                 "整链全终态即时归档，主图同帧淡出。",
                 {"out": "Nebula", "ttlExpireAt": now+86400000, "description": "主图即时离场实现"}),
    "n-keep2": n("n-keep2", "复核-主图即时离场", "blocked", now-940000, None, None,
                 {"out": "Nebula", "description": "待办复核：作者看图点头",
                  "blockedFeedback": {"category": "needs-split", "detail": "等作者视觉验收", "suggestion": "看截图后放行"}, "blockCount": 1}),
  },
}
archive = {
  "project": "e2e-ttl",
  "nodes": {
    "n-legacy1": n("n-legacy1", "实施-归档面板v3", "completed", now-7200000, now-7100000,
                   "## 归档面板 v3\n\n- 整链归档 + 悬浮钮入口\n- mailbox 式链条目\n\n**LEGACY-RESULT-1 标记**",
                   {"out": "n-legacy2", "ttlExpireAt": now-3600000, "description": "归档面板 v3 实现（存量迁移样本）"}),
    "n-legacy2": n("n-legacy2", "验收-归档面板v3", "completed", now-7140000, now-7000000,
                   "LEGACY-RESULT-2",
                   {"out": "Nebula", "ttlExpireAt": now-3600000, "description": "归档面板 v3 验收"}),
  },
}
open(f"{ws}/.nebflow/flow-map.json", "w").write(json.dumps(active))
open(f"{ws}/.nebflow/flow-map-archive.json", "w").write(json.dumps(archive))
print("seeded")
PYEOF

cd /tmp/nb-ttl-split-archive
NEBFLOW_GATEWAY_PORT=$PORT java -cp "$CP" nebflow.Main --home "$HOME_DIR" --port $PORT start > "$BASE/instance.log" 2>&1 &
JAVA_PID=$!
echo "java pid=$JAVA_PID"
for i in $(seq 1 60); do
  curl -sf -o /dev/null "http://localhost:$PORT/" && break
  sleep 1
done
TOKEN=$(python3 -c "import json; print(json.load(open('$HOME_DIR/auth.json')))")

echo "== wait chain sweep =="
for i in $(seq 1 20); do
  [ -f "$WS/.nebflow/flow-map-archive/chain-n-sweep1.json" ] && break
  sleep 3
done
ls "$WS/.nebflow/flow-map-archive/"

NEBFLOW_URL="http://localhost:$PORT" NEBFLOW_TOKEN="$TOKEN" PROJ="e2e-ttl" \
  OUT_DIR="/tmp/nb-ttl-split-archive/.nebflow/Spec/assets/archive-split-live" \
  NODE_PATH=/opt/homebrew/lib/node_modules \
  node scripts/shot-archive-split-live.cjs
