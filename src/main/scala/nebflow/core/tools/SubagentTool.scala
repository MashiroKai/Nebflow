package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

/**
 * SubagentTool — reconnaissance sub-agent entry (Explore
 * 同型）。挂载面 = 合并执行 agent `nebflow`（[[nebflow.agent.AgentCore]]
 * .NebflowFixedTools）；阻塞运行，结果即返回值（不经后台回投链）。
 */
object SubagentTool extends Tool:
  val name = "Subagent"

  val description =
    """Run a recon sub-agent to completion and get its findings as THIS tool's result. The fixed tools are Read/Glob/Grep/WebSearch/WebFetch/Bash. Bash uses the normal execution and permission pipeline, not a read-only sandbox. Reconnaissance is role policy; no separate Write/Edit, messaging or recursive agent tools are granted.

**When to use:** lookups, codebase surveys, documentation digs and foreground shell-backed reconnaissance whose raw material would flood your context. The sub-agent must finish shell work and include its result before answering; it must not start background work. Do NOT use it for implementation or work needing your judgment on intermediate state. Follow your role's execution rules for that work; dispatchers must use chain nodes instead of executing it themselves.

**Parameters:**
- `prompt` (required): the question to answer — make it self-contained (paths to start from, what counts as an answer). The sub-agent has none of your context.
- `description` (optional): short UI label for the sub-agent panel row.

**Semantics:** this call BLOCKS until the sub-agent finishes (hard cap 15 minutes). Its final text comes back as the tool result; cite it, don't redo it."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "prompt" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained research question: what to find, where to start (absolute paths), what the answer must contain.".asJson
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the sub-agent panel row.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val label =
      input("description").flatMap(_.asString).orElse(input("prompt").flatMap(_.asString).map(_.take(40))).getOrElse("")
    s"Subagent($label)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val description = input("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse("recon")
    if prompt.trim.isEmpty then
      IO.pure(Left(ToolError("Missing required parameter: prompt — give the sub-agent a self-contained question.")))
    else BlockingSubagent.run(prompt, description, ctx)
end SubagentTool
