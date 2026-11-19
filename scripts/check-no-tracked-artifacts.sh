#!/bin/bash
# check-no-tracked-artifacts.sh — 过程产物不入库门（与协作方对齐批 2026-11-03）
# ------------------------------------------------------------------
# 病史：docs/ 与 reports/ 长期被跟踪（docs 162 文件/17M，reports 504 文件/53M），
# 而 .gitignore:30 早已有 docs/ 一条 —— 规则与事实自相矛盾。成因是历史上的
# `git add -f` 强塞（如提交 51beafa16，提交信息自己写着"靠 add -f"）。
# 后果是与协作方每次合并反复拉锯：那边清一次，这边下一次合并又带回去。
#
# 本门禁把「操作产物一律不得出现在索引里」钉死在 CI：
#   A 面  git ls-files -- <产物 pathspec> 必须为空。非空即红，逐条列出违规路径。
#   B 面  probe-before-trust：先证明 git ls-files 在本 checkout 里真的能列出
#         东西（探 src 必须非空）。否则「零命中」可能只是 git 不可用/不是仓库/
#         浅克隆异常造成的假绿 —— 检测器瞎了必须红，不能静默放行。
#
# 判定面（ARTIFACT_SPECS）只收已由作者裁定为过程产物的路径，全部根锚定
# （:(top) magic），与 .gitignore 里同名根锚定规则一一对应：
#   docs/  reports/  evidence/  reports-evidence/   —— 第一批
#   design/                                          —— 第二批
#   scripts/data-root-migration/evidence/            —— 第二批（嵌套路径，写全路径）
#   仓库根 *_flow-*.md                              —— 第二批（:(top,glob) 只匹配根层）
#   .nebflow/                                        —— 第三批：gitignore 已忽略整个目录，
#     但 gitignore 挡不住已跟踪文件（实测有过程日志穿透入库）——索引面必须自证零跟踪
# 新增产物时同步本文件与 .gitignore（两处都得写，单一真源是 .gitignore，
# 本文件是它的 CI 执法面）。
#
# 本地等价：bash scripts/check-no-tracked-artifacts.sh
# Exit 0 = 全绿（索引里零产物）；非 0 = 有产物被跟踪 / git 面不可信。
#
# C-face: provenance-triple schema validation for direct-merge evidence archives.
# Every .nebflow/evidence/direct-merge-*.json archive must carry the provenance
# triple: authorization (who ordered the direct merge), executor (who ran it),
# verification (what was run: command + exitCode + a readout). Any missing leg
# = red. Archives written before this schema may declare their gaps factually
# under a top-level "schemaBackfill" object (keys: "executor",
# "verification.command", "verification.exitCode", "verification.readout").
# A backfill must state what the archive DOES record - never invent values.
# Validation is presence-based: a non-zero exitCode backed by a documented
# known-baseline readout still satisfies the schema (the readout carries the
# verdict). The evidence directory is gitignored, so a fresh CI checkout has
# zero archives here - that is a vacuous green, not an error. Override the
# scanned directory with EVIDENCE_ROOT=<dir> to validate a live dataRoot's
# archives from any checkout.
set -u

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }

# "<展示名>|<git pathspec>"，一行一条。pathspec 一律 :(top) 根锚定：
# 不加 top magic 时语义随 cwd 漂移，且 design 这类词会连带匹配子目录同名路径。
# 规则里含 / 的（嵌套 evidence、根目录 md glob）必须写全，才不会被误读成任一层的同名目录。
ARTIFACT_SPECS='docs|:(top)docs
reports|:(top)reports
evidence|:(top)evidence
reports-evidence|:(top)reports-evidence
stand-evidence|:(top)stand-evidence
design|:(top)design
scripts/data-root-migration/evidence|:(top)scripts/data-root-migration/evidence
.nebflow|:(top).nebflow
<仓库根>*__flow-*.md|:(top,glob)*__flow-*.md'

cd "$REPO_ROOT" || fail "无法进入仓库根 $REPO_ROOT"
git rev-parse --is-inside-work-tree > /dev/null 2>&1 || fail "不是 git 工作树：${REPO_ROOT}（本门禁依赖索引，纯导出目录无法判定）"

# -- B 面：探针先行，证明 git ls-files 面可信 ---------------------------------
# src/ 是本仓的核心源码面，任何真实 checkout 都不可能为空。它为空 = git 面
# 不可信（非仓库 / 索引损坏 / 检出异常），此时 A 面的「零命中」毫无意义。
PROBE_COUNT="$(git ls-files -- src 2>/dev/null | wc -l | tr -d ' ')"
[ "$PROBE_COUNT" -gt 0 ] || fail "探针失败：git ls-files -- src 零命中（git 面不可信，拒绝给出「零产物」的假绿结论；检查 checkout 是否完整）"

# -- A 面：产物必须零跟踪 ----------------------------------------------------
VIOLATIONS=0
CHECKED=""
while IFS='|' read -r label spec; do
    [ -n "${label:-}" ] || continue
    # 目录/路径不存在时 git ls-files 输出为空且退出 0（CI 全新检出即此情形）。
    hits="$(git ls-files -- "$spec")"
    CHECKED="${CHECKED}${label} "
    if [ -n "$hits" ]; then
        count="$(printf '%s\n' "$hits" | wc -l | tr -d ' ')"
        echo "FAIL: ${label} 有 ${count} 个文件被 git 跟踪（过程产物不得入库，见 .gitignore）" >&2
        printf '%s\n' "$hits" | head -20 | sed 's/^/    /' >&2
        [ "$count" -gt 20 ] && echo "    …（其余 $((count - 20)) 条略）" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
done <<HEADS
$ARTIFACT_SPECS
HEADS

