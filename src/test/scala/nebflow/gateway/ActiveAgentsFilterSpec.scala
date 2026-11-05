package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.actor.ActorRef

/**
 * `filterActiveAgents` — the in-flight agent list behind getActiveAgents /
 * the bg-agent dropdown.
 *
 * 团队功能删除批（team-feature-deletion-20261104）阶段 3：本件原有四条 **team
 * busy 门**用例（idle team agent is NOT reported / busy team agent IS reported /
 * team agent transitions out when the turn ends / mixed registry）与 `withBusy`
 * helper 全部删除。它们断言的是 `TeamSessionRegistry.markBusy/markIdle` 的 busymap
 * 门（2026-08-16 Teams ghost-running 修复）——该散表随团队会话登记表本体一并退役，
 * `filterActiveAgents` 现在只按 `taskKinds` 过滤（Team 记录不再出现在在飞列表里，
 * 也不再有 busy 门）。删因：**团队 busy 能力已退役**（不是「跑不过」）。
 *
 * 保留：task-lifecycle 三 kind 的 presence 语义、Root 不报告、sessionBusyStates。
 */
class ActiveAgentsFilterSpec extends FunSuite:

  // filterActiveAgents never touches the actor ref — null is safe here.
  private def rec(
    sessionId: String,
    kind: AgentKind,
    status: AgentStatus = AgentStatus.Idle
  ): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = kind,
      rootSessionId = "root-1",
      status = status
    )

  private def run(registry: Map[String, AgentRecord]): Set[(String, AgentKind)] =
    WebSocketRoutes
      .filterActiveAgents(registry)
      .unsafeRunSync()
      .map(r => (r.sessionId, r.kind))
      .toSet

  test("task-lifecycle kinds are presence-based (no busy gating)") {
    // Delegate/SubTask/Flow/Ephemeral unregister on completion, so registry
    // presence == in flight.
    val registry = Map(
      "del-1" -> rec("del-1", AgentKind.Delegate),
      "sub-1" -> rec("sub-1", AgentKind.SubTask),
      "flow-1" -> rec("flow-1", AgentKind.Flow),
      "eph-1" -> rec("eph-1", AgentKind.Ephemeral)
    )
    val out = run(registry)
    assertEquals(
      out,
      Set(
        ("del-1", AgentKind.Delegate),
        ("sub-1", AgentKind.SubTask),
        ("flow-1", AgentKind.Flow),
        ("eph-1", AgentKind.Ephemeral)
      )
    )
  }

  test("root agents are never reported") {
    val out = run(Map("root-1" -> rec("root-1", AgentKind.Root)))
    assert(out.isEmpty)
  }

  test("sessionBusyStates: only Processing is busy; idle/background wait/unknown are authoritative false") {
    val registry = Map(
      "root-processing" -> rec("root-processing", AgentKind.Root, AgentStatus.Processing),
      // Idle 是后台任务等待期的合法相位，不能因此让 composer 显示不可解释的停止钮。
      "root-bg-wait" -> rec("root-bg-wait", AgentKind.Root, AgentStatus.Idle),
      "root-ask-wait" -> rec("root-ask-wait", AgentKind.Root, AgentStatus.WaitingForUser),
      "delegate-processing" -> rec("delegate-processing", AgentKind.Delegate, AgentStatus.Processing),
      "not-requested" -> rec("not-requested", AgentKind.Root, AgentStatus.Processing)
    )
    val out = WebSocketRoutes.sessionBusyStates(
      registry,
      List("root-processing", "root-bg-wait", "root-ask-wait", "missing", "delegate-processing", "root-processing")
    )
    assertEquals(
      out,
      Map(
        "root-processing" -> true,
        "root-bg-wait" -> false,
        "root-ask-wait" -> false,
        "missing" -> false,
        "delegate-processing" -> true
      )
    )
    assert(!out.contains("not-requested"), "response must stay scoped to requested session ids")
  }
end ActiveAgentsFilterSpec
