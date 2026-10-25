#!/bin/bash
# nebflow-restart.sh — Nebflow 开发实例自重启工具 v4.2（外部工具，不属于 Nebflow 源码）
# ⚠️ 调用方式: 必须以路径直接调用（例: ~/.nebflow/bin/nebflow-restart.sh --canary）。
#    禁止 source/eval 执行——source/eval 下 $0=shell 名，脚本内路径解析失效（v4.2 已加
#    规范位回退兜底，但直接调用始终是最可靠方式）。
# ⚠️ 运行安全铁律（2026-08-19 00:20 事故：验证 Ctrl+C 修复的 agent 对 ps 定位进程发信号，
#    误杀宿主 Nebflow，用户断连重启）:
#    - 本脚本是唯一允许对 Nebflow 主实例执行终止/重启的开发工具，且必须走四信号+canary 护栏
#    - 除本脚本外，任何场景（验证 shutdown/Ctrl+C、测试、排查）一律禁止对 sbt/java/nebflow
#      进程 kill/pkill/发信号——先走隔离 canary 实例（NEBFLOW_HOME 隔离 + 独立端口）
#    - 裸调用（无参数）= 只检测不动作（--check），任何重启动作都必须显式指定模式
# 安全设计（用户要求"绝不中断任何工作"）:
#   预检:   sbt compile 失败立即放弃，旧实例不动（绝不"杀了起不来"）
#   Canary: 隔离 NEBFLOW_HOME + 独立端口真实启动验证，通过才动主实例
#   空闲:   四信号合取（v3）——任一信号活跃即 BUSY:
#     S1 日志静默   ~/.nebflow/logs/nebflow.log ≥180s 无写入（LLM 思考期可能无日志，故仅作首道防线）
#     S2 Mail 队列  ~/.nebflow/sessions/*/mail-queue.json 全部为空/不存在（防 Mail 传递间隙误杀）
#     S3 子任务     ~/.nebflow/subagent-tasks/*/*.json 无 status=running（≥30min 未更新的 running 视为陈旧忽略并记日志）
#     S4 任务流转   ~/.nebflow/tasks/*/*.json 近 10min 无 mtime 变化（任务状态变化即写入）
#   观察期: 四信号首次全绿后进入 5 分钟观察窗，每 30s 复查，任一转红即重置重新等待
#   终检:   canary 预检耗时数分钟，重启前再查一次四信号，转红则放弃本次回到守望
# v4.0（2026-08-30，根因报告 docs/Nebflow/20260830_restart-script-stability.md）:
#   R2 杀后验证「进程死亡」而非「端口释放」: TERM 后按进程树快照轮询进程表直至全灭（≤45s），
#      超时对存活者 KILL 升级；进程全灭后再做 TIME_WAIT 排空轮询（与 SingleInstanceGuard 同语义的
#      严格 bind 探测，≤45s）才 spawn——杜绝半死实例（监听已关、进程活着）与 TW 撞 bind。
#   R3 启动等待自适应: 记录 sbt 子进程 pid，子进程死且端口未就绪 → 立即失败（不盲等）；
#      SBT_OUT 增长（编译活跃）→ 续期（硬上限 900s，活跃可延至 1200s）；放弃路径清扫进程树防孤儿。
#   R4 --force 保留编译预检: force 只跳过日志空闲等待，编译失败不杀旧实例；
#      --force --no-compile 为显式逃生舱（双重警告）。
#   R5 重启期间暂停 watchdog: 动作模式入口 touch ~/.nebflow/logs/watchdog-paused，
#      所有出口（成功/失败/中断）经 trap 自动删除。
#   R6 sbt 附带击杀收窄: 只杀 cwd 在本项目目录的 sbt/scala/java 进程，不再全机器扫杀。
#   R7 notify_nebula 的 sid 初始化（修 set -u 未绑定告警）。
# v4.1（2026-09-01，用户报告 + 日志实证 /tmp/nebflow-restart-force.log）:
#   A 脱离实例进程树: 脚本经 Nebula Bash 工具启动时是 JVM 的 ppid 后代（JVM→Bash 工具→bash→脚本），
#     TERM 实例 → JVM 关闭钩子/会话清理走 ProcessTree.killProcessTree（ProcessTree.scala:
#     ps ppid-walk + ProcessHandle descendants 按 PID 连坐，维度是进程树而非组/会话）→ 脚本被
#     连带杀死 → 「能关掉 Nebflow 但无法重启」。修复: 脚本处于实例树内时 daemonize——fork 后原进程
#     退出（子进程被 launchd 收养，ppid=1，脱离 ppid 树）+ setsid（新会话/新进程组）再 exec。
#     killProcessTree 是树维度，仅 setsid 不够（setsid 不改 ppid 链），必须 reparent；setsid 是双保险。
#   B line 401 unbound variable: bash 3.2 set -u 下 `local timeout="$1" deadline=$((...timeout...))`
#     同句跨引用取「外层作用域」的值（非本句已赋值）——外层无 timeout → unbound 崩溃。修: 声明与
#     赋值拆开 + 给默认值；wait_pids_dead/就绪等待同型潜伏缺陷一并拆开。
#   C kill 后必启动: 旧树按进程表验证消亡后，TIME_WAIT 排空失败/端口仍占不再「放弃退出」——
#     一律进入启动分支，不留「旧实例已死、新实例永不起」的空窗；新实例由 setsid 独立进程承载，
#     脚本后续崩/被杀不影响启动。
# v4.2（2026-09-09，canary 300s 未就绪事故 /tmp/nebflow-canary.log）:
#   A helper 解析去 $0 单点依赖: source/eval 执行时 $0=shell 名（实测 bash source/eval → $0=bash，
#     zsh eval → $0=zsh；zsh source 因 FUNCTION_ARGZERO 默认 on 反而安全）→ dirname "." →
#     BIN_DIR=cwd → canary spawn 的 python3 找不到 nebflow-setsid-exec.py（报错路径=主仓根）
#     → canary 永不监听，300s 空等后放弃。修复: BIN_DIR 下 helper 不齐时回退规范位
#     $NF_HOME/bin，两处都没有 → fail-fast 输出可行动指引；daemonize re-exec 的脚本路径
#     同步走 SELF_PATH（$0 失效时同样回退规范位）。
#   B AGENTS.md 隔离配方硬化: canary/最终 spawn 补 NEBFLOW_GATEWAY_PORT env 保险（fork-proof，
#     即使 argv 解析链断裂也不回落 8080）。
# 文案补注（2026-09-17，纯注释，零行为变更）: 两条易被误读的现状，提示口径在此对齐——
#   · 优雅停机: 本脚本对实例树发 TERM，实例走 graceful shutdown（GracefulInterruptHook: draining →
#     在途节点 running→interrupted、result 空、零 failed，重启后通常同轮续跑）——属正常停机形态，非故障。
#     这与「~91min 周期失联」（应用层连接数打满，非本脚本职责；取证见
#     .nebflow/tools/20260917_hang91-watch.sh）是两回事，勿混为脚本/重启故障。
#   · 宿主进程是一对: sbt-launch（父）→ java（子，:8080 监听者）。本脚本以 :8080 监听者为杀树 root，
#     父进程由 R6 窄域清扫（cwd 在本项目目录的 sbt/scala/java）兜住。
# 已知边界（v4.1）: daemonize 脱离后无控制终端——终端 Ctrl+C 不再送达（trap INT/TERM/HUP 仍响应
#   显式 kill）；Bash 工具管道可能在原进程退出后被关闭，建议启动时重定向输出到文件（如 nohup … > log 2>&1）。
# 测试开关: NEBFLOW_RESTART_PORT=<port> 改目标端口；NEBFLOW_RESTART_FORCE_DETACH=1 强制 daemonize；
#   NEBFLOW_RESTART_NO_NARROW_SBT=1 跳过「本项目残留 sbt/scala 窄域击杀」（隔离验证专用——
#   宿主实例 cwd 也在项目目录，隔离验证不允许触碰宿主；真实重启默认行为不变）。
# 用法:
#   nebflow-restart.sh [无参数]           等价 --check：仅检测四信号状态，不动作（安全默认）
#   nebflow-restart.sh --check            仅检测四信号状态，不动作
#   nebflow-restart.sh --canary           最稳路径（推荐）: 编译预检 → canary 验证 → 四信号+观察期 → 重启
#   nebflow-restart.sh --watch [-m M]     守望模式: 持续等待全 agent 完成后自动重启（默认最多 24h，可 -m 指定分钟）
#   nebflow-restart.sh --wait-idle [-m M] 等待四信号空闲+观察期后重启（默认最多 60 分钟）
#   nebflow-restart.sh --restart-if-idle 立即探测四信号：空闲则重启，忙则拒绝（exit 3，零动作）
#                                        （R2 回炉 2026-09-22 F1 接线：此前该旗标无解析臂 ⇒ 静默落入 --check）
#   nebflow-restart.sh [--idle-secs N]    空闲则重启（轻量场景，仍走四信号）
#   nebflow-restart.sh --force            强制重启: 跳过空闲检测，但保留编译预检（R4，编译失败不杀旧实例）
#   nebflow-restart.sh --force --no-compile  逃生舱: 连编译预检也跳过（双重警告，风险自负）
#   通用: --dry-run 全部预检验证后退出不重启
# 报告: 每次重启动作写 /tmp/nebflow-restart-last-run.txt（新实例可读回溯）

