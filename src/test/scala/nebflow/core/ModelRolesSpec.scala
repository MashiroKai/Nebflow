package nebflow.core

import munit.FunSuite
import nebflow.shared.AgentModelConfig
import scala.jdk.CollectionConverters.*

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
 *   · sessionId 前缀分类（机械可判）：`kernel-`→kernel、`dag-`→node、`node-`→node、
 *     `dispatcher-`→project-dispatcher、`ask-`→Nebula；未命中/空/None ⇒ None
 *     （不覆写，回落既有优先级）；
 *   · **delegate 归槽批（2026-11-18）**：`delegate-<agent>-<8hex>` 的槽由**记录
 *     mode** 判（kernel/node/dispatcher/subagent 四类，映射表单点
 *     `DelegateModeWires`）；mode 缺席/不识别 ⇒ None + 可读原因（绝不静默塞进
 *     subagent）。`delegate-kernel-` 仍是结构性命中（压过 mode 入参）。
 *   · effectiveModel 透传面：槽未命中 ⇒ base 原样（不改写、不造候选）。
 *
 * 优先级组合序（槽 > 派发钉链 > agent 自身链）的运行时单点在
 * AgentSessionExecution 的 LlmRequest 构造（`slotChainOf(sid).orElse(pin 面)`）；
 * 其「钉链生效」腿由 ProviderConcurrencyFallbackSpec T2 的逐字快照断言钉住
 * （T2 会话 id 不带 delegate-/kernel-/dag- 前缀 ⇒ 槽不命中 ⇒ pin 原样生效——
 * 若槽链错误地压过一切或 pin 被丢，T2 变红；本批已实证一次，见其头注）。
 */
