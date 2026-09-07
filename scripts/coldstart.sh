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
#
# 用法：
#   ./scripts/coldstart.sh                                            # 真实冷启动
#   DRY_RUN=1 ./scripts/coldstart.sh                                  # 只预览，不启动 sbt
#   COLDSTART_PORT=<port> COLDSTART_HOME=<path> ./scripts/coldstart.sh  # 覆盖默认
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
