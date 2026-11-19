package nebflow.agent

import munit.FunSuite
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentState, AgentStatus}
import nebflow.core.flow.MailIdleGate
import nebflow.shared.MessageRole

/**
 * 平台修复批「delegate 回执投递可靠性」（作者令 2026-10-21）—— actor 侧决策面的
 * 规格（[[AgentActor.heldFlushPlan]]「先补注入未读回执再处理事件」+ fix 4 等待
 * 停靠态的等待屏障语义面）：
 *
 *  - F1 空 held 队列 ⇒ None（调用路径逐字现状，零注入影响）；
 *  - F2 非空 ⇒ 计划：reminder 前置于事件、pendingEvents/outstanding 清空（幻影
 *    屏障槽位归还——issue #31 的闭环面）、其余 execution 字段原样保真；
 *  - F3 reminder 内容承载全部回执载荷（buildEventReminder 单点组装，LLM 可见）；
 *  - W1 MailIdleGate：Waiting 视同可投递（停靠态的已产出报告照样走投递链）；
 *  - W2 Waiting 状态存在且非 Idle/Processing——面板快照与等待豁免的判别词面。
 */
class HeldDeliveryFlushSpec extends FunSuite:

  private def heldEvent(payload: String, cid: String): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent("delegate", "completed", payload, correlationId = Some(cid))

  private def stateWith(pending: List[AgentCommand.ExternalEvent], outstanding: Int): AgentState =
    AgentState().copy(execution =
      AgentState().execution.copy(
        pendingEvents = pending,
        outstandingSubagentResults = outstanding,
        pendingImmediateInputs = List(AgentCommand.ImmediateInput("keep-me"))
      )
    )

  test("F1 空 held 队列 ⇒ None（本事件路径逐字现状）"):
    assertEquals(AgentActor.heldFlushPlan(stateWith(Nil, 0)), None)

  test("F2 非空 ⇒ 前置于事件的 reminder + 清空 held 与幻影槽位、其余 execution 保真"):
    val st = stateWith(List(heldEvent("result-A", "sub-1"), heldEvent("result-B", "sub-2")), outstanding = 1)
    val plan = AgentActor.heldFlushPlan(st).get
    // 先补注入：reminder 消息存在且排在事件前（调用点把 messages 前置于用户/事件消息）。
    assertEquals(plan.messages.size, 1)
    val reminderText = plan.messages.head.content.fold(identity, _ => fail("expected Left(text)"))
    assert(reminderText.contains("result-A"), "payload A carried to the LLM")
    assert(reminderText.contains("result-B"), "payload B carried to the LLM")
    assertEquals(plan.events.size, 2)
    // 幻影屏障槽位归还：outstanding 清零、held 清空。
    assertEquals(plan.state.execution.pendingEvents, Nil)
    assertEquals(plan.state.execution.outstandingSubagentResults, 0)
    // 其余 execution 字段保真（flush 不碰其它队列）。
    assertEquals(plan.state.execution.pendingImmediateInputs.size, 1)

  test("F3 reminder 沿用 buildEventReminder 单点形态（编号行 + source=external）"):
    val st = stateWith(List(heldEvent("payload-line", "sub-9")), 0)
    val plan = AgentActor.heldFlushPlan(st).get
    val msg = plan.messages.head
    assertEquals(msg.role, MessageRole.User)
    assertEquals(msg.source, Some("external"))
    val text = msg.content.fold(identity, _ => fail("expected Left(text)"))
    assert(text.startsWith("<system-reminder>\n1. payload-line"), text)

  // ── fix 4 等待停靠态（等待屏障语义）────────────────────────────────────────

  private val nullRef: nebflow.actor.ActorRef[AgentCommand] = null

  private def rec(status: AgentStatus): AgentRecord =
    AgentRecord("s1", nullRef, AgentKind.Delegate, "s1", status = status)

  test("W1 Waiting 视同可投递（MailIdleGate）——停靠态的 Mail/合并提醒可达"):
    assert(MailIdleGate.isAgentTreeIdle("s1", Map("s1" -> rec(AgentStatus.Waiting))), "Waiting is delivery-idle")

  test("W2 Waiting 既不判 Processing 也不混同 Idle（等待豁免的判别词面）"):
    assert(AgentStatus.Waiting != AgentStatus.Idle)
    assert(AgentStatus.Waiting != AgentStatus.Processing)
    assert(AgentStatus.Waiting != AgentStatus.WaitingForUser)

end HeldDeliveryFlushSpec
