# 统一登录两路径 · 退役设备码 — 台账 #924 收口报告

分支：`delegate/delegate-nebflow-c2e6b6d0`（KAI Windows 仓）· 2026-10-26 · 先尖刺后建（作者令照办）

## 一行结论

**尖刺结论=可行**：部署租户（auth.nebflow.space）的 Logto **Experience API 族全部在位**，无头密码登录整链（交互挂载 → 密码标识识别 → identification → submit → 授权码 → 换票）已在租户上逐腿实探证实（零凭据形态探针）；据此落地路径②（CLI `stand login` 交互账密 → 本机网关第一方执行舞步 → 与路径①同一 register+persist 汇聚点），设备码流（CLI 旗标 / 网关路由 / 本仓协议词汇 / 前端回退面）整体退役，Mac 两仓（link-server 中继、stand-website 批准页）的退役面已盘点到文件级、按只读边界留待另批。

## 一、可行性尖刺（第一步，先证后建）

只读探针（PowerShell，零凭据、零真实账号；体验会话为 cookie 级临时态，等价打开托管页）：

| # | 探针 | 结果 | 判读 |
|---|------|------|------|
| 1 | `GET /oidc/auth?client_id=<AC>&redirect_uri=loopback&code_challenge…` | **303** → `/sign-in?app_id=…`，种下 `_logto(+sig)`、`_interaction(+sig)` | 交互可无浏览器挂载；AC app + loopback 回调活 |
| 2 | `GET/POST /api/experience`（无会话） | **405** Method Not Allowed（Koa 默认体） | 该版本不接受 GA 版 `POST` 创建 ⇒ 方言不同 |
| 3 | `PUT /api/experience {"method":"password"}` | 400 `guard.invalid_input`（Zod：要 `interactionEvent: 'SignIn'|'Register'|'ForgotPassword'`） | **v1-beta 方言**：创建/更新 = `PUT` + `interactionEvent` |
| 4 | `PUT /api/experience {"interactionEvent":"SignIn"}` | **204** | 体验会话建立成功 |
| 5 | `POST /api/experience/verification/password`（错误 schema） | 400 Zod：identifier 要 `{type: 'username'|'email'|'phone', value}` | 识别腿 schema 实锤 |
| 6 | 同上（正确 schema，不存在用户+假密码） | **422 `session.invalid_credentials`**「Incorrect account or password」 | 识别链活；**anti-enumeration**（不泄账号存在性）|
| 7 | `POST /api/experience/identification`（伪造 verificationId） | 404 `session.verification_session_not_found` | identification 腿活、结构化错误 |
| 8 | `POST /api/experience/submit`（未识别） | 404 `session.identifier_not_found` | submit 腿活、结构化错误 |

**判定：可行 → 接**。换票腿（`/oidc/token` 授权码+PKCE verifier）与 register 腿（`/api/device/register`）均为生产已验面的原样复用（浏览器路径同款）。租户版本固定为 v1-beta 方言，实现**逐字钉死该方言**（`PUT {interactionEvent}` + `{type,value}` 标识符）。

**边界尊重声明**：Logto 租户配置零改动（只读）；探针仅产生自过期交互/体验 cookie，无用户/应用/配置写入。

## 二、统一注册路径（作者裁定的落地形态）

- **路径①（有浏览器）**：原 portal 深链 PKCE 流**零改动**（`auth/start` → 托管页 → loopback `/auth/callback` → 换票 → register+persist）。回归面：`AuthRoutes`/`LogtoAuthCode` 仅随传输层改名（`LogtoDeviceFlow`→`LogtoTransport`）无行为差。
- **路径②（无头）**：`stand login` 交互提示账号+密码（终端隐藏回显；无终端如实警告后明读）→ `POST /api/neblink/auth/password`（**本机环回**、`bootstrapGate` 匿名+loopback-only，与 auth/start 引导腿同判据）→ 网关执行 Experience 舞步 → `LogtoTransport.register`（携带 `machineFingerprint`，与 PKCE 回调同参）→ `completeDeviceEnrollment(explicitUserAction=true)`（键入凭据即用户动作，与 PKCE 回调/配对码同类）→ 设备自动注册 + 上线。
- **汇聚点同一**：两路径的注册都落 `register` → `completeDeviceEnrollmentDetailed`（persist + 配置切换 + 隧道热换 + 免重启上线），设备列表/在线态天然一致。
- 密码**不过 CLI 直连第三方域**：CLI 只与本机网关说话（环回），舞步在网关进程内完成 —— 与任务书建议的「第一方端点代理」同构，代理面落在**本仓网关**（link-server 代理为跨仓增强项，见 §六 open items）。

## 三、落地清单（本仓写面）

