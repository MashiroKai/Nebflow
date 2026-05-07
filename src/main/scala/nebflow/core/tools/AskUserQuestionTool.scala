package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.{AskItem, AskOption}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*

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
            "required" -> io.circe.Json.arr("question".asJson)
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

    if questionsJson.isEmpty then IO.pure(Left(ToolError("No valid questions provided")))
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

      if items.isEmpty then IO.pure(Left(ToolError("No valid questions provided")))
      else
        // Use Pekko Ask pattern via AskUser actor message — actor handles WS forwarding
        // and completes replyTo when UserAnswered arrives
        (ctx.agentActorRef, ctx.pekkoScheduler) match
          case (Some(agentRef), Some(scheduler)) =>
            import scala.concurrent.ExecutionContext.Implicits.global
            implicit val sched: org.apache.pekko.actor.typed.Scheduler = scheduler
            implicit val askTimeout: org.apache.pekko.util.Timeout =
              org.apache.pekko.util.Timeout(scala.concurrent.duration.Duration(5, "minutes"))
            val requestId = java.util.UUID.randomUUID().toString.take(8)
            IO.fromFuture(IO {
              agentRef.ask[List[String]](replyTo => AgentCommand.AskUser(requestId, items, replyTo))
            }).map { answers =>
              if items.size <= 1 then Right(answers.headOption.getOrElse(""))
              else
                val formatted = items.zipWithIndex
                  .map { case (item, idx) =>
                    val answer = answers.lift(idx).getOrElse("(no answer)")
                    s"${idx + 1}. ${item.question.take(60)}\n   → $answer"
                  }
                  .mkString("\n")
                Right(formatted)
            }.handleError(e => Left(ToolError(s"AskUser failed: ${e.getMessage}")))

          case _ =>
            IO.pure(Left(ToolError("AskUserQuestion requires agent actor and scheduler")))
      end if
    end if
  end call
end AskUserQuestionTool
