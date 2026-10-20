#!/usr/bin/env bash
# coldstart.sh — Nebflow 冷启动脚本
#
# 用途：先清理旧残留（占端口的旧实例 + 旧 data home 中【非模型配置】的条目），
#       再启动一个全新的冷启动实例（已配置的模型跨冷启动保留，不必重配）。
# 解决原命令 `sbt "run --home /tmp/nebflow-coldstart --port 8097"` 的路径/端口被脏
# （旧 sessions / 注册 / 锁 残留导致"不是真正冷启动"）的问题。
#
# 安全设计（为何这样写）：
#   1) 逐 pid 验身才 kill —— 端口可能被无关进程占用（复用 8097 的其它服务/调试进程）。
#      只有 cmdline 含 `nebflow-coldstart` 特征（即本脚本自己起的实例）才允许 kill；
#      非特征进程一律拒绝启动并报 WARN，绝不盲杀（盲杀 = 杀错无辜进程 / 宿主）。
#   1b) pid 文件自证（sbt-fork 形态补丁）—— `sbt run` fork 出的 JVM 真参在启动即被
#      sbt 删除的临时文件里，cmdline 永不含 marker ⇒ Ctrl+C 杀掉 sbt 壳后，孤儿 fork
#      会被特征法误判为「非特征」而永远无法重跑接管。引擎 boot 会把真实 JVM 的
#      pid/命令行写进 <home>/.pid 与 .pid.meta，据此增设第二验身渠道（详见
#      coldstart_pidfile_self）：三元一致才允许 kill，对真外来进程的拒绝语义不变。
#      端口释放等待由 5s 扩为有界 120s（sbt-fork 对 TERM 收敛慢，实测 6s~60s+），
#      超时拒绝继续并给人工指引。
#   2) 绝不碰宿主 :8080 —— 脚本只对 $COLDSTART_PORT（默认 8097）做 lsof 定位，
#      且 COLDSTART_PORT 被误设为 8080 时直接拒绝执行：8080 是 Nebflow 宿主实例端口，
#      对它产生任何信号都会杀掉宿主与当前会话。本脚本逻辑面从头到尾只覆盖 $COLDSTART_PORT。
#   3) DRY_RUN=1 只预览不删不杀不种 —— 供作者先审行为（将 kill 谁 / 将清哪个 home /
#      将种哪些件）再实跑，防止脚本在作者未 review 的情况下就动手删数据/杀进程/写配置。
#   4) 默认【不】放行生产设备注册 —— 隔离实验若要进生产网必须显式设 env（作者 2026-09-17 裁定 (b)）。
#      本脚本的 data root 在 /tmp（非默认根），会命中 NebLink 隔离护栏
#      （src/main/scala/nebflow/neblink/EnrollGuard.scala：非默认 data root ∧ 未显式放行 ∧
#      目标 host ∈ 生产域 nebflow.space ⇒ 拒绝自动注册，且以响亮日志说明原因，绝不静默）。
#      故未设 NEBFLOW_ALLOW_PROD_ENROLL 时，本脚本【不导出、不覆盖】该变量 ⇒ 护栏按原语义生效、
#      设备注册被拒 ⇒ 隔离实例默认不进生产网。为免「静默」，脚本在启动前另打印 NOTICE 明示本次
#      判定（见下方分支），使作者一眼可见「本次不进生产网」。
#      为何不再默认放行：旧行为让「随手起一个隔离实例」= 往【生产】NebLink
#      https://link.mystand.space 注册一台新设备（brand.conf 的 serverUrl 真值，也是
#      `neblinkServerUrl()` 的最终 fallback）——同一账号跑多实例会互踢：后登录的实例上线时
#      旧实例被踢下线；且生产设备表会被隔离实例污染（2026-09-11 事故，dup-device-verdict
#      §1.2 类 2）。
#      显式放行：NEBFLOW_ALLOW_PROD_ENROLL=1（或 true / yes，大小写不敏感）时脚本原样透传；
#      使用者已设该变量时脚本一律原样保留、绝不覆盖。
#      真值口径与 Scala 端 EnrollGuard.explicitAllowEnv 逐字对齐：1 / true / yes（大小写不敏感）；
#      其它值（含 0 / false / 空）都等同未放行。
#   5) 步骤 2 是【选择性清理】而非 rm -rf 整个 home —— 模型配置文件原地保留，免得每跑一次冷启动
#      就要重新配一遍模型。保留件 = 顶层三件、精确名匹配：nebflow.json（provider×model 定义）、
#      models.json（provider/model 能力注记）、model-presets.json（preset 链）；其余顶层条目
#      （sessions / 注册 / 锁 / 点目录等）照旧清空，真·冷启动语义不变。选「就地删非保留件」而非
#      「先搬走三件→rm -rf→再搬回」：搬移形态一旦在清理与恢复之间中断，配置会滞留在暂存目录；
#      就地清理的中断方向只会留下残留条目，配置始终在原位（fail-safe）。
#      首次冷启动（保留件尚未生成）下三件均未命中，脚本照常启动；每轮都逐条打印保留清单与
#      删除清单，便于作者由 stdout 核对「哪些留、哪些删」。
#   6) 网络档位 --net（coldstart DX 批①，2026-10-14 作者令）—— staging | prod | off（默认 staging）：
#      - staging（默认）：清理后往新 home 写 <home>/neblink/config.json 的
#        `neblinkServer.url` = staging relay + `neblinkServer.networkId` = staging 网络
#        路由标识（真源 .nebflow/evidence/20261014_delegate-p6/repro.md；分别可用
#        COLDSTART_STAGING_SERVER / COLDSTART_STAGING_NETWORK 覆盖）。networkId 是
#        NeblinkServerConfig 解码的必填字段（缺它 ⇒ 整文件解码失败 ⇒ NeblinkConfig.load
#        静默回落默认 ⇒ 注入死代码），且为路由标识**非凭据**（无 token/secret 时零认证
#        能力；登录成功后服务端响应里的 networkId 会覆写此占位）。EnrollGuard 只拦
#        生产域（mystand.space / nebflow.space），staging host 天然放行 ⇒ 登录即指向 staging 网络，
#        生产网零触碰。已有 config.json 时【绝不覆盖】（作者手配优先）。
#        注入面 = 配置文件（`NeblinkConfig.load` 的唯一读取点），零 Scala 改动；
#        仅地址与网络标识、零凭据（🔴 无任何 token/密钥写入）。
#      - prod：现行为——不注入任何 server 覆盖；未显式设 NEBFLOW_ALLOW_PROD_ENROLL=1 时
#        隔离护栏照旧拒绝生产注册（4) 的全部语义逐字保留）。
#      - off：不注入 server ⇒ 实例无入网目标，不起 relay 隧道。登录/设备互联在本档【不可用】：
#        登录链路会给出「无入网目标」的可见失败（EnrollGuard 案 b① + guard 感知文案，
#        批②）——这是本档预期行为而非故障；要入网请改用 --net staging（或 --net prod +
#        NEBFLOW_ALLOW_PROD_ENROLL=1）。脚本启动前把这句话打印清楚，不给死路。
#   7) 模型配置种子（coldstart DX 批③，2026-10-14 作者令）——主 home → 新 home 单向拷贝：
#      首次冷启动无配置是「localhost:8097 无法直接可用」的根因（新 home 无 LLM provider）。
#      清理后逐件检查主 home 的 nebflow.json / models.json / model-presets.json：
#      源存在且新 home 无同名件 ⇒ 拷入（cp -p 保权限；nebflow.json 可能含 provider 密钥，
#      种入后收紧为 600，与仓内凭据件 ACL 纪律同向）。已有同名件（冷启动保留件）⇒ 不覆盖。
#      主 home 解析与 Scala 端同序：NEBFLOW_HOME（Branding.env 首选）> $HOME/<brand 目录>；
#      brand 目录名从 brand.conf 的 homeDirName 现读，🔴 不写死任何绝对路径。
#      🔴 device.json / auth.json（身份与凭据面）明确不拷、零触碰——身份/凭据跨 home
#      流动正是 2026-09-11 撞身份事故链的一环（见 coldstart DX 批④的 scope 熵修复）。
#   8) 浏览器隔离开门 + 出厂档（新用户体验批；2026-10-18 13:29 作者二次拍板改形）——
#      实例的 home 是干净的，但浏览器层会沿用本机登录状态：默认浏览器档案的 cookie
#      与 Logto SSO 会话是域名级、跨实例共享的，点「登录」会被静默签入已登录账号、
#      跳过凭据页。作者拍板的冷启动开门形态（拆开「官网入口」与「静默 SSO」的捆）：
#      - 恢复 boot 自动开门（实例不再带 --no-browser）：开门【目标】由引擎 PortalUrl
#        单点解析（env > config > brand 缺省 = 官网统一入口 https://mystand.space/portal；
#        NEBFLOW_OPEN_UI=local / portalUrl="" 回落本地），登录后经门户回投 relay-mint
#        票进本地控制台。
#      - 开门【载体】= Chrome【一次性档案】（--user-data-dir 临时目录：零 cookie、
#        零 SSO、零 localStorage ⇒ 浏览器层等价一台全新机器，凭据页必然出现 = 不被
#        静默签入的机制保障）。载体注入面：脚本生成 wrapper 可执行件（内部写死
#        Chrome 路径 + 档案参数），经 STAND_OPEN_BROWSER / NEBFLOW_OPEN_BROWSER env
#        传给实例——引擎 openBrowser 只认「载体路径」，URL 由引擎追加，目标解析零改动
#        （GatewayMain.openBrowser）。该 env 只在本脚本子进程树内生效，宿主实例不受影响。
#      - localhost?ticket 自铸票开门降级为【回落路径】：仅当官网/门户不可达（探测
#        失败）或实例侧开门未落地时才走，且必打印回落说明——冷启动绝不死在门外。
#        铸票面不变（POST /api/auth/console-ticket，AuthAdmin BearerFileOnly，token
#        读自 <home>/auth.json，120s 一次性、origin=local-ticket）。
#      - Chrome 缺席（或 COLDSTART_CHROME 指向他物）时：实例退回 --no-browser 启动
#        （宁可不开门，绝不用默认档案浏览器开门——那是静默签入面），脚本打印「请用
#        无痕窗口打开」指引——无痕窗同样隔离本机会话状态。一次性档案目录每轮独立
#        （/tmp/stand-coldstart-console-profile-<pid>；上轮门窗还开着则保留其档，
#        无引用的陈档启动前自动清——绝不抽运行中浏览器脚下的档案）。
#      - COLDSTART_FULL=1：跳过 7) 的模型配置种子 ⇒ home 连 LLM 配置都零携带，
#        onboarding 的选模型步走真实新用户路径。缺省 0 = 沿用种子（开发免重配）。
#   9) 运行时密钥持久位（悬空引用修复批，2026-10-20）——<home>/secrets 符号链接到
#      持久目录（缺省 $MAIN_HOME/coldstart-secrets，COLDSTART_SECRETS_DIR 可覆盖）：
#      事故链：#418 系曾在冷启动实例（home=/tmp/nebflow-coldstart）上完成 weixin
#      扫码绑定，引擎 SocialChannels.writeSecret 把密钥写进 <home>/secrets/（root 即
#      数据根），配置面引用的也是 /tmp 路径；macOS /tmp 清理后引用集体悬空
#      （面板 Invalid configuration「Referenced file does not exist」）+ sidecar
#      LaunchAgent 指向同目录的 ingress token 一并失踪。根治 = 引擎写 secrets 的
#      位置不动（<dataRoot>/secrets 是引擎的硬语义），本脚本把【这个子树】落到
#      持久盘：启动前确保 <home>/secrets → $COLDSTART_SECRETS_DIR（0700）的符号
#      链接；历史上已是实目录则一次性搬迁内容后再换链接（home 内副本为活态、
#      覆盖同名）；清理步把 secrets 列入保留集（永不删该条目，无论链接还是目录）。
#      引擎经链接读写时 NIO 解析父链接，ACL 收窄落在真实目录上，语义不变。
#      同批 /tmp 落盘审计：开门载体 CHROME_PROFILE（一次性浏览器档案）与
#      open-door.sh wrapper 属【真正可丢的缓存/脚手架】，保留在 /tmp 是设计而非
#      遗漏；脚本其余写盘（.pid/.pid.meta、neblink/config.json 的 url+networkId、
#      模型配置种子）均无凭据面。生产通道（主 home）的密钥引用一律指向
#      ~/.nebflow/secrets/——跨 home 引用 /tmp 路径即本批事故形态，勿再为。

