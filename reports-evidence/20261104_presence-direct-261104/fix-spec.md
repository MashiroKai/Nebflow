# fix-spec：presence 候选发现面最小改动（供 p3-fix 直接照写）

依据：同目录 `audit.md`（缺陷编号 C-1/C-3/C-4 与 Q1-Q5 证据）。本规格只补 76417e90 **未覆盖**的面；
`audit.md` 的 C-2（并发首胜 / 专用池 / 共用负缓存）由 76417e90 交付，**禁止重造第二套**。

## 1. 文件放置（硬性）

主逻辑**全部**放新文件：

```
src/main/scala/nebflow/neblink/PresenceCandidateProbe.scala
```

命名沿用规定名（不改名）：它是「候选端点卫生 / 保真 / 拨号客户端」的单一去处。
新文件**不含**任何 `IO` 副作用、不引入新依赖、不 import 76417e90 的新类型（`PresenceDialPool` / `PresenceDialSchedule` / `DialRoundOutcome`），
否则合并时会连带编译失败。只依赖：`nebflow.shared.PeerInfo`、`java.net.http.HttpClient`、`java.time.Duration`。

## 2. 新文件公开签名（可直接粘贴的骨架，实现体留给 p3）

```scala
package nebflow.neblink

import nebflow.shared.PeerInfo
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * presence 候选端点面（候选发现卫生 / 候选集保真 / 拨号客户端）——纯逻辑 + 一个共享 HttpClient。
 * 见 evidence/presence-direct-261104/audit/{audit,fix-spec}.md。
 */
object PresenceCandidateProbe:

  /** 上报候选条数上限：与 76417e90 的 `PresenceDialSchedule.maxConcurrentPerRound`(4) 对齐，一轮即拨完。 */
  val MaxReportedEndpoints: Int = 4

  /** 共享拨号客户端的 TCP 建连上界（= 最贵档 Tailnet 3000ms，NeblinkPresenceService.scala:73）。 */
  val MaxConnectTimeoutMs: Long = 3_000L

  /** 一条自报地址是否不该上报（链路本地/未指定/组播/广播）。纯函数，spec 直测。 */
  def isUnreportableAddress(address: String): Boolean

  /** 网卡名是否「已知隧道/虚拟适配器」但旧 marker 表漏掉（tap / tun / utun / zerotier / hamachi / npcap / vpn / wireguard）。纯函数。 */
  def isTunnelNicLabel(label: String): Boolean

  /**
   * ① 上报面卫生：丢不可路由地址 + 丢隧道网卡地址（仅当该批里还有 tailnet/非隧道地址存活时）+ 去重 + 封顶，
   * 保序；过滤后为空 ⇒ 原样返回入参（沿用 NeblinkClient.scala:295 的 "never lose all endpoints" 不变量）。
   */
  def sanitizeReported(
    endpoints: List[NeblinkEndpoint],
    max: Int = MaxReportedEndpoints
  ): List[NeblinkEndpoint]

  /**
   * ② 候选集保真：入站观察到的记录（endpoints = Nil）不得抹掉名册给的全量候选集。
   * 结果 = observed.copy(endpoints = (observed.address :: prev.endpoints).filter(_.nonEmpty).distinct.take(max))
   * prev = None ⇒ observed.copy(endpoints = List(observed.address))
   * observed.endpoints 非空 ⇒ 以 observed 为准（名册腿，零行为变化）
   * 纯函数（无 IO、无锁），只在调用方的 peersRef.modify 闭包里用。
   */
  def mergeObserved(prev: Option[PeerInfo], observed: PeerInfo, max: Int = MaxReportedEndpoints): PeerInfo

  /** 兜底臂（仅 76417e90 被回滚时用）：只读共用负缓存的剩余窗口，不拨任何东西。 */
  def reachHintMs(deviceId: String, addressKey: String, nowMs: Long): Option[Long]

  /**
   * ③ 共享拨号客户端：单例 + 显式 connectTimeout，其余语义与现状逐字一致
   * （proxy(null) 绕代理；不设 .version(...) ⇒ WS 升级面零行为变更）。
   */
  def dialClient(connectTimeoutMs: Long = MaxConnectTimeoutMs): HttpClient
```

