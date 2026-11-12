package nebflow.core.mail

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{Message, PathUtil}
import nebflow.actor.AgentCommand
import nebflow.shared.given
import nebflow.shared.NebflowLogger

/** Durable, identity-keyed completion debt for a session's current supervisor. */
object CompletionDebtStore:
  final case class Debt(
    ownerSessionId: String,
    recipientSessionId: String,
    messages: List[Message],
    phase: String = "deliverable",
    /** 持久稳定代际身份（#2141/#2178）：每个真实执行/续轮一枚（UUID，record 时铸造
      * 并随债落盘）；同代崩溃重试沿债重放保持同代，Mail 续轮的新完成铸造新代。
      * 旧格式文件解码为 ""（= 无代际，行为与升级前逐字兼容）。 */
    generation: String = ""
  )
  object Debt:
    given Encoder[Debt] = Encoder.forProduct5("ownerSessionId", "recipientSessionId", "messages", "phase", "generation")(d =>
      (d.ownerSessionId, d.recipientSessionId, d.messages, d.phase, d.generation)
    )
    given Decoder[Debt] = Decoder.instance { c =>
      for
        owner <- c.get[String]("ownerSessionId")
        recipient <- c.get[Option[String]]("recipientSessionId")
        messages <- c.get[List[Message]]("messages")
        phase <- c.get[Option[String]]("phase")
        generation <- c.get[Option[String]]("generation")
      yield Debt(owner, recipient.getOrElse(""), messages, phase.getOrElse("deliverable"), generation.getOrElse(""))
    }

  private val logger = NebflowLogger.forName("nebflow.core.mail.completion-debt")
  enum Acceptance:
    case New, Duplicate, Conflict
  final case class AcceptedTerminal(
    ownerSessionId: String,
    recipientSessionId: String,
    eventType: String,
    payload: String,
    generation: String = ""
  )
  private val acceptedKey = "completion-accepted.json"
  private val terminalAcceptedLock = new Object
  // Serialize all debt read-modify-write operations within this process.
  private val debtLock = new Object
  /** 接收账容量（drop-oldest）：每轮一行，防多年续轮无界增长。 */
  val MaxAcceptedEntries: Int = 256
  private def acceptedFile(recipient: String): os.Path =
    PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(recipient) / acceptedKey

  /** 事件携带的代际（缺席 = 旧链事件，落无代际账行）。 */
  def generationOf(event: AgentCommand.ExternalEvent): String =
    event.metadata("completionGeneration").flatMap(_.asString).getOrElse("")

  private def findRow(existing: List[AcceptedTerminal], owner: String, gen: String): Option[AcceptedTerminal] =
    existing.find(row => row.ownerSessionId == owner && row.generation == gen)

  def checkAcceptance(owner: String, recipient: String, event: AgentCommand.ExternalEvent): IO[Acceptance] = IO.blocking {
    require(owner.nonEmpty && recipient.nonEmpty, "terminal acceptance requires owner and recipient")
    if scala.util.Try(nebflow.core.delegate.DelegateRegistry.find(owner).exists(_.cancelRequestedAt.nonEmpty)).getOrElse(false) then
      Acceptance.Conflict
    else terminalAcceptedLock.synchronized {
      val existing = acceptedFor(recipient)
      findRow(existing, owner, generationOf(event)) match
        case Some(row) if row.recipientSessionId == recipient && row.eventType == event.eventType && row.payload == event.payload => Acceptance.Duplicate
        case Some(_) => Acceptance.Conflict
        case None => Acceptance.New
    }
  }

  def acceptOnce(owner: String, recipient: String, event: AgentCommand.ExternalEvent): IO[Acceptance] = IO.blocking {
    require(owner.nonEmpty && recipient.nonEmpty, "terminal acceptance requires owner and recipient")
    if scala.util.Try(nebflow.core.delegate.DelegateRegistry.find(owner).exists(_.cancelRequestedAt.nonEmpty)).getOrElse(false) then
      Acceptance.Conflict
    else terminalAcceptedLock.synchronized {
      val existing = acceptedFor(recipient)
      findRow(existing, owner, generationOf(event)) match
        case Some(row) if row.recipientSessionId == recipient && row.eventType == event.eventType && row.payload == event.payload => Acceptance.Duplicate
        case Some(_) => Acceptance.Conflict
        case None =>
          val p = acceptedFile(recipient)
          os.makeDir.all(p / os.up)
          // 同 owner 跨代累积 ⇒ 每代一行（Mail 续轮重新有效）；容量 drop-oldest。
          val updated = (existing.filterNot(_.ownerSessionId == owner) :+
            AcceptedTerminal(owner, recipient, event.eventType, event.payload, generationOf(event))).takeRight(MaxAcceptedEntries)
          AtomicJson.writeSync(p, updated.asJson.noSpaces)
          Acceptance.New
    }
  }

  private def acceptedFor(recipient: String): List[AcceptedTerminal] =
    val p = acceptedFile(recipient)
    if !os.exists(p) then Nil else decode[List[AcceptedTerminal]](os.read(p)).fold(err => throw new IllegalStateException(err.getMessage), identity)

  private given Encoder[AcceptedTerminal] = Encoder.forProduct5("ownerSessionId", "recipientSessionId", "eventType", "payload", "generation")(r =>
    (r.ownerSessionId, r.recipientSessionId, r.eventType, r.payload, r.generation)
  )
  private given Decoder[AcceptedTerminal] = Decoder.instance { c =>
    for
      owner <- c.get[String]("ownerSessionId")
      recipient <- c.get[String]("recipientSessionId")
      eventType <- c.get[String]("eventType")
      payload <- c.get[String]("payload")
      generation <- c.get[Option[String]]("generation")
    yield AcceptedTerminal(owner, recipient, eventType, payload, generation.getOrElse(""))
  }

  private def file(owner: String): os.Path =
    PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(owner) / "completion-debt.json"
  private def quarantine(owner: String): os.Path =
    PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(owner) / "completion-debt.json.corrupt"

  /**
   * Durable before the caller sends its actor notification. 幂等面（作者裁定
   * #2141/#2178「完成回报不能是 session 一生只有首次有效」）：
   *   - 已取消（墓碑/registry）⇒ false（取消栅栏不变）；
   *   - 在途债同收件方同内容 ⇒ true 且**保留原代**（同完成的重声明不改身份）；
   *   - 其余（无债 / 已结算 / 收件方或内容漂移）⇒ **铸造新代覆写**——旧格式
   *     `settled ⇒ false`（一生一次）废除：Mail 续轮的新完成（含内容相同者）
   *     必须拿新身份重新回报。
   *   - 读面损坏（Left）同样走新代覆写：损坏债不能永久阻断后续轮次的完成义务，
   *     原字节已在 .corrupt 保全（read 侧职责）。
   */
  def record(ownerSessionId: String, recipientSessionId: String, messages: List[Message]): IO[Boolean] = IO.blocking { debtLock.synchronized {
    require(ownerSessionId.trim.nonEmpty, "completion debt requires an owner session id")
    val p = file(ownerSessionId)
    os.makeDir.all(p / os.up)
    val existing = read(ownerSessionId)
    val cancelRequested = scala.util.Try(nebflow.core.delegate.DelegateRegistry.find(ownerSessionId).exists(_.cancelRequestedAt.nonEmpty)).getOrElse(false)
    if cancelRequested then false
    else existing match
      case Right(Some(debt)) if debt.phase == "cancelled" => false
      case Right(Some(debt)) if debt.phase != "settled" && debt.recipientSessionId == recipientSessionId && debt.messages == messages => true
      case _ =>
        // 走到这里 = 无债 / 已结算（任一内容）/ 收件方或内容漂移 / 读面损坏——
        // 都是**新一轮完成**：一律铸造新代覆写（同代保留只在上方早退分支发生）。
        AtomicJson.writeSync(p, Debt(ownerSessionId, recipientSessionId, messages, "deliverable", java.util.UUID.randomUUID().toString).asJson.noSpaces)
        true
  }}

  private def read(ownerSessionId: String): Either[String, Option[Debt]] =
    val p = file(ownerSessionId)
    val bad = quarantine(ownerSessionId)
    if os.exists(bad) then Left(s"completion debt for '$ownerSessionId' is quarantined at $bad")
    else if !os.exists(p) then Right(None)
    else
      val raw = os.read.bytes(p)
      decode[Debt](new String(raw, java.nio.charset.StandardCharsets.UTF_8)) match
        case Right(debt) if debt.ownerSessionId == ownerSessionId => Right(Some(debt))
        case Right(_) => preserveAndFail(ownerSessionId, raw, "invalid owner/recipient")
        case Left(error) => preserveAndFail(ownerSessionId, raw, error.getMessage)

  private def preserveAndFail(owner: String, raw: Array[Byte], reason: String): Either[String, Option[Debt]] =
    val bad = quarantine(owner)
    if !os.exists(bad) then os.write.over(bad, raw, createFolders = true)
    logger.error(s"completion debt for '$owner' is invalid; original retained at $bad ($reason)")
    Left(s"invalid completion debt for '$owner' (preserved at $bad): $reason")

  /** Missing debt is distinct from an unreadable debt; callers must not treat Left as completion. */
  def pending(ownerSessionId: String): IO[Either[String, Option[Debt]]] = IO.blocking(read(ownerSessionId))
    .handleError(e => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

  /** Acknowledge only the matching delivered payload; conflict/malformed files are never cleared. */
  def acknowledge(owner: String, recipient: String, messages: List[Message], generation: String): IO[Boolean] = IO.blocking { debtLock.synchronized {
    read(owner) match
      case Right(Some(debt)) if debt.phase == "cancelled" => false
      case Right(Some(debt)) if debt.recipientSessionId == recipient && debt.messages == messages && debt.generation == generation =>
        AtomicJson.writeSync(file(owner), debt.copy(phase = "settled").asJson.noSpaces)
        true
      case Right(None) => true
      case _ => false
  }}

  /** Cancellation is a durable tombstone racing record/ack; old completion cannot overwrite it. */
  def cancel(owner: String): IO[Unit] = IO.blocking { debtLock.synchronized {
    val p = file(owner)
    os.makeDir.all(p / os.up)
    val prior = read(owner).toOption.flatten
    scala.util.Try(nebflow.core.delegate.DelegateRegistry.markCancelRequested(owner, "completion-debt-terminal")).toOption
    prior.filter(_.phase == "cancelled") match
      case Some(_) => ()
      case None =>
        val recipient = prior.map(_.recipientSessionId).filter(_.nonEmpty).getOrElse("cancelled")
          AtomicJson.writeSync(p, Debt(owner, recipient, Nil, "cancelled", prior.map(_.generation).getOrElse("")).asJson.noSpaces)
  }}
end CompletionDebtStore
