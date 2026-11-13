#!/bin/bash
# nebflow-watchdog.sh — Tier-2 外部监督看门狗 v2（launchd 每 120s 调度）
# 职责链: HTTP 健康连续 N 次失败 → 【鉴别「失聪 vs 真死」】→
#           真死 ⇒ 编译预检 → 优雅重启（restart.sh --restart-if-idle，非 --force）→ 15s 后复验
#           失聪 ⇒ 只记 WARN 一行，跳过重启（fail 计数保留、限速照旧、绝不杀）
#         → 仍不健康或编译失败 → 外部 agent（claude headless）诊断修复
# 边界（刻意）: 只覆盖「实例死/HTTP 挂」——agent 层楔死但 HTTP 正常的场景归进程内机制
#   （TaskStuckWatcher + #31 Fix A）管辖，看门狗不碰（防误杀活跃系统）。
# 维护开关: touch "$NF_HOME/logs/watchdog-paused" → 暂停（用户手动维护实例/编辑本脚本时）
#           rm    "$NF_HOME/logs/watchdog-paused" → 恢复
#           （旗标路径跟 NF_HOME 走：缺省 ~/.nebflow，live 部署经 plist 设 NEBFLOW_WD_NF_HOME
#            （现为 ~/.stand）——touch 固定 ~/.nebflow 位对 live 部署无效。旗标带 TTL：
#            age > PAUSE_TTL_SECS ⇒ 视为 kill -9/OOM 残留，按非暂停路径继续，只记一行；
#            本脚本绝不删除/移动该旗标——创建与删除始终是 restart.sh 的职责：restart.sh 动作模式
#            入口 touch、EXIT trap rm。故「临时停看门狗」= touch 本旗标，「恢复」= 由 restart.sh 的
#            下一次动作或人工 rm 完成；本脚本只读它。）
# 日志哨兵: bin/nebflow-log-sentinel.sh（只读告警腿，纳入本脚本每轮巡检；自带 30min 节流与自限；
#           自身失败经 `|| true` 吞掉，绝不影响本脚本任何判定与退出码）
# 限速: 每小时最多 S1_PER_HOUR 次重启；外部 agent 修复冷却 6h（防循环烧订阅额度）
# 日志: $NF_HOME/logs/watchdog.log（缺省 ~/.nebflow；live 部署 NF_HOME=~/.stand）
#
# v2（2026-09-22，R-2 批「失聪误杀」根治）——两处行为变更 + 评估项：
#   ① Stage1 升级前鉴别「失聪 vs 真死」（本版新增，缺陷 a 的根治）：
#      0922 夜 8 圈实证：连接摄取面打满时 HTTP=000 但 JVM 活（失聪），旧版不可分辨 ⇒ Stage1 强杀 ⇒
#      每圈 TERM 杀光全部活会话（误杀）。新版判据（全只读 ps/lsof/stat）：
#        ① :$PORT 有 LISTEN 监听者 ∧ ② 该监听进程可经 ps 确证存活且 cmdline 像本实例
#        ∧ ③ $NF_HOME/logs/nebflow.log 近窗活性（age ≤ DEAF_LOG_FRESH_SECS）
#        ⇒ 判「失聪」⇒ 记 WARN + 跳过重启（绝不杀；fail 计数保留、限速照旧）。
#      非失聪（无监听 / 监听者非本实例 / 日志陈旧）⇒ 按原语义进 Stage1。
#      审慎三态：无法确证（ps/lsof 读取失败、cmdline 不可读、日志 mtime 缺失）或端口被**非本实例**
#      占用 ⇒ 一律「不动作 + WARN」上报等人工。这是**本批自立的取舍**（宁可漏救、绝不误杀）：旧版对
#      「端口被谁占」无概念，会把任何占用者当作自有实例处理。🔴 此处**不援引 restart.sh 先例**——
#      restart.sh 无「外来监听者拒绝动作」的护栏：它以 :$PORT 监听者为杀树 root（restart.sh:61），
#      外来占用下反而会把它当自有树终止；其 v4.1 C「kill 后必启动」（restart.sh:615-618）也明示
#      端口被外来进程占用时**仍进入启动分支**。故二者的口径不同，勿混为同源判据。
#   ② --force 收缩（缺陷 b）：Stage1 不再用 --force，改调 restart.sh 的**非 --force 入口**
#      --restart-if-idle（四信号空闲闸 + 同款 TERM 两段式优雅停机；四信号 BUSY ⇒ rc=3 不动作）。
#      Stage2 修复流 PROMPT 内仍保留 --force，理由与边界见本文件末注释。
#   ③ 阈值环境变量化（缺省 = 现值，生产不设 ⇒ 零变化）：NEBFLOW_WD_FAIL_THRESHOLD /
#      NEBFLOW_WD_S1_PER_HOUR；新增可配窗 NEBFLOW_WD_DEAF_LOG_FRESH_SECS（缺省 600s）。
#      既有夹具挂钩（2026-09-13 先例）NEBFLOW_WD_PORT / NEBFLOW_WD_NF_HOME / NEBFLOW_WD_PAUSE_TTL_SECS
#      语义不变；本版新增夹具挂钩 NEBFLOW_WD_COMPILE_CMD（缺省 `sbt -batch compile`，仅隔离夹具用）。
#
# v3（2026-11-05 运维修复批「watchdog 两处真缺陷」）——失聪探针生产失配根治：
#   缺陷（已核实）：v2 的判据③ 探单路径 $NF_HOME/logs/nebflow.log，而实例自 2026-10-18 起实际写
#     $NF_HOME/logs/stand.log（`lsof -p 91266` 实测 fd 114w）。旧路径 mtime 冻结在 2026-10-18
#     ⇒ age 恒 > DEAF_LOG_FRESH_SECS ⇒ **DEAF 恒不命中** ⇒ 「活着但 HTTP 不可达」被判 DEAD
#     直接进 Stage1 ⇒ v2 的「失聪误杀」根治在生产等于零。修法（三条，缺一不可）：
#     ① 日志候选**可配**：NEBFLOW_WD_LOG_CANDIDATES（冒号分隔，缺省 = stand.log:nebflow.log）；
#        plist 的 EnvironmentVariables 写死同一缺省值，避免下次换日志路径又静默失聪。
#     ② **多候选取最新**：同时探两个候选，用**最近 mtime 更新者**判新鲜度 ⇒ 陈旧的历史路径
#        不可能把新鲜的活性盖掉。
#     ③ **判据不可用 ⇒ 禁止据此判 DEAD**：所有候选都不存在/不可读时输出 NOJUDGE（= 判据不可用），
#        与 UNKNOWN/FOREIGN 同路「不动作 + WARN 等人工」——宁可不救，绝不误杀。
#     三态（DEAF / NOJUDGE / DEAD）各自落一行可读判定行，含**实际采用的日志路径与 age**。
#   注意：无监听者时仍直接判 DEAD（不需要日志判据，无「活着的东西」可误杀；保持 v2 语义）。