实现要点（三条硬约束）：
1. `sanitizeReported` **保序**：只删不重排（排序权仍在 `EndpointPreference`，见 §4③）。
2. `mergeObserved` **只增不减**：`observed.address` 必须留在候选表首位（「此刻观察到的活地址」），`prev.endpoints` 去重后追加。
3. `dialClient` 用 `java.util.concurrent.atomic.AtomicReference` 缓存实例（spec 用 `eq` 钉「两次调用同一实例」）；除 `connectTimeout` 外**不加**任何与现状不同的选项。

## 3. 调用点（5 处，每处 1-3 行）

### M1 — 上报面卫生（修 C-3）
`src/main/scala/nebflow/neblink/NeblinkClient.scala:293-296`

改前：
```scala
      val physical = allNics.filterNot(isLikelyVirtualNic)
      val preferred = toEndpoints(physical)
      val chosen = if preferred.nonEmpty then preferred else toEndpoints(allNics) // never lose all endpoints
      cachedLocalPrefixes = EndpointPreference.localPrefixesOf(chosen.map(_.address))
```
改后：
```scala
      val physical = allNics.filterNot(isLikelyVirtualNic)
      val preferred = toEndpoints(physical)
      val raw = if preferred.nonEmpty then preferred else toEndpoints(allNics) // never lose all endpoints
      val chosen = PresenceCandidateProbe.sanitizeReported(raw)               // ← 唯一新增行
      cachedLocalPrefixes = EndpointPreference.localPrefixesOf(chosen.map(_.address))
```
（`cachedLocalPrefixes` 因而已在**过滤后**的地址上计算 —— 这是对的：/24 判据必须与本机真正上报的地址一致。）

### M2 — 候选集保真（修 C-1，三处一行替换）
`src/main/scala/nebflow/neblink/NeblinkService.scala`

(a) `:392`（handleAnnounce）：
```scala
          _ <- peersRef.update(ps => ps + (info.deviceId -> PresenceCandidateProbe.mergeObserved(ps.get(info.deviceId), finalPeer)))
```
(b) `:449`（handleHandshake）：
```scala
      _ <- peersRef.update(ps => ps + (deviceId -> PresenceCandidateProbe.mergeObserved(ps.get(deviceId), finalPeer)))
```
(c) `upsertPeer` 的 `modify` 闭包（`:333-338`，覆盖 `gateway/PresenceRoutes.scala:542` 的入站 presence 路径）：
```scala
      newlyOnline <- peersRef.modify { peers =>
        val now = System.currentTimeMillis()
        val kept = PresenceCandidateProbe.mergeObserved(peers.get(peer.deviceId), finalPeer)   // ← 新增
        val wasOnline = peers.get(peer.deviceId).exists(p => NeblinkService.isPeerOnline(p, now, syncSec))
        val isOnline  = NeblinkService.isPeerOnline(kept, now, syncSec)                        // ← finalPeer → kept
        (peers + (peer.deviceId -> kept), prevIsNew || (isOnline && !wasOnline))               // ← finalPeer → kept
      }
```

### M3 — 共享拨号客户端（修 C-4）
`src/main/scala/nebflow/neblink/NeblinkPresenceService.scala:525-528`

改前：
```scala
      val client = HttpClient
        .newBuilder()
        .proxy(java.net.ProxySelector.of(null)) // bypass HTTP proxy for P2P
        .build()
```
改后：
```scala
      // 共享单例 + 显式 connectTimeout（黑洞候选的 TCP 建连不再无限在飞）
      val client = PresenceCandidateProbe.dialClient()
```

### 为何这些调用点在 76417e90 合并时可 3 行内消解
- **M1**：76417e90 只改 `NeblinkPresenceService.scala` / `gateway/GatewayMain.scala` 并新增 `PresenceDialPool.scala` 与两个 spec ⇒ `NeblinkClient.scala` **零冲突**。
- **M2**：76417e90 不碰 `NeblinkService.scala` ⇒ **零冲突**（且新文件不 import 他们的类型，见 §1）。
- **M3**：他们改的是 `openConnection` 的**签名行**（hunk `@@ -513,7 +870,8 @@`，新增 `publishGate` 形参）与 `earlyClose` 之后**新加的分支**（hunk `@@ -586,6 +944,12 @@`）；`:525-528` 三行落在其上下文里但**内容未被改动** ⇒ 三方合并自动通过。万一被判冲突：保留他们的两处、把 `val client = PresenceCandidateProbe.dialClient()` 一行重贴回去 —— **3 行内**手工消解，之后无需再动他们的拨号结构（`dialOne` 仍走 `dialBlocking` → `openConnection`）。

