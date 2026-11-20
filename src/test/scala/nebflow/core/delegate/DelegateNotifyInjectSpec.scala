package nebflow.core.delegate

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}

import scala.concurrent.duration.*

/**
 * **DelegateNotify.inject 真的把消息送出去了吗**（P0 投递地址批 2026-11-02 · 二次根因）。
 *
 * == 为什么需要这份 spec ==
 * P0 把 delegate 作用域引擎的 dispatch-notify 投递目标接到
 * `DelegateNotify.inject(resources.agentRegistry, delegateId, _)`（
 * [[nebflow.core.project.DelegateFlowHosts.mount]]）。该函数**返回 `IO[Boolean]`**
 * 且 `true` 的语义被 [[nebflow.core.project.DispatchNotify.flushBatch]] 用作「已投达」
 * 判据（true ⇒ 逐件 `markSent` + `dispatch-notify-delivery outcome=delivered`）。
 *
 * 但 `ref ! msg` 在 [[nebflow.actor.LocalActorRef]] 里是 **`IO[Unit]`（`queue.offer`）**
 * ——它是一个**待执行的 IO 值**，不是副作用调用。改前实现把它构造出来后**直接丢弃**，
 * 然后 `IO.pure(true)`：消息从未入队，调用方却收到「已投达」。
 * ⇒ 审计面写着 `outcome=delivered`、节点被 `markSent`（退出补投候选集），
 * 创建者 delegate 一条都没收到——**「通知说发了、其实没发」，且不再重试**。
 * 这是本批 RCA 记的「无人收到通知」之外的第二形态：连滞留补投的兜底都被记账关掉了。
 *
 * 这两条用例把该语义钉死：
 *   - [[T1]]：目标 live ⇒ 消息**真的进了 actor 队列**（捕获 ImmediateInput，逐字对文本 +
 *     `source=delegate-notify`），且返回 `true`；
 *   - [[T2]]：目标不在注册表 ⇒ 返回 `false` 且零投递（调用方据此走滞留分支）。
 *
 * T1/T2 在**改前实现上 T1 必红**（捕获 0 条，`queue.offer` 的 IO 从未运行）——
 * 验红读数见提交说明。
 */
class DelegateNotifyInjectSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  /** 有界轮询等 `ref ! msg`（fire-and-forget：offer 与投递不同刻）。 */
  private def awaitImms(
    recorded: Ref[IO, List[AgentCommand]],
    min: Int,
    timeoutMs: Long = 5_000L
  ): IO[List[AgentCommand.ImmediateInput]] =
    def go(deadline: Long): IO[List[AgentCommand.ImmediateInput]] =
      recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m }).flatMap { ms =>
        if ms.size >= min || System.currentTimeMillis() >= deadline then IO.pure(ms)
        else IO.sleep(20.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  test(
    "T1: a live target ACTUALLY receives the injection (the `ref ! msg` IO is executed, not discarded) and inject reports true"
  ) {
    val system = ActorSystem(s"dn-inject-${scala.util.Random.nextInt(1000000)}")
    val sid = "delegate-general-inject01"
    val text = "[dispatch-notify] 节点 'N1' (n-1) failed — inject seam delivery probe"
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-inject-${scala.util.Random.nextInt(1000000)}")
      registry <- Ref.of[IO, Map[String, AgentRecord]](
        Map(sid -> AgentRecord(sessionId = sid, ref = ref, kind = AgentKind.Delegate, rootSessionId = sid))
      )
      ok <- DelegateNotify.inject(registry, sid, text)
      got <- awaitImms(recorded, 1)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(ok, "T1: inject must report true for a registered (live) target")
      assertEquals(
        got.size,
        1,
        "T1: the message must ACTUALLY be enqueued — a discarded `ref ! msg` IO means zero deliveries while still " +
          "reporting `delivered` downstream (that is the defect this test pins)"
      )
      assertEquals(got.head.text, text, "T1: the delivered text must be the notification verbatim")
      assertEquals(got.head.source, Some(DelegateNotify.Source), "T1: the attribution source must be delegate-notify")
      assertEquals(got.head.eventType, Some("flow-notify"), "T1: the turn-boundary event type is flow-notify")
      assertEquals(got.head.fromUser, false, "T1: engine-originated, not a human input")
    end for
  }

  test("T2: a target that is not in the registry yields false and delivers nothing (the caller's park branch)") {
    val system = ActorSystem(s"dn-inject2-${scala.util.Random.nextInt(1000000)}")
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-inject2-${scala.util.Random.nextInt(1000000)}")
      registry <- Ref.of[IO, Map[String, AgentRecord]](
        Map(
          "other-sid" -> AgentRecord(
            sessionId = "other-sid",
            ref = ref,
            kind = AgentKind.Delegate,
            rootSessionId = "other-sid"
          )
        )
      )
      ok <- DelegateNotify.inject(registry, "delegate-general-absent", "should not arrive")
      got <- recorded.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(!ok, "T2: an absent target must report false so flushBatch parks the件 instead of markSent")
      assertEquals(got, Nil, "T2: nothing may be delivered to any other session")
    end for
  }

end DelegateNotifyInjectSpec
