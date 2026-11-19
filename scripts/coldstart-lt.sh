#!/usr/bin/env bash
# coldstart-lt.sh — 本地测试便利批（作者 10-30 17:18 语义纠正批）：真独立冷启动实例 + 免登录开门
#
# 背景：本脚本首版（10-30 16:34 令，已合 main e5106dd09）把「本地测试」做成了「6138 活引擎
#       + console-ticket 铸票开门」。作者纠正：coldstart 的真义是【单独的冷启动实例】
#       （scripts/coldstart.sh 头注的定义）——lt 必须是冷启动实例，不是打开活的 :6138。
#
# 用途：作者日常本地测试的一步入口 —— 起一个【真独立】冷启动实例（自有 home + 自有端口，
#       默认避开 6138/8097）→ 冷实例就绪后追加免登录开门腿：冷 home 自铸本地 dev token →
#       POST /api/auth/console-ticket 铸 120s 一次性票 → 浏览器开
#       http://localhost:<冷端口>/?ticket= 落 stand_session，面板已登录态直达，
#       全程零 mystand.space / Logto 接触。
#
# 构成（两段既有机制的编排，零平行重写、零 Scala/JS 改动）：
#   1) 引擎腿：整建制委托 scripts/coldstart.sh（LT_COLDSTART_SCRIPT 可覆盖路径）——
#      逐 pid 验身（cmdline 特征 + <home>/.pid/.pid.meta 三元自证）、选择性清理（保留
#      模型配置三件 + secrets）、主 home 模型种子三件（device.json/auth.json 身份件
#      明确不拷）、secrets 持久符号链接、--net 档位、宿主 :8080 护栏，全部原样生效。
#      本脚本只追加两件事：免开门档注入（COLDSTART_NO_OPEN=1 ⇒ 冷实例 --no-browser
#      启动，开门责任在本脚本）与冷实例参数（端口 / home / secrets 目录）。
#      首版的 nebflow-restart.sh 引擎腿整腿移除——本脚本对主实例体系零触碰。
#   2) 免登录腿：复用引擎既有本地鉴权旁路 —— POST /api/auth/console-ticket
#      （AuthAdminRoutes，BearerFileOnly：只认本地文件 token）。冷 home 无 auth.json
#      （coldstart.sh 纪律：主 home 身份件不拷）⇒ 冷实例 boot 时 Auth.loadOrCreateToken
#      在冷 home 内【自铸】随机本地 dev token（SecureRandom；仅 loopback 面可用、随冷
#      home 生命周期，零身份流动——不经官网、不注册设备、不触生产网）。本脚本有界探测
#      该件出现后读 Bearer 铸票。票 = 120s 一次性（verifyAndConsume 原子核销，重放即
#      invalid-ticket）⇒ GET /?ticket= 兑换 303 ⇒ stand_session（local-ticket origin
#      仅 loopback 腿生效，GatewayAuth.localTicketOnLeg）⇒ /ws + /api/* 全放行。
#   3) 开门腿：macOS `open`（默认档案即可——本链路不经官网域名，无静默 SSO 面；与
#      coldstart.sh 一次性档案诉求相反：那里【制造】新用户态，这里【复用】作者日常
#      浏览器态）。
#
# 宿主 :6138 红线：
#   - 本脚本全程对 :6138 零信号：引擎腿只委托 coldstart.sh（其逻辑面只覆盖
#     COLDSTART_PORT），铸票/探测只打 127.0.0.1:<LT_PORT>。
#   - LT_PORT=6138 直接拒绝执行（同 coldstart.sh 对 8080 的护栏语义）；:6138 是否在
#     监听与本脚本无关——绝不探测、绝不复用、绝不重启。
#   - 同形护栏：LT_HOME 撞主 home（~/.stand 系）直接拒绝——否则 coldstart.sh 的
#     选择性清理会清掉主 home 的 sessions/注册/锁（模型三件与 secrets 虽保留，其余全删）。
#
# 生命周期：实例就绪 → 铸票开门后【陪跑】（wait coldstart.sh；终端 Ctrl+C 同前台
#   进程组直达实例，零特殊处理）。本脚本被单点 kill（非交互信号面）时 trap 委托回收：
#   kill coldstart.sh 包装进程（其 EXIT trap 收 sbt 壳）→ 有界复查端口释放 → 未释放则
#   按 <home>/.pid + .pid.meta 三元验身收 sbt fork 出的孤儿 JVM（逐 pid 验身纪律，
#   非特征进程绝不盲杀）→ 回收后端口必空，下轮冷启动可干净接管。
#   铸票腿失败（未就绪/token 缺失/铸票被拒）时冷实例【保持运行】，修复后重跑本脚本
#   即复用（活着即复用，不会重复起实例）——与首版失败语义一致。
#
# 用法：
#   ./scripts/coldstart-lt.sh                     # 冷实例(默认 :8199 /tmp/stand-lt) → 铸票 → 开浏览器
#   ./scripts/coldstart-lt.sh --no-open           # 只起冷实例 + 铸票，打印已登录 URL 不开浏览器
#   DRY_RUN=1 ./scripts/coldstart-lt.sh           # 全预览（含 coldstart.sh 内部预览，零动作）
#   LT_PORT=8299 LT_HOME=/tmp/xxx ./scripts/coldstart-lt.sh    # 覆盖端口 / home
#   LT_NET=staging ./scripts/coldstart-lt.sh      # 改入网档位（默认 off：零外网接触）
#   COLDSTART_FULL=1 ./scripts/coldstart-lt.sh    # 出厂档透传：不种模型配置（onboarding 走真实新用户路径）
#
# 环境旋钮：
#   LT_PORT（默认 8199）— 冷实例端口；6138 直接拒绝；8080 由 coldstart.sh 自带护栏拒绝
#   LT_HOME（默认 /tmp/stand-lt）— 冷实例 home；撞主 home 直接拒绝
#   LT_NET（默认 off）— 委托 coldstart.sh 的 --net 档位（staging|prod|off）
#   LT_SECRETS_DIR（默认 <主 home>/lt-secrets）— 冷实例通道密钥持久位（/tmp 可丢盘对策；
#       与 coldstart.sh 默认的 coldstart-secrets 分家：两类冷实例不共享通道密钥）
#   LT_COLDSTART_SCRIPT — 显式指定 coldstart.sh 路径（缺省 = 本脚本同目录的 coldstart.sh）
#   LT_OPEN_CMD — 覆盖开门命令（缺省 macOS `open` / Linux xdg-open；无则只打印 URL）
#   COLDSTART_FULL / COLDSTART_CHROME / NEBFLOW_ALLOW_PROD_ENROLL — 原样透传 coldstart.sh
#   DRY_RUN=1 — 全预览
#
# 退出码：0 = 已开门（或已打印 URL，或 DRY_RUN 预览完成）；1 = 失败（冷实例保持运行，
#         附可行动指引）；其余 = coldstart.sh / 实例退出码原样透传（陪跑终止语义）。

