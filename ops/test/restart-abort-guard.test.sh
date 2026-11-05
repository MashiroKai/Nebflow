#!/bin/bash
# restart-abort-guard.test.sh — stand-restart.sh v4.6「已杀、未起」兜底演练
#
# 目的（2026-11-05 故障恢复批 · 件二 验证要求）:
#   在**假 pid / 假端口**下演练「杀完即崩」，证明三件事：
#     T1 窗口内异常退出 ⇒ EXIT trap 兜底**真的把替身进程拉起来了** + 写了 restart-abort-*.marker
#     T2 正常路径 ⇒ 兜底**不重复起第二个实例**（幂等），且不写 marker
#     T3 marker 内容含 崩溃行号 / 退出码 / 时间 / 实例实测在跑与否 / 「已停未起」结论
#
# 安全边界（硬约束，本脚本遵守）:
#   · 绝不触碰真实例: 端口全部动态取空闲端口；数据根 STAND_HOME 指向 mktemp 沙箱；
#     旧腿是本地 python 假监听者（不是 sbt/java/nebflow 进程）；新腿是 fake-sbt（假服务）。
#   · 绝不杀任何 java/sbt/nebflow 进程: NEBFLOW_RESTART_NO_NARROW_SBT=1 关掉「本项目残留清扫」
#     腿（该腿只在该开关关闭时才会去扫 cwd 在项目目录的 sbt/scala/java）。
#   · 只 mock 掉「启动什么」，不 mock 被测逻辑本身——被测的是仓库里的真实脚本，原样拷贝进沙箱。
#
# 用法: bash ops/test/restart-abort-guard.test.sh [被测脚本路径]
#   缺省被测 = <本脚本目录>/../stand-restart.sh（仓内权威副本）
# 退出码: 0 = 全绿；1 = 有断言失败
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_UNDER_TEST="${1:-$HERE/../stand-restart.sh}"
HELPER_SRC="${STAND_HELPER_SRC:-$HOME/.stand/bin}"

PASS=0; FAIL=0; FAILED_NAMES=""
ok()   { PASS=$((PASS+1)); echo "  ✓ $*"; }
bad()  { FAIL=$((FAIL+1)); FAILED_NAMES="$FAILED_NAMES\n  - $*"; echo "  ✗ $*" >&2; }
hdr()  { echo; echo "── $* ──"; }

[[ -f "$SCRIPT_UNDER_TEST" ]] || { echo "FATAL: 被测脚本不存在: $SCRIPT_UNDER_TEST" >&2; exit 1; }
SCRIPT_UNDER_TEST="$(cd "$(dirname "$SCRIPT_UNDER_TEST")" && pwd)/$(basename "$SCRIPT_UNDER_TEST")"

# ---------- 沙箱 ----------
SBX="$(mktemp -d "${TMPDIR:-/tmp}/stand-guard-test.XXXXXX")" || exit 1
STD_HOME="$SBX/.stand"
mkdir -p "$STD_HOME/bin" "$STD_HOME/logs" "$SBX/bin"

cleanup() {
  # 只回收本脚本自己创建的沙箱进程（按记录下来的 pid/marker），绝不按名字扫杀。
  [[ -n "${OLD_LEG_PID:-}" ]] && kill -KILL "$OLD_LEG_PID" 2>/dev/null
  [[ -n "${NEW_LEG_PID:-}" ]] && kill -KILL "$NEW_LEG_PID" 2>/dev/null
  for f in "$SBX"/newleg-*.pid; do
    [[ -f "$f" ]] || continue
    p="$(cat "$f" 2>/dev/null)"; [[ -n "$p" ]] && kill -KILL "$p" 2>/dev/null
  done
  # 被测脚本的 SBT_OUT 是硬编码的 /tmp/nebflow-sbt-restart.log（真实环境也在用）——
  # 演练会 `>` 截断它，故先备份、退出时原样还原，保证演练对真实环境零副作用。
  if [[ -f "$SBX/sbt-out.bak" ]]; then
    cp -p "$SBX/sbt-out.bak" /tmp/nebflow-sbt-restart.log 2>/dev/null
  elif [[ -f "$SBX/sbt-out.absent" ]]; then
    rm -f /tmp/nebflow-sbt-restart.log 2>/dev/null
  fi
  rm -rf "$SBX"
}
trap cleanup EXIT

