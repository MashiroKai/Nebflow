#!/bin/bash
# precopy.sh — 段一：引擎运行中对数据根做安全预拷贝  ~/.nebflow → ~/.stand
#
# 策略（磁盘实测：~/.nebflow ≈ 212G，容器仅剩 ~37G 可用 → 普通 rsync 全量副本物理不可行）：
#   Phase A  cp -Rc 逐顶层条目 APFS clonefile 克隆（写时共享，瞬时几乎 0 额外空间；
#            clonefile 原生保留 mode/owner/mtime/xattr/ACL；引擎只读源侧，克隆为逐文件
#            原子快照，运行中拷贝安全）
#   Phase B  rsync -aH（openrsync 不支持 -A/-X 时自动降级）补差：补上 Phase A 期间
#            新建/克隆失败的文件
# 权威同步不在这里 —— finalize.sh 在停机窗做增量补差 + 改名。
#
# 铁律：绝不触碰 ~/.nebflow-stable（8090 稳定线）；本脚本只读源侧，绝不改源内容。
# 环境变量覆盖（dry-run 用）：NEBFLOW_MIGRATE_SRC / NEBFLOW_MIGRATE_DST /
#   NEBFLOW_MIGRATE_LOG
set -u
SRC="${NEBFLOW_MIGRATE_SRC:-$HOME/.nebflow}"
DST="${NEBFLOW_MIGRATE_DST:-$HOME/.stand}"
LOG="${NEBFLOW_MIGRATE_LOG:-/tmp/stand-precopy-$(date +%Y%m%d-%H%M%S).log}"

die() { echo "[$(date '+%F %T')] FATAL: $*" | tee -a "$LOG" >&2; exit 1; }
note() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

# ---- guards ----------------------------------------------------------------
[ -d "$SRC" ] || die "source $SRC not found"
case "$(basename "$SRC")" in *nebflow-stable*) die "refusing: source is the stable line" ;; esac
case "$(basename "$DST")" in *nebflow-stable*) die "refusing: target is the stable line" ;; esac
[ "$SRC" != "$DST" ] || die "SRC == DST"
mkdir -p "$(dirname "$LOG")" ; : > "$LOG"

if [ -e "$DST" ]; then
  if [ -d "$DST" ] && [ -z "$(ls -A "$DST" 2>/dev/null)" ]; then
    note "target $DST exists but is empty — reusing"
  else
    die "target $DST already exists and is non-empty (refusing to mix). Remove it first or point NEBFLOW_MIGRATE_DST elsewhere."
  fi
fi
mkdir -p "$DST"

# clonefile sanity probe (same APFS volume required)
echo probe > "$DST/.clonefile-probe.$$"
if ! cp -c "$DST/.clonefile-probe.$$" "$DST/.clonefile-probe2.$$" 2>/dev/null; then
  rm -f "$DST/.clonefile-probe.$$"
  die "APFS clonefile probe failed — cannot pre-copy without ~212G free. Free space or provide a same-volume APFS target."
fi
rm -f "$DST/.clonefile-probe.$$" "$DST/.clonefile-probe2.$$"

FREE_KB="$(df -k "$(dirname "$DST")" | awk 'NR==2{print $4}')"
note "free space on target volume: $((FREE_KB/1024/1024)) GiB"

# ---- Phase A: clone per top-level entry ------------------------------------
note "Phase A: clonefile copy $SRC/ → $DST/"
FAILS=0; N=0
cd "$SRC" || die "cannot cd $SRC"
ls -A | while IFS= read -r entry; do
  [ -n "$entry" ] || continue
  if cp -Rc "$entry" "$DST/" 2>>"$LOG"; then
    N=$((N+1))
    echo "[$(date '+%F %T')] cloned: $entry" >> "$LOG"
  else
    echo "[$(date '+%F %T')] clone-failed (rsync top-up will retry): $entry" >> "$LOG"
  fi
done
note "Phase A done (per-entry detail in $LOG)"

# ---- Phase B: rsync top-up (missing/new files only, no --delete) -----------
RSYNC_FLAGS="-aH"
if rsync -aHAX --dry-run "$SRC/" "$DST/" >/dev/null 2>&1; then RSYNC_FLAGS="-aHAX"; fi
note "Phase B: rsync $RSYNC_FLAGS top-up"
if rsync $RSYNC_FLAGS --stats "$SRC/" "$DST/" >>"$LOG" 2>&1; then
  note "Phase B rsync: ok (stats appended to log)"
else
  FAILS=1
  note "Phase B rsync: NON-FATAL errors (engine live; vanished tmp files expected). See log."
fi

# ---- summary ---------------------------------------------------------------
SRC_CNT="$(find "$SRC" -xdev | wc -l | tr -d ' ')"
DST_CNT="$(find "$DST" -xdev | wc -l | tr -d ' ')"
SRC_DU="$(du -sh "$SRC" 2>/dev/null | awk '{print $1}')"
DST_DU="$(du -sh "$DST" 2>/dev/null | awk '{print $1}')"
FREE_KB="$(df -k "$(dirname "$DST")" | awk 'NR==2{print $4}')"
note "SUMMARY: entries src=$SRC_CNT dst=$DST_CNT | du src=$SRC_DU dst=$DST_DU | free=$(((FREE_KB)/1024/1024))GiB | rsync_errors=$FAILS"
note "pre-copy DONE — engine kept running; authoritative sync happens in finalize.sh during the downtime window."
exit 0
