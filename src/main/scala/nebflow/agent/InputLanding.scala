package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.core.mail.{InputMailbox, InputPayload}
import nebflow.shared.*

/**
 * InputLanding —— **idle 直达腿的落盘门**（P3 磁盘邮箱批 · 节点 3 最小闭环）。
 *
 * 作者 2026-11-05 10:46 裁定：先做主腿最小闭环（`AgentIdle` 两条 idle 直达腿 +
 * 必须的 `clientMessageId` 穿线），D3 锚点 = `pipeLlmCall` 成功后 unlink，
 * **不许写成「投递即完成」**。
 *
 * == 它解决什么 ==
 * idle 直达腿今天的行为是：命令进 actor → 直接开 turn。进程若崩在「已接收」与
 * 「turn 执行完」之间，用户输入**零痕迹丢失**（`pendingUserInputs` 从不落盘）。
 * 本门把这条缝补上：
 *
 *   1. [[landUser]] / [[landImmediate]]：**入队 + 取走**（`pending-input/` → `taken-input/`），
 *      条目在 turn 执行期间**仍在盘上**；返回 `None` = 该 identity 已执行过（墓碑命中），
 *      调用方据此**跳过本 turn**（幂等键兜住「恰好一次」，客户端重发同一帧不会双跑）。
 *   2. [[consume]]：**turn 成功之后**才 unlink + 写墓碑（D3）。崩在 turn 中途 ⇒
 *      条目留在 `taken-input/`，恢复点重投（至多一次重复、零丢失）。
 *
 * == 幂等键（identity）生成规则 ==
 *   - **K2**：`clientMessageId`（真人帧的客户端幂等键）优先 —— 同 cid 重发 = 同 key。
 *   - **K3**：无 cid 的服务端注入件 ⇒ 本门铸造 `minted-<nanoTime>-<hash>`。
 *     ⚠️ 铸造件对「同一条语义重投」**不具**去重能力（每次铸造不同 key）；这类件的
 *     去重必须由**调用点**提供稳定 key（节点 4 的生产者切换做这件事）。
 *     此处不作弊：宁可如实标注无幂等，也不假装有。
 *
 * == 失败面 ==
 * 落盘失败（磁盘故障）= **不阻断 turn**（响亮 WARN + 照旧执行）：本门是「额外保险」，
 * 不是 turn 的前置条件——把邮箱故障升级成「用户消息发不出去」是更坏的退化。
 */
