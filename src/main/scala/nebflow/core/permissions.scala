package nebflow.core

import io.circe.JsonObject
import nebflow.core.tools.BashTool

/** Determines whether a tool call is reversible (auto-approve) or
  * irreversible (ask the user for confirmation).
  *
  * Design principle: only truly irreversible operations require user approval.
  * Everything else is auto-approved. File operations are reversible via FileHistory.
  */
object ToolReversibility:

  private val AlwaysReversible = Set(
    "Read", "Glob", "Grep",
    "WebSearch", "WebFetch",
    "Edit", "Write",
    "AskUserQuestion", "ContextManage",
    "TaskCreate", "TaskUpdate", "TaskGet", "TaskDelete", "TaskList",
    "Card", "RemoveUnnecessary"
  )

  private val SafeHttpMethods = Set("GET", "HEAD", "OPTIONS")

  def isReversible(toolName: String, input: JsonObject): Boolean =
    if AlwaysReversible.contains(toolName) then true
    else if toolName == "Bash" then
      // Non-dangerous bash commands are considered reversible
      input("command").flatMap(_.asString).forall(cmd => !BashTool.isDangerous(cmd))
    else if toolName == "Curl" then
      // Only read-only HTTP methods are reversible
      input("method").flatMap(_.asString).forall(m => SafeHttpMethods.contains(m.toUpperCase))
    else
      // Unknown tools: conservative — ask user
      false

end ToolReversibility
