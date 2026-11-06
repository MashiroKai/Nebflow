package nebflow.llm

import io.circe.Json
import nebflow.shared.NebflowLogger
import nebflow.shared.TokenUsage

import scala.collection.mutable

/**
 * 可归因观测（cache-warmup 批 2026-11-06，方案 §三 + 作者补充令 §三）。
 *
 * 目标：每次实际候选发送，在**最终 wire 装配点**（两适配器的 body/requestMessages）
 * 采指纹；usage 落账时与同 (session, provider, model) 域**上一发**比对，把 miss
 * 归因到明确类别；区分「证实客户端变了」与「客户端没观测到变化」（后者只标
 * unknown，**不谎称已证实驱逐**）。
 *
 * 纪律（方案 §三 + 补充令 §三.8 逐条对应）：
 *   · 本地会话盐摘要——指纹混入每进程随机盐，不落密钥、不落正文（只存
 *     hash/计数/位置/长度）；日志行同口径（只含类别/索引/计数/域身份）。
 *   · 有界——每域指纹窗口 2 发、域表封顶 1024、miss 环封顶 512；块指纹向量
 *     封顶 2048 块（超额只存总数）；哈希走 Json 结构折叠（**不做**全历史
 *     noSpaces 序列化，零 O(history) 内存放大）。
 *   · 口径——总率 = sum(cacheRead)/sum(input)，**不是**逐请求百分比平均
 *     （CacheAttributionSpec 判据⑦机械钉）；缺失 usage（None）单列
 *     UsageMissing，不进分母、不假装命中或免费；warmup 类账目与业务类
 *     **分列**（本批保温=shadow 零发送，字段先行，防未来混账）。
 *   · 归因类别（MissCategory）：NewSession / DomainSwitched（跨供应商或凭据域）/
 *     ConfigChanged（tools/system/thinking 指纹变化，标首个变化轴）/
 *     HistoryChanged（历史内容或顺序变化，标首个差异块位）/ CompactionRebuild
 *     （前部重建：首块变且变短）/ EphemeralMarked（断点落点超出持久区——旧装配
 *     缺陷签名）/ Unknown（指纹全同仍 miss ⇒ 供应商侧，只标记不断言）。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.llm.CacheAttributionSpec"`
 * 归属 CI：既有 `sbt test` 家族。
 */
