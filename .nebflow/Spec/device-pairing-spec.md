# 设计规格书 · Nebflow 多设备扫码/链接配对（手机 WebUI ↔ 桌面实例）

- 日期：2026-08-22 · 作者：design-engineer · 状态：draft v1.0 · 待用户/Manager 确认冻结
- 适用代码（实现依据，本规格不改码）：Scala 客户端 `src/main/scala/nebflow/neblink/`（NeblinkClient / NeblinkRelayTunnel / Protocol / RestApiRoutes neblink 段）· 前端 `src/main/resources/web/`（neblink.js / neblink.css / 设置面板 NebLink 区）· Rust 中继服务器（VPS，仓外）
- 铁律依据：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（最高优先级）；软参考：`~/.nebflow/skills/design-system/SKILL.md`
- 版本日志：v1.0（2026-08-22）首版——配对协议 + 信任生命周期 + 通道架构 + 权限分级 + 桌面/手机交互规格 + P0/P1 分期

---

## 0 · 一句话目标

在现有 NebLink 账号/设备登录制之上，补一条**扫码或链接配对**通道——桌面端弹出一次性 QR/链接，手机浏览器扫码打开即确认，随后手机作为已注册设备通过既有中继通道安全控制桌面 agent；全程短时效、防重放、防中间人，手机默认最小权限、可随时一键吊销。

**本规格书范围声明**：
- ✅ 覆盖：配对协议（载荷/状态机/时序/安全模型）、信任生命周期、通道架构决策、权限分级、桌面端配对弹窗与设备列表交互、手机端确认页交互、连接层契约（WebUI 控制台壳的接入契约，见 §5.4）、可断言验收点、分期。
- ❌ 不覆盖：手机端 WebUI 控制台本体的完整功能设计（会话列表/消息流/输入区……）——那是独立规格书（依赖本规格的 §5.4 接入契约 + 移动端布局 token）；本规格只定义「配对成功后壳层如何建立连接 + 连接状态 UI」。
- 实现影响面提示（非本规格改动项）：中继服务器（Rust，仓外）需新增 ~5 个端点（附录 A 清单）；Scala 客户端与前端为主要实施面。

## 1 · 参考与依据

| 来源 | 提炼的可执行规则 | 链接 |
|---|---|---|
| 微信网页版扫码登录（用户点名范式） | ① 桌面显示 QR → 手机扫 → 手机单键确认 → 桌面 QR 态原地转为登录成功态（无需手动刷新）；② QR 短时效（约 1~2 分钟），过期后原地出现「刷新二维码」覆盖层而非整窗重开；③ 扫码后桌面先显示「已扫码，请在手机确认」中间态——**两阶段反馈消除不确定性** | 产品行为观察（微信网页版）；https://weixin.qq.com/ |
| Chrome Remote Desktop（PIN/配对码范式） | ① 一次性 PIN 是「能力凭证」：拿到即可建立会话，因此必须短时效 + 单次使用；② 配对成功后设备进入持久列表，可命名、可移除 | https://support.google.com/chrome/answer/1649523 |
| Tailscale 设备授权（admin approve 范式） | ① 新设备加入后默认处于「待批准/受限」态，管理员在设备列表审阅设备名/用户后批准；② 批准后**立即生效，无需重启**；③ 授权可后续撤销（同一 API `{"authorized":false}`）——**配对、批准、吊销三动作都落在持久设备列表上** | https://tailscale.com/kb/1099/device-authorization |
| RFC 8628 · OAuth 2.0 Device Authorization Grant（Nebflow 既有 device-flow 的同源标准） | ① device_code（长随机、不可猜）与 user_code（短、人可读）分离：机器轮询用前者、人眼核对用后者；② `authorization_pending` 轮询语义 + `slow_down` 限速 + `expired_token` 终态；③ 短码须防暴力枚举（足够熵 + 尝试次数上限）；④ user code 字符集建议避开易混字符（0/O/1/I）。本规格复用其轮询语义与字符集纪律 | https://www.rfc-editor.org/rfc/rfc8628 |
| Apple HIG（Modal / 设备信任） | ① 弹窗内容单一焦点：一次只让用户做一个决定（扫 or 取消）；② 破坏性操作（吊销设备）需二次确认；③ 状态变化给用户明确的文字解释而非仅图标变色 | https://developer.apple.com/design/human-interface-guidelines/ |
| Nebflow NebLink 现状（实现基线，必读） | 已有：`/api/device/code→token` RFC 8628 式 device-flow（前端轮询契约 `{deviceCode,userCode,verificationUri,interval,expiresIn}`）· `pairCode` 配对码 enroll（`POST /api/neblink/enroll`）· 长期 `deviceToken` 设备凭据 + `/api/device/session` 换会话 token · WS relay tunnel（指数退避 0→30s、token 热读取）· `trustedPeerIps` 可信 peer 模型。本规格**不推翻以上任何契约**，在其上叠加手机配对层（见 §2.4 端点清单与附录 A） | `RestApiRoutes.scala:526-703` · `NeblinkClient.scala:166-283` · `NeblinkRelayTunnel.scala:19-33` · `Protocol.scala:22-30` |
| Nebflow 前端现状 | 无 QR 渲染库（Grep 确认仅 pdfjs）；设置面板已有 NebLink 区；avatar 弹窗承载 device-flow 登录（`neblink.js:162`）。→ QR 渲染需引入轻量库（决策见 §5.1）或直接走「链接为主、QR 为可选增强」 | `src/main/resources/web/js/neblink.js` |

**取舍声明（范式冲突裁定）**：
- 微信范式与 Chrome Remote Desktop 范式方向相反：微信是「新设备（手机）批准旧设备（桌面）登录」，而 Nebflow 场景是「已信任的桌面邀请新设备（手机）加入」——本规格采用**桌面发邀请、手机接受**的方向（桌面已持有登录态与 daemon auth，是唯一可信发证方），微信借鉴的是其**交互节奏**（两阶段反馈 / 过期刷新 / 原地态迁移）而非信任方向。
- 不采用 Chrome Remote Desktop 的「手输 PIN」作为主路径（手机浏览器手输 12 位码体验差）——但保留短码作为 QR 的降级与核对锚（RFC 8628 user_code 思路）。

