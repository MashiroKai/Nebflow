#!/bin/bash
# stand/nebflow CLI 生命周期守卫 — 纯 bash 断言测试 (无 bats 依赖)
# 被测对象: <repo>/ops/bin/{stand,nebflow,stand-cli-lib.sh,nebflow-stand-shim}
#           (stand + nebflow 两入口的共享实现库) + ops/bin/stand-jdk (体检面)
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
SRC_SHIM="$REPO_ROOT/ops/bin/nebflow-stand-shim"
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
fresh_sandbox() { # 每个 case 重建: bin/(stand+nebflow+库+jars) + fake java + home
    rm -rf "$SANDBOX"/*
    mkdir -p "$SANDBOX/bin/versions/2026.10.25" "$SANDBOX/fakebin" "$SANDBOX/home"
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

run_nebflow() { # $@: nebflow 子命令 (品牌隔离面: 只认 NEBFLOW_* env)
    RUN_OUT="$(cd "$SANDBOX" && \
        NEBFLOW_HOME="$SANDBOX/home" \
        NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
        FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/nebflow" "$@" 2>&1)"
    return $?
}

run_nebflow_on() { # <port> $@: 同 run_nebflow, 以 NEBFLOW_GATEWAY_PORT 把端口告知被测脚本
    local _port="$1"; shift
    RUN_OUT="$(cd "$SANDBOX" && \
        NEBFLOW_HOME="$SANDBOX/home" \
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
for _f in "$SRC_STAND" "$SRC_NEBFLOW" "$SRC_LIB" "$SRC_SHIM" "$SRC_JDK" "$SRC_AUTOSTART" "$SRC_RESTART"; do
    if bash -n "$_f" 2>"$SANDBOX/syn.err"; then ok "syntax: $(basename "$_f")"; else bad "syntax: $(basename "$_f"): $(cat "$SANDBOX/syn.err")"; fi
done
if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -S warning "$SRC_STAND" "$SRC_NEBFLOW" "$SRC_LIB" "$SRC_SHIM" 2>/dev/null; then ok "shellcheck: entries+lib+shim"; else bad "shellcheck: entries+lib+shim"; fi
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
    TERM=xterm-256color \
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
    TERM=xterm-256color \
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
    TERM=xterm-256color \
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
    TERM=xterm-256color \
    script -q "$SANDBOX/pty-d.txt" "$SANDBOX/bin/stand" start 2>&1)"
RC=$?
assert_rc "banner C start rc" 0 "$RC"
assert_contains "candidate C slogan" "One entry, Every agent." "$(cat "$SANDBOX/pty-d.txt")"
assert_lacks "candidate C has no block art" "█" "$(cat "$SANDBOX/pty-d.txt")"

# ── 18. nebflow 品牌面: start 未命中 → 透传 java, 只钉 NEBFLOW_* (零 STAND_* 写入) ──
t "case 18: nebflow start miss -> passthrough with NEBFLOW_* pins only (no STAND_* writes)"
fresh_sandbox
P18=$(pick_free_port)
run_nebflow_on "$P18" start; RC=$?
assert_rc "nebflow start miss rc" 0 "$RC"
assert_eq "java exec'd (ARGS+PORTS+HOMEVARS)" "3" "$(FAKE_LOG_LINES)"
assert_contains "nebflow jar wired" "nebflow-assembly-2026.9.19.jar" "$(cat "$FAKE_LOG")"
assert_contains "nebflow port pinned" "PORTS:|$P18" "$(cat "$FAKE_LOG")"
assert_contains "nebflow home pinned" "HOMEVARS:|$SANDBOX/home" "$(cat "$FAKE_LOG")"
assert_lacks "no STAND_GATEWAY_PORT write" "PORTS:$P18|" "$(cat "$FAKE_LOG")"

# ── 19. 隔离: 端口解析各只认自家 env (行为证明: 对家变量指的口上有活监听也绝不探它) ──
t "case 19: env isolation - each brand ignores the other brand's port var on stop"
fresh_sandbox
P19A=$(pick_free_port); P19B=$(pick_free_port)
# 对家变量 NEBFLOW_GATEWAY_PORT=P19B 上放一个 stand 品牌监听器; stand 自家口 P19A 空着
FIX19=$(start_fixture "$P19B" marker)
if case "$FIX19" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P19A" NEBFLOW_GATEWAY_PORT="$P19B" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" stop 2>&1)"; RC=$?
    assert_rc "stand stop ignores NEBFLOW_* var rc" 0 "$RC"
    assert_contains "stand stop probes own port only" "stand is not running" "$RUN_OUT"
    if kill -0 "$FIX19" 2>/dev/null; then ok "stand stop did not touch NEBFLOW_* port listener"; else bad "stand stop killed the other brand's listener"; fi
    # 镜像: 对家变量 STAND_GATEWAY_PORT=P19A 上放 nebflow 标记监听器; nebflow 自家口 P19B 现在也空了
    kill "$FIX19" 2>/dev/null; wait_gone "$P19B" >/dev/null
    FIX19B=$(start_fixture "$P19A" marker nebflow-fixture)
    if case "$FIX19B" in ''|*[!0-9]*) false ;; *) true ;; esac; then
        RUN_OUT="$(cd "$SANDBOX" && NEBFLOW_HOME="$SANDBOX/home" NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
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
mkdir -p "$SANDBOX/homeB"
FIX20A=$(start_fixture "$P20A" marker)                      # stand 品牌侧监听器
FIX20B=$(start_fixture "$P20B" marker nebflow-fixture)      # nebflow 品牌侧监听器
if case "$FIX20A" in ''|*[!0-9]*) false ;; *) true ;; esac && case "$FIX20B" in ''|*[!0-9]*) false ;; *) true ;; esac; then
    echo "$FIX20A" > "$SANDBOX/home/.pid"
    echo "$FIX20B" > "$SANDBOX/homeB/.pid"
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P20A" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" stop 2>&1)"; RC=$?
    assert_rc "stand stop rc" 0 "$RC"
    if wait_gone "$P20A"; then ok "stand side stopped"; else bad "stand side still held"; fi
    if kill -0 "$FIX20B" 2>/dev/null; then ok "nebflow side untouched by stand stop"; else bad "stand stop killed nebflow side"; fi
    if [ "$(cat "$SANDBOX/homeB/.pid" 2>/dev/null)" = "$FIX20B" ]; then ok "nebflow home .pid untouched"; else bad "nebflow home .pid disturbed"; fi
    RUN_OUT="$(cd "$SANDBOX" && NEBFLOW_HOME="$SANDBOX/homeB" NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
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
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
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
    echo "$FIX23" > "$SANDBOX/home/.pid"
    _L23_BEFORE=$(FAKE_LOG_LINES)
    RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
        STAND_GATEWAY_PORT="$P23" FAKE_LOG="$FAKE_LOG" \
        "$SANDBOX/bin/stand" restart --dry-run 2>&1)"; RC=$?
    assert_rc "dry-run arm rc" 0 "$RC"
    assert_contains "dry-run armed message" "DRY-RUN armed" "$RUN_OUT"
    _L23=""
    for _i in $(seq 1 80); do
        _L23="$(cat "$SANDBOX/home"/logs/restart-detached-*.log 2>/dev/null || true)"
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
    if [ -e "$SANDBOX/home/.restart-detached.pid" ]; then bad "restart guard not cleaned"; else ok "restart guard cleaned"; fi
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
RUN_OUT="$(cd "$SANDBOX" && NEBFLOW_HOME="$SANDBOX/home" NEBFLOW_JAVA_BIN="$SANDBOX/fakebin/java" \
    NEBFLOW_GATEWAY_PORT="$P24" FAKE_LOG="$FAKE_LOG" \
    "$SANDBOX/bin/nebflow" restart --dry-run 2>&1)"; RC=$?
assert_rc "nebflow dry-run arm rc" 0 "$RC"
_L24=""
for _i in $(seq 1 80); do
    _L24="$(cat "$SANDBOX/home"/logs/restart-detached-*.log 2>/dev/null || true)"
    case "$_L24" in *"verdict:"*) break ;; esac
    sleep 0.1
done
assert_contains "nebflow brand recorded" "brand: nebflow" "$_L24"
assert_contains "nebflow dry-run verdict OK" "verdict: DRY-RUN OK" "$_L24"

# ── 25. 守卫记账: 活 pid 但非点火器 = 陈年记账 → WARN 覆写放行 ──
t "case 25: stale restart guard (alive non-igniter pid) -> WARN + overwrite + proceed"
fresh_sandbox
P25=$(pick_free_port)
echo "$$" > "$SANDBOX/home/.restart-detached.pid"   # 套件自身 bash: 活着但不是点火器
RUN_OUT="$(cd "$SANDBOX" && STAND_HOME="$SANDBOX/home" STAND_JAVA_BIN="$SANDBOX/fakebin/java" \
    STAND_GATEWAY_PORT="$P25" FAKE_LOG="$FAKE_LOG" \
    "$SANDBOX/bin/stand" restart --dry-run 2>&1)"; RC=$?
assert_rc "arm proceeds past stale guard rc" 0 "$RC"
assert_contains "stale guard warning" "stale restart guard" "$RUN_OUT"
assert_contains "arm completed anyway" "DRY-RUN armed" "$RUN_OUT"
for _i in $(seq 1 80); do
    _L25="$(cat "$SANDBOX/home"/logs/restart-detached-*.log 2>/dev/null || true)"
    case "$_L25" in *"verdict:"*) break ;; esac
    sleep 0.1
done
assert_contains "stale-guard dry-run verdict OK" "verdict: DRY-RUN OK" "$_L25"

echo
echo "== summary: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
