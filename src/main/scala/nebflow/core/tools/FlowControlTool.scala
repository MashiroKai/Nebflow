package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.core.flow.{FlowTreeRegistry, PipelineStateStore, TreeCommand}

/** Query flow progress or cancel a stuck flow. */
object FlowControlTool extends Tool:
  val name = "FlowControl"

  val description =
    """Query running flow progress or cancel a stuck flow.

Actions:
- "status": List all flows with step-by-step progress (done/running/pending/failed).
- "cancel": Cancel a flow by name. Stops all running agents and marks the flow as failed.

Use "status" to check what's happening when a flow seems stuck. Use "cancel" to forcefully stop a hung flow."""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "action" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "\"status\" to query progress, \"cancel\" to stop a flow".asJson,
        "default" -> "status".asJson
      ),
      "name" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Flow instance name (required for cancel)".asJson
      )
    ),
    "required" -> Json.arr("action".asJson)
  ))

  def summarize(input: JsonObject): String =
    val action = input("action").flatMap(_.asString).getOrElse("status")
    val name = input("name").flatMap(_.asString).getOrElse("")
    action match
      case "cancel" => s"CancelFlow($name)"
      case _ => "FlowStatus()"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val action = input("action").flatMap(_.asString).getOrElse("status")
    val flowName = input("name").flatMap(_.asString).getOrElse("")
    val sessionId = ctx.sessionId.getOrElse("")

    if action == "status" then
      if sessionId.isEmpty then IO.pure(Right("No active session."))
      else
        PipelineStateStore.loadAll(sessionId).map { states =>
          if states.isEmpty then Right("No flows found.")
          else Right(states.sortBy(_.phase != "Running").map(formatState).mkString("\n\n"))
        }
    else if action == "cancel" then
      if flowName.isEmpty then
        IO.pure(Left(ToolError("Flow name is required for cancel action")))
      else
        FlowTreeRegistry.get(sessionId).flatMap {
          case Some(treeRef) =>
            treeRef ! TreeCommand.CancelPipeline(flowName)
            IO.pure(Right(s"Cancel signal sent to flow '$flowName'. Running agents will be stopped."))
          case None =>
            IO.pure(Left(ToolError(s"No flow system active for this session")))
        }
    else
      IO.pure(Left(ToolError(s"Unknown action '$action'. Use 'status' or 'cancel'.")))
    end if
  end call

  private def formatState(state: PipelineStateStore.PipelineState): String =
    val phaseLabel = state.phase match
      case "Running" => "RUNNING"
      case "Completed" => "completed"
      case "Failed" => "FAILED"
      case "Idle" => "idle"
      case other => other

    val header = s"Flow: ${state.name} [$phaseLabel]"
    val iter = if state.iteration > 0 then s" (iteration ${state.iteration})" else ""

    val steps = state.steps.map { step =>
      val marker = step.status match
        case "Done" => "done"
        case "Running" => "RUNNING"
        case "Failed" => "FAIL"
        case "Pending" => "pending"
        case "Canceled" => "canceled"
        case other => other
      val agentTag = if step.agent.nonEmpty then s" [${step.agent}]" else ""
      val verdictTag = if step.verdict then " (verdict)" else ""
      s"  ${step.id}$agentTag$verdictTag — $marker"
    }.mkString("\n")

    s"$header$iter\n$steps"

end FlowControlTool
