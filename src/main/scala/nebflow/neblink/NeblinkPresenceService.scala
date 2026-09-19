package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Outcome of the most recent presence dial attempt against a peer (C3,
 * 2026-09-11 P2P 直连修复批).
 *
 * Unlike `connections` (which only exists while a dial SUCCEEDS), this record
 * exists for failures too — that is the whole point: before this批 a failed
 * dial only produced a `logger.debug` line, which `root level=INFO` filtered
 * away, so `directOnline=false` had **zero** attributable留痕 (方案 §1.1 环③ /
 * U-1). `endpoint` is the candidate actually dialed (C1 择优: the winner on
 * success, the last tried candidate on failure) and `error = None` marks a
 * successful dial — so consumers can tell "dialed and succeeded" from "dialed
 * and failed (with reason)" from "never dialed" (`Option[Nothing]`).
 */
final case class PresenceDialStatus(endpoint: String, error: Option[String], atMs: Long)

/**
 * Shape of the most recent dial ROUND for a peer (F-D, 2026-09-17 presence 拨号
 * 抖动批). `PresenceDialStatus` answers "what did the last single attempt do";
 * this record answers "what did the round do": which candidates were actually
 * dialed, which were skipped because they were judged dead, and whether the
 * round budget cut the walk short. It is the reading surface for the F-D
 * acceptance criteria (剔除可证 / 不误剔除 / 单拍上界) and is purely additive —
 * `dialStatus` keeps its C3 semantics and wire shape untouched.
 */
final case class PresenceDialRound(
  /** Candidates actually dialed this round, in preference order. */
  attempted: List[String],
  /** Candidates skipped as dead (F-D 死端点剔除) — not dialed this round. */
  skippedSuppressed: List[String],
  /** The round budget cut the walk short; the untried tail waits for the next beat. */
  truncated: Boolean,
  /**
   * Per-candidate failure class of this round, in dial order: `(endpoint, class)`.
   *
   * WHY 单列一类而不是只看 `dialStatus.error` 文本：剔除判据是**按失败类**分档的
   * （强信号 vs 歧义信号），而平台差异会把文本抹平（实测 macOS + JDK 23 的
   * `ConnectException.getMessage == null` ⇒ `error` 只能退化成类名）。把分类本身
   * 暴露出来，判据才能断言「判据」而不是断言某个平台的措辞。
   */
  failures: List[(String, DialFailureClass)],
  atMs: Long
)

/**
 * Address class of a candidate endpoint (F-D) — the input of the per-candidate
 * dial budget.
 *
 * WHY它决定预算：一次存在面拨号 = TCP 建连 + HTTP upgrade + 对端 presence 路由，
 * 量级是「2~3 × RTT」；三类地址的 RTT 量级与波动性差一个数量级，所以单一常量
 * 必然要么误杀慢候选、要么在黑洞地址上白等（见 [[DialBudget]] 的数值依据）。
 */
enum DialAddressClass(val label: String, val budgetMs: Long):
  /** `100.64.0.0/10`（Tailscale / CGNAT）：跨网络，可能经 DERP 中继 ⇒ RTT 大且抖。 */
  case Tailnet extends DialAddressClass("tailnet", 3_000L)
  /** RFC1918 / 环回 / 链路本地：同链路，RTT 在毫秒级。 */
  case Lan extends DialAddressClass("lan", 1_500L)
  /** 公网 IPv4 或主机名：路径类别未知（≥1 个 WAN RTT，无中继不确定性）。 */
  case Other extends DialAddressClass("other", 2_500L)

/**
 * 拨号预算策略（F-D ①）：地址类别 → 单候选预算，外加**单轮总预算**。
 *
 * 数值依据（与 `20260917_devosc-diag.md` §7-F-D 的现状读数对照）：
 *
 *  - **Tailnet = 3s**（改前 1.5s）。诊断报告给的现状是「单候选 1500ms，余量
 *    ≈2.7×RTT」⇒ 实测 RTT ≈ 555ms；握手量级 2~3×RTT ≈ 1.1~1.7s ⇒ 1.5s 卡在
 *    临界点上：一个**活着**的中继候选会被判成死（本次抖动的直接形态）。3s 相对
 *    555ms = **5.4×RTT**（相对历史读数 272ms = 11.0×），覆盖 2~3×RTT 握手后仍有
 *    约 2× 余量。作者 2026-09-17 放行口径给的示例值也正是「tailnet 候选 3s」。
 *  - **Lan = 1.5s（维持现状）**。同链路 RTT 毫秒级 ⇒ 1.5s 已是手数量级的余量；
 *    更重要的是事故里被黑洞的正是 LAN 候选（默认路由外送、永不回包），放宽只会
 *    把「白等」拉长一倍，故不动。
 *  - **Other = 2.5s**。类别未知（公网直连 / 主机名）⇒ 至少 1 个 WAN RTT，但无
 *    DERP 中继的额外不确定性 ⇒ 取 tailnet 与 lan 之间。
 *  - **roundBudgetMs = 12s**：单 peer 单拍拨号时长上界（Σ 候选预算封顶）。它必须
 *    < 同步拍（[[NeblinkPresenceService.SyncBeatFloorMs]] = 45s），否则一个 peer 的
 *    拨号腿会跨到下一拍 ⇒ 与 `syncPeers` 的拍子重叠（判据④）。12s 覆盖 4 个
 *    tailnet 候选（4×3s），超出的候选**不丢**：本轮截断，下一拍继续（`truncated` 留痕）。
 *
 * `classify` 是参数而非硬编码，是为了让策略**纯且可注入环境口径**（同
 * `EndpointPreference.rank(_, localPrefixes)` 的缝：分类只决定候选拿哪一档预算；
 * 真正的拨号仍走唯一的 `openConnection` 路径）。
 */
final case class DialBudget(
  classify: String => DialAddressClass = DialBudget.defaultClassify,
  roundBudgetMs: Long = 12_000L
):
  /** 单候选预算（按 endpoint 的主机类别）。 */
  def budgetMsFor(endpoint: String): Long = classify(EndpointPreference.hostOf(endpoint)).budgetMs

  /** 一轮拨号的时长上界：Σ 候选预算，受 `roundBudgetMs` 封顶。 */
  def roundUpperBoundMs(candidates: List[String]): Long =
    math.min(roundBudgetMs, candidates.map(budgetMsFor).sum)

object DialBudget:

  /**
   * 默认分类：纯区间判定（**不吃环境输入** ⇒ 判据可确定性复现）。
   *
   * 不按「本机同 /24」细分的原因：NebLink 名册里的 LAN 候选实际都是 RFC1918，
   * 而「公网的 /24」不是观测到的形态；真出现时落到 `Other`（同样是放宽档）。
   */
  def defaultClassify(host: String): DialAddressClass =
    if EndpointPreference.isTailscaleHost(host) then DialAddressClass.Tailnet
    else if isPrivateOrLoopbackHost(host) then DialAddressClass.Lan
    else DialAddressClass.Other

  /** RFC1918 / 环回 / 链路本地（IPv4 字面量；主机名与 IPv6 走 `Other`）。 */
  def isPrivateOrLoopbackHost(host: String): Boolean =
    octets(host).exists { o =>
      o(0) == 10 || o(0) == 127 || (o(0) == 192 && o(1) == 168) ||
      (o(0) == 172 && o(1) >= 16 && o(1) <= 31) || (o(0) == 169 && o(1) == 254)
    }

  private def octets(host: String): Option[Vector[Int]] =
    val parts = host.split('.')
    if parts.length != 4 then None
    else
      val nums = parts.toVector.map(_.toIntOption.filter(n => n >= 0 && n <= 255))
      if nums.forall(_.isDefined) then Some(nums.map(_.get)) else None

  /** 生产口径（本批默认值；注入仅供环境校准/判据复现）。 */
  val Default: DialBudget = DialBudget()

/**
 * 死端点剔除策略（F-D ②）。
 *
 * 判据按**失败类**分档（[[DialFailureClass]]）：「主动拒绝 / 无路由 / 域名不可解析」
 * 是端点确定不服务（强信号），「预算耗尽」是歧义信号（黑洞、DERP 中继拥塞、睡眠
 * 唤醒、对端忙）——后者单拍甚至连续几拍全超时**都不得判死**（判据③的口径），
 * 所以它的门槛更高。
 *
 * 恢复路径（🔴 禁永久剔除）：① `suppressedTtlMs` 到期自动复位并清零计数（有限期）；
 * ② 服务端名册里该 peer 的候选集合变化 ⇒ 复位（设备换网/换地址后旧判定失效）；
 * ③ 重连梯收手交回同步拍时复位（见 `reconnectGaveUp`）；④ 任一候选拨通即清除该
 * 候选的判定。全部候选都在抑制窗内时**仍每拍半开探测最优候选**（每拍成本上界
 * = 1×budget），绝不出现「零拨号」。
 */
