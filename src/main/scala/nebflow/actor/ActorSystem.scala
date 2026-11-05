package nebflow.actor

import cats.effect.std.Queue
import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import nebflow.shared.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * The actor system — registry + message routing + death watch.
 *
 * spawn creates local actors (Queue + Fiber + Behavior loop).
 * resolve looks up actors by path (local registry lookup).
 * stop cancels an actor's fiber, releasing all resources.
 * watch / unwatch manage death watch subscriptions.
 */
trait ActorSystem:
  def localDevice: String
  def spawn[Msg](behavior: Behavior[Msg], name: String, ttl: Option[FiniteDuration] = None): IO[ActorRef[Msg]]
  def resolve[Msg](path: String): IO[ActorRef[Msg]]
  def stop(ref: ActorRef[?]): IO[Unit]
  def stopAll: IO[Unit]

  /**
   * Liveness probe (#22): a cached ActorRef whose loop has exited (crash /
   * TTL / stop without watched cleanup) still offers into its queue —
   * messages sent to it vanish silently. This checks the live registry.
   */
  def isAlive(path: ActorPath): IO[Boolean]
  def watch(watcher: ActorPath, target: ActorPath): IO[Unit]
  def unwatch(watcher: ActorPath, target: ActorPath): IO[Unit]

  /**
   * 死信累计数（刀三·爆炸保护只读面）：邮箱/信号箱满时被拒的消息条数。
   * 恒 0 = 从未触发（正常态）；> 0 = 曾经极端积压，详见 ERROR 日志。
   */
  def deadLetterCount: IO[Long]
end ActorSystem

object ActorSystem:

  /**
   * 内核消息箱容量默认值（刀三·爆炸保护）。
   *
   * 来源 = 审计件 `actorization-gap-and-pekko-migration-audit__20261104_211833__
   * delegate-nebflow-18eb158f.md` §3 刀三 + 作者裁定：**只做爆炸保护，不做流量
   * 控制**——上界极高，只在极端积压时拒绝 + 计数 + 死信可见；绝不 TTL / 丢弃 /
   * 常态限流。8192 ≈ 正常单 actor 积压（一 turn 一条）的数十倍，越界即异常态。
   */
  val DefaultMailboxCapacity = 8192

  /**
   * 内核信号箱（死信监视 `SystemSignal`）容量默认值（刀三·爆炸保护）。
   *
   * 与消息箱分开定值：信号箱只承载 `Terminated`，量级远小于消息流，2 千级足够；
   * 同批理由见 [[DefaultMailboxCapacity]]（只做爆炸保护，不做流量控制）。
   */
  val DefaultSystemQueueCapacity = 2048

  /** `-D nebflow.actor.mailboxCapacity=<n>` 覆写面（≤0 / 非数 → 回落默认）。 */
  def mailboxCapacity: Int = capOf("nebflow.actor.mailboxCapacity", DefaultMailboxCapacity)

  /** `-D nebflow.actor.systemQueueCapacity=<n>` 覆写面（≤0 / 非数 → 回落默认）。 */
  def systemQueueCapacity: Int = capOf("nebflow.actor.systemQueueCapacity", DefaultSystemQueueCapacity)

  private def capOf(key: String, default: Int): Int =
    sys.props.getOrElse(key, default.toString).trim.toIntOption.filter(_ > 0).getOrElse(default)

  /** Create a local actor system. */
  def apply(deviceName: String): ActorSystem =
    new LocalActorSystem(deviceName)

  /** Create an actor system from a given device name, as a Resource. */
  def resource(deviceName: String): cats.effect.Resource[IO, ActorSystem] =
    cats.effect.Resource.make(IO(apply(deviceName)))(_.stopAll)

end ActorSystem

