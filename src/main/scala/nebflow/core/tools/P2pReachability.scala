package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*

import java.net.URI

/**
 * P2P 候选端点可达性判据 —— 「砍探测税」实施批（作者令 2026-11-04 10:43）。
 *
 * == 病灶（审计已定案，见 `20261104_103020_local-acceleration-audit.md` §4.2） ==
 *
 * 改前每一次设备腿下发（`RemoteExecutor.p2pExecuteCandidates`）都**串行**挨个拨
 * 候选端点：Tailscale 网段一个、局域网一个，各付满一个 connect 超时
 * （实测 SYN 黑洞 ⇒ 3 000 ms + 1 500 ms = 4.5 s），拨完才回落 relay。而「负证据」
 * 只活 60 s（`P2pPathDecision.NegativeCacheTtlMs`）⇒ **每过一分钟就重付一次**。
 * 一个 5 MiB 文件的浏览取件腿有 1 次目录探针 + 10 块 = 11 次下发，本可付 11 次税。
 *
 * == 本文件的判据（两件事，都只做一次） ==
 *
 *   1. **并发探测 + 首胜即放行**（[[P2pReachability.probeConcurrently]]）：所有候选同时拨，
 *      第一个拨通的立即放行并取消其余 ⇒ 一次探测的总成本 = 单端点预算（有界），
 *      不是「候选数 × 预算」。
 *   2. **负缓存带指数退避**（[[P2pReachability]]）：一次真实失败写入负证据，窗口从
 *      [[P2pProbePolicy.NegativeTtlBaseMs]] 起按连续失败次数翻倍、封顶
 *      [[P2pProbePolicy.NegativeTtlMaxMs]] ⇒ 已知不可达的设备在窗口内**零成本旁路**，
 *      窗口到期后只做**一次**半开探测。
 *
 * == 失效条件（负证据不是永久判决） ==
 *
 *   - **地址集变化**：负证据按 `P2pPathDecision.addressKey(peer)` 记账，地址集一変
 *     ⇒ 判据不命中，下一次照常探测（与既有路径记忆同一条轴）。
 *   - **任何一次成功**：探测通 / 真实下发成功 ⇒ `noteSuccess` 立刻清账。
 *   - **显式作废**：`invalidate(deviceId)`（配对变更、设备重连等调用方主动清）。
 *
 * 零新协议、零新 wire 面：探测只用一次**有界的 HTTP GET**去既有只读端点 `/api/health`
 * （看到任何 HTTP 应答即算在线）——不是裸 TCP connect，理由是实测踩到的假阳性：
 * 本机透明代理会把任意地址的 TCP 连接就地「接通」，裸 connect 探针会把死端点判成活的
 * （详见 `httpReachable` 的注释）。
 */
object P2pProbePolicy:

  /** 负证据首窗（与既有 `P2pPathDecision.NegativeCacheTtlMs` 同值：改前是常量，改后是起点）。 */
  val NegativeTtlBaseMs: Long = 60_000L

  /** 负证据封顶窗：已知不可达的端点最多每 15 分钟被摸一次。 */
  val NegativeTtlMaxMs: Long = 15L * 60 * 1000

  /**
   * 单端点探测预算。取 1 200 ms 的理由：同网段 RTT 是 1 ms 量级、Tailscale 直连
   * 探活是 100 ms 量级，1 200 ms 足以覆盖「慢但在」的端点，同时把「不在就放弃」的
   * 成本钉在有界值（改前是 3 000 ms + 1 500 ms 串行累加）。
   */
  val ProbeConnectTimeoutMs: Int = 1_200

  /**
   * 连续失败 k 次的负证据窗口（纯函数，spec 直测）。
   *
   * `k = 1 ⇒ 60 s`，之后每次翻倍，封顶 15 min：1 → 2 → 4 → 8 → 15 → 15 min …
   * 语义：**偶发抖动**（失败 1–2 次）很快恢复探测；**长期不可达**（Tailscale 未起、
   * 对端不在同一网段）每小时只付 4 次探测 × 单端点预算。
   */
  def negativeWindowMs(consecutiveFailures: Int): Long =
    if consecutiveFailures <= 0 then 0L
    else
      val shift = math.min(consecutiveFailures - 1, 16)
      math.min(NegativeTtlBaseMs * (1L << shift), NegativeTtlMaxMs)

  /** 从端点字符串取探测目标 `host:port`（缺端口 ⇒ 与既有无端口地址同判 `6138`）。 */
  def probeTarget(endpoint: String): Either[String, (String, Int)] =
    if endpoint.trim.isEmpty then Left("empty endpoint")
    else
      val uri = URI.create(if endpoint.contains("://") then endpoint else s"http://$endpoint")
      Option(uri.getHost).filter(_.nonEmpty) match
        case None => Left(s"endpoint has no host: $endpoint")
        case Some(h) => Right((h, if uri.getPort > 0 then uri.getPort else 6138))