## 4. 规格内容 ①②③④

### ① 可达性预探 —— **不新增**，验收既有实现
`audit.md` Q3 的算术：先探再拨 = 同一事实付两遍（`httpReachable` 1200ms + 拨号 3000ms），而**并发首胜拨号本身就是最优探测器**（与 `core/tools/P2pReachability.scala:111-131` 同构）。
- 生产路径：76417e90 的 `dialCandidatesConcurrent`（首胜即放行、其余 `LostRace` 自退）。**只验收**（AC-4），不写新代码。
- 若 76417e90 被回滚（兜底，p3 仅在确认回滚后启用）：**不要**在新文件里自写探测循环，改为在 `NeblinkPresenceService.scala:411` 一行复用现成账本：
  ```scala
  val live = candidates.filterNot(ep => isSuppressed(peer.deviceId, ep, startedAtMs))   // 既有
  PresenceCandidateProbe.reachHintMs(peer.deviceId, …, startedAtMs)                     // ← 兜底一行
  ```
  `reachHintMs` 只读共用负缓存剩余窗口（内部 `P2pReachability.current.negativeRemainingMs`），不拨任何东西。

### ② 负证据 TTL —— 复用 76417e90 的共用账本；本文件只钉「常数与恢复曲线」的判据源
不新建第二套。定版数值全部沿用 `core/tools/P2pReachability.scala:44-67` 的既有口径，**不另立常数**：

| 项 | 值 | 理由 |
|---|---|---|
| 负证据首窗 | 60 000ms（`NegativeTtlBaseMs`） | 与文件腿同源；偶发抖动 1-2 次即恢复重探 |
| 退避 | 每次连续失败翻倍，封顶 15min（`negativeWindowMs`） | 长期不可达（tailscale 未起 / 跨网段）⇒ 每小时至多 4 次探测 |
| 恢复 | 任一成功即清账；地址集变即失效（`addressKey`）；显式 `invalidate` | 三条与 `P2pReachability` 逐字一致 |
| presence 腿本地 `EvictionPolicy`（3 拒绝 / 5 超时 / 5min） | **保留不动** | 它是「按失败类分档」的强/弱信号判据，与跨腿负缓存互补，不是重复 |

⇒ p3 的工作只有一项：**确认 `GatewayMain` 装配了 `reachLedger = Some(P2pReachability.current)`**（76417e90 的 `GatewayMain.scala` +11 行内），并在验收里加读数断言 AC-5。

### ③ 候选排序优先级 —— **据证据判定：不改**
证据给出的更优顺序就是现状顺序（tailnet → 同网段 LAN → 其余），理由三条：

1. 76417e90 的并发首胜把「首位成本」压成 **0**（一轮 ≈ `max(单候选预算)`，不是 Σ）⇒ 换序买不到延迟收益；
2. `EndpointPreference.rank` 是**跨腿共用纯函数**（`core/tools/RemoteExecutor.scala:1376-1381` 也用它），改序连带 P2P 下发腿与 `EndpointPreferenceSpec.scala:68-92`，风险 ≥ 收益；
3. 反向证据（`NeblinkModel.scala:632-637`）：2026-09-11 事故里被黑洞的**正是 LAN**、可通的是 tailnet ⇒「LAN 优先」等于把当年的断因写进排序。

唯一细化（并入 M1，不新增函数）：同网段 `/24` 判据在 **sanitize 之后**的地址集上计算（M1 已隐含），避免「本机 /24 来自一条不该上报的隧道网卡」把无关 LAN 候选误提升到档 1。

### ④ 可回滚 / 不降低失败回落
- `sanitizeReported` 过滤后为空 ⇒ **原样返回入参**（沿用 `NeblinkClient.scala:295` 不变量）；
- `mergeObserved` **只增不减**，`observed.address` 恒在首位 ⇒ 任何时刻候选数 ≥ 1（等价或优于现状「`endpoints` 空则回落 `address`」）；
- `dialClient` 只加 `connectTimeout` 与单例，`proxy(null)` 保留，不动 `.version`；
- **relay 兜底零触碰**：不改 `NeblinkRelayTunnel*`、不改 `RemoteExecutor.sendDataOrRelay` / `shouldSkipP2pForRelay`（`RemoteExecutor.scala:77-82`）、不改 `DialBudget`；
- 回滚方式：删新文件 + 撤回 §3 的 5 处一行改动，无残留状态（新文件不注册任何全局状态）。

