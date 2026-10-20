# 本地加速 M1 · 引擎侧实施批（同机近路 origin 迁移 · 会话绑定 · 探测面 · 预算 spec）

- 批次：20261020_175100 · 依据：v1 `20261020_154500_全数据面本地加速通道设计` + v2 `20261020_162217_全中继面本地加速v2`（作者批「可做」）· 状态：实施完成（生效候重启窗）
- 分支：`delegate/delegate-nebflow-fe4f891e`（自 worktree，main 零触碰）

## 结论先行

四项引擎侧交付全部落地并带 spec 钉死：①近路本地票会话的接受链核验 + **会话令牌腿绑定**（local-ticket 只在真实环回 socket 作数，防 localhost 会话令牌被搬腿重放任意读）；②`/api/health` 增同机近路探测面（环回腿并 `deviceId` 身份核对锚 + 门户 origin 白名单 CORS + PNA 预检腿）；③性能预算 spec（v2 §4.3 两条预算线：近路全链 p95<50ms、304 对账 p95<10ms，实测 5.89ms / 1.00ms，10-50× 裕度）；④降级语义零新增代码、spec 钉死（非环回/非白名单/未铸身份 ⇒ 停留门户现状，会话级 Q16 默认）。ws.js 侧按 v2 §3.3 步骤 4 判定为**零改**（origin 迁移后 `ws.js:626` 自动跟随，本批以源码核验 + 预算 spec 端到端活体实证）。

## ①近路本地票会话（ws.js/网关）+ 会话令牌绑定

- **接受链（核验，零改）**：`ConsoleTicketRoutes.scala`（`/?ticket` 原子核销 → 303 + HttpOnly 会话 cookie）与 `LocalConsoleUrl.scala`（本机凭据铸 local 票）原样在役；#522 的 Secure-by-consuming-origin 语义保证 plain-http localhost 腿 cookie 可坐（`ConsoleTicketRoutes.scala:92-94`）。`LocalAccelBudgetSpec` 以真实回环 socket 端到端跑通铸票→兑换→会话 REST（204），即接受链活体验证。
- **新增绑定**（本批唯一引擎安全面变更）：`gateway/LoopbackLeg.scala`（环回腿判据，归一口径 = `ConnGuard.normalizeIp` 同款 collapseMappedV4；`remote=None` 保守判非）+ `GatewayAuth.localTicketOnLeg`（`GatewayAuth.scala` sessionLeg）：`origin == local-ticket` 的会话只在环回腿作数；脱腿（隧道进程内腿 remote=None / LAN / 公网）会话轨拒绝，fall-through 纪律不变（拒后 Bearer 照常接管）。relay-mint 会话逐腿不变（origin-class 域定，`GatewayAuthSpec` 矩阵钉）。
- **申报的残余边界**：WS 隧道腿的桥真拨 `ws://127.0.0.1:<port>`（`TunnelOriginMark` 文件头引注），对端地址呈环回——重放该腿需先持有受害者本人的门户会话（第二腿转发浏览器 cookie），不在本绑定威胁模型内；HTTP 隧道腿与一切非环回直连腿均被挡死。`verifyAndTouch` 先于判据发生（off-leg 重放推进滑动窗但永远拿不到任何面，12h 绝对上限不豁免），已在代码注释申报。
- **ws.js 零改核验**：连接归宿 `location.host`（`ws.js:626`）、探针 same-origin `/api/nf-authcheck`（`ws.js:418`）、cookie 清扫按 `location.hostname`——全 origin 相对，无门户域名假设；`getWebsiteLoginUrl` 仅登录门兜底。

## ②健康探测端点核对 deviceId（v2 §3.3 步骤 1-2 契约）

- `HealthRoutes.scala` GET `/api/health`：**环回腿**上并 `deviceId` 键（`DeviceIdentity.readDeviceIdOnly`，零写盘零铸造，`PortalUrl.startupUrl` 同读法先例）；LAN/隧道腿与未铸身份 = 默认载荷逐字节不变（与 `update` 键同一「有状态才出现」纪律，探针契约零回归；hot-restart 门自检读 version 字段不受影响）。
- CORS 白名单（设计明言「新增一小刀」）：白名单 = brand.conf `portalUrl` origin + 门户 env 覆盖（`Branding.env("PORTAL_URL")` 双前缀，同 `PortalUrl.livePortalEnv` 口径），origin 归一（缺省端口抹除/大小写折叠）。**不是 `*`**：载荷含 deviceId/version 指纹面，仅门户 origin 配读；且整个 CORS 应答只在环回腿发生（跨设备本无近路）。新增 `OPTIONS /health` 预检腿（此前落 /api 树 404，Chromium 私网预检必败）：回显 origin + `Allow-Private-Network: true`（nf-reach 同款）+ `Max-Age: 600`。
- deviceId 只对环回腿披露——`/health` 无令牌门（watchdog 用），本批不把 deviceId 变成 LAN 可枚举量。

## ③性能预算 spec（同机靶心，#538 分段法口径）

`gateway/LocalAccelBudgetSpec.scala`（munit，Ember 绑 127.0.0.1 临时端口 + JDK HttpClient，**真实回环 socket** 非进程内直调）：

