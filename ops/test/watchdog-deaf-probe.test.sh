#!/bin/bash
# watchdog-deaf-probe.test.sh — 失聪鉴别三态隔离夹具（2026-11-05 运维修复批，② 的机械验收）
#
# 目的：把「失聪判据读错日志路径」这条真缺陷钉成可复跑的机械断言。被测 = watchdog 脚本本体，
#   在**完全沙箱化**的 NF_HOME 上跑，绝不触碰真实实例：
#     * 夹具端口取空位端口（19138 起），绝不碰 6138；真实 6138 监听 pid 前后必须相同（收尾硬断言）
#     * NEBFLOW_WD_NF_HOME / NEBFLOW_WD_REPO_DIR / NEBFLOW_WD_COMPILE_LOG 全部落沙箱
#     * NEBFLOW_WD_COMPILE_CMD=true        不跑 sbt
#     * NEBFLOW_WD_CLAUDE_BIN=/usr/bin/false  挡死真 claude（Stage2 一律不逃逸夹具）
#     * 沙箱 bin/nebflow-restart.sh = 桩（只记一行、零信号），故「DEAD ⇒ 进 Stage1」可被机械断言
#
# 用例（三态 + 两侧回归）：
#   C0 HEALTHY         HTTP 200 且日志新鲜                ⇒ 健康，无判定行
#   C1 DEAF-stand      HTTP 非 2xx + 监听存活 + stand.log 新鲜（nebflow.log 陈旧）⇒ 判 DEAF、不进 Stage1
#   C2 DEAF-legacy     HTTP 非 2xx + 监听存活 + nebflow.log 新鲜（stand.log 陈旧）⇒ 判 DEAF（多候选取最新）
#   C3 DEAD-nolisten   无监听 + 日志新鲜                  ⇒ 判 DEAD、进 Stage1（restart 桩被调用）
#   C4 DEAD-stalelog   监听存活 + 两个日志都陈旧          ⇒ 判 DEAD（日志陈旧理由）、进 Stage1
#   C5 NOJUDGE         监听存活 + 日志候选全不存在        ⇒ 判「判据不可用」、**禁判 DEAD**、不进 Stage1
#   C6 FOREIGN         端口被非本实例进程占用             ⇒ 判 FOREIGN、不动作
#
# 用法：bash ops/test/watchdog-deaf-probe.test.sh [被测脚本路径]
#   缺省被测脚本 = 本文件同仓的 ops/bin/nebflow-watchdog.sh
# 退出码：0 = 全绿；1 = 有断言失败。
set -u
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

HERE=$(cd "$(dirname "$0")" && pwd)
SUT="${1:-$HERE/../bin/nebflow-watchdog.sh}"
[[ -f "$SUT" ]] || { echo "被测脚本不存在：$SUT" >&2; exit 1; }
SUT=$(cd "$(dirname "$SUT")" && pwd)/$(basename "$SUT")

SBX=$(mktemp -d "${TMPDIR:-/tmp}/wd-probe.XXXXXX") || exit 1
LISTENER_PIDS=""
cleanup() {
  local p
  for p in $LISTENER_PIDS; do kill "$p" 2>/dev/null; done   # 仅杀本夹具自建的 python 监听桩
  rm -rf "$SBX"
}
trap cleanup EXIT

