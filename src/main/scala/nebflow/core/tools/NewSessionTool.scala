package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.{AskItem, AskOption}

object NewSessionTool extends Tool:
  val name = "NewSession"

  val description =
    """Proactively suggest creating a new session when the user's message clearly shifts to an unrelated task or topic.

This tool only asks the user for confirmation — it does NOT modify the current session. Use it liberally whenever you detect a topic shift, even if the user didn't explicitly ask to switch.

Examples of when to call this tool:
- User was debugging a Python backend issue, then suddenly asks about writing a React component
- User was working on a database migration, then pastes an unrelated error log
- User was discussing architecture, then asks to write a shell script for something completely different
- The new message has no logical connection to the ongoing conversation

Do NOT call this tool for:
- Follow-up questions on the same topic
- Natural progression of the current task (e.g., "now add tests for that")
- Minor tangents that still relate to the current work

After confirmation, a clean empty session is created. Tell the user it's ready and they can switch via the sidebar."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "name" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short name for the new session, describing the new topic(use user's language)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("name".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val name = input("name").flatMap(_.asString).getOrElse("")
    s"NewSession($name)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 100 then result.take(97) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val suggestedName = input("name").flatMap(_.asString).getOrElse("New Session")

    (ctx.replUi, ctx.sessionStore) match
      case (Some(ui), Some(store)) =>
        val items = List(
          AskItem(
            s"Topic shift detected. Create a new session \"$suggestedName\"?",
            List(
              AskOption("Yes, create new session"),
              AskOption("No, continue here")
            ),
            allowOther = false
          )
        )
        ui.askUser(items).flatMap { answers =>
          answers.headOption match
            case Some(a) if a.startsWith("Yes") =>
              store.createSession(suggestedName).map { _ =>
                Right(s"New session \"$suggestedName\" created. Tell the user they can switch to it via the sidebar.")
              }
            case _ =>
              IO.pure(Right("User chose to continue in the current session."))
          end match
        }
      case _ =>
        IO.pure(Left(ToolError("NewSession tool requires interactive UI and session storage.")))
  end call
end NewSessionTool
