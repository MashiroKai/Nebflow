#!/usr/bin/env bash
# 中断恢复验收台架 · 一键跑腿（9.19 版中断恢复批 · 2026-11-04）
#
# 用法:
#   bash reports-evidence/20261104_interrupt-recovery/run.sh V1            # 只跑 V1，日志落 red/V1.log
#   bash reports-evidence/20261104_interrupt-recovery/run.sh V1 green       # 修复后复跑：日志落 green/V1.log
#   bash reports-evidence/20261104_interrupt-recovery/run.sh all [green]    # 顺序跑 V1..V5
#
# 退出码 = sbt 的退出码（红 = 非 0）。日志同时打印到终端与 red/<leg>.log。
#
# 安全：只跑 munit spec（本 JVM 内），不启动引擎进程、不碰生产实例 pid 41715、
#      不调用 stand stop/restart。
set -uo pipefail

WT="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-70e8d8ab"
EVID="$WT/reports-evidence/interrupt-recovery__20261104_114731"
SPEC="nebflow.core.project.InterruptRecoveryLegsSpec"
LEG="${1:-}"
OUT="${2:-red}"   # 第二参 = 输出子目录（red = 改动前红证；green = 修复后绿证）

if [[ -z "$LEG" ]]; then
  echo "usage: bash run.sh V1|V2|V3|V4|V5|all" >&2
  exit 2
fi

mkdir -p "$EVID/$OUT"
cd "$WT" || exit 2

run_one() {
  local leg="$1"
  echo "=== [$leg] IR_LEG=$leg sbt \"testOnly $SPEC\" ===" | tee "$EVID/$OUT/$leg.log"
  IR_LEG="$leg" sbt -batch "testOnly $SPEC" 2>&1 | tee -a "$EVID/$OUT/$leg.log"
  local rc=${PIPESTATUS[0]}
  echo "=== [$leg] exit=$rc (日志: reports-evidence/20261104_interrupt-recovery/$OUT/$leg.log) ===" | tee -a "$EVID/$OUT/$leg.log"
  return "$rc"
}

case "$LEG" in
  all)
    rc=0
    for leg in V1 V2 V3 V4 V5; do
      run_one "$leg" || rc=$?
    done
    exit "$rc"
    ;;
  V1|V2|V3|V4|V5)
    run_one "$LEG"
    ;;
  *)
    echo "unknown leg '$LEG' (expect V1|V2|V3|V4|V5|all)" >&2
    exit 2
    ;;
esac
