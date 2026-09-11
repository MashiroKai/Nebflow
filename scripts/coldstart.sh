#!/usr/bin/env bash
# coldstart.sh — Nebflow 冷启动脚本
#
# 用途：先清理旧残留（占端口的旧实例 + 旧 data home），再启动一个全新的冷启动实例。
# 解决原命令 `sbt "run --home /tmp/nebflow-coldstart --port 8097"` 的路径/端口被脏
# （旧 sessions / 注册 / 锁 残留导致"不是真正冷启动"）的问题。
#
# 安全设计（为何这样写）：
#   1) 逐 pid 验身才 kill —— 端口可能被无关进程占用（复用 8097 的其它服务/调试进程）。
#      只有 cmdline 含 `nebflow-coldstart` 特征（即本脚本自己起的实例）才允许 kill；
#      非特征进程一律拒绝启动并报 WARN，绝不盲杀（盲杀 = 杀错无辜进程 / 宿主）。
#   2) 绝不碰宿主 :8080 —— 脚本只对 $COLDSTART_PORT（默认 8097）做 lsof 定位，
#      且 COLDSTART_PORT 被误设为 8080 时直接拒绝执行：8080 是 Nebflow 宿主实例端口，
#      对它产生任何信号都会杀掉宿主与当前会话。本脚本逻辑面从头到尾只覆盖 $COLDSTART_PORT。
#   3) DRY_RUN=1 只预览不删不杀 —— 供作者先审行为（将 kill 谁 / 将清哪个 home）再实跑，
#      防止脚本在作者未 review 的情况下就动手删数据/杀进程。
#   4) 默认放行生产设备注册，但保留显式退出口 —— 本脚本的 data root 在 /tmp（非默认根），
#      会命中 NebLink 隔离护栏（src/main/scala/nebflow/neblink/EnrollGuard.scala：
#      非默认 data root ∧ 未显式放行 ∧ 目标 host ∈ 生产域 nebflow.space ⇒ 拒绝自动注册），
#      设备注册直接被拒。而冷启动实例的用途正是「以真实身份走通注册」，故脚本默认导出
#      NEBFLOW_ALLOW_PROD_ENROLL=1 放行。
#      代价：注册进的是【生产】NebLink https://neblink.nebflow.space（brand.conf 的
#      serverUrl 真值，也是 `neblinkServerUrl()` 的最终 fallback）——同一账号跑多实例会互踢：
#      后登录的实例上线时，旧实例被踢下线。
#      退出口：使用者已设 NEBFLOW_ALLOW_PROD_ENROLL（如 =0）时脚本一律原样保留、绝不覆盖
#      （取值用 ${NEBFLOW_ALLOW_PROD_ENROLL:-1}），护栏恢复生效、注册被拒。
#      真值口径与 Scala 端 EnrollGuard.explicitAllowEnv 逐字对齐：1 / true / yes（大小写不敏感）；
#      其它值（含 0 / false / 空）都等同未放行。
#
# 用法：
#   ./scripts/coldstart.sh                                            # 真实冷启动（默认放行生产注册）
#   DRY_RUN=1 ./scripts/coldstart.sh                                  # 只预览，不启动 sbt
#   COLDSTART_PORT=<port> COLDSTART_HOME=<path> ./scripts/coldstart.sh  # 覆盖默认
#   NEBFLOW_ALLOW_PROD_ENROLL=0 ./scripts/coldstart.sh                # 退出口：保留隔离护栏
#
set -euo pipefail

COLDSTART_PORT="${COLDSTART_PORT:-8097}"
COLDSTART_HOME="${COLDSTART_HOME:-/tmp/nebflow-coldstart}"
DRY_RUN="${DRY_RUN:-0}"
# 本脚本自起实例的 cmdline 特征标记（验身用）。
COLDSTART_MARKER="nebflow-coldstart"

# --- 安全红线①：绝不触碰宿主 :8080 ---
# 作者现行习惯默认端口为 8097；若被误设为 8080（宿主端口），在动手前直接拒绝。
if [ "$COLDSTART_PORT" = "8080" ]; then
  echo "[coldstart] ERROR: COLDSTART_PORT=8080 是运行中的 Nebflow 宿主实例端口，拒绝触碰。" >&2
  exit 1
fi

echo "[coldstart] port=$COLDSTART_PORT home=$COLDSTART_HOME dry_run=$DRY_RUN"