set -euo pipefail

# 文法钉：bash 会把【裸】$VAR 的变量名解析延进紧邻的多字节字符（$VAR）ok 即炸
# 「未绑定的变量」——凡 $VAR 后紧邻中文处一律用 ${VAR} 花括号形态（与 coldstart.sh
# 全文惯例一致；花括号形态实测免疫）。

LT_PORT="${LT_PORT:-8199}"
LT_HOME="${LT_HOME:-/tmp/stand-lt}"
LT_HOME="${LT_HOME%/}"; LT_HOME="${LT_HOME:-/}"
LT_NET="${LT_NET:-off}"
DRY_RUN="${DRY_RUN:-0}"
NO_OPEN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --no-open) NO_OPEN=1; shift ;;
    --open)    NO_OPEN=0; shift ;;
    *)
      echo "[lt] ERROR: 未知参数 '$1'（本脚本只认 --no-open / --open；端口/home/档位走 LT_PORT/LT_HOME/LT_NET env）" >&2
      exit 2
      ;;
  esac
done

case "$LT_PORT" in
  ''|*[!0-9]*) echo "[lt] ERROR: LT_PORT 须为数字端口，得到 '$LT_PORT'" >&2; exit 2 ;;
esac
case "$LT_NET" in
  staging | prod | off) : ;;
  *) echo "[lt] ERROR: LT_NET 须为 staging|prod|off，得到 '$LT_NET'" >&2; exit 2 ;;
