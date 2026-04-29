package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*

object ContextManageTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.context")
  val name = "ContextManage"

  val description = """Manage your own context window by replacing or deleting previous messages.

Messages are tagged with [ctx:N] in the conversation. Use these indices to identify targets.

Operations:
- replace: Replace one message or a range of messages with a summary string
- delete: Remove one message or a range of messages entirely

Use this tool proactively to keep your context window focused and avoid hitting token limits."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "operation" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("replace".asJson, "delete".asJson),
          "description" -> "Operation to perform".asJson
        ),
        "index" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Target message index (from [ctx:N] tag). Used for single-message operations.".asJson
        ),
        "start_index" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Start of range (inclusive). Used for range replace/delete.".asJson
        ),
        "end_index" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> "End of range (inclusive). Used for range replace/delete.".asJson
        ),
        "content" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Replacement text (for replace operation)".asJson
        )
      ),
      "required" -> io.circe.Json.arr("operation".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val op = input("operation").flatMap(_.asString).getOrElse("")
    val idx = input("index").flatMap(_.asNumber).flatMap(_.toInt)
    val startIdx = input("start_index").flatMap(_.asNumber).flatMap(_.toInt)
    val endIdx = input("end_index").flatMap(_.asNumber).flatMap(_.toInt)
    (op, idx, startIdx, endIdx) match
      case ("replace", _, Some(s), Some(e)) => s"CtxReplace([$s-$e] -> summary)"
      case ("replace", Some(i), _, _) => s"CtxReplace([$i] -> summary)"
      case ("delete", _, Some(s), Some(e)) => s"CtxDelete([$s-$e])"
      case ("delete", Some(i), _, _) => s"CtxDelete([$i])"
      case _ => s"CtxManage($op)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.messagesRef match
      case None => IO.pure(Left(ToolError("ContextManage: no message ref available")))
      case Some(ref) =>
        val op = input("operation").flatMap(_.asString).getOrElse("")
        op match
          case "replace" => doReplace(input, ref)
          case "delete" => doDelete(input, ref)
          case _ => IO.pure(Left(ToolError(s"Unknown operation: $op. Use replace or delete.")))

  // === Replace: replace message(s) with summary ===
  private def doReplace(input: JsonObject, ref: Ref[IO, List[Message]]): IO[Either[ToolError, String]] =
    val indexOpt = input("index").flatMap(_.asNumber).flatMap(_.toInt)
    val startOpt = input("start_index").flatMap(_.asNumber).flatMap(_.toInt)
    val endOpt = input("end_index").flatMap(_.asNumber).flatMap(_.toInt)
    val content = input("content").flatMap(_.asString).getOrElse("")

    if content.isEmpty then
      IO.pure(Left(ToolError("replace requires non-empty content")))
    else
      val range = (indexOpt, startOpt, endOpt) match
        case (_, Some(s), Some(e)) => Some((s, e))
        case (Some(i), _, _) => Some((i, i))
        case _ => None

      range match
        case None => IO.pure(Left(ToolError("replace requires: index or (start_index + end_index)")))
        case Some((start, end)) =>
          ref.get.flatMap { messages =>
            if start < 0 || end >= messages.length || start > end then
              IO.pure(Left(ToolError(s"Invalid range [$start-$end] (0-${messages.length - 1})")))
            else
              val count = end - start + 1
              val removedChars = (start to end).map(i => messages(i).textContent.length).sum
              val summaryMsg = Message(MessageRole.Assistant, Left(s"[context summary] $content"))
              val updated = messages.take(start) ++ (summaryMsg :: messages.drop(end + 1))
              ref.set(updated) *> logger.info(s"Replaced [ctx:$start-$end] ($count msgs, $removedChars chars)") *> IO.pure(Right(
                s"OK: Replaced [ctx:$start-$end] ($count messages, ${removedChars} chars) with summary"
              ))
          }

  // === Delete: remove message(s) ===
  private def doDelete(input: JsonObject, ref: Ref[IO, List[Message]]): IO[Either[ToolError, String]] =
    val indexOpt = input("index").flatMap(_.asNumber).flatMap(_.toInt)
    val startOpt = input("start_index").flatMap(_.asNumber).flatMap(_.toInt)
    val endOpt = input("end_index").flatMap(_.asNumber).flatMap(_.toInt)

    val range = (indexOpt, startOpt, endOpt) match
      case (_, Some(s), Some(e)) => Some((s, e))
      case (Some(i), _, _) => Some((i, i))
      case _ => None

    range match
      case None => IO.pure(Left(ToolError("delete requires: index or (start_index + end_index)")))
      case Some((start, end)) =>
        ref.get.flatMap { messages =>
          if start < 0 || end >= messages.length || start > end then
            IO.pure(Left(ToolError(s"Invalid range [$start-$end] (0-${messages.length - 1})")))
          else
            val remaining = messages.length - (end - start + 1)
            if remaining < 1 then
              IO.pure(Left(ToolError("Cannot delete all messages")))
            else
              val count = end - start + 1
              val removedChars = (start to end).map(i => messages(i).textContent.length).sum
              val updated = messages.take(start) ++ messages.drop(end + 1)
              ref.set(updated) *> logger.info(s"Deleted [ctx:$start-$end] ($count msgs, $removedChars chars freed)") *> IO.pure(Right(
                s"OK: Deleted [ctx:$start-$end] ($count messages, ${removedChars} chars freed)"
              ))
        }

end ContextManageTool