set -u
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

# 端口与数据目录：默认值与改前逐字等价；NEBFLOW_WD_* 仅供隔离夹具复核（生产不设 = 零变化）
PORT="${NEBFLOW_WD_PORT:-8080}"
NF_HOME="${NEBFLOW_WD_NF_HOME:-$HOME/.nebflow}"
LOG_DIR="$NF_HOME/logs"
WLOG="$LOG_DIR/watchdog.log"
LOCK="$LOG_DIR/watchdog.lock"
PAUSE_FLAG="$LOG_DIR/watchdog-paused"
FAILS_FILE="$LOG_DIR/wd-fails"
S1_LOG="$LOG_DIR/wd-stage1-history"      # 每行一个 epoch，用于每小时限速
S2_TS_FILE="$LOG_DIR/wd-stage2-ts"
RESTART_SH="$NF_HOME/bin/nebflow-restart.sh"
# Stage2 外部修复 agent 二进制。改前为硬编码字面量 ⇒ 隔离夹具下 Stage2 会**逃逸夹具直调真实 claude**
#（2026-09-22 R-2 批夹具实测踩中：4 次真实 headless 调用飞出夹具、覆写宿主 watchdog-repair-report.md）。
# 故与其余夹具挂钩同族加 NEBFLOW_WD_CLAUDE_BIN（缺省 = 原字面量 ⇒ 生产零变化；夹具置 stub 即可全隔离）。
CLAUDE_BIN="${NEBFLOW_WD_CLAUDE_BIN:-/opt/homebrew/bin/claude}"
# 编译预检 / Stage2 的工作目录。v3 起加 NEBFLOW_WD_REPO_DIR 夹具挂钩（缺省 = 原字面量 ⇒ 生产零变化）：
# 隔离夹具必须能在沙箱里跑完 Stage1/Stage2 而不 cd 进真实仓（否则「DEAD ⇒ 进 Stage1」这条断言
# 只能看日志、看不到 restart 桩是否真被调用）。
NEBFLOW_DIR="${NEBFLOW_WD_REPO_DIR:-$HOME/Claude code/Nebflow}"
HEALTH_URL="http://localhost:$PORT/api/sessions"
# 失聪鉴别用：实例日志活性（mtime）依据。v3 起为**多候选、冒号分隔、可配**（见头注释 v3 ①）。
# 缺省同时含当前真实日志 stand.log 与历史名 nebflow.log，取最近 mtime 更新者判新鲜度；
# 全部不可读 ⇒ 判据不可用（NOJUDGE）⇒ 不动作（绝不据此判 DEAD）。
LOG_CANDIDATES="${NEBFLOW_WD_LOG_CANDIDATES:-$LOG_DIR/stand.log:$LOG_DIR/nebflow.log}"
FAIL_THRESHOLD="${NEBFLOW_WD_FAIL_THRESHOLD:-3}"
S1_PER_HOUR="${NEBFLOW_WD_S1_PER_HOUR:-2}"
S2_COOLDOWN=21600
LOCK_STALE=2700   # 45min 锁视为陈旧
# 失聪鉴别窗（2026-09-22）：日志 mtime 年龄 ≤ 该值 ⇒ 视为「实例仍在写日志 = 活着但 HTTP 不可达」。
# 缺省 600s = 10min（任务书建议值）：远大于健康探针 10s 超时与一轮 120s 调度间隔，又不至于把
# 「几十分钟前就死透但日志停写」的实例误判为失聪。
DEAF_LOG_FRESH_SECS="${NEBFLOW_WD_DEAF_LOG_FRESH_SECS:-600}"
# 编译预检命令（仅隔离夹具挂钩：夹具置 `true` 以免真跑 sbt；生产不设 = 与改前逐字等价）
COMPILE_CMD="${NEBFLOW_WD_COMPILE_CMD:-sbt -batch compile}"
# 编译预检输出路径（同族夹具挂钩：改前硬编码 /tmp/wd-compile.log ⇒ 夹具下把宿主 /tmp 也写了、
# 并让 Stage2 诊断误读「0 字节 = 编译失败」）。缺省 = 原字面量 ⇒ 生产零变化。
COMPILE_LOG="${NEBFLOW_WD_COMPILE_LOG:-/tmp/wd-compile.log}"

