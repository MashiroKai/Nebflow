package nebflow.core.processor

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.AtomicJson
import nebflow.shared.given
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * DeliveredReceiptLedger —— **已投递回执标记的持久台账**（#886 完成回执重启去重批，
 * 作者令 2026-10-25「完成回执重启去重」修法要求 1/2 的数据面）。
 *
 * == #886 实证机制链（本台账要堵的重复形态）==
 *
 * 回执的投递判定此前只有两条腿：内存 `pendingEvents`（队列在 = 未投）＋
 * DelegateDeliveryReceipts 回执 ack（注入即确认）。两腿都有「已投被判未投」的缝：
 *   - injection-queues.json 快照与投递之间的窗口（缺 persist 的投递腿 / 崩溃点）；
 *   - ack 侧（回执缺席 / correlationId 被重投腿改写）判不中 ⇒ sweep 按超龄未确认
 *     重投（10min 节拍 × 3 次上限）——2026-10-25 23:15 重启后 canvas ×3、console-card
 *     ×2、imagegen ×3 的重复到达即本族。
 * 重启后 `RecoverPersistedQueues` 把队列文件里的**已投件**当「未投」重放进
 * pendingEvents，watchdog 催注 / 先补注入腿把它们再次注入根会话。
 *
 * == 本台账是什么 ==
 *
 * 每会话一份**已投递事件身份集**（`<dataRoot>/sessions/<sid>/delivered-receipts.json`）：
 *   - 键 = 事件 `correlationId`（跨重启稳定——delegate/subtask 终态恒带子会话 id，
 *     重投腿带 `delivery-redelivery-<id>`、mq-recovery 带 `mq-recovery-<id>`）。
 *     **无 correlationId 的事件不入账也不过滤**——载荷摘要不具身份唯一性
 *     （同文多次通知是合法形态，实证 delegate-nebflow-59f171d8 队列三连同文件），
 *     用它判重会误杀（语义零回退约束）。
 *   - 写点 = 每个投递腿的注入决策点（与 DelegateDeliveryReceipts.ackEvent 同点同序，
 *     「注入即标记」——LLM 消费失败属父会话内部事务，引擎职责到「入回合」为止，
 *     既有 ack 先例同款纪律）。
 *   - 读点 = ① `RecoverPersistedQueues` 重放闸（重启恢复先把已投件滤掉再入队）；
 *     ② 催注 / 先补注入两类「复活式批量注入器」的注入前闸（双保险）。
 *
 * == 容量与崩溃窗口 ==
 *
 *   - 容量 [[MaxEntries]]（drop-oldest）：标记集只服务「重启不重放」，老条目出窗
 *     后同 id 事件再入队会重放一次——与重启窗口同级的有界代价，换文件不无限增长。
 *   - 崩溃窗口（标记已写、注入 turn 未落盘即崩）：恢复闸按标记跳过 = 该件不重放。
 *     这是「注入即确认」既有教义（ack 同点同窗）的既有代价类，非本批新增；
 *     反向窗口（注入已落盘、标记未写即崩）恢复闸放行 = 重放一次（at-least-once），
 *     与改前逐字同。
 *
 * fail-soft 纪律：读写失败只 WARN 绝不阻断主路径（簿记面，同 HeldReceiptStore）。
 */
