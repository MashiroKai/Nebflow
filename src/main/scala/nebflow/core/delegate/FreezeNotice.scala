package nebflow.core.delegate

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, FreezeDisplay, FreezeKind}
import nebflow.shared.{Defaults, NebflowLogger}

import java.util.concurrent.ConcurrentHashMap

/**
 * FreezeNotice —— 冻结的**通知策略 / 静默统计 / 自动续跑预算**单点。
 *
 * 作者令（2026-11-05 10:23 逐字「然后frozen这种可恢复的，不需要通知，」；同日 10:36
 * 细化逐字「不论是节点还是delegate，由于provider 故障，都不需要向上通知，冻结等待
 * 恢复就行。然后在面板显示，这个是需要用户去处理的，我们引擎要做的就行检测恢复了能
 * 接着工作。所以不需要向上汇报。因为provider 故障上级也基本死了，汇报给上级没用。」）。
 *
 * == 本件不造第二套判定 ==
 * 「这条冻结该怎么呈现」的单点是 `nebflow.actor.FreezeDisplay`（冻结态误报批已合入
 * 的单点，`FreezeKind × needsHuman × autoRecoverable × v 用户面措辞`）。本件只消费
 * 它，回答**另外三个问题**：
 *   ① **要不要向上发消息**（本件 `silent` / `escalate`）——provider 故障族一律"不发"；
 *   ② **一波怎么读**（[[Digest]]，面板统计行的数据源）；
 *   ③ **自动续跑预算用了几次、下次什么时候能再试**（[[AutoResume]] 台账）。
 *
 * == 政策（provider 故障 = 基础设施故障，不是任务失败）==
 *   - `delegate-frozen:llm-failure` / `cancelled-by-quota`（provider 故障族，
 *     `FreezeDisplay.isInfraReasonCode`）⇒ **零向上通知**：不投递父会话冻结回执、不进
 *     升级链、不扣任何重试/重启预算；引擎自己探测 provider 可用性，恢复即用既有唤醒腿
 *     续跑（同一会话、续在检查点），最多 [[Defaults.DelegateFreezeAutoResumeMax]] 次、
 *     间隔递增；**次数用尽**才升级成一条聚合的「需用户处理」告警。
 *   - `delegate-frozen:tool-failure` / `engine-fault`（确定性缺陷）⇒ 一条聚合告警
 *     （同波合并，绝不 N 条）。
 *   - actor 级 `loop` ⇒ 一条聚合告警；`schedule`/`llm-transient`/`network`/
 *     `provider-down`/`restart-recovery`/`waiting-network` ⇒ 静默（引擎自己退避/探测）。
 *
 * == 分层 ==
 * 只读 `DelegateRegistry` 记录、只用 `Defaults` / `FreezeDisplay`（actor 层）与
 * `AgentCommand`，**不 import agent 层**；投递与唤醒在 `nebflow.agent.FreezeAlertSweep`。
 */