end P2pProbePolicy

object P2pReachability:

  /** 负证据条目：最近一次真实失败的时刻 + 连续失败次数（窗口由 [[P2pProbePolicy.negativeWindowMs]] 派生）。 */
  final case class Failure(atMs: Long, consecutive: Int, addressKey: String)

  /**
   * 一次端点解析的结论（判据与读数一处出，禁两处漂移）：
   *
   *   - `endpoint`：本次可下发的端点（`None` ⇒ 无 P2P 可用，调用方直接走 relay）；
   *   - `probed`：本次真拨了几个候选（0 = 走了记忆/负缓存，零成本）；
   *   - `probeMs`：探测实际耗时（0 = 没探）；
   *   - `negativeHit`：是否命中负缓存（日志/取数用）；
   *   - `probeFailed`：探了但全不通（本次写入了新的负证据）。
   */
  final case class Decision(
    endpoint: Option[String],
    probed: Int,
    probeMs: Long,
    negativeHit: Boolean,
    probeFailed: Boolean
  )

  object Decision:
    val none: Decision = Decision(None, 0, 0L, negativeHit = false, probeFailed = false)

  /**
   * 并发探测：**所有候选同时拨，第一个拨通者即放行**，其余立刻取消。
   *
   * 成本上界 = 单端点预算（不是候选数 × 预算）；最坏情况（全不通）= 一次
   * `perEndpointTimeoutMs`。空候选 ⇒ `None`（调用方按既有语义报错）。
   * 单候选也走这条腿（不特判）：一次有界的探测换来「下次零成本」的负证据，
   * 比让真实下发去撞 3 000 ms 的 connect 超时更便宜，也更早拿到「不可达」的事实。
   */
  def probeConcurrently(endpoints: List[String], perEndpointTimeoutMs: Int): IO[Option[String]] =
    val eps = endpoints.map(_.trim).filter(_.nonEmpty).distinct
    if eps.isEmpty then IO.pure(None)
    else
      val budget = math.max(1, perEndpointTimeoutMs)
      IO.deferred[String].flatMap { won =>
        def racer(ep: String): IO[Unit] =
          httpReachable(ep, budget).flatMap { ok =>
            if ok then won.complete(ep).void else IO.unit
          }
        // 全部同时拨：`parTraverse_` 在「全部结束」或「首个成功者取消其余」时收口。
        //
        // 两臂都必须以 `won.tryGet` 收口——这是本判据唯一一个「同刻竞争」的坑：
        // 最后一个探测者「先填 won、再返回」与「全部结束」几乎同时发生，若把
        // 「全部结束」臂无条件读成 `None`，一次**成功**的探测会被读成「全不通」
        // （实测踩到：stub 对端可达却连重试 4 轮）。带 `tryGet` 兜底后两臂同义。
        val allProbes = eps.parTraverse_(racer)
        val firstWin = won.get.map(Some(_))
        val allDone = allProbes.flatMap(_ => won.tryGet)
        IO.race(firstWin, allDone).map(_.merge)
      }

    end if

  end probeConcurrently

  /**
   * 单端点在活性探测 = **一次有界的 HTTP GET**（`/api/health`，既有只读端点；看到任何
   * HTTP 应答即算在线，含 4xx —— 应答本身就证明对端在服务）。
   *
   * 🔴 为什么不是裸 TCP connect（本批实测教训，2026-11-04）：本机装了透明代理/TUN，
   * `java.net.Socket.connect` 对**任意**地址（含 RFC 5737 TEST-NET-3 与两台死端点）
   * 都在 0–12 ms 内返回「已连接」，而同一地址的 HTTP 请求要等到超时。裸 connect 在这种
   * 环境下是**假阳性机器**——按它判活会把死端点判成活的，税一分不少还多付一跳。
   * 应用层探针（HTTP）把「TCP 被本机代理接走」与「对端真的在服务」分开，代价是一次
   * 小请求（`/api/health` 应答 2.7 KB）。
   */
  private def httpReachable(endpoint: String, timeoutMs: Int): IO[Boolean] =
    IO.blocking {
      P2pProbePolicy.probeTarget(endpoint) match
        case Left(_) => false
        case Right((host, port)) =>
          val client = java.net.http.HttpClient
            .newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(timeoutMs.toLong))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build()
          try
            val req = java.net.http.HttpRequest
              .newBuilder(java.net.URI.create(s"http://$host:$port/api/health"))
              .timeout(java.time.Duration.ofMillis(timeoutMs.toLong))
              .GET()
              .build()
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
            // 任何 HTTP 状态码都算「端点在服务」。
            resp.statusCode() > 0
          catch case _: Throwable => false
    }.handleError(_ => false)

  // ---- 进程内单例账本（与 `RemoteExecutor` 的路径记忆同生命周期：进程重启即清） ----

  private val ledger = new P2pReachability

  /** 进程内账本（`RemoteExecutor` 消费；spec 可 new 独立实例直测）。 */
  def current: P2pReachability = ledger

