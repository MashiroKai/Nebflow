package nebflow.agent

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
 * Delegate mode tool faces, prompt validation and orchestration guidance.
 * Both modes retain reconnaissance Subagent; only dispatchers receive node tools.
 * Ordinary callers and legacy records retain their existing behavior.
 */
class DualModeToolFaceSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  // buildAllowedToolSet is protected; expose it via a minimal stub (AllowedToolSetSpec 同款探针)。
  private object CoreProbe extends AgentCore:

    def allowedWithDelegateMode(defn: AgentDef, delegateSession: Boolean, delegateMode: Option[String]): Set[String] =
      buildAllowedToolSet(defn, 0, delegateSession = delegateSession, delegateMode = delegateMode)

    def askFace(defn: AgentDef): Set[String] =
      buildAllowedToolSet(defn, 0, askSession = true)

    def gitvizAndAskFace(defn: AgentDef): Set[String] =
      buildAllowedToolSet(defn, 0, askGitViz = true, askSession = true)

    def standaloneFace(defn: AgentDef): Set[String] = buildAllowedToolSet(defn, 0)

  test("real Ask and GitViz definitions share ordinary standalone authorization") {
    val file = tmp / "notes.md"
    os.write(file, "notes")
    val registry = new nebflow.core.ask.AskSessionRegistry(tmp / "registry")
    val sources = new nebflow.core.ask.AskContextSources:
      def fileStat(p: String) = cats.effect.IO.pure(None)
      def fileText(p: String) = cats.effect.IO.pure(None)
      def graphNeighbors(p: String) = cats.effect.IO.pure(Nil)
      def recallHits(q: String) = cats.effect.IO.pure(Nil)
      def recallDbMtime = cats.effect.IO.pure(0L)
      def graphVersion = cats.effect.IO.pure("")
    val services = nebflow.core.ask.AskServices(registry, sources, new nebflow.core.ask.AskPromptCache)
    val fileBinding = registry.bind(file.toString, file.last).unsafeRunSync()
    val gvBinding = registry.bind(tmp.toString, "project", explicitId = "ask-gv-12345678", kind = nebflow.core.ask.AskBinding.KindGitViz).unsafeRunSync()
    val ask = services.agentDefFor(fileBinding.id).unsafeRunSync()
    val gitviz = services.agentDefFor(gvBinding.id).unsafeRunSync()
    val standalone = AgentDef(name = "ordinary-standalone", description = "", tools = Nil)
    val ordinary = CoreProbe.standaloneFace(standalone)
    val askFace = CoreProbe.askFace(ask)
    val gitvizFace = CoreProbe.gitvizAndAskFace(gitviz)
    assertEquals(askFace -- nebflow.core.ask.AskSessions.AskExclusiveTools, ordinary)
    assertEquals(gitvizFace -- nebflow.core.ask.AskSessions.AskExclusiveTools, ordinary)
    assert(AgentCore.BaseTools.subsetOf(ordinary))
    val privileged = Set("Project", "AskHost", "AskHostReply", "AskHostClarify")
    assertEquals(askFace.intersect(privileged), Set.empty[String])
    assertEquals(gitvizFace.intersect(privileged), Set.empty[String])
    assertEquals(ask.pluginMcpServers, standalone.pluginMcpServers)
    assertEquals(ask.pluginTools, standalone.pluginTools)
    assertEquals(gitviz.pluginMcpServers, standalone.pluginMcpServers)
    assertEquals(gitviz.pluginTools, standalone.pluginTools)
  }

  test("non-Ask roles retain Bash without gaining Ask-exclusive tools") {
    val definitions = List(
      AgentDef(name = "subagent", description = "", tools = List("*")),
      AgentDef(name = "custom-bash", description = "", tools = List("Bash")),
      AgentDef(name = "custom-read", description = "", tools = List("Read")),
      probeDef,
      AgentDef(name = nebflow.actor.RootAgentIdentity.Name, description = "")
    )
    for definition <- definitions; mode <- List(None, Some("kernel"), Some("dispatcher")) do
      val face = CoreProbe.allowedWithDelegateMode(definition, delegateSession = mode.isDefined, delegateMode = mode)
      assert(face.contains("Bash"), clue(definition.name))
      assert(!face.contains("message_record_search"))
      assert(!face.contains("send_message_to_main_chat"))
      assert(!face.contains("GitQuery"))
  }

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
   * Kernel-leg expected face = `NebflowFixedTools` (file/shell six + BgWait + AskUserQuestion + Subagent)
   * + `Mail`. The kernel gets Subagent (read-only reconnaissance leg) by ruling. The four Node tools and
   * the terminal report tools are not on this leg.
   */
  private val ExpectedKernelFace = nebflow.agent.AgentCore.NebflowFixedTools + "Mail"

  /** Dispatcher delegates retain reconnaissance alongside the node orchestration face. */
  private val ExpectedDispatcherFace: Set[String] = ExpectedKernelFace ++ NodeFace

  // ── 挂载面 ──────────────────────────────────────────────────────────────────

  test("F1 kernel leg: has Subagent; the four node tools are absent") {
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
    // 🔴 裁定二：AgentFlow 工具整体退役 ⇒ 任何固定面都不许再出现这个名字。
    assert(
      !set.contains("AgentFlow"),
      s"the AgentFlow tool is retired — no face may carry the name (got: ${set.toList.sorted})"
    )
    assert(!nebflow.core.tools.ToolRegistry.TOOL_MAP.contains("AgentFlow"), "AgentFlow must be unregistered")
    assert(set.contains("Mail"), "kernel delegate keeps Mail (node:<id> continuation leg)")
    assert(set.contains("Read") && set.contains("Bash"), "kernel delegate keeps the executor base face")
    // 读数：面值恰 10 件（不照抄任务书，按本读数核）
    println(s"[FACE-KERNEL] n=${set.size} => ${set.toList.sorted.mkString(", ")}")
    assertEquals(set, ExpectedKernelFace, s"kernel face reading, got: ${set.toList.sorted}")
    assertEquals(
      set.size,
      ExpectedKernelFace.size,
      s"kernel face size must match expected set, got: ${set.toList.sorted}"
    )
  }

  test("F2 dispatcher exposes Subagent alongside the four node tools") {
    val id = "delegate-nebflow-f2"
    mkModeRecord(id, DualModeConfig.ModeDispatcher)
    val set = CoreProbe.allowedWithDelegateMode(
      probeDef,
      delegateSession = true,
      delegateMode = DelegateRegistry.modeFaceOf(Some(id))
    )
    assert(set.contains("Subagent"), "dispatcher face keeps the read-only reconnaissance tool")
    // 裁定二：AgentFlow 工具退役 ⇒ dispatcher 面同样没有它（编排 = ChainWrite）。
    assert(
      !set.contains("AgentFlow"),
      s"dispatcher must not carry AgentFlow — the tool is retired, orchestration goes through ChainWrite (got: ${set.toList.sorted})"
    )
    NodeFace.foreach { t =>
      assert(set.contains(t), s"dispatcher keeps the node face: $t (got: ${set.toList.sorted})")
    }
    println(s"[FACE-DISPATCHER] n=${set.size} => ${set.toList.sorted.mkString(", ")}")
    assertEquals(set, ExpectedDispatcherFace, s"dispatcher face reading, got: ${set.toList.sorted}")
    assertEquals(
      set.size,
      ExpectedDispatcherFace.size,
      s"dispatcher face size must match expected set, got: ${set.toList.sorted}"
    )
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

  test("F4 ordinary callers retain their face when passed dispatcher mode") {
    val set = CoreProbe.allowedWithDelegateMode(
      probeDef,
      delegateSession = false,
      delegateMode = Some(DualModeConfig.ModeDispatcher)
    )
    // 非 delegate 基线面本就无 node 四件（那是 delegateSession 尾段）——本条钉的是
    // Subagent 不因 mode 参数而被摘（挂载面分叉需要两条件同时成立）。
    assert(set.contains("Subagent"), "non-delegate sessions never fork on mode: Subagent")
    assert(!set.contains("ChainWrite"), "the node face remains delegateSession-tail-only (unchanged baseline)")
    assertEquals(set, CoreProbe.allowedWithDelegateMode(probeDef, delegateSession = false, delegateMode = None))
  }

  // ── 工具内第二保险 ──────────────────────────────────────────────────────────

  private def faceCtx(sessionId: Option[String]): ToolContext =
    ToolContext(projectRoot = tmp.toString, sessionId = sessionId)

  test("F5 Subagent validates empty prompts for dispatcher, kernel and ordinary callers") {
    val kernelId = "delegate-nebflow-f5k"
    val dispatcherId = "delegate-nebflow-f5d"
    mkModeRecord(kernelId, DualModeConfig.ModeKernel)
    mkModeRecord(dispatcherId, DualModeConfig.ModeDispatcher)
    val callers = List(Some(kernelId), Some(dispatcherId), Some("ordinary-session"), None)
    val invalidInputs = List(JsonObject.empty, JsonObject("prompt" -> " \n\t".asJson))
    for caller <- callers; input <- invalidInputs do
      val out = SubagentTool.call(input, faceCtx(caller)).unsafeRunSync()
      assertEquals(
        out.left.map(_.message),
        Left("Missing required parameter: prompt — give the sub-agent a self-contained question."),
        s"normal prompt validation must apply to caller $caller"
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
      s"mode enum must be kernel|dispatcher (merger is retired with the managed workspaces); mode=$modeJ"
    )
    // delegate-param-decisions 批（2026-11-04）：判定机已删除 ⇒ schema 里不许再出现
    // 「worktree judge」；且必须写明「不填 = 确定性 kernel + 没有分配器」。
    val desc = modeJ.flatMap(_.asObject).flatMap(_("description")).flatMap(_.asString).getOrElse("")
    assert(!desc.contains("worktree judge"), s"the removed judge must not be advertised: $desc")
    assert(desc.contains("no allocator"), s"the description states the allocator is gone: $desc")
    assert(desc.contains("deterministically"), s"the omitted-mode fallback is deterministic: $desc")
  }

  // ── 分发器提示词两段（2026-10-29 作者补令 · DispatcherDisciplineLine 单点）──

  test("F9 dispatcher distinguishes blocking read-only reconnaissance from chain task execution") {
    val line = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(line.contains("use Subagent for blocking lookups and analysis"))
    assert(line.contains("Read/Glob/Grep/WebSearch/WebFetch/Bash"))
    assert(line.contains("findings return as this tool call's result"))
    assert(line.contains("foreground shell-backed reconnaissance"))
    assert(line.contains("no separate Write/Edit, messaging or recursive agent tools"))
    assert(line.contains("not a read-only sandbox"))
    assert(line.contains("finish shell work and include its result before answering"))
    assert(line.contains("Use chain nodes for task execution, not Subagent"))
    assert(!line.contains("Subagent tool is gone"))
    assert(!line.contains("read-only / analysis = one node"))
    assert(SubagentTool.description.contains("dispatchers must use chain nodes instead of executing it themselves"))
    assert(!SubagentTool.description.contains("do those yourself"))
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
    // 座位共享 + 自管隔离（口径统一批）：节点默认落本会话工作区（共享非嵌套）；
    // 需隔离位时节点自建自管 branch/worktree；main 保护 seal 与 deps 串行不变。
    assert(!line.contains("no branches and no worktrees"), s"the retracted claim must be gone: $line")
    assert(
      !line.contains("never create branches or worktrees"),
      s"the retracted node-layer isolation ban must be gone: $line"
    )
    assert(
      line.contains("THIS session's workspace"),
      "the shared-seat semantics are stated (sharing, not nesting)"
    )
    assert(
      line.contains("creates and manages its own branch/worktree"),
      "on-demand node-level self-isolation is stated"
    )
    assert(line.contains("never commits straight to `main`"), "the main-protection seal is stated")
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

  test("F12 repository-specific branch discipline and on-demand isolation across faces") {
    // Author order 2026-11-22 20:08, #3143: generic prompts follow the target
    // repository's rules rather than imposing Stand's branch policy everywhere.
    val node = nebflow.prompts.PromptCatalog.Roles.NebflowNodePrompt
    val delegate = nebflow.prompts.PromptCatalog.Roles.NebflowDelegatePrompt
    val dispatcher = nebflow.core.tools.DelegateTool.DispatcherDisciplineLine
    assert(node.contains("Follow the target repository's branch and commit rules and the branch named in the brief"))
    assert(delegate.contains("Branch discipline: follow the target repository's branch and commit rules and the branch named in the brief"))
    for (name, face) <- List("node" -> node, "delegate" -> delegate) do
      assert(face.contains("create and manage your own branch/worktree with git"), s"[$name] self-isolation")
      assert(!face.contains("never commit straight to"), s"[$name] no generic main-branch ban")
    assert(dispatcher.contains("never commits straight to `main`"), "dispatcher main-protection seal")
    for (name, face) <- List("node" -> node, "delegate" -> delegate, "dispatcher" -> dispatcher) do
      assert(face.contains("own branch/worktree"), s"[$name] on-demand self-isolation")
      assert(!face.contains("never create branches or worktrees"), s"[$name] retired isolation ban")
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

  test("the retired workspace/merger tools are on no face at all (kernel, executor, root)") {
    val definition = AgentDef(name = "nebflow", description = "faces")
    val kernel = CoreProbe.allowedWithDelegateMode(definition, true, Some("kernel"))
    val dispatcher = CoreProbe.allowedWithDelegateMode(definition, true, Some("dispatcher"))
    val executor = CoreProbe.allowedWithDelegateMode(definition, false, None)
    val root = CoreProbe.allowedWithDelegateMode(
      AgentDef(name = nebflow.actor.RootAgentIdentity.Name, description = "root face"),
      delegateSession = false,
      delegateMode = None
    )
    for face <- List(kernel, dispatcher, executor, root) do
      assert(!face.contains("MergerRequest"), "MergerRequest is retired and mounts on no face")
      assert(!face.contains("WorktreeContext"), "WorktreeContext is retired and mounts on no face")
      assert(!face.contains("WorkspaceSummary"), "WorkspaceSummary is retired and mounts on no face")
      assert(!face.contains("WorkspaceReconcile"), "WorkspaceReconcile is retired and mounts on no face")
    assert(!kernel.contains("ChainWrite"))
    assert(dispatcher.contains("ChainWrite"), "the dispatcher keeps its chain tools")
  }
end DualModeToolFaceSpec
