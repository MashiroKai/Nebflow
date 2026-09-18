package nebflow.agent

import munit.FunSuite

/**
 * Nebula 工具面恢复 spec（2026-09-18 18:18 作者令「恢复nebula的bash edit write
 * glob grep」：+Bash/Edit/Write/Glob/Grep ⇒ Nebula 机制集在飞恰十七件）。
 * 本令**取代** ① 2026-09-16 18:41 摘除令（−Glob −Grep，15→13）之 **root 面部分**、
 * ② 2026-09-05 23:34 作者裁定（「把你的 bash 和编辑工具收起来」）之 **root 面部分**
 * ——**仅 root 面**；分发器面（DispatcherFixedTools）与节点/基础面（BaseTools /
 * GeneralFixedTools / KernelFixedTools）逐字不变。
 * 🔴 依据**只有** 2026-09-18 18:18 令本身——0913「Glob/Grep 永久保留」旧裁定**不因
 * 本批复活**（该裁定已归档，不得作为待拍板项重提，也不得替它翻案）。
 * 【2026-09-06 00:48 作者裁定】NodeList 从 NebulaOrchestrationTools 摘除
 * （节点结果沿 out 边自动投递 Nebula，主动查图与裁定职责重叠；dispatcher
 * 自身面不受影响）——**本令未触碰该裁定，NodeList 缺席锚保留**。
 * 【2026-09-06 TaskList 批】+TaskList（作者 00:07 提议 + 00:11 首期无前端
 * 拍板：任务=快变状态出记忆、入 tasks.json 运行时数据层）——本集恰十四件（史实，
 * 时点即该批；沿革：−Glob −Grep ⇒ 13、−Delegate ⇒ 12、本令 +5 ⇒ 17 在飞）。
 * 本文件原为 13:11「基础六件补齐」spec（nebula-toolface@21bc2e74 四件 → 13:11
 * 补齐六件），随 23:34 裁定改写断言语义；本令再把缺席锚改写回在场锚。
 *
 * - ① 文件面六件（Read/Glob/Grep/Bash/Write/Edit）⊆ Nebula 机制集（fixedToolsFor
 *   静态集 + buildAllowedToolSet 交付面双层）∧ NodeList ∉ Nebula 集 ∧ TaskList ∈
 *   Nebula 集 + 件数计数（史实时点恰十四件，在飞 = 17）——本文件即变异验红锚点：
 *   从机制集再摘文件面任一件（或摘掉 TaskList、或计数漂移）即红。
 * - ② 六件基础 ⊆ general 机制集（GeneralFixedTools = BaseTools +
 *   AskUserQuestion 恰七件；2026-09-08 作者修订恢复 AskUser，D6 批D1；
 *   2026-09-10 作者裁定摘 Pop——收归 Nebula 专属）
 *   ——回归钉死（general 已含六件是断言对象非改动对象；六件全体默认对
 *   general 侧不变）。
 * - ③（已删除，注明缘由）原「Write/Edit 真工具调用 × Nebula 会话写根」联合
 *   语义用例在 2026-09-05 23:34 裁定时随授能前提一并删除；本令恢复了前提
 *   （Nebula 携带 Write/Edit）但**未随本令复建**该用例体——沙箱写根闸语义由
 *   SandboxSpec 既有覆盖（写根放行/SANDBOX_DENIED 闸层断言），读面缺省根语义由
 *   AgentConvergenceSpec 用例覆盖，本文件不重复。
 */
