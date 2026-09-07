#!/usr/bin/env bash
# #338 用户知识泄漏门禁：禁止用户专属知识进入将公开的代码/文档。
#
# ── 背景 ──
# 2026-08-19 冷启动路由硬编码 107 网关事故（用户私有配置进产品代码）
# → ~/.nebflow/docs/Nebflow/20260819_user-knowledge-leak-audit.md
# 本门禁将「把本地信息混进代码」的检测固化为脚本+模式库，便于公开 MIT
# 前置排查。CI 与本地同逻辑：.github/workflows/ci.yml 的 user-knowledge-gate
# job 直接调用本脚本。
#
# ── 数据与逻辑分离 ──
# 检测模式**外置**在 scripts/user-knowledge-patterns.txt（随代码走、不进
# gitignore，公开后随仓发布）。本脚本只读清单、不硬编码模式。清单分两层：
#   STRICT — 高置信泄漏，fail-fast。作者用户名/机构网关/自有公网 IP/
#            API key 形态/个人邮箱。默认与 CI 的 src/main Scala 门禁
#            只应用此层（对合法产品代码零误报；src/test/ 豁免）。
#   WIDE   — 通用形态，多为合法代码里的占位/示例/产品目录名（非泄漏），
#            仅 --all 全仓参考扫描列出并标注 [review]，不阻塞门禁。
#
# ── 豁免设计（安全理由）──
#   - src/test/ 与 tests/：测试 fixture 模拟用户配置属正常用途。
#   - vendor/压缩文件（monaco 等 minified 噪音，非人工书写）：--all 按
#     *.min.js / *.min.css / *.map 过滤。
#   - .nebflow/：运行时/内部 Spec/worktree，非公开源码（另本仓 .gitignore
#     已忽略 `.nebflow/*`，git ls-files 天然不含）。
#   - 工具自身 scripts/user-knowledge-patterns.txt：它必须包含这些模式字面，
#     属扫描配置而非被扫内容，显式排除。
#
# ── 用法 ──
#   bash scripts/check-user-knowledge.sh              # 默认：STRICT × src/main *.scala（CI 同）
#   bash scripts/check-user-knowledge.sh --all        # 全仓：STRICT fail-fast + WIDE 参考，按路径标注
#   bash scripts/check-user-knowledge.sh -d <dir>     # 指定目录：STRICT × 该目录文本文件
#
# git pre-commit 接入（可选，不强制装 hook）：在 .git/hooks/pre-commit 加
#   bash "$(git rev-parse --show-toplevel)/scripts/check-user-knowledge.sh"
# 或在提交前手动跑 `bash scripts/check-user-knowledge.sh --all` 全仓复核。
# 宽口径门禁（--all）默认**不**接入 CI——私有仓 Actions 烧免费额度，公开后
# 免费；启用时机见 .github/workflows/user-knowledge-gate-wide.yml。
set -euo pipefail
# 全脚本按字节处理（LC_ALL=C），避免扫描到含非 UTF-8 字节的文件时报
# "illegal byte sequence"（grep/sed 在 UTF-8 locale 下对坏字节敏感）。
export LC_ALL=C
cd "$(dirname "$0")/.."

PATTERNS_FILE="scripts/user-knowledge-patterns.txt"

# ── 解析模式库（STRICT / WIDE 两层）──
sect=strict; STRICT=""; WIDE=""
while IFS= read -r line; do
  case "$line" in
    "# === STRICT ===") sect=strict; continue ;;
    "# === WIDE ===")   sect=wide;   continue ;;
  esac
  [[ "$line" =~ ^[[:space:]]*$ ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  line="$(printf '%s' "$line" | sed 's/#.*$//; s/[[:space:]]*$//')"
  [[ -z "$line" ]] && continue
  if [[ "$sect" == "wide" ]]; then WIDE="${WIDE:+$WIDE|}$line"; else STRICT="${STRICT:+$STRICT|}$line"; fi
done < "$PATTERNS_FILE"
[ -n "$STRICT" ] || { echo "✗ no strict patterns parsed from $PATTERNS_FILE" >&2; exit 2; }

# ── 参数 ──
MODE=default; SCOPE="src/main"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) MODE=all; shift ;;
    -d|--dir) MODE=dir; SCOPE="$2"; shift 2 ;;
    *) echo "usage: check-user-knowledge.sh [--all|-d <dir>]" >&2; exit 2 ;;
  esac
done

