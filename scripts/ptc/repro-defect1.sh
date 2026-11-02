#!/usr/bin/env bash
# repro-defect1.sh —— 把缺陷① 打红/打绿的复现脚本（2026-11-02）。
#
# 缺陷①：插件自动分配腿在慢模型下**恰好 60s** 被掐断，然后 fail-open 成 plugins=[]，
# 模型拿不到 ptc_run 却"看起来正常完成"。
#
# 做法：本地假模型（scripts/ptc/mock-llm.mjs）把**分配响应**延迟 N 毫秒（缺省 70000），
# 再用 --jar 指定的引擎实例跑一次 delegate spawn，看两件事：
#   RED  （改动前 jar）：分配在 ~60s 被传输层掐断 ⇒ 日志出现 allocator 失败 + 空集
#   GREEN（本批 jar）  ：分配在 ~70s 正常返回 ⇒ 日志出现 plugins=[ptc-python,...]
#
# 用法：
#   bash scripts/ptc/repro-defect1.sh --jar <jar> --home D:/ptc-e2e/home3 --port 18125 \
#        --workspace D:/ptc-e2e/home3/general [--alloc-delay 70000] [--mock-port 18124]
#
# 前置：假模型已在跑（本脚本会检测并在缺失时自行拉起一份临时实例）；
#       数据根必须已装好两个 PTC 插件（见 docs 复验章）。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR=""; HOME_DIR=""; PORT=""; WS=""; ALLOC_DELAY=70000; MOCK_PORT=18124
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2;;
    --home) HOME_DIR="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --workspace) WS="$2"; shift 2;;
    --alloc-delay) ALLOC_DELAY="$2"; shift 2;;
    --mock-port) MOCK_PORT="$2"; shift 2;;
    *) echo "unknown arg: $1"; exit 2;;
  esac
done
[ -n "$JAR" ] && [ -n "$HOME_DIR" ] && [ -n "$PORT" ] || { echo "usage: see header"; exit 2; }
WS="${WS:-$HOME_DIR/general}"
MOCKLOG="$HOME_DIR/logs/mock-llm.requests.jsonl"
STANDLOG="$HOME_DIR/logs/stand.log"

echo "== jar        : $JAR"
echo "== data root  : $HOME_DIR"
echo "== port       : $PORT"
echo "== workspace  : $WS"
echo "== alloc delay: ${ALLOC_DELAY}ms"

# 0) 假模型在跑？（不在就起一份，退出时收掉）
MOCK_STARTED=0
if ! curl -s -o /dev/null "http://127.0.0.1:$MOCK_PORT/"; then
  echo "== starting mock-llm on $MOCK_PORT"
  node "$ROOT/scripts/ptc/mock-llm.mjs" --port "$MOCK_PORT" --out "$HOME_DIR/logs/mock-llm" \
       --alloc-delay "$ALLOC_DELAY" --workspace "$WS" >"$HOME_DIR/logs/mock-llm.out" 2>&1 &
  MOCK_STARTED=$!
  sleep 3
fi
rm -f "$MOCKLOG" "$HOME_DIR/logs/mock-llm/requests.jsonl"

# 1) 停掉**本脚本自己**在 --port 上留下的实例（按监听端口取 pid，别的端口/别的 home 一概不碰）
PIDLIST=$(netstat -ano 2>/dev/null | grep -E "TCP.*:$PORT[[:space:]].*LISTENING" | awk '{print $5}' | sort -u)
for p in $PIDLIST; do
  [ -n "$p" ] && echo "== stopping stale instance pid=$p on :$PORT" && MSYS_NO_PATHCONV=1 taskkill /PID "$p" /F >/dev/null 2>&1
done
sleep 2

# 2) 起实例（与作者实例同一启动形态：-cp + nebflow.Main --home … --port … --daemon-child）
mkdir -p "$HOME_DIR/logs"
MSYS_NO_PATHCONV=1 nohup java --add-opens=java.base/java.lang=ALL-UNNAMED -cp "$JAR" nebflow.Main \
  --home "$HOME_DIR" --port "$PORT" --daemon-child >"$HOME_DIR/logs/stand-launch.out" 2>&1 &
echo "== instance launching (log: $HOME_DIR/logs/stand-launch.out)"

# 3) 等健康
for i in $(seq 1 40); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/api/health" && break
  sleep 1
done

# 4) 打一个 turn（假模型会先回 Delegate 工具调用，再触发分配腿）
node "$ROOT/scripts/ptc/ptc-e2e-drive.mjs" --base "http://127.0.0.1:$PORT" --home "$HOME_DIR" \
     --mock "$HOME_DIR/logs/mock-llm" --workspace "$WS" --timeout 60 >/dev/null 2>&1 &
DRIVE=$!
# 分配腿的观察窗：delay + 30s 余量（改动前 jar 会在 60s 掐断，本批在 delay 后返回）
WINDOW=$(( (ALLOC_DELAY / 1000) + 30 ))
sleep "$WINDOW"
kill "$DRIVE" 2>/dev/null || true

# 5) 判定
echo
echo "=== allocator 侧（stand.log） ==="
grep -E "llm allocator:|plugin allocation '" "$STANDLOG" | tail -8
echo
echo "=== 插件 MCP（stand.log） ==="
grep -E "tools/call budget|connected, [0-9]+ tools registered" "$STANDLOG" | tail -6
echo
if grep -q "plugin allocation .*plugins=\[ptc-" "$STANDLOG"; then
  echo "VERDICT: GREEN — 分配腿在 ${ALLOC_DELAY}ms 后正常返回，插件集非空（不再 60s 静默降级）"
  RC=0
elif grep -qE "plugin allocation '.*': (llm-error|parse-fail|fail-open)" "$STANDLOG"; then
  echo "VERDICT: RED — 分配腿失败并被记成失败模式词（旧 jar 的静默降级，或本批的显式失败）"
  RC=1
else
  echo "VERDICT: INCONCLUSIVE — 观察窗内没有分配结论，检查 mock 是否收到分配请求"
  RC=2
fi
[ "$MOCK_STARTED" != "0" ] && kill "$MOCK_STARTED" 2>/dev/null || true
exit "$RC"
