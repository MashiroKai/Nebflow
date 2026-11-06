package nebflow.actor

import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * Capabilities available to an actor during message processing.
 *
 * The actor uses this to:
 *   - reference itself (for replyTo)
 *   - fork IO work (non-blocking, tracked for cancellation)
 *   - cancel the current turn's IO work (for Interrupt)
 *   - spawn child actors (lifecycle bound to parent)
 *   - access the system (for resolve, etc.)
 */
trait ActorContext[Msg]:
  /** This actor's own reference — pass as replyTo in messages. */
  def self: ActorRef[Msg]

  /** The actor system — for resolve, global operations. */
  def system: ActorSystem

  /** Logger scoped to this actor. */
  def log: NebflowLogger

  /**
   * Fork an IO computation as a tracked turn fiber.
   *
   * Multiple forkTurn calls can be active simultaneously — each is tracked
   * independently by a unique key. This prevents side-effect forkTurns
   * (e.g. wsSend, deferred.complete) from overwriting the main turn's fiber
   * in the tracking slot.
   *
   * The fiber is tracked so that [[cancelCurrentTurn]] can cancel it.
   * When the actor stops (returns [[Behaviors.stopped]] or system.stop),
   * any in-flight turn fiber is automatically cancelled.
   *
   * Errors in the IO are caught and logged — the fiber never fails silently.
   * If the IO needs to notify the actor of failure (e.g. LlmFailed), it should
   * include its own handleErrorWith that sends a message to [[self]].
   *
   * The Actor returns immediately — it never blocks on this IO.
   * The IO should send a message back to [[self]] when done.
   */
  def forkTurn(io: IO[Unit]): IO[Unit]

  /** Cancel all currently running turn fibers (if any). */
  def cancelCurrentTurn(): IO[Unit]

  /** Spawn a child actor with lifecycle bound to this actor. */
  def spawn[ChildMsg](behavior: Behavior[ChildMsg], name: String): IO[ActorRef[ChildMsg]]

  /**
   * Register interest in another actor's lifecycle. When the watched actor
   * terminates, this actor receives [[SystemSignal.Terminated]] via the system
   * signal channel.
   */
  def watch(ref: ActorRef[?]): IO[Unit]

  /** Unregister interest in another actor's lifecycle. */
  def unwatch(ref: ActorRef[?]): IO[Unit]

  /**
   * 刀一（合并队列重构批 P0，作者裁定「提醒一律走 Tick 消息，不许新增扫描拍」）：
   * **到点投递一条消息给 `self`**——等待态的唯一合法计时形态。
   *
   * 语义（逐条）：
   *   - 一次性：`delay` 到点投一条 `msg`，投完即终结（不重复、不自重臂；重臂 =
   *     再次调用本方法）。
   *   - **不挂在回合 fiber 上**：定时器与被投递的回合无关——`cancelCurrentTurn`
   *     取消的是回合里的 IO，定时器照常到点（否则「回合结束 ⇒ 提醒蒸发」）。
   *   - **不保证送达**：actor 已停（registry 无此 path）⇒ 静默丢弃（发送前用
   *     `system.isAlive` 探一次，不给死 actor 邮箱塞垃圾）。投递失败只 WARN。
   *   - 幂等性由**调用方**保证（典型做法：消息携带臂号/世代号，到点后与当前臂号
   *     比对，陈旧则忽略）——本方法只负责「到点」，不负责去重。
   *
   * 反面（本方法存在的理由）：用 `IO.sleep` 小拍轮询一个**已知截止时刻**是错的——
   * 唤醒次数与截止时刻无关，白白烧 CPU 与 spec 墙钟，粒度永远是猜的。有一个明确
   * 的「到点」就用本方法；只有「截止时刻本身在变」才重算后重臂。
   */
  def scheduleOnce(delay: FiniteDuration, msg: Msg): IO[Unit]

end ActorContext

/** Implementation of ActorContext for local actors. */
final class LocalActorContext[Msg](
  val self: ActorRef[Msg],
  val system: ActorSystem,
  val log: NebflowLogger,
  private val activeTurnFibers: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]],
  private val childrenRef: Ref[IO, List[ActorRef[?]]]
) extends ActorContext[Msg]:

  def forkTurn(io: IO[Unit]): IO[Unit] =
    for
      key <- IO(java.util.UUID.randomUUID().toString)
      safeIo = io
        .handleErrorWith(err => log.error(s"forkTurn IO failed: ${err.getMessage}").void)
        .guarantee(activeTurnFibers.update(_ - key))
      fiber <- safeIo.start
      _ <- activeTurnFibers.update(_ + (key -> fiber))
    yield ()

  /**
   * Hard-recovery P3 (2026-09-07): fire-and-forget. `Fiber.cancel` in CE3
   * waits for the cancellation (finalizers included) to COMPLETE — for a
   * fiber parked on an uncancellable wait (JDK HttpClient body read over a
   * half-open connection, 取证 2026-09-07 §1.4) that wait is infinite, and
   * the Interrupt/Stop/RestartAgent mailbox handlers that call this would
   * wedge the whole actor (production: Interrupt at 11:23:35 froze the
   * mailbox — a later user message never even queued). Instead: clear the
   * tracking map immediately and fork each cancel on its own fiber. Late
   * completion results from an abandoned turn are discarded by the existing
   * stale-turnId guard (AgentActor LlmComplete/LlmFailed) — callers that
   * interrupt a turn MUST bump currentTurnId — and the parked fiber itself
   * is unwedged by the transport abort (LlmInterface.transportAbortFor,
   * hard-recovery P1) when escalation reaches L2.
   */
  def cancelCurrentTurn(): IO[Unit] =
    activeTurnFibers.get.flatMap { fibers =>
      activeTurnFibers.set(Map.empty) *>
        fibers.values.toList.traverse_(_.cancel.start.void)
    }

  def spawn[ChildMsg](behavior: Behavior[ChildMsg], name: String): IO[ActorRef[ChildMsg]] =
    system.spawn(behavior, name).flatTap(ref => childrenRef.update(_ :+ ref))

  def watch(ref: ActorRef[?]): IO[Unit] =
    system.watch(self.path, ref.path)

  def unwatch(ref: ActorRef[?]): IO[Unit] =
    system.unwatch(self.path, ref.path)

  /**
   * 刀一实现：一条独立 fiber 睡到点，再向 `self` 投一条消息。
   *
   * 三条实现纪律（与 trait 文档逐条对应）：
   *   - **不入 `activeTurnFibers`**：`cancelCurrentTurn` 只清回合 IO，定时器必须
   *     活得比回合长（提醒/兜底的本体就是「回合早就结束了」）。
   *   - **发前探活**：`system.isAlive(self.path)` 为假 ⇒ 不投（死 actor 的邮箱
   *     只会被死信台账计一笔，纯噪音）。探活与投递之间的竞态无害（投给刚停的
   *     actor 只是丢一条消息）。
   *   - **异常只 WARN**：计时器绝不让 actor 因定时投递失败而崩（fail-soft）。
   */
  def scheduleOnce(delay: FiniteDuration, msg: Msg): IO[Unit] =
    val wait = if delay.toMillis <= 0L then IO.unit else IO.sleep(delay)
    (wait *> system.isAlive(self.path).flatMap { alive =>
      if alive then self ! msg else IO.unit
    }).handleErrorWith(err => log.warn(s"scheduleOnce delivery failed: ${err.getMessage}").void).start.void
end LocalActorContext