final case class EvictionPolicy(
  /** 连续「主动拒绝」类失败 ⇒ 剔除（3 拍 ≈2.25min @45s 拍，容忍对端 presence 重启窗）。 */
  refusalStrikes: Int = 3,
  /** 连续「超时」类失败 ⇒ 剔除（5 拍 ≈3.75min，歧义信号给足机会）。 */
  timeoutStrikes: Int = 5,
  /** 剔除后的存活期（有限 ⇒ 到期自动复位）。 */
  suppressedTtlMs: Long = 300_000L
)

object EvictionPolicy:
  val Default: EvictionPolicy = EvictionPolicy()

/** 单次拨号失败的类别（F-D 剔除判据的输入；与 C3 的原因字符串一起返回）。 */
enum DialFailureClass(val label: String):
  /** 地址主动拒绝 / 无路由 / 域名不可解析 —— 端点确定不服务（强信号）。 */
  case Refused extends DialFailureClass("refused")
  /** 预算耗尽 —— 歧义信号（黑洞 / 中继拥塞 / 睡眠唤醒），单轮全超时绝不判死。 */
  case Timeout extends DialFailureClass("timeout")
  /** 其它（握手层/本地异常）。 */
  case Other extends DialFailureClass("other")

/** 一次失败拨号：C3 的可读原因 + F-D 的失败类别。 */
private[neblink] final case class DialFailure(reason: String, cls: DialFailureClass)

/**
 * Manages outgoing WebSocket presence connections to NebLink peers.
 *
 * When device A discovers device B via the NebLink Server, A opens a persistent
 * WebSocket to `ws://B:<B's advertised port>/api/neblink/presence` (F-1: the
 * candidate endpoint's own port — the local `serverPort` is only a fallback for
 * candidates that carry none). As long as the WS is open, both
 * devices consider each other online.
 *
 * Heartbeat: ping every [[NeblinkPresenceService.HeartbeatIntervalSec]] (5s); if no pong for
 * [[NeblinkPresenceService.HeartbeatTimeoutMs]] (10s) the connection is retired.
 *
 * 连接生命周期（hblife 批 2026-09-19 · presence 心跳自振缺陷修复）：
 *  - **一条连接 = 一条心跳线程**，且两者**同生同灭**：唯一的退役例程 [[retireConnection]]
 *    负责 `alive=false` + `heartbeat.shutdownNow()` + compare-and-remove + 关自己的 socket；
 *  - **任何**换连接/关连接的路径都必须走它（发布顶替 / onClosed / disconnectPeer /
 *    disconnectAll / 心跳僵尸分支）⇒ 不变量：任一颗刻「存活心跳线程数 ≤ `connections` 条数」，
 *    且**没有**任何线程的宿主 conn 已不在 `connections` 里；
 *  - 心跳拍**闭包捕获自己的 conn 句柄**，判活只用本 conn 的 `alive`/`lastPong`，
 *    且僵尸分支必须先做 compare-and-remove：摘到**自己的**那条才允许「掉线 + 重连」，
 *    摘不到（已被退役/顶替）⇒ 该拍**静默退出**，绝不替新连接触发 removePeer/重连。
 *  机制依据：`.nebflow/reports/20260919_devflip-forensic.md` §3.4（12.0 次/分 vs 单连接
 *  理论 3.70 次/分 ⇒ 现场有并发陈旧心跳线程打同一 peer）。
 *
 * Auto-reconnect: when a connection drops unexpectedly (TCP RST, heartbeat
 * timeout, sleep/wake), an exponential-backoff loop immediately starts trying
 * to reconnect: 0s (immediate) -> 1s -> 2s -> 4s -> 8s -> 16s -> 30s (capped,
 * see [[NeblinkPresenceService.reconnectDelayMs]]). This ensures sub-second
 * recovery when the network recovers, instead of waiting up to one sync beat
 * (45s) for the next periodic scan.
 *
 * F-D (2026-09-17 presence 拨号抖动批) 对本梯子的两条口径：
 *  - 延迟封顶 `ReconnectDelayCapMs`(30s) **≤ 同步拍**(45s) ⇒ 梯子永不比拍子慢，
 *    交回同步拍时最多再等 1 拍（判据④的「不跨拍」按此成立）；
 *  - 退避梯的**时长上界**由 `reconnectDelayMs` + 每轮拨号预算算出
 *    （[[NeblinkPresenceService.ladderDrainUpperBoundMs]]）并写进收手日志——
 *    原注释「~5 min total」已失真（延迟 Σ=451s 已超此数，未计拨号时长）。
 * 梯子在 `ReconnectMaxAttempts` 次后收手并**复位该设备的死端点判定**（恢复路径③），
 * 或在该 peer 被显式断开（离开网络 / 登出）时取消。
 */
