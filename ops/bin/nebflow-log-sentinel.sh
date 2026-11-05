#!/bin/bash
# nebflow-log-sentinel.sh — 日志失控哨兵 v1（只读告警腿）
#
# 职责：为 NF_HOME（默认 ~/.nebflow）落一条「日志失控」告警腿，让日志病在潜伏期就被发现。
#   判据①（噪声型）     单文件 > NOISE_MIN_MB 且 distinct 行 = 1（文本、行数 ≥ NOISE_MIN_LINES）
#   判据②（无界增长型） 同一路径在 WINDOW_SEC 窗内增长 > GROWTH_MIN_MB
#
# 硬纪律：
#   * 只读被扫文件——零截断 / 零删除 / 零改名 / 零移动 / 零信号；命中只告警不处置（处置须作者裁决）。
#   * 自限：SLOG / 明细 / 状态 / 去重 均自带上限；单次全量扫描受 DEADLINE_SEC 约束。
#   * 接线方以 `bash nebflow-log-sentinel.sh || true` 调用；本脚本任何失败都不影响调用方判定与退出码。
#
# 退出码：0 = 完成（无论有无命中）；1 = 内部错误（已记 SLOG）。命中不改变退出码。
# 独立运行：bash nebflow-log-sentinel.sh [--force]     # --force 跳过节流，强制全量扫描
# 节流：距上次全量扫描 < INTERVAL_SEC 的调用直接早退（零新增行）。
#
# 排除面（来源：上游 logrunaway-sweep 实测增长基线表；标注「实测补充」「自身」者为本节点依据）：
#   [基线·既定轮转面·必须排除出速率轴] logs/nebflow.log、logs/nebflow.<date>.<n>.log、
#                                     logs/watchdog.log、logs/router/<date>_{sse,full,summary}.jsonl、
#                                     logs/tools/<date>.jsonl
#   [基线·硬排除]                      projects/、.git/、bin/*.jar、browser-data/、voice-models/、
#                                     docs/、mesh/
#   [实测补充]                         logs/router/objects/（实测 max=1.19 MB、0 件 >5 MB、p99=43 KB
#                                     ⇒ 数学上不可满足①②；其病灶是「文件数」型，不属本哨兵两条判据）
#   [自身]                             logs/log-sentinel*（哨兵自己的日志/状态/明细，防自噪）
#
# 已知边界（不得当已验证）：
#   * 扫描靠 `find -exec stat`，路径中含 `|` 或换行会破坏解析（本次实测面 0 例）。
#   * ①文本判定用「前 4 KB 无 NUL」启发式；① 要求行数 ≥ NOISE_MIN_LINES，故「无换行的单行巨块」
#     （如 sessions/*.json 单行快照）不告警——噪声病灶的签名是「同一行重复」，不是「单行很长」。
#   * ② 以「同一路径」为锚：窗口内新增的路径下一窗口才计增长；轮转/改名后的新名不计入旧锚。
#   * 节流早退不更新任何状态；状态只在全量扫描时落盘。
#
# v2（2026-11-05 运维修复批「watchdog ②：每轮先跑的全量 find」）——扫描面收窄：
#   缺陷（已核实）：改前 `find $SENTINEL_ROOT -type f -exec stat …` 扫全根 268,329 个文件 / 29 MB 输出，
#     实测 25–28s（热缓存；作者首轮冷缓存实测 367s）。而 DEADLINE_SEC 只约束判据①循环、管不到 find，
#     且哨兵排在健康探针**之前** ⇒ 每 30min 一次的长扫描把「宕机发现」推迟分钟级。
#   改法（三条，正交，都不牺牲告警面）：
#     ① **尺寸闸** SENTINEL_SCAN_MIN_MB（缺省 1 MB）：判据① 要 >10 MB、判据② 要窗口内 >5 MB 增量
#        ⇒ 不足 1 MB 的文件在任何窗口内都触发不了任一判据；文件一旦越过 1 MB 就进锚定表，
#        参与**下一窗口**的增长比较（不会漏报，只是把「首次可告警时刻」定在越过 1 MB 之后）。
#     ② **剪掉纯遍历成本目录** SENTINEL_SCAN_PRUNE_EXTRA（缺省 `history`）：实测 history/ = 19,646 个
#        目录 / 151,091 个文件 / 单文件最大 26 KB（VS Code 式本地历史快照树，与「日志失控」无涉），
#        剪掉它单项省 ≈6s。其余子树**一律保留**（不按目录猜测告警面）。
#     ③ **逃生口** SENTINEL_SCAN_FULL=1 ⇒ 逐字回到改前口径（全根、无尺寸闸、不剪 history），
#        供人工深扫/排查用；SLOG 的 `scope=` 字段记录本轮实际口径，收窄永远是可审计的。
#   注：本批同改了调用方（`ops/bin/nebflow-watchdog.sh`）的执行顺序——哨兵移到健康探针**之后**、
#   且只在健康轮跑，宕轮不再先付 find 的账。两处叠加后宕机发现延迟与 find 成本彻底解耦。

