# 修复批 · 感知「连接邮箱」Missing token —— 定因（relay ingress 门）+ 面板鉴权伞接线报告

日期：2026-10-26　分支：`delegate/delegate-nebflow-c2e6b6d0`（基点 21f19dfaf）
症状（作者 12:43 报障，截图在案）：感知面板「连接邮箱」对话框填好邮箱+授权码点「连接」，红字报「Missing token」；引擎日志 12:39-12:43 窗口**零**感知路由鉴权拒绝记录（请求没到引擎）。

---

## 1. 定因链（证据 → 机械链）——两条既定假设均不成立

### 1.1 假设 A「面板没接会话令牌组装链」——不成立（逐行核对）

`perception.js:25-43` 的 `getToken()/authHeaders()/api()` 与全部已工作面板**逐字同构**（`agentManager.js:20-23`、`daemonPanel.js:112-115`、`modelPanel.js:40-43` 等，同一 `key('token')` localStorage 派生 + 同款 Bearer 头）。无令牌时 `authHeaders()` 返回 `{}`、`api()` 照发请求——**面板根本没有「就地拒绝」的 client-side 守卫**，任务简报推断的前端自查代码不存在。

### 1.2 假设 B「NEBFLOW_OPEN_UI=local 自铸票没覆盖该路由」——不成立

自铸票链（`LocalConsoleUrl.issue` → `POST /api/auth/console-ticket` → `?ticket=` 兑换 HttpOnly 会话 cookie，`LocalConsoleUrl.scala:15-43`）是**路由无关**的会话面，不存在「覆盖/不覆盖某路由」的形态；且下一节证明响应体来自 relay 门，与本地自铸票链无涉。

### 1.3 真定因：relay ingress 门 403，请求死于隧道之前

**「Missing token」四个字在引擎全树零生产点**（grep `src/main` 零命中）；它的唯一生产者是 **link-server（Rust）ingress 门** `require_user`：

- 无 cookie/票死臂：`return Err(forbidden("Missing token"))`（link-server 仓 `src/routes.rs:601`；本地只读挂载 `/Users/kaiyu/.stand/projects/link-server`）；
- 控制台同源 `/api/{*path}` 全部落 `console_ingress_http`（`main.rs:649` → `routes.rs:3844`）：**先过 `require_user`，拒绝即裸 403 返回，隧道帧根本不发**——与引擎日志零痕、conn-guard `tunnelReq=2` 平线（12:38-12:41 快照，`stand.log`）完全一致；
- 时点机制：#907（今日 10:31 提交，link-server 仓 `58b41d8`）注释自证修复前形状——access cookie max-age=JWT TTL=900s，浏览器 15 分钟即删 cookie，删后「missing-cookie 臂」早返回「Missing token」、**不查仍活着的 refresh 行**；而已建立的 WS 照常工作 ⇒「控制台看着活着、HTTP 全门 403」。作者 12:43 的会话正处于该形状（生产 relay 尚未部署 #907 重建）。

**完整机械链**：作者浏览器（relay 源控制台，WS 活着）→ 提交 `POST /api/perception/mail/connect`（**带**引擎 Bearer 令牌，见 1.1）→ relay ingress `require_user` 403 `{"error":"Missing token"}` → 隧道零帧、引擎零痕 → 前端 `api()` 返回 `r.data.error` → 配置卡 `setResult(..., r.data.error)` **原样透传门错误体**（修前 `perception.js:466`）→ 作者看到红字「Missing token」。

**旁证**：面板打开腿 `GET /api/perception/entries` 同样 403，但 `renderPanel` 对 GET 失败静默（`perception.js:184` 的 `if (r.ok && ...)`）照常渲染未配置占位条目——作者得以一路点到「连接」，全程无其他可见异常；`positionPanel` 重写批（47cef9cf8）只动定位/占位，不触配置卡提交链，回归面排除。

### 1.4 引擎侧对照形状（本机真例实测，见 §3）