object DeliveredReceiptLedger:

  private val logger = NebflowLogger.forName("nebflow.core.processor.delivered-receipts")

  /** 标记集容量（drop-oldest）——只服务重启不重放窗，老条目出窗回退 at-least-once。 */
  val MaxEntries: Int = 256

  /** 文件形态版本（向前兼容读面自留）。 */
  final case class Ledger(v: Int = 1, ids: List[String] = Nil)

  object Ledger:
    given Encoder[Ledger] = Encoder.instance(l => Json.obj("v" -> l.v.asJson, "ids" -> l.ids.asJson))
    given Decoder[Ledger] = Decoder.instance { c =>
      for v <- c.get[Option[Int]]("v"); ids <- c.get[Option[List[String]]]("ids")
      yield Ledger(v.getOrElse(1), ids.getOrElse(Nil))
    }
  end Ledger

  private def fileOf(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "delivered-receipts.json"

  private val writeLock = new Object

  /** 读全量标记集（缺失/损坏 = 空集，绝不抛）。 */
  def load(sessionId: String): IO[Set[String]] =
    if sessionId.isEmpty then IO.pure(Set.empty)
    else
      IO.blocking {
        val f = fileOf(sessionId)
        if !os.exists(f) then Set.empty[String]
        else
          decode[Ledger](os.read(f)) match
            case Right(l) => l.ids.toSet
            case Left(e) =>
              logger.warn(s"DeliveredReceiptLedger.load: corrupt ledger for $sessionId — treated as empty (${e.getMessage})")
              Set.empty[String]
      }.handleErrorWith(e =>
        logger.warn(s"DeliveredReceiptLedger.load failed for $sessionId: ${e.getMessage}").as(Set.empty[String])
      )

  /**
   * 标记已投递（幂等追加，drop-oldest 封顶 [[MaxEntries]]；写失败只 WARN）。
   * 空集 / 空 sessionId = no-op。
   */
  def markDelivered(sessionId: String, ids: List[String]): IO[Unit] =
    val clean = ids.filter(_.nonEmpty).distinct
    if sessionId.isEmpty || clean.isEmpty then IO.unit
    else
      IO.blocking {
        writeLock.synchronized {
          val f = fileOf(sessionId)
          val existing =
            if !os.exists(f) then Ledger()
            else decode[Ledger](os.read(f)).getOrElse(Ledger())
          val merged = existing.ids.filterNot(clean.contains) ::: clean
          val capped = merged.takeRight(MaxEntries)
          if capped == existing.ids then ()
          else
            if !os.exists(f / os.up) then os.makeDir.all(f / os.up)
            AtomicJson.writeSync(f, Ledger(existing.v, capped).asJson.noSpaces)
        }
      }.void.handleErrorWith(e =>
        logger.warn(s"DeliveredReceiptLedger.markDelivered failed for $sessionId: ${e.getMessage}").void
      )

  /** 身份判据（纯函数）：有 correlationId 的事件才具备跨重启稳定身份。 */
  def hasStableId(e: AgentCommand.ExternalEvent): Boolean =
    e.correlationId.exists(_.nonEmpty)

  /**
   * 事件身份键（不过时批 2026-10-29 · 作者令「消息按 (sessionId, kind, 幂等键)
   * 去重」的幂等键面）：raw correlationId ＋ delegate 终态回执的**归位 id**
   * （redeliveryOf 元数据优先、`delivery-redelivery-<id>` 前缀剥离次之——重投件
   * 与原件同一身份）。无 correlationId = Nil（永放行——既有纪律：同文多次通知
   * 是合法形态，载荷摘要不具身份唯一性）。
   */
  def identityKeys(e: AgentCommand.ExternalEvent): List[String] =
    val raw = e.correlationId.filter(_.nonEmpty).toList
    val resolved = nebflow.core.delegate.DelegateDeliveryReceipts.ackableReceiptId(e).toList
    (raw ++ resolved).distinct

  /**
   * 事件列的身份键展开（markDelivered 喂养面）：注入腿标记时把重投件的原件 id
   * 一并标记，重启重放闸 / intake 闸按身份命中。
   */
  def identityKeysOf(events: List[AgentCommand.ExternalEvent]): List[String] =
    events.flatMap(identityKeys).distinct

  /**
   * intake 判重（纯函数，spec 判红锚）：事件身份键与**已投标记集**或**待发队列
   * 里任一事件的身份键**相交 = 重复件（2026-10-29 21:49→21:59 cancel 回执双投
   * 实证：原件在忙父队列滞留未注入，sweep 按 10min grace 重投改写 correlationId
   * → 两件同入回合）。无身份键恒 false（永放行）。
   */
  private[processor] def isDuplicateIdentity(
    delivered: Set[String],
    pending: List[AgentCommand.ExternalEvent],
    e: AgentCommand.ExternalEvent
  ): Boolean =
    val ids = identityKeys(e)
    ids.nonEmpty && (ids.exists(delivered.contains) ||
      pending.exists(p => identityKeys(p).exists(ids.contains)))
  end isDuplicateIdentity

  /**
   * intake 闸（IO 面，消费点 = AgentProcessing/AgentIdle 的 ExternalEvent 收件口）：
   * false = 重复件——调用方丢弃并留审计，不进 pendingEvents、不注入（同一会话内
   * 恰一次）。读失败 = 放行（fail-open——簿记面故障绝不造成丢件，既有纪律）。
   */
  def intakeAccepts(
    sessionId: String,
    pending: List[AgentCommand.ExternalEvent],
    e: AgentCommand.ExternalEvent
  ): IO[Boolean] =
    if identityKeys(e).isEmpty then IO.pure(true)
    else
      load(sessionId).map(delivered => !isDuplicateIdentity(delivered, pending, e))
        .handleErrorWith(_ => IO.pure(true))

  /**
   * 注入前闸（纯函数，spec 判红锚）：给定已投标记集，把事件列分成
   * （放行件, 已投被滤件）。无 correlationId 的事件**恒放行**（不入账不过滤——
   * 语义零回退）；有身份键且**任一键已标记** ⇒ 滤除（不过时批 2026-10-29：判重
   * 从 raw correlationId 升级为身份键——重投件按归位 id 命中已投标记，重启重放
   * 不再复活原件已投递过的重投件）。
   */
  private[processor] def partitionDelivered(
    delivered: Set[String],
    events: List[AgentCommand.ExternalEvent]
  ): (List[AgentCommand.ExternalEvent], List[AgentCommand.ExternalEvent]) =
    events.partition { e =>
      val ids = identityKeys(e)
      ids.isEmpty || !ids.exists(delivered.contains)
    }

  /**
   * 注入前闸（IO 面）：读标记集 → [[partitionDelivered]]。返回 (放行件, 被滤件)。
   * 读失败 = 全量放行（fail-open——簿记面故障绝不造成丢件）。
   */
  def filterUndelivered(
    sessionId: String,
    events: List[AgentCommand.ExternalEvent]
  ): IO[(List[AgentCommand.ExternalEvent], List[AgentCommand.ExternalEvent])] =
    if events.isEmpty then IO.pure((Nil, Nil))
    else
      load(sessionId).map { delivered =>
        val (kept, blocked) = partitionDelivered(delivered, events)
        if blocked.nonEmpty then
          logger.info(
            s"DeliveredReceiptLedger: filtered ${blocked.size} already-delivered receipt(s) for session " +
              s"$sessionId (ids: ${blocked.flatMap(_.correlationId).map(_.take(48)).mkString(", ")})"
          )
        (kept, blocked)
      }

end DeliveredReceiptLedger
