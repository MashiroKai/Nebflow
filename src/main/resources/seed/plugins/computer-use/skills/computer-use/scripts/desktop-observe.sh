#!/usr/bin/env bash
# computer-use · 观测入口
#
# 两条硬语义：
#   1) 默认 dry-run —— 不加 --run 时只打印将执行的命令原文，一个宿主调用都不发。
#   2) 机械守卫常开 —— 目标命中宿主保护区（本机服务端口监听进程、调用者祖先链、
#      拒绝名单应用名）时以退出码 3 拒绝（HOST-PROTECTED）。
#
# 退出码：0 成功 / 2 用法错误 / 3 HOST-PROTECTED / 4 目标不存在
#         5 执行失败（含权限拒绝，fail-closed，绝不重试） / 64 环境不满足
#
# 纪律：只读优先；失败即收口；不重试；不发任何信号；除目标文件外不写任何路径。
set -u

PROG="desktop-observe.sh"
SELF_PID=$$
LISTEN_PORT="${DESKTOP_CONTROL_LISTEN_PORT:-8080}"
# 拒绝名单（粗粒度前置过滤；平台侧「验身」就绪后由其接管，本层宁拒不放）
DENY_NAMES_DEFAULT="nebflow nebula sbt java openjdk"

RUN=0
REGION=""
TARGET_PID=""

die() { echo "$PROG: $2" >&2; exit "$1"; }

usage() {
  cat <<EOF
用法: $PROG <子命令> [选项] [位置参数]

子命令:
  readiness                        被动就绪探测（零宿主副作用，恒安全）
  apps                             已安装应用清单（文件系统面，零授权）
  running                          运行中应用清单（需辅助功能授权）
  windows <应用名>                 窗口清单（需辅助功能授权）
  screen [--region x,y,w,h] <路径> 截图（需屏幕录制授权）
  clipboard-read                   读剪贴板
  clipboard-write <文本>           写剪贴板

选项:
  --run        实际执行宿主调用（缺省 = dry-run：只打印命令原文，零执行）
  --pid <n>    声明目标进程号；命中宿主保护区即拒绝
  -h|--help    本帮助

环境变量:
  DESKTOP_CONTROL_LISTEN_PORT   服务端口（默认 8080，用于识别宿主保护区）
  DESKTOP_CONTROL_DENY_NAMES    拒绝名单应用名（空格分隔），覆盖默认值
EOF
  exit 0
}

# ── 宿主保护区判定 ────────────────────────────────────────────────
listener_pid() {
  command -v lsof >/dev/null 2>&1 || return 1
  lsof -nP -iTCP:"$LISTEN_PORT" -sTCP:LISTEN -t 2>/dev/null | head -1
}

ancestors() {
  local p="$SELF_PID" i=0 line ppid
  while [ "$i" -lt 32 ]; do
    line=$(ps -p "$p" -o pid=,ppid= 2>/dev/null) || break
    [ -z "$line" ] && break
    ppid=$(echo "$line" | awk '{print $2}')
    [ -z "$ppid" ] && break
    echo "$ppid"
    [ "$ppid" -le 1 ] && break
    p="$ppid"; i=$((i+1))
  done
}

is_protected_pid() {
  local pid="$1" a lp
  [ -z "$pid" ] && return 1
  [ "$pid" = "$SELF_PID" ] && return 0
  [ "$pid" = "1" ] && return 0
  lp=$(listener_pid)
  [ -n "$lp" ] && [ "$pid" = "$lp" ] && return 0
  for a in $(ancestors); do [ "$pid" = "$a" ] && return 0; done
  return 1
}

deny_names() { echo "${DESKTOP_CONTROL_DENY_NAMES:-$DENY_NAMES_DEFAULT}"; }

is_denied_name() {
  local name_lc n n_lc
  name_lc=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
  for n in $(deny_names); do
    n_lc=$(printf '%s' "$n" | tr '[:upper:]' '[:lower:]')
    case "$name_lc" in *"$n_lc"*) return 0 ;; esac
  done
  return 1
}

refuse_host() {
  echo "HOST-PROTECTED: $1" >&2
  echo "  原因: 目标落在宿主保护区（本机服务端口监听进程 / 调用者祖先链 / 拒绝名单应用）" >&2
  echo "  纪律: 宿主目标在审批流之前即拒绝——不重试、不换姿势、不绕过（references/host-dependencies.md §C）" >&2
  exit 3
}

# ── 执行层：dry-run 默认 ──────────────────────────────────────────
run_cmd() {
  local printable="$1"; shift
  if [ "$RUN" -ne 1 ]; then
    echo "dry-run: $printable"
    echo "  (未执行；加 --run 才真跑。默认零宿主调用。)"
    return 0
  fi
  echo "exec: $printable"
  local out rc
  out=$("$@" 2>&1); rc=$?
  printf '%s\n' "$out"
  if [ "$rc" -ne 0 ]; then
    case "$out" in
      *"not allowed assistive access"*|*"辅助功能"*|*"-1719"*|*"-25211"*)
        echo "[FAIL] 辅助功能授权缺失 —— 引导一次权限卡后结束本回合（SKILL §2），不重试" >&2 ;;
      *"screen recording"*|*"Screen Recording"*|*"-3801"*)
        echo "[FAIL] 屏幕录制授权缺失 —— 引导一次权限卡后结束本回合（SKILL §2），不重试" >&2 ;;
      *)
        echo "[FAIL] 命令返回 rc=$rc —— 原样保留上面的错误文本，fail-closed 收口" >&2 ;;
    esac
    exit 5
  fi
  return 0
}

