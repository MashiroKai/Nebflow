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

  /**
   * 刀四·监督关系落盘化（裁定3「整刀落盘」）：把盘上的 `(watcherPath,
   * targetSessionId, registeredAt)` 边读回来，重建 `watch` 订阅表。**boot 时调用
   * 一次**（装配点见 `GatewayMain`）。未接落盘面（[[WatchStore.disabled]]）时为空操作。
   */
  def replayWatches: IO[Unit]
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

  /**
   * Create a local actor system.
   *
   * 刀四：`watchStore` 缺省 = [[WatchStore.disabled]]（零落盘，行为与刀四之前逐字
   * 一致——全套测试大量构造本对象，隐式落盘会写真实数据根）。生产装配点显式传
   * `WatchStore.file(...)`（`GatewayMain`）。
   */
  def apply(deviceName: String, watchStore: WatchStore = WatchStore.disabled): ActorSystem =
    new LocalActorSystem(deviceName, watchStore)

  /** Create an actor system from a given device name, as a Resource. */
  def resource(
    deviceName: String,
    watchStore: WatchStore = WatchStore.disabled
  ): cats.effect.Resource[IO, ActorSystem] =
    cats.effect.Resource.make(IO(apply(deviceName, watchStore)))(_.stopAll)

end ActorSystem

/** Local-only actor system implementation. */
final class LocalActorSystem(
  val localDevice: String,
  watchStore: WatchStore = WatchStore.disabled
) extends ActorSystem:
  private val log = NebflowLogger.forName("nebflow.actor")

  private case class RegistryEntry(
    ref: ActorRef[?],
    fiber: Fiber[IO, Throwable, Unit],
    systemQueue: Queue[IO, SystemSignal]
  )

  private val registry: Ref[IO, Map[String, RegistryEntry]] = Ref.unsafe(Map.empty)

  /**
   * Death watch: target path → (watcher path → 注册时刻)。
   *
   * 刀四把内层从 `Set[String]` 改成 `Map[String, Long]`：落盘三元组要
   * `registeredAt`，而内存表必须与盘上**同源**（否则重放会把时刻重置成 0，
   * 盘上读数与内存读数不再一致）。
   */
  private val watchers: Ref[IO, Map[String, Map[String, Long]]] = Ref.unsafe(Map.empty)

  /** 容量在此刻定值（构造期读 -D；装配后不随 sys.props 漂移——行为可预测）。 */
  private val mailboxCapacity: Int = ActorSystem.mailboxCapacity
  private val systemQueueCapacity: Int = ActorSystem.systemQueueCapacity

  /** 死信台账（刀三·爆炸保护）：满箱拒绝的计数面，只读可见（[[deadLetterCount]]）。 */
  private val deadLetters: DeadLetterLedger = DeadLetterLedger.unsafe

  def deadLetterCount: IO[Long] = deadLetters.count

  /**
   * provider 并发占坑 · 终态释放钩子（provider-concurrency 批，2026-11-06）：
   * actor **退出**时（正常自停 / 外部 stop / 崩溃 / TTL / cancel——所有退出路径都走
   * `actorLoop` 的 `guarantee` 收尸块，见下方注释）调用一次 `release(path.name)`。
   * 默认 no-op（测试/极早期装配零改动）；生产由 GatewayMain 装配赋值为
   * `ProviderConcurrency.release(counts, _)`。ActorSystem 不持有 SharedResources
   * ⇒ 可赋值函数注入而非构造参数（禁止在 ActorSystem 里 new 第二份计数表）。
   */
  private[nebflow] var providerSlotRelease: String => IO[Unit] = _ => IO.unit

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
    watchStoreEdge(watcher, target)

  /**
   * 订阅 + 落盘（刀四）：内存表先变，再全量重写盘上快照。
   *
   * 落盘失败**不回滚内存表**（真源是内存；盘面是可重放快照）——`save` 自己已经
   * 把异常吞成 WARN，所以这条链永不因为盘出错而打断 `watch` 的语义。
   */
  private def watchStoreEdge(watcher: ActorPath, target: ActorPath): IO[Unit] =
    watchers.update(_.updatedWith(target.toString) {
      case Some(existing) => Some(existing.updated(watcher.toString, System.currentTimeMillis()))
      case None => Some(Map(watcher.toString -> System.currentTimeMillis()))
    }) *> persistWatches

  def unwatch(watcher: ActorPath, target: ActorPath): IO[Unit] =
    watchers.update(_.updatedWith(target.toString) {
      case Some(existing) =>
        val next = existing - watcher.toString
        if next.isEmpty then None else Some(next)
      case None => None
    }) *> persistWatches

  /** 内存表 → 落盘快照（`(watcherPath, targetSessionId, registeredAt)` 三元组）。 */
  private def persistWatches: IO[Unit] =
    watchers.get.flatMap { snapshot =>
      val edges = snapshot.toList.flatMap { case (targetPath, byWatcher) =>
        // 目标 path 是本进程的 ActorPath；落盘的靶心是**会话身份**（最后一段）。
        val targetSessionId = ActorPath.parse(targetPath).toOption.map(_.name).getOrElse(targetPath)
        byWatcher.toList.map { case (watcherPath, registeredAt) =>
          WatchEdge(watcherPath, targetSessionId, registeredAt)
        }
      }
      watchStore.save(edges)
    }

  /**
   * boot 重放（刀四）：读盘上三元组，只取**本设备**的 watcher 边，按当前
   * `localDevice` 把 `targetSessionId` 还原成 `ActorPath(localDevice, sid)`。
   *
   * 重放后立刻回写一次：异地设备的边在这一步被自然剪掉（否则它们会永远躺在盘上
   * 等着永远不会属于它们的进程）。
   */
  def replayWatches: IO[Unit] =
    watchStore.load.flatMap { edges =>
      val localEdges =
        edges.filter(e => ActorPath.parse(e.watcherPath).exists(_.isLocal(localDevice)))
      watchers
        .update { current =>
          localEdges.foldLeft(current) { (acc, edge) =>
            val targetPath = ActorPath(localDevice, edge.targetSessionId).toString
            acc.updatedWith(targetPath) { prev =>
              Some(prev.getOrElse(Map.empty).updated(edge.watcherPath, edge.registeredAt))
            }
          }
        } *> persistWatches *>
        (if localEdges.size == edges.size then IO.unit
         else log.info(s"watch replay: dropped ${edges.size - localEdges.size} non-local edge(s)"))
          *> log.info(s"watch replay: ${localEdges.size} edge(s) restored on device '$localDevice'")
    }

  /** Deliver Terminated signal to all watchers of the given path. */
  private def notifyWatchers(deadPath: String, deadRef: ActorRef[?]): IO[Unit] =
    watchers.get
      .flatMap { watchersMap =>
        val watcherPaths = watchersMap.getOrElse(deadPath, Map.empty).keySet
        // Clean up: remove dead actor from watchers map (刀四：盘上快照同步剪掉，
        // 否则重启后会把一条指向死靶心的边重放回来)
        (watchers.update(_ - deadPath) *> persistWatches) *>
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
        // provider 并发占坑释放（provider-concurrency 批）：onStop 之后、notifyWatchers
        // 之前——所有退出路径（正常/崩溃/TTL/cancel/stopAll）唯一收尸点。失败只
        // WARN（释放失败不得拖垮收尸链；启动 reconcile + 幂等 release 双兜底）。
        _ <- providerSlotRelease(path.name).handleErrorWith(e =>
          ctx.log.warn(s"providerSlotRelease failed: ${e.getMessage}").void
        )
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