set -u
set -o pipefail

SELF_PID=$$
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"   # 辅助脚本（nebflow-daemonize.py / nebflow-setsid-exec.py）同目录

# 端口对调（作者令 2026-10-23 微信，9.19 重装自有根批收尾）: 默认改 6138——下次重启生效，
#   让出 8080 给 9.19（其自有根启动器 ~/.nebflow/bin/nebflow 缺省 8080）。旧行为可
#   NEBFLOW_RESTART_PORT=8080 注回（v4.1 注入口不变）；对调前 8080 活实例须由作者自行停机，
#   本脚本只处置 6138 目标端口，绝不触碰 8080 上的任何进程。
PORT="${NEBFLOW_RESTART_PORT:-6138}"       # v4.3: 默认 6138（端口对调落地）；v4.1: 端口可注入（隔离验证用）
CANARY_PORT=8081
CANARY_HOME="$HOME/.nebflow-canary"
NF_HOME="$HOME/.stand"   # 2026-10-23 对齐现役数据根（原值 ~/.nebflow 系 rebrand 前旧账，S2/S3/S4/notify 全指错家）
LOG="$NF_HOME/logs/nebflow.log"
NEBFLOW_DIR="$HOME/Claude code/Stand"   # 2026-10-23 仓目录更名对齐（旧 Nebflow 目录仍在，不修会从旧树编译起实例）
SBT_OUT="/tmp/nebflow-sbt-restart.log"
CANARY_OUT="/tmp/nebflow-canary.log"
WATCH_LOG="/tmp/nebflow-restart-watch.log"
REPORT="/tmp/nebflow-restart-last-run.txt"
PAUSE_FLAG="$NF_HOME/logs/watchdog-paused"   # R5: watchdog 维护暂停标志（机制已存在于 nebflow-watchdog.sh）

# v4.2 A: helper 解析去 $0 单点依赖。BIN_DIR 由 $0 派生——source/eval 下 $0=shell 名，
# BIN_DIR=cwd，helper 引用全断（2026-09-09 canary 事故根因）。helper 不齐时回退规范位
# $NF_HOME/bin；两处都没有 → fail-fast（在任何 spawn 之前断言，绝不带病跑 canary）。
NEBFLOW_BIN_DIR="$BIN_DIR"
SELF_PATH="$0"
if [[ ! -x "$NEBFLOW_BIN_DIR/nebflow-setsid-exec.py" || ! -x "$NEBFLOW_BIN_DIR/nebflow-daemonize.py" || ! -f "$SELF_PATH" ]]; then
  if [[ -x "$NF_HOME/bin/nebflow-setsid-exec.py" && -x "$NF_HOME/bin/nebflow-daemonize.py" && -f "$NF_HOME/bin/nebflow-restart.sh" ]]; then
    NEBFLOW_BIN_DIR="$NF_HOME/bin"
    SELF_PATH="$NF_HOME/bin/nebflow-restart.sh"
    echo "[helper] \$0 未解析出 helper 目录（source/eval 调用？BIN_DIR=${BIN_DIR}）——回退规范位 ${NEBFLOW_BIN_DIR}"
  else
    echo "✗ 找不到辅助脚本 nebflow-setsid-exec.py / nebflow-daemonize.py" >&2
    echo "  已尝试: $BIN_DIR 与 $NF_HOME/bin" >&2
    echo "  请以路径直接调用本脚本（例: $NF_HOME/bin/nebflow-restart.sh --canary），不要 source" >&2
    exit 1
  fi