set -u
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

# ============================================================
# 常量（同名 env 可覆盖；默认值 = 生产口径）
# ============================================================
NF_HOME="${NF_HOME:-$HOME/.nebflow}"
SENTINEL_ROOT="${SENTINEL_ROOT:-$NF_HOME}"
SENTINEL_ROOT="${SENTINEL_ROOT%/}"
LOG_DIR="${SENTINEL_LOG_DIR:-$NF_HOME/logs}"

SLOG="$LOG_DIR/log-sentinel.log"
ALERTS_JSON="$LOG_DIR/log-sentinel-alerts.json"
ALERTS_TSV="$LOG_DIR/log-sentinel-alerts.tsv"
STATE_FILE="$LOG_DIR/log-sentinel-state.tsv"
DEDUPE_FILE="$LOG_DIR/log-sentinel-dedupe.tsv"
LAST_RUN_FILE="$LOG_DIR/log-sentinel-last-run"
WD_LOG="${SENTINEL_WDLOG:-$LOG_DIR/watchdog.log}"

INTERVAL_SEC="${SENTINEL_INTERVAL_SEC:-1800}"           # 节流窗：≥30 min 才做一次全量扫描
NOISE_MIN_MB="${SENTINEL_NOISE_MIN_MB:-10}"             # 判据① 体量阈
NOISE_MIN_LINES="${SENTINEL_NOISE_MIN_LINES:-100}"      # 判据① 最小行数（防「单行巨块」误报）
GROWTH_MIN_MB="${SENTINEL_GROWTH_MB:-5}"                # 判据② 增长阈
WINDOW_SEC="${SENTINEL_WINDOW_SEC:-86400}"              # 判据② 窗口（锚点最大年龄）
DEDUPE_SEC="${SENTINEL_DEDUPE_SEC:-86400}"              # 同一 (路径,判据) 去重窗
SLOG_MAX_LINES="${SENTINEL_SLOG_MAX_LINES:-500}"        # SLOG 自截断上限
ALERTS_KEEP="${SENTINEL_ALERTS_KEEP:-200}"              # 明细保留条数上限
MAX_ALERTS_PER_RUN="${SENTINEL_MAX_ALERTS_PER_RUN:-50}" # 单次扫描最多报几条新命中
DEADLINE_SEC="${SENTINEL_DEADLINE_SEC:-45}"             # 单次全量扫描自我时限
MAX_FILES="${SENTINEL_MAX_FILES:-500000}"               # 扫描文件数保险阀（超出则降级锚定）
SCAN_MIN_MB="${SENTINEL_SCAN_MIN_MB:-1}"                # v2 尺寸闸：只锚定 ≥ 该尺寸的文件（见头注释 v2 ①）
SCAN_PRUNE_EXTRA="${SENTINEL_SCAN_PRUNE_EXTRA:-history}" # v2 纯遍历成本目录，空格分隔（见头注释 v2 ②）
SCAN_FULL="${SENTINEL_SCAN_FULL:-0}"                    # 1 = 改前口径逃生口（见头注释 v2 ③）

