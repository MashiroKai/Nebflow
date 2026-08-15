package nebflow.core.tools

import munit.FunSuite

/**
 * Guards the 2026-08-15 tool deletions (RemoveUnnecessary, SaveWorkspaceItem,
 * commit 91117499): no ghost references may resurface in surviving tool
 * descriptions or the registry.
 */
class DeletedToolGuardSpec extends FunSuite:

  private val ghostNames = List("RemoveUnnecessary", "SaveWorkspaceItem")

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
    ghostNames.foreach { g =>
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g still registered")
    }
  }
end DeletedToolGuardSpec
