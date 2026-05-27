package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.service.MemoryStore

/**
 * Built-in tool for submitting observations to the memory staging area.
 *
 * WriteMemoryTool does NOT write to final memory files. It appends to a staging
 * area (~/.nebflow/memory-staging.jsonl). The MemoryAgent processes the staging
 * area during the Dream cycle and writes to the final memory files.
 *
 * The agent handles formatting, headings, dedup, and organization.
 * Detail content can be attached and will be carried through staging.
 */
object WriteMemoryTool extends Tool:
  val name = "WriteMemory"

  val description =
    """Write an observation to memory. Queued for processing — not written immediately.
Processed by the MemoryAgent within minutes.

## When to write (HIGH BAR)

Only write when rediscovering this knowledge would cost significant time or cause repeated mistakes.
- DO write: addresses, credentials, user corrections, non-obvious pitfalls, behavioral patterns, entity relationships, architectural decisions
- DO NOT write: things already in system prompt, transient state (line numbers, temp errors), detailed step-by-step procedures, information that's obvious from the code

## Which scope — think about the *impact range* of the information

- **folder** — Matters for this project (folder tree). Codebase structure, project conventions, API details, config values, build instructions.
- **agent** — Matters for you across all projects. Architecture decisions, debugging patterns, gotchas, tool conventions.
- **user** — Matters across all agents and all sessions. User preferences, communication style, personal context, cross-project information the user wants persisted globally.

When in doubt, prefer a narrower scope. You can always promote to a wider scope later.

## How to write

- **Self-contained**: a fresh agent in a new session with zero context must understand and act on this entry
- **One line per entry** in the content field. Keep it concise
- Use the `detail` field for longer explanations that support the index entry
- Do not duplicate information already present in memory or system prompt
- Do not log transient state (line numbers, temporary errors)"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "scope" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("folder".asJson, "agent".asJson, "user".asJson),
          "description" -> "Which memory scope to write to".asJson
        ),
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Index entry — one self-contained line.".asJson
        ),
        "detail" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional. Detail content (markdown). Will be stored in a separate file.".asJson
        )
      ),
      "required" -> Json.arr("scope".asJson, "content".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val scope = input("scope").flatMap(_.asString).getOrElse("?")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val preview = if content.length > 60 then content.take(57) + "..." else content
    s"WriteMemory($scope: $preview)"

  def summarizeResult(input: JsonObject, result: String): String =
    "Memory queued for Dream processing"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val scopeStr = input("scope").flatMap(_.asString).getOrElse("")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val detail = input("detail").flatMap(_.asString)

    if content.trim.isEmpty then IO.pure(Left(ToolError("Content cannot be empty")))
    else if !Set("folder", "agent", "user").contains(scopeStr) then
      IO.pure(Left(ToolError(s"Unknown scope: $scopeStr")))
    else
      val source = ctx.agentDef.map(_.name).getOrElse("unknown")
      val folderId = ctx.folderId
      MemoryStore
        .appendStaging(scopeStr, content, detail, source, folderId)
        .as(Right("Queued for Dream processing."))
  end call

end WriteMemoryTool
