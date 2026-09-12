package nebflow.core

import munit.FunSuite
import nebflow.shared.{Message, MessageRole}

/**
 * plugins-live 批（2026-09-12）：插件面变更提醒的纯函数判据。
 *
 * 被修缺陷：分发器会话的 Plugin Catalog 只进首条消息（spawn 期快照），会话存活
 * 期内零刷新路径 ⇒ 关闭插件后已开着的分发器仍能看到其能力描述。本批新增
 * plugin-surface delta 通道（`SystemReminders.pluginSurfaceReminder`）。
 *
 * 本 spec 钉死四件：
 *   ① 无变更 ⇒ None（零注入，反向可证伪）
 *   ② 关闭 ⇒ 只出包名 + 显式作废段，**不得**再出该插件的能力描述
 *   ③ 重新批准 ⇒ 能力行回归（含描述）
 *   ④ 权威化文案存在（取代首条消息目录段），且当前权威目录来自现渲染文本
 */
class PluginSurfaceReminderSpec extends FunSuite:

  private val header =
    "# Plugin Catalog（可分配能力包，NodeEdit 的 plugins 参数按 name 引用；能力句 = 该插件让节点具备什么能力）"
  private val capA = "- cap-a: cap-a 能力描述句（关闭后不得再出现在提醒里） [skills: probe | mcp: -]"
  private val visual = "- visual-report: 视觉报告能力 [skills: report | mcp: -]"
  private val withCapA = s"$header\n$capA\n$visual"
  private val withoutCapA = s"$header\n$visual"

  private def body(change: PluginSurfaceChange): String =
    SystemReminders.pluginSurfaceReminder(Some(change)).map(_.content).getOrElse("")

  test("no change → no reminder (zero injection)") {
    assertEquals(SystemReminders.pluginSurfaceReminder(Some(PluginSurfaceChange(withCapA, withCapA))), None)
    assertEquals(SystemReminders.pluginSurfaceReminder(None), None)
    assertEquals(SystemReminders.pluginSurfaceReminder(Some(PluginSurfaceChange("", ""))), None)
  }

  test("revoke: reminder names the closed plugin but does NOT re-advertise its description") {
    val text = body(PluginSurfaceChange(withCapA, withoutCapA))
    assert(text.contains(SystemReminders.PluginSurfaceMarker), text)
    assert(text.contains("已关闭"), text)
    assert(text.contains("- cap-a"), text)
    // 能力描述作废：关闭插件的能力描述句绝不出现
    assert(!text.contains("cap-a 能力描述句"), text)
    // 当前权威目录 = 现渲染文本（不含 cap-a）
    assert(text.contains("- visual-report: 视觉报告能力 [skills: report | mcp: -]"), text)
    assert(!text.contains("- cap-a: cap-a 能力描述句"), text)
  }

  test("re-approve: capability line comes back with its description") {
    val text = body(PluginSurfaceChange(withoutCapA, withCapA))
    assert(text.contains("已批准 / 重新可用"), text)
    assert(text.contains(capA), text)
    assert(!text.contains("- - "), text) // 列表前缀单份（渲染缺陷回归哨兵）
    assert(!text.contains("已关闭 / 不再可用"), text)
  }

  test("authoritative clause supersedes the first message's catalog section") {
    val text = body(PluginSurfaceChange(withCapA, withoutCapA))
    assert(text.contains("权威"), text)
    assert(text.contains("首条消息"), text)
    assert(text.contains("作废"), text)
  }

  test("all plugins closed → explicit empty placeholder, never a silent blank section") {
    val text = body(PluginSurfaceChange(withCapA, ""))
    assert(text.contains("当前无可分配插件"), text)
    assert(!text.contains("cap-a 能力描述句"), text)
  }

  test("description-only change still refreshes the authoritative catalog") {
    val text = body(PluginSurfaceChange(withCapA, s"$header\n$capA\n$visual (v2)"))
    assert(text.contains("(v2)"), text)
    assert(!text.contains("已关闭 / 不再可用"), text) // 无包级 +/-
    assert(!text.contains("已批准 / 重新可用"), text)
  }

  test("persisted-message marker + prune keeps only the newest reminder") {
    val mk = (c: PluginSurfaceChange) =>
      Message(MessageRole.User, Left(SystemReminders.pluginSurfaceReminder(Some(c)).get.render))
    val normal = Message(MessageRole.User, Left("hello"))
    val r1 = mk(PluginSurfaceChange(withCapA, withoutCapA))
    val r2 = mk(PluginSurfaceChange(withoutCapA, withCapA))
    assert(SystemReminders.isPluginSurfaceReminderMessage(r1))
    assert(!SystemReminders.isPluginSurfaceReminderMessage(normal))
    val pruned = SystemReminders.prunePluginSurfaceReminders(List(normal, r1, r2))
    assertEquals(pruned, List(normal, r2))
  }
end PluginSurfaceReminderSpec
