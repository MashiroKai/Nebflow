package nebflow.core.tools

import munit.FunSuite
import nebflow.agent.AgentCore

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

  /** R2 退役件（2026-09-12）· ⑧ 三老键 fail-closed（mailunify-full 批，2026-09-23 作者裁定）。
    *
    * 🔴 本名单的语义**已随 ⑧ 反转**：它不再是「必须配指引」的名单，而是
    * 「必须**零**指引」的名单——载体从「指引存在性」换成「fail-closed 政策本身」。
    * 名单面照旧保留（非空是本 test 的守卫，不是指引的载体）。 */
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

  test("R2 retired tools are fail-closed — zero migration guide, shaped like a name that never existed (⑧ 2026-09-23)"):
    // 🔴 非空守卫（防「空遍历恒真」）：本名单被清空即本 test 失去判据 ⇒ 必须硬红。
    assert(
      retiredR2Names.nonEmpty,
      "retiredR2Names must stay non-empty — emptying it would make this test vacuously true (静默失效)"
    )
    // 🔴 政策本体：整表摘空 ⇒ 一切旧名与一切从未存在的名走**同一条**未知工具路径。
    assert(
      AgentCore.RetiredToolGuides.isEmpty,
      s"RetiredToolGuides must be EMPTY (fail-closed, ⑧ 2026-09-23); got keys: ${AgentCore.RetiredToolGuides.keys.toList.sorted}"
    )
    assert(
      AgentCore.RetiredToolGuides.keys.isEmpty,
      "no retired name may carry a guide entry (the retired-guide practice is reversed)"
    )
    // 🔴 先例同向（作者 2026-09-21 `Delegate` 令）：同族改名退役工具亦零指引。
    assert(
      !AgentCore.RetiredToolGuides.contains("Delegate"),
      "Delegate carries no guide (author ruling 2026-09-21) — the table must not regrow one"
    )
    for g <- retiredR2Names do
      assert(!ToolRegistry.TOOL_MAP.contains(g), s"$g must be unregistered (R2 2026-09-12)")
      assert(
        !AgentCore.RetiredToolGuides.contains(g),
        s"$g must carry NO migration guide (fail-closed) — a surviving entry would re-open the retired-guide practice"
      )

  test("registry face is unchanged by the R2 retirement — Mail is registered, ghost names are not"):
    assert(ToolRegistry.TOOL_MAP.contains("Mail"), "Mail must stay registered (single message primitive)")
end DeletedToolGuardSpec
