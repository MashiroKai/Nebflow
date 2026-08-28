#!/usr/bin/env bash
# #338 用户知识泄漏门禁：src/main 的 Scala 源码禁止出现用户专属知识。
#
# 禁模式（大小写不敏感）：
#   \b107\b                                — 107 网关前缀及模型 ID 里的 107
#                                           （比 "107/" 更宽：覆盖 "free 107
#                                           gateway" / "glm-5.2-107" 形态；
#                                           词边界排除 1107/1079 类数字噪音，
#                                           现树零误报）
#   ustc                                   — 用户所属机构网关
#   \bkai\b                                 — 用户名/设备名（词边界独立词：
#                                           覆盖 kai/Kai/KAI，kaiyu 由下一模式
#                                           覆盖；不误中 MashiroKai——GitHub
#                                           repo URL，功能性地址，rebrand 批4
#                                           随仓库迁移更换）
#   (^|[^A-Za-z])sk-[A-Za-z0-9]{8}         — API key 形态（词边界防误中
#                                           "task-specific"/"ask-reminder"）
#
# 范围：src/main 下 *.scala（src/test/ 豁免——测试 fixture 模拟用户配置属
# 正常用途；vendor 压缩包（monaco 等）除外——minified 噪音，非人工书写）。
# CI 与本地同逻辑：.github/workflows/ci.yml 的 user-knowledge-gate job
# 直接调用本脚本。本地复现：bash scripts/check-user-knowledge.sh
#
# 背景：2026-08-19 冷启动路由硬编码 107 网关事故（用户私有配置进产品代码）
# → ~/.nebflow/docs/Nebflow/20260819_user-knowledge-leak-audit.md
set -euo pipefail
cd "$(dirname "$0")/.."

PATTERN='\b107\b|ustc|\bkai\b|kaiyu|(^|[^A-Za-z])sk-[A-Za-z0-9]{8}'

HITS=$(grep -rInE -i "$PATTERN" --include='*.scala' src/main || true)

if [ -n "$HITS" ]; then
  echo "✗ user-knowledge gate FAILED — user-specific knowledge in src/main:"
  echo "$HITS"
  echo ""
  echo "These look like user-specific identifiers (provider gateway / personal"
  echo "paths / API keys). Replace with neutral examples (provider-x, /Users/name)."
  echo "If a hit is a false positive, refine the pattern here — do not silence it."
  exit 1
fi

echo "✓ user-knowledge gate clean: src/main Scala sources contain none of: 107 / ustc / kai / kaiyu / sk-XXXXXXXX"