# 暂停旗 TTL（2026-09-13 作者令）：restart.sh 靠 EXIT trap 成对 touch/rm 旗标（restart.sh:187），
#   kill -9 / OOM 绕过 EXIT trap ⇒ 旗标残留 ⇒ 健康检查与重启的安全网静默失效。故设年龄上限：
#   旗标 age > TTL ⇒ 视为过期（按「非暂停」路径继续，只记一行日志；本脚本不删/不移该旗标）。
# 默认 7200s = 2h，推导（全部取 restart.sh 实测常量，禁凭空取整）：
#   MAX_WAIT_MIN=60 min  ← wait-idle/canary 的空闲等待上限（restart.sh:114）
# + 动作尾段最坏 1367s ≈ 23 min ← PROC_WAIT_SECS 45 + TW_WAIT_SECS 45 + READY_IDLE_BONUS_CAP 1200
#                                + SPAWN_GRACE 30 + kill 路径 sleep 2+5+3（restart.sh:118,119,121,123,305,320,598）
# + 编译预检（canary/wait-idle 在持旗期内编译，restart.sh:713-724 / 761 起；无上限，观测分钟量级）
#   ⇒ 最坏合法持旗 ≈ 93 min；实测最长连续持旗 52 min（2026-09-03 23:46:13→09-04 00:36:14，watchdog.log）
#   ⇒ 取 ≈1.3× 余量上取整为 2h。
# 未覆盖：watch 模式 MAX_WAIT_MIN=1440 min（restart.sh:133）——本机零使用痕迹，见交付报告「作者待决项」。
PAUSE_TTL_SECS="${NEBFLOW_WD_PAUSE_TTL_SECS:-7200}"
# 日志失控哨兵（只读；节流早退 ~10ms，全量扫描分钟级且每 ≥30min 一次）
SENTINEL_BIN="$NF_HOME/bin/nebflow-log-sentinel.sh"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$WLOG"; }