# ── 默认 / 指定目录：STRICT fail-fast ──
if [[ "$MODE" != "all" ]]; then
  if [[ "$MODE" == "dir" ]]; then
    # 指定目录：扫其下全部文本文件（-I 跳过二进制），不限于 .scala
    HITS=$(grep -rInE -i "$STRICT" -I "$SCOPE" 2>/dev/null || true)
    human_scope="$SCOPE (text files)"
  else
    HITS=$(grep -rInE -i "$STRICT" --include='*.scala' "$SCOPE" 2>/dev/null || true)
    human_scope="$SCOPE (*.scala)"
  fi
  if [ -n "$HITS" ]; then
    echo "✗ user-knowledge gate FAILED — user-specific knowledge in $human_scope:"
    echo "$HITS"
    echo ""
    echo "These look like user-specific identifiers (provider gateway / personal"
    echo "paths / API keys / owned IPs / personal emails). Replace with neutral examples."
    echo "If a hit is a false positive, refine scripts/user-knowledge-patterns.txt"
    echo "— do not silence it."
    exit 1
  fi
  echo "✓ user-knowledge gate clean: $human_scope contain none of the strict patterns"
  exit 0
fi

# ── --all：全仓参考扫描（简洁汇总，防大文件/长行刷屏）──
echo "━━ user-knowledge 全仓扫描（--all）━━"

# 逐文件扫描并聚合：每文件只留前 MAX_SAMPLES 条样例、内容截断到 MAX_LEN 字符。
# 输出 = 样例行 `file:line:content(截断)`（一条一文件） + 额外的 `…(共 n 处)`
# 计数行。避免 bundle/fixture/大文件把输出刷爆。
MAX_LEN=150; MAX_SAMPLES=3
scan_tier() {  # $1=pattern  $2=body_var  $3=files_var  $4=total_var
  local pattern="$1" body_var="$2" files_var="$3" total_var="$4"
  local f hit n samples files=0 total=0 out=""
  while IFS= read -r -d '' f; do
    case "$f" in
      .nebflow/*|scripts/user-knowledge-patterns.txt|scripts/check-user-knowledge.sh) continue ;;
      *.min.js|*.min.css|*.map) continue ;;
      */vendor/*|vendor/*) continue ;;   # 三方 vendored 压缩/生成代码，非人工书写
    esac
    hit=$(grep -IinE "$pattern" "$f" 2>/dev/null || true)
    [ -z "$hit" ] && continue
    n=$(printf '%s\n' "$hit" | grep -c .)
    files=$((files+1)); total=$((total+n))
    samples=$(printf '%s\n' "$hit" | sed -n "1,${MAX_SAMPLES}p" \
      | awk -v m="$MAX_LEN" '{ if (length($0) > m) print substr($0,1,m) "..."; else print }' \
      | awk -v f="$f" '{ print f ":" $0 }')
    out+="$samples"$'\n'
    if [ "$n" -gt "$MAX_SAMPLES" ]; then out+="$f: …(共 $n 处)"$'\n'; fi
  done < <(git ls-files -z)
  printf -v "$body_var" '%s' "$out"
  printf -v "$files_var" '%d' "$files"
  printf -v "$total_var" '%d' "$total"
}
scan_tier "$STRICT" STR_BODY STR_FILES STR_TOTAL
scan_tier "$WIDE"  WIDE_BODY WIDE_FILES WIDE_TOTAL

# STRICT：fail-fast（命中非测试路径即 leak）。样例行形如 `file:line:content`。
leak=0; testcount=0
echo ""
echo "── STRICT（fail-fast 泄漏）──"
if [ -z "$STR_BODY" ]; then
  echo "  无"
else
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    # 只处理样例行（`file:数字:`）；`…(共 n 处)` 计数行跳过
    [[ "$h" =~ ^[^:]+:[0-9]+: ]] || continue
    f="${h%%:*}"
    if [[ "$f" == src/test/* || "$f" == tests/* || "$f" == tests/fixtures/* ]]; then
      echo "  [test-exempt] $h"
      testcount=$((testcount+1))
    else
      echo "  [leak] $h"
      leak=1
    fi
  done <<< "$STR_BODY"
fi

echo ""
echo "── WIDE（informational，仅人工复核）──"
if [ -z "$WIDE_BODY" ]; then
  echo "  无"
else
  echo "$WIDE_BODY" | sed 's/^/  /'
fi

echo ""
echo "── 小结 ──"
echo "  STRICT 命中文件: ${STR_FILES:-0} 个 / 共 ${STR_TOTAL:-0} 处；其中非测试路径 = $([ "$leak" = 1 ] && echo '有（公开前必处理）' || echo '无')"
echo "  STRICT 测试 fixture 豁免命中: $testcount 处"
echo "  WIDE 参考项: ${WIDE_FILES:-0} 个文件 / 共 ${WIDE_TOTAL:-0} 处（多为占位/示例/产品目录名，非泄漏）"
echo ""
echo "说明：--all 为全仓参考扫描。非测试路径的 STRICT 命中 = 公开前需处理；"
echo "测试 fixture 命中属合理（模拟用户配置）；WIDE 多为占位/示例/产品目录名，"
echo "非泄漏，仅提示人工复核。要在 CI/公开前强制拦截，请把本模式接入门禁；"
echo "私有仓 Actions 烧免费额度，公开后免费——见 user-knowledge-gate-wide.yml（默认不启用）。"
[ "$leak" = 1 ] && exit 1 || exit 0
