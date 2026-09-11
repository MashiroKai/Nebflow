package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import nebflow.agent.AgentEvent
import nebflow.core.project.NodeLifecycle

import scala.concurrent.duration.*

/**
 * **硬约束② 观察用例（改前 / 改后逐字同源）**（`stuckrec-fix` 节点自建，2026-09-11）。
 *
 * 被修缺陷：恢复成功腿的账本写点原位于 `hardResumeNode` **返回之后**，而它尾部 `startNode`
 * 同步等到被恢复会话的**整段终态** ⇒ 恢复后会话运行期间 `lastRecoveryAt == 0`、双基线未写，
 * 冷却窗（`recoveryGate`）与互斥点 2（`loopDetectedAfterRecovery`）**不生效**。
 *
 * 两条用例（**必须给被恢复会话存活期的实时读数**，不得以「预置账本」形态代替）：
 *   1. 观察：CAS 接受且 `resumedAlive=true` ⇒ `lastRecoveryAt != 0` + `strikeBaseline` /
 *      `fpBaseline` 已按恢复时快照写入 + 冷却窗/互斥点 2 当场命中；
 *   2. 负控：CAS 被拒（并发终态）⇒ 账本**零写入**、零冷却窗误设。
 *
 * 本文件**只用基线已存在的 API**（`TaskStuckWatcher.scan` 两参直测不涉及），故可在改前构建
 * （`9c052707`）上原样复跑产出对照读数。
 */
