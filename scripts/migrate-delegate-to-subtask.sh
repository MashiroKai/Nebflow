#!/usr/bin/env bash
# migrate-delegate-to-subtask.sh — Delegate split (方案 A), P2 配置迁移
#
# Delegate 语义拆分后：
#   - Delegate 保留给 Nebula（调度器专用：指派 standalone agent / 触发 flow）
#   - Team 成员委派改用 SubTaskTool（self-clone + ephemeral worker）
#
# 本脚本把团队 agent 的 agent.json 从 "Delegate" 迁移到 "SubTask"，
# 并尽力同步 system.md 措辞。Nebula agent.json 零改动（保留 Delegate）。
#
# 用法：
#   bash scripts/migrate-delegate-to-subtask.sh          # 迁移
#   bash scripts/migrate-delegate-to-subtask.sh --check  # 只检查不修改
#
# 回滚：还原 ~/.nebflow/teams/*/agents/*/agent.json + system.md
#   （这些文件在 git 中可追踪，用 git checkout 还原）

set -euo pipefail

NEBFLOW_HOME="${NEBFLOW_HOME:-$HOME/.nebflow}"
AGENTS_DIR="$NEBFLOW_HOME/teams"
CHECK_ONLY=0
if [[ "${1:-}" == "--check" ]]; then CHECK_ONLY=1; fi

# ---------------------------------------------------------------
# 1. agent.json：12 个团队 agent 的 "Delegate" -> "SubTask"
#    （nebflow-project×6 / nebflow-rust×3 / nebflow-website×3）
#    用 find 自动发现，不硬编码名单——未来新增团队 agent 也覆盖。
# ---------------------------------------------------------------
mapfile -t TARGETS < <(grep -rl '"Delegate"' "$AGENTS_DIR"/*/agents/*/agent.json 2>/dev/null || true)

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "[migrate] 没有发现含 \"Delegate\" 的团队 agent.json（已迁移或无需迁移）"
else
  for f in "${TARGETS[@]}"; do
    if [[ "$CHECK_ONLY" -eq 1 ]]; then
      echo "[migrate] (check) 将迁移: $f"
    else
      # 只替换完整的 "Delegate" 字符串（工具名），不误伤描述文本中的
      # "Delegate"（如 systemPrompt 段落——但 agent.json 的 systemPrompt
      # 一般为空，描述字段不含该词；保险起见仅替换 JSON 字符串值）
      sed -i '' 's/"Delegate"/"SubTask"/g' "$f"
      echo "[migrate] 已迁移: $f"
    fi
  done
fi

# ---------------------------------------------------------------
# 2. system.md 措辞同步（尽力而为，当前所有 team system.md 不含
#    delegate/委派，此步为未来防御——命中才替换）
# ---------------------------------------------------------------
for f in "$AGENTS_DIR"/*/agents/*/system.md; do
  [[ -f "$f" ]] || continue
  if grep -qi 'delegate' "$f"; then
    if [[ "$CHECK_ONLY" -eq 1 ]]; then
      echo "[migrate] (check) system.md 含 delegate，需人工审查: $f"
    else
      # Manager coaching 段 "use Delegate" -> "use SubTask"；
      # EntityArchitect 等 "Delegate — sub-agent spawning" -> "SubTask — ..."
      sed -i '' -e 's/[Uu]se Delegate/use SubTask/g' \
                 -e 's/Delegate — sub-agent spawning/SubTask — self-clone sub-agent/g' \
                 -e 's/[Dd]elegate(/(SubTask(/g' "$f"
      echo "[migrate] system.md 已更新: $f"
    fi
  fi
done

# ---------------------------------------------------------------
# 3. 校验
# ---------------------------------------------------------------
echo "[migrate] 校验："
LEFT=$(grep -rl '"Delegate"' "$AGENTS_DIR"/*/agents/*/agent.json 2>/dev/null | wc -l | tr -d ' ' || true)
if [[ "$LEFT" -eq 0 ]]; then
  echo "  ✓ 团队 agent.json 已无 \"Delegate\"（$LEFT 残留）"
else
  echo "  ✗ 仍有 $LEFT 个团队 agent.json 含 \"Delegate\":"
  grep -rl '"Delegate"' "$AGENTS_DIR"/*/agents/*/agent.json 2>/dev/null | sed 's/^/    /' || true
fi

if grep -q '"Delegate"' "$NEBFLOW_HOME/agents/Nebula/agent.json" 2>/dev/null; then
  echo "  ✓ Nebula agent.json 保留 \"Delegate\"（零改动，符合设计）"
else
  echo "  ! Nebula agent.json 未显式列出 Delegate（tools 为空或 * 时由 NebulaExclusiveTools 提供，无需迁移）"
fi

echo "[migrate] 完成。回滚: git checkout 还原 ~/.nebflow/teams/*/agents/*/agent.json"
