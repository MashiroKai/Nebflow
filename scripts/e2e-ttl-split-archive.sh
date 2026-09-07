#!/usr/bin/env bash
# e2e-ttl-split-archive.sh — 裁定④「TTL 分开」批隔离实例 e2e（验收①②③）。
#
# 场景种子：
#   活动区 flow-map.json：
#     链A n-sweep1+n-sweep2（同批全终态）→ 期望 ≤40s 被链级 sweep 归档（验收①）
#     链B n-keep1(completed)+n-keep2(blocked 待办) → 链未齐保留主图
#     链C n-child(pending, in=[n-legacy1] 归档上游) → barrier 自愈投递（验收③）
#   存量归档单文件 flow-map-archive.json：n-legacy1+n-legacy2（同批）
#     → 期望 open 迁移为 flow-map-archive/chain-n-legacy1.json + .split-bak（验收②前半）
# REST 断言：
#   GET flow-map/archive 聚合端点含迁移批与 sweep 批；GET nodes/<id>/result 对
#   归档节点 200 且全文（验收②后半）。
#
# 进程纪律：trap EXIT 全清理；端口 8097（≠8080 宿主）；home/workspace 全隔离 /tmp。
set -u
PORT=8097
BASE=/tmp/nb-ttl-e2e
HOME_DIR=$BASE/home
WS=$BASE/ws
CP=$(cat /tmp/nb-ttl-cp.txt)
JAVA_PID=""

cleanup() {
  if [ -n "$JAVA_PID" ]; then kill -KILL "$JAVA_PID" 2>/dev/null; wait "$JAVA_PID" 2>/dev/null; fi
  lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | xargs kill -KILL 2>/dev/null
}
trap cleanup EXIT INT TERM

echo "== seed =="
rm -rf "$BASE"
mkdir -p "$HOME_DIR/projects/e2e-ttl" "$WS/.nebflow"
NOW=$(python3 -c 'import time; print(int(time.time()*1000))')
cat > "$HOME_DIR/projects/e2e-ttl/project.json" <<EOF
{"name":"e2e-ttl","workspace":"$WS","agentFile":"$WS/AGENTS.md","createdAt":$NOW}
EOF
echo "# e2e" > "$WS/AGENTS.md"
python3 - "$WS" "$NOW" <<'PYEOF'
import json, sys
ws, now = sys.argv[1], int(sys.argv[0+2])
def n(id, name, status, created, completed=None, result=None, extra=None):
    d = {"id": id, "name": name, "agent": "general", "status": status,
         "createdAt": created, "in": [], "out": None, "deps": [], "deliveredTo": []}
    if completed is not None: d["completedAt"] = completed
    if result is not None: d["result"] = result
    if extra: d.update(extra)
    return d
active = {
  "project": "e2e-ttl", "updatedAt": now,
  "nodes": {
    "n-sweep1": n("n-sweep1", "链A-实施", "completed", now-3600000, now-3500000, "SWEEP-RESULT-1", {"out": "n-sweep2", "ttlExpireAt": now+86400000}),
    "n-sweep2": n("n-sweep2", "链A-验收", "completed", now-3540000, now-3400000, "SWEEP-RESULT-2", {"out": "Nebula", "ttlExpireAt": now+86400000}),
    "n-keep1": n("n-keep1", "链B-实施", "completed", now-1000000, now-900000, "KEEP-RESULT-1", {"out": "Nebula", "ttlExpireAt": now+86400000}),
    "n-keep2": n("n-keep2", "链B-验收", "blocked", now-940000, None, None, {"out": "Nebula"}),
    "n-child": n("n-child", "链C-下游", "pending", now-300000, None, None, {"in": ["n-legacy1"], "out": "Nebula", "task": "consume archived upstream"}),
  },
}
archive = {
  "project": "e2e-ttl",
  "nodes": {
    "n-legacy1": n("n-legacy1", "遗产-实施", "completed", now-7200000, now-7100000, "LEGACY-RESULT-1", {"out": "n-legacy2", "ttlExpireAt": now-3600000}),
    "n-legacy2": n("n-legacy2", "遗产-验收", "completed", now-7140000, now-7000000, "LEGACY-RESULT-2", {"out": "Nebula", "ttlExpireAt": now-3600000}),
  },
}
open(f"{ws}/.nebflow/flow-map.json", "w").write(json.dumps(active))
open(f"{ws}/.nebflow/flow-map-archive.json", "w").write(json.dumps(archive))
print("seeded", ws)
PYEOF

echo "== boot isolated instance (port $PORT) =="
cd /tmp/nb-ttl-split-archive
NEBFLOW_GATEWAY_PORT=$PORT java -cp "$CP" nebflow.Main --home "$HOME_DIR" --port $PORT start > "$BASE/instance.log" 2>&1 &
JAVA_PID=$!
echo "java pid=$JAVA_PID (host PID 43459 untouched)"

for i in $(seq 1 60); do
  curl -sf -o /dev/null "http://localhost:$PORT/" && break
  sleep 1