# 用法：
#   ./scripts/coldstart.sh                                            # staging 档冷启动（默认，不进生产网）
#   ./scripts/coldstart.sh --net prod                                 # 现行为（须配 NEBFLOW_ALLOW_PROD_ENROLL=1 才进生产网）
#   ./scripts/coldstart.sh --net off                                  # 纯本地：不注入入网目标，不起 relay 隧道
#   COLDSTART_NET=off ./scripts/coldstart.sh                          # env 等价形态（--net 优先于 COLDSTART_NET）
#   NEBFLOW_OPEN_UI=local ./scripts/coldstart.sh                      # 显式本地档：boot 开门直接走本地自铸票（PortalUrl 既有回落语义）
#   DRY_RUN=1 ./scripts/coldstart.sh                                  # 只预览，不启动 sbt、不写任何文件
#   COLDSTART_PORT=<port> COLDSTART_HOME=<path> ./scripts/coldstart.sh  # 覆盖默认
#   COLDSTART_FULL=1 ./scripts/coldstart.sh                           # 出厂档：不种模型配置（全零携带）
#   COLDSTART_CHROME=<path> ./scripts/coldstart.sh                    # 指定开门用的 Chrome 可执行件
#   NEBFLOW_ALLOW_PROD_ENROLL=1 ./scripts/coldstart.sh --net prod     # 显式放行：注册到生产 NebLink
#
# 说明：已配置的模型（home 顶层的 nebflow.json / models.json / model-presets.json）
#       在冷启动时原地保留；主 home 有而新 home 无的模型配置会被种入（见 7)）。
#       开门形态见文件头 8)：boot 自动开门 → PortalUrl 官网统一入口；一次性档案
#       = 开门载体（无静默 SSO）；localhost?ticket 自铸票 = 明确回落（门户不可达 /
#       开门未落地时才走，打印回落说明）。STAND_OPEN_BROWSER / NEBFLOW_OPEN_BROWSER
#       env 由本脚本管理（开门载体注入面），使用者无需手设。
#
set -euo pipefail