# 备份真实 SBT_OUT（见 cleanup 注释）；缺失则记「原本不存在」，退出时删掉演练产物。
if [[ -f /tmp/nebflow-sbt-restart.log ]]; then
  cp -p /tmp/nebflow-sbt-restart.log "$SBX/sbt-out.bak"
else
  : > "$SBX/sbt-out.absent"
fi

cp "$SCRIPT_UNDER_TEST" "$STD_HOME/bin/stand-restart.sh"
chmod +x "$STD_HOME/bin/stand-restart.sh"
for h in stand-setsid-exec.py stand-daemonize.py; do
  if [[ -f "$HELPER_SRC/$h" ]]; then
    cp "$HELPER_SRC/$h" "$STD_HOME/bin/$h"; chmod +x "$STD_HOME/bin/$h"
  else
    echo "FATAL: 缺 helper $HELPER_SRC/${h}（可用 STAND_HELPER_SRC 指定）" >&2; exit 1
  fi
done

free_port() {
  python3 - <<'PY'
import socket
s = socket.socket(); s.bind(("0.0.0.0", 0)); print(s.getsockname()[1]); s.close()
PY
}
listener_on() { lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1; }
api_code()    { curl -s -o /dev/null -w "%{http_code}" -m 3 "http://localhost:$1/api/sessions" 2>/dev/null; }

# ---------- 夹具: 假「旧腿」监听者（本地 python，可被 TERM） ----------
# 注意: 必须把 python 的 stdout/stderr 重定向走——否则它继承 $(...) 命令替换的管道写端，
# 后台进程不退出 ⇒ 命令替换永远等不到 EOF ⇒ 测试死挂（本批首次跑实测踩中）。
fake_listener() {
  local port="$1"
  python3 - "$port" >/dev/null 2>&1 <<'PY' &
import socket, sys, signal, time
port = int(sys.argv[1])
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("0.0.0.0", port)); s.listen(8)
signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
while True:
    try: s.accept()[0].close()
    except Exception: time.sleep(0.2)
PY
  echo $!
}

# ---------- 夹具: 假 sbt（新腿）——按 --port 起一个会说 HTTP 的假服务 ----------
cat > "$SBX/bin/fake-sbt" <<'FAKE_SBT'
#!/bin/bash
# 夹具冒充 sbt: 解析 --port，起一个最小 HTTP 服务（/api/sessions → 200）。
# 注意: 被测脚本 spawn 的形态是 `setsid-exec.py <sbt> "run --port N"`——**整个 "run --port N"
#   是一个 argv**（原脚本里带引号），不是两个。必须整体用正则抠端口，否则 argv 循环匹配不到
#   ⇒ port 为空 ⇒ python int("") 崩 ⇒ 就绪等待空转到宽限期后才报「sbt 提前退出」（实测踩中）。
# 每次被拉起都记一行 pid 到 ${FAKE_SBT_PIDDIR}，供测试数「被启动了几次」。
port="$(printf '%s' "$*" | sed -n 's/.*--port[ =]*\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ -n "${FAKE_SBT_PIDDIR:-}" ]] && { mkdir -p "$FAKE_SBT_PIDDIR"; echo "$$" > "$FAKE_SBT_PIDDIR/leg-$$.pid"; }
exec python3 - "$port" <<'PY'
import http.server, socketserver, sys
port = int(sys.argv[1])
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api/sessions"):
            body = b"[]"; self.send_response(200); self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
        else:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
