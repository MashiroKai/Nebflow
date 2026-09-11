package nebflow.core.processor

import cats.effect.{IO, Ref}
import nebflow.core.project.NodeLifecycle

import scala.concurrent.duration.*

/**
 * **「恢复已接受」回调（`onResumed`）的二分直测 + 失败隔离**（`stuckrec-fix` 节点自建，2026-09-11）。
 *
 * 与 [[StuckRecoveryLedgerWritePointSpec]] 的关系：该 spec 从**行为面**（账本 / 会话存活）
 * 观察写点时点；本 spec 从**API 面**钉死回调语义，两者互为交叉验证：
 *   1. CAS 接受 ⇒ 回调**已触发**（且被恢复会话已重新登记、`hardResumeNode` 仍阻塞在
 *      `startNode`）⇒ 结构性证明「写点在返回值之前」；
 *   2. CAS 被拒（Cancelled 节点）⇒ 回调**零触发**、返回值 `None`、节点原样
 *      ⇒ 负控（拒绝 ⇒ 零写入）的结构性保证；
 *   3. `onResumed` 抛错 ⇒ 恢复腿照常推进（CAS 留痕 + 启动 + 阻塞在 `startNode`）
 *      ⇒ 写失败只留痕、不中断恢复腿。
 *
 * 本文件引用**新增的三参形** `hardResumeNode(sessionId, anchor, onResumed)`，只在改后构建可编译
 * （改前对照由 [[StuckRecoveryLedgerWritePointSpec]] 承担）。
 */
class StuckRecoveryResumeCallbackSpec extends StuckRecoveryFixture:

  test("回调二分直测: CAS 接受 ⇒ onResumed 在 startNode 阻塞前触发；CAS 被拒 ⇒ 零触发") {
    withFixture("cb") { fx =>
      val sidOk = "node-cb-accepted"
      val sidRej = "node-cb-rejected"
      for
        _ <- seedNode(fx.rt.store, "n-cb-ok", sidOk, NodeLifecycle.Running)
        _ <- seedNode(fx.rt.store, "n-cb-rej", sidRej, NodeLifecycle.Cancelled)
        _ <- seedTranscript(fx.res, sidOk)
        _ <- seedTranscript(fx.res, sidRej)
        anchorOk <- fx.rt.engine.probeRecoveryAnchors(sidOk)
        anchorRej <- fx.rt.engine.probeRecoveryAnchors(sidRej)
        // ① 接受腿：回调 = 置位 Ref（可观测「已触发」）
        firedOk <- Ref.of[IO, Boolean](false)
        fiber <- fx.rt.engine.hardResumeNode(sidOk, Some(anchorOk), firedOk.set(true)).start
        okFired <- waitSoft(10.seconds)(firedOk.get)
        alive <- waitSoft(10.seconds)(aliveFlowSession(fx.res, sidOk))
        // 「回调发生在返回值之前」的结构性证据：硬恢复纤维仍阻塞在 startNode
        stillBlocked <- IO.race(fiber.join, IO.sleep(500.millis)).map(_.isRight)
        casOk <- casLogged(fx.ws, "n-cb-ok")
        // ② 拒绝腿：Cancelled 节点 ⇒ CAS 必拒
        firedRej <- Ref.of[IO, Boolean](false)
        rej <- fx.rt.engine.hardResumeNode(sidRej, Some(anchorRej), firedRej.set(true))
        rejFired <- firedRej.get
        nodeRej <- fx.rt.store.getNode("n-cb-rej")
        casRej <- casLogged(fx.ws, "n-cb-rej")
        _ <- IO(println(s"[FIX-READING] 回调-接受腿: fired=$okFired resumedAlive=$alive " +
          s"stillBlockedInStartNode=$stillBlocked casLog=$casOk"))
        _ <- IO(println(s"[FIX-READING] 回调-拒绝腿: result=$rej fired=$rejFired node=${nodeRej.map(_.status)} casLog=$casRej"))
      yield
        assert(okFired, "CAS 接受 ⇒ onResumed 必须已触发（写点前移的结构性证明）")
        assert(alive, "回调触发时被恢复会话已重新登记 ⇒ 写点确实早于会话终态")
        assert(stillBlocked, "hardResumeNode 仍阻塞在 startNode ⇒ 回调发生在**返回值之前**")
        assert(casOk, "CAS 接受必须留痕（resumed from stuck）")
        assertEquals(rej, None, "Cancelled 节点 CAS 必须被拒（cancelled = 真终态）")
        assertEquals(rejFired, false, "CAS 被拒 ⇒ onResumed **零触发**（零写入的结构性保证）")
        assertEquals(nodeRej.map(_.status), Some(NodeLifecycle.Cancelled), "拒绝不得改动节点状态")
        assert(!casRej, "CAS 被拒 ⇒ 无 'resumed from stuck' 留痕")
    }
  }

  test("回调失败隔离: onResumed 抛错 ⇒ 恢复腿照常推进（CAS 留痕 + 启动 + 阻塞在 startNode）") {
    withFixture("boom") { fx =>
      val sid = "node-cb-boom"
      for
        _ <- seedNode(fx.rt.store, "n-cb-boom", sid, NodeLifecycle.Running)
        _ <- seedTranscript(fx.res, sid)
        anchor <- fx.rt.engine.probeRecoveryAnchors(sid)
        fiber <- fx.rt.engine.hardResumeNode(sid, Some(anchor),
          IO.raiseError(new RuntimeException("onResumed boom (fixture)"))).start
        casSeen <- waitSoft(15.seconds)(casLogged(fx.ws, "n-cb-boom"))
        alive <- waitSoft(15.seconds)(aliveFlowSession(fx.res, sid))
        stillBlocked <- IO.race(fiber.join, IO.sleep(500.millis)).map(_.isRight)
        _ <- IO(println(s"[FIX-READING] 回调失败隔离: casLog=$casSeen resumedAlive=$alive " +
          s"stillBlockedInStartNode=$stillBlocked"))
      yield
        assert(casSeen, "onResumed 失败不得中断 CAS 留痕（write-failure isolation）")
        assert(alive, "onResumed 失败不得中断恢复启动（被恢复会话仍在运行）")
        assert(stillBlocked, "onResumed 失败后恢复腿仍正常推进到 startNode（阻塞点）")
    }
  }
