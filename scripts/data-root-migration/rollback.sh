#!/bin/bash
# rollback.sh — 一条命令把数据根名字换回来（零删除纪律的回滚腿）
#
#   rollback.sh           把最新的 ~/.nebflow-migrated-<ts> 改名回 ~/.nebflow
#   rollback.sh --full    额外把 finalize 写过的 launchd plist 与数据根内配置从各自 .bak-<ts> 恢复
#   rollback.sh --ts <YYYYmmdd-HHMMSS>   指定用哪个迁移备份
#
# 前置：~/.stand 存在且 ~/.nebflow 不存在（即已切换）。跑完后按 runbook 用原 NEBFLOW_HOME 启动引擎。
# 环境变量覆盖：NEBFLOW_MIGRATE_SRC / NEBFLOW_MIGRATE_DST / NEBFLOW_MIGRATE_PLIST_DIR
set -u
SRC="${NEBFLOW_MIGRATE_SRC:-$HOME/.nebflow}"
DST="${NEBFLOW_MIGRATE_DST:-$HOME/.stand}"
PLIST_DIR="${NEBFLOW_MIGRATE_PLIST_DIR:-$HOME/Library/LaunchAgents}"
FULL=0; WANT_TS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --full) FULL=1 ;;
    --ts) shift; WANT_TS="${1:-}" ;;
  esac
  shift
done

die() { echo "FATAL: $*" >&2; exit 1; }
case "$(basename "$SRC")" in *nebflow-stable*) die "refusing: stable line path" ;; esac
[ -d "$DST" ] || die "target $DST missing — nothing to roll back from"
[ -e "$SRC" ] && die "$SRC already exists — looks already rolled back (or never cut over)"

if [ -n "$WANT_TS" ]; then
  MIGRATED="${SRC}-migrated-$WANT_TS"
  [ -d "$MIGRATED" ] || die "requested backup $MIGRATED not found"
else
  MIGRATED="$(ls -d "${SRC}"-migrated-* 2>/dev/null | sort | tail -n1)"
  [ -n "$MIGRATED" ] && [ -d "$MIGRATED" ] || die "no ${SRC}-migrated-* backup found"
fi

echo "rollback: $MIGRATED → $SRC"
mv "$MIGRATED" "$SRC" || die "mv failed (nothing changed)"
echo "  ok — old data root is back at $SRC (the .stand copy at $DST is left untouched for now)"

restore() { # $1 = current file path (finds latest .bak-* sibling)
  local f="$1" bak
  [ -f "$f" ] || return 0
  bak="$(ls -d "$f".bak-* 2>/dev/null | sort | tail -n1)"
  if [ -n "$bak" ] && [ -f "$bak" ]; then
    cp -p "$f" "$f.pre-rollback-$(date +%Y%m%d-%H%M%S)"
    cp -p "$bak" "$f"
    echo "  restored: $f  (from $(basename "$bak"))"
  else
    echo "  no .bak for $f — left as-is"
  fi
}

if [ "$FULL" = "1" ]; then
  echo "restoring patched files from .bak (full rollback):"
  for lbl in com.nebflow.weixin-sidecar com.nebflow.gateway com.nebflow.memsample; do
    restore "$PLIST_DIR/$lbl.plist"
  done
  for rel in daemons.json install-manifest.json bin/nebflow-autostart.sh bin/memsample.sh; do
    restore "$DST/$rel"
  done
fi

echo "ROLLBACK DONE."
echo "next: start the engine the usual way (old data root) — e.g. your normal sbt start in /Users/kaiyu/Claude code/Stand"
echo "      ($DST remains on disk; nothing was deleted)"