COLDSTART_PORT="${COLDSTART_PORT:-8097}"
COLDSTART_HOME="${COLDSTART_HOME:-/tmp/nebflow-coldstart}"
DRY_RUN="${DRY_RUN:-0}"
# 本脚本自起实例的 cmdline 特征标记（验身用）。
COLDSTART_MARKER="nebflow-coldstart"
# 出厂档开关：1 = 跳过模型配置种子（home 全零携带，见文件头 8)）；缺省 0。
COLDSTART_FULL="${COLDSTART_FULL:-0}"
# 开门用 Chrome 可执行件（缺省 = 标准 macOS 安装位；缺失时退化为无痕窗口指引）。
CHROME_BIN="${COLDSTART_CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

# ── 网络档位解析（批①）：--net <tier> 优先，其次 COLDSTART_NET，默认 staging ──
NET_TIER="${COLDSTART_NET:-staging}"
while [ $# -gt 0 ]; do
  case "$1" in
    --net)
      if [ $# -lt 2 ]; then
        echo "[coldstart] ERROR: --net 需要一个取值（staging|prod|off）" >&2
        exit 2
      fi
      NET_TIER="$2"
      shift 2
      ;;
    --net=*)
      NET_TIER="${1#--net=}"
      shift
      ;;
    *)
      echo "[coldstart] ERROR: 未知参数 '$1'（本脚本只认 --net staging|prod|off；端口/home 走 COLDSTART_PORT/COLDSTART_HOME env）" >&2
      exit 2
      ;;
  esac
done
case "$NET_TIER" in
  staging | prod | off) : ;;
  *)
    echo "[coldstart] ERROR: --net 取值须为 staging|prod|off，得到 '$NET_TIER'" >&2
    exit 2
    ;;
esac

# staging relay 真源：.nebflow/evidence/20261014_delegate-p6/repro.md（地址非凭据，可覆盖）。
COLDSTART_STAGING_SERVER="${COLDSTART_STAGING_SERVER:-https://neblink-stg.45.32.62.185.sslip.io}"
# staging 网络 id（同上真源）：NeblinkServerConfig 解码必填字段（解码器见
# src/main/scala/nebflow/neblink/NeblinkClient.scala —— 缺 networkId ⇒ 整文件解码失败 ⇒
# NeblinkConfig.load 静默回落默认 ⇒ 本注入沦为死代码）；路由标识非凭据（无
# token/secret 零认证能力，登录成功后被服务端响应的 networkId 覆写）。
COLDSTART_STAGING_NETWORK="${COLDSTART_STAGING_NETWORK:-90287e76-b4aa-4778-8ac5-fd63aacd5d63}"

# ── 安全红线①：绝不触碰宿主 :8080 ──
# 作者现行习惯默认端口为 8097；若被误设为 8080（宿主端口），在动手前直接拒绝。
if [ "$COLDSTART_PORT" = "8080" ]; then
  echo "[coldstart] ERROR: COLDSTART_PORT=8080 是运行中的 Nebflow 宿主实例端口，拒绝触碰。" >&2
  exit 1
fi

echo "[coldstart] port=$COLDSTART_PORT home=$COLDSTART_HOME dry_run=$DRY_RUN net=$NET_TIER full=$COLDSTART_FULL"
echo "[coldstart] 开门形态: boot 自动开门 → PortalUrl 官网统一入口；载体=Chrome 一次性档案（真·新用户浏览器层，凭据页必然出现）；localhost 自铸票=明确回落"

# ── 网络档位 NOTICE（三档都明示，绝不静默；批①「启动 NOTICE 文案同步三档」）──
case "$NET_TIER" in
  staging)
    echo "[coldstart] NOTICE: --net staging（默认档）→ 本实例 neblink 指向 staging relay ${COLDSTART_STAGING_SERVER}（配置注入 <home>/neblink/config.json，已有件不覆盖）。EnrollGuard 只拦生产域，staging 天然放行 ⇒ 登录即指向 staging 网络；生产网零触碰。"
    if [ -n "${NEBFLOW_ALLOW_PROD_ENROLL:-}" ]; then
      echo "[coldstart] NOTICE: 你已设 NEBFLOW_ALLOW_PROD_ENROLL=${NEBFLOW_ALLOW_PROD_ENROLL}——staging 档的入网目标来自配置文件（staging relay），该开关在本档不改变目标；脚本仍原样透传、绝不改写你的设置。"
    fi
    ;;
  prod)
    echo "[coldstart] NOTICE: --net prod → 不注入任何 server 覆盖（现行为）。入网目标与生产放行判定见下方 NEBFLOW_ALLOW_PROD_ENROLL 分支。"
    ;;
  off)
    echo "[coldstart] NOTICE: --net off → 不注入入网目标、不起 relay 隧道：登录/设备互联在本档【不可用】（登录会看到「实例未放行入网目标」的可见提示——这是本档预期行为，不是故障）。要入网请改用 --net staging（staging 网络），或 --net prod 且显式 NEBFLOW_ALLOW_PROD_ENROLL=1（生产网）。"
    ;;