引擎自身鉴权拒绝 = `403 {"error":"Unauthorized"}` + `X-Nf-Reason: credential-required`（`RestApiCtx.scala:71-78`，稳定性批 2026-10-26 的可分形出向）。两种 403 可分形：**带 reason = 引擎形状**（可静默重座）；**无 reason JSON「Missing token」= relay 门形状**（须重登 relay）。

## 2. 修复（仓内面）

任务简报的修复指令前提（令牌组装链漏接）经 1.1/1.2 证伪；按其**降级选项的精神**（「先尝试请求，auth 拒才引导重新打开控制台——不得无 token 就地拒绝」）落地仓内真缺陷面：感知面板是控制台**最后一个未接 `sessionGate` 伞的 REST 面**（scan-bind-403 / graph-button-403 / projects 三批已接各自面，感知批昨晚上线时未接）。

| 面 | 修前 | 修后 |
|---|---|---|
| `perception.js api()`（13 个调用点单一咽喉） | 401/403 静默返回，无自愈 | `resp.status === 401 \|\| 403 ⇒ noteAuthRejected('perception')`——静默重座优先（portal status → console-ticket → 导航重座；relay 门真死走 ingress 登录腿），单飞+60s 冷却防环在本体；WS 活着 + HTTP 403 的既有盲区由伞统一收口 |
| 连接卡失败文案 | `r.data.error` 原样透传（「Missing token」直达作者） | `authErrMsg()`：401/403 ⇒ `perception.sessionExpired`（「登录状态已过期，正在重新进入控制台…」）；**业务错误（服务商测连失败等）仍透传服务端原文**——直达作者的字一个不少 |
| 快照卡「照此发送」/ 画布「发送」失败文案 | 同上透传 | 同 `authErrMsg` 处理 |
| locales zh-CN/en | — | 新增 `perception.sessionExpired` 一键（双语） |

调用点取舍：toggle/remove/abandon/OTP 消费等无文案面的调用由 `api()` 咽喉伞统一覆盖；`GET entries` 打开腿 403 亦拉伞（面板自愈，A3 钉）。业务面从不出 401/403（PerceptionRoutes 全表核对：200/400/404/409/502），判据零误报面。

## 3. 验证

### 3.1 前端钉（playwright harness，REAL perception.js + 真 sessionGate）

`tests/mail-perception.spec.mjs` 新增「鉴权拒绝脸」4 钉（A1-A4）+ 全套既有 8 钉回归：

- A1 connect 403「Missing token」⇒ 卡内文案 = 会话引导、**不出现裸错误体**、鉴权伞拉动（重座腿收 `'perception'`）、重座接管零横幅零 reload；
- A2 connect 400（服务商测连失败）⇒ 服务端 error 原文透传 + **伞不拉**（业务错误≠会话死亡，回归钉）；
- A3 面板打开 entries 403 ⇒ 咽喉拉伞（面板自愈）；
- A4 快照「照此发送」403 ⇒ 回执行会话引导。
- harness 附带：`spawn python3` 静态服务换 node 实现（KAI Windows 无 python，test6 分诊已立案失败类；MIME 显式声明保 ES module 语义，Mac/Windows 双栖）。

### 3.2 引擎侧契约钉（新增 Scala spec，随批提交）

`src/test/scala/nebflow/gateway/PerceptionRoutesAuthGateSpec.scala`：感知 connect/entries 无凭据 ⇒ 403「Unauthorized」+ reason 头（by-name 门，null 依赖安全，ApiGuardFollowupRoutesSpec 同法）。**本批运行注记**：共享检出上另有 #919/#920 两批在飞、sbt target 争用（sbt-server 管道锁 error 5 + 增量编译幻象错），spec 编译运行延至队列空窗；同一契约已由 §3.3 探针①在真实例上**先行实测为真**（更强证据层），spec 作为回归钉留存。

### 3.3 真链路 E2E（真实例 + 真浏览器 + 伪 IMAP，`scripts/e2e-perception-authchain.mjs`）

