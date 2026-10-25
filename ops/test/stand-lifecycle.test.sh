#!/bin/bash
# stand CLI 生命周期守卫 — 纯 bash 断言测试 (无 bats 依赖)
# 被测对象: <repo>/ops/bin/stand (LIFECYCLE-GUARD v1) + ops/bin/stand-jdk (体检面)
#
# 方法: 沙箱化 —— 每个用例把被测脚本拷进临时 bin/(伪造 jar + current-version),
#   STAND_HOME 指沙箱 home, STAND_JAVA_BIN 指假 java (记录调用+可控版本输出),
#   端口用 21000+ 随机空闲段; 真实监听用 python http.server, stand 身份用
#   「可执行路径含 stand-assembly 标记」假冒 (macOS ps 对 .app 二进制不认假
#   argv[0], 对脚本路径认原样 —— 守卫身份门匹配的正是 ps 串)。
# 零真实 daemon: start 未命中路径的 exec 落在假 java 上, 不起 JVM。
#
# 用法: bash ops/test/stand-lifecycle.test.sh   (exit 0 = 全绿)
set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC_STAND="$REPO_ROOT/ops/bin/stand"
SRC_JDK="$REPO_ROOT/ops/bin/stand-jdk"
SRC_AUTOSTART="$REPO_ROOT/ops/nebflow-autostart.sh"
SRC_RESTART="$REPO_ROOT/ops/nebflow-restart.sh"

