package nebflow.core.tools

import nebflow.shared.ToolCall

/**
 * Untrusted agent-write boundary for the human-owned project instruction file.
 *
 * 判据 = **操作风险**（[[CommandRisk]]），不是「命令里出现某文件名」。目标维度
 * 只回答「这条调用**指向**受保护文件吗」，永远单独不触发；不透明命令路径必须
 * **风险类 ∧ 目标提及** 合取才拦。
 *
 * 2026-11-18 收窄（作者裁定「只有出现修改原语才拦」）：旧规则是
 * `命令文本含文件名 && 不匹配只读白名单` —— 白名单只认 `cat/grep/…` 那几种单命令，
 * `git log -- <file>`、`cat <file> | grep x`、`ls; git status -- <file>` 全被误伤，
 * 而真正要拦的只在「出现修改原语」时。`scripts/check-permission-gate.mjs` 静态钉住
 * 两条不变式：判据不得基于文件名字面量、只读白名单不得复活。
 */
object AgentInstructionWriteGate:
  private val AgentWriteTools = Set("Write", "Edit", "MultiEdit", "Bash")

  /**
   * Detect writes to the workspace-root instruction file. The tool arguments are used
   * only to identify the target; `isAgent` must be supplied by the trusted
   * engine call context, not read from user/model input.
   */
  def requiresConfirmation(call: ToolCall, isAgent: Boolean): Boolean =
    isAgent && AgentWriteTools.contains(call.name) &&
      (call.input("file_path").flatMap(_.asString).exists(isAgentsFile) ||
        call.input("command").flatMap(_.asString).exists(commandTargetsInstructionFile))

  /**
   * 目标维度（结构化参数）：**只认文件路径参数**，不认任何命令文本。
   * 这是「受保护目标」的唯一判定点，它不产生审批结论——审批结论在
   * [[requiresConfirmation]] 里由「目标 ∧ 风险」合取给出。
   */
  def isAgentsFile(raw: String): Boolean =
    if raw == null || raw.trim.isEmpty then false
    else
      val normalized = raw.replace('\\', '/').split('/').filter(_.nonEmpty).mkString("/")
      normalized.equalsIgnoreCase("AGENTS.md") || normalized.toLowerCase.endsWith("/agents.md")

  /**
   * 不透明命令维度：风险类（[[CommandRisk]]）∧ 目标提及。
   * 只要目标被提及但没有任何风险原语（纯只读引用），放行；风险原语打到别的文件，
   * 不归本闸管（那是安全档位/`ToolReversibility` 的面）。
   */
  private def commandTargetsInstructionFile(command: String): Boolean =
    CommandRisk.isRisky(command) &&
      command.replace('\\', '/').toLowerCase.contains("agents.md")

end AgentInstructionWriteGate