## 2 · 配对协议与通道架构

### 2.1 核心决策

| 决策点 | 裁定 | 理由 |
|---|---|---|
| **D1 · QR/链接承载什么** | 一次性配对 URL：`https://neblink.nebflow.space/pair/<pairId>#k=<code>&v=1`。`pairId` = 128-bit 随机（base64url，22 字符，公开定位符）；`code` = 128-bit 随机（22 字符，持有型密钥，**放 URL fragment**）。QR 与可复制链接是同一 URL 的两种呈现 | ① fragment 不随 HTTP 请求发给服务器——中继日志/CDN 永远看不到 `code`；② QR 里**不含任何长效凭据、token、桌面 IP**——泄露窗口内最坏结果 = 有人在 120s 内抢先配对，可立即看见并吊销（§3）；③ 手机无需装 app，浏览器直达 |
| **D2 · 谁发证** | 桌面是唯一发证方（反向微信模型）：桌面用既有 session token 向中继申请配对会话，本地生成 `code`，只把 `SHA-256(code)` 交给中继（中继不存明文）。手机扫码后凭 `code` 原像向中继换取设备凭据 | 桌面已持有登录态 + daemon auth，是信任锚；中继只见哈希，降低中继存储面泄露影响 |
| **D3 · 人眼核对锚** | `shortCode` = `base32(SHA-256(code))` 前 6 字符，字符集 `BCDFGHJKLMNPQRSTVWXZ`（RFC 8628 §6.1 纪律：无 0/O/1/I/易混字符），格式 `XXX-XXX`。桌面 QR 下方与手机确认页**同显**，用户核对一致才点允许。双方各自从 `code` 派生，不经网络传输 | 防「扫到过期/他人二维码」与钓鱼换码；零额外往返 |
| **D4 · 时效与次数** | 配对会话 TTL = **120s**（中继服务端时钟为准，桌面倒计时仅提示）；`code` 单次使用——confirm 成功后中继原子删除会话；每桌面同时仅允许 1 个活跃配对会话（重开 = 旧会话置 `replaced`） | 短时效压缩窃取窗口；单次使用防重放；单会话防状态分叉 |
| **D5 · 手机凭据形态** | 确认瞬间手机浏览器本地生成 `deviceId + deviceSecret`（WebCrypto `randomUUID()×2`，对齐既有 Scala 双 UUID 纪律），向中继换取长效 `deviceToken`（与既有 enroll 同构），存 localStorage（隐私模式不可用时显式报错）。中继设备记录新增 `kind:"mobile-web"` + `ownerDeviceId`（= 桌面 deviceId）+ `role` | 复用既有 deviceToken/session 契约（§2.4），手机成为「一台没有 LAN 端点、只走中继隧道的设备」 |
| **D6 · 配对后是否要桌面二次批准** | **P0：不要**——手机确认即入列（微信式速度），安全网 = 桌面设备列表即时可见 + 一键吊销（Tailscale 可见性纪律）。**P1 可选开关**：admin-approve 门（扫码后桌面点「允许」才发证） | P0 优先闭环速度；批准门对家庭单用户场景是摩擦，留给多设备/团队场景 P1 |

### 2.2 配对流程状态机

**桌面端视角（配对弹窗）**：

| 状态 | 触发 | 界面表现 | 退出路径 |
|---|---|---|---|
| `idle` | 未发起 | 设置页 NebLink 区「配对手机…」按钮 | 点击 → `generating` |
| `generating` | 点击配对 | 弹窗打开（modalIn 动效 §5.6），QR 区骨架占位，状态行 `pair.generating` | 成功 → `waiting`；失败 → `error` |
| `waiting` | `POST /api/pair/start` 成功 | QR 渲染 + 下方 shortCode（`XXX-XXX`）+ 可复制完整链接 + 状态行 `pair.waiting`（含剩余秒数，每秒更新，仅文字不滴答动画）+ 「取消」按钮 | 收到 `scanned` → `scanned`；TTL 到 → `expired`；取消/关窗 → `cancelled` |
| `scanned` | 中继推 `pair_event{status:"scanned"}`（手机页加载即上报） | QR 降不透明度 0.4 + 覆盖一行 `pair.scanned`（「已扫码，请在手机上确认」）——**微信式两阶段反馈** | `confirmed` / `expired` / `cancelled` |
| `confirmed` | 中继推 `pair_event{status:"confirmed", device:{…}}` | QR 区原地切换为成功态：勾号 + `pair.done`（「已配对 {设备名}」）+ 「完成」按钮；同时设置页设备列表插入新行 | 点「完成」/ 1.5s 后自动 → `idle`（弹窗关闭） |
| `expired` | TTL 到（中继权威；桌面倒计时到点也先切态再等确认） | QR 覆盖层：`pair.expired` + 「刷新二维码」按钮（`.glass-control`）| 刷新 → `generating`（旧会话作废）；关窗 → `idle` |
| `error` | start 失败（中继不可达/401） | 行内错误文案 + 「重试」按钮；401 特判提示「请先登录 NebLink」 | 重试 / 关窗 |
| `cancelled` | 用户取消/关窗 | 弹窗关闭；向中继发 `POST /api/pair/{pairId}/cancel`（best-effort） | → `idle` |

**手机端视角（确认页）**：