# ---- 失聪鉴别（全只读：lsof / ps / stat；零信号、零写动作） ----
# 本实例 cmdline 指纹：restart.sh 以 :$PORT 监听者为杀树 root，故「监听者是不是本实例」用
# 与 StaleProcessGuard 同族的 marker 判据（nebflow / sbt / GatewayMain / nebflow.Main）。
instance_cmd_matches() {
  local cmd="$1"
  [[ "$cmd" == *nebflow* || "$cmd" == *sbt* || "$cmd" == *GatewayMain* || "$cmd" == *Nebflow* ]]
}
listener_pid() { lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1; }
# 最近日志活性（v3）：在 $LOG_CANDIDATES 里取 **mtime 最新** 的可读候选。
# stdout 成功时单行 `<age_sec>|<path>|<mtime>`；**无任何可读候选 ⇒ 返回 1**（调用方据此判「判据不可用」）。
# 只读 stat，零信号零写动作。
latest_log_activity() {
  local c mt best_mt=0 best_path="" now_s
  now_s=$(date +%s)
  local IFS=':'
  for c in $LOG_CANDIDATES; do
    [[ -n "$c" ]] || continue
    [[ -f "$c" && -r "$c" ]] || continue
    mt=$(stat -f %m "$c" 2>/dev/null) || continue
    [[ -n "$mt" ]] || continue
    if (( mt > best_mt )); then best_mt=$mt; best_path="$c"; fi
  done
  [[ -n "$best_path" ]] || return 1
  echo "$(( now_s - best_mt ))|${best_path}|${best_mt}"
  return 0
}
# 判定结果（stdout 单行 `<TOKEN>|<可读理由>`，理由进日志行）：
#   DEAF     = 监听在 + 本实例 + 日志新鲜        ⇒ 失聪（活着但 HTTP 不可达）
#   DEAD     = 无监听 / 监听在但日志陈旧          ⇒ 真死/半死 ⇒ 进 Stage1（优雅路径）
#   FOREIGN  = 端口被非本实例进程占用            ⇒ 不动作（绝不杀外来者）
#   UNKNOWN  = 无法确证（ps/lsof 读取失败、cmdline 不可读）⇒ 不动作
#   NOJUDGE  = 日志判据不可用（全部候选不存在/不可读）⇒ **禁止据此判 DEAD**，不动作
classify_instance_state() {
  local lpid lcmd ps_rc lf log_age log_path
  lpid=$(listener_pid)
  if [[ -z "$lpid" ]]; then echo "DEAD|端口 :$PORT 无监听者"; return 0; fi  # 无监听 = 真死（无活物可误杀）
  LC_ALL=C ps -p "$lpid" >/dev/null 2>&1; ps_rc=$?
  if (( ps_rc != 0 )); then echo "UNKNOWN|ps 无法确证 pid=$lpid 存活"; return 0; fi
  lcmd=$(LC_ALL=C ps -p "$lpid" -o command= 2>/dev/null)
  if [[ -z "$lcmd" ]]; then echo "UNKNOWN|pid=$lpid 的 cmdline 不可读"; return 0; fi
  if ! instance_cmd_matches "$lcmd"; then echo "FOREIGN|pid=$lpid 的 cmdline 不像本实例"; return 0; fi
  if ! lf=$(latest_log_activity); then echo "NOJUDGE|日志候选全部不存在或不可读：${LOG_CANDIDATES}"; return 0; fi
  log_age="${lf%%|*}"; log_path="${lf#*|}"; log_path="${log_path%%|*}"
  if (( log_age <= DEAF_LOG_FRESH_SECS )); then
    echo "DEAF|监听 pid=$lpid 在册且像本实例，日志最新写入者 ${log_path} age=${log_age}s ≤ ${DEAF_LOG_FRESH_SECS}s"
  else
    echo "DEAD|监听 pid=$lpid 在册但日志陈旧：最新写入者 ${log_path} age=${log_age}s > ${DEAF_LOG_FRESH_SECS}s"
  fi
  return 0
}

