#!/bin/bash
# 30-stats-probe.sh — PoC-b：docker stats --no-stream 采样开销与稳定性（Spec §2.4-D progressProbe 依据）
# 形态：1 个 CPU 满载容器（busybox 算术循环，单核）+ 1 个 idle 容器；N 次采样记录
#       单次调用墙钟延迟 + 各容器 CPUPerc；末尾附「全容器一次调用」与「单容器限定调用」延迟对照。
# 产物：results/b-stats-probe.tsv / b-stats-probe.md
# 复现：bash scripts/sandbox-poc/30-stats-probe.sh [N]
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

N="${1:-30}"
TSV="$RESULTS/b-stats-probe.tsv"
: > "$TSV"
tsv_append "$TSV" "idx	lat_ms	scope	load_cpu	idle_cpu"

docker rm -f "$NB-load" "$NB-idle" >/dev/null 2>&1 || true
trap 'docker rm -f "$NB-load" "$NB-idle" >/dev/null 2>&1 || true' EXIT

# CPU 满载容器：12 CPU VM 上单核 busyloop 理论值 ≈ 100/12 ≈ 8.3%
docker run -d --name "$NB-load" alpine:latest sh -c 'i=0; while :; do i=$((i+1)); done' >/dev/null
docker run -d --name "$NB-idle" alpine:latest sleep 600 >/dev/null
sleep 2   # 让负载进入稳态

echo "[b] sampling N=$N ..."
i=0
while [ "$i" -lt "$N" ]; do
  i=$((i+1))
  t0=$(now)
  out=$(docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}' "$NB-load" "$NB-idle" 2>/dev/null)
  t1=$(now)
  lat=$(elapsed_ms "$t0" "$t1")
  load_cpu=$(printf '%s\n' "$out" | awk -F'|' -v n="$NB-load" '$1==n{gsub("%","",$2); print $2}')
  idle_cpu=$(printf '%s\n' "$out" | awk -F'|' -v n="$NB-idle" '$1==n{gsub("%","",$2); print $2}')
  tsv_append "$TSV" "$i	$lat	two	${load_cpu:-NA}	${idle_cpu:-NA}"
  sleep 1
done

# 对照 A：全容器（含作者存量 postgres——只读查询）
t0=$(now); docker stats --no-stream >/dev/null 2>&1; t1=$(now)
lat_all=$(elapsed_ms "$t0" "$t1")
# 对照 B：单容器限定
t0=$(now); docker stats --no-stream "$NB-load" >/dev/null 2>&1; t1=$(now)
lat_one=$(elapsed_ms "$t0" "$t1")
tsv_append "$TSV" "ref	$lat_all	all	-	-"
tsv_append "$TSV" "ref	$lat_one	one	-	-"

# 汇总
python3 - "$TSV" "$RESULTS/b-stats-probe.md" "$lat_all" "$lat_one" <<'EOF'
import sys, statistics
tsv, md, lat_all, lat_one = sys.argv[1:5]
rows = [l.split('\t') for l in open(tsv).read().splitlines()[1:] if l]
samples = [r for r in rows if r[1] != 'ref']
lats = sorted(float(r[1]) for r in samples)
load = [float(r[3]) for r in samples if r[3] not in ('NA', '-', '')]
idle = [float(r[4]) for r in samples if r[4] not in ('NA', '-', '')]
def pct(a, p): return a[min(len(a)-1, int(len(a)*p))]
with open(md, 'w') as f:
    f.write(f"# PoC-b docker stats --no-stream 采样（n={len(samples)}）\n\n")
    f.write("| 指标 | 值 |\n|---|---|\n")
    f.write(f"| 单次调用延迟 min/median/p95/max | {lats[0]:.0f} / {statistics.median(lats):.0f} / {pct(lats,0.95):.0f} / {lats[-1]:.0f} ms |\n")
    if load:
        f.write(f"| 满载容器 CPUPerc mean±stdev | {statistics.mean(load):.2f}% ± {statistics.pstdev(load):.2f}%（理论单核 ≈8.3%）|\n")
        f.write(f"| 满载容器 CPUPerc min/max | {min(load):.2f}% / {max(load):.2f}% |\n")
    if idle:
        f.write(f"| idle 容器 CPUPerc mean/max | {statistics.mean(idle):.3f}% / {max(idle):.3f}% |\n")
    f.write(f"| 对照：全容器一次调用 | {lat_all} ms |\n")
    f.write(f"| 对照：单容器限定一次调用 | {lat_one} ms |\n")
print(open(md).read())
EOF
echo "[b] done. md=$RESULTS/b-stats-probe.md"
