package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.delegate.{DelegateRegistry, DualModeConfig}
import nebflow.core.tools.{AgentFlowTool, SubagentTool, ToolContext}
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * 双模式批（2026-10-28 · 设计 §4.2/§5.1）工具面分叉 spec：
 *
 *  - F1-F4 **挂载面单点分叉**（buildAllowedToolSet 尾段 delegateSession 腿）：
 *    kernel = 编排面整体退场（Subagent/AgentFlow/node 四件全摘——极简内核执行器）；
 *    dispatcher = Subagent 摘除、node 四件保留（编排本体）；`delegateMode=None`
 *    （闸关/未判定）与 `delegateSession=false` ⇒ 本段全部分叉失效（现状逐字节）。
 *  - F5/F6 **工具内第二保险**（防 schema 注入/显式调用逃逸）：kernel/dispatcher
 *    会话显式拒（DUAL_MODE_FACE_REMOVED）；闸关 = 现行为腿（参数校验错误，绝不
 *    出双模式文案）。
 *  - F7 mode 参数声明面：DelegateTool schema 携 mode enum（kernel|dispatcher）。
 *  - F8 mode 字段落任务行：SubAgentTask 手写 decoder 缺键容忍（旧盘面照读）。
 */
class DualModeToolFaceSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  // buildAllowedToolSet is protected; expose it via a minimal stub (AllowedToolSetSpec 同款探针)。
  private object CoreProbe extends AgentCore:

    def allowedWithDelegateMode(defn: AgentDef, delegateSession: Boolean, delegateMode: Option[String]): Set[String] =
      buildAllowedToolSet(defn, 0, delegateSession = delegateSession, delegateMode = delegateMode)

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "dual-mode-face")
    PathUtil.setDataRoot(tmp / "data")
    System.clearProperty(DualModeConfig.SysPropEnabled)

  override def afterEach(context: munit.AfterEach): Unit =
    System.clearProperty(DualModeConfig.SysPropEnabled)
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def gateOn(): Unit = System.setProperty(DualModeConfig.SysPropEnabled, "true")

  private def mkModeRecord(id: String, mode: String): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tmp.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-face",
        title = s"face spec $id",
        createdAt = System.currentTimeMillis() - 60_000L,
        mode = Some(mode)
      )
    )

  private val probeDef = AgentDef(name = "nebflow", description = "", tools = Nil, systemPrompt = "")

  private val NodeFace = Set("NodeEdit", "NodeList", "NodeCancel", "ChainWrite")
  private val KernelStripped = Set("Subagent", "AgentFlow") ++ NodeFace

  // ── 挂载面 ──────────────────────────────────────────────────────────────────

  test("F1 kernel 腿：编排面整体退场（Subagent/AgentFlow/node 四件全摘），Mail/delegate_report 保留") {
    val set = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = Some(DualModeConfig.ModeKernel))
    KernelStripped.foreach { t =>
      assert(!set.contains(t), s"kernel face must strip the orchestration face: $t (got: ${set.toList.sorted})")
    }
    assert(set.contains("Mail"), "kernel delegate keeps Mail (node:<id> continuation leg)")
    assert(set.contains(nebflow.core.tools.DelegateReportTool.Name), "kernel delegate keeps delegate_report")
    assert(set.contains("Read") && set.contains("Bash"), "kernel delegate keeps the executor base face")
  }

  test("F2 dispatcher 腿：Subagent 摘除，AgentFlow + node 四件保留（编排本体）") {
    val set = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = Some(DualModeConfig.ModeDispatcher))
    assert(!set.contains("Subagent"), "dispatcher face strips Subagent (Q2: recon subagent is not an orchestration face)")
    assert(set.contains("AgentFlow"), "dispatcher keeps AgentFlow (inline DAG convenience face)")
    NodeFace.foreach { t =>
      assert(set.contains(t), s"dispatcher keeps the node face: $t (got: ${set.toList.sorted})")
    }
  }

  test("F3 闸关（mode=None）⇒ 全量面逐字节：Subagent/AgentFlow/node 四件俱在") {
    val withMode = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = None)
    KernelStripped.foreach { t =>
      assert(withMode.contains(t), s"gate-off face must equal the current face: $t (got: ${withMode.toList.sorted})")
    }
  }

  test("F4 非 delegate 会话 + mode=dispatcher ⇒ 零分叉（挂载面判据 = delegateSession ∧ mode 两条件）") {
    val set = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = false, delegateMode = Some(DualModeConfig.ModeDispatcher))
    // 非 delegate 基线面本就无 node 四件（那是 delegateSession 尾段）——本条钉的是
    // Subagent/AgentFlow 不因 mode 参数而被摘（挂载面分叉需要两条件同时成立）。
    Set("Subagent", "AgentFlow").foreach { t =>
      assert(set.contains(t), s"non-delegate sessions never fork on mode: $t")
    }
    assert(!set.contains("ChainWrite"), "the node face remains delegateSession-tail-only (unchanged baseline)")
  }

  // ── 工具内第二保险 ──────────────────────────────────────────────────────────

  private def faceCtx(sessionId: Option[String]): ToolContext = ToolContext(projectRoot = tmp.toString, sessionId = sessionId)

  test("F5 SubagentTool：kernel/dispatcher 会话显式拒（DUAL_MODE_FACE_REMOVED）；闸关 = 现行为腿") {
    gateOn()
    mkModeRecord("delegate-nebflow-f5k", DualModeConfig.ModeKernel)
    mkModeRecord("delegate-nebflow-f5d", DualModeConfig.ModeDispatcher)
    val kernel = SubagentTool.call(JsonObject("prompt" -> "look around".asJson), faceCtx(Some("delegate-nebflow-f5k"))).unsafeRunSync()
    assert(kernel.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), kernel.toString)
    val dispatcher = SubagentTool.call(JsonObject("prompt" -> "look around".asJson), faceCtx(Some("delegate-nebflow-f5d"))).unsafeRunSync()
    assert(dispatcher.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), dispatcher.toString)
    // 闸关：同一会话形态（无记录 ⇒ mode=None）= 现行为（缺参校验错误，绝不出双模式文案）。
    val gateOff = SubagentTool.call(JsonObject.empty, faceCtx(Some("delegate-nebflow-f5x"))).unsafeRunSync()
    assert(gateOff.left.exists(_.message.contains("Missing required parameter")), gateOff.toString)
    assert(!gateOff.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), "gate-off must never produce the dual-mode error")
  }

  test("F6 AgentFlowTool：kernel 会话显式拒（DUAL_MODE_FACE_REMOVED）；闸关 = 非 kernel 错误路径") {
    gateOn()
    mkModeRecord("delegate-nebflow-f6k", DualModeConfig.ModeKernel)
    val valid = JsonObject(
      "steps" -> io.circe.Json.arr(
        io.circe.Json.obj("id" -> "a".asJson, "task" -> "t".asJson)
      )
    )
    val kernel = AgentFlowTool.call(valid, faceCtx(Some("delegate-nebflow-f6k"))).unsafeRunSync()
    assert(kernel.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), kernel.toString)
    // 闸关：无记录 ⇒ mode=None ⇒ 走既有路径（delegate 会话无宿主 = scope 不可用，
    // 亦非 kernel 文案——双模式批零痕迹）。
    val gateOff = AgentFlowTool.call(valid, faceCtx(Some("delegate-nebflow-f6x"))).unsafeRunSync()
    assert(gateOff.isLeft, gateOff.toString)
    assert(!gateOff.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), "gate-off must never produce the dual-mode error")
  }

  // ── 声明面/记录面 ───────────────────────────────────────────────────────────

  test("F7 DelegateTool schema 声明 mode enum（kernel|dispatcher|auto）") {
    val schema = nebflow.core.tools.DelegateTool.inputSchema
    val modeJ = (for
      props <- schema("properties")
      obj <- props.asObject
      modeJ <- obj("mode")
    yield modeJ)
    assert(modeJ.isDefined, s"the Delegate schema must declare the mode parameter: keys=${schema.keys}")
    val enumVals: List[String] =
      modeJ.flatMap(_.asObject).flatMap(_("enum")).flatMap(_.asArray).map(_.toList.flatMap(_.asString)).getOrElse(Nil)
    assertEquals(enumVals, List("kernel", "dispatcher", "auto"), s"mode enum must be kernel|dispatcher|auto; mode=$modeJ")
  }

  test("F8 SubAgentTask mode 字段：缺键容忍（旧盘面照读解码 None）；在键透传") {
    val legacy = """{"taskId":"delegate-nebflow-f8","parentSessionId":"p","agentName":"nebflow","prompt":"p",
      |"description":"d","status":"running","retryCount":0,"spawnedAt":1700000000000,"source":"delegate"}""".stripMargin
    val decoded = io.circe.parser.decode[SubAgentTask](legacy)
    assert(decoded.isRight, s"legacy row without the mode key must still decode: $decoded")
    assertEquals(decoded.map(_.mode), Right(None))
    val withMode = """{"taskId":"delegate-nebflow-f8b","parentSessionId":"p","agentName":"nebflow","prompt":"p",
      |"description":"d","status":"running","retryCount":0,"spawnedAt":1700000000000,"source":"delegate",
      |"mode":"dispatcher"}""".stripMargin
    val decoded2 = io.circe.parser.decode[SubAgentTask](withMode)
    assertEquals(decoded2.map(_.mode), Right(Some("dispatcher")))
  }
end DualModeToolFaceSpec