esac

# ── 生产注册放行开关：默认【不】放行（详见文件头 4)）──
# 🔴 本批（作者 2026-09-17 裁定 (b)）已删除「未设时默认导出 1」的行为：
#    未设 NEBFLOW_ALLOW_PROD_ENROLL ⇒ 脚本【不赋值、不导出】该变量（无 :-1 默认值），
#    护栏 EnrollGuard 按原语义生效 ⇒ 不进生产网。
# set -u 已开 ⇒ 用 ${NEBFLOW_ALLOW_PROD_ENROLL:-} 只读探测（空串 = 未设；探测不改写、不导出）。
# 提示语按与 EnrollGuard.explicitAllowEnv 相同的真值口径分支（1/true/yes，大小写不敏感），
# 避免「因 0/false 被拒注册」时还打印「将注册到生产」的错误预告。
# 🔴 三条分支都打印明示，绝不静默：未设时明确告知「本次不进生产网」并给出显式放行用法。
case "${NEBFLOW_ALLOW_PROD_ENROLL:-}" in
  1 | [tT][rR][uU][eE] | [yY][eE][sS])
    echo "[coldstart] NOTICE: NEBFLOW_ALLOW_PROD_ENROLL=${NEBFLOW_ALLOW_PROD_ENROLL}（显式放行）→ 本次将把设备注册到【生产 NebLink】https://link.mystand.space —— 同一账号多实例会互踢，后登录的实例上线时旧实例会被踢下线。"
    ;;
  "")
    echo "[coldstart] NOTICE: 未设 NEBFLOW_ALLOW_PROD_ENROLL → 本次【不进生产网】：本实例不会注册到生产 NebLink https://link.mystand.space，隔离护栏 EnrollGuard 生效（非默认 data root ∧ 未显式放行 ∧ 生产域 ⇒ 拒绝自动注册）。要显式放行请用：NEBFLOW_ALLOW_PROD_ENROLL=1 ./scripts/coldstart.sh"
    ;;
  *)
    echo "[coldstart] NOTICE: NEBFLOW_ALLOW_PROD_ENROLL=${NEBFLOW_ALLOW_PROD_ENROLL}（非 1/true/yes）→ 保留你的设置，隔离护栏生效：本实例不会注册到生产 NebLink https://link.mystand.space（同一账号多实例的互踢风险随之消失）。"
    ;;
esac

