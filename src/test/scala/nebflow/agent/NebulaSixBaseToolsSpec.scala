package nebflow.agent

import munit.FunSuite

/**
 * Nebula 工具面写手裁撤 spec（2026-09-05 23:34 作者裁定「把你的 bash 和编辑
 * 工具收起来」：Nebula 回归纯编排——Bash/Write/Edit 从 NebulaOrchestrationTools
 * 移除；general/BaseTools 六件默认注入不变，Nebula 是唯一例外）。
 * 【2026-09-06 00:48 作者裁定增补】NodeList 从 NebulaOrchestrationTools 摘除
 * （节点结果沿 out 边自动投递 Nebula，主动查图与裁定职责重叠；dispatcher
 * 自身面不受影响）——本集彼时恰十三件。
 * 【2026-09-06 TaskList 批增补】+TaskList（作者 00:07 提议 + 00:11 首期无前端
 * 拍板：任务=快变状态出记忆、入 tasks.json 运行时数据层）——本集恰十四件。
 * 本文件原为 13:11「基础六件补齐」spec（nebula-toolface@21bc2e74 四件 → 13:11
 * 补齐六件），随 23:34 裁定同点改写断言语义。
 *
 * - ① 读三件 ⊆ Nebula 机制集（fixedToolsFor 静态集 + buildAllowedToolSet
 *   交付面双层）∧ Bash/Write/Edit ∉ Nebula 集 + TaskList ∈ Nebula 集 +
 *   恰十四件计数——本文件即变异验红锚点：机制集加回写手或 NodeList 任一件
 *   （或摘掉 TaskList、或计数漂移）即红。
 * - ② 六件基础 ⊆ general 机制集（GeneralFixedTools = BaseTools +
 *   AskUserQuestion 恰七件；2026-09-08 作者修订恢复 AskUser，D6 批D1；
 *   2026-09-10 作者裁定摘 Pop——收归 Nebula 专属）
 *   ——回归钉死（general 已含六件是断言对象非改动对象；六件全体默认对
 *   general 侧不变）。
 * - ③（已删除，注明缘由）原「Write/Edit 真工具调用 × Nebula 会话写根」联合
 *   语义用例的授能前提（Nebula 携带 Write/Edit）已被 23:34 裁定消灭——
 *   Nebula 不再是 Write/Edit 授能身份，沙箱写根闸语义由 SandboxSpec 既有
 *   覆盖（写根放行/SANDBOX_DENIED 闸层断言），读三件缺省根语义由
 *   AgentConvergenceSpec §C.5 Glob/Grep 用例覆盖，本文件不再重复。
 */
class NebulaSixBaseToolsSpec extends FunSuite:

  private object CoreProbe extends AgentCore:
    def allowed(defn: AgentDef, isFlowNode: Boolean = false): Set[String] =
      buildAllowedToolSet(defn, isFlowNode = isFlowNode)

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  // ===== ① 读三件 ⊆ Nebula 机制集 ∧ 零写手（变异验红锚点）=====

  test("① 读三件 ⊆ Nebula 机制集（静态集+交付面双层）∧ Bash/Write/Edit 均不在 ∧ TaskList 在——加回任一写手或 NodeList、摘掉 TaskList 即红"):
    val six = AgentCore.BaseTools
    assert(six == Set("Read", "Write", "Edit", "Glob", "Grep", "Bash"),
      "前置：BaseTools 即基础六件（全体默认不变，Nebula 例外）")
    val fixed = AgentCore.fixedToolsFor(mkDef("Nebula"))
    Set("Read", "Glob", "Grep").foreach { t =>
      assert(fixed.contains(t), s"Nebula 机制集缺读三件之一: $t")
    }
    // 钉死断言（2026-09-05 23:34 作者裁定）：Nebula 机制集不含 Bash、不含
    // Write、不含 Edit——变异验红锚
    assert(!fixed.contains("Bash"), "Nebula 机制集不含 Bash（23:34 裁定：回归纯编排）")
    assert(!fixed.contains("Write"), "Nebula 机制集不含 Write（23:34 裁定）")
    assert(!fixed.contains("Edit"), "Nebula 机制集不含 Edit（23:34 裁定）")
    // 钉死断言（2026-09-06 00:48 作者裁定）：Nebula 机制集不含 NodeList——
    // 节点结果沿 out 边自动投递，主动查图与裁定职责重叠（dispatcher 面不受影响）
    assert(!fixed.contains("NodeList"), "Nebula 机制集不含 NodeList（00:48 裁定摘除）")
    // 钉死断言（2026-09-06 TaskList 批）：TaskList ∈ Nebula 机制集——
    // 快变状态出记忆的专属编排件（摘掉或改名即红）
    assert(fixed.contains("TaskList"), "Nebula 机制集含 TaskList（TaskList 批 +1）")
    // 交付面（buildAllowedToolSet，注册表过滤后）同样读三件在、写手零、TaskList 在
    val delivered = CoreProbe.allowed(mkDef("Nebula"))
    Set("Read", "Glob", "Grep").foreach { t =>
      assert(delivered.contains(t), s"Nebula 交付面缺读三件之一: $t")
    }
    Set("Bash", "Write", "Edit").foreach { t =>
      assert(!delivered.contains(t), s"Nebula 交付面不得含写手三件之一: $t")
    }
    assert(!delivered.contains("NodeList"), "Nebula 交付面零 NodeList（00:48 裁定摘除）")
    assert(delivered.contains("TaskList"), "Nebula 交付面含 TaskList（注册层已挂）")
    // +TaskList 后恰十四件（编排三件 + 任务编排 + 通信 + 读三件 + Card +
    // 用户面二件 + 平台二件 + MemoryEdit）
    assertEquals(fixed.size, 14, "Nebula 机制集恰十四件（2026-09-06 TaskList 批：+TaskList；00:48 裁定：摘 NodeList；23:34 裁定：回归纯编排）")

  // ===== ② 六件基础 ⊆ general 机制集（回归钉死）=====

  test("② 六件基础 ⊆ general 机制集（GeneralFixedTools=六件+AskUserQuestion 恰七件；2026-09-10 摘 Pop）"):
    val six = AgentCore.BaseTools
    assert(six.subsetOf(AgentCore.GeneralFixedTools),
      s"general 固定集必须含基础六件（缺: ${six.diff(AgentCore.GeneralFixedTools)}）")
    assertEquals(AgentCore.GeneralFixedTools.size, 7,
      "general 固定集恰七件（BaseTools 六件 + AskUserQuestion；2026-09-08 作者修订恢复 AskUser；2026-09-10 作者裁定摘 Pop——收归 Nebula 专属）")
    assert(AgentCore.GeneralFixedTools.contains("AskUserQuestion"),
      "general 固定集含 AskUserQuestion（2026-09-08 作者修订恢复——变异验红锚）")
    assert(!AgentCore.GeneralFixedTools.contains("Pop"),
      "general 固定集零 Pop（2026-09-10 作者裁定——变异验红锚：加回即红）")
    val delivered = CoreProbe.allowed(mkDef("general"), isFlowNode = true)
    six.foreach(t => assert(delivered.contains(t), s"general 交付面缺基础六件之一: $t"))

end NebulaSixBaseToolsSpec
