#!/bin/bash
# stand-cli-lib.sh — stand 与 nebflow 两个 CLI 入口的**共享实现**(单一代码路径)。
#
# 入口契约(部署纪律: 权威副本在 stand 仓 ops/bin/, live 是部署物):
#   ops/bin/stand    (entry, brand=stand)   → ~/.stand/bin/stand
#   ops/bin/nebflow  (entry, brand=nebflow) → ~/.nebflow/bin/nebflow
#   ops/bin/stand-cli-lib.sh (本文件)       → 与 entry 同目录部署两份根各一份
#
# ── 隔离契约 (作者令 2026-10-27: stand 与 nebflow 彻底独立, 互不拉起/探活/误杀;
#               作者令 2026-10-30 双实例并存批: 两命令同机可用) ──
#   命令      home 解析(只读自己的 env)   端口解析(只读自己的 env)                 jar 位
#   stand     STAND_HOME > ~/.stand       --port > STAND_GATEWAY_PORT > 6138       ENTRY_DIR/versions 指针+winsort
#   nebflow   NEBFLOW_HOME > ~/.nebflow   --port > NEBFLOW_GATEWAY_PORT > 6140     ENTRY_DIR/nebflow-assembly-*.jar winsort
#   历史病根: ~/.stand/bin/nebflow 弃用垫片 exec stand (PATH 经 ~/.zshrc:18 含
#   ~/.stand/bin) ⇒ 跑 nebflow 实际起的是 stand 6138; 且旧 stand 端口/home 链回落
#   NEBFLOW_* 变量(env 互窜)。修复: 垫片改指真 launcher; 端口/home 链各只认自己的变量。
#   stop 面口径天然按 home/端口收窄: 双探针 = lsof(本品牌端口) ∪ $本品牌home/.pid,
#   ⇒ `nebflow stop` 碰不到 6138 实例, `stand stop` 碰不到 6140 实例（双生分家批 2026-10-31：nebflow 自有独立默认口 6140，8080 自此只属 9.19 自有根实例）。
#
# ── restart --detached (作者令 2026-10-27, 自重启事故 2026-10-26 20:55 的 CLI 面修复) ──
#   作者用 Bash 工具挂 `stand stop && stand start` 定时器时, 点火器活在引擎进程树内,
#   树杀(ProcessTree.killProcessTree, ppid 维度)连带点火器 ⇒ 「停了起不回」。
#   机制: 双 fork 孤儿化(中间层立即退出, 点火器过继 launchd, ppid=1) + 点火前自检
#   ppid=1(不达标 ABORT rc=3, 引擎分毫未动) + 全程日志 $HOME根/logs/restart-detached-*.log。
#   验证面: --dry-run 假点火 —— 只写「自身 ppid + 引擎树可达探针」进日志, 不停不启。
#   守卫: $HOME根/.restart-detached.pid (活着的 __ignite-restart 在飞 ⇒ 拒绝双点火;
#   陈年死 pid/非点火器 pid ⇒ 视为陈年记账, 覆写)。
#   与应用侧 #916 (`stand restart` 注册 launchd 一次性任务, DetachedApply) 互补:
#   jar 面要求引擎活着能注册; 本 CLI 面零 JVM 依赖(点火腿), 引擎死/挂也能点火。
#   bare `stand restart` 保持透传给 jar(#916 detached apply 语义不变)。
#
# ── restart 三面口径 (作者裁定 2026-11-03 07:45「stand restart 的 cli 的话应该从用户
#    的角度去设计, 用户应该不是用编译版本的 stand, 应该和我们官网的自动更新、热重启相关」) ──
#   bare `stand restart` 不接开发护栏腿——终端用户装的是官网发布版(jar+指针布局), 没有源码
#   树, sbt 编译预检对他们不适用。bare = jar 面 #916 热重启(注册 OS 一次性作业后立即返回,
#   stop→start→健康闸由系统托管), 与 `stand update` 成对(同一 apply 执行器/日志/状态相)。
#   CLI 帮助面: `stand restart --help` 分三段(用户面/自救面/开发面), 用户面不出现 sbt/
#   编译/canary 字样; 开发面单独成段指路 ~/.stand/bin/stand-restart.sh(原 nebflow-restart.sh,
#   2026-11-03 改名批, 编译预检+canary+四信号, 不进 bare 路径、CLI 不暴露开发旗标)。
# =============================================================================

BRAND="${_STAND_CLI_BRAND:-stand}"
case "$BRAND" in
    stand|nebflow) ;;
    *) echo "ERROR: stand-cli-lib: unknown brand '$_STAND_CLI_BRAND'" >&2; exit 66 ;;
esac
ENTRY_DIR="${_STAND_CLI_ENTRY_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
SELF_ENTRY="${_STAND_CLI_SELF:-}"
[ -n "$SELF_ENTRY" ] || SELF_ENTRY="$0"

# home 解析 — 每品牌只认自己的 env (隔离: stand 不回落 NEBFLOW_HOME, nebflow 不读 STAND_HOME)
case "$BRAND" in
    stand)   STAND_HOME="${STAND_HOME:-$HOME/.stand}" ;;
    nebflow) STAND_HOME="${NEBFLOW_HOME:-$HOME/.nebflow}" ;;
esac

stand_port_from_args() { # 扫描 "$@": --port N / --port=N -> echo 端口; 无则 rc=1
    local _i=1 _n=$# _a _v
    while [ "$_i" -le "$_n" ]; do
        eval "_a=\${$_i}"
        case "$_a" in
            --port=*) printf '%s\n' "${_a#--port=}"; return 0 ;;
            --port)   eval "_v=\${$((_i+1))}"; [ -n "${_v:-}" ] && { printf '%s\n' "$_v"; return 0; } ;;
        esac
        _i=$((_i+1))
    done
    return 1
}

# >>> HOME-SCOPE (隔离实例误伤批 2026-11-04 §2: 端口解析先看 home) >>>
# 事故: STAND_HOME=/tmp/seal-canary-home 的隔离 canary 解析出品牌缺省口 6138,
#      把跑在 6138 的生产实例停机了。本段是法则——Scala 侧同一法则的单一实现是
#      src/main/scala/nebflow/shared/InstanceScope.scala（decide / proveHome / homeRefusal），
#      此处是它的 bash 镜像（同一判据、同一文案骨架、逐条对账）。
stand_home_norm() { # <path> -> 去尾斜杠 (路径字节口径, 不解析符号链接)
    local _p="$1"
    while [ "${#_p}" -gt 1 ] && [ "${_p%?}" != "$_p" ] && [ "${_p%/}" != "$_p" ]; do _p="${_p%/}"; done
    printf '%s\n' "$_p"
}

