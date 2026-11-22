package nebflow.gateway

import munit.FunSuite
import nebflow.core.scheduler.{DecisionAnswer, DecisionOption, ScheduledTask, TodoDecision}

/** 待办决策作答 → agent 信箱载荷（P0 送达腿）纯函数单测。 */
class TodoDecisionAnswerPayloadSpec extends FunSuite:

  private val task = ScheduledTask(
    id = "t1",
    sessionId = "s1",
    content = "买菜",
    triggerAt = 0L,
    createdAt = 0L,
    name = Some("买菜"),
    decision = Some(
      TodoDecision(
        question = "去哪家？",
        options = List(DecisionOption("o1", "盒马"), DecisionOption("o2", "叮咚"))
      )
    )
  )

  test("载荷以选项 title（人话）而非 id 呈现，并带标识行") {
    val p = WsTasksWorkspaceHandlers.decisionAnswerPayload(task, DecisionAnswer(List("o2"), None, 1L, "mobile"))
    assert(p.startsWith("[TODO-DECISION-ANSWER]"), p)
    assert(p.contains("所选：叮咚"), p)
    assert(p.contains("问题：去哪家？"), p)
    assert(!p.contains("Some("), p)
  }

  test("自由文本单列为补充文字") {
    val p = WsTasksWorkspaceHandlers.decisionAnswerPayload(
      task,
      DecisionAnswer(Nil, Some("自己去"), 1L, "desktop")
    )
    assert(p.contains("所选：（无）"), p)
    assert(p.contains("补充文字：自己去"), p)
  }