pass=0; fail=0
ok()  { pass=$((pass + 1)); printf 'PASS  %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf 'FAIL  %s\n' "$1"; }
hdr() { printf '\n── %s ──\n' "$1"; }

assert_has()     { if grep -qF -- "$2" "$1" 2>/dev/null; then ok "$3"; else bad "$3（应命中「$2」，实际未命中；$1）"; fi; }
assert_hasnt()   { if grep -qF -- "$2" "$1" 2>/dev/null; then bad "$3（不应命中「$2」；$1）"; else ok "$3"; fi; }
assert_marker()  { if [[ -f "$1" ]]; then ok "$2"; else bad "$2（标记文件未生成：$1）"; fi; }
assert_no_marker() { if [[ -f "$1" ]]; then bad "$2（标记文件不应存在：$1）"; else ok "$2"; fi; }

# ---- 真实 6138 监听 pid：前后必须相同（本夹具的红线自检） ----
REAL_PID_BEFORE=$(lsof -nP -tiTCP:6138 -sTCP:LISTEN 2>/dev/null | head -1)

# ---- 空位端口 ----
PORT=""
for p in $(seq 19138 19170); do
  if ! lsof -nP -tiTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then PORT="$p"; break; fi
done
[[ -n "$PORT" ]] || { echo "找不到空位端口" >&2; exit 1; }
echo "沙箱: $SBX   夹具端口: $PORT   被测: $SUT"
echo "真实 6138 监听 pid（前）: ${REAL_PID_BEFORE:-none}"

# ---- 监听桩：python 脚本名含 nebflow ⇒ cmdline 命中 instance_cmd_matches（模拟「本实例」） ----
cat > "$SBX/nebflow-fixture-listener.py" <<'PY'
import http.server, sys
CODE = int(sys.argv[2]) if len(sys.argv) > 2 else 500
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(CODE)
        self.send_header('Content-Length', '4')
        self.end_headers()
        self.wfile.write(b'down')
    def log_message(self, *a):
        pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY
# 非本实例的监听桩（cmdline 不含 nebflow/sbt/GatewayMain/Nebflow ⇒ 应判 FOREIGN）
sed 's/^import http.server, sys$/import http.server, sys  # plain/' "$SBX/nebflow-fixture-listener.py" > "$SBX/plain-listener.py"

start_listener() {  # $1 = 脚本路径  $2 = 端口  $3 = HTTP 码；等就绪后回显 pid
  local pid i
  python3 "$1" "$2" "${3:-500}" >/dev/null 2>&1 &
  pid=$!
  LISTENER_PIDS="$LISTENER_PIDS $pid"
  for i in $(seq 1 100); do
    if lsof -nP -tiTCP:"$2" -sTCP:LISTEN >/dev/null 2>&1; then echo "$pid"; return 0; fi
    sleep 0.1
  done
  return 1
}
stop_listener() { kill "$1" 2>/dev/null; sleep 0.3; }

# ---- 单轮运行器：沙箱 home + 桩 restart.sh + 夹具环境变量 ----
run_case() {  # $1 = 用例名
  local name="$1"
  FX="$SBX/$name"
  LOCAL_PORT="$PORT"
  mkdir -p "$FX/home/bin" "$FX/home/logs" "$FX/repo"
  printf '#!/bin/bash\necho "STUB-CALLED $*" >> "%s"\nexit 0\n' "$FX/restart-calls.log" \
    > "$FX/home/bin/nebflow-restart.sh"
  chmod +x "$FX/home/bin/nebflow-restart.sh"
  WLOG="$FX/home/logs/watchdog.log"
}

invoke_wd() {  # 夹具环境变量齐备后跑一轮被测脚本；回显耗时（秒）
  local t0 t1
  t0=$(date +%s)
  env \
    NEBFLOW_WD_PORT="$LOCAL_PORT" \
    NEBFLOW_WD_NF_HOME="$FX/home" \
    NEBFLOW_WD_REPO_DIR="$FX/repo" \
    NEBFLOW_WD_FAIL_THRESHOLD="${FX_THRESHOLD:-1}" \
    NEBFLOW_WD_COMPILE_CMD=true \
    NEBFLOW_WD_COMPILE_LOG="$FX/compile.log" \
    NEBFLOW_WD_CLAUDE_BIN=/usr/bin/false \
    NEBFLOW_WD_LOG_CANDIDATES="$FX/home/logs/stand.log:$FX/home/logs/nebflow.log" \
    /bin/bash "$SUT" >/dev/null 2>&1
  RC=$?
  t1=$(date +%s)
  ELAPSED=$((t1 - t0))
}

# ============================================================
hdr "C0 HEALTHY — HTTP 200 + 日志新鲜"
run_case healthy
: > "$FX/home/logs/stand.log"; : > "$FX/home/logs/nebflow.log"
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 200) || bad "监听桩未就绪"
FX_THRESHOLD=3 invoke_wd
assert_hasnt "$WLOG" "鉴别判据" "健康轮不落判定行"
assert_no_marker "$FX/restart-calls.log" "健康轮不调 restart 桩"
printf 'INFO  健康轮耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

