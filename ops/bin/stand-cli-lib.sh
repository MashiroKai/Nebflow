#!/bin/bash
# stand-cli-lib.sh — stand 与 nebflow 两个 CLI 入口的**共享实现**(单一代码路径)。
#
# 入口契约(部署纪律: 权威副本在 stand 仓 ops/bin/, live 是部署物):
#   ops/bin/stand    (entry, brand=stand)   → ~/.stand/bin/stand
#   ops/bin/nebflow  (entry, brand=nebflow) → ~/.nebflow/bin/nebflow
#   ops/bin/stand-cli-lib.sh (本文件)       → 与 entry 同目录部署两份根各一份
#
# ── 隔离契约 (作者令 2026-10-27: stand 与 nebflow 彻底独立, 互不拉起/探活/误杀) ──
#   命令      home 解析(只读自己的 env)   端口解析(只读自己的 env)                 jar 位
#   stand     STAND_HOME > ~/.stand       --port > STAND_GATEWAY_PORT > 6138       ENTRY_DIR/versions 指针+winsort
#   nebflow   NEBFLOW_HOME > ~/.nebflow   --port > NEBFLOW_GATEWAY_PORT > 8080     ENTRY_DIR/nebflow-assembly-*.jar winsort
#   历史病根: ~/.stand/bin/nebflow 弃用垫片 exec stand (PATH 经 ~/.zshrc:18 含
#   ~/.stand/bin) ⇒ 跑 nebflow 实际起的是 stand 6138; 且旧 stand 端口/home 链回落
#   NEBFLOW_* 变量(env 互窜)。修复: 垫片改指真 launcher; 端口/home 链各只认自己的变量。
#   stop 面口径天然按 home/端口收窄: 双探针 = lsof(本品牌端口) ∪ $本品牌home/.pid,
#   ⇒ `nebflow stop` 碰不到 6138 实例, `stand stop` 碰不到 8080 实例。
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

stand_resolve_port() { # $@ = 子命令参数; --port > 本品牌 env > 品牌缺省 (隔离: 不读对家 env)
    local _p _env _dflt
    case "$BRAND" in
        stand)   _env="${STAND_GATEWAY_PORT:-}";   _dflt=6138 ;;
        nebflow) _env="${NEBFLOW_GATEWAY_PORT:-}"; _dflt=8080 ;;
    esac
    if _p=$(stand_port_from_args "$@"); then printf '%s\n' "$_p"; return 0; fi
    printf '%s\n' "${_env:-$_dflt}"
}

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

# >>> BRAND-BANNER (仅 stand; nebflow 面无横幅 — 9.19 自有根 launcher 契约) >>>
# 仅 start 且守卫放行(真的要起 daemon)时打印; already-running / 拒启 / stop 不印。
# 分寸: 非 TTY / NO_COLOR / TERM=dumb → 单行纯文本。宽窄: cols>=80 款A, <80 款B;
# STAND_BANNER=A|B|C 强制, STAND_BANNER_COLS 覆写探测宽。
stand_banner_print() {
    local _cols _cand
    if [ -n "${STAND_BANNER_COLS:-}" ]; then
        _cols="$STAND_BANNER_COLS"
    else
        _cols="$(tput cols 2>/dev/null)"
        _cols="${_cols:-80}"
    fi
    case "$_cols" in ''|*[!0-9]*) _cols=80 ;; esac
    if [ ! -t 1 ] || [ -n "${NO_COLOR:-}" ] || [ "${TERM:-}" = "dumb" ]; then
        printf 'stand — One entry, Every agent.\n'
        return 0
    fi
    local _w=$'\033[97m' _k=$'\033[40m' _b=$'\033[1m' _r=$'\033[0m'
    _cand="${STAND_BANNER:-}"
    if [ -z "$_cand" ]; then
        if [ "$_cols" -ge 80 ]; then _cand="A"; else _cand="B"; fi
    fi
    case "$_cand" in
        A)  # 徽标款: 黑底白 S 方块(favicon 同构) + 标语
            printf '%s\n' \
"$_k ${_w}███████${_r}$_k       ${_r}" \
"$_k ${_w}█${_r}$_k        ${_r}" \
"$_k ${_w}███████${_r}$_k   $_r$_b${_w} One entry, Every agent.$_r" \
"$_k ${_w}      █${_r}$_k   ${_r}" \
"$_k ${_w}███████${_r}$_k       ${_r}"
            ;;
        B)  # 双行紧凑款: 窄终端 / 择优
            printf '%s\n' \
"$_k ${_w}▐█▌${_r}$_k ${_r}  $_b${_w}One entry,$_r" \
"$_k ${_w}▐█▌${_r}$_k ${_r}  $_b${_w}Every agent.$_r"
            ;;
        C)  # 单行字标款
            printf '%s\n' "$_b${_w}stand$_r — $_b${_w}One entry, Every agent.$_r"
            ;;
        *)  printf 'stand — One entry, Every agent.\n' ;;
    esac
}
# <<< BRAND-BANNER <<<

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
        local _port _stop_port_pids _stop_pf_pid _stop_pf_alive _stop_targets _stop_foreign _p _i _stop_left
        _port=$(stand_resolve_port "$@")
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
            echo "  TERM ignored, sending KILL to:$_stop_left" >&2
            kill -9 $_stop_left 2>/dev/null
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
    local _port _pp _i _targets _t _c _chain _cur _eng_in_tree _rc _jdk _jar
    _port=$(stand_resolve_port "${IGN_PORT_ARGS[@]}")
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
    local _port="$1"
    case "$BRAND" in
        stand)   export STAND_GATEWAY_PORT="$_port"; export NEBFLOW_GATEWAY_PORT="$_port" ;;
        nebflow) export NEBFLOW_GATEWAY_PORT="$_port"; export NEBFLOW_HOME="$STAND_HOME" ;;
    esac
}

stand_cli_main() {
    local cli_cmd="${1:-}"
    case "$cli_cmd" in
        start)
            local _port _guard_pids _guard_pid
            _port=$(stand_resolve_port "$@")
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
                echo "       Stop it, or pick another port (--port / ${BRAND}_GATEWAY_PORT)." >&2
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
            local _detached=0 _dry=0 _fwd=() _a
            for _a in "$@"; do
                case "$_a" in
                    --detached) _detached=1 ;;
                    --dry-run)  _dry=1 ;;
                    *)          _fwd+=("$_a") ;;   # 其余一律透传 (--port/--force/--tree/run --inline…)
                esac
            done
            if [ "$_detached" = 0 ] && [ "$_dry" = 0 ]; then
                if [ "$BRAND" = "stand" ]; then
                    cli_boot "restart" "$@"   # bare 透传(逐字, 含 --force/--tree/run --inline): jar 侧 #916 detached apply 语义不变
                else
                    echo "nebflow (2026.9.19) jar has no restart command — use: nebflow restart --detached (or --dry-run 假点火验证)" >&2
                    exit 2
                fi
            fi
            [ "$_dry" = 1 ] && _RESTART_DRY=1
            # dry-run 隐含 detached (ppid=1 证据必须经孤儿化产生)
            cli_restart_arm "${_fwd[@]}"
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

cli_boot() { # $@ = 原样透传给 jar; banner 仅 stand + start
    local JAVA_BIN JAR
    cli_boot_prep JAVA_BIN JAR || exit 11
    if [ "$BRAND" = "stand" ] && [ "${1:-}" = "start" ]; then
        stand_banner_print
    fi
    exec "$JAVA_BIN" --add-opens java.base/java.lang=ALL-UNNAMED -jar "$JAR" "$@"
}