stand_home_isolated() { # rc 0 = 本品牌的数据根被覆盖(隔离根); rc 1 = 是默认根
    # 默认根 = 本品牌默认家 (~/.stand 或 ~/.nebflow) 与 legacy ~/.nebflow —— 与
    # PathUtil.resolveDefaultDataRoot 的候选集同源。
    local _b _legacy
    _b=$(stand_home_norm "$(stand_brand_default_home "$BRAND")")
    _legacy=$(stand_home_norm "${HOME:-}/.nebflow")
    case "$(stand_home_norm "${STAND_HOME:-}")" in
        "$_b"|"$_legacy") return 1 ;;
    esac
    return 0
}

stand_port_from_config() { # <root> -> 该根配置里的顶层 "port" 整数; 无则 rc=1
    # 配置文件名迁移（config-single-source 2026-11-05，品牌值 = brand.conf 的
    # configFileName = stand.json）：写入面早已是 stand.json，本读取面曾遗留旧名
    # nebflow.json ⇒ 操作员在 stand.json 里声明 "port" 被静默忽略，隔离根于是永远
    # 拒启（2026-11-21 跨机 E2E 缺陷 ①）。读取与 Scala 侧 PathUtil.resolveConfigJson
    # 同序双读：canonical stand.json 优先，legacy nebflow.json 只读兜底。
    local _root="$1" _f _v
    for _f in "$_root/stand.json" "$_root/nebflow.json"; do
        [ -r "$_f" ] || continue
        _v=$(grep -o '"port"[[:space:]]*:[[:space:]]*[0-9][0-9]*' "$_f" 2>/dev/null | head -n 1 | grep -o '[0-9][0-9]*$')
        [ -n "${_v:-}" ] && { printf '%s\n' "$_v"; return 0; }
    done
    return 1
}

# 端口解析 (单一实现): --port > [默认根时: 本品牌 env] > 本根配置 "port" >
#   [默认根时: 品牌缺省口] / [隔离根时: **拒绝** rc=3]。
# 隔离根里继承来的 *_GATEWAY_PORT 不算显式(可能就是父 shell 给生产实例的那一个):
# 忽略并打一行 WARN 点名它。
stand_resolve_port() { # $@ = 子命令参数
    local _p _env _envname _dflt _cnf
    case "$BRAND" in
        stand)   _env="${STAND_GATEWAY_PORT:-}";   _envname=STAND_GATEWAY_PORT;   _dflt=6138 ;;
        nebflow) _env="${NEBFLOW_GATEWAY_PORT:-}"; _envname=NEBFLOW_GATEWAY_PORT; _dflt=6140 ;;
    esac
    if _p=$(stand_port_from_args "$@"); then printf '%s\n' "$_p"; return 0; fi
    if stand_home_isolated; then
        if _cnf=$(stand_port_from_config "$STAND_HOME"); then
            if [ -n "$_env" ]; then
                echo "WARN: ignoring inherited $_envname=$_env — this invocation's data root is $STAND_HOME (an isolated root, not the default root), so an inherited port is not treated as explicit" >&2
            fi
            printf '%s\n' "$_cnf"; return 0
        fi
        echo "ERROR: refusing to inherit the brand default port ($_dflt) for data root $STAND_HOME: it is an ISOLATED root (not the default root), and an instance in an isolated root must name its own port." >&2
        echo "       Pass --port <N> explicitly, or set the \"port\" key in $STAND_HOME/stand.json." >&2
        [ -n "$_env" ] && echo "       (the inherited $_envname=$_env is ignored: an inherited port is not explicit)" >&2
        return 3
    fi
    printf '%s\n' "${_env:-$_dflt}"
}

stand_pid_home() { # <pid> -> 目标进程可证的 home; 无法判定 rc=1
    # 腿与 InstanceScope.homeFromCommandLine 同序: --home <p> / --home=<p> / -Duser.home=<p>
    local _cmd _h
    _cmd=$(stand_cmd_of "$1")
    [ -n "${_cmd:-}" ] || return 1
    _h=$(printf '%s\n' "$_cmd" | tr ' ' '\n' | awk 'p{print;exit} $0=="--home"{p=1}')
    [ -n "${_h:-}" ] || _h=$(printf '%s\n' "$_cmd" | tr ' ' '\n' | sed -n 's/^--home=//p' | head -n 1)
    [ -n "${_h:-}" ] || _h=$(printf '%s\n' "$_cmd" | tr ' ' '\n' | sed -n 's/^-Duser\.home=//p' | head -n 1)
    [ -n "${_h:-}" ] || return 1
    stand_home_norm "$_h"
}
# <<< HOME-SCOPE <<<


stand_listener_pids() { # <port> -> LISTEN pids (每行一个), 空则端口空闲
    lsof -ti tcp:"$1" -sTCP:LISTEN 2>/dev/null
}

stand_alive_pids() { # stdin: pids -> 只回活着的数字 pid
    local _p
    while IFS= read -r _p; do
        case "$_p" in ''|*[!0-9]*) continue ;; esac
        kill -0 "$_p" 2>/dev/null && printf '%s\n' "$_p"
    done
    return 0
}

stand_cmd_of() { ps -p "$1" -o command= 2>/dev/null | head -n 1; }

stand_is_ours() { # <pid> 身份门(按品牌收窄 — 隔离: 各家只认自家 jar 标记/sbt)
    local _c
    _c=$(stand_cmd_of "$1")
    case "$BRAND" in
        stand)   case "$_c" in *stand-assembly*|*sbt*) return 0 ;; esac ;;
        nebflow) case "$_c" in *nebflow-assembly*|*nebflow*|*sbt*) return 0 ;; esac ;;
    esac
    return 1
}

# >>> BRAND-MAP (双实例并存批 2026-10-30: 端口冲突时指明占口的是哪一实例) >>>
# 各品牌的默认家/默认口 —— 单一来源（本文档头契约表的机械读面；测试与文档表同读此处）。
stand_brand_default_home() { # <brand> -> 默认 root (无 env 时)
    case "$1" in
        stand)   printf '%s\n' "${HOME:-}/.stand" ;;
        nebflow) printf '%s\n' "${HOME:-}/.nebflow" ;;
        *) return 1 ;;
    esac
}

stand_brand_default_port() { # <brand> -> 默认端口
    case "$1" in
        stand)   printf '6138\n' ;;
        nebflow) printf '6140\n' ;;
        *) return 1 ;;
    esac
}

