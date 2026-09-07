#!/bin/bash
# common.sh — sandbox-poc 共享助手（macOS bash 3.2 兼容，无 bash4 特性）
# 资源命名一律 $NB- 前缀（nb-poc-*），与作者存量零碰撞；作者容器/镜像只读。

POC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"    # <worktree>/scripts/sandbox-poc
RESULTS="$POC_DIR/results"
LOGDIR="${POC_LOGDIR:-/tmp/nb-sbx-poc/logs}"
mkdir -p "$RESULTS" "$LOGDIR"
NB="nb-poc"
WT="$(cd "$POC_DIR/../.." && pwd)"                            # worktree 根（scripts/sandbox-poc 上两级）
IMG_UB="$NB-sbx:ubuntu"
IMG_AL="$NB-sbx:alpine"

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

# elapsed_ms <t0> <t1>
elapsed_ms() { python3 -c "print(f'{($2-$1)*1000:.0f}')"; }

# tsv_append <file> <row...>  —— 无 header 则先写（调用方第一行自己写 header 更稳，这里纯 append）
tsv_append() { local f="$1"; shift; printf '%s\n' "$*" >> "$f"; }

# poc_repos —— coursier 源串：detect-mirror.sh 结果 + 保证有 fallback
poc_repos() {
  local base
  base="$(bash "$POC_DIR/../detect-mirror.sh" 2>/dev/null || echo https://repo1.maven.org/maven2)"
  case "$base" in
    *aliyun*) echo "$base" ;;
    *)        echo "$base|https://maven.aliyun.com/repository/public" ;;
  esac
}

# df_snapshot <label> —— docker 磁盘记账行
df_snapshot() {
  local label="$1"
  {
    echo "--- $label ($(date '+%F %T'))"
    docker system df 2>/dev/null | grep -E '^(Images|Containers|Local Volumes|Build Cache)'
  } >> "$RESULTS/resource-ledger.md"
}

# timeit <label> <logfile> <cmd...> —— 计时执行，rc 返回，行落 $RESULTS/timing.tsv
TIME_TSV="$RESULTS/timing.tsv"
timeit() {
  local label="$1" logfile="$2"; shift 2
  local t0 t1 rc
  t0=$(now)
  set +e; "$@" > "$logfile" 2>&1; rc=$?; set -e
  t1=$(now)
  tsv_append "$TIME_TSV" "$label$(printf '\t')$(python3 -c "print(f'{$t1-$t0:.1f}')")$(printf '\t')$rc"
  echo "[$label] wall=$(python3 -c "print(f'{$t1-$t0:.1f}')")s rc=$rc log=$logfile"
  return $rc
}
