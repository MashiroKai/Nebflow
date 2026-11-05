package nebflow.core.delegate

import cats.effect.IO

/**
 * P1 投递身份化批（2026-11-05）的 spec 夹具：[[MergeQueue.OwnerDeliveryPort]] 的
 * 记录实现。
 *
 * 旧夹具（P1 前）靠 spawn 一个 `Sink` actor 收 `AgentCommand.UserInput`，再用
 * `InternalLeg(child, sup)` 把 ref 挂到成员上——**那正是本批删掉的形态**。现在投递
 * 只认收件身份（`ownerSessionId`），所以 spec 直接在这个端口上断言「投递了什么给
 * 谁」，比探 actor 信箱更贴近真实的落盘邮箱语义。
 */
object MergeQueueTestPorts:

  /** 记录一条投递：收件身份 / kind / brief。 */
  final case class Sent(ownerSessionId: String, kind: String, brief: String, identity: String)

  /** 只关心「投得出去 / 投不出去」与「属主在场与否」的最小端口。 */
  def install(presentOwner: Boolean = true, deliverResult: Either[String, Unit] = Right(())): Unit =
    MergeQueue.OwnerDeliveryPort.install(new MergeQueue.OwnerDeliveryPort.Face:
      def deliver(ownerSessionId: String, kind: String, brief: String, identity: String): IO[Either[String, Unit]] =
        IO.pure(deliverResult)
      def present(ownerSessionId: String): IO[Boolean] = IO.pure(presentOwner)
    )

  /**
   * 记录端口：所有投递按顺序交给 `sink`（spec 直接断言 brief 内容/顺序），
   * `presentOwner` 控制在场读数。
   */
  def recording(sink: Sent => Unit, presentOwner: Boolean = true): Unit =
    MergeQueue.OwnerDeliveryPort.install(new MergeQueue.OwnerDeliveryPort.Face:
      def deliver(ownerSessionId: String, kind: String, brief: String, identity: String): IO[Either[String, Unit]] =
        IO { sink(Sent(ownerSessionId, kind, brief, identity)); Right(()) }
      def present(ownerSessionId: String): IO[Boolean] = IO.pure(presentOwner)
    )

  /**
   * 把端口投递**转发到既有 Sink actor**（`AgentCommand.UserInput`，replyTo = 传入的
   * supervisor ref）。
   *
   * 用途：P1 把「投递」从 actor ref 换成身份后，绝大多数存量 spec 的断言形状
   * （`records` 里的 `(text, replyTo)`、`grants` 里的 brief 文本）仍然是正确的
   * **观测口径**——它们关心的是「投了什么、replyTo 是谁、投了几次」，不关心中间
   * 有没有 ref 闭包。用本方法把端口接到同一个 Sink，既有断言零改动继续有效，
   * 且验的是**真实投递路径**（队列 → 端口 → 收件人）。
   */
  def toActor(
    child: nebflow.actor.ActorRef[nebflow.actor.AgentCommand],
    supervisor: nebflow.actor.ActorRef[nebflow.actor.AgentEvent],
    presentOwner: Boolean = true
  ): Unit =
    MergeQueue.OwnerDeliveryPort.install(new MergeQueue.OwnerDeliveryPort.Face:
      def deliver(ownerSessionId: String, kind: String, brief: String, identity: String): IO[Either[String, Unit]] =
        (child ! nebflow.actor.AgentCommand.UserInput(
          text = brief,
          replyTo = Some(supervisor),
          source = Some("merge-queue"),
          eventType = Some(kind)
        )).attempt.map {
          case Right(_)  => Right(())
          case Left(err) => Left(Option(err.getMessage).getOrElse(err.toString))
        }
      def present(ownerSessionId: String): IO[Boolean] = IO.pure(presentOwner)
    )

  /** 复位（afterEach）。 */
  def clear(): Unit = MergeQueue.OwnerDeliveryPort.clear()
end MergeQueueTestPorts
