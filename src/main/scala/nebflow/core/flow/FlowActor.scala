package nebflow.core.flow

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, ReadTracker, ToolRegistry}
import nebflow.shared.{Message, MessageRole}

import scala.concurrent.duration.FiniteDuration

object FlowActor:
  private val logger = NebflowLogger(getClass)

  sealed trait FlowCommand
  case class StepCompleted(stepId: String, output: String) extends FlowCommand
  case class StepFailed(stepId: String, error: String) extends FlowCommand
  case class VerifyCompleted(passed: Boolean, summary: String) extends FlowCommand
  case class StopFlow(reason: String = "") extends FlowCommand

  private val VerifyId = "__verify__"
  private val FixId = "__fix__"

  val VerifyPromptPreamble =
    """You are the verification step of this workflow.
      |
      |You MUST call the FlowVerify tool to report your result:
      |  - passed=true if the work meets all requirements
      |  - passed=false if there are issues
      |  - Include a concise summary of findings
      |
      |If you finish without calling FlowVerify, the verification will fail and retry.
      |
      |--- Verification Criteria ---
      |""".stripMargin

  // ============================================================
  // Factory
  // ============================================================

  def apply(
    flowDef: FlowDef,
    flowId: String,
    parentAgentRef: ActorRef[AgentCommand],
    wsSend: Option[Json => IO[Unit]],
    parentSessionId: Option[String],
    parentDepth: Int,
    resources: SharedResources,
    projectRoot: String,
    bypass: Boolean,
    restoreSnapshot: Option[FlowSnapshot] = None
  ): Behavior[FlowCommand] =
    Behaviors.setup { ctx =>
      given ActorContext[FlowCommand] = ctx

      val cfg = FlowConfig(
        flowDef,
        flowId,
        parentAgentRef,
        wsSend,
        parentSessionId,
        parentDepth,
        resources,
        projectRoot,
        bypass
      )
      val stateRef = Ref.unsafe[IO, FlowState](FlowState.empty(flowDef))

      // Restore from snapshot if provided (graceful restart recovery)
      val restoreIO = restoreSnapshot match
        case Some(snap) =>
          val restoredStatus = snap.stepStatus.view.mapValues {
            case "Running" => StepStatus.Pending  // interrupted steps → re-run
            case other => StepStatus.valueOf(other)
          }.toMap
          for
            _ <- stateRef.update(_.copy(
              results = snap.results,
              failedReasons = snap.failedReasons,
              stepStatus = restoredStatus,
              retryLeft = snap.retryLeft,
              verifyResult = snap.verifyResult,
              iteration = snap.iteration,
              phase = FlowPhase.valueOf(snap.phase) match
                case FlowPhase.Working => FlowPhase.Working
                case FlowPhase.VerifyRunning => FlowPhase.Working  // re-verify after working
                case FlowPhase.LoopFixing => FlowPhase.Working     // go back to working
                case terminal => terminal
            ))
            _ <- logger.info(s"Flow '${flowDef.name}' restored from snapshot (phase=${snap.phase}, steps=${restoredStatus.size})")
          yield ()
        case None => IO.unit

      for
        _ <- restoreIO
        _ <- FlowMembership.join(parentAgentRef.path.toString, flowId)
        _ <- emit(
          wsSend,
          parentSessionId,
          "flowStarted",
          "flowName" -> flowDef.name.asJson,
          "totalSteps" -> flowDef.steps.length.asJson,
          "steps" -> flowDef.steps
            .map(s =>
              Json.obj(
                "id" -> s.id.asJson,
                "agent" -> s.agent.asJson,
                "dependsOn" -> s.dependsOn.toList.asJson
              )
            )
            .asJson,
          "hasLoop" -> flowDef.loop.isDefined.asJson,
          "maxIterations" -> flowDef.loop.map(_.maxIterations).getOrElse(0).asJson,
          "verifyAgent" -> flowDef.verify.agent.asJson
        )
        _ <- logger.info(
          s"Flow '${flowDef.name}' started: ${flowDef.steps.length} steps, verify=${flowDef.verify.agent}"
        )
        _ <- scheduleReadySteps(ctx, stateRef, cfg)
      yield running(ctx, stateRef, cfg)
      end for
    }

  // ============================================================
  // Running behavior
  // ============================================================

  private def running(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig
  ): Behavior[FlowCommand] =
    given ActorContext[FlowCommand] = ctx

    new Behavior[FlowCommand]:
      def receive(ctx: ActorContext[FlowCommand], msg: FlowCommand): IO[Behavior[FlowCommand]] =
        msg match
          case StepCompleted(stepId, output) =>
            for
              state <- stateRef.get
              done <- state.stepStatus.get(stepId) match
                case Some(StepStatus.Running) =>
                  for
                    _ <- handleCompleted(ctx, stateRef, cfg, stepId, output, state)
                    _ <- afterStepUpdate(ctx, stateRef, cfg)
                  yield false
                case _ => IO.pure(false) // ignore duplicate
              st <- stateRef.get
            yield
              if st.phase == FlowPhase.Completed || st.phase == FlowPhase.Failed
              then Behaviors.stopped[FlowCommand]
              else this

          case StepFailed(stepId, error) =>
            for
              state <- stateRef.get
              _ <- state.stepStatus.get(stepId) match
                case Some(StepStatus.Running) =>
                  for
                    _ <- handleFailed(ctx, stateRef, cfg, stepId, error, state)
                    _ <- afterStepUpdate(ctx, stateRef, cfg)
                  yield ()
                case _ => IO.unit // ignore duplicate
              st <- stateRef.get
            yield
              if st.phase == FlowPhase.Completed || st.phase == FlowPhase.Failed
              then Behaviors.stopped[FlowCommand]
              else this

          case VerifyCompleted(passed, summary) =>
            for
              state <- stateRef.get
              _ <- state.stepStatus.get(VerifyId) match
                case Some(StepStatus.Running) =>
                  for
                    _ <- stateRef.update(s =>
                      s.copy(
                        verifyResult = Some(summary),
                        stepStatus = s.stepStatus + (VerifyId -> StepStatus.Done),
                        runningAgents = s.runningAgents - VerifyId,
                        phase = if passed then FlowPhase.Completed else s.phase
                      )
                    )
                    _ <- emit(
                      cfg.wsSend,
                      cfg.sessionId,
                      "flowVerifyResult",
                      "pass" -> passed.asJson,
                      "summary" -> summary.take(500).asJson,
                      "iteration" -> state.iteration.asJson
                    )
                    _ <-
                      if passed then completeFlow(stateRef, cfg, summary)
                      else handleVerifyFail(ctx, stateRef, cfg, summary, state)
                  yield ()
                case _ => IO.unit // ignore duplicate or unexpected
              st <- stateRef.get
            yield
              if st.phase == FlowPhase.Completed || st.phase == FlowPhase.Failed
              then Behaviors.stopped[FlowCommand]
              else this

          case StopFlow(reason) =>
            for
              _ <- stateRef.update(_.copy(phase = FlowPhase.Failed))
              _ <- cfg.parentAgentRef ! AgentCommand.ExternalEvent(
                source = "flow",
                eventType = "cancelled",
                payload = s"[Flow: ${cfg.flowDef.name}] Cancelled: $reason",
                metadata = JsonObject("flowName" -> cfg.flowDef.name.asJson)
              )
            yield Behaviors.stopped[FlowCommand]

      override def onStop(ctx: ActorContext[FlowCommand]): IO[Unit] =
        for
          state <- stateRef.get
          _ <- state.runningAgents.values.toList.traverse_(ref =>
            ctx.system.stop(ref) *> FlowMembership.leaveAll(ref.path.toString)
          )
          _ <- FlowMembership.leaveAll(cfg.parentAgentRef.path.toString)
          _ <- logger.info(s"Flow '${cfg.flowDef.name}' stopped, cleaned up ${state.runningAgents.size} agent(s)")
        yield ()
    end new
  end running

  // ============================================================
  // Step completion / failure
  // ============================================================

  private def handleCompleted(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    stepId: String,
    output: String,
    state: FlowState
  )(using ActorContext[FlowCommand]): IO[Unit] =
    if stepId == FixId then
      // Fix completed → re-run verify
      stateRef.update(s =>
        s.copy(
          stepStatus = s.stepStatus + (FixId -> StepStatus.Done),
          runningAgents = s.runningAgents - FixId,
          phase = FlowPhase.VerifyRunning
        )
      ) *> runVerify(ctx, stateRef, cfg, state.results)
    else
      // Normal work step completed
      for
        _ <- stateRef.update(s =>
          s.copy(
            results = s.results + (stepId -> output),
            stepStatus = s.stepStatus + (stepId -> StepStatus.Done),
            runningAgents = s.runningAgents - stepId
          )
        )
        _ <- emit(cfg.wsSend, cfg.sessionId, "flowStepCompleted", "stepId" -> stepId.asJson)
        _ <- logger.info(s"Step '$stepId' completed (${output.length} chars)")
      yield ()

  private def handleFailed(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    stepId: String,
    error: String,
    state: FlowState
  )(using ActorContext[FlowCommand]): IO[Unit] =
    if stepId == FixId then
      // Fix failed → flow fails immediately
      failFlow(stateRef, cfg, s"Fix step failed: $error")
    else if stepId == VerifyId then
      // Verify crashed/timed out → treat as FAIL
      handleVerifyFail(ctx, stateRef, cfg, s"Verify step error: $error", state)
    else
      // Work step failed — check retries
      val retries = state.retryLeft.getOrElse(stepId, 0)
      if retries > 0 then
        val step = cfg.flowDef.steps.find(_.id == stepId).getOrElse(cfg.flowDef.steps.head)
        for
          _ <- stateRef.update(s =>
            s.copy(
              retryLeft = s.retryLeft + (stepId -> (retries - 1)),
              runningAgents = s.runningAgents - stepId
            )
          )
          _ <- logger.info(s"Step '$stepId' failed ($error), retrying (${retries - 1} left)")
          _ <- spawnStep(ctx, stateRef, cfg, step, stepId)
        yield ()
      else
        // Retries exhausted
        for
          _ <- stateRef.update(s =>
            s.copy(
              stepStatus = s.stepStatus + (stepId -> StepStatus.Failed),
              failedReasons = s.failedReasons + (stepId -> error),
              runningAgents = s.runningAgents - stepId
            )
          )
          _ <- emit(cfg.wsSend, cfg.sessionId, "flowStepFailed", "stepId" -> stepId.asJson, "error" -> error.asJson)
          _ <- logger.warn(s"Step '$stepId' permanently failed: $error")
          // Check if any pending step depends on the failed one
          hasDependers = cfg.flowDef.steps.exists(s =>
            s.dependsOn.contains(stepId) &&
              state.stepStatus.get(s.id).contains(StepStatus.Pending)
          )
          _ <-
            if hasDependers then failFlow(stateRef, cfg, s"Step '$stepId' failed, dependents cannot run")
            else IO.unit
        yield ()
      end if

  // ============================================================
  // Post-step update: decide what to do next
  // ============================================================

  private def afterStepUpdate(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig
  )(using ActorContext[FlowCommand]): IO[Unit] =
    for
      state <- stateRef.get
      _ <-
        if state.phase == FlowPhase.Working then
          val allDone = cfg.flowDef.steps
            .forall(s => state.stepStatus.get(s.id).exists(x => x == StepStatus.Done || x == StepStatus.Failed))
          if allDone then
            stateRef.update(_.copy(phase = FlowPhase.VerifyRunning)) *>
              runVerify(ctx, stateRef, cfg, state.results)
          else scheduleReadySteps(ctx, stateRef, cfg)
        else if state.phase == FlowPhase.LoopFixing then
          // Fix just completed, verify already re-triggered by handleCompleted
          IO.unit
        else IO.unit // VerifyRunning / Completed / Failed — nothing to schedule
    yield ()

  // ============================================================
  // Verify handling
  // ============================================================

  private def handleVerifyFail(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    reason: String,
    state: FlowState
  )(using ActorContext[FlowCommand]): IO[Unit] =
    cfg.flowDef.loop match
      case Some(loop) if state.iteration < loop.maxIterations =>
        for
          _ <- stateRef.update(s =>
            s.copy(
              phase = FlowPhase.LoopFixing,
              iteration = s.iteration + 1,
              stepStatus = s.stepStatus ++ Map(FixId -> StepStatus.Pending)
            )
          )
          _ <- emit(
            cfg.wsSend,
            cfg.sessionId,
            "flowLoopIteration",
            "iteration" -> (state.iteration + 1).asJson,
            "maxIterations" -> loop.maxIterations.asJson
          )
          _ <- logger.info(s"Verify FAIL (iter ${state.iteration}), running fix")
          fixPrompt = resolveTemplate(loop.fix.prompt, state.results ++ Map("verify" -> reason))
          _ <- spawnById(ctx, stateRef, cfg, loop.fix.agent, fixPrompt, FixId, loop.fix.timeout)
        yield ()
      case _ =>
        failFlow(stateRef, cfg, s"Verification failed after ${state.iteration} iteration(s): $reason")

  private def runVerify(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    results: Map[String, String]
  )(using ActorContext[FlowCommand]): IO[Unit] =
    for
      state <- stateRef.get
      contextBlock = buildVerifyContext(results, state.failedReasons, state.stepStatus)
      prompt = s"$contextBlock\n\n${cfg.flowDef.verify.prompt}"
      _ <- emit(
        cfg.wsSend,
        cfg.sessionId,
        "flowStepStarted",
        "stepId" -> VerifyId.asJson,
        "agentName" -> cfg.flowDef.verify.agent.asJson
      )
      _ <- spawnById(ctx, stateRef, cfg, cfg.flowDef.verify.agent, prompt, VerifyId, cfg.flowDef.verify.timeout)
    yield ()

  // ============================================================
  // Scheduling
  // ============================================================

  private def scheduleReadySteps(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig
  )(using ActorContext[FlowCommand]): IO[Unit] =
    for
      state <- stateRef.get
      ready = cfg.flowDef.steps.filter { step =>
        state.stepStatus.get(step.id).contains(StepStatus.Pending) &&
        !state.runningAgents.contains(step.id) &&
        step.dependsOn.forall(dep => state.stepStatus.get(dep).contains(StepStatus.Done))
      }
      slots = (cfg.flowDef.maxConcurrency - state.runningAgents.size).max(0)
      toStart = ready.take(slots)
      _ <- toStart.traverse_(step => spawnStep(ctx, stateRef, cfg, step, step.id))
      _ = if toStart.nonEmpty then logger.info(s"Scheduled: ${toStart.map(_.id).mkString(", ")}")
      else ()
    yield ()

  // ============================================================
  // Agent spawning
  // ============================================================

  private def spawnStep(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    step: FlowStep,
    stepId: String
  )(using ActorContext[FlowCommand]): IO[Unit] =
    for
      state <- stateRef.get
      resolved = resolveTemplate(step.prompt, state.results)
      _ <- spawnById(ctx, stateRef, cfg, step.agent, resolved, stepId, step.timeout)
      _ <- stateRef.update(s => s.copy(retryLeft = s.retryLeft + (stepId -> step.retry)))
    yield ()

  private def spawnById(
    ctx: ActorContext[FlowCommand],
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    agentName: String,
    prompt: String,
    stepId: String,
    timeout: FiniteDuration
  )(using ActorContext[FlowCommand]): IO[Unit] =
    for
      defOpt <- cfg.resources.agentLibrary.get(agentName)
      _ <- defOpt match
        case None =>
          // Agent not found → notify self of failure
          ctx.self ! StepFailed(stepId, s"Agent '$agentName' not found")
          IO.unit
        case Some(agentDef) =>
          val isVerify = stepId == VerifyId
          val agentUid = s"flow-${cfg.flowDef.name.take(16)}-$stepId-${java.util.UUID.randomUUID().toString.take(8)}"
          for
            // Verify setup: create Deferred now, register AFTER spawn with agent's real path
            verifyDeferred <-
              if isVerify then Deferred[IO, VerifyResult].map(Some(_))
              else IO.pure(None)
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            childDepth = cfg.parentDepth + 1
            childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(stepId))
            // For verify: ensure FlowVerify tool is available (excluded from * expansion)
            actualDef =
              if isVerify then
                agentDef.tools match
                  case List("*") => agentDef.copy(tools = ToolRegistry.builtinToolNames)
                  case tools => agentDef.copy(tools = (tools :+ "FlowVerify").distinct)
              else agentDef
            // For verify: prepend fixed preamble; for others: inject team roster
            st <- stateRef.get
            actualPrompt =
              if isVerify then VerifyPromptPreamble + prompt
              else buildTeamRoster(cfg, st) + prompt
            // Mark as running BEFORE spawn so duplicate messages are ignored
            _ <- stateRef.update(s =>
              s.copy(
                stepStatus = s.stepStatus + (stepId -> StepStatus.Running)
              )
            )
            agentRef <- ctx.system.spawn(
              AgentActor(
                agentDef = actualDef,
                resources = cfg.resources,
                wsSend = childWs,
                depth = childDepth,
                parentRef = Some(cfg.parentAgentRef),
                sessionId = cfg.sessionId,
                sessionName = Some(s"${cfg.flowDef.name}/$stepId"),
                readTracker = Some(readTracker),
                fileHistory = Some(fileHistory),
                contextWindow = cfg.resources.contextWindow,
                projectRoot = Some(cfg.projectRoot),
                bypass = cfg.bypass
              ),
              agentUid
            )
            // Register verify Deferred with agent's full path (must match FlowVerifyTool's ctx.agentActorRef.path.toString)
            _ <- verifyDeferred match
              case Some(d) => FlowVerifyRegistry.register(agentRef.path.toString, d)
              case None => IO.unit
            adapterRef <- ctx.spawn(
              stepAdapter(ctx.self, stepId, agentRef, timeout, isVerify, agentRef.path.toString),
              s"$agentUid-adapter"
            )
            _ <- stateRef.update(s => s.copy(runningAgents = s.runningAgents + (stepId -> agentRef)))
            _ <- FlowMembership.join(agentRef.path.toString, cfg.flowId)
            _ <- saveSnapshot(stateRef, cfg)
            _ <- emit(
              cfg.wsSend,
              cfg.sessionId,
              "flowStepStarted",
              "stepId" -> stepId.asJson,
              "agentName" -> agentName.asJson
            )
            _ <- agentRef ! AgentCommand.UserInput(actualPrompt, Some(adapterRef))
            _ = logger.info(s"Spawned '$stepId' ($agentName, depth=$childDepth)")
          yield ()
          end for
    yield ()

  // ============================================================
  // Flow result helpers
  // ============================================================

  private def completeFlow(
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    summary: String
  )(using ctx: ActorContext[?]): IO[Unit] =
    for
      _ <- stateRef.update(_.copy(phase = FlowPhase.Completed))
      _ <- saveSnapshot(stateRef, cfg)
      _ <- emit(
        cfg.wsSend,
        cfg.sessionId,
        "flowCompleted",
        "pass" -> true.asJson,
        "summary" -> summary.take(500).asJson
      )
      _ <- cfg.parentAgentRef ! AgentCommand.ExternalEvent(
        source = "flow",
        eventType = "completed",
        payload = s"[Flow: ${cfg.flowDef.name}] PASS\n$summary",
        metadata = JsonObject("flowName" -> cfg.flowDef.name.asJson)
      )
      _ <- logger.info(s"Flow '${cfg.flowDef.name}' COMPLETED")
    yield ()

  private def failFlow(
    stateRef: Ref[IO, FlowState],
    cfg: FlowConfig,
    reason: String
  )(using ctx: ActorContext[?]): IO[Unit] =
    for
      _ <- stateRef.update(_.copy(phase = FlowPhase.Failed))
      _ <- saveSnapshot(stateRef, cfg)
      _ <- emit(
        cfg.wsSend,
        cfg.sessionId,
        "flowCompleted",
        "pass" -> false.asJson,
        "summary" -> reason.take(500).asJson
      )
      _ <- cfg.parentAgentRef ! AgentCommand.ExternalEvent(
        source = "flow",
        eventType = "failed",
        payload = s"[Flow: ${cfg.flowDef.name}] FAIL\n$reason",
        metadata = JsonObject("flowName" -> cfg.flowDef.name.asJson)
      )
      _ <- logger.warn(s"Flow '${cfg.flowDef.name}' FAILED: $reason")
    yield ()

  // ============================================================
  // Step adapter
  // ============================================================

  private def stepAdapter(
    flowActorRef: ActorRef[FlowCommand],
    stepId: String,
    subagentRef: ActorRef[AgentCommand],
    timeout: FiniteDuration,
    isVerify: Boolean = false,
    verifyAgentPath: String = ""
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      ctx.watch(subagentRef)

      val done = Ref.unsafe[IO, Boolean](false)

      // Timeout: stop agent and notify FlowActor (skipped if step already completed)
      ctx.forkTurn(
        IO.sleep(timeout) *>
          done.get.flatMap {
            case true  => IO.unit
            case false =>
              ctx.system.stop(subagentRef) *>
                (if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit) *>
                (flowActorRef ! StepFailed(stepId, s"timeout after ${timeout.toSeconds}s"))
          }
      )

      IO.pure(new Behavior[AgentEvent]:
        def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
          event match
            case AgentEvent.Completed(_, messages) =>
              done.set(true) *>
                (if isVerify then
                  // Check if FlowVerify tool was called
                  for
                    deferredOpt <- FlowVerifyRegistry.tryGet(verifyAgentPath)
                    _ <- FlowVerifyRegistry.remove(verifyAgentPath)
                    result <- deferredOpt match
                      case Some(deferred) =>
                        deferred.tryGet.flatMap {
                          case Some(vr) =>
                            IO.delay(flowActorRef ! VerifyCompleted(vr.pass, vr.summary))
                          case None =>
                            IO.delay(
                              flowActorRef ! StepFailed(stepId, "Verify agent completed without calling FlowVerify tool")
                            )
                        }
                      case None =>
                        IO.delay(flowActorRef ! StepFailed(stepId, "Verify registry error"))
                    _ = result
                  yield Behaviors.stopped[AgentEvent]
                else
                  val text = extractLastAssistantText(messages)
                  for _ <- flowActorRef ! StepCompleted(stepId, text)
                  yield Behaviors.stopped[AgentEvent])

            case AgentEvent.Failed(_, error) =>
              done.set(true) *>
                (if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit) *>
                IO.delay(flowActorRef ! StepFailed(stepId, error.message)) *>
                IO.pure(Behaviors.stopped[AgentEvent])

        override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
          signal match
            case SystemSignal.Terminated(_) =>
              for
                alreadyDone <- done.get
                _ <- done.set(true)
                _ <-
                  if isVerify then FlowVerifyRegistry.remove(verifyAgentPath)
                  else IO.unit
                _ <-
                  if !alreadyDone then
                    IO.delay(flowActorRef ! StepFailed(stepId, "agent crashed"))
                  else IO.unit
              yield Behaviors.stopped[AgentEvent]

        override def onStop(ctx: ActorContext[AgentEvent]): IO[Unit] =
          (if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit) *>
            FlowMembership.leaveAll(subagentRef.path.toString)
      )
    }

  // ============================================================
  // Utility helpers
  // ============================================================

  private[flow] val MaxStepOutputChars = 8000

  private[flow] def resolveTemplate(prompt: String, results: Map[String, String]): String =
    results.foldLeft(prompt) { case (p, (id, output)) =>
      val truncated =
        if output.length > MaxStepOutputChars
        then output.take(MaxStepOutputChars) + s"\n... (truncated, ${output.length} chars total)"
        else output
      p.replace("${" + id + "}", truncated)
    }

  /** Build team roster string for injection into agent prompts. */
  private def buildTeamRoster(cfg: FlowConfig, state: FlowState): String =
    val members = state.runningAgents.toList.flatMap { (stepId, ref) =>
      val agentName = cfg.flowDef.steps.find(_.id == stepId).map(_.agent).getOrElse(stepId)
      if ref.path.toString != cfg.parentAgentRef.path.toString then
        Some(s"| $stepId ($agentName) | ${ref.path.toString} |")
      else None
    }
    val parent = s"| main (parent) | ${cfg.parentAgentRef.path.toString} |"
    val all = parent :: members
    s"""## Flow Team Members
       |
       |You can communicate with these agents using the Mail tool:
       |
       || Role | Address |
       |------|---------|
       |${all.mkString("\n")}
       |
       |""".stripMargin

  /** Persist current state to disk for graceful restart recovery. */
  private def saveSnapshot(stateRef: Ref[IO, FlowState], cfg: FlowConfig): IO[Unit] =
    for
      state <- stateRef.get
      snapshot = FlowSnapshot(
        flowDef = cfg.flowDef,
        flowId = cfg.flowId,
        phase = state.phase.toString,
        stepStatus = state.stepStatus.view.mapValues(_.toString).toMap,
        results = state.results,
        failedReasons = state.failedReasons,
        retryLeft = state.retryLeft,
        verifyResult = state.verifyResult,
        iteration = state.iteration
      )
      _ <- cfg.sessionId match
        case Some(sid) if sid.nonEmpty => FlowStore.save(sid, cfg.flowId, snapshot)
        case _ => IO.unit
    yield ()

  private[flow] def buildVerifyContext(
    results: Map[String, String],
    failedReasons: Map[String, String],
    stepStatus: Map[String, StepStatus]
  ): String =
    val sb = new StringBuilder("=== Step Results ===\n\n")
    val allIds = (results.keys ++ failedReasons.keys).toSeq.sorted
    allIds.foreach { id =>
      stepStatus.get(id) match
        case Some(StepStatus.Failed) =>
          val reason = failedReasons.getOrElse(id, "unknown error")
          sb.append(s"[$id] [FAILED]\n$reason\n\n")
        case _ =>
          val output = results.getOrElse(id, "")
          val truncated = if output.length > 3000 then output.take(3000) + "\n... (truncated)" else output
          sb.append(s"[$id]\n$truncated\n\n")
      end match
    }
    sb.append("=== End Results ===\n")
    sb.toString

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst { case msg if msg.role == MessageRole.Assistant => msg.textContent }
      .filter(_.nonEmpty)
      .getOrElse("")

  private def routeWsSend(
    wsSend: Option[Json => IO[Unit]],
    parentSessionId: Option[String],
    flowStepId: Option[String] = None
  ): Json => IO[Unit] =
    val base = wsSend.getOrElse((_: Json) => IO.unit)
    val patches = List(
      parentSessionId.map(sid => Json.obj("sessionId" -> sid.asJson)),
      flowStepId.map(fid => Json.obj("flowStepId" -> fid.asJson))
    ).flatten
    patches match
      case Nil => base
      case _ => json => base(patches.foldLeft(json)((j, p) => j.deepMerge(p)))

  private def emit(
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    eventName: String,
    fields: (String, Json)*
  )(using ctx: ActorContext[?]): IO[Unit] =
    ctx.forkTurn(
      wsSend match
        case Some(send) =>
          val all = ("type", eventName.asJson) :: ("sessionId", sessionId.getOrElse("").asJson) :: fields.toList
          send(Json.fromJsonObject(JsonObject.fromIterable(all)))
        case None => IO.unit
    )

end FlowActor

// ============================================================
// Config & State
// ============================================================

private case class FlowConfig(
  flowDef: FlowDef,
  flowId: String,
  parentAgentRef: ActorRef[AgentCommand],
  wsSend: Option[Json => IO[Unit]],
  sessionId: Option[String],
  parentDepth: Int,
  resources: SharedResources,
  projectRoot: String,
  bypass: Boolean
)

case class FlowState(
  results: Map[String, String],
  failedReasons: Map[String, String],
  stepStatus: Map[String, StepStatus],
  runningAgents: Map[String, ActorRef[AgentCommand]],
  retryLeft: Map[String, Int],
  verifyResult: Option[String],
  iteration: Int,
  phase: FlowPhase
)

object FlowState:

  def empty(flowDef: FlowDef): FlowState =
    FlowState(
      results = Map.empty,
      failedReasons = Map.empty,
      stepStatus = flowDef.steps.map(s => s.id -> StepStatus.Pending).toMap,
      runningAgents = Map.empty,
      retryLeft = Map.empty,
      verifyResult = None,
      iteration = 0,
      phase = FlowPhase.Working
    )
