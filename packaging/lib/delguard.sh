#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# delguard.sh — 递归删除守卫（R5，2026-09-11 delguard 批）
#
# packaging/build-{linux,msi,dmg}.sh 共用的单一 choke point：**所有 rm -rf 目标先过断言**。
#
# 用法（source 后调用；断言失败 → 打印解析后路径 → 返回 1，调用方**必须** exit，绝不降级执行）：
#   . "$(dirname "$0")/lib/delguard.sh"
#   delguard_assert_paths "<label>" "<path>" [...]     # 只断言（入参处 / 删除点各跑一次）
#   delguard_rm_rf        "<label>" "<path>" [...]     # 断言 + rm -rf（DRY_RUN=1 时只打印不删）
#   delguard_resolve      "<path>"                     # 打印解析后绝对路径（最深已存在祖先 realpath + 余段）
#
# 断言口径：非空 / 非字面量 `~` / 长度 ≤ 1024 / 可解析 / 不命中黑名单
#   （`/`、`$HOME`、用户目录根、卷根、盘根）。黑名单对**目标**无条件生效，白名单不能翻案。
# 显式放行非标准输出根（默认**不放行**）：NEBFLOW_PKG_ALLOW_ROOT=<绝对路径> + CONFIRM_DELETE=yes-i-mean-it
#   → 目标必须严格位于该根之下且 ≠ 根本身；白名单根本身不得是 `/`。
#
# 非破坏性探测（负控不需要真跑打包；只断言、不删除）：
#   bash -c '. packaging/lib/delguard.sh; delguard_assert_paths probe "$HOME"; echo rc=$?'      # rc=1（拒）
#   bash -c '. packaging/lib/delguard.sh; delguard_assert_paths probe build/dist; echo rc=$?'  # rc=0（通过）
#   DRY_RUN=1 packaging/build-dmg.sh --out build/dist      # 只跑断言 + 跳过删除
#
# 注：本文件的**运行期消息一律用 ASCII**。原因：本机 bash 5.3.9 + zh_CN.UTF-8 下，
#     多字节字符紧邻变量展开时会发生字节丢失（`printf '%s\n' "（$v）"` → "（" 后的展开被吞），
#     纯文件内复现、LC_ALL=C 下正常。中文保留在注释里（不参与运行期字符串拼接）。
# ----------------------------------------------------------------------------

_DELGUARD_MAX_LEN=1024

# 词法归一（. / .. / 重复斜杠 / 末尾斜杠）→ 绝对路径；失败返回 1
_delguard_abspath() {
  local p="$1" seg out="" IFS
  [ -n "$p" ] || return 1
  case "$p" in
    /*) ;;
    *) p="$PWD/$p" ;;
  esac
  IFS=/
  # shellcheck disable=SC2086
  set -- $p
  IFS=$' \t\n'
  for seg in "$@"; do
    case "$seg" in
      ''|.) continue ;;
      ..) out="${out%/*}" ;;
      *) out="$out/$seg" ;;
    esac
  done
  [ -n "$out" ] || out="/"
  printf '%s\n' "$out"
}

# 解析后绝对路径 =「最深已存在祖先的 realpath」+ 余下段（不存在的尾段保留；异常不静默）
# 注意：末段可能是**文件**或 symlink（不能直接 cd 进去）→ 非目录时取父目录 realpath + 末段字面量。
delguard_resolve() {
  local p head tail real joined base parent
  p="$(_delguard_abspath "$1")" || return 1
  [ "$p" = "/" ] && { printf '%s\n' "/"; return 0; }
  head="$p"; tail=""
  while [ ! -e "$head" ] && [ "$head" != "/" ]; do
    tail="/$(basename "$head")$tail"
    head="$(dirname "$head")"
  done
  if [ -d "$head" ]; then
    real="$(cd "$head" 2>/dev/null && pwd -P)" || return 1
    joined="${real%/}${tail}"
  else
    base="$(basename "$head")"
    parent="$(dirname "$head")"
    real="$(cd "$parent" 2>/dev/null && pwd -P)" || return 1
    joined="${real%/}/$base$tail"
  fi
  [ -n "$joined" ] || joined="/"
  printf '%s\n' "$joined"
}

# 默认落点根（除黑名单外仍须落在其中之一）：$PWD / $TMPDIR / /tmp（均取 realpath）
_delguard_default_roots() {
  local c t
  c="$(cd "$PWD" 2>/dev/null && pwd -P)" || c=""
  [ -n "$c" ] && printf '%s\n' "$c"
  t="$(cd /tmp 2>/dev/null && pwd -P)" || t=""
  [ -n "$t" ] && printf '%s\n' "$t"
  if [ -n "${TMPDIR:-}" ]; then
    t="$(cd "$TMPDIR" 2>/dev/null && pwd -P)" || t=""
    [ -n "$t" ] && printf '%s\n' "$t"
  fi
  return 0
}

# 黑名单判定：命中 → 打印原因（ASCII）；未命中 → 无输出（调用方用 [ -n ... ]）
_delguard_blacklist_reason() {
  local r="$1" d1 rest hr
  [ -n "$r" ] || { printf '%s\n' "empty resolved path"; return 0; }
  case "$r" in
    /) printf '%s\n' "filesystem root"; return 0 ;;
    /root) printf '%s\n' "root home root"; return 0 ;;
    /Users|/Volumes|/home|/System) printf '%s\n' "system/user/volume root"; return 0 ;;
  esac
  if [ -n "${HOME:-}" ]; then
    hr="$(delguard_resolve "$HOME" 2>/dev/null)" || hr=""
    if [ -n "$hr" ] && [ "$r" = "$hr" ]; then
      printf 'user home root [%s]\n' "$hr"; return 0
    fi
  fi
  case "$r" in
    /Users/*|/Volumes/*|/home/*)
      d1="${r#/}"; d1="${d1%%/*}"
      rest="${r#"/$d1"}"; rest="${rest#/}"
      case "$rest" in
        */*) : ;;                                     # 更深层子目录 → 放行
        *) printf 'user/volume root [%s]\n' "$r"; return 0 ;;
      esac
      ;;
  esac
  # 盘符形态（git-bash on Windows）：C: / C:/ / C:\Users / C:\Users\<seg>
  if printf '%s\n' "$r" | grep -Eq '^[A-Za-z]:([\\/])?([Uu]sers([\\/][^\\/]+)?)?$'; then
    printf 'drive/user root [%s]\n' "$r"; return 0
  fi
  return 0
}