# ---- 防重叠锁（claude 修复可能跑很久，launchd 再次触发时跳过） ----
if [[ -d "$LOCK" ]]; then
  lock_age=$(( $(date +%s) - $(stat -f %m "$LOCK" 2>/dev/null || echo 0) ))
  if (( lock_age < LOCK_STALE )); then exit 0; fi
  log "锁陈旧 ${lock_age}s，接管"
  rm -rf "$LOCK"
fi
mkdir "$LOCK" 2>/dev/null || exit 0
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

mkdir -p "$LOG_DIR"

# ---- 维护暂停（旗标 TTL：过期旗视为 kill -9 残留，按非暂停路径继续） ----
if [[ -f "$PAUSE_FLAG" ]]; then
  pause_age=$(( $(date +%s) - $(stat -f %m "$PAUSE_FLAG" 2>/dev/null || echo 0) ))
  if (( pause_age <= PAUSE_TTL_SECS )); then
    log "暂停中（watchdog-paused 存在）——健康不管，不动作"
    echo 0 > "$FAILS_FILE"
    exit 0
  fi
  log "暂停旗已过期（age=${pause_age}s > TTL=${PAUSE_TTL_SECS}s）——恢复巡检"
fi

# ---- 健康检查（与 restart.sh 同探针口径） ----
# v3 顺序调整（2026-11-05 运维修复批 ②）：健康探针从「哨兵之后」提到**最前**，哨兵只在**健康轮**跑。
#   理由（实测）：哨兵的全量 find 改前 25–28s（热缓存）/ 367s（冷缓存首轮），而它排在探针之前 ⇒
#   每 30min 一次的长扫描把当轮宕机发现推迟分钟级（DEADLINE_SEC=45 只约束判据①循环，管不到 find）。
#   移序后「宕机发现延迟」只剩 curl 探针本身；宕轮不跑哨兵在语义上也正确：实例已死 ⇒ 没人写日志，
#   「日志失控」这条告警腿在宕轮没有观测对象（哨兵自身也不依赖本脚本的健康结论）。
#   哨兵自身失败一律 `|| true` 吞掉，绝不影响本脚本任何判定与退出码。
code=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "$HEALTH_URL" 2>/dev/null)
if [[ "$code" =~ ^(200|401|403)$ ]]; then
  fails=$(cat "$FAILS_FILE" 2>/dev/null || echo 0)
  (( fails > 0 )) && log "健康恢复 HTTP=${code}（此前连续失败 ${fails} 次已复位）"
  echo 0 > "$FAILS_FILE"
  # ---- 日志失控哨兵（只读告警腿；健康轮才跑，见上） ----
  if [[ -x "$SENTINEL_BIN" ]]; then
    NF_HOME="$NF_HOME" SENTINEL_ROOT="$NF_HOME" /bin/bash "$SENTINEL_BIN" >/dev/null 2>&1 || true
  fi
  exit 0
fi

fails=$(( $(cat "$FAILS_FILE" 2>/dev/null || echo 0) + 1 ))
echo "$fails" > "$FAILS_FILE"
log "健康检查失败 #$fails HTTP=${code:-timeout}"
if (( fails < FAIL_THRESHOLD )); then exit 0; fi