新增：
- `neblink/LogtoTransport.scala` — 自旧设备流客户端提取的共享传输 + register 面（`Send`/`Request`(+`method`)/`jdkSend`/`formEncode`/`RegisterRejection`/`register`）；设备流专属（`start/poll/parseStartResponse/PollOutcome/classifyPoll`）随之消亡。
- `neblink/LogtoPasswordFlow.scala` — 路径②六腿舞步（方言钉死）+ cookie jar 纯合并 + 回调 Location 解析 + 闭集失败模型（`Transport/InvalidCredentials/RateLimited/Protocol`，**任何载荷零密码回显**）+ 生产 `jdkSend`（不自动跟 3xx——303 就是协议）。
- `gateway/NeblinkRoutes` 增 `POST /neblink/auth/password` + `performHeadlessLogin` + `LoginRateGuard`（本机爆破坝：每远端 10 败/15min 固定窗，成功清零；进程内存态，重启归零 fail-open）。

退役（本仓）：
- CLI：`--device-code` 旗标、H2 三行设备码提示、轮询腿（`pollLoop`/`PollStep`/`heartbeatLine`）、RFC 8628 稳定码（`expired_token`/`access_denied`/`flow_start_failed`/`poll_failed`/`timeout`）。`stand login` 语义换为无头账密登录；`logout`/`whoami` 不动。
- 网关：`/neblink/device-flow/start|poll` 两路由整段删除；`Protocol` 删 `LogtoOidc.deviceAuth` 与 `DeviceApi.code/token`（中继端点族的本仓调用词汇归零）。
- 前端：`neblink.js` 删 `startDeviceFlow/pollDeviceFlow/cancelDeviceFlow` + `userCode/deviceCode` 状态；`activityBar.js` 删 `waiting-device` 态/相关分支，`logto-not-configured` 从「回退触发」改「错误面」（三段式既有文案）；locales（en/zh）删 7 个设备流文案键。`socialPanel` 的扫码 `userCode` 与 slideblocks 插件自己的设备流（独立产品协议）**不在退役面**。
- 测试：`LogtoDeviceFlowSpec` 删除 ⇒ 新 `LogtoPasswordFlowSpec`（11 判据）+ `DeviceFlowRetiredSpec`（清理断言，见 §五）+ `HeadlessLoginE2ESpec`（env 门控，见 §六）；`LoginCommandSpec` 重写（闭集/文案逐字节/注入面整链）。
- `cli/GatewayClient.requestError`（契约面 #924）：非 2xx 包装在 body 带稳定 `code` 字段时追加 ` (code: <c>)` 后缀 —— 修前包装只透 `error` 人类文案，机器面（CLI 闭集分类的判据）被丢弃。加法改动：无 `code` 的 body 渲染逐字不变；`LoginCommandSpec` 增契约锚（有 code 带后缀 / 无 code 逐字旧形）。

## 四、安全自查（台账 #924 指定项）

- **密码零落日志**：网关路由日志只落**结果码**（`invalid credentials` / `status`/`reason`），account/password 不进任何 logger 行；失败响应体 = provider 稳定码 + 分类文案，不回显输入（spec 断言）。
- **密码零落盘**：CLI 侧 `readPassword` char[] 用后 `Arrays.fill` 清零；密码只存在于「输入 → POST 体 → 舞步第 3 腿请求体」的内存通路，无任何持久化写面（CLI 不触凭据文件的既有红线不变）。
- **传输**：CLI→网关 = 本机环回；网关→租户 = TLS（Direct15s 策略，与全部既有 Logto 对话同款）。
- **限速**：网关本机坝（10 败/15min/远端）+ 租户侧 anti-enumeration 实探证实。**未做**（边界）：Logto 租户侧爆破防护/速率配置 —— 租户只读，另批。
- **并发/复用**：舞步单飞无共享会话态；cookie jar 每次登录新建、用后即弃。

## 五、spec 面与回归

- `LogtoPasswordFlowSpec`：cookie 合并（覆盖/删除/确定性序）、redirect 解析、authorize URL（无 prompt 位）、失败闭集、**整链 happy path**（六腿齐全 + cookie 全程携带 + 换票带 verifier）、密码错 ⇒ `InvalidCredentials` 且**零回显**、state 不匹配/缺 code/缺 Location ⇒ Protocol、authorize 503/传输 0 ⇒ 分类正确。
- `LoginCommandSpec`：H1/H4/H5 逐字节锚不变；`--device-code` 参数面为空断言；新提示文案（无 code 字样）；H3 逐字；新闭集（新码在位 + 旧码不在位）；`failureOfDetail/failureOfMessage` 全分支；`signInWith` 注入面整链（成功/`--json`/三失败码/`no_input` 零出站/机器闸 409 ⇒ `register_failed`）。
- `DeviceFlowRetiredSpec`（清理断言）：退役字面量（`device-flow`、`deviceCode`、`userCode`、`--device-code`、`/oidc/device/auth`、`/api/device/code|token`、旧文案键、`LogtoDeviceFlow.scala` 文件存在性）在**活面文件清单**内零命中 + 两路径汇聚点正面存在性断言。排除面：slideblocks 插件（独立产品）、reports/evidence（档案）、Mac 两仓（跨仓）。
- 浏览器路径回归：PKCE 链无行为改动（传输改名级）；全仓测试与编译读数见 §七。

