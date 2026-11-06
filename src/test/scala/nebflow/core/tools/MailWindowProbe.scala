package nebflow.core.tools

/**
 * 测试探针（seal 批，2026-11-03）：把 `MailTool.dispatcherMailWindowMs` 的
 * `private[tools]` 读面暴露给**包外** spec（`nebflow.core.boot.ProcessSealSpec`
 * 要判「封存 ⇒ 窗长 0」）。与 `MailToolTestAccess` 同一先例：只在测试树里，
 * 生产面不新增任何 public 入口。
 */
object MailWindowProbe:

  def windowMs: Long = MailTool.dispatcherMailWindowMs

end MailWindowProbe
