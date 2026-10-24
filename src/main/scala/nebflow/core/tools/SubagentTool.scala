package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

/**
 * SubagentTool —— 通用子代理入口（builtin-merge 批 2026-10-03 设立；
 * subagent-generalization 批 2026-10-24 去只读：子会话 = 完整基础工具面的
 * 通用 agent 位——能写文件、跑命令、完成端到端小任务）。挂载面 = 合并执行
 * agent `nebflow`（[[nebflow.agent.AgentCore.NebflowFixedTools]]）；阻塞运行，
 * 结果即返回值（不经后台回投链）。
 */
object SubagentTool extends Tool:
  val name = "Subagent"

  val description =
    """Run a general-purpose sub-agent to completion and get its final text as THIS tool's result. The sub-agent has the full base tool face (Read/Write/Edit/Glob/Grep/Bash/AskUserQuestion/WebSearch/WebFetch) and works the task end to end — it can write files, run commands and verify its own work. It has NO dispatch or messaging tools (leaf by mechanism).

**When to use:** a bounded, self-contained leg of work you can hand off in one brief — codebase surveys and lookups, documentation digs, or a small end-to-end task (generate a file, run a check, produce a verified artifact) whose raw material or step-by-step execution would flood your context. Do NOT use it for work you must judge between phases, or for parallel multi-part execution (use AgentFlow) or a long-running background task (use Delegate).

**Parameters:**
- `prompt` (required): the task — make it self-contained (paths to start from, what done looks like). The sub-agent has none of your context.
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
