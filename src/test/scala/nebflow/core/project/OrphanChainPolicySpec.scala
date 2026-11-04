package nebflow.core.project

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

/**
 * 缺陷六 · 孤儿链（引擎会话治理专项，作者令 2026-11-03 14:52）机械断言。
 *
 * 改前红：`recoverDelegateScopes` 无条件认领/续跑死属主作用域的 Running/Interrupted
 * 节点，零孤儿标记、零通知 ⇒ 「链标孤儿 + 通知到达 + 节点不无限推进」三条全红。
 * 改后绿：[[OrphanChainPolicy]]（默认 `pause`）——链台账落 paused、`chain-orphaned`
 * 事件逐链留痕、根会话收到通知、**零**节点续跑动作。
 */
class OrphanChainPolicySpec extends CatsEffectSuite:

  private final case class FakePort(
    chains: List[String],
    remaining: Int,
    paused: Ref[IO, List[String]],
    events: Ref[IO, List[(String, Int)]],
    notices: Ref[IO, List[String]],
    resumed: Ref[IO, Int]
  ) extends OrphanChainPolicy.Port:
    def scopeState = IO.pure((chains, remaining))
    def pauseChain(chainId: String) = paused.update(_ :+ chainId)
    def event(chainId: String, remaining: Int) = events.update(_ :+ (chainId, remaining))
    override def notifyRoot(scope: String, chains: List[String], remaining: Int) =
      notices.update(_ :+ s"$scope|${chains.mkString(",")}|$remaining")
    /** 本假端口上「节点续跑」是可观测动作：孤儿处置必须**零**次（节点不无限推进）。 */
    def resumeNode(node: String): IO[Unit] = resumed.update(_ + 1)

  private def mk(chains: List[String], remaining: Int) =
    for
      paused <- Ref.of[IO, List[String]](Nil)
      events <- Ref.of[IO, List[(String, Int)]](Nil)
      notices <- Ref.of[IO, List[String]](Nil)
      resumed <- Ref.of[IO, Int](0)
      port = FakePort(chains, remaining, paused, events, notices, resumed)
      n <- OrphanChainPolicy.handle(port, "delegate-dead-parent")
      p <- paused.get
      e <- events.get
      no <- notices.get
      r <- resumed.get
    yield (n, p, e, no, r)

  test("① 孤儿判定：属主不在 delegate 注册表 ⇒ 孤儿；在册 ⇒ 非孤儿") {
    assert(OrphanChainPolicy.isOrphan(false), "no owner record = orphan")
    assert(!OrphanChainPolicy.isOrphan(true), "recorded owner = not an orphan (crash-resume leg unchanged)")
    assertEquals(OrphanChainPolicy.action, "pause", "default disposition = pause (author-facing rationale in the report)")
    assert(OrphanChainPolicy.pauseEnabled)
  }

  test("② 孤儿链处置：每条链落 paused + chain-orphaned 事件（含链号与剩余节点数）+ 根通知 + 零节点续跑") {
    mk(List("chain-1", "chain-2"), remaining = 3).map { case (n, paused, events, notices, resumed) =>
      assertEquals(n, 2, "both chains are handled")
      assertEquals(paused, List("chain-1", "chain-2"), "每链落 paused（暂停推进）")
      assertEquals(events.map(_._1), List("chain-1", "chain-2"), "每链一条 chain-orphaned 事件")
      assert(events.forall(_._2 == 3), s"事件携带剩余非终态节点数: $events")
      assertEquals(notices, List("delegate-dead-parent|chain-1,chain-2|3"), "根通知含作用域/链号/剩余数")
      assertEquals(resumed, 0, "🔴 孤儿链的节点绝不续跑（不无限推进）")
    }
  }

  test("③ 回滚开关：action=resume ⇒ 零暂停零事件（走改前续跑腿，行为可回滚）") {
    val prev = sys.props.get("nebflow.boot.orphanChain.action")
    val restore = IO(prev match {
      case Some(v) => sys.props("nebflow.boot.orphanChain.action") = v
      case None    => sys.props.remove("nebflow.boot.orphanChain.action")
    }).void
    (IO(sys.props("nebflow.boot.orphanChain.action") = "resume") *> mk(List("chain-1"), remaining = 1))
      .guarantee(restore)
      .map { case (n, paused, events, _, _) =>
        assertEquals(n, 0)
        assertEquals(paused, Nil)
        assertEquals(events, Nil)
      }
  }

end OrphanChainPolicySpec