## 5. 既有 spec 影响 + 新增 spec

| 既有 spec | 影响 | 处理 |
|---|---|---|
| `EndpointPreferenceSpec.scala` | 无（不改 rank/order） | 保持绿 |
| `NeblinkPresenceServiceSpec.scala` | 无（不碰 `candidatesOf` / `resolveDialTarget`） | 保持绿 |
| `NeblinkPresenceDialBudgetSpec.scala` | 无（不碰 `DialBudget` / `EvictionPolicy`） | 保持绿 |
| `NeblinkPresenceConvergenceSpec.scala` | 无（`handleAnnounce` self 过滤仍在；无 spec 断言 `endpoints == Nil`） | 保持绿 |
| `NeblinkHeartbeatUiPortSpec.scala` | 无（只断言 `endpoints` 键存在，:108） | 保持绿 |
| 76417e90 的 `NeblinkPresenceDialConcurrencySpec` / `NeblinkPresenceDialBudgetSpec(+45)` | 需实跑确认（共享 HttpClient 对并发透明；不冻结拨号线程口径） | 合并后跑一遍 |

新增 spec（**新文件**，避开他们已占用的两个文件名）：

1. `src/test/scala/nebflow/neblink/PresenceCandidateProbeSpec.scala`（纯函数，零网络）
   - `sanitizeReported`：混合夹具 ⇒ 无 `169.254.*`、无隧道网卡的非 tailnet 地址、`size ≤ 4`、**保序**；全污染夹具 ⇒ 返回原样（≥1 条）；
   - `mergeObserved`：`prev` 有 3 条 + `observed.endpoints = Nil` ⇒ 4 条且首位 = `observed.address`；`prev = None` ⇒ 1 条；`observed.endpoints` 非空 ⇒ 原样（名册腿零变化）；
   - `dialClient`：两次调用 `.eq` 为 true；参数生效（`dialClient(1200)` 与默认值不同实例或不同 timeout 可观测）；
   - `isUnreportableAddress` / `isTunnelNicLabel` 正反例各 3 条（含 `100.64.0.1` **不得**被判不可上报）。
2. `src/test/scala/nebflow/neblink/PeerInfoEndpointFidelitySpec.scala`（内存态，无网络）
   - 名册 upsert(N 条) → `handleAnnounce`（同 deviceId）→ 断言 `peers(id).endpoints.size == min(N+1, 4)` 且含原候选（改前必为 0）；
   - 名册 upsert → `upsertPeer`（入站 presence 形态，`endpoints = Nil`）→ 同上；
   - `RemoteExecutor.addressKey` 在三步之间**不抖动**（同一 key）。

## 6. 验收条件（二值可判定）

- **AC-1（冒烟红线）**：`sbt "Test/compile"` 通过；`PresenceCandidateProbeSpec` + `PeerInfoEndpointFidelitySpec` 全绿。
- **AC-2（回归）**：§5 表内既有 spec 全绿（`NeblinkPresenceServiceSpec` / `NeblinkPresenceDialBudgetSpec` / `EndpointPreferenceSpec` / `NeblinkPresenceConvergenceSpec` / `NeblinkHeartbeatUiPortSpec`）。
- **AC-3（保真）**：`PeerInfoEndpointFidelitySpec` 的「名册 → 入站覆盖」用例：改前红（`endpoints == Nil`）、改后绿（`size ≥ N`）。
- **AC-4（无重复探测）**：`grep -rn "probeConcurrently\|new Socket" src/main/scala/nebflow/neblink/` 无新增命中；`PresenceCandidateProbe.scala` 内**不含**任何 socket/HTTP 探测循环。
- **AC-5（负缓存装配读数）**：合并 76417e90 后 `grep -n "reachLedger = Some" src/main/scala/nebflow/gateway/GatewayMain.scala` 有且仅有 1 处（存在即通过；不存在 ⇒ 记 open item，p3 不得擅自改他们的文件）。
- **AC-6（回滚安全）**：`git diff --stat` 只含 §3 的 5 处（≤12 行）+ 新文件 + 2 个新 spec；`git diff -- src/main/scala/nebflow/neblink/NeblinkRelayTunnel.scala src/main/scala/nebflow/core/tools/` 为空。
- **AC-7（红线）**：不改 `DialBudget` / `EvictionPolicy` 默认值；不改 `EndpointPreference.rank`；不新增第二套负缓存。
