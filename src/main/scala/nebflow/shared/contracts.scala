/* 严格DAG第②步:契约类型随协议面下移(行为保持重构,2026-09-26)。 */
package nebflow.shared

case class SystemReminder(
  category: String,
  content: String,
  /**
   * F-2 计数面（presdial 批 2026-09-19）：本条提醒所宣告的变化**身份** —— device
   * 通道 = 设备**成员集合**键（deviceId 面，见 [[SystemReminders.deviceMemberKey]]）。
   * `None`（缺省）= 无计数语义 ⇒ 行为与改前逐字相同。
   *
   * 🔴 **不进渲染**（[[render]] 只用 `content`）⇒ 模型可见字面零影响；只被
   * [[SystemReminders.logAndReturn]] 用来对「同一变化」跨会话去重（M1）与对
   * 文本轴抖动切零增量（M3）。
   */
  countKey: Option[String] = None
):
  def render: String = s"<system-reminder>\n$content\n</system-reminder>"
end SystemReminder

object SystemReminder:

  def renderAll(reminders: List[SystemReminder]): String =
    if reminders.isEmpty then ""
    else if reminders.length == 1 then reminders.head.render
    else
      val body = reminders.map(_.content).mkString("\n\n")
      s"<system-reminder>\n$body\n</system-reminder>"

/**
 * @param content         Content visible to the LLM.
 * @param isError         Whether the tool execution failed.
 * @param frontendContent Full content for frontend rendering (e.g. card HTML).
 *                        When present, the frontend receives this instead of `content`.
 * @param imageBlocks     Image content blocks extracted from the result (e.g. Read on an image file).
 *                        Injected alongside tool_result blocks in the user message so LLM
 *                        vision APIs can process them. None for text-only results.
 */
case class ToolExecResult(
  content: String,
  isError: Boolean = false,
  frontendContent: Option[String] = None,
  imageBlocks: Option[List[ContentBlock.Image]] = None
)
