package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.ToolError
import nebflow.core.{AskItem, AskOption, AskUser}

object AskUserQuestionTool extends Tool:
  val name = "AskUserQuestion"

  val description =
    """Ask the user one or more questions. Each question can have predefined options or be open-ended.

Use `AskUserQuestion` when you need to pause and get clarification from the user before proceeding. Typical scenarios:

1. **Ambiguous input** — the user's request is unclear or could lead to incorrect output.
2. **Insufficient details** — the requirement is too vague and needs more context.
3. **Technical or design decisions** — multiple valid approaches exist and you need the user to choose or confirm.
4. **Missing information** — you need the user to provide files, credentials, preferences, or other data to continue.

Guidelines:
- Ask all related questions in a single tool call rather than making multiple sequential calls.
- For multiple-choice questions, provide clear label values and optional description for each option.
- For open-ended questions, omit options so the user gets a free-text input.
- Do not use this tool for trivial confirmations you can decide yourself.
- The UI always provides an "Other..." option so the user can type freely even for multiple-choice.
- Supports multiple questions in one call — ask everything you need at once."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "questions" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> "Questions to ask the user, each with its own options".asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "object".asJson,
            "properties" -> io.circe.Json.obj(
              "question" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The question to ask".asJson),
              "options" -> io.circe.Json.obj(
                "type" -> "array".asJson,
                "description" -> "Predefined choices for this question".asJson,
                "items" -> io.circe.Json.obj(
                  "type" -> "object".asJson,
                  "properties" -> io.circe.Json.obj(
                    "label" -> io.circe.Json
                      .obj("type" -> "string".asJson, "description" -> "Short option label".asJson),
                    "description" -> io.circe.Json
                      .obj("type" -> "string".asJson, "description" -> "Optional explanation".asJson)
                  ),
                  "required" -> io.circe.Json.arr("label".asJson)
                )
              )
            ),
            "required" -> io.circe.Json.arr("question".asJson, "options".asJson)
          )
        )
      ),
      "required" -> io.circe.Json.arr("questions".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val questions = input("questions").flatMap(_.asArray).getOrElse(Nil)
    if questions.isEmpty then "AskUser()"
    else if questions.length == 1 then
      val q = questions.head.hcursor.downField("question").as[String].getOrElse("")
      val short = if q.length > 40 then q.take(37) + "..." else q
      s"AskUser($short)"
    else s"AskUser(${questions.length} questions)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 100 then result.take(97) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val questionsJson = input("questions").flatMap(_.asArray).getOrElse(Nil)

    if questionsJson.isEmpty then
      IO.pure(
        Left(
          ToolError(
            "At least one question is required. For simple questions without options, ask directly in your text response."
          )
        )
      )
    else
      val items = questionsJson.flatMap { q =>
        val question = q.hcursor.downField("question").as[String].getOrElse("")
        val options = q.hcursor.downField("options").as[List[io.circe.Json]].getOrElse(Nil)
        val opts = options.flatMap { o =>
          val label = o.hcursor.downField("label").as[String].getOrElse("")
          val desc = o.hcursor.downField("description").as[String].toOption
          Some(AskOption(label, desc))
        }
        Some(AskItem(question, opts))
      }.toList

      // Open-ended questions (empty options) are allowed — UI shows a text input
      ctx.agentActorRef match
        case Some(agentRef) =>
          // TODO: Phase 1 AskUser wiring — send AskUser to agent actor and await response
          // For now, instruct the agent to ask the user directly in its next response
          IO.pure(
            Left(
              ToolError(
                "AskUserQuestion requires async user response support (not yet wired). " +
                  "Please ask the user directly in your natural language response instead."
              )
            )
          )
        case None =>
          IO.pure(
            Left(ToolError("AskUserQuestion tool requires an agent actor. Not available in non-interactive mode."))
          )
    end if
  end call
end AskUserQuestionTool
