package nebflow.core.entity

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.given
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, FlowReportStore, ReadTracker}
import nebflow.shared.{Message, MessageRole}

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
    instanceId: String
  ): IO[Either[String, String]] =

    val emitWs = wsSend.getOrElse((_: Json) => IO.unit)

    /** Emit a flowProgress WS event. */
    def emitProgress(nodeId: String, status: String, extra: (String, Json)*): IO[Unit] =
      val fields = List(
        "type" -> "flowProgress".asJson,
        "instanceId" -> instanceId.asJson,
        "flowName" -> flow.name.asJson,
        "nodeId" -> nodeId.asJson,
        "status" -> status.asJson
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
                val agentDef = AgentDef(
                  name = entry.name,
                  description = entry.description,
                  tools = entry.tools,
                  systemPrompt = entry.systemPrompt,
                  voiceEnabled = entry.voice,
                  category = entry.category,
                  mcpServers = entry.mcpServers
                )
                executeAgent(nodeId, node.agent, agentDef, inputText, resources, actorSystem, wsSend, flow.name)
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
            case NodeRoute.Switch(switchExpr, cases) =>
              val fieldValue = result.verdict.getOrElse {
                extractSwitchValue(switchExpr, result.output, ctx, cases)
              }
              cases.get(fieldValue) match
                case Some(NodeRoute.Return) =>
                  IO.pure(Right(result.output))
                case Some(NodeRoute.Goto(target)) =>
                  val loopKey = s"${result.nodeId}->$target"
                  val newCount = ctx.loopCounts.getOrElse(loopKey, 0) + 1
                  if newCount > flow.maxLoop then IO.pure(Left(s"Max loop (${flow.maxLoop}) exceeded at edge $loopKey"))
                  else
                    val newCtx = ctx.copy(
                      loopCounts = ctx.loopCounts + (loopKey -> newCount),
                      totalLoops = ctx.totalLoops + 1
                    )
                    runNode(target, newCtx)
                case Some(NodeRoute.Switch(_, _)) =>
                  IO.pure(Left(s"Nested switch not supported in routing"))
                case None =>
                  IO.pure(Left(s"Switch '$switchExpr' value '$fieldValue' matched no case"))
              end match

    /** Run a node: execute -> handleResult -> route. */
    def runNode(nodeId: String, ctx: FlowExecContext): IO[Either[String, String]] =
      for
        _ <- logger.info(s"Flow '${flow.name}': executing node '$nodeId'")
        _ <- nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, "running")
        _ <- emitProgress(nodeId, "running")
        (result, ctx2) <- executeNode(nodeId, ctx)
        _ <-
          if result.success then
            nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, "completed", result.output) *>
              emitProgress(
                nodeId,
                "completed",
                "output" -> (if result.output.length > 200 then result.output.take(197) + "..."
                             else result.output).asJson
              )
          else
            nebflow.core.flow.RunningFlowRegistry.setNodeStatus(
              instanceId,
              nodeId,
              "failed",
              "",
              result.error.getOrElse("unknown")
            ) *>
              emitProgress(nodeId, "failed", "error" -> result.error.getOrElse("unknown").asJson)
        handled <- handleResult(nodeId, result, ctx2)
        finalResult <- handled match
          case Left(err) => IO.pure(Left(err))
          case Right((nr, ctx3)) => route(nr, ctx3)
      yield finalResult

    // Register the running flow, execute, then clean up
    for
      _ <- registerFlow(instanceId, flow)
      result <- runNode(flow.entry, FlowExecContext(flow.name, taskInput))
      _ <- nebflow.core.flow.RunningFlowRegistry.update(instanceId)(rf =>
        rf.copy(
          status = if result.isRight then "completed" else "failed",
          completedAt = Some(System.currentTimeMillis())
        )
      )
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
        status = "pending"
      )
    }.toMap

    // Build edges from onComplete routing
    val edges = flow.nodes.toList.flatMap { (nodeId, node) =>
      node.onComplete match
        case NodeRoute.Goto(target) => List((nodeId, target, None))
        case NodeRoute.Return => List((nodeId, "$return", None))
        case NodeRoute.Switch(_, cases) =>
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
        status = "running",
        startedAt = System.currentTimeMillis()
      )
    )

  end registerFlow

  /**
   * Spawn an AgentActor, send input, wait for completion via event-driven callback (no timeout).
   *
   *  Uses a Deferred + bridge actor pattern instead of ask-with-timeout:
   *  - A temporary actor receives the AgentEvent (Completed/Failed) from the agent
   *  - The bridge completes a Deferred, unblocking executeAgent
   *  - No artificial timeout — the agent runs as long as needed
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
    flowName: String
  ): IO[NodeResult] =
    val rawWsSend = wsSend.getOrElse((_: Json) => IO.unit)
    val sessionId = s"dag-${flowName.take(10)}-$nodeId-${System.currentTimeMillis().toString.takeRight(6)}"
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
          wsSend = rawWsSend,
          depth = 1,
          parentRef = None,
          sessionId = Some(sessionId),
          sessionName = Some(s"$flowName/$nodeId"),
          initialMessages = Nil,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          expectsMail = false
        ),
        s"dagnode-${nodeId.take(10)}-${sessionId.take(8)}"
      )
      // Send input with the bridge actor as replyTo
      _ <- (ref ! AgentCommand.UserInput(
        text = inputText,
        replyTo = Some(bridgeRef)
      )).void
      // Wait for completion — no timeout, event-driven
      eventResult <- resultDeferred.get
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
   *  -> fallback: scan output for keyword matching case keys
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
        // Try JSON parsing
        io.circe.parser
          .parse(output)
          .toOption
          .flatMap(_.hcursor.downField(field).as[String].toOption)
          .getOrElse {
            // Fallback: match against case keys (case-insensitive contains)
            cases.keys
              .find(k => output.toLowerCase.contains(k.toLowerCase))
              .getOrElse("unknown")
          }
      case _ => "unknown"
    end match
  end extractSwitchValue

end FlowDagExecutor
