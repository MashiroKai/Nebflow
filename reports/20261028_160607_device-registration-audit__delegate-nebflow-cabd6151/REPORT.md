# 设备注册行为审计：新装机→登录→控制台开了但设备列表无此设备（作者问 2026-10-28 15:3x「现在版本修复了吗」）

纯只读核查+评估，零代码/配置/引擎改动（未触碰 pid 70481/6138）。基线：本 worktree main HEAD `3cde1fa30`（2026-10-28 15:06），官方 beta 通道实测拉取见 §3。

## 0. 四问一句话结论

1. **行为链**：官网门户（浏览器）登录**不会**让本机引擎注册进任何名册——引擎入网只有「引擎自己发起的登录」一条路（console 头像 PKCE / CLI 登录），成功后引擎在 link-server 落**持久设备行**（enroll），但要出现在各端「设备列表」还须持有**活会话**（心跳 45s 保活、TTL 90s 清扫）。引擎本地 pairing 表（`neblink/devices.json`）登录**不进**，它是手工 HMAC 配对表。
2. **设备列表数据源**：控制台设备段读 `/api/neblink/status` 的 `peers`＝link-server **活会话名册**（本机自身被 UI 过滤，永不显示自己）；「三路径收口同一 persist」＝各登录路径都汇聚到 `LogtoTransport.register → POST /api/device/register → completeDeviceEnrollment(NeblinkEnrollment.persist)`，入列条件＝enroll 成功 ∧ 引擎进程在跑且心跳活着。
3. **版本核对**：官网 beta 通道今天装的仍是 **2026.10.21-beta.2**（OSS 指针+manifest 实测）；GitHub 虽有 v2026.10.24-beta.1 但 beta 指针未切。本症状 10-22 已有过同族分诊：引擎链完整（设计内）、真缺口＝门户空态引导（website 仓，候批未落）；控制台「零凭据登录发起 403」鸡生蛋 bug **main 已修**（10-22 bootstrapGate）＋无头登录 10-26 落 main——**两者都不在官方 beta 通道里**。名册「活会话成员」语义本身是**设计不是 bug**，main 相对 10-21-beta.2 未改注册语义（EnrollGuard diff 仅注释）。
4. **差距与方案**：推荐「引导配对」路线（首启未登录开门改本地引导面 + 门户空态引导卡，~1.5-2 天，零新权限面）；「门户代注册」路线（~2-3 天+安全评审）不建议——最小变体（门户探测 `/api/health` deviceId 锚后一键跳本机引导，~0.5-1 天）已达同效。**自动注册无「陌生人见我设备列表」风险**（网络＝账号隔离，注册须账号主人 token）；真正的语义落差是「列表＝活会话成员」而非持久资产清单，若要离线设备灰显常驻是 link-server 侧 roster 语义改动（~1-2 天，需作者拍板）。

---

## 1. 问① 行为链：登录与名册的关系；控制台面 vs 引擎设备

### 1.1 两条「登录」必须先分开

| 登录面 | 代码入口 | 对引擎名册的效果 |
|---|---|---|
| **官网门户登录**（浏览器 mystand.space → Logto 托管页） | website 仓（本机无此仓）；引擎仓零参与 | **零**。只建立浏览器↔网站的 Logto 会话；不调 `/api/device/register`、不写 config.json/deviceToken、不建 relay 隧道 |
| **引擎登录**（console 头像 PKCE ／ CLI `stand login`） | `POST /api/neblink/auth/start`（PKCE 路径①，`NeblinkRoutes.scala:815`）；`POST /neblink/auth/password`（无头路径②，`NeblinkRoutes.scala:751`，#924） | **注册**：两路径汇聚 `LogtoTransport.register`（`LogtoTransport.scala:10-36,89-140`）→ link-server `POST /api/device/register`（EnrollResponse: deviceToken/networkId）→ `completeDeviceEnrollment` → `NeblinkEnrollment.persist`（`NeblinkEnrollment.scala:47-263`） |

