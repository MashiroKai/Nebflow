package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.entity.FlowParamSpec

/**
 * FlowTriggerTool — triggers a flow DAG pipeline.
 *
 * Split out of DelegateTool (R1, 2026-08-15): "delegate an agent" and
 * "trigger a pipeline" are two verbs with different runtime mechanics
 * (AgentActor + BackoffSupervisor vs one-shot FlowDagRunner) and different
 * permission dimensions. Delegate is now a pure sub-agent spawner.
 *
 * Availability is whitelist-driven, NOT Nebula-exclusive: buildAllowedToolSet
 * injects this tool into any agent whose agent.json declares a non-empty
 * `flows` array, and strips it from everyone else. Each call is gated by the
 * same whitelist (the agent must declare the flow it triggers). SubTask
 * workers are leaf agents and never get it.
 *
 * The flow runs in the background; the result is delivered to the caller as
 * an ImmediateInput message ("[Flow '...' completed] ...") when it finishes.
 */
object FlowTriggerTool extends Tool:
  val name = "FlowTrigger"

  val description =
    """Trigger a flow DAG pipeline (e.g. "code-review", "release-stable"). The pipeline drives a sequence of agents defined in its flow.json — you do not pick the agents, the flow routes itself.

Complementary to FlowExecute (not a transition): this tool runs FIXED predefined pipelines (flows/ definitions, whitelist-triggered, repeated same-shape use, discipline gates); FlowExecute runs one-shot ad-hoc orchestration (inline DAG, shape follows the task, parallelism scales with the task). Check for a matching predefined flow in flows/ first — if one exists use this tool; only when none matches and the task needs multi-agent orchestration use FlowExecute.

**Parameters:**
- flow (required): flow name. Must be declared in your agent's flows whitelist — calling with an undeclared flow is rejected.
- prompt (required): self-contained task input for the flow's entry node.
- params (optional): structured parameters the flow declares in its `params` schema (e.g. { "fanout": 3 }). Validated against declared types/ranges; missing keys fall back to the flow's defaults. Node inputs reference them via $params.<name>.

**Behavior:**
- Returns immediately after the flow starts; the pipeline runs in the background
- The result is delivered to you as a system message when the flow completes ("[Flow '<name>' completed]")
- The prompt goes to the entry node; downstream nodes receive upstream outputs automatically

**Rules:**
- Prompt must be self-contained (the flow's agents start with clean contexts)
- State what "done" looks like (e.g. "Report findings — do not modify files")
- Do NOT duplicate the pipeline's work while it runs
- To spawn a sub-agent instead (a single autonomous worker), use Delegate."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "flow" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Flow name to trigger (e.g. \"code-review\", \"release-beta\"). Must be declared in your agent's flows whitelist.".asJson
        ),
        "prompt" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task input for the flow's entry node. Must include all context the pipeline needs.".asJson
        ),
        "params" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "Optional structured parameters declared in the flow's params schema (validated against types/ranges; defaults apply for missing keys).".asJson,
          "additionalProperties" -> Json.True
        )
      ),
      "required" -> Json.arr("flow".asJson, "prompt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val flow = input("flow").flatMap(_.asString).getOrElse("?")
    val params = input("params").flatMap(_.asObject).map(_.keys.toList.sorted.mkString(",")).getOrElse("")
    s"FlowTrigger(→ flow:$flow${if params.nonEmpty then s" params:$params" else ""})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  /**
   * Validate trigger params against the flow's declared schema. Unknown keys
   * are rejected (catches typos), types and int ranges are enforced, and
   * declared defaults fill in missing keys. Returns the effective param map
   * (defaults ++ provided) or a clear rejection reason.
   */
  private[tools] def validateFlowParams(
    declared: Map[String, FlowParamSpec],
    provided: JsonObject
  ): Either[String, Map[String, Json]] =
    provided.keys.find(k => !declared.contains(k)) match
      case Some(k) =>
        val known = if declared.isEmpty then "(flow declares no params)" else declared.keys.toList.sorted.mkString(", ")
        Left(s"unknown parameter '$k' — declared: $known")
      case None =>
        provided.toList.foldLeft[Either[String, Map[String, Json]]](Right(declared.collect {
          case (k, spec) if spec.default.nonEmpty => k -> spec.default.get
        })) { (acc, kv) =>
          val (k, v) = kv
          acc.flatMap { m =>
            val spec = declared(k)
            val typeOk = spec.`type` match
              case "int"    => v.isNumber
              case "string" => v.isString
              case "bool"   => v.isBoolean
              case _        => false
            if !typeOk then
              Left(s"parameter '$k' must be ${spec.`type`} (got ${v.name})")
            else
              val rangeOk = spec.`type` match
                case "int" =>
                  (spec.min, spec.max, v.asNumber.flatMap(_.toInt)) match
                    case (Some(lo), Some(hi), Some(x)) => x >= lo && x <= hi
                    case (Some(lo), None, Some(x))     => x >= lo
                    case (None, Some(hi), Some(x))     => x <= hi
                    case _                             => true
                case _ => true
              if !rangeOk then
                val bounds = (spec.min, spec.max) match
                  case (Some(lo), Some(hi)) => s"[$lo, $hi]"
                  case (Some(lo), None)     => s">= $lo"
                  case (None, Some(hi))     => s"<= $hi"
                  case _                    => ""
                Left(s"parameter '$k' out of range $bounds (got ${v.noSpaces})")
              else Right(m + (k -> v))
          }
        }

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val flowName = input("flow").flatMap(_.asString).filter(_.nonEmpty)
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val paramsInput = input("params").flatMap(_.asObject).getOrElse(JsonObject.empty)

    if flowName.isEmpty then IO.pure(Left(ToolError("Missing required parameter: flow")))
    else if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else
      // Whitelist: agent must declare this flow in its flows list.
      // "*" is a wildcard matching any named flow — mirrors the skills layer
      // semantics (skills:["*"] = full catalog injection).
      val whitelistError: Option[ToolError] = ctx.agentDef match
        case Some(ad)
            if !(ad.flows.contains(flowName.get) || ad.flows.contains("*")) =>
          val allowed = if ad.flows.isEmpty then "(none — no flows declared)" else ad.flows.mkString(", ")
          Some(ToolError(s"Flow '${flowName.get}' not allowed for agent '${ad.name}'. Allowed: $allowed"))
        case _ => None
      whitelistError match
        case Some(err) => IO.pure(Left(err))
        case None =>
          // Trigger flow via FlowDagRunner
          (ctx.sharedResources, ctx.actorSystem, ctx.agentActorRef) match
            case (Some(resources), Some(sys), Some(callerRef)) =>
              for
                // Resolve the caller's root session so flow nodes inherit the
                // same permission policy and render interactions in the caller's
                // window.
                callerRoot <- ctx.sessionId match
                  case Some(sid) =>
                    resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
                  case None => IO.pure("")
                flowOpt <- nebflow.core.entity.EntityLoader.loadFlow(flowName.get)
                r <- flowOpt match
                  case Some(flowDef) =>
                    validateFlowParams(flowDef.params, paramsInput) match
                      case Left(err) =>
                        IO.pure(Left(ToolError(s"Flow '${flowName.get}' params rejected: $err")))
                      case Right(effectiveParams) =>
                        for
                          runnerRef <- sys.spawn(
                            nebflow.core.flow.FlowDagRunner(resources, ctx.wsSend),
                            s"dag-runner-${flowName.get.take(10)}-${System.currentTimeMillis().toString.takeRight(6)}"
                          )
                          _ <- (runnerRef ! nebflow.core.flow.FlowDagRunner.RunFlow(
                            flowDef,
                            prompt,
                            callerRef,
                            callerRoot,
                            effectiveParams,
                            callerSessionId = ctx.sessionId.getOrElse("")
                          )).void
                        yield Right(s"Flow '${flowName.get}' started. Result will be delivered when complete.")
                  case None =>
                    IO.pure(Left(ToolError(s"Flow '${flowName.get}' not found")))
              yield r
            case _ =>
              IO.pure(Left(ToolError("Cannot start flow: missing resources")))
    end if
  end call

end FlowTriggerTool
