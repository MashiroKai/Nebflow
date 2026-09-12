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
 */
class DeletedToolGuardSpec extends FunSuite:

  private val ghostNames = List("RemoveUnnecessary", "SaveWorkspaceItem")

  /** R2 退役件（2026-09-12）。 */
  private val retiredR2Names = List("Task", "NodeMessage")

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
    (ghostNames ++ retiredR2Names).foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g still registered")
    }
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

  test("registry face is unchanged by the R2 retirement — Mail is registered, ghost names are not"):
    assert(ToolRegistry.TOOL_MAP.contains("Mail"), "Mail must stay registered (single message primitive)")
end DeletedToolGuardSpec