final class NeblinkPresenceService(
  neblinkService: NeblinkService,
  serverPort: Int,
  dialBudget: DialBudget = DialBudget.Default,
  eviction: EvictionPolicy = EvictionPolicy.Default
)(dispatcher: Dispatcher[IO]):
  private val logger = NebflowLogger.forName("nebflow.neblink.presence")

  /**
   * 一条**自持**的 presence 连接：socket + 它的存活标记 / 最后 pong 时刻 / 心跳线程 / 代际。
   *
   * 🔴 `deviceId` 与 `gen` 是「身份」两件套：`deviceId` 让退役例程只按**本 conn** 的键做
   * compare-and-remove，`gen` 是单调代际（用于日志与读出面：能一眼分辨「这条拍子/这条
   * 事件属于哪一代连接」）。改前这张表只按 `deviceId` 反查 ⇒ 陈旧线程摘掉的是**别人的**
   * conn（别名错配家族）。
   */
  private[neblink] case class PresenceConnection(
    deviceId: String,
    gen: Long,
    ws: WebSocket,
    alive: AtomicBoolean,
    lastPong: AtomicLong,
    heartbeat: ScheduledExecutorService
  )

  /**
   * 一次拨号的**连接槽**：WS 监听器在 `buildAsync` 之前就要拿到，而 conn 只在握手返回后
   * 才存在 ⇒ 用这个槽把「事件所属的那条 conn」交接给监听器（监听器带上它，关闭事件就
   * **不可能**被误算到同 deviceId 的另一条 conn 头上）。
   *
   * `earlyClose` = 握手期（发布之前）监听器就收到了 close/error：该次拨号**不得发布**，
   * 否则会留下一条「上线即死」的在册 conn，并在 ~15s 后制造一次伪掉线 + 重连。
   */
  private[neblink] final class ConnSlot:
    @volatile var conn: PresenceConnection = null
    @volatile var earlyClose: Boolean = false

  /** deviceId -> active outgoing connection. */
  private val connections = new ConcurrentHashMap[String, PresenceConnection]()

  /** 连接代际计数器（全局单调；跨 deviceId 也唯一，便于日志/读数里逐条对账）。 */
  private val connGen = new AtomicLong(0L)

  private def nextGen(): Long = connGen.incrementAndGet()

  /** Peers currently in the reconnection loop (deviceId -> PeerInfo). */
  private val reconnecting = new ConcurrentHashMap[String, PeerInfo]()

  /** Device IDs whose reconnection should stop (explicit disconnect / peer left network). */
  private val cancelReconnect = new ConcurrentHashMap[String, java.lang.Boolean]()

  /** deviceId -> most recent dial outcome (C3). Successes are recorded too. */
  private val dialStatuses = new ConcurrentHashMap[String, PresenceDialStatus]()

  /** deviceId -> last outcome key we already logged, for C3 log rate-limiting. */
  private val lastLoggedDialKey = new ConcurrentHashMap[String, String]()

  /** deviceId -> shape of the most recent dial round (F-D readings). */
  private val dialRounds = new ConcurrentHashMap[String, PresenceDialRound]()

  /** "deviceId\u0000endpoint" -> failure bookkeeping for F-D dead-endpoint eviction. */
  private val endpointHealth = new ConcurrentHashMap[String, EndpointHealth]()

  /** deviceId -> fingerprint of the candidate set last seen from the server namelist. */
  private val candidateFingerprints = new ConcurrentHashMap[String, String]()

  /** 单端点失败账本（F-D ②）。`suppressedUntilMs = 0` ⇒ 不在剔除窗内。 */
  private final case class EndpointHealth(
    refusals: Int,
    timeouts: Int,
    suppressedUntilMs: Long,
    lastClass: String,
    lastFailureAtMs: Long
  )

  /** Most recent dial outcome for a peer; `None` = never dialed. */
  def dialStatus(deviceId: String): Option[PresenceDialStatus] = Option(dialStatuses.get(deviceId))

  /** Endpoint a SUCCESSFUL dial actually landed on (C1 择优结果), if any. */
  def chosenEndpoint(deviceId: String): Option[String] =
    dialStatus(deviceId).filter(_.error.isEmpty).map(_.endpoint).filter(_.nonEmpty)

  /** F-D: shape of the most recent dial round (attempted / skipped / truncated). */
  def lastDialRound(deviceId: String): Option[PresenceDialRound] = Option(dialRounds.get(deviceId))

  /**
   * Candidate endpoints to dial, in preference order (C1).
   *
   * `peer.endpoints` already arrives preference-ordered (100.64.0.0/10 →
   * 同网段 → 其余, see [[EndpointPreference]]); peers built by other paths
   * (inbound presence 路由 / tests) have `endpoints = Nil` and fall back to the
   * single `address` — behaviour identical to before C1.
   */
  private def candidatesOf(peer: PeerInfo): List[String] =
    val all = if peer.endpoints.nonEmpty then peer.endpoints else List(peer.address)
    all.filter(_.nonEmpty).distinct

  // ===== Public API =====

  /**
   * Reconcile active WS connections with a freshly scanned peer list.
   *
   * - Upserts discovered peers into the NeblinkService peer list.
   * - Connects to peers that are in the list but have no active WS (and aren't
   *   already being auto-reconnected).
   * - Disconnects from peers that have a WS but are no longer in the list.
   * - Removes peers from the local list when they vanished from the server
   *   response — staleness is judged against peersRef (the whole local list),
   *   not only active WS connections: a peer with no P2P connection (e.g.
   *   cross-network, relay-only) that the server no longer reports must leave
   *   the list too, otherwise it becomes a permanent ghost entry.
   * - Cancels auto-reconnect for peers that left the network.
   */
  def syncPeers(peers: List[PeerInfo]): IO[Unit] =
    val peerIds = peers.iterator.map(_.deviceId).toSet
    for
      knownPeers <- neblinkService.peers
      stalePeerIds = knownPeers.iterator.map(_.deviceId).toSet.filterNot(peerIds.contains)
      staleConnIds = connections.keySet().asScala.filterNot(peerIds.contains).toList
      staleReconnectIds = reconnecting.keySet().asScala.filterNot(peerIds.contains).toList
      _ <- peers.traverse_(peer => neblinkService.upsertPeer(peer))
      // F-D 恢复路径②：名册里候选集合变了 ⇒ 该设备的死端点判定整体失效（设备换网/
      // 换地址后旧判定不再适用），复位重新评估。对**全部**在册 peer 做，不只看要拨
      // 的那些（正在重连的 peer 也要能被名册变化复位）。
      _ <- IO(peers.foreach(p => refreshCandidateFingerprint(p, candidatesOf(p))))
      // Disconnect peers that left the network
      _ <- IO.blocking((stalePeerIds ++ staleConnIds).foreach(id => disconnectPeer(id)))
      _ <- (stalePeerIds ++ staleConnIds).toList.traverse_(id => neblinkService.removePeer(id))
      // Cancel reconnection for peers no longer in the network
      _ <- IO.blocking(staleReconnectIds.foreach(id => cancelReconnect.put(id, true)))
      // Connect to peers without active connection, skip those already reconnecting.
      // lastSeen > 0 skips server-flagged-offline peers (lastSeen=0 marker from
      // toNeblinkPeers) — no P2P attempt against a device the server calls dead.
      _ <- peers
        .filter(p => p.lastSeen > 0 && !connections.containsKey(p.deviceId) && !reconnecting.containsKey(p.deviceId))
        .traverse_(p => connect(p).start.void)
    yield ()

  end syncPeers

  /** Establish an outgoing WS presence connection to a peer. No-op if already connected.
   *
   * C1 (2026-09-11 P2P 直连修复批): dials every candidate endpoint in preference
   * order with a short per-candidate budget, short-circuiting on the first
   * success. Before this批 only `peer.address` (= the server namelist's
   * `endpoints.head`, in the incident an unreachable LAN address) was dialed, so
   * a healthy Tailscale endpoint sitting at index 1 was never tried and
   * `directOnline` stayed false forever (方案 §1.2 (A)/(D)).
   *
   * C3: the outcome of every attempt is recorded (and logged on state change) so
   * a failed dial is attributable instead of vanishing into a DEBUG line.
   *
   * F-D: 预算按地址类别给（[[DialBudget]]），被判定死的候选本轮跳过
   * （[[EvictionPolicy]]），每轮的选择/跳过/截断记进 [[PresenceDialRound]]。
   */
  def connect(peer: PeerInfo): IO[Unit] =
    if connections.containsKey(peer.deviceId) then IO.unit
    else
      val candidates = candidatesOf(peer)
      if candidates.isEmpty then
        recordDialOutcome(peer, "", Some("no usable endpoint (empty peer address)"))
      else
        neblinkService.identity
          .flatMap(id => dialRound(peer, candidates, id))
          .handleErrorWith(e =>
            // identity / dispatcher-level failure — still must be attributable
            recordDialOutcome(peer, candidates.head, Some(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
          )

  /**
   * One dial ROUND (F-D ①/②): pick the candidates to try — candidates judged dead
   * are skipped so a black-holed address stops eating the round budget — then walk
   * them in preference order until one answers or the round budget runs out.
   *
   * 🔴 全部候选都在剔除窗内时**不判死也不空转**：只半开探测最优候选（成本上界
   * = 1×budget），其余等恢复路径（TTL 到期 / 名册变化 / 梯子收手复位）。
   */
  private def dialRound(peer: PeerInfo, candidates: List[String], id: DeviceIdentity): IO[Unit] =
    val startedAtMs = System.currentTimeMillis()
    val live = candidates.filterNot(ep => isSuppressed(peer.deviceId, ep, startedAtMs))
    val (toTry, skipped, probeOnly) =
      if live.nonEmpty then (live, candidates.filterNot(live.contains), false)
      else (candidates.take(1), candidates.drop(1), true)
    val preamble =
      if probeOnly then
        logger.debug(s"All candidates suppressed for ${peer.deviceName} — half-open probing ${toTry.head}")
      else if skipped.nonEmpty then
        logger.debug(s"Skipping ${skipped.size} suppressed candidate(s) for ${peer.deviceName}: ${skipped.mkString(", ")}")
      else IO.unit
    preamble *>
      dialCandidates(peer, toTry, id, startedAtMs, Nil, Nil, probeOnly).flatMap {
        case (attempted, truncated, failures) =>
          val round = PresenceDialRound(attempted, skipped, truncated, failures, startedAtMs)
          IO(dialRounds.put(peer.deviceId, round)) *>
            (if truncated then
               logger.info(
                 s"Presence dial round for ${peer.deviceName} hit the ${dialBudget.roundBudgetMs}ms round budget " +
                   s"after ${attempted.size} candidate(s) — the remaining candidate(s) wait for the next beat"
               )
             else IO.unit)
      }

  /**
   * Try candidates left-to-right; on success record the winner and write it back as
   * the peer's `address` (so a subsequent `p2pExecute` — which also walks the
   * candidate list — starts from the endpoint proven reachable), otherwise record
   * the failure and move to the next candidate.
   *
   * Returns `(endpoints actually dialed in order, truncatedByRoundBudget,
   * failures-in-dial-order)`. Stops early when the round budget is spent (never
   * before the first attempt, so a round always makes progress) — the untried tail
   * waits for the next beat. `truncated` is strictly the budget case: a
   * short-circuit on SUCCESS is not truncation.
   */
  private def dialCandidates(
    peer: PeerInfo,
    toTry: List[String],
    id: DeviceIdentity,
    startedAtMs: Long,
    attemptedRev: List[String],
    failuresRev: List[(String, DialFailureClass)],
    probeOnly: Boolean
  ): IO[(List[String], Boolean, List[(String, DialFailureClass)])] =
    toTry match
      case Nil => IO.pure((attemptedRev.reverse, false, failuresRev.reverse))
      case endpoint :: rest =>
        if attemptedRev.nonEmpty && System.currentTimeMillis() - startedAtMs >= dialBudget.roundBudgetMs then
          IO.pure((attemptedRev.reverse, true, failuresRev.reverse))
        else
          resolveDialTarget(endpoint) match
            case None =>
              recordDialOutcome(peer, endpoint, Some(s"unparseable endpoint: $endpoint")) *>
                noteEndpointFailure(peer, endpoint, DialFailureClass.Other, probeOnly) *>
                dialCandidates(
                  peer,
                  rest,
                  id,
                  startedAtMs,
                  endpoint :: attemptedRev,
                  (endpoint, DialFailureClass.Other) :: failuresRev,
                  probeOnly
                )
            case Some((host, port)) =>
              IO.blocking(openConnection(peer, host, port, id, dialBudget.budgetMsFor(endpoint))).flatMap {
                case Right(_) =>
                  noteEndpointSuccess(peer, endpoint) *>
                    recordDialOutcome(peer, endpoint, None) *>
                    (if endpoint != peer.address then neblinkService.upsertPeer(peer.copy(address = endpoint))
                     else IO.unit) *>
                    IO.pure(((endpoint :: attemptedRev).reverse, false, failuresRev.reverse))
                case Left(failure) =>
                  recordDialOutcome(peer, endpoint, Some(failure.reason)) *>
                    noteEndpointFailure(peer, endpoint, failure.cls, probeOnly) *>
                    dialCandidates(
                      peer,
                      rest,
                      id,
                      startedAtMs,
                      endpoint :: attemptedRev,
                      (endpoint, failure.cls) :: failuresRev,
                      probeOnly
                    )
              }

  /**
   * Blocking dial of a single host+port with the caller's per-candidate budget.
   * Returns `Left(failure)` on failure — `reason` is what C3 §反控-3 needs to tell
   * "地址不可达 / 拨号超时" apart, `cls` is what F-D §剔除 needs to apply the
   * refusal-vs-timeout (strong-vs-ambiguous) judgement.
   * Registers the connection in `connections` on success.
   *
   * F-1 (presdial 批 2026-09-19): the port is now a **parameter** (the candidate's
   * own port, see [[resolveDialTarget]]) instead of being read off `serverPort`
   * inside [[buildWsUri]] — see that helper for why.
   */
  private def openConnection(
    peer: PeerInfo,
    host: String,
    port: Int,
    id: DeviceIdentity,
    budgetMs: Long
  ): Either[DialFailure, Unit] =
    val wsUri = buildWsUri(host, port, id)
    try
      val alive = new AtomicBoolean(true)
      // 🔴 逾期时钟的起点**刻意留在拨号开始处**（不是握手/发布之后）：逾期判据是「严格大于」阈值，
      // 而拍子的起点在**握手之后** ⇒ 两者相抵 ⇒ 判定恰好等价于「**本连接建立后**连续 10s 无 pong」
      // （握手 h > 0 时第 2 拍到达即满足 `h + 2×5s > 10s`；仅 h = 0 的退化情形落到第 3 拍）。
      // 若把起点挪到握手之后，实际容忍窗会变成 15s（晚一拍），反而偏离「10s 无 pong」的口径。
      // 判词报告 §3.4 的「单连接自循环 ≈16.2s」是按「起点 = 连接建立 + 第 2 拍恰 10000ms 不触发」
      // 推的算术值；本批实测的单连接自循环 = **10.05s**（= 10s 无 pong + 环回重连握手 ≈50ms），
      // 见 `NeblinkPresenceHeartbeatLifecycleSpec` ② 的逐条间隔读数。详见
      // [[NeblinkPresenceService.singleConnectionSelfCycleMs]]。
      val lastPong = new AtomicLong(System.currentTimeMillis())
      val gen = nextGen()
      val heartbeat = Executors.newSingleThreadScheduledExecutor { r =>
        val t = new Thread(r, s"presence-hb-${peer.deviceName}")
        t.setDaemon(true)
        t
      }

      val slot = new ConnSlot
      val listener = new PresenceWsListener(this, peer, slot)
      val client = HttpClient
        .newBuilder()
        .proxy(java.net.ProxySelector.of(null)) // bypass HTTP proxy for P2P
        .build()
      val ws = client
        .newWebSocketBuilder()
        .buildAsync(URI.create(wsUri), listener)
        .get(budgetMs, TimeUnit.MILLISECONDS)

      val conn = PresenceConnection(peer.deviceId, gen, ws, alive, lastPong, heartbeat)
      slot.conn = conn
      if slot.earlyClose then
        // 握手期（发布之前）监听器就收到了 close/error ⇒ 🔴 **不得发布**：否则会留下一条
        // 「上线即死」的在册 conn，并在 ~15s 后制造一次**伪掉线 + 重连**（本批要消灭的形态）。
        retireConnection(conn, "closed during handshake")
        Left(DialFailure("closed during handshake", DialFailureClass.Other))
      else
        // ① 无条件先退役同 deviceId 的现役 conn（幂等；禁游离心跳线程）——
        //    任何换连接路径都必须走这一步。
        retireAllFor(peer.deviceId, "superseded")
        // ② 发布本 conn；`put` 的返回值就是被我们顶替掉的那条（并发窗口兜底：它也一并退役）。
        val incumbent = connections.put(peer.deviceId, conn)
        if incumbent != null && (incumbent ne conn) then retireConnection(incumbent, "superseded")
        try
          // 心跳：每 5s 一拍；逾期（>10s 无 pong）则强制清理。
          // 🔴 拍子**只认自己的 conn**（闭包捕获 conn 句柄，绝不按 deviceId 反查别人的状态）；
          //    🔴 顺序纪律 = 先发布、后起拍（反序会在并发顶替下留下「拍子在跑但 conn 不在册」
          //    的游离线程，正是本批要消灭的形态）。
          heartbeat.scheduleAtFixedRate(
            { () =>
              try
                if conn.alive.get() then
                  val overdue = System.currentTimeMillis() - conn.lastPong.get() > NeblinkPresenceService.HeartbeatTimeoutMs
                  if overdue then
                    // 僵尸分支：先做 compare-and-remove —— **只有摘到自己的那条**才允许走
                    // 「掉线 + 重连」腿（真掉线仍须摘除，判据④）。
                    if removeIfCurrent(conn.deviceId, conn) then
                      logger.debugSync(s"Heartbeat timeout: ${peer.deviceName}")
                      // Force immediate cleanup — don't rely on onClose (may never fire
                      // if the TCP connection is broken, e.g. after sleep/wake)
                      retireConnection(conn, "heartbeat timeout")
                      // Remove peer and trigger auto-reconnect
                      dispatcher.unsafeRunAndForget(
                        neblinkService.removePeer(peer.deviceId) *>
                          logger.info(s"Heartbeat timeout: ${peer.deviceName}, auto-reconnecting...") *>
                          startReconnect(peer)
                      )
                    else
                      // 本线程的 conn 已被退役/顶替（迟到拍）⇒ **静默退出该拍**：
                      // 🔴 禁 removePeer、禁 startReconnect、禁动他人的 socket（判据②③）。
                      logger.debugSync(
                        s"Stale heartbeat beat for ${peer.deviceName} (gen $gen, current ${currentGenOf(peer.deviceId)}) — retiring this thread only"
                      )
                      retireConnection(conn, "stale heartbeat")
                  else ws.sendText("""{"type":"ping"}""", true)
              catch case _: Exception => ()
            },
            NeblinkPresenceService.HeartbeatIntervalSec,
            NeblinkPresenceService.HeartbeatIntervalSec,
            TimeUnit.SECONDS
          )
          Right(())
        catch
          case _: java.util.concurrent.RejectedExecutionException =>
            // 「发布 → 起拍」之间被并发顶替并退役（executor 已 shutdown）⇒ 本 conn 的拍子
            // 已无宿主。现役 conn 由顶替者持有 ⇒ 本条按拨号失败返回（🔴 不得留下无拍子的在册 conn）。
            retireConnection(conn, "superseded")
            Left(DialFailure("superseded during publish", DialFailureClass.Other))
    catch
      case _: java.util.concurrent.TimeoutException =>
        // C3 §反控-3: the "timeout" class is a distinct, attributable reason —
        // the address was black-holed rather than actively refused.
        Left(DialFailure(s"timeout after ${budgetMs}ms", DialFailureClass.Timeout))
      case e: Exception =>
        Left(dialFailureOf(e))

  /**
   * 把一次失败的拨号异常折成 [[DialFailure]]（F-D 2026-09-17）。
   *
   * 🔴 为什么按**整条链**判定、而不是只看根因：JDK 的 WS 建连失败链实测为
   * `ExecutionException("java.net.ConnectException") → ConnectException(msg=null)
   * → ClosedChannelException(msg=null)`（探针：环回无监听端口）。走到最深处反而
   * **丢信号**——被判成 `ClosedChannelException` ⇒ 既非「主动拒绝」也非「超时」⇒
   * 落到 [[DialFailureClass.Other]] ⇒ 剔除永不触发（判据②红），且 C3 的「可归因」
   * 退化成无信息的类名。故：链上任一段命中强信号即判 `Refused`（强信号优先于超时），
   * 可读原因取链上**第一个有消息**的因（跳过 CompletableFuture 包装层的消息，
   * 它的 getMessage 只是被包异常的类名）。
   */
  private def dialFailureOf(t: Throwable): DialFailure =
    val chain = causeChain(t)
    DialFailure(failureReasonOf(chain), failureClassOf(chain))

  /** 异常链（由外到内；去自引用、深度封顶，防病态链把线程栈吃干）。 */
  private def causeChain(t: Throwable): List[Throwable] =
    val b = List.newBuilder[Throwable]
    var cur: Throwable = t
    var depth = 0
    while cur != null && depth < 12 do
      b += cur
      val nxt = cur.getCause
      cur = if nxt eq cur then null else nxt
      depth += 1
    b.result()

  /** 可读原因：跳过包装层消息，取链上第一个有消息的因；全无消息时用已知网络异常的类名。 */
  private def failureReasonOf(chain: List[Throwable]): String =
    val meaningful = chain.filterNot(isCompletionWrapper)
    meaningful
      .flatMap(t => Option(t.getMessage))
      .headOption
      .orElse(meaningful.find(isKnownNetworkFailure).map(_.getClass.getSimpleName))
      .getOrElse(meaningful.lastOption.map(_.getClass.getSimpleName).getOrElse("dial failed"))

  private def isCompletionWrapper(t: Throwable): Boolean =
    t.isInstanceOf[java.util.concurrent.CompletionException] ||
      t.isInstanceOf[java.util.concurrent.ExecutionException]

  private def isKnownNetworkFailure(t: Throwable): Boolean =
    isRefusal(t) || isTimeoutSignal(t)

  /**
   * F-D 失败分类：强信号（主动拒绝/无路由/域名不可解析 ⇒ 端点确定不服务）vs
   * 歧义信号（超时 ⇒ 可能只是慢/中继拥塞/沉睡唤醒）。这一分类直接决定剔除门槛；
   * 判定面是整条异常链（见 [[dialFailureOf]]），强信号优先。
   */
  private def failureClassOf(chain: List[Throwable]): DialFailureClass =
    if chain.exists(isRefusal) then DialFailureClass.Refused
    else if chain.exists(isTimeoutSignal) then DialFailureClass.Timeout
    else DialFailureClass.Other

  private def isRefusal(t: Throwable): Boolean =
    t match
      case _: java.net.ConnectException | _: java.net.NoRouteToHostException |
          _: java.net.UnknownHostException | _: java.net.PortUnreachableException =>
        true
      case _ => false

  private def isTimeoutSignal(t: Throwable): Boolean =
    t match
      case _: java.net.SocketTimeoutException | _: java.net.http.HttpTimeoutException => true
      case _                                                                          => false

  /**
   * Record a dial outcome (C3) and log **only on state change** — a peer that
   * stays unreachable is retried every sync cycle (~30s), so unconditional
   * logging would flood the file. Failures log at WARN (was `debug`, which
   * `root level=INFO` swallowed entirely — 方案 §1.1 环③ evidence E-5).
   *
   * F-4 (presdial 批 2026-09-19): the line names the **resolved dial target**
   * (`host:port` actually opened) next to the candidate string. Before this批 the
   * line only echoed the candidate, so 8097-instance readings like
   * "via `http://100.91.165.120:8080`: timeout" were read as "it dialed :8080"
   * while it had in fact dialed the dialer's own port (诊断 §2.3 ①).
   */
  private def recordDialOutcome(peer: PeerInfo, endpoint: String, error: Option[String]): IO[Unit] =
    val shown = if endpoint.nonEmpty then endpoint else if peer.address.nonEmpty then peer.address else "(no endpoint)"
    dialStatuses.put(peer.deviceId, PresenceDialStatus(shown, error, System.currentTimeMillis()))
    val key = s"$shown|${error.getOrElse("")}"
    val changed = Option(lastLoggedDialKey.put(peer.deviceId, key)).forall(_ != key)
    if !changed then IO.unit
    else
      error match
        case Some(err) =>
          logger.warn(
            s"Presence dial failed for ${peer.deviceName} via $shown: $err " +
              s"(dial target ${dialTargetLabel(endpoint)}; tried ${candidatesOf(peer).size} candidate endpoint(s))"
          )
        case None =>
          logger.info(
            s"Presence dial ok for ${peer.deviceName} via $shown (dial target ${dialTargetLabel(endpoint)})"
          )

  // ===== F-D ②: 死端点剔除（账本 + 恢复语义） =====

  private def endpointKey(deviceId: String, endpoint: String): String = s"$deviceId\u0000$endpoint"

  /** 该候选当前是否在剔除窗内（`suppressedUntilMs` 是绝对时刻 ⇒ TTL 到期自动失效）。 */
  private def isSuppressed(deviceId: String, endpoint: String, nowMs: Long): Boolean =
    val h = endpointHealth.get(endpointKey(deviceId, endpoint))
    h != null && h.suppressedUntilMs > nowMs

  /**
   * 记一次失败并判定是否剔除该**候选端点**（不是 peer——peer 级「判死」本批不引入）。
   *
   * 计数按失败类**分轴**且要求「连续」：换类即把另一轴清零。`probeOnly`
   * （全部候选都在剔除窗内时的那一次半开探测）失败**不改账本**：不延窗、不计数 ⇒
   * 每拍成本恒为 1×budget，且探测不会把抑制窗无限续期。
   */
  private def noteEndpointFailure(
    peer: PeerInfo,
    endpoint: String,
    cls: DialFailureClass,
    probeOnly: Boolean
  ): IO[Unit] =
    if probeOnly then IO.unit
    else
      IO {
        val key = endpointKey(peer.deviceId, endpoint)
        val now = System.currentTimeMillis()
        // 窗口已过期（或从未剔除）⇒ 旧计数视为失效，从零重评（恢复路径①的落点）
        val fresh = Option(endpointHealth.get(key)).filter(h => h.suppressedUntilMs == 0L || h.suppressedUntilMs > now)
        val base = fresh.getOrElse(EndpointHealth(0, 0, 0L, "", 0L))
        val (refusals, timeouts) = cls match
          case DialFailureClass.Refused => (base.refusals + 1, 0)
          case DialFailureClass.Timeout => (0, base.timeouts + 1)
          case DialFailureClass.Other   => (0, 0)
        val strikes = if cls == DialFailureClass.Refused then refusals else timeouts
        val threshold = if cls == DialFailureClass.Refused then eviction.refusalStrikes else eviction.timeoutStrikes
        if cls != DialFailureClass.Other && strikes >= threshold then
          endpointHealth.put(key, EndpointHealth(0, 0, now + eviction.suppressedTtlMs, cls.label, now))
          logger.warn(
            s"Presence endpoint suppressed for ${peer.deviceName} via $endpoint: ${cls.label} × $strikes " +
              s"(retry after ${eviction.suppressedTtlMs / 1000}s; also reset by namelist change / reconnect-ladder handback)"
          )
        else endpointHealth.put(key, EndpointHealth(refusals, timeouts, 0L, cls.label, now))
      }

  /** 拨通即清除该候选的账本（恢复路径④）。 */
  private def noteEndpointSuccess(peer: PeerInfo, endpoint: String): IO[Unit] =
    IO { endpointHealth.remove(endpointKey(peer.deviceId, endpoint)); () }

  private def clearEndpointHealth(deviceId: String): Unit =
    val prefix = s"$deviceId\u0000"
    endpointHealth.keySet().asScala.filter(_.startsWith(prefix)).foreach(endpointHealth.remove)

  /** 该设备整体复位（离开网络 / 显式断开 / 梯子收手）：账本 + 名册指纹 + 轮记录。 */
  private def forgetDevice(deviceId: String): Unit =
    clearEndpointHealth(deviceId)
    candidateFingerprints.remove(deviceId)
    dialRounds.remove(deviceId)

  /** F-D 恢复路径②：名册候选集合变化 ⇒ 复位该设备的死端点判定。 */
  private def refreshCandidateFingerprint(peer: PeerInfo, candidates: List[String]): Unit =
    val fp = candidates.mkString("\u0000")
    val prev = candidateFingerprints.put(peer.deviceId, fp)
    if prev != null && prev != fp then
      clearEndpointHealth(peer.deviceId)
      logger.info(s"Presence candidate list changed for ${peer.deviceName} — dead-endpoint verdicts reset")

  /** Explicitly disconnect from a peer by deviceId. Cancels any pending reconnection. */
  def disconnect(deviceId: String): IO[Unit] =
    IO.blocking(disconnectPeer(deviceId))

  /** Disconnect all peers and cancel all reconnections (e.g. on logout). */
  def disconnectAll(): IO[Unit] =
    IO.blocking {
      // Signal all reconnection loops to stop
      val allIds = Set[String]() ++ connections.keySet().asScala ++ reconnecting.keySet().asScala
      allIds.foreach(id => cancelReconnect.put(id, true))
      allIds.foreach(id => reconnecting.remove(id))
      // Close all active connections（快照遍历：disconnectPeer 会改 `connections`，
      // 边遍历边摘会让弱一致迭代器漏掉/重复访问 —— 直接取一份键快照）
      connections.keySet().asScala.toList.foreach(id => disconnectPeer(id))
    }

  // ===== 连接生命周期：唯一退役例程（幂等 · 只对本 conn 生效）=====

  /**
   * 退役一条连接 —— **唯一**的关连接例程，幂等，且**只对本 conn 生效**（判据①）。
   *
   * 三件事（顺序即语义）：
   *  1. `alive.set(false)`：先把「本 conn 已死」钉住 —— 任何**迟到**事件（onClose / 心跳拍 /
   *     pong）随后读到的都是「已死」，因此不会再触发 removePeer / 重连（判据③的迟到事件护栏）；
   *  2. `heartbeat.shutdownNow()`：拍子与连接**同灭** ⇒ 不产生游离心跳线程；
   *  3. compare-and-remove + 关**自己的** socket：只有 `connections` 里仍是本 conn 时才摘除
   *     （顶替者绝不被误摘），Close 帧也只发在**自己的** socket 上。
   *
   * 🔴 禁在任何地方「按 deviceId 取回一条 conn 就退役」：那正是改前的别名错配
   * （`onClosed` / 僵尸分支摘掉的是别人的 conn、`shutdownNow` 关掉的是别人的心跳线程）。
   */
  private def retireConnection(conn: PresenceConnection, reason: String): Unit =
    if conn != null then
      conn.alive.set(false)
      try conn.heartbeat.shutdownNow()
      catch case _: Exception => ()
      removeIfCurrent(conn.deviceId, conn)
      try conn.ws.sendClose(WebSocket.NORMAL_CLOSURE, reason)
      catch case _: Exception => ()

  /**
   * 身份比较-摘除：**只有** `connections(deviceId)` 仍是 `conn` **这一条**时才摘除。
   *
   * 为什么不用 `connections.remove(k, v)` 的两参数版：`PresenceConnection` 是 case class ⇒
   * 两参数版走 `equals`（结构相等），语义会依赖字段实现。这里显式用引用相等判一遍；循环
   * 封顶（≤8 轮）是为了在「读-比-摘」之间被别的线程改动时不空转 —— 下一轮要么看到自己的
   * 那条（摘）、要么看到别人的（退出）。
   */
  private def removeIfCurrent(deviceId: String, conn: PresenceConnection): Boolean =
    var removed = false
    var done = false
    var guard = 0
    while !done && guard < 8 do
      guard += 1
      val cur = connections.get(deviceId)
      if cur eq conn then
        if connections.remove(deviceId, cur) then
          removed = true
          done = true
      else done = true
    removed

  /** 现役连接代际（读出面：`-1` = 该 deviceId 当前无现役连接）。 */
  private def currentGenOf(deviceId: String): Long =
    val cur = connections.get(deviceId)
    if cur == null then -1L else cur.gen

  /**
   * 把该 deviceId 名下的现役连接**全部**退役（有界循环，正常路径只跑一轮）。
   *
   * 「换连接」类路径专用（[[openConnection]] 发布之前 / 显式断开）：只退役**取到的**那条会
   * 漏掉并发窗口里刚被换上的另一条 ⇒ 循环到表里没有该键为止。`maxRounds` 只是防活锁兜底
   * （重连腿已被 `cancelReconnect`/`reconnecting` 锁住，不会无限续命）。
   */
  private def retireAllFor(deviceId: String, reason: String, maxRounds: Int = 4): Unit =
    var rounds = 0
    var cur = connections.get(deviceId)
    while cur != null && rounds < maxRounds do
      rounds += 1
      retireConnection(cur, reason)
      cur = connections.get(deviceId)

  /** 现役连接条数（验收读数面：恒有 `count(presence-hb-*) ≤ 本值`，且稳态相等）。 */
  private[neblink] def connectionCount: Int = connections.size()

  /** 现役连接 `deviceId -> 代际` 快照（验收/诊断读数面：线程 ↔ 连接逐轮对账）。 */
  private[neblink] def connectionGens: Map[String, Long] =
    connections.entrySet().asScala.map(e => e.getKey -> e.getValue.gen).toMap

  // ===== Internal (called from WS listener / heartbeat threads) =====

  /**
   * Called by the WS listener when the connection closes or errors — **身份入口**：
   * 带进来的是**事件所属的那条** conn（`slot`），不是按 deviceId 反查到的任意一条。
   * If the close was unexpected (alive still true **and** this conn was still the live one),
   * removes the peer and starts auto-reconnection immediately.
   */
  private[neblink] def onClosed(slot: ConnSlot, peer: PeerInfo): Unit =
    val conn = slot.conn
    if conn == null then slot.earlyClose = true // 握手期关闭：交回 openConnection，不发布
    else closeConnection(conn, peer)

  /**
   * 按 deviceId 的关闭入口（**签名保留**：`NeblinkPresenceDialBudgetSpec` 直接调它造
   * 「对端关闭 ⇒ 梯子立即重拨」形态；生产侧只有 WS 监听器，走上面的身份入口）。
   *
   * 语义 = 「以该 deviceId 为身份的那条连接刚关闭」⇒ 退役它并按真掉线处置（判据④）。
   * 🔴 改前这是**唯一**入口 —— 陈旧监听器的迟到 close 因此会摘掉**新连接的** conn 并关掉
   * 新连接的心跳线程（别名错配家族）；现在它只对「此刻在册的那条」生效，而知道自己身份的
   * 调用方（监听器）一律走身份入口。
   */
  private[neblink] def onClosed(deviceId: String, peer: PeerInfo): Unit =
    closeConnection(connections.get(deviceId), peer)

  /** 退役 `conn`，并按「是否真掉线」决定 removePeer + 重连（判据②③④的汇合点）。 */
  private def closeConnection(conn: PresenceConnection, peer: PeerInfo): Unit =
    if conn != null then
      // 🔴 wasAlive 必须在退役**之前**读：退役会把 alive 置 false（迟到事件因此按「已死」处理）
      val wasAlive = conn.alive.get()
      val wasCurrent = removeIfCurrent(peer.deviceId, conn)
      retireConnection(conn, "closed")
      if wasCurrent && wasAlive then
        // Unexpected close — remove peer and auto-reconnect
        dispatcher.unsafeRunAndForget(
          neblinkService.removePeer(peer.deviceId) *>
            logger.info(s"Presence disconnected: ${peer.deviceName}, auto-reconnecting...") *>
            startReconnect(peer)
        )
    // 非现役 / 已被退役（心跳超时或 disconnectPeer 已处置）⇒ 静默：
    // 判据③ —— 迟到事件不得为新连接触发 removePeer 或额外重连。

  /** Called by the WS listener when a pong frame arrives — refreshes liveness.
    *
    * 🔴 hblife 批口径（**刻意不改**）：pong 属于**当前连接**，所以按 deviceId 刷新在册的那条
    * 是正确语义。陈旧心跳线程不再因此误判 —— 它的 conn 句柄已随退役而死（退役即 shutdownNow +
    * compare-and-remove），拍子本身也只认自己的 `lastPong`（见 [[openConnection]] 的心跳回调）。
    */
  private[neblink] def updateLastPong(deviceId: String): Unit =
    val conn = connections.get(deviceId)
    if conn != null then conn.lastPong.set(System.currentTimeMillis())

  /** Called by the WS listener when a data message arrives from a peer. */
  private[neblink] def onDataReceived(payload: Json): Unit =
    dispatcher.unsafeRunAndForget(neblinkService.handleDataMessage(payload))

  /** Check if a direct WS connection exists to the given device. */
  def isConnected(deviceId: String): Boolean =
    connections.containsKey(deviceId)

  /** Send a data message to a connected peer over the WS presence connection.
    * Returns true if the message was actually flushed to the socket, false if
    * there is no connection or the write did not complete (half-open TCP). */
  def sendData(deviceId: String, channel: String, payload: Json): IO[Boolean] =
    IO.blocking {
      val conn = connections.get(deviceId)
      if conn != null then
        val msg = Json.obj(
          "type" -> "data".asJson,
          "channel" -> channel.asJson,
          "payload" -> payload
        )
        // Delivery-verified send (diag-transfer-stuck R3/P2): sendText used to
        // return true as soon as the frame was *queued*. On a half-open
        // connection the future never completes and the frame was silently
        // lost while the caller believed P2P delivery succeeded (so it never
        // fell back to relay). Wait briefly for the flush; timeout/failure →
        // false so sendDataOrRelay can use the relay path.
        try
          conn.ws.sendText(msg.noSpaces, true).get(5, TimeUnit.SECONDS)
          true
        catch case _: Exception =>
          logger.debug(s"WS send to $deviceId did not complete (half-open?), caller should use relay fallback")
          false
      else
        logger.debug(s"No WS connection to $deviceId, data message not delivered via P2P")
        false
    }

  /**
   * Start an auto-reconnection loop for a peer. Idempotent: if the peer is
   * already being reconnected, or was explicitly cancelled, does nothing.
   */
  private def startReconnect(peer: PeerInfo): IO[Unit] =
    IO.blocking {
      if cancelReconnect.remove(peer.deviceId) != null then
        reconnecting.remove(peer.deviceId)
        false // was cancelled
      else reconnecting.putIfAbsent(peer.deviceId, peer) == null // true if we won the slot
    }.flatMap {
      case false => IO.unit
      case true => reconnectLoop(peer, 0)
    }

  /**
   * Reconnection with immediate first attempt, then exponential backoff:
   * 0s (immediate) -> 1s -> 2s -> 4s -> 8s -> 16s -> 30s (capped).
   *
   * F-D ③ 与同步拍的关系（判据④）：每次尝试的耗时上界 = 一轮拨号预算
   * （[[NeblinkPresenceService.dialRoundUpperBoundMs]]，默认 12s），延迟封顶
   * [[NeblinkPresenceService.ReconnectDelayCapMs]] (30s) ≤ 同步拍 45s ⇒ **梯子不会
   * 比拍子慢**：收手后最多再等 1 拍就回到 `syncPeers` 的拨号腿。
   * 收手时（见 [[reconnectGaveUp]]）复位该设备的死端点判定并把真实时长上界写进日志。
   */
  private def reconnectLoop(peer: PeerInfo, attempt: Int): IO[Unit] =
    if cancelReconnect.remove(peer.deviceId) != null then
      reconnecting.remove(peer.deviceId)
      IO.unit
    else if attempt >= NeblinkPresenceService.ReconnectMaxAttempts then
      reconnecting.remove(peer.deviceId)
      reconnectGaveUp(peer, attempt)
    else if connections.containsKey(peer.deviceId) then
      // Already reconnected (e.g. by syncPeers)
      reconnecting.remove(peer.deviceId)
      IO.unit
    else
      // attempt 0: immediate. Then 1, 2, 4, 8, 16, 30, 30, ...
      IO.sleep(NeblinkPresenceService.reconnectDelayMs(attempt).millis) *>
        IO.blocking(Option(cancelReconnect.remove(peer.deviceId))).flatMap {
          case Some(_) =>
            // Cancelled during sleep
            reconnecting.remove(peer.deviceId)
            IO.unit
          case None =>
            connect(peer)
              .flatMap { _ =>
                if connections.containsKey(peer.deviceId) then
                  // Success — re-add peer to NeblinkService and clean up
                  reconnecting.remove(peer.deviceId)
                  neblinkService.upsertPeer(peer) *>
                    logger.info(s"Reconnected to ${peer.deviceName} after ${attempt + 1} attempt(s)")
                else reconnectLoop(peer, attempt + 1)
              }
              .handleErrorWith(_ => reconnectLoop(peer, attempt + 1))
        }

  /**
   * 梯子收手（F-D ③ 的「兜底」）：把交回同步拍之前的场地清干净。
   *
   * 🔴 为什么必须复位：收手后接手的 `syncPeers` 拨号腿若沿用「已剔除」的账本，就只在
   * 被自己缩小的子集里拨 ⇒ 恢复面被梯子自己吃掉。复位 = 恢复路径③。
   * 日志给出**真实**上界（每轮拨号预算 + 退避梯）——原文的「~5 min total」已失真
   * （仅延迟 Σ 就有 451s）。
   */
  private[neblink] def reconnectGaveUp(peer: PeerInfo, attempt: Int): IO[Unit] =
    IO(forgetDevice(peer.deviceId)) *>
      logger.info(
        s"Reconnect gave up after $attempt attempts " +
          s"(drain bound ≈${NeblinkPresenceService.ladderDrainUpperBoundMs(attempt, dialBudget.roundUpperBoundMs(candidatesOf(peer))) / 1000}s " +
          s"@ ${dialBudget.roundBudgetMs}ms/round); dead-endpoint verdicts reset for ${peer.deviceName} — " +
          s"handing back to the ${NeblinkPresenceService.SyncBeatFloorMs / 1000}s sync beat"
      )

  // ===== Explicit disconnect (cancels reconnection) =====

  /** Close WS, shut down heartbeat, remove from map. Signals reconnection to stop. */
  private def disconnectPeer(deviceId: String): Unit =
    cancelReconnect.put(deviceId, true)
    reconnecting.remove(deviceId)
    // F-D: 显式断开 / 离开网络 ⇒ 该设备的死端点判定与名册指纹一并复位
    // （重新入网 = 全新评估，不背旧账）。恢复路径：离开网络后回来即从零开始。
    forgetDevice(deviceId)
    // hblife 批：无条件走**唯一退役例程**（改前是「按 deviceId 取回一条 → 关它的心跳」，
    // 在并发顶替窗口里会摘掉/关掉刚换上的那条）；循环到该 deviceId 名下不再有现役连接为止，
    // 与「显式断开 = 此后不得再有该 peer 的心跳线程」的语义对齐。
    retireAllFor(deviceId, "disconnect")

  // ===== Helpers =====

  /**
   * F-1 (presdial 批 2026-09-19): resolve a candidate endpoint to the **dial target**
   * `(host, port)` — the endpoint's OWN port when it carries one, falling back to
   * this process's `serverPort` only when it carries none.
   *
   * WHY: the pre-F-1 code kept the host and **dropped the port**
   * (`extractHost` → `buildWsUri(host, id)` read `serverPort`), so any instance
   * whose gateway port is not the peer's port dialed the wrong endpoint — an
   * isolated instance on `--port 8097` dialing a peer at `http://10.0.0.5:8097`
   * actually opened `ws://10.0.0.5:<its own port>` (诊断 §2.3 ①②: "隔离实例永远
   * 拨不到任何对端"), and the failure line printed the *candidate* string as
   * "the port we dialed". Peers that carry no port (inbound presence / legacy
   * `PeerInfo.address` without a port) keep the old behaviour exactly.
   *
   * The peer side is **not** touched: advertising the port we listen on (the WS
   * query `port` param, still `serverPort`) is correct behaviour — only the
   * dialing side was fixed.
   */
  private def resolveDialTarget(address: String): Option[(String, Int)] =
    try
      val host = EndpointPreference.hostOf(address)
      Option(host).map(_.trim).filter(_.nonEmpty).map(h => (h, endpointPortOf(address).getOrElse(serverPort)))
    catch case _: Exception => None

  /** Port written in the candidate endpoint's authority (`http://h:8097` → 8097);
    * `None` when the candidate carries no (valid) port — [[resolveDialTarget]] then
    * falls back to `serverPort`. IPv6 literals (`[::1]:8097`) are handled).
    */
  private def endpointPortOf(address: String): Option[Int] =
    val stripped = address.replaceFirst("(?i)^https?://", "")
    val slashIdx = stripped.indexOf('/')
    val authority = if slashIdx >= 0 then stripped.substring(0, slashIdx) else stripped
    val hostPort = authority.substring(authority.lastIndexOf('@') + 1)
    if hostPort.startsWith("[") then
      val close = hostPort.indexOf(']')
      if close > 0 && hostPort.length > close + 1 && hostPort.charAt(close + 1) == ':' then
        hostPort.substring(close + 2).trim.toIntOption.filter(isValidPort)
      else None
    else
      val colonIdx = hostPort.indexOf(':')
      if colonIdx > 0 then hostPort.substring(colonIdx + 1).trim.toIntOption.filter(isValidPort)
      else None

  private def isValidPort(p: Int): Boolean = p > 0 && p <= 65535

  /** The `host:port` a dial attempt actually opened — the log-line read-out of
    * [[resolveDialTarget]] (F-4: never present the candidate string as if it were
    * the dial target). */
  private def dialTargetLabel(endpoint: String): String =
    resolveDialTarget(endpoint).map((h, p) => s"$h:$p").getOrElse("(unresolved)")

  /**
   * Build the WS URI with our device info as query params.
   *
   * F-1: `port` is the **dial target's** port (the peer's advertised port when the
   * candidate carries one). The query param `port` is a different thing and stays
   * `serverPort` — it tells the peer which port **we** listen on (正确行为, 零改动).
   */
  private def buildWsUri(host: String, port: Int, id: DeviceIdentity): String =
    val params = Map(
      "deviceId" -> id.deviceId,
      "deviceName" -> id.deviceName,
      "platform" -> id.platform,
      "capabilities" -> id.capabilities.asJson.noSpaces,
      "port" -> serverPort.toString
    )
    val query = params.map((k, v) => s"$k=${enc(v)}").mkString("&")
    s"ws://$host:$port/api/neblink/presence?$query"

  private def enc(s: String): String =
    try java.net.URLEncoder.encode(s, "UTF-8")
    catch case _: Exception => s
end NeblinkPresenceService

/**
 * F-D 的网络面参数（纯函数 + 常量，便于独立读数，不必构造整个服务）。
 *
 * 与 `NeblinkDiscovery.HeartbeatBackoffCap`（收归伴生对象的同款先例）一致：
 * 「spec 复制一份阈值来测副本」等于没钉住，所以数值与曲线都留在这里。
 */
object NeblinkPresenceService:

  /**
   * 心跳拍间隔与逾期阈值（hblife 批 2026-09-19：从 [[NeblinkPresenceService]] 的
   * `openConnection` 内联字面量提升为常量）。
   *
   * WHY 提升：缺陷判据是「到达间隔 ∈ 理论带」，而理论带 = `3 × 心跳拍 − ε`（第 2 拍恰
   * `2×5s = 10 000ms` 不触发 `> 10 000ms`，第 3 拍才触发）≈ 15s ⇒ 单连接自循环 ≈16.2s
   * （+ 重连握手，判词报告 §3.4）。验收 spec 必须引用**同一份**阈值，否则测的是副本
   * （F-D 批已定同款纪律：`reconnectDelayMs`/`ReconnectDelayCapMs` 也留在这里）。
   */
  val HeartbeatIntervalSec: Long = 5L

  /** 逾期阈值：`now - lastPong > 本值` ⇒ 判死（严格大于 ⇒ 恰 10 000ms 不触发）。 */
  val HeartbeatTimeoutMs: Long = 10_000L

  /**
   * 单连接自循环的理论周期（ms）= 首个满足 `k×拍间隔 + 握手时长 > 逾期阈值` 的拍号 k 的到达时刻。
   *
   * 逐字复算（`HeartbeatIntervalSec = 5s`、`HeartbeatTimeoutMs = 10 000ms`，判据是**严格大于**，
   * 拍子起点在**握手之后**而 `lastPong` 起点在**拨号开始处**）：
   *  - `handshake > 0`（一切真实握手）⇒ `h + 2×5000 > 10 000` 成立 ⇒ k = 2 ⇒
   *    **10 000ms + h**（本机环回 h ≈50ms ⇒ 实测 **10.05s** ≈ 5.7~6.0 次/分）；
   *  - `handshake = 0`（退化）⇒ k = 3 ⇒ 15 000ms。
   *  ⇒ 判词报告 §3.4 的「≈16.2s / 3.70 次/分」= k = 3 + 其公网重连握手 1.18s 的算术值，
   *  与代码实际口径差**一整拍**（起点取在连接建立处）；本批以**实测 10.05s** 为准，
   *  并在结果里做锚点对账（报告值 ÷ 实测值 = 1.61）。
   *
   * 🔴 阈值与公式留在伴生对象（同 `reconnectDelayMs` 先例）：验收 spec 引**同一份**公式，
   * 而不是把数抄进 spec（否则测的是副本）。
   */
  def singleConnectionSelfCycleMs(handshakeMs: Long): Long =
    val beat = HeartbeatIntervalSec * 1000L
    var k = 1
    while k * beat + handshakeMs <= HeartbeatTimeoutMs do k += 1
    k * beat + handshakeMs

  /**
   * 同步拍／心跳拍的下界 = `NeblinkConfig.syncIntervalSec` 的缺省值（45s；服务端
   * 自己文档化的心跳节奏，见 `NeblinkDiscovery.HeartbeatBackoffCap` 的依据）。
   * 拨号预算与重连梯延迟都以它为天花板：**任何一腿都不许跨拍**。
   */
  val SyncBeatFloorMs: Long = 45_000L

  /** 重连梯最大尝试次数（收手 ⇒ 交回同步拍，并复位死端点判定）。 */
  val ReconnectMaxAttempts: Int = 20

  /** 重连梯延迟封顶（≤ [[SyncBeatFloorMs]] ⇒ 梯子永不比拍子慢）。 */
  val ReconnectDelayCapMs: Long = 30_000L

  /** 重连梯：第 `attempt` 次尝试前的等待（0 = 立即）。曲线 0/1/2/4/8/16/30… 秒。 */
  def reconnectDelayMs(attempt: Int): Long =
    if attempt <= 0 then 0L
    else math.min(ReconnectDelayCapMs, 1_000L << math.min(attempt - 1, 20))

  /** 一轮拨号的时长上界（Σ 候选预算，受 `roundBudgetMs` 封顶）。 */
  def dialRoundUpperBoundMs(candidates: List[String], budget: DialBudget): Long =
    budget.roundUpperBoundMs(candidates)

  /** 梯子全部耗尽的时长上界（每轮拨号 + 每次退避）——收手日志的读数来源。 */
  def ladderDrainUpperBoundMs(attempts: Int, roundMs: Long): Long =
    (0 until attempts.max(0)).map(a => reconnectDelayMs(a) + roundMs).sum

/**
 * JDK WebSocket.Listener for outgoing presence connections.
 * Forwards events back to NeblinkPresenceService — stateless on its own.
 * Uses *Sync logging because callbacks run on JDK WS threads (no IO context).
 *
 * 🔴 `slot`（[[NeblinkPresenceService.ConnSlot]]）是本监听器的**身份**：它把关闭事件
 * 绑到「事件所属的那条 conn」上，而不是让服务端按 deviceId 反查 —— 陈旧连接的迟到
 * close/error 因此不可能被算到同 deviceId 的新连接头上（hblife 批 2026-09-19）。
 */
private final class PresenceWsListener(
  service: NeblinkPresenceService,
  peer: PeerInfo,
  slot: service.ConnSlot
) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.neblink.presence")

  override def onOpen(ws: WebSocket): Unit =
    ws.request(1)

  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    try
      val text = data.toString
      decode[Json](text) match
        case Right(json) =>
          json.hcursor.downField("type").as[String].getOrElse("") match
            case "pong" => service.updateLastPong(peer.deviceId)
            case "ping" =>
              // Respond to server-side heartbeat
              try ws.sendText("""{"type":"pong"}""", true)
              catch case _: Exception => ()
            case "data" =>
              // Forward data message to registered handlers
              val payload = json.hcursor.downField("payload").focus.getOrElse(Json.Null)
              service.onDataReceived(payload)
            case _ => ()
        case Left(_) => ()
    catch case _: Exception => ()
    end try
    ws.request(1)
    null

  end onText

  override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    service.onClosed(slot, peer)
    null

  override def onError(ws: WebSocket, error: Throwable): Unit =
    logger.debugSync(s"Presence WS error: ${peer.deviceName} - ${error.getMessage}")
    service.onClosed(slot, peer)
end PresenceWsListener
