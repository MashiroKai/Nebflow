package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.AgentCommand
import nebflow.core.AgentRuntimePort
import nebflow.core.mail.SessionMailbox
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.*

/**
 * MailboxProducer —— 终态类事件入箱的**生产者职责单点**（终态通知 actor 邮箱化批
 * P2；设计 §3.3「mailOnce 唯一入队口」）。
 *
 * 职责链：父会话在场判定（已删 = 不产无人读的垃圾文件，F2 兜底同款守卫）→
 * `mailOnce` 幂等入队 → **就地有限重试**（写失败=生产者腿失败，绝不静默继续）→
 * 门铃 best-effort（信已在盘上，门铃蒸发无所谓）。
 *
 * 纪律：
 *   - 生产者职责止于 Enqueued/Coalesced——无回执、无标记、无重投（I5 无 ack）；
 *   - 重试穷尽 = 响亮 ERROR（payload 头随行）+ Failed 回传——**绝不回退旧 push 链**
 *     （`ref ! ExternalEvent` 与邮箱双发 = 事故）；残余由 boot 对账合成补投
 *     （[[MailboxBootSweep]] 账面真源）；
 *   - 重试间的退避是**生产者腿**的有限礼让，条目生命周期本身零墙钟（开放决策⑤）。
 */
private[agent] object MailboxProducer:

  private val logger = NebflowLogger.forName("nebflow.agent.mailbox-producer")

  /** 就地重试上限（含首投）。 */
  val MaxAttempts: Int = 3
  private val RetryGap: FiniteDuration = 200.millis

  /**
   * 终态/升级事件入箱。返回最终 MailResult（Enqueued/Coalesced = 必达成立；
   * Failed = 条目不在盘上，调用方按 cause 处置）。
   *
   * @param parentSessionId 收件方会话（邮箱按会话建——根/dispatcher/团队 manager
   *        三类收件方同构覆盖，设计 §3.1）
   */
  def deliver(
    resources: AgentRuntimePort,
    parentSessionId: String,
    event: AgentCommand.ExternalEvent,
    identity: String,
    kind: String,
    coalesce: Boolean = false,
    producer: String = ""
  ): IO[SessionMailbox.MailResult] =
    if parentSessionId.trim.isEmpty then
      logger.warn(s"[mailbox-producer] empty parentSessionId — event not deliverable (kind=$kind identity=$identity)")
        .as(SessionMailbox.MailResult.Failed("parent-session-missing"))
    else
      resources.sessionStore.listSessions.flatMap { sessions =>
        if !sessions.exists(_.id == parentSessionId) then
          // 父会话已删 = 跳过（不产生无人读的垃圾文件；payload 仍在生产者转录可取回）
          logger.warn(
            s"[mailbox-producer] parent session '$parentSessionId' is gone — skipping mailbox write " +
              s"(kind=$kind identity=$identity); payload remains in the producer session transcript"
          ).as(SessionMailbox.MailResult.Failed("parent-session-gone"))
        else
          attempt(resources, parentSessionId, event, identity, kind, coalesce, producer, attemptNo = 1)
      }.handleErrorWith(e =>
        logger
          .warn(s"[mailbox-producer] deliver failed for '$parentSessionId' ($kind/$identity): ${e.getMessage}")
          .as(SessionMailbox.MailResult.Failed(Option(e.getMessage).getOrElse("deliver-error")))
      )

  private def attempt(
    resources: AgentRuntimePort,
    parentSessionId: String,
    event: AgentCommand.ExternalEvent,
    identity: String,
    kind: String,
    coalesce: Boolean,
    producer: String,
    attemptNo: Int
  ): IO[SessionMailbox.MailResult] =
    SessionMailbox.mailOnce(parentSessionId, event, kind, identity, coalesce = coalesce, producer = producer).flatMap {
      case r @ (SessionMailbox.MailResult.Enqueued(_) | SessionMailbox.MailResult.Coalesced(_)) =>
        MailboxDrain.doorbell(resources, parentSessionId).as(r)
      case SessionMailbox.MailResult.AlreadyPresent => IO.pure(SessionMailbox.MailResult.AlreadyPresent)
      case SessionMailbox.MailResult.Failed(cause) =>
        if attemptNo >= MaxAttempts then
          // 穷尽 = 响亮 ERROR（绝不静默继续；绝不回退旧 push 链——双发=事故）。
          // 残余由 boot 对账合成补投（SubAgentTaskStore 终态行 = 账面真源）。
          logger.error(
            s"[mailbox-producer] FAILED to enqueue after $MaxAttempts attempts — terminal/escalation event " +
              s"(kind=$kind identity=$identity parent=$parentSessionId) is NOT on disk; cause=$cause; " +
              s"payload head: ${event.payload.take(300)} — boot reconcile will synthesize a replacement entry"
          ).as(SessionMailbox.MailResult.Failed(cause))
        else IO.sleep(RetryGap) *> attempt(resources, parentSessionId, event, identity, kind, coalesce, producer, attemptNo + 1)
    }
end MailboxProducer