fi
IDLE_SECS=180              # S1 日志静默阈值
TASK_MMIN=10               # S4 任务流转冷却（分钟）
STALE_RUN_MIN=30           # S3 running 陈旧豁免（分钟）
OBSERVE_SECS=300           # 观察窗时长
OBSERVE_STEP=30            # 观察窗复查步长
MODE="check"               # 默认只检测不动作（安全）; 重启必须显式: canary | watch | wait-idle | restart-if-idle | force
MAX_WAIT_MIN=60
DRY_RUN=0
NO_COMPILE=0               # R4 逃生舱: --force --no-compile 显式跳过编译预检
# R2/R3 时间预算（依据报告: macOS 2×MSL=30s；JVM 优雅关机常 >10s；热启动 ~20s；冷编译可达数分钟）
PROC_WAIT_SECS=45          # TERM 后进程表等待上限（超时 KILL 升级）
TW_WAIT_SECS=45            # TIME_WAIT 排空等待上限（2×MSL=30s + 余量）
READY_HARD_CAP=900         # 就绪等待硬上限（报告 P1: 300s 对冷编译不足）
READY_IDLE_BONUS_CAP=1200  # 编译持续活跃时的总上限
READY_NO_PROGRESS=120      # 输出停滞超过该秒数且达硬上限 → 放弃
SPAWN_GRACE=30             # sbt 启动宽限（进程瞬死但可能正在 detach/交接）
SBT_PID=""                 # R3: spawn 的 sbt 子进程
PID_SET=""                 # R2: 杀前进程树快照（放弃路径清扫用）
LAST_SNAPSHOT=""

args=("$@")
for (( i=0; i<${#args[@]}; i++ )); do
  case "${args[$i]}" in
    --check)      MODE="check" ;;
    --canary)     MODE="canary" ;;
    --watch)      MODE="watch"; MAX_WAIT_MIN=1440 ;;
    --force)      MODE="force" ;;
    --wait-idle)  MODE="wait-idle" ;;
    --restart-if-idle) MODE="restart-if-idle" ;;   # R2 回炉（2026-09-22 F1）：解析环补臂——此前旗标被静默吞掉、MODE 落回 check ⇒ watchdog Stage1 优雅入口不可达（case 臂 restart-if-idle 成死代码）
    --idle-secs)  IDLE_SECS="${args[$((i+1))]:-180}" ;;
    -m)           MAX_WAIT_MIN="${args[$((i+1))]:-60}" ;;
    --dry-run)    DRY_RUN=1 ;;
    --no-compile) NO_COMPILE=1 ;;
  esac
done

now_ts()  { date +%s; }
ts_str()  { date "+%Y-%m-%d %H:%M:%S"; }
log_age() { local t; t=$(stat -f %m "$LOG" 2>/dev/null) || echo 999999; echo $(( $(now_ts) - t )); }
pid_on_port() { lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1; }
ps_alive() { kill -0 "$1" 2>/dev/null; }

# ---------- 缺陷 A（v4.1 2026-09-01）：脱离 Nebflow 实例进程树/会话/进程组 ----------
# 仅当脚本处于实例进程树内时 daemonize（判定只查 ppid 树，不查会话/组相等——launchd 会话下
# 所有进程同会话，会误伤 watchdog 的同步调用：watchdog 依赖 bash 同步等待重启完成）。
in_instance_tree() {
  local port_pid me pp
  port_pid=$(pid_on_port "$PORT" 2>/dev/null)
  [[ -z "$port_pid" ]] && return 1
  me=$$
  while [[ -n "$me" && "$me" != "1" ]]; do
    [[ "$me" == "$port_pid" ]] && return 0
    pp=$(ps -o ppid= -p "$me" 2>/dev/null | tr -d ' ')
    [[ -z "$pp" || "$pp" == "$me" ]] && return 1
    me="$pp"
  done
  return 1
}
if [[ "$(ps -o ppid= -p $$ 2>/dev/null | tr -d ' ')" != "1" ]] \
   && { [[ "${NEBFLOW_RESTART_FORCE_DETACH:-0}" == "1" ]] || in_instance_tree; }; then
  # ppid==1（已被 launchd 收养）即已脱离实例树，不得再 daemonize——否则 FORCE_DETACH 下
  # 每次 re-exec 都满足条件 → exec 死循环（2026-09-01 隔离验证实测）
  echo "[detach] 脚本处于实例进程树内（或 FORCE_DETACH）——daemonize 脱离（fork→reparent ppid=1→setsid→exec）"
  exec python3 "$NEBFLOW_BIN_DIR/nebflow-daemonize.py" "$SELF_PATH" "$@"
fi
trap '' PIPE   # daemonize 后 Bash 工具管道可能已关闭：忽略 SIGPIPE，防「首个 echo 即死」

# R5: 动作模式入口——暂停 watchdog（watchdog.sh 每轮检查该标志，见其注释「维护开关」）。
# 所有出口经下方 EXIT trap 自动删除；绝不在杀树之后才 touch（防止竞态窗口）。
begin_action_mode() {
  mkdir -p "$NF_HOME/logs"
  touch "$PAUSE_FLAG"
  echo "[watchdog] 已暂停 watchdog（touch ${PAUSE_FLAG}；脚本退出时经 trap 自动恢复）"
}
cleanup_on_exit() {
  if [[ -f "$PAUSE_FLAG" ]]; then
    rm -f "$PAUSE_FLAG"
    echo "[watchdog] 已恢复 watchdog（删除 ${PAUSE_FLAG}）"
  fi
}
trap cleanup_on_exit EXIT
trap 'echo "[signal] 收到中断信号，清理退出"; exit 130' INT TERM HUP

# ---------- 四信号检测（返回 0=全空闲，1=忙；输出诊断到 stdout） ----------
sig_log()   { local a; a=$(log_age); if (( a >= IDLE_SECS )); then echo "S1:log GREEN(${a}s)"; return 0; else echo "S1:log RED(${a}s)"; return 1; fi; }
sig_mail()  {
  local f busy=0
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    # 内容非 "[]" 视为有待投递
    if ! grep -q '^\[\]$' "$f" 2>/dev/null; then
      if [[ $(stat -f %z "$f" 2>/dev/null || echo 0) -gt 4 ]]; then
        echo "S2:mail RED(${f#$NF_HOME/sessions/})"; busy=1; return 1
      fi
    fi
  done < <(find "$NF_HOME/sessions" -name "mail-queue.json" -type f 2>/dev/null)
  echo "S2:mail GREEN(队列全空)"; [[ $busy == 0 ]]
}
sig_sub()   {
  local f st m age stale=0 running=0
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    st=$(grep -o '"status"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -1 | grep -o '"[^"]*"$' | tr -d '"')
    [[ "$st" == "running" ]] || continue
    m=$(stat -f %m "$f" 2>/dev/null) || continue
    age=$(( $(now_ts) - m ))
    if (( age > STALE_RUN_MIN * 60 )); then
      stale=$((stale+1))   # 陈旧 running：subagent 状态 bug 或真死任务，忽略但记数
    else
      running=$((running+1))
      echo "S3:sub RED(${f#$NF_HOME/subagent-tasks/} ${age}s)"; return 1
    fi
  done < <(find "$NF_HOME/subagent-tasks" -mindepth 2 -name "*.json" -type f 2>/dev/null)
  echo "S3:sub GREEN(running=0 陈旧忽略=${stale})"; [[ $running == 0 ]]
}
sig_tasks() {
  local n
  n=$(find "$NF_HOME/tasks" -name "*.json" -type f -mmin -${TASK_MMIN} 2>/dev/null | grep -v highwatermark | wc -l | tr -d ' ')
  if [[ "$n" == "0" ]]; then echo "S4:tasks GREEN(${TASK_MMIN}min 无流转)"; return 0
  else echo "S4:tasks RED(近${TASK_MMIN}min 有 ${n} 个任务文件更新)"; return 1; fi
}

