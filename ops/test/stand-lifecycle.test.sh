#!/bin/bash
# stand/nebflow CLI 生命周期守卫 — 纯 bash 断言测试 (无 bats 依赖)
# 被测对象: <repo>/ops/bin/{stand,nebflow,stand-cli-lib.sh}
#           (stand + nebflow 两入口的共享实现库) + ops/bin/stand-jdk (体检面)
#           + 改名批弃用垫片 {nebflow-restart-shim, nebflow-autostart-shim}
#           + ops/stand-autostart.sh (2026-11-03 双根分家批: nebflow-autostart.sh git mv 改名)
#
# 方法: 沙箱化 —— 每个用例把被测脚本(含同目录依赖库)拷进临时 bin/(伪造 jar +
#   current-version), STAND_HOME/NEBFLOW_HOME 指沙箱 home, STAND_JAVA_BIN 指假
#   java (记录调用+可控版本输出), 端口用 21000+ 随机空闲段; 真实监听用 C 迷你
#   监听器 (marker: 路径含 stand-assembly 标记) 与 python http.server (foreign)。
# 零真实 daemon: start 未命中路径的 exec 落在假 java 上, 不起 JVM。
#
# 用法: bash ops/test/stand-lifecycle.test.sh   (exit 0 = 全绿)
set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC_STAND="$REPO_ROOT/ops/bin/stand"
SRC_NEBFLOW="$REPO_ROOT/ops/bin/nebflow"
SRC_LIB="$REPO_ROOT/ops/bin/stand-cli-lib.sh"
SRC_JDK="$REPO_ROOT/ops/bin/stand-jdk"
SRC_AUTOSTART="$REPO_ROOT/ops/stand-autostart.sh"       # 2026-11-03 双根分家批: nebflow-autostart.sh → stand-autostart.sh
SRC_AUTOSTART_SHIM="$REPO_ROOT/ops/bin/nebflow-autostart-shim"
SRC_RESTART="$REPO_ROOT/ops/stand-restart.sh"          # 2026-11-03 改名批: nebflow-restart.sh → stand-restart.sh
SRC_RESTART_SHIM="$REPO_ROOT/ops/bin/nebflow-restart-shim"

PASS=0; FAIL=0
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/stand-lifecycle.XXXXXX")"
# 隔离实例误伤批 2026-11-04 §2：沙箱必须把「本品牌默认根」搬进沙箱，否则
# 隔离根法则（R ≠ 默认根 ⇒ 继承来的端口 env 不算显式、品牌缺省口拒绝）会把既有
# 全部用例改判。做法：HOME 指向沙箱，STAND_HOME=$SANDBOX/.stand = $HOME/.stand
# ⇒ 沙箱内是**默认根**（历史语义逐字保留）；新用例显式用 $SANDBOX/canary 之类
# 的**隔离根**验新法则。Real home 另存一份供 stand-jdk 体检面使用。
REAL_HOME="$HOME"
export HOME="$SANDBOX"
FAKE_LOG="$SANDBOX/fake-java.log"
PYTHON="$(command -v python3 || true)"
cleanup() { rm -rf "$SANDBOX" 2>/dev/null; }
trap cleanup EXIT

# ── 断言原语 ──────────────────────────────────────────────
t()    { echo "--- $*"; }
ok()   { PASS=$((PASS+1)); echo "  PASS: $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $*"; }
assert_rc()      { # <desc> <want> <got>
    if [ "$2" = "$3" ]; then ok "$1 (rc=$3)"; else bad "$1 (want rc=$2, got rc=$3)"; fi; }
assert_contains(){ # <desc> <needle> <haystack>
    case "$3" in *"$2"*) ok "$1" ;; *) bad "$1 — missing '$2' in: ${3:0:300}" ;; esac; }
assert_lacks()   { # <desc> <needle> <haystack>
    case "$3" in *"$2"*) bad "$1 — unexpectedly found '$2'" ;; *) ok "$1" ;; esac; }