# ── sbt-fork 自证（pid 文件验身，安全设计 1b）──────────────────
# `sbt run` fork 的 JVM 形如 `java @/…/sbt-args<rand>.tmp`：真参（含 --home/特征路径）在
# 启动即被 sbt 删除的临时文件里 ⇒ cmdline 永不含 marker，特征法认不出自家上一轮实例。
# 引擎 boot 时会把真实 JVM 的 pid/命令行写进 <home>/.pid 与 .pid.meta，以此作第二验身渠道：
#   占端 pid == .pid == .pid.meta.pid  ∧  sha256(实测 cmdline) == .pid.meta.cmdlineSha256
# （sha 不匹配时退回命令行原文全等比较，双保险）。三元全对 ⇒ 自家上一轮实例，允许 kill；
# 否则维持「非特征拒绝」，对真外来进程的护栏一字不松。实例优雅退出时会自删这两件 ⇒
# 盘上残留恰是「杀壳留孤儿」的目标场景。
coldstart_pidfile_self() {
  local pid="$1" rec="" meta="" meta_pid="" meta_cmdline="" meta_sha="" live_cmdline="" live_sha=""
  [ -r "$COLDSTART_HOME/.pid" ] && [ -r "$COLDSTART_HOME/.pid.meta" ] || return 1
  rec=$(tr -d '[:space:]' < "$COLDSTART_HOME/.pid" 2>/dev/null || true)
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
' "$COLDSTART_HOME/.pid.meta" 2>/dev/null || true)
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
    elif coldstart_pidfile_self "$pid"; then
      echo "[coldstart]   pid=$pid 命中 pid 文件自证（<home>/.pid + .pid.meta 与实测进程一致）→ 判定为本脚本上一轮 sbt-fork 实例（真参在已删临时文件，特征法不可见），允许清理"
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
    # 等待端口释放（有界 120s，1s 轮询，每 10s 一次心跳读数）。sbt-fork 实例对 TERM
    # 的收敛可能很慢（实测 6s ~ 60s+，运行越久收尾越久），旧 5s 等待会过早放弃 ⇒
    # 端口仍被旧实例占着就往下走清场/启动，新实例必然起不来。超时 ⇒ 拒绝继续
    # （清场与启动都以「端口已空」为前提），给人工处置指引，绝不带占启动。
    released=0
    for i in $(seq 1 120); do
      if ! lsof -tiTCP:"$COLDSTART_PORT" -sTCP:LISTEN 2>/dev/null | grep -q .; then
        released=1
        break
      fi
      if [ $((i % 10)) -eq 0 ]; then
        echo "[coldstart] 等待旧实例退出… ${i}s/120s（仍占端: $(lsof -tiTCP:"$COLDSTART_PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')）"
      fi
      sleep 1
    done
    if [ "$released" != "1" ]; then
      echo "[coldstart] WARN: 旧实例 120s 未释放端口 $COLDSTART_PORT（kill 已发但未收敛）→ 拒绝继续，绝不带占启动。" >&2
      echo "[coldstart] 人工处置：lsof -nP -iTCP:$COLDSTART_PORT -sTCP:LISTEN 验明占端 pid 后 kill -9 <pid>（该 pid 已通过验身，属本脚本实例）。" >&2
      exit 1
    fi
    echo "[coldstart] 旧实例清理完成。"
  fi
fi

# ── 步骤 2：清空旧 home（保留已配置的模型），保证真·冷启动 ──
# 保留列 = 顶层精确名匹配的三个模型配置件（详见文件头 5)）+ secrets 条目（文件头 9)：
# 运行时密钥子树，实目录或符号链接都保留，永不删）；其余顶层条目（含点文件/点目录）
# 一律真删。home 不存在 ⇒ 只建不删（mkdir -p，原语义照旧）。
# 保留清单与删除清单每轮都打印（DRY_RUN 下亦打印），使外部可由 stdout 核对留/删。
KEEP_FILES="nebflow.json models.json model-presets.json"
KEEP_ENTRY_SECRETS="secrets"
print_keep_report() {
  local f
  echo "[coldstart] 保留清单（顶层精确名匹配）:"
  for f in $KEEP_FILES; do
    if [ -e "$COLDSTART_HOME/$f" ]; then
      echo "[coldstart]   保留 $COLDSTART_HOME/$f （命中）"
    else
      echo "[coldstart]   保留 $COLDSTART_HOME/$f （未命中：本次未生成，跳过）"
    fi
  done
  if [ -e "$COLDSTART_HOME/$KEEP_ENTRY_SECRETS" ] || [ -L "$COLDSTART_HOME/$KEEP_ENTRY_SECRETS" ]; then
    echo "[coldstart]   保留 $COLDSTART_HOME/$KEEP_ENTRY_SECRETS （命中：运行时密钥子树，文件头 9)）"
  else
    echo "[coldstart]   保留 $COLDSTART_HOME/$KEEP_ENTRY_SECRETS （未命中：步骤 2.7 将建持久链接）"
  fi
}
# 删除谓词（两处使用同一段，DRY_RUN 列表与实删口径一致）：
# 顶层条目 减去 KEEP_FILES 三件 减去 secrets 保留条目。
find_delete_args() {
  find "$COLDSTART_HOME" -maxdepth 1 -mindepth 1 \
    ! -name 'nebflow.json' ! -name 'models.json' ! -name 'model-presets.json' \
    ! -name "$KEEP_ENTRY_SECRETS" \
    -print 2>/dev/null
}
if [ "$DRY_RUN" = "1" ]; then
  echo "[coldstart] [DRY_RUN] 将选择性清理 HOME=${COLDSTART_HOME}（清旧 sessions/注册/锁；保留模型配置件与 secrets）"
  print_keep_report
  if [ -d "$COLDSTART_HOME" ]; then
    echo "[coldstart] [DRY_RUN] 将删除以下顶层条目:"
    find_delete_args | sed 's/^/[coldstart]   - /'
  else
    echo "[coldstart] [DRY_RUN] $COLDSTART_HOME 不存在，将仅 mkdir -p（无条目可删）"
  fi
else
  echo "[coldstart] 选择性清理旧 home: ${COLDSTART_HOME}（保留模型配置件与 secrets）"
  mkdir -p "$COLDSTART_HOME"
  print_keep_report
  # 删除清单先落 stdout、再执行删除（同谓词、无并发写者 ⇒ 两遍所见一致）：即使清理中途中断，
  # 配置也只是留在原位，不会滞留在别处。
  echo "[coldstart] 删除清单（顶层非保留条目，实删）:"
  find_delete_args | sed 's/^/[coldstart]   - /'
  find "$COLDSTART_HOME" -maxdepth 1 -mindepth 1 \
    ! -name 'nebflow.json' ! -name 'models.json' ! -name 'model-presets.json' \
    ! -name "$KEEP_ENTRY_SECRETS" \
    -exec rm -rf {} + || true
  echo "[coldstart] home 已选择性清理: $COLDSTART_HOME"
fi

# ── 步骤 2.5：主 home 模型配置种子（批③；清理后执行 ⇒ 「种子→后续冷启动原地保留」协同）──
# 主 home 解析与 Scala 端 PathUtil.dataRoot 同序：NEBFLOW_HOME（Branding.env 首选）>
# $HOME/<brand 目录>；brand 目录名从 brand.conf 现读，🔴 不写死绝对路径。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# brand.conf 缺席（脚本被单拷走）时管道失败 ⇒ `|| true` 兜底（pipefail 下退化为默认 .nebflow）。
BRAND_DIR="$(sed -n -E 's/^[[:space:]]*homeDirName[[:space:]]*=[[:space:]]*([^[:space:]#]+).*/\1/p' "$SCRIPT_DIR/../brand.conf" 2>/dev/null | tail -1 || true)"
BRAND_DIR="${BRAND_DIR:-.nebflow}"
MAIN_HOME="${NEBFLOW_HOME:-$HOME/$BRAND_DIR}"
echo "[coldstart] 模型配置种子来源（主 home）: $MAIN_HOME"
seed_model_configs() {
  local f src dst
  for f in $KEEP_FILES; do
    src="$MAIN_HOME/$f"
    dst="$COLDSTART_HOME/$f"
    if [ -e "$dst" ]; then
      echo "[coldstart]   种子 $f ：新 home 已有（冷启动保留件），不覆盖 → $dst"
    elif [ -f "$src" ]; then
      if [ "$DRY_RUN" = "1" ]; then
        echo "[coldstart] [DRY_RUN]   种子 $f ：将 cp -p $src → $dst"
      else
        cp -p "$src" "$dst"
        # nebflow.json 可能含 provider 密钥（LLM 块）：种入即收紧为 owner-only，
        # 与仓内凭据件写后 ACL 纪律（CredentialWriteAcl）同向；其余两件为能力注记，保原权限。
        if [ "$f" = "nebflow.json" ]; then
          chmod 600 "$dst" 2>/dev/null || true
        fi
        echo "[coldstart]   种子 $f ：已拷入 $dst ← $src"
      fi
    else
      echo "[coldstart]   种子 $f ：主 home 无此件（$src 不存在）→ 跳过；模型可在 web UI 设置"
    fi
  done
  echo "[coldstart] 种子面仅限三件模型配置；device.json / auth.json（身份与凭据面）明确不拷、零触碰。"
}
if [ "$COLDSTART_FULL" = "1" ]; then
  echo "[coldstart] COLDSTART_FULL=1 ⇒ 跳过模型配置种子：home 全零携带（出厂档）；模型/Provider 请在 onboarding 或设置面现配。"
elif [ -d "$MAIN_HOME" ]; then
  seed_model_configs
else
  echo "[coldstart] 主 home $MAIN_HOME 不存在 ⇒ 无配置可种；首次模型配置请走 web UI 设置。"
fi

# ── 步骤 2.6：staging 档注入入网目标（批①；off/prod 档不注入）──
# 注入面 = <home>/neblink/config.json（NeblinkConfig.load 的唯一读取点），url + networkId
# （解码必填）、零凭据。已存在 config.json ⇒ 绝不覆盖（作者手配 / 上轮登录凭据优先）。
# 清理步已删整个 neblink/ 目录，故正常路径下这里总是「新建」；DRY_RUN 只预告不写。
seed_staging_target() {
  local cfg_dir="$COLDSTART_HOME/neblink"
  local cfg="$cfg_dir/config.json"
  if [ -e "$cfg" ]; then
    echo "[coldstart] staging 注入：已存在 ${cfg}，不覆盖（保留现状）。"
    return
  fi
  if [ "$DRY_RUN" = "1" ]; then
    echo "[coldstart] [DRY_RUN] staging 注入：将写入 $cfg （{\"neblinkServer\":{\"url\":\"$COLDSTART_STAGING_SERVER\",\"networkId\":\"$COLDSTART_STAGING_NETWORK\"}}）"
    return
  fi
  mkdir -p "$cfg_dir"
  printf '{\n  "neblinkServer" : {\n    "url" : "%s",\n    "networkId" : "%s"\n  }\n}\n' "$COLDSTART_STAGING_SERVER" "$COLDSTART_STAGING_NETWORK" >"$cfg"
  echo "[coldstart] staging 注入：已写入 $cfg （neblinkServer.url=${COLDSTART_STAGING_SERVER} networkId=${COLDSTART_STAGING_NETWORK}）——登录即指向 staging relay。"
}
if [ "$NET_TIER" = "staging" ]; then
  seed_staging_target
fi

# ── 步骤 2.7：运行时密钥持久位（文件头 9)；悬空引用事故根治）──
# <home>/secrets → $COLDSTART_SECRETS_DIR（0700）符号链接。引擎把通道密钥写进
# <dataRoot>/secrets（SocialChannels.writeSecret 硬语义），home 在 /tmp ⇒ 该子树
# 曾随 macOS 清理蒸发、留下悬空引用。链接在每次冷启动前确保存在；历史实目录先
# 搬迁再换链接（home 内副本视作活态、覆盖同名）；ACL 收窄由引擎经链接落在真实
# 目录上（NIO 解析父链接），本脚本只对持久目录收 0700。
COLDSTART_SECRETS_DIR="${COLDSTART_SECRETS_DIR:-$MAIN_HOME/coldstart-secrets}"
ensure_coldstart_secrets() {
  local link="$COLDSTART_HOME/secrets"
  if [ -L "$link" ] && [ "$(readlink "$link")" = "$COLDSTART_SECRETS_DIR" ]; then
    mkdir -p "$COLDSTART_SECRETS_DIR"
    chmod 700 "$COLDSTART_SECRETS_DIR" 2>/dev/null || true
    echo "[coldstart] 密钥持久位就绪（链接已在）: $link → $COLDSTART_SECRETS_DIR"
    return
  fi
  if [ -d "$link" ] && [ ! -L "$link" ]; then
    # 历史实目录：一次性搬迁（保权限），home 内副本为活态 → 覆盖同名后换链接。
    if [ "$DRY_RUN" = "1" ]; then
      echo "[coldstart] [DRY_RUN] 密钥搬迁: 将 cp -pR $link/. → $COLDSTART_SECRETS_DIR/ 后以符号链接替换 $link"
      return
    fi
    mkdir -p "$COLDSTART_SECRETS_DIR"
    cp -pR "$link/." "$COLDSTART_SECRETS_DIR/" 2>/dev/null || true
    chmod 700 "$COLDSTART_SECRETS_DIR" 2>/dev/null || true
    rm -rf "$link"
    ln -s "$COLDSTART_SECRETS_DIR" "$link"
    echo "[coldstart] 密钥持久位: 历史实目录已搬迁并替换为链接 $link → $COLDSTART_SECRETS_DIR"
    return
  fi
  if [ "$DRY_RUN" = "1" ]; then
    echo "[coldstart] [DRY_RUN] 密钥持久位: 将建目录 ${COLDSTART_SECRETS_DIR}（0700）+ 链接 $link → 该目录"
    return
  fi
  mkdir -p "$COLDSTART_SECRETS_DIR"
  chmod 700 "$COLDSTART_SECRETS_DIR" 2>/dev/null || true
  # 悬空链接 / 指向他处的旧链接：一律重建为本批语义（决不跟随未知目标删数据）。
  rm -f "$link" 2>/dev/null || true
  ln -s "$COLDSTART_SECRETS_DIR" "$link"
  echo "[coldstart] 密钥持久位: $link → $COLDSTART_SECRETS_DIR （通道密钥落持久盘；/tmp 只放可丢缓存）"
}
if [ ! -d "$MAIN_HOME" ] && [ "$DRY_RUN" != "1" ]; then
  echo "[coldstart] 主 home $MAIN_HOME 不存在 ⇒ 密钥持久位将由 mkdir -p 现建（$COLDSTART_SECRETS_DIR，仍持久）"
fi
ensure_coldstart_secrets

# ── 步骤 3：启动全新实例（DRY_RUN 跳过）──
if [ "$DRY_RUN" = "1" ]; then
  echo "[coldstart] [DRY_RUN] 预览结束，未启动 sbt。"
  exit 0
fi

# ── 步骤 3 前置：开门载体（文件头 8)）——一次性档案重建 + wrapper 注入 ──
# 档案每轮启动【独立目录】（-$$ 后缀）：上一轮的门窗 Chrome 可能还开着（门窗页
# 不随实例停止关闭），固定路径会撞出两层事故——① rm -rf 把运行中 Chrome 脚下的
# 档案目录抽掉；② 旧窗进程被开门观察误判成本轮开门。独立目录 + 「有活进程引用
# 则不删」的陈档清理双双根除（陈档 = 上轮遗留，实例停止后可整目录删除的语义不变）。
# 重建提前到 sbt 启动【之前】：boot 自动开门（实例侧）在就绪时即用此档案开门。
# wrapper = 可执行件（内部写死 Chrome 路径 + 档案参数），经 NEBFLOW_OPEN_BROWSER
# 传给实例的 openBrowser——单 token exec、URL 由引擎追加（零 shell、零分词）。
# 放进 $COLDSTART_HOME：下轮清理步顺带回收，无残留。
CHROME_PROFILE="/tmp/stand-coldstart-console-profile-$$"
CHROME_PRESENT=0
if [ -x "$CHROME_BIN" ]; then CHROME_PRESENT=1; fi
LAUNCH_FLAGS=""
if [ "$CHROME_PRESENT" = "1" ]; then
  # 陈档清理（含旧版固定路径 /tmp/stand-coldstart-console-profile）：仅当无活进程
  # 引用才删——门窗还开着的档绝不抽底（绝不重演 rm -rf 抽运行中 Chrome 的档案）。
  for stale in /tmp/stand-coldstart-console-profile /tmp/stand-coldstart-console-profile-*; do
    [ -d "$stale" ] || continue
    [ "$stale" = "$CHROME_PROFILE" ] && continue
    if pgrep -f "user-data-dir=$stale" >/dev/null 2>&1; then
      echo "[coldstart] 陈档保留（门窗仍开着）: $stale"
    else
      rm -rf "$stale"
      echo "[coldstart] 陈档已清（无进程引用）: $stale"
    fi
  done
  mkdir -p "$CHROME_PROFILE"
  OPEN_DOOR="$COLDSTART_HOME/open-door.sh"
  sh_quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
  printf '#!/bin/sh\n# coldstart 开门载体（每轮重建；引擎 openBrowser 追加 URL 调用）\nexec %s --user-data-dir=%s --no-first-run --no-default-browser-check "$@"\n' \
    "$(sh_quote "$CHROME_BIN")" "$(sh_quote "$CHROME_PROFILE")" >"$OPEN_DOOR"
  chmod +x "$OPEN_DOOR"
  # 双前缀都导出：与 Branding.env 读取序对齐（STAND_ 现役前缀优先，NEBFLOW_ 永久回退）。
  export STAND_OPEN_BROWSER="$OPEN_DOOR"
  export NEBFLOW_OPEN_BROWSER="$OPEN_DOOR"
  echo "[coldstart] 开门载体就绪: 一次性档案=${CHROME_PROFILE}（每轮重建） wrapper=${OPEN_DOOR}"
  echo "[coldstart] 恢复 boot 自动开门（无 --no-browser）：就绪后实例经 PortalUrl 解析开门目标"
else
  # 无一次性载体 ⇒ 宁可不开门：保留 --no-browser，绝不让实例用默认档案浏览器开门
  # （默认档案的 Logto SSO 域名级共享 = 静默签入面）；脚本在就绪后打印无痕窗指引。
  LAUNCH_FLAGS="--no-browser"
  echo "[coldstart] Chrome 缺席（$CHROME_BIN 不可执行）⇒ 实例带 --no-browser 启动（宁可不开门，不用默认档案开门）；就绪后打印无痕窗口指引"
fi

# ── 开门目标解析（与 PortalUrl 既有解析序同向：env > config > brand 缺省）──
# 仅用于打印/回落探测；权威目标由引擎 PortalUrl.startupUrl 单点解析，脚本不复制判定。
OPEN_UI_VAL="${STAND_OPEN_UI:-${NEBFLOW_OPEN_UI:-}}"
OPEN_UI_LOCAL=0
case "$OPEN_UI_VAL" in [lL][oO][cC][aA][lL]) OPEN_UI_LOCAL=1 ;; esac
PORTAL_BASE="${STAND_PORTAL_URL:-${NEBFLOW_PORTAL_URL:-}}"
if [ -z "$PORTAL_BASE" ] && [ -f "$SCRIPT_DIR/../brand.conf" ]; then
  PORTAL_BASE="$(sed -n -E 's/^[[:space:]]*portalUrl[[:space:]]*=[[:space:]]*([^[:space:]#]+).*/\1/p' "$SCRIPT_DIR/../brand.conf" 2>/dev/null | tail -1 || true)"
