package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.delegate.{DelegateRegistry, DualModeConfig}
import nebflow.core.tools.{SubagentTool, ToolContext}
import nebflow.shared.{PathUtil, SubAgentTask}

/**
 * 双模式批（2026-10-28 · 设计 §4.2/§5.1）工具面分叉 spec：
 *
 *  - F1-F4 **挂载面单点分叉**（buildAllowedToolSet 尾段 delegateSession 腿）：
 *    kernel = 编排面整体退场（node 四件全摘）**但携带 Subagent**（只读侦察腿，
 *    tool-face-agentflow-retire 批 2026-11-05 作者裁定一）；dispatcher = Subagent
 *    摘除、node 四件保留（编排本体）；`delegateMode=None`（闸关/未判定）与
 *    `delegateSession=false` ⇒ 本段全部分叉失效（现状逐字节）。
 *  - 同批裁定二：`AgentFlow` **工具整体退役** ⇒ 任何面都不再携带该名（F1/F2/F3 各钉一条）。
 *  - F5 **工具内第二保险**（防 schema 注入/显式调用逃逸）：dispatcher 会话显式拒
 *    （DUAL_MODE_FACE_REMOVED）、**kernel 会话放行**；闸关 = 现行为腿（参数校验错误，
 *    绝不出双模式文案）。
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

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

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

  /**
   * 内核腿期望面（**11 件**）= `NebflowFixedTools` 九件（文件/壳六件 + BgWait +
   * AskUserQuestion + Subagent）+ `delegate_report` + `Mail`。tool-face-agentflow-retire
   * 批（2026-11-05）：AgentFlow 随工具退役从固定面消失（十 → 九），kernel 按裁定一
   * **拿到** Subagent（只读侦察腿）。Node 四件与 node_report 不在本腿。
   */
  private val ExpectedKernelFace =
    nebflow.agent.AgentCore.NebflowFixedTools + nebflow.core.tools.DelegateReportTool.Name + "Mail"

  /** 分发器腿期望面（**14 件**）= 内核腿 − Subagent ＋ node 四件。 */
  private val ExpectedDispatcherFace: Set[String] = (ExpectedKernelFace - "Subagent") ++ NodeFace

  // ── 挂载面 ──────────────────────────────────────────────────────────────────

  test("F1 kernel 腿：**有** Subagent（只读侦察）+ 编排面退场（node 四件全摘、node_report 摘除、AgentFlow 已退役）—— 恰 11 件") {
    val set = CoreProbe.allowedWithDelegateMode(
      probeDef,
      delegateSession = true,
      delegateMode = Some(DualModeConfig.ModeKernel)
    )
    // 编排面整体退场（node 四件 = 编排本体，kernel 无编排诉求）
    NodeFace.foreach { t =>
      assert(!set.contains(t), s"kernel face must strip the node face: $t (got: ${set.toList.sorted})")
    }
    // 🔴 反转点（作者裁定一 2026-11-05「给 kernel 腿补 Subagent（用只读版）」）：
    // kernel 现在**必须**含 Subagent——只读五件、无写/无 shell/无消息。与之配套的
    // 是不变量 R-3a 的收窄（KernelChildSpawnGate 只放行 def='subagent' 的子 actor），
    // 两条断言分开钉在 KernelChildSpawnGateSpec K1a/K1b。
    assert(
      set.contains("Subagent"),
      s"kernel face MUST carry Subagent (read-only recon leg, author ruling 2026-11-05; got: ${set.toList.sorted})"
    )
    // node_report 摘除（作者裁定 2026-10-29：「那不是agentflow的节点使用的工具吗」）
    // ——现状事实源：RootExclusiveTools 防声明逃逸剥离 + flowNodeSession 尾段专属
    // 挂载，本就不达 delegate 面；kernel 腿的显式减法是未来挂载面变动下的保险销。
    assert(
      !set.contains(nebflow.core.tools.NodeReportToolDef.Name),
      "kernel face must not carry node_report (flow-node-only tool — dual insurance: mount face + in-tool identity rejection)"
    )
    // 🔴 裁定二：AgentFlow 工具整体退役 ⇒ 任何固定面都不许再出现这个名字。
    assert(
      !set.contains("AgentFlow"),
      s"the AgentFlow tool is retired — no face may carry the name (got: ${set.toList.sorted})"
    )
    assert(!nebflow.core.tools.ToolRegistry.TOOL_MAP.contains("AgentFlow"), "AgentFlow must be unregistered")
    assert(set.contains("Mail"), "kernel delegate keeps Mail (node:<id> continuation leg)")
    assert(set.contains(nebflow.core.tools.DelegateReportTool.Name), "kernel delegate keeps delegate_report")
    assert(set.contains("Read") && set.contains("Bash"), "kernel delegate keeps the executor base face")
    // 读数：面值恰 11 件（不照抄任务书，按本读数核）
    println(s"[FACE-KERNEL] n=${set.size} => ${set.toList.sorted.mkString(", ")}")
    assertEquals(set, ExpectedKernelFace, s"kernel face reading = 11 pieces, got: ${set.toList.sorted}")
    assertEquals(set.size, 11, s"kernel face must be exactly 11 pieces, got: ${set.toList.sorted}")
  }

  test("F2 dispatcher 腿：Subagent 摘除、**无 AgentFlow**（工具退役）、node 四件保留（编排本体）—— 恰 14 件") {
    val set = CoreProbe.allowedWithDelegateMode(
      probeDef,
      delegateSession = true,
      delegateMode = Some(DualModeConfig.ModeDispatcher)
    )
    assert(
      !set.contains("Subagent"),
      "dispatcher face strips Subagent (Q2: recon subagent is not an orchestration face)"
    )
    // 裁定二：AgentFlow 工具退役 ⇒ dispatcher 面同样没有它（编排 = ChainWrite）。
    assert(
      !set.contains("AgentFlow"),
      s"dispatcher must not carry AgentFlow — the tool is retired, orchestration goes through ChainWrite (got: ${set.toList.sorted})"
    )
    NodeFace.foreach { t =>
      assert(set.contains(t), s"dispatcher keeps the node face: $t (got: ${set.toList.sorted})")
    }
    // 事实钉（2026-10-29 裁定②「dispatcher 保留」= 本腿不做 node_report 摘除动作；
    // 现状该工具本就 flow 节点会话专属——本断言钉的是「本批不引入变化」）。
    assert(
      !set.contains(nebflow.core.tools.NodeReportToolDef.Name),
      "node_report stays flow-node-only (unchanged by this batch; the dispatcher leg does no node_report work)"
    )
    println(s"[FACE-DISPATCHER] n=${set.size} => ${set.toList.sorted.mkString(", ")}")
    assertEquals(set, ExpectedDispatcherFace, s"dispatcher face reading = 14 pieces, got: ${set.toList.sorted}")
    assertEquals(set.size, 14, s"dispatcher face must be exactly 14 pieces, got: ${set.toList.sorted}")
  }

  test("F3 闸关（mode=None）⇒ 全量面逐字节：Subagent + node 四件俱在（AgentFlow 已不存在于任何面）") {
    val withMode = CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = true, delegateMode = None)
    (Set("Subagent") ++ NodeFace).foreach { t =>
      assert(withMode.contains(t), s"gate-off face must equal the current face: $t (got: ${withMode.toList.sorted})")
    }
    assert(
      !withMode.contains("AgentFlow"),
      "the retired name is gone from every config path — the registry has no such tool at all"
    )
  }

  test("F4 非 delegate 会话 + mode=dispatcher ⇒ 零分叉（挂载面判据 = delegateSession ∧ mode 两条件）") {
    val set = CoreProbe.allowedWithDelegateMode(
      probeDef,
      delegateSession = false,
      delegateMode = Some(DualModeConfig.ModeDispatcher)
    )
    // 非 delegate 基线面本就无 node 四件（那是 delegateSession 尾段）——本条钉的是
    // Subagent 不因 mode 参数而被摘（挂载面分叉需要两条件同时成立）。
    assert(set.contains("Subagent"), "non-delegate sessions never fork on mode: Subagent")
    assert(!set.contains("ChainWrite"), "the node face remains delegateSession-tail-only (unchanged baseline)")
  }

  // ── 工具内第二保险 ──────────────────────────────────────────────────────────

  private def faceCtx(sessionId: Option[String]): ToolContext =
    ToolContext(projectRoot = tmp.toString, sessionId = sessionId)

  test("F5 SubagentTool：**kernel 放行**、dispatcher 显式拒（DUAL_MODE_FACE_REMOVED）；闸关 = 现行为腿") {
    mkModeRecord("delegate-nebflow-f5k", DualModeConfig.ModeKernel)
    mkModeRecord("delegate-nebflow-f5d", DualModeConfig.ModeDispatcher)
    // ① kernel：身份闸**放行**——用空输入证明「过了身份闸、落到参数校验」（真 spawn 的
    //    端到端面由 BlockingSubagentEndToEndSpec 与 KernelChildSpawnGateSpec K1a 承担）。
    val kernel = SubagentTool.call(JsonObject.empty, faceCtx(Some("delegate-nebflow-f5k"))).unsafeRunSync()
    assert(kernel.left.exists(_.message.contains("Missing required parameter")), kernel.toString)
    assert(
      !kernel.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")),
      s"kernel must NOT be refused anymore (author ruling 2026-11-05), got: $kernel"
    )
    // ② dispatcher：工具内第二保险仍然拒（挂载面已摘 + 本闸双保险）
    val dispatcher = SubagentTool
      .call(JsonObject("prompt" -> "look around".asJson), faceCtx(Some("delegate-nebflow-f5d")))
      .unsafeRunSync()
    assert(dispatcher.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")), dispatcher.toString)
    // ③ 闸关：同一会话形态（无记录 ⇒ mode=None）= 现行为（缺参校验错误，绝不出双模式文案）。
    val gateOff = SubagentTool.call(JsonObject.empty, faceCtx(Some("delegate-nebflow-f5x"))).unsafeRunSync()
    assert(gateOff.left.exists(_.message.contains("Missing required parameter")), gateOff.toString)
    assert(
      !gateOff.left.exists(_.message.contains("DUAL_MODE_FACE_REMOVED")),
      "gate-off must never produce the dual-mode error"
    )
  }

  // ── 声明面/记录面 ───────────────────────────────────────────────────────────

  test("F7 DelegateTool schema 声明 mode enum（kernel|dispatcher，无 auto）") {
    val schema = nebflow.core.tools.DelegateTool.inputSchema
    val modeJ = (for
      props <- schema("properties")
      obj <- props.asObject
      modeJ <- obj("mode")
    yield modeJ)
    assert(modeJ.isDefined, s"the Delegate schema must declare the mode parameter: keys=${schema.keys}")
    val enumVals: List[String] =
      modeJ.flatMap(_.asObject).flatMap(_("enum")).flatMap(_.asArray).map(_.toList.flatMap(_.asString)).getOrElse(Nil)
    assertEquals(
      enumVals,
      List("kernel", "dispatcher"),
      s"mode enum must be kernel|dispatcher (the allocator is gone, so auto is no longer a legal word); mode=$modeJ"
    )
    // delegate-param-decisions 批（2026-11-04）：判定机已删除 ⇒ schema 里不许再出现
    // 「worktree judge」；且必须写明「不填 = 确定性 kernel + 没有分配器」。
    val desc = modeJ.flatMap(_.asObject).flatMap(_("description")).flatMap(_.asString).getOrElse("")
    assert(!desc.contains("worktree judge"), s"the removed judge must not be advertised: $desc")
    assert(desc.contains("no allocator"), s"the description states the allocator is gone: $desc")
    assert(desc.contains("deterministically"), s"the omitted-mode fallback is deterministic: $desc")
  }

  // ── 分发器提示词两段（2026-10-29 作者补令 · DispatcherDisciplineLine 单点）──

  test("F9 分发器纪律段：subagent-via-nodes 用法指导逐点在案（自足 task / out 边回流 / verifier+loop 判定）") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(line.contains("Subagent tool is gone by design"), "the removal is explained, not silent")
    assert(
      line.contains("SELF-CONTAINED"),
      "node tasks must be self-contained (nodes see none of the dispatcher's context)"
    )
    assert(line.contains("completion notifications"), "results flow back via out edges / completion notifications")
    assert(
      line.contains("verifier node with a (fail)<worker>:loop back edge"),
      "judgment steps route through verifier+loop"
    )
  }

  test("F10 分发器纪律段：整链一次编写纪律（一次 ChainWrite 给全链；NodeEdit 只精修不拼链）+ 座位共享精确化") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(
      line.contains("ONE ChainWrite call"),
      "the whole chain — multi-node complex chains included — rides one call"
    )
    assert(line.contains("never assemble a chain node-by-node"), "piecemeal NodeEdit chain-building is forbidden")
    assert(line.contains("not for building it"), "NodeEdit is refinement-only")
    // worktree 裁定（2026-10-29 18:12）：座位共享而非「无 worktree」——旧表述必须消失。
    assert(!line.contains("no branches and no worktrees"), s"the retracted claim must be gone: $line")
    assert(
      line.contains("inside this session's worktree seat"),
      "the shared-seat semantics are stated (sharing, not nesting)"
    )
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
    // （NebflowDelegatePrompt 通篇 executor 框架——「Simple work → you do it
    // yourself」），分界只能由本段的开场覆盖句承载。
    assert(
      line.contains("overrides the executor framing"),
      "the shared-seat executor framing must be explicitly overridden"
    )
    assert(
      line.contains("simple work → you do it yourself"),
      "the overridden seat phrase is quoted verbatim (unambiguous target)"
    )
    assert(line.contains("executor tier of your seat is OFF"), "the executor tier is named as off in this mode")
    assert(line.contains("mode violation, not a shortcut"), "task-work Write/Edit/Bash is a violation clause")
    assert(line.contains("only read the map, the files and git facts"), "the dispatcher's own calls stay read-only")
    assert(
      line.contains("simple work → a one-node chain"),
      "the fallback for simple work is a chain node, never personal work"
    )
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
    assert(
      line.contains("the repair is a chain edit, never your own hands"),
      "advancement = chain edit, zero hands-on repair"
    )
    // 插件发现行消解（PluginDiscoveryLine 的 declare 邀请对 dispatcher 不适用）
    assert(
      line.contains("query-only for you"),
      "the [plugin discovery] declare invitation is neutralized for the dispatcher"
    )
  }

  test("F11c 两模式提示词语义分界：kernel 腿无模式段（brief 零 dispatcher 文本），语义差异由工具面 + 本段承载") {
    // kernel 腿：组装走缺省参数（dispatcherDiscipline=None）——模式段零泄漏；
    // 注入面逐字节锚在 core.tools 侧 DualModeBriefInjectionSpec（assembleBrief
    // 为 private[tools]，跨包不可及——本条钉常量面身份）。
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(
      !line.contains("kernel"),
      "the dispatcher section never mentions the kernel mode (modes must not cross-reference)"
    )
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