http.server.HTTPServer(("127.0.0.1", port), H).serve_forever()
PY
FAKE_SBT
chmod +x "$SBX/bin/fake-sbt"

# 被测脚本的公共注入面（生产不设 = 零变化；这里全部显式指向沙箱）
# 夹具: S1「日志静默 ≥180s ⇒ 空闲」——必须让沙箱实例日志**存在且陈旧**。
#   （不建这个文件时 sig_log 会走「stat 失败」分支而判 RED ⇒ --restart-if-idle 一律 BUSY，
#    根本走不到重启臂。这是夹具前提，不是被测脚本的缺陷。）
seed_log_fixture() {
  mkdir -p "$STD_HOME/logs"
  : > "$STD_HOME/logs/nebflow.log"
  touch -t 202001010000 "$STD_HOME/logs/nebflow.log"
}
run_script() {   # $1 = 额外 env 赋值（可为空）; 其余 = 脚本参数
  local extra="$1"; shift
  seed_log_fixture
  env STAND_HOME="$STD_HOME" \
      NEBFLOW_RESTART_PORT="$P_MAIN" \
      NEBFLOW_RESTART_KILL_PORT="$P_KILL" \
      NEBFLOW_RESTART_SBT_BIN="$SBX/bin/fake-sbt" \
      NEBFLOW_RESTART_NO_NARROW_SBT=1 \
      FAKE_SBT_PIDDIR="$SBX/newleg" \
      ${extra:+$extra} \
      /bin/bash "$STD_HOME/bin/stand-restart.sh" "$@"
}

echo "=================================================================="
echo " stand-restart v4.6「已杀、未起」兜底演练（假 pid / 假端口）"
echo " 被测: $SCRIPT_UNDER_TEST"
echo " 沙箱: $SBX  （STAND_HOME=${STD_HOME}）"
echo "=================================================================="

# ==================================================================
hdr "T1 窗口内异常退出（STAND_RESTART_TEST_ABORT_AT=start-leg）"
# ==================================================================
P_MAIN="$(free_port)"; P_KILL="$(free_port)"
echo "  假端口: 主=$P_MAIN 杀=$P_KILL"

OLD_LEG_PID="$(fake_listener "$P_KILL")"
sleep 1
if [[ -n "$(listener_on "$P_KILL")" ]]; then ok "假旧腿已监听 :${P_KILL}（pid=${OLD_LEG_PID}）"; else bad "假旧腿未起来，夹具无效"; fi

run_script "STAND_RESTART_TEST_ABORT_AT=start-leg" --restart-if-idle > "$SBX/t1.out" 2>&1
T1_RC=$?
echo "  脚本退出码: ${T1_RC}（期望 66 = 在「起新腿」处模拟崩溃）"

MARKERS="$(ls "$STD_HOME"/logs/restart-abort-*.marker 2>/dev/null | head -5)"
MARKER_N="$(echo "$MARKERS" | grep -c . )"

# --- 断言 ---
[[ "$T1_RC" == "66" ]] && ok "脚本确在窗口内异常退出（rc=66）" || bad "rc=${T1_RC}，未在预期位置中止"

if [[ -z "$(kill -0 "$OLD_LEG_PID" 2>/dev/null && echo alive)" ]]; then
  ok "假旧腿已被脚本 TERM 掉（证明真的进入了「已杀」状态）"
else
  bad "假旧腿仍活着——「杀完」前提没成立"
fi

if [[ -n "$(listener_on "$P_MAIN")" ]]; then
  NEW_LEG_PID="$(listener_on "$P_MAIN")"
  ok "★ 兜底真的拉起了替身: :$P_MAIN 有监听者 pid=$NEW_LEG_PID"
else
  bad "★ 兜底未拉起替身——:$P_MAIN 无监听者（兜底失效）"
fi

CODE="$(api_code "$P_MAIN")"
[[ "$CODE" == "200" ]] && ok "替身服务可用（API=${CODE}）" || bad "替身 API=${CODE}（期望 200）"

