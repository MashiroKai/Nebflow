#!/bin/bash
# watchdog-stage2-lockout.test.sh — Stage2「锁死」零副作用夹具（2026-11-05 运维批 · 开启 Stage1 同批）
#
# 目的：把「NEBFLOW_WD_CLAUDE_BIN 指到确定不存在的路径 ⇒ Stage2 只记一行、绝不执行」这条契约
#   钉成可复跑的机械断言。被测 = watchdog 脚本本体，在**完全沙箱化**的 NF_HOME 上跑：
#     * 夹具端口取空位端口（19171 起），绝不碰 6138；真实 6138 监听 pid 前后必须相同（收尾硬断言）
#     * NEBFLOW_WD_NF_HOME / REPO_DIR / COMPILE_LOG 全部落沙箱；COMPILE_CMD=true 不跑 sbt
#     * restart.sh = 桩（只记一行、零信号），故「DEAD ⇒ 进 Stage1 一次」可被机械断言
#
# 用例：
#   L1 LOCKED    CLAUDE_BIN = 沙箱内**不存在**的路径（= 生产 plist 的锁死形态）
#                ⇒ Stage2 走「不可用」分支：恰好一行日志、无 `=== Stage2`、不落 wd-stage2-ts、
#                  不生成 repair-report、restart 桩仍只被 Stage1 调用 1 次、rc=0
#   L2 CONTROL   CLAUDE_BIN = 可执行的**桩**（对照：证明夹具真的看得见 spawn）
#                ⇒ 出现 `=== Stage2` 行、桩被调用、wd-stage2-ts 被写（故 L1 的「零」不是空转）
#   L3 COOLDOWN  预写冷却戳 ⇒ 走「Stage2 冷却中」一行 + 不 spawn（冷却闸仍在，锁死不改变它）
#
# 用法：bash ops/test/watchdog-stage2-lockout.test.sh [被测脚本路径]
# 退出码：0 = 全绿；1 = 有断言失败。
set -u
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

HERE=$(cd "$(dirname "$0")" && pwd)
SUT="${1:-$HERE/../bin/nebflow-watchdog.sh}"
[[ -f "$SUT" ]] || { echo "被测脚本不存在：$SUT" >&2; exit 1; }
SUT=$(cd "$(dirname "$SUT")" && pwd)/$(basename "$SUT")

SBX=$(mktemp -d "${TMPDIR:-/tmp}/wd-s2lock.XXXXXX") || exit 1
cleanup() { rm -rf "$SBX"; }
trap cleanup EXIT

