#!/bin/bash
# nebflow-autostart.sh — authoritative copy (stand repo ops/nebflow-autostart.sh)
# 部署位置: ~/.stand/bin/nebflow-autostart.sh (cp + chmod +x; launchd/开机自启入口, 见 ops/README.md)
#
# v2 (2026-10-25): 从 main 树以 sbt run 启动（与 nebflow-restart.sh 同源）——旧版经 bin/stand 起
# 封装 jar，OS 重启后会回退旧构建，修好的代码到不了用户眼前（2026-10-25 审计实证）。
# v2.1 (2026-10-25 入库批): REPO 路径参数化（STAND_REPO env 可覆写；live 副本同构）。
# 端口守卫（lsof 6138 listener 探测）与 ops/bin/stand 的 start 守卫同源——双启动断根。
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
export NEBFLOW_GATEWAY_PORT=6138
REPO="${STAND_REPO:-/Users/kaiyu/Claude code/Stand}"
LOG="$HOME/.stand/logs/autostart.log"
PID="$(lsof -ti tcp:6138 -sTCP:LISTEN 2>/dev/null | head -n 1)"
if [ -n "$PID" ]; then
  echo "$(date '+%F %T') host already listening on 6138 (pid $PID) — nothing to do" >> "$LOG"
  exit 0
fi
cd "$REPO" || { echo "$(date '+%F %T') repo dir missing: $REPO" >> "$LOG"; exit 1; }
echo "$(date '+%F %T') no listener on 6138 — starting stand from main tree (sbt run)" >> "$LOG"
exec python3 "$HOME/.stand/bin/nebflow-setsid-exec.py" sbt "run --port 6138"