hdr "C1 DEAF（stand.log 新鲜 / nebflow.log 陈旧）— 旧缺陷的核心场景"
run_case deaf_stand
: > "$FX/home/logs/stand.log"                      # 当前真实日志：新鲜
: > "$FX/home/logs/nebflow.log"; touch -t 202610180000 "$FX/home/logs/nebflow.log"   # 历史路径：mtime 冻结
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 500) || bad "监听桩未就绪"
invoke_wd
assert_has "$WLOG" "本轮结论=DEAF" "判 DEAF（不是 DEAD）"
assert_has "$WLOG" "判「失聪」" "落「失聪」WARN 行"
assert_has "$WLOG" "stand.log" "判定行点名实际采用的日志 = stand.log"
assert_hasnt "$WLOG" "鉴别结果=DEAD" "不得判 DEAD"
assert_no_marker "$FX/restart-calls.log" "DEAF 不进 Stage1（restart 桩零调用）"
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

hdr "C2 DEAF（反向：nebflow.log 新鲜 / stand.log 陈旧）— 多候选「取最新 mtime」双向成立"
run_case deaf_legacy
: > "$FX/home/logs/stand.log";    touch -t 202610180000 "$FX/home/logs/stand.log"
: > "$FX/home/logs/nebflow.log"                    # 历史路径新鲜（若实例回退写旧名，判据仍活）
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 500) || bad "监听桩未就绪"
invoke_wd
assert_has "$WLOG" "本轮结论=DEAF" "判 DEAF（多候选取最新者）"
assert_has "$WLOG" "nebflow.log" "判定行点名 nebflow.log 为最新写入者"
assert_no_marker "$FX/restart-calls.log" "DEAF 不进 Stage1"
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

hdr "C3 DEAD（无监听 + 日志新鲜）— 端口上没有任何监听者"
run_case dead_nolistener
: > "$FX/home/logs/stand.log"
invoke_wd
assert_has "$WLOG" "鉴别结果=DEAD" "判 DEAD"
assert_has "$WLOG" "无监听者" "理由行点名「无监听者」"
assert_marker "$FX/restart-calls.log" "DEAD 进 Stage1（restart 桩被调用）"
printf 'INFO  耗时 %ss（rc=%s，含 Stage1 后 sleep 15）\n' "$ELAPSED" "$RC"

hdr "C4 DEAD（日志与 HTTP 都停：监听存活但两个日志都陈旧）"
run_case dead_stalelog
: > "$FX/home/logs/stand.log";    touch -t 202610010000 "$FX/home/logs/stand.log"
: > "$FX/home/logs/nebflow.log";  touch -t 202610010000 "$FX/home/logs/nebflow.log"
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 500) || bad "监听桩未就绪"
invoke_wd
assert_has "$WLOG" "鉴别结果=DEAD" "判 DEAD"
assert_has "$WLOG" "日志陈旧" "理由行点名「日志陈旧」"
assert_marker "$FX/restart-calls.log" "DEAD 进 Stage1（restart 桩被调用）"
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