/** Local-only actor system implementation. */
final class LocalActorSystem(val localDevice: String) extends ActorSystem:
  private val log = NebflowLogger.forName("nebflow.actor")

  private case class RegistryEntry(
    ref: ActorRef[?],
    fiber: Fiber[IO, Throwable, Unit],
    systemQueue: Queue[IO, SystemSignal]
  )

  private val registry: Ref[IO, Map[String, RegistryEntry]] = Ref.unsafe(Map.empty)

  /** Death watch: target path → set of watcher paths. */
  private val watchers: Ref[IO, Map[String, Set[String]]] = Ref.unsafe(Map.empty)

  /** 容量在此刻定值（构造期读 -D；装配后不随 sys.props 漂移——行为可预测）。 */
  private val mailboxCapacity: Int = ActorSystem.mailboxCapacity
  private val systemQueueCapacity: Int = ActorSystem.systemQueueCapacity

  /** 死信台账（刀三·爆炸保护）：满箱拒绝的计数面，只读可见（[[deadLetterCount]]）。 */
  private val deadLetters: DeadLetterLedger = DeadLetterLedger.unsafe

  def deadLetterCount: IO[Long] = deadLetters.count

  def spawn[Msg](
    behavior: Behavior[Msg],
    name: String,
    ttl: Option[FiniteDuration] = None
  ): IO[ActorRef[Msg]] =
    val path = ActorPath(localDevice, name)
    val ctxLog = NebflowLogger.forName(s"nebflow.actor.$name")
    for
      // 刀三·爆炸保护（审计件 §3 刀三 + 作者裁定）：有界而非无界——满则不挂起
      // （不传染阻塞给发送方）、不丢弃静默（死信 ERROR + 计数），TTL/限流一概不引入。
      queue <- Queue.bounded[IO, Msg](mailboxCapacity)
      sysQueue <- Queue.bounded[IO, SystemSignal](systemQueueCapacity)
      turnFibersRef <- Ref.of[IO, Map[String, Fiber[IO, Throwable, Unit]]](Map.empty)
      childrenRef <- Ref.of[IO, List[ActorRef[?]]](Nil)
      fiberPromise <- cats.effect.Deferred[IO, Fiber[IO, Throwable, Unit]]
      ref = new LocalActorRef[Msg](path, queue, sysQueue, fiberPromise, mailboxCapacity, deadLetters)

      ctx = new LocalActorContext[Msg](ref, this, ctxLog, turnFibersRef, childrenRef)
      initBehavior <- behavior.onStart(ctx)
      behaviorRef <- Ref.of[IO, Behavior[Msg]](initBehavior)
      fiber <- actorLoop(path, ctx, queue, sysQueue, behaviorRef, ttl, childrenRef).start
      _ <- fiberPromise.complete(fiber)
      _ <- registry.update(_.updated(path.toString, RegistryEntry(ref, fiber, sysQueue)))
    yield ref

  end spawn

  def resolve[Msg](pathStr: String): IO[ActorRef[Msg]] =
    ActorPath.parse(pathStr) match
      case Right(path) =>
        if path.isLocal(localDevice) then
          registry.get.map(_.get(path.toString) match
            case Some(entry) => entry.ref.asInstanceOf[ActorRef[Msg]]
            case None => throw new NoSuchElementException(s"Actor not found: $pathStr"))
        else
          IO.raiseError(
            new NotImplementedError(s"Remote actor resolution not yet implemented: $pathStr")
          )
      case Left(err) =>
        IO.raiseError(new IllegalArgumentException(err))

  def stop(ref: ActorRef[?]): IO[Unit] =
    ref match
      case r: LocalActorRef[?] =>
        r.stop *> registry.update(_ - r.path.toString)
      case _ => IO.unit

  def isAlive(path: ActorPath): IO[Boolean] = registry.get.map(_.contains(path.toString))

  /** Stop all actors — for shutdown. */
  def stopAll: IO[Unit] =
    registry.get.flatMap { entries =>
      entries.values.toList.traverse_ { entry =>
        entry.fiber.cancel
      } *> registry.set(Map.empty)
    }

  // ============================================================
  // Death watch
  // ============================================================

  def watch(watcher: ActorPath, target: ActorPath): IO[Unit] =
    watchers.update(_.updatedWith(target.toString) {
      case Some(set) => Some(set + watcher.toString)
      case None => Some(Set(watcher.toString))
    })

  def unwatch(watcher: ActorPath, target: ActorPath): IO[Unit] =
    watchers.update(_.updatedWith(target.toString) {
      case Some(set) =>
        val next = set - watcher.toString
        if next.isEmpty then None else Some(next)
      case None => None
    })

  /** Deliver Terminated signal to all watchers of the given path. */
  private def notifyWatchers(deadPath: String, deadRef: ActorRef[?]): IO[Unit] =
    watchers.get
      .flatMap { watchersMap =>
        val watcherPaths = watchersMap.getOrElse(deadPath, Set.empty)
        // Clean up: remove dead actor from watchers map
        watchers.update(_ - deadPath) *>
          registry.get.flatMap { entries =>
            watcherPaths.toList.traverse_ { wp =>
              entries.get(wp) match
                // 刀三·爆炸保护：信号箱有界 → 满时 `tryOffer` 拒绝并记死信（ERROR
                // 带 path + 计数），绝不静默；不挂起（不阻塞正在收尸的 loop）。
                case Some(entry) =>
                  entry.systemQueue.tryOffer(SystemSignal.Terminated(deadRef)).flatMap {
                    case true => IO.unit
                    case false =>
                      deadLetters.record(
                        wp,
                        "system-queue",
                        systemQueueCapacity,
                        s"Terminated(${deadPath}) rejected"
                      )
                  }
                case None => IO.unit
            }
          }
      }
      .handleErrorWith(e => log.warn(s"notifyWatchers failed: ${e.getMessage}").void)

  // ============================================================
  // Actor message loop — dual queue (messages + system signals)
  // ============================================================

  private def actorLoop[Msg](
    path: ActorPath,
    ctx: LocalActorContext[Msg],
    queue: Queue[IO, Msg],
    sysQueue: Queue[IO, SystemSignal],
    behaviorRef: Ref[IO, Behavior[Msg]],
    ttl: Option[FiniteDuration],
    childrenRef: Ref[IO, List[ActorRef[?]]]
  ): IO[Unit] =

    // Three-way race: message vs system signal vs TTL
    val take: IO[Either[Option[Msg], SystemSignal]] = ttl match
      case Some(duration) =>
        IO.race(
          IO.race(queue.take.map(msg => Some(msg): Option[Msg]), IO.sleep(duration).as(None: Option[Msg])).map(_.merge),
          sysQueue.take
        )
      case None =>
        IO.race(queue.take.map(Some(_)), sysQueue.take)

    def processMessageOrSignal(
      current: Behavior[Msg],
      action: IO[Behavior[Msg]]
    ): IO[Unit] =
      action
        .handleErrorWith(err => current.onError(ctx, err))
        .flatMap { next =>
          behaviorRef.set(next) *>
            (if next.isStopped then current.onStop(ctx) else loop)
        }

    def loop: IO[Unit] =
      take.flatMap {
        case Left(None) =>
          // TTL expired
          ctx.log.info(s"Actor $path TTL expired, stopping")
          IO.unit // exit loop — guarantee handles cleanup
        case Left(Some(msg)) =>
          for
            current <- behaviorRef.get
            _ <- processMessageOrSignal(current, current.receive(ctx, msg))
          yield ()
        case Right(signal) =>
          for
            current <- behaviorRef.get
            _ <- processMessageOrSignal(current, current.onSignal(ctx, signal))
          yield ()
      }

    // Guarantee: always runs when loop ends (normal stop, crash, cancel).
    // Self-deregistration (#22): stop(ref) removes the registry entry, but a
    // loop that exits any other way (crash / TTL / external cancel) used to
    // leave a ZOMBIE entry — resolve kept returning the dead ref and every
    // message sent to it vanished into an unconsumed queue. The loop itself
    // is the only place that knows it exited; remove self here.
    loop.guarantee {
      for
        _ <- registry.update(_ - path.toString)
        _ <- ctx
          .cancelCurrentTurn()
          .handleErrorWith(e => ctx.log.warn(s"cancelCurrentTurn during cleanup failed: ${e.getMessage}").void)
        _ <- cleanupChildren(childrenRef)
        _ <- behaviorRef.get
          .flatMap(_.onStop(ctx))
          .handleErrorWith(e => ctx.log.error(s"onStop failed: ${e.getMessage}").void)
        _ <- notifyWatchers(path.toString, ctx.self)
      yield ()
    }
  end actorLoop

  private def cleanupChildren(childrenRef: Ref[IO, List[ActorRef[?]]]): IO[Unit] =
    childrenRef.get.flatMap { children =>
      children.traverse_ {
        case r: LocalActorRef[?] => r.stop
        case _ => IO.unit
      } *> childrenRef.set(Nil)
    }

end LocalActorSystem
