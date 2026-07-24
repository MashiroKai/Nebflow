package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, ReadTracker}
import nebflow.shared.{LlmRequest, Message, MessageRole, UiMessage}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

// ============================================================
// PipelineActor — independent actor for pipeline execution
// ============================================================

object PipelineActor:
  private val logger = NebflowLogger.forName("nebflow.flow.pipeline")

  private val ManagerId = "__manager__"

  private val ManagerPromptPreamble =
    """You are the manager of this workflow. All steps have completed. Review the results below and provide a concise summary of what was accomplished, any issues encountered, and the overall outcome.

--- Step Results ---
""".stripMargin

  /** System prefix injected into EVERY flow agent's prompt.
   *  Tells agents the working principles of operating inside a flow pipeline.
   *  Without this, agents don't know they need Mail, shouldn't use background
   *  tasks, or that their output is auto-forwarded. */
  val FlowAgentPrefix =
    """=== Flow Agent Guidelines ===
You are a step in a flow pipeline. Follow these rules strictly:

1. OUTPUT FORWARDING: Your response text is automatically captured and passed to downstream steps. Write a clear, complete summary of what you did and found.

2. MAIL IS MANDATORY: If peer agents are listed in the "Flow Communication" section below, you MUST use Mail to send your key findings to relevant peers before finishing. If you finish without calling Mail, your turn will be rejected and you will be asked to retry.

3. NO BACKGROUND TASKS: Do NOT use run_in_background for any command. All commands must complete synchronously within your turn. Background task notifications do not work inside flows — they will cause your step to hang.

4. FOCUS: Complete only your assigned task. Do not expand scope or work on unrelated files.
=== End Guidelines ===

""".stripMargin

  // ============================================================
  // Messages
  // ============================================================

  sealed trait PipelineCommand

  object PipelineCommand:

    /** Start a new pipeline run with the given input. */
    case class Trigger(
      input: String,
      replyTo: Option[ActorRef[PipelineEvent]]
    ) extends PipelineCommand

    /** Step agent completed. */
    case class StepCompleted(stepId: String, output: String) extends PipelineCommand

    /** Step agent failed. */
    case class StepFailed(stepId: String, error: String) extends PipelineCommand

    /** Query current state. */
    case class GetState(replyTo: ActorRef[PipelineSnapshot]) extends PipelineCommand

    /** Global flow timeout fired. */
    case object FlowTimeout extends PipelineCommand

    /** Cancel the pipeline — stop all agents, mark as failed. */
    case object Cancel extends PipelineCommand

    /** Internal: re-schedule ready steps after a retry backoff. */
    case object ScheduleReady extends PipelineCommand

    /** Shutdown the pipeline actor. */
    case object Stop extends PipelineCommand

  end PipelineCommand

  // ============================================================
  // Events (replies to Trigger)
  // ============================================================

  sealed trait PipelineEvent

  object PipelineEvent:
    case class Progress(stepId: String, status: String, summary: String = "") extends PipelineEvent
    case class Done(summary: String) extends PipelineEvent
    case class Failed(reason: String) extends PipelineEvent

  // ============================================================
  // State snapshot
  // ============================================================

  case class PipelineSnapshot(
    name: String,
    flowName: String,
    phase: String,
    stepStatus: Map[String, String],
    results: Map[String, String],
    iteration: Int,
    verifyResult: Option[String]
  )

  // ============================================================
  // Run state — reset on each Trigger
  // ============================================================

  private case class RunState(
    phase: RunPhase,
    stepStatus: Map[String, String] = Map.empty,
    results: Map[String, String] = Map.empty,
    failedReasons: Map[String, String] = Map.empty,
    verdicts: Map[String, Boolean] = Map.empty,
    nodeExecCount: Map[String, Int] = Map.empty,
    stepRetries: Map[String, Int] = Map.empty,
    /** Errors from previous attempts, injected into retry prompts so agents
     * can learn from failures rather than repeating the same mistake. */
    lastErrors: Map[String, String] = Map.empty,
    agentPaths: Map[String, String] = Map.empty,
    agentRefs: Map[String, ActorRef[AgentCommand]] = Map.empty,
    nodeSessionIds: Map[String, String] = Map.empty,
    verifyResult: Option[String] = None,
    iteration: Int = 0,
    triggerInput: String = "",
    replyTo: Option[ActorRef[PipelineEvent]] = None
  )

  private enum RunPhase:
    case Idle, Running, Completed, Failed

  // ============================================================
  // Config
  // ============================================================

  case class PipelineConfig(
    name: String,
    flowName: String,
    flowDef: FlowDef,
    parentAgentRef: ActorRef[AgentCommand],
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    resources: SharedResources,
    projectRoot: String,
    safetyMode: String,
    flowMemory: String = "",
    gatewayPort: Int = 8080,
    expectsMail: Boolean = true
  )

  // ============================================================
  // Factory
  // ============================================================

  def apply(config: PipelineConfig): Behavior[PipelineCommand] =
    Behaviors.setup { ctx =>
      given ActorContext[PipelineCommand] = ctx

      val stateRef = Ref.unsafe[IO, RunState](RunState(RunPhase.Idle))

      for
        _ <- logger.info(s"PipelineActor '${config.name}' created (flow: ${config.flowName})")
        // Check for interrupted state from a previous run (crash recovery)
        _ <- config.sessionId match
          case Some(sid) if sid.nonEmpty =>
            PipelineStateStore.load(sid, config.name).flatMap {
              case Some(saved) if saved.phase == "Running" =>
                val input = saved.triggerInput.getOrElse("")
                for
                  _ <- logger.warn(s"[${config.name}] Detected interrupted run (phase=Running), auto-resuming in 3s")
                  _ <- emit(config, "flowResuming",
                    "branchName" -> config.name.asJson,
                    "flowName" -> config.flowName.asJson
                  )
                  _ <- ctx.forkTurn(
                    IO.sleep(3.seconds) *> IO(ctx.self ! PipelineCommand.Trigger(input, None))
                  )
                yield ()
              case _ => IO.unit
            }
          case _ => IO.unit
        _ <- saveState(stateRef, config) // persist initial idle state
      yield running(ctx, stateRef, config)
    }

  // ============================================================
  // Running behavior
  // ============================================================

  private def running(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  ): Behavior[PipelineCommand] =

    new Behavior[PipelineCommand]:
      override def onError(ctx: ActorContext[PipelineCommand], err: Throwable): IO[Behavior[PipelineCommand]] =
        logger
          .error(
            s"PipelineActor '${cfg.name}' error: ${err.getMessage}\n${err.getStackTrace.take(10).map(_.toString).mkString("\n")}"
          )
          .as(this)

      def receive(ctx: ActorContext[PipelineCommand], msg: PipelineCommand): IO[Behavior[PipelineCommand]] =
        given ActorContext[PipelineCommand] = ctx
        msg match
          case PipelineCommand.Trigger(input, replyTo) =>
            for
              _ <- handleTrigger(ctx, stateRef, cfg, input, replyTo)
              _ <- saveState(stateRef, cfg)
            yield this

          case PipelineCommand.StepCompleted(stepId, output) =>
            for
              _ <- handleStepCompleted(ctx, stateRef, cfg, stepId, output)
              _ <- afterStepUpdate(ctx, stateRef, cfg)
              _ <- saveState(stateRef, cfg)
            yield this

          case PipelineCommand.StepFailed(stepId, error) =>
            for
              _ <- handleStepFailed(ctx, stateRef, cfg, stepId, error)
              _ <- afterStepUpdate(ctx, stateRef, cfg)
              _ <- saveState(stateRef, cfg)
            yield this

          case PipelineCommand.FlowTimeout =>
            stateRef.get.flatMap { state =>
              if state.phase == RunPhase.Running then
                failPipeline(ctx, stateRef, cfg, s"Flow timed out after ${cfg.flowDef.flowTimeoutSeconds}s")
              else IO.unit
            }.as(this)

          case PipelineCommand.Cancel =>
            stateRef.get.flatMap { state =>
              if state.phase == RunPhase.Running then
                failPipeline(ctx, stateRef, cfg, "Flow canceled by user/agent")
              else IO.unit
            }.as(this)

          case PipelineCommand.ScheduleReady =>
            for
              s <- stateRef.get
              _ <- if s.phase == RunPhase.Running then scheduleReadySteps(ctx, stateRef, cfg) else IO.unit
            yield this

          case PipelineCommand.GetState(replyTo) =>
            for
              state <- stateRef.get
              snapshot = PipelineSnapshot(
                cfg.name,
                cfg.flowName,
                state.phase.toString,
                state.stepStatus,
                state.results,
                state.iteration,
                state.verifyResult
              )
              _ <- replyTo ! snapshot
            yield this

          case PipelineCommand.Stop =>
            for _ <- logger.info(s"PipelineActor '${cfg.name}' stopping")
            yield Behaviors.stopped[PipelineCommand]
        end match

      end receive
  end running

  // ============================================================
  // Trigger — start a new run
  // ============================================================

  private def handleTrigger(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    input: String,
    replyTo: Option[ActorRef[PipelineEvent]]
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      _ <-
        if state.phase == RunPhase.Running then
          // Already running — reject
          replyTo.traverse_(_ ! PipelineEvent.Failed(s"Pipeline '${cfg.name}' is already running"))
        else
          // Reset run state and start
          val initialStepStatus = cfg.flowDef.nodes.map(n => n.id -> StepStatus.Pending.toString).toMap
          for
            _ <- stateRef.set(
              RunState(
                phase = RunPhase.Running,
                stepStatus = initialStepStatus,
                triggerInput = input,
                replyTo = replyTo
              )
            )
            _ <- emit(
              cfg,
              "flowStarted",
              "flowName" -> cfg.name.asJson,
              "steps" -> cfg.flowDef.nodes
                .map(n =>
                  Json.obj(
                    "id" -> n.id.asJson,
                    "agent" -> n.agent.getOrElse("").asJson,
                    "dependsOn" -> n.dependsOn.toList.asJson,
                    "verdict" -> n.verdict.asJson,
                    "condition" -> n.condition.asJson,
                    "retry" -> n.retry.map(r =>
                      Json.obj("target" -> r.target.asJson, "maxIterations" -> r.maxIterations.asJson)
                    ).getOrElse(Json.Null)
                  )
                )
                .asJson,
              // The agent of the first verdict node + the first retry's maxIterations,
              // so the frontend can label the verify step and show iteration limits
              // WITHOUT hardcoding 'Explorer' / 3 (it had no backend source for these).
              "verifyAgent" -> cfg.flowDef.nodes.find(_.verdict).flatMap(_.agent).getOrElse("").asJson,
              "maxIterations" -> cfg.flowDef.nodes.flatMap(_.retry).headOption.map(_.maxIterations).getOrElse(0).asJson
            )
            _ <- logger.info(s"[${cfg.name}] Triggered with input (${input.length} chars)")
            _ <- FlowMembership.registerFlow(cfg.name, cfg.flowDef.nodes)
            _ <- scheduleReadySteps(ctx, stateRef, cfg)
            // Start global flow timeout
            _ <- ctx.forkTurn(
              IO.sleep(cfg.flowDef.flowTimeoutSeconds.seconds) *>
                stateRef.get.flatMap { s =>
                  if s.phase == RunPhase.Running then IO(ctx.self ! PipelineCommand.FlowTimeout)
                  else IO.unit
                }
            )
          yield ()
          end for
    yield ()

  // ============================================================
  // DAG scheduling
  // ============================================================

  private def scheduleReadySteps(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      ready = cfg.flowDef.nodes.filter { node =>
        state.stepStatus.get(node.id).contains(StepStatus.Pending.toString) &&
        node.dependsOn.forall(dep => state.stepStatus.get(dep).contains(StepStatus.Done.toString)) &&
        isConditionMet(node.condition, state.verdicts)
      }
      // Count running steps (those with Running status)
      runningCount = state.stepStatus.count(_._2 == StepStatus.Running.toString)
      slots = (cfg.flowDef.maxConcurrency - runningCount).max(0)
      toStart = ready.take(slots)
      _ <- toStart.traverse_(node => spawnStep(ctx, stateRef, cfg, node))
      _ =
        if toStart.nonEmpty then logger.info(s"[${cfg.name}] Scheduled: ${toStart.map(_.id).mkString(", ")}")
    yield ()

  /** Check if a condition (format "nodeId.fail") is met based on recorded verdicts. */
  private def isConditionMet(condition: Option[String], verdicts: Map[String, Boolean]): Boolean =
    condition match
      case None => true
      case Some(cond) =>
        cond.lastIndexOf('.') match
          case idx if idx > 0 =>
            val nodeId = cond.substring(0, idx)
            val condType = cond.substring(idx + 1)
            condType == "fail" && verdicts.get(nodeId).contains(false)
          case _ => true // malformed condition — allow scheduling

  // ============================================================
  // Nested flow spawning
  // ============================================================

  /** Spawn a nested flow as a sub-pipeline.
   *
   * Loads the referenced flow definition, resolves its input template,
   * creates a child PipelineActor, and triggers it. The child's result
   * (Done/Failed) is forwarded back as StepCompleted/StepFailed via an
   * adapter actor.
   */
  private def spawnNestedFlow(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    node: FlowNode
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    val subFlowName = node.flow.getOrElse("")
    val subInstanceName = s"${cfg.name}/${node.id}"

    def reject(msg: String): IO[Unit] =
      stateRef.update(s => s.copy(stepStatus = s.stepStatus + (node.id -> StepStatus.Running.toString))) *>
        IO(ctx.self ! PipelineCommand.StepFailed(node.id, msg))

    FlowDefLoader.load(subFlowName).flatMap {
      case None =>
        reject(s"Nested flow '$subFlowName' not found in ~/.nebflow/flows/")
      case Some(subFlowDef) =>
        // Validate: no Nebula as step agent in sub-flow
        val nebViolation = subFlowDef.nodes.find(_.agent.contains("Nebula"))
        if nebViolation.isDefined then
          reject(s"Nested flow '$subFlowName' contains Nebula as step agent (node '${nebViolation.get.id}'). Use Explorer, Coder, etc.")
        else
          for
            state <- stateRef.get
            // Resolve flowInput template: use node's flowInput, or fall back to trigger input
            rawInput = node.flowInput.getOrElse(state.triggerInput)
            resolvedInput = resolveTemplate(rawInput, state.results ++ Map("input" -> state.triggerInput))
            subUid = s"sub-${cfg.name.take(15)}-${node.id}-${java.util.UUID.randomUUID().toString.take(8)}"
            // Build sub-pipeline config — shares resources, wsSend, session, etc.
            subConfig = PipelineConfig(
              name = subInstanceName,
              flowName = subFlowName,
              flowDef = subFlowDef,
              parentAgentRef = cfg.parentAgentRef,
              wsSend = cfg.wsSend,
              sessionId = cfg.sessionId,
              resources = cfg.resources,
              projectRoot = cfg.projectRoot,
              safetyMode = cfg.safetyMode,
              expectsMail = false // sub-pipeline agents don't Mail the parent
            )
            // Spawn adapter: converts PipelineEvent → PipelineCommand
            adapterRef <- ctx.spawn(
              nestedFlowAdapter(ctx.self, node.id, node.timeout),
              s"$subUid-adapter"
            )
            // Spawn sub-pipeline
            subPipeRef <- ctx.system.spawn(
              PipelineActor(subConfig),
              subUid
            )
            _ <- stateRef.update(s =>
              s.copy(
                stepStatus = s.stepStatus + (node.id -> StepStatus.Running.toString),
                nodeExecCount = s.nodeExecCount.updatedWith(node.id)(v => Some(v.getOrElse(0) + 1))
                // Sub-pipeline ref not tracked in agentRefs — different type
                // (ActorRef[PipelineCommand] vs ActorRef[AgentCommand]).
                // The adapter handles the sub-pipeline's lifecycle.
              )
            )
            _ <- emit(
              cfg,
              "flowStepStarted",
              "branchName" -> cfg.name.asJson,
              "stepId" -> node.id.asJson,
              "agentName" -> s"flow:$subFlowName".asJson,
              "nestedFlow" -> subFlowName.asJson
            )
            _ = logger.info(s"[${cfg.name}] Spawned nested flow '${node.id}' ($subFlowName)")
            // Trigger the sub-pipeline — adapter receives Done/Failed
            _ <- subPipeRef ! PipelineCommand.Trigger(resolvedInput, Some(adapterRef))
          yield ()
    }

  /** Adapter that converts a child PipelineActor's events into StepCompleted/StepFailed
   * for the parent pipeline. */
  private def nestedFlowAdapter(
    parentRef: ActorRef[PipelineCommand],
    stepId: String,
    timeout: FiniteDuration
  ): Behavior[PipelineEvent] =
    Behaviors.setup { ctx =>
      val done = Ref.unsafe[IO, Boolean](false)
      ctx.forkTurn(
        IO.sleep(timeout) *>
          done.get.flatMap {
            case true => IO.unit
            case false =>
              done.set(true) *>
                (parentRef ! PipelineCommand.StepFailed(stepId, s"nested flow timeout after ${timeout.toSeconds}s"))
          }
      )
      IO.pure(new Behavior[PipelineEvent]:
        def receive(ctx: ActorContext[PipelineEvent], event: PipelineEvent): IO[Behavior[PipelineEvent]] =
          event match
            case PipelineEvent.Done(summary) =>
              done.set(true) *>
                (parentRef ! PipelineCommand.StepCompleted(stepId, summary)) *>
                IO.pure(Behaviors.stopped[PipelineEvent])
            case PipelineEvent.Failed(reason) =>
              done.set(true) *>
                (parentRef ! PipelineCommand.StepFailed(stepId, reason)) *>
                IO.pure(Behaviors.stopped[PipelineEvent])
            case PipelineEvent.Progress(_, _, _) =>
              // Forward progress as-is (could be used for UI updates)
              IO.pure(this)
      )
    }

  private def spawnStep(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    node: FlowNode
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    val agentName = node.agent.getOrElse("")

    def reject(msg: String): IO[Unit] =
      // Mark as Running so handleStepFailed processes the failure
      stateRef.update(s => s.copy(stepStatus = s.stepStatus + (node.id -> StepStatus.Running.toString))) *>
        IO(ctx.self ! PipelineCommand.StepFailed(node.id, msg))

    if agentName == "Nebula" then
      reject("Nebula cannot be used as a step agent inside flows. Use Explorer, Coder, or other specialized agents.")
    else if node.isNested then
      spawnNestedFlow(ctx, stateRef, cfg, node)
    else
      for
        state <- stateRef.get
        resolvedPrompt = resolveTemplate(
          node.prompt.getOrElse(""),
          state.results ++ Map("input" -> state.triggerInput)
        )
        promptWithPreamble = resolvedPrompt
        defOpt <- cfg.resources.agentLibrary.get(agentName)
        _ <- defOpt match
          case None =>
            reject(s"Agent '$agentName' not found")
          case Some(agentDef) =>
            val agentUid = s"pipe-${cfg.name.take(20)}-${node.id}-${java.util.UUID.randomUUID().toString.take(8)}"
            for
              // Create a real session for this flow node — unified with normal sessions.
              // Wrap in handleErrorWith so transient I/O errors (e.g. concurrent
              // _index.json write race) don't crash the entire pipeline.
              nodeSession <- cfg.resources.sessionStore
                .createSession(
                  s"${cfg.name}/${node.id}",
                  agentName = Some(agentName)
                )
                .handleErrorWith(e =>
                  logger.warn(s"[${cfg.name}] createSession failed for '$node.id', using fallback: ${e.getMessage}") *>
                    cfg.resources.sessionStore
                      .createSession(s"${cfg.name}/${node.id}", agentName = Some(agentName))
                      .handleErrorWith(e2 =>
                        // Last resort: synthesize a session-like object with a random ID
                        IO.pure(nebflow.shared.SessionMeta(
                          java.util.UUID.randomUUID().toString,
                          s"${cfg.name}/${node.id}",
                          System.currentTimeMillis(),
                          System.currentTimeMillis(),
                          hasUnread = false,
                          agentName = Some(agentName)
                        ))
                      )
                )
              readTracker <- ReadTracker.create
              fileHistory <- FileHistory.create()
              childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(node.id), Some(nodeSession.id))
              peerInfo = buildPeerInfo(node.id, state.agentPaths, cfg.name, neighborsOf(cfg.flowDef.nodes, node.id))
              // If this is a retry, inject the previous error so the agent can adjust
              retryContext = state.lastErrors.get(node.id) match
                case Some(err) =>
                  val safeError = err.take(500)
                  s"\n\n=== Previous Attempt Failed ===\nThe previous attempt failed with this error:\n$safeError\n\nPlease adjust your approach to avoid this failure.\n=== End Error Context ===\n"
                case None => ""
              actualPrompt = withMemory(FlowAgentPrefix + peerInfo + promptWithPreamble + retryContext, cfg)
              // Only require Mail if the node has reachable peers already spawned
              nodeNeighbors = neighborsOf(cfg.flowDef.nodes, node.id)
              hasReachablePeers = nodeNeighbors.exists(id => state.agentPaths.contains(id))
              effectiveExpectsMail = cfg.expectsMail && hasReachablePeers
              agentRef <- ctx.system.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = cfg.resources,
                  wsSend = childWs,
                  depth = 1,
                  parentRef = Some(cfg.parentAgentRef),
                  sessionId = Some(nodeSession.id),
                  sessionName = Some(s"${cfg.name}/${node.id}"),
                  readTracker = Some(readTracker),
                  fileHistory = Some(fileHistory),
                  contextWindow = cfg.resources.contextWindow,
                  projectRoot = Some(cfg.projectRoot),
                  safetyMode = cfg.safetyMode,
                  expectsMail = effectiveExpectsMail
                ),
                agentUid
              )
              _ <- stateRef.update(s =>
                s.copy(
                  stepStatus = s.stepStatus + (node.id -> StepStatus.Running.toString),
                  nodeExecCount = s.nodeExecCount.updatedWith(node.id)(v => Some(v.getOrElse(0) + 1)),
                  agentPaths = s.agentPaths + (node.id -> agentRef.path.toString),
                  agentRefs = s.agentRefs + (node.id -> agentRef),
                  nodeSessionIds = s.nodeSessionIds + (node.id -> nodeSession.id)
                )
              )
              adapterRef <- ctx.spawn(
                stepAdapter(ctx.self, node.id, agentRef, node.timeout),
                s"$agentUid-adapter"
              )
              _ <- FlowMembership.join(agentRef.path.toString, cfg.name, node.id)
              _ <- emit(
                cfg,
                "flowStepStarted",
                "branchName" -> cfg.name.asJson,
                "stepId" -> node.id.asJson,
                "agentName" -> agentName.asJson,
                "nodeSessionId" -> nodeSession.id.asJson
              )
              // Persist the step's input prompt as a user message in the node
              // session, so the flow node popup history shows what was asked.
              _ <- cfg.resources.sessionStore.appendUiMessages(
                nodeSession.id,
                List(UiMessage.User(actualPrompt, Nil, true, System.currentTimeMillis()))
              )
              _ <- agentRef ! AgentCommand.UserInput(actualPrompt, Some(adapterRef))
              _ = logger.info(s"[${cfg.name}] Spawned '${node.id}' ($agentName)")
              // Persist state immediately so nodeSessionId is available via
              // the status API — the flow popup needs it to load chat history.
              _ <- saveState(stateRef, cfg)
            yield ()
            end for
      yield ()
      end for
  end spawnStep

  // ============================================================
  // Step completion handling
  // ============================================================

  private def handleStepCompleted(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    stepId: String,
    output: String
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      _ <-
        if stepId == ManagerId then
          // Manager completed — its output IS the final summary.
          stateRef.update(s => s.copy(verifyResult = Some(output))) *>
            stateRef.get.flatMap(s => completePipeline(ctx, stateRef, cfg, output, computePass(s, cfg)))
        else if state.stepStatus.get(stepId).contains(StepStatus.Running.toString) then
          val nodeOpt = cfg.flowDef.nodes.find(_.id == stepId)
          nodeOpt match
            case Some(node) if node.verdict =>
              // Determine pass/fail from Mail activity:
              //   - If Mail was sent to retry target → FAIL
              //   - Otherwise → PASS
              //   (FlowMembership ensures only DAG neighbors can Mail, so
              //   only the verify agent could have sent to the retry target.)
              val checkTarget = node.retry match
                case Some(RetryTarget(target, _)) =>
                  val targetPath = state.agentPaths.getOrElse(target, "")
                  FlowMailTracker.anySentTo(targetPath)
                case None => IO.pure(false)
              checkTarget.flatMap { mailedToTarget =>
                val passed = !mailedToTarget
                val verdictText = s"VERDICT: ${if passed then "PASS" else "FAIL"}: ${output.take(200)}"
                for
                  _ <- stateRef.update(s =>
                    s.copy(
                      results = s.results + (stepId -> verdictText),
                      stepStatus = s.stepStatus + (stepId -> StepStatus.Done.toString),
                      verdicts = s.verdicts + (stepId -> passed)
                    )
                  )
                  _ <- emit(cfg, "flowVerifyResult",
                    "branchName" -> cfg.name.asJson,
                    "pass" -> passed.asJson,
                    "summary" -> output.take(500).asJson
                  )
                  _ <- emit(cfg, "flowStepCompleted", "branchName" -> cfg.name.asJson, "stepId" -> stepId.asJson)
                  _ <- state.replyTo.traverse_(_ ! PipelineEvent.Progress(stepId, "Done", output.take(200)))
                  _ = logger.info(s"[${cfg.name}] Verdict '$stepId': ${if passed then "PASS" else "FAIL"} (mailedToTarget=$mailedToTarget)")
                  _ <- FlowMailTracker.clearAllForTest
                  _ <- node.retry match
                    case Some(RetryTarget(target, maxIter)) if !passed =>
                      handleRetry(ctx, stateRef, cfg, stepId, target, maxIter)
                    case _ => IO.unit
                yield ()
              }
            case _ =>
              // Normal (non-verdict) node — store result, mark Done.
              for
                _ <- stateRef.update(s =>
                  s.copy(
                    results = s.results + (stepId -> output),
                    stepStatus = s.stepStatus + (stepId -> StepStatus.Done.toString)
                  )
                )
                _ <- emit(cfg, "flowStepCompleted", "branchName" -> cfg.name.asJson, "stepId" -> stepId.asJson)
                _ <- state.replyTo.traverse_(_ ! PipelineEvent.Progress(stepId, "Done", output.take(200)))
                _ = logger.info(s"[${cfg.name}] Step '$stepId' completed (${output.length} chars)")
                _ <- nodeOpt.flatMap(_.retry) match
                  case Some(RetryTarget(target, maxIter)) =>
                    handleRetry(ctx, stateRef, cfg, stepId, target, maxIter)
                  case None => IO.unit
              yield ()
        else IO.unit
    yield ()

  /** Check if a retry (loop-back) should trigger after a node completes. */
  private def handleRetry(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    nodeId: String,
    target: String,
    maxIter: Int
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      node = cfg.flowDef.nodes.find(_.id == nodeId)
      isVerdictNode = node.exists(_.verdict)
      verdictFailed = isVerdictNode && state.verdicts.get(nodeId).contains(false)
      // For verdict nodes: retry only on FAIL. For non-verdict nodes: always retry.
      shouldRetry = !isVerdictNode || verdictFailed
      targetCount = state.nodeExecCount.getOrElse(target, 0)
      _ <-
        if shouldRetry && targetCount < maxIter then
          // Reset target and all its transitive downstream nodes (that are Done) back to Pending
          val toReset = (downstreamOf(cfg.flowDef.nodes, target) + nodeId)
            .filter(id => state.stepStatus.get(id).contains(StepStatus.Done.toString))
          for
            _ <- stateRef.update(s =>
              s.copy(stepStatus = s.stepStatus ++ toReset.map(_ -> StepStatus.Pending.toString).toMap)
            )
            _ <- emit(
              cfg,
              "flowLoopIteration",
              "branchName" -> cfg.name.asJson,
              "target" -> target.asJson,
              "iteration" -> (targetCount + 1).asJson,
              "maxIterations" -> maxIter.asJson
            )
            _ <- logger.info(
              s"[${cfg.name}] Retry: resetting [${toReset.toList.sorted.mkString(", ")}] (target: $target, iter ${targetCount + 1}/$maxIter)"
            )
          yield ()
        else if verdictFailed then
          // Verdict failed and retries exhausted
          failPipeline(
            ctx,
            stateRef,
            cfg,
            s"Verification failed after $targetCount iteration(s) of '$target'"
          )
        else IO.unit
    yield ()

  private def handleStepFailed(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    stepId: String,
    error: String
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    import StepErrorClassifier.*
    val errorType = StepErrorClassifier.classify(error)
    for
      state <- stateRef.get
      _ <-
        if stepId == ManagerId then
          failPipeline(ctx, stateRef, cfg, s"Manager agent failed: $error")
        else if state.stepStatus.get(stepId).contains(StepStatus.Running.toString) then
          val nodeOpt = cfg.flowDef.nodes.find(_.id == stepId)
          val currentRetries = state.stepRetries.getOrElse(stepId, 0)
          // Effective max retries: node config (default 1 for transient errors)
          val effectiveMaxRetries = nodeOpt.map(n => if n.maxRetries > 0 then n.maxRetries else 1).getOrElse(0)
          val canRetry = errorType.shouldRetry && currentRetries < effectiveMaxRetries
          nodeOpt match
            case Some(node) if canRetry =>
              val delayMs = StepErrorClassifier.backoffMs(currentRetries)
              for
                _ <- stateRef.update(s =>
                  s.copy(
                    // Use Retrying (not Pending) so scheduleReadySteps doesn't
                    // immediately re-spawn it. ScheduleReady flips it to Pending
                    // after the backoff delay.
                    stepStatus = s.stepStatus + (stepId -> StepStatus.Retrying.toString),
                    stepRetries = s.stepRetries + (stepId -> (currentRetries + 1)),
                    lastErrors = s.lastErrors + (stepId -> error)
                  )
                )
                _ <- emit(
                  cfg,
                  "flowStepRetrying",
                  "branchName" -> cfg.name.asJson,
                  "stepId" -> stepId.asJson,
                  "attempt" -> (currentRetries + 1).asJson,
                  "maxRetries" -> effectiveMaxRetries.asJson,
                  "error" -> error.take(500).asJson,
                  "errorType" -> errorType.toString.asJson,
                  "backoffMs" -> delayMs.asJson
                )
                _ <- logger.info(
                  s"[${cfg.name}] Retrying step '$stepId' (attempt ${currentRetries + 1}/$effectiveMaxRetries, type=$errorType, backoff=${delayMs}ms)"
                )
                // After backoff: flip Retrying → Pending, then reschedule
                _ <- ctx.forkTurn(
                  IO.sleep(scala.concurrent.duration.FiniteDuration(delayMs, scala.concurrent.duration.MILLISECONDS)) *>
                    stateRef.update(s =>
                      s.copy(stepStatus = s.stepStatus + (stepId -> StepStatus.Pending.toString))
                    ) *>
                    IO(ctx.self ! PipelineCommand.ScheduleReady)
                )
              yield ()
            case _ =>
              // Check if any pending step depends on this one
              val hasDependers = cfg.flowDef.nodes.exists(n =>
                n.dependsOn.contains(stepId) &&
                  state.stepStatus.get(n.id).contains(StepStatus.Pending.toString)
              )
              if hasDependers then failPipeline(ctx, stateRef, cfg, s"Step '$stepId' failed ($errorType): $error")
              else
                for
                  _ <- stateRef.update(s =>
                    s.copy(
                      stepStatus = s.stepStatus + (stepId -> StepStatus.Failed.toString),
                      failedReasons = s.failedReasons + (stepId -> error)
                    )
                  )
                  _ <- emit(
                    cfg,
                    "flowStepFailed",
                    "branchName" -> cfg.name.asJson,
                    "stepId" -> stepId.asJson,
                    "error" -> error.take(500).asJson,
                    "errorType" -> errorType.toString.asJson
                  )
                  _ <- state.replyTo.traverse_(_ ! PipelineEvent.Progress(stepId, "Failed", error))
                yield ()
              end if
          end match
        else IO.unit
    yield ()

  private def afterStepUpdate(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      _ <- cancelUnreachable(stateRef, cfg)
      state <- stateRef.get
      _ <-
        if state.phase != RunPhase.Running then IO.unit
        else
          val managerRunning = state.stepStatus.contains(ManagerId) &&
            state.stepStatus.get(ManagerId).contains(StepStatus.Running.toString)
          val terminated = cfg.flowDef.nodes.forall(n =>
            state.stepStatus.get(n.id).exists(isTerminal)
          )
          if terminated && !managerRunning then
            runManagerSummary(ctx, stateRef, cfg)
          else scheduleReadySteps(ctx, stateRef, cfg)
    yield ()

  /** A step status is terminal if the node will never change again. */
  private def isTerminal(status: String): Boolean =
    status == StepStatus.Done.toString ||
      status == StepStatus.Failed.toString ||
      status == StepStatus.Canceled.toString

  /**
   *  Fixed-point: mark Pending nodes whose condition can NEVER be satisfied as
   *  Canceled (a true terminal state). This is the root fix for the conditional
   *  deadlock — a `condition: verify.fail` node, when verify PASSES, would
   *  otherwise stay Pending forever and block termination.
   *
   *  A Pending node with a condition is unreachable if its gating verdict node
   *  has already produced a verdict opposite to what the condition requires
   *  (e.g. gate PASS but condition needs fail). We also cancel Pending nodes
   *  whose dependencies have hit a terminal FAILED/Canceled state (they can
   *  never become Done). Both run to a fixed point. */
  private def cancelUnreachable(
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  ): IO[Unit] =
    stateRef.update { s =>
      var st = s
      var changed = true
      while changed do
        changed = false
        cfg.flowDef.nodes.foreach { n =>
          if st.stepStatus.get(n.id).contains(StepStatus.Pending.toString) then
            val shouldCancel = conditionUnsatisfiable(n, st.verdicts) ||
              depsBlocked(n, st.stepStatus)
            if shouldCancel then
              st = st.copy(stepStatus = st.stepStatus + (n.id -> StepStatus.Canceled.toString))
              changed = true
        }
      st
    }

  /** A condition can never be met: e.g. condition=verify.fail but verify PASSED. */
  private def conditionUnsatisfiable(node: FlowNode, verdicts: Map[String, Boolean]): Boolean =
    node.condition.exists { cond =>
      cond.lastIndexOf('.') match
        case idx if idx > 0 =>
          val nodeId = cond.substring(0, idx)
          // gate produced a verdict AND it's the opposite of what .fail needs (a fail)
          verdicts.get(nodeId).contains(true)
        case _ => false
    }

  /** A dependency is in a terminal non-Done state (Failed/Canceled) → this node
   *  can never have all deps Done, so it can never run. */
  private def depsBlocked(node: FlowNode, stepStatus: Map[String, String]): Boolean =
    node.dependsOn.exists { dep =>
      stepStatus.get(dep).exists(s => s == StepStatus.Failed.toString || s == StepStatus.Canceled.toString)
    }

  // ============================================================
  // Manager summary
  // ============================================================

  /**
   *  Derive the flow's pass/fail from the run state — NOT hardcoded. A flow
   *  passes iff:
   *    - no verdict node failed (every verdict node reported PASS), AND
   *    - no non-verdict node hard-failed.
   *  This is the root fix for "completePipeline always reports pass=true": a
   *  leaf step failure or a FAIL verdict now correctly yields pass=false. */
  private def computePass(state: RunState, cfg: PipelineConfig): Boolean =
    val verdictFailed = cfg.flowDef.nodes.exists(n =>
      n.verdict && state.verdicts.get(n.id).contains(false)
    )
    val stepFailed = cfg.flowDef.nodes.exists(n =>
      !n.verdict && state.stepStatus.get(n.id).contains(StepStatus.Failed.toString)
    )
    !verdictFailed && !stepFailed

  private def runManagerSummary(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    cfg.flowDef.manager match
      case None =>
        // No manager — build summary from node results and complete
        for
          state <- stateRef.get
          summary = buildStepSummary(state.results, state.failedReasons, state.stepStatus)
          pass = computePass(state, cfg)
          _ <- completePipeline(ctx, stateRef, cfg, summary, pass)
        yield ()
      case Some(agentName) =>
        for
          state <- stateRef.get
          contextBlock = buildNodeResultsContext(state.results, state.failedReasons, state.stepStatus)
          prompt = ManagerPromptPreamble + "\n" + contextBlock
          _ <- stateRef.update(s =>
            s.copy(stepStatus = s.stepStatus + (ManagerId -> StepStatus.Running.toString))
          )
          _ <- emit(
            cfg,
            "flowStepStarted",
            "branchName" -> cfg.name.asJson,
            "stepId" -> ManagerId.asJson,
            "agentName" -> agentName.asJson
          )
          _ <- spawnById(ctx, stateRef, cfg, agentName, withMemory(prompt, cfg), ManagerId, 600.seconds, isManager = true)
        yield ()

  // ============================================================
  // Pipeline completion / failure
  // ============================================================

  private def completePipeline(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    summary: String,
    pass: Boolean
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    val status = if pass then "PASS" else "FAIL"
    for
      _ <- stateRef.update(s => s.copy(phase = RunPhase.Completed))
      _ <- emit(
        cfg,
        "flowCompleted",
        "branchName" -> cfg.name.asJson,
        "pass" -> pass.asJson,
        "summary" -> summary.take(500).asJson
      )
      // Notify parent agent of completion
      _ <- cfg.parentAgentRef ! AgentCommand.ExternalEvent(
        source = "flow",
        eventType = "completed",
        payload = s"[Flow: ${cfg.name}] $status\n$summary",
        metadata = JsonObject("flowName" -> cfg.name.asJson, "pass" -> pass.asJson)
      )
      _ <- stateRef.get.flatMap(_.replyTo.traverse_(_ ! PipelineEvent.Done(summary)))
      _ <- stateRef.update(s => s.copy(phase = RunPhase.Idle, replyTo = None))
      // Stop all persistent agents and clean up flow membership
      _ <- cleanupAgents(stateRef, cfg)
      // Launch reflect in background — non-blocking, fire-and-forget
      _ <- runReflect(stateRef, cfg, summary, passed = pass)
      _ = logger.info(s"[${cfg.name}] Pipeline COMPLETED (pass=$pass)")
    yield ()

  private def failPipeline(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    reason: String
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      _ <- stateRef.update(s => s.copy(phase = RunPhase.Failed))
      _ <- emit(
        cfg,
        "flowCompleted",
        "branchName" -> cfg.name.asJson,
        "pass" -> false.asJson,
        "summary" -> reason.take(500).asJson
      )
      _ <- cfg.parentAgentRef ! AgentCommand.ExternalEvent(
        source = "flow",
        eventType = "failed",
        payload = s"[Flow: ${cfg.name}] FAIL\n$reason",
        metadata = JsonObject("flowName" -> cfg.name.asJson)
      )
      _ <- stateRef.get.flatMap(_.replyTo.traverse_(_ ! PipelineEvent.Failed(reason)))
      _ <- stateRef.update(s => s.copy(phase = RunPhase.Idle, replyTo = None))
      // Stop all persistent agents and clean up flow membership
      _ <- cleanupAgents(stateRef, cfg)
      // Learn from failures too — reflect in background
      _ <- runReflect(stateRef, cfg, reason, passed = false)
      _ = logger.warn(s"[${cfg.name}] Pipeline FAILED: $reason")
    yield ()

  /** Stop all persistent agents and clean up flow membership on pipeline end. */
  private def cleanupAgents(
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  )(using ctx: ActorContext[?]): IO[Unit] =
    for
      state <- stateRef.get
      _ <- state.agentRefs.values.toList.traverse_(ref =>
        IO(ctx.system.stop(ref)).handleErrorWith(e =>
          logger.warn(s"[${cfg.name}] Failed to stop agent ${ref.path}: ${e.getMessage}")
        )
      )
      _ <- state.agentPaths.values.toList.traverse_(path => FlowMembership.leaveAll(path))
      _ <- FlowMembership.unregisterFlow(cfg.name)
      _ = logger.info(s"[${cfg.name}] Cleaned up ${state.agentRefs.size} agents")
    yield ()

  // ============================================================
  // Spawn helper (for manager and ad-hoc agents)
  // ============================================================

  private def spawnById(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    agentName: String,
    prompt: String,
    stepId: String,
    timeout: FiniteDuration,
    isManager: Boolean = false
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    // Nebula restriction: cannot be used as step agent (manager is exempt)
    if !isManager && agentName == "Nebula" then
      IO(ctx.self ! PipelineCommand.StepFailed(
        stepId,
        "Nebula cannot be used as a step agent inside flows. Use Explorer, Coder, or other specialized agents."
      ))
    else
      for
        defOpt <- cfg.resources.agentLibrary.get(agentName)
        _ <- defOpt match
          case None =>
            IO(ctx.self ! PipelineCommand.StepFailed(stepId, s"Agent '$agentName' not found"))
          case Some(agentDef) =>
            val agentUid = s"pipe-${cfg.name.take(20)}-$stepId-${java.util.UUID.randomUUID().toString.take(8)}"
            for
              readTracker <- ReadTracker.create
              fileHistory <- FileHistory.create()
              childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(stepId))
              agentRef <- ctx.system.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = cfg.resources,
                  wsSend = childWs,
                  depth = 1,
                  parentRef = Some(cfg.parentAgentRef),
                  sessionId = cfg.sessionId,
                  sessionName = Some(s"${cfg.name}/$stepId"),
                  readTracker = Some(readTracker),
                  fileHistory = Some(fileHistory),
                  contextWindow = cfg.resources.contextWindow,
                  projectRoot = Some(cfg.projectRoot),
                  safetyMode = cfg.safetyMode,
                  expectsMail = false // manager/verify-summary has no peers to Mail
                ),
                agentUid
              )
              _ <- stateRef.update(s =>
                s.copy(
                  stepStatus = s.stepStatus + (stepId -> StepStatus.Running.toString),
                  agentRefs = s.agentRefs + (stepId -> agentRef)
                )
              )
              _ <- FlowMembership.join(agentRef.path.toString, cfg.name, stepId)
              adapterRef <- ctx.spawn(
                stepAdapter(ctx.self, stepId, agentRef, timeout),
                s"$agentUid-adapter"
              )
              _ <- agentRef ! AgentCommand.UserInput(prompt, Some(adapterRef))
              _ = logger.info(s"[${cfg.name}] Spawned '$stepId' ($agentName)")
            yield ()
            end for
      yield ()

  // ============================================================
  // Step adapter — monitors agent, forwards results
  // ============================================================

  private def stepAdapter(
    pipeRef: ActorRef[PipelineCommand],
    stepId: String,
    subagentRef: ActorRef[AgentCommand],
    timeout: FiniteDuration
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      ctx.watch(subagentRef)
      val done = Ref.unsafe[IO, Boolean](false)

      ctx.forkTurn(
        IO.sleep(timeout) *>
          done.get.flatMap {
            case true => IO.unit
            case false =>
              ctx.system.stop(subagentRef) *>
                (pipeRef ! PipelineCommand.StepFailed(stepId, s"timeout after ${timeout.toSeconds}s"))
          }
      )

      IO.pure(new Behavior[AgentEvent]:
        def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
          event match
            case AgentEvent.Completed(_, messages) =>
              val text = extractLastAssistantText(messages)
              done.set(true) *>
                (pipeRef ! PipelineCommand.StepCompleted(stepId, text)) *>
                IO.pure(Behaviors.stopped[AgentEvent])

            case AgentEvent.Failed(_, error) =>
              done.set(true) *>
                (pipeRef ! PipelineCommand.StepFailed(stepId, error.message)) *>
                IO.pure(Behaviors.stopped[AgentEvent])

        override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
          signal match
            case SystemSignal.Terminated(_) =>
              for
                alreadyDone <- done.get
                _ <- done.set(true)
                _ <-
                  if !alreadyDone then (pipeRef ! PipelineCommand.StepFailed(stepId, "agent crashed"))
                  else IO.unit
              yield Behaviors.stopped[AgentEvent]

        override def onStop(ctx: ActorContext[AgentEvent]): IO[Unit] = IO.unit
      )
    }

  // ============================================================
  // Utility helpers
  // ============================================================

  private val MaxStepOutputChars = 8000
  private val MaxMemoryChars = 4000

  /** Inject flow memory as a context prefix to a prompt. */
  private def withMemory(prompt: String, cfg: PipelineConfig): String =
    if cfg.flowMemory.isBlank then prompt
    else
      val mem =
        if cfg.flowMemory.length > MaxMemoryChars
        then cfg.flowMemory.take(MaxMemoryChars) + "\n... (truncated)"
        else cfg.flowMemory
      s"=== Flow Memory (accumulated from past runs) ===\n$mem\n=== End Memory ===\n\n$prompt"

  /** Find target + all nodes that (transitively) depend on target. */
  private def downstreamOf(nodes: List[FlowNode], target: String): Set[String] =
    var result = Set(target)
    var changed = true
    while changed do
      changed = false
      nodes.foreach { n =>
        if !result.contains(n.id) && n.dependsOn.exists(result.contains) then
          result = result + n.id
          changed = true
      }
    result

  /** Compute the set of node IDs that have a DAG edge with the given node. */
  private def neighborsOf(nodes: List[FlowNode], nodeId: String): Set[String] =
    nodes.find(_.id == nodeId) match
      case None => Set.empty
      case Some(node) =>
        val upstream = node.dependsOn
        val downstream = nodes.filter(_.dependsOn.contains(nodeId)).map(_.id).toSet
        val retryTargets = node.retry.map(_.target).toSet
        val retrySources = nodes.filter(_.retry.exists(_.target == nodeId)).map(_.id).toSet
        upstream ++ downstream ++ retryTargets ++ retrySources

  /** Build peer address info block for agent prompt injection (filtered by DAG edges). */
  private def buildPeerInfo(
    nodeId: String,
    agentPaths: Map[String, String],
    flowName: String,
    neighbors: Set[String]
  ): String =
    val peers = agentPaths.filter { (id, _) => id != nodeId && neighbors.contains(id) }
    if peers.isEmpty then ""
    else
      val lines = peers.map { (id, path) => s"- $id: $path" }.mkString("\n")
      s"""=== Flow Communication ===
         |You are node "$nodeId" in flow "$flowName". You can communicate with these peers via Mail:
         |$lines
         |
         |When you complete your work, use Mail to send your result to relevant peers.
         |If you are a verify node and find issues, Mail the peer with specific feedback
         |so they can fix it in the next iteration.
         |=== End Communication ===
         |
         |""".stripMargin
    end if

  // ============================================================
  // Reflect — learning loop after pipeline completion
  // ============================================================

  private val ReflectSystemPrompt =
    """You are the learning component of a workflow system. Your job is to analyze a completed pipeline run and produce an updated memory file that captures reusable knowledge for future runs.

Rules:
- Output ONLY the updated memory content in Markdown. No explanations, no wrapping.
- Keep it concise — under 3000 chars total. This is accumulated knowledge, not a log.
- Merge with existing memory: keep what's still valid, add new insights, update stale entries.
- Structure with clear sections (## headings) such as: User Preferences, Verification Patterns, Common Pitfalls, Effective Approaches.
- Each entry should be a single actionable line. No dates, no run-specific details.
- If there is nothing new to learn from this run, output the existing memory unchanged."""

  private def runReflect(
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    summary: String,
    passed: Boolean
  )(using ctx: ActorContext[?]): IO[Unit] =
    for
      _ <- logger.info(s"[${cfg.name}] Reflect starting (passed=$passed)")
      state <- stateRef.get
      stepSummary = buildStepSummary(state.results, state.failedReasons, state.stepStatus)
      reflectPrompt = buildReflectPrompt(cfg.flowName, state.triggerInput, stepSummary, summary, passed, cfg.flowMemory)
      request = LlmRequest(
        messages = List(Message(MessageRole.User, Left(reflectPrompt))),
        sessionId = cfg.sessionId.getOrElse("flow-reflect"),
        agentId = s"flow-reflect-${cfg.name}",
        systemStable = Some(ReflectSystemPrompt)
      )
      _ <- ctx.forkTurn(
        cfg.resources.llm
          .send(request)
          .flatMap { resp =>
            val newMemory = resp.reply.trim
            if newMemory.nonEmpty then
              FlowMemoryStore.save(cfg.flowName, newMemory) *>
                logger.info(s"[${cfg.name}] Flow memory updated (${newMemory.length} chars)")
            else logger.warn(s"[${cfg.name}] Reflect returned empty memory, skipping save")
          }
          .handleErrorWith(e => logger.warn(s"[${cfg.name}] Reflect failed: ${e.getMessage}").void)
      )
      // Trigger evolution: analyze flow definition for improvements
      _ <- ctx.forkTurn(
        FlowEvolver
          .runEvolution(
            cfg.flowName,
            cfg.resources.llm,
            cfg.sessionId,
            cfg.name,
            stepSummary,
            passed,
            state.iteration,
            cfg.flowMemory
          )
          .handleErrorWith(e => logger.warn(s"[${cfg.name}] Evolution failed: ${e.getMessage}").void)
      )
    yield ()

  private def buildReflectPrompt(
    flowName: String,
    input: String,
    stepSummary: String,
    verifySummary: String,
    passed: Boolean,
    currentMemory: String
  ): String =
    val status = if passed then "PASS" else "FAIL"
    val memBlock = if currentMemory.isBlank then "(empty — first run)" else currentMemory
    s"""Analyze this pipeline run and update the flow memory.

Flow: $flowName
Input: ${input.take(2000)}
Result: $status — $verifySummary

$stepSummary

=== Current Flow Memory ===
$memBlock
=== End Memory ===

Produce the updated memory file:"""

  end buildReflectPrompt

  private def buildStepSummary(
    results: Map[String, String],
    failedReasons: Map[String, String],
    stepStatus: Map[String, String]
  ): String =
    val sb = new StringBuilder("=== Step Results ===\n")
    val allIds = (results.keys ++ failedReasons.keys).toSeq.sorted
    allIds.foreach { id =>
      stepStatus.get(id) match
        case Some(s) if s == StepStatus.Failed.toString =>
          sb.append(s"[$id] FAILED: ${failedReasons.getOrElse(id, "unknown").take(500)}\n")
        case _ =>
          val output = results.getOrElse(id, "")
          sb.append(s"[$id] ${output.take(800)}\n")
    }
    sb.toString

  end buildStepSummary

  private[flow] def resolveTemplate(prompt: String, results: Map[String, String]): String =
    results.foldLeft(prompt) { case (p, (id, output)) =>
      val truncated =
        if output.length > MaxStepOutputChars
        then output.take(MaxStepOutputChars) + s"\n... (truncated, ${output.length} chars total)"
        else output
      p.replace("${" + id + "}", truncated)
    }

  private[flow] def buildNodeResultsContext(
    results: Map[String, String],
    failedReasons: Map[String, String],
    stepStatus: Map[String, String]
  ): String =
    val sb = new StringBuilder("=== Step Results ===\n\n")
    val allIds = (results.keys ++ failedReasons.keys).toSeq.sorted
    allIds.foreach { id =>
      stepStatus.get(id) match
        case Some(s) if s == StepStatus.Failed.toString =>
          val reason = failedReasons.getOrElse(id, "unknown error")
          sb.append(s"[$id] [FAILED]\n$reason\n\n")
        case _ =>
          val output = results.getOrElse(id, "")
          val truncated = if output.length > 3000 then output.take(3000) + "\n... (truncated)" else output
          sb.append(s"[$id]\n$truncated\n\n")
    }
    sb.append("=== End Results ===\n")
    sb.toString

  end buildNodeResultsContext

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst { case msg if msg.role == MessageRole.Assistant => msg.textContent }
      .filter(_.nonEmpty)
      .getOrElse("")

  private def routeWsSend(
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    flowStepId: Option[String] = None,
    nodeSessionId: Option[String] = None
  ): Json => IO[Unit] =
    val base = wsSend.getOrElse((_: Json) => IO.unit)
    val patches = List(
      sessionId.map(sid => Json.obj("sessionId" -> sid.asJson)),
      flowStepId.map(fid => Json.obj("flowStepId" -> fid.asJson)),
      // Inject nodeSessionId so the recording wrapper can persist sub-agent
      // events (agentTextDelta / agentToolEnd / agentDone) into the flow node's
      // own session history — not the parent session.
      nodeSessionId.map(nsid => Json.obj("nodeSessionId" -> nsid.asJson))
    ).flatten
    patches match
      case Nil => base
      case _ => json => base(patches.foldLeft(json)((j, p) => j.deepMerge(p)))

  /** Persist current state to disk for frontend status queries. */
  private def saveState(
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          state <- stateRef.get
          stepInfos = cfg.flowDef.nodes.map { node =>
            PipelineStateStore.StepInfo(
              id = node.id,
              agent = node.agent.getOrElse(""),
              status = state.stepStatus.getOrElse(node.id, "Pending"),
              dependsOn = node.dependsOn.toList,
              nodeSessionId = state.nodeSessionIds.get(node.id),
              verdict = node.verdict,
              condition = node.condition,
              retryTarget = node.retry.map(_.target),
              retryMaxIterations = node.retry.map(_.maxIterations)
            )
          }
          pipelineState = PipelineStateStore.PipelineState(
            name = cfg.name,
            flowName = cfg.flowName,
            phase = state.phase.toString,
            steps = stepInfos,
            results = state.results,
            iteration = state.iteration,
            verifyResult = state.verifyResult,
            verifyAgent = cfg.flowDef.nodes.find(_.verdict).flatMap(_.agent).getOrElse(""),
            maxIterations = cfg.flowDef.nodes.flatMap(_.retry).headOption.map(_.maxIterations).getOrElse(0),
            triggerInput = if state.triggerInput.nonEmpty then Some(state.triggerInput) else None
          )
          _ <- PipelineStateStore.save(sid, pipelineState)
        yield ()
      case _ => IO.unit

  private def emit(
    cfg: PipelineConfig,
    eventName: String,
    fields: (String, Json)*
  )(using ctx: ActorContext[?]): IO[Unit] =
    ctx.forkTurn(
      cfg.wsSend match
        case Some(send) =>
          val all = ("type", eventName.asJson) :: ("sessionId", cfg.sessionId.getOrElse("").asJson) :: fields.toList
          val json = Json.fromJsonObject(JsonObject.fromIterable(all))
          logger.info(s"emit: $eventName sessionId=${cfg.sessionId.getOrElse("")}") *>
            send(json)
        case None =>
          logger.warn(s"emit: $eventName but wsSend is None!") *> IO.unit
    )

end PipelineActor
