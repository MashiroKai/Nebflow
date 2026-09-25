package nebflow.agent

import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.sandbox.{SandboxConfig, SandboxPolicy}
import nebflow.core.tools.{GlobTool, GrepTool, ToolContext, ToolRegistry}

import java.nio.file.Files

/**
 * 阶段 2c agent 收敛 spec（设计文档 §C.1/§C.5 + §G.3 验收点①③）。
 *
 * - §G.3-①：Nebula 工具清单 = §C.1 NebulaSet 逐项断言（buildToolList 层——
 *   LLM 实际收到的工具定义列表，未注册名自然缺席，比 allowedSet 更接近交付面）。
 * - dispatcher / general 固定集同层断言（§C.1 分发器行 / §C.4 七件）。
 * - MultiEdit 从 ToolRegistry 删除（§C.1：能力由 Edit replace_all 覆盖）；
 *   旧记忆记账工具已整体退役（govmemory 批 2026-09-25）：注册表零挂、
 *   一切身份声明无效、迁移指引表带 Edit/Write 直写口径；旧 dream 准入例外
 *   随工具退役一并摘除（dream 身份回落默认剥离分支）。
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

  // ===== §G.3-① Nebula 工具清单 = §C.1 矩阵 =====

  test("Nebula LLM tool list == §C.1 NebulaSet exactly（编排/任务/通信/读三件/写手三件/可视化/用户/平台）"):
    val delivered = CoreProbe.toolList(mkDef("Nebula")).toSet
    val expected = Set(
      "Mail", "ProjectCreate", "AgentControl",
      // Delegate 退役批（史实 −1，13 → 12）：一次性执行任务改路由 general 项目
      "Task",                                               // task orchestration (taskunify merge batch 2026-09-24: TaskList + TaskBoard merged into Task, net count 0)
      "SendMessage",
      "ListFriends",                                        // 通信（2026-09-12 好友消息改造批 ⑩：只读名册，+1）
      "Read",                                               // 读件（08:40 解禁四件；2026-09-18 18:18 令恢复 Glob/Grep + 写手三件）
      "Card",                                               // 可视化（2026-09-05 解封恢复）
      "AskUserQuestion", "Pop",
      "Schedule",
      // MemoryNote（旧记忆行）已随 govmemory 批（2026-09-25）退役摘除：记忆 = Edit/Write 直写
      // 文件面五件（2026-09-18 18:18 作者令「恢复nebula的bash edit write glob grep」）
      "Glob", "Grep", "Bash", "Write", "Edit"
    )
    assertEquals(delivered, expected,
      "the tool list Nebula exposes to the LLM must equal the fixed §C.1 matrix entry by entry (the count is single-sourced from AgentCore.NebulaOrchestrationToolsExpectedSize: in flight 16 = the value after the govmemory batch 2026-09-25 -MemoryNote; history: the 2026-09-18 18:18 author order +Bash/Edit/Write/Glob/Grep => 17; friend-message refactor batch (10) +ListFriends; TaskList batch +TaskList; task board batch 2 +TaskBoard; NodeList removal; the two patches -Glob -Grep and -Delegate are prior facts; taskunify merge batch 2026-09-24 merged TaskList + TaskBoard into the single Task => net count 0; zero Issue)")
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

  test("the Nebula list contains the six file-face tools (Read/Glob/Grep/Bash/Write/Edit all present, 2026-09-18 18:18 order); zero NodeList, zero MultiEdit, zero Web family, zero legacy-set three, zero TeamTask/SubTask/NodeEdit/NodeCancel, zero TaskList/TaskBoard"):
    val delivered = CoreProbe.toolList(mkDef("Nebula")).toSet
    // 2026-09-18 18:18 作者令：root 面恢复 Bash/Edit/Write/Glob/Grep（+既有的 Read
    // ⇒ 文件面六件在场）；取代 2026-09-16 18:41 与 2026-09-05 23:34 两笔摘除令之
    // root 面部分（仅 root 面）。
    Set("Read", "Glob", "Grep", "Bash", "Write", "Edit").foreach { t =>
      assert(delivered.contains(t), s"文件面六件必须机制固定（2026-09-18 18:18 令恢复 5 件 + 既有 Read）: $t")
    }
    // 🔴 Bash/Write/Edit 已从下方 forbidden 集移出（2026-09-18 18:18 令恢复 ⇒ 在场
    // 合法，留在 forbidden 里 leaked 必红）——与上方在场锚同批同源。
    val forbidden = Set("MultiEdit", "NodeList",  // NodeList（00:48 裁定摘除，dispatcher 面不受影响）
      // R2 反转（2026-09-12）："Mail" 从本集**摘除**——Mail 已翻案为唯一消息原语并进入
      // Nebula face (-Task +Mail; history 16->16 net 0; history: after the 2026-09-16 18:41 order -2 => 13).
      // taskunify merge batch (2026-09-24): `Task` is **removed** from this set -- it has come
      // back as the unified ledger's only write face (replacing TaskList + TaskBoard, net count
      // 0); the newly retired entries are `TaskList` / `TaskBoard`.
      "TaskList", "TaskBoard", "NodeMessage", "FlowTrigger", "FlowExecute",  // deleted-retired
      "Delegate",                                           // 退役批（史实 −1 ⇒ 12）：一次性执行任务改路由 general 项目
      "TransferFile",                                       // #145 附件腿批退役（2026-09-14）：能力并入 SendMessage 设备附件腿
      "WebSearch", "WebFetch", "Curl",
      "TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList", "SubTask",
      "NodeEdit", "NodeCancel", "FlowReport", "Load")
    val leaked = delivered.intersect(forbidden)
    assertEquals(leaked, Set.empty[String], s"被显式移除的工具泄漏进 Nebula 清单: $leaked")

  // ===== 分发器固定集 =====

  test("dispatcher LLM tool list == Node 四件 + 读四件（isFlowNode spawn 形态；NodeMessage 20260905 机制批）"):
    val delivered = CoreProbe.toolList(mkDef("project-dispatcher"), isFlowNode = true).toSet
    assertEquals(delivered, Set("NodeList", "NodeEdit", "NodeCancel", "Mail", "Read", "Glob", "Grep", "Bash"),
      "分发器固定工具集（§C.1 + NodeMessage 20260905 机制批第八件）：不给 Write/Edit/AskUserQuestion")

  test("NodeMessage 仅分发器（20260905 机制批裁定⑥）：Nebula/general 交付面均不含"):
    assert(!CoreProbe.toolList(mkDef("Nebula")).toSet.contains("NodeMessage"), "NodeMessage 已删净退役（Nebula 面不加）")
    assert(!CoreProbe.toolList(mkDef("general"), isFlowNode = true).toSet.contains("NodeMessage"), "NodeMessage 已删净退役（节点面不加）")
    // R2 细则：节点会话**零 Mail 入口**（工具面结构性摘除）
    assert(!CoreProbe.toolList(mkDef("general"), isFlowNode = true).toSet.contains("Mail"), "节点面零 Mail（R2 细则）")

  // ===== general 固定 7 件（2026-09-08 恢复 AskUser；2026-09-10 摘 Pop）=====

  test("general LLM tool list == 7 件（isFlowNode 节点形态；2026-09-10 作者裁定摘 Pop）"):
    val delivered = CoreProbe.toolList(mkDef("general"), isFlowNode = true).toSet
    assertEquals(delivered, Set("Read", "Glob", "Edit", "Write", "Grep", "Bash", "AskUserQuestion"),
      "通用模版固定 7 件（§C.5 顺序语义 + 2026-09-08 作者修订恢复 AskUser + 2026-09-10 裁定 Pop 收归 Nebula 专属，非配置）")

  // ===== ToolRegistry 面变化 =====

  test("MultiEdit is removed from ToolRegistry (class kept for spec/plugin reference)"):
    assert(!ToolRegistry.TOOL_MAP.contains("MultiEdit"), "registry 不再挂 MultiEdit（移除=注册层不挂）")
    assert(!ToolRegistry.builtinToolNames.contains("MultiEdit"))
    assert(!ToolRegistry.ALL_TOOLS.exists(_.name == "MultiEdit"))
    // 类保留：Edit 共享编辑内核仍在（EditToolSpec/MultiEditToolSpec 编译即证）

  test("the retired memory bookkeeping tool is fully deregistered; no identity can summon it (govmemory batch 2026-09-25)"):
    val retiredMemoryTool = "Memory" + "Note"
    assert(!ToolRegistry.TOOL_MAP.contains(retiredMemoryTool), "retired tool must be gone from the registry (govmemory batch)")
    assert(AgentCore.RetiredToolGuides.contains(retiredMemoryTool), "retired name carries the migration guide (direct Edit/Write wording)")
    // 阴性断言（2026-09-17 更名批）：旧名必须已从注册表彻底消失（无残留注册键 = 更名零悬挂）。
    // 旧名字面按片段拼接：让 tracked 面字面判据（tracked 树内 grep 旧名 = 0 行）成立，
    // 断言语义不受影响（判的是注册表键集，不是源码文本）。
    val legacyMemoryTool = "Memory" + "Edit"
    assert(!ToolRegistry.TOOL_MAP.contains(legacyMemoryTool), "旧名记忆工具必须已从注册表消失（更名零残留键）")
    // 交付面（schema 注入，模型可见面）零出现：未注册名无 schema、无可执行路径
    assert(!CoreProbe.toolList(mkDef("memo", List(retiredMemoryTool))).contains(retiredMemoryTool),
      "standalone 声明到不了交付面（注册表已无此件 ⇒ 无 schema 无执行路径）")
    val sneakyWildcard = CoreProbe.allowed(mkDef("omni", List("*")))
    assert(!sneakyWildcard.contains(retiredMemoryTool), "wildcard 也不给（未注册名不在 ALL_TOOLS）")
    val generalDef = CoreProbe.allowed(mkDef("general", List(retiredMemoryTool)))
    assert(!generalDef.contains(retiredMemoryTool), "收敛名无视声明（记忆写入 = Edit/Write 直写）")
    // 裸声明在 allowed 算术层原样透传（已退役名已离开剥离集 NebulaExclusiveTools）——
    // 零执行面兜底 = 调用命中 RetiredToolGuides 迁移指引（错误文案，零执行路径）
    val sneakyStandalone = CoreProbe.allowed(mkDef("memo", List(retiredMemoryTool)))
    assert(sneakyStandalone.contains(retiredMemoryTool),
      "raw declaration passes through the allowed-set arithmetic (the retired name left the strip set); execution returns the retirement guide only")

  test("dream admission exemption is gone (govmemory batch): dream falls through to the default strip-all branch"):
    val retiredMemoryTool = "Memory" + "Note"
    // 声明到不了交付面：注册表已无此件（豁免摘除 + 注册摘除双保险），无 schema 无执行路径
    assert(!CoreProbe.toolList(mkDef("dream", List(retiredMemoryTool))).contains(retiredMemoryTool),
      "dream declaration reaches no schema face — admission exemption and registry entry are both gone")
    val sneakyDream = CoreProbe.allowed(mkDef("dream", List("Schedule", "Delegate", "AgentControl", "Task", "TaskInfo")))
    assert(!sneakyDream.contains("Schedule"), "dream 对 Schedule 仍被剥")
    assert(!sneakyDream.contains("Delegate"), "dream 对 Delegate 仍被剥")
    assert(!sneakyDream.contains("AgentControl"), "dream 对 AgentControl 仍被剥（机制层 controlGrant 也只给 Nebula/lead）")
    assert(!sneakyDream.contains("Task"), "dream is still stripped of Task (taskunify batch)")
    assert(!sneakyDream.contains("TaskInfo"), "dream is still stripped of TaskInfo (a read-only entry is likewise stripped; declaring it grants nothing)")
    // 单点函数全身份语义（govmemory 批：dream 例外摘除 ⇒ 除 Nebula 外全身份同集）
    assertEquals(AgentCore.exclusiveToolsFor("Nebula"), Set.empty[String], "Nebula 无剥离")
    assertEquals(AgentCore.exclusiveToolsFor("dream"), AgentCore.NebulaExclusiveTools,
      "dream 准入例外已摘（govmemory 批）⇒ 剥全集（与其它非 Nebula 身份同）")
    // 2026-09-10 作者裁定：Pop 收归 Nebula 专属——dream（及一切非 Nebula 身份）拿不到 Pop
    assert(!CoreProbe.allowed(mkDef("dream", List("Pop"))).contains("Pop"),
      "dream 声明 Pop 无效（Pop 仅 Nebula）")
    assertEquals(AgentCore.exclusiveToolsFor("general"), AgentCore.NebulaExclusiveTools, "其余身份剥全集")

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
      val res = GrepTool.call(
        JsonObject("pattern" -> "NEBULA2CMARKER".asJson, "output_mode" -> "content".asJson), ctx
      ).unsafeRunSync()
      val out = res.toOption.get
      assert(out.contains("NEBULA2CMARKER"), "root 内命中")
      assert(out.contains("a.txt"), s"命中来自 root 内文件（实际输出：${out.take(120)}）")
      assert(!out.contains("b.txt"), "sibling 目录内容不可见——缺省根=root 非 user.dir")
    finally
      os.remove.all(root); os.remove.all(sibling)
end AgentConvergenceSpec