pass=0; fail=0
ok()  { pass=$((pass + 1)); printf 'PASS  %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf 'FAIL  %s\n' "$1"; }
hdr() { printf '\n── %s ──\n' "$1"; }

assert_has()      { if grep -qF -- "$2" "$1" 2>/dev/null; then ok "$3"; else bad "$3（应命中「$2」，实际未命中；$1）"; fi; }
assert_hasnt()    { if grep -qF -- "$2" "$1" 2>/dev/null; then bad "$3（不应命中「$2」；$1）"; else ok "$3"; fi; }
assert_marker()   { if [[ -f "$1" ]]; then ok "$2"; else bad "$2（标记文件未生成：$1）"; fi; }
assert_no_marker(){ if [[ -f "$1" ]]; then bad "$2（标记文件不应存在：$1）"; else ok "$2"; fi; }
assert_eq()       { if [[ "$1" == "$2" ]]; then ok "$3"; else bad "$3（实际「$1」≠ 期望「$2」）"; fi; }
# grep -c / pgrep -c 零命中时退出码非 0（grep 仍打印 0，pgrep 也可能空）⇒ 统一收敛成单个数字，缺省 0
count_of()        { local n; n=$(grep -cF -- "$2" "$1" 2>/dev/null | head -1); echo "${n:-0}"; }
proc_count()      { local n; n=$(pgrep -fc "$1" 2>/dev/null | head -1); echo "${n:-0}"; }

# ---- 真实 6138 监听 pid / 宿主 claude 进程数：前后必须相同（红线自检） ----
REAL_PID_BEFORE=$(lsof -nP -tiTCP:6138 -sTCP:LISTEN 2>/dev/null | head -1)
CLAUDE_HOST_BEFORE=$(proc_count '/opt/homebrew/bin/claude')

PORT=""
for p in $(seq 19171 19200); do
  if ! lsof -nP -tiTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then PORT="$p"; break; fi
done
[[ -n "$PORT" ]] || { echo "找不到空位端口" >&2; exit 1; }
echo "沙箱: $SBX   夹具端口: $PORT   被测: $SUT"
echo "真实 6138 监听 pid（前）: ${REAL_PID_BEFORE:-none}   宿主 claude 进程数（前）: ${CLAUDE_HOST_BEFORE}"

# ---- 单轮运行器：沙箱 home + restart 桩 + Stage2 目标（空路径 / 可执行桩） ----
FX=""; CLAUDE_TARGET=""; RC=0; ELAPSED=0
run_case() {
  local name="$1" kind="$2"
  FX="$SBX/$name"
  mkdir -p "$FX/home/bin" "$FX/home/logs" "$FX/repo"
  printf '#!/bin/bash\necho "STUB-CALLED %s" >> "%s"\nexit 0\n' "$name" "$FX/restart-calls.log" \
    > "$FX/home/bin/nebflow-restart.sh"
  chmod +x "$FX/home/bin/nebflow-restart.sh"
  WLOG="$FX/home/logs/watchdog.log"
  case "$kind" in
    locked) CLAUDE_TARGET="$FX/does-not-exist/claude-locked" ;;   # 父目录都不存在 ⇒ 永不可 -x
    stub)   CLAUDE_TARGET="$FX/claude-stub.sh"
            printf '#!/bin/bash\necho "SPAWNED %s" >> "%s"\nexit 0\n' "$name" "$FX/claude-spawned.log" \
              > "$CLAUDE_TARGET"
            chmod +x "$CLAUDE_TARGET" ;;
  esac
}

invoke_wd() {
  local t0 t1
  t0=$(date +%s)
  ( cd "$FX/home" && env \
      NEBFLOW_WD_PORT="$PORT" \
      NEBFLOW_WD_NF_HOME="$FX/home" \
      NEBFLOW_WD_REPO_DIR="$FX/repo" \
      NEBFLOW_WD_FAIL_THRESHOLD=1 \
      NEBFLOW_WD_COMPILE_CMD=true \
      NEBFLOW_WD_COMPILE_LOG="$FX/compile.log" \
      NEBFLOW_WD_CLAUDE_BIN="$CLAUDE_TARGET" \
      NEBFLOW_WD_LOG_CANDIDATES="$FX/home/logs/stand.log:$FX/home/logs/nebflow.log" \
      /bin/bash "$SUT" >/dev/null 2>&1 )
  RC=$?
  t1=$(date +%s)
  ELAPSED=$((t1 - t0))
}

# 沙箱快照（剔除本脚本/Stage1 自身的正常记账产物），用于「Stage2 零文件写」断言
snapshot_files() { find "$FX" -type f | grep -v -E '/(watchdog\.log|wd-fails|compile\.log|wd-stage1-history|restart-calls\.log|claude-spawned\.log)$' | sort; }

