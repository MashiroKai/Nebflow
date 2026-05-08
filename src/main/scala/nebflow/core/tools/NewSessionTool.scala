package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.*

object NewSessionTool extends Tool:
  val name = "NewSession"

  val description =
    """Create a new session with an initial task. Asks the user for confirmation first, then the new session starts working immediately.

Use this tool when:
- The user's request is unrelated to the current task and deserves its own session
- You want to delegate a sub-task to a separate session that runs independently
- A topic shift is detected that warrants a clean context

Parameters:
- name: Short descriptive name for the new session
- message: The initial task/message to send to the new session. Write it as specific instructions for what needs to be done.
  After user confirms, this message is injected into the new session and it starts processing immediately."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "name" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short name for the new session (use user's language)".asJson
        ),
        "message" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The initial task to execute in the new session".asJson
        )
      ),
      "required" -> io.circe.Json.arr("name".asJson, "message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val name = input("name").flatMap(_.asString).getOrElse("")
    s"NewSession($name)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 100 then result.take(97) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val suggestedName = input("name").flatMap(_.asString).getOrElse("New Session")
    val message = input("message").flatMap(_.asString).getOrElse("")

    if message.isEmpty then IO.pure(Left(ToolError("message is required — describe what the new session should do")))
    else
      ctx.sessionStore match
        case Some(store) =>
          for
            meta <- store.createSession(suggestedName, agentName = ctx.agentDef.map(_.name))
            _ <- store.switchSession(meta.id)
          yield Right(s"New session \"$suggestedName\" created and switched to.")
        case None =>
          IO.pure(Left(ToolError("Session storage not available.")))
    end if
  end call
end NewSessionTool