| 状态 | 触发 | 界面表现 | 退出路径 |
|---|---|---|---|
| `loading` | 打开配对 URL | 骨架 + 品牌标识（克制，单色） | 会话有效 → `verify`；无效/过期 → `invalid` |
| `verify` | `GET /api/pair/{pairId}/status` 返回 waiting + 桌面设备名 | 卡片：「连接到 {desktopName}」+ 大字 shortCode + 提示「请确认与桌面显示一致」+ 权限选择（默认「仅观察」，可切「可发送消息」）+ 底部主按钮「允许连接」+ 次按钮「取消」 | 允许 → `pairing`；取消 → `denied`（本地态，页面关闭提示） |
| `pairing` | 点允许 | 主按钮转 loading（spinner in-button，禁重复点击） | 成功 → `done`；410/409 → `toolate` |
| `done` | confirm 2xx，凭据已存 | 成功勾号 + 「正在打开控制台…」→ 跳转控制台壳 | — |
| `toolate` | 过期/已被使用 | 文案「二维码已失效，请在桌面重新发起配对」 | — |
| `invalid` | pairId 不存在/网络错误 | 文案 + 「返回」 | — |

**中继侧配对会话对象（权威状态）**：`waiting → scanned → confirmed`（终态，会话删除）/ `expired`（TTL）/ `cancelled` / `replaced`（被新会话顶替）。状态迁移只允许沿此 DAG，任何 confirm 仅接受 `waiting|scanned`。

### 2.3 协议时序

```
桌面（已登录 NebLink）          中继 Relay                     手机浏览器
   │                                │                              │
   │ ① POST /api/pair/start         │                              │
   │   {deviceToken-session,        │                              │
   │    codeHash=SHA256(code)}      │   创建会话 {pairId,           │
   ├───────────────────────────────▶│   codeHash, desktopId,        │
   │                                │   status:waiting, exp:120s}   │
   │ ◀──────────────────────────────┤   {pairId, expiresAt}         │
   │ 渲染 QR(pairURL)+shortCode     │                              │
   │                                │                              │
   │                                │ ② GET /api/pair/{pairId}/status│
   │                                │ ◀─────────────────────────────┤ 手机扫码/点链接打开
   │                                │   waiting→scanned，返回        │
   │                                │   {status, desktopName,        │
   │                                │    lanEndpoints?}              │
   │ ◀─pair_event{status:"scanned"}─┤                               │
   │   （走既有 WS tunnel）          │                              │
   │ QR 降透明 + 「已扫码,请确认」   │                              │
   │                                │                              │
   │                                │ ③ POST /api/pair/{pairId}/confirm
   │                                │ ◀─────────────────────────────┤ {code, deviceId,
   │                                │   校验 SHA256(code)==codeHash、│  deviceName, role}
   │                                │   status∈{waiting,scanned}、   │
   │                                │   未过期 → 铸 deviceToken、    │
   │                                │   记录设备(kind:mobile-web,    │
   │                                │   ownerDeviceId:desktop)       │
   │ ◀─pair_event{status:"confirmed",device}─┤─────────────────────▶ {deviceToken,
   │ 原地切成功态 + 设备列表插入行   │                              │   deviceId, networkId}
   │                                │                              │ 存 localStorage →
   │                                │                              │ 打开控制台壳(§5.4)
   │                                │                              │
   │ ◀══════ 后续数据面：手机 ↔ 中继 ↔ 桌面（WS tunnel，LAN 直连可选 §2.5）══════▶
```

- **两阶段反馈**（微信节奏）：桌面在 ② 后立即感知「已扫码」，③ 后感知「已确认」，全程无需用户手动刷新。
- **code 只在手机↔中继之间以 HTTPS 传输一次**（③），且只传明文给中继做哈希比对；桌面与 QR 都不再持有可复用凭据。
- **桌面掉线**：若 ① 时桌面 WS tunnel 未连上，中继在 confirm 成功时把 `pair_event` 入队，桌面重连后补推（幂等按 pairId）。

### 2.4 载荷与端点契约

**新增中继端点（Rust 服务器，仓外，实施清单见附录 A）**——全部要求桌面 `Authorization: Bearer <sessionToken>`（start/cancel）或无鉴权但凭 pairId（status）/ pairId+code（confirm）：

| 端点 | 方向 | 请求体 | 响应 | 错误 |
|---|---|---|---|---|
| `POST /api/pair/start` | 桌面→中继 | `{codeHash}` | `{pairId, expiresAt}` | 401 未登录 / 409 已有活跃会话（返回旧 pairId 并置 replaced） |
| `GET /api/pair/{pairId}/status` | 手机→中继 | — | `{status, desktopName, expiresAt, lanEndpoints?:[]}` | 404 不存在 / 410 过期或已用 |
| `POST /api/pair/{pairId}/confirm` | 手机→中继 | `{code, deviceId, deviceName, platform:"web", role}` | `{deviceToken, deviceId, networkId}` | 400 hash 不符 / 409 非 waiting|scanned / 410 过期 |
| `POST /api/pair/{pairId}/cancel` | 桌面→中继 | — | `{ok}` | 404/410 |

**配对 URL 形态（QR 内容 = 可复制链接，同一字符串）**：
```
https://neblink.nebflow.space/pair/<pairId>#k=<code>&v=1
```
- `<pairId>`：22 字符 base64url（128-bit）。`<code>`：22 字符 base64url（128-bit），**fragment**（不发服务器）。`v=1` 版本位。
- 二维码容错级别 L、边距 quiet zone 4 模块；尺寸 ≥ 180×180 CSS px 保证手机相机易读。

**复用既有契约（零改动）**：手机后续换会话 token 走 `POST /api/device/session`（同桌面）；设备吊销走既有设备管理面（§3）。

**WS tunnel 新增消息类型**（`NeblinkRelayTunnel`，桌面侧接收）：
```json
{"type":"pair_event","pairId":"...","status":"scanned|confirmed","device":{"deviceId":"...","deviceName":"iPhone","role":"observer"}}
```

### 2.5 通道架构与断线重连

**控制面**（配对/吊销/设备列表）：一律经中继 Relay，保证跨网络可达。