object FreezeNotice:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.freeze-notice")

  // ── ① 分类：单一判据来自 FreezeDisplay（不另造表）────────────────────────

  /** 通知面分类：会不会惊动作者（呈现面分类另见 `FreezeDisplay`）。 */
  enum Tier:
    /** 静默：零向上消息（面板可见；引擎自己探测恢复后续跑）。 */
    case Silent

    /** 升级：同一波**一条**聚合告警（需人处置的确定性缺陷，或自动续跑次数用尽）。 */
    case Escalate

  def tierWire(t: Tier): String = t match
    case Tier.Silent => "silent"
    case Tier.Escalate => "escalate"

  /** 面板/回执用中文短名。 */
  def tierLabelZh(t: Tier): String = t match
    case Tier.Silent => "静默（面板可见）"
    case Tier.Escalate => "需你处理"

  /**
   * delegate 冻结原因 → 通知面分类（**枚举 × 分类 × 是否通知**表）：
   *
   *   | 原因码 | FreezeDisplay | 是否向上通知 |
   *   |---|---|---|
   *   | `llm-failure`        | 待你处理 · 可自动恢复 | **否**（本轮冻结超过 30 分钟时限 ⇒ 一条摘要告警）|
   *   | `cancelled-by-quota` | 待你处理 · 可自动恢复 | **否**（同上）|
   *   | `tool-failure`       | 需唤醒                | 是（一条聚合告警，立即）|
   *   | `engine-fault`       | 需唤醒                | 是（一条聚合告警，立即）|
   *
   * 注：`llm-failure` 现在是 provider 侧可恢复故障的**收容码**（鉴权/订阅 403、
   * 配额余额、上游 5xx 含 550、连接失败/超时、格式层拒绝含 `Input too long` 全归
   * 这里；判据表见 [[ProviderFault]]）——所以这一族的静默覆盖面正是作者报障的那
   * 一屏。
   */
  def tierOfReason(r: DelegateFreeze.Reason, detail: String = ""): Tier =
    val fd = FreezeDisplay.ofWire(s"${DelegateFreeze.WirePrefix}:${DelegateFreeze.wire(r)}")
    if fd.autoRecoverable then Tier.Silent else Tier.Escalate

  /** 线值 → 通知面分类（全量词表，actor 级族同表判读）。 */
  def tierOfLine(line: String, detail: String = ""): Tier =
    val fd = FreezeDisplay.ofWire(Option(line).getOrElse(""))
    if fd.autoRecoverable then Tier.Silent else Tier.Escalate

  /** 呈现面判定（面板/审计统一读这里，杜绝两套表）。 */
  def displayOf(line: String): FreezeDisplay = FreezeDisplay.ofWire(Option(line).getOrElse(""))

  // ── ①' 合并窗口的归并键 = 错误类型族（provider 冻结告警合并窗批 2026-11-05 19:08）──

  /** 确定性缺陷族的线值（= `ProviderFault.Family.DeterministicDefect`；单一真源）。 */
  val DeterministicFamily: String =
    ProviderFault.familyWire(ProviderFault.Family.DeterministicDefect)

  /** 未命中判据表的 provider 冻结的兜底类型（宁可给出「未知形状」桶，也不并进已知族）。 */
  val UnclassifiedProviderFamily: String = "provider-unclassified"

  /**
   * 一条冻结的**错误类型**（合并窗口的归并键）。
   *
   * 判据**不另写一份分类**：`ProviderFault.Table` 是唯一真源（`familyWire` 线值），
   * 本方法只做投影：
   *   - `Tier.Escalate`（真失败）⇒ [[DeterministicFamily]]；
   *   - 正文命中判据表 ⇒ 该族线值（`quota-balance` / `auth-subscription` /
   *     `upstream-5xx` / `connect-timeout` / `format-rejected` / `rate-limited`）；
   *   - 正文全未命中 ⇒ 原因码兜底：`cancelled-by-quota` 归 `quota-balance`（原因码本身
   *     就是额度族），其余 `llm-failure` 归 [[UnclassifiedProviderFamily]]。
   *
   * 安全方向：正文若同时像缺陷（判据表首行优先命中确定性缺陷），即使呈现面判为静默，
   * 本方法也返回 [[DeterministicFamily]] ⇒ `planAlerts` 把它**放进缺陷腿立即告警**
   * （宁可多报一条，不许把 bug 静音）。
   */
  def familyOfItem(i: Item): String =
    if i.tier == Tier.Escalate then DeterministicFamily
    else
      ProviderFault.familyOf(i.detail) match
        case Some(f) => ProviderFault.familyWire(f)
        case None =>
          if i.reason == DelegateFreeze.wireValue(DelegateFreeze.Reason.CancelledByQuota) then
            ProviderFault.familyWire(ProviderFault.Family.QuotaBalance)
          else UnclassifiedProviderFamily

  /** 是否并进合并窗口的 provider 族（确定性缺陷族不进窗口——它必须即时单独通知）。 */
  def inMergeWindow(i: Item): Boolean =
    i.tier == Tier.Silent && familyOfItem(i) != DeterministicFamily

  /** 该冻结是否属 provider 故障族（基础设施故障，不是任务失败）。 */
  def infraFamily(line: String): Boolean =
    val w = Option(line).getOrElse("")
    if !w.startsWith(DelegateFreeze.WirePrefix) then false
    else FreezeDisplay.isInfraReasonCode(w.stripPrefix(DelegateFreeze.WirePrefix).stripPrefix(":"))

  /** 是否静默（provider 故障族 = 零向上通知）。 */
  def silent(line: String, detail: String = ""): Boolean = tierOfLine(line, detail) == Tier.Silent

  // ── 解冻重放面：静默回执不许随解冻重放冒泡（修复批 2026-11-06）────────────
  //
  // 作者原话逐字（2026-11-06 09:43）：「不是说了 llm 不稳定 不需要向上通知吗 原地等待
  // 恢复就行了 搜一下我之前的要求吧 现在还是在给你报障」。
  //
  // 现场取证：作者看到的「一刷 15 条」不是投递腿造的，是**冻结窗期间累积在邮箱里**的
  // 回执在每次解冻时被原样重放——`MailboxDrain.resumeSweep` 把 taken 残差与 pending
  // 全取并注入，每条 `delegate/frozen` 都过 `visibleExternalEventSource` 变成一格
  // `delegate-frozen` 气泡、正文整段进父会话上下文（日志 `mailbox-drained-at-unfreeze
  // events=15 replayed=15` 即此）。静默总闸只关得住**投递**，关不住**重放**。
  //
  // 本段把重放面并入静默族：同一父会话同一错误族只留**一条**「等待恢复中」状态行，
  // 其余只落 INFO 计数（同因去重）。判据不另写一份——原因从回执首行
  // `hit **<Reason>**` 投影回 [[DelegateFreeze.Reason]]，分类走 [[tierOfReason]]。

  /** 冻结回执载荷标记（`DelegateFreeze.receiptText` 首行；重放面据此认件）。 */
  val ReceiptMarker: String = "[delegate-frozen]"

  /** 回执 payload → 原因（读首行 `hit **<Reason>**`；非回执 / 旧盘面 ⇒ None，零误判）。 */
  def receiptReasonOf(payload: String): Option[DelegateFreeze.Reason] =
    val head = Option(payload).getOrElse("").linesIterator.find(_.contains(ReceiptMarker)).getOrElse("")
    if head.isEmpty then None
    else DelegateFreeze.All.find(r => head.contains(s"**${DelegateFreeze.reasonLabelEn(r)}**"))

  /** 回执正文里 `WHY:` 之后的摘要（族判定读真实故障形状，不只读原因码）。 */
  private def receiptWhy(payload: String): String =
    Option(payload).toList.flatMap(_.split('\n')).find(_.startsWith("WHY:")).getOrElse("").take(400)

  /** 回执 payload → 错误类型族（与合并窗口同一个归并键；非回执 ⇒ None）。 */
  def receiptFamilyOf(payload: String): Option[String] =
    receiptReasonOf(payload).map { r =>
      val d = receiptWhy(payload)
      ProviderFault.familyOf(d) match
        case Some(f) => ProviderFault.familyWire(f)
        case None =>
          if tierOfReason(r, d) == Tier.Escalate then DeterministicFamily
          else if r == DelegateFreeze.Reason.CancelledByQuota then
            ProviderFault.familyWire(ProviderFault.Family.QuotaBalance)
          else UnclassifiedProviderFamily
    }

  /** 该回执是否静默族（provider 故障族 = 解开也不许向作者冒泡）。 */
  def receiptSilent(payload: String): Boolean =
    receiptReasonOf(payload).exists(r => tierOfReason(r, receiptWhy(payload)) == Tier.Silent)

  /** 静默回执的「原因人话」（状态行用）。 */
  def receiptReasonZh(payload: String): String =
    receiptReasonOf(payload).map(DelegateFreeze.reasonLabelZh).getOrElse("")

  /** 重放面认件：delegate × frozen × 静默族回执。 */
  def isSilentReceipt(e: AgentCommand.ExternalEvent): Boolean =
    e.source == "delegate" && e.eventType == "frozen" && receiptSilent(e.payload)

  /** 返回 (保留集, 被合并掉的静默回执数)。 */
  def collapseSilentReceipts(
    events: List[AgentCommand.ExternalEvent]
  ): (List[AgentCommand.ExternalEvent], Int) =
    val quietIdxs = events.zipWithIndex.collect { case (e, i) if isSilentReceipt(e) => i }
    if quietIdxs.isEmpty then (events, 0)
    else
      val quiet = quietIdxs.map(events(_))
      val gone = quietIdxs.toSet
      val groups: List[(String, List[AgentCommand.ExternalEvent])] =
        quiet.zipWithIndex
          .groupBy { case (e, _) => receiptFamilyOf(e.payload).getOrElse(UnclassifiedProviderFamily) }
          .toList
          .map { case (f, es) => (f, es.sortBy(_._2).map(_._1)) }
          .sortBy { case (_, es) => quiet.indexOf(es.head) }
      // 每个族一条状态行，落回该族**最后**一条的位置之后（相对序不乱）。
      val insertAfter: Map[Int, AgentCommand.ExternalEvent] = groups.flatMap { case (f, es) =>
        val at = quietIdxs(quiet.indexOf(es.head))
        Some(at -> collapsedReceipt(f, es))
      }.toMap
      val out = events.zipWithIndex.flatMap { case (e, i) =>
        val kept = if gone.contains(i) then Nil else List(e)
        insertAfter.get(i) match
          case Some(c) => List(c) ++ kept
          case None => kept
      }
      (out, quiet.size - groups.size)
  end collapseSilentReceipts

  /** 静默族回执的合并件：改派为 `frozen-waiting`（呈现面据此不逐个冒泡，视觉走「等待恢复中」）。 */
  private def collapsedReceipt(family: String, es: List[AgentCommand.ExternalEvent]): AgentCommand.ExternalEvent =
    val first = es.head
    val ids = es.flatMap(_.metadata("frozenDelegateId").flatMap(_.asString)).distinct
    val parents = es.flatMap(_.metadata("parentSessionId").flatMap(_.asString)).distinct
    first.copy(
      eventType = "frozen-waiting",
      payload = quietReceiptText(family, es),
      metadata = first.metadata
        .add("quietReceipt", Json.fromBoolean(true))
        .add("quietFamily", Json.fromString(family))
        .add("quietCount", Json.fromInt(es.size))
        .add("quietDelegateIds", Json.fromValues(ids.map(Json.fromString)))
        .deepMerge(if parents.isEmpty then JsonObject.empty else JsonObject("parentSessionId" -> parents.head.asJson)),
      correlationId = Some(s"frozen-waiting-$family-${ids.hashCode.abs}")
    )

  /**
   * 静默回执的状态行正文：一条说完「在等什么 / 谁 / 会不会自己好」。
   *
   * 措辞纪律（作者口径）：这是**状态**不是待办——不给唤醒地址、不要求动作；能自己恢复
   * 的事引擎自己做（退避重试 / 换供应商 / 恢复后续跑同一会话）。
   */
  def quietReceiptText(family: String, es: List[AgentCommand.ExternalEvent]): String =
    val zhs = es.map(e => receiptReasonZh(e.payload)).filter(_.nonEmpty).distinct
    val ids = es.flatMap(_.metadata("frozenDelegateId").flatMap(_.asString)).distinct
    val names = es.flatMap(_.metadata("description").flatMap(_.asString)).distinct.take(3)
    val label = if zhs.isEmpty then family else zhs.mkString(" / ")
    s"[frozen-waiting] ${es.size} 个委托等待恢复（$label · `$family`）：这不是失败，也不影响你——引擎会退避重试，" +
      s"供应商恢复后在原会话上接着跑（已完成的不重做）。涉及 ${ids.take(6).mkString(", ")}" +
      s"${if ids.size > 6 then s" 等 ${ids.size} 个" else ""}" +
      s"${if names.nonEmpty then s"（${names.mkString("；")}）" else ""}。同因只报这一条，状态见后台智能体面板。"

  // ── provider / 基础设施故障标记（通知面与 flow-notify 面共用同一把尺）──────

  /**
   * 这段失败正文是否属**基础设施故障**（provider / 网络 / 额度 / 鉴权 / 格式退回）。
   * flow-notify 闸（`DispatchNotify`）与冻结面共用本判据——单一尺子。
   *
   * provider 故障降噪批（2026-11-05 17:18 作者令）后，词表真源搬到
   * [[ProviderFault.Table]]（可测的判据表，逐族带裁定），本方法只做投影：**命中
   * 可恢复族且不属确定性缺陷**即「不是任务失败」。旧的本类私有词表一并删除——
   * 两套词表就是两套真相的发源地（旧表漏了 `550` 与 `Input too long`，正是
   * 作者报障那一屏里被当失败逐条通知的两类）。
   */
  def infraFault(detail: String): Boolean = ProviderFault.silent(detail)

  // ── ② 一波统计（静默面：面板就地看，零消息）──────────────────────────────

  /** 一条冻结记录的分类读数（digest/告警/自动续跑三腿共用）。 */
  final case class Item(
    delegateId: String,
    parentSessionId: String,
    title: String,
    reason: String,
    detail: String,
    tier: Tier,
    infra: Boolean,
    frozenSince: Long,
    ageMs: Long,
    workspace: String,
    autoResumes: Int,
    autoResumesExhausted: Boolean
  ):
    /** 面板统计行用的「已冻结（可唤醒）」判据。 */
    def wakeable: Boolean = true

  end Item

  /** 一波冻结的静默统计（面板统计行数据源）。 */
  final case class Digest(now: Long, windowMs: Long, items: List[Item]):
    def total: Int = items.size
    def silentCount: Int = items.count(_.tier == Tier.Silent)
    def escalateCount: Int = items.count(_.tier == Tier.Escalate)
    def infraCount: Int = items.count(_.infra)
    def oldestAgeMs: Long = if items.isEmpty then 0L else items.map(_.ageMs).max

  /**
   * 记录 → 一波统计项（纯函数）：只收**在册未归档**且带 delegate 冻结线值的记录；
   * 窗口 = `now - windowMs` 之后冻结的算同一波（`windowMs <= 0` = 全部）。
   */
  def itemsOf(records: List[DelegateRegistry.Record], now: Long, windowMs: Long): List[Item] =
    records
      .filter(r => r.frozenSince.isDefined && r.archivedAt.isEmpty)
      .filter(r => DelegateFreeze.isFreezeWire(r.frozenReason.getOrElse("")))
      .filter { r =>
        windowMs <= 0L || now - r.frozenSince.getOrElse(now) <= windowMs
      }
      .flatMap { r =>
        DelegateFreeze.parse(r.frozenReason.getOrElse("")).map { reason =>
          val detail = r.frozenDetail.getOrElse("")
          val since = r.frozenSince.getOrElse(now)
          val wire = DelegateFreeze.wire(reason)
          Item(
            delegateId = r.id,
            parentSessionId = r.parentSessionId,
            title = Option(r.title).getOrElse(""),
            reason = wire,
            detail = detail,
            tier = tierOfReason(reason, detail),
            infra = FreezeDisplay.isInfraReasonCode(wire) || infraFault(detail),
            frozenSince = since,
            ageMs = math.max(0L, now - since),
            workspace = Option(r.worktree.getOrElse(r.cwd)).getOrElse(""),
            autoResumes = autoResumesOf(r.id, since),
            autoResumesExhausted = autoResumesExhausted(r.id, since)
          )
        }
      }
      .sortBy(i => -i.frozenSince)

  /** 一波统计（缺省窗口 = [[Defaults.DelegateFreezeDigestWindowMs]]）。 */
  def digestOf(records: List[DelegateRegistry.Record], now: Long): Digest =
    Digest(
      now,
      Defaults.DelegateFreezeDigestWindowMs,
      itemsOf(records, now, Defaults.DelegateFreezeDigestWindowMs)
    )

  /**
   * **全量**冻结统计（窗口 = 0，含全部在冻项）：告警腿必须读这份。
   *
   * 合并窗批（2026-11-05 19:08）修的旧缺陷：告警腿此前读的是
   * [[digestOf]]（面板波窗 [[Defaults.DelegateFreezeDigestWindowMs]] = 10 分钟），于是
   * 「冻结超过 10 分钟」的条目直接从 digest 掉出去 —— 而升级判据恰好是「本轮冻结
   * ≥30 分钟」⇒ 两个窗口错配，30 分钟时限**永远触发不了**，超时冻结反而不告警。
   * 面板波窗（10 分钟，只影响「本波 N 条」读数）与告警口径（全量在冻集）从此分开。
   */
  def fullDigest(records: List[DelegateRegistry.Record], now: Long): Digest =
    Digest(now, 0L, itemsOf(records, now, 0L))

  /** 面板统计行载荷（WS `delegateFreezeDigest` 帧；快照同形态）。 */
  def digestJson(d: Digest): Json =
    Json.obj(
      "type" -> "delegateFreezeDigest".asJson,
      "total" -> d.total.asJson,
      "recoverable" -> d.infraCount.asJson,
      "needsHuman" -> (d.total - d.infraCount).asJson,
      "silent" -> d.silentCount.asJson,
      "escalate" -> d.escalateCount.asJson,
      "oldestAgeMs" -> d.oldestAgeMs.asJson,
      "windowMs" -> d.windowMs.asJson,
      "items" -> d.items.map { i =>
        Json.obj(
          "sessionId" -> i.delegateId.asJson,
          "parentSessionId" -> i.parentSessionId.asJson,
          "reason" -> i.reason.asJson,
          "family" -> familyOfItem(i).asJson,
          "tier" -> tierWire(i.tier).asJson,
          "tierLabel" -> tierLabelZh(i.tier).asJson,
          "infra" -> i.infra.asJson,
          "frozenSince" -> i.frozenSince.asJson,
          "ageMs" -> i.ageMs.asJson,
          "autoResumes" -> i.autoResumes.asJson,
          "autoResumesExhausted" -> i.autoResumesExhausted.asJson,
          "wakeAddress" -> s"delegate:${i.delegateId}".asJson
        )
      }.asJson
    )

  // ── ③ 自动续跑预算（provider 故障族的引擎自愈台账）──────────────────────

  /** 一个冻结点的自动续跑台账（键 = `delegateId#episodeStart`：同一轮冻结 = 同一预算）。 */
  final case class AutoResume(attempts: Int, lastAt: Long)

  private val autoResumes = new ConcurrentHashMap[String, AutoResume]()

  /**
   * 冻结**轮次（episode）台账**：`delegateId → 本轮冻结起点`（provider 故障降噪批
   * 2026-11-05）。
   *
   * 为什么不是 `frozenSince`：自动续跑会唤醒会话，若 provider 其实还没好，子会话会
   * 很快再冻一次 ⇒ `frozenSince` 刷新 ⇒ 计数与时限**双双归零**，「30 分钟才升级」
   * 永远到不了、退避也永远从 60s 重新开始（这正是「无限重试」形状）。轮次台账把
   * 一串「冻→续→又冻」当**同一轮**：起点只认第一次，直到该 delegate 不再处于冻结
   * 态才清账（[[endEpisodes]]，由扫描拍按当前冻结集收口）。
   */
  private val episodes = new ConcurrentHashMap[String, java.lang.Long]()

  /** 本轮冻结起点（首次见到该 delegate 冻结的时刻；幂等）。 */
  def episodeOf(delegateId: String, frozenSince: Long): Long =
    episodes.computeIfAbsent(delegateId, _ => java.lang.Long.valueOf(frozenSince)).longValue()

  /** 本轮冻结已持续多久（ms）——「长时间仍不可恢复」的判据材料。 */
  def episodeAgeMs(delegateId: String, frozenSince: Long, now: Long): Long =
    math.max(0L, now - episodeOf(delegateId, frozenSince))

  /**
   * 轮次收口：不再处于冻结态的 delegate 清账（恢复/唤醒后下一次冻结算新的轮次）。
   * 由 `FreezeAlertSweep` 每拍调用，fail-soft（台账只增不减会误伤后来的冻结）。
   */
  def endEpisodes(activeIds: Set[String]): Unit =
    episodes.keySet().removeIf(id => !activeIds.contains(id))

  private def autoKey(delegateId: String, frozenSince: Long): String =
    s"$delegateId#${episodeOf(delegateId, frozenSince)}"

  def autoResumesOf(delegateId: String, frozenSince: Long): Int =
    Option(autoResumes.get(autoKey(delegateId, frozenSince))).map(_.attempts).getOrElse(0)

  /**
   * 次数是否达到「面板读数」意义上的上限（缺省 [[Defaults.DelegateFreezeAutoResumeMax]]）：
   * **只作面板读数与告警文案**，不再是续跑闸——续跑一直按封顶间隔进行到恢复或升级
   * 时限（provider 故障降噪批：把「3 次即停」改成「时间到了才升级」）。
   */
  def autoResumesExhausted(delegateId: String, frozenSince: Long): Boolean =
    val max = Defaults.DelegateFreezeAutoResumeMax
    max > 0 && autoResumesOf(delegateId, frozenSince) >= max

  /**
   * 是否到「升级」时限（纯函数）：本轮冻结（[[episodeAgeMs]]，跨「冻→续→又冻」累计）
   * 超过 [[Defaults.DelegateFreezeEscalateAfterMs]]（缺省 30 分钟）⇒ 才允许把这一族
   * 升级成一条摘要通知。作者令：「如果长时间（给出你选的上限，例如 30 分钟）仍不可
   * 恢复，才升级为一条摘要式通知」。
   */
  def escalateDue(delegateId: String, frozenSince: Long, now: Long): Boolean =
    val horizon = Defaults.DelegateFreezeEscalateAfterMs
    horizon <= 0L || episodeAgeMs(delegateId, frozenSince, now) >= horizon

  /**
   * 自动续跑是否到期（纯函数）：已试 `attempts` 次后，下一次尝试的最早时刻 =
   * `frozenSince + afterMs + min(base × 2^(attempts-1), backoffMax)`（attempts=0 ⇒
   * 只等 afterMs；之后每次翻倍，**到 [[Defaults.DelegateFreezeAutoResumeBackoffMaxMs]]
   * 封顶**，此后恒按封顶间隔）。间隔从冻结起点起算（不是从上次尝试起算）——同一份
   * 台账即可判定，不需要额外存上次时刻。
   *
   * 退避曲线（缺省 after=60s、base=60s、cap=300s）的实际尝试时刻：
   * `+60s → +120s → +180s → +300s → +360s → +420s → …`（每次 +5min 封顶）——
   * 指数起步、迅速封顶，**不是固定间隔死循环**（固定间隔会让 provider 恢复瞬间
   * 撞上一串同时唤醒；指数退避把压力摊平）。
   */
  def autoResumeDue(frozenSince: Long, now: Long, attempts: Int): Boolean =
    val after = Defaults.DelegateFreezeAutoResumeAfterMs
    val base = Defaults.DelegateFreezeAutoResumeBackoffMs
    val cap = Defaults.DelegateFreezeAutoResumeBackoffMaxMs
    val grow = if attempts <= 0 then 0L else base * (1L << math.min(attempts - 1, 16))
    val gap = after + (if cap > 0L then math.min(grow, cap) else grow)
    now - frozenSince >= gap

  /** 记一次自动续跑尝试（返回累计次数）。 */
  def recordAutoResume(delegateId: String, frozenSince: Long, now: Long): Int =
    val key = autoKey(delegateId, frozenSince)
    autoResumes
      .merge(key, AutoResume(1, now), (a, b) => AutoResume(a.attempts + b.attempts, now))
      .attempts

  /** 台账复位（测试/复盘）。 */
  def resetAutoResumes(): Unit = autoResumes.clear()

  // ── ④ 合并窗口（同类型只汇报一条）────────────────────────────────────────

  /**
   * 一个**错误类型**在合并窗口内的累计台账（面板/汇报读数，零 IO）。
   *
   * `seen` = 窗内见过、**当前仍在冻结**的 delegate（值 = 其冻结时刻）；
   * `recovered` = 窗内见过、**已自动恢复**的 delegate（值 = 观测到恢复的时刻）。
   * 两者合计 = 本窗受影响的会话数（汇报里「影响的会话数」）。
   */
  final case class FamilyWindow(
    family: String,
    openedAt: Long,
    firstFrozenAt: Long,
    lastFrozenAt: Long,
    seen: Map[String, Long],
    recovered: Map[String, Long],
    seq: Int = 0
  ):
    /** 本窗影响的会话数（在冻 + 已恢复，去重）。 */
    def affected: Int = (seen.keySet ++ recovered.keySet).size

    /** 仍冻结（= 仍需人工）的会话数。 */
    def stillFrozen: Int = seen.size

    /** 窗内已自动恢复的会话数。 */
    def recoveredCount: Int = recovered.size

    /** 时间跨度（首次冻结 → 末次冻结）。 */
    def spanMs: Long = math.max(0L, lastFrozenAt - firstFrozenAt)

    /** 是否仍需人工（还有在冻的）。 */
    def needsHuman: Boolean = seen.nonEmpty

  end FamilyWindow

  private val windows = new ConcurrentHashMap[String, FamilyWindow]()

  /** 某类型的当前窗口台账（无 = None）。 */
  def windowOf(family: String): Option[FamilyWindow] = Option(windows.get(family))

  /**
   * 每拍累积窗口台账（纯内存，零通知）：按类型把当前在冻项记进 `seen`，把「上一拍还在、
   * 这一拍已不在冻」的记进 `recovered`（= 引擎自动续跑成功 / 人或上游自愈）。
   *
   * 归并单位是**类型**而不是单个冻结点：同一类型在窗内出现多少次，都只累计成一行读数。
   * 某类型当前一条在冻都没有 ⇒ 台账收摊（不涨内存，也不留幽灵读数）。
   */
  def trackWave(items: List[Item], now: Long): Unit =
    val grouped = items.filter(inMergeWindow).groupBy(familyOfItem)
    grouped.foreach { case (fam, its) =>
      val active = its.map(_.delegateId).toSet
      val firsts = its.map(_.frozenSince)
      windows.compute(
        fam,
        (_, prev) =>
          val p = Option(prev).getOrElse(
            FamilyWindow(fam, now, firsts.min, firsts.max, Map.empty, Map.empty)
          )
          val gone = p.seen.collect { case (id, _) if !active.contains(id) => id -> now }
          val still = p.seen.filter { case (id, _) => active.contains(id) }
          p.copy(
            firstFrozenAt = math.min(p.firstFrozenAt, firsts.min),
            lastFrozenAt = math.max(p.lastFrozenAt, firsts.max),
            // 又冻回来的（自动续跑后再次冻结）不再算「已恢复」。
            recovered = (p.recovered ++ gone) -- active,
            seen = still ++ its.map(i => i.delegateId -> i.frozenSince)
          )
      )
    }
    val live = grouped.keySet
    windows.keySet().removeIf(f => !live.contains(f))

  end trackWave

  /**
   * 窗口滚动（**tumbling，非滑动**）：一条类型汇报发出后，把该类型的窗口台账重开在
   * 「此刻」，`seen` 只带当前仍在冻的项（它们跨窗延续，汇报里因此能读出「这一轮已经
   * 冻了多久」），`recovered` 清零（下一窗重新计恢复数）。
   *
   * 为什么是 tumbling 而不是滑动窗：滑动窗（事件进窗就重置起点）在**持续不断**的同类型
   * 冻结下永远凑不满窗 ⇒ 该类型的汇报永远推迟（读者最需要它的时候它不说话）；事件窗
   * （每 N 条一报）把节奏绑在流量上 ⇒ 抖动期刷屏。tumbling 给出**确定性下限**：同一类型
   * 两次汇报间隔恒 ≥ 合并窗（缺省 30 分钟），且到点必报（只要还在冻）。
   */
  def rollWindow(family: String, now: Long, items: List[Item]): Unit =
    val its = items.filter(i => inMergeWindow(i) && familyOfItem(i) == family)
    if its.isEmpty then windows.remove(family)
    else
      val firsts = its.map(_.frozenSince)
      windows.put(
        family,
        FamilyWindow(
          family = family,
          openedAt = now,
          firstFrozenAt = firsts.min,
          lastFrozenAt = firsts.max,
          seen = its.map(i => i.delegateId -> i.frozenSince).toMap,
          recovered = Map.empty,
          seq = Option(windows.get(family)).map(_.seq + 1).getOrElse(1)
        )
      )
    end if
  end rollWindow

  // ── ⑤ 聚合告警（需人处理面；同类型一条）────────────────────────────────────

  /**
   * 一条聚合告警 = **一个错误类型**（[[Alert.family]]）在当前窗口内的一批冻结项。
   *
   * 归并单位是类型而不是父会话（provider 冻结告警合并窗批 2026-11-05 19:08 作者令
   * 「把同类型的错误合并后一起汇报，不要分开汇报」）：同类型错误只有一个处置动作
   * （充值 / 换链 / 等上游自愈），按父会话拆开就是把一个事件拆成 N 条消息。
   */
  final case class Alert(
    parentSessionId: String,
    family: String,
    items: List[Item],
    window: FamilyWindow
  ):
    def ids: List[String] = items.map(_.delegateId).sorted

    /** 确定性缺陷（真失败）：不进合并窗，即时通知。 */
    def deterministic: Boolean = family == DeterministicFamily

    /**
     * 去重键／窗口键。
     *   - provider 族：**只含类型**——窗内新来的同类冻结只并入同一条汇报（键不变 ⇒
     *     不加发）；这正是旧键（父会话 + id 集）的病根：每多冻一条，键就变，于是一条
     *     一条地发。
     *   - 确定性缺陷：含父会话 + id 集——新出现的缺陷立即成为新键 ⇒ **永不被吞报**
     *     （同一批重复只由冷却窗压住，不是由窗口归并压住）。
     */
    def key: String =
      if deterministic then s"freeze-defect:$parentSessionId:${ids.mkString(",")}"
      else s"freeze-family:$family"

    /** 该条告警的重发冷却 = 合并窗（provider 族）／同批去重冷却（缺陷族）。 */
    def rearmMs: Long =
      if deterministic then Defaults.DelegateFreezeAlertReArmMs
      else Defaults.DelegateFreezeDigestMergeWindowMs

    /**
     * 邮箱条目身份（`SessionMailbox.mailOnce` 按它在**在途未读**时去重）：
     *   - provider 族 = 类型 + **本窗起点**——同一窗内的重复认领只落一条；窗滚过后起点
     *     变了 ⇒ 新的一窗可以再落一条（否则第一窗那条一直在途，永远挡住后续窗）。
     *   - 缺陷族 = 类型 + id 集——同批不重发，新缺陷（id 变了）立刻落新条目。
     */
    def identity: String =
      if deterministic then s"freeze-alert-$family-${ids.mkString("-")}"
      else s"freeze-digest-$family-${window.seq}-${window.openedAt}"

  end Alert

  /**
   * 告警计划（纯函数）：
   *   - **缺陷腿**（`tool-failure` / `engine-fault`，或正文判为确定性缺陷的静默条）⇒
   *     **立即**进告警，按父会话聚合，同批一条；不受 30 分钟时限与合并窗约束。
   *   - **provider 腿**（可恢复族）⇒ 只有该类型里至少一条**本轮冻结超过升级时限**
   *     （[[escalateDue]]，缺省 30 分钟）才成计划；一类型 = 一条，窗内同类**全部并入**
   *     （含尚未到时限的同类型新冻结——它们属于同一个事件，不该另发一条）。
   */
  def planAlerts(d: Digest): List[Alert] =
    val (defectish, providerish) =
      d.items.partition(i => i.tier == Tier.Escalate || familyOfItem(i) == DeterministicFamily)
    val defectAlerts =
      defectish
        .groupBy(_.parentSessionId)
        .toList
        .sortBy(_._1)
        .map { case (parent, items) =>
          Alert(
            parent,
            DeterministicFamily,
            items,
            windowOf(DeterministicFamily).getOrElse(emptyWindow(DeterministicFamily, d.now))
          )
        }
    val dueFamilies =
      providerish
        .filter(i => escalateDue(i.delegateId, i.frozenSince, d.now))
        .map(familyOfItem)
        .distinct
        .sorted
    val providerAlerts =
      providerish
        .filter(i => dueFamilies.contains(familyOfItem(i)))
        .groupBy(familyOfItem)
        .toList
        .sortBy(_._1)
        .map { case (fam, items) =>
          val anchor = items.minBy(_.frozenSince) // 最早冻住的那个父会话收这条汇总
          Alert(anchor.parentSessionId, fam, items, windowOf(fam).getOrElse(deriveWindow(fam, items, d.now)))
        }
    defectAlerts ++ providerAlerts

  end planAlerts

  /** 台账缺位时的窗口读数（进程刚起/刚清理，按当前项推一份，保证汇报字段不缺）。 */
  private def deriveWindow(family: String, items: List[Item], now: Long): FamilyWindow =
    if items.isEmpty then emptyWindow(family, now)
    else
      val firsts = items.map(_.frozenSince)
      FamilyWindow(family, now, firsts.min, firsts.max, items.map(i => i.delegateId -> i.frozenSince).toMap, Map.empty)

  private def emptyWindow(family: String, now: Long): FamilyWindow =
    FamilyWindow(family, now, now, now, Map.empty, Map.empty)

  /** 告警正文（按腿分流：确定性缺陷 = 即时单条；provider 族 = 该类型一条摘要）。 */
  def alertText(alert: Alert): String =
    if alert.deterministic then defectText(alert) else familyDigestText(alert)

  private def clockOf(ms: Long): String =
    java.time.Instant
      .ofEpochMilli(math.max(0L, ms))
      .atZone(java.time.ZoneId.systemDefault())
      .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

  private def spanOf(ms: Long): String =
    val s = math.max(0L, ms) / 1000L
    if s < 60L then s"$s 秒" else s"${s / 60L} 分 ${s % 60L} 秒"

  /** 一条**类型摘要**（provider 合并窗的产出物）：类型/会话数/时间跨度/已恢复/仍待人工。 */
  private def familyDigestText(alert: Alert): String =
    val w = alert.window
    val horizonMin = Defaults.DelegateFreezeEscalateAfterMs / 60000L
    val windowMin = Defaults.DelegateFreezeDigestMergeWindowMs / 60000L
    val byId = alert.items.map(i => i.delegateId -> i).toMap
    val frozenIds = w.seen.keys.toList.sorted
    val recoveredIds = w.recovered.keys.toList.sorted
    val sample = alert.items.map(_.detail).find(_.nonEmpty).getOrElse("")
    val wake = alert.items.take(6).map(i => s"`delegate:${i.delegateId}`").mkString(" / ")
    val more = if alert.items.size > 6 then s"（另有 ${alert.items.size - 6} 条在面板行上）" else ""
    val recoverLine =
      if recoveredIds.isEmpty then "- 已自动恢复：0（本窗内无恢复）"
      else
        val each = recoveredIds
          .take(6)
          .map { id =>
            w.recovered.get(id).map(t => s"$id@${clockOf(t)}").getOrElse(id)
          }
          .mkString(", ")
        val tail = if recoveredIds.size > 6 then s" 等 ${recoveredIds.size} 条" else ""
        s"- 已自动恢复：${recoveredIds.size}（$each$tail）——引擎自愈已把它们唤醒续跑，不需要你动手"
    val stillLine =
      if frozenIds.isEmpty then "- 仍待人工：0（该类型已全部恢复）"
      else
        val each = frozenIds
          .take(8)
          .map { id =>
            val ageMin = byId.get(id).map(i => math.max(0L, (w.lastFrozenAt - i.frozenSince) / 60000L)).getOrElse(0L)
            s"$id（已冻 ${ageMin} 分钟）"
          }
          .mkString(", ")
        val tail = if frozenIds.size > 8 then s" 等 ${frozenIds.size} 条" else ""
        s"- 仍待人工：${frozenIds.size}（$each$tail）——供应商仍未服务：充值 / 换模型 / 取消"
    s"""[freeze-digest] provider 故障类型 `${alert.family}` 合并汇报（本窗唯一一条）
       |- 类型：`${alert.family}` —— 归并键 = 错误类型族（同类型的全部冻结合并进这一条，不逐条发）
       |- 影响的会话数：${w.affected}（仍冻 ${w.stillFrozen} / 已恢复 ${w.recoveredCount}）
       |- 时间跨度：${clockOf(w.firstFrozenAt)} → ${clockOf(w.lastFrozenAt)}（${spanOf(w.spanMs)}）
       |$recoverLine
       |$stillLine
       |- 示例：${
        if sample.nonEmpty then DelegateFreeze.compactDetail(sample, 200) else "（无附带正文）"
      }
       |
       |Wake one: $wake$more — the 唤醒 button on the background-agents panel, or mail the address (it continues in the SAME session; finished work is not redone, and waking never spends the supervisor restart budget).
       |Quiet by design: a provider-failure freeze mails you nothing at all while it is younger than the ${horizonMin}-minute horizon (the engine itself probes the provider and resumes the SAME session on exponential backoff with a capped gap). Past that horizon the freezes of **one error family** are merged into this single digest, and this family speaks at most once per ${windowMin}-minute merge window — a new freeze of the same family joins THIS message instead of adding another one.
       |
       |【provider 冻结 · 同类型合并汇报】类型 `${alert.family}`：受影响 ${w.affected} 个会话（仍冻 ${w.stillFrozen}、已自动恢复 ${w.recoveredCount}），时间跨度 ${clockOf(
        w.firstFrozenAt
      )} → ${clockOf(w.lastFrozenAt)}（${spanOf(
        w.spanMs
      )}）。超过 ${horizonMin} 分钟时限才汇报，且同类型每 ${windowMin} 分钟合并窗内只发这一条：窗内新增的同类冻结并入本条，不同类各发一条，确定性缺陷（代码 bug / 越权 / 数据损坏）不受本窗约束、即时单独通知。唤醒：面板「唤醒」按钮或给上列地址发 Mail（原会话上下文继续，已完成的不重做，不消耗重启预算）。""".stripMargin

  end familyDigestText

  /** 一条**确定性缺陷**告警（真失败：即时、不受时限与合并窗约束）。 */
  private def defectText(alert: Alert): String =
    val n = alert.items.size
    val horizonMin = Defaults.DelegateFreezeEscalateAfterMs / 60000L
    val causeLine = alert.items
      .groupBy(i => (i.reason, i.tier))
      .toList
      .sortBy { case ((r, t), _) => (tierWire(t), r) }
      .map { case ((reason, tier), items) =>
        val ids = items.map(_.delegateId).mkString(", ")
        val sample = items.headOption.map(_.detail).getOrElse("")
        s"- ${tierWire(tier)} `${reason}` ×${items.size} ($ids)" +
          s"${if sample.nonEmpty then s": ${DelegateFreeze.compactDetail(sample, 160)}" else ""}"
      }
      .mkString("\n")
    val wake = alert.items.take(6).map(i => s"`delegate:${i.delegateId}`").mkString(" / ")
    val more = if n > 6 then s" (+${n - 6} more on the panel)" else ""
    s"""[freeze-alert] $n frozen delegate(s) stopped on a deterministic defect
       |$causeLine
       |
       |Wake one: $wake$more — the 唤醒 button on the background-agents panel, or mail the address (it continues in the SAME session; finished work is not redone, and waking never spends the supervisor restart budget).
       |This is one aggregated alert for this batch of defects. A deterministic defect (code bug / permission breach / data corruption) is a REAL failure: it is never merged into the provider merge window and never delayed by the ${horizonMin}-minute provider horizon — every new defect notifies immediately.
       |
       |【确定性缺陷告警】$n 条 delegate 停在确定性缺陷上（真失败：代码 bug / 越权 / 数据损坏）。本批合并成这一条；新出现的缺陷即时另发，不受 provider 合并窗（${Defaults.DelegateFreezeDigestMergeWindowMs / 60000L} 分钟）与 ${horizonMin} 分钟时限约束。原因：$causeLine。唤醒：面板「唤醒」按钮或给上列地址发 Mail（原会话上下文继续，已完成的不重做，不消耗重启预算）。""".stripMargin

  end defectText

  /** 聚合告警事件（入父会话持久邮箱；`eventType=freeze-alert`，不是终态）。 */
  def alertEvent(alert: Alert): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "freeze-alert",
      payload = alertText(alert),
      metadata = io.circe.JsonObject(
        "freezeAlert" -> true.asJson,
        "family" -> alert.family.asJson,
        "merged" -> (!alert.deterministic).asJson,
        "count" -> alert.items.size.asJson,
        "affectedSessions" -> alert.window.affected.asJson,
        "stillFrozen" -> alert.window.stillFrozen.asJson,
        "autoRecovered" -> alert.window.recoveredCount.asJson,
        "spanMs" -> alert.window.spanMs.asJson,
        "firstFrozenAt" -> alert.window.firstFrozenAt.asJson,
        "lastFrozenAt" -> alert.window.lastFrozenAt.asJson,
        "needsHuman" -> alert.window.needsHuman.asJson,
        "deterministicDefects" -> alert.items.count(_.tier == Tier.Escalate).asJson,
        "autoResumeExhausted" -> alert.items.count(_.autoResumesExhausted).asJson,
        "delegateIds" -> alert.ids.asJson,
        "reasons" -> alert.items.map(_.reason).distinct.asJson,
        "wakeAddresses" -> alert.ids.map(id => s"delegate:$id").asJson
      ),
      correlationId = Some(s"freeze-alert-${alert.family}-${alert.ids.mkString("-")}")
    )

  // ── ⑥ 告警去重（进程内台账；重启后重发一次 = 无害）────────────────────────

  private val alertLedger = new ConcurrentHashMap[String, java.lang.Long]()

  /**
   * 认领一次告警发送权（无 IO 副作用）：同键在 `windowMs` 内只放行一次。
   *
   * `windowMs` 由 [[Alert.rearmMs]] 给：provider 族 = 合并窗
   * （[[Defaults.DelegateFreezeDigestMergeWindowMs]]，缺省 30 分钟 ⇒ 同类型每 30 分钟
   * 最多一条，tumbling 滚动）；缺陷族 = [[Defaults.DelegateFreezeAlertReArmMs]]
   * （同批不重复，但新缺陷的键含 id 集 ⇒ 立刻放行）。30s 扫描拍因此不会把同一茬
   * 重复推成 N 条。
   */
  def claimAlert(key: String, now: Long, windowMs: Long = Defaults.DelegateFreezeAlertReArmMs): Boolean =
    val prev = alertLedger.get(key)
    if prev != null && now - prev < windowMs then false
    else
      alertLedger.put(key, now)
      true

  /** 测试/复盘复位（进程内四份台账）。 */
  def resetLedger(): Unit =
    alertLedger.clear()
    autoResumes.clear()
    episodes.clear()
    windows.clear()

end FreezeNotice