引擎侧 persist 落三件事：凭据（**单源化**后唯一权威写面＝`config.json` 的 `neblinkServer.deviceToken`，kaiauth 修法批②；`neblink/device.json` 只留 logto 块+身份面）→ 客户端热换 → `ensureRelayTunnel` + `PeerDiscovered`（`NeblinkEnrollment.scala:201-263`）。**此后设备才进入 link-server 的名册体系。**

### 1.2 link-server 侧名册：持久设备行 ≠ 列表可见性

（link-server 仓不在本机；以下行号全部来自本仓注释的跨仓只读引用，如实标注）

- **持久层**：enroll 按 `(deviceId, networkId)` `INSERT OR REPLACE`（`store.rs:2951-2969`，引于 `NeblinkEnrollment.scala:78-83`）；首设备 enroll 自动 `get_or_create_default_network`（`store.rs:697`「auto-created on first device enrollment」，10-22 分诊报告 §② 引）——**账号即网络，登录即入网无建网步骤**（`neblink.js:383-384` 注释逐字）。deviceId＝机器码派生 UUIDv5（`EnrollGuard.scala:10-12`），新电脑＝新 deviceId，无跨账号劫持面（服务端 409 机器闸 `device_bound_other_account`，`LogtoTransport.scala:63-69`）。
- **可见层（列表真正读的）**：**活会话表**。`devices_for_user(user)`＝`SELECT DISTINCT device_id, network_id FROM sessions WHERE user_id=?1`（`store.rs:3130-3134`，引于 `NeblinkDiscovery.scala:205-207`）；会话由引擎每 45s 心跳续命（`NeblinkClient.scala:514-545`，心跳带 endpoints/version/build/uiPort），`DEVICE_LIVENESS_TTL=90s`＋30s 清扫（`store.rs:378` / `main.rs:66-71`，引于 `NeblinkDiscovery.scala:199-203`）。**引擎一停/心跳断 ≥90-120s，该设备就从所有端的发现响应里消失**，各引擎本地名册经 `syncPeers` 收敛删除（`NeblinkDiscovery.scala:117-131`：「vanished from the server response are removed」ghost 防治）。relay 隧道连接/断开时服务端广播 online/offline（`relay.rs:649/694`），引擎侧 presence v2 推送即时刷 lastSeen（`NeblinkService.applyServerPeerStatus`，`NeblinkService.scala:400-416`）。

### 1.3 引擎本地 pairing 表：登录不进

`neblink/devices.json`（`core/device/DeviceRegistry.scala`）是 **v2.1 阶段 1 手工配对登记面**：「两侧各写一份相同 sharedKey 的 devices.json 即完成配对；注册门户引导属阶段 2」（类注释逐字），存 HMAC 共享密钥+allowSpawn，运行期**只读**消费者＝DeviceDelegate（跨设备 spawn）、RemoteExecutor（P2P 工具调用）、AgentSessionSync/AgentIdentitySync（逐对端签验）。全 src/main **无运行期写点**——登录、入网、presence 都不碰它。它与「设备列表」完全正交。

### 1.4 浏览器控制台（隧道面）vs 引擎设备（presence 名册成员）

代码上是两个物种：

- **控制台＝一张网页**。门户隧道链：portal 页发 ConsoleTicketMint → relay WS（`wss://link.mystand.space/api/device/relay-ws`）→ 目标引擎的 `NeblinkRelayTunnel` → `PortalTunnelBridge` 回真 loopback `ws://127.0.0.1:6138` → `/?ticket=` 兑一次性票（relay 铸 90s/本地 break-glass 120s，`ConsoleTicketStore.scala:168`）→ `stand_session` cookie → 控制台全功能（参考报告 `reports/20261028_104800_report2-.../REPORT.md` §5）。**控制台本身不是任何名册的成员**；它能打开，当且仅当被隧道指向的引擎已 enroll 且 relay 隧道活着。
- **引擎设备＝presence 名册成员**＝enroll 行 ∧ 活会话 ∧（对跨网可达性）relay 隧道。控制台里「设置→账号」显示的本机行来自 `/api/neblink/status` 的 `device` 字段（本机身份，恒在）；「设备列表」行来自同端点的 `peers` 字段（他机，见 §2）。