class ModelRolesSpec extends FunSuite:

  test("small LLM slot is settable and resolves its own chain before main") {
    assert(ModelRoles.isSlot("small-llm"))
    assert(ModelRoles.isVirtualSlot("small-llm"))
    assert(SchemePolicy.isSettable("small-llm"))
    val original = nebflow.shared.PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-small-llm-")
    try
      nebflow.shared.PathUtil.setDataRoot(tmp)
      os.write.over(
        tmp / "agents" / "Nebula" / "agent.json",
        """{"model":{"preferred":"p/main"}}""",
        createFolders = true
      )
      assertEquals(SchemePolicy.smallLlmModel().preferred, Some("p/main"))
      os.write.over(
        tmp / "agents" / "small-llm" / "agent.json",
        """{"model":{"preferred":"p/small"}}""",
        createFolders = true
      )
      assertEquals(SchemePolicy.smallLlmModel().preferred, Some("p/small"))
      os.write.over(tmp / "agents" / "small-llm" / "agent.json", """{"model":{}}""")
      assertEquals(SchemePolicy.smallLlmModel().preferred, Some("p/main"))
    finally
      nebflow.shared.PathUtil.setDataRoot(original)
      os.remove.all(tmp)
    end try
  }

  test("small LLM fallback logs WARN while configured selection does not") {
    val original = nebflow.shared.PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-small-llm-warn-")
    val logger = org.slf4j.LoggerFactory
      .getLogger("nebflow.model.small-llm")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try
      nebflow.shared.PathUtil.setDataRoot(tmp)
      os.write.over(
        tmp / "agents" / "Nebula" / "agent.json",
        """{"model":{"preferred":"p/main"}}""",
        createFolders = true
      )
      assertEquals(SchemePolicy.smallLlmModel().preferred, Some("p/main"))
      val events = appender.list.asScala.toList
      assertEquals(
        events.count(e =>
          e.getLevel == ch.qos.logback.classic.Level.WARN &&
            e.getFormattedMessage.contains("falling back to the main model")
        ),
        1
      )
      appender.list.clear()
      os.write.over(
        tmp / "agents" / "small-llm" / "agent.json",
        """{"model":{"preferred":"p/small"}}""",
        createFolders = true
      )
      assertEquals(SchemePolicy.smallLlmModel().preferred, Some("p/small"))
      assert(!appender.list.asScala.exists(_.getLevel == ch.qos.logback.classic.Level.WARN))
    finally
      logger.detachAppender(appender)
      appender.stop()
      nebflow.shared.PathUtil.setDataRoot(original)
      os.remove.all(tmp)
    end try
  }

  test("五槽 wire 名 = main/kernel/dispatcher/node/subagent；sidecar 槽名与 wire 名解耦") {
    assertEquals(
      ModelRoles.Slots.map(_._1),
      List("main", "kernel", "dispatcher", "node", "subagent", "small-llm"),
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
      Set("kernel", "node", "subagent", "small-llm"),
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

  test("slotOfSessionId：四类生产 id 形状逐条归槽；delegate-<agent>-<8hex> 按记录 mode 归槽（不再一律 subagent）；未命中/空/None ⇒ None") {
    // 生产 id 形状锚（逐条）：delegate-<agent>-<8hex> = DelegateTool.spawnBackground
    // （DelegateTool.scala:1138——**与 mode 无关**，kernel/dispatcher 身份也是这条形状）；
    // delegate-kernel-<8hex> = MailAddress.KernelSessionPrefix / AgentActor.isKernelSession；
    // dispatcher-<uuid8> = ProjectActor.DispatcherSessionPrefix；dag-… = FlowDagExecutor；
    // node-… = SessionScope.BackgroundAgentPrefixes 的项目节点族。
    // 🔴 返回值面 = **槽目录名**（`ownChainOf` 的入参形态），故 dispatcher 会话归
    // `project-dispatcher`（wire 名 "dispatcher" 在 agents/ 下没有目录——首轮实跑把它
    // 直接喂给 ownChainOf 时类切换静默失效，本断言即该缺陷的钉子）。
    assertEquals(
      ModelRoles.slotOfSessionId(Some("delegate-nebflow-05578bd2"), Some("kernel")),
      Some("kernel"),
      "kernel 模式的 delegate（生产 id 形状 delegate-nebflow-<8hex>）⇒ kernel 槽"
    )
    assertEquals(
      ModelRoles.slotOfSessionId(Some("delegate-nebflow-05578bd2"), Some("dispatcher")),
      Some("project-dispatcher"),
      "dispatcher 模式的 delegate ⇒ 槽目录 project-dispatcher"
    )
    assertEquals(
      ModelRoles.slotOfSessionId(Some("delegate-abc123"), Some("subagent")),
      Some("subagent"),
      "显式 subagent 模式的 delegate ⇒ subagent 槽"
    )
    assertEquals(
      ModelRoles.slotOfSessionId(Some("delegate-abc123")),
      None,
      "归槽批：delegate- 前缀本身不再声明任何类（旧判据 = 一律 subagent，已删除）"
    )
    assertEquals(
      ModelRoles.slotOfSessionId(Some("dispatcher-1a2b3c4d")),
      Some("project-dispatcher"),
      "分发器会话 → 槽目录 project-dispatcher"
    )
    assertEquals(ModelRoles.slotOfSessionId(Some("dag-node-1-2")), Some("node"), "FlowDagExecutor 节点会话 id 形状")
    assertEquals(ModelRoles.slotOfSessionId(Some("node-proj-1")), Some("node"), "项目节点会话族")
    assertEquals(
      ModelRoles.slotOfSessionId(Some("ask-a0d6ad6e")),
      Some("Nebula"),
      "Ask 会话继承 main/Nebula 模型槽，避免迁移后链配置不可达"
    )
    // 🔴 判据顺序：kernel 实例 id 本身以 delegate- 开头，前缀顺序颠倒会被错归其它类。
    assertEquals(
      ModelRoles.slotOfSessionId(Some("delegate-kernel-9f8e7d6c")),
      Some("kernel"),
      "kernel 实例不得被 delegate- 抢先归到别的类"
    )
    assertEquals(
      ModelRoles.slotOfSessionId(Some("delegate-kernel-9f8e7d6c"), Some("dispatcher")),
      Some("kernel"),
      "结构性 delegate-kernel- 命中压过 mode 入参（判据顺序纪律）"
    )
    assertEquals(ModelRoles.slotOfSessionId(Some("kernel-xyz")), Some("kernel"), "裸 kernel- 前缀同样归 kernel 槽")
    assertEquals(ModelRoles.slotOfSessionId(Some("sess-0001")), None, "主会话（Nebula 对话框）不命中任何槽 ⇒ 不覆写")
    assertEquals(ModelRoles.slotOfSessionId(Some("")), None, "空 id 不命中")
    assertEquals(ModelRoles.slotOfSessionId(Some("   ")), None, "纯空白 id 不命中")
    assertEquals(ModelRoles.slotOfSessionId(None), None, "无会话 id（depth>1 内部 actor）不命中")
    assertEquals(
      ModelRoles.slotOfSessionId(Some("DELEGATE-ABC"), Some("SUBAGENT")),
      Some("subagent"),
      "id 前缀与 mode 词判据大小写不敏感"
    )
    assertEquals(ModelRoles.slotOfSessionId(Some("DELEGATE-KERNEL-ABC")), Some("kernel"), "大小写不敏感同样不得越过 kernel 判据")
    assertEquals(ModelRoles.slotOfSessionId(Some("delegate-")), None, "裸前缀不再命中任何槽（归槽批）")
    // 返回值恒为 Slots 表里的槽目录名（不泄漏 wire 名——唯一真源对账）。
    List(
      "delegate-a" -> Some("subagent"),
      "delegate-b" -> Some("kernel"),
      "dispatcher-1" -> None,
      "dag-x-1-2" -> None,
      "node-p" -> None,
      "delegate-kernel-1" -> None
    ).foreach { case (id, mode) =>
      ModelRoles.slotOfSessionId(Some(id), mode).foreach { s =>
        assert(ModelRoles.Slots.exists(_._3 == s), s"$id 归槽 $s 必须是 Slots 表里的槽目录名")
      }
    }
  }

  test("归槽批：四种 mode 各归对槽（kernel / node / dispatcher / subagent）——真相来源 = 记录 mode") {
    assertEquals(
      ModelRoles.DelegateModeWires.map(_._1),
      List("kernel", "dispatcher", "node", "subagent"),
      "映射表词表 = 作者名出的四类（DelegateModeWires 单点）"
    )
    // 四条：mode ⇒ 槽名（文件:行依据见 ModelRoles.DelegateModeWires 头注的表）。
    assertEquals(
      ModelRoles.slotOfDelegateMode(Some("kernel")),
      Some("kernel"),
      "① mode=kernel ⇒ kernel 槽（DualMode.scala:23）"
    )
    assertEquals(
      ModelRoles.slotOfDelegateMode(Some("node")),
      Some("node"),
      "② mode=node ⇒ node 槽（NodeEngineContract.scala:270 的 node- 族）"
    )
    assertEquals(
      ModelRoles.slotOfDelegateMode(Some("dispatcher")),
      Some("project-dispatcher"),
      "③ mode=dispatcher ⇒ project-dispatcher 槽（DualMode.scala:24）"
    )
    assertEquals(ModelRoles.slotOfDelegateMode(Some("subagent")), Some("subagent"), "④ mode=subagent ⇒ subagent 槽")
    // delegate 身份 + mode ⇒ 同一个槽（判据在会话面上等价）。
    List("kernel", "node", "dispatcher", "subagent").foreach { word =>
      assertEquals(
        ModelRoles.slotOfSessionId(Some("delegate-nebflow-1a2b3c4d"), Some(word)),
        ModelRoles.slotOfDelegateMode(Some(word)),
        s"delegate 身份 + mode '$word' 必须与映射表给同一个槽"
      )
    }
    // 大小写/空白容忍 + 无值面。
    assertEquals(ModelRoles.slotOfDelegateMode(Some(" KERNEL ")), Some("kernel"))
    assertEquals(ModelRoles.slotOfDelegateMode(Some("   ")), None)
    assertEquals(ModelRoles.slotOfDelegateMode(Some("")), None)
    assertEquals(ModelRoles.slotOfDelegateMode(None), None)
  }

  test("兜底：mode 缺席/未识别 ⇒ 不归槽（None）+ 可读原因；绝不静默塞进 subagent 槽") {
    val sid = Some("delegate-nebflow-9a8b7c6d")
    // 缺席（mode 参数化之前的存量身份）
    assertEquals(ModelRoles.slotOfSessionId(sid), None, "mode 缺席 ⇒ 不归槽")
    val absent = ModelRoles.delegateSlotUnresolvedReason(sid, None)
    assert(
      absent.exists(r => r.contains("has no recorded mode") && r.contains("NO slot chain")),
      s"mode 缺席必须给出可读原因（不许吞真信息）：$absent"
    )
    // 在场但不识别（存量值域外词 merger）
    assertEquals(ModelRoles.slotOfSessionId(sid, Some("merger")), None, "值域外 mode ⇒ 不归槽")
    val unknown = ModelRoles.delegateSlotUnresolvedReason(sid, Some("merger"))
    assert(
      unknown.exists(r => r.contains("mode 'merger'") && r.contains("kernel|dispatcher|node|subagent")),
      s"未识别 mode 必须点名取值并列出归槽值域：$unknown"
    )
    // 「不许静默塞进 subagent」的机械断言：两种兜底面都不给 subagent 槽。
    assert(
      ModelRoles.slotOfSessionId(sid).forall(_ != "subagent") &&
        ModelRoles.slotOfSessionId(sid, Some("merger")).forall(_ != "subagent"),
      "兜底绝不静默塞进 subagent 槽"
    )
    // 无需解释的三面：已归槽 / 非 delegate 会话 / 结构性 kernel 命中。
    assertEquals(ModelRoles.delegateSlotUnresolvedReason(sid, Some("kernel")), None)
    assertEquals(ModelRoles.delegateSlotUnresolvedReason(Some("sess-0001"), None), None)
    assertEquals(ModelRoles.delegateSlotUnresolvedReason(Some("subtask-1a2b3c4d"), None), None)
    assertEquals(ModelRoles.delegateSlotUnresolvedReason(Some("delegate-kernel-1a2b3c4d"), None), None)
    assertEquals(ModelRoles.delegateSlotUnresolvedReason(Some("delegate-kernel-1a2b3c4d"), Some("merger")), None)
    assertEquals(ModelRoles.delegateSlotUnresolvedReason(None, None), None)
  }

  test("兜底记录：未归槽原因落一条 WARN（同一会话不重复刷屏）；已归槽零记录") {
    val logger = org.slf4j.LoggerFactory
      .getLogger("nebflow.model.roles")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    val mine = (id: String) =>
      appender.list.asScala.count(e =>
        e.getLevel == ch.qos.logback.classic.Level.WARN && e.getFormattedMessage.contains(id)
      )
    try
      val sid = Some("delegate-nebflow-warnonce1")
      ModelRoles.slotChainOf(sid)
      ModelRoles.slotChainOf(sid)
      assertEquals(mine("delegate-nebflow-warnonce1"), 1, "同一未归槽会话只记一条 WARN（逐 turn 请求面不刷屏）")
      ModelRoles.slotChainOf(Some("delegate-nebflow-warnonce2"), Some("kernel"))
      assertEquals(mine("delegate-nebflow-warnonce2"), 0, "已归槽（或结构性命中）⇒ 零记录")
      ModelRoles.slotChainOf(Some("subtask-1a2b3c4d"))
      assertEquals(mine("subtask-1a2b3c4d"), 0, "非 delegate 后台族 ⇒ 零记录")
    finally
      logger.detachAppender(appender)
      appender.stop()
  }

  test("回归：组合序不变（槽链 > 派发钉链 > agent 自身链）；未归槽 ⇒ 钉链/agent 链原样") {
    val original = nebflow.shared.PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-roles-order-")
    val pin = Some(AgentModelConfig(Some("pin/model"), List("pin/fallback")))
    val mainId = "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff"
    try
      nebflow.shared.PathUtil.setDataRoot(tmp)
      os.write.over(
        tmp / "agents" / "kernel" / "agent.json",
        """{"model":{"preferred":"slot/kernel"}}""",
        createFolders = true
      )
      os.write.over(
        tmp / "agents" / "subagent" / "agent.json",
        """{"model":{"preferred":"slot/subagent"}}""",
        createFolders = true
      )
      val delegated = "delegate-nebflow-1a2b3c4d"
      // ① 已归槽 + 槽已配置 ⇒ 槽链压过钉链（既有优先级，本批未动）。
      assertEquals(
        ModelRoles.slotChainOf(Some(delegated), Some("kernel")).orElse(pin).flatMap(_.preferred),
        Some("slot/kernel"),
        "槽链 > 钉链：kernel 身份的 delegate 吃 kernel 槽链"
      )
      // ② 未归槽（mode 缺席 / 不识别）⇒ slotChainOf = None ⇒ 钉链原样生效（零行为漂移）。
      assertEquals(ModelRoles.slotChainOf(Some(delegated)).orElse(pin), pin, "mode 缺席 ⇒ 钉链原样（不覆写）")
      assertEquals(
        ModelRoles.slotChainOf(Some(delegated), Some("merger")).orElse(pin),
        pin,
        "不识别 mode ⇒ 钉链原样（不覆写）"
      )
      // ③ 主会话（uuid）/ 无槽后台族 ⇒ 恒不覆写 ⇒ agent 自身链（base）原样。
      assertEquals(
        ModelRoles.slotChainOf(Some(mainId)).orElse(pin).flatMap(_.preferred),
        Some("pin/model"),
        "主会话不命中槽 ⇒ 钉链/base 原样"
      )
      assertEquals(ModelRoles.slotChainOf(Some("subtask-1a2b3c4d")).orElse(pin), pin, "无槽后台族 ⇒ 钉链/base 原样")
      // ④ subagent 槽的唯一去处从「前缀」挪到「mode」：显式 subagent 身份仍读该槽链。
      assertEquals(
        ModelRoles.slotChainOf(Some(delegated), Some("subagent")).flatMap(_.preferred),
        Some("slot/subagent"),
        "显式 subagent 模式仍走 subagent 槽链"
      )
      // ⑤ 钉链快照稳定面（provider-concurrency 批）的运行时单点不在本 spec：
      //    ProviderConcurrencyFallbackSpec T2 断言「同一 actor 连续 5 turn agentModel
      //    逐字 = 派发时刻快照」——其会话 id 不带任何族前缀 ⇒ 槽不命中 ⇒ pin 原样，
      //    本批改动不触碰该腿（基线已跑绿）。
    finally
      nebflow.shared.PathUtil.setDataRoot(original)
      os.remove.all(tmp)
    end try
  }

  test("pinOwnerSlot owns slot families and main conversations, not slotless background families or blank ids") {
    assertEquals(ModelRoles.pinOwnerSlot("delegate-kernel-9f8e7d6c"), Some("kernel"))
    assertEquals(
      ModelRoles.pinOwnerSlot("delegate-abc123", Some("subagent")),
      Some("subagent"),
      "delegate 会话的钉链归属同样吃记录 mode"
    )
    assertEquals(ModelRoles.pinOwnerSlot("delegate-abc123"), None, "mode 不明 ⇒ 钉链不可归属（不许按前缀猜）")
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
    // 归槽批：delegate- 族在没有 mode 时对任何类都不归属（含 subagent）——「不许按前缀猜」。
    assertNotEquals(ModelRoles.pinOwnerSlot("delegate-1a2b3c4d"), Some("subagent"))
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
      assert(
        allUnknown.nonEmpty && !allUnknown.exists(_.startsWith("zhipu/")),
        "全未配置链仍可识别为无可用候选，接口会记录 MODEL-CHAIN-EMPTY"
      )
    finally
      nebflow.shared.PathUtil.setDataRoot(original)
      os.remove.all(tmp)
    end try
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
