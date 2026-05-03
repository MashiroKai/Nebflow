package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskCreateTool extends Tool:
  val name = "TaskCreate"

  val description =
    """Create a new task in the task list.

Use this tool to create a structured task list for your current coding session.
This helps you track progress, organize complex tasks, and show the user what you are doing.

## When to Use

- Complex multi-step tasks — When a task requires 3 or more distinct steps
- Plan mode — When planning, create a task list to track the work
- User explicitly requests todo list
- User provides multiple tasks (numbered or comma-separated)

## When NOT to Use

- Single, straightforward task
- Task can be completed in less than 3 trivial steps
- Purely conversational or informational requests

## Fields

- **subject**: Brief, actionable title in imperative form (e.g., "Fix authentication bug")
- **description**: What needs to be done
- **activeForm** (optional): Present continuous form for spinner (e.g., "Fixing authentication bug")

All tasks are created with status `pending`.

## Task System Overview

- Status progresses: `pending` -> `in_progress` -> `completed`
- Valid transitions only: pending -> in_progress, in_progress -> completed
- `completed` cannot go back to any other state
- Use TaskUpdate to change status and manage dependencies (addBlocks, addBlockedBy, removeBlocks, removeBlockedBy)
- Use TaskDelete to permanently remove a task (irreversible)
- Prefer working on tasks in ID order when multiple are available"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "subject" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Brief title for the task".asJson
        ),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "What needs to be done".asJson
        ),
        "activeForm" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Present continuous form shown in spinner (e.g., 'Running tests')".asJson
        )
      ),
      "required" -> Json.arr("subject".asJson, "description".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val subject = input("subject").flatMap(_.asString).getOrElse("")
    s"TaskCreate($subject)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 120 then result.take(117) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    (ctx.taskStore, ctx.sessionId) match
      case (Some(store), Some(sessionId)) =>
        val createInput = TaskCreateInput(
          subject = input("subject").flatMap(_.asString).getOrElse(""),
          description = input("description").flatMap(_.asString).getOrElse(""),
          activeForm = input("activeForm").flatMap(_.asString),
          metadata = input("metadata").flatMap(_.as[Map[String, io.circe.Json]].toOption)
        )
        for
          id <- store.create(sessionId, createInput)
          _ <- emitTaskListUpdate(store, sessionId, ctx)
        yield Right(s"Task #$id created successfully: ${createInput.subject}")
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

  private def emitTaskListUpdate(store: TaskStore, sessionId: String, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case Some(send) =>
        store.list(sessionId).flatMap { tasks =>
          val json = io.circe.Json.obj(
            "type" -> "taskListUpdate".asJson,
            "sessionId" -> sessionId.asJson,
            "tasks" -> tasks.asJson
          )
          send(json)
        }
      case None => IO.unit

end TaskCreateTool