**数据面**（配对成功后的实际控制流量：观察/发消息/执行）：
- **P0 · 全部经中继 WS tunnel**（复用 `NeblinkRelayTunnel` 的指数退避 0→1→2…→30s + token 热读取 + ping/pong）。手机 = 一台「无 LAN 端点、只走中继」的设备，走与桌面间相同的 relay 通道。
- **P1 · LAN 直连优化**：② 的 status 响应里中继附带桌面 `lanEndpoints`（来自桌面 heartbeat 上报的 `detectLocalEndpoints`）。手机先试 `http://<lanIp>:<port>` 直连（低延迟），失败回落中继。控制面仍经中继，仅数据面直连。

**断线重连语义**（手机侧）：
- 手机控制台壳维持一条到中继的会话（P0 为短轮询/SSE，P1 为 WS）；断开即顶部显示非阻断重连条（不遮内容、不清输入草稿），指数退避重连（1→2→4→8s 上限），恢复后自动续传当前视图。
- 桌面侧 tunnel 断线沿用既有 0→30s 退避；重连成功补推未送达的 `pair_event`。
- **语义不变式**：重连只恢复「视图与订阅」，不重放用户已确认的写操作（发消息/执行）——写操作需幂等键，防重连重复投递。

## 3 · 信任生命周期与安全模型

### 3.1 设备生命周期

```
配对入列 ──▶ active（活跃，token 每次 /api/device/session 换发短期会话）
   │            │
   │            ├─ 90 天未出现 ──▶ stale（设备列表灰显「长期未用」，凭据仍在）
   │            ├─ 180 天未出现 ──▶ 自动过期（token 作废，列表标「已过期」，可重新扫码）
   │            ├─ 用户吊销 ──▶ revoked（中继侧 token 立即失效 + 设备记录软删除）
   │            └─ 手机丢失 ──▶ 远程擦除：吊销 + 删除该设备全部会话凭据（见 3.3）
   └─ 同桌面可并行注册多台手机（P0 上限 3 台，超出提示先吊销旧设备）
```

**核心原则：长效凭据是 `deviceToken`（≈永久，直到吊销），短期凭据是会话 token（`/api/device/session` 换发，小时级）。** 手机 localStorage 里只放 deviceToken，会话 token 每次开页面现换——缩小浏览器存储泄露的窗口。

### 3.2 吊销（桌面侧操作）

- 设备列表每行：设备名 + 类型图标 + 最近活跃时间 + 权限档位 badge + 「吊销」按钮。
- 吊销 = 二次确认（Apple HIG 破坏性操作纪律）→ 中继立即失效该 deviceToken 的所有会话 → 手机端下次任何请求收到 401，控制台壳显示「连接已被桌面撤销」并清空本地凭据。
- 吊销后设备行保留 7 天显示为「已吊销」灰态（可彻底移除），便于用户确认操作对象。

### 3.3 「手机丢失」处置（P1）

- 设备列表提供「手机丢失/失窃」快捷入口 = 一键吊销 + 通知中继删除该设备一切存储记录 + （若未来引入手机本地缓存的消息/文件）下发远程擦除指令。
- 因 P0 手机端**不落任何敏感数据到 localStorage 之外**、且会话 token 短期，最坏影响 = 攻击者持有 deviceToken 可继续访问直至吊销——所以设备列表要显示「最近活跃 IP/时间」辅助用户发现异常（P1）。

### 3.4 防重放 / 防中间人

| 攻击面 | 对策 |
|---|---|
| QR 被旁观者抢先扫走 | TTL 120s + 单次使用；桌面 `scanned` 态立即可见异常扫码（他人扫码 = 桌面立刻显示「已扫码」，用户未操作即知有异，可取消） |
| code 重放 | confirm 成功即原子删除会话；同 pairId 二次 confirm → 409 |
| code 暴力枚举 | code 128-bit 随机（2^128 空间）；confirm 端点按 pairId 限流（每 pairId 5 次尝试即作废会话）；shortCode 仅作核对锚不作凭证 |
| 中继被攻破/日志泄露 | 中继只存 `codeHash`，不存 code 明文；设备凭据存储加密（服务器侧职责，见附录 A） |
| 中间人篡改配对 URL | 配对域强制 HTTPS（HSTS）；QR 内容校验版本位 `v=1`，未知版本拒绝 |
| 钓鱼：伪造确认页 | shortCode 双端同显、人眼核对（§2.1 D3）；确认页域名必须是 `neblink.nebflow.space`，文案提示用户核对地址栏 |
| 手机 localStorage 被同机恶意页面读取 | 同源策略保护；会话 token 不落盘；提示用户使用系统级浏览器隔离（隐身/无痕）作 P1 增强 |
| 桌面 WS tunnel 掉线错过 pair_event | 中继对 pair_event 按 pairId 队列 + 桌面重连补推（§2.3） |

## 4 · 权限分级

手机不是桌面的全权代理。采用**三档角色**，默认最小，桌面随时可降档/吊销：

| 档位 | 标识 | 能做 | 不能做 | 默认 |
|---|---|---|---|---|
| `observer`（仅观察） | 眼睛图标 | 看会话列表、看消息流（含流式更新）、看 agent 运行状态 | 发任何消息、触发任何写操作 | ✅ **P0 默认** |
| `messenger`（可发消息） | 对话图标 | observer 全部 + 向既有会话发用户消息（= 远程替桌面敲键盘发消息给 agent） | 创建/删除会话、执行工具、改配置、吊销设备 | — |
| `full`（完全控制） | 钥匙图标 | messenger 全部 + 创建会话、审批 AskUserQuestion、触发工具执行 | 改桌面 NebLink 配置、吊销其他设备（管理面永远只在桌面） | ❌ P1 才开放 |