PASS=0; FAIL=0
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/stand-lifecycle.XXXXXX")"
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
fresh_sandbox() { # 每个 case 重建: bin/(stand+jars) + fake java + home
    rm -rf "$SANDBOX"/*
    mkdir -p "$SANDBOX/bin/versions/2026.10.25" "$SANDBOX/fakebin" "$SANDBOX/home"
    : > "$SANDBOX/bin/versions/2026.10.25/stand-assembly-2026.10.25.jar"
    printf '2026.10.25\n' > "$SANDBOX/bin/current-version"
    cat > "$SANDBOX/fakebin/java" <<'FAKEJAVA'
#!/bin/bash
# 假 java: -version 走 FAKE_JAVA_VERSION; 真调用记进 FAKE_LOG (args + 端口 env)
if [ "${FAKE_MODE:-run}" = "version" ] || [ "${1:-}" = "-version" ]; then
    echo "openjdk version \"${FAKE_JAVA_VERSION:-21.0.5}\" 2024-10-15"
    exit 0
fi
printf 'ARGS:%s\n' "$*" >> "$FAKE_LOG"
printf 'PORTS:%s|%s\n' "${STAND_GATEWAY_PORT:-}" "${NEBFLOW_GATEWAY_PORT:-}" >> "$FAKE_LOG"
exit 0
FAKEJAVA
    chmod +x "$SANDBOX/fakebin/java"
    cp "$SRC_STAND" "$SANDBOX/bin/stand"
    chmod +x "$SANDBOX/bin/stand"
    : > "$FAKE_LOG"
}

run_stand() { # $@: stand 子命令; 返回 rc, stdout+stderr 捕获在 RUN_OUT
    RUN_OUT="$(cd "$SANDBOX" && \
        STAND_HOME="$SANDBOX/home" \
        STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" "$@" 2>&1)"
    return $?
}

run_stand_on() { # <port> $@: 同 run_stand, 以 STAND_GATEWAY_PORT 把端口告知被测脚本
    local _port="$1"; shift
    RUN_OUT="$(cd "$SANDBOX" && \
        STAND_HOME="$SANDBOX/home" \
        STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$_port" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" "$@" 2>&1)"
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

start_fixture() { # <port> <marker|foreign> — 起假冒监听; echo pid
    local _port="$1" _mode="$2" _pid
    make_fixture "$_mode" || { echo "NO_FIXTURE"; return 1; }
    if [ "$_mode" = "marker" ]; then
        ( cd / && "$SANDBOX/fakebin/stand-assembly-fixture" "$_port" ) >/dev/null 2>&1 &
    else
        ( cd / && "$SANDBOX/fakebin/plain-listener" "$_port" ) >/dev/null 2>&1 &
    fi
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
for _f in "$SRC_STAND" "$SRC_JDK" "$SRC_AUTOSTART" "$SRC_RESTART"; do
    if bash -n "$_f" 2>"$SANDBOX/syn.err"; then ok "syntax: $(basename "$_f")"; else bad "syntax: $(basename "$_f"): $(cat "$SANDBOX/syn.err")"; fi
done
if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -S warning "$SRC_STAND" 2>/dev/null; then ok "shellcheck: stand"; else bad "shellcheck: stand"; fi
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
    kill "$FIX1" 2>/dev/null; wait_gone "$P1" >/dev/null
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
    kill "$FIX2" 2>/dev/null; wait_gone "$P2" >/dev/null
else
    bad "case 2 skipped: fixture failed ($FIX2)"
fi

# ── 3. start 未命中 (端口空闲) → 放行进 java, 子命令与端口 env 钉一致 ──
t "case 3: start miss (free port) -> passes through to java with coherent port env"
fresh_sandbox
P3=$(pick_free_port)
run_stand_on "$P3" start; RC=$?
assert_rc "start miss rc" 0 "$RC"
assert_eq "java exec'd (ARGS+PORTS)" "2" "$(FAKE_LOG_LINES)"
assert_contains "jar wired" "-jar" "$(cat "$FAKE_LOG")"
assert_contains "start subcommand preserved" " start" "$(cat "$FAKE_LOG")"
assert_contains "port env pinned to probe value" "PORTS:$P3|$P3" "$(cat "$FAKE_LOG")"

# ── 4. stop 命中 (端口+pid 文件都在) → TERM 杀掉, 端口释放, pid 记账清 ──
t "case 4: stop hit -> TERM kills listener, port freed, stale pid files cleaned"
fresh_sandbox
P4=$(pick_free_port)
FIX4=$(start_fixture "$P4" marker)
if case "$FIX4" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIX4" > "$SANDBOX/home/.pid"
    echo "meta" > "$SANDBOX/home/.pid.meta"
    run_stand_on "$P4" stop; RC=$?
    assert_rc "stop hit rc" 0 "$RC"
    assert_contains "stop message" "stand stopped" "$RUN_OUT"
    if wait_gone "$P4"; then ok "port freed"; else bad "port still held after stop"; fi
    if kill -0 "$FIX4" 2>/dev/null; then bad "listener pid alive"; else ok "listener pid dead"; fi
    if [ -e "$SANDBOX/home/.pid" ]; then bad ".pid not cleaned"; else ok ".pid cleaned"; fi
    if [ -e "$SANDBOX/home/.pid.meta" ]; then bad ".pid.meta not cleaned"; else ok ".pid.meta cleaned"; fi
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
echo "$WEDGED" > "$SANDBOX/home/.pid"
run_stand_on "$P5" stop; RC=$?
assert_rc "wedged stop rc" 0 "$RC"
if kill -0 "$WEDGED" 2>/dev/null; then bad "wedged pid survived"; else ok "wedged pid killed (pid-file leg)"; fi
if [ -e "$SANDBOX/home/.pid" ]; then bad ".pid not cleaned"; else ok ".pid cleaned"; fi

# ── 6. stop 双空 (没进程 + 陈年死 pid 文件) → not running rc=0, 记账清 ──
t "case 6: stop miss (nothing alive, stale pid) -> 'not running' rc=0 + cleanup"
fresh_sandbox
P6=$(pick_free_port)
echo "999999" > "$SANDBOX/home/.pid"   # 造陈年记账
run_stand_on "$P6" stop; RC=$?
assert_rc "stop miss rc" 0 "$RC"
assert_contains "stop miss message" "not running" "$RUN_OUT"
if [ -e "$SANDBOX/home/.pid" ]; then bad "stale .pid not cleaned"; else ok "stale .pid cleaned"; fi

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
    kill "$FIX7" 2>/dev/null; wait_gone "$P7" >/dev/null
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
    kill "$FIX11" 2>/dev/null; wait_gone "$P11" >/dev/null
else
    bad "case 11 skipped: fixture failed ($FIX11)"
fi

# ── 12. stand-jdk check 体检面 ──
t "case 12: stand-jdk check against real pinned JDK"
if [ -x "$HOME/.stand/jdk-21/bin/java" ]; then
    JDK_OUT="$(STAND_HOME="$HOME/.stand" bash "$SRC_JDK" check 2>&1)"; RC=$?
    assert_rc "stand-jdk check rc" 0 "$RC"
else
    echo "  (host pinned JDK absent — negative probe instead)"
    JDK_OUT="$(STAND_HOME="$SANDBOX/home" bash "$SRC_JDK" check 2>&1)"; RC=$?
    assert_rc "stand-jdk check rc (absent)" 1 "$RC"
fi

# ── 13. 横幅降级: 非 TTY → 单行纯文本, 零艺术字零 ANSI ──
t "case 13: banner non-TTY -> single plain line, no art no ANSI"
fresh_sandbox
P13=$(pick_free_port)
run_stand_on "$P13" start; RC=$?
assert_rc "non-tty start rc" 0 "$RC"
assert_contains "plain slogan line" "stand — One entry, Every agent." "$RUN_OUT"
assert_lacks "no block art in non-TTY" "█" "$RUN_OUT"
assert_lacks "no ANSI escape in non-TTY" "$(printf '\033[')" "$RUN_OUT"

# ── 14. 横幅徽标款: pty(TTY) + 宽终端 → 候选 A 艺术字 ──
t "case 14: banner TTY wide (pty, cols>=80) -> candidate A art"
fresh_sandbox
P14=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P14" FAKE_LOG="$FAKE_LOG" STAND_BANNER_COLS=100 \
    script -q "$SANDBOX/pty-a.txt" "$SANDBOX/bin/stand" start 2>&1)"
RC=$?
assert_rc "pty wide start rc" 0 "$RC"
assert_contains "candidate A slogan" "One entry, Every agent." "$(cat "$SANDBOX/pty-a.txt")"
assert_contains "candidate A block art" "███████" "$(cat "$SANDBOX/pty-a.txt")"

# ── 15. NO_COLOR 恒胜 TTY → 单行纯文本 ──
t "case 15: banner NO_COLOR under pty -> plain single line"
fresh_sandbox
P15=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P15" FAKE_LOG="$FAKE_LOG" NO_COLOR=1 STAND_BANNER_COLS=100 \
    script -q "$SANDBOX/pty-b.txt" "$SANDBOX/bin/stand" start 2>&1)"
RC=$?
assert_rc "NO_COLOR start rc" 0 "$RC"
assert_contains "NO_COLOR plain slogan" "stand — One entry, Every agent." "$(cat "$SANDBOX/pty-b.txt")"
assert_lacks "NO_COLOR kills art" "███████" "$(cat "$SANDBOX/pty-b.txt")"

# ── 16. 窄终端 (cols<80) → 候选 B 双行款 ──
t "case 16: banner narrow (cols<80) -> candidate B two-line"
fresh_sandbox
P16=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P16" FAKE_LOG="$FAKE_LOG" STAND_BANNER_COLS=40 \
    script -q "$SANDBOX/pty-c.txt" "$SANDBOX/bin/stand" start 2>&1)"
RC=$?
assert_rc "narrow start rc" 0 "$RC"
assert_contains "candidate B slogan part 1" "One entry," "$(cat "$SANDBOX/pty-c.txt")"
assert_contains "candidate B slogan part 2" "Every agent." "$(cat "$SANDBOX/pty-c.txt")"
assert_lacks "narrow has no wide top bar" "███████" "$(cat "$SANDBOX/pty-c.txt")"

# ── 17. STAND_BANNER=C 强制单行款 (宽终端也不出艺术字) ──
t "case 17: STAND_BANNER=C override -> single-line wordmark"
fresh_sandbox
P17=$(pick_free_port)
PTY_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P17" FAKE_LOG="$FAKE_LOG" STAND_BANNER=C STAND_BANNER_COLS=100 \
    script -q "$SANDBOX/pty-d.txt" "$SANDBOX/bin/stand" start 2>&1)"
RC=$?
assert_rc "banner C start rc" 0 "$RC"
assert_contains "candidate C slogan" "One entry, Every agent." "$(cat "$SANDBOX/pty-d.txt")"
assert_lacks "candidate C has no block art" "█" "$(cat "$SANDBOX/pty-d.txt")"

echo
echo "== summary: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