# ── 生产注册放行开关：默认放行 + 显式退出口（详见文件头 4)）──
# 仅当使用者未设或设为空时才默认导出 1；已设任何值一律原样保留、不覆盖其选择。
# set -u 已开 ⇒ 用 ${NEBFLOW_ALLOW_PROD_ENROLL:-1} 取默认值（:- 同时覆盖「已设但为空」）。
NEBFLOW_ALLOW_PROD_ENROLL="${NEBFLOW_ALLOW_PROD_ENROLL:-1}"
export NEBFLOW_ALLOW_PROD_ENROLL
# 提示语按与 EnrollGuard.explicitAllowEnv 相同的真值口径分支（1/true/yes，大小写不敏感），
# 避免「因 0/false 被拒注册」时还打印「将注册到生产」的错误预告。
# 两条分支都写出真实生产域名与互踢代价，供作者一眼看清本次行为。
case "$NEBFLOW_ALLOW_PROD_ENROLL" in
  1 | [tT][rR][uU][eE] | [yY][eE][sS])
    echo "[coldstart] NOTICE: 本次将把设备注册到【生产 NebLink】https://neblink.nebflow.space —— 同一账号多实例会互踢，后登录的实例上线时旧实例会被踢下线。"
    ;;
  *)
    echo "[coldstart] NOTICE: NEBFLOW_ALLOW_PROD_ENROLL=${NEBFLOW_ALLOW_PROD_ENROLL}（非 1/true/yes）→ 保留你的设置，隔离护栏生效：本实例不会注册到生产 NebLink https://neblink.nebflow.space（同一账号多实例的互踢风险随之消失）。"
    ;;
esac

# ── 步骤 1：清理占端口的旧实例（逐 pid 验身）──
# lsof -ti 找占 $COLDSTART_PORT 的 pid；无输出则说明端口空闲。
pids=$(lsof -tiTCP:"$COLDSTART_PORT" -sTCP:LISTEN 2>/dev/null || true)
if [ -z "$pids" ]; then
  echo "[coldstart] 端口 $COLDSTART_PORT 空闲，无旧实例残留。"
else
  echo "[coldstart] 检测到占用 $COLDSTART_PORT 的 pid(s): $(echo $pids | tr '\n' ' ')"
  # 两遍处理：先全量验身（任一非特征即整体拒绝），全部通过再 kill —— 避免部分误杀。
  feature_pids=""
  for pid in $pids; do
    # 读取 cmdline。ps 失败（沙箱受限 / 进程已消亡）则置空，视作"无法确认特征"→ 归入拒绝分支。
    cmdline=$(ps -p "$pid" -o command= 2>/dev/null || true)
    if echo "$cmdline" | grep -q "$COLDSTART_MARKER"; then
      echo "[coldstart]   pid=$pid 特征命中（${COLDSTART_MARKER}）→ 判定为本脚本旧实例，允许清理"
      feature_pids="$feature_pids $pid"
    else
      echo "[coldstart]   pid=$pid cmdline=『${cmdline:-<无法读取>}』不含特征 '${COLDSTART_MARKER}' → 非特征进程" >&2
      feature_pids=""   # 一旦发现非特征，整体拒绝。
      break
    fi
  done
  if [ -z "$feature_pids" ]; then
    echo "[coldstart] WARN: 端口 $COLDSTART_PORT 被非特征进程占用，拒绝启动（绝不盲杀）。" >&2
    echo "[coldstart] WARN: cmdline 不含 '${COLDSTART_MARKER}' 的进程不是本脚本实例，可能是其它服务/调试进程。" >&2
    echo "[coldstart] 请用 lsof -nP -iTCP:$COLDSTART_PORT -sTCP:LISTEN 检查占端进程，人工确认后释放，或改用 COLDSTART_PORT。" >&2
    exit 1
  fi
  # 全部通过：DRY_RUN 只预告不真杀；真实运行逐 pid kill + 等端口释放。
  for pid in $feature_pids; do
    if [ "$DRY_RUN" = "1" ]; then
      echo "[coldstart] [DRY_RUN] 将 kill 旧实例 pid=$pid"
    else
      echo "[coldstart] 正在 kill 旧实例 pid=$pid"
      kill "$pid" 2>/dev/null || true
    fi
  done
  if [ "$DRY_RUN" != "1" ]; then
    # 等待端口释放（最多约 5s，每 0.5s 复查一次）。
    for _ in $(seq 1 10); do
      if ! lsof -tiTCP:"$COLDSTART_PORT" -sTCP:LISTEN 2>/dev/null | grep -q .; then
        break
      fi
      sleep 0.5
    done
    echo "[coldstart] 旧实例清理完成。"
  fi
fi

# ── 步骤 2：清空旧 home，保证真·冷启动 ──
if [ "$DRY_RUN" = "1" ]; then
  echo "[coldstart] [DRY_RUN] 将 rm -rf 并重建 HOME=${COLDSTART_HOME}（清旧 sessions/注册/锁）"
else
  echo "[coldstart] 清空旧 home: $COLDSTART_HOME"
  rm -rf "$COLDSTART_HOME"
  mkdir -p "$COLDSTART_HOME"
  echo "[coldstart] home 已清空重建: $COLDSTART_HOME"
fi

# ── 步骤 3：启动全新实例（DRY_RUN 跳过）──
if [ "$DRY_RUN" = "1" ]; then
  echo "[coldstart] [DRY_RUN] 预览结束，未启动 sbt。"
  exit 0
fi

# exec 替换当前 shell。sbt run = Main 入口，--home / --port 正确解析（安全形态）。
# 注：禁止 `java nebflow.gateway.GatewayMain` 直启 —— 它带参即 fail-fast，且非用户入口。
exec sbt "run --home $COLDSTART_HOME --port $COLDSTART_PORT"