**设计理由**：
- 手机场景高频需求是「出门在外看一眼 agent 跑得怎么样 / 回一句话催一下」，observer+messenger 已覆盖；`full` 涉及执行与配置，风险面大，留给 P1 且必须桌面二次确认升档。
- 权限在**配对确认页由用户预选**（默认 observer），写入设备记录 `role` 字段；中继在每次 `/api/device/session` 换发会话 token 时把 role 嵌进 token claims，桌面网关按 claims 强制鉴权——**权限不由前端隐藏按钮实现，由后端 token 声明 + 网关校验实现**。
- 降档/升档（P1）：桌面设备列表切换档位 → 中继更新 role → 手机下次换会话 token 时生效（小时级内必然收敛，无需即时踢线）。

## 5 · 交互规格

### 5.1 桌面端：配对弹窗（QR 展示）

**入口位置**：设置面板 NebLink 区——设备列表卡片右上角新增「配对手机…」按钮（`.glass-control`，带 QR 小图标）。不设全局快捷键、不放侧栏（配对是低频管理操作，归设置页；Apple HIG 单一入口纪律）。

**弹窗形态**：居中毛玻璃弹窗（视觉铁律 1）：
- overlay `background: var(--overlay-bg)` = **transparent**，禁背景暗化/模糊；
- 面板 `--glass-bg` + `blur(24px) saturate(1.15)` + `--glass-border` + 既有 `modalIn` 入场；
- 宽度 360px（QR 弹窗单一焦点，不需要 640 宽）、`max-width: calc(100vw - 48px)`。

**内容结构（自上而下）**：
```
┌─────────────────────────────────┐
│ 配对手机                  [×]   │  header：标题 600/15px + 关闭钮
├─────────────────────────────────┤
│                                 │
│         ┌───────────┐           │
│         │    QR     │  180px    │  QR 容器：白底圆角卡（QR 需高对比
│         │  180×180  │           │  底色，亮暗主题都用白底——QR 例外，
│         └───────────┘           │  不算违反 token 纪律，理由见 5.5）
│                                 │
│      核对码  XXX-XXX            │  shortCode：等宽字体 600/20px，字距加宽
│                                 │
│  或复制链接  [🔗 复制]           │  链接行：muted 12px + 复制小钮
├─────────────────────────────────┤
│ 状态行：等待手机扫码… (102s)     │  muted 12px，每秒更新（仅数字，无动画）
│                    [取消]       │  .glass-control 次级按钮
└─────────────────────────────────┘
```

**QR 渲染技术决策**：前端当前无 QR 库（Grep 确认）。二选一，实现时定：
- 方案 a（推荐）：引入 `qrcode`（~18KB gzip，canvas/SVG 输出，无依赖）进 vendor/；
- 方案 b：P0 若不想加依赖，先只做「可复制链接」+ 桌面显示链接文本，QR 降级为 P1。**规格书按方案 a 写断言，方案 b 为回退备案。**

**状态覆盖层**（`scanned`/`expired`/`confirmed`）：不重开弹窗、不动布局，直接在 QR 容器上叠覆盖层（半透明白底 + 图标 + 文案），QR 本体 opacity 0.4——微信式原地态迁移。

### 5.2 桌面端：设备列表与吊销

既有设备列表（`neblink.js` `deviceRows`）扩展，**不改既有行结构**，新增：
- 手机设备行 = 手机图标 + 设备名（用户可后续改名，沿用 peer-description 机制）+ role badge（`仅观察`/`可发消息`，muted 小徽章）+ 最近活跃时间 + 「吊销」按钮（muted 文字钮，hover 转红）；
- 配对成功时新行以 300ms 淡入插入列表顶部（不跳动布局）；
- 吊销二次确认：行内原位展开确认条「确定吊销 {设备名}？[吊销][取消]」（不弹新窗——破坏性确认就地完成更克制）；确认后行转灰态「已吊销」。

### 5.3 手机端：确认页（`neblink.nebflow.space/pair/…`）

手机浏览器页面（中继服务器托管的静态页，仓外实施——本规格定义其交互契约）：

```
┌────────────────────────────┐
│  ◀（返回）                  │  极简顶栏，仅返回
│                            │
│      连接到                │  muted 14px
│   Mashiros-MacBook-Pro     │  桌面设备名 600/20px（从 status 取）
│                            │
│      ┌──────────────┐      │
│      │   XXX-XXX    │      │  shortCode 大字卡：等宽 600/28px
│      └──────────────┘      │
│  请确认此核对码与桌面屏幕    │  说明文案 12px muted
│  上显示的一致               │
│                            │
│  权限  ○ 仅观察（默认）      │  单选组，默认 observer；
│        ○ 可发送消息          │  messenger 选项附一句说明
│                            │
│      [ 允许连接 ]           │  主按钮：品牌绿玻璃（对齐桌面 .glass-control
│        取消                │  语义；移动端为独立样式表，参数见 5.5）
└────────────────────────────┘
```

- **单屏完成**：确认页不超过一屏（375×667 无滚动）；所有文案 i18n（zh/en 跟随浏览器 `navigator.language`）。
- 加载/无效/过期/成功各态见 §2.2 手机端状态机；主按钮点击后立即转 loading 防重复提交。
- **不做**：不在确认页要求登录/注册账号（配对即入列，账号制是既有另一条路径，二者并列不耦合）。

### 5.4 手机端：控制台壳（连接层契约）

配对成功后跳转的控制台壳（P0 范围 = 连接层 + 状态 UI；完整控制台另立规格书）：

- **凭据建立**：confirm 返回的 `deviceToken` 存 localStorage（key 版本化 `nfp.v1.deviceToken`）→ 立即调 `/api/device/session` 换会话 token → 壳层就绪。
- **顶部状态条**（常驻，非阻断式）：`已连接 · {桌面名}` 绿点 / `重连中…` 黄点 / `已断开` 红点 + 重试钮——三态即 §2.5 断线语义的 UI 映射。
- **P0 最小视图**：会话列表（只读）+ 单会话消息流（只读，流式追加）+ （messenger 档）底部输入条。observer 档输入条整体不渲染（后端 token 鉴权为底线，前端不渲染为体验）。
- **被吊销时**：任何请求 401 → 壳层全屏提示「连接已被桌面撤销」+ 清除本地凭据 + 无重试入口（引导回桌面重新配对）。
- **viewport**：`<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`；safe-area inset 适配刘海屏。

