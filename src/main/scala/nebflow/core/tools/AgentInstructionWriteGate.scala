package nebflow.core.tools

import nebflow.shared.ToolCall

/** Untrusted agent-write boundary for project instruction files. */
object AgentInstructionWriteGate:
  private val AgentWriteTools = Set("Write", "Edit", "MultiEdit", "Bash")

  /**
   * Detect writes to the workspace-root AGENTS.md. The tool arguments are used
   * only to identify the target; `isAgent` must be supplied by the trusted
   * engine call context, not read from user/model input.
   */
  def requiresConfirmation(call: ToolCall, isAgent: Boolean): Boolean =
    isAgent && AgentWriteTools.contains(call.name) &&
      (call.input("file_path").flatMap(_.asString).exists(isAgentsFile) ||
        call.input("command").flatMap(_.asString).exists(commandTargetsAgentsFile))

  private def commandTargetsAgentsFile(command: String): Boolean =
    val lower = command.replace('\\', '/').trim.toLowerCase
    val readOnlySingleCommand =
      lower.matches("(?:cat|less|more|head|tail|grep|rg|test|stat|readlink)\\s+[^;&|<>]*")
    lower.contains("agents.md") && !readOnlySingleCommand

  def isAgentsFile(raw: String): Boolean =
    if raw == null || raw.trim.isEmpty then false
    else
      val normalized = raw.replace('\\', '/').split('/').filter(_.nonEmpty).mkString("/")
      normalized.equalsIgnoreCase("AGENTS.md") || normalized.toLowerCase.endsWith("/agents.md")
end AgentInstructionWriteGate
