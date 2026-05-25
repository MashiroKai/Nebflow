package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.MemoryAgentProtocol.*
import nebflow.service.MemoryStore

/**
 * Built-in tool for writing to memory files across all scopes.
 *
 * - Session scope: writes directly to the session memory file.
 * - User/Agent/Folder scopes: routes to the appropriate Memory Agent
 *   via MemoryAgentManager for async merge processing.
 */
object WriteMemoryTool extends Tool:
  val name = "WriteMemory"

  val description =
    """Write an observation to memory. The system automatically routes to the correct scope.

## Scopes

- **session** — Per-session scratchpad: task goals, progress notes, open questions. Direct write, takes effect immediately.
- **folder** — Project-specific knowledge: codebase facts, conventions, gotchas. Managed by Memory Agent, merged in background.
- **agent** — Architecture decisions, conventions, debugging patterns. Durable across sessions. Managed by Memory Agent.
- **user** — User preferences and explicit instructions. Global, shared across all agents. Managed by Memory Agent.

## How to write

- Use concise bullet points prefixed with a tag: `[decision]`, `[fact]`, `[gotcha]`, `[convention]`, `[todo]`, `[fix]`.
- One entry per call — keep content focused.
- Do not duplicate information already present in memory or system prompt.
- Do not log transient state (line numbers, temporary errors)."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "scope" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("session".asJson, "folder".asJson, "agent".asJson, "user".asJson),
          "description" -> "Which memory scope to write to".asJson
        ),
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The memory entry to write (e.g., '- [fact] ...')".asJson
        ),
        "category" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr(
            "decision".asJson, "fact".asJson, "gotcha".asJson,
            "convention".asJson, "todo".asJson, "fix".asJson
          ),
          "description" -> "Tag category for this entry".asJson
        )
      ),
      "required" -> Json.arr("scope".asJson, "content".asJson, "category".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val scope = input("scope").flatMap(_.asString).getOrElse("?")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val preview = if content.length > 60 then content.take(57) + "..." else content
    s"WriteMemory($scope: $preview)"

  def summarizeResult(input: JsonObject, result: String): String =
    val scope = input("scope").flatMap(_.asString).getOrElse("?")
    s"Memory written to $scope"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val scopeStr = input("scope").flatMap(_.asString).getOrElse("")
    val content = input("content").flatMap(_.asString).getOrElse("")
    val category = input("category").flatMap(_.asString).getOrElse("fact")

    if content.trim.isEmpty then
      return IO.pure(Left(ToolError("Content cannot be empty")))

    scopeStr match
      case "session" =>
        // Session memory: direct append via MemoryStore (preserves cache invalidation)
        ctx.sessionId match
          case Some(sid) =>
            MemoryStore.appendSessionMemory(sid, content).as(Right(s"Written to session memory."))
          case None =>
            IO.pure(Left(ToolError("No active session")))

      case "user" =>
        routeToMemoryAgent(MemoryAgentScope.User, content, category, ctx)

      case "agent" =>
        val agentName = ctx.agentDef.map(_.name).getOrElse("Nebula")
        routeToMemoryAgent(MemoryAgentScope.Agent(agentName), content, category, ctx)

      case "folder" =>
        ctx.folderId match
          case Some(fid) =>
            ctx.memoryAgentManager match
              case Some(mgr) =>
                mgr.resolveFolderScope(fid).flatMap {
                  case Some(folderScope) =>
                    routeToMemoryAgent(folderScope, content, category, ctx)
                  case None =>
                    IO.pure(Left(ToolError(s"Could not resolve top-level folder for $fid")))
                }
              case None => IO.pure(Left(ToolError("Memory Agent system not available")))
          case None =>
            IO.pure(Left(ToolError("No folder associated with this session")))

      case _ =>
        IO.pure(Left(ToolError(s"Unknown scope: $scopeStr")))

  private def routeToMemoryAgent(
    scope: MemoryAgentScope,
    content: String,
    category: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ctx.memoryAgentManager match
      case Some(mgr) =>
        val mail = MemoryWriteMail(
          entry = content,
          sourceSessionId = ctx.sessionId.getOrElse("unknown"),
          category = category
        )
        mgr.sendMail(scope, mail).as(Right(
          s"Memory queued for ${scope.label} Memory Agent. Will be merged on next processing cycle."
        ))
      case None =>
        IO.pure(Left(ToolError("Memory Agent system not available")))

end WriteMemoryTool