is_all_idle() {
  local d1 d2 d3 d4 ok=0
  d1=$(sig_log)  || ok=1
  d2=$(sig_mail) || ok=1
  d3=$(sig_sub)  || ok=1
  d4=$(sig_tasks)|| ok=1
  echo "$d1 | $d2 | $d3 | $d4"
  return $ok
}

status_line() {
  local age pid wd
  age=$(log_age); pid=$(pid_on_port $PORT)
  wd=$( [[ -f "$PAUSE_FLAG" ]] && echo "watchdog=暂停中" || echo "watchdog=守望中" )
  echo "$(ts_str) log_age=${age}s port_pid=${pid:-none} $( [[ -n "$pid" ]] && ps -p "$pid" -o etime= | xargs echo uptime= ) $wd"
}

# ---------- 观察期：连续 OBSERVE_SECS 全绿才算真空闲 ----------
observe_idle() {
  echo "[observe] 四信号全绿，进入 ${OBSERVE_SECS}s 观察窗（每 ${OBSERVE_STEP}s 复查，转红即重置）"
  local green_since=-1
  while :; do
    if is_all_idle > /dev/null; then
      if (( green_since < 0 )); then green_since=$(now_ts); fi
      if (( $(now_ts) - green_since >= OBSERVE_SECS )); then
        echo "[observe] ✓ 观察窗期满仍全绿"; return 0
      fi
    else
      if (( green_since >= 0 )); then echo "[observe] ✗ 信号转红，重置观察窗"; fi
      green_since=-1
    fi
    sleep $OBSERVE_STEP
  done
}

# ---------- 预检 1: 编译 ----------
preflight_compile() {
  echo "[preflight] sbt compile（编译不过绝不重启）…"
  cd "$NEBFLOW_DIR" || { echo "[preflight] ✗ 目录不存在"; exit 1; }
  if sbt -batch compile > /tmp/nebflow-preflight-compile.log 2>&1; then
    echo "[preflight] ✓ 编译通过"
  else
    echo "[preflight] ✗ 编译失败（见 /tmp/nebflow-preflight-compile.log）—— 保留旧实例，放弃重启"
    tail -5 /tmp/nebflow-preflight-compile.log
    exit 1
  fi
}

# ---------- 预检 2: canary 真实启动验证 ----------

# canary 回收时清理它 spawn 的 daemon（marker 文件驱动，只杀 canary 自己起的）。
# 背景（2026-08-24 ⑥ Dev Server 修复）：canary 用独立 NEBFLOW_HOME，daemon 状态
# 与主实例完全隔离（不共用 daemons.json/daemon-pids.json）；但若 canary home 残留
# 了 daemons.json（上次 canary 测试留下），canary 会以 autoStart 拉起与主实例同端口
# 的 dev server——canary JVM 被 KILL 后这些进程成为孤儿占着端口，主实例的 marker
# 看不到它们（不同 home），只能永远等待端口释放 = "Dev Server 无法打开"。这里按
# canary 自己的 marker 文件回收，TERM→KILL 两段式（与 ProcessTree.killProcessTree 同语义）。
kill_canary_daemons() {
  local mf="$CANARY_HOME/daemon-pids.json" pass pid
  [[ -f "$mf" ]] || return 0
  echo "[canary] 清理 canary spawn 的 daemon 残留（marker: ${mf}）"
  for pass in TERM KILL; do
    while read -r pid; do
      [[ -n "$pid" ]] || continue
      if [[ "$pass" == "TERM" ]]; then kill "$pid" 2>/dev/null; else kill -KILL "$pid" 2>/dev/null; fi
    done < <(python3 - "$mf" <<'PYEOF'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
for m in d.get("markers", []):
    if m.get("pid"):
        print(m["pid"])
PYEOF
)
    [[ "$pass" == "TERM" ]] && sleep 2
  done
  rm -f "$mf"
}

preflight_canary() {
  local cpid code started
  if [[ -n $(pid_on_port $CANARY_PORT) ]]; then
    echo "[canary] ✗ 端口 $CANARY_PORT 已被占用（$(pid_on_port $CANARY_PORT)），先清理再试"; exit 1
  fi
  mkdir -p "$CANARY_HOME"
  echo "[canary] 隔离实例启动: NEBFLOW_HOME=$CANARY_HOME port=${CANARY_PORT}（日志 ${CANARY_OUT}）"
  ( cd "$NEBFLOW_DIR" && NEBFLOW_HOME="$CANARY_HOME" NEBFLOW_GATEWAY_PORT="$CANARY_PORT" nohup python3 "$NEBFLOW_BIN_DIR/nebflow-setsid-exec.py" sbt -batch "run --port $CANARY_PORT" > "$CANARY_OUT" 2>&1 & )
  started=$(now_ts)
  for i in $(seq 1 60); do
    sleep 5
    cpid=$(pid_on_port $CANARY_PORT)
    if [[ -n "$cpid" ]]; then
      code=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "http://localhost:$CANARY_PORT/api/sessions" 2>/dev/null)
      if [[ "$code" =~ ^(200|401|403)$ ]]; then
        echo "[canary] ✓ 新代码可正常启动（${cpid}，$(( $(now_ts) - started ))s，API=${code}）—— 允许重启主实例"
        kill "$cpid" 2>/dev/null; sleep 3
        pgrep -f "\-\-port $CANARY_PORT" | xargs kill -KILL 2>/dev/null
        pgrep -f "nebflow-canary" >/dev/null 2>&1 && pkill -f "nebflow-canary" 2>/dev/null
        kill_canary_daemons
        echo "[canary] 提示: 若浏览器开着 localhost:8081 标签页请随手关闭（canary 已回收）"
        return 0
      fi
    fi
    (( i % 6 == 0 )) && echo "[canary] …等待启动 $(( $(now_ts) - started ))s"
  done
  echo "[canary] ✗ canary 300s 未就绪 —— 新代码启动有问题，保留旧实例，放弃重启（canary 日志 ${CANARY_OUT}）"
  tail -5 "$CANARY_OUT"
  pgrep -f "\-\-port $CANARY_PORT" | xargs kill -KILL 2>/dev/null
  kill_canary_daemons
  exit 1
}

