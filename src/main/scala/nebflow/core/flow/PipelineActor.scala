package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, ReadTracker}
import nebflow.shared.{LlmRequest, Message, MessageRole}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

// ============================================================
// PipelineActor — independent actor for pipeline execution
// ============================================================

object PipelineActor:
  private val logger = NebflowLogger.forName("nebflow.flow.pipeline")

  private val ManagerId = "__manager__"
  private val MaxConcurrency = 5

  val VerifyPromptPreamble =
    """You are the verification step of this workflow. Analyze the step results against the verification criteria below.

At the END of your response, you MUST include a verdict line in exactly this format:
  VERDICT: PASS
or
  VERDICT: FAIL: <one-line reason>

The verdict line is how the pipeline detects your result — without it, verification fails.
Do NOT call any tools to report the result. Just output the verdict line at the end of your analysis.

--- Verification Criteria ---
""".stripMargin

  private val ManagerPromptPreamble =
    """You are the manager of this workflow. All steps have completed. Review the results below and provide a concise summary of what was accomplished, any issues encountered, and the overall outcome.

--- Step Results ---
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
    agentPaths: Map[String, String] = Map.empty,
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
                    "dependsOn" -> n.dependsOn.toList.asJson
                  )
                )
                .asJson
            )
            _ <- logger.info(s"[${cfg.name}] Triggered with input (${input.length} chars)")
            _ <- scheduleReadySteps(ctx, stateRef, cfg)
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
      slots = (MaxConcurrency - runningCount).max(0)
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
      reject("Nested flow execution not yet implemented")
    else
      for
        state <- stateRef.get
        resolvedPrompt = resolveTemplate(
          node.prompt.getOrElse(""),
          state.results ++ Map("input" -> state.triggerInput)
        )
        promptWithPreamble =
          if node.verdict then VerifyPromptPreamble + resolvedPrompt
          else resolvedPrompt
        defOpt <- cfg.resources.agentLibrary.get(agentName)
        _ <- defOpt match
          case None =>
            reject(s"Agent '$agentName' not found")
          case Some(agentDef) =>
            val agentUid = s"pipe-${cfg.name.take(20)}-${node.id}-${java.util.UUID.randomUUID().toString.take(8)}"
            for
              readTracker <- ReadTracker.create
              fileHistory <- FileHistory.create()
              childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(node.id))
              peerInfo = buildPeerInfo(node.id, state.agentPaths, cfg.name)
              actualPrompt = withMemory(peerInfo + promptWithPreamble, cfg)
              agentRef <- ctx.system.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = cfg.resources,
                  wsSend = childWs,
                  depth = 1,
                  parentRef = Some(cfg.parentAgentRef),
                  sessionId = cfg.sessionId,
                  sessionName = Some(s"${cfg.name}/${node.id}"),
                  readTracker = Some(readTracker),
                  fileHistory = Some(fileHistory),
                  contextWindow = cfg.resources.contextWindow,
                  projectRoot = Some(cfg.projectRoot),
                  safetyMode = cfg.safetyMode,
                  expectsMail = cfg.expectsMail
                ),
                agentUid
              )
              _ <- stateRef.update(s =>
                s.copy(
                  stepStatus = s.stepStatus + (node.id -> StepStatus.Running.toString),
                  nodeExecCount = s.nodeExecCount.updatedWith(node.id)(v => Some(v.getOrElse(0) + 1)),
                  agentPaths = s.agentPaths + (node.id -> agentRef.path.toString)
                )
              )
              adapterRef <- ctx.spawn(
                stepAdapter(ctx.self, node.id, agentRef, node.timeout),
                s"$agentUid-adapter"
              )
              _ <- FlowMembership.join(agentRef.path.toString, cfg.name)
              _ <- emit(
                cfg,
                "flowStepStarted",
                "branchName" -> cfg.name.asJson,
                "stepId" -> node.id.asJson,
                "agentName" -> agentName.asJson
              )
              _ <- agentRef ! AgentCommand.UserInput(actualPrompt, Some(adapterRef))
              _ = logger.info(s"[${cfg.name}] Spawned '${node.id}' ($agentName)")
            yield ()
            end for
      yield ()
      end for
  end spawnStep

  // ============================================================
  // Step completion / failure
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
          // Manager completed — its output IS the final summary
          stateRef.update(s => s.copy(verifyResult = Some(output))) *>
            completePipeline(ctx, stateRef, cfg, output)
        else if state.stepStatus.get(stepId).contains(StepStatus.Running.toString) then
          val nodeOpt = cfg.flowDef.nodes.find(_.id == stepId)
          for
            // Mark as Done, store result
            _ <- stateRef.update(s =>
              s.copy(
                results = s.results + (stepId -> output),
                stepStatus = s.stepStatus + (stepId -> StepStatus.Done.toString)
              )
            )
            // Parse verdict if applicable
            _ <- nodeOpt.filter(_.verdict) match
              case Some(node) =>
                val vr = parseVerdict(output)
                for
                  _ <- stateRef.update(s =>
                    s.copy(verdicts = s.verdicts + (node.id -> vr.pass))
                  )
                  _ <- emit(
                    cfg,
                    "flowVerifyResult",
                    "branchName" -> cfg.name.asJson,
                    "pass" -> vr.pass.asJson,
                    "summary" -> vr.summary.take(500).asJson
                  )
                yield ()
              case None => IO.unit
            // Standard completion events
            _ <- emit(cfg, "flowStepCompleted", "branchName" -> cfg.name.asJson, "stepId" -> stepId.asJson)
            _ <- state.replyTo.traverse_(_ ! PipelineEvent.Progress(stepId, "Done", output.take(200)))
            _ = logger.info(s"[${cfg.name}] Step '$stepId' completed (${output.length} chars)")
            // Retry / loop-back logic
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
    for
      state <- stateRef.get
      _ <-
        if stepId == ManagerId then
          failPipeline(ctx, stateRef, cfg, s"Manager agent failed: $error")
        else if state.stepStatus.get(stepId).contains(StepStatus.Running.toString) then
          // Check if any pending step depends on this one
          val hasDependers = cfg.flowDef.nodes.exists(n =>
            n.dependsOn.contains(stepId) &&
              state.stepStatus.get(n.id).contains(StepStatus.Pending.toString)
          )
          if hasDependers then failPipeline(ctx, stateRef, cfg, s"Step '$stepId' failed, dependents cannot run")
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
                "error" -> error.asJson
              )
              _ <- state.replyTo.traverse_(_ ! PipelineEvent.Progress(stepId, "Failed", error))
            yield ()
          end if
        else IO.unit
    yield ()

  private def afterStepUpdate(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      _ <-
        if state.phase != RunPhase.Running then IO.unit
        else
          val managerRunning = state.stepStatus.contains(ManagerId)
          val allDone = cfg.flowDef.nodes.forall(n =>
            state.stepStatus.get(n.id).exists(x =>
              x == StepStatus.Done.toString || x == StepStatus.Failed.toString
            )
          )
          if allDone && !managerRunning then
            runManagerSummary(ctx, stateRef, cfg)
          else scheduleReadySteps(ctx, stateRef, cfg)
    yield ()

  // ============================================================
  // Manager summary
  // ============================================================

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
          _ <- completePipeline(ctx, stateRef, cfg, summary)
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
    summary: String
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      _ <- stateRef.update(s => s.copy(phase = RunPhase.Completed))
      _ <- emit(
        cfg,
        "flowCompleted",
        "branchName" -> cfg.name.asJson,
        "pass" -> true.asJson,
        "summary" -> summary.take(500).asJson
      )
      // Notify parent agent of completion
      _ <- cfg.parentAgentRef ! AgentCommand.ExternalEvent(
        source = "flow",
        eventType = "completed",
        payload = s"[Flow: ${cfg.name}] PASS\n$summary",
        metadata = JsonObject("flowName" -> cfg.name.asJson, "pass" -> true.asJson)
      )
      _ <- stateRef.get.flatMap(_.replyTo.traverse_(_ ! PipelineEvent.Done(summary)))
      _ <- stateRef.update(s => s.copy(phase = RunPhase.Idle, replyTo = None))
      // Launch reflect in background — non-blocking, fire-and-forget
      _ <- runReflect(stateRef, cfg, summary, passed = true)
      _ = logger.info(s"[${cfg.name}] Pipeline COMPLETED")
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
      // Learn from failures too — reflect in background
      _ <- runReflect(stateRef, cfg, reason, passed = false)
      _ = logger.warn(s"[${cfg.name}] Pipeline FAILED: $reason")
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
              _ <- stateRef.update(s =>
                s.copy(stepStatus = s.stepStatus + (stepId -> StepStatus.Running.toString))
              )
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
                  expectsMail = cfg.expectsMail
                ),
                agentUid
              )
              _ <- FlowMembership.join(agentRef.path.toString, cfg.name)
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

        override def onStop(ctx: ActorContext[AgentEvent]): IO[Unit] =
          FlowMembership.leaveAll(subagentRef.path.toString)
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

  /** Build peer address info block for agent prompt injection. */
  private def buildPeerInfo(nodeId: String, agentPaths: Map[String, String], flowName: String): String =
    val peers = agentPaths.filter { (id, _) => id != nodeId }
    if peers.isEmpty then ""
    else
      val lines = peers.map { (id, path) => s"- $id: $path" }.mkString("\n")
      s"""=== Flow Communication ===
         |You are node "$nodeId" in flow "$flowName". You can communicate with these peers via Mail:
         |$lines
         |
         |When you complete your work, use Mail(type=message) to send your result to relevant peers.
         |If you are a verify node, use Mail(type=verify, passed=..., summary=...) to report the verdict.
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

  /** Parse VERDICT line from verify agent output to determine pass/fail. */
  private val VerdictRegex = "(?i)VERDICT:\\s*(PASS|FAIL)\\s*:?\\s*(.*)".r

  private def parseVerdict(text: String): VerifyResult =
    text.linesIterator
      .collect { case VerdictRegex(status, reason) =>
        val passed = status.equalsIgnoreCase("PASS")
        val summary = reason.trim match
          case "" => if passed then "Verification passed." else "Verification failed."
          case r => r
        VerifyResult(passed, summary)
      }
      .toList
      .lastOption
      .getOrElse(VerifyResult(false, "No VERDICT line found in verify agent output."))

  private def routeWsSend(
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    flowStepId: Option[String] = None
  ): Json => IO[Unit] =
    val base = wsSend.getOrElse((_: Json) => IO.unit)
    val patches = List(
      sessionId.map(sid => Json.obj("sessionId" -> sid.asJson)),
      flowStepId.map(fid => Json.obj("flowStepId" -> fid.asJson))
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
              dependsOn = node.dependsOn.toList
            )
          }
          pipelineState = PipelineStateStore.PipelineState(
            name = cfg.name,
            flowName = cfg.flowName,
            phase = state.phase.toString,
            steps = stepInfos,
            results = state.results,
            iteration = state.iteration,
            verifyResult = state.verifyResult
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