隔离纪律：java 直启 assembly（今日 08:24 `stand-assembly-2026.10.26-beta.1.jar`——本批仅改 js，引擎行为与新版逐字同）+ 独立 `--home` + 端口 8091（≠8080/6138）+ 杀前验身 + 端口在位检查（run-1 泄漏教训入码）。前端面板经 playwright route 拦截注入仓内**修后** perception.js（零重打包验证修后码）。

（证据行见批执行输出/截图目录 `.nebflow/evidence/20261026_missing-token-authchain/`；**判词 `E2E-PERC-AUTHCHAIN: PASS`，11/11 检查绿**，含 4 步截图取证——终帧 `4-connect-result.png` 实见红字=引擎 MailClient 原文）

- ①a-①d 鉴权形状探针：无凭据 connect/entries ⇒ 403 + `{"error":"Unauthorized"}` + `X-Nf-Reason: credential-required`，体内**永不出「Missing token」**；
- ②a console-ticket 铸票 200（自铸票链活着）；
- ②b-②f 写穿链：真控制台 → 感知面板 → 连接邮箱表单（假凭据 + 高级服务器指向本机伪 IMAP）→ 提交 ⇒ 伪 IMAP 伪件收到**引擎的真实出站连接，首包字节为 TLS ClientHello**（JavaMail 隐式 SSL 形态——能触发该出站连接的唯一路径是引擎 `/connect` 处理器走完鉴权→body 解析→preset 解析→自定义服务器采纳→MailClient 连箱，UI→引擎→IMAP 全链写穿实证）；卡片显示**引擎侧 MailClient 失败原文**「无法连接 127.0.0.1:<port> · 请检查网络」（非 Missing token、非会话引导——400 业务失败伞不误拉）。

### 3.4 生产面处置（工程台账，本仓外）

作者侧 12:43 卡死的根（relay 门）已由 link-server 仓 #907（`58b41d8`，今日 10:31）定因修复（access cookie 保留窗与 JWT TTL 解耦 + 无 cookie 滑动准入 + refresh 行滑动续期），**待部署**；部署前作者即时自救 = relay 控制台重新登录（新 15min cookie 在位即恢复）。本仓面的伞接线让未来任何会话面死亡在感知面板上表现为「静默重座/引导」，不再死底裸文。

## 4. 改动清单

| 件 | 变更 |
|---|---|
| `src/main/resources/web/js/perception.js` | `api()` 鉴权伞接线 + `authErrMsg()` 文案分流 + 三处失败文案面换 `authErrMsg` |
| `src/main/resources/web/js/locales/zh-CN.js` / `en.js` | `perception.sessionExpired` 新键（双语） |
| `tests/mail-perception.spec.mjs` | 「鉴权拒绝脸」4 钉 + 静态服务 node 化 |
| `tests/fixtures/mail-perception/harness.html` | 暴露 `window.__gate`（sessionGate 测试缝） |
| `src/test/scala/nebflow/gateway/PerceptionRoutesAuthGateSpec.scala` | 新增：感知路由门契约钉 |
| `scripts/e2e-perception-authchain.mjs` | 新增：真链路 E2E 驱动（本报告 §3.3） |

## 5. 未竟 / 移交

1. **relay #907 部署**：link-server 仓面（非本仓），部署后作者既有会话自动恢复（refresh 行活着即可被滑动准入）。
2. **PerceptionRoutesAuthGateSpec 的 sbt 运行**：队列空窗补跑（§3.2 注记；契约已被 §3.3① 实测）。
3. **伪 IMAP 的 LOGIN 明文腿（可选加固）**：JavaMail 隐式 SSL ⇒ 见 LOGIN 明文需 JVM 信任链（keytool 自签 + `-Djavax.net.ssl.trustStore` 启动注入）——写穿已由 TLS ClientHello 字节 + 引擎侧失败原文钉死，登录拒绝明文属锦上添花。
4. checkJs 门基线：本批 face（perception.js + locales）**零新增零报错**；门总读数 +3 来自同仓在飞批的 `ws.js`(2) 与新文件 `chat/renderGate.js`(1)——非本批面，不代管。
5. 作者 12:43 已填的真实凭据：只在浏览器表单态，服务端/日志零触碰（未动）。
