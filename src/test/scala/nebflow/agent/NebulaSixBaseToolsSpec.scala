package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.sandbox.{SandboxConfig, SandboxPolicy}
import nebflow.core.tools.{EditTool, ToolContext, WriteTool}

/**
 * Nebula 基础六件补齐 spec（2026-09-05 13:11 作者裁定：基础六件
 * Read/Glob/Edit/Write/Grep/Bash 作为所有 agent 的统一默认工具集，Nebula
 * 也不例外——nebula-toolface@21bc2e74 只给 Nebula 回归了 Bash/Read/Glob/Grep
 * 四件，本批补齐 Write/Edit；08:40 批「严禁夹带 Write/Edit」限制被推翻）。
 *
 * - ① 六件基础 ⊆ Nebula 机制集（fixedToolsFor 静态集 + buildAllowedToolSet
 *   交付面双层）+ 恰十七件计数——本文件即变异验红锚点：机制集缺任一件
 *   基础六件（或计数漂移）即红。
 * - ② 六件基础 ⊆ general 机制集（GeneralFixedTools = BaseTools + 用户面二件）
 *   ——回归钉死（general 已含六件是断言对象非改动对象）。
 * - ③ 联合语义（同支沙箱批 8de54441 之上）：Write/Edit 真工具调用 × Nebula
 *   会话写根（root=PathUtil.dataRoot，SandboxPolicy.sessionRoot 推导产物）——
 *   写根内放行（建+改+读回）、写根外拒（SANDBOX_DENIED）。WriteTool.scala:74 /
 *   EditTool.scala:137 与 FileSandbox.checkWrite 同闸（SandboxSpec 已有闸层
 *   断言，此处补真工具层取证）。
 */
class NebulaSixBaseToolsSpec extends FunSuite:

  private object CoreProbe extends AgentCore:
    def allowed(defn: AgentDef, isFlowNode: Boolean = false): Set[String] =
      buildAllowedToolSet(defn, isFlowNode = isFlowNode)

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  // dataRoot 是全局可变状态（SandboxSpec 同款防串扰）：用例前钉到 home 下一次性
  // 目录、用例后还原并清理。
  private var savedDataRoot: Option[os.Path] = None
  private var pinnedDataRoot: Option[os.Path] = None

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedDataRoot = Some(PathUtil.dataRoot)
    val pinned = os.home / s".nb-sixtools-dataroot-${System.nanoTime()}"
    os.makeDir.all(pinned / "projects")
    PathUtil.setDataRoot(pinned)
    pinnedDataRoot = Some(pinned)
    super.beforeEach(context)

  override def afterEach(context: munit.AfterEach): Unit =
    savedDataRoot.foreach(PathUtil.setDataRoot)
    pinnedDataRoot.foreach(p => os.remove.all(p))
    super.afterEach(context)

  // ===== ① 基础六件 ⊆ Nebula 机制集（变异验红锚点）=====

  test("① 六件基础 ⊆ Nebula 机制集（静态集+交付面双层）且恰十七件——缺任一件即红"):
    val six = AgentCore.BaseTools
    assert(six == Set("Read", "Write", "Edit", "Glob", "Grep", "Bash"),
      "前置：BaseTools 即基础六件")
    val fixed = AgentCore.fixedToolsFor(mkDef("Nebula"))
    six.foreach(t => assert(fixed.contains(t), s"Nebula 机制集缺基础六件之一: $t"))
    // 交付面（buildAllowedToolSet，注册表过滤后）同样全六件
    val delivered = CoreProbe.allowed(mkDef("Nebula"))
    six.foreach(t => assert(delivered.contains(t), s"Nebula 交付面缺基础六件之一: $t"))
    // 补齐后恰十七件（15 编排/通信/可视化/用户/平台/记忆 + 基础六件）
    assertEquals(fixed.size, 17, "Nebula 机制集恰十七件（2026-09-05 13:11 裁定）")

  // ===== ② 基础六件 ⊆ general 机制集（回归钉死）=====

  test("② 六件基础 ⊆ general 机制集（GeneralFixedTools=六件+用户面二件）"):
    val six = AgentCore.BaseTools
    assert(six.subsetOf(AgentCore.GeneralFixedTools),
      s"general 固定集必须含基础六件（缺: ${six.diff(AgentCore.GeneralFixedTools)}）")
    assertEquals(AgentCore.GeneralFixedTools.size, 8,
      "general 固定集恰八件（裁定 5：BaseTools 六件 + AskUserQuestion/Pop）")
    val delivered = CoreProbe.allowed(mkDef("general"), isFlowNode = true)
    six.foreach(t => assert(delivered.contains(t), s"general 交付面缺基础六件之一: $t"))

  // ===== ③ 联合语义：Write/Edit 真工具 × Nebula 会话写根（root=dataRoot）=====

  test("③ Write/Edit 真工具调用 × Nebula 写根：根内成功（建+改+读回）、根外拒"):
    val root = PathUtil.dataRoot
    assert(SandboxPolicy.sessionRoot(sandboxEnabled = true, depth = 0, agentName = "Nebula",
      projectRoot = Some((root / "projects" / "x").toString), fallbackProjectRoot = "/fallback") == root.toString,
      "前置：Nebula 根会话写根 = dataRoot（沙箱批 sessionRoot 推导）")
    val policy = SandboxPolicy.forRoot(root, SandboxConfig())
    val ctx = ToolContext(projectRoot = root.toString, sandbox = policy)
    // 根内：Write 建（nebflow.json 运维补丁形态）→ Edit 改 → 读回验证
    val target = root / "nebflow-sixtools-spec.json"
    val w = WriteTool.call(
      JsonObject("file_path" -> target.toString.asJson, "content" -> "{\"patch\":1}".asJson), ctx
    ).unsafeRunSync()
    assert(w.isRight, s"Nebula 写根内 Write 必须成功: ${w.left.map(_.message)}")
    val e = EditTool.call(
      JsonObject("file_path" -> target.toString.asJson,
        "old_string" -> "{\"patch\":1}".asJson, "new_string" -> "{\"patch\":2}".asJson), ctx
    ).unsafeRunSync()
    assert(e.isRight, s"Nebula 写根内 Edit 必须成功: ${e.left.map(_.message)}")
    assert(os.read(target).contains("\"patch\":2"), "读回验证：Edit 已生效")
    // 根外：Write 必拒（SANDBOX_DENIED——os.home 本身不在 writableRoots）
    val outside = os.home / s"nb-sixtools-outside-${System.nanoTime()}.txt"
    val w2 = WriteTool.call(
      JsonObject("file_path" -> outside.toString.asJson, "content" -> "x".asJson), ctx
    ).unsafeRunSync()
    assert(w2.left.exists(_.message.startsWith("SANDBOX_DENIED")),
      s"写根外 Write 必须拒: ${w2}")
    assert(!os.exists(outside), "根外文件未被创建（拒绝发生在闸层）")

end NebulaSixBaseToolsSpec
