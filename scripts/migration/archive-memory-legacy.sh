#!/usr/bin/env bash
# archive-memory-legacy.sh — personal-agent 批（#482）记忆归档腿
#
# 🔴 显式运维动作，**不由 boot 自动执行**。两种用途：
#   ① 孤儿 agent 记忆归档（6 件历史 memory.md：Backend / Coder / Explorer /
#      Frontend / Manager / design-engineer）——**复制不删**；
#   ② 项目记忆归档（`<workspace>/.nebflow/memory.md`）——停注入后存量数据的处置。
#
# 安全纪律（默认条款 D-3 隔离前置闸）：
#   - 本脚本**默认 dry-run**（只打印将发生什么），必须显式 `--apply` 才落盘；
#   - 落盘前必须先打印「目标 home」并做一次隔离读数（见 --home 校验）；
#   - **禁止**对正在运行的实例的 home 直接跑（先停实例，或对副本跑）。
#
# 用法：
#   archive-memory-legacy.sh --home ~/.nebflow --stamp 20261004            # dry-run
#   archive-memory-legacy.sh --home ~/.nebflow --stamp 20261004 --apply    # 落盘
#   archive-memory-legacy.sh --home /tmp/iso-home --workspace /path/ws \
#                            --stamp 20261004 --apply --projects
set -euo pipefail

HOME_DIR=""
STAMP=""
APPLY=0
WITH_PROJECTS=0
WORKSPACE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --home) HOME_DIR="$2"; shift 2 ;;
    --stamp) STAMP="$2"; shift 2 ;;
    --workspace) WORKSPACE="$2"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    --projects) WITH_PROJECTS=1; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[ -n "$HOME_DIR" ] || { echo "FAIL: --home is required (never inferred — isolation-first gate)" >&2; exit 2; }
[ -n "$STAMP" ] || { echo "FAIL: --stamp is required (the archive dir name suffix)" >&2; exit 2; }
[ -d "$HOME_DIR" ] || { echo "FAIL: --home '$HOME_DIR' is not a directory" >&2; exit 2; }

ROOT_AGENT="Nebula"   # 旧数据目录名（历史位面）；机制键单点 = RootAgentIdentity.Name
ARCHIVE="$HOME_DIR/memory-archive/agents-$STAMP"

echo "== isolation readout (record BEFORE any write) =="
echo "target home : $HOME_DIR"
echo "archive dir : $ARCHIVE"
echo "apply       : $([ "$APPLY" = 1 ] && echo YES || echo 'no (dry-run)')"
echo

echo "== orphan agent memories (agents/<name>/memory.md, excluding the root agent) =="
FOUND=0
if [ -d "$HOME_DIR/agents" ]; then
  for d in "$HOME_DIR"/agents/*/; do
    [ -d "$d" ] || continue
    name="$(basename "$d")"
    [ "$name" = "$ROOT_AGENT" ] && continue
    if [ -f "$d/memory.md" ]; then
      sz=$(wc -c < "$d/memory.md" | tr -d ' ')
      echo "  $name  $sz B  ->  $ARCHIVE/$name/memory.md"
      FOUND=$((FOUND + 1))
      if [ "$APPLY" = 1 ]; then
        mkdir -p "$ARCHIVE/$name"
        cp "$d/memory.md" "$ARCHIVE/$name/memory.md"
      fi
    fi
  done
fi
[ "$FOUND" = 0 ] && echo "  (none)"
echo "  total: $FOUND"

if [ "$WITH_PROJECTS" = 1 ]; then
  echo
  echo "== project memories (<workspace>/.nebflow/memory.md) =="
  if [ -n "$WORKSPACE" ] && [ -f "$WORKSPACE/.nebflow/memory.md" ]; then
    sz=$(wc -c < "$WORKSPACE/.nebflow/memory.md" | tr -d ' ')
    echo "  $WORKSPACE/.nebflow/memory.md  $sz B  ->  $ARCHIVE/projects/<slug>/memory.md"
    if [ "$APPLY" = 1 ]; then
      slug=$(printf '%s' "$WORKSPACE" | sed 's#[^A-Za-z0-9._-]#_#g')
      mkdir -p "$ARCHIVE/projects/$slug"
      cp "$WORKSPACE/.nebflow/memory.md" "$ARCHIVE/projects/$slug/memory.md"
    fi
  else
    echo "  (no workspace given or no project memory found)"
  fi
fi

echo
if [ "$APPLY" = 1 ]; then
  echo "APPLIED. Originals were COPIED, never removed (归档不删)."
  echo "rollback: nothing to undo — every source file is still in place."
else
  echo "DRY-RUN. Re-run with --apply to copy."
fi