### 5.5 视觉规格

| 元素 | 规格 | 来源 |
|---|---|---|
| 配对弹窗面板 | `--glass-bg` + `backdrop-filter: blur(24px) saturate(1.15)` + 1px `--glass-border` + 圆角 20px + 既有 modal 阴影；overlay `var(--overlay-bg)` = transparent | 铁律 1 + `sapphire.css:14-58` |
| 弹窗入场 | `modalIn 0.2s cubic-bezier(0.16,1,0.3,1)`（既有 keyframes 复用） | `modal.css:96,262` |
| QR 容器 | 白底（`#fff`，亮暗主题均白底）+ 圆角 12px + 1px `rgba(0,0,0,0.06)` 边；**白底是 QR 可扫性硬需求，属功能性例外，不视为新颜色 token** | QR 识读对比度需求 |
| shortCode 字体 | `ui-monospace, 'SF Mono', Menlo, monospace`；桌面 600/20px letter-spacing 0.15em；手机确认页 600/28px | RFC 8628 §6.1 可读性 |
| 按钮 | 「取消/复制」= 既有 `.glass-control`（铁律 2）；手机确认页主按钮 = 品牌绿玻璃语义移植（rgba(7,193,96,0.42) + blur 8px 参数组，移动端样式表内复刻） | 铁律 2 |
| role badge / 状态点 | 复用既有 muted 徽章样式；连接状态点 8px 圆（绿 `rgb(7,193,96)` / 黄 `rgb(255,159,10)` / 红 `rgb(255,59,48)` 0.9 alpha——移动端新样式表内定义，桌面端不引入这三个色） | 克制原则；桌面端零新色 |
| 字重 | 标题 600 / 正文 400 / 按钮 500（铁律 3） | visual-style |
| 颜色纪律 | 桌面端除 QR 白底外**零新色值**；手机端是独立样式表（中继托管页），其色板自成体系但语义对齐（品牌绿玻璃/蓝宝石强调） | 克制 + 范围隔离 |

### 5.6 动效规范

| 动效 | 触发 | 参数 | reduced-motion |
|---|---|---|---|
| 弹窗入场/退场 | 打开/关闭 | `modalIn` 0.2s 既有曲线；退场 opacity 0.15s（无位移动画） | 入场降 0.01s；退场保留 |
| QR 态切换覆盖层 | scanned/expired/confirmed | 覆盖层 opacity 0→1 150ms ease-out；QR 本体 opacity 1→0.4 同曲线 | 直接切换无过渡 |
| 倒计时 | waiting 每秒 | **仅文字更新，无滴答/进度环动画**（克制原则） | 天然合规 |
| 设备列表新行插入 | confirmed | 行高展开 + opacity 300ms ease-out | 直接插入 |
| 手机确认页按钮 loading | 点允许 | 按钮内 spinner（CSS border 旋转 0.8s linear infinite） | spinner 换静态「…」省略号文本 |
| 手机端重连条滑入 | 断线 | translateY(-100%)→0 200ms | 直出 |

全部包进 `@media (prefers-reduced-motion: reduce)` 覆盖。

## 6 · 边界与异常

| 场景 | 规格 |
|---|---|
| 中继不可达（桌面 start 失败） | 弹窗 `error` 态 + 重试钮；**不做 LAN 直配对降级**（配对必须经可信发证方，LAN 直连是数据面优化不是控制面降级）；文案提示检查网络 |
| 手机与桌面同 LAN 但中继挂了 | 手机页 `invalid` 态文案「无法连接配对服务」；P1 的 LAN 直连仅优化已配对后的数据面，不覆盖配对本身 |
| 桌面未登录 NebLink 就点配对 | start 返回 401 → 弹窗特判文案「请先登录 NebLink」+ 引导按钮跳 avatar 登录流程（复用既有 device-flow 弹窗），不静默失败 |
| 桌面 WS tunnel 断线中发起配对 | 允许（start 是 HTTP）；pair_event 由中继排队，tunnel 恢复后补推；弹窗状态行显示「等待中（事件通道重连中）」muted 提示 |
| QR 过期瞬间手机恰好点允许 | 中继权威：confirm 收 410 → 手机 `toolate` 态；桌面已切 `expired`。两端各自收敛，无脏状态 |
| 同一桌面重开配对弹窗 | 旧会话置 `replaced` 作废（中继保证）；旧 QR 扫了也 410 |
| 两台手机先后扫同一 QR | 第一台 confirm 成功后会话删除；第二台收 409/410 → `toolate` 文案「已被其他设备使用」 |
| 多手机配对 | P0 上限 3 台活跃手机设备；达上限再配对 → 桌面弹窗提示「请先在设备列表吊销一台」，不静默顶替 |
| 桌面离线时手机打开控制台 | 壳层状态条红点「桌面离线」+ 最后在线时间（来自中继 presence）；消息流显示缓存到最后可见位置；observer 无写操作可失败，messenger 输入条禁用并提示 |
| 手机浏览器隐私模式（localStorage 不可用） | confirm 前先探测存储可用性；不可用 → 确认页顶部警示条「隐私模式无法保持连接，每次需重新扫码」，降级为会话内存凭据（关页即失效） |
| 手机时钟严重不准 | 时效判断一律以中继服务器时钟为准（expiresAt 由中继签发、中继校验），端侧倒计时仅提示 |
| QR 内容超长（URL > QR 舒适容量） | pairId+code = 44 字符 + 固定前后缀 ≈ 80 字符 → QR 版本 4-5，180px 内轻松容纳；若未来加长须重验可扫性 |
| 超长桌面设备名 | 手机确认页设备名单行 ellipsis + `title` 全名；桌面列表沿用既有 ellipsis 规则 |
| 配对中桌面被关机/重启 | tunnel 断 → 中继排队 pair_event 失效（会话 TTL 自然过期）；重启后弹窗状态重置为 idle，用户重新发起 |
| locale 切换 | 桌面弹窗与设备列表文案全走 `t()`，i18n key 前缀 `neblink.pair.*`（先行注册 key，断言比对运行时输出——案例 001 教训①）；手机页按 `navigator.language` 静态选择 zh/en |

