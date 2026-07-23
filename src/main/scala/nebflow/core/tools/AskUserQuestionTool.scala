package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.ActorRef
import nebflow.agent.AgentCommand
import nebflow.core.{AskItem, AskOption, QuestionDependency}

object AskUserQuestionTool extends Tool:
  val name = "AskUserQuestion"

  val description =
    """Ask the user one or more questions. Each question can have predefined options or be open-ended.

Use this tool when you need user input to proceed. Never ask clarifying questions in plain text — use this tool instead.

Guidelines:
- For multiple-choice questions, provide clear label values and optional description for each option.
- For open-ended questions, omit options so the user gets a free-text input.
- The UI always provides an "Other..." option so the user can type freely even for multiple-choice.
- Independent questions are shown together and can be answered at once.

Conditional branching (dependsOn):
- Give the upstream question an `id`, then set `dependsOn: {"ref": "<id>", "equals": "<answer>"}` on the dependent question.
- The dependent question is only shown when the referenced answer matches `equals`.

Behavior:
- This tool blocks until the user responds. Your turn pauses and resumes automatically when the user answers."""

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
              "id" -> io.circe.Json.obj(
                "type" -> "string".asJson,
                "description" -> "Unique identifier for this question. Required when other questions depend on this one.".asJson
              ),
              "dependsOn" -> io.circe.Json.obj(
                "type" -> "object".asJson,
                "description" -> "Only show this question when the referenced question's answer matches. Use for conditional branching.".asJson,
                "properties" -> io.circe.Json.obj(
                  "ref" -> io.circe.Json
                    .obj("type" -> "string".asJson, "description" -> "The id of the question this depends on".asJson),
                  "equals" -> io.circe.Json.obj(
                    "type" -> "string".asJson,
                    "description" -> "The answer value that must match for this question to appear".asJson
                  )
                ),
                "required" -> io.circe.Json.arr("ref".asJson, "equals".asJson)
              ),
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
        if question.isBlank then None  // skip malformed entries with empty question
        else
          val id = q.hcursor.downField("id").as[String].toOption
          val dependsOn = for
            dep <- q.hcursor.downField("dependsOn").focus
            ref <- dep.hcursor.downField("ref").as[String].toOption
            equals <- dep.hcursor.downField("equals").as[String].toOption
          yield QuestionDependency(ref, equals)
          val options = q.hcursor.downField("options").as[List[io.circe.Json]].getOrElse(Nil)
          val opts = options.flatMap { o =>
            val label = o.hcursor.downField("label").as[String].getOrElse("")
            if label.isBlank then None  // skip options with empty label
            else
              val desc = o.hcursor.downField("description").as[String].toOption
              Some(AskOption(label, desc))
          }
          Some(AskItem(question, opts, id = id, dependsOn = dependsOn))
      }.toList

      if items.isEmpty then IO.pure(Left(ToolError("No valid questions provided")))
      else
        ctx.agentActorRef match
          case Some(agentRef) =>
            val requestId = java.util.UUID.randomUUID().toString.take(8)
            agentRef
              .?(
                (replyTo: ActorRef[List[String]]) => AgentCommand.AskUser(requestId, items, Some(replyTo)),
                timeout = None
              )
              .map { answers =>
                Right(formatAnswer(items, answers))
              }
          case None =>
            IO.pure(Left(ToolError("AskUserQuestion requires agent actor")))
      end if
    end if
  end call

  /** Format user answers for display. */
  def formatAnswer(items: List[AskItem], answers: List[String]): String =
    if items.size <= 1 then answers.headOption.filter(_.nonEmpty).getOrElse("")
    else
      items.zipWithIndex
        .map { case (item, idx) =>
          val raw = answers.lift(idx).getOrElse("")
          val answer = if raw.isEmpty then "(skipped)" else raw
          s"${idx + 1}. ${item.question.take(60)}\n   → $answer"
        }
        .mkString("\n")
end AskUserQuestionTool