esac

# ── 宿主 :6138 红线（护栏语义同 coldstart.sh 对 8080：动手前直接拒绝）──
if [ "$LT_PORT" = "6138" ]; then
  echo "[lt] ERROR: LT_PORT=6138 是运行中的 Nebflow 宿主实例端口——本脚本全程不得对其产生任何信号，拒绝执行。" >&2
  echo "[lt] 看活宿主面板请直接开 http://localhost:6138/；要起本地测试冷实例请用默认 LT_PORT=8199 或其它空闲端口。" >&2
  exit 1
fi

# ── 主 home 解析（与 coldstart.sh 步骤 2.5 同序：STAND_HOME > NEBFLOW_HOME > $HOME/<brand>）──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRAND_DIR="$(sed -n -E 's/^[[:space:]]*homeDirName[[:space:]]*=[[:space:]]*([^[:space:]#]+).*/\1/p' "$SCRIPT_DIR/../brand.conf" 2>/dev/null | tail -1 || true)"
BRAND_DIR="${BRAND_DIR:-.stand}"
MAIN_HOME="${STAND_HOME:-${NEBFLOW_HOME:-$HOME/$BRAND_DIR}}"
MAIN_HOME="${MAIN_HOME%/}"; MAIN_HOME="${MAIN_HOME:-/}"

# ── 同形护栏：LT_HOME 撞主 home ⇒ 选择性清理会清掉主 home 非保留条目，动手前直接拒绝 ──
if [ "$LT_HOME" = "$MAIN_HOME" ]; then
  echo "[lt] ERROR: LT_HOME=$LT_HOME 与主 home 相同——coldstart.sh 的选择性清理会删除主 home 的 sessions/注册/锁。拒绝执行。" >&2
  echo "[lt] 冷实例 home 必须落在 /tmp 等隔离位（默认 /tmp/stand-lt）。" >&2
  exit 1
fi

# ── 委托面解析：coldstart.sh（同仓兄弟脚本，随仓库同步演进）──
COLDSTART_SCRIPT="${LT_COLDSTART_SCRIPT:-$SCRIPT_DIR/coldstart.sh}"
if [ ! -x "$COLDSTART_SCRIPT" ]; then
  echo "[lt] ERROR: coldstart.sh 不可执行: ${COLDSTART_SCRIPT}（用 LT_COLDSTART_SCRIPT=<path> 显式指定）" >&2
  exit 1
fi

LT_SECRETS_DIR="${LT_SECRETS_DIR:-$MAIN_HOME/lt-secrets}"

echo "[lt] port=$LT_PORT home=$LT_HOME net=$LT_NET dry_run=$DRY_RUN no_open=$NO_OPEN"
echo "[lt] 链路: 独立冷实例(委托 coldstart.sh + COLDSTART_NO_OPEN=1) → 冷 home 自铸本地 dev token → 铸 120s 票 → localhost 面板已登录态直达（:6138 零信号，零官网接触）"