- **预算线 1（v2 §4.3）**：近路全链（铸 local 票 → `/?ticket` 303 落会话 → 会话 REST）p95 < **50ms**；实测 n=240：p95=5.89ms（前轮 7.79ms），分段中位 mint=0.77ms / redeem=0.80ms / sessionREST=0.67ms（逐段打印供 #538 式比对）。本链同时是 ① 绑定的端到端活体（local-ticket 在真环回 socket 必须坐进会话轨）。
- **预算线 2（v2 §4.3）**：304 条件对账 p95 < **10ms**（nf-file ETag 腿代表既有 304 机制，v1 §A.1 锚 1.6ms）；实测 p95=1.00ms / 中位 0.64ms。
- **三靶心映射**（v2 §4.2 量级表钉入 spec 头）：历史页（未命中全页 10-500KB）、token 首帧（WS 回环 1-5ms）、AskUser 全程（两段 5-10ms）在近路下共享同一组回环分段（会话 cookie 常驻、无逐载荷铸票），以近路全链一条闸统一钉住。预算对回环 HTTP 有 10-50× 裕度，击穿即回归信号。

## ④降级语义（停留门户现状，Q16 采默认）

零新增代码，语义钉死在 `HealthRoutesSpec`：非白名单 origin（含任意其他 localhost 端口页面、`https://evil.example`、`null`）零 CORS 头 ⇒ 浏览器读不到探测应答；非环回腿不回 CORS、不披露 deviceId；未铸身份不并键。三路任一 ⇒ 门户探测不成立 ⇒ 停留门户入口（隧道永远在，现状即兜底）。降级粒度 = 会话级（Q16 默认：重连对账即完整降级语义），引擎侧零新增。

## 质量闸读数

- 新增 spec：`GatewayAuthSpec` +5（腿绑定矩阵/relay-mint 域定/fall-through 组合）、`HealthRoutesSpec` 新文件 9 条、`LocalAccelBudgetSpec` 新文件 2 条——16 条全绿。
- 家族回归：`Test/testOnly nebflow.gateway.* nebflow.neblink.*` = **1562 tests, 0 failed**（2 skipped 为既有 skip；含 ConsoleTicketRoutesSpec / NfFileRoutesSpec / NfTicketRoutesSpec / PortalTunnelBridgeSpec / NeblinkRelayTunnelAuthSpec 等全部受影响家族）。
- 构建：`compile` 零错误；本批 6 文件 scalafmt + scalafix **零发现**。申报：仓内存在**既有** lint 债（`scalafmtCheckAll`/`scalafix --check` 全仓跑不过——`AtomicJson.scala:400` DisableSyntax.return、DaemonSpawn/ProcessManager 同类，及 ~170 文件的 doc-comment 缩进/import 归组漂移，全部先于本批存在于 main）；本批未触碰这些文件，期间误产生的格式化噪声已逐文件回退（工作树仅含本批 6 文件）。

## 边界与候裁（未做清单）

- 门户页探测 UI / 近路 mint 策略（`LocalConsoleUrl.issue` 同构的门户代发）：**stand-website 并行批**（Q15 双轨的 A 轨）；本批引擎侧探测应答面就绪。
- 跨设备实时流保中继（Q14）：维持开放，未实施打洞。
- v2 §3.1 类一六端点 ETag/immutable 小刀、§3.4 轮询变推送/刷窗自适应：M2/M3 范围，本批未做（预算线 2 的 304 钉在既有 nf-file 机制上）。
- 非标端口近路发现（门户如何得知网关端口）属门户批接口约定，本批探测应答未加 port 键（避免破坏默认载荷逐字节纪律）。

## 生效窗（候重启）

引擎侧生效 = 重建部署后的**下一个网关重启窗**：LoopbackLeg 绑定、健康探测面随进程装配生效。申报两点：①网关会话存内存（既有已申报取舍），重启清空——local 票经 `nebflow token`/门户重铸即恢复，门户会话经既有 remint 链恢复；②重启同时重建 relay 隧道（既有 boot 链），期间门户入口路径不受影响（降级态即现状）。零配置新增、零迁移、零协议变更（健康载荷纯增量键）。

## 文件清单

| 文件 | 变更 |
|---|---|
| `src/main/scala/nebflow/gateway/LoopbackLeg.scala` | 新增（环回腿判据，单一实现） |
| `src/main/scala/nebflow/gateway/GatewayAuth.scala` | sessionLeg 增 local-ticket 腿绑定 + `localTicketOnLeg` |
| `src/main/scala/nebflow/gateway/HealthRoutes.scala` | `/health` 环回腿并 deviceId + 门户白名单 CORS + `OPTIONS` 预检腿 |
| `src/test/scala/nebflow/gateway/GatewayAuthSpec.scala` | +5 条腿绑定矩阵 |
| `src/test/scala/nebflow/gateway/HealthRoutesSpec.scala` | 新增（9 条探测面钉） |
| `src/test/scala/nebflow/gateway/LocalAccelBudgetSpec.scala` | 新增（2 条预算线 + #538 分段打印） |