fi

# ── 官网可达性预探（文件头 8) 回落触发器；启动前一次，必然早于 boot 开门）──
# 门户不可达时若仍让引擎开官网，用户只会得到一张浏览器报错页 = 死在门外。故启动前
# 预探（≤5s）：可达 → 官网统一入口档照常；不可达 → 显式回落【本地开门档】（导出
# NEBFLOW_OPEN_UI=local——PortalUrl 既有回落语义，引擎自己走 LocalConsoleUrl 自铸
# 票开门），并大声打印回落原因。不另造开关、不改解析序：用的就是既有 env 的既有语义。
# 预探通过而 boot 时刻门户才断（探测窗口外抖动）属残余风险：开门后页面报错时按
# 打印指引 NEBFLOW_OPEN_UI=local 重跑即可。
if [ "$CHROME_PRESENT" = "1" ] && [ "$OPEN_UI_LOCAL" = "0" ] && [ -n "$PORTAL_BASE" ]; then
  if curl -sS -o /dev/null -m 5 "$PORTAL_BASE" 2>/dev/null; then
    echo "[coldstart] 官网可达: $PORTAL_BASE → 本次走官网统一入口档（真实凭据登录）"
  else
    export STAND_OPEN_UI=local
    export NEBFLOW_OPEN_UI=local
    OPEN_UI_LOCAL=1
    echo "[coldstart] WARN: 官网/门户探测不可达（${PORTAL_BASE}，超时 5s）→ 本次自动回落【本地开门档】（等价 NEBFLOW_OPEN_UI=local；PortalUrl 既有回落语义，非新开关）——冷启动绝不死在门外"
  fi
