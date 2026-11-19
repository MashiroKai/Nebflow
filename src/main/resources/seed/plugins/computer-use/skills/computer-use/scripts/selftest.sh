#!/usr/bin/env bash
# computer-use · 自测（全部为零副作用断言：不截图、不发按键、不读剪贴板内容）
# 退出码：0 全过 / 1 有断言失败
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
OBS="$HERE/desktop-observe.sh"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; echo "         实际: $2"; }

echo "== computer-use selftest =="

# T1 readiness 可跑通并给出宿主保护区读数
out=$("$OBS" readiness 2>&1); rc=$?
if [ "$rc" = 0 ] && printf '%s' "$out" | grep -q '^host.listener_pid='; then
  ok "T1 readiness rc=0 且含 host.listener_pid"
else bad "T1 readiness" "rc=$rc out=$(printf '%s' "$out" | head -3 | tr '\n' '|')"; fi

HOSTPID=$(printf '%s' "$out" | sed -n 's/^host.listener_pid=//p' | head -1)

# T2 守卫正例：服务端口监听进程 ⇒ HOST-PROTECTED / rc=3
if [ -n "$HOSTPID" ] && [ "$HOSTPID" != "unavailable" ]; then
  out=$("$OBS" --pid "$HOSTPID" readiness 2>&1); rc=$?
  if [ "$rc" = 3 ] && printf '%s' "$out" | grep -q 'HOST-PROTECTED'; then
    ok "T2 监听进程 pid=$HOSTPID ⇒ rc=3 HOST-PROTECTED"
  else bad "T2 监听进程守卫" "rc=$rc out=$(printf '%s' "$out" | head -2 | tr '\n' '|')"; fi
else
  bad "T2 监听进程守卫" "readiness 未取到监听进程 pid（lsof 不可用？）"
fi

# T3 守卫正例：调用者祖先链（本自测进程是子进程的祖先）⇒ HOST-PROTECTED
out=$("$OBS" --pid "$$" readiness 2>&1); rc=$?
if [ "$rc" = 3 ]; then ok "T3 祖先链 pid=$$ ⇒ rc=3"; else bad "T3 祖先链守卫" "rc=$rc"; fi

# T4 守卫正例：拒绝名单应用名
out=$("$OBS" windows nebflow-gui 2>&1); rc=$?
if [ "$rc" = 3 ] && printf '%s' "$out" | grep -q 'HOST-PROTECTED'; then
  ok "T4 拒绝名单应用名 ⇒ rc=3"
else bad "T4 应用名守卫" "rc=$rc out=$(printf '%s' "$out" | head -2 | tr '\n' '|')"; fi

# T5 守卫负例（变异安全）：未受保护的 pid 不误拒
out=$("$OBS" --pid 999999 readiness 2>&1); rc=$?
if [ "$rc" = 0 ]; then ok "T5 未受保护 pid ⇒ rc=0（不误拒）"; else bad "T5 误拒" "rc=$rc"; fi

# T6 dry-run：windows 未加 --run ⇒ 零执行
out=$("$OBS" windows Safari 2>&1); rc=$?
if [ "$rc" = 0 ] && printf '%s' "$out" | grep -q '^dry-run:' && ! printf '%s' "$out" | grep -q '^exec:'; then
  ok "T6 windows 默认 dry-run（零宿主调用）"
else bad "T6 dry-run" "rc=$rc out=$(printf '%s' "$out" | head -2 | tr '\n' '|')"; fi

# T7 dry-run：screen 未加 --run ⇒ 不产出文件
TMPF="${TMPDIR:-/tmp}/computer-use-selftest-$$.png"
rm -f "$TMPF"
out=$("$OBS" screen "$TMPF" 2>&1); rc=$?
if [ "$rc" = 0 ] && [ ! -e "$TMPF" ]; then ok "T7 screen 默认 dry-run（未产出文件）"
else bad "T7 dry-run 截图" "rc=$rc exists=$([ -e "$TMPF" ] && echo yes || echo no)"; fi
rm -f "$TMPF"

# T8 用法错误码
"$OBS" nonsense >/dev/null 2>&1; rc=$?
if [ "$rc" = 2 ]; then ok "T8 未知子命令 ⇒ rc=2"; else bad "T8 用法错误码" "rc=$rc"; fi

# T9 --help 可用
"$OBS" --help >/dev/null 2>&1; rc=$?
if [ "$rc" = 0 ]; then ok "T9 --help ⇒ rc=0"; else bad "T9 --help" "rc=$rc"; fi

# T10 脚本语法自检
if bash -n "$OBS" 2>/dev/null; then ok "T10 bash -n 语法通过"; else bad "T10 语法" "bash -n 报错"; fi

echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" = 0 ] || exit 1
exit 0
