#!/bin/bash
# sbt-server-bench.sh — sbt 闸 server 层 A/B 实测采集器（墙钟 + 峰值 RSS）
#
# 用途: 同一场景（同一批 worktree、同一批命令）分别以「现状（每调用冷启）」与
#       「server 常驻」跑一遍，量出总耗时与 JVM 峰值 RSS。只测量、不改变任何语义
#       （闸的锁/堆帽由被测的 sbt 垫片自己决定，本脚本不注入）。
#
# 用法:
#   ops/test/sbt-server-bench.sh <label> -- <worktree> "<sbt args...>" [-- <worktree> "<args>" ...]
#   例:
#     ops/test/sbt-server-bench.sh baseline-3wt \
#        -- /tmp/sbtbench-1 "compile" -- /tmp/sbtbench-2 "compile"
#
# 输出: $BENCH_OUT/<label>.log（原始输出）+ 同目录 <label>.summary.tsv
#      BENCH_OUT 默认 /tmp/sbt-server-bench
#
# 采样口径:
#   sbtRssMB  = 本轮期间「cwd 指向被编译 worktree 的 java 进程」RSS 峰值
#   allRssMB  = 全部 java 进程 RSS 之和的峰值（含生产实例/其他会话，作环境背景读数）
#   gateWait  = 从 ~/.stand/logs/sbt-gate.log 抄出的本轮闸等待秒数（排队 ≠ 编译成本）
set -u

LABEL="${1:?usage: sbt-server-bench.sh <label> -- <worktree> <args> [-- ...]}"
shift
[ "${1:-}" = "--" ] && shift

OUT="${BENCH_OUT:-/tmp/sbt-server-bench}"
mkdir -p "$OUT"
LOG="$OUT/$LABEL.log"
SUM="$OUT/$LABEL.summary.tsv"
: >"$LOG"

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

sample_peak() {   # $1 = worktree abs path (可为空) → 打印 "sbtRssMB allRssMB"
  python3 - "$1" <<'PY'
import subprocess, sys
wt = sys.argv[1] if len(sys.argv) > 1 else ""
ps = subprocess.run(["ps", "-Ao", "pid=,rss=,command="], capture_output=True, text=True).stdout
allrss = 0
wtrss = 0
for line in ps.splitlines():
    parts = line.split(None, 2)
    if len(parts) < 3:
        continue
    pid, rss, cmd = parts[0], int(parts[1]), parts[2]
    if "java" not in cmd.split(" ")[0]:
        continue
    allrss += rss
    if wt:
        try:
            cwd = subprocess.run(["lsof", "-a", "-p", pid, "-d", "cwd", "-Fn"],
                                 capture_output=True, text=True).stdout
        except Exception:
            cwd = ""
        if wt in cwd:
            wtrss += rss
print(f"{wtrss//1024} {allrss//1024}")
PY
}

printf 'label\tidx\tworktree\targs\twall_s\tsbtRssMB\tallRssMB\tgateWait_s\trc\n' >"$SUM"

idx=0
total_wall=0
peak_sbt=0
while [ $# -gt 0 ]; do
  [ "$1" = "--" ] && { shift; continue; }
  wt="$1"; args="${2:-}"; shift 2 2>/dev/null || shift
  idx=$((idx+1))
  gate_log="$HOME/.stand/logs/sbt-gate.log"
  gate_lines_before=0
  [ -f "$gate_log" ] && gate_lines_before=$(wc -l <"$gate_log" | tr -d ' ')

  sampler=$(mktemp /tmp/sbtbench-samp.XXXXXX)
  ( while :; do sample_peak "$wt" >>"$sampler"; sleep 0.5; done ) >/dev/null 2>&1 &
  samp_pid=$!

  t0=$(now)
  printf '\n===== [%s] run %d: %s :: sbt %s =====\n' "$LABEL" "$idx" "$wt" "$args" >>"$LOG"
  ( cd "$wt" && eval "sbt $args" ) >>"$LOG" 2>&1
  rc=$?
  t1=$(now)
  kill "$samp_pid" 2>/dev/null; wait "$samp_pid" 2>/dev/null

  wall=$(python3 -c "print(f'{$t1-$t0:.1f}')")
  sbt_peak=$(awk '{if($1>m)m=$1} END{print m+0}' "$sampler")
  all_peak=$(awk '{if($2>m)m=$2} END{print m+0}' "$sampler")
  rm -f "$sampler"

  gate_wait=0
  if [ -f "$gate_log" ]; then
    gate_wait=$(tail -n +$((gate_lines_before+1)) "$gate_log" | sed -n 's/.*acquired after \([0-9]*\)s.*/\1/p' | sort -n | tail -1)
    gate_wait=${gate_wait:-0}
  fi

  total_wall=$(python3 -c "print(f'{$total_wall+$wall:.1f}')")
  [ "$sbt_peak" -gt "$peak_sbt" ] && peak_sbt="$sbt_peak"
  printf '%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$LABEL" "$idx" "$wt" "$args" "$wall" "$sbt_peak" "$all_peak" "$gate_wait" "$rc" >>"$SUM"
  printf '[%s] run %d %.1fs sbtPeak=%sMB allPeak=%sMB gateWait=%ss rc=%s\n' \
    "$LABEL" "$idx" "$wall" "$sbt_peak" "$all_peak" "$gate_wait" "$rc"
done

echo
echo "===== $LABEL SUMMARY ====="
column -t -s $'\t' "$SUM" 2>/dev/null || cat "$SUM"
echo "TOTAL wall = ${total_wall}s over ${idx} run(s); peak sbt JVM RSS = ${peak_sbt}MB"
echo "raw log: $LOG"
