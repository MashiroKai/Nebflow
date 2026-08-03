package nebflow.core.entity

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.given
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, ReadTracker}
import nebflow.shared.{Message, MessageRole}

/** Executes a Flow DAG deterministically.
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

  /** Node execution timeout (for LLM response). */
  private val nodeTimeout = scala.concurrent.duration.DurationInt(5).minutes

  /** Execute a flow DAG.
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

    /** Resolve template variables: $task -> task input, $<nodeId>.output -> node output. */
    def resolveInput(template: String, ctx: FlowExecContext): String =
      val withTask = template.replace("$task", ctx.taskInput)
      val pattern = "\\$([a-zA-Z0-9_-]+)\\.output".r
      pattern.replaceAllIn(withTask, m =>
        ctx.nodeOutputs.getOrElse(m.group(1), s"[output of ${m.group(1)} not found]")
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
            agentDefOpt <- resources.agentLibrary.get(node.agent)
            result <- agentDefOpt match
              case None =>
                IO.pure(NodeResult(nodeId, "", false,
                  Some(s"Agent '${node.agent}' not found in library")))
              case Some(agentDef) =>
                executeAgent(nodeId, node.agent, agentDef, inputText,
                  resources, actorSystem, wsSend, flow.name)
            updatedCtx = ctx.copy(nodeOutputs = ctx.nodeOutputs + (nodeId -> result.output))
          yield (result, updatedCtx)

    /** Error handling + retry logic. */
    def handleResult(
      nodeId: String,
      result: NodeResult,
      ctx: FlowExecContext
    ): IO[Either[String, (NodeResult, FlowExecContext)]] =
      if result.success then
        IO.pure(Right((result, ctx)))
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
                    handled <- handleResult(nodeId,
                      newResult.copy(attemptCount = retryCount + 1), newCtx)
                  yield handled
                else
                  IO.pure(Left(s"Node '$nodeId' failed after $retryCount retries: ${result.error.getOrElse("unknown")}"))
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
              if newCount > flow.maxLoop then
                IO.pure(Left(s"Max loop (${flow.maxLoop}) exceeded at edge $loopKey"))
              else
                val newCtx = ctx.copy(
                  loopCounts = ctx.loopCounts + (loopKey -> newCount),
                  totalLoops = ctx.totalLoops + 1
                )
                runNode(target, newCtx)
            case NodeRoute.Switch(switchExpr, cases) =>
              val fieldValue = extractSwitchValue(switchExpr, result.output, ctx, cases)
              cases.get(fieldValue) match
                case Some(NodeRoute.Return) =>
                  IO.pure(Right(result.output))
                case Some(NodeRoute.Goto(target)) =>
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
                case Some(NodeRoute.Switch(_, _)) =>
                  IO.pure(Left(s"Nested switch not supported in routing"))
                case None =>
                  IO.pure(Left(s"Switch '$switchExpr' value '$fieldValue' matched no case"))

    /** Run a node: execute -> handleResult -> route. */
    def runNode(nodeId: String, ctx: FlowExecContext): IO[Either[String, String]] =
      for
        _ <- logger.info(s"Flow '${flow.name}': executing node '$nodeId'")
        _ <- nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, "running")
        _ <- emitProgress(nodeId, "running")
        (result, ctx2) <- executeNode(nodeId, ctx)
        _ <- if result.success then
          nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, "completed", result.output) *>
          emitProgress(nodeId, "completed", "output" -> (if result.output.length > 200 then result.output.take(197) + "..." else result.output).asJson)
        else
          nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, "failed", "", result.error.getOrElse("unknown")) *>
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
        rf.copy(status = if result.isRight then "completed" else "failed", completedAt = Some(System.currentTimeMillis())))
      _ <- emitWs(Json.obj(
        "type" -> "flowCompleted".asJson,
        "instanceId" -> instanceId.asJson,
        "flowName" -> flow.name.asJson,
        "success" -> result.isRight.asJson
      ))
    yield result

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

  /** Spawn an AgentActor, send input, wait for completion, extract output. */
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
      eventResult <- (ref ? (
        (replyTo: ActorRef[AgentEvent]) => AgentCommand.UserInput(
          text = inputText,
          replyTo = Some(replyTo)
        ),
        timeout = Some(nodeTimeout)
      )).attempt
      _ <- actorSystem.stop(ref)
      _ <- resources.sessionStore.deleteSession(sessionId).handleErrorWith(_ => IO.unit)
      nodeResult <- eventResult match
        case Right(AgentEvent.Completed(_, messages)) =>
          IO.pure(NodeResult(
            nodeId = nodeId,
            output = extractLastAssistantOutput(messages),
            success = true
          ))
        case Right(AgentEvent.Failed(_, err)) =>
          IO.pure(NodeResult(
            nodeId = nodeId,
            output = "",
            success = false,
            error = Some(s"Agent '$agentName' failed: ${err.message}")
          ))
        case Left(e) =>
          IO.pure(NodeResult(
            nodeId = nodeId,
            output = "",
            success = false,
            error = Some(s"Agent '$agentName' timed out or errored: ${e.getMessage}")
          ))
    yield nodeResult

  /** Extract the text content of the last assistant message. */
  private def extractLastAssistantOutput(messages: List[Message]): String =
    messages.reverse.find(_.role == MessageRole.Assistant) match
      case Some(msg) =>
        msg.content match
          case Left(text) => text
          case Right(blocks) =>
            blocks.collect {
              case nebflow.shared.ContentBlock.Text(t) => t
            }.mkString("\n")
      case None => ""

  /** Extract a field value from node output for switch routing.
   *  switchExpr format: "$reviewer.verdict"
   *  -> look up reviewer's output, try to parse as JSON and extract "verdict" field
   *  -> fallback: scan output for keyword matching case keys */
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
        io.circe.parser.parse(output).toOption
          .flatMap(_.hcursor.downField(field).as[String].toOption)
          .getOrElse {
            // Fallback: match against case keys (case-insensitive contains)
            cases.keys.find(k => output.toLowerCase.contains(k.toLowerCase))
              .getOrElse("unknown")
          }
      case _ => "unknown"

end FlowDagExecutor
