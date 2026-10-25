package nebflow.agent

import munit.FunSuite
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentState
import nebflow.core.tools.{BgTaskLedger, BgTaskRegistry}
import nebflow.shared.{Message, MessageRole, PathUtil}
import scala.compiletime.uninitialized

/**
 * 修复批 2026-10-24 spec ②③ 的 actor 侧接线面：
 *
 *  - ② 「完成事件在会话 idle 下不蒸发、唤醒先补投」：账本挂起件在唤醒点的补投
 *    腿（AgentActor.flushPendingLedgerCompletions）——未投递 ⇒ 注入+消费；已在
 *    转录/在途 ⇒ 静默消费（与直投路径判重幂等，绝不双注）；orphan 取证行不参与。
 *  - ③ 「有在飞任务结束回合必有栅栏或警告」：回合尾门
 *    （AgentFinishTurn.bgTurnEndAccounting）——非空集 = 栅栏必挂 + 新等待期首拍
 *    警告（进 idle 底座消息 = 模型上下文可见「在飞 N 个（id+描述+起止）」，即
 *    spec ① 的 agent 上下文账目面）；空集撤栅栏零警告。
 */
class BgLedgerRedeliverySpec extends FunSuite:

  private var prevRoot: os.Path = uninitialized
  private var tmp: os.Path = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.temp.dir()
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(tmp)

  private val payload =
    "[Background task completed] \"render deck\":\nline-1\nline-2 (exit 0)"

  private def stateWithMessages(sid: Option[String], messages: List[Message]): AgentState =
    AgentState().copy(sessionId = sid, messages = messages)

  private def msg(text: String): Message =
    Message(MessageRole.User, Left(text))

  // ── ② 唤醒补投腿 ──────────────────────────────────────────────────────

  test("R1 未投递的挂起完成事件 ⇒ 唤醒时注入补投 reminder + 消费出账（永不蒸发）"):
    val sid = "sid-r1"
    BgTaskLedger.recordCompletion(sid, "job-1", "render deck", "completed", payload).unsafeRunSync()
    val plan = AgentActor.flushPendingLedgerCompletions(stateWithMessages(Some(sid), Nil), Nil).unsafeRunSync()
    assertEquals(plan.entries.map(_.jobId), List("job-1"), "entry picked up for redelivery")
    assertEquals(plan.consumedJobIds, List("job-1"))
    assertEquals(plan.messages.size, 1)
    val text = plan.messages.head.content.fold(identity, _ => fail("expected Left(text)"))
    assert(text.contains("[Background-task ledger replay]"), text)
    assert(text.contains(payload), "payload (event + output tail) carried into the turn")
    assert(plan.messages.head.source.contains("external"), "replay marked like the direct external-event face")
    assertEquals(BgTaskLedger.pending(sid).unsafeRunSync(), Nil, "consume-on-injection: no second replay")

  test("R2 事件已在转录（直投路径此前注入过）⇒ 静默消费、不重注（判重幂等）"):
    val sid = "sid-r2"
    BgTaskLedger.recordCompletion(sid, "job-2", "render deck", "completed", payload).unsafeRunSync()
    // 转录里已有同 payload（直投注入件的形态）
    val transcript = List(msg("user turn"), msg(payload + "\nextra context"))
    val plan =
      AgentActor.flushPendingLedgerCompletions(stateWithMessages(Some(sid), transcript), Nil).unsafeRunSync()
    assertEquals(plan.messages, Nil, "no duplicate injection")
    assertEquals(plan.consumedJobIds, List("job-2"), "silently consumed")
    assertEquals(BgTaskLedger.pending(sid).unsafeRunSync(), Nil)

  test("R3 在途事件（本回合正在注入的同一完成）⇒ 只消费，处理器本体负责注入"):
    val sid = "sid-r3"
    BgTaskLedger.recordCompletion(sid, "job-3", "render deck", "completed", payload).unsafeRunSync()
    val plan =
      AgentActor.flushPendingLedgerCompletions(stateWithMessages(Some(sid), Nil), List(payload)).unsafeRunSync()
    assertEquals(plan.messages, Nil, "in-flight event is injected by the handler, not the ledger")
    assertEquals(plan.consumedJobIds, List("job-3"))

  test("R4 orphan 取证行不参与补投（等 recordCompletion 升级或留证）"):
    val sid = "sid-r4"
    BgTaskLedger.markOrphaned(sid, List(("job-4", "long build", Some("tail")))).unsafeRunSync()
    val plan = AgentActor.flushPendingLedgerCompletions(stateWithMessages(Some(sid), Nil), Nil).unsafeRunSync()
    assertEquals(plan.messages, Nil, "empty-payload orphans are never injected")
    assertEquals(plan.consumedJobIds, Nil, "orphans stay in the ledger as evidence")
    assertEquals(BgTaskLedger.pending(sid).unsafeRunSync().map(_.kind), List("orphan"))

  test("R5 空账本/无会话 ⇒ 零开销 no-op（唤醒主路径零行为漂移）"):
    val empty = AgentActor.flushPendingLedgerCompletions(stateWithMessages(Some("sid-r5"), Nil), Nil).unsafeRunSync()
    assertEquals(empty, AgentActor.LedgerFlushPlan(Nil, Nil, Nil))
    val noSid = AgentActor.flushPendingLedgerCompletions(stateWithMessages(None, Nil), Nil).unsafeRunSync()
    assertEquals(noSid, AgentActor.LedgerFlushPlan(Nil, Nil, Nil))

  // ── ③ 回合尾栅栏门 ────────────────────────────────────────────────────

  test("W1 有自有在飞任务结束回合 ⇒ 栅栏必挂 + 警告消息（新等待期首拍）"):
    val sid = "sid-w1"
    BgTaskRegistry.register("job-w1", sid, "render deck", kind = "local").unsafeRunSync()
    try
      val st = stateWithMessages(Some(sid), List(msg("work done")))
      val accounting = AgentFinishTurn.bgTurnEndAccounting(st).unsafeRunSync()
      assert(accounting.isDefined, "turn end with in-flight tasks produces the accounting warning")
      val text = accounting.get.content.fold(identity, _ => fail("expected Left(text)"))
      assert(text.contains("1 background task"), text)
      assert(text.contains("job-w1") && text.contains("render deck"), text)
      assertEquals(accounting.get.source, Some("system"))
      assert(BgTaskLedger.barrierOf(sid).unsafeRunSync().isDefined, "barrier MUST be armed (必有栅栏)")
      // 同集第二个回合尾：栅栏保持、警告不重发（防逐轮刷转录）
      val again = AgentFinishTurn.bgTurnEndAccounting(st).unsafeRunSync()
      assertEquals(again, None, "same wait set does not re-warn")
      assert(BgTaskLedger.barrierOf(sid).unsafeRunSync().isDefined)
    finally BgTaskRegistry.unregisterSession(Some(sid)).unsafeRunSync()

  test("W2 子代理挂靠任务（rootSessionId=本会话）不灌水父会话回合尾"):
    val parentSid = "sid-w2-parent"
    BgTaskRegistry
      .register(
        "job-child",
        "delegate-child-1",
        "child task",
        kind = "local",
        rootSessionId = parentSid
      )
      .unsafeRunSync()
    try
      val accounting =
        AgentFinishTurn.bgTurnEndAccounting(stateWithMessages(Some(parentSid), Nil)).unsafeRunSync()
      assertEquals(accounting, None, "own-task filter: child-owned tasks are the child's accounting")
    finally BgTaskRegistry.unregisterSession(Some("delegate-child-1")).unsafeRunSync()

  test("W3 无在飞任务结束回合 ⇒ 无警告 + 撤栅栏（正常收尾零行为漂移）"):
    val sid = "sid-w3"
    BgTaskLedger.armBarrier(sid, List("job-stale")).unsafeRunSync()
    val accounting =
      AgentFinishTurn.bgTurnEndAccounting(stateWithMessages(Some(sid), Nil)).unsafeRunSync()
    assertEquals(accounting, None)
    assertEquals(BgTaskLedger.barrierOf(sid).unsafeRunSync(), None, "stale barrier cleared on a clean turn end")
end BgLedgerRedeliverySpec
