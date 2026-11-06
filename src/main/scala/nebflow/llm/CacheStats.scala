package nebflow.llm

import nebflow.shared.{NebflowLogger, TokenUsage}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
 * 供应商侧 prompt-cache 命中率聚合读数（cache-hit 批 2026-11-06）。
 *
 * 为什么要有这个面（作者令 2026-11-06「缓存命中率必须做成可观测读数，防未来
 * 改动无感打碎」）：批前实测（stand 日志 1574 单、202.7M input tokens）命中率
 * 只有 **4.83%**——根因是请求 wire 上 cache_control 断点只钉了 system 稳定段 +
 * 末尾 tool schema（≈13k tokens），会话历史（中位 ≈119k）结构性不可缓存（详见
 * [[nebflow.llm.providers.AnthropicAdapter]] 消息尾断点批注）。修完断点后若无
 * 聚合读数，未来任何改 prompt 组装面的批（折叠/摘要/注入/换腿）都可能无感打碎
 * 前缀。本对象 = **日志聚合即可**（作者原话）的落点：两个 adapter 在 usage 定格
 * 点调 [[record]]，每 200 单或 15 分钟落一条 INFO 聚合行，不新增定时器、不接面板。
 *
 * 口径（与供应商面板可直接对照）：
 *   - cacheRead  = Anthropic `cache_read_input_tokens` ≡ OpenAI
 *     `prompt_tokens_details.cached_tokens` ≡ 面板「缓存命中 tokens」；
 *   - cacheWrite = Anthropic `cache_creation_input_tokens`（部分端点不上报 → 0/缺省）；
 *   - hit% = cacheRead / inputTokens，分母 = 本请求**总** input（含被缓存读回的
 *     部分）——即面板「缓存命中率」的同一分母。
 *   - **failover 换 provider/model = 换缓存域（物理性，不算回归）**：域切换在
 *     domains 分列里直接可见（同一 model 两行 provider = 换域了）。
 */
