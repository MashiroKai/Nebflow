# presence 候选地址/端口的来源审计（只读）

- 基线：worktree `delegate-nebflow-4280aa54`，HEAD `3047906d0`（= main tip），零 `src/` 改动。
- 范围：候选端点「从哪来 → 谁排序 → 谁拨 → 失败后怎么记账」，判发现逻辑是否断因之一。
- 配套实施规格：同目录 `fix-spec.md`（供 p3-fix 直接照写）。

## 0. 结论（先给，不做「多因素」糊话）

按影响排序，发现面上有三条真缺陷 + 一条被证伪的嫌疑：

| # | 缺陷 | 影响 | 现状 |
|---|---|---|---|
| **C-1** | **候选集被「观察式覆盖」塌缩成单地址**：4 个 `PeerInfo` 生产者里 3 个写 `endpoints = Nil`、`address = http://<观察到的对端源 IP>:<port>`，且都是**整记录替换** ⇒ 名册给的 N 条候选最多活到下一条入站 presence/announce 为止（≤1 拍，45s） | 任何读 `neblinkService.peers` 的腿（`RemoteExecutor` p2p 下发、路径记忆 `addressKey`）失去回落列表；坐标集一变 `addressKey` 就换键 ⇒ 负证据失效、重新付探测税 | **未修**（76417e90 未触及） |
| **C-2** | **拨号腿没有跨拍负证据复用 + 串行 + tailnet 首位**：`EvictionPolicy` 只在本腿内、要 5 次连续超时才生效；与文件腿的 `P2pReachability` 互不可见 ⇒ 每轮白等一个候选预算 | 黑洞 tailnet 首位：每轮多付 3000ms；两候选全黑 = 4500ms/轮（与 `P2pReachability` 注释的实测读数一致） | **并行批 76417e90 已修**（P1 并发首胜 / P2 专用池 / P3 共用负缓存）。本审计**不另造第二套** |
| **C-3** | **上报面污染**：`detectLocalEndpoints` 无地址级过滤（169.254/16 全量上报），虚拟网卡只靠名字 marker ⇒ TAP / tun / utun / ZeroTier / Hamachi / Npcap 全漏网 | 每条污染地址都是对端要付满一个预算的候选（Lan 1500ms / Other 2500ms），并发批之后也会占满 `maxConcurrentPerRound`=4 的名额、把真候选挤到下一拍 | **未修** |
| **C-4** | **每次拨号新建 `HttpClient` 且不设 `connectTimeout`**：预算到点只是调用方不再等，底层 TCP 连接/握手继续在飞；每个 client 自带 selector 线程 | 黑洞候选在并发批下放大成 ×N 的线程/句柄churn；预算并非资源上界 | **未修** |
| ~~端口混用~~ | **证伪**：`serverPort` 恒 = `cfg.port.value` = 网关端口（缺省 6138），且拨号侧候选自带端口优先 | — | 零缺陷 |

主因判定：**C-1 是发现逻辑自身的断因**（候选集保真），**C-2 是拨号策略的断因**（已在并行批修掉），C-3/C-4 是放大器。三者都不是端口问题。

---

## Q1 `PeerInfo.endpoints` 的生产路径：谁填充、按什么顺序

四个生产者，两条腿（名册 / 观察），全部落到同一张 `peersRef`：

| 生产者 | file:line | `endpoints` | `address` |
|---|---|---|---|
| 名册（登录/心跳） | `neblink/NeblinkClient.scala:1420-1444` | **全量**、`EndpointPreference.order` 排序 | `urls.head`（:1438） |
| 入站 presence WS | `gateway/PresenceRoutes.scala:535-541` | `Nil`（缺省） | `s"http://$remoteIp:$peerPort"`（:539） |
| announce 推送 | `neblink/NeblinkService.scala:382-388` | `Nil` | `s"http://$remoteIp:$port"`（:386） |
| 入站 handshake | `neblink/NeblinkService.scala:445` | `Nil` | `s"http://$callerIp:$port"`（:444） |

写入方式全是**整记录替换**，没有字段级合并：

```
NeblinkService.scala:333-338   peersRef.modify { peers => ... (peers + (peer.deviceId -> finalPeer), ...) }
NeblinkService.scala:392       peersRef.update(_ + (info.deviceId -> finalPeer))
NeblinkService.scala:449       peersRef.update(_ + (deviceId -> finalPeer))
NeblinkService.scala:327-340   def upsertPeer(peer): peersRef.modify(...) → 同上（PresenceRoutes.scala:542 走这里）
```

名册腿的排序（`NeblinkClient.scala:1430-1433`）：