[[ "$MARKER_N" == "1" ]] && ok "写了 1 个 restart-abort-*.marker" || bad "marker 数=${MARKER_N}（期望 1）"
MARKER="$(echo "$MARKERS" | head -1)"

if [[ -n "$MARKER" && -f "$MARKER" ]]; then
  echo "  ── marker 内容 ──"; sed 's/^/  │ /' "$MARKER"
  grep -q "崩溃行号" "$MARKER" && ok "marker 含崩溃行号" || bad "marker 缺崩溃行号"
  grep -qE "退出码 \\\$\\? *: *[0-9]+" "$MARKER" && ok "marker 含退出码 \$?" || bad "marker 缺退出码"
  grep -qE "时间 *: *[0-9]{4}-[0-9]{2}-[0-9]{2}" "$MARKER" && ok "marker 含时间戳" || bad "marker 缺时间"
  grep -q "实例-兜底前" "$MARKER" && ok "marker 含兜底前实测状态" || bad "marker 缺兜底前状态"
  grep -q "实例-兜底后" "$MARKER" && ok "marker 含兜底后实测状态" || bad "marker 缺兜底后状态"
  grep -qE "在跑|未在跑" "$MARKER" && ok "marker 明写实例在跑与否" || bad "marker 未明写实例在跑与否"
  grep -q "已停未起" "$MARKER" && ok "marker 含「已停未起」结论" || bad "marker 缺「已停未起」"
else
  bad "marker 文件不存在，无法校验内容"
fi

if grep -q "终态: 成功（EXIT 兜底拉起" "$SBX/t1.out"; then
  ok "脚本自身终态行明写「EXIT 兜底拉起」"
else
  bad "脚本终态行未交代兜底结果（日志仍只有一行崩）"
fi
grep -q "已杀、未起" "$SBX/t1.out" && ok "日志明写「已杀、未起」窗口" || bad "日志未提「已杀、未起」"

# 清理 T1 的替身
[[ -n "${NEW_LEG_PID:-}" ]] && kill -KILL "$NEW_LEG_PID" 2>/dev/null
sleep 1

# ==================================================================
hdr "T2 正常路径：兜底不得重复起第二个实例（幂等）"
# ==================================================================
rm -f "$STD_HOME"/logs/restart-abort-*.marker "$STD_HOME"/logs/restart-abort-latest.path
rm -rf "$SBX/newleg"
P_MAIN="$(free_port)"; P_KILL="$(free_port)"
echo "  假端口: 主=$P_MAIN 杀=$P_KILL"
OLD_LEG_PID="$(fake_listener "$P_KILL")"
sleep 1
[[ -n "$(listener_on "$P_KILL")" ]] && ok "假旧腿已监听 :$P_KILL" || bad "假旧腿未起来，夹具无效"

run_script "" --restart-if-idle > "$SBX/t2.out" 2>&1
T2_RC=$?
echo "  脚本退出码: ${T2_RC}（期望 0 = 正常成功）"
grep -E "^\[restart\] (起新腿|✓ 新实例就绪)|终态:" "$SBX/t2.out" | tail -5 | sed 's/^/  │ /'

[[ "$T2_RC" == "0" ]] && ok "正常路径成功退出（rc=0）" || bad "rc=${T2_RC}（期望 0）"
grep -q "终态: 成功" "$SBX/t2.out" && ok "终态行 = 成功" || bad "终态行非成功"

LEG_N="$(ls "$SBX"/newleg/*.pid 2>/dev/null | wc -l | tr -d ' ')"
[[ "$LEG_N" == "1" ]] && ok "★ 只启动了 1 个新腿（无重复启动）" || bad "★ 启动了 $LEG_N 个新腿（幂等失效，兜底与正常路径重复起）"

LISTENERS="$(lsof -nP -tiTCP:"$P_MAIN" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
LON="$(echo "$LISTENERS" | wc -w | tr -d ' ')"
[[ "$LON" == "1" ]] && ok "主端口只有 1 个监听者（${LISTENERS}）" || bad "主端口有 $LON 个监听者: $LISTENERS"

