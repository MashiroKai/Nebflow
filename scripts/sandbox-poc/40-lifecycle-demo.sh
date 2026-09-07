#!/bin/bash
# 40-lifecycle-demo.sh — PoC-c：per-task 容器生命周期全程计时（裁定 A4-A5 模型实证）
#   create → start(冷) → 执行任务(写状态) → stop 进 retention → TTL 内再激活复用
#   （docker start 温启动 + 文件系统状态保持断言）→ TTL 到期 auto-destroy → 零残留断言
# 产物：results/c-lifecycle.tsv / c-lifecycle.md
# 复现：bash scripts/sandbox-poc/40-lifecycle-demo.sh [retain_s] [ttl_s]
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

RETAIN_S="${1:-15}"     # retention 演示驻留时长（真实缺省建议 30-60min，PoC 压缩）
TTL_S="${2:-20}"        # TTL 到期时长（演示引擎 TtlTick 形态的脚本侧等价物）
IMAGE="${IMAGE:-$IMG_AL}"
CTR="$NB-task"
TSV="$RESULTS/c-lifecycle.tsv"
: > "$TSV"
tsv_append "$TSV" "phase	ms	note"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL: $1"; }

cleanup() { docker rm -f "$CTR" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker rm -f "$CTR" >/dev/null 2>&1 || true
df_snapshot "c-start"

# ① 任务激活：create + start（冷）
t0=$(now); docker create --name "$CTR" "$IMAGE" sleep infinity >/dev/null; t1=$(now)
ms=$(elapsed_ms "$t0" "$t1"); tsv_append "$TSV" "create	$ms	"; echo "[c] create ${ms}ms"
t0=$(now); docker start "$CTR" >/dev/null; t1=$(now)
ms=$(elapsed_ms "$t0" "$t1"); tsv_append "$TSV" "start_cold	$ms	"; echo "[c] start(cold) ${ms}ms"

# ② 任务执行：写状态文件 + 8MB blob（fs 状态证据）+ 读回
t0=$(now)
docker exec "$CTR" sh -c 'echo "marker-$(date +%s)" > /var/task-state.txt; dd if=/dev/urandom of=/var/task-blob bs=1M count=8 2>/dev/null; sync' >/dev/null 2>&1
t1=$(now); ms=$(elapsed_ms "$t0" "$t1"); tsv_append "$TSV" "exec_task	$ms	写状态+8MB blob"
MARKER1=$(docker exec "$CTR" cat /var/task-state.txt)
BLOB1=$(docker exec "$CTR" sh -c 'wc -c < /var/task-blob' | tr -d '[:space:]')
[ -n "$MARKER1" ] && ok "任务执行写入状态 marker=$MARKER1 blob=${BLOB1}B" || bad "任务执行未写入状态"
run_size=$(docker ps -a --filter "name=^$CTR$" --format '{{.Size}}')
echo "[c] running container size: $run_size"

# ③ 任务完成 → stop 进 retention
t0=$(now); docker stop -t 1 "$CTR" >/dev/null; t1=$(now)
ms=$(elapsed_ms "$t0" "$t1"); tsv_append "$TSV" "stop	$ms	"
state=$(docker inspect -f '{{.State.Status}}' "$CTR")
[ "$state" = "exited" ] && ok "容器进入 retention（exited）" || bad "stop 后状态=$state"
ret_size=$(docker ps -a --filter "name=^$CTR$" --format '{{.Size}}')
echo "[c] retention 驻留占盘: ${ret_size}（fs 状态保持中）"

# ④ retention：驻留 RETAIN_S（模拟保留期；期间容器不动）
sleep "$RETAIN_S"
docker inspect "$CTR" >/dev/null 2>&1 && ok "retention ${RETAIN_S}s 后容器仍驻留（未销毁）" || bad "retention 期间容器消失"

# ⑤ 再激活：docker start（温）+ 状态直续断言
t0=$(now); docker start "$CTR" >/dev/null; t1=$(now)
ms=$(elapsed_ms "$t0" "$t1"); tsv_append "$TSV" "start_warm	$ms	"
echo "[c] start(warm/reactivate) ${ms}ms"
MARKER2=$(docker exec "$CTR" cat /var/task-state.txt 2>/dev/null || true)
BLOB2=$(docker exec "$CTR" sh -c 'wc -c < /var/task-blob' 2>/dev/null | tr -d '[:space:]' || true)
[ "$MARKER1" = "$MARKER2" ] && ok "再激活复用：marker 状态保持（${MARKER2}）" || bad "marker 丢失（${MARKER1} → ${MARKER2}）"
[ "$BLOB1" = "$BLOB2" ] && ok "再激活复用：blob 完整（${BLOB2}B）" || bad "blob 丢失（${BLOB1} → ${BLOB2}）"

# ⑥ TTL 到期 → auto-destroy（脚本侧 TtlTick 等价物：驻留 TTL_S 无再激活 → rm -f）
echo "[c] TTL 观察窗 ${TTL_S}s（无再激活 → 销毁）..."
sleep "$TTL_S"
t0=$(now); docker rm -f "$CTR" >/dev/null; t1=$(now)
ms=$(elapsed_ms "$t0" "$t1"); tsv_append "$TSV" "auto_destroy	$ms	TTL 到期 docker rm -f"
echo "[c] auto-destroy ${ms}ms"

# ⑦ 零残留断言
docker inspect "$CTR" >/dev/null 2>&1 && bad "容器仍存在" || ok "容器已销毁（inspect 404）"
docker top "$CTR" >/dev/null 2>&1 && bad "docker top 仍可见" || ok "docker top: No such container"
left=$(docker ps -a --format '{{.Names}}' | grep -c "^$NB-" || true)
[ "$left" = "0" ] && ok "docker ps -a 零 nb-poc-* 残留" || bad "残留 nb-poc-* 容器 ${left} 个"

df_snapshot "c-end"

{
  echo "# PoC-c per-task 容器生命周期（image=$IMAGE, retain=${RETAIN_S}s, ttl=${TTL_S}s）"
  echo
  echo "| phase | ms | note |"
  echo "|---|---|---|"
  tail -n +2 "$TSV" | awk -F'\t' '{printf "| %s | %s | %s |\n", $1, $2, $3}'
  echo
  echo "PASS=$PASS FAIL=$FAIL"
} > "$RESULTS/c-lifecycle.md"
echo "[c] PASS=$PASS FAIL=$FAIL  md=$RESULTS/c-lifecycle.md"
[ "$FAIL" = "0" ]