```scala
val urls = EndpointPreference.order(
  p.endpoints.map(ep => s"http://${ep.address}:${ep.port}"), prefixes)
```

`EndpointPreference.rank`（`NeblinkModel.scala:678-682`）：`100.64.0.0/10 ⇒ 0`、**本机同 `/24` ⇒ 1**、其余 ⇒ 2；`order` = 稳定排序 + 去重（:685-686）。设计依据写在 `NeblinkModel.scala:632-637`（2026-09-11 事故：首位是不可达 LAN，tailnet 在第 1 位却从不被拨）。

**确认：tailnet 优先、同网段 LAN 排其后。** 消费侧 `PresenceService.candidatesOf`（`NeblinkPresenceService.scala:322-324`）原样保留该顺序，只在 `endpoints == Nil` 时回落 `List(peer.address)`（= C-1 塌缩后的单地址）。

**这个优先序会不会让整轮预算被首位吃光**——会，但上界不是「整轮」：
- 串行臂逐候选付满本类预算（:479 `dialBudget.budgetMsFor(endpoint)`），首位黑洞 ⇒ 每轮固定多付 3000ms（tailnet 档）；
- 单轮总预算 12s 封顶（`DialBudget.roundBudgetMs`，:107），但判据在**起拨前**检查（:462），且 `attemptedRev.nonEmpty` 才检查 ⇒ 名义 12s 是**软上限**，实际墙钟上界 = `12000 + 单候选最大预算(3000) = 15000ms`（按现有档位的可达组合实测 13.0s）；
- 12s < 同步拍 45s（`SyncBeatFloorMs`:1321）⇒ 不跨拍，但**每拍都白等一次首位**。

## Q2 对端（KAI）自报的地址集合是否含陈旧/虚拟网卡地址

上报面 `NeblinkClient.detectLocalEndpoints`（:279-299）：

```scala
val allNics = NetworkInterface.getNetworkInterfaces.asScala.toList.filter(_.isUp).filterNot(_.isLoopback)
def toEndpoints(nics) = nics.flatMap { nic =>
  nic.getInetAddresses.asScala.toList
    .filter(_.isInstanceOf[java.net.Inet4Address])                       // ← 唯一的地址级过滤
    .map(addr => NeblinkEndpoint(addr.getHostAddress, serverPort, classifyKind(...), label))
}
val physical = allNics.filterNot(isLikelyVirtualNic)
val preferred = toEndpoints(physical)
val chosen = if preferred.nonEmpty then preferred else toEndpoints(allNics)   // ← 只保证「不为空」
```

- **地址级无过滤**：`169.254.0.0/16`（APIPA / 网线半通）、`0.0.0.0` 一律上报；同一网卡多地址全上报（无去重、无封顶）。
- 虚拟网卡判据（:321-325）只认 `isVirtual`（注释自认在 Windows 上不可靠）+ 名字 marker：
  `virtual / vethernet / vmware / docker / wsl / hyper-v / virtualbox / loopback pseudo`。

**Windows 上会漏掉哪些（KAI 侧）**：
- `TAP-Windows Adapter V9`（OpenVPN）、`ZeroTier One [<netid>]`、`Hamachi`、`Npcap Loopback Adapter`、各类 `* TAP/TUN *` 适配器 —— 名字里**没有**任何 marker ⇒ 一律进候选；
- ZeroTier 常给 `10.x` / `172.x`，会被 `classifyKind` 判成 `lan`（:302-303 只看地址段），于是对端拿到一条「看起来像同网段、实际跨 overlay 不可达」的 LAN 候选，黑洞且最贵。
- `vEthernet (...)` / WSL / Hyper-V / Docker Desktop 这几类是**能**命中的（`vethernet` / `wsl` marker）。

**tailnet 能否被正确分类**：能，且是**两件事同时成立**才安全：
1. 分类只按地址段（`classifyKind` → `EndpointPreference.isTailscaleHost`，`NeblinkModel.scala:666-667`，`100.64/10`）⇒ 与网卡名无关，Windows 上 tailscale 是真实 NIC、名字 `Tailscale` 不含任何 marker ⇒ **不会被误过滤**，`kind = "tailscale"`，排序档 0。✔
2. 反向风险在 macOS：userspace 模式下 tailscale 表现为 `utun*`，`utun` 不在 marker 表里 ⇒ 不过滤；靠地址段兜住分类。但**同名的 utun（WireGuard / 其它 VPN）持 `10.x` 时会被当成 LAN 候选**上报 —— 与 Windows 侧同一处漏。

