#!/bin/sh
# check-process-artifacts.sh — 过程产物停跟踪门禁（作者裁定 2026-11-02 16:20）
# ------------------------------------------------------------------
# 判据：`git ls-files evidence/ reports/ docs/` 必须为空。
#
# ── 为什么是「提交后检测」而不是「提交前预防」──
# 2026-11-02 16:20 作者裁定：evidence/ reports/ docs/ 三个顶层目录为**过程产物**，
# 本地保留、永不入库（裁定提交 5ba295d2a；清仓落地 1a42c7128 / cd2baefb5）。
# 归因回溯：37 个回流文件里 30 个来自我们自己的 stand@local 自动提交
# （9bd068a18 11-02 14:43 共 17 个、3b7448a0e 11-01 20:42 共 13 个），入库时间
# **早于**停跟踪裁定；其余 6 个为其它提交、1 个来自 USTC 侧 51beafa16。
#
# 复发路径实测是**双向合并**：对方分支上这些文件仍 tracked，合并进来会
#   (a) 重新出现在 `git ls-files`（本门禁的判据面），且
#   (b) **物理删除本地磁盘实体文件**（比重新纳管更疼）。
# git 原生无法阻止 `git add -f` 绕过 .gitignore —— 真·阻断只能靠服务端
# pre-receive（本仓无该权限）。所以本门禁的定位是**提交/合并后机器检测**，不靠人记：
# .gitignore 是首道防线（拦无 `-f` 的普通 add），本脚本是第二道。
#
# ── 白名单机制（默认空 = 全停）──
# 例外清单外置在 scripts/process-artifacts-allowlist.txt（数据与逻辑分离，同
# scripts/user-knowledge-patterns.txt 的先例）：每行一个相对仓根的路径，前缀匹配
# （条目 `docs/x.md` 一并豁免 `docs/x.md/…` 之下）。**默认清单为空 = 三目录全停**；
# 这是给「以后真遇到必须入库的交付物再单独谈」留的机制口子——放行任何条目都必须是
# 一次显式、可评审、留痕的改动，不得在合并冲突里静默追加。
#
# ── 用法 ──
#   sh scripts/check-process-artifacts.sh            # 门禁（CI 与本地同逻辑）
#   sh scripts/check-process-artifacts.sh --repo DIR  # 查另一个工作区（delegate 在
#                                                    # worktree 作业、要验 main 工作区时）；
#                                                    # 白名单取被查工作区那份
#   sh scripts/check-process-artifacts.sh --help
#
# 退出码：0 = 三目录归零（或违规项全部被白名单豁免）；1 = 发现被跟踪的过程产物；
#         2 = 环境错误（不在 git 仓内 / git 不可用）——与 1 区分，避免把「跑不起来」
#         误读成「有违规」。
#
# ── 合并后必须重跑（铁律）──
# 复发路径是双向合并，因此**每次跨仓合并（含反向合并、吸收对方分支）之后都必须
# 重跑本脚本**，即便合并前是绿的：
#   sh scripts/check-process-artifacts.sh || echo "合并带回了过程产物，先止损"
# CI 面：.gitlab-ci.yml 的 process-artifacts-gate（stage=gates）在 main/beta/release
# 分支 push 与 tag 上跑 ——「合并进 main 后 push」即被覆盖。
set -u

# ── 受管目录（裁定面的三目录；增减属作者裁量，须同步本表与 .gitignore 尾部）──
GUARDED_DIRS="evidence reports docs"

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$SCRIPT_DIR/..

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      awk 'NR>1 && /^set -u$/{exit} NR>1{sub(/^# ?/,""); print}' "$0"
      exit 0 ;;
    --repo)
      [ $# -ge 2 ] || { echo "usage: check-process-artifacts.sh [--repo DIR] [--help]" >&2; exit 2; }
      REPO_ROOT=$2; shift 2 ;;
    *) echo "usage: check-process-artifacts.sh [--repo DIR] [--help]" >&2; exit 2 ;;
  esac
done

[ -d "$REPO_ROOT" ] || { echo "环境错误：--repo 目录不存在" >&2; exit 2; }
REPO_ROOT=$(cd "$REPO_ROOT" && pwd)
ALLOWLIST=$REPO_ROOT/scripts/process-artifacts-allowlist.txt

command -v git >/dev/null 2>&1 || { echo "环境错误：找不到 git" >&2; exit 2; }
git -C "$REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1 \
  || { echo "环境错误：$REPO_ROOT 不是 git 工作区" >&2; exit 2; }

TMPD=$(mktemp -d "${TMPDIR:-/tmp}/proc-artifacts-gate.XXXXXX") || { echo "环境错误：mktemp 失败" >&2; exit 2; }
trap 'rm -rf "$TMPD"' EXIT INT TERM
ENTRIES=$TMPD/entries          # 归一化后的白名单条目（每行一条）
TRACKED=$TMPD/tracked          # 三目录下被跟踪的文件（每行一条）

# ── 读白名单（文件缺失 = 空清单，脚本仍可独立运行）──
: > "$ENTRIES"
if [ -f "$ALLOWLIST" ]; then
  sed 's/#.*//' "$ALLOWLIST" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' \
    | sed '/^$/d; s|^\./||' > "$ENTRIES"
else
  echo "NOTE: 白名单文件不存在（${ALLOWLIST}）——按空清单处理（全停）" >&2