### 1.5 套作者场景（官方 beta 通道＝10-21-beta.2，新电脑）

1. 装 beta、`stand start` → 引擎无凭据：boot 客户端**不创建**（`GatewayMain.scala:1112-1138` 仅在 `neblinkConfig.neblinkServer` 已配置时建；`1357-1358` 隔离根拒绝时打 `[neblink] ... boot client not created`）——**全新安装自动注册动作为零**（设计如此，EnrollGuard 家族护栏；默认根无闸，`EnrollGuard.scala:104-119`）。
2. boot 开门自动开浏览器到**门户**：`PortalUrl.startupUrl`（`GatewayMain.scala:1932-1957`；`PortalUrl.scala:31-78`）＝env>config>brand 缺省 `https://mystand.space/portal`＋`?device=<本机 deviceId>`（`brand.conf:16`；`NEBFLOW_OPEN_UI=local` 是本地逃生口）。**注意 `?device=` 只是个参数，门户能否隧道到它取决于该引擎是否在线。**
3. 作者在门户登录（Logto 托管页）→ 网站会话建立，**新电脑引擎仍未 enroll**：无 deviceToken→无 relay 注册→`?device=<新机>` 隧道无对端。
4. 「控制台直接打开了」只能是：**隧道落到了另一台已 enroll 且在线的引擎**（他的老机器），或门户空态。作者看到的设备列表＝那台引擎的 peers——新机当然不在。10-22 同族分诊已判：**「没有主机」卡死＝门户空态（website 仓缺口），本仓链完整零改**（`reports/20261022_183000_new-device-triage__delegate-nebflow-4a1ffcec.md` §②；跨仓清单三项**候批至今**）。
5. 若作者看的是**新机自己的控制台**设备段：本机被自我过滤**永不入列**（`neblink.js:340-345` 显式剔除 self），列表只剩他机活会话成员——老机不在线时就是空态「暂无其他设备」（`zh-CN.js:2270`）。两种读法根因同源：**列表＝活会话名册，非持久资产清单，且不含本机**。

---

## 2. 问② 设备列表数据源与「三路径收口」

### 2.1 数据源全链

```
contacts 面板设备段 (contacts.js:332-333,378-424)
  └ devicePeers() = getNeblinkState().peers，未登录返回空集 (messages.js:5354-5360)
      └ fetchNeblinkStatus() → GET /api/neblink/status → data.peers（本机 selfId 过滤，neblink.js:296-369 特 :345）
          └ NeblinkRoutes.scala:186-296 → ms.peers = peersRef（NeblinkService.scala:301）
              ├ 服务端名册：45s 心跳/发现 → client.discover = POST /api/device/heartbeat 响应 peers → syncPeers 收敛
              │   （NeblinkDiscovery.scala:107-159；服务端源=活会话表，§1.2）
              ├ presence v2 推送：applyServerPeerStatus 在线刷 lastSeen/离线置 0（NeblinkService.scala:400-416）
              ├ LAN announce：handleAnnounce（NeblinkService.scala:361-378，同网段第二来源）
              └ 行内 online 判据 = 服务端可见新鲜度（isPeerOnline），directOnline = P2P WS 实连（NeblinkRoutes.scala:204,230）
```

**新电脑引擎要出现在这里，唯一动作链＝引擎侧登录**（§1.1 表右列）：`register` 成功 → persist → `ensureRelayTunnel` → 首次 `POST /api/device/session` 会话铸造 → 心跳循环接管 → 下一拍（≤45s）出现在同账号所有在线引擎的发现响应与 presence 推送里。**不启动引擎/不心跳，enroll 了也不显示。**

### 2.2 「三路径收口同一 persist」的今天形态

参考报告 §5 原口径「配对码/浏览器 PKCE/无头密码」三路径，现状收缩：