# 目录级排除（相对 SENTINEL_ROOT 的整段路径；find -prune，不下降）
PRUNE_PATHS="projects .git browser-data voice-models docs mesh logs/router/objects"
# 文件级排除（awk 正则，作用于相对路径）
EXCL_REGEX='^logs/nebflow\.log(\.|$)|^logs/nebflow\.[0-9]{4}-[0-9]{2}-[0-9]{2}\..*\.log$|^logs/watchdog\.log$|^logs/router/[0-9]{4}-[0-9]{2}-[0-9]{2}_(sse|full|summary)\.jsonl$|^logs/tools/[0-9]{4}-[0-9]{2}-[0-9]{2}\.jsonl$|^logs/log-sentinel|\.jar$'

FORCE=0
for a in "$@"; do
  case "$a" in
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
  esac
done

# ============================================================
# 基础设施
# ============================================================
mkdir -p "$LOG_DIR" 2>/dev/null
now=$(date +%s)
slog() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$SLOG"; }
die()  { slog "ERROR $*"; exit 1; }

CAND_TMP=""; STATE_TMP=""; HITS_TMP=""; SCAN_RAW=""; AWK_META=""
cleanup() { rm -f "$CAND_TMP" "$STATE_TMP" "$HITS_TMP" "$SCAN_RAW" "$AWK_META" 2>/dev/null; }
trap cleanup EXIT

# ---- 节流（未到窗零新增行早退；--force 跳过） ----
if (( FORCE == 0 )); then
  last=$(stat -f %m "$LAST_RUN_FILE" 2>/dev/null || echo 0)
  (( now - last < INTERVAL_SEC )) && exit 0
fi

[[ -d "$SENTINEL_ROOT" ]] || die "扫描根不存在：$SENTINEL_ROOT"

# ---- SLOG 自截断（保留最近 SLOG_MAX_LINES 行；截断动作记一行） ----
truncate_slog() {
  [[ -f "$SLOG" ]] || return 0
  local n; n=$(wc -l < "$SLOG" 2>/dev/null | tr -d ' ')
  n="${n:-0}"
  if (( n > SLOG_MAX_LINES )); then
    local keep=$(( SLOG_MAX_LINES / 2 ))
    tail -n "$keep" "$SLOG" > "$SLOG.tmp.$$" 2>/dev/null && mv "$SLOG.tmp.$$" "$SLOG"
    # 注意（/bin/bash 3.2 词法坑，本机 launchd 用的就是它）：$var 紧跟多字节字符会被当成变量名的一部分
    # ⇒ 报 unbound variable 并中止。此串与全脚本一律用带花括号的 ${var} 收尾。
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] SLOG 自截断：${n} 行 → ${keep} 行（上限 ${SLOG_MAX_LINES}）" >> "$SLOG"
  fi
}

start_ts=$(date '+%Y-%m-%d %H:%M:%S')
start_sec=$(date +%s)

# ============================================================
# 全量扫描：find(-prune) → stat(size|mtime|path) → awk(排除面 + ①候选 + ②命中 + 新状态)
# ============================================================
CAND_TMP="$LOG_DIR/log-sentinel-cand.$$"
STATE_TMP="$LOG_DIR/log-sentinel-state.$$.tmp"
HITS_TMP="$LOG_DIR/log-sentinel-hits.$$"
SCAN_RAW="$LOG_DIR/log-sentinel-scan.$$.raw"
AWK_META="$LOG_DIR/log-sentinel-awkmeta.$$"

find_args=()
if (( SCAN_FULL == 1 )); then
  scope_tag="full"
  prune_list="$PRUNE_PATHS"
else
  scope_tag="gated(size>=${SCAN_MIN_MB}MB,prune=${SCAN_PRUNE_EXTRA:-none})"
  prune_list="$PRUNE_PATHS $SCAN_PRUNE_EXTRA"
fi
for p in $prune_list; do find_args+=( -path "$SENTINEL_ROOT/$p" -o ); done
find_args+=( -path "$SENTINEL_ROOT/__sentinel_never_match__" )

if (( SCAN_FULL == 1 )); then
  # 逃生口：改前口径逐字（全根、无尺寸闸）——仅供人工深扫，SLOG 记 scope=full
  find "$SENTINEL_ROOT" -xdev \
    \( "${find_args[@]}" \) -prune -o \
    -type f -exec stat -f '%z|%m|%N' {} + 2>/dev/null > "$SCAN_RAW"
  find_rc=$?