## 7 · 无障碍

- **键盘**：配对弹窗 focus trap（Tab 循环不逃逸）；QR 不可聚焦（纯图像，`aria-hidden`）；「取消/复制/刷新」全键盘可达；Esc 关闭弹窗 = 取消语义。
- **读屏**：弹窗 `role="dialog" aria-modal="true" aria-labelledby`；状态行 `aria-live="polite"`（scanned/confirmed/expired 态迁移自动播报）；shortCode 文本逐字符可读（`aria-label="核对码 X X X X X X"`，字母间空格）。
- **QR 的无视觉替代**：弹窗始终同时提供「复制链接」——无法扫码的用户可把链接发到手机（微信/邮件）打开，链接路径是一等公民不是备选。
- **对比度**：shortCode、状态行、按钮文字对比度 ≥ 4.5:1（亮暗双主题）；QR 白底黑码对比度天然 ≥ 15:1。
- **触控目标**：手机端所有可点元素 ≥ 44×44px（Apple HIG）；确认页主按钮高度 48px。
- **reduced-motion**：§5.6 全覆盖；手机页同样尊重 `prefers-reduced-motion`。

## 8 · 可断言验收点

口径纪律（案例 001 教训）：凡涉文案 → 锁定 i18n key；凡涉状态 → 锁定显式语义；凡涉度量 → 锁定增量口径。以下断言供 qa 转 Playwright（桌面端）+ 接口级测试（中继端）：

**桌面端（Playwright，隔离实例）**：

| # | 断言 | 二值判据 |
|---|---|---|
| A1 | 配对弹窗毛玻璃 + 无遮罩 | computed：overlay `background` = `var(--overlay-bg)` 解析为 transparent；面板 `backdrop-filter` 含 `blur(24px)` 且 `background` 为 rgba 半透明 |
| A2 | 入口位置唯一 | 设置面板 NebLink 区存在且仅存在 1 个 `pair.phone` 触发钮；侧栏/顶栏无配对入口（query 计数 = 1） |
| A3 | QR 渲染 | `waiting` 态 QR 容器内存在 canvas/svg（方案 a）且渲染尺寸 ≥ 180×180 CSS px；容器背景 computed = `rgb(255,255,255)`（亮暗主题均白底） |
| A4 | shortCode 格式与双端一致 | 桌面 shortCode 匹配 `^[BCDFGHJKLMNPQRSTVWXZ]{3}-[BCDFGHJKLMNPQRSTVWXZ]{3}$`；对同一 code 独立派生的期望值相等（纯函数单测） |
| A5 | 倒计时语义 | 状态行每秒文本含递减整数；无 CSS animation/transition 作用于倒计时元素（computed animation-duration = 0s） |
| A6 | scanned 态迁移 | mock 中继推 `pair_event{status:"scanned"}` 后 500ms 内：QR 容器 opacity computed ≈ 0.4 ± 0.05 且覆盖层含 `neblink.pair.scanned` 的 `t()` 输出文本 |
| A7 | confirmed 态迁移 + 设备列表插入 | mock 推 confirmed 后：弹窗出现 `neblink.pair.done` 文本；设备列表行数 = 基线 + 1 且新行含返回的 deviceName |
| A8 | expired 态与刷新 | TTL mock 到期后覆盖层含 `neblink.pair.expired` + 刷新钮；点刷新 → 旧 pairId 作废（cancel 被调用）且新 QR 渲染 |
| A9 | 吊销二次确认就地展开 | 点吊销 → 该行原位出现确认条（无新 dialog 节点）；确认 → 行文本含 `neblink.pair.revoked`；取消 → 恢复原状 |
| A10 | 键盘契约 | 弹窗内 Tab 循环 10 次焦点不逃逸到背景；Esc 关闭弹窗且触发 cancel 请求（网络断言） |
| A11 | i18n 纪律 | 弹窗子树内所有可见文案均为 `t()` 输出：注入伪 locale 使未知 key 渲染为 `[KEY]`，弹窗子树无 `[neblink.pair.*]` 裸 key 泄漏、无硬编码中文字面量（白名单：shortCode/设备名/URL） |
| A12 | reduced-motion | `prefers-reduced-motion: reduce` 下弹窗子树所有 animation-duration ≤ 0.01s |

**中继端（接口级测试）**：

| # | 断言 | 二值判据 |
|---|---|---|
| B1 | code 不出现在服务器日志/存储 | start 请求体只含 codeHash；中继存储记录 Grep 明文 code 命中数 = 0 |
| B2 | 单次使用 | 同一 pairId confirm 第二次 → 409/410 |
| B3 | hash 校验 | confirm 提交错误 code → 400 且会话不作废计数外泄（错误响应不含 codeHash） |
| B4 | 枚举限流 | 同 pairId 连续 5 次错误 confirm → 会话作废，第 6 次正确 code 也 410 |
| B5 | TTL 权威 | 伪造未过期客户端时间戳不影响判定：过期后 confirm 一律 410 |
| B6 | fragment 不发服务器 | 配对 URL 请求中继，access log 中 fragment 出现次数 = 0（HTTP 协议保证 + 日志抽查） |
| B7 | role 入 token | observer 档设备换发的 session token claims 含 `role:"observer"`；桌面网关对 observer token 的写接口一律 403 |

