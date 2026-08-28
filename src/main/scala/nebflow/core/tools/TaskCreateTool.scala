package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.task.*

object TaskCreateTool extends Tool:
  val name = "TaskCreate"

  val description =
    """Create a new task in the task list.

## When to Use

- Complex multi-step tasks — When a task requires 3 or more distinct steps
- Plan mode — When planning, create a task list to track the work
- User explicitly requests todo list
- User provides multiple tasks (numbered or comma-separated)

## When NOT to Use

- Single, straightforward task
- Task can be completed in less than 3 trivial steps
- Purely conversational or informational requests

## Task Organization

The first level of the task list must be the **project name** (derived from the working directory).
Create a parent task with the project name as its subject, then create actual work tasks as
sub-tasks under it using `parentTaskId`.

Example structure:
- "Nebflow" (project root, parentTaskId omitted)
  - "Fix authentication bug" (parentTaskId = project root id)
  - "Implement dark mode" (parentTaskId = project root id)

When switching to a different project, create a new project-level parent task for it.

## Nested Tasks

Pass `parentTaskId` to create a sub-task under a parent task. This creates a
hierarchical task tree. Sub-tasks inherit the parent's context.

## Fields

- **subject**: Brief, actionable title in imperative form (e.g., "Fix authentication bug")
- **description**: What needs to be done
- **activeForm** (optional): Present continuous form for spinner (e.g., "Fixing authentication bug")
- **parentTaskId** (optional): Parent task ID for creating sub-tasks
- **taskKind** (optional): `"agent"` (default — your own tracked work) or `"human"` (a reminder FOR the user, e.g. "confirm the production deploy" — the user completes it by clicking its circle in the todos panel; do NOT work it yourself and never mark it in_progress)

All tasks are created with status `pending`. Use TaskUpdate to change status and manage dependencies. When you fully finish a task, mark it `needs_confirmation` (the user confirms completion themselves — `completed` is reserved for the user).

The current task list is always visible in your system prompt — no need to call TaskList."""

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
        ),
        "parentTaskId" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Parent task ID. Omit ONLY for the project root task (subject should be the project name). All work tasks MUST have a parentTaskId linking them to their project.".asJson
        ),
        "taskKind" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("agent".asJson, "human".asJson),
          "description" -> "'agent' (default) = your own tracked work; 'human' = a reminder for the user to complete via the todos panel (do not work it yourself)".asJson
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
          parentTaskId = input("parentTaskId").flatMap(_.asString),
          taskKind = input("taskKind").flatMap(_.asString)
        )
        for
          id <- store.create(sessionId, createInput)
          _ <- TaskToolHelper.emitTaskListUpdate(store, sessionId, ctx)
        yield Right(s"Task created: ${createInput.subject} (ID: $id)")
      case (None, _) => IO.pure(Left(ToolError("No task store available")))
      case (_, None) => IO.pure(Left(ToolError("No session ID available")))

end TaskCreateTool
