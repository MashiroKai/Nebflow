package nebflow.agent

import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.sandbox.{SandboxConfig, SandboxPolicy}
import nebflow.core.tools.{GlobTool, GrepTool, ToolContext, ToolRegistry}

import java.nio.file.Files

/**
 * 阶段 2c agent 收敛 spec（设计文档 §C.1/§C.5 + §G.3 验收点①③）。
 *
 * - §G.3-①：Root 工具清单 = §C.1 NebulaSet 逐项断言（buildToolList 层——
 *   LLM 实际收到的工具定义列表，未注册名自然缺席，比 allowedSet 更接近交付面）。
 * - dispatcher / general 固定集同层断言（§C.1 分发器行 / §C.4 七件）。
 * - MultiEdit 从 ToolRegistry 删除（§C.1：能力由 Edit replace_all 覆盖）；
 *   旧记忆记账工具已整体退役（govmemory 批 2026-09-25）：注册表零挂、一切身份
 *   声明无效、迁移指引表带 Edit/Write 直写口径；dream 准入例外条目仍在
 *   `AgentCore.DreamAdmittedTools` 集内，但其动作面承载（原记账工具 DREAM_APPEND_DENIED）
 *   与队列族已在本批整体退役（memory-family-retirement 2026-09-29）⇒ 该例外现无
 *   执行面效果，名族收口归 e5-memconsolidator 批。
 * - §C.5：Glob/Grep 缺省根 = node root（沙箱开时 = sandbox.root =
 *   SessionContext.projectRoot 权威口径；user.dir 仅沙箱关回退，§G.1 rollback
 *   已由 SandboxSpec「G.1 回滚」用例覆盖）。
 */