# ── sbt-fork 自证（与 coldstart.sh coldstart_pidfile_self 同口径的三元验身）──
# kill 包装进程后，sbt `fork` 出的实例 JVM（cmdline 不含特征）可能成孤儿续占端口。
# 回收时只有 <home>/.pid == 占端/记录 pid ∧ sha256(实测 cmdline) == .pid.meta.cmdlineSha256
# （或命令行原文全等）才允许 kill——对真外来进程一字不松。
lt_pidfile_self() {
  local pid="$1" rec="" meta="" meta_pid="" meta_cmdline="" meta_sha="" live_cmdline="" live_sha=""
  [ -r "$LT_HOME/.pid" ] && [ -r "$LT_HOME/.pid.meta" ] || return 1
  rec=$(tr -d '[:space:]' < "$LT_HOME/.pid" 2>/dev/null || true)
  if [ -z "$rec" ] || [ "$rec" != "$pid" ]; then return 1; fi
  meta=$(python3 -c '
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    print(d.get("pid") or "")
    print(d.get("cmdline") or "")
    print(d.get("cmdlineSha256") or "")
except Exception:
    pass
' "$LT_HOME/.pid.meta" 2>/dev/null || true)
  meta_pid=$(printf '%s\n' "$meta" | sed -n '1p')
  meta_cmdline=$(printf '%s\n' "$meta" | sed -n '2p')
  meta_sha=$(printf '%s\n' "$meta" | sed -n '3p')
  if [ "$meta_pid" != "$pid" ]; then return 1; fi
  live_cmdline=$(ps -p "$pid" -o command= 2>/dev/null | sed 's/[[:space:]]*$//' || true)
  if [ -z "$live_cmdline" ]; then return 1; fi
  live_sha=$(printf '%s' "$live_cmdline" | shasum -a 256 2>/dev/null | awk '{print $1}')
  if [ -n "$meta_sha" ] && [ -n "$live_sha" ] && [ "$live_sha" = "$meta_sha" ]; then
    return 0
  fi
  if [ -n "$meta_cmdline" ] && [ "$live_cmdline" = "$meta_cmdline" ]; then
    return 0
  fi
  return 1
}

# ── 单点 kill（非交互信号面）回收：包装进程 → 有界复查端口 → 三元验身收孤儿 JVM ──
# 终端 Ctrl+C 走前台进程组直达（实例/包装/本脚本同组同收），不进本 trap。
COLD_PID=""
cleanup() {
  trap - INT TERM
  [ -z "$COLD_PID" ] && return 0
  echo "[lt] 收到终止信号 → 回收冷实例（先委托包装进程，再逐 pid 验身收 fork JVM）…"
  kill "$COLD_PID" 2>/dev/null || true
  for _ in $(seq 1 60); do
    if ! lsof -tiTCP:"$LT_PORT" -sTCP:LISTEN 2>/dev/null | grep -q .; then
      echo "[lt] 冷实例已回收，端口 :$LT_PORT 已释放。"
      return 0
    fi
    sleep 1
  done
  orphan=$(tr -d '[:space:]' < "$LT_HOME/.pid" 2>/dev/null || true)
  if [ -n "$orphan" ] && lt_pidfile_self "$orphan"; then
    echo "[lt] 包装进程已退但端口仍被占 → .pid/.pid.meta 三元验身命中 pid=${orphan}（本冷实例 sbt-fork JVM）→ 回收"
    kill "$orphan" 2>/dev/null || true
  else
    echo "[lt] WARN: 端口 :$LT_PORT 仍被占用且 .pid 三元验身未命中——非本冷实例进程，绝不盲杀。" >&2
    echo "[lt] 请 lsof -nP -iTCP:$LT_PORT -sTCP:LISTEN 人工验明占端进程后处置。" >&2
  fi
}
trap cleanup INT TERM

# ── 就绪探测（与首版同口径：/api/sessions 返回 200/401/403 即视为服务活着）──
api_code() {
  curl -s -o /dev/null -w "%{http_code}" -m 3 "http://127.0.0.1:$LT_PORT/api/sessions" 2>/dev/null || true
}

# ── 步骤 1：引擎腿 —— 整建制委托 coldstart.sh（免开门档 + 冷实例参数注入）──
# env 前缀只覆盖冷实例三参数与免开门档；NEBFLOW_ALLOW_PROD_ENROLL / COLDSTART_FULL /
# COLDSTART_CHROME 等使用者自设 env 经进程树自然透传，本脚本绝不改写。
if [ "$DRY_RUN" = "1" ]; then
  echo "[lt] [DRY_RUN] 步骤1 引擎腿预览（coldstart.sh 内部 DRY_RUN 全预览）:"
  env DRY_RUN=1 COLDSTART_PORT="$LT_PORT" COLDSTART_HOME="$LT_HOME" COLDSTART_NO_OPEN=1 \
    COLDSTART_SECRETS_DIR="$LT_SECRETS_DIR" "$COLDSTART_SCRIPT" --net "$LT_NET"
  echo "[lt] [DRY_RUN] 步骤2 免登录腿: 将有界探测 $LT_HOME/auth.json（冷实例 boot 自铸的本地 dev token）→ POST 127.0.0.1:$LT_PORT/api/auth/console-ticket → 120s 一次性票"
  echo "[lt] [DRY_RUN] 步骤3 开门: 将开 http://localhost:$LT_PORT/?ticket=<urlenc>（或 --no-open 仅打印）"
  exit 0
fi

echo "[lt] 步骤1: 委托 coldstart.sh 拉起独立冷实例（: $LT_PORT home=$LT_HOME net=${LT_NET}）…"
env COLDSTART_PORT="$LT_PORT" COLDSTART_HOME="$LT_HOME" COLDSTART_NO_OPEN=1 \
  COLDSTART_SECRETS_DIR="$LT_SECRETS_DIR" "$COLDSTART_SCRIPT" --net "$LT_NET" &
COLD_PID=$!

# ── 步骤 2：就绪确认（包装进程存活 + API 探测双条件；包装早退 = 委托失败，原样上报）──
READY=0
for _ in $(seq 1 100); do
  if ! kill -0 "$COLD_PID" 2>/dev/null; then
    set +e; wait "$COLD_PID"; rc=$?; set -e
    echo "[lt] ERROR: coldstart.sh 提前退出（exit=${rc}，见上方其输出）——冷实例未就绪。" >&2
    exit 1
  fi
  case "$(api_code)" in 200|401|403) READY=1; break ;; esac
  sleep 3