# -- C 面：direct-merge 证据档的溯源三元组结构红验 ----------------------------
# 判据（机械，presence-based）：authorization 非空；executor 非空（或
# schemaBackfill.executor 按事实补注）；verification 为对象或数组，且每条
# 记录带 command + exitCode + 读数字段（result/readings/reading/compile/
# specs/build/inTree/onMain/inWorktree/log/beforeFix/afterFix/detail 任一）。
# 旧档缺腿时由 schemaBackfill.<腿名> 出具事实补注（只描述档案里实际记了什么，
# 禁编造执行者身份或退出码）。EVIDENCE_ROOT 可指向活数据根的检出做实测。
# C-face runs before the A-face verdict so an A-face artifact debt never
# masks the schema verdict; the final exit aggregates both faces.
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$REPO_ROOT/.nebflow/evidence}"
C_FAIL=0
C_COUNT=0
shopt -s nullglob
for evidence_file in "$EVIDENCE_ROOT"/direct-merge-*.json; do
    C_COUNT=$((C_COUNT + 1))
    python3 - "$evidence_file" <<'PY' || C_FAIL=1
import json, sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
except Exception as exc:
    print(f"GAP: {path}: not valid JSON ({exc})")
    sys.exit(1)
if not isinstance(doc, dict):
    print(f"GAP: {path}: top level is not a JSON object")
    sys.exit(1)

backfill = doc.get("schemaBackfill")
backfill = backfill if isinstance(backfill, dict) else {}

def covered(leg):
    return bool(str(backfill.get(leg, "")).strip())

problems = []

auth = doc.get("authorization")
if auth is None or not str(auth).strip():
    problems.append("authorization missing or empty (no schemaBackfill face for it)")

executor = doc.get("executor")
if not (isinstance(executor, str) and executor.strip()) and not covered("executor"):
    problems.append("executor missing and schemaBackfill.executor absent")

READOUT_KEYS = ("result", "readings", "readout", "reading", "compile", "specs",
                "build", "inTree", "onMain", "inWorktree", "log",
                "beforeFix", "afterFix", "detail",
                "totalPassed", "totalFailed", "perSuite")

def entry_problems(entry, where):
    found = []
    if not isinstance(entry, dict):
        return [f"{where}: entry is not an object"]
    if not str(entry.get("command", "")).strip() and not covered("verification.command"):
        found.append(f"{where}: command missing and schemaBackfill['verification.command'] absent")
    if ("exitCode" not in entry or str(entry.get("exitCode")).strip() == "") and not covered("verification.exitCode"):
        found.append(f"{where}: exitCode missing and schemaBackfill['verification.exitCode'] absent")
    if not any(str(entry.get(k, "")).strip() for k in READOUT_KEYS) and not covered("verification.readout"):
        found.append(f"{where}: no readout field and schemaBackfill['verification.readout'] absent")
    return found

verification = doc.get("verification")
if verification is None:
    if not all(covered(f"verification.{leg}") for leg in ("command", "exitCode", "readout")):
        problems.append("verification missing and schemaBackfill does not cover all three faces")
elif isinstance(verification, list):
    if not verification:
        problems.append("verification is an empty list")
    for i, entry in enumerate(verification):
        problems += entry_problems(entry, f"verification[{i}]")
elif isinstance(verification, dict):
    direct = entry_problems(verification, "verification")
    if not direct:
        pass  # direct entry: command + exitCode + readout on the object itself
    else:
        # grouped form: every dict-valued member must itself be a complete
        # entry (e.g. {"compile": {...}, "specSuites": {...}}). Groups whose
        # members are readout-only (or non-dict) do not carry the triple and
        # must declare the gap via schemaBackfill.
        grouped_problems = []
        grouped_members = 0
        for name, member in verification.items():
            if isinstance(member, dict):
                grouped_members += 1
                grouped_problems += entry_problems(member, f"verification.{name}")
        if grouped_members == 0 or grouped_problems:
            problems += direct
else:
    problems.append("verification is neither an object nor a list")

if problems:
    for problem in problems:
        print(f"GAP: {path}: {problem}")
    sys.exit(1)
print(f"OK: {path}")
sys.exit(0)
PY
done
shopt -u nullglob
if [ "$C_COUNT" -eq 0 ]; then
    echo "OK: C 面 vacuous green：$EVIDENCE_ROOT 下无 direct-merge-*.json（本检出无证据档，无需红验）"
fi
if [ "$C_COUNT" -gt 0 ] && [ "$C_FAIL" -eq 0 ]; then
    echo "OK: C 面 direct-merge 溯源三元组全绿（${C_COUNT} 档全过）"
fi

# -- 总裁定：A 面（零跟踪）与 C 面（溯源三元组）任一红即整体红 -----------------
if [ "$VIOLATIONS" -gt 0 ]; then
    echo "" >&2
    echo "过程产物不得入库，见 .gitignore。修复方式（只移索引，磁盘文件零删除）：" >&2
    echo "    git rm -r --cached <目录>" >&2
    echo "禁止用 git add -f 绕过本门禁 —— 历史上正是 add -f 造成了 docs/reports 的矛盾态。" >&2
fi
if [ "$C_FAIL" -ne 0 ]; then
    echo "" >&2
    echo "direct-merge 证据档溯源三元组不全：缺的腿要么补齐，要么在 schemaBackfill 里按事实补注" >&2
    echo "（只描述档案实际记录了什么；禁编造执行者身份、命令或退出码）。" >&2
fi
if [ "$VIOLATIONS" -gt 0 ] || [ "$C_FAIL" -ne 0 ]; then
    exit 1
fi

echo "OK: 索引里零过程产物（${CHECKED% } 全部零跟踪；探针 src=${PROBE_COUNT} 条）"