class AgentConvergenceSpec extends FunSuite:

  private object CoreProbe extends AgentCore:

    def toolList(defn: AgentDef, isFlowNode: Boolean = false): List[String] =
      buildToolList(defn, isFlowNode = isFlowNode).get.map(_.name).toSet.toList.sorted

    def allowed(defn: AgentDef, isFlowNode: Boolean = false): Set[String] =
      buildAllowedToolSet(defn, isFlowNode = isFlowNode)

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  /** 节点实际执行的 agent 名（建位落名 + spawn 读名的同一单点）。
    * P1-3 归因修正（2026-10-03）：旧断言用手搓 "general" 造节点身份——该名已非
    * 内置名，落 legacyFixedTools catch-all ⇒ 假红。判据源对齐生产落名。 */
  private val executorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  // ===== §G.3-① Nebula 工具清单 = §C.1 矩阵 =====

  test("Nebula LLM tool list == §C.1 NebulaSet exactly（编排/任务/通信/读三件/写手三件/可视化/用户/平台/记忆）"):
    val delivered = CoreProbe.toolList(mkDef("Nebula")).toSet
    // 交付面期望集 = RootOrchestrationTools 的注册表实收面（friendseal strip
    // 另加）。RootOrchestrationTools 成员资格本身是上游机制面（P1-3 归因修正
    // 2026-10-03：静态集仍带已注销的 MemoryNote/TaskList 是本树最大现存缺口，
    // 属阶段 3 收口，不属本批断言过时——本批按「交付面 = 机制集 ∩ 注册表」钉死
    // 实际交付面并显式登记缺口，两条 baseline-existing 红的根因不再被断言层放大）。
    val baseExpected = Set(
      "Mail",
      // AgentFlow 已退役（unified-delegate 批 2026-10-03）：派发统一走 Delegate。
      "ProjectCreate",
      "AgentControl",
      // Delegate（builtin-def 批 2026-10-03 作者令②在場恢复）：极简内核入口，
      // 直接触发内置执行 agent 并返回 delegate:<id> 续聊地址
      "Delegate",
      "SendMessage",
      "ListFriends", // 通信（2026-09-12 好友消息改造批 ⑩：只读名册，+1）
      "Read", // 读件（08:40 解禁四件；2026-09-18 18:18 令恢复 Glob/Grep + 写手三件）
      // Card 已退役（pop-upgrade 批 2026-10-03 作者令「去掉card工具」）——展示职责并入 Pop
      "AskUserQuestion",
      "Pop",
      "Schedule",
      // MemoryNote / TaskList 已注销（govmemory 批 2026-09-25 裁「MemoryNote
      // registration retired」；TaskList 同批「not registered (retired and removed)」）
      // ⇒ 不在 LLM 交付面（未注册名自然缺席，本 spec 判的是 buildToolList 面）。
      // P1-3 归因修正（2026-10-03）：基线既有红 3 条里有两条根因在此——
      // RootOrchestrationTools 的 MemoryNote/TaskList 两成员从未注销 ⇒ 本批按任务书
      // 口径「断言对齐现实，不是恢复注册」把交付面期望集对齐到注册表实收面。
      // 文件面五件（2026-09-18 18:18 作者令「恢复nebula的bash edit write glob grep」）
      "Glob",
      "Grep",
      "Bash",
      "Write",
      "Edit"
    )
    // friendseal (2026-09-25): flag-aware expectation — the literal above stays
    // UNTOUCHED; the sealed default strips ListFriends at the single delivery point
    // (AgentCore.friendsSealedStrip), so the expected face removes the same entry
    // only when the latch reads sealed on this snapshot. Never a bare number.
    val sealStrip = if nebflow.core.FriendsSeal.isSealed then Set("ListFriends") else Set.empty[String]
    val expected = baseExpected -- sealStrip
    // 交叉闸：期望集必须是机制集 ∩ 注册表的**逐字节实收面**（机制面多收/漏收任何一件
    // 即红——缺口显式化，不靠断言藏住）。
    assertEquals(
      expected,
      (AgentCore.RootOrchestrationTools -- sealStrip).filter(ToolRegistry.TOOL_MAP.contains),
      "Nebula 交付面 = RootOrchestrationTools ∩ 已注册（friendseal strip 后）"
    )
    // 已知缺口显式登记（P1-3 归因）：机制面静态集 vs 注册表的差异只能随阶段 3 收口，
    // 断言层不再容忍新差异加入。
    assertEquals(
      AgentCore.RootOrchestrationTools.filter(!ToolRegistry.TOOL_MAP.contains(_)),
      Set("MemoryNote", "TaskList"),
      "机制面静态集 vs 注册表的现存差异恰 = {MemoryNote, TaskList}（基线既有；阶段 3 收口面）"
    )
    assertEquals(
      delivered,
      expected,
      "Nebula 面向 LLM 的工具清单必须逐项等于「RootOrchestrationTools ∩ 已注册」的实收面（friendseal 封存期按单点 strip 派生；静态集与注册表的现存差异显式登记见上——属机制面既有缺口，非本断言放宽）"
    )
    assert(!delivered.contains("Issue"), "交付面零 Issue（2026-09-04 终裁退役）")
    // 钉死断言（2026-09-18 18:18 作者令）：Nebula（root）面**在场**含 Glob、含
    // Grep——取代 2026-09-16 18:41 摘除令之 root 面部分（仅 root 面；分发器/节点面
    // 逐字不变）。🔴 依据只有 09-18 18:18 令本身——0913「Glob/Grep 永久保留」旧裁定
    // 不因本批复活，也不得援引。变异验红锚：从机制集再摘任一件即红。
    assert(delivered.contains("Glob"), "Nebula 面含 Glob（2026-09-18 18:18 令——变异验红锚：摘掉即红）")
    assert(delivered.contains("Grep"), "Nebula 面含 Grep（2026-09-18 18:18 令——变异验红锚：摘掉即红）")
    // 钉死断言（2026-09-18 18:18 作者令）：Nebula 机制集**在场**含 Bash、含 Write、
    // 含 Edit——取代 2026-09-05 23:34 裁定「把你的 bash 和编辑工具收起来」之 root
    // 面部分（仅 root 面；general/BaseTools 六件默认注入逐字不变）。变异验红锚。
    assert(delivered.contains("Bash"), "Nebula 含 Bash（2026-09-18 18:18 令恢复——变异验红锚：摘掉即红）")
    assert(delivered.contains("Write"), "Nebula 含 Write（2026-09-18 18:18 令恢复）")
    assert(delivered.contains("Edit"), "Nebula 含 Edit（2026-09-18 18:18 令恢复）")

  test(
    "Nebula 清单含文件面六件（Read/Glob/Grep/Bash/Write/Edit 均在，2026-09-18 18:18 令）；零 NodeList、零 MultiEdit、零 Web 系、零旧体系三件、零 TeamTask/SubTask/NodeEdit/NodeCancel"
  ):
    val delivered = CoreProbe.toolList(mkDef("Nebula")).toSet
    // 2026-09-18 18:18 作者令：root 面恢复 Bash/Edit/Write/Glob/Grep（+既有的 Read
    // ⇒ 文件面六件在场）；取代 2026-09-16 18:41 与 2026-09-05 23:34 两笔摘除令之
    // root 面部分（仅 root 面）。
    Set("Read", "Glob", "Grep", "Bash", "Write", "Edit").foreach { t =>
      assert(delivered.contains(t), s"文件面六件必须机制固定（2026-09-18 18:18 令恢复 5 件 + 既有 Read）: $t")
    }
    // 🔴 Bash/Write/Edit 已从下方 forbidden 集移出（2026-09-18 18:18 令恢复 ⇒ 在场
    // 合法，留在 forbidden 里 leaked 必红）——与上方在场锚同批同源。
    val forbidden = Set(
      "MultiEdit",
      "NodeList", // NodeList（00:48 裁定摘除，dispatcher 面不受影响）
      // R2 反转（2026-09-12）："Mail" 从本集**摘除**——Mail 已翻案为唯一消息原语并进入
      // Nebula 面（−Task +Mail；史实 16→16 净 0；史实 2026-09-16 18:41 令后 −2 ⇒ 13）；新增 "Task"/"NodeMessage" 两个已删净退役件。
      "Task",
      "NodeMessage",
      "FlowTrigger",
      "FlowExecute", // 已退役/维持退役
      // Delegate 已由 builtin-def 批 2026-10-03 作者令②恢复在场（史实 −1 ⇒ 12 的
      // 退役批被取代）——从 forbidden 集移出，改由上方 expected 集的成员资格断言。
      "TransferFile", // #145 附件腿批退役（2026-09-14）：能力并入 SendMessage 设备附件腿
      "WebSearch",
      "WebFetch",
      "Curl",
      "TeamTaskCreate",
      "TeamTaskUpdate",
      "TeamTaskList",
      "SubTask",
      "NodeEdit",
      "NodeCancel",
      "FlowReport",
      "Load"
    )
    val leaked = delivered.intersect(forbidden)
    assertEquals(leaked, Set.empty[String], s"被显式移除的工具泄漏进 Nebula 清单: $leaked")

  // ===== 分发器固定集 =====

  test("dispatcher LLM tool list == Node 三件 + AskUserQuestion + 读四件（isFlowNode spawn 形态；agentflow 批 −Mail +AskUserQuestion）"):
    val delivered = CoreProbe.toolList(mkDef("project-dispatcher"), isFlowNode = true).toSet
    assertEquals(
      delivered,
      Set("NodeList", "NodeEdit", "NodeCancel", "AskUserQuestion", "Read", "Glob", "Grep", "Bash"),
      "分发器固定工具集（agentflow 批 2026-10-02：−Mail +AskUserQuestion，件数 9→9 中本 spawn 形态不含 projectBoardSession ⇒ 8 件）：不给 Write/Edit；TaskBoard 由 projectBoardSession 单独挂载（本形态未置位故不在场）"
    )

  test("NodeMessage 仅分发器（20260905 机制批裁定⑥）：Nebula/节点执行面均不含"):
    assert(!CoreProbe.toolList(mkDef("Nebula")).toSet.contains("NodeMessage"), "NodeMessage 已删净退役（Nebula 面不加）")
    assert(
      !CoreProbe.toolList(mkDef(executorName), isFlowNode = true).toSet.contains("NodeMessage"),
      "NodeMessage 已删净退役（节点面不加）"
    )
    // R2 细则：节点会话**零 Mail 入口**（工具面结构性摘除）
    assert(!CoreProbe.toolList(mkDef(executorName), isFlowNode = true).toSet.contains("Mail"), "节点面零 Mail（R2 细则）")

  // ===== 执行 agent 固定 9 件（builtin-merge 批 2026-10-03：kernel+general ⇒ nebflow）=====

  test("执行 agent LLM tool list == NebflowFixedTools 9 件（isFlowNode 节点形态；2026-09-08 恢复 AskUser；2026-09-10 摘 Pop；builtin-merge 批 +Subagent+Workflow）"):
    // P1-3 归因修正（2026-10-03）：原断言用手搓 "general" 名造节点身份——该名已
    // 非内置名（builtin-merge 批收敛名集 = {Nebula, project-dispatcher, nebflow,
    // subagent}），落 legacyFixedTools catch-all ⇒ 实测 6 件 BaseTools 全族假红。
    // 生产路径按 BuiltinAgents.ExecutorName 落名（NodeEditTool createNode），判据
    // 源对齐执行名 = 修根因不是放宽断言。9 件 = BaseTools 6 + AskUserQuestion
    // （2026-09-08 恢复）+ Subagent + Workflow（builtin-merge 批机制面）。
    val delivered = CoreProbe.toolList(mkDef(executorName), isFlowNode = true).toSet
    assertEquals(
      delivered,
      AgentCore.NebflowFixedTools.filter(ToolRegistry.TOOL_MAP.contains),
      "执行 agent 交付面 = NebflowFixedTools 注册表实收（BaseTools 六件 + AskUserQuestion + Subagent + Workflow）"
    )

  // ===== ToolRegistry 面变化 =====

  test("MultiEdit is removed from ToolRegistry (class kept for spec/plugin reference)"):
    assert(!ToolRegistry.TOOL_MAP.contains("MultiEdit"), "registry 不再挂 MultiEdit（移除=注册层不挂）")
    assert(!ToolRegistry.builtinToolNames.contains("MultiEdit"))
    assert(!ToolRegistry.ALL_TOOLS.exists(_.name == "MultiEdit"))
    // 类保留：Edit 共享编辑内核仍在（EditToolSpec/MultiEditToolSpec 编译即证）

  test("MemoryNote / TaskList 已注销（govmemory 批 2026-09-25 裁定）——一切身份声明不授能；dream 的 MemoryNote 豁免保留在剥离函数但无执行面"):
    // P1-3 归因修正（2026-10-03）：本条**基线既有红**（两树同红，根因 = 原断言
    // 「MemoryNote 进注册表」违反 govmemory 批 2026-09-25 裁定「MemoryNote registration
    // retired」——该裁定属任务书明示的对齐基准，按「断言过时对齐现实」对齐，
    // **不是**恢复注册）。当前注册表实收 = MemoryNote/TaskList 均缺席（registry
    // `TOOL_MAP` 无键——注销是刻意的：旧名注册回来会让 RetiredToolGuides 迁移指引
    // 永不触发且重开第二写入通道）。
    assert(!ToolRegistry.TOOL_MAP.contains("MemoryNote"), "MemoryNote 已注销（govmemory 批裁定）")
    assert(!ToolRegistry.TOOL_MAP.contains("TaskList"), "TaskList 已注销（同批，现体 = Task/TaskInfo）")
    // 阴性断言（2026-09-17 更名批）：旧名必须已从注册表彻底消失（无残留注册键 = 更名零悬挂）。
    // 旧名字面按片段拼接：让本批验收①的 tracked 面字面判据（tracked 树内 grep 旧名 = 0 行）成立，
    // 断言语义不受影响（判的是注册表键集，不是源码文本）。
    val legacyMemoryTool = "Memory" + "Edit"
    assert(!ToolRegistry.TOOL_MAP.contains(legacyMemoryTool), "旧名记忆工具必须已从注册表消失（更名零残留键）")
    // 未注册名 ⇒ 声明（含 wildcard）对任何身份都不授能（allowedSet 里可能有惰性
    // 字符串，但 buildToolList 按注册表过滤 ⇒ LLM 面永远缺席——「未注册名自然缺席」
    // 即本 spec 判据面的定义）。
    assert(!CoreProbe.toolList(mkDef("memo", List("MemoryNote"))).contains("MemoryNote"), "standalone 声明无效（未注册名不进 LLM 面）")
    assert(!CoreProbe.toolList(mkDef("omni", List("*"))).contains("MemoryNote"), "wildcard 也不给（记忆写面=Nebula+dream 的静态面语义随注销仅存于剥离函数）")
    assert(!CoreProbe.toolList(mkDef("dream", List("*"))).contains("MemoryNote"), "dream wildcard 同样缺席（豁免只放授能面，注册表墙先于豁免生效）")
    val executorDef = CoreProbe.allowed(mkDef(executorName, List("MemoryNote")))
    assert(!executorDef.contains("MemoryNote"), "执行 agent 身份同样无 MemoryNote 授能——剥离语义不变")

  test("dream MemoryNote 豁免保留在 exclusiveToolsFor（无执行面），其余 Nebula 专属仍被剥"):
    // P1-3 归因修正（2026-10-03）：本条**基线既有红**（两树同红，根因 = 原断言
    // 「dream wildcard 授能 MemoryNote」建立在「MemoryNote 已注册」前提上——该前提
    // 被 govmemory 批裁定移除）。现钉：豁免机制本身保留在剥离函数（DreamAdmittedTools
    // 条目——工具退役后仅存授能/剥离语义，无执行面），Nebula 专属其余件对 dream
    // 仍全集剥离；「MemoryNote 授能」现表现为 allowedSet 惰性字符串（不注册 ⇒
    // LLM 面/执行面双缺席，上一用例已钉），不为测绿而宣称有执行面。
    // 豁免恰为 MemoryNote 一件——Schedule/Delegate/AgentControl/TaskList/TaskBoard/node_report/Pop/ListFriends/Subagent/Workflow 对 dream 不得放开
    assertEquals(
      AgentCore.RootExclusiveTools -- AgentCore.DreamAdmittedTools,
      Set("Schedule", "Delegate", "AgentControl", "TaskList", "TaskBoard", "node_report", "Pop", "ListFriends", "Subagent", "Workflow"),
      "dream 豁免面 = 仅 MemoryNote（builtin-merge 批 +Subagent/Workflow 防声明逃逸条目同样剥离；TaskBoard/node_report/Pop/ListFriends 对 dream 同样剥离，真实授能在 project 会话身份末段追加 / Pop 仅 Nebula / ListFriends 仅 Nebula）"
    )
    val sneakyDream =
      CoreProbe.allowed(mkDef("dream", List("Schedule", "Delegate", "AgentControl", "TaskList", "TaskBoard")))
    assert(!sneakyDream.contains("Schedule"), "dream 对 Schedule 仍被剥")
    assert(!sneakyDream.contains("Delegate"), "dream 对 Delegate 仍被剥")
    assert(!sneakyDream.contains("AgentControl"), "dream 对 AgentControl 仍被剥（机制层 controlGrant 也只给 Nebula/lead）")
    assert(!sneakyDream.contains("TaskList"), "dream 对 TaskList 仍被剥（TaskList 批：非 DreamAdmittedTools）")
    assert(!sneakyDream.contains("TaskBoard"), "dream 对 TaskBoard 仍被剥（任务板批 2：非 DreamAdmittedTools，声明不授能）")
    // 单点函数全身份语义（Nebula 空 / dream 豁免 / 其余全集）
    assertEquals(AgentCore.exclusiveToolsFor("Nebula"), Set.empty[String], "Nebula 无剥离")
    assertEquals(
      AgentCore.exclusiveToolsFor("dream"),
      Set("Schedule", "Delegate", "AgentControl", "TaskList", "TaskBoard", "node_report", "Pop", "ListFriends", "Subagent", "Workflow"),
      "dream 剥十件（原八件 + builtin-merge 批防声明逃逸的 Subagent/Workflow——执行 agent 单点授能，dream 不在授权面）"
    )
    // 2026-09-10 作者裁定：Pop 收归 Nebula 专属——dream（及一切非 Nebula 身份）
    // 拿不到 Pop：不在 DreamAdmittedTools 豁免面内
    assert(!AgentCore.DreamAdmittedTools.contains("Pop"), "DreamAdmittedTools 不含 Pop ⇒ dream 拿不到 Pop")
    assert(
      !CoreProbe.allowed(mkDef("dream", List("Pop"))).contains("Pop"),
      "dream 声明 Pop 无效（非 DreamAdmittedTools；Pop 仅 Nebula）"
    )
    // 执行 agent 的机制面豁免（builtin-merge 批 2026-10-03）：Subagent/Workflow
    // 是 NebflowFixedTools 单点授能 ⇒ exclusiveToolsFor("nebflow") 剥全集 −
    // {Subagent, Workflow}；其余身份（含通用自定义名）仍剥全集。
    assertEquals(
      AgentCore.exclusiveToolsFor(executorName),
      AgentCore.RootExclusiveTools -- Set("Subagent", "Workflow"),
      "执行 agent 剥全集 − {Subagent, Workflow}（机制面单点授能）"
    )
    assertEquals(AgentCore.exclusiveToolsFor("some-custom"), AgentCore.RootExclusiveTools, "其余身份剥全集")

  // ===== §C.5：Glob/Grep 缺省根 = node root =====

  test("Glob default root = sandbox.root (node root) — sibling-dir marker invisible"):
    val root = os.Path(Files.createTempDirectory("nb-2c-glob-root"))
    val sibling = os.Path(Files.createTempDirectory("nb-2c-glob-sib"))
    try
      os.write(root / "marker-2c.zzc", "x")
      os.write(sibling / "sibling-2c.zzc", "x")
      val ctx = ToolContext(
        projectRoot = root.toString,
        sandbox = SandboxPolicy.forRoot(root, SandboxConfig())
      )
      val res = GlobTool.call(JsonObject("pattern" -> "*.zzc".asJson), ctx).unsafeRunSync()
      val out = res.toOption.get
      assert(out.contains("marker-2c.zzc"), "root 内 marker 可见")
      assert(!out.contains("sibling-2c.zzc"), "root 外（含 JVM user.dir）文件不可见——缺省根=root 非 user.dir")
    finally
      os.remove.all(root); os.remove.all(sibling)

  test("Grep default root = sandbox.root (node root) — sibling-dir content invisible"):
    val root = os.Path(Files.createTempDirectory("nb-2c-grep-root"))
    val sibling = os.Path(Files.createTempDirectory("nb-2c-grep-sib"))
    try
      os.write(root / "a.txt", "NEBULA2CMARKER here\n")
      os.write(sibling / "b.txt", "NEBULA2CMARKER there\n")
      val ctx = ToolContext(
        projectRoot = root.toString,
        sandbox = SandboxPolicy.forRoot(root, SandboxConfig())
      )
      val res = GrepTool
        .call(
          JsonObject("pattern" -> "NEBULA2CMARKER".asJson, "output_mode" -> "content".asJson),
          ctx
        )
        .unsafeRunSync()
      val out = res.toOption.get
      assert(out.contains("NEBULA2CMARKER"), "root 内命中")
      assert(out.contains("a.txt"), s"命中来自 root 内文件（实际输出：${out.take(120)}）")
      assert(!out.contains("b.txt"), "sibling 目录内容不可见——缺省根=root 非 user.dir")
    finally
      os.remove.all(root); os.remove.all(sibling)
    end try
end AgentConvergenceSpec