object CacheAttribution:

  private val logger = NebflowLogger.forName("nebflow.llm.cache-attribution")
  @volatile private[llm] var attributionLogSink: String => Unit = logger.infoSync

  /** 每进程随机盐（指纹去内容化；跨实例不可比是特性不是缺陷）。 */
  private val salt: Long = scala.util.Random.nextLong()

  // ── 有界常量 ──────────────────────────────────────────────────────────
  private val DomainCap        = 1024 // (session, provider, model) 域表封顶
  private val MissRing         = 512  // 最近归因记录
  private val MaxHistoryBlocks = 2048 // 块指纹向量封顶（超额只记总数）

  /** 命中带阈值：read/input ≥ 0.5 记正常带（不做归因）；< 0.5 进归因。
    * 口径声明：这是观测阈值不是供应商契约（补充令一：不拿命中率当远端契约）。
    */
  private val MissThreshold = 0.5

  // ── 指纹结构 ──────────────────────────────────────────────────────────

  final case class WireFingerprint(
    face: String, // "anthropic" | "openai"
    providerId: String,
    model: String,
    sessionId: String,
    agentId: String,
    streaming: Boolean,
    thinkingFp: Long,
    toolsFp: Long,
    toolsCount: Int,
    systemFp: Long,
    systemChars: Long,
    historyBlockFps: Vector[Long], // 封顶 MaxHistoryBlocks
    historyBlocks: Int,            // 未封顶总数
    historyChars: Long,
    breakpointIdx: Int, // 持久区内的断点块位（-1 = 无）
    ephemeralBlocks: Int,
    ephemeralChars: Long,
    /** 缺陷签名：marker 漏进临时段（旧装配「断点钉在 H+R 的 R 上」的直接证据）。 */
    ephemeralMarked: Boolean,
    sentAtMs: Long
  )

  sealed trait MissCategory:
    def name: String

  case object NewSession        extends MissCategory { val name = "new-session" }
  case object DomainSwitched    extends MissCategory { val name = "domain-switched" }
  case object ConfigChanged     extends MissCategory { val name = "config-changed" }
  case object HistoryChanged    extends MissCategory { val name = "history-changed" }
  case object CompactionRebuild extends MissCategory { val name = "compaction-rebuild" }
  case object EphemeralMarked   extends MissCategory { val name = "ephemeral-marked" }
  case object UsageMissing      extends MissCategory { val name = "usage-missing" }
  case object Unknown           extends MissCategory { val name = "unknown" }

  /** 一条归因记录（无正文——只有类别/位置/计数/域身份）。 */
  final case class MissRecord(
    category: MissCategory,
    sessionId: String,
    providerId: String,
    model: String,
    firstDiffIdx: Option[Int],
    detail: String,
    inputTokens: Long,
    cacheReadTokens: Long,
    atMs: Long
  )

  /** token 账目（总率口径 = sum(read)/sum(input)）。 */
  final case class TokenTotals(
    requests: Long = 0L,
    inputTokens: Long = 0L,
    cacheRead: Long = 0L,
    cacheWrite: Long = 0L,
    outputTokens: Long = 0L
  ):
    def hitPct: Double = if inputTokens <= 0 then 0.0 else cacheRead * 100.0 / inputTokens
    def plus(u: TokenUsage): TokenTotals =
      TokenTotals(
        requests + 1,
        inputTokens + u.inputTokens.toLong,
        cacheRead + u.cacheReadTokens.getOrElse(0).toLong,
        cacheWrite + u.cacheWriteTokens.getOrElse(0).toLong,
        outputTokens + u.outputTokens.toLong
      )

  final case class Snapshot(
    business: TokenTotals,
    warmup: TokenTotals, // 保温请求单列——永不混入 business
    perDomain: Map[String, TokenTotals],
    missCounts: Map[String, Int],
    recentMisses: List[MissRecord]
  )

  // ── 状态（进程内有界环；重启清零——不复活过期观测）───────────────────

  /** 每域最近两发指纹（0=prev，1=cur；observeWire 先落 cur，usage 时比对）。 */
  private val recentByDomain = mutable.LinkedHashMap.empty[String, mutable.ArrayDeque[WireFingerprint]]
  private val missRing       = mutable.ArrayDeque.empty[MissRecord]
  private var businessTotals = TokenTotals()
  private var warmupTotals   = TokenTotals()
  private val domainTotals   = mutable.Map.empty[String, TokenTotals]
  private val missCounts     = mutable.Map.empty[String, Int]

  private def domainKey(sessionId: String, providerId: String, model: String): String =
    s"$sessionId|$providerId|$model"

  // ── 结构哈希（Json 折叠，无字符串物化；混盐）──────────────────────────

  private def hashString(s: String): Long =
    var h = 0xcbf29ce484222325L ^ salt
    var i = 0
    while i < s.length do
      h = (h ^ s.charAt(i).toLong) * 0x100000001b3L
      i += 1
    h

  private def mix(acc: Long, v: Long): Long = (acc ^ v) * 0x100000001b3L

  private def hashJson(j: Json): Long =
    j.fold(
      mix(0xcbf29ce484222325L, 1L),
      b => mix(0xcbf29ce484222325L, if b then 2L else 3L),
      n => mix(0xcbf29ce484222325L, hashString(n.toString)),
      s => mix(0xcbf29ce484222325L, hashString(s)),
      arr => arr.foldLeft(0xcbf29ce484222325L)((a, x) => mix(a, hashJson(x))),
      obj =>
        // cache_control 是缓存元数据——移动 ≠ 语义前缀变更（方案 §三），不进指纹
        obj.toList.foldLeft(0xcbf29ce484222325L) { case (a, (k, v)) =>
          if k == "cache_control" then a else mix(mix(a, hashString(k)), hashJson(v))
        }
    )

  /** 块序列：消息列表拍平成内容块（Anthropic/OpenAI 两面同构）。 */
  private def blocksOf(messages: List[Json]): Vector[Json] =
    messages.flatMap(m => m.hcursor.downField("content").focus.flatMap(_.asArray).getOrElse(Vector.empty)).toVector

  private def blockChars(blocks: Vector[Json]): Long =
    blocks.foldLeft(0L) { (acc, b) =>
      acc + b.hcursor.get[String]("text").toOption.map(_.length.toLong).getOrElse(0L) +
        b.hcursor.get[String]("content").toOption.map(_.length.toLong).getOrElse(0L)
    }

  // ── 采集点（最终 wire 装配点；sessionId 缺省的辅助面跳过）────────────

  /** @param face          协议面（"anthropic" | "openai"）
    * @param providerId    真实候选腿的 providerId（adapter 内部名是协议名，域分列以腿为准）
    * @param persistedWire 持久历史段的 wire 消息（断点应对它的尾块）
    * @param ephemeralWire 临时尾段（REQUEST-ONLY 提醒 + systemDynamic）的 wire 形态
    */
  def observeWire(
    face: String,
    params: SendMessageParams,
    providerId: String,
    persistedWire: List[Json],
    ephemeralWire: List[Json]
  ): Unit =
    params.sessionId match
      case None => () // 辅助面（probe/judge/allocator）无缓存连续性，零采集
      case Some(sid) =>
        val blocks        = blocksOf(persistedWire)
        val ephemeralBlk  = blocksOf(ephemeralWire)
        val breakpointIdx = blocks.lastIndexWhere(_.hcursor.downField("cache_control").focus.isDefined)
        val fp = WireFingerprint(
          face = face,
          providerId = providerId,
          model = params.model,
          sessionId = sid,
          agentId = params.agentId.getOrElse(""),
          streaming = false, // wire 形状与流式无关（stream 是 body 顶层键）；两面一致
          thinkingFp = params.thinking.filter(!_.isNull).map(hashJson).getOrElse(0L),
          toolsFp = params.tools.map(ts => hashJson(Json.fromValues(ts.map(t => Json.fromJsonObject(t.inputSchema))))).getOrElse(0L),
          toolsCount = params.tools.foldLeft(0)(_ + _.size),
          systemFp = params.systemStable.map(hashString).getOrElse(0L),
          systemChars = params.systemStable.map(_.length.toLong).getOrElse(0L),
          historyBlockFps = blocks.take(MaxHistoryBlocks).map(hashJson),
          historyBlocks = blocks.length,
          historyChars = blockChars(blocks),
          breakpointIdx = breakpointIdx,
          ephemeralBlocks = ephemeralBlk.length,
          ephemeralChars = blockChars(ephemeralBlk),
          ephemeralMarked = ephemeralBlk.exists(_.hcursor.downField("cache_control").focus.isDefined),
          sentAtMs = System.currentTimeMillis()
        )
        recordFingerprint(fp)

  private def recordFingerprint(fp: WireFingerprint): Unit = synchronized {
    val key = domainKey(fp.sessionId, fp.providerId, fp.model)
    val dq  = recentByDomain.getOrElseUpdate(key, mutable.ArrayDeque.empty[WireFingerprint])
    dq.addOne(fp)
    while dq.length > 2 do dq.removeHead() // 只留 prev/cur
    recentByDomain.remove(key)
    recentByDomain.update(key, dq) // LRU touch
    while recentByDomain.size > DomainCap do
      recentByDomain.remove(recentByDomain.keys.iterator.next())
  }

  /** usage 落账（与 CacheStats.record 同点位调用）。kind="business"|"warmup" 分列。
    * 返回归因记录（正常命中带/保温腿返回 None）。
    */
  def observeUsage(
    sessionId: String,
    providerId: String,
    model: String,
    usage: Option[TokenUsage],
    kind: String = "business",
    nowMs: Long = System.currentTimeMillis()
  ): Option[MissRecord] =
    val record: Option[MissRecord] = synchronized {
      val prevAndCur = recentByDomain.get(domainKey(sessionId, providerId, model))
      usage match
        case None =>
          if kind == "business" then
            Some(bump(MissRecord(UsageMissing, sessionId, providerId, model, None, "usage absent (failed request)", 0, 0, nowMs)))
          else None
        case Some(u) if kind == "warmup" => None // 保温账目只记账不归因（无业务前缀连续性）
        case Some(u) =>
          val ratio = if u.inputTokens <= 0 then 1.0 else u.cacheReadTokens.getOrElse(0).toDouble / u.inputTokens
          if ratio >= MissThreshold then None // 正常命中带
          else Some(classify(prevAndCur, sessionId, providerId, model, u, nowMs))
    }
    usage.foreach { u =>
      synchronized {
        if kind == "warmup" then warmupTotals = warmupTotals.plus(u)
        else
          businessTotals = businessTotals.plus(u)
          val dk = s"$providerId/$model"
          domainTotals.update(dk, domainTotals.getOrElse(dk, TokenTotals()).plus(u))
      }
    }
    record.foreach { r =>
      // observeUsage is a synchronous Unit API; NebflowLogger.info returns a lazy IO,
      // so discarding it silently suppressed every attribution event in production logs.
      attributionLogSink(
        s"cache-attribution: category=${r.category.name} session=${r.sessionId} domain=$providerId/$model " +
          s"firstDiff=${r.firstDiffIdx.getOrElse("-")} input=${r.inputTokens} read=${r.cacheReadTokens} detail=${r.detail}"
      )
    }
    record

  private def bump(r: MissRecord): MissRecord =
    missRing.addOne(r)
    while missRing.length > MissRing do missRing.removeHead()
    missCounts.update(r.category.name, missCounts.getOrElse(r.category.name, 0) + 1)
    r

  private def classify(
    prevAndCur: Option[mutable.ArrayDeque[WireFingerprint]],
    sessionId: String,
    providerId: String,
    model: String,
    u: TokenUsage,
    nowMs: Long
  ): MissRecord =
    val pair: Option[(WireFingerprint, WireFingerprint)] =
      prevAndCur.flatMap(dq => if dq.length >= 2 then Some((dq(0), dq(dq.length - 1))) else None)
    val (inTok, readTok) = (u.inputTokens.toLong, u.cacheReadTokens.getOrElse(0).toLong)
    pair match
      case None =>
        // 本域无上一发：同会话换域（failover/换凭据）⇒ DomainSwitched；否则新会话
        val switched = recentByDomain.keys.iterator.exists { k =>
          val seg      = k.split("\\|")
          val sameSess = seg.headOption.contains(sessionId)
          val otherDom = seg.length >= 3 && !(seg(1) == providerId && seg(2) == model)
          sameSess && otherDom
        }
        val cat = if switched then DomainSwitched else NewSession
        bump(MissRecord(cat, sessionId, providerId, model, None,
          if switched then "same session, different provider/model domain" else "no prior fingerprint in this cache domain",
          inTok, readTok, nowMs))
      case Some((p, cur)) =>
        // ① 历史缺陷签名：marker 漏进临时段（旧装配「断点钉在 H+R 的 R 上」直接证据），
        //    或持久历史非空却无断点（marker 丢失）。空历史（0 块）不判（新会话首轮前无历史可钉）。
        if cur.ephemeralMarked ||
          (cur.historyBlocks > 0 && (cur.breakpointIdx < 0 || cur.breakpointIdx != cur.historyBlocks - 1))
        then
          bump(MissRecord(EphemeralMarked, sessionId, providerId, model, Some(cur.breakpointIdx),
            s"breakpoint misplaced: persistedIdx=${cur.breakpointIdx} blocks=${cur.historyBlocks} ephemeralMarked=${cur.ephemeralMarked}",
            inTok, readTok, nowMs))
        // ② 配置面变化（tools/system/thinking 任一轴——首个变化轴即报）
        else if cur.toolsFp != p.toolsFp || cur.toolsCount != p.toolsCount then
          bump(MissRecord(ConfigChanged, sessionId, providerId, model, None, "tools schema/order fingerprint changed", inTok, readTok, nowMs))
        else if cur.systemFp != p.systemFp then
          bump(MissRecord(ConfigChanged, sessionId, providerId, model, None, "system fingerprint changed", inTok, readTok, nowMs))
        else if cur.thinkingFp != p.thinkingFp then
          bump(MissRecord(ConfigChanged, sessionId, providerId, model, None, "thinking config fingerprint changed", inTok, readTok, nowMs))
        // ③ 历史内容变化
        else
          val shared    = cur.historyBlockFps.zip(p.historyBlockFps).takeWhile { case (a, b) => a == b }.length
          val minBlocks = math.min(cur.historyBlocks, p.historyBlocks)
          val firstDiff = if shared < minBlocks then Some(shared) else None
          if firstDiff.isEmpty && cur.historyBlocks >= p.historyBlocks then
            // 纯追加 + 指纹全同仍 miss ⇒ 客户端未观测到变化（不谎称驱逐）
            bump(MissRecord(Unknown, sessionId, providerId, model, None,
              "no client-observed change; provider-side (not claimed as eviction)", inTok, readTok, nowMs))
          else if firstDiff.contains(0) && cur.historyBlocks < p.historyBlocks then
            bump(MissRecord(CompactionRebuild, sessionId, providerId, model, firstDiff,
              "front rebuilt and shrunk (compaction/lifecycle signature)", inTok, readTok, nowMs))
          else
            bump(MissRecord(HistoryChanged, sessionId, providerId, model, firstDiff,
              s"history content/order changed at block ${firstDiff.getOrElse(-1)} (persisted=${cur.historyBlocks} prev=${p.historyBlocks})",
              inTok, readTok, nowMs))

  // ── 读数面 ────────────────────────────────────────────────────────────

  def snapshot: Snapshot = synchronized {
    Snapshot(
      business = businessTotals,
      warmup = warmupTotals,
      perDomain = domainTotals.toMap,
      missCounts = missCounts.toMap,
      recentMisses = missRing.toList
    )
  }

  /** 测试钩子：清空进程内账目（真实引擎不调用）。 */
  private[llm] def resetForTest(): Unit = synchronized {
    recentByDomain.clear()
    missRing.clear()
    businessTotals = TokenTotals()
    warmupTotals = TokenTotals()
    domainTotals.clear()
    missCounts.clear()
  }

end CacheAttribution