assert_eq()      { # <desc> <want> <got>
    if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 — want '$2', got '$3'"; fi; }

# ── 沙箱脚手架 ────────────────────────────────────────────
fresh_sandbox() { # 每个 case 重建: bin/(stand+nebflow+库+jars) + fake java + home
    # 隐藏根必须一起清: `*` 不匹配点开头 ⇒ .stand/.nebflow 里的 .pid 记账与
    # restart-detached-*.log 会跨用例泄漏（旧日志被判成本用例的点火证据）。
    rm -rf "$SANDBOX"/* "$SANDBOX"/.[!.]* "$SANDBOX"/..?* 2>/dev/null
    mkdir -p "$SANDBOX/bin/versions/2026.10.25" "$SANDBOX/fakebin" "$SANDBOX/.stand" "$SANDBOX/.nebflow"
    : > "$SANDBOX/bin/versions/2026.10.25/stand-assembly-2026.10.25.jar"
    printf '2026.10.25\n' > "$SANDBOX/bin/current-version"
    : > "$SANDBOX/bin/nebflow-assembly-2026.9.19.jar"   # nebflow 入口 winsort 面
    cat > "$SANDBOX/fakebin/java" <<'FAKEJAVA'
#!/bin/bash
# 假 java: -version 走 FAKE_JAVA_VERSION; 真调用记进 FAKE_LOG (args + 端口 env + home env)
if [ "${FAKE_MODE:-run}" = "version" ] || [ "${1:-}" = "-version" ]; then
    echo "openjdk version \"${FAKE_JAVA_VERSION:-21.0.5}\" 2024-10-15"
    exit 0
fi
printf 'ARGS:%s\n' "$*" >> "$FAKE_LOG"
printf 'PORTS:%s|%s\n' "${STAND_GATEWAY_PORT:-}" "${NEBFLOW_GATEWAY_PORT:-}" >> "$FAKE_LOG"
printf 'HOMEVARS:%s|%s\n' "${STAND_HOME:-}" "${NEBFLOW_HOME:-}" >> "$FAKE_LOG"
exit 0
FAKEJAVA
    chmod +x "$SANDBOX/fakebin/java"
    cp "$SRC_STAND" "$SANDBOX/bin/stand"
    cp "$SRC_NEBFLOW" "$SANDBOX/bin/nebflow"
    cp "$SRC_LIB" "$SANDBOX/bin/stand-cli-lib.sh"
    chmod +x "$SANDBOX/bin/stand" "$SANDBOX/bin/nebflow"
    # 2026-11-03 改名批: 开发重启工具 (stand-restart.sh) + 旧名弃用垫片, 连同它要求的
    # 两个 helper（setsid-exec / daemonize——沙箱里只需存在且可执行, --check 面不 spawn;
    # 2026-11-03 双根分家批 helper 随归属 stand 改名 stand-*）。
    cp "$SRC_RESTART" "$SANDBOX/bin/stand-restart.sh"
    cp "$SRC_RESTART_SHIM" "$SANDBOX/bin/nebflow-restart.sh"
    printf '#!/bin/bash\nexit 0\n' > "$SANDBOX/bin/stand-setsid-exec.py"
    printf '#!/bin/bash\nexit 0\n' > "$SANDBOX/bin/stand-daemonize.py"
    chmod +x "$SANDBOX/bin/stand-restart.sh" "$SANDBOX/bin/nebflow-restart.sh" \
             "$SANDBOX/bin/stand-setsid-exec.py" "$SANDBOX/bin/stand-daemonize.py"
    # 2026-11-03 双根分家批: 开机自启工具 (stand-autostart.sh) + 旧名弃用垫片
    cp "$SRC_AUTOSTART" "$SANDBOX/bin/stand-autostart.sh"
    cp "$SRC_AUTOSTART_SHIM" "$SANDBOX/bin/nebflow-autostart.sh"
    chmod +x "$SANDBOX/bin/stand-autostart.sh" "$SANDBOX/bin/nebflow-autostart.sh"
    mkdir -p "$SANDBOX/.stand/logs"
    : > "$SANDBOX/.stand/logs/nebflow.log"
    : > "$FAKE_LOG"
}

run_stand() { # $@: stand 子命令; 返回 rc, stdout+stderr 捕获在 RUN_OUT
    RUN_OUT="$(cd "$SANDBOX" && \
        STAND_HOME="$SANDBOX/.stand" \
        STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" "$@" 2>&1)"
    return $?
}

run_stand_on() { # <port> $@: 同 run_stand, 以 STAND_GATEWAY_PORT 把端口告知被测脚本
    local _port="$1"; shift
    RUN_OUT="$(cd "$SANDBOX" && \
        STAND_HOME="$SANDBOX/.stand" \
        STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$_port" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" "$@" 2>&1)"
    return $?
}

run_nebflow() { # $@: nebflow 子命令 (品牌隔离面: 只认 NEBFLOW_* env)
    RUN_OUT="$(cd "$SANDBOX" && \
        NEBFLOW_HOME="$SANDBOX/.nebflow" \
        NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/nebflow" "$@" 2>&1)"
    return $?
}

run_nebflow_on() { # <port> $@: 同 run_nebflow, 以 NEBFLOW_GATEWAY_PORT 把端口告知被测脚本
    local _port="$1"; shift
    RUN_OUT="$(cd "$SANDBOX" && \
        NEBFLOW_HOME="$SANDBOX/.nebflow" \
        NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
        NEBFLOW_GATEWAY_PORT="$_port" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/nebflow" "$@" 2>&1)"
    return $?
}

pick_free_port() { # 21000-25999 里挑空闲口
    local _p
    while :; do
        _p=$((21000 + RANDOM % 5000))
        [ -z "$(lsof -ti tcp:"$_p" -sTCP:LISTEN 2>/dev/null)" ] && { echo "$_p"; return 0; }
    done
}

make_fixture() { # <marker|foreign> — 造假冒监听器
    # 身份假冒走「可执行文件路径含 stand-assembly 标记」: macOS ps command= 显示
    # 真实二进制路径 + argv[1:], python 一律现形; Apple 系二进制复制即被 SIP
    # 击杀 (Killed:9) —— 故 marker 侧现编一个 10 行 C 监听器 (adhoc 签名, 落在
    # 标记路径上, 自身即 socket 属主, TERM 默认处置即死)。foreign 侧用原味
    # python (无标记, 供身份门拒绝)。
    local _mode="$1" _name
    [ -n "$PYTHON" ] || return 1
    if [ "$_mode" = "marker" ]; then
        cat > "$SANDBOX/fakebin/tinylisten.c" <<'CEOF'
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc < 2) return 2;
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a; memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons((unsigned short)atoi(argv[1]));
    int one = 1; setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    if (bind(s, (struct sockaddr *)&a, sizeof a) < 0) return 3;
    if (listen(s, 8) < 0) return 4;
    while (pause() == -1 && errno == EINTR) {}
    return 0;
}
CEOF
        cc -O0 -o "$SANDBOX/fakebin/stand-assembly-fixture" "$SANDBOX/fakebin/tinylisten.c" || return 1
    else
        cat > "$SANDBOX/fakebin/plain-listener" <<FIXEOF
#!/bin/bash
exec "$PYTHON" -m http.server "\$1" --bind 127.0.0.1
FIXEOF
        chmod +x "$SANDBOX/fakebin/plain-listener"
    fi
    return 0
}

start_fixture() { # <port> <marker|foreign> [name] — 起假冒监听; echo pid (监听器本体 pid)
    local _port="$1" _mode="$2" _name="${3:-}" _pid _bin
    make_fixture "$_mode" || { echo "NO_FIXTURE"; return 1; }
    # exec 必须: 子壳只包 cd —— 没有 exec 时 $! 是等待中的子壳而非监听器,
    # pid 记账/身份门全部失配 (2026-10-27 定因: case4 假败 + fixture 孤儿泄漏的根)。
    # _name 供品牌侧假冒 (如 nebflow 侧监听器路径须含 nebflow 标记才过 nebflow 身份门)。
    if [ "$_mode" = "marker" ]; then
        _bin="$SANDBOX/fakebin/stand-assembly-fixture"
        if [ -n "$_name" ]; then
            cp "$_bin" "$SANDBOX/fakebin/$_name"
            _bin="$SANDBOX/fakebin/$_name"
        fi
        ( cd / && exec "$_bin" "$_port" ) >/dev/null 2>&1 &
    else
        ( cd / && exec "$SANDBOX/fakebin/plain-listener" "$_port" ) >/dev/null 2>&1 &
    fi
    _pid=$!
    wait_listener "$_port" 30 || { kill "$_pid" 2>/dev/null; echo "LISTEN_TIMEOUT"; return 1; }
    echo "$_pid"
}

start_marker_home() { # <port> <home> — marker 监听器, argv 带 `--home <home>`
    # 隔离实例误伤批 2026-11-04: 停机面的 home 归属门只认「命令行可证的 home」
    # (--home / --home= / -Duser.home=)。C 监听器忽略多余 argv, ps 显示 exec 后的
    # 真实命令行 = <.../stand-assembly-fixture> <port> --home <home> ⇒ 身份门(marker)
    # 与 home 门(可证)同时成立 —— 正是引擎 daemon 子进程的形状 (DaemonSpawn:109)。
    local _port="$1" _home="$2" _pid
    make_fixture marker || { echo "NO_FIXTURE"; return 1; }
    ( cd / && exec "$SANDBOX/fakebin/stand-assembly-fixture" "$_port" --home "$_home" ) >/dev/null 2>&1 &
    _pid=$!
    wait_listener "$_port" 30 || { kill "$_pid" 2>/dev/null; echo "LISTEN_TIMEOUT"; return 1; }
    echo "$_pid"
}

wait_listener() { # <port> <tries x0.1s> — 等到监听出现
    local _i=0
    while [ "$_i" -lt "$2" ]; do
        [ -n "$(lsof -ti tcp:"$1" -sTCP:LISTEN 2>/dev/null)" ] && return 0
        sleep 0.1; _i=$((_i+1))
    done
    return 1
}

wait_gone() { # <port> — 等监听消失 (≤5s)
    local _i=0
    while [ "$_i" -lt 50 ]; do
        [ -z "$(lsof -ti tcp:"$1" -sTCP:LISTEN 2>/dev/null)" ] && return 0
        sleep 0.1; _i=$((_i+1))
    done
    return 1
}

FAKE_LOG_LINES() { [ -f "$FAKE_LOG" ] && wc -l < "$FAKE_LOG" | tr -d ' ' || echo 0; }

# ══════════════════════════════════════════════════════════
echo "== stand CLI lifecycle guard tests =="
echo "   repo: $REPO_ROOT"
echo "   sandbox: $SANDBOX"

# ── 0. 静态: 语法 ──
t "case 0: bash -n on ops scripts"
for _f in "$SRC_STAND" "$SRC_NEBFLOW" "$SRC_LIB" "$SRC_JDK" "$SRC_AUTOSTART" "$SRC_AUTOSTART_SHIM" "$SRC_RESTART" "$SRC_RESTART_SHIM"; do
    if bash -n "$_f" 2>"$SANDBOX/syn.err"; then ok "syntax: $(basename "$_f")"; else bad "syntax: $(basename "$_f"): $(cat "$SANDBOX/syn.err")"; fi
done
if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -S warning "$SRC_STAND" "$SRC_NEBFLOW" "$SRC_LIB" "$SRC_AUTOSTART_SHIM" "$SRC_RESTART_SHIM" 2>/dev/null; then ok "shellcheck: entries+lib+shims"; else bad "shellcheck: entries+lib+shims"; fi
else
    echo "  (shellcheck not installed — skipped)"
fi

# ── 1. start 命中 (stand listener 在) → already running, 不进 JVM ──
t "case 1: start hit (own listener) -> 'already running' rc=0, zero JVM"
fresh_sandbox
P1=$(pick_free_port)
FIX1=$(start_fixture "$P1" marker)
if case "$FIX1" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    run_stand_on "$P1" start; RC=$?
    assert_rc "start hit rc" 0 "$RC"
    assert_contains "start hit message" "already running" "$RUN_OUT"
    assert_eq "no java exec'd" "0" "$(FAKE_LOG_LINES)"
    kill "$FIX1" 2>/dev/null
    if wait_gone "$P1"; then ok "fixture cleaned up (no orphan leak)"; else bad "fixture leaked on $P1"; fi
else
    bad "case 1 skipped: fixture failed ($FIX1)"
fi

# ── 2. start 命中但占口的是外姓进程 → 拒启 rc=1 ──
t "case 2: start hit (foreign occupant) -> refuse rc=1"
fresh_sandbox
P2=$(pick_free_port)
FIX2=$(start_fixture "$P2" foreign)
if case "$FIX2" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    run_stand_on "$P2" start; RC=$?
    assert_rc "foreign refuse rc" 1 "$RC"
    assert_contains "foreign refuse message" "non-stand" "$RUN_OUT"
    assert_lacks   "foreign refuse is not 'already running'" "already running" "$RUN_OUT"
    assert_eq "no java exec'd" "0" "$(FAKE_LOG_LINES)"
    kill "$FIX2" 2>/dev/null
    if wait_gone "$P2"; then ok "fixture cleaned up (no orphan leak)"; else bad "fixture leaked on $P2"; fi
else
    bad "case 2 skipped: fixture failed ($FIX2)"
fi

# ── 3. start 未命中 (端口空闲) → 放行进 java, 子命令与端口 env 钉一致 ──
t "case 3: start miss (free port) -> passes through to java with coherent port env"
fresh_sandbox
P3=$(pick_free_port)
run_stand_on "$P3" start; RC=$?
assert_rc "start miss rc" 0 "$RC"
assert_eq "java exec'd (ARGS+PORTS+HOMEVARS)" "3" "$(FAKE_LOG_LINES)"
assert_contains "jar wired" "-jar" "$(cat "$FAKE_LOG")"
assert_contains "start subcommand preserved" " start" "$(cat "$FAKE_LOG")"
assert_contains "port env pinned to probe value" "PORTS:$P3|$P3" "$(cat "$FAKE_LOG")"

# ── 4. stop 命中 (端口+pid 文件都在) → TERM 杀掉, 端口释放, pid 记账清 ──
t "case 4: stop hit -> TERM kills listener, port freed, stale pid files cleaned"
fresh_sandbox
P4=$(pick_free_port)
FIX4=$(start_fixture "$P4" marker)
if case "$FIX4" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIX4" > "$SANDBOX/.stand/.pid"
    echo "meta" > "$SANDBOX/.stand/.pid.meta"
    run_stand_on "$P4" stop; RC=$?
    assert_rc "stop hit rc" 0 "$RC"
    assert_contains "stop message" "stand stopped" "$RUN_OUT"
    if wait_gone "$P4"; then ok "port freed"; else bad "port still held after stop"; fi
    if kill -0 "$FIX4" 2>/dev/null; then bad "listener pid alive"; else ok "listener pid dead"; fi
    if [ -e "$SANDBOX/.stand/.pid" ]; then bad ".pid not cleaned"; else ok ".pid cleaned"; fi
    if [ -e "$SANDBOX/.stand/.pid.meta" ]; then bad ".pid.meta not cleaned"; else ok ".pid.meta cleaned"; fi
else
    bad "case 4 skipped: fixture failed ($FIX4)"
fi

# ── 5. stop 双探针 pid 腿 (端口空但 pid 文件指活进程 — 病历失配态) → 真能停 ──
t "case 5: stop pid-file leg (wedged: port free, pid alive) -> killed"
fresh_sandbox
P5=$(pick_free_port)
cat > "$SANDBOX/fakebin/stand-assembly-wedge" <<'WEDGEEOF'
#!/bin/bash
sleep 40 &
CHILD=$!
trap 'kill $CHILD 2>/dev/null; exit 0' TERM INT
wait $CHILD
WEDGEEOF
chmod +x "$SANDBOX/fakebin/stand-assembly-wedge"
( "$SANDBOX/fakebin/stand-assembly-wedge" ) >/dev/null 2>&1 &
WEDGED=$!
sleep 0.2
echo "$WEDGED" > "$SANDBOX/.stand/.pid"
run_stand_on "$P5" stop; RC=$?
assert_rc "wedged stop rc" 0 "$RC"
if kill -0 "$WEDGED" 2>/dev/null; then bad "wedged pid survived"; else ok "wedged pid killed (pid-file leg)"; fi
if [ -e "$SANDBOX/.stand/.pid" ]; then bad ".pid not cleaned"; else ok ".pid cleaned"; fi

# ── 6. stop 双空 (没进程 + 陈年死 pid 文件) → not running rc=0, 记账清 ──
t "case 6: stop miss (nothing alive, stale pid) -> 'not running' rc=0 + cleanup"
fresh_sandbox
P6=$(pick_free_port)
echo "999999" > "$SANDBOX/.stand/.pid"   # 造陈年记账
run_stand_on "$P6" stop; RC=$?
assert_rc "stop miss rc" 0 "$RC"
assert_contains "stop miss message" "not running" "$RUN_OUT"
if [ -e "$SANDBOX/.stand/.pid" ]; then bad "stale .pid not cleaned"; else ok "stale .pid cleaned"; fi

# ── 7. stop 撞外姓进程 → 身份门拒杀 rc=1, 进程存活 ──
t "case 7: stop foreign occupant -> identity gate refuses rc=1, process survives"
fresh_sandbox
P7=$(pick_free_port)
FIX7=$(start_fixture "$P7" foreign)
if case "$FIX7" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    run_stand_on "$P7" stop; RC=$?
    assert_rc "foreign refuse rc" 1 "$RC"
    assert_contains "refuse message" "non-stand" "$RUN_OUT"
    if kill -0 "$FIX7" 2>/dev/null; then ok "foreign process survived"; else bad "foreign process was killed"; fi
    kill "$FIX7" 2>/dev/null
    if wait_gone "$P7"; then ok "fixture cleaned up (no orphan leak)"; else bad "fixture leaked on $P7"; fi
else
    bad "case 7 skipped: fixture failed ($FIX7)"
fi

# ── 8. stop --force 撞外姓进程 → 跳过身份门杀之 ──
t "case 8: stop --force overrides identity gate"
fresh_sandbox
P8=$(pick_free_port)
FIX8=$(start_fixture "$P8" foreign)
if case "$FIX8" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    run_stand_on "$P8" stop --force; RC=$?
    assert_rc "force stop rc" 0 "$RC"
    assert_contains "force warning shown" "--force" "$RUN_OUT"
    if wait_gone "$P8"; then ok "foreign listener killed by --force"; else bad "port still held"; fi
else
    bad "case 8 skipped: fixture failed ($FIX8)"
fi

# ── 9. 版本门: 假 java 报 1.8 → rc=11 ──
t "case 9: java version gate (fake java 1.8) -> rc=11"
fresh_sandbox
export FAKE_JAVA_VERSION="1.8.0_392"
run_stand version; RC=$?
unset FAKE_JAVA_VERSION
assert_rc "old java rejected" 11 "$RC"

# ── 10. 非生命周期子命令透传 (version) → 无守卫拦截 ──
t "case 10: passthrough subcommand (version) -> java exec'd, no guard"
fresh_sandbox
run_stand version; RC=$?
assert_rc "passthrough rc" 0 "$RC"
assert_contains "version passed to jar" "version" "$(cat "$FAKE_LOG")"

# ── 11. start --port 覆写被守卫尊重 ──
t "case 11: start --port N -> guard probes N"
fresh_sandbox
P11=$(pick_free_port)
FIX11=$(start_fixture "$P11" marker)
if case "$FIX11" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    run_stand start --port "$P11"; RC=$?
    assert_rc "--port hit rc" 0 "$RC"
    assert_contains "--port hit message" "already running" "$RUN_OUT"
    kill "$FIX11" 2>/dev/null
    if wait_gone "$P11"; then ok "fixture cleaned up (no orphan leak)"; else bad "fixture leaked on $P11"; fi
else
    bad "case 11 skipped: fixture failed ($FIX11)"
fi

# ── 12. stand-jdk check 体检面 ──
t "case 12: stand-jdk check against real pinned JDK"
if [ -x "$REAL_HOME/.stand/jdk-21/bin/java" ]; then
    JDK_OUT="$(STAND_HOME="$REAL_HOME/.stand" bash "$SRC_JDK" check 2>&1)"; RC=$?
    assert_rc "stand-jdk check rc" 0 "$RC"
else
    echo "  (host pinned JDK absent — negative probe instead)"
    JDK_OUT="$(STAND_HOME="$SANDBOX/.stand" bash "$SRC_JDK" check 2>&1)"; RC=$?
    assert_rc "stand-jdk check rc (absent)" 1 "$RC"
fi

# The launcher must not duplicate Splash.printBanner, which owns runtime artwork.
# The silent Java mock lets these cases reject all shell art, slogans and escapes.
assert_banner_handoff() { # <label> <output> <port>
    # macOS script may echo a synthetic EOF when its own stdin is not a TTY.
    # Strip only that transport prefix; any launcher output must still fail.
    local _output="${2#$'^D\b\b'}"
    assert_eq "$1: no shell banner output" "" "$_output"
    assert_eq "$1: one Java invocation" "3" "$(FAKE_LOG_LINES)"
    assert_contains "$1: startup arguments preserved" " start --port $3" "$(< "$FAKE_LOG")"
    assert_contains "$1: port environment preserved" "PORTS:$3|$3" "$(< "$FAKE_LOG")"
}

# ── 13. Non-TTY startup is silent until the Java runtime takes over. ──
t "case 13: non-TTY start -> no shell banner, arguments reach Java"
fresh_sandbox
P13=$(pick_free_port)
run_stand_on "$P13" start --port "$P13"; RC=$?
assert_rc "non-TTY start rc" 0 "$RC"
assert_banner_handoff "non-TTY" "$RUN_OUT" "$P13"

# ── 14. A wide TTY must not enable launcher artwork. ──
t "case 14: wide TTY start -> no duplicate shell banner"
fresh_sandbox
P14=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P14" FAKE_LOG="$FAKE_LOG" COLUMNS=100 \
    TERM=xterm-256color \
    script -q "$SANDBOX/pty-wide.txt" "$SANDBOX/bin/stand" start --port "$P14" 2>&1)"
RC=$?
assert_rc "wide TTY start rc" 0 "$RC"
assert_banner_handoff "wide TTY" "$PTY_OUT" "$P14"

# ── 15. NO_COLOR belongs to the runtime, not a shell fallback banner. ──
t "case 15: NO_COLOR under TTY -> no shell banner"
fresh_sandbox
P15=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P15" FAKE_LOG="$FAKE_LOG" NO_COLOR=1 COLUMNS=100 \
    TERM=xterm-256color \
    script -q "$SANDBOX/pty-no-color.txt" "$SANDBOX/bin/stand" start --port "$P15" 2>&1)"
RC=$?
assert_rc "NO_COLOR start rc" 0 "$RC"
assert_banner_handoff "NO_COLOR" "$PTY_OUT" "$P15"

# ── 16. Narrow-terminal fallback is also rendered only by the runtime. ──
t "case 16: narrow TTY start (columns<74) -> no shell fallback banner"
fresh_sandbox
P16=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P16" FAKE_LOG="$FAKE_LOG" COLUMNS=40 \
    TERM=xterm-256color \
    script -q "$SANDBOX/pty-narrow.txt" "$SANDBOX/bin/stand" start --port "$P16" 2>&1)"
RC=$?
assert_rc "narrow TTY start rc" 0 "$RC"
assert_banner_handoff "narrow TTY" "$PTY_OUT" "$P16"

# ── 17. Runtime settings and the ignored legacy switch never enable shell art. ──
t "case 17: banner settings under CI/TTY -> no shell banner, Java handoff intact"
for _prefix in STAND NEBFLOW; do
    fresh_sandbox
    P17=$(pick_free_port)
    PTY_OUT="$(cd "$SANDBOX" && env STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P17" FAKE_LOG="$FAKE_LOG" CI=1 COLUMNS=100 \
        STAND_BANNER=ignored "${_prefix}_BANNER_IMAGE=osc" "${_prefix}_BANNER_THEME=light" \
        "${_prefix}_UI_LEVEL=3" TERM=xterm-256color \
        script -q "$SANDBOX/pty-settings.txt" "$SANDBOX/bin/stand" start --port "$P17" 2>&1)"
    RC=$?
    assert_rc "$_prefix settings start rc" 0 "$RC"
    assert_banner_handoff "$_prefix settings" "$PTY_OUT" "$P17"
done

# ── 18. nebflow 品牌面: start 未命中 → 透传 java, 只钉 NEBFLOW_* (零 STAND_* 写入) ──
t "case 18: nebflow start miss -> passthrough with NEBFLOW_* pins only (no STAND_* writes)"
fresh_sandbox
P18=$(pick_free_port)
run_nebflow_on "$P18" start; RC=$?
assert_rc "nebflow start miss rc" 0 "$RC"
assert_eq "java exec'd (ARGS+PORTS+HOMEVARS)" "3" "$(FAKE_LOG_LINES)"
assert_contains "nebflow jar wired" "nebflow-assembly-2026.9.19.jar" "$(cat "$FAKE_LOG")"
assert_contains "nebflow port pinned" "PORTS:|$P18" "$(cat "$FAKE_LOG")"
assert_contains "nebflow home pinned" "HOMEVARS:|$SANDBOX/.nebflow" "$(cat "$FAKE_LOG")"
assert_lacks "no STAND_GATEWAY_PORT write" "PORTS:$P18|" "$(cat "$FAKE_LOG")"

# ── 19. 隔离: 端口解析各只认自家 env (行为证明: 对家变量指的口上有活监听也绝不探它) ──
t "case 19: env isolation - each brand ignores the other brand's port var on stop"
fresh_sandbox
P19A=$(pick_free_port); P19B=$(pick_free_port)
# 对家变量 NEBFLOW_GATEWAY_PORT=P19B 上放一个 stand 品牌监听器; stand 自家口 P19A 空着
FIX19=$(start_fixture "$P19B" marker)
if case "$FIX19" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P19A" NEBFLOW_GATEWAY_PORT="$P19B" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" stop 2>&1)"; RC=$?
    assert_rc "stand stop ignores NEBFLOW_* var rc" 0 "$RC"
    assert_contains "stand stop probes own port only" "stand is not running" "$RUN_OUT"
    if kill -0 "$FIX19" 2>/dev/null; then ok "stand stop did not touch NEBFLOW_* port listener"; else bad "stand stop killed the other brand's listener"; fi
    # 镜像: 对家变量 STAND_GATEWAY_PORT=P19A 上放 nebflow 标记监听器; nebflow 自家口 P19B 现在也空了
    kill "$FIX19" 2>/dev/null; wait_gone "$P19B" >/dev/null
    FIX19B=$(start_fixture "$P19A" marker nebflow-fixture)
    if case "$FIX19B" in ''|*[!0-9]*) false ;; *) true ;; esac; then
        RUN_OUT="$(cd "$SANDBOX" && NEBFLOW_HOME="$SANDBOX/.nebflow" NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
            NEBFLOW_GATEWAY_PORT="$P19B" STAND_GATEWAY_PORT="$P19A" FAKE_LOG="$FAKE_LOG" \
            "$SANDBOX/bin/nebflow" stop 2>&1)"; RC=$?
        assert_rc "nebflow stop ignores STAND_* var rc" 0 "$RC"
        assert_contains "nebflow stop probes own port only" "nebflow is not running" "$RUN_OUT"
        if kill -0 "$FIX19B" 2>/dev/null; then ok "nebflow stop did not touch STAND_* port listener"; else bad "nebflow stop killed the other brand's listener"; fi
        kill "$FIX19B" 2>/dev/null; wait_gone "$P19A" >/dev/null
    else
        bad "case 19b skipped: fixture failed ($FIX19B)"
    fi
else
    bad "case 19 skipped: fixture failed ($FIX19)"
fi

# ── 20. 隔离: stop 互不波及 (双 home 双端口并存, 各停各家) ──
t "case 20: stop does not cross-kill (two homes, two ports, side by side)"
fresh_sandbox
P20A=$(pick_free_port); P20B=$(pick_free_port)
mkdir -p "$SANDBOX/.nebflow"
FIX20A=$(start_fixture "$P20A" marker)                      # stand 品牌侧监听器
FIX20B=$(start_fixture "$P20B" marker nebflow-fixture)      # nebflow 品牌侧监听器
if case "$FIX20A" in ''|*[!0-9]*) false ;; *) true ;; esac && case "$FIX20B" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIX20A" > "$SANDBOX/.stand/.pid"
    echo "$FIX20B" > "$SANDBOX/.nebflow/.pid"
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P20A" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" stop 2>&1)"; RC=$?
    assert_rc "stand stop rc" 0 "$RC"
    if wait_gone "$P20A"; then ok "stand side stopped"; else bad "stand side still held"; fi
    if kill -0 "$FIX20B" 2>/dev/null; then ok "nebflow side untouched by stand stop"; else bad "stand stop killed nebflow side"; fi
    if [ "$(cat "$SANDBOX/.nebflow/.pid" 2>/dev/null)" = "$FIX20B" ]; then ok "nebflow home .pid untouched"; else bad "nebflow home .pid disturbed"; fi
    RUN_OUT="$(cd "$SANDBOX" && NEBFLOW_HOME="$SANDBOX/.nebflow" NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
        NEBFLOW_GATEWAY_PORT="$P20B" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/nebflow" stop 2>&1)"; RC=$?
    assert_rc "nebflow stop rc" 0 "$RC"
    if wait_gone "$P20B"; then ok "nebflow side stopped by its own face"; else bad "nebflow side still held"; fi
else
    bad "case 20 skipped: fixture failed ($FIX20A / $FIX20B)"
fi

# ── 21. 身份门按品牌收窄: nebflow 标记监听器对 stand 品牌是外姓 → 拒杀 ──
t "case 21: brand-scoped identity gate - nebflow marker is foreign to stand stop"
fresh_sandbox
P21=$(pick_free_port)
FIX21=$(start_fixture "$P21" marker nebflow-fixture)
if case "$FIX21" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P21" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" stop 2>&1)"; RC=$?
    assert_rc "stand stop refuses nebflow marker rc" 1 "$RC"
    assert_contains "refuse message says non-stand" "non-stand" "$RUN_OUT"
    if kill -0 "$FIX21" 2>/dev/null; then ok "nebflow marker survived stand stop"; else bad "stand stop killed nebflow marker"; fi
    kill "$FIX21" 2>/dev/null; wait_gone "$P21" >/dev/null
else
    bad "case 21 skipped: fixture failed ($FIX21)"
fi

# ── 22. bare restart 逐字透传 (#916 jar 面语义不变, 含 run --inline --force) ──
t "case 22: bare restart passthrough (jar-side #916 face untouched)"
fresh_sandbox
run_stand restart; RC=$?
assert_rc "bare restart passthrough rc" 0 "$RC"
assert_contains "restart reached jar" " restart" "$(cat "$FAKE_LOG")"
: > "$FAKE_LOG"
run_stand restart run --inline --force; RC=$?
assert_rc "restart run --inline --force passthrough rc" 0 "$RC"
assert_contains "subcommand+flags reached jar" "restart run --inline --force" "$(cat "$FAKE_LOG")"

# ── 23. restart --dry-run 假点火 (引擎活着): ppid=1 孤儿 + 树杀不可达 + 零停零启 ──
t "case 23: restart --dry-run fake ignition (engine alive) -> orphan igniter, tree-kill unreachable, zero stop/start"
fresh_sandbox
P23=$(pick_free_port)
FIX23=$(start_fixture "$P23" marker)
if case "$FIX23" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIX23" > "$SANDBOX/.stand/.pid"
    _L23_BEFORE=$(FAKE_LOG_LINES)
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P23" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" restart --dry-run 2>&1)"; RC=$?
    assert_rc "dry-run arm rc" 0 "$RC"
    assert_contains "dry-run armed message" "DRY-RUN armed" "$RUN_OUT"
    _L23=""
    for _i in $(seq 1 80); do
        _L23="$(cat "$SANDBOX/.stand"/logs/restart-detached-*.log 2>/dev/null || true)"
        case "$_L23" in *"verdict:"*) break ;; esac
        sleep 0.1
    done
    assert_contains "igniter ppid=1 (launchd adopted)" "[self-check] ppid=1 OK" "$_L23"
    assert_contains "tree-kill unreachable verdict" "verdict: DRY-RUN OK" "$_L23"
    assert_contains "ancestor chain clean" "engine pid in igniter ancestor chain: NO" "$_L23"
    assert_contains "probe saw the live engine pid" "probe: pid $FIX23" "$_L23"
    assert_contains "probe signal permission ok" "signal-permission=ok" "$_L23"
    assert_contains "mode recorded dry-run" "mode: dry-run" "$_L23"
    if kill -0 "$FIX23" 2>/dev/null; then ok "engine untouched by fake ignition"; else bad "fake ignition killed the engine"; fi
    if wait_gone "$P23" >/dev/null 2>&1; then bad "port should still be held"; else ok "port still held (engine alive)"; fi
    assert_eq "zero java exec'd by dry-run" "$_L23_BEFORE" "$(FAKE_LOG_LINES)"
    if [ -e "$SANDBOX/.stand/.restart-detached.pid" ]; then bad "restart guard not cleaned"; else ok "restart guard cleaned"; fi
    kill "$FIX23" 2>/dev/null; wait_gone "$P23" >/dev/null
else
    bad "case 23 skipped: fixture failed ($FIX23)"
fi

# ── 24. nebflow 品牌: bare restart 拒绝 (9.19 jar 无此命令) + --dry-run 同代码路径假点火 ──
t "case 24: nebflow brand - bare restart refused, --dry-run arms nebflow igniter"
fresh_sandbox
run_nebflow restart; RC=$?
assert_rc "nebflow bare restart refused rc" 2 "$RC"
assert_contains "refusal hint names the detached face" "--detached" "$RUN_OUT"
P24=$(pick_free_port)
RUN_OUT="$(cd "$SANDBOX" && NEBFLOW_HOME="$SANDBOX/.nebflow" NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
    NEBFLOW_GATEWAY_PORT="$P24" FAKE_LOG="$FAKE_LOG" \
    "$SANDBOX/bin/nebflow" restart --dry-run 2>&1)"; RC=$?
assert_rc "nebflow dry-run arm rc" 0 "$RC"
_L24=""
for _i in $(seq 1 80); do
    # nebflow 品牌的 home 是它自己的默认根（$SANDBOX/.nebflow），点火器日志落在那里。
    _L24="$(cat "$SANDBOX/.nebflow"/logs/restart-detached-*.log 2>/dev/null || true)"
    case "$_L24" in *"verdict:"*) break ;; esac
    sleep 0.1
done
assert_contains "nebflow brand recorded" "brand: nebflow" "$_L24"
assert_contains "nebflow dry-run verdict OK" "verdict: DRY-RUN OK" "$_L24"

# ── 25. 守卫记账: 活 pid 但非点火器 = 陈年记账 → WARN 覆写放行 ──
t "case 25: stale restart guard (alive non-igniter pid) -> WARN + overwrite + proceed"
fresh_sandbox
P25=$(pick_free_port)
echo "$$" > "$SANDBOX/.stand/.restart-detached.pid"   # 套件自身 bash: 活着但不是点火器
RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P25" FAKE_LOG="$FAKE_LOG" \
    "$SANDBOX/bin/stand" restart --dry-run 2>&1)"; RC=$?
assert_rc "arm proceeds past stale guard rc" 0 "$RC"
assert_contains "stale guard warning" "stale restart guard" "$RUN_OUT"
assert_contains "arm completed anyway" "DRY-RUN armed" "$RUN_OUT"
for _i in $(seq 1 80); do
    _L25="$(cat "$SANDBOX/.stand"/logs/restart-detached-*.log 2>/dev/null || true)"
    case "$_L25" in *"verdict:"*) break ;; esac
    sleep 0.1
done
assert_contains "stale-guard dry-run verdict OK" "verdict: DRY-RUN OK" "$_L25"

# ── 26. 双生分家批 2026-10-31: 默认值解析（stand→~/.stand+6138, nebflow→~/.nebflow+6140；端口判据换代）──
t "case 26: default resolution per brand (hidden __brand-defaults probe)"
fresh_sandbox
_D26="$(cd "$SANDBOX" && env -u STAND_HOME -u NEBFLOW_HOME -u STAND_GATEWAY_PORT -u NEBFLOW_GATEWAY_PORT \
    "$SANDBOX/bin/stand" __brand-defaults 2>&1)"
assert_contains "stand default home is ~/.stand" "home=$HOME/.stand" "$_D26"
assert_contains "stand default port is 6138" "port=6138" "$_D26"
assert_contains "stand names its peer brand" "other=nebflow" "$_D26"
assert_contains "stand peer default home is ~/.nebflow" "other_home=$HOME/.nebflow" "$_D26"
assert_contains "stand peer default port is 6140" "other_port=6140" "$_D26"
_D26N="$(cd "$SANDBOX" && env -u STAND_HOME -u NEBFLOW_HOME -u STAND_GATEWAY_PORT -u NEBFLOW_GATEWAY_PORT \
    "$SANDBOX/bin/nebflow" __brand-defaults 2>&1)"
assert_contains "nebflow default home is ~/.nebflow" "home=$HOME/.nebflow" "$_D26N"
assert_contains "nebflow default port is 6140" "port=6140" "$_D26N"
assert_contains "nebflow names its peer brand" "other=stand" "$_D26N"
assert_contains "nebflow peer default port is 6138" "other_port=6138" "$_D26N"
assert_eq "declared defaults are distinct ports" "1" \
    "$([ "$(printf '%s' "$_D26" | sed -n 's/^port=//p')" != "$(printf '%s' "$_D26N" | sed -n 's/^port=//p')" ] && echo 1 || echo 0)"
assert_eq "declared defaults are distinct homes" "1" \
    "$([ "$(printf '%s' "$_D26" | sed -n 's/^home=//p')" != "$(printf '%s' "$_D26N" | sed -n 's/^home=//p')" ] && echo 1 || echo 0)"
# env 只认自家: 对家 env 不得改写本品牌读数
_D26E="$(cd "$SANDBOX" && STAND_GATEWAY_PORT=25111 NEBFLOW_GATEWAY_PORT=25112 STAND_HOME="$SANDBOX/.stand" NEBFLOW_HOME="$SANDBOX/.nebflow" \
    "$SANDBOX/bin/stand" __brand-defaults 2>&1)"
assert_contains "stand port reads own env only" "port=25111" "$_D26E"
assert_contains "stand home reads own env only" "home=$SANDBOX/.stand" "$_D26E"
_D26EN="$(cd "$SANDBOX" && STAND_GATEWAY_PORT=25111 NEBFLOW_GATEWAY_PORT=25112 STAND_HOME="$SANDBOX/.stand" NEBFLOW_HOME="$SANDBOX/.nebflow" \
    "$SANDBOX/bin/nebflow" __brand-defaults 2>&1)"
assert_contains "nebflow port reads own env only" "port=25112" "$_D26EN"
assert_contains "nebflow home reads own env only" "home=$SANDBOX/.nebflow" "$_D26EN"

# ── 27. 双实例并存批: 端口被对家品牌实例占用 → 报错点名「另一实例」──
t "case 27: start hits the OTHER brand instance -> refusal names the peer instance"
fresh_sandbox
P27=$(pick_free_port)
# 造一个 nebflow 品牌占口进程: 命令行路径落 …/.nebflow/bin/ 下（品牌路径归属判据）,
# 且不含任何 *-assembly- 标记 ⇒ stand 的身份门判它「非本品牌」, 品牌归属判据点名为 nebflow。
mkdir -p "$SANDBOX/fakebin/peer/.nebflow/bin"
FIX27=$(start_fixture "$P27" marker peer/.nebflow/bin/gateway-peer)
if case "$FIX27" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    run_stand_on "$P27" start; RC=$?
    assert_rc "peer-occupant refusal rc" 1 "$RC"
    assert_contains "refusal names the OTHER instance" "OTHER instance" "$RUN_OUT"
    assert_contains "refusal names the peer brand" "nebflow" "$RUN_OUT"
    assert_contains "refusal states coexistence intent" "coexist" "$RUN_OUT"
    assert_eq "no java exec'd on peer refusal" "0" "$(FAKE_LOG_LINES)"
    kill "$FIX27" 2>/dev/null
    if wait_gone "$P27"; then ok "fixture cleaned up (no orphan leak)"; else bad "fixture leaked on $P27"; fi
else
    bad "case 27 skipped: fixture failed ($FIX27)"
fi

# ── 28. 2026-11-03 改名批: 弃用垫片 nebflow-restart.sh → stand-restart.sh 转发 + --check 只读 ──
t "case 28: deprecation shim forwards to stand-restart.sh (verbatim args) and --check is read-only"
fresh_sandbox
RUN_OUT="$(STAND_HOME="$SANDBOX/.stand" NEBFLOW_RESTART_PORT=25891 \
    "$SANDBOX/bin/nebflow-restart.sh" --check 2>&1)"; RC=$?
assert_rc "shim --check rc" 0 "$RC"
assert_contains "shim prints deprecation line" "nebflow-restart.sh is deprecated — use stand-restart.sh" "$RUN_OUT"
assert_contains "forwarded check output reached (status line)" "watchdog=" "$RUN_OUT"
assert_contains "forwarded --check verdict present" "状态:" "$RUN_OUT"
# 参数逐字透传: 未知旗标原样到达正文脚本（正文解析器忽略它, 仍走 --check 默认面）
RUN_OUT="$(STAND_HOME="$SANDBOX/.stand" NEBFLOW_RESTART_PORT=25891 \
    "$SANDBOX/bin/nebflow-restart.sh" --check --totally-unknown-flag 2>&1)"; RC=$?
assert_rc "shim forwards unknown flags verbatim (body ignores, rc unchanged)" 0 "$RC"
# 垫片缺失正文时的报错指引（临时挪走正文再恢复）
mv "$SANDBOX/bin/stand-restart.sh" "$SANDBOX/bin/stand-restart.sh.hidden"
RUN_OUT="$(STAND_HOME="$SANDBOX/.stand" NEBFLOW_RESTART_PORT=25891 \
    "$SANDBOX/bin/nebflow-restart.sh" --check 2>&1)"; RC=$?
assert_rc "shim without body rc" 66 "$RC"
assert_contains "shim missing-body guidance" "shim cannot forward" "$RUN_OUT"
mv "$SANDBOX/bin/stand-restart.sh.hidden" "$SANDBOX/bin/stand-restart.sh"

# ── 29. 2026-11-03 改名批: `stand restart --dry-run` 与 `stand-restart.sh --check` 零真实终止 ──
t "case 29: zero real termination - dry-run fake ignition + restart-script --check both leave the live engine untouched"
fresh_sandbox
P29=$(pick_free_port)
FIX29=$(start_fixture "$P29" marker)
if case "$FIX29" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIX29" > "$SANDBOX/.stand/.pid"
    # 腿1: stand-restart.sh --check (沙箱 home + 沙箱端口 ⇒ 四信号全绿, 无任何信号发送)
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" NEBFLOW_RESTART_PORT="$P29" \
        "$SANDBOX/bin/stand-restart.sh" --check 2>&1)"; RC=$?
    assert_rc "restart-script --check rc" 0 "$RC"
    assert_contains "check face reads four signals" "状态:" "$RUN_OUT"
    if kill -0 "$FIX29" 2>/dev/null; then ok "--check did not terminate the live engine"; else bad "--check killed the live engine"; fi
    if [ ! -e "$SANDBOX/.stand/logs/watchdog-paused" ]; then ok "--check leaves no watchdog pause flag"; else bad "--check created watchdog pause flag"; fi
    # 腿2: stand restart --dry-run (假点火, 与 case 23 同语义 — 此处只断「进程零终止」)
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P29" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" restart --dry-run 2>&1)"; RC=$?
    assert_rc "dry-run arm rc" 0 "$RC"
    assert_contains "dry-run armed message" "DRY-RUN armed" "$RUN_OUT"
    _L29=""
    for _i in $(seq 1 80); do
        _L29="$(cat "$SANDBOX/.stand"/logs/restart-detached-*.log 2>/dev/null || true)"
        case "$_L29" in *"verdict:"*) break ;; esac
        sleep 0.1
    done
    assert_contains "dry-run verdict OK" "verdict: DRY-RUN OK" "$_L29"
    if kill -0 "$FIX29" 2>/dev/null; then ok "--dry-run did not terminate the live engine"; else bad "--dry-run killed the live engine"; fi
    assert_eq "zero java exec'd across both legs" "0" "$(FAKE_LOG_LINES)"
    kill "$FIX29" 2>/dev/null; wait_gone "$P29" >/dev/null
else
    bad "case 29 skipped: fixture failed ($FIX29)"
fi

# ── 30. 2026-11-03 作者裁定批: restart 帮助三面口径（用户面/自救面/开发面; 用户面无 sbt/canary 字样）──
t "case 30: restart --help renders three-face help; user face free of dev-tool words"
fresh_sandbox
run_stand restart --help; RC=$?
assert_rc "stand restart --help rc" 0 "$RC"
assert_contains "help names user face" "用户面" "$RUN_OUT"
assert_contains "help names rescue face" "自救面" "$RUN_OUT"
assert_contains "help names dev face" "开发面" "$RUN_OUT"
assert_contains "help points at stand-restart.sh" "stand-restart.sh" "$RUN_OUT"
assert_contains "help mentions rollback on update" "回滚" "$RUN_OUT"
_U30="$(printf '%s\n' "$RUN_OUT" | awk '/── 用户面/,/── 自救面/')"
assert_lacks "user face has no sbt" "sbt" "$_U30"
assert_lacks "user face has no canary" "canary" "$_U30"
assert_eq "help execs no java" "0" "$(FAKE_LOG_LINES)"
run_nebflow restart --help; RC=$?
assert_rc "nebflow restart --help rc" 0 "$RC"
assert_contains "nebflow help flags jar lacks restart" "没有 restart 命令" "$RUN_OUT"
assert_contains "nebflow help still shows three faces" "自救面" "$RUN_OUT"
# --help 拦截不破坏既有透传面: 其余旗标照旧进 jar
: > "$FAKE_LOG"
run_stand restart run --inline --force; RC=$?
assert_rc "restart run --inline --force still passthrough rc" 0 "$RC"
assert_contains "passthrough intact after help arm" "restart run --inline --force" "$(cat "$FAKE_LOG")"
# --jar-apply 显式具名面: 与 bare 同落 jar; 与 --detached/--dry-run 互斥
: > "$FAKE_LOG"
run_stand restart --jar-apply; RC=$?
assert_rc "jar-apply passthrough rc" 0 "$RC"
assert_contains "jar-apply reached jar" "restart" "$(cat "$FAKE_LOG")"
# bare 与 --jar-apply 的 jar argv 必须逐字等价（empty-array 展开在 bash 3.2 + set -u 下不炸；
# `"${_fwd[@]}"` 而非 `"${_fwd[@]:-}"` —— 这条断言把该等价性钉住）
_BARE30="$SANDBOX/jar-argv-bare.txt"; _JAR30="$SANDBOX/jar-argv-jarapply.txt"
: > "$FAKE_LOG"; run_stand restart >/dev/null 2>&1; grep '^ARGS:' "$FAKE_LOG" > "$_BARE30"
: > "$FAKE_LOG"; run_stand restart --jar-apply >/dev/null 2>&1; grep '^ARGS:' "$FAKE_LOG" > "$_JAR30"
assert_eq "bare and --jar-apply send identical jar argv" "$(cat "$_BARE30")" "$(cat "$_JAR30")"
assert_contains "bare argv really reached the jar" "restart" "$(cat "$_BARE30")"
run_stand restart --jar-apply --dry-run; RC=$?
assert_rc "jar-apply + dry-run mutual exclusion rc" 2 "$RC"
assert_contains "mutual exclusion message" "互斥" "$RUN_OUT"

# ── 31. 2026-11-03 双根分家批: 自启弃用垫片 nebflow-autostart.sh → stand-autostart.sh 转发 ──
t "case 31: autostart deprecation shim forwards to stand-autostart.sh (verbatim args)"
fresh_sandbox
# 转发面验证用替身正文（真正文会 exec sbt——测试绝不真起实例）; 语义同 case 28 手法。
printf '#!/bin/bash\nprintf "%%s\\n" "$*" > "%s/body-got.txt"\n' "$SANDBOX" > "$SANDBOX/bin/stand-autostart.sh"
chmod +x "$SANDBOX/bin/stand-autostart.sh"
RUN_OUT="$(HOME="$SANDBOX/.stand" "$SANDBOX/bin/nebflow-autostart.sh" --some-flag val 2>&1)"; RC=$?
assert_rc "autostart shim forward rc" 0 "$RC"
assert_contains "autostart shim prints deprecation line" "nebflow-autostart.sh is deprecated — use stand-autostart.sh" "$RUN_OUT"
assert_eq "autostart shim forwards args verbatim" "--some-flag val" "$(cat "$SANDBOX/body-got.txt")"
# 正文缺失时的报错指引（挪走正文再恢复）
mv "$SANDBOX/bin/stand-autostart.sh" "$SANDBOX/bin/stand-autostart.sh.hidden"
RUN_OUT="$(HOME="$SANDBOX/.stand" "$SANDBOX/bin/nebflow-autostart.sh" 2>&1)"; RC=$?
assert_rc "autostart shim without body rc" 66 "$RC"
assert_contains "autostart shim missing-body guidance" "shim cannot forward" "$RUN_OUT"
mv "$SANDBOX/bin/stand-autostart.sh.hidden" "$SANDBOX/bin/stand-autostart.sh"
# 真正文引用面静态对账: exec 代码行只准用新名 helper（stand-setsid-exec.py），
# 不准残留旧名的路径形态引用（`bin/nebflow-*`；注释里的改名纪年不算代码引用）
if grep -q 'exec python3 .*stand-setsid-exec\.py' "$SRC_AUTOSTART" \
   && ! grep -q 'bin/nebflow-setsid-exec\|bin/nebflow-daemonize' "$SRC_AUTOSTART"; then
    ok "autostart body execs stand-* helper (no old-name path refs)"
else
    bad "autostart body helper wiring wrong (old-name path refs or missing new exec line)"
fi

# ══════════════════════════════════════════════════════════
# 隔离实例误伤批 2026-11-04 —— spec A / B / C 的 shell 面（红→绿机械验收）
# 现场事实（已核查，勿再考古）:
#   canary  pid 68701  home /tmp/seal-canary-home  端口 6411（端口是真隔离的）
#   生产    pid 44339  home ~/.stand               端口 6138
#   死亡命令可见前缀: `cat /tmp/seal-canary-home/.pid; echo;
#     STAND_HOME=/tmp/seal-canary-home …/bin/stand`（截断在动词前，无 --port）
#   ⇒ 一次 STAND_HOME=/tmp/seal-canary-home 的 stand CLI 调用停掉的是 home=~/.stand/
#     端口=6138 的 44339，而不是 home=/tmp/seal-canary-home 的 68701 —— 目标集合只按
#     端口/品牌认实例、从不看 home。主因即此；端口解析链的 6138 兜底是同一缺口的另
#     一半（防下一次的硬化），不是本事故主因。
# 沙箱约定: HOME=$SANDBOX ⇒ $SANDBOX/.stand 是**默认根**（生产形态），
#           $SANDBOX/canary 是**隔离根**（canary 形态）。
# ══════════════════════════════════════════════════════════

# ── 32. spec A: 隔离根里没有实例 → not running rc=0，且"生产形态"监听器零信号 ──
t "spec A: isolated root without a live instance → not running rc=0, zero kill on the other root's instance"
fresh_sandbox
PA=$(pick_free_port); PPROD=$(pick_free_port)
mkdir -p "$SANDBOX/canary"
# 判据前置（零探针，只读解析面）: 隔离根必须解析到**它自己**声明的端口，绝不静默
# 变成品牌缺省 6138 —— 事故里那句 `STAND_HOME=/tmp/seal-canary-home … stand` 就是
# 在没有 --port 的情况下解析出了 6138。本断言不碰任何 socket（__brand-defaults 只
# 求解端口链），因此在 HEAD 实现上也能安全跑出红证据（在 HEAD 上会打印 6138）。
printf '{"port": 21098}\n' > "$SANDBOX/canary/nebflow.json"
_DA="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/canary" "$SANDBOX/bin/stand" __brand-defaults 2>&1)"
assert_contains "spec A isolated root resolves its OWN declared port" "port=21098" "$_DA"
assert_lacks "spec A isolated root never silently becomes 6138" "port=6138" "$_DA"
PA=21098
rm -f "$SANDBOX/canary/nebflow.json"
FIXPROD=$(start_marker_home "$PPROD" "$SANDBOX/.stand")   # 生产形态: home=$SANDBOX/.stand
if case "$FIXPROD" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/canary" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" "$SANDBOX/bin/stand" stop --port "$PA" 2>&1)"; RC=$?
    assert_rc "spec A not-running rc" 0 "$RC"
    assert_contains "spec A not-running message" "not running" "$RUN_OUT"
    assert_lacks "spec A never mentions the other root's port" "$PPROD" "$RUN_OUT"
    if kill -0 "$FIXPROD" 2>/dev/null; then ok "spec A: production-shaped instance untouched (zero signal)"; else bad "spec A: production-shaped instance was killed"; fi
    if wait_listener "$PPROD" 5; then ok "spec A: production-shaped port still listening"; else bad "spec A: production-shaped port lost"; fi
    kill "$FIXPROD" 2>/dev/null; wait_gone "$PPROD" >/dev/null
else
    bad "spec A skipped: fixture failed ($FIXPROD)"
fi

# ── 32b. spec A 反向面: 隔离根无端口声明且无实例 → "not running" rc=0（绝不回落 6138）──
t "spec A reverse: undeclared isolated root with no live instance → not running rc=0, never 6138"
fresh_sandbox
PDECL=$(pick_free_port)
mkdir -p "$SANDBOX/canary"
# 零真实探针纪律: 本用例把 lsof 换成一个**只记录、永远说没监听**的空壳。这样即使实现
# 回落到品牌缺省口 6138（HEAD 的行为），也绝不会真的去探/碰生产实例 —— 它只会在
# LSOF_LOG 里留下一次「查过 6138」的痕迹，那就是本用例的红证据。
LSOF_LOG="$SANDBOX/lsof-queries.log"; : > "$LSOF_LOG"
mkdir -p "$SANDBOX/fakebin-nolsof"
cat > "$SANDBOX/fakebin-nolsof/lsof" <<'FAKELSOF'
#!/bin/bash
printf '%s\n' "$*" >> "$LSOF_LOG"
exit 0
FAKELSOF
chmod +x "$SANDBOX/fakebin-nolsof/lsof"
FIXDECL=$(start_marker_home "$PDECL" "$SANDBOX/.stand")     # "生产形态"监听器: 本根的邻居
if case "$FIXDECL" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    RUN_OUT="$(cd "$SANDBOX" && PATH="$SANDBOX/fakebin-nolsof:$PATH" LSOF_LOG="$LSOF_LOG" \
        STAND_HOME="$SANDBOX/canary" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" "$SANDBOX/bin/stand" stop 2>&1)"; RC=$?
    assert_rc "spec A reverse: not-running rc" 0 "$RC"
    assert_contains "spec A reverse: not-running message" "not running" "$RUN_OUT"
    assert_lacks "spec A reverse: never probes the brand default 6138" "6138" "$(cat "$LSOF_LOG")"
    assert_lacks "spec A reverse: never names 6138 in its output" "6138" "$RUN_OUT"
    if kill -0 "$FIXDECL" 2>/dev/null; then ok "spec A reverse: neighbouring instance untouched (zero signal)"; else bad "spec A reverse: neighbouring instance was killed"; fi
    kill "$FIXDECL" 2>/dev/null; wait_gone "$PDECL" >/dev/null
else
    bad "spec A reverse skipped: fixture failed ($FIXDECL)"
fi

# ── 33. spec B: 目标实例 home ≠ 调用 home → 拒绝 + 点名两个 home + 零信号 ──
t "spec B: target home differs from caller home → refuse naming both homes, zero signal"
fresh_sandbox
PB=$(pick_free_port)
mkdir -p "$SANDBOX/canary"
FIXB=$(start_marker_home "$PB" "$SANDBOX/.stand")         # 目标: home=$SANDBOX/.stand
if case "$FIXB" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIXB" > "$SANDBOX/.stand/.pid"                 # 生产形态自己那本账
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/canary" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" "$SANDBOX/bin/stand" stop --port "$PB" 2>&1)"; RC=$?
    assert_rc "spec B refusal rc" 1 "$RC"
    assert_contains "spec B names the target home" "$SANDBOX/.stand" "$RUN_OUT"
    assert_contains "spec B names the caller home" "$SANDBOX/canary" "$RUN_OUT"
    assert_contains "spec B declares zero signal" "zero signal" "$RUN_OUT"
    assert_lacks "spec B is not a stop" "stand stopped" "$RUN_OUT"
    if kill -0 "$FIXB" 2>/dev/null; then ok "spec B: target survived (zero signal)"; else bad "spec B: target was killed"; fi
    if wait_listener "$PB" 5; then ok "spec B: target port still listening"; else bad "spec B: target port lost"; fi
    kill "$FIXB" 2>/dev/null; wait_gone "$PB" >/dev/null
    # 反向对照: 同一个目标, 从**它自己的 home** 发起的 stop 必须放行（不是「谁都不许停」）
    FIXB2=$(start_marker_home "$PB" "$SANDBOX/.stand")
    if case "$FIXB2" in ''|*[!0-9]*) false ;; *) true ;; esac; then
        RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
            FAKE_LOG="$FAKE_LOG" "$SANDBOX/bin/stand" stop --port "$PB" 2>&1)"; RC=$?
        assert_rc "spec B reverse: home-matched stop rc" 0 "$RC"
        if wait_gone "$PB"; then ok "spec B reverse: home-matched stop really stopped it"; else bad "spec B reverse: port still held"; fi
    else
        bad "spec B reverse skipped: fixture failed ($FIXB2)"
    fi
else
    bad "spec B skipped: fixture failed ($FIXB)"
fi

# ── 34. spec C: canary 启动（隔离根、未显式给端口）→ 不占生产默认口 6138 ──
t "spec C: isolated-root start takes its own config port (never 6138); no declaration → hard refusal, zero JVM"
fresh_sandbox
mkdir -p "$SANDBOX/canary"
printf '{"port": 21099}\n' > "$SANDBOX/canary/nebflow.json"
PC_OWN=21099
: > "$FAKE_LOG"
# 继承来的 STAND_GATEWAY_PORT=6138 是父 shell 给生产实例的那一个: 隔离根必须忽略它并 WARN。
RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/canary" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT=6138 FAKE_LOG="$FAKE_LOG" "$SANDBOX/bin/stand" start 2>&1)"; RC=$?
assert_rc "spec C start rc" 0 "$RC"
assert_contains "spec C pins the root's own config port" "PORTS:$PC_OWN|$PC_OWN" "$(cat "$FAKE_LOG")"
assert_lacks "spec C never pins 6138" "PORTS:6138|6138" "$(cat "$FAKE_LOG")"
assert_contains "spec C WARN names the ignored inherited env" "ignoring inherited STAND_GATEWAY_PORT=6138" "$RUN_OUT"
# 无声明（无 --port、无 config）⇒ 硬拒 rc=3, 绝不回落品牌缺省口, 零 JVM
: > "$FAKE_LOG"
rm -f "$SANDBOX/canary/nebflow.json"
RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/canary" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    FAKE_LOG="$FAKE_LOG" "$SANDBOX/bin/stand" start 2>&1)"; RC=$?
assert_rc "spec C undeclared port refusal rc" 3 "$RC"
assert_contains "spec C refusal names the brand default it refuses" "6138" "$RUN_OUT"
assert_contains "spec C refusal asks for --port" "--port" "$RUN_OUT"
assert_eq "spec C refusal exec'd no JVM" "0" "$(FAKE_LOG_LINES)"
# 默认根（生产形态）行为逐字不变: 无 config 时端口解析仍是品牌缺省 6138。
# 只断言**解析结果**（__brand-defaults 零探针、零 JVM）——绝不对真 6138 跑 start 探针。
_DC="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/.stand" STATUS_HIDE=1 "$SANDBOX/bin/stand" __brand-defaults 2>&1)"
assert_contains "spec C default root keeps the brand default 6138" "port=6138" "$_DC"
: > "$FAKE_LOG"

echo
echo "== summary: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