# ---- 达阈值：先鉴别「失聪 vs 真死」（本版新增；失聪 ⇒ 绝不杀） ----
# classify_instance_state 输出 `<TOKEN>|<可读理由>`：理由直接进日志行（三态各有独立一行，见头注释 v3 ③）
STATE_RAW=$(classify_instance_state)
STATE="${STATE_RAW%%|*}"
if [[ "$STATE_RAW" == "$STATE" ]]; then STATE_DETAIL=""; else STATE_DETAIL="${STATE_RAW#*|}"; fi
log "鉴别判据：DEAF/NOJUDGE/DEAD（日志候选=${LOG_CANDIDATES}，新鲜窗=${DEAF_LOG_FRESH_SECS}s）⇒ 本轮结论=${STATE}"
if [[ "$STATE" == "DEAF" ]]; then
  log "WARN 判「失聪」（HTTP=${code:-timeout} 但实例活着）：${STATE_DETAIL} ⇒ 跳过重启（绝不杀；fail 计数保留 #${fails}、限速照旧）"
  exit 0
fi
if [[ "$STATE" == "NOJUDGE" ]]; then
  _lp=$(listener_pid)
  log "WARN 判据不可用（${STATE_DETAIL}；监听 pid=${_lp:-none} 存活且 cmdline 匹配本实例）⇒ **禁止据此判 DEAD**，本轮不动作、绝不杀（等人工/下一轮）"
  exit 0
fi
if [[ "$STATE" == "FOREIGN" || "$STATE" == "UNKNOWN" ]]; then
  _lp=$(listener_pid)
  _lc=$(LC_ALL=C ps -p "${_lp:-0}" -o command= 2>/dev/null | head -c 120)
  log "WARN 端口 :$PORT 状态不可判（${STATE}：${STATE_DETAIL}，监听 pid=${_lp:-none} cmd=[${_lc}]）⇒ 不动作、绝不杀（等人工/下一轮）"
  exit 0
fi
log "鉴别结果=DEAD（真死：${STATE_DETAIL}）⇒ 进入 Stage1（优雅路径）"

# ---- 达阈值：升级 Stage 1（优雅重启） ----
now=$(date +%s)
recent=$(awk -v cut=$((now - 3600)) '$1 > cut' "$S1_LOG" 2>/dev/null | wc -l | tr -d ' ')
if (( recent >= S1_PER_HOUR )); then
  log "Stage1 限速（近 1h 已 ${recent} 次）——直接评估 Stage2"
else
  log "=== Stage1: 编译预检 + 优雅重启（restart.sh --restart-if-idle，非 --force）==="
  if (cd "$NEBFLOW_DIR" && $COMPILE_CMD > "$COMPILE_LOG" 2>&1); then
    log "编译通过，执行 restart.sh --restart-if-idle"
    bash "$RESTART_SH" --restart-if-idle >> "$WLOG" 2>&1
    s1_rc=$?
    # 限速预算记账口径（本版明确化，防重试热环）：rc=3（四信号 BUSY，restart.sh:783 分支）与
    # rc=4（final_idle_check 转忙，restart.sh:770 区域）两条路径都**发生在任何 kill/spawn 之前**
    # ⇒ 零动作 ⇒ 不计预算（否则 BUSY 空转吃光配额，反而把真需要重启的窗口挤掉；代价 = 下一轮
    # 重复一次增量编译预检）。其余退出码一律计预算（含失败码 1：do_restart 失败 ⇒ 仍按老口径
    # 限速 2/h，防「失败即重试」热环）。
    if (( s1_rc == 3 || s1_rc == 4 )); then
      log "restart.sh --restart-if-idle 退出码 ${s1_rc}（3=BUSY 四信号未空闲 / 4=终检转忙 ⇒ 按设计零动作、绝不杀）；本轮未消耗限速预算"
    else
      echo "$now" >> "$S1_LOG"
      log "restart.sh --restart-if-idle 退出码 ${s1_rc}（已动作/已尝试 ⇒ 计入近 1h 限速预算）"
    fi
    # 重启后验证（restart.sh 内部已等就绪，这里双检）
    sleep 15
    code2=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "$HEALTH_URL" 2>/dev/null)
    if [[ "$code2" =~ ^(200|401|403)$ ]]; then
      log "Stage1 成功：实例恢复 HTTP=${code2}"
      echo 0 > "$FAILS_FILE"
      exit 0
    fi
    log "Stage1 后仍不健康 HTTP=${code2:-timeout}"
  else
    log "编译失败（${COMPILE_LOG}）——代码坏，跳过重启进 Stage2"
  fi