| 路径 | 状态 | 入口 |
|---|---|---|
| 配对码（device-flow） | **已退役**：控制台入口 2026-10-13 作者令移除（`neblink.js:379-384`）；`/api/neblink/device-flow/*` 路由+CLI `--device-code` 腿 2026-10-26 整体退役（#924，`LogtoTransport.scala:26-31`、`NeblinkRoutes.scala:730-732`） | — |
| 浏览器 PKCE（路径①） | 在役。console 头像 → `POST /api/neblink/auth/start`（`NeblinkRoutes.scala:815`）→ Logto 托管页 → 环回 `127.0.0.1/auth/callback`（单次 state 标记 ⇒ `explicitUserAction=true`，`EnrollGuard.scala:39-45`） | 桌面/有浏览器 |
| 无头密码（路径②） | **main 新增**（10-26，#924）：`POST /neblink/auth/password`，CLI `stand login` 递账号密码给本机网关，`LogtoPasswordFlow` 走 Logto Experience，同样收敛 `register+persist`，`explicitUserAction=true`（`NeblinkRoutes.scala:723-781`） | 无浏览器/SSH |

（beta.2 里的 CLI login 还是设备码流形态：打印 `Code: XXXX-XXXX` → 作者去网页批准——10-22 分诊报告 §② 实录。）

三/两路径的汇聚点逐字同一：`LogtoTransport.register`（provider token → `/api/device/register` → EnrollResponse）→ `completeDeviceEnrollment` → `NeblinkEnrollment.persist`。**设备列表/在线态对两条路径完全一致**（`NeblinkRoutes.scala:739-746` 注释明示）。

---

## 3. 问③ 版本核对：官网给的是哪个 beta；main 动没动注册代码；修复判定

### 3.1 官网下载位实测（2026-10-28，curl+网页核）

| 面 | 值 | 证据 |
|---|---|---|
| beta 通道版本指针 | **2026.10.21-beta.2** | `https://nebflow-releases.oss-cn-hangzhou.aliyuncs.com/latest-beta-version.txt`（本机 curl 实拉）；`updates/manifest-beta.json` 同值，build `1ab024df2`，publishedAt `2026-10-21T14:58:24Z` |
| 稳定通道 | 2026.9.19 | 同 bucket `latest-version.txt` |
| 安装链 | `mystand.space/install-beta.sh|ps1` → `install.sh --beta` → 上指针 → bucket jar `stand-assembly-2026.10.21-beta.2.jar`（sha256 502fcf04…，110,274,860 B） | 门户页+安装脚本实拉（website 仓不在本机，网页核由只读 recon 完成） |
| GitHub releases | v2026.10.24-beta.1（10-24T14:05Z）已发布**但 beta 通道指针未切**；v2026.10.21-beta.2=Pre-release（10-21）；正式版 2026.9.19 | releases.atom+release 页 |
| main 版本串 | `VERSION`＝2026.10.26-beta.2（未发布；在产引擎跑 main tip 构建同串，见 `reports/20261027_230551_beta2-verify/REPORT.md`） | 仓内 VERSION |

**作者新机今天装 beta，拿到的就是 2026.10.21-beta.2。**

### 3.2 main 相对 v2026.10.21-beta.2 的注册/配对面改动（`git log/diff tag..HEAD`）

设备注册语义**零变化**：`EnrollGuard.scala` diff 6 行纯注释（device-flow 退役+headless 传 true）；`NeblinkClient/NeblinkService` 各 2 行；`NeblinkEnrollment.scala` 零 diff。实质改动集中在**登录入口面**：