else
  # 缺省口径：同一次遍历 + 尺寸闸（见头注释 v2 ①：<1MB 的文件触发不了任何判据）
  find "$SENTINEL_ROOT" -xdev \
    \( "${find_args[@]}" \) -prune -o \
    -type f -size "+${SCAN_MIN_MB}M" -exec stat -f '%z|%m|%N' {} + 2>/dev/null > "$SCAN_RAW"
  find_rc=$?
fi
# 遍历期失败分级（v2）：活系统上 find 会因为「扫描面在遍历中变动」（文件被轮转/删除）返回非零，
#   改前一律 die ⇒ 整轮哨兵作废（不告警、不更锚）。改为：**拿去部分结果继续**（告警腿宁可有毛边也别整轮哑火），
#   只有「零结果 + 非零」才判真失败。真失败仍 die（不写坏状态、不动锚）。
if (( find_rc != 0 )); then
  if [[ -s "$SCAN_RAW" ]]; then
    slog "WARN find 非零退出 rc=${find_rc}（扫描面在遍历中变动，非致命）——按已得 $(( $(wc -l < "$SCAN_RAW" | tr -d ' ') )) 条继续，scope=${scope_tag}"
  else
    die "find 失败：${SENTINEL_ROOT}（rc=${find_rc}，零结果）"
  fi
fi

scanned=$(wc -l < "$SCAN_RAW" 2>/dev/null | tr -d ' '); scanned="${scanned:-0}"
degrade=0
(( scanned > MAX_FILES )) && degrade=1   # 保险阀：只锚定 ≥1 MB 的文件（降级记录在案）

state_in=/dev/null
[[ -f "$STATE_FILE" ]] && state_in="$STATE_FILE"