fi

# 实例启动（文件头 8)）：开门目标经 PortalUrl 单点解析（env > config > brand 缺省 =
# 官网统一入口）；开门载体由 NEBFLOW_OPEN_BROWSER 注入（无载体 = --no-browser 关闭
# boot 开门）。sbt run = Main 入口，--home / --port / --no-browser 均为全局旗标
# （安全形态）；NEBFLOW_GATEWAY_PORT 为 fork-proof 主保险（随进程树继承到 fork 出
# 的实例 JVM，NEBFLOW_OPEN_BROWSER 同机制随之注入）。
# 注：禁止 `java nebflow.gateway.GatewayMain` 直启 —— 它带参即 fail-fast，且非用户入口。
echo "[coldstart] 启动实例…"
NEBFLOW_GATEWAY_PORT=$COLDSTART_PORT sbt "run --home $COLDSTART_HOME --port $COLDSTART_PORT $LAUNCH_FLAGS" &
SBT_PID=$!
cleanup() { kill "$SBT_PID" 2>/dev/null || true; }
trap cleanup EXIT

# 就绪轮询：端口开始 LISTEN 即视为就绪。sbt 首次编译可能较久（上限 ~5 分钟）；
# 超时不杀实例，打印手动开门说明后继续陪跑。
echo "[coldstart] 等待实例就绪（端口 ${COLDSTART_PORT}，每 3s 复查）…"
READY=0
for _ in $(seq 1 100); do
  if lsof -tiTCP:"$COLDSTART_PORT" -sTCP:LISTEN 2>/dev/null | grep -q .; then READY=1; break; fi
  sleep 3
done

