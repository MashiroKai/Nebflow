package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.flow.*

/**
 * ExecuteFlow — create and launch a multi-step workflow with verification.
 *
 * The flow runs autonomously in the background. The main agent receives only
 * the verified summary via ExternalEvent when the flow completes.
 *
 * Requires:
 * - "verify" is mandatory — it evaluates all work and produces PASS/FAIL + summary
 * - "loop" is optional — if verify fails, the fix step runs and verify re-runs
 * - Step prompts can reference other step outputs with ${stepId}
 *
 * Depth-limited: filtered out when depth >= DelegateTool.MaxDepth, same as Delegate.
 */
object ExecuteFlowTool extends Tool:
  private val logger = NebflowLogger(getClass)

  val name = "ExecuteFlow"

  val description =
    """Create and launch a multi-step workflow with verification and retry loops.

A flow runs autonomously in the background — you receive only the verified summary when it completes.

Define work steps (DAG with dependencies), a mandatory verify step, and an optional retry loop.

When to use ExecuteFlow:
- Complex tasks with multiple phases (explore -> plan -> implement -> verify)
- Tasks requiring parallel work on independent modules
- Tasks that may need multiple fix iterations

Do NOT use ExecuteFlow for:
- Simple tasks you can do directly (use Read, Edit, Bash)
- Single focused subtask (use Delegate)
- Tasks needing user interaction during execution (ask first, then flow)

Requirements:
- "verify" is mandatory — the verify agent calls the FlowVerify tool to report PASS/FAIL + summary
- "loop" is optional — if verify fails, the fix step runs, then verify re-runs (up to maxIterations)
- Step prompts can reference upstream outputs with ${stepId} (e.g. "Based on: ${explore}")
- In the loop fix step, use ${verify} to reference the verify failure summary
"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short name for the workflow.".asJson
        ),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "What this workflow accomplishes.".asJson
        ),
        "steps" -> Json.obj(
          "type" -> "array".asJson,
          "description" -> "Work steps forming a DAG. Steps with no unmet dependencies run in parallel (up to maxConcurrency).".asJson,
          "items" -> Json.obj(
            "type" -> "object".asJson,
            "properties" -> Json.obj(
              "id" -> Json.obj("type" -> "string".asJson, "description" -> "Unique step identifier.".asJson),
              "agent" -> Json
                .obj("type" -> "string".asJson, "description" -> "Agent name: Explorer, Planner, Nebula.".asJson),
              "prompt" -> Json.obj(
                "type" -> "string".asJson,
                "description" -> "Task prompt. Use ${stepId} to reference upstream results.".asJson
              ),
              "dependsOn" -> Json.obj(
                "type" -> "array".asJson,
                "items" -> Json.obj("type" -> "string".asJson),
                "description" -> "Step IDs that must complete first.".asJson
              )
            ),
            "required" -> Json.arr("id".asJson, "agent".asJson, "prompt".asJson)
          )
        ),
        "verify" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "Mandatory verification step. The verify agent uses the FlowVerify tool to report PASS/FAIL.".asJson,
          "properties" -> Json.obj(
            "agent" -> Json.obj("type" -> "string".asJson, "default" -> "Explorer".asJson),
            "prompt" -> Json.obj(
              "type" -> "string".asJson,
              "description" -> "Verification instructions. The agent will call FlowVerify tool with passed=true/false.".asJson
            )
          ),
          "required" -> Json.arr("prompt".asJson)
        ),
        "loop" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "Optional retry loop. On verify FAIL, runs fix step then re-verifies.".asJson,
          "properties" -> Json.obj(
            "fix" -> Json.obj(
              "type" -> "object".asJson,
              "properties" -> Json.obj(
                "id" -> Json.obj("type" -> "string".asJson),
                "agent" -> Json.obj("type" -> "string".asJson),
                "prompt" -> Json.obj(
                  "type" -> "string".asJson,
                  "description" -> "Fix instructions. Use ${verify} for verify output.".asJson
                )
              ),
              "required" -> Json.arr("agent".asJson, "prompt".asJson)
            ),
            "maxIterations" -> Json.obj("type" -> "integer".asJson, "default" -> 3.asJson)
          )
        ),
        "maxConcurrency" -> Json.obj("type" -> "integer".asJson, "default" -> 5.asJson)
      ),
      "required" -> Json.arr("name".asJson, "description".asJson, "steps".asJson, "verify".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val name = input("name").flatMap(_.asString).getOrElse("?")
    val stepCount = input("steps").flatMap(_.asArray).map(_.length).getOrElse(0)
    s"ExecuteFlow($name, $stepCount steps)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // Parse input → FlowDef
    FlowDefParser.parse(input) match
      case Left(parseError) =>
        IO.pure(Left(ToolError(s"Invalid flow definition: $parseError")))

      case Right(flowDef) =>
        // Validate structure
        FlowValidator.validate(flowDef) match
          case Left(validationError) =>
            IO.pure(Left(ToolError(s"Flow validation failed: $validationError")))

          case Right(_) =>
            // Check prerequisites
            (ctx.actorSystem, ctx.sharedResources, ctx.agentLibrary) match
              case (Some(system), Some(resources), Some(agentLibrary)) =>
                checkAgentsExist(flowDef, agentLibrary).flatMap {
                  case Left(err) => IO.pure(Left(ToolError(err)))
                  case Right(_) =>
                    spawnFlow(flowDef, ctx, system, resources)
                }
              case _ =>
                IO.pure(Left(ToolError("ExecuteFlow requires ActorSystem, SharedResources, and AgentLibrary")))
  end call

  // ============================================================
  // Pre-flight checks
  // ============================================================

  private def checkAgentsExist(
    flowDef: FlowDef,
    library: AgentLibrary
  ): IO[Either[String, Unit]] =
    val allAgents = flowDef.steps.map(_.agent) ++
      List(flowDef.verify.agent) ++
      flowDef.loop.map(_.fix.agent).toList
    val uniqueAgents = allAgents.distinct

    for
      results <- uniqueAgents.traverse(name => library.get(name).map(name -> _))
      missing = results.collect { case (name, None) => name }
    yield
      if missing.isEmpty then Right(())
      else Left(s"Unknown agent(s): ${missing.mkString(", ")}")
  end checkAgentsExist

  // ============================================================
  // Spawn FlowActor
  // ============================================================

  private def spawnFlow(
    flowDef: FlowDef,
    ctx: ToolContext,
    system: ActorSystem,
    resources: SharedResources
  ): IO[Either[ToolError, String]] =
    // Inherit bypass from parent session
    val bypassIO = (ctx.sessionStore, ctx.sessionId) match
      case (Some(store), Some(sid)) => store.getBypass(sid)
      case _ => IO.pure(false)

    bypassIO.flatMap { bypass =>
      val flowId = s"flow-${flowDef.name.take(20)}-${java.util.UUID.randomUUID().toString.take(8)}"
      val sessionId = ctx.sessionId.getOrElse("")

      // Save initial snapshot for persistence
      val initialSnapshot = FlowSnapshot(
        flowDef = flowDef,
        flowId = flowId,
        phase = FlowPhase.Working.toString,
        stepStatus = flowDef.steps.map(s => s.id -> StepStatus.Pending.toString).toMap,
        results = Map.empty,
        failedReasons = Map.empty,
        retryLeft = Map.empty,
        verifyResult = None,
        iteration = 0
      )

      // Register parent agent as flow member
      val parentPath = ctx.agentActorRef.map(_.path.toString).getOrElse("")

      for
        _ <- FlowStore.save(sessionId, flowId, initialSnapshot)
        _ <- if parentPath.nonEmpty then FlowMembership.join(parentPath, flowId) else IO.unit
        _ <- system
          .spawn(
            FlowActor(
              flowDef = flowDef,
              flowId = flowId,
              parentAgentRef =
                ctx.agentActorRef.getOrElse(throw RuntimeException("ExecuteFlow requires agentActorRef")),
              wsSend = ctx.wsSend,
              parentSessionId = ctx.sessionId,
              parentDepth = ctx.depth,
              resources = resources,
              projectRoot = ctx.projectRoot,
              bypass = bypass,
              restoreSnapshot = None
            ),
            flowId
          )
      yield
        logger.info(s"Spawned FlowActor: $flowId (parent=${ctx.agentActorRef.map(_.path.name)})")
        val stepSummary = flowDef.steps
          .map(s =>
            s"  ${s.id} (${s.agent})" +
              (if s.dependsOn.nonEmpty then s" <- ${s.dependsOn.mkString(", ")}" else "")
          )
          .mkString("\n")

        Right(
          s"""Flow '${flowDef.name}' started ($flowId).
             |$stepSummary
             |verify: ${flowDef.verify.agent}
             |${flowDef.loop.map(l => s"loop: fix=${l.fix.agent}, max=${l.maxIterations}").getOrElse("no loop")}
             |You will be notified when the flow completes via a system message.""".stripMargin
        )
      end for
    }
  end spawnFlow

end ExecuteFlowTool
