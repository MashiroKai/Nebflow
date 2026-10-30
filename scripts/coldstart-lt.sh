#!/usr/bin/env bash
# coldstart-lt.sh — 本地测试便利批（作者 10-30 16:34 令）：一步拉起本地测试面
#
# 用途：作者本地开发测试的一步入口 —— 起引擎（nebflow-restart.sh / NEBFLOW_RESTART_PORT
#       体系）→ 浏览器开 localhost 面板 → 已登录态直达，全程【不走 mystand.space 官网登录】
#       （绕开 Logto 官网登录链）。「lt」= local test，与作者既有 browser-use 持久档案
#       ~/.stand/browser-use/profiles/coldstart-lt 同名同义。
#
# 基座（不新造平行体系，三个既有机制的直接编排）：
#   1) 引擎腿：复用 ops/nebflow-restart.sh（唯一被允许对主实例做终止/重启的开发工具，
#      自带编译预检 R4 / 四信号空闲 / canary 护栏）。端口未监听 ⇒ 实例已死，经
#      LT_RESTART_MODE（默认 --force：跳过空闲等待、保留编译预检——「绝杀了起不来」对
#      force 同样成立；引擎已死时无活会话可中断）拉起；端口已在监听 ⇒ 引擎活着，
#      【绝不重启】（活着的引擎可能正载着 delegate 会话，重启须由作者显式另行发起）。
#   2) 免登录腿：复用引擎既有本地鉴权旁路 —— POST /api/auth/console-ticket
#      （AuthAdminRoutes，BearerFileOnly：只认本地文件 token；session cookie / PAT 一律
#      拒），这是 coldstart.sh 回落腿与 LocalConsoleUrl.authenticated 的同一铸票面。
#      换票 ⇒ 303 ⇒ stand_session cookie（local-ticket origin 仅 loopback 腿生效，
#      GatewayAuth.localTicketOnLeg），之后 /ws + /api/* 全部面正常放行 —— 面板开出来
#      就是已登录态，官网/Logto 零接触。本脚本不新增任何鉴权旁路代码（零 Scala/JS 改动）。
#   3) 开门腿：macOS `open`（默认档案即可——本链路根本不经过官网域名，无静默 SSO 面；
#      与 coldstart.sh 的一次性档案诉求相反：那里要【制造】新用户态，这里要【复用】
#      作者日常浏览器态）。
#
# 安全红线：
#   - 目标只许 loopback：铸票/探测一律打 127.0.0.1 / localhost，本脚本不存在任何
#     可配置的外部主机面 ⇒ 「生产构建/官网域名零影响」由构造保证（在产零触碰）。
#   - 铸票零外泄：Bearer 只读本机 $LT_HOME/auth.json；票只进本机浏览器地址栏。
#     票 = 120s 一次性（verifyAndConsume 原子核销），重放即 invalid-ticket。
#   - 引擎活着绝不碰；引擎死了才委托 restart 脚本拉起，其全部安全护栏原样生效。
#   - DRY_RUN=1 只预览：不拉引擎、不铸票、不开浏览器。
#   - 绝不杀进程：本脚本无任何 kill 语义（隔离实例的回收属 scripts/coldstart.sh 辖区）。
#
# 用法：
#   ./scripts/coldstart-lt.sh                     # 一步：起引擎(若未跑) + 铸票 + 开浏览器
#   ./scripts/coldstart-lt.sh --no-open           # 只起引擎+铸票，打印已登录 URL 不开浏览器
#   DRY_RUN=1 ./scripts/coldstart-lt.sh           # 全预览（零动作）
#   LT_PORT=8199 LT_HOME=/tmp/xxx ./scripts/coldstart-lt.sh --no-open   # 指向隔离实例验证
#   LT_RESTART_MODE=--restart-if-idle ./scripts/coldstart-lt.sh          # 改重启档位（见下）
#
# 环境旋钮：
#   LT_PORT（默认 $NEBFLOW_RESTART_PORT │ 6138）— 与 restart 脚本同旋钮，保持「6138 体系」一致
#   LT_HOME（默认 $HOME/.stand）— 读 auth.json 文件 token 的主 home；指向隔离实例时随 home 改
#   LT_RESTART_SCRIPT — 显式指定 restart 脚本路径（缺省解析：~/.stand/bin → 仓内 ops/）
#   LT_RESTART_MODE（默认 --force）— 引擎未监听时委托 restart 脚本的档位；--restart-if-idle
#       更保守（忙则拒，exit 3 原样透传），--canary 最稳（编译+canary+等空闲，慢）
#   LT_OPEN_CMD — 覆盖开门命令（缺省 macOS `open` / Linux xdg-open；无则只打印 URL）
#
# 退出码：0 = 已开门（或已打印 URL）；1 = 失败（附可行动指引）；3 = restart 脚本判忙拒绝
#         （仅 --restart-if-idle 档位可能出现，原样透传）。

