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

import scala.concurrent.duration.{DurationInt, FiniteDuration, *}

// ============================================================
// FlowTreeActor — session-level orchestrator (replaces FlowActor)
// ============================================================

object FlowTreeActor:
  private val logger = NebflowLogger(getClass)

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
  // Config & Runtime types
  // ============================================================

  case class TreeConfig(
    parentAgentRef: ActorRef[AgentCommand],
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    resources: SharedResources,
    projectRoot: String,
    safetyMode: String,
    gatewayPort: Int = 8080
  )

  case class BranchRuntime(
    state: BranchState,
    flowDef: FlowDef,
    runningAgents: Map[String, ActorRef[AgentCommand]] = Map.empty,
    depth: Int = 0,
    parentStep: Option[(String, String)] = None // (parentBranchName, stepId) for nesting
  )

  // ============================================================
  // Factory
  // ============================================================

  def apply(config: TreeConfig): Behavior[TreeCommand] =
    Behaviors.setup { ctx =>
      given ActorContext[TreeCommand] = ctx

      val branchesRef = Ref.unsafe[IO, Map[String, BranchRuntime]](Map.empty)

      for
        _ <- logger.info(s"FlowTreeActor started for session ${config.sessionId}")
        _ <- startFileWatcher(ctx, config)
      yield running(ctx, branchesRef, config)
    }

  // ============================================================
  // Running behavior
  // ============================================================

  private def running(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig
  ): Behavior[TreeCommand] =

    new Behavior[TreeCommand]:
      override def onError(ctx: ActorContext[TreeCommand], err: Throwable): IO[Behavior[TreeCommand]] =
        logger.error(s"FlowTreeActor error: ${err.getMessage}\n${err.getStackTrace.take(15).map(_.toString).mkString("\n")}").as(this)

      def receive(ctx: ActorContext[TreeCommand], msg: TreeCommand): IO[Behavior[TreeCommand]] =
        given ActorContext[TreeCommand] = ctx
        logger.debug(s"FlowTreeActor received: ${msg.getClass.getSimpleName}") *>
        (msg match
          case TreeCommand.MountBranch(defn, instanceName, replyTo) =>
            handleMount(ctx, branchesRef, cfg, defn, instanceName, replyTo).as(this)

          case TreeCommand.UnmountBranch(name) =>
            handleUnmount(ctx, branchesRef, cfg, name).as(this)

          case TreeCommand.RetriggerPipeline(name) =>
            handleRetrigger(ctx, branchesRef, cfg, name).as(this)

          case TreeCommand.StepCompleted(branchName, stepId, output) =>
            for
              _ <- handleStepCompleted(ctx, branchesRef, cfg, branchName, stepId, output)
              _ <- afterStepUpdate(ctx, branchesRef, cfg, branchName)
            yield this

          case TreeCommand.StepFailed(branchName, stepId, error) =>
            for
              _ <- handleStepFailed(ctx, branchesRef, cfg, branchName, stepId, error)
              _ <- afterStepUpdate(ctx, branchesRef, cfg, branchName)
            yield this

          case TreeCommand.VerifyCompleted(branchName, passed, summary) =>
            handleVerifyCompleted(ctx, branchesRef, cfg, branchName, passed, summary).as(this)

          case TreeCommand.EventFired(eventType, data) =>
            handleEvent(ctx, branchesRef, cfg, eventType, data).as(this)

          case TreeCommand.SourceExited(branchName, exitCode) =>
            handleSourceExited(ctx, branchesRef, cfg, branchName, exitCode).as(this)

          case TreeCommand.MailForBranch(address, message) =>
            // Mail routing for serverless daemons is handled by the proxy actor directly
            logger.info(s"Mail for $address").as(this)

          case TreeCommand.ReloadDefinition(flowName) =>
            handleReload(ctx, branchesRef, cfg, flowName).as(this)

          case TreeCommand.Shutdown =>
            for
              branches <- branchesRef.get
              _ <- branches.values.toList.traverse_ { br =>
                br.runningAgents.values.toList.traverse_(ref => ctx.system.stop(ref))
              }
              _ <- logger.info("FlowTreeActor shutdown complete")
            yield Behaviors.stopped[TreeCommand]
        )
      end receive

      override def onStop(ctx: ActorContext[TreeCommand]): IO[Unit] =
        for
          branches <- branchesRef.get
          _ <- branches.values.toList.traverse_ { br =>
            br.runningAgents.values.toList.traverse_(ref =>
              ctx.system.stop(ref) *> FlowMembership.leaveAll(ref.path.toString)
            )
          }
        yield ()

  // ============================================================
  // Branch management
  // ============================================================

  private def handleMount(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    defn: FlowDef,
    instanceName: Option[String],
    replyTo: Option[ActorRef[MountResult]]
  )(using ActorContext[TreeCommand]): IO[Unit] =
    logger.info(s"handleMount: defn=${defn.name}, type=${defn.branchType.typeName}, instanceName=$instanceName") *>
    (for
      branches <- branchesRef.get
      // Generate unique instance name
      baseName = instanceName.getOrElse(defn.name)
      name = makeUniqueName(branches.keys.toSet, baseName)
      address = s"nebflow://local/branch-$name-${java.util.UUID.randomUUID().toString.take(8)}"
      // Create initial branch state
      initialState = defn.branchType match
        case p: BranchType.Pipeline =>
          BranchState(
            name = name,
            address = address,
            branchType = defn.branchType,
            phase = BranchPhase.Starting,
            stepStatus = p.steps.map(s => s.id -> StepStatus.Pending.toString).toMap
          )
        case _ =>
          BranchState(
            name = name,
            address = address,
            branchType = defn.branchType,
            phase = BranchPhase.Starting
          )
      runtime = BranchRuntime(state = initialState, flowDef = defn, depth = 0)
      // Insert
      _ <- branchesRef.update(_ + (name -> runtime))
      _ <- FlowMembership.join(cfg.parentAgentRef.path.toString, name)
      _ <- emit(
        cfg,
        "treeBranchMounted",
        "name" -> name.asJson,
        "type" -> defn.branchType.typeName.asJson,
        "address" -> address.asJson
      )
      // Start branch based on type
      _ <- defn.branchType match
        case _: BranchType.Pipeline =>
          startPipeline(ctx, branchesRef, cfg, name)
        case daemon: BranchType.Daemon =>
          startDaemon(ctx, branchesRef, cfg, name, daemon)
        case _: BranchType.Reactor =>
          // Reactor: just mark as Running — it listens via handleEvent
          for
            _ <- branchesRef.update(s =>
              s.updated(name, s(name).copy(state = s(name).state.copy(phase = BranchPhase.Running)))
            )
            _ <- logger.info(s"Reactor '$name' mounted and listening")
          yield ()
        case source: BranchType.Source =>
          startSource(ctx, branchesRef, cfg, name, source)
      // Inject address table to main agent
      _ <- injectAddressTable(branchesRef, cfg)
      // Persist
      _ <- saveTree(branchesRef, cfg)
      // Reply
      _ <- replyTo.traverse_(_ ! MountResult.Mounted(name, address, defn.branchType.typeName))
      _ <- logger.info(s"Branch '$name' (${defn.branchType.typeName}) mounted at $address")
    yield ())

  private def handleUnmount(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(name) match
        case Some(br) =>
          for
            _ <- br.runningAgents.values.toList.traverse_(ref =>
              ctx.system.stop(ref) *> FlowMembership.leaveAll(ref.path.toString)
            )
            _ <- branchesRef.update(_ - name)
            _ <- FlowMembership.leave(cfg.parentAgentRef.path.toString, name)
            _ <- emit(cfg, "treeBranchUnmounted", "name" -> name.asJson)
            _ <- injectAddressTable(branchesRef, cfg)
            _ <- saveTree(branchesRef, cfg)
            _ <- logger.info(s"Branch '$name' unmounted")
          yield ()
        case None =>
          logger.warn(s"Cannot unmount '$name': not found")
    yield ()

  private def handleRetrigger(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches
        .get(name)
        .flatMap(_.state.branchType match
          case p: BranchType.Pipeline => Some(p)
          case _ => None) match
        case Some(pipeline) =>
          for
            _ <- branchesRef.update { m =>
              m.updated(
                name,
                m(name).copy(
                  state = m(name).state.copy(
                    phase = BranchPhase.Starting,
                    stepStatus = pipeline.steps.map(s => s.id -> StepStatus.Pending.toString).toMap,
                    results = Map.empty,
                    failedReasons = Map.empty,
                    retryLeft = Map.empty,
                    verifyResult = None,
                    iteration = 0
                  ),
                  runningAgents = Map.empty
                )
              )
            }
            _ <- startPipeline(ctx, branchesRef, cfg, name)
          yield ()
        case None =>
          logger.warn(s"Cannot retrigger '$name': not a pipeline or not found")
    yield ()

  // ============================================================
  // Pipeline execution
  // ============================================================

  private def startPipeline(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      _ <- branchesRef.update(m =>
        m.updated(name, m(name).copy(state = m(name).state.copy(phase = BranchPhase.Running)))
      )
      pipelineDef <- branchesRef.get.map(_.get(name).flatMap(_.state.branchType match
        case p: BranchType.Pipeline => Some(p)
        case _ => None))
      _ <- emit(cfg, "flowStarted",
        "flowName" -> name.asJson,
        "branchName" -> name.asJson,
        "steps" -> pipelineDef.map(p => p.steps.map(s => Json.obj(
          "id" -> s.id.asJson,
          "agent" -> s.agent.getOrElse("").asJson,
          "dependsOn" -> s.dependsOn.toList.asJson
        ))).getOrElse(Nil).asJson
      )
      _ <- scheduleReadySteps(ctx, branchesRef, cfg, name)
    yield ()

  private def scheduleReadySteps(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(branchName) match
        case Some(br) =>
          br.state.branchType match
            case pipeline: BranchType.Pipeline =>
              val ready = pipeline.steps.filter { step =>
                br.state.stepStatus.get(step.id).contains(StepStatus.Pending.toString) &&
                !br.runningAgents.contains(step.id) &&
                step.dependsOn.forall(dep => br.state.stepStatus.get(dep).contains(StepStatus.Done.toString))
              }
              val slots = (pipeline.maxConcurrency - br.runningAgents.size).max(0)
              val toStart = ready.take(slots)
              for
                _ <- toStart.traverse_(step => spawnPipelineStep(ctx, branchesRef, cfg, branchName, step))
                _ =
                  if toStart.nonEmpty then logger.info(s"[$branchName] Scheduled: ${toStart.map(_.id).mkString(", ")}")
              yield ()
            case _ => IO.unit
        case None => IO.unit
    yield ()

  private def spawnPipelineStep(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    step: PipelineStep
  )(using ActorContext[TreeCommand]): IO[Unit] =
    if step.isNested then
      // Nested flow: mount child branch
      spawnNestedFlow(ctx, branchesRef, cfg, branchName, step)
    else
      // Atomic step: spawn agent
      spawnAtomicStep(ctx, branchesRef, cfg, branchName, step)

  private def spawnNestedFlow(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    step: PipelineStep
  )(using ActorContext[TreeCommand]): IO[Unit] =
    val flowName = step.flow.getOrElse("")
    for
      // Load child flow definition
      loaded <- FlowDefLoader.load(flowName)
      _ <- loaded match
        case None =>
          // Child flow not found → step fails
          ctx.self ! TreeCommand.StepFailed(branchName, step.id, s"Nested flow '$flowName' not found")
          IO.unit
        case Some(childDef) =>
          for
            branches <- branchesRef.get
            parentBranch = branches(branchName)
            childName = makeUniqueName(branches.keys.toSet, s"$branchName/${step.id}")
            childAddress = s"nebflow://local/branch-$childName-${java.util.UUID.randomUUID().toString.take(8)}"
            childState = childDef.branchType match
              case p: BranchType.Pipeline =>
                BranchState(
                  name = childName,
                  address = childAddress,
                  branchType = childDef.branchType,
                  phase = BranchPhase.Starting,
                  parentBranch = Some(branchName),
                  stepStatus = p.steps.map(s => s.id -> StepStatus.Pending.toString).toMap
                )
              case _ =>
                BranchState(
                  name = childName,
                  address = childAddress,
                  branchType = childDef.branchType,
                  phase = BranchPhase.Starting,
                  parentBranch = Some(branchName)
                )
            childRuntime = BranchRuntime(
              state = childState,
              flowDef = childDef,
              depth = parentBranch.depth + 1,
              parentStep = Some((branchName, step.id))
            )
            _ <- branchesRef.update(m =>
              m.updated(childName, childRuntime)
                .updated(
                  branchName,
                  m(branchName).copy(
                    runningAgents = m(branchName).runningAgents + (step.id -> cfg.parentAgentRef), // placeholder ref
                    state = m(branchName).state.copy(
                      stepStatus = m(branchName).state.stepStatus + (step.id -> StepStatus.Running.toString)
                    )
                  )
                )
            )
            _ <- FlowMembership.join(cfg.parentAgentRef.path.toString, childName)
            _ <- emit(
              cfg,
              "flowStepStarted",
              "branchName" -> branchName.asJson,
              "stepId" -> step.id.asJson,
              "nestedFlow" -> childName.asJson
            )
            _ <- logger.info(s"[$branchName] Nested flow '$childName' ($flowName) mounted for step '${step.id}'")
            // Start the child pipeline
            _ <- childDef.branchType match
              case _: BranchType.Pipeline => startPipeline(ctx, branchesRef, cfg, childName)
              case _ => IO.unit
          yield ()
    yield ()

    end for

  end spawnNestedFlow

  private def spawnAtomicStep(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    step: PipelineStep
  )(using ActorContext[TreeCommand]): IO[Unit] =
    val agentName = step.agent.getOrElse("")
    for
      branches <- branchesRef.get
      br = branches(branchName)
      resolved = resolveTemplate(step.prompt.getOrElse(""), br.state.results)
      defOpt <- cfg.resources.agentLibrary.get(agentName)
      _ <- defOpt match
        case None =>
          ctx.self ! TreeCommand.StepFailed(branchName, step.id, s"Agent '$agentName' not found")
          IO.unit
        case Some(agentDef) =>
          val agentUid = s"branch-${branchName.take(20)}-${step.id}-${java.util.UUID.randomUUID().toString.take(8)}"
          for
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            childDepth = br.depth + 1
            childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(step.id))
            st <- branchesRef.get
            actualPrompt = buildTeamRoster(cfg, st) + resolved
            _ <- branchesRef.update(m =>
              m.updated(
                branchName,
                m(branchName).copy(
                  state = m(branchName).state.copy(
                    stepStatus = m(branchName).state.stepStatus + (step.id -> StepStatus.Running.toString)
                  ),
                  runningAgents = m(branchName).runningAgents + (step.id -> null) // placeholder, updated after spawn
                )
              )
            )
            agentRef <- ctx.system.spawn(
              AgentActor(
                agentDef = agentDef,
                resources = cfg.resources,
                wsSend = childWs,
                depth = childDepth,
                parentRef = Some(cfg.parentAgentRef),
                sessionId = cfg.sessionId,
                sessionName = Some(s"$branchName/${step.id}"),
                readTracker = Some(readTracker),
                fileHistory = Some(fileHistory),
                contextWindow = cfg.resources.contextWindow,
                projectRoot = Some(cfg.projectRoot),
                safetyMode = cfg.safetyMode
              ),
              agentUid
            )
            adapterRef <- ctx.spawn(
              stepAdapter(ctx.self, branchName, step.id, agentRef, step.timeout, isVerify = false, ""),
              s"$agentUid-adapter"
            )
            _ <- branchesRef.update(m =>
              m.updated(
                branchName,
                m(branchName).copy(
                  runningAgents = m(branchName).runningAgents + (step.id -> agentRef),
                  state = m(branchName).state.copy(
                    retryLeft = m(branchName).state.retryLeft + (step.id -> step.retry)
                  )
                )
              )
            )
            _ <- FlowMembership.join(agentRef.path.toString, branchName)
            _ <- saveTree(branchesRef, cfg)
            _ <- emit(
              cfg,
              "flowStepStarted",
              "branchName" -> branchName.asJson,
              "stepId" -> step.id.asJson,
              "agentName" -> agentName.asJson
            )
            _ <- agentRef ! AgentCommand.UserInput(actualPrompt, Some(adapterRef))
            _ = logger.info(s"[$branchName] Spawned '${step.id}' ($agentName, depth=$childDepth)")
          yield ()
          end for
    yield ()
    end for
  end spawnAtomicStep

  // ============================================================
  // Step completion / failure
  // ============================================================

  private def handleStepCompleted(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    stepId: String,
    output: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(branchName) match
        case Some(br) if br.state.stepStatus.get(stepId).contains(StepStatus.Running.toString) =>
          if stepId == FixId then
            // Fix completed → re-verify
            for
              _ <- branchesRef.update(m =>
                m.updated(
                  branchName,
                  m(branchName).copy(
                    state = m(branchName).state.copy(
                      stepStatus = m(branchName).state.stepStatus + (FixId -> StepStatus.Done.toString),
                      phase = BranchPhase.Running
                    ),
                    runningAgents = m(branchName).runningAgents - FixId
                  )
                )
              )
              _ <- runVerify(ctx, branchesRef, cfg, branchName)
            yield ()
          else
            for
              _ <- branchesRef.update(m =>
                m.updated(
                  branchName,
                  m(branchName).copy(
                    state = m(branchName).state.copy(
                      results = m(branchName).state.results + (stepId -> output),
                      stepStatus = m(branchName).state.stepStatus + (stepId -> StepStatus.Done.toString)
                    ),
                    runningAgents = m(branchName).runningAgents - stepId
                  )
                )
              )
              _ <- emit(cfg, "flowStepCompleted", "branchName" -> branchName.asJson, "stepId" -> stepId.asJson)
              _ <- logger.info(s"[$branchName] Step '$stepId' completed (${output.length} chars)")
            yield ()
        case _ => IO.unit // ignore duplicate or unknown
    yield ()

  private def handleStepFailed(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    stepId: String,
    error: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(branchName) match
        case Some(br) if br.state.stepStatus.get(stepId).contains(StepStatus.Running.toString) =>
          if stepId == FixId then failPipeline(ctx, branchesRef, cfg, branchName, s"Fix step failed: $error")
          else if stepId == VerifyId then handleVerifyFail(ctx, branchesRef, cfg, branchName, s"Verify error: $error")
          else
            // Work step failed — check retries
            val retries = br.state.retryLeft.getOrElse(stepId, 0)
            if retries > 0 then
              br.state.branchType match
                case pipeline: BranchType.Pipeline =>
                  val step = pipeline.steps.find(_.id == stepId).getOrElse(pipeline.steps.head)
                  for
                    _ <- branchesRef.update(m =>
                      m.updated(
                        branchName,
                        m(branchName).copy(
                          state = m(branchName).state.copy(
                            retryLeft = m(branchName).state.retryLeft + (stepId -> (retries - 1))
                          ),
                          runningAgents = m(branchName).runningAgents - stepId
                        )
                      )
                    )
                    _ <- logger.info(s"[$branchName] Step '$stepId' failed ($error), retrying (${retries - 1} left)")
                    _ <- spawnPipelineStep(ctx, branchesRef, cfg, branchName, step)
                  yield ()
                  end for
                case _ => IO.unit
            else
              // Retries exhausted
              for
                _ <- branchesRef.update(m =>
                  m.updated(
                    branchName,
                    m(branchName).copy(
                      state = m(branchName).state.copy(
                        stepStatus = m(branchName).state.stepStatus + (stepId -> StepStatus.Failed.toString),
                        failedReasons = m(branchName).state.failedReasons + (stepId -> error)
                      ),
                      runningAgents = m(branchName).runningAgents - stepId
                    )
                  )
                )
                _ <- emit(
                  cfg,
                  "flowStepFailed",
                  "branchName" -> branchName.asJson,
                  "stepId" -> stepId.asJson,
                  "error" -> error.asJson
                )
                // Check dependents
                hasDependers = br.state.branchType match
                  case p: BranchType.Pipeline =>
                    p.steps.exists(s =>
                      s.dependsOn.contains(stepId) &&
                        br.state.stepStatus.get(s.id).contains(StepStatus.Pending.toString)
                    )
                  case _ => false
                _ <-
                  if hasDependers then
                    failPipeline(ctx, branchesRef, cfg, branchName, s"Step '$stepId' failed, dependents cannot run")
                  else IO.unit
              yield ()
            end if
        case _ => IO.unit
    yield ()

  private def afterStepUpdate(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(branchName) match
        case Some(br) if br.state.phase == BranchPhase.Running =>
          br.state.branchType match
            case pipeline: BranchType.Pipeline =>
              val allDone = pipeline.steps.forall(s =>
                br.state.stepStatus
                  .get(s.id)
                  .exists(x => x == StepStatus.Done.toString || x == StepStatus.Failed.toString)
              ) && !br.runningAgents.contains(VerifyId) && !br.runningAgents.contains(FixId)
              if allDone && !br.state.stepStatus.contains(VerifyId) then
                for
                  _ <- branchesRef.update(m =>
                    m.updated(
                      branchName,
                      m(branchName).copy(state = m(branchName).state.copy(phase = BranchPhase.Running))
                    )
                  )
                  _ <- runVerify(ctx, branchesRef, cfg, branchName)
                yield ()
              else scheduleReadySteps(ctx, branchesRef, cfg, branchName)
            case _ => IO.unit
        case _ => IO.unit
    yield ()

  // ============================================================
  // Verify handling
  // ============================================================

  private def runVerify(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches
        .get(branchName)
        .flatMap(_.state.branchType match
          case p: BranchType.Pipeline => Some(p)
          case _ => None) match
        case Some(pipeline) =>
          val br = branches(branchName)
          val contextBlock = buildVerifyContext(br.state.results, br.state.failedReasons, br.state.stepStatus)
          val prompt = s"$contextBlock\n\n${pipeline.verify.prompt}"
          for
            _ <- emit(
              cfg,
              "flowStepStarted",
              "branchName" -> branchName.asJson,
              "stepId" -> VerifyId.asJson,
              "agentName" -> pipeline.verify.agent.asJson
            )
            _ <- spawnById(
              ctx,
              branchesRef,
              cfg,
              branchName,
              pipeline.verify.agent,
              prompt,
              VerifyId,
              pipeline.verify.timeout,
              isVerify = true
            )
          yield ()
          end for
        case None => IO.unit
    yield ()

  private def handleVerifyCompleted(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    passed: Boolean,
    summary: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(branchName) match
        case Some(br) =>
          for
            _ <- branchesRef.update(m =>
              m.updated(
                branchName,
                m(branchName).copy(
                  state = m(branchName).state.copy(
                    verifyResult = Some(summary),
                    stepStatus = m(branchName).state.stepStatus + (VerifyId -> StepStatus.Done.toString)
                  ),
                  runningAgents = m(branchName).runningAgents - VerifyId
                )
              )
            )
            _ <- emit(
              cfg,
              "flowVerifyResult",
              "branchName" -> branchName.asJson,
              "pass" -> passed.asJson,
              "summary" -> summary.take(500).asJson,
              "iteration" -> br.state.iteration.asJson
            )
            _ <-
              if passed then completePipeline(ctx, branchesRef, cfg, branchName, summary)
              else handleVerifyFail(ctx, branchesRef, cfg, branchName, summary)
          yield ()
        case None => IO.unit
    yield ()

  private def handleVerifyFail(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    reason: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches
        .get(branchName)
        .flatMap(_.state.branchType match
          case p: BranchType.Pipeline => Some(p)
          case _ => None) match
        case Some(pipeline) =>
          val br = branches(branchName)
          pipeline.loop match
            case Some(loop) if br.state.iteration < loop.maxIterations =>
              for
                _ <- branchesRef.update(m =>
                  m.updated(
                    branchName,
                    m(branchName).copy(
                      state = m(branchName).state.copy(
                        iteration = m(branchName).state.iteration + 1,
                        stepStatus = m(branchName).state.stepStatus + (FixId -> StepStatus.Pending.toString)
                      )
                    )
                  )
                )
                _ <- emit(
                  cfg,
                  "flowLoopIteration",
                  "branchName" -> branchName.asJson,
                  "iteration" -> (br.state.iteration + 1).asJson,
                  "maxIterations" -> loop.maxIterations.asJson
                )
                _ <- logger.info(s"[$branchName] Verify FAIL (iter ${br.state.iteration}), running fix")
                fixPrompt = resolveTemplate(loop.fix.prompt.getOrElse(""), br.state.results ++ Map("verify" -> reason))
                _ <- spawnById(
                  ctx,
                  branchesRef,
                  cfg,
                  branchName,
                  loop.fix.agent.getOrElse("Nebula"),
                  fixPrompt,
                  FixId,
                  loop.fix.timeout,
                  isVerify = false
                )
              yield ()
            case _ =>
              failPipeline(
                ctx,
                branchesRef,
                cfg,
                branchName,
                s"Verification failed after ${br.state.iteration} iteration(s): $reason"
              )
          end match
        case None => IO.unit
    yield ()

  // ============================================================
  // Pipeline completion
  // ============================================================

  private def completePipeline(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    summary: String
  )(using ActorContext[?]): IO[Unit] =
    for
      branches <- branchesRef.get
      br <- branches.get(branchName) match
        case Some(b) => IO.pure(b)
        case None => IO.unit *> IO.pure(null.asInstanceOf[BranchRuntime]) // skip
      _ <-
        if br == null then IO.unit
        else
          for
            _ <- branchesRef.update(m =>
              m.updated(branchName, m(branchName).copy(state = m(branchName).state.copy(phase = BranchPhase.Completed)))
            )
            _ <- saveTree(branchesRef, cfg)
            _ <- emit(
              cfg,
              "flowCompleted",
              "branchName" -> branchName.asJson,
              "pass" -> true.asJson,
              "summary" -> summary.take(500).asJson
            )
            _ <- br.parentStep match
              case Some((parentName, stepId)) =>
                ctx.self ! TreeCommand.StepCompleted(parentName, stepId, summary)
                IO.unit
              case None =>
                cfg.parentAgentRef ! AgentCommand.ExternalEvent(
                  source = "flow",
                  eventType = "completed",
                  payload = s"[Flow: $branchName] PASS\n$summary",
                  metadata = JsonObject("flowName" -> branchName.asJson)
                )
            _ <- logger.info(s"[$branchName] Pipeline COMPLETED")
          yield ()
    yield ()

  private def failPipeline(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    reason: String
  )(using ActorContext[?]): IO[Unit] =
    branchesRef.get.flatMap { branches =>
      branches.get(branchName) match
        case None => IO.unit
        case Some(br) =>
          for
            _ <- branchesRef.update(m =>
              m.updated(branchName, m(branchName).copy(state = m(branchName).state.copy(phase = BranchPhase.Crashed)))
            )
            _ <- saveTree(branchesRef, cfg)
            _ <- emit(
              cfg,
              "flowCompleted",
              "branchName" -> branchName.asJson,
              "pass" -> false.asJson,
              "summary" -> reason.take(500).asJson
            )
            _ <- br.parentStep match
              case Some((parentName, stepId)) =>
                ctx.self ! TreeCommand.StepFailed(parentName, stepId, reason)
                IO.unit
              case None =>
                cfg.parentAgentRef ! AgentCommand.ExternalEvent(
                  source = "flow",
                  eventType = "failed",
                  payload = s"[Flow: $branchName] FAIL\n$reason",
                  metadata = JsonObject("flowName" -> branchName.asJson)
                )
            _ <- logger.warn(s"[$branchName] Pipeline FAILED: $reason")
          yield ()
    }

  // ============================================================
  // Event handling (Reactor — Phase 4 stub)
  // ============================================================

  private def handleEvent(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    eventType: String,
    data: JsonObject
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      // Find reactors subscribed to this event type
      reactors = branches.values
        .filter(_.state.branchType match
          case r: BranchType.Reactor => r.subscribe.contains(eventType)
          case _ => false)
        .toList
      _ <- reactors.traverse_ { br =>
        val reactor = br.state.branchType.asInstanceOf[BranchType.Reactor]
        // Check filter
        val matches = reactor.filter.forall { (key, value) =>
          data(key).flatMap(_.asString).contains(value)
        }
        if matches then
          reactor.action match
            case ReactorAction.RunAgent(agent, prompt) =>
              val resolved = data.toList.foldLeft(prompt) { case (p, (k, v)) =>
                p.replace(s"$${event.$k}", v.toString)
              }
              spawnById(
                ctx,
                branchesRef,
                cfg,
                br.state.name,
                agent,
                resolved,
                s"reactor-${java.util.UUID.randomUUID().toString.take(8)}",
                30.minutes,
                isVerify = false
              )
            case ReactorAction.MountFlow(flowName) =>
              FlowDefLoader.load(flowName).flatMap {
                case Some(defn) =>
                  ctx.self ! TreeCommand.MountBranch(defn, None, None)
                  IO.unit
                case None =>
                  logger.warn(s"Reactor '${br.state.name}': flow '$flowName' not found")
              }
        else IO.unit
        end if
      }
    yield ()

  // ============================================================
  // Spawn helper (shared by atomic steps, verify, fix, reactor)
  // ============================================================

  private def spawnById(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    branchName: String,
    agentName: String,
    prompt: String,
    stepId: String,
    timeout: FiniteDuration,
    isVerify: Boolean
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      defOpt <- cfg.resources.agentLibrary.get(agentName)
      _ <- defOpt match
        case None =>
          ctx.self ! TreeCommand.StepFailed(branchName, stepId, s"Agent '$agentName' not found")
          IO.unit
        case Some(agentDef) =>
          val agentUid = s"branch-${branchName.take(20)}-$stepId-${java.util.UUID.randomUUID().toString.take(8)}"
          for
            verifyDeferred <-
              if isVerify then Deferred[IO, VerifyResult].map(Some(_))
              else IO.pure(None)
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            branches <- branchesRef.get
            br = branches(branchName)
            childDepth = br.depth + 1
            childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(stepId))
            actualDef =
              if isVerify then
                agentDef.tools match
                  case List("*") => agentDef.copy(tools = ToolRegistry.builtinToolNames)
                  case tools => agentDef.copy(tools = (tools :+ "FlowVerify").distinct)
              else agentDef
            actualPrompt =
              if isVerify then VerifyPromptPreamble + prompt
              else prompt
            _ <- branchesRef.update(m =>
              m.updated(
                branchName,
                m(branchName).copy(
                  state = m(branchName).state.copy(
                    stepStatus = m(branchName).state.stepStatus + (stepId -> StepStatus.Running.toString)
                  )
                )
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
                sessionName = Some(s"$branchName/$stepId"),
                readTracker = Some(readTracker),
                fileHistory = Some(fileHistory),
                contextWindow = cfg.resources.contextWindow,
                projectRoot = Some(cfg.projectRoot),
                safetyMode = cfg.safetyMode
              ),
              agentUid
            )
            _ <- verifyDeferred match
              case Some(d) => FlowVerifyRegistry.register(agentRef.path.toString, d)
              case None => IO.unit
            adapterRef <- ctx.spawn(
              stepAdapter(ctx.self, branchName, stepId, agentRef, timeout, isVerify, agentRef.path.toString),
              s"$agentUid-adapter"
            )
            _ <- branchesRef.update(m =>
              m.updated(
                branchName,
                m(branchName).copy(runningAgents = m(branchName).runningAgents + (stepId -> agentRef))
              )
            )
            _ <- FlowMembership.join(agentRef.path.toString, branchName)
            _ <- saveTree(branchesRef, cfg)
            _ <- agentRef ! AgentCommand.UserInput(actualPrompt, Some(adapterRef))
            _ = logger.info(s"[$branchName] Spawned '$stepId' ($agentName, depth=$childDepth)")
          yield ()
          end for
    yield ()

  // ============================================================
  // Daemon management
  // ============================================================

  private def startDaemon(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String,
    daemon: BranchType.Daemon
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      defOpt <- cfg.resources.agentLibrary.get(daemon.agent)
      _ <- defOpt match
        case None =>
          // Agent not found — mark as crashed
          branchesRef.update(m =>
            m.updated(name, m(name).copy(state = m(name).state.copy(phase = BranchPhase.Crashed)))
          )
        case Some(agentDef) =>
          if daemon.persistent then
            // Persistent: spawn AgentActor directly at the daemon's address
            for
              readTracker <- ReadTracker.create
              fileHistory <- FileHistory.create()
              agentUid = s"daemon-$name-${java.util.UUID.randomUUID().toString.take(8)}"
              childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(name))
              agentRef <- ctx.system.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = cfg.resources,
                  wsSend = childWs,
                  depth = 1,
                  parentRef = Some(cfg.parentAgentRef),
                  sessionId = cfg.sessionId,
                  sessionName = Some(s"$name (daemon)"),
                  readTracker = Some(readTracker),
                  fileHistory = Some(fileHistory),
                  contextWindow = cfg.resources.contextWindow,
                  projectRoot = Some(cfg.projectRoot),
                  safetyMode = cfg.safetyMode
                ),
                agentUid
              )
              _ <- branchesRef.update(m =>
                m.updated(
                  name,
                  m(name).copy(
                    state = m(name).state.copy(
                      phase = BranchPhase.Running,
                      address = agentRef.path.toString
                    ),
                    runningAgents = m(name).runningAgents + ("__daemon__" -> agentRef)
                  )
                )
              )
              _ <- FlowMembership.join(agentRef.path.toString, name)
              _ <- agentRef ! AgentCommand.UserInput(daemon.prompt, None)
              _ <- emit(
                cfg,
                "daemonStarted",
                "branchName" -> name.asJson,
                "mode" -> "persistent".asJson,
                "address" -> agentRef.path.toString.asJson
              )
              _ = logger.info(s"Daemon '$name' mounted (persistent) at ${agentRef.path}")
            yield ()
          else
            // Serverless: spawn a proxy actor at the daemon's address
            val daemonName = s"daemon-proxy-$name-${java.util.UUID.randomUUID().toString.take(8)}"
            for
              proxyRef <- ctx.system.spawn(
                daemonProxy(ctx.self, name, agentDef, daemon.prompt, cfg),
                daemonName
              )
              _ <- branchesRef.update(m =>
                m.updated(
                  name,
                  m(name).copy(
                    state = m(name).state.copy(
                      phase = BranchPhase.Running,
                      address = proxyRef.path.toString
                    ),
                    runningAgents = m(name).runningAgents + ("__daemon__" -> proxyRef)
                  )
                )
              )
              _ <- FlowMembership.join(proxyRef.path.toString, name)
              _ <- emit(
                cfg,
                "daemonStarted",
                "branchName" -> name.asJson,
                "mode" -> "serverless".asJson,
                "address" -> proxyRef.path.toString.asJson
              )
              _ = logger.info(s"Daemon '$name' mounted (serverless) at ${proxyRef.path}")
            yield ()
            end for
    yield ()

  /** Serverless daemon proxy: spawns temp agent on each incoming Mail. */
  private def daemonProxy(
    treeRef: ActorRef[TreeCommand],
    branchName: String,
    agentDef: AgentDef,
    daemonPrompt: String,
    cfg: TreeConfig
  ): Behavior[AgentCommand] =
    Behaviors.setup { ctx =>
      def loop(): Behavior[AgentCommand] = Behaviors.receiveMessage[AgentCommand] {
        case AgentCommand.UserInput(message, replyTo, _, _, _) =>
          for
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            agentUid = s"daemon-call-$branchName-${java.util.UUID.randomUUID().toString.take(8)}"
            childWs = routeWsSend(cfg.wsSend, cfg.sessionId, Some(branchName))
            agentRef <- ctx.system.spawn(
              AgentActor(
                agentDef = agentDef,
                resources = cfg.resources,
                wsSend = childWs,
                depth = 1,
                parentRef = Some(cfg.parentAgentRef),
                sessionId = cfg.sessionId,
                sessionName = Some(s"$branchName/call"),
                readTracker = Some(readTracker),
                fileHistory = Some(fileHistory),
                contextWindow = cfg.resources.contextWindow,
                projectRoot = Some(cfg.projectRoot),
                safetyMode = cfg.safetyMode
              ),
              agentUid
            )
            adapterRef <- ctx.spawn(
              daemonCallAdapter(replyTo, agentRef, branchName),
              s"$agentUid-adapter"
            )
            fullPrompt = s"$daemonPrompt\n\n--- Incoming Message ---\n$message"
            _ <- agentRef ! AgentCommand.UserInput(fullPrompt, Some(adapterRef))
            _ = logger.info(s"[$branchName] Daemon invoked (serverless)")
          yield loop()

        case _ => IO.pure(loop())
      }
      IO.pure(loop())
    }

  /** Adapter for a serverless daemon call: forwards result to the Mail sender's replyTo. */
  private def daemonCallAdapter(
    replyTo: Option[ActorRef[AgentEvent]],
    agentRef: ActorRef[AgentCommand],
    branchName: String
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      ctx.watch(agentRef)
      val done = Ref.unsafe[IO, Boolean](false)

      IO.pure(new Behavior[AgentEvent]:
        def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
          done.set(true) *>
            IO.delay(replyTo.foreach(_ ! event)) *>
            IO.pure(Behaviors.stopped[AgentEvent])

        override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
          signal match
            case SystemSignal.Terminated(_) =>
              for
                alreadyDone <- done.get
                _ <- done.set(true)
                _ <-
                  if !alreadyDone then
                    IO.delay(
                      replyTo.foreach(
                        _ ! AgentEvent
                          .Failed("", AgentError("", branchName, 1, AgentErrorType.Unknown, "daemon agent crashed"))
                      )
                    )
                  else IO.unit
              yield Behaviors.stopped[AgentEvent])
    }

  // ============================================================
  // Hot reload — file watcher + definition reload
  // ============================================================

  private def startFileWatcher(
    ctx: ActorContext[TreeCommand],
    cfg: TreeConfig
  ): IO[Unit] =
    val flowsDir = (nebflow.core.PathUtil.dataRoot / "flows").toIO.toPath
    ctx.forkTurn(
      IO.blocking {
        if !java.nio.file.Files.exists(flowsDir) then java.nio.file.Files.createDirectories(flowsDir)
        val watcher = java.nio.file.FileSystems.getDefault.newWatchService()
        flowsDir.register(
          watcher,
          java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
          java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
        )
        while true do
          val key = watcher.take()
          val events = key.pollEvents()
          events.forEach { event =>
            val fileName = event.context().toString
            if fileName.endsWith(".yaml") then
              val flowName = fileName.stripSuffix(".yaml")
              ctx.self ! TreeCommand.ReloadDefinition(flowName)
          }
          key.reset()
      }.void
        .handleErrorWith(e => logger.warn(s"File watcher error: ${e.getMessage}").void)
    )

  end startFileWatcher

  private def handleReload(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    flowName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      newDefOpt <- FlowDefLoader.load(flowName)
      _ <- newDefOpt match
        case None => logger.warn(s"Hot reload: '$flowName' not found or invalid")
        case Some(newDef) =>
          for
            branches <- branchesRef.get
            // Find branches mounted from this definition (by matching flowDef.name)
            matching = branches.filter { case (_, br) =>
              br.flowDef.name == flowName || br.state.name.startsWith(flowName)
            }
            _ <- matching.toList.traverse_ { case (name, _) =>
              // Unmount old, mount new
              for
                _ <- handleUnmount(ctx, branchesRef, cfg, name)
                _ <- ctx.self ! TreeCommand.MountBranch(newDef, Some(name), None)
                _ <- emit(cfg, "treeBranchUpdated", "name" -> name.asJson, "flowName" -> flowName.asJson)
                _ <- logger.info(s"Hot reload: '$name' reloaded from '$flowName'")
              yield ()
            }
          yield ()
    yield ()

  // ============================================================
  // Source script management
  // ============================================================

  private def startSource(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String,
    source: BranchType.Source
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      _ <- branchesRef.update(m =>
        m.updated(name, m(name).copy(state = m(name).state.copy(phase = BranchPhase.Running)))
      )
      _ <- spawnSourceProcess(ctx, branchesRef, cfg, name, source)
      _ <- emit(cfg, "sourceStarted", "branchName" -> name.asJson, "command" -> source.command.asJson)
      _ = logger.info(s"Source '$name' started: ${source.command}")
    yield ()

  private def spawnSourceProcess(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String,
    source: BranchType.Source
  )(using ActorContext[TreeCommand]): IO[Unit] =
    val sessionId = cfg.sessionId.getOrElse("")
    val pb = new ProcessBuilder("sh", "-c", source.command)
    pb.environment().put("NEBFLOW_GATEWAY_PORT", cfg.gatewayPort.toString)
    pb.environment().put("NEBFLOW_SESSION_ID", sessionId)
    pb.environment().put("NEBFLOW_BRANCH_NAME", name)

    ctx.forkTurn(
      IO.blocking {
        val process = pb.start()
        // Log stdout/stderr for debugging
        val stdout = scala.io.Source.fromInputStream(process.getInputStream)
        val stderr = scala.io.Source.fromInputStream(process.getErrorStream)
        // Consume output in background to prevent pipe blocking
        val stdoutThread = new Thread(() =>
          try stdout.getLines().foreach(line => logger.info(s"[$name] stdout: $line"))
          catch case _: Exception => ()
        )
        val stderrThread = new Thread(() =>
          try stderr.getLines().foreach(line => logger.warn(s"[$name] stderr: $line"))
          catch case _: Exception => ()
        )
        stdoutThread.setDaemon(true)
        stderrThread.setDaemon(true)
        stdoutThread.start()
        stderrThread.start()

        val exitCode = process.waitFor()
        ctx.self ! TreeCommand.SourceExited(name, exitCode)
      }.void
    )

  end spawnSourceProcess

  private def handleSourceExited(
    ctx: ActorContext[TreeCommand],
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig,
    name: String,
    exitCode: Int
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      branches <- branchesRef.get
      _ <- branches.get(name) match
        case Some(br) =>
          br.state.branchType match
            case source: BranchType.Source =>
              source.restart match
                case RestartPolicy.Permanent =>
                  for
                    _ <- logger.info(s"Source '$name' exited (code=$exitCode), restarting (permanent)")
                    _ <- emit(cfg, "sourceRestarted", "branchName" -> name.asJson, "exitCode" -> exitCode.asJson)
                    _ <- spawnSourceProcess(ctx, branchesRef, cfg, name, source)
                  yield ()
                case RestartPolicy.Transient if exitCode != 0 =>
                  for
                    _ <- logger.info(s"Source '$name' crashed (code=$exitCode), restarting (transient)")
                    _ <- spawnSourceProcess(ctx, branchesRef, cfg, name, source)
                  yield ()
                case _ =>
                  for
                    _ <- branchesRef.update(m =>
                      m.updated(name, m(name).copy(state = m(name).state.copy(phase = BranchPhase.Stopped)))
                    )
                    _ <- logger.info(s"Source '$name' exited (code=$exitCode), not restarting")
                  yield ()
            case _ => IO.unit
        case None => IO.unit
    yield ()

  private def stepAdapter(
    treeRef: ActorRef[TreeCommand],
    branchName: String,
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
                (if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit) *>
                (treeRef ! TreeCommand.StepFailed(branchName, stepId, s"timeout after ${timeout.toSeconds}s"))
          }
      )

      IO.pure(new Behavior[AgentEvent]:
        def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
          event match
            case AgentEvent.Completed(_, messages) =>
              done.set(true) *>
                (if isVerify then
                   for
                     deferredOpt <- FlowVerifyRegistry.tryGet(verifyAgentPath)
                     _ <- FlowVerifyRegistry.remove(verifyAgentPath)
                     _ = logger.info(
                       s"stepAdapter: verify Completed received, deferredFound=${deferredOpt.isDefined}, agentPath=$verifyAgentPath"
                     )
                     result <- deferredOpt match
                       case Some(deferred) =>
                         deferred.tryGet.flatMap {
                           case Some(vr) =>
                             logger.info(s"stepAdapter: FlowVerify was called, passed=${vr.pass}") *>
                               (treeRef ! TreeCommand.VerifyCompleted(branchName, vr.pass, vr.summary))
                           case None =>
                             logger.warn(s"stepAdapter: verify agent completed WITHOUT calling FlowVerify") *>
                               (treeRef ! TreeCommand.StepFailed(
                                 branchName,
                                 stepId,
                                 "Verify agent completed without calling FlowVerify tool"
                               ))
                         }
                       case None =>
                         logger.warn(s"stepAdapter: verify Deferred not in registry for $verifyAgentPath") *>
                           (treeRef ! TreeCommand.StepFailed(branchName, stepId, "Verify registry error"))
                     _ = result
                   yield Behaviors.stopped[AgentEvent]
                 else
                   val text = extractLastAssistantText(messages)
                   for _ <- treeRef ! TreeCommand.StepCompleted(branchName, stepId, text)
                   yield Behaviors.stopped[AgentEvent])

            case AgentEvent.Failed(_, error) =>
              done.set(true) *>
                (if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit) *>
                (treeRef ! TreeCommand.StepFailed(branchName, stepId, error.message)) *>
                IO.pure(Behaviors.stopped[AgentEvent])

        override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
          signal match
            case SystemSignal.Terminated(_) =>
              for
                alreadyDone <- done.get
                _ <- done.set(true)
                _ <- if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit
                _ <-
                  if !alreadyDone then (treeRef ! TreeCommand.StepFailed(branchName, stepId, "agent crashed"))
                  else IO.unit
              yield Behaviors.stopped[AgentEvent]

        override def onStop(ctx: ActorContext[AgentEvent]): IO[Unit] =
          (if isVerify then FlowVerifyRegistry.remove(verifyAgentPath) else IO.unit) *>
            FlowMembership.leaveAll(subagentRef.path.toString))
    }

  // ============================================================
  // Utility helpers
  // ============================================================

  private val MaxStepOutputChars = 8000

  private[flow] def resolveTemplate(prompt: String, results: Map[String, String]): String =
    results.foldLeft(prompt) { case (p, (id, output)) =>
      val truncated =
        if output.length > MaxStepOutputChars
        then output.take(MaxStepOutputChars) + s"\n... (truncated, ${output.length} chars total)"
        else output
      p.replace("${" + id + "}", truncated)
    }

  private def buildTeamRoster(cfg: TreeConfig, branches: Map[String, BranchRuntime]): String =
    val lines = branches.values.flatMap { br =>
      if br.state.address != cfg.parentAgentRef.path.toString then
        Some(s"| ${br.state.name} (${br.state.branchType.typeName}) | ${br.state.address} |")
      else None
    }.toList
    if lines.isEmpty then ""
    else s"""## Flow 实例地址表
         |
         || 名称 | 地址 |
         |------|------|
         |${lines.mkString("\n")}
         |
         |""".stripMargin

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

  private[flow] def buildAddressTable(branches: Map[String, BranchRuntime]): String =
    val lines = branches.values.map { br =>
      s"| ${br.state.name} | ${br.state.branchType.typeName} | ${br.state.address} | ${br.state.phase.toString} |"
    }.toList
    if lines.isEmpty then "（无活跃 Flow 实例）"
    else s"""## Flow 实例地址表
         |
         || 名称 | 类型 | 地址 | 状态 |
         |------|------|------|------|
         |${lines.mkString("\n")}""".stripMargin

  private def injectAddressTable(
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig
  )(using ctx: ActorContext[?]): IO[Unit] =
    ctx.forkTurn(
      for
        branches <- branchesRef.get
        table = buildAddressTable(branches)
        _ = cfg.parentAgentRef ! AgentCommand.ExternalEvent(
          source = "flow",
          eventType = "addressTable",
          payload = table,
          metadata = JsonObject.empty
        )
      yield ()
    )

  private def makeUniqueName(existing: Set[String], base: String): String =
    if !existing.contains(base) then base
    else
      var i = 2
      while existing.contains(s"$base-$i") do i += 1
      s"$base-$i"

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst { case msg if msg.role == MessageRole.Assistant => msg.textContent }
      .filter(_.nonEmpty)
      .getOrElse("")

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

  private def emit(
    cfg: TreeConfig,
    eventName: String,
    fields: (String, Json)*
  )(using ctx: ActorContext[?]): IO[Unit] =
    ctx.forkTurn(
      cfg.wsSend match
        case Some(send) =>
          val all = ("type", eventName.asJson) :: ("sessionId", cfg.sessionId.getOrElse("").asJson) :: fields.toList
          send(Json.fromJsonObject(JsonObject.fromIterable(all)))
        case None => IO.unit
    )

  private def saveTree(
    branchesRef: Ref[IO, Map[String, BranchRuntime]],
    cfg: TreeConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          branches <- branchesRef.get
          snapshot = FlowTreeSnapshot(sid, branches.map { case (name, br) => name -> br.state })
          _ <- FlowTreeStore.save(snapshot)
        yield ()
      case _ => IO.unit

end FlowTreeActor
