#!/bin/bash
# stand-autostart.sh — authoritative copy (stand repo ops/stand-autostart.sh)
# 部署位置: ~/.stand/bin/stand-autostart.sh (cp + chmod +x; launchd/开机自启入口, 见 ops/README.md)
#
# 2026-11-03 作者令·双根分家批: 由 nebflow-autostart.sh 改名（git mv）——它是 stand 的
# 开机自启（6138 守卫），语义属 stand；旧名 ~/.stand/bin/nebflow-autostart.sh 留弃用垫片
# 转发（权威副本 ops/bin/nebflow-autostart-shim），launchd 现挂 com.nebflow.gateway 作业
# 仍指旧名，经垫片继续工作（改 plist 属 launchd 操作，须作者裁定）。
#
# v2 (2026-10-25): 从 main 树以 sbt run 启动（与 stand-restart.sh〔原 nebflow-restart.sh，2026-11-03 改名〕同源）——旧版经 bin/stand 起
# 封装 jar，OS 重启后会回退旧构建，修好的代码到不了用户眼前（2026-10-25 审计实证）。
# v2.1 (2026-10-25 入库批): REPO 路径参数化（STAND_REPO env 可覆写；live 副本同构）。
# 端口守卫（lsof 6138 listener 探测）与 ops/bin/stand 的 start 守卫同源——双启动断根。
# helper 改名批 2026-11-03: nebflow-setsid-exec.py → stand-setsid-exec.py（本工具属 stand）。
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
exec python3 "$HOME/.stand/bin/stand-setsid-exec.py" sbt "run --port 6138"
