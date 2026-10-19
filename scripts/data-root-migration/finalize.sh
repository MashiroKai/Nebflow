#!/bin/bash
# finalize.sh — 段二：停机窗秒切  ~/.nebflow → ~/.stand
# 【必须先停引擎（8080 无监听）再跑本脚本；precopy.sh 必须已经跑过】
#
# 步骤：
#   1. 守卫：引擎必须已停（8080 无监听）；~/.nebflow-stable 永不触碰
#   2. rsync -aH[XA] --delete 增量补差（权威同步；运行期产生的差量在此收口）
#   3. mv ~/.nebflow → ~/.nebflow-migrated-<ts>（零删除：只改名保底）
#   4. launchd plist 补丁（.bak 后只写 patch，绝不 reload —— reload 是 runbook 里 Kai 的可选步骤）：
#      - com.nebflow.weixin-sidecar.plist: NEBFLOW_HOME → <DST>
#      - 其他 com.nebflow.* plist 中 <SRC> 路径 → <DST>（.disabled/.bak 文件不动）
#   5. 数据根内已知引用面 path patch（逐个 .bak）：
#      daemons.json / install-manifest.json / bin/nebflow-autostart.sh / bin/memsample.sh
#      （bin/nebflow 是 8090 稳定线启动器，明确不动）
#   6. 残留引用只报告不修改（tasks-v2.json 等 = 历史记录，保持原样）
# 幂等：可安全重跑（已改名/已补丁的步骤自动跳过）。
#
# 环境变量覆盖（dry-run 用）：NEBFLOW_MIGRATE_SRC / NEBFLOW_MIGRATE_DST /
#   NEBFLOW_MIGRATE_PLIST_DIR / NEBFLOW_MIGRATE_ENGINE_GUARD=0 / NEBFLOW_MIGRATE_LOG
set -u
SRC="${NEBFLOW_MIGRATE_SRC:-$HOME/.nebflow}"
DST="${NEBFLOW_MIGRATE_DST:-$HOME/.stand}"
PLIST_DIR="${NEBFLOW_MIGRATE_PLIST_DIR:-$HOME/Library/LaunchAgents}"
ENGINE_GUARD="${NEBFLOW_MIGRATE_ENGINE_GUARD:-1}"
TS="$(date +%Y%m%d-%H%M%S)"
LOG="${NEBFLOW_MIGRATE_LOG:-/tmp/stand-finalize-$TS.log}"

die() { echo "[$(date '+%F %T')] FATAL: $*" | tee -a "$LOG" >&2; exit 1; }
note() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
mkdir -p "$(dirname "$LOG")" ; : > "$LOG"

command -v perl >/dev/null || die "perl not found (needed for safe in-place path patch)"
PLIST_HELPER="$(dirname "$0")/plist-patch.py"
[ -f "$PLIST_HELPER" ] || PLIST_HELPER=""

# ---- guards ----------------------------------------------------------------
case "$(basename "$SRC")" in *nebflow-stable*) die "refusing: source is the stable line" ;; esac
case "$(basename "$DST")" in *nebflow-stable*) die "refusing: target is the stable line" ;; esac
[ "$SRC" != "$DST" ] || die "SRC == DST"

note "engine guard: checking for listener on 8080"
if [ "$ENGINE_GUARD" = "1" ]; then
  if [ -n "$(lsof -ti tcp:8080 -sTCP:LISTEN 2>/dev/null | head -n 1)" ]; then
    die "engine is STILL RUNNING (port 8080 listening). Stop the engine first, then re-run finalize.sh."
  fi
  note "  ok — nothing listens on 8080"
else
  note "  SKIPPED by NEBFLOW_MIGRATE_ENGINE_GUARD=0 (dry-run mode)"
fi

# ---- 2. authoritative delta sync -------------------------------------------
ALREADY_MIGRATED=0
if [ -d "$SRC" ]; then
  [ -d "$DST" ] || die "target $DST missing — run precopy.sh first"
  RSYNC_FLAGS="-aH"
  if rsync -aHAX --dry-run "$SRC/" "$DST/" >/dev/null 2>&1; then RSYNC_FLAGS="-aHAX"; fi
  note "delta sync: rsync $RSYNC_FLAGS --delete $SRC/ → $DST/"
  rsync $RSYNC_FLAGS --delete --stats "$SRC/" "$DST/" >>"$LOG" 2>&1 \
    || die "delta rsync failed — source left UNTOUCHED, safe to investigate (see $LOG)"
  note "  ok"
else
  ALREADY_MIGRATED=1
  note "source $SRC already absent — assuming renamed earlier; skipping rsync+rename (idempotent re-run)"
