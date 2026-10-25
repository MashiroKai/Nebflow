# 官网登录回环 ticket reject · 只读排查报告

批：ticket-flow-triage · 2026-10-25 · 工作树 `delegate/delegate-nebflow-e983c3b5`（零代码改动）

## 0. 结论（先行）

`/?ticket=` 是**控制台一次性入场券的赎回面**：票由**被点开的那台 host 引擎**在内存里签发（中继下发 `ConsoleTicketMint` 帧 → `ConsoleTicketStore.issue`，TTL 90s、一次性、**只存签发实例的进程内存**），由 `GET /?ticket=` 路由在**同一实例**上原子核销换 session cookie。302 回环地址 `http://127.0.0.1:8080/?ticket=…` 由**服务端（link-server + website 仓，本仓外）**拼装，目标是**浏览器所在那台机器**的环回。作者现象「另一台电脑进官网 → 302 到 127.0.0.1:8080 → ticket reject」与架构逐字吻合：票落在浏览器那台机器的**另一个引擎实例**上，该实例的内存票表里没有这个 token → `invalid-ticket` → 403 "Ticket rejected"。**不是版本漂移**（token 为 256-bit 不透明随机数，无签名无版本字段；302 能带出票本身就证明签发端 mint 契约走通了）。近两日各批（感知 7746771a0、域名收口 aca93765a、新设备引导腿 3203c8224）均未触碰签发/校验路径——票据轨最后实质改动停在 10-11 phase3b。

## 1. 一图流链路（官网 → 302 回环 → 校验 → session）

```
浏览器（任意机器）
  │ ① 登录 mystand.space（官网门户，website 独立仓；本仓只有 PortalUrl 就绪行打印入口）
  ▼
link-server / 中继（本仓外）
  │ ② 校验 Logto token + 账号-主机 owner 绑定（契约 v1.1 §2.10）
  │ ③ 沿目标 host 引擎已连的 relay WS 隧道下发 ConsoleTicketMint{accountSub, reqOrigin:"portal"}
  ▼
被点开的 host 引擎（本仓）
  │ ④ PortalTunnelBridge.handleConsoleTicketMint（PortalTunnelBridge.scala:724-741）
  │    → PortalTunnelDeps.mintConsoleTicket（PortalHostFaces.scala:135-155：
  │      绑定闸 unbound → 预算闸 10 次/60s → identity）
  │    → ConsoleTicketStore.issue（ConsoleTicketStore.scala:66-93）：
  │      32B 随机 base64url 不透明 token（:191-194），origin=relay-mint，
  │      TTL 90s（:168），**只存本实例内存 Ref[IO, Map]**，一次性
  │ ⑤ 上行 ConsoleTicketResult{ticket, expiresInSec}（契约 §2.11）
  ▼
服务端（本仓外）拼 302
  │ ⑥ Location: http://127.0.0.1:8080/?ticket=<token>
  │    ——目标是【浏览器所在机器】的环回；8080=默认网关端口约定。
  │    本仓从不向服务端上报本机控制台 URL（enroll/注册载荷无此字段，
  │    已核 NeblinkEnrollment.scala 全量；桥接腿自用地址见 PortalTunnelBridge.scala:444）
  ▼
浏览器所在机器的 127.0.0.1:8080（本仓）
  │ ⑦ GatewayMain.scala:1646「/」槽 ConsoleTicketRoutes.ticketRoutes（先于静态树，
  │    匹配谓词= query 带非空 ticket，ConsoleTicketRoutes.scala:52）
  │ ⑧ face.verifyAndConsume 原子核销（ConsoleTicketStore.scala:124-137）：
  │    查【本实例】内存 map —— 未知→invalid-ticket / TTL 过→expired /
  │    命中即删（redeemed-then-destroyed，一次性）
  │ ⑨ relay-mint 票再过 unbound 闸（:69-75）；本地 break-glass 票豁免
  │ ⑩ sessions.create(accountSub, origin 原样带入, networkId)
  │    → 303 Location:/ + HttpOnly cookie（Secure 仅 https/隧道标记腿，
  │    :92-94 #522；会话 TTL 30d 绝对/7d 闲置，981889a17 批）
  ▼
成功日志一行：console ticket redeemed: session established …（:95-98）
```

要点：**签发者 = 被点开的 host 引擎；校验者 = 收到 GET 的那台机器上恰好监听 8080 的引擎**——跨机器时两者不是同一个实例，这就是全部问题。

## 2. reject 原因码全表（本仓渲染面 ConsoleTicketRoutes.scala:61-73）

| # | 分支 | 页面（状态+headline） | 触发条件 | 引擎日志 |
|---|------|----------------------|----------|----------|
| 1 | face 未装配 | **503** Console unavailable | `consoleTicketFace=None`：隧道未组装（未登录/测试形态） | 无 |
| 2 | `expired` | 403 Ticket expired | token 在 map 但 TTL（relay-mint 90s）已过 | 无 |
| 3 | `invalid-ticket` | 403 **Ticket rejected** | token 不在**本实例** map：本机从未签发 / 已被核销（一次性第二次命中）/ `revokeAll` 清空（:112-113）/ 实例重启内存丢失 | **无（Left 分支零日志）** |
| 4 | `other` | 403 Ticket rejected (…) | 预留——现 store 只产 #2/#3 两种 | 无 |
| 5 | 票有效但 unbound | 403 Binding revoked | relay-mint 票 + 绑定已吊销（local-ticket 豁免，break-glass） | 无 |

