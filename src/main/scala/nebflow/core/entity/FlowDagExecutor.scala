package nebflow.core.entity

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.given
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.flow.{NodeStatus, VerdictFamily}
import nebflow.core.presets.PresetStore
import nebflow.core.tools.{FileHistory, FlowReportStore, ReadTracker}
import nebflow.shared.{Message, MessageRole}

import scala.concurrent.duration.*

/**
 * Executes a Flow DAG deterministically.
 *
 *  Unlike the Mail-driven flow model where agents coordinate emergently,
 *  the DAG executor follows explicit routing rules defined in flow.json.
 *  Each node runs its agent, produces output, and the executor routes to the
 *  next node based on onComplete rules.
 *
 *  Error handling follows Actor Supervision model:
 *  - resume: log warning, continue routing
 *  - restart: re-execute node (up to maxRetries)
 *  - stop: abort flow, return error
 */
object FlowDagExecutor:
  private val logger = NebflowLogger.forName("nebflow.entity.executor")

  /** Per-node timeout: a single agent node may run up to this long. */
  private val NodeTimeout: FiniteDuration = 5.minutes

  /** Global flow timeout: the entire DAG must complete within this limit. */
  private val GlobalFlowTimeout: FiniteDuration = 30.minutes

  /**
   * Execute a flow DAG.
   *
   *  @param flow The DAG definition
   *  @param taskInput The initial task string (replaces "$task" in entry node input)
   *  @param resources Shared resources (for agent activation)
   *  @param actorSystem Actor system (for spawning agent actors)
   *  @param wsSend WebSocket send (for streaming output)
   *  @return Either error message or final output string
   */
  def execute(
    flow: FlowDagDef,
    taskInput: String,
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[Json => IO[Unit]],
    instanceId: String,
    parentAgentRef: Option[ActorRef[AgentCommand]] = None,
    rootSessionId: String = ""
  ): IO[Either[String, String]] =

    val emitWs = wsSend.getOrElse((_: Json) => IO.unit)

    /** Emit a flowProgress WS event. Status serializes via NodeStatus.wire (stable wire values). */
    def emitProgress(nodeId: String, status: NodeStatus, extra: (String, Json)*): IO[Unit] =
      val fields = List(
        "type" -> "flowProgress".asJson,
        "instanceId" -> instanceId.asJson,
        "flowName" -> flow.name.asJson,
        "nodeId" -> nodeId.asJson,
        "status" -> status.wire.asJson
      ) ++ extra
      emitWs(Json.obj(fields*))

    /**
     * Resolve template variables: $task -> task input, $<nodeId>.output -> node output.
     *  Uses quoteReplacement to prevent $ and \ in agent output from being
     *  interpreted as regex group references (Illegal group reference error).
     */
    def resolveInput(template: String, ctx: FlowExecContext): String =
      val withTask = template.replace("$task", ctx.taskInput)
      val pattern = "\\$([a-zA-Z0-9_-]+)\\.output".r
      pattern.replaceAllIn(
        withTask,
        m =>
          java.util.regex.Matcher.quoteReplacement(
            ctx.nodeOutputs.getOrElse(m.group(1), s"[output of ${m.group(1)} not found]")
          )
      )

    /** Execute a single DAG node: spawn agent, send input, collect output. */
    def executeNode(
      nodeId: String,
      ctx: FlowExecContext
    ): IO[(NodeResult, FlowExecContext)] =
      flow.nodes.get(nodeId) match
        case None =>
          IO.pure((NodeResult(nodeId, "", false, Some(s"Unknown node: $nodeId")), ctx))
        case Some(node) =>
          val inputText = resolveInput(node.input, ctx)
          for
            agentEntryOpt <- nebflow.core.entity.EntityLoader.loadFlowAgent(flow.name, node.agent)
            result <- agentEntryOpt match
              case None =>
                IO.pure(
                  NodeResult(nodeId, "", false, Some(s"Agent '${node.agent}' not found in flow or global library"))
                )
              case Some(entry) =>
                val agentDef =
                  val (resolvedModel, _) = PresetStore().resolve(entry.preset, entry.model)
                  AgentDef(
                    name = entry.name,
                    description = entry.description,
                    tools = entry.tools,
                    systemPrompt = entry.systemPrompt,
                    voiceEnabled = entry.voice,
                    category = entry.category,
                    mcpServers = entry.mcpServers,
                    model = Some(resolvedModel),
                    preset = entry.preset
                  )
                executeAgent(
                  nodeId,
                  node.agent,
                  agentDef,
                  inputText,
                  resources,
                  actorSystem,
                  wsSend,
                  flow.name,
                  parentAgentRef,
                  rootSessionId
                )
            updatedCtx = ctx.copy(nodeOutputs = ctx.nodeOutputs + (nodeId -> result.output))
          yield (result, updatedCtx)
          end for

    /** Error handling + retry logic. */
    def handleResult(
      nodeId: String,
      result: NodeResult,
      ctx: FlowExecContext
    ): IO[Either[String, (NodeResult, FlowExecContext)]] =
      if result.success then IO.pure(Right((result, ctx)))
      else
        flow.nodes.get(nodeId) match
          case None => IO.pure(Left(s"Unknown node '$nodeId' in error handler"))
          case Some(node) =>
            node.onError.getOrElse(OnError.Stop) match
              case OnError.Resume =>
                for _ <- logger.warn(s"Node '$nodeId' failed, resuming")
                yield Right((result, ctx))
              case OnError.Restart =>
                val retryCount = result.attemptCount
                if retryCount <= node.maxRetries then
                  for
                    _ <- logger.info(s"Node '$nodeId' retry ${retryCount}/${node.maxRetries}")
                    (newResult, newCtx) <- executeNode(nodeId, ctx)
                    handled <- handleResult(nodeId, newResult.copy(attemptCount = retryCount + 1), newCtx)
                  yield handled
                else
                  IO.pure(
                    Left(s"Node '$nodeId' failed after $retryCount retries: ${result.error.getOrElse("unknown")}")
                  )
              case OnError.Stop =>
                IO.pure(Left(s"Node '$nodeId' failed: ${result.error.getOrElse("unknown")}"))

    /** Route to next node based on onComplete rules. */
    def route(
      result: NodeResult,
      ctx: FlowExecContext
    ): IO[Either[String, String]] =
      flow.nodes.get(result.nodeId) match
        case None => IO.pure(Left(s"Unknown node '${result.nodeId}' in routing"))
        case Some(node) =>
          node.onComplete match
            case NodeRoute.Return =>
              IO.pure(Right(result.output))
            case NodeRoute.Goto(target) =>
              val loopKey = s"${result.nodeId}->$target"
              val newCount = ctx.loopCounts.getOrElse(loopKey, 0) + 1
              if newCount > flow.maxLoop then IO.pure(Left(s"Max loop (${flow.maxLoop}) exceeded at edge $loopKey"))
              else
                val newCtx = ctx.copy(
                  loopCounts = ctx.loopCounts + (loopKey -> newCount),
                  totalLoops = ctx.totalLoops + 1
                )
                runNode(target, newCtx)
            case NodeRoute.Switch(switchExpr, cases, default) =>
              // Verdict priority: FlowReport verdict (structured, trustworthy) →
              // extract from output via the five-stage pipeline.
              val rawValue = result.verdict.getOrElse {
                extractSwitchValue(switchExpr, result.output, ctx, cases)
              }
              // Normalize: exact → same-family → substring (warn). flow.json
              // declares only standard keys; aliases (pass/success/done → ok,
              // fail/error → error) are the engine's responsibility.
              val matchedKey = VerdictFamily.matchCase(rawValue, cases.keySet)
              def routeTo(route: NodeRoute): IO[Either[String, String]] =
                route match
                  case NodeRoute.Return =>
                    IO.pure(Right(result.output))
                  case NodeRoute.Goto(target) =>
                    val loopKey = s"${result.nodeId}->$target"
                    val newCount = ctx.loopCounts.getOrElse(loopKey, 0) + 1
                    if newCount > flow.maxLoop then
                      IO.pure(Left(s"Max loop (${flow.maxLoop}) exceeded at edge $loopKey"))
                    else
                      val newCtx = ctx.copy(
                        loopCounts = ctx.loopCounts + (loopKey -> newCount),
                        totalLoops = ctx.totalLoops + 1
                      )
                      runNode(target, newCtx)
                  case NodeRoute.Switch(_, _, _) =>
                    IO.pure(Left(s"Nested switch not supported in routing"))
              matchedKey match
                case Some(key) => routeTo(cases(key))
                case None =>
                  // No case matched — conservative `default` route, else a clear
                  // error listing the available cases (replaces bare "matched no case").
                  default match
                    case Some(route) => routeTo(route)
                    case None =>
                      IO.pure(
                        Left(
                          s"Switch '$switchExpr' value '$rawValue' matched no case. " +
                            s"Available cases: ${cases.keys.toList.sorted.mkString(", ")}"
                        )
                      )
              end match

    /** Run a node: execute -> handleResult -> route. */
    def runNode(nodeId: String, ctx: FlowExecContext): IO[Either[String, String]] =
      for
        // Check cancellation before executing each node
        cancelled <- nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId)
        result <-
          if cancelled then IO.pure(Left[String, String]("Flow cancelled by user"))
          else
            for
              _ <- logger.info(s"Flow '${flow.name}': executing node '$nodeId'")
              _ <- nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, NodeStatus.Running)
              _ <- emitProgress(nodeId, NodeStatus.Running)
              (result, ctx2) <- executeNode(nodeId, ctx)
              status = NodeStatus.toNodeStatus(result, cancelled = false)
              _ <-
                if status == NodeStatus.Completed then
                  nebflow.core.flow.RunningFlowRegistry.setNodeStatus(
                    instanceId,
                    nodeId,
                    NodeStatus.Completed,
                    result.output
                  ) *>
                    emitProgress(
                      nodeId,
                      NodeStatus.Completed,
                      "output" -> (if result.output.length > 200 then result.output.take(197) + "..."
                                   else result.output).asJson
                    )
                else
                  nebflow.core.flow.RunningFlowRegistry.setNodeStatus(
                    instanceId,
                    nodeId,
                    NodeStatus.Failed,
                    "",
                    result.error.getOrElse("unknown")
                  ) *>
                    emitProgress(nodeId, NodeStatus.Failed, "error" -> result.error.getOrElse("unknown").asJson)
              handled <- handleResult(nodeId, result, ctx2)
              finalResult <- handled match
                case Left(err) => IO.pure(Left(err))
                case Right((nr, ctx3)) => route(nr, ctx3)
            yield finalResult
      yield result

    // Register the running flow, execute, then clean up
    for
      _ <- registerFlow(instanceId, flow)
      // Emit flowStarted so the frontend can render the full DAG immediately
      _ <- emitWs(
        Json.obj(
          "type" -> "flowStarted".asJson,
          "instanceId" -> instanceId.asJson,
          "flowName" -> flow.name.asJson,
          "description" -> flow.description.asJson,
          "entry" -> flow.entry.asJson,
          "nodes" -> flow.nodes.toList
            .sortBy(_._1)
            .map { (nodeId, node) =>
              Json.obj(
                "nodeId" -> nodeId.asJson,
                "agent" -> node.agent.asJson,
                "status" -> "pending".asJson
              )
            }
            .asJson,
          "edges" -> flow.nodes.toList.flatMap { (nodeId, node) =>
            node.onComplete match
              case NodeRoute.Goto(target) =>
                List(Json.obj("from" -> nodeId.asJson, "to" -> target.asJson, "condition" -> Json.Null))
              case NodeRoute.Return =>
                List(Json.obj("from" -> nodeId.asJson, "to" -> "$return".asJson, "condition" -> Json.Null))
              case NodeRoute.Switch(_, cases, _) =>
                cases.toList.map { (cond, route) =>
                  val target = route match
                    case NodeRoute.Goto(t) => t
                    case NodeRoute.Return => "$return"
                    case _ => "?"
                  Json.obj("from" -> nodeId.asJson, "to" -> target.asJson, "condition" -> cond.asJson)
                }
          }.asJson
        )
      )
      raceResult <- IO.race(
        runNode(flow.entry, FlowExecContext(flow.name, taskInput)),
        IO.sleep(GlobalFlowTimeout)
      )
      result = raceResult match
        case Left(r) => r
        case Right(_) =>
          logger.warn(s"Flow '${flow.name}' timed out after ${GlobalFlowTimeout.toMinutes} minutes")
          Left[String, String](
            s"Flow '${flow.name}' timed out after ${GlobalFlowTimeout.toMinutes} minutes"
          )
      // Update final status (preserve "cancelled" if it was cancelled)
      cancelled <- nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId)
      _ <-
        if cancelled then IO.unit
        else
          nebflow.core.flow.RunningFlowRegistry.update(instanceId)(rf =>
            rf.copy(
              status = if result.isRight then NodeStatus.Completed else NodeStatus.Failed,
              completedAt = Some(System.currentTimeMillis())
            )
          )
      _ <- nebflow.core.flow.RunningFlowRegistry.clearCancelled(instanceId)
      _ <- emitWs(
        Json.obj(
          "type" -> "flowCompleted".asJson,
          "instanceId" -> instanceId.asJson,
          "flowName" -> flow.name.asJson,
          "success" -> result.isRight.asJson
        )
      )
    yield result
    end for

  end execute

  /** Register a running flow instance from its DAG definition. */
  private def registerFlow(instanceId: String, flow: FlowDagDef): IO[Unit] =
    val nodes = flow.nodes.map { (nodeId, node) =>
      nodeId -> nebflow.core.flow.RunningFlowRegistry.NodeState(
        nodeId = nodeId,
        agent = node.agent,
        status = NodeStatus.Pending
      )
    }.toMap

    // Build edges from onComplete routing
    val edges = flow.nodes.toList.flatMap { (nodeId, node) =>
      node.onComplete match
        case NodeRoute.Goto(target) => List((nodeId, target, None))
        case NodeRoute.Return => List((nodeId, "$return", None))
        case NodeRoute.Switch(_, cases, _) =>
          cases.toList.map { (cond, route) =>
            val target = route match
              case NodeRoute.Goto(t) => t
              case NodeRoute.Return => "$return"
              case _ => "?"
            (nodeId, target, Some(cond))
          }
    }

    nebflow.core.flow.RunningFlowRegistry.register(
      nebflow.core.flow.RunningFlowRegistry.RunningFlow(
        instanceId = instanceId,
        flowName = flow.name,
        description = flow.description,
        entry = flow.entry,
        nodes = nodes,
        edges = edges,
        status = NodeStatus.Running,
        startedAt = System.currentTimeMillis()
      )
    )

  end registerFlow

  /**
   * Spawn an AgentActor, send input, wait for completion via event-driven callback.
   *
   *  Uses a Deferred + bridge actor pattern instead of ask-with-timeout:
   *  - A temporary actor receives the AgentEvent (Completed/Failed) from the agent
   *  - The bridge completes a Deferred, unblocking executeAgent
   *  - Node-level timeout (5 min): if the agent never finishes (e.g. a blocking
   *    tool call), the node fails with a timeout error instead of stalling the
   *    flow forever
   *  - LLM-level timeouts (first-token, response) still apply inside the agent
   */
  private def executeAgent(
    nodeId: String,
    agentName: String,
    agentDef: AgentDef,
    inputText: String,
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[Json => IO[Unit]],
    flowName: String,
    parentAgentRef: Option[ActorRef[AgentCommand]] = None,
    rootSessionId: String = ""
  ): IO[NodeResult] =
    val rawWsSend = wsSend.getOrElse((_: Json) => IO.unit)
    val sessionId = s"dag-${flowName.take(10)}-$nodeId-${System.currentTimeMillis().toString.takeRight(6)}"
    // P2: flow nodes inherit the triggering agent's root session so their
    // InteractionRequests render in the Nebula window and share its policy.
    val effectiveRootSessionId = if rootSessionId.nonEmpty then rootSessionId else sessionId
    // Route flow agent events with nodeSessionId = this flow node's session id.
    // Injected only when absent: AgentStreamEvent.toJson already stamps
    // nodeSessionId for subagent events (protocol.scala withNodeSession), and
    // an unconditional merge would clobber the nodeSessionId of a Delegate
    // sub-agent spawned inside this flow agent (which must keep its
    // "delegate-*" prefix so the frontend routes it to the delegate popup).
    val routedWsSend: Json => IO[Unit] = (json: Json) =>
      val withNode =
        if json.hcursor.downField("nodeSessionId").as[String].isRight then json
        else json.deepMerge(Json.obj("nodeSessionId" -> sessionId.asJson))
      rawWsSend(withNode)
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      resultDeferred <- Deferred[IO, Either[String, List[Message]]]
      // Bridge actor: receives AgentEvent, completes the Deferred, self-stops
      bridgeRef <- actorSystem.spawn(
        Behaviors.receive[AgentEvent] { (ctx, event) =>
          event match
            case AgentEvent.Completed(_, messages) =>
              ctx.forkTurn(resultDeferred.complete(Right(messages)).void).as(Behaviors.stopped)
            case AgentEvent.Failed(_, err) =>
              ctx.forkTurn(resultDeferred.complete(Left(err.message)).void).as(Behaviors.stopped)
        },
        s"bridge-${nodeId.take(10)}-${sessionId.take(8)}"
      )
      ref <- actorSystem.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = routedWsSend,
          depth = 1,
          parentRef = parentAgentRef,
          sessionId = Some(sessionId),
          sessionName = Some(s"$flowName/$nodeId"),
          initialMessages = Nil,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          expectsMail = false,
          rootSessionId = effectiveRootSessionId
        ),
        s"dagnode-${nodeId.take(10)}-${sessionId.take(8)}"
      )
      // P1: register flow node agents in the unified AgentRegistry — this was
      // previously missing entirely, so permission answers routed to a ghost
      // root agent and were silently dropped (D2 for dag-* sessions).
      _ <- resources.agentRegistry.update(
        _ + (
          sessionId -> AgentRecord(sessionId, ref, AgentKind.Flow, effectiveRootSessionId, parentAgentRef)
        )
      )
      // Send input with the bridge actor as replyTo
      _ <- (ref ! AgentCommand.UserInput(
        text = inputText,
        replyTo = Some(bridgeRef)
      )).void
      // Wait for completion — with node-level timeout to prevent permanent stalls
      raceResult <- IO.race(resultDeferred.get, IO.sleep(NodeTimeout))
      eventResult = raceResult match
        case Left(r) => r
        case Right(_) =>
          logger.warn(s"Node '$nodeId' timed out after ${NodeTimeout.toMinutes} minutes")
          Left(s"Node '$nodeId' timed out after ${NodeTimeout.toMinutes} minutes")
      _ <- resources.agentRegistry.update(_ - sessionId)
      _ <- actorSystem.stop(ref).handleErrorWith(_ => IO.unit)
      _ <- actorSystem.stop(bridgeRef).handleErrorWith(_ => IO.unit)
      // Read FlowReport if the agent called FlowReport, then clean up
      reportOpt <- FlowReportStore.get(sessionId)
      _ <- FlowReportStore.remove(sessionId)
      _ <- resources.sessionStore.deleteSession(sessionId).handleErrorWith(_ => IO.unit)
      nodeResult <- eventResult match
        case Right(messages) =>
          val textOutput = extractLastAssistantOutput(messages)
          reportOpt match
            case Some((verdict, reportOutput)) =>
              IO.pure(
                NodeResult(
                  nodeId = nodeId,
                  output = if reportOutput.nonEmpty then reportOutput else textOutput,
                  success = true,
                  verdict = Some(verdict)
                )
              )
            case None =>
              // Backward compat: agent didn't call FlowReport, use text output
              IO.pure(
                NodeResult(
                  nodeId = nodeId,
                  output = textOutput,
                  success = true
                )
              )
          end match
        case Left(errMsg) =>
          IO.pure(
            NodeResult(
              nodeId = nodeId,
              output = "",
              success = false,
              error = Some(s"Agent '$agentName' failed: $errMsg")
            )
          )
    yield nodeResult

    end for

  end executeAgent

  /** Extract the text content of the last assistant message. */
  private def extractLastAssistantOutput(messages: List[Message]): String =
    messages.reverse.find(_.role == MessageRole.Assistant) match
      case Some(msg) =>
        msg.content match
          case Left(text) => text
          case Right(blocks) =>
            blocks
              .collect { case nebflow.shared.ContentBlock.Text(t) =>
                t
              }
              .mkString("\n")
      case None => ""

  /**
   * Extract a field value from node output for switch routing.
   *  switchExpr format: "$reviewer.verdict"
   *  -> look up reviewer's output, try to parse as JSON and extract "verdict" field
   *  -> fallback: text regex `(?i)\b(?:verdict|status)\s*[:：]\s*(\w+)` (covers
   *     plain-text "VERDICT: pass" output, e.g. entity-creator)
   *  -> fallback: case-insensitive substring containment against case keys (warn)
   *  -> "unknown" (the route layer then applies default / explicit error)
   *
   *  FlowReport verdict (when the agent called the tool) is the stage-1 source,
   *  already consumed by route() before this pipeline runs.
   */
  private def extractSwitchValue(
    switchExpr: String,
    currentNodeOutput: String,
    ctx: FlowExecContext,
    cases: Map[String, NodeRoute]
  ): String =
    val pattern = "\\$([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_-]+)".r
    switchExpr match
      case pattern(nodeId, field) =>
        val output = ctx.nodeOutputs.getOrElse(nodeId, currentNodeOutput)
        // ① JSON field extraction
        val fromJson = io.circe.parser
          .parse(output)
          .toOption
          .flatMap(_.hcursor.downField(field).as[String].toOption)
          .map(_.trim)
          .filter(_.nonEmpty)
        fromJson.getOrElse {
          // ② Text regex: "VERDICT: pass" / "status: ok"
          val regex = "(?i)\\b(?:verdict|status)\\s*[:：]\\s*(\\w+)".r
          val fromRegex = regex.findFirstMatchIn(output).map(_.group(1))
          fromRegex.getOrElse {
            // ③ Case-insensitive substring containment (legacy fallback, warns)
            cases.keys.find(k => output.toLowerCase.contains(k.toLowerCase)) match
              case Some(hit) =>
                logger.warnSync(
                  s"Switch '$switchExpr': matched case '$hit' by substring containment (legacy fallback — prefer JSON/FlowReport verdicts)"
                )
                hit
              case None => "unknown"
          }
        }
      case _ => "unknown"
    end match
  end extractSwitchValue

end FlowDagExecutor