| commit | 内容 | 对本症状的意义 |
|---|---|---|
| `606e536f8`（10-22，main-only） | **bootstrapGate**：auth/start、auth/state、neblink/status 三面换「匿名+环回腿」放行——修「零凭据新设备连登录都发起不了」（鸡生蛋缺陷，作者 Windows 实截「状态读取失败 HTTP 403」） | 新机本地控制台/CLI 登录入口打通（**beta.2 里没有**） |
| `142b3e9ed`（10-26，#924，main-only） | 无头密码登录路径② + 设备码流整体退役 | 新机无浏览器/远程 SSH 也能 enroll（**beta.2 里没有**） |
| `aca93765a`（10-25） | 旧域清扫：installUrl→mystand.space；auth/neblink/api 服务子域按裁定保留 | 引擎内嵌 auth 缺省仍指 auth.nebflow.space（`NeblinkModel.scala:765-769`），账号体同租户，非本症状因子 |
| 其余（portal v1.3 B0-B2、#949/#944、f09e98fd2 身份同步、4e9779f70 设备挂载等） | 数据面/传输/周边 | 不改注册语义 |

### 3.3 修复判定（回答「现在版本修复了吗」）

- **官方 beta 通道（10-21-beta.2）：没修**。该版本里：① 零凭据新机的本地控制台登录发起 403（bug，main 10-22 才修）；② CLI 登录只有设备码流（要第二台设备网页批准）；③ 门户登录≠引擎 enroll 的落差照旧；④ 门户空态无引导（website 仓缺口）。
- **main（未发布）：登录入口链已通**（①②已修），引擎侧「登录即注册入网、在线即在列」的链 10-22 分诊已判**完整零改**。但作者描述的**体验落差主体不在引擎仓**：门户（浏览器登录后的引导/空态/设备面）属 website 仓，10-22 跨仓清单**候批至今**，本仓可见证据范围内无落地记录（website/link-server 仓均不在本机，无法直接核伪——如实声明）。
- **定性**：名册＝活会话成员、本机自我过滤、门户登录不注册引擎——**设计**（有意语义：ghost 防治、单活会话踢旧、隔离根护栏都有成文作者裁定）；零凭据控制台 403——**曾是 bug，main 已修、通道未发布**。

---

## 4. 问④ 差距与方案评估（设计先行，未实施）

### 4.1 差距定性

作者期望「新装机登录即出现在设备列表」。现状差距拆三层：
- **G1 引导缺口**：新机首启唯一自发动作是开门户，门户登录又恰好**不**enroll 引擎——用户的自然路径永远到不了引擎登录。10-22 分诊已把「门户空态引导」列入候批。
- **G2 语义落差**：「列表」是活会话成员视图，引擎一停 ~2min 内消失；若期望是持久资产清单（离线灰显），需要改语义而非修 bug。
- **G3 发布断层**：入口链修复只在 main，beta 通道落后 7 天。

### 4.2 安全边界（先回答作者之问：自动注册危险吗）

- **「陌生人登录即见设备列表」不成立**：设备列表按网络（＝账号）隔离；`/api/device/register` 要 Logto access token（账号主人才能注册）；新设备 deviceId 机器码派生＋服务端 409 机器闸防跨账号绑定（`LogtoTransport.scala:63-69`）。陌生人注册只进陌生人自己的网络。
- **真实风险面两条，且都已有护栏或可控**：① 隔离数据根实例自动注册污染生产名册（2026-09-11 事故）——EnrollGuard 案 b① 已拦，仅显式用户动作放行（案 C）；② 同账号多机互踢——单活会话按 `(deviceId, network)` 维度，新机是新 deviceId，不互踢；「显式登录解锁停摆」语义（案 C）已把「自动路径不静默入网」钉死。
- **结论**：把「引擎侧显式登录＝自动注册入列」做成默认引导**不引入新权限面**（这正是 `zh-CN.js:1770` 登录提示已承诺的「登录后自动入网」）；要守住的只有「门户代注册」不得变成无用户动作的静默注册（案 C 判据照用）。

### 4.3 两条路线

**路线 A：装机引导配对（推荐，零安全模型改动）**

