package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.{AgentCommand, SharedResources}
import nebflow.core.NebflowLogger
import nebflow.core.flow.*

/**
 * ExecuteFlow — the universal execution primitive for flows.
 *
 * Replaces both Delegate and MountFlow. Any agent can call ExecuteFlow to
 * run a flow (named or inline) and receive the result via ExternalEvent.
 *
 * A flow is a declarative YAML pipeline at ~/.nebflow/flows/<name>.yaml.
 * Think of flows as composable recipes — define once, execute many times.
 */
object ExecuteFlowTool extends Tool:
  private val logger = NebflowLogger(getClass)

  val name = "ExecuteFlow"

  val description =
    """Execute a flow — a declarative pipeline of agent tasks.

A flow is a YAML-defined graph of nodes. Each node runs an agent task or a nested flow.
ExecuteFlow mounts the flow (if not already mounted) and triggers it with the given input.
The flow runs autonomously and you will be notified when it completes via a system message.

## Parameters
- source: Name of the flow to execute (from ~/.nebflow/flows/<name>.yaml)
- inline: Inline YAML flow definition (for ad-hoc flows)
- input: Input text passed to the flow, referenced as ${input} in node prompts

Either source or inline is required. input is required.

## Examples

Execute a named flow:
  {"source": "code-review", "input": "src/auth/Login.scala"}

Execute an inline ad-hoc flow:
  {"inline": "name: ad-hoc\\nnodes:\\n  - id: task\\n    agent: Explorer\\n    prompt: Search ${input}", "input": "find TODO comments"}

## Flow YAML Format

```yaml
name: my-flow
manager: Nebula        # optional: agent that summarizes results

nodes:
  - id: scan
    agent: Explorer
    prompt: "Analyze ${input}"
  - id: review
    agent: Explorer
    prompt: "Review: ${scan}"
    dependsOn: [scan]
    verdict: true       # outputs VERDICT: PASS/FAIL
  - id: fix
    agent: Coder
    prompt: "Fix: ${review}"
    dependsOn: [review]
    condition: review.fail   # only runs if review verdict is FAIL
    retry:                     # after fix, re-run review (cycle)
      target: review
      maxIterations: 3
```

## Key Rules
- Nebula cannot be used as a step agent (use Explorer, Coder, etc.)
- Flows can be nested (node with `flow: other-flow-name`)
- Use ${input} and ${nodeId} as template variables in prompts"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "source" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Name of the flow to execute (from ~/.nebflow/flows/<name>.yaml). Either source or inline is required.".asJson
        ),
        "inline" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Inline YAML flow definition. Use for ad-hoc tasks.".asJson
        ),
        "input" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Input text passed to the flow. Referenced as ${input} in node prompts.".asJson
        )
      ),
      "required" -> Json.arr("input".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val name = input("source")
      .flatMap(_.asString)
      .orElse(input("inline").flatMap(_.asString).map(_.take(30)))
      .getOrElse("?")
    s"ExecuteFlow($name)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sourceOpt = input("source").flatMap(_.asString)
    val inlineOpt = input("inline").flatMap(_.asString)
    val triggerInput = input("input").flatMap(_.asString).getOrElse("")

    (sourceOpt, inlineOpt) match
      case (None, None) =>
        IO.pure(Left(ToolError("Must provide either 'source' or 'inline'")))

      case (Some(_), Some(_)) =>
        IO.pure(Left(ToolError("Cannot specify both 'source' and 'inline'")))

      case (Some(source), None) =>
        FlowDefLoader.load(source).flatMap {
          case None =>
            IO.pure(Left(ToolError(s"Flow '$source' not found in ~/.nebflow/flows/")))
          case Some(defn) =>
            mountAndTrigger(defn, triggerInput, ctx)
        }

      case (None, Some(yaml)) =>
        FlowDefLoader.parseInline(yaml).flatMap {
          case Left(err) =>
            IO.pure(Left(ToolError(s"Invalid flow YAML: $err")))
          case Right(defn) =>
            mountAndTrigger(defn, triggerInput, ctx)
        }
    end match

  // ============================================================
  // Mount (if needed) + trigger
  // ============================================================

  private def mountAndTrigger(
    defn: FlowDef,
    triggerInput: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // Validate: Nebula cannot be used as a step agent
    val nebulaViolation = defn.nodes.find(_.agent.contains("Nebula"))
    if nebulaViolation.isDefined then
      return IO.pure(Left(ToolError(
        s"Nebula cannot be used as a step agent inside flows (node '${nebulaViolation.get.id}'). Use Explorer, Coder, or other specialized agents."
      )))

    getOrCreateTreeActor(ctx).flatMap { treeRef =>
      for
        // Mount the flow (idempotent — if already mounted with same name, will create unique name)
        _ <- treeRef ! TreeCommand.MountBranch(defn, None, None)
        // Trigger immediately
        _ <- treeRef ! TreeCommand.TriggerPipeline(defn.name, triggerInput, None)
        _ <- logger.info(s"ExecuteFlow: mounted and triggered '${defn.name}' with input (${triggerInput.length} chars)")
      yield Right(
        s"""Flow '${defn.name}' started in background.
You will be notified when it completes via a system message.
Do NOT duplicate this flow's work — avoid working on the same files or topics it covers."""
      )
    }

  // ============================================================
  // Find or create FlowTreeActor
  // ============================================================

  private def getOrCreateTreeActor(ctx: ToolContext): IO[ActorRef[TreeCommand]] =
    val sessionId = ctx.sessionId.getOrElse("default")

    FlowTreeRegistry.get(sessionId).flatMap {
      case Some(ref) => IO.pure(ref)
      case None =>
        (ctx.actorSystem, ctx.sharedResources, ctx.agentActorRef) match
          case (Some(system), Some(resources), Some(parentRef)) =>
            val safetyModeIO = ctx.sessionStore match
              case Some(store) => store.getSafetyMode(sessionId)
              case None => IO.pure("confirm-edits")

            safetyModeIO.flatMap { safetyMode =>
              val config = FlowTreeActor.TreeConfig(
                parentAgentRef = parentRef,
                wsSend = ctx.wsSend,
                sessionId = ctx.sessionId,
                resources = resources,
                projectRoot = ctx.projectRoot,
                safetyMode = safetyMode
              )
              for
                ref <- system.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")
                _ <- FlowTreeRegistry.register(sessionId, ref)
                _ <- logger.info(s"Created FlowTreeActor for session $sessionId")
              yield ref
            }
          case _ =>
            IO.raiseError(
              new RuntimeException("ExecuteFlow requires ActorSystem, SharedResources, and agentActorRef")
            )
    }

end ExecuteFlowTool
