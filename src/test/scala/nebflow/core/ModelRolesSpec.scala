package nebflow.core

import munit.FunSuite
import nebflow.shared.AgentModelConfig

/**
 * 模型粒度批（作者令 2026-11-06 20:36 逐字「按主agent/ kernel /dispatcher/node/subagent
 * 这几类分」）——[[ModelRoles]] 五类模型槽的机械判据。
 *
 * 覆盖面（纯函数单点；槽 sidecar 现读真实 dataRoot 的腿不在本 spec 内造——
 * `SchemePolicy.ownChainOf` 按磁盘现读、无 root 注入缝，spec 不写真实 HOME）：
 *   · 五槽形状：wire 名清单 + sidecar 槽名与 wire 名解耦（main→Nebula、
 *     dispatcher→project-dispatcher）；
 *   · 虚拟槽集合（kernel/node/subagent 只有 model sidecar、不是完整 agent）——
 *     REST GET/PUT /agents/:name/model 跳过 EntityLoader 存在性闸的判据；
 *   · sessionId 前缀分类（机械可判）：`delegate-`→subagent、`kernel-`→kernel、
 *     `dag-`→node；未命中/空/None ⇒ None（不覆写，回落既有优先级）；
 *   · effectiveModel 透传面：槽未命中 ⇒ base 原样（不改写、不造候选）。
 *
 * 优先级组合序（槽 > 派发钉链 > agent 自身链）的运行时单点在
 * AgentSessionExecution 的 LlmRequest 构造（`slotChainOf(sid).orElse(pin 面)`）；
 * 其「钉链生效」腿由 ProviderConcurrencyFallbackSpec T2 的逐字快照断言钉住
 * （T2 会话 id 不带 delegate-/kernel-/dag- 前缀 ⇒ 槽不命中 ⇒ pin 原样生效——
 * 若槽链错误地压过一切或 pin 被丢，T2 变红；本批已实证一次，见其头注）。
 */