## 六、跨仓退役面（Mac 仓只读 → 另批执行清单）

本批在两仓**只读盘点**（未写一字节）：

1. **link-server（中继，Rust）** — 复活批提交 `9baec9a`（device-flow: 复活设备登录批准腿）：`src/deviceflow_test.rs`(554 行)、`src/routes.rs`(+171)、`src/store.rs`(+178)、`src/model.rs`(+34)、`src/main.rs`(+7)。退役 = revert/拆除该批准状态机 + `/api/device/code`、`/api/device/token` 端点族（2026-09-01 曾删，本次复活再拆）。旧码自然过期（900s 硬上限），无迁移包袱。
2. **stand-website（官网）** — 提交 `48c2d17`（/device/<code> 批准页，已在 main）：`app/api/device/approve/route.ts`、`app/api/device/info/route.ts`、`app/device/DeviceCodeEntry.tsx`、`app/device/[code]/DeviceApprovalClient.tsx`、`app/device/[code]/page.tsx`、`app/device/dict.ts`、`app/device/page.tsx`（7 文件 +625 行）。未部署 ⇒ 直接拆（revert 该提交或删文件）。
3. **link-server 第一方代理端点（增强项，非阻塞）**：本批代理面落在本机网关（环回），已满足「密码不过 CLI 直连第三方域」；若要把代理上移到 link-server（企业部署形态），需在该仓加第一方账密端点——协议同 §一，另批提请。

## 七、验证读数

- 编译：`Compile/compile` + `Test/compile` = **PASS**（绿轮次 13:55:07，134 测试源全量；中间红轮均为初级错误已修——两处 scaladoc 嵌套注释 `/*`、一处 `Files.exists` 重载推断、http4s DSL 双包 `IO.pure`、`Option(System.console())`、双重 `.attempt`、缺 `HttpRequest`/`Request` 导入）。
- 定向套件（本批面 + 受影响 neblink 族，9 suite）：**Total 99, Failed 0, Passed 98, Skipped 1**（skip = `HeadlessLoginE2ESpec` 的 env 门控 cancel，就绪待测试账号形态）——13:59:01 读数。
- 中途修复一轮：首轮 98 中 3 红，根因 = `GatewayClient.requestError` 丢 `code` 字段（§三契约面），修复后全绿。
- 广面回归（neblink + cli + gateway 包 testOnly）读数见提交附言；全仓 `sbt test` 按批惯例由合并队列门禁跑。

## 八、文案清单（§16 文案闸口径）

- 新增（超 H1–H5，本批呈报）：`Account: ` / `Password: `（stderr 提示位）、`[login] no terminal detected — password input will not be hidden (piped input)`（降级警告）。前端错误面复用既有 `login.reason.logto-not-configured` 三段式，零新键。
- 退役：H2 三行（`Open https://mystand.space/device...` / `Code: ` / `Waiting for approval...`）、心跳行、前端 7 键（en/zh 各 7）。
- H1/H3/H4/H5 逐字不变（H1 描述位本就与登录方式无关；spec 逐字节锚继续钉住）。

## 九、假设与边界（明示）

1. 部署租户为 Logto **v1-beta Experience 方言**——实现按 2026-10-26 实探逐字钉死；租户升级到 GA 方言（`POST /api/experience {method}`）时本舞步需同批升级（`DeviceFlowRetiredSpec` 之外的方言漂移风险，报告在此留痕）。
2. 无头登录 E2E 需**专用测试账号**（作者令禁用真实凭据）：`HeadlessLoginE2ESpec` 以 env 门控就绪（`STAND_E2E_LOGTO_ACCOUNT/PASSWORD`），缺省 cancel 不假绿。E2E 只验到换票为止，register 腿不向生产中继写记录。
3. Mac 两仓只读；本报告 §六 即另批执行清单。
4. `stand login` 在无终端环境（管道）明读密码并**如实警告**（不谎称隐藏）；纯 TTY 场景隐藏回显。
5. identifier 面开放 `username/email/phone` 三种（网关白名单），CLI 按账号含 `@` 自动推导 email/username，电话形态暂不暴露。

## 十、生产安全声明

本批只读探针：Logto 域 8 个请求（§一表，零凭据/零配置写）；Mac 两仓只读 git/ls。本仓写面 = §三清单 + 测试 + 本报告。旧设备码无任何吊销动作（自然过期即可，作者令）。