done
if [ "$READY" != "1" ]; then
  echo "[lt] ERROR: 冷实例 API 探测未就绪（: $LT_PORT 最近 http=$(api_code)）。" >&2
  echo "[lt] 冷实例进程仍在（coldstart.sh pid=${COLD_PID}，可能仍在编译）——稍后重跑本脚本即复用铸票开门；要回收请 kill ${COLD_PID}。" >&2
  exit 1
fi
echo "[lt] ✓ 冷实例就绪（: ${LT_PORT}，API=$(api_code)）"

# ── 步骤 3：冷 home 自铸 token 的有界探测（引擎 Auth.loadOrCreateToken 在冷 home 自铸）──
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
if [ -z "$TOKEN" ]; then
  echo "[lt] $LT_HOME/auth.json 暂无 token（冷实例 boot 自铸中）→ 有界探测 ≤30s…"
  for _ in $(seq 1 30); do
    TOKEN="$(token_from_auth_json)"
    [ -n "$TOKEN" ] && break
    sleep 1
  done
fi
if [ -z "$TOKEN" ]; then
  echo "[lt] ERROR: 读不到 $LT_HOME/auth.json 的 token（冷实例自铸未落地）。冷实例仍在跑——稍后重跑本脚本即可；若持续失败请查冷实例日志。" >&2
  exit 1
fi
echo "[lt] ✓ 冷 home 本地 dev token 已就位（冷实例自铸，仅 loopback，零身份流动）"

# ── 步骤 4：铸票（复用引擎既有 BearerFileOnly console-ticket 面；零新代码）──
MINT="$(curl -sS --noproxy '*' -m 10 -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' \
  "http://127.0.0.1:$LT_PORT/api/auth/console-ticket" 2>/dev/null || true)"
TICKET="$(printf '%s' "$MINT" | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("ticket",""))
except Exception:
    pass' 2>/dev/null || true)"
if [ -z "$TICKET" ]; then
  echo "[lt] ERROR: 铸票失败（响应: ${MINT:-<空>}）。冷实例仍在跑——核对后重跑本脚本即可（活着即复用）。" >&2
  exit 1
fi
echo "[lt] ✓ 已铸本地开门票（120s 一次性，origin=local-ticket；仅 loopback 腿可换）"

# ── 步骤 5：开门 —— 已登录态直达；--no-open 只打印 ──
PLAIN_URL="http://localhost:$LT_PORT/"
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
  echo "[lt] ✓ 已开门（303 换票后落 stand_session，30 天绝对期；后续直接开 $PLAIN_URL 亦保持登录）"
fi
echo "[lt] 常规入口（cookie 存活期内免票）: $PLAIN_URL ｜ 票过期未开 ⇒ 重跑本脚本铸新票"

# ── 陪跑：实例生命周期 = coldstart.sh 生命周期；退出码原样透传 ──
echo "[lt] 陪跑中（Ctrl+C 一并回收冷实例；或另开终端重跑本脚本铸新票）…"
set +e
wait "$COLD_PID"
EXIT_CODE=$?
set -e
COLD_PID=""
echo "[lt] 冷实例已停止（exit=${EXIT_CODE}）。"
exit "$EXIT_CODE"