**手机端（Playwright mobile viewport 或真机抽检）**：

| # | 断言 | 二值判据 |
|---|---|---|
| C1 | 确认页单屏 | 375×667 视口 document scrollHeight ≤ 667 + 1px 容差 |
| C2 | 默认权限 | 确认页加载后权限单选默认选中 = observer |
| C3 | 短码大字可读 | shortCode 元素 font-size ≥ 28px 且字体为等宽族 |
| C4 | 防重复提交 | 点允许后 500ms 内二次点击不产生第二次 confirm 请求（网络计数 = 1） |
| C5 | 被吊销收敛 | 吊销后手机壳层下次请求收 401 → 显示撤销文案且 localStorage 凭据键被删除 |

## 9 · 参考链接

- RFC 8628 · OAuth 2.0 Device Authorization Grant — https://www.rfc-editor.org/rfc/rfc8628（轮询语义 / shortCode 字符集纪律 / 防枚举，2026-08-22 抓取验证）
- Tailscale · Device approval — https://tailscale.com/kb/1099/device-approval（批准/吊销落在设备列表、批准即生效无重启，2026-08-22 抓取验证）
- 微信网页版扫码登录 — 产品行为观察（两阶段反馈 / 过期原地刷新；https://weixin.qq.com/）
- Chrome Remote Desktop 帮助 — https://support.google.com/chrome/answer/1649523（一次性 PIN 时效纪律 / 持久设备列表）
- Apple HIG — https://developer.apple.com/design/human-interface-guidelines/（单一焦点弹窗 / 破坏性操作确认 / 44px 触控目标）
- Nebflow 内部基线：`RestApiRoutes.scala:526-703`（enroll + device-flow）· `NeblinkClient.scala:166-283`（session/login/heartbeat）· `NeblinkRelayTunnel.scala`（WS tunnel 协议）· `Protocol.scala:22-30`（DeviceApi 路径表）· `~/.nebflow/skills/nebflow/visual-style/SKILL.md`（视觉铁律）· 案例库 `~/.nebflow/skills/design-system/SKILL.md` 案例 001（i18n key 先行 / 显式状态语义 / 增量口径三条断言纪律）

## 10 · 分期建议（P0 最小闭环 vs P1 增强）

### P0 · 最小闭环（扫码/链接 → 手机能看）

| 项 | 内容 |
|---|---|
| 中继 | 4 个配对端点（§2.4）+ 配对会话对象 + pair_event 推送/补推 + 设备记录新增 `kind/ownerDeviceId/role` 字段 |
| 桌面 | 配对弹窗全状态机（§2.2/§5.1）+ 设备列表手机行与就地吊销（§5.2）+ tunnel 收 pair_event |
| 手机 | 确认页（§5.3）+ 控制台壳连接层与只读视图（§5.4）+ observer/messenger 双档 |
| 数据面 | 全部经中继（§2.5 P0） |
| 显式不做 | LAN 直连、admin-approve 门、full 档、手机丢失远程擦除、最近活跃 IP 展示 |

P0 验收 = §8 A1-A12 + B1-B7 + C1-C5 全绿。

### P1 · 增强

1. **LAN 直连数据面**（status 下发 lanEndpoints，手机先试直连回落中继）——降延迟，控制面不变。
2. **admin-approve 门**（设置开关：扫码后桌面须点「允许」才发证；对应状态机 `waiting → pending-approve → confirmed`）。
3. **full 档开放**（含创建会话/审批 AskUserQuestion/工具触发），升档需桌面二次确认。
4. **手机丢失处置**（一键吊销+远程擦除+存储清理，§3.3）。
5. **设备安全可视化**（最近活跃时间/IP、异常登录提示）。
6. **手机 PWA 化**（manifest + 添加到主屏幕 + 后台通知，依赖服务端推送通道）。
7. **多桌面选择**（一个手机配对多台桌面后，壳层顶部切换目标桌面）——P0 已支持多手机对一桌面，反向多对一留给 P1。

### 实现依赖顺序建议

中继端点（B 断言可先行接口测试）→ 桌面弹窗（A 断言）→ 手机确认页+壳层（C 断言）→ 端到端联调。桌面与手机页面可并行开发（契约 §2.4 已锁死）。

## 附录 A · 中继服务器（Rust）实施清单

本规格不改产品代码；以下为派发中继团队的任务清单摘要：

1. 新增配对会话存储（内存 + TTL 清理即可，无需持久化）：`{pairId, codeHash, ownerDeviceId, status, expiresAt, confirmAttempts, pendingEvents[]}`。
2. 端点：`POST /api/pair/start`（Bearer session）· `GET /api/pair/{id}/status` · `POST /api/pair/{id}/confirm`（限流 5 次/pairId）· `POST /api/pair/{id}/cancel`。
3. 设备表扩展字段：`kind`（desktop/mobile-web）· `ownerDeviceId` · `role`（observer/messenger/full）；session token claims 注入 role。
4. WS tunnel 下行新消息类型 `pair_event`（含离线补推队列，按 pairId 幂等）。
5. 静态托管手机页 `/pair/{pairId}`（确认页 + 控制台壳，见 §5.3/§5.4）；域 `neblink.nebflow.space` 已有 HSTS 前提，确认覆盖。
6. 吊销接口：deviceToken 即时失效（既有设备管理面扩展 `DELETE /api/device/{id}` 语义）。

## 待裁定项（冻结前需用户/Manager 确认）

- Q1：P0 默认档 observer 是否符合预期？（或希望默认即 messenger）
- Q2：QR 库引入（方案 a，+18KB vendor 依赖）vs P0 仅链接（方案 b）？
- Q3：P0 手机上限 3 台是否合适？
- Q4：手机页托管在中继域（`neblink.nebflow.space/pair/…`）无异议？（备选：桌面网关直托管，但跨网络不可达，不推荐）