# ---------- 重启 ----------
# 两段式进程树终止：phase1 TERM 全树 → 等待 → phase2 对存活后代 KILL。
# 2026-08-24 ⑥ 硬化：旧版只 TERM 树、KILL 仅根，TERM-ignoring 的 daemon 子进程
# （dev server 常见）会成为孤儿占住端口——新实例 autoStart 探到端口被占只能标记
# Stopped+portOpen 等端口释放（等不到）= "Dev Server 无法打开"。
kill_tree_term() {
  local pid="$1" kids
  kids=$(pgrep -P "$pid" 2>/dev/null)
  for k in $kids; do
    [[ "$k" == "$SELF_PID" ]] && continue   # 跳过脚本自身（及其整个后代链），否则杀主实例树会连带杀掉脚本，nohup sbt run 永不执行
    kill_tree_term "$k"
  done
  [[ "$pid" == "$SELF_PID" ]] || kill -TERM "$pid" 2>/dev/null
}
kill_tree_kill() {
  local pid="$1" kids
  kids=$(pgrep -P "$pid" 2>/dev/null)
  for k in $kids; do
    [[ "$k" == "$SELF_PID" ]] && continue
    kill_tree_kill "$k"
  done
  [[ "$pid" == "$SELF_PID" ]] || kill -KILL "$pid" 2>/dev/null
}

# ---------- R6: 本项目范围内的 sbt 附带击杀（替代旧版 pgrep -f sbt-launch 全机器扫杀） ----------
# 只返回命令行是 sbt/scala/java 且 cwd 在 $NEBFLOW_DIR 的进程（含 fork 出的无父链 JVM）。
# 其他项目的 sbt 会话一律不碰（旧版误杀样本：15455/16666）。
pids_of_cwd() {
  python3 - "$NEBFLOW_DIR" "$SELF_PID" <<'PYEOF'
import subprocess, sys
target = sys.argv[1].rstrip('/')   # lsof 输出的路径不带尾斜杠——等值比或「目录/」前缀比
self_pid = sys.argv[2]
try:
    out = subprocess.run(['lsof', '-Fn', '-d', 'cwd'],
                         capture_output=True, text=True).stdout
except Exception:
    sys.exit(0)
cand = []
pid = None
for line in out.splitlines():
    if line.startswith('p'):
        pid = line[1:]
    elif line.startswith('n') and pid:
        path = line[1:]
        if path == target or path.startswith(target + '/'):
            cand.append(pid)
        pid = None
if not cand:
    sys.exit(0)
# 类型过滤：只留 sbt/scala/java（含 launcher 脚本），不碰编辑器/终端等无关进程
try:
    psout = subprocess.run(['ps', '-ax', '-o', 'pid=,comm='],
                           capture_output=True, text=True).stdout
except Exception:
    sys.exit(0)
comms = {}
for line in psout.splitlines():
    parts = line.split(None, 1)
    if len(parts) == 2:
        comms[parts[0]] = parts[1].lower()
for p in cand:
    if p == self_pid:
        continue
    comm = comms.get(p, '')
    base = comm.rsplit('/', 1)[-1]
    if base.startswith(('sbt', 'scala', 'java')):
        print(p)
PYEOF
}

kill_narrow_sbt() {
  local sig="$1" p
  for p in $(pids_of_cwd); do
    ps_alive "$p" || continue
    if [[ "$sig" == "TERM" ]]; then
      kill "$p" 2>/dev/null && echo "[restart] 终止本项目残留 sbt/scala 进程 pid=${p}（cwd=${NEBFLOW_DIR}）"
    else
      kill -KILL "$p" 2>/dev/null && echo "[restart] KILL 本项目残留 sbt/scala 进程 pid=$p"
    fi
  done
}

# ---------- R2: 进程表验证（杀后验证「进程死亡」而非「端口释放」） ----------
# 等待一组 pid 全部退出；超时则 stdout 输出存活者并返回 1。
wait_pids_dead() {
  # v4.1: 声明与赋值拆开——bash 3.2 set -u 下 local 同句跨引用取「外层作用域」值，
  # 无外层同名变量即 unbound 崩溃（2026-09-01 line 401 同根因，此处为潜伏同型）
  local pids timeout deadline p live
  pids="$1"; timeout="$2"
  deadline=$(( $(now_ts) + timeout ))
  while :; do
    live=""
    for p in $pids; do ps_alive "$p" && live="$live $p"; done
    [[ -z "$live" ]] && return 0
    (( $(now_ts) >= deadline )) && { echo "$live"; return 1; }
    sleep 1
  done
}