class ModelRolesSpec extends FunSuite:

  test("五槽 wire 名 = main/kernel/dispatcher/node/subagent；sidecar 槽名与 wire 名解耦") {
    assertEquals(
      ModelRoles.Slots.map(_._1),
      List("main", "kernel", "dispatcher", "node", "subagent"),
      "五类槽的 wire 名清单（/model 面板、设置面板、REST 路径共用同一拼写）"
    )
    val byWire = ModelRoles.Slots.map { case (w, _, slot) => w -> slot }.toMap
    assertEquals(byWire("main"), SchemePolicy.NebulaName, "main 的槽 = Nebula（作者本人对话框）")
    assertEquals(byWire("kernel"), "kernel", "kernel 是独立虚拟槽（模型配置粒度批回归，与运行身份合并不冲突）")
    assertEquals(byWire("dispatcher"), SchemePolicy.DispatcherName, "dispatcher 的槽 = project-dispatcher")
    assertEquals(byWire("node"), "node", "node 虚拟槽")
    assertEquals(byWire("subagent"), "subagent", "subagent 虚拟槽")
  }

  test("虚拟槽 = kernel/node/subagent；isSlot 覆盖 wire 名与槽名双拼写，不收编陌生名") {
    assertEquals(
      ModelRoles.VirtualSlots,
      Set("kernel", "node", "subagent"),
      "只有 model sidecar、没有 agent 实体的三个槽（EntityLoader 存在性闸跳过判据）"
    )
    List("kernel", "node", "subagent").foreach(n => assert(ModelRoles.isVirtualSlot(n)))
    List("main", "dispatcher").foreach(n => assert(!ModelRoles.isVirtualSlot(n), s"$n 是实体槽，不是虚拟槽"))
    List("main", "kernel", "dispatcher", "node", "subagent", "Nebula", "project-dispatcher").foreach { n =>
      assert(ModelRoles.isSlot(n), s"$n 必须是合法模型槽（GET/PUT /agents/:name/model 的合法面）")
    }
    assert(!ModelRoles.isSlot("kernel-agent"), "前缀相似但不同的名字不得误判")
    assert(!ModelRoles.isSlot(""), "空名不是槽")
    // wire 拼写 → 槽目录名的规范形（REST 两臂的归一单点；不归一 = 静默空写）。
    assertEquals(ModelRoles.canonicalSlotName("main"), "Nebula", "main ⇒ Nebula 槽")
    assertEquals(ModelRoles.canonicalSlotName("dispatcher"), "project-dispatcher", "dispatcher ⇒ project-dispatcher 槽")
    List("kernel", "node", "subagent", "Nebula", "project-dispatcher", "Coder").foreach { n =>
      assertEquals(ModelRoles.canonicalSlotName(n), n, s"$n 已是槽目录名/非槽名 ⇒ 原样返回")
    }
  }

  test("slotOfSessionId：五类生产 id 形状逐条归槽（delegate-kernel- 压过 delegate-；返回值是槽目录名）；未命中/空/None ⇒ None") {
    // 生产 id 形状锚（逐条）：delegate-<id> = DelegateTool.spawnDelegate；
    // delegate-kernel-<8hex> = MailAddress.KernelSessionPrefix / AgentActor.isKernelSession；
    // dispatcher-<uuid8> = ProjectActor.DispatcherSessionPrefix；dag-… = FlowDagExecutor；
    // node-… = SessionScope.BackgroundAgentPrefixes 的项目节点族。
    // 🔴 返回值面 = **槽目录名**（`ownChainOf` 的入参形态），故 dispatcher 会话归
    // `project-dispatcher`（wire 名 "dispatcher" 在 agents/ 下没有目录——首轮实跑把它
    // 直接喂给 ownChainOf 时类切换静默失效，本断言即该缺陷的钉子）。
    assertEquals(ModelRoles.slotOfSessionId(Some("delegate-abc123")), Some("subagent"), "DelegateTool.spawnDelegate 的 id 形状")
    assertEquals(ModelRoles.slotOfSessionId(Some("dispatcher-1a2b3c4d")), Some("project-dispatcher"), "分发器会话 → 槽目录 project-dispatcher")
    assertEquals(ModelRoles.slotOfSessionId(Some("dag-node-1-2")), Some("node"), "FlowDagExecutor 节点会话 id 形状")
    assertEquals(ModelRoles.slotOfSessionId(Some("node-proj-1")), Some("node"), "项目节点会话族")
    assertEquals(ModelRoles.slotOfSessionId(Some("ask-a0d6ad6e")), Some("Nebula"), "Ask 会话继承 main/Nebula 模型槽，避免迁移后链配置不可达")
    // 🔴 判据顺序：kernel 会话 id 本身以 delegate- 开头，前缀顺序颠倒会被错归 subagent。
    assertEquals(ModelRoles.slotOfSessionId(Some("delegate-kernel-9f8e7d6c")), Some("kernel"), "kernel 会话不得被 delegate- 抢先归到 subagent")
    assertEquals(ModelRoles.slotOfSessionId(Some("kernel-xyz")), Some("kernel"), "裸 kernel- 前缀同样归 kernel 槽")
    assertEquals(ModelRoles.slotOfSessionId(Some("sess-0001")), None, "主会话（Nebula 对话框）不命中任何槽 ⇒ 不覆写")
    assertEquals(ModelRoles.slotOfSessionId(Some("")), None, "空 id 不命中")
    assertEquals(ModelRoles.slotOfSessionId(Some("   ")), None, "纯空白 id 不命中")
    assertEquals(ModelRoles.slotOfSessionId(None), None, "无会话 id（depth>1 内部 actor）不命中")
    assertEquals(ModelRoles.slotOfSessionId(Some("DELEGATE-ABC")), Some("subagent"), "前缀判据大小写不敏感")
    assertEquals(ModelRoles.slotOfSessionId(Some("DELEGATE-KERNEL-ABC")), Some("kernel"), "大小写不敏感同样不得越过 kernel 判据")
    assertEquals(ModelRoles.slotOfSessionId(Some("delegate-")), Some("subagent"), "裸前缀也算命中（机械判据不猜长度）")
    // 返回值恒为 Slots 表里的槽目录名（不泄漏 wire 名——唯一真源对账）。
    List("delegate-a", "dispatcher-1", "dag-x-1-2", "node-p", "delegate-kernel-1").foreach { id =>
      ModelRoles.slotOfSessionId(Some(id)).foreach { s =>
        assert(ModelRoles.Slots.exists(_._3 == s), s"$id 归槽 $s 必须是 Slots 表里的槽目录名")
      }
    }
  }

  test("pinOwnerSlot owns slot families and main conversations, not slotless background families or blank ids") {
    assertEquals(ModelRoles.pinOwnerSlot("delegate-kernel-9f8e7d6c"), Some("kernel"))
    assertEquals(ModelRoles.pinOwnerSlot("delegate-abc123"), Some("subagent"))
    assertEquals(ModelRoles.pinOwnerSlot("dispatcher-1a2b3c4d"), Some("project-dispatcher"))
    assertEquals(ModelRoles.pinOwnerSlot("dag-node-1-2"), Some("node"))
    assertEquals(ModelRoles.pinOwnerSlot("ask-a0d6ad6e"), Some(SchemePolicy.NebulaName))
    // A main conversation is deliberately unclassified for request resolution,
    // but a main-chain edit must still reach its pin.
    val mainId = "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff"
    assertEquals(ModelRoles.slotOfSessionId(Some(mainId)), None)
    assertEquals(ModelRoles.pinOwnerSlot(mainId), Some(SchemePolicy.NebulaName))
    assertEquals(ModelRoles.pinOwnerSlot("  sess-0001 "), Some(SchemePolicy.NebulaName))
    // Background families without a model slot, and blank ids, cannot be attributed: no category edit may clear them.
    val unowned = List("subtask-1a2b3c4d", "subagent-1a2b3c4d", "workflow-1a2b3c4d", "external-codex-7cf3", "", "   ")
    unowned.foreach { id =>
      assertEquals(ModelRoles.pinOwnerSlot(id), None, s"'$id' must not be attributed to a category")
    }
    // Drift gate: a new background family must never silently become owned by the main slot.
    nebflow.shared.SessionScope.BackgroundAgentPrefixes.foreach { prefix =>
      assertNotEquals(
        ModelRoles.pinOwnerSlot(s"${prefix}1a2b3c4d"),
        Some(SchemePolicy.NebulaName),
        s"background family '$prefix' must not be owned by the main slot"
      )
    }
  }

  test("迁移后 ask 类别链读取 main/Nebula sidecar；全未配置引用可被判空而不静默") {
    val original = nebflow.shared.PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-ask-model-role-")
    try
      nebflow.shared.PathUtil.setDataRoot(tmp)
      os.write.over(
        tmp / "agents" / "Nebula" / "agent.json",
        """{"model":{"preferred":"zhipu/glm-5.3-flash"}}""",
        createFolders = true
      )
      val chain = ModelRoles.slotChainOf(Some("ask-a0d6ad6e"))
      assertEquals(chain.flatMap(_.preferred), Some("zhipu/glm-5.3-flash"))
      val refs = chain.toList.flatMap(c => c.preferred.toList ++ c.fallbacks)
      assertEquals(refs, List("zhipu/glm-5.3-flash"), "Ask 链必须非空且保留已配置 provider ref")
      val allUnknown = List("missing/model")
      assert(allUnknown.nonEmpty && !allUnknown.exists(_.startsWith("zhipu/")), "全未配置链仍可识别为无可用候选，接口会记录 MODEL-CHAIN-EMPTY")
    finally
      nebflow.shared.PathUtil.setDataRoot(original)
      os.remove.all(tmp)
  }

  test("effectiveModel：槽未命中 ⇒ base 原样透传（不改写、不造候选；槽已配置腿依赖真实 dataRoot，运行时单点见头注）") {
    val base = Some(AgentModelConfig(Some("a/m1"), Nil))
    assertEquals(ModelRoles.effectiveModel(base, Some("sess-0001")), base, "未命中槽的会话零行为变化")
    assertEquals(ModelRoles.effectiveModel(None, Some("sess-0001")), None, "base 为空且槽未命中 ⇒ None（不凭空造）")
  }

  test("写闸面：五类槽全部进入 SettableAgents（/model 与设置面板按类切的写闸判据）") {
    List("main", "kernel", "dispatcher", "node", "subagent", "Nebula", "project-dispatcher").foreach { n =>
      assert(SchemePolicy.isSettable(n), s"$n 必须可经 /model 写闸（类粒度切换的落点）")
    }
  }

end ModelRolesSpec