签发侧错误枚举（不出 reject 页，portal 自行展示）：`unbound` / `rate_limited` / `quiesced`（PortalWireCodec.scala:77；host 侧实际只产前两种，PortalTunnelBridge.scala:737-739）。

已知边（代码注释登记，ConsoleTicketRoutes.scala:37-42）：首张 303 丢失后的重试、消费成功但响应腿失败——均落在 #3，页面自会引导重新取票。

## 3. 跨机器场景最可能原因判定

**命中 #3 `invalid-ticket`（"Ticket rejected" 文案），根因 = 回环赎回模型的跨机器盲区，非版本漂移、非近两日批引入。**

推理链：
1. 302 目标是**浏览器那台机器**的 127.0.0.1:8080。作者 Windows 机（DESKTOP-IJBBEOT）恰有 stand 引擎在听 8080（design/20261022_new-device-triage/DESIGN.md 实证 9112/8080 存活）⇒ 赎回请求落在**另一个实例**上；它的内存票表从未见过该 token ⇒ 必然 `invalid-ticket`。若那台机器 8080 无实例，浏览器只会连接拒绝、根本出不了 reject 页——**出了页本身就是旁证**。
2. portal 上点开的 host 是 Mac（生产 sqlite `account_host_binding`：Mac=bound，同 DESIGN.md）⇒ 票签发在 Mac 实例内存里，留在 Mac，永远到不了 Windows。
3. 排除过期：headline 应为 "Ticket expired"（#2），与所见 "ticket reject" 不符。
4. 排除重放：若首刷已在**本机**成功建会话，刷新落在控制台而非 reject 页；reject 说明首次核销就没在本机发生过——回到第 1 条。
5. 排除版本漂移：token 是 256-bit 不透明随机数（ConsoleTicketStore.scala:191-194），无签名/版本字段，新旧 jar 票表同形；且 302 带出了票 ⇒ 签发端（被点 host）已成功解码并应答 `ConsoleTicketMint`（proto 1 契约自 10-11 冻结未变）。

判别实锤（如需，只读即可）：reject 页出现的那台机器引擎日志**不会新增任何行**（#3 零日志）；Mac 侧 mint 也静默（bridge 无 INFO）——最硬的判据是 portal 上点开的 host 与浏览器所在机器是否同机，即根因本身；中继生产库（scan-bind-403 批先例只读通道）可查该次 mint 路由到了哪个 device。

## 4. 近两日契约面改动核查（问 3）

| 批 | commit（日期） | 触及 | 票签发/校验路径？ |
|----|----------------|------|--------------------|
| 感知批（mail-perception） | 7746771a0（10-25） | GatewayMain 仅 +9 行=感知守护 boot（已核逐行 diff）；PerceptionRoutes 新件、RestApiRoutes +3 挂路由 | **否** |
| 域名收口（legacy-domain-sweep） | aca93765a（10-25） | brand.conf installUrl / README / LoginCommand DevicePromptAddress / verify-profile-url.mjs + 6 spec 断言镜像 + check-legacy-domain 门禁 | **否**（纯用户可见 URL 字符串与门禁） |
| 新设备引导腿批 | 3203c8224（10-24） | GatewayMain/NeblinkRoutes/RestApiCtx = auth start/state/status 三面 bootstrapGate（stand login 设备码流） | **否**（该 merge 对 GatewayMain/RestApiCtx 的 diff 零 ticket/session 字样，已核） |
| 会话 TTL 批 | 981889a17（10-21） | GatewayAuth：会话轨 12h→30d / 2h→7d + authcheck 204 重签 | 会话轨，非票据轨 |
| 品牌清扫批 | 5c7d44238（10-22） | ConsoleTicketRoutes 仅 1 处文案 "nebflow token"→"stand token"（:72） | **否**（文案） |
| 票据轨真身最后实质改动 | ab02521c6（10-11，phase3b：ConsoleTicketStore/NfTicketStore/赎回路由/统一 GatewayAuth）+ 23561099f（10-11，契约 v1.1） | — | 票据轨自 10-11 冻结 |

## 5. 修法口径（若按「版本漂移」提问）

不属漂移——**无「等新 jar 切版」也无「官网侧兼容旧票」可做**（票无签名无版本，不存在新旧格式互认问题）。治本属跨仓（website/link-server）：portal 识别「浏览器机 ≠ 目标 host 机」时改发**隧道 HTTPS 入口控制台脸**的 URL（`http_request` 桥接脸已在仓内完备，ConsoleTicketRoutes #522 注释即为其 Secure 语义预留），或明示「请在 host 机器上打开此控制台」；引擎侧零改动即可支撑（桥接脸已能完整服务控制台与会话）。

## 6. 边界与未决

- 302 拼装方在 link-server + website 仓（本仓外）：其目标端口/host 选择逻辑（硬编码 8080 还是读 enrollment 数据）本仓不可见；已核实本仓从不向服务端上报本机控制台 URL（NeblinkEnrollment 载荷逐字段核查）。
- 本报告为静态代码排查，未读作者两台机器的现场引擎日志（reject 零日志的判定使日志取证价值有限，见 §3 判别法）。