# 快照 root 的全部后代（杀前调用才完整），存入 PID_SET，然后等待全灭。
wait_tree_dead() {
  local root="$1" timeout="$2" snap
  snap=$( { echo "$root"; ps -o ppid=,pid= -ax 2>/dev/null | awk -v r="$root" -v self="$SELF_PID" '
    { pp[$2]=$1; pids[++n]=$2 }
    END {
      for (i=1;i<=n;i++) {
        p=pids[i]; if (p==r || p==self) continue
        q=p; d=0
        while ((q in pp) && d<64) { if (pp[q]==r) { print p; break } q=pp[q]; d++ }
      }
    }'; } | sort -un )
  PID_SET="$snap"
  echo "[kill] 进程树快照（root=${root}，已排除脚本自身）: $(echo "$snap" | tr '\n' ' ')" >&2
  wait_pids_dead "$snap" "$timeout"
}

# R2: TIME_WAIT 排空探测——与 SingleInstanceGuard 同语义的严格 bind（SO_REUSEADDR off）。
# 严格 bind 对 TIME_WAIT 同样失败（macOS 2×MSL=30s）：杀后 30s 内 bind 失败 = TW 未排空，
# 必须等待而非直接 spawn（否则新实例自检撞 BindException 误报端口被占——本次事故直接根因）。
port_is_strict_free() {
  python3 - "$1" <<'PYEOF'
import socket, sys
port = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
try:
    s.bind(("0.0.0.0", port))
except OSError:
    sys.exit(1)
finally:
    s.close()
sys.exit(0)
PYEOF
}

wait_tw_drained() {
  # v4.1 2026-09-01（缺陷 B 根因）: 原 `local timeout="$1" deadline=$(( $(now_ts) + timeout ))` 在
  # bash 3.2 + set -u 下崩溃——local 同句跨引用取外层作用域值，外层无 timeout → line 401
  # `timeout: unbound variable`（日志实证: kill 旧实例成功后死于此，没走到启动）。
  # 修: 声明与赋值拆开 + 默认值，任何调用路径（含漏传参）都不崩。
  local timeout deadline t0
  timeout="${1:-$TW_WAIT_SECS}"
  deadline=$(( $(now_ts) + timeout ))
  t0=$(now_ts)
  while :; do
    if port_is_strict_free $PORT; then
      echo "[restart] ✓ 端口 $PORT 可严格 bind（无活占用且 TIME_WAIT 已排空，耗时 $(( $(now_ts) - t0 ))s）"
      return 0
    fi
    (( $(now_ts) >= deadline )) && return 1
    sleep 2
  done
}

# ---------- 重启完成通知 ----------
# 用户 2026-08-25 21:21 要求：重启完成后必须触发通知 Nebula（此前只写报告文件，
# 新实例里的 Nebula 不知道重启发生 → 16:27 Backend 失活 1h 无人发现）。
# 通道：POST /api/command + {type:"userMessage"}（WebSocketRoutes.handleUserText
#   → ImmediateInput 注入 Nebula），auth 用 ~/.nebflow/auth.json 的 Bearer token。
# 通知失败不阻塞重启（已成功，仅提示）。
notify_nebula() {
  local token content payload
  local sid=""   # R7: 显式初始化——旧版在 set -u 下有未绑定分支（watchdog.log 两次复现）
  token=$(cat "$NF_HOME/auth.json" 2>/dev/null | tr -d '"' | tr -d '[:space:]')
  if [[ -z "$token" ]]; then
    echo "[notify] ✗ auth.json 缺失，跳过通知"; return 1
  fi
  sid=$(curl -s -m 5 -H "Authorization: Bearer $token" "http://localhost:$PORT/api/sessions" 2>/dev/null \
        | python3 -c "import sys,json
try:
    d=json.load(sys.stdin)
    s=[x for x in d.get('sessions',[]) if x.get('agentName')=='Nebula']
    print(s[0]['id'] if s else '')
except Exception:
    print('')" 2>/dev/null)
  if [[ -z "$sid" ]]; then
    echo "[notify] ✗ 找不到 Nebula 会话（agentName=Nebula），跳过通知"; return 1
  fi
  content="【重启完成通知】新实例已就绪 pid=$(pid_on_port $PORT)（$(ts_str)）。请核查：① team 成员激活状态（重启以 TERM 触发优雅停机 draining：在途节点转 interrupted、零 failed，重启后多能同轮续跑，但不保证全部自动恢复——AgentControl list + 日志活动）② 本次合入的新代码验证 ③ 浏览器刷新重连。"
  payload=$(python3 -c "import json,sys; print(json.dumps({'type':'userMessage','sessionId':sys.argv[1],'content':sys.argv[2]}))" "$sid" "$content" 2>/dev/null)
  if curl -s -m 8 -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
      -d "$payload" "http://localhost:$PORT/api/command" >/dev/null 2>&1; then
    echo "[notify] ✓ 已通知 Nebula（session ${sid}）"
  else
    echo "[notify] ✗ 通知投递失败（重启本身已成功）"
  fi
}

# R3: 放弃路径——清扫未就绪的启动进程树（TERM→KILL 两段），绝不留孤儿。
# 背景：旧版放弃时不清理，慢编译场景 sbt 会继续编译并在数分钟后绑 8080，
# 与后续 watchdog Stage1 / 手动启动形成绑口竞态。
abandon_restart() {
  local reason="$1" p
  echo "[restart] ✗ ${reason}"
  if [[ -n "$SBT_PID" ]] && ps_alive "$SBT_PID"; then
    echo "[restart] 清扫未就绪的启动进程树 root=${SBT_PID}（TERM→KILL，防孤儿）"
    kill_tree_term "$SBT_PID"
    sleep 3
    ps_alive "$SBT_PID" && kill_tree_kill "$SBT_PID"
  fi
  for p in $PID_SET; do ps_alive "$p" && kill -KILL "$p" 2>/dev/null; done
  local occ; occ=$(pid_on_port $PORT)
  [[ -n "$occ" ]] && echo "[restart] ⚠ 端口 $PORT 当前被 pid=$occ 占用（非本脚本所启，不处置）"
  { echo "== nebflow-restart $(ts_str) =="; echo "结果: 失败（${reason}）"; } > "$REPORT"
  exit 1
}

do_restart() {
  local pid tree_live
  # 2026-10-23 端口对调腿: 杀旧=KILL_PORT(NEBFLOW_RESTART_KILL_PORT 注入，缺省同 PORT)，起新=PORT——一次重启完成 8080→6138 切换
  local KILL_PORT="${NEBFLOW_RESTART_KILL_PORT:-$PORT}"
  pid=$(pid_on_port $KILL_PORT)

  if [[ $DRY_RUN == 1 ]]; then
    echo "[dry-run] do_restart 演练（不杀任何进程、不启动任何实例）:"
    if [[ -z "$pid" ]]; then
      echo "[dry-run]   端口 $PORT 无监听进程 → 将窄域清扫本项目残留后直接 spawn"
    else
      echo "[dry-run]   将杀进程树 root=${pid}：TERM → 进程表验证全灭（≤${PROC_WAIT_SECS}s）→ 必要时 KILL 升级"
    fi
    echo "[dry-run]   R6 窄域清扫候选（cwd=$NEBFLOW_DIR 的 sbt/scala/java）: $(pids_of_cwd | tr '\n' ' ')"
    echo "[dry-run]   spawn 前将做 TIME_WAIT 排空探测（严格 bind，≤${TW_WAIT_SECS}s）"
    echo "[dry-run]   spawn 后自适应就绪等待：sbt 死即失败 / 编译活跃续期（硬上限 ${READY_HARD_CAP}s）/ 放弃即清扫"
    echo "[dry-run] ✓ 演练结束，未做任何动作"
    exit 0
  fi

  if [[ -z "$pid" ]]; then
    echo "[restart] 端口 $KILL_PORT 无监听进程（杀旧腿目标）"
    # R6: 无监听也可能有本项目残留（半死实例/上次重启的孤儿编译）——窄域清扫，范围限本项目目录
    local stray; stray=$(pids_of_cwd)
    if [[ -n "$stray" ]]; then
      echo "[restart] 发现本项目目录残留进程: $(echo "$stray" | tr '\n' ' ')——TERM"
      local p; for p in $stray; do kill "$p" 2>/dev/null; done
      if ! tree_live=$(wait_pids_dead "$stray" $PROC_WAIT_SECS); then
        echo "[restart] 残留进程 TERM 未退出，KILL:${tree_live}"
        for p in $tree_live; do kill -KILL "$p" 2>/dev/null; done
        if ! wait_pids_dead "$tree_live" 10 > /dev/null; then
          echo "[restart] ✗ 残留进程无法终止，放弃"
          { echo "== nebflow-restart $(ts_str) =="; echo "结果: 失败（残留进程无法终止:${tree_live}）"; } > "$REPORT"
          exit 1
        fi
      fi
      PID_SET="$stray"
      echo "[restart] ✓ 残留进程已全部退出"
    fi
  else
    echo "[restart] 杀进程树 root=$pid (TERM)"
    kill_tree_term "$pid"
    # v4.1: NEBFLOW_RESTART_NO_NARROW_SBT=1 为隔离验证测试开关（宿主 cwd 也在项目目录；默认行为不变）
    [[ "${NEBFLOW_RESTART_NO_NARROW_SBT:-0}" == "1" ]] || kill_narrow_sbt TERM
    # R2: 进程表验证——「端口释放 ≠ 实例死亡」（半死 JVM 监听已关、进程活着，旧版被骗 7 分钟）
    if ! tree_live=$(wait_tree_dead "$pid" $PROC_WAIT_SECS); then
      echo "[restart] TERM ${PROC_WAIT_SECS}s 后仍有存活:${tree_live} —— KILL 升级（含半死实例与 TERM-ignoring daemon）"
      kill_tree_kill "$pid"
      [[ "${NEBFLOW_RESTART_NO_NARROW_SBT:-0}" == "1" ]] || kill_narrow_sbt KILL
      local p; for p in $tree_live; do kill -KILL "$p" 2>/dev/null; done
      sleep 2
      if ! tree_live=$(wait_tree_dead "$pid" 10); then
        echo "[restart] ✗ KILL 后仍有进程存活:${tree_live}，放弃（绝不在旧实例存活时启动新实例）"
        { echo "== nebflow-restart $(ts_str) =="; echo "结果: 失败（旧进程树 KILL 后仍存活:${tree_live}）"; } > "$REPORT"
        exit 1
      fi
    fi
    echo "[restart] ✓ 旧实例进程树已全部消亡（进程表验证，非端口验证）"
  fi
  # v4.1 C（kill 后必启动）: 旧树已按进程表验证消亡，此处不再「放弃退出」——端口若仍被占
  # （外来进程 / TIME_WAIT 未排空）只会让新实例自检报端口被占，绝不留下「旧实例已死、新实例
  # 永不起」的空窗；新实例由 setsid 独立进程承载，脚本即使在此后崩溃/被杀，启动仍在进行。
  if [[ -n $(pid_on_port $KILL_PORT) ]]; then
    echo "[restart] ⚠ 端口 $KILL_PORT 仍被占用（$(pid_on_port $KILL_PORT)，旧树已消亡 → 外来进程）——仍进入启动分支"
  fi

  # R2: TIME_WAIT 排空——杀后 30s 内旧连接处于内核回收期，严格 bind 会失败；排空前绝不 spawn
  if ! wait_tw_drained $TW_WAIT_SECS; then
    echo "[restart] ⚠ 端口 $PORT 在 ${TW_WAIT_SECS}s 内未严格 bind（TIME_WAIT 未排空或外来占用）——仍进入启动分支"
  fi

  echo "[restart] 启动 sbt run --port $PORT （日志 ${SBT_OUT}）"
  cd "$NEBFLOW_DIR" || { echo "✗ 找不到 $NEBFLOW_DIR"; exit 1; }
  # v4.1 A: 新实例用 setsid 独立会话承载（macOS 无 setsid 命令，nebflow-setsid-exec.py 等价实现，
  # exec 后同 pid，$! 跟踪语义不变）——脚本退出/被杀后新实例继续运行，与脚本不同会话/进程组。
  NEBFLOW_GATEWAY_PORT="$PORT" nohup python3 "$NEBFLOW_BIN_DIR/nebflow-setsid-exec.py" sbt "run --port $PORT" > "$SBT_OUT" 2>&1 &
  SBT_PID=$!
  disown
  echo "[restart] sbt pid=${SBT_PID}（setsid 独立会话），进入自适应就绪等待（硬上限 ${READY_HARD_CAP}s，编译活跃可续期至 ${READY_IDLE_BONUS_CAP}s）"

  # R3: 自适应等待循环——每轮三查：
  #   ① 端口+API 就绪 → 成功
  #   ② sbt 子进程已死且端口未就绪 → 立即失败（旧版对尸体盲等 4 分 40 秒）
  #   ③ SBT_OUT 增长（编译活跃）→ 到期续期；输出停滞 + 达硬上限 → 放弃并清扫
  # v4.1: 声明与赋值拆开（同 wait_tw_drained 的 bash 3.2 set -u 同句跨引用崩溃，潜伏同型）
  local started deadline last_size last_progress code listener now2 cur_size
  started=$(now_ts)
  deadline=$(( started + READY_HARD_CAP ))
  last_size=0
  last_progress=$started
  sleep 3
  last_size=$(stat -f %z "$SBT_OUT" 2>/dev/null || echo 0)
  while :; do
    sleep 5
    now2=$(now_ts)
    listener=$(pid_on_port $PORT)
    if [[ -n "$listener" ]]; then
      code=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "http://localhost:$PORT/api/sessions" 2>/dev/null)
      if [[ "$code" =~ ^(200|401|403)$ ]]; then
        echo "[restart] ✓ 新实例就绪 pid=$listener 耗时 $(( now2 - started ))s（API=${code}）"
        printf '{"startedBy":"restart-script"}\n' > "${NEBFLOW_HOME:-$HOME/.nebflow}/.pid.meta" 2>/dev/null || true
        echo "[restart] 前端请刷新重连；会话持久化将自动恢复"
        { echo "== nebflow-restart $(ts_str) =="; echo "结果: 成功 pid=$listener API=${code}"; echo "四信号快照: ${LAST_SNAPSHOT:-n/a}"; } > "$REPORT"
        notify_nebula
        exit 0
      fi
    fi
    cur_size=$(stat -f %z "$SBT_OUT" 2>/dev/null || echo 0)
    if (( cur_size > last_size )); then last_size=$cur_size; last_progress=$now2; fi
    if ! ps_alive "$SBT_PID"; then
      if (( now2 - started < SPAWN_GRACE )); then continue; fi   # 启动宽限：瞬死可能是交接中
      sleep 3
      listener=$(pid_on_port $PORT)
      if [[ -n "$listener" ]]; then
        code=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "http://localhost:$PORT/api/sessions" 2>/dev/null)
        if [[ "$code" =~ ^(200|401|403)$ ]]; then
          echo "[restart] ✓ sbt 外壳已退出但服务健在（detached）pid=$listener 耗时 $(( $(now_ts) - started ))s（API=${code}）"
          { echo "== nebflow-restart $(ts_str) =="; echo "结果: 成功 pid=$listener API=${code}（sbt 外壳已退出）"; echo "四信号快照: ${LAST_SNAPSHOT:-n/a}"; } > "$REPORT"
          notify_nebula
          exit 0
        fi
      fi
      tail -5 "$SBT_OUT"
      abandon_restart "sbt 启动进程提前退出（存活约 $(( now2 - started ))s）且端口未就绪——立即失败，不盲等"
    fi
    if (( now2 >= deadline )); then
      if (( cur_size > 0 && now2 - last_progress < READY_NO_PROGRESS && now2 < started + READY_IDLE_BONUS_CAP )); then
        echo "[restart] 编译输出仍活跃（${cur_size}B），续期 60s（已等 $(( now2 - started ))s）"
        deadline=$(( now2 + 60 ))
      else
        tail -5 "$SBT_OUT"
        abandon_restart "等待新实例就绪超时（已等 $(( now2 - started ))s，输出停滞 $(( now2 - last_progress ))s）"
      fi
    fi
    (( (now2 - started) % 30 >= 0 && (now2 - started) % 30 < 5 )) && echo "[restart] …等待启动 $(( now2 - started ))s（sbt 存活，日志 ${cur_size}B）"
  done
}

# 终检：预检耗时数分钟，重启前四信号必须仍全绿（转红回守望）
final_idle_check() {
  LAST_SNAPSHOT=$(is_all_idle) || {
    echo "[final] ✗ 预检期间系统转忙，放弃本次重启：${LAST_SNAPSHOT}"
    return 1
  }
  echo "[final] ✓ 终检全绿：${LAST_SNAPSHOT}"
  return 0
}

wait_until_idle() {
  local deadline=$(( $(now_ts) + MAX_WAIT_MIN * 60 ))
  while (( $(now_ts) < deadline )); do
    if is_all_idle > /dev/null; then
      if observe_idle; then echo "[wait] ✓ 四信号+观察期全过"; return 0; fi
    else
      sleep 20
    fi
  done
  echo "[wait] ✗ ${MAX_WAIT_MIN} 分钟内未达全空闲，安全退出（未做任何动作）"; exit 2
}

case "$MODE" in
  check)
    status_line
    if is_all_idle; then echo "状态: IDLE（四信号全绿，可安全重启）"; else echo "状态: BUSY —— 见上方红色信号"; fi
    exit 0 ;;
  canary)
    begin_action_mode
    status_line
    preflight_compile
    preflight_canary
    if [[ $DRY_RUN == 1 ]]; then
      echo "[dry-run] 预检+canary 验证全过 —— 按约定不重启（去掉 --dry-run 执行完整流程）"
      exit 0
    fi
    wait_until_idle
    final_idle_check || exit 4
    do_restart ;;
  watch)
    begin_action_mode
    echo "[$(ts_str)] 守望模式启动（最多 ${MAX_WAIT_MIN} 分钟）—— 等待全部 agent 完成后自动重启" | tee -a "$WATCH_LOG"
    status_line | tee -a "$WATCH_LOG"
    preflight_compile 2>&1 | tee -a "$WATCH_LOG" || exit 1
    preflight_canary  2>&1 | tee -a "$WATCH_LOG" || exit 1
    if [[ $DRY_RUN == 1 ]]; then
      echo "[dry-run] 预检+canary 验证全过 —— 守望模式 dry-run 到此为止，不等待不重启" | tee -a "$WATCH_LOG"
      exit 0
    fi
    exec >> "$WATCH_LOG" 2>&1
    wait_until_idle
    final_idle_check || { echo "[$(ts_str)] 终检失败，10 分钟后重新守望"; sleep 600; exec "$0" --watch -m $MAX_WAIT_MIN; }
    echo "[$(ts_str)] 全部检查通过，执行重启"
    do_restart ;;
  force)
    begin_action_mode
    # R4: force 只跳过日志空闲等待，编译预检必须保留——「绝不杀了起不来」对 force 同样成立
    if [[ $DRY_RUN == 1 ]]; then
      echo "[dry-run] force 演练（只读：不编译、不杀、不启动）："
      do_restart
    fi
    if [[ $NO_COMPILE == 1 ]]; then
      echo "[force] ⚠⚠ --no-compile：显式跳过编译预检——若代码编译失败将「杀了起不来」，风险自负"
      echo "[force] ⚠⚠ 此逃生舱仅用于「实例已死且确信代码可编译」的紧急场景"
    else
      echo "[force] R4：保留编译预检（编译失败不杀旧实例；确需跳过请用 --force --no-compile）"
      preflight_compile
    fi
    do_restart ;;
  wait-idle)
    if [[ $DRY_RUN == 1 ]]; then
      status_line
      if is_all_idle; then echo "[dry-run] 四信号当前全绿（真实模式将进观察窗后重启）——按约定不动作"
      else echo "[dry-run] 四信号当前有红（真实模式将等待空闲）——按约定不动作"; fi
      exit 0
    fi
    begin_action_mode
    status_line; wait_until_idle
    final_idle_check || exit 4
    do_restart ;;
  restart-if-idle)
    if [[ $DRY_RUN == 1 ]]; then
      status_line
      if is_all_idle; then echo "[dry-run] 四信号当前全绿（真实模式将立即重启）——按约定不动作"
      else echo "[dry-run] 四信号当前有红（真实模式将拒绝重启）——按约定不动作"; fi
      exit 0
    fi
    begin_action_mode
    status_line
    if is_all_idle; then
      final_idle_check || exit 4
      do_restart
    else
      echo "BUSY — 不重启（--watch / --canary / --wait-idle / --force）"; exit 3
    fi ;;
esac