fi

# ---- Stage 2: 外部 agent 修复（冷却 6h） ----
s2_last=$(cat "$S2_TS_FILE" 2>/dev/null || echo 0)
if (( now - s2_last < S2_COOLDOWN )); then
  log "Stage2 冷却中（上次 $(( (now - s2_last) / 60 )) 分钟前）——本轮放弃，等下一窗口"
  exit 0
fi
if [[ ! -x "$CLAUDE_BIN" ]]; then
  log "Stage2 不可用：$CLAUDE_BIN 不存在"
  exit 0
fi
log "=== Stage2: 外部 agent（claude headless）介入修复 ==="
echo "$now" > "$S2_TS_FILE"

PROMPT='Nebflow 实例宕机且自助重启失败，你是外部修复 agent（独立于 Nebflow 运行）。任务：
1. 诊断：tail -100 这些日志找根因——'"$COMPILE_LOG"'（编译输出）、/tmp/nebflow-sbt-restart.log（上次启动）、'"$HOME"'/.nebflow/logs/nebflow.log（实例日志尾部）、'"$HOME"'/.nebflow/logs/watchdog.log（看门狗时间线）
2. 修复：仓库在 "/Users/kaiyu/Claude code/Nebflow"（路径含空格）。做最小必要修复让 sbt compile 通过。通常是最近的 commit 引入的坏改动——可 git log -5 定位，必要时回滚该改动（不要 push）
3. 恢复：编译通过后执行 bash ~/.nebflow/bin/nebflow-restart.sh --force
4. 报告：无论成败，把诊断+改动摘要写到 ~/.nebflow/logs/watchdog-repair-report.md
铁律：只允许对 Nebflow 实例本身操作（restart.sh 已封装）；禁止 kill/pkill 其他任何进程；禁止 git push；禁止修改 ~/.nebflow 下 agents/teams/flows/skills 定义。30 分钟内修不完就写报告停止。'

cd "$NEBFLOW_DIR" && "$CLAUDE_BIN" -p "$PROMPT" --dangerously-skip-permissions >> "$WLOG" 2>&1
log "Stage2 claude 退出码 $?（报告见 watchdog-repair-report.md）"
code3=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "$HEALTH_URL" 2>/dev/null)
[[ "$code3" =~ ^(200|401|403) ]] && log "Stage2 后实例恢复 HTTP=${code3}" || log "Stage2 后仍不健康 HTTP=${code3:-timeout}——冷却 6h 后才会再试，期间请人工介入"

# ── Stage2 保留 --force 的理由与边界（2026-09-22 R-2 批申报）─────────────────────────
# 理由：Stage2 的前置已含 Stage1 失败（含优雅路径 BUSY/无效）+ 6h 冷却限频，属「自助恢复全败后的
#   末级手段」；此时拿不到「实例还活着」的诊断保证（真死/编译坏/优雅闸长期 BUSY 皆可能），若再要求
#   它先过空闲闸，会把「代码坏 + 有在途工作」的死局推给人工。
# 边界：① 仅在 Stage1 之后运行（不早于此）；② 6h 冷却（S2_COOLDOWN）限频，最多 4 次/日；
#   ③ PROMPT 内明确「只允许对 Nebflow 实例本身操作（restart.sh 已封装）、禁止 kill/pkill 其他进程」；
#   ④ 本版未改动 Stage2 的触发条件与冷却值（与改前逐字一致）。
