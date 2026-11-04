#!/usr/bin/env bash
# log-noise-stats.sh —— 日志噪音量化脚本（日志降噪批 C 证据件，2026-11-04）
#
# 口径：给定若干日志文件（默认当日引擎日志），输出
#   ① 总行数 / 字节数 / 时间跨度 / MB/h 增长速率
#   ② 两条目标噪音家族的行数与占比
#   ③ 按小时分布（逐家族）
#   ④ 其它 INFO 家族 Top N（体检面，只列不改）
#
# 可复现：脚本只读文件，不碰进程；任意机器上 `bash log-noise-stats.sh <log...>` 即可重跑。
set -uo pipefail

FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
  FILES=("$HOME/.stand/logs/stand.log")
fi

FAMILIES=("presence sendData" "session-sync push")

printf '## files\n'
for f in "${FILES[@]}"; do
  printf '  %s  (%s bytes, %s lines)\n' "$f" "$(wc -c <"$f" | tr -d ' ')" "$(wc -l <"$f" | tr -d ' ')"
done

TOTAL=$(cat "${FILES[@]}" | wc -l | tr -d ' ')
BYTES=$(cat "${FILES[@]}" | wc -c | tr -d ' ')
FIRST=$(head -1 "${FILES[0]}" | cut -c1-23)
LAST=$(tail -1 "${FILES[${#FILES[@]}-1]}" | cut -c1-23)

printf '\n## totals\n'
printf '  total_lines = %s\n' "$TOTAL"
printf '  total_bytes = %s (%s MiB)\n' "$BYTES" "$(echo "scale=2; $BYTES/1048576" | bc)"
printf '  first_line_ts = %s\n' "$FIRST"
printf '  last_line_ts  = %s\n' "$LAST"
# 时间跨度（秒）与 MB/h —— 用 date 解析首末行时间戳（macOS: date -j -f）
S=$(date -j -f "%Y-%m-%d %H:%M:%S" "${FIRST}" +%s 2>/dev/null)
E=$(date -j -f "%Y-%m-%d %H:%M:%S" "${LAST}" +%s 2>/dev/null)
if [ -n "${S:-}" ] && [ -n "${E:-}" ] && [ "$E" -gt "$S" ]; then
  SPAN=$((E - S))
  printf '  span_seconds = %s (%.2f h)\n' "$SPAN" "$(echo "scale=4; $SPAN/3600" | bc)"
  printf '  growth_MB_per_h = %.2f\n' "$(echo "scale=4; ($BYTES/1048576)/($SPAN/3600)" | bc)"
  printf '  lines_per_h = %.0f\n' "$(echo "scale=2; $TOTAL/($SPAN/3600)" | bc)"
fi

printf '\n## target families\n'
# 精确口径（按 logger 前缀）：别的行（例如工具回显里出现同名字符串）不算进来
NOISE_SUM=0
for pat in 'INFO  nebflow\.neblink\.presence - presence sendData:' 'INFO  nebflow\.neblink\.session-sync - session-sync push:'; do
  N=$(cat "${FILES[@]}" | grep -cE "$pat")
  NOISE_SUM=$((NOISE_SUM + N))
  printf '  %-52s lines=%s   share=%.2f%%\n' "$pat" "$N" "$(echo "scale=4; 100*$N/$TOTAL" | bc)"
done
printf '  %-52s lines=%s   share=%.2f%%\n' "NOISE_TOTAL" "$NOISE_SUM" "$(echo "scale=4; 100*$NOISE_SUM/$TOTAL" | bc)"

printf '\n## hourly distribution (HH: count_total / presence / session-sync)\n'
HOURS=$(cat "${FILES[@]}" | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:' | cut -c12-13 | sort -u)
for h in $HOURS; do
  T=$(cat "${FILES[@]}" | grep -cE "^[0-9-]{10} ${h}:")
  P=$(cat "${FILES[@]}" | grep -E "^[0-9-]{10} ${h}:" | grep -c "presence sendData")
  SS=$(cat "${FILES[@]}" | grep -E "^[0-9-]{10} ${h}:" | grep -c "session-sync push")
  printf '  %s  total=%-7s presence=%-7s session-sync=%-7s\n' "$h" "$T" "$P" "$SS"
done

printf '\n## other INFO families (top 12, 归一化数字后聚合)\n'
cat "${FILES[@]}" \
  | grep -E "INFO  nebflow\." \
  | sed -E 's/^[0-9-]+ [0-9:.]+ \[[^]]*\] //' \
  | sed -E 's/[0-9]+/N/g' \
  | sed -E 's/: .*//' \
  | sort | uniq -c | sort -rn | head -12

printf '\n## other INFO message families (top 12, 首 70 字符)\n'
cat "${FILES[@]}" \
  | grep -E "INFO  nebflow\." \
  | sed -E 's/^[0-9-]+ [0-9:.]+ \[[^]]*\] //' \
  | sed -E 's/[0-9]+/N/g' \
  | cut -c1-70 \
  | sort | uniq -c | sort -rn | head -12