# 断言：全部通过 → 0（打印解析后路径）；任一不通过 → 1（stderr 打印解析后路径）
delguard_assert_paths() {
  local label="$1"; shift
  [ "$#" -gt 0 ] || { echo "delguard: internal error - no path given" >&2; return 1; }
  local p r reason aroot allow default_roots root ok
  allow="${NEBFLOW_PKG_ALLOW_ROOT:-}"
  default_roots="$(_delguard_default_roots)"
  if [ -n "$allow" ] && [ "${CONFIRM_DELETE:-}" != "yes-i-mean-it" ]; then
    echo "delguard[$label]: REFUSE NEBFLOW_PKG_ALLOW_ROOT set without CONFIRM_DELETE=yes-i-mean-it (deny by default)" >&2
    return 1
  fi
  aroot=""
  if [ -n "$allow" ]; then
    aroot="$(delguard_resolve "$allow")" || { echo "delguard[$label]: REFUSE unresolvable allow-root [$allow]" >&2; return 1; }
    if [ "$aroot" = "/" ]; then
      echo "delguard[$label]: REFUSE allow-root = filesystem root" >&2; return 1
    fi
    echo "delguard[$label]: allow-root active [$aroot] (CONFIRM_DELETE=yes-i-mean-it)" >&2
  fi
  for p in "$@"; do
    if [ -z "$p" ]; then
      echo "delguard[$label]: REFUSE empty path -> ABORT" >&2; return 1
    fi
    case "$p" in
      '~'|'~/'*)
        echo "delguard[$label]: REFUSE literal tilde (unexpanded) [$p] -> ABORT" >&2; return 1 ;;
    esac
    if [ "${#p}" -gt "$_DELGUARD_MAX_LEN" ]; then
      echo "delguard[$label]: REFUSE path too long (${#p} > $_DELGUARD_MAX_LEN) [$p] -> ABORT" >&2; return 1
    fi
    r="$(delguard_resolve "$p")" || { echo "delguard[$label]: REFUSE unresolvable path (resolved=<none>) raw=[$p] -> ABORT" >&2; return 1; }
    reason="$(_delguard_blacklist_reason "$r")"
    if [ -n "$reason" ]; then
      echo "delguard[$label]: REFUSE blacklist hit [$reason] -> ABORT: resolved=[$r]" >&2; return 1
    fi
    # ① 默认落点根之下（$PWD / $TMPDIR / /tmp）——挡住 symlink 逃逸到 home 等 repo 外目录
    ok=0
    while IFS= read -r root; do
      [ -n "$root" ] || continue
      if [ "$r" = "$root" ]; then
        echo "delguard[$label]: REFUSE target IS an allowed root itself: resolved=[$r]" >&2; return 1
      fi
      case "$r" in
        "$root"/*) ok=1; break ;;
      esac
    done <<< "$default_roots"
    # ② 显式白名单根之下（已确认）
    if [ "$ok" != "1" ] && [ -n "$aroot" ]; then
      if [ "$r" = "$aroot" ]; then
        echo "delguard[$label]: REFUSE deleting the allow-root itself: resolved=[$r]" >&2; return 1
      fi
      case "$r" in
        "$aroot"/*) ok=1 ;;
      esac
    fi
    if [ "$ok" != "1" ]; then
      echo "delguard[$label]: REFUSE target outside allowed roots ({\$PWD,\$TMPDIR,/tmp} or NEBFLOW_PKG_ALLOW_ROOT): resolved=[$r] -> ABORT" >&2
      return 1
    fi
  done
  for p in "$@"; do
    r="$(delguard_resolve "$p")"
    echo "delguard[$label]: WILL DELETE resolved=[$r] raw=[$p]"
  done
  return 0
}

# 断言 + 删除。DRY_RUN=1 → 只断言 + 打印，不执行删除（非破坏性探测入口）。
delguard_rm_rf() {
  local label="$1"; shift
  delguard_assert_paths "$label" "$@" || return 1
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "delguard[$label]: DRY_RUN=1 - skip rm -rf [$*]" >&2
    return 0
  fi
  rm -rf -- "$@"
}