# ── 子命令 ────────────────────────────────────────────────────────
cmd_readiness() {
  echo "platform=$(uname -s)"
  echo "arch=$(uname -m)"
  echo "os=$(sw_vers -productVersion 2>/dev/null || echo unknown)"
  echo "session=$(launchctl managername 2>/dev/null || echo unknown)"
  local t
  for t in osascript screencapture pbpaste pbcopy open system_profiler lsof; do
    if command -v "$t" >/dev/null 2>&1; then echo "tool.$t=yes"; else echo "tool.$t=no"; fi
  done
  if command -v python3 >/dev/null 2>&1; then
    if python3 -c 'import Quartz' >/dev/null 2>&1; then echo "quartz=yes"; else echo "quartz=no"; fi
  else
    echo "quartz=unknown(no python3)"
  fi
  local lp; lp=$(listener_pid)
  echo "host.listener_pid=${lp:-unavailable}"
  echo "host.self_pid=$SELF_PID"
  echo "host.ancestors=$(ancestors | tr '\n' ',' | sed 's/,$//')"
  echo "deny.names=$(deny_names)"
  echo "note=被动探测：零宿主副作用、不触发任何系统授权弹窗"
}

cmd_apps() {
  if [ "$RUN" -ne 1 ]; then
    echo "dry-run: ls /Applications"
    echo "  (未执行；加 --run 才真跑。本子命令仅读文件系统，无需任何系统授权。)"
    return 0
  fi
  echo "exec: ls /Applications"
  ls /Applications 2>/dev/null || die 5 "无法读取 /Applications"
}

cmd_running() {
  run_cmd "osascript -e 'tell application \"System Events\" to get name of every application process whose background only is false'" \
    osascript -e 'tell application "System Events" to get name of every application process whose background only is false'
}

cmd_windows() {
  [ -n "$WANT_APP" ] || die 2 "windows 需要 <应用名>"
  is_denied_name "$WANT_APP" && refuse_host "应用名 '$WANT_APP' 命中拒绝名单（$(deny_names)）"
  run_cmd "osascript -e 'tell application \"System Events\" to tell process \"$WANT_APP\" to get name of every window'" \
    osascript -e "tell application \"System Events\" to tell process \"$WANT_APP\" to get name of every window"
}

cmd_screen() {
  [ -n "$OUT_PATH" ] || die 2 "screen 需要 <路径>"
  if [ -n "$REGION" ]; then
    run_cmd "screencapture -x -R $REGION $OUT_PATH" screencapture -x -R "$REGION" "$OUT_PATH"
  else
    run_cmd "screencapture -x -t png $OUT_PATH" screencapture -x -t png "$OUT_PATH"
  fi
}

cmd_clipboard_read() { run_cmd "pbpaste" pbpaste; }

cmd_clipboard_write() {
  [ -n "$CLIP_TEXT" ] || die 2 "clipboard-write 需要 <文本>"
  if [ "$RUN" -ne 1 ]; then
    echo "dry-run: pbcopy (stdin <- 给定文本)"
    echo "  (未执行；加 --run 才真跑。写类动作，分级照走。)"
    return 0
  fi
  echo "exec: pbcopy (stdin <- 给定文本)"
  printf '%s' "$CLIP_TEXT" | pbcopy || die 5 "pbcopy 失败"
}

# ── 参数解析 ──────────────────────────────────────────────────────
WANT_APP=""; OUT_PATH=""; CLIP_TEXT=""
POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --run)    RUN=1 ;;
    --pid)    shift; [ $# -ge 1 ] || die 2 "--pid 需要数值"; TARGET_PID="$1" ;;
    --region) shift; [ $# -ge 1 ] || die 2 "--region 需要 x,y,w,h"; REGION="$1" ;;
    -h|--help) usage ;;
    -*) die 2 "未知选项: $1" ;;
    *) POS+=("$1") ;;
  esac
  shift
done

[ ${#POS[@]} -ge 1 ] || die 2 "缺子命令（-h 看用法）"
SUB="${POS[0]}"
case "$SUB" in
  windows)         [ ${#POS[@]} -ge 2 ] && WANT_APP="${POS[1]}" ;;
  screen)          [ ${#POS[@]} -ge 2 ] && OUT_PATH="${POS[1]}" ;;
  clipboard-write) [ ${#POS[@]} -ge 2 ] && CLIP_TEXT="${POS[1]}" ;;
  readiness|apps|running|clipboard-read) [ ${#POS[@]} -ge 2 ] && die 2 "子命令 $SUB 不接受位置参数: ${POS[1]}" ;;
  *)               die 2 "未知子命令: $SUB" ;;
esac

# 目标进程守卫（先于一切子命令）
if [ -n "$TARGET_PID" ]; then
  case "$TARGET_PID" in *[!0-9]*) die 2 "--pid 必须是数字" ;; esac
  is_protected_pid "$TARGET_PID" && refuse_host "进程号 $TARGET_PID 命中宿主保护区"
fi

case "$SUB" in
  readiness)       cmd_readiness ;;
  apps)            cmd_apps ;;
  running)         cmd_running ;;
  windows)         cmd_windows ;;
  screen)          cmd_screen ;;
  clipboard-read)  cmd_clipboard_read ;;
  clipboard-write) cmd_clipboard_write ;;
  *)               die 2 "未知子命令: $SUB" ;;
esac

exit 0