set -euo pipefail

LT_PORT="${LT_PORT:-${NEBFLOW_RESTART_PORT:-6138}}"
LT_HOME="${LT_HOME:-$HOME/.stand}"
DRY_RUN="${DRY_RUN:-0}"
LT_RESTART_MODE="${LT_RESTART_MODE:---force}"
NO_OPEN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --no-open) NO_OPEN=1; shift ;;
    --open)    NO_OPEN=0; shift ;;
    *)
      echo "[lt] ERROR: 未知参数 '$1'（本脚本只认 --no-open / --open；端口/home 走 LT_PORT/LT_HOME env）" >&2
      exit 2
      ;;
  esac
done

case "$LT_PORT" in
  ''|*[!0-9]*) echo "[lt] ERROR: LT_PORT 须为数字端口，得到 '$LT_PORT'" >&2; exit 2 ;;
esac
case "$LT_RESTART_MODE" in
  --force | --restart-if-idle | --canary | --wait-idle) : ;;
  *) echo "[lt] ERROR: LT_RESTART_MODE 须为 --force|--restart-if-idle|--canary|--wait-idle，得到 '$LT_RESTART_MODE'" >&2; exit 2 ;;
esac

echo "[lt] port=$LT_PORT home=$LT_HOME dry_run=$DRY_RUN restart_mode=$LT_RESTART_MODE no_open=$NO_OPEN"
echo "[lt] 链路: 引擎(nebflow-restart.sh $LT_RESTART_MODE) → 本地铸票(console-ticket, BearerFileOnly) → localhost 面板已登录态直达（零 mystand.space 接触）"

# ── 就绪探测（与 restart 脚本同口径：/api/sessions 返回 200/401/403 即视为服务活着）──
api_code() {
  curl -s -o /dev/null -w "%{http_code}" -m 3 "http://127.0.0.1:$LT_PORT/api/sessions" 2>/dev/null || true
}
probe_ready() {
  local tries="${1:-1}" i code
  for (( i=0; i<tries; i++ )); do
    code="$(api_code)"
    case "$code" in 200|401|403) return 0 ;; esac
    (( i+1 < tries )) && sleep 2
  done
  return 1
}

# ── 步骤 1：引擎腿 —— 活着绝不碰；死了才委托 restart 脚本 ──
listener_pid="$(lsof -nP -tiTCP:$LT_PORT -sTCP:LISTEN 2>/dev/null | head -1 || true)"
if [ -n "$listener_pid" ]; then
  echo "[lt] 引擎已在跑（pid=${listener_pid}，:${LT_PORT}）→ 跳过启动腿（活着的引擎绝不重启；要换代码请显式走 nebflow-restart.sh）"
else
  # restart 脚本路径解析：显式指定 > 作者 live 副本（与仓内 ops/ 逐字节一致）> 仓内 ops/
  RESTART_SCRIPT="${LT_RESTART_SCRIPT:-}"
  if [ -z "$RESTART_SCRIPT" ]; then
    if [ -x "$HOME/.stand/bin/nebflow-restart.sh" ]; then
      RESTART_SCRIPT="$HOME/.stand/bin/nebflow-restart.sh"
    elif [ -x "$(dirname "$0")/../ops/nebflow-restart.sh" ]; then
      RESTART_SCRIPT="$(cd "$(dirname "$0")/../ops" && pwd)/nebflow-restart.sh"
    else
      echo "[lt] ERROR: 找不到 nebflow-restart.sh（试了 ~/.stand/bin 与仓内 ops/）。请用 LT_RESTART_SCRIPT=<path> 显式指定。" >&2
      exit 1
    fi
  fi
  [ -x "$RESTART_SCRIPT" ] || { echo "[lt] ERROR: restart 脚本不可执行: $RESTART_SCRIPT" >&2; exit 1; }
  echo "[lt] 引擎未在监听 :$LT_PORT → 委托 $RESTART_SCRIPT ${LT_RESTART_MODE}（NEBFLOW_RESTART_PORT=${LT_PORT}；其编译预检/安全护栏原样生效）"
  if [ "$DRY_RUN" = "1" ]; then
    echo "[lt] [DRY_RUN] 将执行: NEBFLOW_RESTART_PORT=${LT_PORT} $RESTART_SCRIPT ${LT_RESTART_MODE}（阻塞至就绪后返回）"
  else
    # 端口旋钮随进程树透传（与 restart 脚本自身测试开关同名同语义）；退出码原样透传
    # （0=已拉起/就绪，1=拉起失败，3=--restart-if-idle 判忙拒绝）。
    if ! NEBFLOW_RESTART_PORT=${LT_PORT} "$RESTART_SCRIPT" $LT_RESTART_MODE; then
      rc=$?
      echo "[lt] ERROR: restart 脚本退出码 $rc —— 引擎未能拉起（见上方其输出；编译预检失败/判忙时引擎保持原状，安全）" >&2
      exit "$rc"
    fi
  fi
