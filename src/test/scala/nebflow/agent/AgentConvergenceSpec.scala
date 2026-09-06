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
 * - dispatcher / general 固定集同层断言（§C.1 分发器行 / §C.4 八件）。
 * - MultiEdit 从 ToolRegistry 删除（§C.1：能力由 Edit replace_all 覆盖），
 *   MemoryEdit 注册且 Nebula 专属（§C.1 记忆行 + NebulaExclusiveTools）；
 *   dream 受限准入例外（2026-09-05 作者签准，DreamAdmittedTools——动作面
 *   append 仍由 MemoryEditTool 拒绝，见 MemoryEditToolSpec）。
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

  test("Nebula LLM tool list == §C.1 NebulaSet exactly（编排/通信/读三件/可视化/用户/平台/记忆）"):
    val delivered = CoreProbe.toolList(mkDef("Nebula")).toSet
    val expected = Set(
      "Task", "ProjectCreate", "AgentControl",
      "SendFriendMessage",
      "Read", "Glob", "Grep",                                // 读三件（08:40 解禁四件；23:34 裁定收走写手）
      "Card",                                               // 可视化（2026-09-05 解封恢复）
      "AskUserQuestion", "Pop",
      "Schedule", "TransferFile",
      "MemoryEdit"
    )
    assertEquals(delivered, expected,
      "Nebula 面向 LLM 的工具清单必须逐项等于 §C.1 固定矩阵（2026-09-06 00:48 作者裁定：NodeList 摘除——out 边自动投递取代主动查图，恰十三件；2026-09-05 23:34 作者裁定：Nebula 回归纯编排——Bash/Write/Edit 移除；零 Issue）")
    assert(!delivered.contains("Issue"), "交付面零 Issue（2026-09-04 终裁退役）")
    // 钉死断言（2026-09-05 23:34 作者裁定）：Nebula 机制集不含 Bash、不含 Write、
    // 不含 Edit——变异验红锚（机制集加回任一件本组断言即红）
    assert(!delivered.contains("Bash"), "Nebula 无写手：Bash 已移除（23:34 裁定）")
    assert(!delivered.contains("Write"), "Nebula 无写手：Write 已移除（23:34 裁定）")
    assert(!delivered.contains("Edit"), "Nebula 无写手：Edit 已移除（23:34 裁定）")

  test("Nebula 清单含读三件、零写手（Bash/Write/Edit 均不在）；零 NodeList、零 MultiEdit、零 Web 系、零旧体系四件、零 TeamTask/SubTask/NodeEdit/NodeCancel"):
    val delivered = CoreProbe.toolList(mkDef("Nebula")).toSet
    Set("Read", "Glob", "Grep").foreach { t =>
      assert(delivered.contains(t), s"读三件必须机制固定（2026-09-05 23:34 裁定）: $t")
    }
    Set("Bash", "Write", "Edit").foreach { t =>
      assert(!delivered.contains(t), s"写手三件不得出现在 Nebula 交付面（23:34 裁定）: $t")
    }
    val forbidden = Set("Bash", "Write", "Edit", "MultiEdit", "NodeList",  // NodeList（00:48 裁定摘除，dispatcher 面不受影响）
      "Mail", "Delegate", "FlowTrigger", "FlowExecute",     // 旧体系四件（2026-09-05 裁定退役）
      "WebSearch", "WebFetch", "Curl",
      "TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList", "SubTask",
      "NodeEdit", "NodeCancel", "FlowReport", "Load")
    val leaked = delivered.intersect(forbidden)
    assertEquals(leaked, Set.empty[String], s"被显式移除的工具泄漏进 Nebula 清单: $leaked")

  // ===== 分发器固定集 =====

  test("dispatcher LLM tool list == Node 四件 + 读四件（isFlowNode spawn 形态；NodeMessage 20260905 机制批）"):
    val delivered = CoreProbe.toolList(mkDef("project-dispatcher"), isFlowNode = true).toSet
    assertEquals(delivered, Set("NodeList", "NodeEdit", "NodeCancel", "NodeMessage", "Read", "Glob", "Grep", "Bash"),
      "分发器固定工具集（§C.1 + NodeMessage 20260905 机制批第八件）：不给 Write/Edit/AskUserQuestion")

  test("NodeMessage 仅分发器（20260905 机制批裁定⑥）：Nebula/general 交付面均不含"):
    assert(!CoreProbe.toolList(mkDef("Nebula")).toSet.contains("NodeMessage"), "Nebula 不加 NodeMessage")
    assert(!CoreProbe.toolList(mkDef("general"), isFlowNode = true).toSet.contains("NodeMessage"), "general 不加 NodeMessage")

  // ===== general 固定 8 件 =====

  test("general LLM tool list == 裁定 5 原文 8 件（isFlowNode 节点形态）"):
    val delivered = CoreProbe.toolList(mkDef("general"), isFlowNode = true).toSet
    assertEquals(delivered, Set("Read", "Glob", "Edit", "Write", "Grep", "Bash", "AskUserQuestion", "Pop"),
      "通用模版固定 8 件（§C.5 顺序语义，非配置）")

  // ===== ToolRegistry 面变化 =====

  test("MultiEdit is removed from ToolRegistry (class kept for spec/plugin reference)"):
    assert(!ToolRegistry.TOOL_MAP.contains("MultiEdit"), "registry 不再挂 MultiEdit（移除=注册层不挂）")
    assert(!ToolRegistry.builtinToolNames.contains("MultiEdit"))
    assert(!ToolRegistry.ALL_TOOLS.exists(_.name == "MultiEdit"))
    // 类保留：Edit 共享编辑内核仍在（EditToolSpec/MultiEditToolSpec 编译即证）

  test("MemoryEdit is registered; non-Nebula agents never see it even when declared — dream admitted (2026-09-05)"):
    assert(ToolRegistry.TOOL_MAP.contains("MemoryEdit"), "MemoryEdit 进注册表（Nebula 注入源）")
    val sneakyStandalone = CoreProbe.allowed(mkDef("memo", List("MemoryEdit")))
    assert(!sneakyStandalone.contains("MemoryEdit"), "standalone 声明无效（NebulaExclusiveTools；dream 除外）")
    val sneakyWildcard = CoreProbe.allowed(mkDef("omni", List("*")))
    assert(!sneakyWildcard.contains("MemoryEdit"), "wildcard 也不给（记忆写面=Nebula+dream，其余身份零变化）")
    val generalDef = CoreProbe.allowed(mkDef("general", List("MemoryEdit")))
    assert(!generalDef.contains("MemoryEdit"), "其他身份（general）仍无 MemoryEdit 授能——剥离语义不变")

  test("dream MemoryEdit 准入（2026-09-05 作者签准）：声明即授能，其余三件 Nebula 专属仍被剥"):
    val declared = CoreProbe.allowed(mkDef("dream", List("MemoryEdit")))
    assert(declared.contains("MemoryEdit"), "dream 声明 MemoryEdit → 授能（exclusiveToolsFor 豁免剥离）")
    val wildcard = CoreProbe.allowed(mkDef("dream", List("*")))
    assert(wildcard.contains("MemoryEdit"), "dream wildcard 同样授能（豁免在剥离面，声明形状无关）")
    // 豁免恰为 MemoryEdit 一件——Schedule/Delegate/AgentControl 对 dream 不得放开
    assertEquals(AgentCore.NebulaExclusiveTools -- AgentCore.DreamAdmittedTools,
      Set("Schedule", "Delegate", "AgentControl"), "dream 豁免面 = 仅 MemoryEdit")
    val sneakyDream = CoreProbe.allowed(mkDef("dream", List("Schedule", "Delegate", "AgentControl")))
    assert(!sneakyDream.contains("Schedule"), "dream 对 Schedule 仍被剥")
    assert(!sneakyDream.contains("Delegate"), "dream 对 Delegate 仍被剥")
    assert(!sneakyDream.contains("AgentControl"), "dream 对 AgentControl 仍被剥（机制层 controlGrant 也只给 Nebula/lead）")
    // 单点函数全身份语义（Nebula 空 / dream 豁免 / 其余全集）
    assertEquals(AgentCore.exclusiveToolsFor("Nebula"), Set.empty[String], "Nebula 无剥离")
    assertEquals(AgentCore.exclusiveToolsFor("dream"), Set("Schedule", "Delegate", "AgentControl"), "dream 剥三件")
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
