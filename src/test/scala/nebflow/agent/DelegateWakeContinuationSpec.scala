package nebflow.agent

import munit.FunSuite

/**
 * ExternalEvent 唤醒轮 replyTo 判据（诊断修复批 2026-10-24 ·
 * delegate-nebflow-52308ee4 完成结果投递丢失）的判红锚：
 *
 *  - delegate 会话 × background-task 完成事件 ⇒ true（唤醒轮的 Completed 必须
 *    到达 BackoffSupervisor 等待屏障复检门——实案根因：该 Completed 此前
 *    replyTo=None 投递集为空，监督者永停 hold 私有态，父会话收不到结果）；
 *  - node- 项目节点腿（既有 2026-09-05 完成闸批）保持 true，零回归；
 *  - 其余全部 false：delegate × 非 bg-task 事件源（mail/schedule——Completed
 *    语义各归既有，防过早终判）、非 delegate 会话、subtask 轨（无等待屏障，
 *    行为零变化）、node- 判据缺 isFlowNode 时 false。
 */
class DelegateWakeContinuationSpec extends FunSuite:

  private val nodeId = s"${nebflow.core.project.NodeEngine.SessionPrefix}abc123"

  test("delegate × background-task = true（本例根因修复腿）") {
    assert(
      AgentIdle.wakeContinuationApplies(
        isFlowNode = false,
        sessionId = Some("delegate-nebflow-52308ee4"),
        eventSource = "background-task",
        isDelegateSession = true
      )
    )
  }

  test("node- 项目节点腿（既有行为）保持 true") {
    assert(
      AgentIdle.wakeContinuationApplies(
        isFlowNode = true,
        sessionId = Some(nodeId),
        eventSource = "background-task",
        isDelegateSession = false
      )
    )
  }

  test("delegate × 非 bg-task 事件源（mail/schedule）= false") {
    assert(
      !AgentIdle.wakeContinuationApplies(
        isFlowNode = false,
        sessionId = Some("delegate-nebflow-52308ee4"),
        eventSource = "mail",
        isDelegateSession = true
      )
    )
    assert(
      !AgentIdle.wakeContinuationApplies(
        isFlowNode = false,
        sessionId = Some("delegate-nebflow-52308ee4"),
        eventSource = "schedule",
        isDelegateSession = true
      )
    )
  }

  test("非 delegate 会话 / subtask / 无会话 = false") {
    assert(
      !AgentIdle.wakeContinuationApplies(
        isFlowNode = false,
        sessionId = Some("subtask-nebflow-abc"),
        eventSource = "background-task",
        isDelegateSession = false
      )
    )
    assert(
      !AgentIdle.wakeContinuationApplies(
        isFlowNode = false,
        sessionId = None,
        eventSource = "background-task",
        isDelegateSession = false
      )
    )
  }

  test("node- 判据缺 isFlowNode = false（身份双判据不松劲）") {
    assert(
      !AgentIdle.wakeContinuationApplies(
        isFlowNode = false,
        sessionId = Some(nodeId),
        eventSource = "background-task",
        isDelegateSession = false
      )
    )
  }

end DelegateWakeContinuationSpec