## Q3 有没有「候选可达性预探」；单次/单轮预算算术

**拨号腿没有预探**：`dialRound` → `dialCandidates` 直接开拨（:409-498），预探尝不到任何收益（见下算术）。

预算常量（`NeblinkPresenceService.scala`）：

| 项 | 值 | 行 |
|---|---|---|
| Tailnet 单候选 | 3000ms | :73 |
| Lan（RFC1918/环回/169.254）单候选 | 1500ms | :76 |
| Other（公网/主机名）单候选 | 2500ms | :79 |
| 单轮总预算 `roundBudgetMs` | 12000ms | :107 |
| `roundUpperBoundMs` = `min(12000, Σ候选预算)` | 计算值 | :113-114 |

**候选 × 预算算术（现状=串行）**：

- KAI 形态（1 tailnet + 1 同网段 LAN，tailnet 黑洞）：`3000 + ~0 = 3.05s/轮`；两候选全黑 = `4500ms/轮`（与 `core/tools/P2pReachability.scala:14-16` 记录的实测值逐字一致）。
- 上界组合：全 tailnet 4 条 = 恰好 12000ms 截断；混合档（3000+3000+2500+1500=10000，第 5 条再起拨）⇒ **13000ms**；理论最坏 `12000 + 3000 = 15000ms`。⇒ 单轮上界不是 12s 而是「12s + 一个候选预算」。
- 重连梯把上界放大：`ReconnectMaxAttempts = 20`（:1324）、延迟曲线 0/1/2/4/8/16/30…（:1330-1332）⇒ `ladderDrainUpperBoundMs`（:1339-1340）= `Σ延迟(451s) + 20×(轮预算 12s) ≈ 691s ≈ 11.5min`，其间**每轮都重付一次 4.5s**。
- 一个黑洞候选拖慢整轮多久：**串行时就是它自己的预算（3000ms），且每轮都付**；预算只在 `Truncated` 时把队尾推到下一拍（:462-463），不会豁免首位。

**为什么不该加 HTTP 预探**：`P2pReachability.httpReachable`（:144-164）的实测教训是「裸 TCP connect 在本机 TUN/透明代理下是假阳性机器」，只能用应用层 GET（:137-143），成本 1200ms；而 presence 拨号本身已是应用层（WS 升级），单候选预算 3000ms 里已包含建连+升级。先探再拨 = 同一事实付两遍（1200 + 3000）。**并发的首胜拨号本身就是最优探测器**——这正是 76417e90 的选择。

## Q4 有没有「负证据 TTL」

**有，两套，互不可见**：

**(a) presence 腿 `EvictionPolicy`（`NeblinkPresenceService.scala:162-169`）**
- `refusalStrikes = 3`（主动拒绝/无路由/域名不可解析，≈3 拍 2.25min）
- `timeoutStrikes = 5`（超时=歧义信号，≈5 拍 3.75min）
- `suppressedTtlMs = 300_000`（5min 定窗，无退避曲线）
- 惩罚/恢复曲线：失败计数按失败类**分轴**且要求连续（换类清零，:793-796）；到阈值 ⇒ 写 `suppressedUntilMs = now+5min` 并 `WARN` 一行（:800-816）；`Other` 类永不剔除（:796）；**全部候选都在窗内时不空转**：只半开探测首位（:411-414），失败**不改账本**（`probeOnly`，:776）。
- 恢复四条：TTL 到期（`isSuppressed`:759-761）/ 名册候选集变化（:834-842）/ 梯子收手（`reconnectGaveUp`:1106-1113）/ 任一候选拨通（:820-821）。
- **缺口**：① 只在**本腿**内生效，逐端点、不跨腿；② 要 5 次**连续**超时才置窗 —— 阶梯期间 20 轮极快可攒够，但同步拍形态下要 3.75min；③ 5min 定窗、无指数退避；④窗内仍每拍付 1×budget。

**(b) 文件腿 `P2pReachability` / `P2pProbePolicy`（`core/tools/P2pReachability.scala:41-231`）**
- `NegativeTtlBaseMs = 60s`（:44）、指数翻倍封顶 `15min`（:47, `negativeWindowMs`:63-67）、探测预算 1200ms（:54）、按 `addressKey` 记账（地址集变即失效，`RemoteExecutor.scala:90-92`）。
- 只被 `RemoteExecutor` 消费；presence 腿**看不见**它（`NeblinkPresenceService` 无任何引用）。

⇒ 结论：main 上「负证据 TTL」存在但**分腿各一套、阈值宽松、无退避**；跨腿合一由 76417e90 P3 完成（`reachLedger: Option[P2pReachability]`，GatewayMain 装配 `Some(P2pReachability.current)`）。

