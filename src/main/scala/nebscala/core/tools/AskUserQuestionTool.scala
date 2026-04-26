package nebscala.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebscala.core.{AskItem, AskOption, AskUser}
import nebscala.core.tools.ToolError

object AskUserQuestionTool extends Tool:
  val name = "AskUserQuestion"

  val description = """Ask the user one or more questions with predefined options. The user selects via arrow keys in the terminal.

Usage:
- Each question MUST have at least 2 options
- The terminal automatically adds "Other (custom input)" so the user can always type freely
- Do NOT use this tool for simple questions without options — just ask directly in your text response
- Supports multiple questions in one call — ask everything you need at once"""

  val inputSchema = JsonObject.fromIterable(List(
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
                  "label" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Short option label".asJson),
                  "description" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Optional explanation".asJson)
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
  ))

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
      IO.pure(Left(ToolError("At least one question is required. For simple questions without options, ask directly in your text response.")))
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

      if items.exists(_.options.isEmpty) then
        IO.pure(Left(ToolError("All questions must have at least one option.")))
      else if !AskUser.isInteractive then
        IO.pure(Left(ToolError("Cannot ask user questions in non-interactive mode.")))
      else
        AskUser.ask(items).map { answers =>
          Right(answers.zipWithIndex.map { case (a, i) =>
            s"${i + 1}. ${items(i).question} -> $a"
          }.mkString("\n"))
        }