done
TOKEN=$(python3 -c "import json; print(json.load(open('$HOME_DIR/auth.json')))")
echo "instance up; token loaded (${#TOKEN} chars)"

api() { curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:$PORT$1"; }

echo "== wait for chain sweep (TtlTick 30s) =="
SWEPT=0
for i in $(seq 1 15); do
  if [ -f "$WS/.nebflow/flow-map-archive/chain-n-sweep1.json" ]; then SWEPT=1; break; fi
  sleep 3
done

PASS=0; FAIL=0
ck() { if [ "$2" = "$3" ]; then echo "PASS  $1"; PASS=$((PASS+1)); else echo "FAIL  $1 — expected [$3] got [$2]"; FAIL=$((FAIL+1)); fi; }

echo "== 验收① 主图即时离场（链级 sweep）=="
ck "链A整链归档(批文件落盘)" "$SWEPT" "1"
ck "链A离开活动区" "$(python3 -c "import json;d=json.load(open('$WS/.nebflow/flow-map.json'));print('n-sweep1' in d['nodes'] or 'n-sweep2' in d['nodes'])")" "False"
ck "链B未齐保留(blocked待办)" "$(python3 -c "import json;d=json.load(open('$WS/.nebflow/flow-map.json'));print('n-keep1' in d['nodes'] and 'n-keep2' in d['nodes'])")" "True"

echo "== 验收② 分批落盘 + 迁移 + REST =="
ck "存量单文件改名.split-bak" "$([ -f "$WS/.nebflow/flow-map-archive.json.split-bak" ] && [ ! -f "$WS/.nebflow/flow-map-archive.json" ] && echo yes || echo no)" "yes"
ck "迁移批文件存在" "$([ -f "$WS/.nebflow/flow-map-archive/chain-n-legacy1.json" ] && echo yes || echo no)" "yes"
ck "迁移批含双节点" "$(python3 -c "import json;d=json.load(open('$WS/.nebflow/flow-map-archive/chain-n-legacy1.json'));print(sorted(d['nodes'].keys())==['n-legacy1','n-legacy2'] and d.get('batch')=='chain-n-legacy1')")" "True"
ARCH=$(api "/api/projects/e2e-ttl/flow-map/archive")
ck "REST archive 200聚合" "$(echo "$ARCH" | python3 -c "import json,sys;d=json.load(sys.stdin);ids=[b['id'] for b in d['batches']];print('chain-n-legacy1' in ids and 'chain-n-sweep1' in ids and d['ttlMs']==86400000)")" "True"
R1=$(api "/api/projects/e2e-ttl/flow-map/nodes/n-legacy1/result")
ck "REST result 命中迁移归档节点" "$(echo "$R1" | python3 -c "import json,sys;print(json.load(sys.stdin).get('result')=='LEGACY-RESULT-1')")" "True"
R2=$(api "/api/projects/e2e-ttl/flow-map/nodes/n-sweep2/result")
ck "REST result 命中sweep归档节点" "$(echo "$R2" | python3 -c "import json,sys;print(json.load(sys.stdin).get('result')=='SWEEP-RESULT-2')")" "True"

echo "== 验收③ barrier 语义回归（归档上游接线照常触达）=="
# n-child(pending, in=[n-legacy1] 归档) → settle sweep 自愈投递 → deliveredTo 落账/脱离 pending
DELIVERED=0
for i in $(seq 1 15); do
  if python3 -c "import json;d=json.load(open('$WS/.nebflow/flow-map.json'));n=d['nodes'].get('n-child',{});import sys;sys.exit(0 if ('n-legacy1' in n.get('deliveredTo',[]) or n.get('status') not in ('pending','wiring')) else 1)" 2>/dev/null; then DELIVERED=1; break; fi
  # n-child 可能已被链sweep（链C整链终态后）→ 查归档兜底
  if python3 -c "import json,glob;ns={};[ns.update(json.load(open(f)).get('nodes',{})) for f in glob.glob('$WS/.nebflow/flow-map-archive/*.json')];n=ns.get('n-child',{});import sys;sys.exit(0 if 'n-legacy1' in n.get('deliveredTo',[]) else 1)" 2>/dev/null; then DELIVERED=1; break; fi
  sleep 3
done
CHILD_STATE=$(python3 -c "
import json,glob
d=json.load(open('$WS/.nebflow/flow-map.json'))
n=d['nodes'].get('n-child')
if n is None:
    ns={}
    for f in glob.glob('$WS/.nebflow/flow-map-archive/*.json'): ns.update(json.load(open(f)).get('nodes',{}))
    n=ns.get('n-child',{})
print(n.get('status','MISSING'), n.get('deliveredTo',[]))")
ck "归档上游结果投递给下游(deliveredTo落账)" "$DELIVERED" "1"
echo "  n-child final: $CHILD_STATE"

echo "== verify-web-assets（前端合并关卡，隔离实例）=="
cd /tmp/nb-ttl-split-archive && node scripts/verify-web-assets.mjs "http://localhost:$PORT" 2>&1 | tail -3

echo "== summary: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" = "0" ]