class NebulaSixBaseToolsSpec extends FunSuite:

  private object CoreProbe extends AgentCore:
    def allowed(defn: AgentDef, isFlowNode: Boolean = false): Set[String] =
      buildAllowedToolSet(defn, isFlowNode = isFlowNode)

  private def mkDef(name: String, tools: List[String] = Nil): AgentDef =
    AgentDef(name = name, description = "", tools = tools)

  // ===== ① 文件面六件 ⊆ Nebula 机制集 ∧ NodeList ∅ ∧ TaskList ∈（变异验红锚点）=====

  test("① 文件面六件 ⊆ Nebula 机制集（静态集+交付面双层，2026-09-18 18:18 令恢复）∧ NodeList 不在 ∧ TaskList 在——再摘文件面任一件或摘掉 TaskList、计数漂移即红"):
    val six = AgentCore.BaseTools
    assert(six == Set("Read", "Write", "Edit", "Glob", "Grep", "Bash"),
      "前置：BaseTools 即基础六件（全体默认不变）——2026-09-18 令只动 root 面，本行即反向钉")
    val fixed = AgentCore.fixedToolsFor(mkDef("Nebula"))
    // 钉死断言（2026-09-18 18:18 作者令「恢复nebula的bash edit write glob grep」）：
    // root 面**在场**恢复 Bash/Edit/Write/Glob/Grep + 既有 Read ⇒ 文件面六件——取代
    // 2026-09-16 18:41 摘除令与 2026-09-05 23:34 裁定之 **root 面部分**（**仅 root
    // 面**；分发器/节点/基础面不变，反向钉见 ② 与 AgentConvergenceSpec/
    // Phase2dToolRefactorSpec 的 dispatcher/general 断言）。变异验红锚：再摘任一件即红。
    Set("Read", "Glob", "Grep", "Bash", "Write", "Edit").foreach { t =>
      assert(fixed.contains(t), s"Nebula 机制集缺文件面件（2026-09-18 18:18 令）: $t")
    }
    // 钉死断言（2026-09-06 00:48 作者裁定，**本令未触碰**）：Nebula 机制集不含
    // NodeList——节点结果沿 out 边自动投递，主动查图与裁定职责重叠
    // （dispatcher 面不受影响）
    assert(!fixed.contains("NodeList"), "Nebula 机制集不含 NodeList（00:48 裁定摘除）")
    // 钉死断言（2026-09-06 TaskList 批）：TaskList ∈ Nebula 机制集——
    // 快变状态出记忆的专属编排件（摘掉或改名即红）
    assert(fixed.contains("TaskList"), "Nebula 机制集含 TaskList（TaskList 批 +1）")
    // 交付面（buildAllowedToolSet，注册表过滤后）同样文件面六件在、NodeList 零、TaskList 在
    val delivered = CoreProbe.allowed(mkDef("Nebula"))
    Set("Read", "Glob", "Grep", "Bash", "Write", "Edit").foreach { t =>
      assert(delivered.contains(t), s"Nebula 交付面缺文件面件（2026-09-18 18:18 令）: $t")
    }
    assert(!delivered.contains("NodeList"), "Nebula 交付面零 NodeList（00:48 裁定摘除）")
    assert(delivered.contains("TaskList"), "Nebula 交付面含 TaskList（注册层已挂）")
    // 件数以单点常量 AgentCore.NebulaOrchestrationToolsExpectedSize 为准：
    // 17（2026-09-18 18:18 令 +Bash/Edit/Write/Glob/Grep）；
    // 沿革（史实）：16 经 #145 附件腿批 −TransferFile 退役 ⇒ 15，
    // 再经 09-16 18:41 令 −2 ⇒ 13，再经 Delegate 退役批 −1 ⇒ 12。
    // 旧「终态 = 15，已定」与「终态 = 13」口径均已被取代 ⇒ 归档。
    // ⑩-9 的「终态待定」悬置口径已被 2026-09-14 拍板取代——归档，不得重提。
    assertEquals(fixed.size, AgentCore.NebulaOrchestrationToolsExpectedSize,
      "Nebula 机制集件数 == 单点常量（不得各处写裸数字；在飞 17 = 2026-09-18 18:18 令后实测值）")
    // 件数第二锚（防「常量漂移而集合未动」类假绿）——本行**刻意用字面量**（常量引用会
    // 让「常量与集合一起漂移」测不出来，与原 12 行同款结构、非以裸数字替代常量）
    assertEquals(AgentCore.NebulaOrchestrationTools.size, 17,
      "NebulaOrchestrationTools 实测恰 17 件（2026-09-18 18:18 令 +5；变异验红锚：再摘任一件即红）")

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