fi

# ── 步骤 2：就绪确认（restart 脚本自身已等就绪；引擎已在跑腿这里做实证探测）──
if [ "$DRY_RUN" = "1" ]; then
  echo "[lt] [DRY_RUN] 将探测就绪: GET 127.0.0.1:$LT_PORT/api/sessions ∈ {200,401,403}"
else
  if ! probe_ready 60; then
    echo "[lt] ERROR: :$LT_PORT API 探测未就绪（最近 http=$(api_code)）。引擎可能仍在编译——稍后重跑本脚本即可（活着即复用，不会重复启动）。" >&2
    exit 1
  fi
  echo "[lt] ✓ 引擎就绪（: ${LT_PORT}，API=$(api_code)）"
fi

# ── 步骤 3：本地铸票（复用引擎既有 BearerFileOnly console-ticket 旁路；零新代码）──
# auth.json = 引擎写在本机 home 的文件 token（bare string 或 {"token":...}）。引擎首次 boot
# 即铸出；刚拉起的实例给一个有界等待窗，绝不盲等。
token_from_auth_json() {
  python3 - "$LT_HOME/auth.json" <<'PYEOF' 2>/dev/null || true
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
if isinstance(d, str):
    print(d)
elif isinstance(d, dict) and isinstance(d.get("token"), str):
    print(d["token"])
PYEOF
}

TOKEN="$(token_from_auth_json)"
if [ "$DRY_RUN" != "1" ] && [ -z "$TOKEN" ]; then
  echo "[lt] $LT_HOME/auth.json 暂无 token（引擎 boot 铸写中）→ 有界等待 ≤30s…"
  for _ in $(seq 1 30); do
    TOKEN="$(token_from_auth_json)"
    [ -n "$TOKEN" ] && break
    sleep 1
  done
fi
if [ "$DRY_RUN" = "1" ]; then
  echo "[lt] [DRY_RUN] 将读 $LT_HOME/auth.json 的 Bearer → POST 127.0.0.1:$LT_PORT/api/auth/console-ticket → 120s 一次性票"
elif [ -z "$TOKEN" ]; then
  echo "[lt] ERROR: 读不到 $LT_HOME/auth.json 的 token。若引擎用的是别的 home，请 LT_HOME=<引擎 home> 重跑。" >&2
  exit 1
else
  MINT="$(curl -sS --noproxy '*' -m 10 -X POST \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' \
    "http://127.0.0.1:$LT_PORT/api/auth/console-ticket" 2>/dev/null || true)"
  TICKET="$(printf '%s' "$MINT" | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("ticket",""))
except Exception:
    pass' 2>/dev/null || true)"
  if [ -z "$TICKET" ]; then
    echo "[lt] ERROR: 铸票失败（响应: ${MINT:-<空>}）。常见因：LT_HOME 的 auth.json 与引擎实例不匹配（403）或 console-ticket 面未装配（503）→ 核对 LT_HOME 后重跑。" >&2
    exit 1
  fi
  echo "[lt] ✓ 已铸本地开门票（120s 一次性，origin=local-ticket；仅 loopback 腿可换）"
fi

# ── 步骤 4：开门 —— 已登录态直达；--no-open 只打印 ──
PLAIN_URL="http://localhost:$LT_PORT/"
if [ "$DRY_RUN" = "1" ]; then
  echo "[lt] [DRY_RUN] 将开浏览器: ${PLAIN_URL}?ticket=<urlenc>（或 --no-open 仅打印）"
  exit 0
fi
ENC="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$TICKET" 2>/dev/null || true)"
[ -n "$ENC" ] || { echo "[lt] ERROR: 票 URL 编码失败" >&2; exit 1; }
OPEN_URL="${PLAIN_URL}?ticket=$ENC"
OPEN_CMD="${LT_OPEN_CMD:-}"
if [ -z "$OPEN_CMD" ]; then
  if command -v open >/dev/null 2>&1; then OPEN_CMD="open"
  elif command -v xdg-open >/dev/null 2>&1; then OPEN_CMD="xdg-open"
  fi
fi
if [ "$NO_OPEN" = "1" ] || [ -z "$OPEN_CMD" ]; then
  echo "[lt] 已登录 URL（120s 内一次性有效，请即开）: $OPEN_URL"
  [ -z "$OPEN_CMD" ] && echo "[lt] 无 open/xdg-open 可用 —— 请手动粘贴上方 URL"
else
  "$OPEN_CMD" "$OPEN_URL"
  echo "[lt] ✓ 已在默认浏览器开门（303 换票后落 stand_session，30 天绝对期；后续直接开 $PLAIN_URL 亦保持登录）"
fi
echo "[lt] 常规入口（cookie 存活期内免票）: $PLAIN_URL ｜ 票过期未开 ⇒ 重跑本脚本铸新票"