object CacheStats:

  private val logger = NebflowLogger.forName("nebflow.llm.cache-stats")

  /** 每 N 个请求至少落一条聚合行（与时间窗双闸，先到先触发）。 */
  private[llm] val EmitEveryRequests = 200L

  /** 空闲兜底窗：距上一条聚合行（或首单——冷启动锚）超过此时长且有新请求即落行（默认 15 分钟）。 */
  private[llm] val EmitWindowMs = 15L * 60 * 1000L

  /** 单域/总量的不可变快照（`hitPct` 即命中率读数，供测试与聚合行复用）。 */
  final case class DomainSnapshot(requests: Long, inputTokens: Long, cacheRead: Long, cacheWrite: Long):
    def hitPct: Double = if inputTokens <= 0L then 0.0 else cacheRead * 100.0 / inputTokens

  private final class Domain:
    val requests    = new AtomicLong(0L)
    val inputTokens = new AtomicLong(0L)
    val cacheRead   = new AtomicLong(0L)
    val cacheWrite  = new AtomicLong(0L)
    def snap: DomainSnapshot =
      DomainSnapshot(requests.get, inputTokens.get, cacheRead.get, cacheWrite.get)

  private val total   = new Domain
  private val domains = new ConcurrentHashMap[String, Domain]()

  // 聚合行发射状态（只在 lock 内读写）。
  private val lock                  = new Object
  private var lastEmitAtMs: Long    = 0L
  private var lastEmittedRequests: Long = 0L
  private var windowCheckpoint: DomainSnapshot = DomainSnapshot(0L, 0L, 0L, 0L)

  /** 时钟注入点（spec 模拟时间窗专用；生产路径恒 = 系统时钟。范围只许这一处。） */
  private[llm] var clockMs: () => Long = () => System.currentTimeMillis()

  /** usage 定格点调用（两协议 adapter 各自的最终 usage 解析处）。无 usage 的失败请求不入分母。 */
  def record(providerId: String, model: String, usage: Option[TokenUsage]): Unit = usage match
    case None => ()
    case Some(u) =>
      val key = s"$providerId/$model"
      val d   = domains.computeIfAbsent(key, _ => new Domain)
      d.requests.incrementAndGet()
      d.inputTokens.addAndGet(u.inputTokens.toLong)
      d.cacheRead.addAndGet(u.cacheReadTokens.getOrElse(0).toLong)
      d.cacheWrite.addAndGet(u.cacheWriteTokens.getOrElse(0).toLong)
      total.requests.incrementAndGet()
      total.inputTokens.addAndGet(u.inputTokens.toLong)
      total.cacheRead.addAndGet(u.cacheReadTokens.getOrElse(0).toLong)
      total.cacheWrite.addAndGet(u.cacheWriteTokens.getOrElse(0).toLong)
      maybeEmit()

  /**
   * 聚合行发射闸：满 [[EmitEveryRequests]] 或距上行超 [[EmitWindowMs]]（且有新
   * 单）即落一条 INFO 行。`force = true` 供测试/手动打点，绕过双闸。
   */
  def maybeEmit(force: Boolean = false): Unit = lock.synchronized {
    val now = clockMs()
    val n   = total.requests.get()
    // 冷启动锚（2026-11-06 小修批）：lastEmitAtMs 初始 0L 时时间窗腿带
    // `lastEmitAtMs > 0L` 前置 ⇒ 首次发射前永不激活 → 实例生命周期内总请求
    // <200（低峰期/刚重启）一条聚合行都不发，可观测面自哑。修法：首单落账时
    // 把窗口锚在当下（窗口从首单起算），时间窗腿冷启动期即可触发——语义 =
    // 「实例有流量后 15 分钟内必见第一条聚合行」。200 单腿与快照/域分列不变。
    if lastEmitAtMs == 0L && n > 0L then lastEmitAtMs = now
    val due =
      force || n - lastEmittedRequests >= EmitEveryRequests ||
        (lastEmitAtMs > 0L && n > lastEmittedRequests && now - lastEmitAtMs >= EmitWindowMs)
    if !due then ()
    else
      val cum = total.snap
      val win = DomainSnapshot(
        requests = cum.requests - windowCheckpoint.requests,
        inputTokens = cum.inputTokens - windowCheckpoint.inputTokens,
        cacheRead = cum.cacheRead - windowCheckpoint.cacheRead,
        cacheWrite = cum.cacheWrite - windowCheckpoint.cacheWrite
      )
      val top = domains.asScala.toList
        .sortBy { case (_, d) => -d.requests.get }
        .take(3)
        .map { case (k, d) => s"$k n=${d.requests.get} hit=${fmt1(d.snap.hitPct)}%" }
        .mkString(" | ")
      logger.infoSync(
        s"cache-stats: window(n=${win.requests}) input=${compact(win.inputTokens)} " +
          s"read=${compact(win.cacheRead)} write=${compact(win.cacheWrite)} hit=${fmt1(win.hitPct)}% | " +
          s"cumulative(n=${cum.requests}) input=${compact(cum.inputTokens)} read=${compact(cum.cacheRead)} " +
          s"hit=${fmt1(cum.hitPct)}% | domains: $top"
      )
      lastEmitAtMs        = now
      lastEmittedRequests = n
      windowCheckpoint    = cum
  }

  def totalSnapshot: DomainSnapshot = total.snap
  def domainSnapshots: Map[String, DomainSnapshot] =
    domains.asScala.toList.map { case (k, d) => k -> d.snap }.toMap

  private[llm] def resetForTest(): Unit = lock.synchronized {
    total.requests.set(0L); total.inputTokens.set(0L); total.cacheRead.set(0L); total.cacheWrite.set(0L)
    domains.clear()
    lastEmitAtMs = 0L; lastEmittedRequests = 0L; windowCheckpoint = DomainSnapshot(0L, 0L, 0L, 0L)
  }

  /** 1,234,567 → 1.2M（聚合行人类可读；不追求无损，精确值在 snapshot 面）。 */
  private def compact(v: Long): String =
    if v >= 1_000_000L then f"${v / 1_000_000.0}%.1fM"
    else if v >= 1_000L then f"${v / 1_000.0}%.1fk"
    else v.toString

  private def fmt1(v: Double): String = f"$v%.1f"