hdr "C5 NOJUDGE（日志候选全不存在）— 判据不可用 ⇒ 禁判 DEAD、绝不杀"
run_case nojudge
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 500) || bad "监听桩未就绪"
invoke_wd
assert_has "$WLOG" "本轮结论=NOJUDGE" "降级为「判据不可用」"
assert_has "$WLOG" "判据不可用" "落「判据不可用」WARN 行"
assert_has "$WLOG" "禁止据此判 DEAD" "明确禁止据此判 DEAD"
assert_hasnt "$WLOG" "鉴别结果=DEAD" "不得判 DEAD"
assert_no_marker "$FX/restart-calls.log" "判据不可用 ⇒ 不进 Stage1（绝不误杀）"
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

hdr "C6 FOREIGN（端口被非本实例进程占用）— 不动作"
run_case foreign
: > "$FX/home/logs/stand.log"
LPID=$(start_listener "$SBX/plain-listener.py" "$PORT" 500) || bad "监听桩未就绪"
invoke_wd
assert_has "$WLOG" "本轮结论=FOREIGN" "判 FOREIGN"
assert_no_marker "$FX/restart-calls.log" "FOREIGN 不动作"
printf 'INFO  耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

hdr "C7 非健康轮不跑哨兵（② 顺序修复：find 不再排在健康探针之前）"
run_case down_no_sentinel
: > "$FX/home/logs/stand.log"                      # 日志新鲜 ⇒ 判据完整（DEAF 走快路径，不含 Stage1 的 sleep 15）
# 慢哨兵桩：真跑会睡 2s 并留标记；非健康轮必须一次都不跑
printf '#!/bin/bash\necho "SENTINEL-RAN" >> "%s"\nsleep 2\nexit 0\n' "$FX/sentinel-calls.log" \
  > "$FX/home/bin/nebflow-log-sentinel.sh"
chmod +x "$FX/home/bin/nebflow-log-sentinel.sh"
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 500) || bad "监听桩未就绪"
invoke_wd
assert_no_marker "$FX/sentinel-calls.log" "非健康轮不调哨兵（不付 find 的账）"
if (( ELAPSED < 2 )); then ok "非健康轮耗时不含哨兵成本（${ELAPSED}s < 哨兵桩 2s）"; else bad "非健康轮耗时 ${ELAPSED}s ≥ 哨兵桩 2s ⇒ 顺序仍有耦合"; fi
stop_listener "$LPID"

hdr "C8 健康轮跑哨兵（告警腿仍在）"
run_case up_with_sentinel
: > "$FX/home/logs/stand.log"
printf '#!/bin/bash\necho "SENTINEL-RAN" >> "%s"\nsleep 2\nexit 0\n' "$FX/sentinel-calls.log" \
  > "$FX/home/bin/nebflow-log-sentinel.sh"
chmod +x "$FX/home/bin/nebflow-log-sentinel.sh"
LPID=$(start_listener "$SBX/nebflow-fixture-listener.py" "$PORT" 200) || bad "监听桩未就绪"
FX_THRESHOLD=3 invoke_wd
assert_marker "$FX/sentinel-calls.log" "健康轮调哨兵（告警腿未被顺序修复误伤）"
printf 'INFO  健康轮（含哨兵桩）耗时 %ss（rc=%s）\n' "$ELAPSED" "$RC"
stop_listener "$LPID"

# ============================================================
hdr "红线自检：真实 6138 监听 pid 前后一致"
REAL_PID_AFTER=$(lsof -nP -tiTCP:6138 -sTCP:LISTEN 2>/dev/null | head -1)
echo "真实 6138 监听 pid（后）: ${REAL_PID_AFTER:-none}"
if [[ "${REAL_PID_BEFORE:-}" == "${REAL_PID_AFTER:-}" ]]; then
  ok "6138 监听 pid 前后相同（${REAL_PID_AFTER:-none}）"
else
  bad "6138 监听 pid 变了：${REAL_PID_BEFORE:-none} → ${REAL_PID_AFTER:-none}"
fi

printf '\n=== 结果：PASS %d / FAIL %d ===\n' "$pass" "$fail"
(( fail == 0 )) || exit 1
exit 0