class StuckRecoveryLedgerWritePointSpec extends StuckRecoveryFixture:

  // ══ 1. 观察：被恢复会话**存活期**的实时读数 ═════════════════════════════════

  test("硬约束②-观察: CAS 接受瞬间写账本 ⇒ 被恢复会话存活期 lastRecoveryAt + 双基线已置位（冷却窗/互斥点 2 当场生效）") {
    withFixture("t1") { fx =>
      val sid = "node-ledger-live"
      for
        _ <- seedNode(fx.rt.store, "n-live", sid, NodeLifecycle.Running)
        _ <- seedTranscript(fx.res, sid)
        acked <- Ref.of[IO, List[AgentEvent]](Nil)
        bridge <- fx.system.spawn(mkSuspendAckEvt(fx.res, acked), "t1-bridge")
        cmdSink <- mkCommandSink(fx.system, "t1-agent")
        rec <- putStuckRecord(fx.res, sid, fx.rt.engine.rootSessionId, cmdSink, Some(bridge))
        ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map.empty)
        _ <- driveL3(fx, ledger)
        casSeen <- waitSoft(20.seconds)(casLogged(fx.ws, "n-live"))
        _ <- IO.sleep(3.seconds)
        live <- aliveFlowSession(fx.res, sid)
        ledMap <- ledger.get
        node <- fx.rt.store.getNode("n-live")
        now <- IO(System.currentTimeMillis())
        led = ledMap.getOrElse(sid, TaskStuckWatcher.RecoveryLedger())
        gate = TaskStuckWatcher.recoveryGate(rec, led, now)
        strikeRise = TaskStuckWatcher.loopDetectedAfterRecovery(
          rec.copy(loopStrikeCount = rec.loopStrikeCount + 1), led, now)
        fpChange = TaskStuckWatcher.loopDetectedAfterRecovery(
          rec.copy(lastLoopFp = "fp-new-after-resume"), led, now)
        _ <- IO(println(s"[FIX-READING] T1 casSeen=$casSeen resumedAlive=$live node=${node.map(_.status)} led=$led"))
        _ <- IO(println(s"[FIX-READING] T1 recoveryGate=${gate.map(g => (g.kind, g.report))} " +
          s"loopDetected(strikeRise=$strikeRise, fpChange=$fpChange) rec.baseline(loopStrikeCount=${rec.loopStrikeCount}, " +
          s"lastLoopFp='${rec.lastLoopFp}')"))
      yield
        assert(casSeen, "前提：CAS 必须已接受（事件面 'resumed from stuck' 留痕）")
        assert(live, s"观察臂要求被恢复会话**仍在运行**（resumedAlive=true）——否则不构成观察条件；实际=$live")
        assert(led.lastRecoveryAt != 0L,
          s"硬约束②：恢复已接受 ⇒ lastRecoveryAt 必须在会话存活期即置位（不得等会话终态）；实际=$led")
        assertEquals(led.strikeBaseline, rec.loopStrikeCount, "互斥点 2 的 strike 基线必须在 CAS 接受瞬间写入")
        assertEquals(led.fpBaseline, rec.lastLoopFp, "互斥点 2 的指纹基线必须在 CAS 接受瞬间写入")
        assertEquals(gate.map(_.kind), Some(TaskStuckWatcher.GateCooldown),
          s"冷却窗必须在被恢复会话运行期即生效；实际 gate=$gate")
        assert(strikeRise, "互斥点 2（跨轮命中计数上升）必须在会话运行期生效")
        assert(fpChange, "互斥点 2（指纹变化）必须在会话运行期生效")
    }
  }

  // ══ 2. 负控：CAS 被拒（并发终态）⇒ 零写入 ══════════════════════════════════

  test("负控: CAS 被拒（并发终态）⇒ 账本零写入、零冷却窗误设") {
    withFixture("t2") { fx =>
      val sid = "node-ledger-rejected"
      for
        _ <- seedNode(fx.rt.store, "n-rej", sid, NodeLifecycle.Running)
        _ <- seedTranscript(fx.res, sid)
        acked <- Ref.of[IO, List[AgentEvent]](Nil)
        // 并发终态化：挂起 ack 的同一瞬间把节点翻成 cancelled ⇒ CAS 前置条件（Running）不再成立
        concurrentTerminal = fx.rt.store.mutate(s => s.copy(nodes =
          s.nodes.updated("n-rej", s.nodes("n-rej").copy(status = NodeLifecycle.Cancelled)))).void
        bridge <- fx.system.spawn(mkSuspendAckEvt(fx.res, acked, concurrentTerminal), "t2-bridge")
        cmdSink <- mkCommandSink(fx.system, "t2-agent")
        rec <- putStuckRecord(fx.res, sid, fx.rt.engine.rootSessionId, cmdSink, Some(bridge))
        ledger <- Ref.of[IO, Map[String, TaskStuckWatcher.RecoveryLedger]](Map.empty)
        _ <- driveL3(fx, ledger)
        _ <- IO.sleep(4.seconds)
        ledMap <- ledger.get
        node <- fx.rt.store.getNode("n-rej")
        resumeLog <- casLogged(fx.ws, "n-rej")
        now <- IO(System.currentTimeMillis())
        led = ledMap.getOrElse(sid, TaskStuckWatcher.RecoveryLedger())
        gate = TaskStuckWatcher.recoveryGate(rec, led, now)
        _ <- IO(println(s"[FIX-READING] T2 node=${node.map(_.status)} led=$led gate=${gate.map(g => (g.kind, g.report))} " +
          s"resumeLog=$resumeLog"))
      yield
        assert(led.chainAttempts == 1, s"前提：恢复尝试已被消费（= 恢复腿确实推进到 CAS 关卡）；实际=$led")
        assertEquals(node.map(_.status), Some(NodeLifecycle.Cancelled), "并发终态保持（恢复不得复活真终态）")
        assertEquals(led.lastRecoveryAt, 0L, s"负控：CAS 被拒 ⇒ 冷却窗基点**零写入**；实际=$led")
        assertEquals(led.strikeBaseline, 0, "负控：CAS 被拒 ⇒ strike 基线零写入")
        assertEquals(led.fpBaseline, "", "负控：CAS 被拒 ⇒ 指纹基线零写入")
        assert(gate.map(_.kind) != Some(TaskStuckWatcher.GateCooldown), s"CAS 被拒 ⇒ 不得出现冷却窗误设；实际 gate=$gate")
        assert(!resumeLog, "CAS 被拒 ⇒ 事件面不得出现 'resumed from stuck' 留痕")
    }
  }