- A1 引擎仓（~0.5-1 天）：首启未登录时开门目标回落**本地引导面**——`PortalUrl.startupUrl` 加「无凭据判据」（读 DeviceCredential 存在性，纯本地读）→ `LocalConsoleUrl.authenticated` 本地自铸票；控制台首屏渲染登录卡（PKCE 卡已有，bootstrapGate 已把匿名环回腿铺平）。用户点一下头像→托管页登录→`explicitUserAction=true`→persist→隧道自连→≤45s 全端在列。
- A2 website 仓（~1 天，跨仓）：门户空态/`?device=` 离线卡——门户已可经环回 `/api/health` 的 `deviceId` 锚＋门户 origin 白名单 CORS 探测「本机引擎在不在跑、是不是 ?device= 那台」（`HealthRoutes.scala:22-67`，现成管道）→ 未 enroll 时给「在本机完成登录」引导（跳 `http://127.0.0.1:<port>` 或给出 `stand login` 一行命令）。
- 合计 **~1.5-2 天**（含 spec/e2e），风险低；与 10-22 候批清单同构，属把它做完。

**路线 B：门户代注册/更深自动化（不建议全量做）**

- B1 最小变体（~0.5-1 天）：即 A2 的探测+一键跳转——门户**不持有**任何新令牌通道，登录动作仍由用户在新机浏览器里亲手完成（PKCE 回环不变），安全面与 A1 等价。
- B2 全量（~2-3 天+安全评审，**不建议**）：门户把 Logto token POST 给引擎新环回路由代注册。需要 origin 白名单+令牌经浏览器中转的暴露面/重放评审；相对 B1 无体验增益，反新增一条令牌通路，违背「显式用户动作」裁定精神。

**可选语义件（独立拍板）**：若「离线设备也常驻列表（灰显）」才是作者要的——link-server 侧 discover 返回面改读持久 devices 表（行已在，enroll 即持久）+引擎/前端离线态渲染，引擎+服务端 **~1-2 天**；这改变「列表＝在线成员」的产品语义，须作者明示再动。

### 4.4 给作者的直接建议（顺序）

1. 先确认作者新机当时到底走的是哪条登录面（门户登录＝不注册引擎；console 头像/CLI 登录才算）。若是门户登录，行为**符合当前设计**，官方 beta 通道也无从「修复」。
2. 若要立刻可用：新机上跑 `stand login`（beta.2＝设备码流需第二台设备批准；main 构建支持密码直登）→ ≤45s 全端设备列表可见（引擎保持运行）。
3. 体验修复走路线 A（A1 引擎仓小改 + A2 门户引导卡，即 10-22 候批清单落地）；发布侧把 main 的登录入口修复带上 beta 通道。

---

## 5. 证据与局限

- 本机锚点：`NeblinkEnrollment.scala:47-263`、`EnrollGuard.scala:39-178`、`LogtoTransport.scala:10-140`、`NeblinkDiscovery.scala:107-231`、`NeblinkService.scala:299-416`、`NeblinkRoutes.scala:186-296,723-815`、`NeblinkClient.scala:503-610`、`GatewayMain.scala:1112-1138,1357-1358,1932-1957`、`PortalUrl.scala:31-78`、`HealthRoutes.scala:22-67`、`core/device/DeviceRegistry.scala`（类注释）、web `neblink.js:296-369,379-384` / `contacts.js:332-424` / `messages.js:5354-5360` / `locales/zh-CN.js:1770,2269-2270`。
- 历史件：`reports/20261022_183000_new-device-triage__delegate-nebflow-4a1ffcec.md`（同族症状 10-22 分诊，§② 本仓零改+跨仓清单）；`reports/20261028_104800_report2-.../REPORT.md` §5（隧道链/配对三路径原口径）；`reports/20261027_230551_beta2-verify/REPORT.md`（在产引擎=beta.2 构建）。
- 外部实测：OSS 两个版本指针文件、mystand.space 安装脚本/manifest-beta.json、GitHub releases atom（只读 recon，2026-10-28）。
- **局限（如实）**：link-server（neblink-server）与 website 仓不在本机——服务端行为引自本仓注释的跨仓行号与 10-22 分诊的实测记录，未独立复核；门户登录后的确切前端行为（空态/设备面）不可本机验证；作者当时看的是哪个面的「设备列表」（老机控制台/门户/新机本机）无截图依据，§1.5 两种读法均给出。