[[ -z "$(ls "$STD_HOME"/logs/restart-abort-*.marker 2>/dev/null)" ]] \
  && ok "正常路径未写 marker（兜底未误触）" || bad "正常路径写了 marker（兜底误触）"
grep -q "abort-guard" "$SBX/t2.out" && bad "正常路径出现 abort-guard 动作（窗口未及时关闭）" || ok "正常路径无 abort-guard 动作"
grep -q "终态: dry-run\|终态: 只检测" "$SBX/t2.out" && bad "模式未落到重启臂" || ok "模式正确落到重启臂"

# ==================================================================
hdr "T3 真实故障形态复现: 崩溃点是那条 PORT 行（bash 3.2 set -u）"
# ==================================================================
# 注: 下面第一行 **刻意保留裸 `$PORT（`**——它就是「改前形态」的复现样本（单引号内字面量，
# 本脚本自身不执行它，而是交给 bash -uc 当被测输入）。全脚本扫同类时这一行是唯一豁免项。
/bin/bash -uc 'PORT=6138; echo "x $PORT（日志 y"' >/dev/null 2>"$SBX/repro_bad.txt"; RC_BAD=$?
/bin/bash -uc 'PORT=6138; echo "x ${PORT}（日志 y"' >"$SBX/repro_good.txt" 2>&1; RC_GOOD=$?
[[ "$RC_BAD" != "0" ]] && LC_ALL=C grep -aq "unbound variable" "$SBX/repro_bad.txt" \
  && ok "改前形态必红: $(LC_ALL=C tr -d '\n' < "$SBX/repro_bad.txt")" || bad "改前形态未如预期报 unbound（rc=${RC_BAD}）"
[[ "$RC_GOOD" == "0" ]] && grep -q "x 6138（日志 y" "$SBX/repro_good.txt" \
  && ok "改后形态必绿: $(cat "$SBX/repro_good.txt")" || bad "改后形态仍失败（rc=${RC_GOOD}）"

# ==================================================================
hdr "T4 回归扫描: 全脚本 \$VAR 紧贴非 ASCII 字节（set -u 崩溃类）必须为 0"
# ==================================================================
# 判据: `$NAME` 后面紧跟 ≥0x80 的字节（全角括号/中文标点/emoji/中文词）。
#   bash 3.2 会把这些字节的首字节并进变量名 ⇒ 查未定义变量 ⇒ set -u 终止。
# 只算**可执行代码**（整行以 # 开头 = 注释，不执行，不计）；本测试脚本自身刻意保留的
# 「改前形态」样本行不参与（只扫被测脚本）。
SCAN_HITS="$(python3 - "$SCRIPT_UNDER_TEST" <<'PY'
import re, sys
pat = re.compile(rb'\$[A-Za-z_][A-Za-z0-9_]*')
hits = []
with open(sys.argv[1], 'rb') as f:
    for ln, line in enumerate(f, 1):
        if line.lstrip().startswith(b'#'):
            continue
        for m in pat.finditer(line):
            if m.end() < len(line) and line[m.end()] >= 0x80:
                hits.append("%d:%s" % (ln, m.group().decode()))
print("\n".join(hits))
PY
)"
if [[ -z "$SCAN_HITS" ]]; then
  ok "可执行代码同类命中 = 0（set -u 下不会再因 \$VAR 紧跟中文而崩）"
else
  bad "同类命中 $(echo "$SCAN_HITS" | wc -l | tr -d ' ') 处: $(echo "$SCAN_HITS" | tr '\n' ' ')"
fi

# ==================================================================
echo
echo "=================================================================="
echo " 结果: PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" != "0" ]] && { echo -e " 失败项:$FAILED_NAMES"; echo "=================================================================="; exit 1; }
echo " 全绿 ✓"
echo "=================================================================="
exit 0