# ============================================================
hdr "L1 LOCKED — CLAUDE_BIN 指向不存在路径（= 生产锁死形态）"
run_case locked locked
: > "$FX/home/logs/stand.log"
if [[ -e "$CLAUDE_TARGET" ]]; then bad "锁死路径竟然存在：$CLAUDE_TARGET"; else ok "锁死路径确认不存在：$CLAUDE_TARGET"; fi
SNAP_BEFORE=$(snapshot_files)
invoke_wd
SNAP_AFTER=$(snapshot_files)
assert_has   "$WLOG" "Stage2 不可用："                       "落「Stage2 不可用」一行"
assert_has   "$WLOG" "$CLAUDE_TARGET"                       "该行点名被锁死的二进制路径"
assert_eq    "$(count_of "$WLOG" 'Stage2 不可用')" "1"      "「Stage2 不可用」恰好 1 行（只记一行）"
assert_hasnt "$WLOG" "=== Stage2"                           '不出现 === Stage2 行（spawn 的唯一前置）'
assert_hasnt "$WLOG" "Stage2 claude 退出码"                 "不产生「Stage2 claude 退出码」行（未执行过）"
assert_no_marker "$FX/home/logs/wd-stage2-ts"               "未落 Stage2 冷却戳（脚本 :282 在 spawn 前写 ⇒ 执行流未越过该点）"
assert_no_marker "$FX/home/logs/watchdog-repair-report.md"  "未生成 repair-report（NF_HOME/logs）"
assert_no_marker "$FX/repo/watchdog-repair-report.md"       "未生成 repair-report（工作目录）"
assert_no_marker "$FX/claude-spawned.log"                   "Stage2 桩零调用标记不存在"
assert_eq    "$(count_of "$FX/restart-calls.log" 'STUB-CALLED')" "1" \
                                                            "restart 桩恰好被调用 1 次（仅 Stage1；Stage2 零）"
assert_eq    "$SNAP_BEFORE" "$SNAP_AFTER"                   "沙箱内零新增/零改动文件（除正常记账 watchdog.log/wd-fails）"
assert_eq    "$RC" "0"                                      "退出码 0（不升级、不失败）"
grep -F 'Stage2 不可用' "$WLOG" | sed 's/^/EVIDENCE  /'
printf 'INFO  耗时 %ss（rc=%s，含 Stage1 后 sleep 15）\n' "$ELAPSED" "$RC"

hdr "L2 CONTROL — CLAUDE_BIN 指向可执行桩（对照：夹具确实看得见 spawn）"
run_case control stub
: > "$FX/home/logs/stand.log"
invoke_wd
assert_has    "$WLOG" "=== Stage2"                      '对照组出现 === Stage2 行（spawn 臂可达）'
assert_marker "$FX/claude-spawned.log"                  "对照组 Stage2 桩被调用（故 L1 的「零」不是空转）"
assert_marker "$FX/home/logs/wd-stage2-ts"              "对照组落了冷却戳（故 L1 的「未落」有意义）"
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"

hdr "L3 COOLDOWN — 冷却戳已存在 ⇒ 只记一行、不 spawn（冷却闸未被锁死改动影响）"
run_case cooldown stub
: > "$FX/home/logs/stand.log"
date +%s > "$FX/home/logs/wd-stage2-ts"
invoke_wd
assert_has    "$WLOG" "Stage2 冷却中"                   "落「Stage2 冷却中」一行"
assert_no_marker "$FX/claude-spawned.log"               "冷却中零 spawn"
assert_hasnt  "$WLOG" "=== Stage2"                      '冷却中不出现 === Stage2 行'
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"

# ============================================================
hdr "红线自检：真实 6138 监听 pid / 宿主 claude 进程数 前后一致"
REAL_PID_AFTER=$(lsof -nP -tiTCP:6138 -sTCP:LISTEN 2>/dev/null | head -1)
CLAUDE_HOST_AFTER=$(proc_count '/opt/homebrew/bin/claude')
echo "真实 6138 监听 pid（后）: ${REAL_PID_AFTER:-none}   宿主 claude 进程数（后）: ${CLAUDE_HOST_AFTER}"
if [[ "${REAL_PID_BEFORE:-}" == "${REAL_PID_AFTER:-}" ]]; then
  ok "6138 监听 pid 前后相同（${REAL_PID_AFTER:-none}）"
else
  bad "6138 监听 pid 变了：${REAL_PID_BEFORE:-none} → ${REAL_PID_AFTER:-none}"
fi
assert_eq "${CLAUDE_HOST_BEFORE}" "${CLAUDE_HOST_AFTER}" "宿主 claude 进程数不变（未 spawn 真实 claude）"

printf '\n=== 结果：PASS %d / FAIL %d ===\n' "$pass" "$fail"
(( fail == 0 )) || exit 1
exit 0
