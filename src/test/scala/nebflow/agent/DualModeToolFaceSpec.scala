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

  test("F1 kernel 腿：编排面整体退场（Subagent/AgentFlow/node 四件全摘 + node_report 摘除），Mail/delegate_report 保留") {
    val set = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = Some(DualModeConfig.ModeKernel))
    KernelStripped.foreach { t =>
      assert(!set.contains(t), s"kernel face must strip the orchestration face: $t (got: ${set.toList.sorted})")
    }
    // node_report 摘除（作者裁定 2026-10-29：「那不是agentflow的节点使用的工具吗」）
    // ——现状事实源：RootExclusiveTools 防声明逃逸剥离 + flowNodeSession 尾段专属
    // 挂载，本就不达 delegate 面；kernel 腿的显式减法是未来挂载面变动下的保险销。
    assert(
      !set.contains(nebflow.core.tools.NodeReportToolDef.Name),
      "kernel face must not carry node_report (flow-node-only tool — dual insurance: mount face + in-tool identity rejection)"
    )
    assert(set.contains("Mail"), "kernel delegate keeps Mail (node:<id> continuation leg)")
    assert(set.contains(nebflow.core.tools.DelegateReportTool.Name), "kernel delegate keeps delegate_report")
    assert(set.contains("Read") && set.contains("Bash"), "kernel delegate keeps the executor base face")
  }

  test("F2 dispatcher 腿：Subagent 摘除，AgentFlow + node 四件保留（编排本体）；node_report 现状事实源不达（flow 节点专属）") {
    val set = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = Some(DualModeConfig.ModeDispatcher))
    assert(!set.contains("Subagent"), "dispatcher face strips Subagent (Q2: recon subagent is not an orchestration face)")
    assert(set.contains("AgentFlow"), "dispatcher keeps AgentFlow (inline DAG convenience face)")
    NodeFace.foreach { t =>
      assert(set.contains(t), s"dispatcher keeps the node face: $t (got: ${set.toList.sorted})")
    }
    // 事实钉（2026-10-29 裁定②「dispatcher 保留」= 本腿不做 node_report 摘除动作；
    // 现状该工具本就 flow 节点会话专属——本断言钉的是「本批不引入变化」）。
    assert(
      !set.contains(nebflow.core.tools.NodeReportToolDef.Name),
      "node_report stays flow-node-only (unchanged by this batch; the dispatcher leg does no node_report work)"
    )
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
    // worktree 裁定回写（作者 2026-10-29 18:12）：schema 文案不得再宣称 dispatcher
    // 本体「no worktree」——判定机照走，节点层 seal。
    val desc = modeJ.flatMap(_.asObject).flatMap(_("description")).flatMap(_.asString).getOrElse("")
    assert(!desc.contains("zero plugins, no worktree"), s"the retracted no-worktree claim must be gone: $desc")
    assert(desc.contains("worktree judge"), s"the schema states the dispatcher goes through the worktree judge: $desc")
  }

  // ── 分发器提示词两段（2026-10-29 作者补令 · DispatcherDisciplineLine 单点）──

  test("F9 分发器纪律段：subagent-via-nodes 用法指导逐点在案（自足 task / out 边回流 / verifier+loop 判定）") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(line.contains("Subagent tool is gone by design"), "the removal is explained, not silent")
    assert(line.contains("SELF-CONTAINED"), "node tasks must be self-contained (nodes see none of the dispatcher's context)")
    assert(line.contains("completion notifications"), "results flow back via out edges / completion notifications")
    assert(line.contains("verifier node with a (fail)<worker>:loop back edge"), "judgment steps route through verifier+loop")
  }

  test("F10 分发器纪律段：整链一次编写纪律（一次 ChainWrite 给全链；NodeEdit 只精修不拼链）+ 座位共享精确化") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(line.contains("ONE ChainWrite call"), "the whole chain — multi-node complex chains included — rides one call")
    assert(line.contains("never assemble a chain node-by-node"), "piecemeal NodeEdit chain-building is forbidden")
    assert(line.contains("not for building it"), "NodeEdit is refinement-only")
    // worktree 裁定（2026-10-29 18:12）：座位共享而非「无 worktree」——旧表述必须消失。
    assert(!line.contains("no branches and no worktrees"), s"the retracted claim must be gone: $line")
    assert(line.contains("inside this session's worktree seat"), "the shared-seat semantics are stated (sharing, not nesting)")
    assert(line.contains("never create branches or worktrees"), "the node-layer seal is stated")
    assert(line.contains("`deps`"), "the write-scope serialization discipline stays")
  }

  // ── 9.19 纯编排口径重写批（2026-11-01 · 作者令「它自己不干活」）──────────────
  // 考古：tag 2026.9.19 树 seed/agents/project-dispatcher/system.md —— 分发器纪律
  // = 拆解/派发/收拢/推进度，零亲自动手（9.19 工具面本无写工具，机制级零动手；
  // delegate 双模式腿共享 executor 工具面 ⇒ 零动手纪律只能由本段提示词承载）。

  test("F11a 分发器纪律段（9.19 口径）：纯编排身份句 + 覆盖句——共享 delegate 面的 executor 框架必须被显式废黜") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(line.contains("pure orchestration"), s"identity header names the doctrine: $line")
    assert(line.contains("you never execute it yourself"), "zero-hands-on identity clause")
    // kernel=自己干 / dispatcher=只编排 的语义分界：两模式共享同一 system prompt
    //（NebflowDelegatePrompt 通篇 executor 框架——「Simple work → you do it
    // yourself」），分界只能由本段的开场覆盖句承载。
    assert(line.contains("overrides the executor framing"), "the shared-seat executor framing must be explicitly overridden")
    assert(line.contains("simple work → you do it yourself"), "the overridden seat phrase is quoted verbatim (unambiguous target)")
    assert(line.contains("executor tier of your seat is OFF"), "the executor tier is named as off in this mode")
    assert(line.contains("mode violation, not a shortcut"), "task-work Write/Edit/Bash is a violation clause")
    assert(line.contains("only read the map, the files and git facts"), "the dispatcher's own calls stay read-only")
    assert(line.contains("simple work → a one-node chain"), "the fallback for simple work is a chain node, never personal work")
  }

  test("F11b 分发器纪律段（9.19 口径）：拆解/接令盘点/收拢/推进度四段逐点在案") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    // 拆解（9.19 纪律原文「一 node＝一个会话能完成的最小单元」+ task 三要素 + 拓扑定量）
    assert(line.contains("smallest unit one agent can finish in one session"), "node granularity (9.19 拆解纪律)")
    assert(line.contains("goal + constraints + acceptance"), "node task = goal+constraints+acceptance, self-contained")
    assert(line.contains("Size the topology to the work"), "topology sizing discipline")
    // 接令/处置（9.19 order intake：先盘点在飞，再显式处置）
    assert(line.contains("inventory the in-flight chain first"), "supplement/failure intake starts with an inventory")
    assert(line.contains("dispose of every affected node explicitly"), "explicit per-node disposal duty")
    // 收拢（到达即综合；终稿=dispatch summary）
    assert(line.contains("synthesize across nodes"), "collection = cross-node synthesis on arrival")
    assert(line.contains("dispatch summary"), "final text = conclusion-first dispatch summary, not a transcript")
    // 推进度（修链不修活）
    assert(line.contains("the repair is a chain edit, never your own hands"), "advancement = chain edit, zero hands-on repair")
    // 插件发现行消解（PluginDiscoveryLine 的 declare 邀请对 dispatcher 不适用）
    assert(line.contains("query-only for you"), "the [plugin discovery] declare invitation is neutralized for the dispatcher")
  }

  test("F11c 两模式提示词语义分界：kernel 腿无模式段（brief 零 dispatcher 文本），语义差异由工具面 + 本段承载") {
    // kernel 腿：组装走缺省参数（dispatcherDiscipline=None）——模式段零泄漏；
    // 注入面逐字节锚在 core.tools 侧 DualModeBriefInjectionSpec（assembleBrief
    // 为 private[tools]，跨包不可及——本条钉常量面身份）。
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(!line.contains("kernel"), "the dispatcher section never mentions the kernel mode (modes must not cross-reference)")
    assert(line.contains("Dispatcher mode"), "the section is unmistakably labeled for the dispatcher mode")
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