fi

# ---- 3. rename (zero-delete safety rename) ---------------------------------
if [ "$ALREADY_MIGRATED" = "0" ]; then
  if [ -n "$(lsof -ti tcp:8080 -sTCP:LISTEN 2>/dev/null | head -n 1)" ] && [ "$ENGINE_GUARD" = "1" ]; then
    die "engine came back during sync — aborting BEFORE rename; nothing changed"
  fi
  MIGRATED="${SRC}-migrated-$TS"
  [ -e "$MIGRATED" ] && die "rename target $MIGRATED already exists — aborting"
  note "rename: $SRC → $MIGRATED"
  mv "$SRC" "$MIGRATED" || die "mv failed (source untouched)"
  note "  ok — old root preserved at $MIGRATED (delete only by Kai's explicit decision, later)"
fi

[ -d "$DST" ] || die "post-condition failed: $DST missing"
[ -f "$DST/tasks-v2.json" ] && note "sanity: $DST/tasks-v2.json present" || note "WARN: $DST/tasks-v2.json missing (unexpected)"

# ---- 4. launchd plist patches (write patch only, NEVER reload) -------------
patch_plist() { # $1 = plist path
  local p="$1"
  [ -f "$p" ] || return 0
  case "$p" in *.disabled*|*.bak*) note "  skip (record): $p"; return 0 ;; esac
  if [ -n "$PLIST_HELPER" ] && python3 "$PLIST_HELPER" --check "$p" "$SRC" 2>>"$LOG"; then
    cp -p "$p" "$p.bak-$TS" || die "cannot backup $p"
    python3 "$PLIST_HELPER" --patch "$p" "$SRC" "$DST" >>"$LOG" 2>&1 \
      || die "plist patch failed on $p (backup at $p.bak-$TS)"
    note "  patched: $p (backup: $p.bak-$TS)"
  else
    note "  no matching refs: $p"
  fi
}
note "plist patches (write-only, NO launchctl reload):"
LABELS="com.nebflow.weixin-sidecar com.nebflow.gateway com.nebflow.memsample"
for lbl in $LABELS; do patch_plist "$PLIST_DIR/$lbl.plist"; done
# sweep any other com.nebflow.* plist carrying old paths (report+patch), report-only for non-nebflow ones
if [ -d "$PLIST_DIR" ]; then
  for p in "$PLIST_DIR"/*.plist; do
    [ -f "$p" ] || continue
    b="$(basename "$p")"
    case "$b" in com.nebflow.*) case " $LABELS " in *" $b "*) continue ;; esac; patch_plist "$p" ;;
      *) if [ -n "$PLIST_HELPER" ] && python3 "$PLIST_HELPER" --check "$p" "$SRC" 2>>"$LOG"; then
           note "  REPORT-ONLY (not a nebflow plist, untouched): $b contains $SRC refs"
         fi ;;
    esac
  done
fi

# ---- 5. known in-data-root reference-surface patches (each .bak) -----------
patch_cfg() { # $1 = file inside DST
  local f="$DST/$1"
  [ -f "$f" ] || { note "  absent (skip): $1"; return 0; }
  # count patchable refs (SRC not followed by [-\w], so ~/.nebflow-stable refs never match)
  local n
  n="$(SRC="$SRC" perl -ne '$c += () = m{\Q$ENV{SRC}\E(?![-\w])}g; END { print $c+0 }' "$f")"
  if [ "$n" = "0" ]; then note "  clean (no patchable $SRC refs): $1"; return 0; fi
  cp -p "$f" "$f.bak-$TS" || die "cannot backup $f"
  SRC="$SRC" DST="$DST" perl -pi -e 's{\Q$ENV{SRC}\E(?![-\w])}{$ENV{DST}}g' "$f" || die "patch failed: $f"
  note "  patched ($n refs): $1 (backup: $(basename "$f").bak-$TS)"
}
note "in-data-root reference patches:"
for rel in daemons.json install-manifest.json bin/nebflow-autostart.sh bin/memsample.sh; do
  patch_cfg "$rel"
done
note "  intentionally untouched: bin/nebflow (8090 stable-line launcher) + all *.bak-* records"

# ---- 6. residual reference scan (REPORT-ONLY) ------------------------------
note "residual $SRC refs in $DST top-level json (records like tasks-v2.json stay as history — REPORT-ONLY):"
grep -lF "$SRC" "$DST"/*.json 2>/dev/null | while IFS= read -r f; do note "  record keeps old path: $(basename "$f")"; done

note "FINALIZE DONE."
note "next: start the engine with NEBFLOW_HOME=$DST (see runbook), then run verify.sh --strict"
exit 0
