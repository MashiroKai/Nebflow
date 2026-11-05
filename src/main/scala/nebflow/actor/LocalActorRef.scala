package nebflow.actor

import cats.effect.*
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * Local actor implementation: backed by a Queue + Fiber.
 *
 * Messages are offered to the queue. A fiber processes them one at a time
 * by applying the current Behavior. Cancellation is via fiber.cancel.
 *
 * System signals (death watch notifications) go through a separate queue
 * so they don't get stuck behind regular messages.
 *
 * == 有界消息箱（刀三·爆炸保护）==
 *
 * 来源 = 审计件（actorization-gap-and-pekko-migration-audit__20261104_211833__
 * delegate-nebflow-18eb158f.md）§3 刀三 + 作者裁定「只做爆炸保护，不做流量
 * 控制」。消息箱由 [[LocalActorSystem]] 以 `Queue.bounded` 装配（容量见
 * [[ActorSystem.mailboxCapacity]]，默认 8192，-D 可覆）。
 *
 * 满时策略 = 审计件选定的 **(b) 拒绝 + 计数 + 死信面**：
 *   - `tryOffer` 返回 false ⇒ 记 [[DeadLetterLedger]]（计数 +1）+ ERROR 日志（带
 *     path），**返回 `IO.unit` 不抛**——`!` 签名不变，发送方不受影响；
 *   - 不选 (a) `offer` 挂起：挂起会让发送方 actor 被拖慢并把停顿级联出去；
 *   - 无 TTL、无丢弃策略、无常态限流：正常积压与无界时完全一致，只有极端积压
 *     （> 容量）才触发本路径，且触发是**响亮可见**的，不是静默丢。
 *
 * 注意（后果如实记）：`?`（ask）内部走 `!`，若目标箱已满，ask 会等到超时
 * 而非立即失败——容量极高（8192）下这只是极端态，且与「不抛不阻塞」的
 * 选定语义一致。
 */
final class LocalActorRef[Msg](
  val path: ActorPath,
  queue: cats.effect.std.Queue[IO, Msg],
  val systemQueue: cats.effect.std.Queue[IO, SystemSignal],
  fiberPromise: Deferred[IO, Fiber[IO, Throwable, Unit]],
  mailboxCapacity: Int,
  deadLetters: DeadLetterLedger
) extends ActorRef[Msg]:

  def !(msg: Msg): IO[Unit] =
    queue.tryOffer(msg).flatMap {
      case true => IO.unit
      case false =>
        deadLetters.record(path.toString, "mailbox", mailboxCapacity, "message rejected at tell")
    }

  def ?[Reply](makeMsg: ActorRef[Reply] => Msg, timeout: Option[FiniteDuration]): IO[Reply] =
    for
      deferred <- Deferred[IO, Reply]
      tempRef = new ActorRef[Reply]:
        val path = ActorPath("__temp", List(java.util.UUID.randomUUID().toString))
        def !(msg: Reply): IO[Unit] = deferred.complete(msg).void
        def ?[R](m: ActorRef[R] => Reply, t: Option[FiniteDuration]): IO[R] =
          IO.raiseError(new UnsupportedOperationException("Nested ask not supported"))
      _ <- this ! makeMsg(tempRef)
      reply <- timeout match
        case Some(d) => deferred.get.timeout(d)
        case None => deferred.get
    yield reply

  /** Stop this actor — cancel the message loop fiber. */
  def stop: IO[Unit] = fiberPromise.get.flatMap(_.cancel)

end LocalActorRef
