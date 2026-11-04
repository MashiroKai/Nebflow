#!/bin/bash
# sbt-gate.test.sh — M1 闸的沙箱化自测（零真实 sbt、零副作用，只用假 sbt）
#
# 覆盖（作者验收口径）：
#   T1 串行闸生效: 3 个并发调用 → 同时在跑的假 sbt 数 **最大 1**，其余排队
#   T2 排队可见: 日志/告警含 waiting + acquired after Ns
#   T3 逃生口 SBT_GATE=off: 完全旁路（无锁、无堆帽）
#   T4 服务形态旁路: 命令含 run ⇒ 不占重活锁（长活 JVM 不堵死开发流）
#   T5 默认堆帽注入 / 显式 -Xmx 尊重用户值（override 记账）
#   T6 超时放行: 等待超 SBT_GATE_TIMEOUT_SECS 即醒目告警并继续（不锁死）
#
# 用法: bash ops/test/sbt-gate.test.sh   （全绿 exit 0）
set -u
GATE="$(cd "$(dirname "$0")/../bin" && pwd)/sbt-gate"
SB="$(mktemp -d /tmp/sbt-gate-test.XXXXXX)"
export SBT_GATE_LOG_DIR="$SB/logs" SBT_GATE_RUN_DIR="$SB/run" SBT_GATE_LOG="$SB/logs/gate.log"
mkdir -p "$SBT_GATE_LOG_DIR" "$SBT_GATE_RUN_DIR"
FAIL=0
ok()  { printf '  ✓ %s\n' "$1"; }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

# 假 sbt: 记并发峰值 + 打印收到的 SBT_OPTS，sleep 后退出
FAKE="$SB/fake-sbt"
cat >"$FAKE" <<'FAKEEOF'
#!/bin/bash
CNT="$SBT_GATE_TEST_COUNTER"
cur=$(mkdir "$CNT/on" 2>/dev/null && ls "$CNT/on" | wc -l | tr -d ' ' || echo 1)
mkdir -p "$CNT/on/$$" 2>/dev/null; cur=$(ls "$CNT/on" 2>/dev/null | wc -l | tr -d ' ')
echo "$(date +%s.%N) start $$ concurrency=$cur" >> "$SBT_GATE_TEST_TRACE"
echo "OPTS=${SBT_OPTS:-}" >> "$SBT_GATE_TEST_TRACE"
sleep "${SBT_GATE_TEST_SLEEP:-2}"
rmdir "$CNT/on/$$" 2>/dev/null
echo "$(date +%s.%N) end $$" >> "$SBT_GATE_TEST_TRACE"
FAKEEOF
chmod +x "$FAKE"

export SBT_GATE_REAL="$FAKE" SBT_GATE_TEST_COUNTER="$SB/cnt" SBT_GATE_TEST_TRACE="$SB/trace"
mkdir -p "$SB/cnt/on"

echo "[T1/T2] 3 个并发 sbt → 串行化（并发峰值应 = 1）"
: > "$SB/trace"
( "$GATE" compile & "$GATE" test & "$GATE" package & wait ) 2>"$SB/stderr.txt" >/dev/null
maxc=$(awk -F'concurrency=' '/start/ {print $2}' "$SB/trace" | sort -n | tail -1)
[ "$maxc" = "1" ] && ok "并发峰值 = 1（3 个调用全串行）" || bad "并发峰值 = ${maxc}（应为 1）"
grep -q "waiting" "$SB/stderr.txt" && ok "排队可见: 出现 waiting 行" || bad "排队可见: 未出现 waiting 行"
grep -q "acquired after" "$SB/stderr.txt" && ok "排队可见: 出现 acquired after Ns" || bad "未出现 acquired after Ns"
acq=$(grep -c "acquired after" "$SBT_GATE_LOG")
[ "$acq" -ge 3 ] && ok "闸日志记账 3 次获取（实际 ${acq}）" || bad "闸日志获取记录 ${acq} < 3"

echo "[T3] SBT_GATE=off → 完全旁路（无锁无堆帽）"
: > "$SB/trace"
( SBT_GATE=off "$GATE" compile & SBT_GATE=off "$GATE" compile & wait ) 2>"$SB/stderr3.txt" >/dev/null
maxc3=$(awk -F'concurrency=' '/start/ {print $2}' "$SB/trace" | sort -n | tail -1)
[ "${maxc3:-0}" -ge 2 ] && ok "旁路后并发 = ${maxc3}（不再串行）" || bad "旁路后并发 = ${maxc3:-0}（应 ≥ 2）"
grep -q "gate bypassed" "$SB/stderr3.txt" && ok "旁路有明示" || bad "旁路无明示"
: > "$SB/trace"; SBT_GATE=off "$GATE" compile >/dev/null 2>&1
grep -q "OPTS=-Xmx" "$SB/trace" && bad "旁路仍注入堆帽（不应）" || ok "旁路不注入堆帽"

echo "[T4] 服务形态（run）→ 不占重活锁"
: > "$SB/trace"
( "$GATE" "run --port 6999" & "$GATE" "run --port 6998" & wait ) 2>"$SB/stderr4.txt" >/dev/null
maxc4=$(awk -F'concurrency=' '/start/ {print $2}' "$SB/trace" | sort -n | tail -1)
[ "${maxc4:-0}" -ge 2 ] && ok "两个 sbt run 并行（并发 ${maxc4}，未被重活锁串行）" || bad "sbt run 被串行（并发 ${maxc4:-0}）"

echo "[T5] 堆帽: 默认注入 1500m / 显式 -Xmx 尊重用户值"
: > "$SB/trace"; "$GATE" compile >/dev/null 2>&1
grep -q "OPTS=.*-Xmx1500m" "$SB/trace" && ok "默认注入 -Xmx1500m" || bad "默认未注入 -Xmx1500m"
: > "$SB/trace"; "$GATE" -Xmx3g compile >/dev/null 2>&1
grep -q "OPTS=.*-Xmx1500m" "$SB/trace" && bad "显式 -Xmx3g 仍被覆盖成 1500m（不应）" || ok "显式 -Xmx3g 覆盖生效（尊重用户值）"

echo "[T6] 超时放行: 持锁者不退，等待方到点告警并继续"
SBT_GATE_TEST_SLEEP=8 "$GATE" compile >/dev/null 2>&1 &
holder=$!
sleep 0.5
t0=$(date +%s)
SBT_GATE_TIMEOUT_SECS=3 "$GATE" test >"$SB/stderr6.txt" 2>&1
elapsed=$(( $(date +%s) - t0 ))
wait $holder 2>/dev/null
[ "$elapsed" -ge 3 ] && [ "$elapsed" -le 7 ] && ok "等待 ${elapsed}s 后放行（超时 3s 语义）" || bad "等待 ${elapsed}s（预期 3~7s）"
grep -q "WITHOUT the gate" "$SB/stderr6.txt" && ok "放行有醒目告警" || bad "放行无告警"

echo
if [ "$FAIL" = "0" ]; then echo "ALL GREEN (6/6 tests)"; rm -rf "$SB"; exit 0; else echo "FAILED: ${FAIL} assertion(s); artifacts: $SB"; exit 1; fi