fi
ALLOW_COUNT=$(awk 'NF{n++} END{print n+0}' "$ENTRIES")

# entry_matches <相对路径> <白名单条目> —— 精确匹配，或条目是父目录/父前缀
entry_matches() {
  [ "$1" = "$2" ] && return 0
  case "$1" in "$2"/*) return 0 ;; esac
  return 1
}
is_allowed() { # $1 = 相对仓根路径
  local_rc=1
  while IFS= read -r e; do
    [ -z "$e" ] && continue
    if entry_matches "$1" "$e"; then local_rc=0; break; fi
  done < "$ENTRIES"
  return $local_rc
}

# ── gitignore 首道防线探针（informational，不影响退出码）──
# 忽略规则被删时普通 `git add` 会静默重新纳管；把这一点显性化。
UNIGNORED=""
for d in $GUARDED_DIRS; do
  git -C "$REPO_ROOT" check-ignore -q "$d/.gate-probe" || UNIGNORED="$UNIGNORED $d/"
done

# shq <path> —— 打印可直接粘贴执行的路径形态：含空白/引号/元字符时单引号包裹
shq() {
  case "$1" in
    *[!A-Za-z0-9._/@%+:,=-]*) printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")" ;;
    *) printf '%s' "$1" ;;
  esac
}

# ── 主断言：git ls-files 三目录必须为空 ──
# -z + tr：路径按字节原样落地（中文文件名/空格不会被 core.quotePath 转义成
# \346\226\207 形态），逐条精确比对白名单。含换行的文件名不在支持范围（本仓无此形态）。
# pathspec 用位置参数逐个传（不靠命令替换做词分割，杜绝文件名带空格时的歧义）。
set -- 
for d in $GUARDED_DIRS; do set -- "$@" "$d/"; done
git -C "$REPO_ROOT" ls-files -z -- "$@" | tr '\0' '\n' > "$TRACKED"

VIOLATIONS=$TMPD/violations
EXEMPTED=$TMPD/exempted
: > "$VIOLATIONS"; : > "$EXEMPTED"
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if is_allowed "$f"; then printf '%s\n' "$f" >> "$EXEMPTED"; else printf '%s\n' "$f" >> "$VIOLATIONS"; fi
done < "$TRACKED"

VIOLATION_COUNT=$(awk 'NF{n++} END{print n+0}' "$VIOLATIONS")
EXEMPT_COUNT=$(awk 'NF{n++} END{print n+0}' "$EXEMPTED")

if [ "$VIOLATION_COUNT" -gt 0 ]; then
  echo "✗ process-artifacts gate FAILED — 过程产物目录被重新纳管（$VIOLATION_COUNT 个文件）"
  echo ""
  while IFS= read -r f; do printf '  VIOLATION  %s\n' "$f"; done < "$VIOLATIONS"
  echo ""
  echo "修复（本机执行；只摘索引，磁盘实体文件原样留在本地）："
  while IFS= read -r f; do printf '  git rm -r --cached %s\n' "$(shq "$f")"; done < "$VIOLATIONS"
  printf '  # 一次性批量：\n  git rm -r --cached --'
  while IFS= read -r f; do printf ' %s' "$(shq "$f")"; done < "$VIOLATIONS"
  echo ""
  echo ""
  echo "历史对象保留、不重写历史：--cached 只摘索引，磁盘上的实体文件原样留在本地。"
  echo "这是止损不是回滚 —— 不要用 reset / filter-branch 改写已发布的历史。"
  echo ""
  echo "复发来源提示：双向合并（对方分支仍 tracked）是实测路径；本次修复后请重跑"
  echo "本脚本，并在下一次跨仓合并之后再跑一次。确需入库的交付物：写进"
  echo "scripts/process-artifacts-allowlist.txt（当前 $ALLOW_COUNT 条），逐条留痕，"
  echo "不要在合并冲突里静默放行。"
  if [ -n "$UNIGNORED" ]; then
    echo ""
    echo "NOTE: .gitignore 未忽略：${UNIGNORED}（首道防线已缺，普通 git add 也会纳管）"
  fi
  exit 1
fi

# ── 通过 ──
printf '✓ process-artifacts gate clean: git ls-files'
for d in $GUARDED_DIRS; do printf ' %s/' "$d"; done
echo " = 0 违规（$EXEMPT_COUNT 个被白名单豁免）"
echo "  白名单：$ALLOW_COUNT 条生效（scripts/process-artifacts-allowlist.txt）"
if [ "$EXEMPT_COUNT" -gt 0 ]; then
  echo "  白名单豁免的已跟踪文件（$EXEMPT_COUNT 个）："
  while IFS= read -r f; do printf '    EXEMPT  %s\n' "$f"; done < "$EXEMPTED"
fi
# 白名单条目一条未命中 = 陈旧条目，提示清理（不影响退出码）
while IFS= read -r e; do
  [ -z "$e" ] && continue
  stale=1
  while IFS= read -r f; do
    if entry_matches "$f" "$e"; then stale=0; break; fi
  done < "$TRACKED"
  [ "$stale" -eq 1 ] && echo "  NOTE: 白名单条目未命中任何被跟踪文件（陈旧条目？）：$e"
done < "$ENTRIES"
[ -n "$UNIGNORED" ] && echo "  NOTE: .gitignore 未忽略：$UNIGNORED"
echo "  铁律：每次跨仓合并之后必须重跑本脚本（双向合并会把对方仍 tracked 的文件带回来）。"
exit 0