stand_other_brand() { # -> 对家品牌名 (stand <-> nebflow)
    case "$BRAND" in
        stand)   printf 'nebflow\n' ;;
        nebflow) printf 'stand\n' ;;
    esac
}

stand_brand_of_pid() { # <pid> -> stand|nebflow|(空=认不出) —— 只认 jar 路径标记, 不猜 sbt
    local _c
    _c=$(stand_cmd_of "$1")
    case "$_c" in
        *stand-assembly*)                    printf 'stand\n' ;;
        *nebflow-assembly*)                  printf 'nebflow\n' ;;
        */stand/bin/*|*/\.stand/bin/*)       printf 'stand\n' ;;
        */\.nebflow/bin/*|*/nebflow/bin/*)   printf 'nebflow\n' ;;
        *)                                   return 0 ;;
    esac
}
# <<< BRAND-MAP <<<

# Runtime artwork is rendered by the JAR after its startup guard admits launch.

# >>> STOP-IMPL (stop 面核心; 与 2026-10-25 修复批语义逐字一致, exit→return 化) >>>
# rc: 0 = stopped / not-running(幂等); 1 = 身份门拒绝; 2 = --tree 透传给应用侧
cli_stop_impl() { # $@ = 原始参数 (--force/--tree/--port 扫描)
    local _stop_force=0 _stop_tree=0 _a
    for _a in "$@"; do
        case "$_a" in
            --force) _stop_force=1 ;;
            --tree)  _stop_tree=1 ;;
        esac
    done
    if [ "$_stop_tree" = 0 ]; then
        local _port _stop_port_pids _stop_pf_pid _stop_pf_alive _stop_targets _stop_ours _stop_foreign _p _i _stop_left _stop_home
        if ! _port=$(stand_resolve_port "$@" 2>/dev/null); then
            # 反向面（隔离实例误伤批 2026-11-04 §1 反）：本根的端口声明不出来，但本根
            # 也没有活实例 ⇒ 诚实的答案是「没在跑」+ exit 0（零信号、零探测），绝不
            # 回落到品牌缺省口 6138 去找别人的实例。有活 .pid 时才把解析失败当拒绝上报。
            local _rp=""
            [ -r "$STAND_HOME/.pid" ] && _rp=$(tr -d ' \t\r\n' < "$STAND_HOME/.pid" 2>/dev/null)
            if [ -z "$(printf '%s\n' "${_rp:-}" | stand_alive_pids)" ]; then
                rm -f "$STAND_HOME/.pid" "$STAND_HOME/.pid.meta" 2>/dev/null
                echo "$BRAND is not running"
                return 0
            fi
            # 本根确有活实例却声明不出端口 ⇒ 把解析失败的原因照原样打出去再拒（fail-closed）
            stand_resolve_port "$@" >/dev/null
            return 3
        fi
        _stop_port_pids=$(stand_listener_pids "$_port" | stand_alive_pids)
        _stop_pf_pid=""
        if [ -r "$STAND_HOME/.pid" ]; then
            _stop_pf_pid=$(tr -d ' \t\r\n' < "$STAND_HOME/.pid" 2>/dev/null)
        fi
        _stop_pf_alive=$(printf '%s\n' "${_stop_pf_pid:-}" | stand_alive_pids)
        # 双探针并集去重 (端口腿 + pid 文件腿) — 双腿都按本品牌 home/端口收窄 ⇒ 不误伤对家实例
        _stop_targets=$({ [ -n "$_stop_port_pids" ] && printf '%s\n' "$_stop_port_pids"
                          [ -n "$_stop_pf_alive" ] && printf '%s\n' "$_stop_pf_alive"; } | sort -u)
        if [ -z "$_stop_targets" ]; then
            # 双空 = 真没在跑: 顺手清陈年 pid 记账 (病历: stop 报 not running 的失配面)
            rm -f "$STAND_HOME/.pid" "$STAND_HOME/.pid.meta" 2>/dev/null
            echo "$BRAND is not running"
            return 0
        fi
        # 身份门 (--force 跳过)
        _stop_foreign=""
        while IFS= read -r _p; do
            [ -n "$_p" ] || continue
            if ! stand_is_ours "$_p"; then
                if [ "$_stop_force" = 1 ]; then
                    echo "WARN: --force: killing non-$BRAND process pid $_p: $(stand_cmd_of "$_p")" >&2
                else
                    _stop_foreign="$_stop_foreign $_p"
                fi
            fi
        done <<< "$_stop_targets"
        if [ -n "$_stop_foreign" ]; then
            echo "ERROR: refusing to stop: port $_port held by non-$BRAND process(es):" >&2
            for _p in $_stop_foreign; do echo "       pid $_p: $(stand_cmd_of "$_p")" >&2; done
            echo "       Re-run with --force to kill anyway." >&2
            return 1
        fi
        # ── HOME 归属门 (隔离实例误伤批 2026-11-04 §1; 不可被 --force 绕过) ──
        # 2026-11-03 22:59 事故铁证: 一次 STAND_HOME=/tmp/seal-canary-home 的 stand CLI
        # 调用停掉的是 home=~/.stand/端口=6138 的 44339, 而不是 home=/tmp/seal-canary-home
        # 的 68701 —— 目标集合只按端口/品牌认实例, 从不看 home。此门把它反过来:
        #   · home 可证且 ≠ 本家 ⇒ 拒绝并点名两个 home, 零信号;
        #   · home 无法判定 ⇒ 拒绝(fail-closed, 本家 .pid 记账是唯一可接受的归属证明);
        # 例外: 用户显式 --force 且目标是**外姓**(上面已判) —— 仍要 home 可证才允许.
        # 只对本品牌实例过人（外姓进程不属本法则：--force 显式允许杀外姓，语义不变）。
        _stop_ours=""
        for _p in $_stop_targets; do
            if stand_is_ours "$_p"; then _stop_ours="$_stop_ours $_p"; fi
        done
        for _p in $_stop_ours; do
            if _stop_home=$(stand_pid_home "$_p"); then
                if [ "$_stop_home" != "$(stand_home_norm "$STAND_HOME")" ]; then
                    echo "ERROR: refusing to stop pid $_p: the target instance's home is $_stop_home but this invocation's home is $(stand_home_norm "$STAND_HOME") — different data roots are different instances; zero signal sent." >&2
                    echo "       Run the stop from THAT home, or pass the --port of your own instance." >&2
                    return 1
                fi
            else
                if [ "${_stop_pf_pid:-}" = "$_p" ]; then
                    :   # 来源即证明: pid 就是本家 .pid 记账的那一个
                else
                    echo "ERROR: refusing to stop pid $_p: cannot prove it belongs to this invocation's home $(stand_home_norm "$STAND_HOME") (no --home/-Duser.home evidence in its command line and it is not this root's .pid record) — fail-closed, zero signal sent" >&2
                    return 1
                fi
            fi
        done
        echo "stopping $BRAND (port $_port, pids:$(echo $_stop_targets))"
        kill $_stop_targets 2>/dev/null
        _i=0
        while [ "$_i" -lt 50 ]; do  # 宽限 ≤10s
            _stop_left=$(printf '%s\n' $_stop_targets | stand_alive_pids)
            if [ -z "$_stop_left" ] && [ -z "$(stand_listener_pids "$_port")" ]; then
                break
            fi
            sleep 0.2
            _i=$((_i+1))
        done
        _stop_left=$(printf '%s\n' $_stop_targets | stand_alive_pids)
        if [ -n "$_stop_left" ]; then
            echo "ERROR: graceful stop deadline expired; process(es) still running:$_stop_left — refusing force kill; service left running." >&2
            return 1
        fi
        if [ -n "$(stand_listener_pids "$_port" | stand_alive_pids)" ]; then
            echo "ERROR: process exited but port $_port is still held; refusing restart." >&2
            return 1
        fi
        # pid 记账清理: 记录的 pid 已死才清 (不碰别的活实例的记账)
        if [ -n "$_stop_pf_pid" ] && ! kill -0 "$_stop_pf_pid" 2>/dev/null; then
            rm -f "$STAND_HOME/.pid" "$STAND_HOME/.pid.meta" 2>/dev/null
        fi
        echo "$BRAND stopped (port $_port free)"
        return 0
    fi
    return 2  # --tree: sbt 祖先链止损在应用侧 (ProcessManager), 脚本只透传
}
# <<< STOP-IMPL <<<

# >>> RESTART-DETACHED-BEGIN v1 (作者令 2026-10-27) >>>
RESTART_GUARD_NAME=".restart-detached.pid"

cli_restart_guard_check() { # 在飞点火器 ⇒ rc 1 (拒绝双点火); 陈年/无关 ⇒ 清理放行
    local _g _cmd
    [ -r "$STAND_HOME/$RESTART_GUARD_NAME" ] || return 0
    _g=$(tr -d ' \t\r\n' < "$STAND_HOME/$RESTART_GUARD_NAME" 2>/dev/null)
    case "$_g" in ''|*[!0-9]*) rm -f "$STAND_HOME/$RESTART_GUARD_NAME"; return 0 ;; esac
    if kill -0 "$_g" 2>/dev/null; then
        _cmd=$(stand_cmd_of "$_g")
        case "$_cmd" in
            *__ignite-restart*)
                echo "ERROR: detached restart already in flight (igniter pid $_g) — log: $STAND_HOME/logs/restart-detached-*.log" >&2
                return 1 ;;
            *)  # 活 pid 但不是点火器 = 陈年垃圾记账, 覆写
                echo "WARN: stale restart guard (pid $_g alive but not an igniter: $_cmd) — overwriting" >&2
                rm -f "$STAND_HOME/$RESTART_GUARD_NAME"
                return 0 ;;
        esac
    fi
    rm -f "$STAND_HOME/$RESTART_GUARD_NAME"
    return 0
}

cli_restart_arm() { # $@ = 已过滤 port args; env: _RESTART_DRY(非空=dry-run)
    local _log _guard_pid
    mkdir -p "$STAND_HOME/logs" 2>/dev/null
    _log="$STAND_HOME/logs/restart-detached-$(date +%Y%m%d-%H%M%S).log"
    [ -e "$_log" ] && _log="$STAND_HOME/logs/restart-detached-$(date +%Y%m%d-%H%M%S)-$$.log"
    cli_restart_guard_check || exit 1
    # 隔离实例误伤批 2026-11-04 §2: 端口解析不出来(隔离根没声明端口) ⇒ 不布防 ——
    # 否则点火器会带着空端口进 stop 腿/点火腿。
    stand_resolve_port "$@" >/dev/null || exit $?
    {
        echo "== $BRAND restart-detached armed =="
        echo "ts: $(date '+%F %T') (epoch $(date +%s))"
        if [ -n "${_RESTART_DRY:-}" ]; then echo "mode: dry-run"; else echo "mode: restart"; fi
        echo "armed by: pid=$$ ppid=$PPID cmdline=$(stand_cmd_of $$)"
        echo "home: $STAND_HOME  port: $(stand_resolve_port "$@")  brand: $BRAND"
        [ -n "${_RESTART_DRY:-}" ] && echo "note: DRY-RUN — igniter 假点火, 只写探针证据, 不停不启"
    } >> "$_log"
    # ── 双 fork 孤儿化: 中间层 fork 出点火器后立即退出 ⇒ 点火器被 launchd 收养(ppid=1),
    #    脱离调用方(含引擎树)的 ppid 链 — ProcessTree.killProcessTree 是树维度, 从此够不着。
    (
        ( exec "$SELF_ENTRY" __ignite-restart "$_log" ${_RESTART_DRY:+--dry-run} -- "$@" ) </dev/null >/dev/null 2>&1 &
        printf '%s\n' "$!" > "$STAND_HOME/$RESTART_GUARD_NAME"
    ) </dev/null >/dev/null 2>&1
    _guard_pid=$(tr -d ' \t\r\n' < "$STAND_HOME/$RESTART_GUARD_NAME" 2>/dev/null)
    if [ -n "${_RESTART_DRY:-}" ]; then
        echo "DRY-RUN armed (假点火): igniter pid ${_guard_pid:-?} — 引擎分毫未动, 证据日志: $_log"
    else
        echo "detached restart armed: igniter pid ${_guard_pid:-?} — log: $_log"
    fi
    exit 0
}

cli_ignite_restart() { # 隐藏动词: 必须以孤儿身份运行; $1=log, 其后旗标, `--` 后为 port args
    local _log="$1"; shift
    local _dry=0 _seen_sep=0 _a
    IGN_PORT_ARGS=()
    for _a in "$@"; do
        case "$_a" in
            --)        _seen_sep=1 ;;
            --dry-run) [ "$_seen_sep" = 0 ] && _dry=1 ;;
            *)         [ "$_seen_sep" = 1 ] && IGN_PORT_ARGS+=("$_a") ;;
        esac
    done
    exec >> "$_log" 2>&1   # 点火器全程日志落盘 (armed 头由外层已写)
    trap 'rm -f "$STAND_HOME/$RESTART_GUARD_NAME" 2>/dev/null' EXIT
    local _port _prc _pp _i _targets _t _c _chain _cur _eng_in_tree _rc _jdk _jar
    # 隔离实例误伤批 2026-11-04 §2: 解析不出端口 ⇒ 点火器一步不动 (不 stop 不 start)。
    _port=$(stand_resolve_port "${IGN_PORT_ARGS[@]}"); _prc=$?
    if [ "$_prc" != 0 ]; then
        echo "-- igniter boot --"
        echo "verdict: ABORT (rc=$_prc) — 端口解析被拒 (隔离根必须显式声明端口); 引擎分毫未动"
        exit 7
    fi
    echo "-- igniter boot --"
    echo "ts: $(date '+%F %T') (epoch $(date +%s))  mode: $( [ "$_dry" = 1 ] && echo dry-run || echo restart )"
    echo "brand: $BRAND  home: $STAND_HOME  port: $_port"
    echo "igniter pid=$$ ppid_at_entry=$(ps -o ppid= -p $$ | tr -d ' ') (contract: ppid==1)"
    # ── 点火前自检: 等待 reparent 稳定(≤2s)后 ppid 必须为 1, 不达标中止并报错 ──
    _i=0
    while [ "$_i" -lt 20 ]; do
        _pp=$(ps -o ppid= -p $$ | tr -d ' ')
        [ "$_pp" = "1" ] && break
        sleep 0.1
        _i=$((_i+1))
    done
    _pp=$(ps -o ppid= -p $$ | tr -d ' ')
    echo "[orphan] waited $((_i * 100))ms; ppid=$_pp"
    if [ "$_pp" != "1" ]; then
        echo "ABORT: ppid=$_pp != 1 — 双 fork 孤儿化失败, 点火器仍在调用方进程树内 (树杀可达); 引擎分毫未动"
        echo "verdict: ABORT (rc=3)"
        exit 3
    fi
    echo "[self-check] ppid=1 OK — launchd 收养, 已脱离一切引擎进程树/会话"
    # ── 引擎树可达探针 (dry-run 与真跑都写, 假点火证据在此) ──
    _targets=$({ stand_listener_pids "$_port"
                 [ -r "$STAND_HOME/.pid" ] && tr -d ' \t\r\n' < "$STAND_HOME/.pid" 2>/dev/null; } | sort -u | stand_alive_pids)
    echo "probe: stop-face pids (port $_port ∪ $STAND_HOME/.pid) = [$(echo $_targets)]"
    if [ -z "$_targets" ]; then
        echo "probe: engine not running (双探针空) — 真跑语义 = 直接点火, 无 stop 腿"
    fi
    for _t in $_targets; do
        echo "probe: pid $_t cmd=$(stand_cmd_of "$_t")"
        if kill -0 "$_t" 2>/dev/null; then echo "probe: pid $_t signal-permission=ok (kill -0)"; else echo "probe: pid $_t signal-permission=DENIED"; fi
    done
    _chain=""; _cur=$$
    for _i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        _cur=$(ps -o ppid= -p "$_cur" 2>/dev/null | tr -d ' ')
        case "$_cur" in ''|0) break ;; esac
        _chain="$_chain $_cur"
        [ "$_cur" = "1" ] && break
    done
    echo "probe: igniter ancestor chain = [${_chain} ]"
    _eng_in_tree="NO"
    for _t in $_targets; do
        for _c in $_chain; do
            [ "$_t" = "$_c" ] && _eng_in_tree="YES(pid $_t)"
        done
    done
    case "$_eng_in_tree" in
        NO) echo "probe: engine pid in igniter ancestor chain: NO → 树杀不可达点火器 (孤儿安全)" ;;
        *)  echo "probe: engine pid in igniter ancestor chain: $_eng_in_tree → 危险: 点火器仍在引擎树内" ;;
    esac
    if [ "$_dry" = 1 ]; then
        if [ "$_eng_in_tree" = "NO" ]; then
            echo "verdict: DRY-RUN OK — 假点火成立 (ppid=1 且点火器不在引擎树内); 未停未启, 引擎分毫未动"
        else
            echo "verdict: DRY-RUN INCONCLUSIVE — 机制不成立 (见上行); 仍不停不启"
        fi
        exit 0
    fi
    # ── 真跑: stop 腿 → 端口空闲闸 → 点火 (exec java start) ──
    if [ -n "$_targets" ]; then
        echo "action: stop leg (TERM→KILL 既有语义) ..."
        cli_stop_impl "${IGN_PORT_ARGS[@]}"
        _rc=$?
        echo "action: stop leg rc=$_rc"
        if [ "$_rc" != 0 ]; then
            echo "verdict: FAIL — stop refused (rc=$_rc), 不点火"
            exit 4
        fi
    fi
    _i=0
    while [ "$_i" -lt 50 ]; do
        [ -z "$(stand_listener_pids "$_port")" ] && break
        sleep 0.2
        _i=$((_i+1))
    done
    if [ -n "$(stand_listener_pids "$_port")" ]; then
        echo "verdict: FAIL — port $_port still held after stop; 不点火"
        exit 5
    fi
    # JDK 门 + jar 选择 (与共同启动面同源; 失败即中止并报因)
    cli_boot_prep _jdk _jar || { echo "verdict: FAIL — boot prep failed; 引擎已停但未点火 (见上行 ERROR)"; exit 6; }
    cli_pin_child_env "$_port"   # 点火子进程端口/家钉 env (与 start 放行路径同源)
    echo "banner: stand — One entry, Every agent. (detached ignition)"
    echo "action: ignition: exec java -jar $_jar start ${IGN_PORT_ARGS[*]:-}"
    rm -f "$STAND_HOME/$RESTART_GUARD_NAME" 2>/dev/null   # exec 不跑 EXIT trap, 先清守卫
    exec "$_jdk" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$_jar" start "${IGN_PORT_ARGS[@]}"
}
# <<< RESTART-DETACHED-END v1 <<<

# >>> WINSORT (version-order jar pick; 版本核与 packaging/app-version.sh:36 同契约:
#      ([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(\.[0-9]+)?(-beta\.[0-9]+)?; 日期制刻意
#      去前导零 ⇒ 名字序 != 版本序, 按数字元组排名; 两不匹配的形状一律剔除, 不静默回落) >>>
cli_winsort_jar_key() { # <jar path> -> fixed-width numeric key (year,month,day,seq); rc=1 if unparseable
  local _base="${1:-}" _re
  _base="${_base##*/}"
  _re='-assembly-([0-9]{4})\.([0-9]{1,2})\.([0-9]{1,2})(\.([0-9]+))?(-beta\.([0-9]+))?\.jar$'
  if [[ $_base =~ $_re ]]; then
    printf '%04d%03d%03d%06d' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))" "$((10#${BASH_REMATCH[5]:-${BASH_REMATCH[7]:-0}}))"
    return 0
  fi
  _re='-assembly-([0-9]+)\.([0-9]+)\.([0-9]+)(\.([0-9]+))?(-beta\.([0-9]+))?\.jar$'
  if [[ $_base =~ $_re ]]; then
    printf '%04d%03d%03d%06d' "$((10#${BASH_REMATCH[1]}))" "$((10#${BASH_REMATCH[2]}))" "$((10#${BASH_REMATCH[3]}))" "$((10#${BASH_REMATCH[5]:-${BASH_REMATCH[7]:-0}}))"
    return 0
  fi
  return 1
}

cli_winsort_pick_newest() { # stdin: one candidate path per line -> prints the newest one
  local _cand _key _best='' _best_key=''
  while IFS= read -r _cand; do
    [ -n "$_cand" ] || continue
    _key=$(cli_winsort_jar_key "$_cand") || continue
    if [ -z "$_best_key" ] || [ "$_key" \> "$_best_key" ]; then
      _best="$_cand"; _best_key="$_key"
    fi
  done
  [ -n "$_best" ] && printf '%s\n' "$_best"
  return 0
}
# <<< WINSORT <<<

# >>> BOOT-PREP (共同启动面: JDK 门 + jar 选择; 与 2026-10-25 修复批语义一致) >>>
cli_boot_prep() { # $1/$2 = 回传变量名 (经调用方 local + bash 动态作用域回传);
                  # 本函数内部变量一律 _ 前缀 —— 不得声明与回传名同名的 local
                  # (2026-10-27 病: local JAVA_BIN 遮蔽调用方 ⇒ eval 回传自赋值空串,
                  #  exec "" rc=127)。rc!=0 = 已报错。
    local _jv="$1" _jr="$2" _java_bin="" _jar_sel="" _pv _cand _major
    # JDK 覆写 env 按品牌各认各的 (隔离: stand 不读 NEBFLOW_JAVA_BIN, 反之亦然)
    case "$BRAND" in
        stand)   [ -n "${STAND_JAVA_BIN:-}" ]   && _java_bin="$STAND_JAVA_BIN" ;;
        nebflow) [ -n "${NEBFLOW_JAVA_BIN:-}" ] && _java_bin="$NEBFLOW_JAVA_BIN" ;;
    esac
    if [ -z "$_java_bin" ]; then
        case "$BRAND" in
            stand)   _java_bin="$STAND_HOME/jdk-21/bin/java" ;;
            nebflow) _java_bin="$HOME/.stand/jdk-21/bin/java" ;;   # 9.19 契约: 钉住 JDK 在 stand 根
        esac
        if [ ! -x "$_java_bin" ]; then
            echo "WARN: $BRAND pinned JDK missing at $_java_bin - falling back to PATH java." >&2
            echo "      This is a NON-silent degrade (author ruling 2026-09-21); the pinned" >&2
            echo "      JDK contract still wants jdk-21 (stand: \$STAND_HOME/jdk-21, nebflow: \$HOME/.stand/jdk-21)." >&2
            echo "      Repair (run the first that exists):" >&2
            echo "        bash \"$HOME/.stand/bin/stand-jdk\" install     # deployed self-heal installer" >&2
            echo "        bash \"$ENTRY_DIR/stand-jdk\" install           # sibling installer, if deployed" >&2
            _java_bin="$(command -v java || true)"
        fi
    fi
    if [ -z "$_java_bin" ]; then
        echo "ERROR: Java 21+ not found (no pinned JDK, none on PATH)." >&2
        cli_java_hint
        return 1
    fi
    _major="$("$_java_bin" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)"
    [ "$_major" = "1" ] && _major="$("$_java_bin" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f2)"
    case "$_major" in ''|*[!0-9]*) _major=0 ;; esac
    if [ "$_major" -lt 21 ]; then
        echo "ERROR: Java 21+ required, found $($_java_bin -version 2>&1 | head -n1)" >&2
        echo "       launcher java: $_java_bin" >&2
        cli_java_hint
        return 1
    fi
    # jar 选择: stand = 指针(current-version)+winsort 兜底 (回滚=切指针); nebflow = 自有根 winsort
    if [ "$BRAND" = "stand" ]; then
        if [ -f "${ENTRY_DIR}/current-version" ]; then
            _pv=$(tr -d ' \t\r\n' < "${ENTRY_DIR}/current-version" 2>/dev/null)
            if [ -n "$_pv" ] && [ -f "${ENTRY_DIR}/versions/${_pv}/stand-assembly-${_pv}.jar" ]; then
                _jar_sel="${ENTRY_DIR}/versions/${_pv}/stand-assembly-${_pv}.jar"
            elif [ -n "$_pv" ]; then
                for _cand in "${ENTRY_DIR}/versions/${_pv}"/*.jar; do
                    [ -f "$_cand" ] || continue
                    _jar_sel="$_cand"
                    break
                done
            fi
        fi
        _jar_sel=${_jar_sel:-$(ls -1 "${ENTRY_DIR}"/stand-assembly-*.jar 2>/dev/null | cli_winsort_pick_newest)}
    else
        _jar_sel=$(ls -1 "${ENTRY_DIR}"/nebflow-assembly-*.jar 2>/dev/null | cli_winsort_pick_newest)
    fi
    if [ -z "$_jar_sel" ]; then
        echo "ERROR: $BRAND JAR not found in ${ENTRY_DIR}" >&2
        return 1
    fi
    eval "$_jv=\$_java_bin"
    eval "$_jr=\$_jar_sel"
    return 0
}

cli_java_hint() {
    case "$(uname -s)" in
        Darwin) echo "  Install: brew install openjdk@21  (or: bash \"$HOME/.stand/bin/stand-jdk\" install)" >&2 ;;
        Linux)  echo "  Install: sudo apt install openjdk-21-jdk-headless  (dnf/yum: java-21-openjdk, apk: openjdk21); no sudo: bash \"$HOME/.stand/bin/stand-jdk\" install" >&2 ;;
        *)      echo "  Install: https://adoptium.net/temurin/releases/?version=21" >&2 ;;
    esac
    echo "  Then re-run: $BRAND <command>" >&2
}
# <<< BOOT-PREP <<<

# ── 主分发 ───────────────────────────────────────────────────────────────────
cli_pin_child_env() { # <port> — 探到的端口/解析的家钉进子进程 env (只写不读; 写对家
                      #   变量名仅为引擎旧名兼容面, 与隔离无涉 — env 是进程私有的)
    #
    # 双实例并存批 2026-10-30（隔离补课 · 数据根 env 穿透洞）：只钉端口不够 ——
    # 引擎侧数据根解析是 `STAND_HOME > NEBFLOW_HOME > 默认`（shared/paths.scala dataRoot）。
    # stand 入口原先不写 STAND_HOME ⇒ 用户在 shell 里遗留导出 NEBFLOW_HOME 时，
    # `stand start` 起的引擎会落进 **~/.nebflow**（对家的家），而 launcher 的
    # already-running / stop 守卫查的是 ~/.stand —— 双实例隔离在「数据根」面破口。
    # 修法：把本品牌解析出的家钉进**自家变量名**（stand ⇒ STAND_HOME，引擎第一优先命中），
    # 并把对家的 home 变量从子进程 env 里**摘掉**（不是改写 —— 摘掉才叫「子进程只见一家」）。
    # 端口侧不变：两家名字都写成同一解析值（引擎旧名兼容面本就是同一个进程私有 env）。
    local _port="$1"
    case "$BRAND" in
        stand)
            export STAND_HOME="$STAND_HOME"
            export STAND_GATEWAY_PORT="$_port"
            export NEBFLOW_GATEWAY_PORT="$_port"   # 引擎旧名端口别名（兼容面，同值）
            unset NEBFLOW_HOME ;;                  # 对家家名不进子进程 env
        nebflow)
            export NEBFLOW_HOME="$STAND_HOME"
            export NEBFLOW_GATEWAY_PORT="$_port"
            unset STAND_HOME STAND_GATEWAY_PORT ;; # 同理: 对家名不残留
    esac
}

# >>> RESTART-HELP (作者裁定 2026-11-03 07:45: restart 的 CLI 从用户角度设计) >>>
# 三段口径: 用户面(jar 面 #916 热重启, 默认) / 自救面(--detached · --dry-run, 零 JVM) /
# 开发面(stand-restart.sh 外部工具, 不进 bare 路径)。用户面刻意不出现 sbt / 编译 / canary
# 字样——读者是「只装了官网发布的 stand、机器上没有源码树」的人。
# 失败语义按引擎实况写(DetachedApply 渲染脚本 do_apply): restart 失败 = phase=failed
# + 报因留证(日志/状态相), 服务保持停止; **只有 update 才有回滚腿**(切回旧指针并起旧版)。
stand_restart_help() {
    local _root="${STAND_HOME:-$(stand_brand_default_home "$BRAND")}"
    if [ "$BRAND" != "stand" ]; then
        # nebflow 品牌挂的是 9.19 自有根 jar，没有 restart 子命令 ⇒ 用户面不可用（既有事实，rc=2）。
        echo "note: '$BRAND' 品牌的 jar（9.19 自有根）没有 restart 命令 —— 用户面（bare / --jar-apply）"
        echo "      在 '$BRAND' 上不可用，请直接用下面「自救面」的两条腿。以下按 stand 品牌口径说明。"
        echo
    fi
    cat <<EOF
$BRAND restart — 重启 stand 服务

── 用户面（默认，敲这个就够）：$BRAND restart ──
  命令只做一件事：把这次「停旧服务 → 起新服务 → 健康检查」交给操作系统的后台作业，
  随即打印「已排定」回执并返回（几秒内）。所以命令返回后你可以立刻关终端、断 ssh、
  退出任何会话——后台作业照常跑完，不会被连带中断。

  · 装的是什么版本，重启的就是什么版本：只重跑当前已安装的那一份，不下载、不换包。
  · 多久生效：通常几十秒。停旧服务最多等 90 秒；新服务的健康检查最多等 180 秒，
    超时按失败处理。
  · 失败了会怎样：后台作业停下并写明原因——服务保持停止（不会带着起不来的实例继续跑），
    原因落在 $_root/logs/apply-restart.log，进度相在 $_root/apply/state.json。
    照日志里的结论处理即可；修好后重跑一次 $BRAND restart。
  · 顺带换新版本：用 $BRAND update --from <jar>。它先校验新包再排定同一次重启；
    健康检查不过会自动回滚到上一个版本并起回旧服务。
  · 平台：macOS（launchd 一次性作业）/ Windows（计划任务）。Linux 上此能力未内置，
    命令会直接报错说明，不会假装已排定。
  · 想同步等待结果、不要后台作业：$BRAND restart --inline（旧同步面，命令自己不返回
  一直到起停做完）；终端断开即前功尽弃）。

── 自救面（引擎已经挂死、命令起不来时）：--detached / --dry-run ──
  上面的用户面要求引擎活着（才能把作业排进系统）。引擎死透时用这两条腿——它们
  完全不碰 JVM，只要 shell 能跑就行：
  · $BRAND restart --detached
      在命令行这一层把点火器过继给系统（ppid=1，脱离调用方进程树/会话），自检通过才
      动手：停旧 → 等端口空 → 直接起服务。全程日志 $_root/logs/restart-detached-*.log。
      防双点火守卫：$_root/.restart-detached.pid（点火器在飞时再敲会被拒绝）。
  · $BRAND restart --dry-run
      假点火：只把「自身 ppid、要动的进程、祖先链里有没有引擎」写成证据日志，不停也不起。
      验证判据：日志出现 "ppid=1 OK" 且 "engine pid in igniter ancestor chain: NO"，
      即点火器已经脱离引擎树，之后真跑不会被引擎的树杀连带带走。

── 开发面（本机有源码树、在跑开发实例时才相关）：stand-restart.sh ──
  这是**另一个工具**，不是本命令的分支：$_root/bin/stand-restart.sh（2026-11 改名前为
  nebflow-restart.sh，旧名保留为弃用垫片，转发同一实现）。
  它服务开发者场景——先在本机源码树上做编译预检，再用隔离实例真实起一遍验证，然后等
  「四信号全空闲 + 观察窗」确认没人在干活了才动手，宁可等也不打断在途任务。
  用法与全部护栏见 ops/README.md 与脚本自身头注释；裸调用 = 只检测不动手
  （$_root/bin/stand-restart.sh --check，安全默认）。
  本命令刻意不把它接进 bare 路径：官网安装版没有源码树，编译预检对终端用户不适用。
EOF
}
# <<< RESTART-HELP <<<

stand_cli_main() {
    local cli_cmd="${1:-}"
    case "$cli_cmd" in
        start)
            local _port _guard_pids _guard_pid
            # 隔离实例误伤批 2026-11-04 §2: 端口解析失败(隔离根没声明端口) ⇒ 直接拒启,
            # 绝不静默回落品牌缺省口 6138/6140 (那正是 canary 抢生产口的入口).
            _port=$(stand_resolve_port "$@") || exit $?
            cli_pin_child_env "$_port"
            _guard_pids=$(stand_listener_pids "$_port")
            if [ -n "$_guard_pids" ]; then
                _guard_pid=$(printf '%s\n' "$_guard_pids" | head -n 1)
                if stand_is_ours "$_guard_pid"; then
                    echo "$BRAND is already running (pid $_guard_pid) on port $_port"
                    exit 0
                fi
                echo "ERROR: port $_port is occupied by a non-$BRAND process (pid $_guard_pid):" >&2
                echo "       $(stand_cmd_of "$_guard_pid")" >&2
                # 双实例并存批 2026-10-30：占口者若是另一品牌实例，点名报出（对号入座，
                # 不再让人对着裸端口猜）。认不出就照旧只说「非本品牌」。
                local _occ_brand
                _occ_brand=$(stand_brand_of_pid "$_guard_pid")
                if [ -n "$_occ_brand" ] && [ "$_occ_brand" != "$BRAND" ]; then
                    echo "       This is the OTHER instance: '$_occ_brand' (default home $(stand_brand_default_home "$_occ_brand"), default port $(stand_brand_default_port "$_occ_brand"))." >&2
                    echo "       The two command brands are isolated and meant to coexist: '$BRAND' and '$_occ_brand'" >&2
                    echo "       never share a port/home. Check the port: it looks like you launched '$BRAND'" >&2
                    echo "       with $_occ_brand's port. Use '$BRAND' default (port $(stand_brand_default_port "$BRAND")) or" >&2
                    echo "       '$BRAND --port <free>' / '$_occ_brand stop' first." >&2
                else
                    echo "       Stop it, or pick another port (--port / ${BRAND}_GATEWAY_PORT)." >&2
                fi
                exit 1
            fi
            cli_boot "$@"
            ;;
        stop)
            shift
            local _tree=0 _a _rc
            for _a in "$@"; do [ "$_a" = "--tree" ] && _tree=1; done
            if [ "$_tree" = 1 ]; then cli_boot "stop" "$@"; fi   # --tree 透传应用侧 (ProcessManager)
            cli_stop_impl "$@"
            _rc=$?
            exit "$_rc"
            ;;
        restart)
            shift
            local _detached=0 _dry=0 _jar_apply=0 _fwd=() _a _help=0
            for _a in "$@"; do
                case "$_a" in
                    --detached)  _detached=1 ;;
                    --dry-run)   _dry=1 ;;
                    --jar-apply) _jar_apply=1 ;;   # 显式具名 = 用户面 jar 面 (#916 detached apply)；与 bare 同路径
                    --help|-h)   _help=1 ;;        # 帮助面在 brand 探针之前: 不落 JVM
                    *)           _fwd+=("$_a") ;;   # 其余一律透传 (--port/--force/--tree/run --inline…)
                esac
            done
            if [ "$_help" = 1 ]; then stand_restart_help; exit 0; fi
            if [ "$_jar_apply" = 1 ] && { [ "$_detached" = 1 ] || [ "$_dry" = 1 ]; }; then
                echo "ERROR: --jar-apply 与 --detached/--dry-run 互斥（前者走引擎 jar 面, 后者是零 JVM 自救腿）" >&2
                exit 2
            fi
            # 用户面（bare / --jar-apply）: jar 面 #916 热重启——RestartCommand.runDetached 注册
            # OS 一次性 apply 作业（stop→start→健康闸由系统托管）后立即返回, 与 `stand update`
            # 成对。作者裁定 2026-11-03 07:45: 终端用户装的是官网发布版, 无源码树,
            # **bare 不接开发护栏腿**（stand-restart.sh 的编译预检/canary 对用户不适用）。
            if [ "$_detached" = 0 ] && [ "$_dry" = 0 ]; then
                if [ "$BRAND" = "stand" ]; then
                    # --jar-apply 是本面显式具名；剥掉后逐字透传（jar 侧不认识该旗标）。
                    cli_boot "restart" "${_fwd[@]}"   # bare 透传(逐字, 含 --force/--tree/run --inline): jar 侧 #916 语义不变
                else
                    echo "nebflow (2026.9.19) jar has no restart command — use: nebflow restart --detached (or --dry-run 假点火验证)" >&2
                    exit 2
                fi
            fi
            [ "$_dry" = 1 ] && _RESTART_DRY=1
            # dry-run 隐含 detached (ppid=1 证据必须经孤儿化产生)
            cli_restart_arm "${_fwd[@]}"
            ;;
        __brand-defaults)   # 隐藏动词（help/补全不列；测试与文档对账用）：
                            # 打印本品牌解析出的 家/口/对家 一行式读数，零 JVM 零网络。
                            # 双实例并存批 2026-10-30 加：让 `ops/test/stand-lifecycle.test.sh`
                            # 无需真起引擎即可断言「stand→~/.stand+6138 / nebflow→~/.nebflow+6140」（双生分家批 2026-10-31）。
                            printf 'brand=%s\nhome=%s\nport=%s\nother=%s\nother_home=%s\nother_port=%s\n' \
                                "$BRAND" "$STAND_HOME" "$(stand_resolve_port "$@")" \
                                "$(stand_other_brand)" "$(stand_brand_default_home "$(stand_other_brand)")" \
                                "$(stand_brand_default_port "$(stand_other_brand)")"
            exit 0
            ;;
        __ignite-restart)   # 隐藏动词 (help/补全不列): cli_restart_arm 专用
            shift
            cli_ignite_restart "$@"
            exit $?
            ;;
        *)
            cli_boot "$@"   # 非生命周期子命令 (version/status/...) 零改动直透
            ;;
    esac
}

cli_boot() { # Forward arguments unchanged; the JAR owns terminal branding.
    local JAVA_BIN JAR
    cli_boot_prep JAVA_BIN JAR || exit 11
    exec "$JAVA_BIN" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$JAR" "$@"
}
