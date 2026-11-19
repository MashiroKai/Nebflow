package nebflow.core.tools

import munit.FunSuite

/**
 * Guards the tool deletions — no ghost references may resurface in surviving
 * tool descriptions or the registry.
 *
 * 两组删除件：
 *  - 2026-08-15 批（commit 91117499）：RemoveUnnecessary / SaveWorkspaceItem ——
 *    名字不得出现在任何存活工具描述里（substring 扫描有效：这些名字无合法用法）。
 *  - R2「一个 Mail 统一」批（2026-09-12）：Task / NodeMessage —— 注册面删净、
 *    调用面改走迁移指引（AgentCore.RetiredToolGuides，C-1）。**不做描述 substring
 *    扫描**：这两个名字在合法文案中照常出现（MailTool 描述显式声明 "the former
 *    `Task` and `NodeMessage` tools are retired"；且 `Task` 作为子串天然命中
 *    SubTask / TaskBoard / TaskList 等存活工具），substring 判据在此恒假。
 *
 *  🔴 The taskunify merge batch (2026-09-24) **rewrites this file's R2 face**: `Task` has come
 *  back as the unified ledger's only write face (replacing `TaskList` + `TaskBoard`), so it is
 *  **moved out** of the R2 retired-name set; the R2 retired-name set is now = `NodeMessage`
 *  (its migration guide must stay on record). The newly added taskunify retired-name set
 *  = `TaskList` / `TaskBoard` (likewise forbidden to become a silent no-op).
 */