end P2pReachability

/**
 * 负证据账本（设备 × 地址集 → 失败计数与窗口）。线程安全：`ConcurrentHashMap` +
 * `compute`，读写都在一次原子操作里完成（禁「读-改-写」三拍）。
 */
final class P2pReachability:

  private val failures = new java.util.concurrent.ConcurrentHashMap[String, P2pReachability.Failure]()
  private val probeCount = new java.util.concurrent.atomic.AtomicLong(0L)
  private val skipCount = new java.util.concurrent.atomic.AtomicLong(0L)

  /** 负证据剩余窗口（ms）；`None` = 无负证据或已过期（\>0 才旁路）。 */
  def negativeRemainingMs(deviceId: String, addressKey: String, nowMs: Long): Option[Long] =
    Option(failures.get(deviceId)) match
      case Some(f) if f.addressKey == addressKey =>
        val until = f.atMs + P2pProbePolicy.negativeWindowMs(f.consecutive)
        val left = until - nowMs
        // 到期即清（半开探测前先落账，避免「过期条目」被反复读成命中）。
        if left > 0 then Some(left)
        else
          failures.remove(deviceId, f)
          None
      case Some(f) =>
        // 地址集变了 ⇒ 旧判决失效（负证据不可跨地址集复用）。
        failures.remove(deviceId, f)
        None
      case None => None

  /** 是否应当旁路 P2P（零成本走 relay）。纯判据，无副作用（计数走 [[noteSkip]]）。 */
  def shouldSkip(deviceId: String, addressKey: String, nowMs: Long): Boolean =
    negativeRemainingMs(deviceId, addressKey, nowMs).isDefined

  /** 记一次旁路（读数面）。 */
  def noteSkip(): Unit =
    skipCount.incrementAndGet(); ()

  /** 记一次真实失败（探测全不通 / 连接级下发失败）；返回累计连续失败次数。 */
  def noteFailure(deviceId: String, addressKey: String, nowMs: Long): Int =
    val entry: P2pReachability.Failure = failures.compute(
      deviceId,
      (_, prev) =>
        Option(prev) match
          case Some(f) if f.addressKey == addressKey => P2pReachability.Failure(nowMs, f.consecutive + 1, addressKey)
          case _ => P2pReachability.Failure(nowMs, 1, addressKey)
    )
    entry.consecutive

  /** 记一次成功（探测通 / 真实下发成功）：立刻清账。 */
  def noteSuccess(deviceId: String): Unit =
    failures.remove(deviceId)
    ()

  /** 显式作废（配对变更、设备重连、手动刷新）。 */
  def invalidate(deviceId: String): Unit =
    failures.remove(deviceId)
    ()

  /** 读数面（报告取数用）：当前负证据条目数 / 累计探测次数 / 累计旁路次数。 */
  def stats: (Int, Long, Long) = (failures.size, probeCount.get(), skipCount.get())

  /** 记一次探测（供读数面；`RemoteExecutor` 每次真拨前调用）。 */
  def noteProbeAttempt(): Unit =
    probeCount.incrementAndGet(); ()
end P2pReachability
