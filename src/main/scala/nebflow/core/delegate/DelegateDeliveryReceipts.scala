package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.AtomicJson
import nebflow.core.tools.BgNotifyDelivery
import nebflow.shared.{Defaults, NebflowLogger, PathUtil}

/**
 * DelegateDeliveryReceipts —— delegate 终态交付回执 + 「父未确认」重投监测
 * （诊断修复批 2026-10-24 · delegate-nebflow-52308ee4 完成结果投递丢失）.
 *
 * == 本例形态 ==
 * 终态 ExternalEvent 的 `ref !` 是 mailbox offer，**从不失败**——监督者发出即
 * 视为已交付，但父会话是否真把结果注入了自己的回合，此前零确认、零监测。
 * 52308ee4 实态：delegate_report(finish) 已落盘、终文完整、会话 Idle、barrier
 * 读数 0/0——一切「正常」，父会话却永远没收到结果，静默 70+ 分钟直到作者肉眼
 * 发现。barrier/held 读数**只数子代理结果**，对终态投递失明——回执是本形态的
 * 可机检读数：回执存在且超龄未 ack 精确等于「delegate 终态已发、父未确认」。
 *
 * == 三段 ==
 *   - **写**：`BackoffSupervisor.notifyParentAndStop` 终态直投（含 parentRef=None
 *     的 F2 持久兜底腿）后落回执——payload 全量随存，重投无损；
 *   - **确认**：父会话 `AgentIdle` 把 delegate 终态事件（source=delegate ×
 *     completed/failed/cancelled × correlationId=delegateId）实际注入 LLM 回合
 *     的直投腿/批次汇聚腿 ack（注入即确认——LLM 消费失败属父会话内部事务，
 *     引擎职责到「入回合」为止）；
 *   - **重投**：`TaskStuckWatcher.scan` 尾部 sweep（MergeWindowCare 同款搭拍，
 *     零新增定时器）——超龄未确认 ⇒ `deliverToRootOrInbox`（父活直投 / 父死
 *     持久收件箱，correlationId=`delivery-redelivery-<id>` 幂等），每次重投
 *     刷新 deliveredAt 重新起 grace 钟（= 重投节拍）；父会话已删 ⇒ 删回执 +
 *     响亮 WARN（终态 payload 仍在 delegate 会话 transcript，可人工取回）；
 *     重投达 [[MaxRedeliveries]] ⇒ 停投留痕等人裁决。已确认回执超 24h 由 sweep
 *     顺手收殓（防目录无限增长）。
 *
 * 分层纪律：本件 ∈ core.delegate，重投经 [[BgNotifyDelivery.deliverToRootOrInbox]]
 * （core.tools，MergeWindowCare 升级腿同款）——不 import agent 层；消费挂点
 * agent 层经 FQN 调本件（MergeWindowCare 先例）。
 */