class DeletedToolGuardSpec extends FunSuite:

  private val ghostNames = List("RemoveUnnecessary", "SaveWorkspaceItem")

  /** R2 retired entries (2026-09-12) -- after the taskunify batch `Task` has come back, hence moved out of this set. */
  private val retiredR2Names = List("NodeMessage")

  /** taskunify merge-batch retired entries (2026-09-24): the two were merged into `Task` / `TaskInfo`. */
  private val retiredTaskunifyNames = List("TaskList", "TaskBoard")

  /**
   * N1 复名批（v2 §3.3 镜像义务，2026-10-06）rename-retired 集：名字退役产生的新
   * 墓碑——`Workflow` 名已随复名让位（注册面删除、guide 在场），打旧名的调用落迁移
   * 指引。与 R2 集的差异 = 断言点：guide 指向**当前唯一的现役入口**。
   * 🔴 tool-face-agentflow-retire 批（2026-11-05，作者裁定二）：中转站 `AgentFlow`
   * 工具整体退役 ⇒ 本集 guide 的指路目标从 AgentFlow 改为 `ChainWrite`，且
   * `AgentFlow` 自己成为本表的新墓碑条目（反向先例，见下方用例）。
   */
  private val renameRetiredNames = List("Workflow")

  /**
   * N2 project-tool 批（2026-10-06，⑨A 硬替换）rename-retired 集：`ProjectCreate`
   * 名让位 `Project`（create 语义原样 + 新增 action="unmount" 硬卸载腿；旧名注册面
   * 删除、guide 指路 Project——N1 Workflow 先例同型，guard 表登记即本块断言）。
   */
  private val renameRetiredProjectNames = List("ProjectCreate")

  /**
   * T2 report-channel 退役集（2026-11-11）：三件结构化申报工具**同通道退役**——
   * node_report（节点）/ delegate_report（delegate）/ merger_report（merger，更早
   * 先例）。终态语义 = **末条 assistant 文本本体**（文本锚定协议：BLOCKED /
   * VERDICT 文法 + delegate 交付 = 正文），不存在可指路的「后继工具」。
   * 🔴 **有意零墓碑条目**：RetiredToolGuides 不登记这三名——指路表的存在前提是
   * 「有现役替代入口」，本通道没有；旧会话调用落注册表通用未命中错误
   * （"No such tool available: X"，AgentSessionExecution fallback 单点），文案已
   * 自含「无此工具」。名字级防复活守卫 = `PromptFaceSplitSpec` 的反回流负断言
   * （delegate 面零 node_report / brief 行零申报调用形态）。
   */
  private val retiredReportChannelNames = List("node_report", "delegate_report", "merger_report")

  test("report-channel retired names (T2): unregistered, no tombstone entry, fallback is the generic miss error"):
    retiredReportChannelNames.foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g must be unregistered (the report channel is retired)")
      assert(
        !nebflow.agent.AgentCore.RetiredToolGuides.contains(g),
        s"$g must NOT carry a migration guide — there is no successor tool to point at (the terminal report is the final text itself)"
      )
    }

  test("no surviving tool description references a deleted tool") {
    val offenders = ToolRegistry.TOOL_MAP.toSeq.collect {
      case (name, tool) if ghostNames.exists(g => tool.description.contains(g)) => name
    }
    assertEquals(offenders, Nil, s"ghost references in: $offenders")
  }

  test("Bash description specifically is ghost-free") {
    ghostNames.foreach { g =>
      assert(!BashTool.description.contains(g), s"Bash description still references $g")
    }
  }

  test("registry no longer contains the deleted tools") {
    (ghostNames ++ retiredR2Names ++ retiredTaskunifyNames).foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g still registered")
    }
    // the taskunify batch's live face must already be mounted (retirement is a rename-merge, not a capability loss)
    assert(ToolRegistry.TOOL_MAP.contains("Task"), "Task must be registered (unified ledger write face)")
    assert(ToolRegistry.TOOL_MAP.contains("TaskInfo"), "TaskInfo must be registered (read-only attachment view)")
  }

  test("R2 retired tools leave an explicit migration guide (C-1)，禁静默 no-op"):
    retiredR2Names.foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g must be unregistered (R2 2026-09-12)")
      assert(
        nebflow.agent.AgentCore.RetiredToolGuides.contains(g),
        s"$g must carry a migration guide entry (退役可诊断错误，非兼容壳)"
      )
      val guide = nebflow.agent.AgentCore.RetiredToolGuides(g)
      assert(guide.trim.nonEmpty, s"$g guide must not be empty")
      assert(
        guide.contains("Mail"),
        s"$g guide must point at Mail (the single message primitive), got: ${guide.take(120)}"
      )
    }

  test("taskunify retired tools leave an explicit migration guide (C-1), no silent no-op"):
    // `TaskList` / `TaskBoard` are deliberately **not registered** (once registered, the
    // RetiredToolGuides would never fire, and it would create a second path to the write
    // face) => the table lookup misses => the migration guide takes effect.
    retiredTaskunifyNames.foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g must be unregistered (taskunify 2026-09-24)")
      assert(
        nebflow.agent.AgentCore.RetiredToolGuides.contains(g),
        s"$g must carry a migration guide entry (a diagnosable retirement error, not a compatibility shell)"
      )
      val guide = nebflow.agent.AgentCore.RetiredToolGuides(g)
      assert(guide.trim.nonEmpty, s"$g guide must not be empty")
      assert(
        guide.contains("Task"),
        s"$g guide must point at Task (the unified ledger), got: ${guide.take(120)}"
      )
    }
    // 名字复用教义（project-trigger 拆除批断言，AgentFlow/Project 先例同型）：
    // `Task` 已注册为台账工具 ⇒ 表内不得存在指向活名的死条目（旧 "Task" 墓碑的
    // 「Project triggering is now Mail」文案既不可达、又与拆除后的现行为相悖）。
    assert(ToolRegistry.TOOL_MAP.contains("Task"), "Task must be registered (the live ledger name)")
    assert(
      !nebflow.agent.AgentCore.RetiredToolGuides.contains("Task"),
      "no tombstone entry may exist for the live name \"Task\" (registered ⇒ unreachable dead text)"
    )

  test("rename-retired (N1): Workflow is unregistered, and its guide now points at ChainWrite (the live entry)"):
    renameRetiredNames.foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g must be unregistered (its DAG face is gone)")
      assert(
        nebflow.agent.AgentCore.RetiredToolGuides.contains(g),
        s"$g must carry a rename migration guide (a diagnosable error, not a compatibility shell)"
      )
      val guide = nebflow.agent.AgentCore.RetiredToolGuides(g)
      // tool-face-agentflow-retire 批（2026-11-05，作者裁定二）：中转站 AgentFlow 也退役
      // ⇒ 旧墓碑不得再指这个死名（那会把调用再送一次空跑），只认唯一现役入口。
      assert(
        guide.contains("ChainWrite"),
        s"$g guide must point at the live entry ChainWrite, got: ${guide.take(160)}"
      )
      assert(
        !guide.contains("Resubmit as AgentFlow"),
        "the guide must never send a caller back to the retired name (dead-end pointer)"
      )
    }
    // 名字生命周期教义：AgentFlow 现在**不可达**（工具整体退役）⇒ 必须在墓碑表里，
    // 且不许静默消失（B5-c 可诊断退役）；同名反向先例见 N1 复名批（Workflow→AgentFlow）。
    assert(!ToolRegistry.TOOL_MAP.contains("AgentFlow"), "AgentFlow must be unregistered (the tool is retired)")
    assert(
      nebflow.agent.AgentCore.RetiredToolGuides.contains("AgentFlow"),
      "AgentFlow must carry a retirement guide — an old session calling it gets a diagnosable error, not a silent no-op"
    )
    val afGuide = nebflow.agent.AgentCore.RetiredToolGuides("AgentFlow")
    assert(afGuide.contains("ChainWrite"), s"the guide must point at ChainWrite, got: ${afGuide.take(160)}")
    assert(
      afGuide.contains("MODE") || afGuide.contains("mode"),
      s"the guide must state that 'agentflow' is only a mode name, not a tool: ${afGuide.take(200)}"
    )

  test("no surviving tool description advertises the retired AgentFlow tool"):
    // 描述面广告 = 模型会照着调一个不存在的工具（本批最贵的缺陷形态）。
    val offenders = ToolRegistry.TOOL_MAP.toSeq.collect {
      case (name, tool) if tool.description.contains("AgentFlow") => name
    }
    assertEquals(offenders, Nil, s"surviving tool descriptions still advertise AgentFlow: $offenders")

  test("旧会话调用 AgentFlow 的实际回执文本（engine 单点拼接：Tool '<name>' is not available. <guide>）"):
    // 回执由 AgentSessionExecution 的注册表未命中分支拼成（`case None` —— 见该方法
    // 内 `val msg = AgentCore.RetiredToolGuides.get(call.name) match ...` 一处）：
    //   s"Tool '${call.name}' is not available. $guide"
    // 本用例按**同一模板**渲染并断言：文案必须点名「已退役」、指向 ChainWrite（唯一
    // 现役入口）并说清 agentflow 只是模式名——三件缺一件，模型只能瞎猜。
    val receipt = nebflow.agent.AgentCore.RetiredToolGuides.get("AgentFlow") match
      case Some(guide) => s"Tool 'AgentFlow' is not available. $guide"
      case None        => fail("AgentFlow must carry a retirement guide")
    println(s"[TOMBSTONE-RECEIPT] $receipt")
    assert(receipt.startsWith("Tool 'AgentFlow' is not available."), receipt)
    assert(receipt.contains("retired"), s"the receipt must say the tool is retired: $receipt")
    assert(receipt.contains("ChainWrite"), s"the receipt must point at the only live entry (ChainWrite): $receipt")
    assert(
      receipt.contains("MODE"),
      s"the receipt must separate the MODE name from the retired tool: $receipt"
    )

  test("registry face is unchanged by the R2 retirement — Mail is registered, ghost names are not"):
    assert(ToolRegistry.TOOL_MAP.contains("Mail"), "Mail must stay registered (single message primitive)")

  test("rename-retired (N2): ProjectCreate is unregistered, its guide is present and names Project"):
    renameRetiredProjectNames.foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g must be unregistered (renamed to Project, N2 2026-10-06)")
      assert(
        nebflow.agent.AgentCore.RetiredToolGuides.contains(g),
        s"$g must carry a rename migration guide (a diagnosable error, not a compatibility shell)"
      )
      val guide = nebflow.agent.AgentCore.RetiredToolGuides(g)
      assert(guide.contains("Project"), s"$g guide must point at the renamed Project, got: ${guide.take(140)}")
      assert(
        guide.contains("unmount"),
        s"$g guide must mention the unmount face (the panel's manual unmount moved into the tool), got: ${guide.take(140)}"
      )
    }
    // 名字复用教义：Project 已注册 ⇒ 表内不得存在指向活名的死条目（留条目 = 对该名
    // 永不可达的死文本，违表零膨胀纪律——N1 AgentFlow 先例同款断言）。
    assert(ToolRegistry.TOOL_MAP.contains("Project"), "Project must be registered (the renamed face)")
    assert(
      !nebflow.agent.AgentCore.RetiredToolGuides.contains("Project"),
      "no tombstone entry may exist for the live name \"Project\" (registered ⇒ unreachable dead text)"
    )
end DeletedToolGuardSpec
