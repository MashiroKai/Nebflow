package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.project.{ProjectActor, ProjectRuntimeRegistry}

/**
 * #28 阶段 2 迁移第一步（作者裁定「新任务都走 Project」）：Nebula 侧
 * （root 上下文）项目任务触发工具。
 *
 * 与 Mail(→project) 同内核（ProjectActor.TriggerDispatcher → 分发器会话）、
 * 入口不同：Task 是一等工具，Mail(→project) 保留兼容。与 TeamTask* 语义域
 * 独立（看板 vs 触发——Flow Map / NodeList 是项目的实时看板）。
 *
 * 分发器行为配置（preset / skills）由分发器 agent 定义承载（~/.nebflow/
 * agents/project-dispatcher/agent.json，agent 面板可改、热加载生效）——
 * Task 本身只传任务文本（作者 09:10 裁定：「任务分发器的配置在 agent 面板
 * 里改就行了」）。
 */
object TaskTool extends Tool:
  val name = "Task"

  val description =
    """Trigger a Project task dispatcher from the Nebula (root) context — the Project-side entry point for task dispatch.
## When to Use
- You (Nebula / root) want a project to pick up a task: Task(project=..., task=...) triggers the project's dispatcher session, which reads the Flow Map (NodeList) and creates/wires nodes (NodeEdit).
- Same engine as Mail(→project) — different entry point: Task is the first-class tool; Mail(→project) remains for compatibility.
- Distinct domain from TeamTask* (team kanban vs project task trigger) — the Flow Map (NodeList) is the project's live board.
- Dispatcher behavior (preset / skills) is configured on the dispatcher agent definition (agent panel, hot-reloaded) — not on this tool.

## Parameters
- **project** (required): the mounted project name.
- **task** (required): the task text for the dispatcher.

## Semantics
- The project must be mounted with a ProjectActor (GatewayMain mounts projects at startup).
- Fire-and-forget: this tool only triggers the dispatcher session; node results flow along out edges (not via this tool)."""
  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "project" -> Json.obj("type" -> "string".asJson, "description" -> "Mounted project name (required)".asJson),
        "task" -> Json.obj("type" -> "string".asJson, "description" -> "Task text for the dispatcher (required)".asJson)
      ),
      "required" -> Json.arr("project".asJson, "task".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val p = input("project").flatMap(_.asString).getOrElse("?")
    s"Task(→$p)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val project = input("project").flatMap(_.asString)
    val task = input("task").flatMap(_.asString)
    (project, task) match
      case (None, _) => IO.pure(Left(ToolError("Missing required parameter: project")))
      case (_, None) => IO.pure(Left(ToolError("Missing required parameter: task")))
      case (Some(p), Some(t)) =>
        ProjectRuntimeRegistry.get(p).flatMap {
          case None =>
            IO.pure(
              Left(ToolError(s"Project '$p' is not mounted — re-mount / restart (GatewayMain mounts projects at startup)"))
            )
          case Some(rt) =>
            rt.actorRef match
              case None =>
                IO.pure(Left(ToolError(s"Project '$p' has no mounted ProjectActor — re-mount / restart")))
              case Some(ref) =>
                // 与 MailTool.routeToProject 同路径：fire-and-forget 触发分发器。
                // rootSessionId 取上链根会话（fallback 调用方会话），保证节点
                // out="Nebula" 结果投递到真正的顶层而非工具调用者。
                val rootSid = ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")
                (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(t, rootSid)).void
                  .as(Right(s"Project '$p' dispatcher triggered"))
        }
end TaskTool
