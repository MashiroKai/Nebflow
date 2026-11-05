package nebflow.actor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 刀一验收 spec（合并队列重构批 P0）：**到点收到消息，不是轮询**。
 *
 * 判红逻辑（与判据逐条对应）：
 *   - ① 截止时刻前**一条消息都没有**（轮询面会在中途不断醒来做读数，且任何
 *     「先快后慢」的实现都会在截止前塞进第一批 probe）；
 *   - ② 截止时刻到点收到**恰一条**（`scheduleOnce` 一次性语义，不重复投递）；
 *   - ③ 处理次数 = 1（actor 的 receive 计数；轮询实现会把拍数计进来）；
 *   - ④ 到点精度：实测间隔落在 [delay, delay + slack]，不做「大概」断言；
 *   - ⑤ actor 已停 ⇒ 定时器不投死信、不炸（fail-soft + 探活）。
 */
class ActorContextScheduleOnceSpec extends CatsEffectSuite:

  sealed trait Probe
  case object Tick extends Probe

  /** 有界等待取一条消息：超窗返回 None（用于「截止前必须为空」这一腿）。 */
  private def tryTake(q: Queue[IO, Probe], within: FiniteDuration): IO[Option[Probe]] =
    IO.race(IO.sleep(within), q.take).map {
      case Left(_)  => None
      case Right(m) => Some(m)
    }

  test("scheduleOnce 到点投递一条消息：截止前零消息、到点恰一条（定时消息，不是轮询拍）") {
    val delay = 300.millis
    for
      system <- IO(ActorSystem("schedule-once-spec"))
      inbox <- Queue.unbounded[IO, Probe]
      ctxRef <- Deferred[IO, ActorContext[Probe]]
      handled <- Ref.of[IO, Int](0)
      startedAt <- IO.monotonic
      // 行为：把每次 receive 的到达时刻记进 inbox；**自身零 sleep、零轮询**。
      behavior = Behaviors.setup[Probe] { ctx =>
        ctxRef.complete(ctx) *> IO.pure(Behaviors.receiveMessage { msg =>
          inbox.offer(msg) *> handled.update(_ + 1) *> IO.pure(Behaviors.receiveMessage(_ => IO.pure(Behaviors.stopped)))
        })
      }
      ref <- system.spawn(behavior, "sched-once-target")
      ctx <- ctxRef.get
      _ <- ctx.scheduleOnce(delay, Tick)
      // ① 截止前的 200ms 窗内必须一条都没有（轮询实现会在这段时间里反复醒来）。
      early <- tryTake(inbox, 200.millis)
      _ <- IO(assertEquals(early, None, "在截止时刻之前不得收到任何消息（否则就是轮询在拍，不是到点消息）"))
      // ② 到点收到恰一条。
      onTime <- tryTake(inbox, 2.seconds)
      arrivedAt <- IO.monotonic
      elapsed = (arrivedAt - startedAt).toMillis
      _ <- IO(assertEquals(onTime, Some(Tick), "截止时刻必须收到那条消息"))
      // ③ 处理次数恰 1（一次性语义 + 无轮询拍）。
      count <- handled.get
      _ <- IO(assertEquals(count, 1, s"到点只投一条：实际 receive 了 $count 次（轮询实现会把拍数计进来）"))
      // ④ 精度：不早于 delay，且不晚于 delay + slack。
      _ <- IO(
        assert(
          elapsed >= delay.toMillis && elapsed <= delay.toMillis + 1500L,
          s"到点精度：期望 [${delay.toMillis}ms, ${delay.toMillis + 1500}ms]，实测 ${elapsed}ms"
        )
      )
      // ⑤ 再等一拍：确认没有第二次投递（一次性）。
      again <- tryTake(inbox, 250.millis)
      _ <- IO(assertEquals(again, None, "scheduleOnce 是一次性的——到点投一条后不得重复投递"))
      _ <- system.stopAll
    yield ()
  }

  test("scheduleOnce 对已停的 actor 不投递、不抛错（探活 + fail-soft）") {
    for
      system <- IO(ActorSystem("schedule-once-stopped"))
      ctxRef <- Deferred[IO, ActorContext[Probe]]
      handled <- Ref.of[IO, Int](0)
      behavior = Behaviors.setup[Probe] { ctx =>
        ctxRef.complete(ctx) *> IO.pure(Behaviors.receiveMessage { _ =>
          handled.update(_ + 1) *> IO.pure(Behaviors.stopped)
        })
      }
      ref <- system.spawn(behavior, "sched-once-doomed")
      ctx <- ctxRef.get
      _ <- system.stop(ref) // actor 已停（registry 无此 path）
      _ <- ctx.scheduleOnce(80.millis, Tick) // 必须静默丢弃，绝不炸
      _ <- IO.sleep(300.millis)
      count <- handled.get
      _ <- IO(assertEquals(count, 0, "已停的 actor 不该收到任何消息（探活挡住死信）"))
      _ <- system.stopAll
    yield ()
  }
end ActorContextScheduleOnceSpec
