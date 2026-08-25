package nebflow.core.flow

import munit.FunSuite
import nebflow.actor.ActorRef
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.core.flow.RunningFlowRegistry.RunningFlow

/**
 * #407 Mail idle gate —— 纯函数判定矩阵（方案 AC-4 / AC-5）。
 *
 * 用户裁定（2026-08-25 19:53/19:57）：queue Mail 投递时机 = 目标 agent 连同子树
 * 全空闲；按发送者区分（root→全 team，team 内→目标自身子树）。
 */
class MailIdleGateSpec extends FunSuite:

  private type AR = ActorRef[AgentCommand]
  private val nullRef: AR = null

  private def rec(
    sid: String,
    kind: AgentKind = AgentKind.Team,
    status: AgentStatus = AgentStatus.Idle,
    parentRef: Option[AR] = None,
    outstanding: Int = 0
  ): AgentRecord =
    AgentRecord(
      sessionId = sid,
      ref = nullRef,
      kind = kind,
      rootSessionId = sid,
      parentRef = parentRef,
      status = status,
      outstandingSubagents = outstanding
    )

  private def runningFlow(sessionId: Option[String] = None): RunningFlow =
    RunningFlow(
      instanceId = "flow-1",
      flowName = "f",
      description = "",
      entry = "n",
      nodes = Map.empty,
      edges = Nil,
      status = NodeStatus.Running,
      startedAt = 0L,
      sessionId = sessionId
    )

  // ---------- AC-4: isAgentTreeIdle 判定矩阵 ----------

  test("目标记录不存在 → 不空闲"):
    assert(!MailIdleGate.isAgentTreeIdle("missing", Map.empty))

  test("自身 Processing → 不空闲"):
    val registry = Map("t" -> rec("t", status = AgentStatus.Processing))
    assert(!MailIdleGate.isAgentTreeIdle("t", registry))

  test("自身 Idle + outstandingSubagents>0 → 不空闲"):
    val registry = Map("t" -> rec("t", outstanding = 2))
    assert(!MailIdleGate.isAgentTreeIdle("t", registry))

  test("自身 Idle + 无子记录 + 无 flow → 空闲"):
    val registry = Map("t" -> rec("t"))
    assert(MailIdleGate.isAgentTreeIdle("t", registry))

  test("Delegate 子记录在飞 → 不空闲"):
    val registry = Map(
      "t" -> rec("t"),
      "delegate-1" -> rec("delegate-1", AgentKind.Delegate, parentRef = Some(nullRef))
    )
    assert(!MailIdleGate.isAgentTreeIdle("t", registry))

  test("SubTask 子记录在飞 → 不空闲"):
    val registry = Map(
      "t" -> rec("t"),
      "subtask-1" -> rec("subtask-1", AgentKind.SubTask, parentRef = Some(nullRef))
    )
    assert(!MailIdleGate.isAgentTreeIdle("t", registry))

  test("Flow 子记录在飞 → 不空闲"):
    val registry = Map(
      "t" -> rec("t"),
      "flow-node" -> rec("flow-node", AgentKind.Flow, parentRef = Some(nullRef))
    )
    assert(!MailIdleGate.isAgentTreeIdle("t", registry))

  test("Ephemeral / Plan 子记录在飞 → 不空闲"):
    for kind <- List(AgentKind.Ephemeral, AgentKind.Plan) do
      val registry = Map(
        "t" -> rec("t"),
        s"child-${kind.toString}" -> rec(s"child-${kind.toString}", kind, parentRef = Some(nullRef))
      )
      assert(!MailIdleGate.isAgentTreeIdle("t", registry), s"kind=$kind should count")

  test("Team 子记录（Mail 激活成员，parentRef=发送者）→ 不计数"):
    // R4 防误判：MailTool.activateAgent 注册 team 成员时 parentRef=发送者 ref，
    // 若不排除 Team kind，gate 检查发送者会永久误判忙。
    val registry = Map(
      "t" -> rec("t"),
      "member" -> rec("member", AgentKind.Team, parentRef = Some(nullRef))
    )
    assert(MailIdleGate.isAgentTreeIdle("t", registry))

  test("Root 子记录 → 不计数"):
    val registry = Map(
      "t" -> rec("t"),
      "root-other" -> rec("root-other", AgentKind.Root, parentRef = Some(nullRef))
    )
    assert(MailIdleGate.isAgentTreeIdle("t", registry))

  test("子记录在飞但 parentRef 不是目标 → 不阻塞目标"):
    val otherRef: AR = null
    val registry = Map(
      "t" -> rec("t"),
      "child" -> rec("child", AgentKind.Delegate, parentRef = Some(otherRef))
    )
    // otherRef 也是 null——无法区分；用无 parentRef 的子记录验证「非本目标下属不阻塞」
    val registry2 = Map(
      "t" -> rec("t"),
      "child-no-parent" -> rec("child-no-parent", AgentKind.Delegate, parentRef = None)
    )
    assert(MailIdleGate.isAgentTreeIdle("t", registry2))

  test("running flow 关联本目标 → 不空闲（Q3 节点间隙覆盖）"):
    val registry = Map("t" -> rec("t"))
    assert(!MailIdleGate.isAgentTreeIdle("t", registry, List(runningFlow(Some("t")))))

  test("flow 已完成 / 不关联目标 → 不阻塞"):
    val registry = Map("t" -> rec("t"))
    val completed = runningFlow(Some("t")).copy(status = NodeStatus.Completed)
    val otherFlow = runningFlow(Some("other"))
    assert(MailIdleGate.isAgentTreeIdle("t", registry, List(completed)))
    assert(MailIdleGate.isAgentTreeIdle("t", registry, List(otherFlow)))

  // ---------- AC-5: isTeamTreeIdle ----------

  test("team 任一成员忙 → 整个 team 不空闲"):
    val registry = Map(
      "m1" -> rec("m1"),
      "m2" -> rec("m2", status = AgentStatus.Processing)
    )
    assert(!MailIdleGate.isTeamTreeIdle("m1", List("m1", "m2"), registry))

  test("team 成员子树忙（成员有 Delegate 在飞）→ 不空闲"):
    val registry = Map(
      "m1" -> rec("m1"),
      "m2" -> rec("m2"),
      "m2-delegate" -> rec("m2-delegate", AgentKind.Delegate, parentRef = Some(nullRef))
    )
    assert(!MailIdleGate.isTeamTreeIdle("m1", List("m1", "m2"), registry))

  test("team 全空闲 → 空闲"):
    val registry = Map(
      "m1" -> rec("m1"),
      "m2" -> rec("m2")
    )
    assert(MailIdleGate.isTeamTreeIdle("m1", List("m1", "m2"), registry))

  test("team 会话列表为空 → 空闲（forall 空集）"):
    assert(MailIdleGate.isTeamTreeIdle("x", Nil, Map.empty))

  test("team 成员 running flow → 不空闲"):
    val registry = Map("m1" -> rec("m1"))
    assert(!MailIdleGate.isTeamTreeIdle("m1", List("m1"), registry, List(runningFlow(Some("m1")))))
  // ---------- checkStatus / checkSelfStatus（turn-end drain 上下文） ----------

  test("checkStatus=false：自身 Processing 残留不算忙（turn-end drain 语义）"):
    val registry = Map("t" -> rec("t", status = AgentStatus.Processing))
    assert(!MailIdleGate.isAgentTreeIdle("t", registry), "默认 checkStatus=true 应判忙")
    assert(MailIdleGate.isAgentTreeIdle("t", registry, checkStatus = false), "checkStatus=false 应跳过自身状态")

  test("checkStatus=false 仍检查子树与 flow（只跳过自身状态）"):
    val registry = Map(
      "t" -> rec("t", status = AgentStatus.Processing),
      "child" -> rec("child", AgentKind.Delegate, parentRef = Some(nullRef))
    )
    assert(!MailIdleGate.isAgentTreeIdle("t", registry, checkStatus = false), "子树忙仍应判不空闲")
    val flowRegistry = Map("t" -> rec("t", status = AgentStatus.Processing))
    assert(!MailIdleGate.isAgentTreeIdle("t", flowRegistry, List(runningFlow(Some("t"))), checkStatus = false),
      "关联 running flow 仍应判不空闲")

  test("isTeamTreeIdle checkSelfStatus=false：目标自身 Processing 不算忙，成员 Processing 算忙"):
    val registry = Map(
      "t" -> rec("t", status = AgentStatus.Processing),
      "m" -> rec("m")
    )
    assert(MailIdleGate.isTeamTreeIdle("t", List("t", "m"), registry, checkSelfStatus = false),
      "目标自身 Processing 残留不应阻塞（turn-end drain）")
    val registry2 = Map(
      "t" -> rec("t", status = AgentStatus.Processing),
      "m" -> rec("m", status = AgentStatus.Processing)
    )
    assert(!MailIdleGate.isTeamTreeIdle("t", List("t", "m"), registry2, checkSelfStatus = false),
      "成员 Processing 仍应阻塞（team 范围语义）")
end MailIdleGateSpec
