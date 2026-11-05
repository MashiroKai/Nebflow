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

  /** 复位（afterEach）。 */
  def clear(): Unit = MergeQueue.OwnerDeliveryPort.clear()
end MergeQueueTestPorts
