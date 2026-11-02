#!/bin/bash
# check-no-tracked-artifacts.sh — 过程产物不入库门（与协作方对齐批 2026-11-03）
# ------------------------------------------------------------------
# 病史：docs/ 与 reports/ 长期被跟踪（docs 162 文件/17M，reports 504 文件/53M），
# 而 .gitignore:30 早已有 docs/ 一条 —— 规则与事实自相矛盾。成因是历史上的
# `git add -f` 强塞（如提交 51beafa16，提交信息自己写着"靠 add -f"）。
# 后果是与协作方每次合并反复拉锯：那边清一次，这边下一次合并又带回去。
#
# 本门禁把「操作产物目录一律不得出现在索引里」钉死在 CI：
#   A 面  git ls-files -- <产物目录> 必须为空。非空即红，逐条列出违规路径。
#   B 面  probe-before-trust：先证明 git ls-files 在本 checkout 里真的能列出
#         东西（探 src 必须非空）。否则「零命中」可能只是 git 不可用/不是仓库/
#         浅克隆异常造成的假绿 —— 检测器瞎了必须红，不能静默放行。
#
# 判定面（ARTIFACT_ROOTS）只收已由作者裁定为过程产物的目录：
#   docs/  reports/  evidence/  reports-evidence/
# 新增产物目录时同步本文件与 .gitignore（两处都得写，单一真源是 .gitignore，
# 本文件是它的 CI 执法面）。
#
# 本地等价：bash scripts/check-no-tracked-artifacts.sh
# Exit 0 = 全绿（索引里零产物）；非 0 = 有产物被跟踪 / git 面不可信。
set -u

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }

ARTIFACT_ROOTS="docs reports evidence reports-evidence"

cd "$REPO_ROOT" || fail "无法进入仓库根 $REPO_ROOT"
git rev-parse --is-inside-work-tree > /dev/null 2>&1 || fail "不是 git 工作树：$REPO_ROOT（本门禁依赖索引，纯导出目录无法判定）"

# -- B 面：探针先行，证明 git ls-files 面可信 ---------------------------------
# src/ 是本仓的核心源码面，任何真实 checkout 都不可能为空。它为空 = git 面
# 不可信（非仓库 / 索引损坏 / 检出异常），此时 A 面的「零命中」毫无意义。
PROBE_COUNT="$(git ls-files -- src 2>/dev/null | wc -l | tr -d ' ')"
[ "$PROBE_COUNT" -gt 0 ] || fail "探针失败：git ls-files -- src 零命中（git 面不可信，拒绝给出「零产物」的假绿结论；检查 checkout 是否完整）"

# -- A 面：产物目录必须零跟踪 ------------------------------------------------
VIOLATIONS=0
for root in $ARTIFACT_ROOTS; do
    # -- 后接目录名：只匹配该顶层目录本身，不做 pathspec 模糊扩散。
    # 目录不存在时 git ls-files 输出为空且退出 0（CI 全新检出即此情形）。
    hits="$(git ls-files -- "$root")"
    if [ -n "$hits" ]; then
        count="$(printf '%s\n' "$hits" | wc -l | tr -d ' ')"
        echo "FAIL: $root/ 有 ${count} 个文件被 git 跟踪（过程产物不得入库，见 .gitignore）" >&2
        printf '%s\n' "$hits" | head -20 | sed 's/^/    /' >&2
        [ "$count" -gt 20 ] && echo "    …（其余 $((count - 20)) 条略）" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
done

if [ "$VIOLATIONS" -gt 0 ]; then
    echo "" >&2
    echo "过程产物不得入库，见 .gitignore。修复方式（只移索引，磁盘文件零删除）：" >&2
    echo "    git rm -r --cached <目录>" >&2
    echo "禁止用 git add -f 绕过本门禁 —— 历史上正是 add -f 造成了 docs/reports 的矛盾态。" >&2
    exit 1
fi

echo "OK: 索引里零过程产物（docs/ reports/ evidence/ reports-evidence/ 全部零跟踪；探针 src=${PROBE_COUNT} 条）"