# ── 开门目标显示口径的设备标识补充（device.json 由实例 boot 铸出，此处现读）──
# 仅用于打印/回落探测；权威目标由引擎 PortalUrl.startupUrl 单点解析，脚本不复制判定。
DEVICE_ID=""
if [ -f "$COLDSTART_HOME/device.json" ]; then
  DEVICE_ID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("deviceId",""))' "$COLDSTART_HOME/device.json" 2>/dev/null || true)"
fi
portal_with_device() {
  local base="$1" enc
  if [ -n "$DEVICE_ID" ]; then
    enc="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$DEVICE_ID" 2>/dev/null || true)"
    if [ -n "$enc" ]; then
      case "$base" in
        *\?*) printf '%s&device=%s' "$base" "$enc" ;;
        *)    printf '%s?device=%s' "$base" "$enc" ;;
      esac
      return
    fi
  fi
  printf '%s' "$base"
}

if [ "$READY" = "1" ]; then
  if [ "$OPEN_UI_LOCAL" = "1" ]; then
    echo "[coldstart] 开门档: NEBFLOW_OPEN_UI=local ⇒ 实例开门走【本地自铸票】（PortalUrl 回落语义 LocalConsoleUrl；不经官网登录）"
  else
    echo "[coldstart] 开门目标: 官网统一入口 $(portal_with_device "$PORTAL_BASE")（PortalUrl 解析序；登录后经门户回投 relay-mint 票进本地控制台）"
  fi
  # 观察实例侧 boot 开门是否落地：一次性档案的 Chrome 进程出现（≤10s 轮询；
  # 避免与 boot 开门时序竞判为失败）。NEBFLOW_OPEN_UI=local 时引擎走 LocalConsoleUrl
  # 自铸票，同一 wrapper 档案开门，观察口径相同。
  DOOR_OPENED=0
  if [ "$CHROME_PRESENT" = "1" ]; then
    # 模式不带前导「-」（BSD pgrep 会把 --user-data-dir 当选项）；子串匹配命令行。
    for _ in $(seq 1 10); do
      if pgrep -f "user-data-dir=$CHROME_PROFILE" >/dev/null 2>&1; then DOOR_OPENED=1; break; fi
      sleep 1
    done
  fi
  if [ "$DOOR_OPENED" = "1" ]; then
    echo "[coldstart] 已自动开门（实例侧 boot 开门落地）。浏览器=一次性档案（零 cookie/零 SSO ⇒ 凭据页必然出现，不被静默签入）"
    echo "[coldstart] 控制台地址(参考): http://localhost:$COLDSTART_PORT/ ｜ 无法走通官网链时可用 NEBFLOW_OPEN_UI=local 重跑（显式本地档）"
  else
    # ── 回落路径（文件头 8)）：仅在此腿才允许 localhost 自铸票直开，且必打印说明 ──
    echo "[coldstart] WARN: 未观察到实例自动开门（Chrome 缺席 / wrapper 未落地 / 实例侧开门失败）→ 走脚本回落开门"
    FALLBACK_URL=""
    PORTAL_UP=0
    if [ "$OPEN_UI_LOCAL" = "0" ] && [ -n "$PORTAL_BASE" ]; then
      if curl -sS -o /dev/null -m 5 "$PORTAL_BASE" 2>/dev/null; then PORTAL_UP=1; fi
    fi
    if [ "$PORTAL_UP" = "1" ]; then
      # 门户可达：回落目标仍是官网入口（实例侧开门失败 ≠ 官网不可用）——保持
      # 「官网统一入口 + 真实凭据登录」形态，只换开门执行者。
      FALLBACK_URL="$(portal_with_device "$PORTAL_BASE")"
      echo "[coldstart] 回落开门: 门户可达 → 用一次性档案打开官网入口（真实凭据登录 → 门户回投进本地控制台）: $FALLBACK_URL"
    else
      # 官网/门户不可达（或本地档）：自铸一次性开门票 → 本地直开，明确回落说明。
      # 铸票失败不拦启动：退化为开门面指引。
      OPEN_URL="http://localhost:$COLDSTART_PORT/"
      TOKEN=""
      for _ in $(seq 1 10); do
        [ -f "$COLDSTART_HOME/auth.json" ] && break
        sleep 1
      done
      if [ -f "$COLDSTART_HOME/auth.json" ]; then
        TOKEN="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])))' "$COLDSTART_HOME/auth.json" 2>/dev/null || true)"
      fi
      if [ -n "$TOKEN" ]; then
        TICKET="$(curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' \
          "http://127.0.0.1:$COLDSTART_PORT/api/auth/console-ticket" \
          | python3 -c 'import json,sys;print(json.load(sys.stdin).get("ticket",""))' 2>/dev/null || true)"
        if [ -n "$TICKET" ]; then
          ENC="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$TICKET" 2>/dev/null || true)"
          [ -n "$ENC" ] && OPEN_URL="http://localhost:$COLDSTART_PORT/?ticket=$ENC"
        fi
      fi
      FALLBACK_URL="$OPEN_URL"
      echo "[coldstart] 回落开门: 官网/门户不可达 → 明确回落【本地直开】（break-glass 自铸票，120s 一次性；不经官网登录——账号互联请在控制台内走设备互联登录链）: $FALLBACK_URL"
    fi
    if [ "$CHROME_PRESENT" = "1" ] && [ -n "$FALLBACK_URL" ]; then
      "$CHROME_BIN" --user-data-dir="$CHROME_PROFILE" --no-first-run --no-default-browser-check "$FALLBACK_URL" >/dev/null 2>&1 &
      echo "[coldstart] 回落开门已执行（一次性档案，零 cookie/零 SSO）"
    else
      echo "[coldstart] 未执行一次性档案开门（Chrome 缺席）⇒ 请用浏览器【无痕窗口】打开: ${FALLBACK_URL:-$(portal_with_device "${PORTAL_BASE:-http://localhost:$COLDSTART_PORT/}")}"
      echo "[coldstart] （无痕窗口同样隔离本机 cookie / Logto SSO；普通窗口会被静默签入你已登录的账号）"
    fi
  fi
else
  echo "[coldstart] WARN: 就绪等待超时（实例可能仍在编译）。就绪后请手动用无痕窗口打开: $(portal_with_device "${PORTAL_BASE:-http://localhost:$COLDSTART_PORT/}")（或 NEBFLOW_OPEN_UI=local 重跑走本地档）"
fi

# 陪跑：Ctrl+C 与 sbt 同进程组直达实例；退出码原样透传。
set +e
wait "$SBT_PID"
EXIT_CODE=$?
set -e
echo "[coldstart] 实例已停止（exit=${EXIT_CODE}）。"
exit "$EXIT_CODE"