object DelegateDeliveryReceipts:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.delivery-receipts")

  /** 终态事件词表（与 BackoffSupervisor.notifyParentAndStop 的 eventType 同串）。 */
  val TerminalEventTypes: Set[String] = Set("completed", "failed", "cancelled")

  /** 重投事件的 correlationId 前缀（[[handleOne]] Redeliver 腿的改写形态）。 */
  val RedeliveryCorrelationPrefix: String = "delivery-redelivery-"

  /**
   * 回执身份解析（纯函数，#886 判红锚）：事件 → 应 ack 的回执 id。
   *   - 重投件（correlationId=`delivery-redelivery-<id>` 改写形态）⇒ **原回执 id**
   *     （metadata `redeliveryOf` 优先、前缀剥离次之）——重投注入若按改写 id 去
   *     find，查无回执恒 no-op，原回执永挂未确认 ⇒ sweep 每 grace 拍重投一次
   *     直至上限（#886 实证：canvas ×3 / console-card ×2 的重复到达链）；
   *   - 普通件 ⇒ correlationId 原值；
   *   - 事件须为 delegate 终态（source × eventType 词表闸），id 过 [[validId]]。
   *     解析不出合法 id ⇒ None（ackEvent no-op，既有宽容面）。
   */
  private[delegate] def ackableReceiptId(event: AgentCommand.ExternalEvent): Option[String] =
    if !(event.source == "delegate" && TerminalEventTypes.contains(event.eventType)) then None
    else
      val fromMeta = event.metadata("redeliveryOf").flatMap(_.asString).filter(validId)
      val fromPrefix = event.correlationId
        .collect {
          case c if c.startsWith(RedeliveryCorrelationPrefix) =>
            c.stripPrefix(RedeliveryCorrelationPrefix)
        }
        .filter(validId)
      fromMeta.orElse(fromPrefix).orElse(event.correlationId.filter(validId))

  /**
   * 父会话注入腿挂点（AgentIdle 直投/批次腿 + AgentFinishTurn turn 边界 drain 腿）：
   * 事件匹配 delegate 终态（source=delegate × 终态词表 × 合法 correlationId）⇒
   * ack（幂等零写）。注入即确认——LLM 消费成败属父会话内部事务，引擎职责到
   * 「入回合」为止。重投件按 [[ackableReceiptId]] 归位原回执（#886）。
   */
  def ackEvent(event: AgentCommand.ExternalEvent): IO[Unit] =
    ackableReceiptId(event).fold(IO.unit)(id => ack(id).void)

  /** 同一回执的重投上限——达上限停投留痕（等人裁决，绝不无限轰炸）。 */
  val MaxRedeliveries: Int = 3

  /** 已确认回执的收殓龄（sweep 顺手 GC；引擎常态量，无 prop 面）。 */
  private val AckedRetentionMs: Long = 24 * 60 * 60 * 1000L

  /**
   * 交付回执（`<dataRoot>/delegates/<id>.receipt.json`，与 DelegateRegistry 记录
   * 同目录不同名——零删除纪律只适用于 registry 记录，回执生命周期由 ack/收殓管）。
   */
  final case class Receipt(
    delegateId: String,
    parentSessionId: String,
    /** completed | failed | cancelled（notifyParentAndStop 的 eventType 原值）。 */
    eventType: String,
    /** 终态 payload 全量——重投无损（不存摘要）。 */
    payload: String,
    metadata: JsonObject,
    /** 最近一发投递时刻（重投时刷新 = 重投节拍钟重起）。 */
    deliveredAt: Long,
    /** 父会话注入终态事件的确认为 Some（AgentIdle ack）。 */
    ackedAt: Option[Long] = None,
    /** 已发重投次数（不含首投）。 */
    redeliveries: Int = 0
  )

  object Receipt:
    given io.circe.Codec[Receipt] = io.circe.generic.semiauto.deriveCodec
  end Receipt

  private def dir: os.Path = PathUtil.dataRoot / "delegates"

  private def fileOf(id: String): os.Path = dir / s"$id.receipt.json"

  /** id 白名单与 DelegateRegistry 同串（防路径穿越）。 */
  def validId(id: String): Boolean = DelegateRegistry.validId(id)

  private val writeLock = new Object

  private def put(r: Receipt): Unit =
    os.makeDir.all(dir)
    AtomicJson.writeSync(fileOf(r.delegateId), r.asJson.noSpaces)

  /** 终态直投成功后的回执落盘（覆盖写——重投刷新 deliveredAt/redeliveries）。 */
  def record(receipt: Receipt): IO[Unit] =
    IO.blocking(writeLock.synchronized(put(receipt)))
      .handleErrorWith(e =>
        logger.warn(s"[delegate-delivery] receipt write failed for ${receipt.delegateId}: ${e.getMessage}")
      )

  /**
   * 父会话注入 delegate 终态事件时的确认（AgentIdle 直投腿/批次腿挂点）。
   * 已 ack / 查无回执 = false（幂等，重复确认零写）。
   */
  def ack(delegateId: String, now: Long = System.currentTimeMillis()): IO[Boolean] =
    IO.blocking(
      writeLock.synchronized {
        find(delegateId) match
          case Some(r) if r.ackedAt.isEmpty =>
            put(r.copy(ackedAt = Some(now)))
            true
          case _ => false
      }
    ).handleErrorWith(e => logger.warn(s"[delegate-delivery] ack failed for $delegateId: ${e.getMessage}").as(false))

  /** 持久查找（每次现读；坏形/缺失 → None，绝不抛）。 */
  def find(id: String): Option[Receipt] =
    if !validId(id) then None
    else
      try
        val p = fileOf(id)
        if !os.exists(p) then None
        else io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Receipt].toOption)
      catch case _: Throwable => None

  /** 全量列出（receipt 文件专用后缀；坏文件跳过）。 */
  def list(): List[Receipt] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(p => p.last.endsWith(".receipt.json"))
          .flatMap(p => io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Receipt].toOption))
          .toList
    catch case _: Throwable => Nil

  def delete(id: String): IO[Unit] =
    IO.blocking(writeLock.synchronized {
      val p = fileOf(id)
      if os.exists(p) then os.remove(p)
    }).handleErrorWith(e => logger.warn(s"[delegate-delivery] receipt delete failed for $id: ${e.getMessage}"))

  /**
   * 重投判据（纯函数，spec 判红锚）：超龄未确认——`ackedAt` 缺席且
   * `now - deliveredAt > graceMs`。已 ack / 未超龄 ⇒ false。
   * 钉死本例形态：barrier 清零但父未收到 ⇒ 本函数对首投回执判 true。
   */
  private[delegate] def needsRedelivery(r: Receipt, now: Long, graceMs: Long): Boolean =
    r.ackedAt.isEmpty && now - r.deliveredAt > graceMs

  /** sweep 汇总（boot/扫描日志 + 测试断言面）。 */
  final case class SweepReport(
    redelivered: List[String] = Nil,
    dropped: List[String] = Nil,
    retained: List[String] = Nil,
    collected: List[String] = Nil
  )

  /**
   * 扫描拍 sweep（消费点 = `TaskStuckWatcher.scan` 尾部，MergeWindowCare 同款）：
   * 逐条超龄未确认回执按 [[handleOne]] 处置；已确认超龄回执顺手收殓。fail-soft：
   * 单条失败只 WARN 不串扰；全部判据读数在调用前于 IO.blocking 内取快照。
   */
  def sweep(
    resources: AgentRuntimePort,
    now: Long = System.currentTimeMillis(),
    graceMs: Long = Defaults.DelegateDeliveryAckGraceMs
  ): IO[SweepReport] =
    IO.blocking(list()).flatMap { receipts =>
      val stale = receipts.filter(r => needsRedelivery(r, now, graceMs))
      val gcable = receipts.filter(r => r.ackedAt.exists(a => now - a > AckedRetentionMs))
      gcable
        .traverse_(r => delete(r.delegateId))
        .as(SweepReport(collected = gcable.map(_.delegateId)))
        .flatMap(base =>
          stale.foldLeftM(base)((acc, r) =>
            handleOne(resources, r)
              .map(combine(acc, _))
              .handleErrorWith(e =>
                logger
                  .warn(s"[delegate-delivery] redelivery sweep failed for ${r.delegateId}: ${e.getMessage}")
                  .as(acc)
              )
          )
        )
        .flatTap(report =>
          IO.whenA(report.redelivered.nonEmpty || report.dropped.nonEmpty || report.retained.nonEmpty)(
            logger.warn(
              s"[delegate-delivery] sweep: redelivered=${report.redelivered.size} dropped=${report.dropped.size} " +
                s"retained=${report.retained.size} collected=${report.collected.size}"
            )
          )
        )
    }

  /**
   * 一条超龄未确认回执的处置：父会话已删 ⇒ 删回执 + WARN；达重投上限 ⇒ 停投
   * 留痕；否则 `deliverToRootOrInbox` 重投（父活直投 / 父死持久收件箱），
   * redeliveries+1 + deliveredAt 刷新（重投节拍钟重起）。
   */
  private def handleOne(resources: AgentRuntimePort, r: Receipt): IO[SweepReport] =
    resources.sessionStore.listSessions.flatMap { sessions =>
      decide(r, parentExists = sessions.exists(_.id == r.parentSessionId)) match
        case Decision.Drop =>
          logger.warn(
            s"[delegate-delivery] parent session '${r.parentSessionId}' is gone — dropping the unacked terminal receipt " +
              s"for ${r.delegateId} (${r.eventType}); the terminal payload remains in the delegate session transcript (recover manually if still needed)"
          ) *> delete(r.delegateId).as(SweepReport(dropped = List(r.delegateId)))
        case Decision.Retain =>
          logger
            .warn(
              s"[delegate-delivery] ${r.delegateId}: parent never acknowledged the ${r.eventType} terminal after " +
                s"$MaxRedeliveries redeliveries — giving up automatic redelivery; receipt retained for human review"
            )
            .as(SweepReport(retained = List(r.delegateId)))
        case Decision.Redeliver =>
          val event = AgentCommand.ExternalEvent(
            source = "delegate",
            eventType = r.eventType,
            payload = r.payload,
            metadata = r.metadata.deepMerge(
              JsonObject(
                "redeliveredBy" -> "delegate-delivery-receipts".asJson,
                "redeliveryOf" -> r.delegateId.asJson
              )
            ),
            correlationId = Some(s"delivery-redelivery-${r.delegateId}")
          )
          BgNotifyDelivery
            .deliverToRootOrInbox(
              Some(resources.actorSystem),
              Some(resources.agentRegistry),
              r.parentSessionId,
              event
            )
            .flatMap { route =>
              IO.blocking(
                writeLock.synchronized {
                  find(r.delegateId).foreach(cur =>
                    put(cur.copy(redeliveries = cur.redeliveries + 1, deliveredAt = System.currentTimeMillis()))
                  )
                }
              ) *>
                logger
                  .warn(
                    s"[delegate-delivery] parent never acknowledged ${r.delegateId} terminal (${r.eventType}) — " +
                      s"redelivery #${r.redeliveries + 1} dispatched via $route (correlationId=delivery-redelivery-${r.delegateId})"
                  )
                  .as(SweepReport(redelivered = List(r.delegateId)))
            }
      end match
    }

  /** 单条处置裁决（纯函数，spec 判红锚）。 */
  private[delegate] enum Decision:
    case Redeliver, Drop, Retain

  /**
   * 裁决序：父会话已删 ⇒ Drop（产物仍可由 delegate transcript 人工取回）；重投
   * 达上限 ⇒ Retain（停投留痕等人）；否则 Redeliver。入参回执必为 needsRedelivery
   * 判真的超龄未确认件。
   */
  private[delegate] def decide(r: Receipt, parentExists: Boolean): Decision =
    if !parentExists then Decision.Drop
    else if r.redeliveries >= MaxRedeliveries then Decision.Retain
    else Decision.Redeliver

  private def combine(a: SweepReport, b: SweepReport): SweepReport =
    SweepReport(
      redelivered = a.redelivered ++ b.redelivered,
      dropped = a.dropped ++ b.dropped,
      retained = a.retained ++ b.retained,
      collected = a.collected ++ b.collected
    )

end DelegateDeliveryReceipts