private[agent] object InputLanding:

  private val logger = NebflowLogger.forName("nebflow.agent.input-landing")

  /** Protected intake is durable and never falls through to the legacy mailbox. */
  def acceptProtected(
    handle: nebflow.core.ProtectedExecutionHandle,
    payload: InputPayload,
    identity: String
  ): IO[Either[String, Boolean]] =
    val admission = handle.admission
    handle.authority.observeChain(admission.sessionId, admission.executionId).flatMap {
      case ExecutionChainObservation.Verified(state)
          if state.attemptId == admission.attemptId && state.incarnationId == admission.incarnationId =>
        val id = java.util.UUID.randomUUID().toString
        val command = ExecutionTransition(
          admission.sessionId,
          admission.executionId,
          id,
          id,
          state.ref,
          state.attemptId,
          state.incarnationId,
          ExecutionEvent.InputAccepted(identity, payload.asJson)
        )
        handle.authority.acceptInput(command).map {
          case ExecutionCommitResult.Committed(_) => Right(true)
          case ExecutionCommitResult.Duplicate(_) | ExecutionCommitResult.DuplicateInput(_, _) => Right(false)
          case ExecutionCommitResult.Rejected(reason) => Left(reason)
          case ExecutionCommitResult.CommitUnknown(_, _) => Left("commit-unknown")
        }
      case _ => IO.pure(Left("execution-review-required"))
    }
  end acceptProtected

  final case class ProtectedInputReceipt(identity: String, accepted: Boolean)

  def protectedUserReceipt(
    handle: nebflow.core.ProtectedExecutionHandle,
    msg: AgentCommand.UserInput
  ): IO[Either[String, ProtectedInputReceipt]] =
    if msg.replyTo.nonEmpty then IO.pure(Left("reply-target-unsupported"))
    else
      val identity = msg.clientMessageId.getOrElse(java.util.UUID.randomUUID().toString)
      acceptProtected(handle, InputPayload.ofUser(msg), identity).map(_.map(ProtectedInputReceipt(identity, _)))

  def protectedImmediateReceipt(
    handle: nebflow.core.ProtectedExecutionHandle,
    msg: AgentCommand.ImmediateInput
  ): IO[Either[String, ProtectedInputReceipt]] =
    val identity = msg.clientMessageId.getOrElse(java.util.UUID.randomUUID().toString)
    acceptProtected(handle, InputPayload.ofImmediate(msg), identity).map(_.map(ProtectedInputReceipt(identity, _)))

  def protectedUser(
    handle: nebflow.core.ProtectedExecutionHandle,
    msg: AgentCommand.UserInput
  ): IO[Either[String, Boolean]] =
    protectedUserReceipt(handle, msg).map(_.map(_.accepted))

  def protectedImmediate(
    handle: nebflow.core.ProtectedExecutionHandle,
    msg: AgentCommand.ImmediateInput
  ): IO[Either[String, Boolean]] =
    protectedImmediateReceipt(handle, msg).map(_.map(_.accepted))

  /** 刚落地/命中的身份（`None` = 无会话身份 or 落盘失败 ⇒ turn 照旧执行）。 */
  type Landed = Option[String]

  private def mintedKey(prefix: String): String =
    s"minted-$prefix-${System.nanoTime()}"

  /**
   * 入队 + 取走。返回：
   *   - `Some(identity)` ⇒ 新件已落盘并取入 taken-input，正常开 turn，turn 后必须 [[consume]]；
   *   - `None`           ⇒ **重复件**（墓碑/在箱命中）**或**落盘失败/无 sid ⇒ 调用方按
   *                        `Duplicate` 语义跳过 turn 时需自行区分（见 [[isDuplicate]]）。
   *
   * 为免调用方误判，返回值用 [[LandOutcome]] 显式三态。
   */
  sealed trait LandOutcome extends Product with Serializable

  object LandOutcome:
    /** 新件已落盘并取走：正常开 turn，turn 成功后必须 `consume(identity)`。 */
    final case class Landed(identity: String) extends LandOutcome

    /** 幂等键已执行过或已在箱：**跳过本 turn**（不得重复执行）。 */
    case object Duplicate extends LandOutcome

    /** 无 sid / 落盘失败：**照旧开 turn**（不因邮箱故障阻断用户输入）。 */
    final case class Passthrough(cause: String) extends LandOutcome

  /**
   * Duplicate 短路时落下的 **agent 侧生命周期事件名**（修复批，2026-11-18：Duplicate
   * 不再无痕）。调用点见 `AgentIdle` 两处 `LandOutcome.Duplicate` 分支。
   */
  val DuplicateEvent: String = "input-duplicate-suppressed"

  /**
   * Duplicate 的**可读诊断**（IO 包装：读 `taken-input/` 残件面 + 消费墓碑面）。
   *
   * 现场缺口（RCA 2026-11-18）：[[LandOutcome.Duplicate]] 此前是**完全静默**的短路——
   * 同一 `clientMessageId` 既落不到 `pending-input/`、也没命中可重投的 pending 副本时，
   * turn 被跳过而**零事件零原因**，父会话/根会话干等。本函数把三类来源显式化，供
   * [[DuplicateEvent]] 事件正文引用（`AgentIdle` 侧落事件）。
   */
  def duplicateReason(sidRaw: Option[String], identity: String): IO[String] =
    sidRaw.filter(_.trim.nonEmpty) match
      case None => IO.pure(s"identity=$identity no-session-id — Duplicate cannot be attributed")
      case Some(sid) =>
        for
          taken <- InputMailbox.scanTaken(sid)
          delivered <- InputMailbox.deliveredContains(sid, Set(identity))
        yield duplicateNotice(identity, taken.find(_.identity == identity).map(_.seq), delivered.contains(identity))

  /**
   * 纯函数：Duplicate 的三类来源 → 可读原因文本（单测承重面）。
   *   - **`taken-input/` 残件**：条目在盘上但重投腿未取走 ⇒ 上一条 turn 从未提交（D3
   *     `consume` 未走到）——本批点名的「静默丢件」毒化形态；条目会在下次 boot 的
   *     [[InputMailbox.replayProducerInputs]] 里重投，此前该 key 的每次重发都是 Duplicate。
   *   - **消费墓碑**（`delivered-inputs.json`）：该 key 已执行过（客户端重发 / 崩溃重放）
   *     ⇒ 正常幂等短路。
   *   - **两者皆无**：身份既无残件也无墓碑 ⇒ 并发取走/清理竞态，无待重投副本。
   */
  def duplicateNotice(identity: String, takenSeq: Option[Long], delivered: Boolean): String =
    if takenSeq.isDefined then
      s"identity=$identity taken-input/ residue seq=${takenSeq.get} — prior turn never committed " +
        "(D3 consume not reached); replayed at next boot; until then every resend of this key is Duplicate"
    else if delivered then
      s"identity=$identity already executed (delivered-inputs tombstone) — client resend / crash replay (expected idempotent skip)"
    else s"identity=$identity no pending/taken copy to re-take (concurrent consume or cleanup race)"

  /**
   * 用户输入（`UserInput`）落盘门。
   *
   * @param replyToSessionId `replyTo` 的**身份形态**（`ActorRef` 是不可序列化句柄，
   *                         规格 R-5：身份≠句柄）；调用方按 registry 折算。
   */
  def landUser(
    sid: Option[String],
    ui: AgentCommand.UserInput,
    replyToSessionId: Option[String] = None
  ): IO[LandOutcome] =
    land(sid, InputPayload.ofUser(ui, replyToSessionId), ui.clientMessageId.getOrElse(mintedKey("user")), "idle-user")

  /** 注入输入（`ImmediateInput`，含真人主腿 #37）落盘门。 */
  def landImmediate(sid: Option[String], imm: AgentCommand.ImmediateInput): IO[LandOutcome] =
    land(sid, InputPayload.ofImmediate(imm), imm.clientMessageId.getOrElse(mintedKey("imm")), "idle-immediate")

  private def land(sid: Option[String], payload: InputPayload, identity: String, producer: String): IO[LandOutcome] =
    sid.filter(_.trim.nonEmpty) match
      case None => IO.pure(LandOutcome.Passthrough("no-session-id"))
      case Some(s) =>
        InputMailbox
          .enqueue(s, payload, identity, producer = producer)
          .flatMap {
            case InputMailbox.InputMailResult.Enqueued(entry) =>
              // 立刻取走：条目从 pending-input 迁到 taken-input，**在 turn 执行期间仍在盘上**
              // （D3：turn 成功后才 unlink；崩在 turn 中途 ⇒ 恢复点扫 taken-input 重投）。
              InputMailbox.take(s, List(entry)).map {
                case taken if taken.nonEmpty => LandOutcome.Landed(identity)
                // 取走失败（并发/IO）：条目仍在 pending-input ⇒ 恢复点同样会扫到，按已落地处理
                case _ => LandOutcome.Landed(identity)
              }
            case InputMailbox.InputMailResult.AlreadyPresent =>
              // A producer may have durably accepted the input before sending the actor command.
              InputMailbox.scanPending(s).flatMap { pending =>
                pending.find(e =>
                  e.identity == identity && e.producer == nebflow.core.mail.ProducerInput.Producer &&
                    (e.payload == payload || (e.payload.kind == nebflow.core.mail.InputPayload.KindImmediate &&
                      e.payload.copy(kind = payload.kind) == payload))
                ) match
                  case Some(entry) =>
                    InputMailbox.take(s, List(entry)).map {
                      case entries if entries.nonEmpty => LandOutcome.Landed(identity)
                      case _ => LandOutcome.Duplicate
                    }
                  case None => IO.pure(LandOutcome.Duplicate)
              }
            case InputMailbox.InputMailResult.Failed(cause) =>
              logger
                .warn(
                  s"[input-landing] disk landing failed sid='$s' identity='$identity' cause=$cause — turn proceeds (mailbox is extra insurance, never a blocker)"
                )
                .as(LandOutcome.Passthrough(cause))
          }
          .handleErrorWith(t =>
            logger
              .warn(s"[input-landing] unexpected failure sid='$s': ${t.getMessage} — turn proceeds")
              .as(LandOutcome.Passthrough(Option(t.getMessage).getOrElse("unknown")))
          )

  /**
   * turn **成功后**回收（D3 锚点）：unlink `taken-input/` 条目 + 写墓碑。
   *
   * 调用位置：`pipeLlmCall(...)` 之后（`flatTap`）——**不许**前移到「投递即完成」。
   */
  def consume(sid: Option[String], outcome: LandOutcome): IO[Unit] =
    outcome match
      case LandOutcome.Landed(identity) =>
        sid.filter(_.trim.nonEmpty).fold(IO.unit)(s => InputMailbox.unlinkTaken(s, List(identity)))
      case _ => IO.unit

  /** Commit accepted producer inputs to the transcript before removing their durable intake. */
  def checkpointProducerInputs(
    resources: nebflow.core.AgentRuntimePort,
    sid: Option[String],
    messages: List[nebflow.shared.Message],
    identities: Set[String]
  ): IO[Unit] =
    val wanted = identities.filter(id => nebflow.core.mail.ProducerInput.isProducerIdentity(Some(id)))
    if wanted.isEmpty then IO.unit
    else
      sid.filter(_.nonEmpty).fold(IO.unit) { session =>
        (InputMailbox.scanPending(session), InputMailbox.scanTaken(session)).tupled.flatMap { (pending, taken) =>
          val owned =
            (pending ++ taken).filter(e => e.producer == nebflow.core.mail.ProducerInput.Producer && wanted(e.identity))
          if owned.isEmpty then IO.unit
          else
            resources.sessionStore.durableSwapMessages(session, messages) *>
              InputMailbox.take(session, pending.filter(e => owned.exists(_.identity == e.identity))) *>
              InputMailbox.unlinkTaken(session, owned.map(_.identity))
        }
      }
  end checkpointProducerInputs

end InputLanding