## Q5 发现逻辑的具体缺陷（可被运行事实证实/证伪的断言）

- **A1（C-1，主因）**：采集任一对等体的 `neblinkService.peers` 快照 —— 预期出现 `endpoints.size` 由 `N ≥ 2` 掉到 `0`（`address` 同时变成入站来源 IP），且每次掉点都紧跟在一次**入站** presence/announce 之后，并在 ≤1 拍（45s）被心跳名册复原。判据：`NeblinkService.scala:392/449/337` 整记录替换 + `PresenceRoutes.scala:535-541` 构造 `endpoints = Nil`。同时可观测 `RemoteExecutor.addressKey` 抖动（`RemoteExecutor.scala:90-92`）。
- **A2（C-3）**：在 KAI（Windows）上读心跳体 `endpoints[]`（`NeblinkClient.scala:518-523`）或直接跑 `detectLocalEndpoints` —— 预期至少 1 条 `169.254.*`，或名字含 `TAP/Tunnel/ZeroTier/Hamachi` 的适配器地址；若全部命中真实物理网卡则**证伪**本条。
- **A3（C-2）**：单 peer 单轮读数（`PresenceDialRound` / `dialMs` / `Presence dial failed ... via http://100.x` 与 `dial target host:port` 两行的时间差）—— 预期首位 `100.64/10` 不可达时每轮固定多付 ≈3000ms；若首位直接是 LAN 且一轮 ≤100ms ⇒ 该场景不成立（但缺陷仍在代码路径上）。
- **A4（C-4）**：`openConnection` 的 `HttpClient.newBuilder()`（:525-528）**未设** `connectTimeout`，且每次拨号新建实例 —— 判据 = 源码事实（无需运行）；运行面读数为「预算到点后仍可见该候选的 SYN/selector 线程」。
- **A5（端口证伪，已成立）**：`serverPort` 唯一来源 `GatewayMain.scala:1319/1355-1357` 的 `cfg.port.value`（缺省 `Defaults.GatewayPort = 6138`，`shared/Defaults.scala:1933`）——远端拨来的端口与本地监听端口**同一个数**；拨号侧 `resolveDialTarget`（:1156-1160）候选自带端口优先、缺端口才回落 `serverPort`；relay 走 `wss://<server>/api/device/relay-ws?deviceId=`（`NeblinkRelayTunnel.scala:1385-1393`，服务端 443），**永不进入候选面**。⇒ 端口混用不是断因。残留面只有：入站记录 `port` 缺省取 `brandDefaultPort`（`PresenceRoutes.scala:520-523`、`NeblinkRoutes.scala:104`）——只影响不带 `port` 参数的旧拨号方。

## 附：一个已存在的正例（规格不该重造的）

`core/tools/P2pReachability.scala`（「砍探测税」批 2026-11-04 10:43）已经把这套模式落地在**文件腿**：并发探测首胜（:111-131）、负缓存指数退避（:63-67）、以及「裸 TCP connect 不可用作探针」的实测结论（:137-143）。presence 腿的对应实现（76417e90 P1/P2/P3）就是在复用同一份账本 —— 因此**新规格不得另写第二套探测/负缓存**，只补它没覆盖的 C-1 / C-3 / C-4。

## 影响面清单（既有 spec，已逐个核对）

| spec | 是否被新改动打破 | 理由 |
|---|---|---|
| `EndpointPreferenceSpec.scala` | 否 | 不改 `rank`/`order`（排序决策见 fix-spec §4） |
| `NeblinkPresenceServiceSpec.scala` | 否 | 不碰 `candidatesOf` / `resolveDialTarget` / 端口回落 |
| `NeblinkPresenceDialBudgetSpec.scala` | 否 | 不碰 `DialBudget` / `EvictionPolicy` |
| `NeblinkPresenceConvergenceSpec.scala` | 否 | 不改 `toNeblinkPeers`；`handleAnnounce` 的 self 过滤仍在（`:380`） |
| `NeblinkHeartbeatUiPortSpec.scala` | 否 | 只断言 `endpoints` 键存在（:108），键仍在 |
| `NeblinkPresenceHeartbeatLifecycleSpec.scala` | 否 | 不碰心跳/退役面 |
| 76417e90 新增 `NeblinkPresenceDialConcurrencySpec` / `NeblinkPresenceDialBudgetSpec(+45)` | 否（需跑一遍确认） | 共享 `HttpClient` 对并发拨号透明；不冻结拨号线程口径（仍走下他们的 `PresenceDialPool`） |
