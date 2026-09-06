package nebflow.agent

import munit.FunSuite

/**
 * Nebula 工具面写手裁撤 spec（2026-09-05 23:34 作者裁定「把你的 bash 和编辑
 * 工具收起来」：Nebula 回归纯编排——Bash/Write/Edit 从 NebulaOrchestrationTools
 * 移除；general/BaseTools 六件默认注入不变，Nebula 是唯一例外）。
 * 【2026-09-06 00:48 作者裁定增补】NodeList 从 NebulaOrchestrationTools 摘除
 * （节点结果沿 out 边自动投递 Nebula，主动查图与裁定职责重叠；dispatcher
 * 自身面不受影响）——本集恰十三件。
 * 本文件原为 13:11「基础六件补齐」spec（nebula-toolface@21bc2e74 四件 → 13:11
 * 补齐六件），随 23:34 裁定同点改写断言语义。
 *
 * - ① 读三件 ⊆ Nebula 机制集（fixedToolsFor 静态集 + buildAllowedToolSet
 *   交付面双层）∧ Bash/Write/Edit ∉ Nebula 集 + 恰十三件计数——本文件即
 *   变异验红锚点：机制集加回写手或 NodeList 任一件（或计数漂移）即红。
 * - ② 六件基础 ⊆ general 机制集（GeneralFixedTools = BaseTools + 用户面二件）
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

  test("① 读三件 ⊆ Nebula 机制集（静态集+交付面双层）∧ Bash/Write/Edit 均不在且恰十三件——加回任一写手或 NodeList 即红"):
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
    // 交付面（buildAllowedToolSet，注册表过滤后）同样读三件在、写手零
    val delivered = CoreProbe.allowed(mkDef("Nebula"))
    Set("Read", "Glob", "Grep").foreach { t =>
      assert(delivered.contains(t), s"Nebula 交付面缺读三件之一: $t")
    }
    Set("Bash", "Write", "Edit").foreach { t =>
      assert(!delivered.contains(t), s"Nebula 交付面不得含写手三件之一: $t")
    }
    assert(!delivered.contains("NodeList"), "Nebula 交付面零 NodeList（00:48 裁定摘除）")
    // 摘 NodeList 后恰十三件（编排三件 + 通信 + 读三件 + Card + 用户面二件 +
    // 平台二件 + MemoryEdit）
    assertEquals(fixed.size, 13, "Nebula 机制集恰十三件（2026-09-06 00:48 裁定：摘 NodeList；2026-09-05 23:34 裁定：回归纯编排）")

  // ===== ② 六件基础 ⊆ general 机制集（回归钉死）=====

  test("② 六件基础 ⊆ general 机制集（GeneralFixedTools=六件+用户面二件）"):
    val six = AgentCore.BaseTools
    assert(six.subsetOf(AgentCore.GeneralFixedTools),
      s"general 固定集必须含基础六件（缺: ${six.diff(AgentCore.GeneralFixedTools)}）")
    assertEquals(AgentCore.GeneralFixedTools.size, 8,
      "general 固定集恰八件（裁定 5：BaseTools 六件 + AskUserQuestion/Pop）")
    val delivered = CoreProbe.allowed(mkDef("general"), isFlowNode = true)
    six.foreach(t => assert(delivered.contains(t), s"general 交付面缺基础六件之一: $t"))

end NebulaSixBaseToolsSpec