if ! LC_ALL=C awk -v excl="$EXCL_REGEX" \
    -v noisemin=$(( NOISE_MIN_MB * 1024 * 1024 )) \
    -v growmin=$(( GROWTH_MIN_MB * 1024 * 1024 )) \
    -v window="$WINDOW_SEC" -v now="$now" -v degrade="$degrade" \
    -v candout="$CAND_TMP" -v hitsout="$HITS_TMP" -v stateout="$STATE_TMP" \
    -v sfile="$state_in" -v rlen=$(( ${#SENTINEL_ROOT} + 2 )) '
  BEGIN { FS="|"; OFS="|"; anchor=0 }
  FILENAME == sfile {                         # 文件 1 = 上一份状态（注意：不能用 NR==FNR——
    if ($0 ~ /^#ANCHOR[|]/) { anchor = $2 + 0; next }   # 状态文件为空时 NR==FNR 对文件 2 恒真）
    if (NF >= 3) asize[$3] = $1 + 0
    next
  }
  {                                            # 文件 2 = 本次扫描
    size = $1 + 0; mtime = $2 + 0; path = $3
    rel = substr(path, rlen)
    if (rel ~ excl) next
    if (degrade == 1 && size < 1048576) next
    kept++
    if (size > noisemin) print path >> candout
    if (anchor > 0 && (now - anchor) <= window) winopen = 1
    if (winopen && (path in asize)) {
      d = size - asize[path]
      if (d > growmin) print "c2|" path "|" size "|" d >> hitsout
      print asize[path], mtime, path >> stateout
    } else {
      print size, mtime, path >> stateout
    }
  }
  END {
    if (winopen) print "#ANCHOR|" anchor >> stateout
    else         print "#ANCHOR|" now    >> stateout
    printf "%d %d\n", kept + 0, winopen + 0   # 元信息：本轮回执（仅读最后一行的第 1 字段）
  }
' "$state_in" "$SCAN_RAW" > "$AWK_META" 2>&1; then
  die "awk 主扫描失败：$(tail -1 "$AWK_META" 2>/dev/null)"
fi
awk_meta=$(tail -1 "$AWK_META" 2>/dev/null)
kept=$(printf '%s' "$awk_meta" | awk '{print $1+0}'); kept="${kept:-0}"
winflag=$(printf '%s' "$awk_meta" | awk '{print $2+0}'); winflag="${winflag:-0}"
rm -f "$SCAN_RAW" "$AWK_META"

# ---- 判据①：对候选逐个做「文本 + distinct 行」判定（只读） ----
is_text_head() {  # 前 4 KB 无 NUL ⇒ 视为文本
  local n; n=$(head -c 4096 "$1" 2>/dev/null | LC_ALL=C tr -d '\0' | wc -c | tr -d ' ')
  [[ "${n:-0}" -ge 4096 ]]
}
c1_checked=0; c1_skipped=0
if [[ -s "$CAND_TMP" ]]; then
  while IFS= read -r f; do
    [[ -f "$f" ]] || continue
    if (( $(date +%s) - start_sec > DEADLINE_SEC )); then
      c1_skipped=$(( c1_skipped + 1 )); continue
    fi
    is_text_head "$f" || continue
    # 只比较「已终止行」：写入方在途时尾部残片（无换行）不计入——否则真实失控日志的
    # 最后一笔半行会被算成第 2 种行形态而漏报（本夹具 `yes|head -c` 即此形态）。
    nt=$(wc -l < "$f" 2>/dev/null | tr -d ' '); nt="${nt:-0}"
    read -r verdict _ < <(LC_ALL=C awk -v n="$nt" 'NR==1{r=$0;next} NR>n{exit} $0!=r{m=1;exit} END{print (m?"MULTI":"SAME")}' "$f" 2>/dev/null)
    c1_checked=$(( c1_checked + 1 ))
    if [[ "${verdict:-MULTI}" == "SAME" ]] && (( nt >= NOISE_MIN_LINES )); then
      printf 'c1|%s|%s|0|%s\n' "$f" "$(stat -f %z "$f" 2>/dev/null || echo 0)" "$nt" >> "$HITS_TMP"
    fi
  done < "$CAND_TMP"
fi
cands=0; [[ -f "$CAND_TMP" ]] && cands=$(wc -l < "$CAND_TMP" | tr -d ' ')

# ---- 状态原子落盘 + DEDUPE 老化 ----
mv -f "$STATE_TMP" "$STATE_FILE" 2>/dev/null || die "状态写盘失败：$STATE_FILE"
if [[ -f "$DEDUPE_FILE" ]]; then
  LC_ALL=C awk -F'|' -v now="$now" -v win="$DEDUPE_SEC" 'NF>=2 && (now - $2) < win' "$DEDUPE_FILE" > "$DEDUPE_FILE.tmp.$$" 2>/dev/null \
    && mv -f "$DEDUPE_FILE.tmp.$$" "$DEDUPE_FILE"
fi

# ============================================================
# 命中处置：去重 → 明细 → watchdog 时间线 → 结构化日志
# ============================================================
total_hits=0; [[ -f "$HITS_TMP" ]] && total_hits=$(wc -l < "$HITS_TMP" | tr -d ' ')
new_hits=0; suppressed=0; reported=0; inline=""
if (( total_hits > 0 )); then
  while IFS='|' read -r crit path size delta extra; do
    [[ -n "${path:-}" ]] || continue
    last=0
    # 去重键 = (判据, 路径)；文件形态 crit|epoch|path（path 置末位，避免键内的 | 与分隔符混淆）
    [[ -f "$DEDUPE_FILE" ]] && last=$(LC_ALL=C awk -F'|' -v c="$crit" -v p="$path" '$1==c && $3==p {print $2}' "$DEDUPE_FILE" 2>/dev/null | tail -1)
    last="${last:-0}"
    if (( now - last < DEDUPE_SEC )); then
      suppressed=$(( suppressed + 1 )); continue
    fi
    new_hits=$(( new_hits + 1 ))
    printf '%s|%s|%s\n' "$crit" "$now" "$path" >> "$DEDUPE_FILE"
    if (( new_hits <= MAX_ALERTS_PER_RUN )); then
      printf '%s|%s|%s|%s|%s|%s\n' "$now" "$size" "$delta" "$crit" "${extra:-}" "$path" >> "$ALERTS_TSV"
      reported=$(( reported + 1 ))
      if (( reported <= 3 )); then inline="$inline ${crit}:${path}"; fi
    fi
  done < "$HITS_TMP"
fi

# ---- 明细自限（保留最近 ALERTS_KEEP 条） ----
alert_n=0
if [[ -f "$ALERTS_TSV" ]]; then
  alert_n=$(wc -l < "$ALERTS_TSV" 2>/dev/null | tr -d ' '); alert_n="${alert_n:-0}"
  if (( alert_n > ALERTS_KEEP )); then
    tail -n "$ALERTS_KEEP" "$ALERTS_TSV" > "$ALERTS_TSV.tmp.$$" 2>/dev/null && mv -f "$ALERTS_TSV.tmp.$$" "$ALERTS_TSV"
    alert_n=$ALERTS_KEEP
  fi
fi

# ---- 渲染 log-sentinel-alerts.json（始终良构，条数受 ALERTS_KEEP 约束） ----
if {
  printf '{\n'
  printf '  "updated": "%s",\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
  printf '  "root": "%s",\n' "$(printf '%s' "$SENTINEL_ROOT" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  printf '  "criteria": { "c1_noise": { "min_mb": %s, "min_lines": %s }, "c2_growth": { "min_mb": %s, "window_sec": %s } },\n' \
    "$NOISE_MIN_MB" "$NOISE_MIN_LINES" "$GROWTH_MIN_MB" "$WINDOW_SEC"
  printf '  "recorded": %s,\n' "$alert_n"
  printf '  "alerts": [\n'
  if [[ -f "$ALERTS_TSV" ]]; then
    LC_ALL=C awk -F'|' '
      { p = $6; gsub(/\\/, "\\\\", p); gsub(/"/, "\\\"", p)
        printf "%s    {\"epoch\": %s, \"crit\": \"%s\", \"path\": \"%s\", \"size\": %s, \"delta\": %s, \"lines\": %s}",
          (NR>1 ? ",\n" : ""), $1+0, $4, p, $2+0, $3+0, ($5==""?0:$5+0) }
      END { if (NR>0) printf "\n" }
    ' "$ALERTS_TSV"
  fi
  printf '  ]\n}\n'
} > "$ALERTS_JSON.tmp.$$" 2>/dev/null; then
  mv -f "$ALERTS_JSON.tmp.$$" "$ALERTS_JSON" 2>/dev/null
else
  rm -f "$ALERTS_JSON.tmp.$$" 2>/dev/null
fi

# ---- 命中时间线：向既有 watchdog.log 追加一行（仅新增命中时） ----
if (( new_hits > 0 )); then
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] [log-sentinel] 新增日志失控命中 ${new_hits} 条（另有 ${suppressed} 条在 ${DEDUPE_SEC}s 去重窗内未重复告警）：${inline}（明细 $(basename "$ALERTS_JSON")）" >> "$WD_LOG"
fi

end_sec=$(date +%s)
elapsed_s=$(( end_sec - start_sec ))

# ---- 结构化日志（写入后自截断） ----
slog "scan root=$SENTINEL_ROOT scope=$scope_tag files=$scanned kept=$kept thr=${INTERVAL_SEC}s window=${WINDOW_SEC}s win_open=$winflag degrade=$degrade cand1=$cands checked1=$c1_checked skipped1=$c1_skipped hits=${total_hits} new=${new_hits} suppressed=${suppressed} reported=${reported} elapsed_s=$elapsed_s start=$start_ts"
if (( total_hits > 0 )); then
  while IFS='|' read -r crit path size delta extra; do
    [[ -n "${path:-}" ]] || continue
    if [[ "$crit" == "c1" ]]; then
      slog "HIT c1 noise path=$path size=$size lines=${extra:-0}"
    elif [[ "$crit" == "c2" ]]; then
      slog "HIT c2 growth path=$path size=$size delta=$delta"
    fi
  done < "$HITS_TMP"
fi
if (( c1_skipped > 0 )); then slog "WARN 判据① 因 DEADLINE_SEC=${DEADLINE_SEC}s 到达跳过 ${c1_skipped} 个候选"; fi
if (( degrade == 1 )); then slog "WARN 文件数 ${scanned} > MAX_FILES=${MAX_FILES} ⇒ 降级：仅锚定 ≥1MB 文件"; fi
if (( total_hits > MAX_ALERTS_PER_RUN )); then slog "WARN 命中 ${total_hits} > MAX_ALERTS_PER_RUN=${MAX_ALERTS_PER_RUN} ⇒ 明细只落前 ${MAX_ALERTS_PER_RUN} 条"; fi
truncate_slog

touch "$LAST_RUN_FILE" 2>/dev/null
exit 0
