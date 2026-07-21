package nebflow.core.flow

import cats.effect.{Deferred, IO, Ref}
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
  private val logger = NebflowLogger(getClass)

  private val VerifyId = "__verify__"
  private val FixId = "__fix__"

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

    /** Verify agent reported result via Mail tool. */
    case class VerifyCompleted(passed: Boolean, summary: String) extends PipelineCommand

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
    retryLeft: Map[String, Int] = Map.empty,
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
    pipeline: BranchType.Pipeline,
    parentAgentRef: ActorRef[AgentCommand],
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    resources: SharedResources,
    projectRoot: String,
    safetyMode: String,
    flowMemory: String = "",
    gatewayPort: Int = 8080
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

          case PipelineCommand.VerifyCompleted(passed, summary) =>
            for
              _ <- handleVerifyCompleted(ctx, stateRef, cfg, passed, summary)
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
          val initialStepStatus = cfg.pipeline.steps.map(s => s.id -> StepStatus.Pending.toString).toMap
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
              "steps" -> cfg.pipeline.steps
                .map(s =>
                  Json.obj(
                    "id" -> s.id.asJson,
                    "agent" -> s.agent.getOrElse("").asJson,
                    "dependsOn" -> s.dependsOn.toList.asJson
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
      ready = cfg.pipeline.steps.filter { step =>
        state.stepStatus.get(step.id).contains(StepStatus.Pending.toString) &&
        step.dependsOn.forall(dep => state.stepStatus.get(dep).contains(StepStatus.Done.toString))
      }
      // Count running steps (those with Running status)
      runningCount = state.stepStatus.count(_._2 == StepStatus.Running.toString)
      slots = (cfg.pipeline.maxConcurrency - runningCount).max(0)
      toStart = ready.take(slots)
      _ <- toStart.traverse_(step => spawnStep(ctx, stateRef, cfg, step))
      _ =
        if toStart.nonEmpty then logger.info(s"[${cfg.name}] Scheduled: ${toStart.map(_.id).mkString(", ")}")
    yield ()

  private def spawnStep(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    step: PipelineStep
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    val agentName = step.agent.getOrElse("")
    for
      state <- stateRef.get
      resolvedPrompt = resolveTemplate(step.prompt.getOrElse(""), state.results ++ Map("input" -> state.triggerInput))
      defOpt <- cfg.resources.agentLibrary.get(agentName)
      _ <- defOpt match
        case None =>
          // Mark as Running so handleStepFailed processes the failure
          // (otherwise the step stays Pending and the pipeline hangs forever)
          stateRef.update(s => s.copy(stepStatus = s.stepStatus + (step.id -> StepStatus.Running.toString))) *> IO(
            ctx.self ! PipelineCommand.StepFailed(step.id, s"Agent '$agentName' not found")
          )
        case Some(agentDef) =>
          val agentUid = s"pipe-${cfg.name.take(20)}-${step.id}-${java.util.UUID.randomUUID().toString.take(8)}"
          for
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(step.id))
            st <- stateRef.get
            actualPrompt = withMemory(resolvedPrompt, cfg)
            _ <- stateRef.update(s =>
              s.copy(
                stepStatus = s.stepStatus + (step.id -> StepStatus.Running.toString),
                retryLeft = s.retryLeft + (step.id -> step.retry)
              )
            )
            agentRef <- ctx.system.spawn(
              AgentActor(
                agentDef = agentDef,
                resources = cfg.resources,
                wsSend = childWs,
                depth = 1,
                parentRef = Some(cfg.parentAgentRef),
                sessionId = cfg.sessionId,
                sessionName = Some(s"${cfg.name}/${step.id}"),
                readTracker = Some(readTracker),
                fileHistory = Some(fileHistory),
                contextWindow = cfg.resources.contextWindow,
                projectRoot = Some(cfg.projectRoot),
                safetyMode = cfg.safetyMode
              ),
              agentUid
            )
            adapterRef <- ctx.spawn(
              stepAdapter(ctx.self, cfg.name, step.id, agentRef, step.timeout, isVerify = false, ""),
              s"$agentUid-adapter"
            )
            _ <- FlowMembership.join(agentRef.path.toString, cfg.name)
            _ <- emit(
              cfg,
              "flowStepStarted",
              "branchName" -> cfg.name.asJson,
              "stepId" -> step.id.asJson,
              "agentName" -> agentName.asJson
            )
            _ <- agentRef ! AgentCommand.UserInput(actualPrompt, Some(adapterRef))
            _ = logger.info(s"[${cfg.name}] Spawned '${step.id}' ($agentName)")
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
        if stepId == FixId then
          // Fix completed → re-verify
          for
            _ <- stateRef.update(s =>
              s.copy(
                stepStatus = s.stepStatus + (FixId -> StepStatus.Done.toString)
              )
            )
            _ <- runVerify(ctx, stateRef, cfg)
          yield ()
        else if state.stepStatus.get(stepId).contains(StepStatus.Running.toString) then
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
          yield ()
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
        if stepId == FixId then failPipeline(ctx, stateRef, cfg, s"Fix step failed: $error")
        else if stepId == VerifyId then handleVerifyFail(ctx, stateRef, cfg, s"Verify error: $error")
        else if state.stepStatus.get(stepId).contains(StepStatus.Running.toString) then
          val retries = state.retryLeft.getOrElse(stepId, 0)
          if retries > 0 then
            val step = cfg.pipeline.steps.find(_.id == stepId).getOrElse(cfg.pipeline.steps.head)
            for
              _ <- stateRef.update(s =>
                s.copy(
                  retryLeft = s.retryLeft + (stepId -> (retries - 1))
                )
              )
              _ <- logger.info(s"[${cfg.name}] Step '$stepId' failed ($error), retrying (${retries - 1} left)")
              _ <- spawnStep(ctx, stateRef, cfg, step)
            yield ()
          else
            // Retries exhausted — check if any step depends on this one
            val hasDependers = cfg.pipeline.steps.exists(s =>
              s.dependsOn.contains(stepId) &&
                state.stepStatus.get(s.id).contains(StepStatus.Pending.toString)
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
          val allDone = cfg.pipeline.steps.forall(s =>
            state.stepStatus.get(s.id).exists(x => x == StepStatus.Done.toString || x == StepStatus.Failed.toString)
          ) && !state.stepStatus.contains(VerifyId) && !state.stepStatus.contains(FixId)
          if allDone && !state.stepStatus.contains(VerifyId) then runVerify(ctx, stateRef, cfg)
          else scheduleReadySteps(ctx, stateRef, cfg)
    yield ()

  // ============================================================
  // Verify handling
  // ============================================================

  private def runVerify(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      contextBlock = buildVerifyContext(state.results, state.failedReasons, state.stepStatus)
      prompt = s"$contextBlock\n\n${cfg.pipeline.verify.prompt}"
      _ <- emit(
        cfg,
        "flowStepStarted",
        "branchName" -> cfg.name.asJson,
        "stepId" -> VerifyId.asJson,
        "agentName" -> cfg.pipeline.verify.agent.asJson
      )
      _ <- spawnById(
        ctx,
        stateRef,
        cfg,
        cfg.pipeline.verify.agent,
        VerifyPromptPreamble + withMemory(prompt, cfg),
        VerifyId,
        cfg.pipeline.verify.timeout,
        isVerify = true
      )
    yield ()

  private def handleVerifyCompleted(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    passed: Boolean,
    summary: String
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      state <- stateRef.get
      _ <- stateRef.update(s =>
        s.copy(
          verifyResult = Some(summary),
          stepStatus = s.stepStatus + (VerifyId -> StepStatus.Done.toString)
        )
      )
      _ <- emit(
        cfg,
        "flowVerifyResult",
        "branchName" -> cfg.name.asJson,
        "pass" -> passed.asJson,
        "summary" -> summary.take(500).asJson,
        "iteration" -> state.iteration.asJson
      )
      _ <-
        if passed then completePipeline(ctx, stateRef, cfg, summary)
        else handleVerifyFail(ctx, stateRef, cfg, summary)
    yield ()

  private def handleVerifyFail(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    reason: String
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    cfg.pipeline.loop match
      case Some(loop) =>
        for
          state <- stateRef.get
          _ <-
            if state.iteration < loop.maxIterations then
              for
                _ <- stateRef.update(s =>
                  s.copy(
                    iteration = s.iteration + 1,
                    stepStatus = s.stepStatus + (FixId -> StepStatus.Pending.toString)
                  )
                )
                _ <- emit(
                  cfg,
                  "flowLoopIteration",
                  "branchName" -> cfg.name.asJson,
                  "iteration" -> (state.iteration + 1).asJson,
                  "maxIterations" -> loop.maxIterations.asJson
                )
                _ <- logger.info(s"[${cfg.name}] Verify FAIL (iter ${state.iteration}), running fix")
                fixPrompt = resolveTemplate(loop.fix.prompt.getOrElse(""), state.results ++ Map("verify" -> reason))
                _ <- spawnById(
                  ctx,
                  stateRef,
                  cfg,
                  loop.fix.agent.getOrElse("Nebula"),
                  withMemory(fixPrompt, cfg),
                  FixId,
                  loop.fix.timeout,
                  isVerify = false
                )
              yield ()
            else failPipeline(ctx, stateRef, cfg, s"Verification failed after ${state.iteration} iteration(s): $reason")
        yield ()
      case None =>
        failPipeline(ctx, stateRef, cfg, s"Verification failed: $reason")

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
      // No ExternalEvent — verify agent's Mail(type=verify) already notified Main Agent
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
  // Spawn helper (shared by steps, verify, fix)
  // ============================================================

  private def spawnById(
    ctx: ActorContext[PipelineCommand],
    stateRef: Ref[IO, RunState],
    cfg: PipelineConfig,
    agentName: String,
    prompt: String,
    stepId: String,
    timeout: FiniteDuration,
    isVerify: Boolean
  )(using ActorContext[PipelineCommand]): IO[Unit] =
    for
      defOpt <- cfg.resources.agentLibrary.get(agentName)
      _ <- defOpt match
        case None =>
          ctx.self ! PipelineCommand.StepFailed(stepId, s"Agent '$agentName' not found")
          IO.unit
        case Some(agentDef) =>
          val agentUid = s"pipe-${cfg.name.take(20)}-$stepId-${java.util.UUID.randomUUID().toString.take(8)}"
          for
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(stepId))
            _ <- stateRef.update(s =>
              s.copy(
                stepStatus = s.stepStatus + (stepId -> StepStatus.Running.toString)
              )
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
                safetyMode = cfg.safetyMode
              ),
              agentUid
            )
            _ <- FlowMembership.join(agentRef.path.toString, cfg.name)
            adapterRef <- ctx.spawn(
              stepAdapter(ctx.self, cfg.name, stepId, agentRef, timeout, isVerify, agentRef.path.toString),
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
    pipeName: String,
    stepId: String,
    subagentRef: ActorRef[AgentCommand],
    timeout: FiniteDuration,
    isVerify: Boolean,
    verifyAgentPath: String
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
              done.set(true) *>
                (if isVerify then
                   val text = extractLastAssistantText(messages)
                   val verdict = parseVerdict(text)
                   for
                     _ <- logger.info(s"stepAdapter: verify verdict=$verdict")
                     _ <- pipeRef ! PipelineCommand.VerifyCompleted(verdict.pass, verdict.summary)
                   yield Behaviors.stopped[AgentEvent]
                 else
                   val text = extractLastAssistantText(messages)
                   for _ <- pipeRef ! PipelineCommand.StepCompleted(stepId, text)
                   yield Behaviors.stopped[AgentEvent])

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
          FlowMembership.leaveAll(subagentRef.path.toString))
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

  private[flow] def buildVerifyContext(
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

  end buildVerifyContext

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
          stepInfos = cfg.pipeline.steps.map { step =>
            PipelineStateStore.StepInfo(
              id = step.id,
              agent = step.agent.getOrElse(""),
              status = state.stepStatus.getOrElse(step.id, "Pending"),
              dependsOn = step.dependsOn.toList
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
