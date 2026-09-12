package nebflow.core.flow

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.ActorRef
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.core.flow.RunningFlowRegistry.RunningFlow

/**
 * #407 Mail idle gate —— 纯函数判定矩阵（AC-4）。
 *
 * 用户裁定：2026-08-25 19:53（目标 agent 连同子树全空闲）+ **2026-08-28 01:00
 * 统一裁定**：无论发送者是谁，投递时机一律 = 目标 agent 自身+子树空闲——
 * 08-25 的按发送者区分（root→全 team）废除，isTeamTreeIdle 退役（其场景
 * 由 wiring 层 AC-6 翻转用例覆盖：Nebula→Manager 不再等兄弟成员）。
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

  // ---------- R2 分层地址面：node: 腿绕行本闸（必须显式钉死） ----------

  test("M-2 R2：node:<id> 腿不经 idle gate —— node 会话无 AgentRecord，入队即永久挂起"):
    // ① 纯函数事实：node 会话不进 agentRegistry（registerSession 的唯一调用点 =
    //    core/flow/FlowTreeActor.scala:502），而本闸首条判据是 registry.get(sid).exists
    //    ⇒ 记录不存在 = **不空闲**。若 node: 邮件走 AgentActor 的 queue drain 闸，
    //    等待条件永假 ⇒ 永久挂起（这正是「node: 腿必须绕行」的实证理由）。
    assert(
      !MailIdleGate.isAgentTreeIdle("node-abc-123", Map.empty[String, AgentRecord], Nil),
      "node 会话无 AgentRecord ⇒ 闸判定不空闲（入队即永久挂起）"
    )
    assert(
      !MailIdleGate.isAgentTreeIdle("node-abc-123", Map("t" -> rec("t")), Nil),
      "node 会话不在快照内 ⇒ 同样不空闲（与注册表里有无别的会话无关）"
    )
    // ② 可执行旁证：MailTool 的 node: 腿在**入队之前**分派（MailTool.layeredRoute：
    //    running=ImmediateInput 注入 / wiring·pending=task 追加 / 终态拒绝），
    //    且显式拒绝 delivery=queue ⇒ 结构性不可达 idle gate。
    val system = nebflow.actor.ActorSystem(s"mailidle-r2-${scala.util.Random.nextInt(100000)}")
    val ctx = nebflow.core.tools.ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("disp-sid"),
      isDispatcher = true,
      projectName = Some("p"),
      actorSystem = Some(system)
    )
    try
      val res = nebflow.core.tools.MailTool
        .call(
          JsonObject(
            "address" -> Json.fromString("node:n-1"),
            "message" -> Json.fromString("补充"),
            "delivery" -> Json.fromString("queue")
          ),
          ctx
        )
        .unsafeRunSync()
      res match
        case Left(err) =>
          assert(err.message.contains("always immediate"), s"node: 腿必须拒绝 queue 模式，got: ${err.message}")
          assert(err.message.contains("delivery=queue"), "错误须指明修复动作（去掉 delivery=queue）")
        case Right(v) => fail(s"node: + queue 必须显式报错（不得落入 queue 闸），got: $v")
    finally system.stopAll.unsafeRunSync()

end MailIdleGateSpec
