package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.entity.{FlowDagDef, FlowStructure}

/**
 * FlowExecuteTool — executes a one-shot dynamic flow DAG (#406, v4).
 *
 * The flow definition is an EXECUTION PARAMETER, not a persisted artifact:
 * the agent passes the full DAG inline (nodes/entry/routes), the engine
 * validates it with the same rules as predefined flows, spawns a one-shot
 * FlowDagRunner, and the whole thing is gone when it finishes — no flows/
 * directory write, no MountedFlowStore entry, no file-watcher event.
 *
 * Complements the "predefined flow + FlowTrigger whitelist" model (user
 * ruling 2026-08-26: the two are complementary, not replacement) — FlowExecute
 * for one-shot ad-hoc orchestration, FlowTrigger for fixed pipelines.
 * Predefined flows stay as a first-class capability; nothing retires.
 *
 * Availability: mechanism-layer injected — Team members and Nebula get it
 * without any agent.json declaration (AgentCore.fixedToolsFor, same pattern
 * as SubTask #381). FlowExecute node agents are leaves and never see it.
 */
object FlowExecuteTool extends Tool:
  val name = "FlowExecute"

  /** Maximum flow depth (matches SubTaskTool.MaxDepth / DelegateTool). */
  val MaxDepth: Int = 5

  val description =
    """Execute a one-shot dynamic flow — you define the DAG inline, the engine runs it, done. No file, no registration, nothing persists.

Use when a task splits into MULTIPLE agents working in parallel (e.g. 64-page deck → 64 workers, one per page) or a multi-stage agent pipeline (research → review → summarize). For a single autonomous worker use Delegate/SubTask instead.

**Parameters:**
- prompt (required): self-contained task input for the flow's entry node.
- nodes (required): the DAG as a JSON object mapping node-id → node. Each node:
    { "agent": "<global agent name>", "input": "<template>", "onComplete": <route> }
  Optional per node: "onError": "stop"|"resume"|"restart", "maxRetries": N,
  "outputs": { "slotName": "string"|"array" } (structured slots for downstream).
  Route forms (onComplete):
    - string: "next-node" | "$return" (terminate with result)
    - switch:  { "switch": "<expr>", "cases": { "pass": "agg", "fail": "redo" }, "default": "agg" }
    - parallel fan: { "parallel": ["r1","r2","r3"], "onFail": "abort"|"collect" }
    - dynamic fan: { "parallel": { "slots": "<array slot>", "template": "<node-id>" }, "onFail": "abort"|"collect" }
      (N = min(array length, maxFanout) runtime instances template#1..#N; template
       node input supports {{item}} = array element, {{index}} = 1-based index)
- entry (required): the id of the entry node (first node executed).
- name (required): short display name for the run (shown in WS events / UI).
- description (required): what this flow does, one line.
- maxFanout (optional, default 4): the parallel scale THIS flow declares —
  the engine caps dynamic fans at it (N = min(len, maxFanout)). It is your
  declared parallelism for the task (64-page deck → 64), NOT a system limit.
- maxLoop (optional, default 10): loop protection bound.
- params (optional): structured parameters the flow declares; node inputs
  reference them via $params.<name>.

**Input template syntax:**
- $task            — the prompt passed to this call (entry node)
- $<nodeId>.output — a node's text output
- $<nodeId>.slots.<field> — a node's structured slot (string inserts directly;
  array inserts compact JSON)
- $<template>.all.output — all dynamic instances' outputs, index order,
  each under a "=== Track N ===" header
- $<template>.all.slots.<field> — all instances' slot values (array of values)
- $params.<name>   — declared parameter (missing → "[param <name> not provided]")

**FlowReport verdicts:** node agents report a structured verdict (pass/fail)
via the FlowReport tool — injected automatically for every flow node. Switch
nodes read the verdict through the switch expression (e.g. "$agg.verdict").
Without a verdict a switch node FAILS the flow (strictVerdict).

**Rules:**
- Prompt must be self-contained — every node agent starts with a clean context.
- Define each node's input so the agent knows exactly what to produce and
  what "done" looks like.
- Cost guidance: set maxFanout to the parallelism the task actually needs
  (Anthropic scaling: simple task → 1-2 workers, complex research → 10+);
  put a token budget in each worker's prompt ("produce ≤ N tokens"). There
  is NO system-wide concurrency limit — the engine does not throttle you.
- Do NOT nest flows inside flow nodes: flow nodes cannot call FlowExecute
  (leaf rule) — put the whole structure in one DAG instead.
- Do NOT duplicate the flow's work while it runs; the result is delivered
  to you as a system message when it completes ("[Flow '<name>' completed]").
- FlowTrigger vs FlowExecute (complementary, not transition): FlowTrigger
  runs FIXED predefined pipelines (flows/ definitions, whitelist-triggered,
  repeated same-shape use, discipline gates); FlowExecute runs ONE-SHOT
  ad-hoc orchestration (inline DAG, shape follows the task, parallelism
  scales with the task). Check for a matching predefined flow first — if
  one exists use FlowTrigger; only when none matches and the task needs
  multi-agent orchestration use FlowExecute."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "prompt" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Self-contained task input for the flow's entry node. Must include all context the pipeline needs.".asJson
        ),
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short display name for this flow run (shown in WS events / UI).".asJson
        ),
        "description" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "One-line description of what this flow does.".asJson
        ),
        "nodes" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "DAG nodes: map of node-id → {agent, input, onComplete, ...} — see tool description for the full DSL.".asJson,
          "additionalProperties" -> Json.True
        ),
        "entry" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Id of the entry node (first node executed).".asJson
        ),
        "maxFanout" -> Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Optional parallel scale this flow declares (default 4); dynamic fans are capped at it. Declared by you per task — not a system limit.".asJson
        ),
        "maxLoop" -> Json.obj(
          "type" -> "integer".asJson,
          "description" -> "Optional loop protection bound (default 10).".asJson
        ),
        "params" -> Json.obj(
          "type" -> "object".asJson,
          "description" -> "Optional structured parameters (type/default/min/max per key); node inputs reference them via $params.<name>.".asJson,
          "additionalProperties" -> Json.True
        )
      ),
      "required" -> Json.arr("prompt".asJson, "name".asJson, "description".asJson, "nodes".asJson, "entry".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val name = input("name").flatMap(_.asString).getOrElse("?")
    val nodeCount = input("nodes").flatMap(_.asObject).map(_.size).getOrElse(0)
    s"FlowExecute(→ $name, $nodeCount nodes)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    val flowJson = Json.obj(
      "name" -> input("name").getOrElse(Json.fromString("dynamic-flow")),
      "description" -> input("description").getOrElse(Json.fromString("")),
      "nodes" -> input("nodes").getOrElse(Json.fromJsonObject(JsonObject.empty)),
      "entry" -> input("entry").getOrElse(Json.Null),
      "maxLoop" -> input("maxLoop").getOrElse(Json.Null),
      "maxFanout" -> input("maxFanout").getOrElse(Json.Null),
      "params" -> input("params").getOrElse(Json.Null)
    )

    if prompt.trim.isEmpty then IO.pure(Left(ToolError("Missing required parameter: prompt")))
    else if input("entry").flatMap(_.asString).isEmpty then IO.pure(Left(ToolError("Missing required parameter: entry")))
    else if ctx.depth >= MaxDepth then
      IO.pure(Left(ToolError(s"Maximum flow depth ($MaxDepth) reached. Cannot execute a flow from this context.")))
    else
      // Decode the inline DAG with the same codec predefined flows use.
      flowJson.as[FlowDagDef] match
        case Left(err) =>
          IO.pure(Left(ToolError(s"Invalid flow definition: ${err.getMessage}")))
        case Right(flowDef) =>
          // 1. Structural validation — identical rules to predefined flows.
          val structureErrors = FlowStructure.validate(flowDef)
          if structureErrors.nonEmpty then
            IO.pure(Left(ToolError(s"Flow '${flowDef.name}' rejected:\n" + structureErrors.mkString("\n"))))
          else
            // 2. Agent-existence validation — dynamic flows resolve node agents
            // from the GLOBAL library only (never flows/<name>/agents/).
            nebflow.core.entity.EntityLoader.listAgents().flatMap { agents =>
              val agentErrors =
                nebflow.core.entity.EntityLoader.validateFlow(flowDef, agents.keySet)
              if agentErrors.nonEmpty then
                IO.pure(Left(ToolError(s"Flow '${flowDef.name}' rejected:\n" + agentErrors.mkString("\n"))))
              else
                // 3. Spawn the one-shot runner (dynamic=true: inline instance id,
                // global-only agent resolution, no flows/ dir write).
                (ctx.sharedResources, ctx.actorSystem, ctx.agentActorRef) match
                  case (Some(resources), Some(sys), Some(callerRef)) =>
                    for
                      // Resolve the caller's root session so flow nodes inherit
                      // the same permission policy and render interactions in
                      // the caller's window (mirrors FlowTriggerTool).
                      callerRoot <- ctx.sessionId match
                        case Some(sid) =>
                          resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
                        case None => IO.pure("")
                      runnerRef <- sys.spawn(
                        nebflow.core.flow.FlowDagRunner(resources, ctx.wsSend),
                        s"dag-inline-${java.util.UUID.randomUUID().toString.take(8)}"
                      )
                      _ <- (runnerRef ! nebflow.core.flow.FlowDagRunner.RunFlow(
                        flowDef,
                        prompt,
                        callerRef,
                        callerRoot,
                        Map.empty,
                        dynamic = true,
                        callerSessionId = ctx.sessionId.getOrElse("")
                      )).void
                    yield Right(s"Flow '${flowDef.name}' started (dynamic). Result will be delivered when complete.")
                  case _ =>
                    IO.pure(Left(ToolError("Cannot start flow: missing resources")))
            }
    end if
  end call

end FlowExecuteTool
