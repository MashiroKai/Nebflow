package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.core.*
import nebflow.core.ask.AskService
import nebflow.core.compact.*
import nebflow.core.flow.TeamSessionRegistry
import nebflow.core.tools.AskUserQuestionTool
import nebflow.llm.{Fallback, FallbackExhaustedError}
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

object AgentActor extends AgentCore with AgentSession:

  private val MaxEmptyResponseRetries = 5
  /** Transient LLM failures (bad_record_mac, connection resets, timeouts) are
    * auto-retried this many times before the agent fails (initial + retries). */
  private val LlmFailRetryMax = 2
  private val logger = NebflowLogger.forName("nebflow.agent")

  /**
   * Infer the injection source (任务 P) for a tool-originated UserInput that
   * did not carry an explicit source. Discriminator: clientMessageId is empty
   * (user WS inputs always carry one). The source is derived from the agent's
   * own session id prefix, falling back to the fork-adapter replyTo for
   * Mail ask/fork spawns, and finally the generic "tool".
   *
   *   delegate-… → "delegate"   SubTaskTool worker
   *   subtask-…  → "subtask"    SubTaskTool worker
   *   dag-…      → "flow"       FlowDagExecutor node
   *   fork-adapter replyTo      → "ask"        Mail ask/fork
   *   otherwise  → "tool"
   */
  private def inferInjectionSource(
    sessionId: Option[String],
    replyTo: Option[ActorRef[AgentEvent]]
  ): Option[String] =
    val sid = sessionId.getOrElse("")
    if sid.startsWith("delegate-") then Some("delegate")
    else if sid.startsWith("subtask-") then Some("subtask")
    else if sid.startsWith("dag-") then Some("flow")
    else if replyTo.exists(_.path.name.startsWith("fork-adapter")) then Some("ask")
    else Some("tool")

  /**
   * ExternalEvent sources whose result-notification payload should render as a
   * visible injected-user bubble (任务 Q, report §3 🔴). Low-value internal
   * sources (mail-ask, schedule, filewatch, bridge-already-visible, etc.) stay
   * hidden — only the high-value result notifications get a bubble.
   *
   *   delegate / subtask  → A6/A9 sub-agent completion
   *   background-task      → B1 background command completion
   *   eventType=="inject"  → B4 API inject (source is user-controlled)
   */
  private def visibleExternalEventSource(source: String, eventType: String): Option[String] =
    if eventType == "inject" then Some(source)
    else if source == "delegate" then Some("delegate")
    else if source == "subtask" then Some("subtask")
    else if source == "background-task" then Some("background")
    else None

  /**
   * Emit a `user` WS event so the frontend renders an injected-task bubble
   * (浅蓝, source-labeled) instead of leaving the task prompt invisible.
   * Mirrors the `user` event shape the frontend already handles for normal
   * user input (SessionRecorder records it as UiMessage.User with injected=true).
   */
  private def emitInjectedUserEvent(
    wsSend: Json => IO[Unit],
    sessionId: Option[String],
    text: String,
    source: String
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      ctx.forkTurn(
        wsSend(
          Json.obj(
            "type" -> "user".asJson,
            "text" -> text.asJson,
            "injected" -> true.asJson,
            "source" -> source.asJson,
            "sessionId" -> sid.asJson
          )
        ).handleErrorWith(e =>
          IO(logger.warn(s"injected user event failed: ${e.getMessage}"))
        )
      )
    }

  def apply(
    agentDef: AgentDef,
    resources: SharedResources,
    wsSend: io.circe.Json => IO[Unit],
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]] = None,
    sessionId: Option[String] = None,
    sessionName: Option[String] = None,
    initialMessages: List[Message] = Nil,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None,
    fileHistory: Option[nebflow.core.tools.FileHistory] = None,
    contextWindow: Int = Defaults.ContextWindow,
    projectRoot: Option[String] = None,
    rulesMd: Option[String] = None,
    folderId: Option[String] = None,
    safetyMode: String = "confirm-edits",
    gitBranch: Option[String] = None,
    expectsMail: Boolean = false,
    /**
     * P2: permission-policy bucket (inheritance anchor). Explicitly passed at
     * every spawn site (Root=itself, Team=parent root session, Flow/Ephemeral=
     * itself, Delegate=resolved caller root). Falls back to sessionId so legacy
     * spawn sites still get a sane bucket (P1 best-effort behavior).
     */
    rootSessionId: String = "",
    isSubTaskWorker: Boolean = false
  ): Behavior[AgentCommand] =
    Behaviors.setup { ctx =>
      val effectiveRootSessionId =
        if rootSessionId.nonEmpty then rootSessionId else sessionId.getOrElse("")
      logAgentEvent(
        agentDef,
        depth,
        sessionId,
        sessionName,
        "spawn",
        s"parent=${parentRef.map(_.path.name).getOrElse("-")} msgs=${initialMessages.size}"
      )(using ctx)
      IO.pure(
        idle(
          agentDef,
          resources,
          depth,
          parentRef,
          AgentState(
            messages = initialMessages,
            status = AgentStatus.Idle,
            depth = depth,
            sessionId = sessionId,
            sessionName = sessionName,
            pendingCompaction = None,
            latestUsage = None,
            pendingAskUser = None,
            pendingPermission = None,
            wsSend = wsSend,
            readTracker = readTracker,
            fileHistory = fileHistory,
            contextWindow = contextWindow,
            projectRoot = projectRoot,
            rulesMd = rulesMd,
            folderId = folderId,
            safetyMode = safetyMode,
            gitBranch = gitBranch,
            expectsMail = expectsMail,
            rootSessionId = effectiveRootSessionId,
            isSubTaskWorker = isSubTaskWorker
          )
        )(using ctx)
      )
    }

  private def buildHookContext(state: AgentState): nebflow.core.hooks.HookContext =
    nebflow.core.hooks.HookContext(
      sessionId = state.sessionId,
      projectRoot = state.projectRoot.getOrElse(""),
      cwd = state.projectRoot.getOrElse("")
    )

  private def fireLifecycleStopHooks(resources: SharedResources, state: AgentState)(using
    ctx: ActorContext[AgentCommand]
  ): IO[Unit] =
    if state.depth == 0 then
      val hookCtx = buildHookContext(state)
      ctx.forkTurn(resources.hookEngine.onStop(hookCtx) *> resources.hookEngine.onSessionEnd(hookCtx))
    else IO.unit

  /**
   * Build the "askUser" WS payload for the frontend. When the question comes
   * from a sub-agent (ForwardAskUser), agentName is set to the source agent
   * for attribution and sourceAgent/sourceSession carry the origin info.
   */
  private def buildAskUserJson(
    sessionId: Option[String],
    agentName: String,
    items: List[AskItem],
    sourceAgent: Option[String] = None,
    sourceSession: Option[String] = None
  ): Json =
    val fields = scala.collection.mutable.ListBuffer(
      "type" -> "askUser".asJson,
      "sessionId" -> sessionId.asJson,
      "agentName" -> agentName.asJson
    )
    sourceAgent.foreach(sa => fields += "sourceAgent" -> sa.asJson)
    sourceSession.foreach(ss => fields += "sourceSession" -> ss.asJson)
    fields += "items" -> Json.fromValues(items.map { item =>
      val base = scala.collection.mutable.ListBuffer(
        "question" -> item.question.asJson,
        "options" -> Json.fromValues(item.options.map { opt =>
          val optFields = scala.collection.mutable.ListBuffer("label" -> opt.label.asJson)
          opt.description.foreach(d => optFields += "description" -> d.asJson)
          Json.obj(optFields.toList*)
        }),
        "allowOther" -> item.allowOther.asJson
      )
      item.id.foreach(id => base += "id" -> id.asJson)
      item.dependsOn.foreach { dep =>
        base += "dependsOn" -> Json.obj("ref" -> dep.ref.asJson, "equals" -> dep.equals.asJson)
      }
      Json.obj(base.toList*)
    })
    Json.obj(fields.toList*)

  // ============================================================
  // Idle state
  // ============================================================

  private def idle(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      case AgentCommand.UserInput(text, replyTo, clientMessageId, blocks, chatWidth, source) =>
        val (isDuplicate, dedupedState) = checkDuplicate(clientMessageId, state)
        if isDuplicate then
          logger.info(s"Dropping duplicate message with clientMessageId=${clientMessageId.getOrElse("")}")
          IO.pure(idle(agentDef, resources, depth, parentRef, state))
        else
          logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "start", s"msgs=${state.messages.size}")
          val stateWithLang =
            if depth == 0 && parentRef.isEmpty && dedupedState.messages.isEmpty then
              LanguageDetector.detect(text) match
                case Some(lang) =>
                  NebflowLogger.forName("nebflow.agent").info(s"Auto-detected language: $lang")
                  dedupedState.withLanguage(Some(lang))
                case None => dedupedState
            else dedupedState
          val stateWithWidth =
            if chatWidth > 0 then stateWithLang.copy(session = stateWithLang.session.copy(chatWidth = chatWidth))
            else stateWithLang
          // Injection source (任务 P): user WS inputs always carry clientMessageId.
          // Tool-injected inputs (Mail ask/fork, Delegate, SubTask, ImmediateInput
          // converted from Mail delivery) have clientMessageId=None → emit a user
          // event so the frontend renders the task prompt as a visible bubble.
          val injectionSource: Option[String] =
            if clientMessageId.isDefined then None
            else source.orElse(inferInjectionSource(state.sessionId, replyTo))
          val userMsg = (blocks.filter(_.nonEmpty) match
            case Some(bl) => Message(MessageRole.User, Right(bl))
            case None => Message(MessageRole.User, Left(text))
          ).copy(source = injectionSource)
          val newMessages = stateWithWidth.messages :+ userMsg
          val sessionBusyIO =
            if depth == 0 then
              stateWithWidth.sessionId.fold(IO.unit)(sid => emitSessionBusy(stateWithWidth.wsSend, sid, busy = true))
            else IO.unit
          val injectedEventIO = injectionSource match
            case Some(src) => emitInjectedUserEvent(stateWithWidth.wsSend, stateWithWidth.sessionId, text, src)
            case None => IO.unit
          for
            _ <- sessionBusyIO
            _ <- injectedEventIO
            result <- pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              stateWithWidth.withMessages(newMessages).withEmptyResponseRetries(0).withMailUsedThisTurn(false),
              replyTo
            )
          yield result
        end if

      case AgentCommand.AskQuestion(question, askSessionId) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "ask-start", s"q=${question.take(60)}")
        val askReminder = AskService.buildAskReminder(question)
        val askState = state
          .withMessages(state.messages :+ askReminder)
          .withAskMode(Some(question))
          .withStatus(AgentStatus.Processing)
        pipeLlmCall(agentDef, resources, depth, parentRef, askState, None)

      case AgentCommand.SkillActivate(skillName, input, skillSessionId, skillContent, skillBaseDir) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "skill-start",
          s"skill=$skillName input=${input.take(60)}"
        )
        val combinedText = s"<skill name=\"$skillName\">\n$skillContent\n</skill>\n\n$input"
        val processingState = state
          .withMessages(
            state.messages :+ Message(MessageRole.User, Left(combinedText), source = Some("skill"))
          )
          .withStatus(AgentStatus.Processing)
        val sessionBusyIO2 =
          if depth == 0 then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        for
          _ <- sessionBusyIO2
          _ <- emitInjectedUserEvent(state.wsSend, state.sessionId, input, "skill")
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, processingState, None)
        yield result

      case AgentCommand.Interrupt() =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.Stop(_) =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.fold(IO.unit)(t => t.clear()) *>
          IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.ResetSession =>
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

      case AgentCommand.TriggerCompaction(mode, replyDeferred, postCompactInstruction) =>
        handleTriggerCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          mode,
          replyDeferred,
          resumeAfterCompact = postCompactInstruction.isDefined,
          postCompactInstruction = postCompactInstruction
        )

      case AgentCommand.UpdateContextWindow(window) =>
        val newState = state.withContextWindow(window)
        val estimatedTokens = TokenEstimator.estimate(newState.messages)
        val threshold = CompactThreshold.threshold(window)
        if newState.messages.nonEmpty && estimatedTokens > threshold then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "model-switch-compact",
            s"newWindow=$window estimated=$estimatedTokens threshold=$threshold msgs=${newState.messages.size}"
          )
          handleTriggerCompaction(
            agentDef,
            resources,
            depth,
            parentRef,
            newState,
            "full",
            None,
            resumeAfterCompact = false
          )
        else IO.pure(idle(agentDef, resources, depth, parentRef, newState))
        end if

      case n: AgentCommand.BackgroundTaskNotification =>
        (ctx.self ! n.toExternalEvent) *> IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event",
          s"source=$source type=$eventType"
        )
        val sessionBusyIO =
          if depth == 0 then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
          else IO.unit
        // 任务 Q: high-value result notifications (sub-agent/flow/background/api
        // completion) render as a visible injected-user bubble so the user can
        // see "what result arrived" in the chat stream, not just the LLM history.
        val visSource = visibleExternalEventSource(source, eventType)
        for
          _ <- sessionBusyIO
          _ <- emitStream(
            state.wsSend,
            AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
            isSubagent = depth > 0,
            state.sessionId
          )
          _ <- visSource match
            case Some(s) => emitInjectedUserEvent(state.wsSend, state.sessionId, payload, s)
            case None => IO.unit
          result <- pipeLlmCall(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withMessages(
              state.messages :+ Message(MessageRole.User, Left(payload), source = visSource)
            ),
            None
          )
        yield result
        end for

      case AgentCommand.UpdateGitBranch(branch) =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state.withGitBranch(branch)))

      case AgentCommand.CompactionComplete(result) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "stale-compaction-discarded",
          result.fold(err => s"err=${err.take(60)}", msgs => s"ok=${msgs.size}msgs")
        )
        state.pendingCompaction.flatMap(_.replyDeferred) match
          case Some(d) =>
            ctx.forkTurn(
              d.complete(Left("Compaction result arrived after agent returned to idle"))
                .void
                .handleErrorWith(_ => IO.unit)
            ) *> IO.pure(idle(agentDef, resources, depth, parentRef, state))
          case None => IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.SetSafetyMode(mode) =>
        // P2: write the root session's permission-policy bucket (dynamic
        // inheritance). The per-agent safetyMode copy is kept in sync for P3
        // cleanup; the decision point reads the policy, not this field.
        val policy = PermissionPolicy(safetyMode = mode)
        resources.permissionPolicies
          .update(_ + (state.session.rootSessionId -> policy)) *>
          IO.pure(
            idle(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withSafetyMode(nebflow.core.SafetyMode.toString(mode))
            )
          )

      case AgentCommand.StartPlan(task) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "plan-start", s"task=${task.take(60)}")
        val projectRootStr = state.projectRoot.getOrElse(resources.projectRoot.toString)
        // Plan mode is Nebula's own capability — uses Explorer (read-only) for safe planning.
        resources.agentLibrary.get("Explorer").flatMap {
          case Some(explorerDef) =>
            val planningDef = explorerDef.copy(
              systemPrompt = PlanAgent.PlanningPrompt + "\n\n" + explorerDef.systemPrompt
            )
            PlanAgent
              .spawn(
                agentDef = planningDef,
                task = task,
                mainAgentRef = ctx.self,
                system = ctx.system,
                resources = resources,
                parentDepth = depth,
                wsSend = state.wsSend,
                projectRoot = projectRootStr,
                parentSessionId = state.sessionId
              )
              .map { planAgentRef =>
                val planState = PlanModeState(planAgentRef = planAgentRef, taskDescription = task)
                planWaiting(agentDef, resources, depth, parentRef, state.withPlanMode(Some(planState)))
              }
          case None =>
            ctx
              .forkTurn(
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "error".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "message" -> "Explorer agent not found — cannot start plan mode".asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
              .as(idle(agentDef, resources, depth, parentRef, state))
        }

      case _: AgentCommand.LlmComplete | _: AgentCommand.LlmFailed | _: AgentCommand.ToolsComplete |
          _: AgentCommand.SetPermissionDeferred | _: AgentCommand.ReplaceToolResults =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))

      // Immediate input arriving in idle (turn already finished) — treat as normal UserInput
      case AgentCommand.ImmediateInput(text, blocks, source) =>
        for _ <- ctx.self ! AgentCommand.UserInput(text, None, None, blocks, 0, source)
        yield idle(agentDef, resources, depth, parentRef, state)

      // Supervisor restart in idle state
      case AgentCommand.RestartAgent(level) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "restart-idle", s"level=${level.toString}")
        val restartState = level match
          case RestartLevel.Rollback => rollbackLastToolCall(state)
          case _ => state
        for
          _ <- state
            .wsSend(
              Json.obj(
                "type" -> "agentRestarted".asJson,
                "sessionId" -> state.sessionId.asJson,
                "level" -> level.toString.asJson
              )
            )
            .handleErrorWith(_ => IO.unit)
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, restartState, None)
        yield result

      case _ =>
        IO.pure(idle(agentDef, resources, depth, parentRef, state))
  end idle
  // ============================================================
  // Plan waiting state — main agent blocked while plan agent works
  // ============================================================

  private def planWaiting(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    Behaviors.receiveMessage:

      case AgentCommand.PlanTurnComplete(planText) =>
        val updatedPlanState = state.planMode.map(_.copy(currentPlanText = planText))
        for _ <- ctx.forkTurn(
            state
              .wsSend(
                Json.obj(
                  "type" -> "planReady".asJson,
                  "sessionId" -> state.sessionId.asJson
                )
              )
              .handleErrorWith(_ => IO.unit)
          )
        yield planWaiting(agentDef, resources, depth, parentRef, state.withPlanMode(updatedPlanState))

      case AgentCommand.PlanFeedback(text) =>
        state.planMode match
          case Some(pm) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "plan-feedback",
              s"text=${text.take(60)}"
            )
            (pm.planAgentRef ! AgentCommand.UserInput(text)) *>
              IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))
          case None =>
            IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PlanApproved =>
        state.planMode match
          case Some(pm) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "plan-approved",
              s"textLen=${pm.currentPlanText.length}"
            )
            for
              _ <- ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit)
              _ <- ctx.forkTurn(
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "planEnd".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "reason" -> "approved".asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
              // Inject approved plan as user message so the main agent executes it
              planMsg = Message(
                MessageRole.User,
                Left(
                  s"User requested planning for: ${pm.taskDescription}\n\n" +
                    s"The plan below has been approved by the user. Execute it now.\n\n" +
                    s"## Approved Plan\n\n${pm.currentPlanText}\n\n" +
                    s"Start by creating tasks with TaskCreate to track progress, then execute each step."
                )
              )
              newMessages = state.messages :+ planMsg
              sessionBusyIO = state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true))
              result <- sessionBusyIO *> pipeLlmCall(
                agentDef,
                resources,
                depth,
                parentRef,
                state.withMessages(newMessages).withPlanMode(None),
                None
              )
            yield result
            end for
          case None =>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PlanCancelled =>
        state.planMode match
          case Some(pm) =>
            logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "plan-cancelled", "")
            for
              _ <- ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit)
              _ <- ctx.forkTurn(
                state
                  .wsSend(
                    Json.obj(
                      "type" -> "planEnd".asJson,
                      "sessionId" -> state.sessionId.asJson,
                      "reason" -> "cancelled".asJson
                    )
                  )
                  .handleErrorWith(_ => IO.unit)
              )
            yield idle(agentDef, resources, depth, parentRef, state.withPlanMode(None))
          case None =>
            IO.pure(idle(agentDef, resources, depth, parentRef, state))

      case AgentCommand.PlanFailed(error) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "plan-failed", s"err=${error.take(80)}")
        state.planMode.foreach(pm => ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit))
        for
          _ <- ctx.forkTurn(
            state
              .wsSend(
                Json.obj(
                  "type" -> "planEnd".asJson,
                  "sessionId" -> state.sessionId.asJson,
                  "reason" -> "failed".asJson,
                  "error" -> error.asJson
                )
              )
              .handleErrorWith(_ => IO.unit)
          )
          _ <- ctx.forkTurn(
            state.sessionId.fold(IO.unit)(sid =>
              state
                .wsSend(
                  Json.obj(
                    "type" -> "error".asJson,
                    "sessionId" -> sid.asJson,
                    "message" -> s"Plan agent failed: $error".asJson
                  )
                )
                .handleErrorWith(_ => IO.unit)
            )
          )
        yield idle(agentDef, resources, depth, parentRef, state.withPlanMode(None))
        end for

      case AgentCommand.Interrupt() =>
        state.planMode.foreach(pm => ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit))
        for _ <- ctx.forkTurn(
            state
              .wsSend(
                Json.obj(
                  "type" -> "planEnd".asJson,
                  "sessionId" -> state.sessionId.asJson,
                  "reason" -> "cancelled".asJson
                )
              )
              .handleErrorWith(_ => IO.unit)
          )
        yield idle(agentDef, resources, depth, parentRef, state.withPlanMode(None))

      case AgentCommand.Stop(_) =>
        state.planMode.foreach(pm => ctx.system.stop(pm.planAgentRef).handleErrorWith(_ => IO.unit))
        for _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.SetSafetyMode(mode) =>
        val policy = PermissionPolicy(safetyMode = mode)
        resources.permissionPolicies
          .update(_ + (state.session.rootSessionId -> policy)) *>
          IO.pure(
            planWaiting(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withSafetyMode(nebflow.core.SafetyMode.toString(mode))
            )
          )

      // Buffer user messages while planning
      case msg: AgentCommand.UserInput =>
        IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))

      case _ =>
        IO.pure(planWaiting(agentDef, resources, depth, parentRef, state))

  end planWaiting
  // ============================================================
  // Processing state
  // ============================================================

  private def processing(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    pending: List[AgentCommand] = Nil
  )(using ctx: ActorContext[AgentCommand]): Behavior[AgentCommand] =
    val base = Behaviors.receiveMessage[AgentCommand]:

      case AgentCommand.UpdateGitBranch(branch) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withGitBranch(branch), pending))

      // --- LLM completed ---
      case LlmComplete(result, replyTo, turnId) =>
        if turnId != state.currentTurnId then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "stale-llm-complete-discarded",
            s"turnId=$turnId current=${state.currentTurnId}"
          )
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val updatedState = state
            .withLatestUsage(result.usage.orElse(state.latestUsage))
            .withLastModel(result.model.orElse(state.lastModel))
            .withLlmFailRetries(0)
            .updateContextWindowIfNeeded(result.contextWindow)
          val isSubagent = depth > 0
          // Emit usageUpdate: use provider-reported tokens if available, otherwise
          // estimate from message history so the UI context bar always updates.
          val effectiveTokens = updatedState.latestUsage
            .map(_.inputTokens)
            .filter(_ > 0)
            .getOrElse(nebflow.core.compact.TokenEstimator.estimate(updatedState.messages))
          val usageEvent: IO[Unit] =
            emitStream(
              state.wsSend,
              AgentStreamEvent.UsageUpdate(
                effectiveTokens,
                updatedState.contextWindow,
                CompactThreshold.thresholdRatio(updatedState.contextWindow)
              ),
              isSubagent = isSubagent,
              state.sessionId
            )
          usageEvent *> handleLlmCompleteBranch(
            agentDef,
            resources,
            depth,
            parentRef,
            updatedState,
            replyTo,
            result,
            pending
          )
        end if

      case LlmFailed(error, replyTo, turnId) =>
        if turnId != state.currentTurnId then
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "stale-llm-failed-discarded",
            s"turnId=$turnId current=${state.currentTurnId}"
          )
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else if state.pendingCompaction.exists(_.phase == CompactionPhase.Save) then
          // Save turn LLM call failed → skip the save phase and go straight to
          // the compact turn. Not counted in compactionFailures (save is best-effort).
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "save-phase-fallback",
            s"err=${error.getMessage.take(80)}"
          )
          val compactState = state
            .withPendingCompaction(state.pendingCompaction.map(_.copy(phase = CompactionPhase.Compact)))
            .withMessages(state.messages :+ CompactService.buildCompactReminder(depth))
          pipeLlmCall(agentDef, resources, depth, parentRef, compactState, replyTo)
        else
          val cleanedState = state
          logAgentEvent(
            agentDef,
            depth,
            state.sessionId,
            state.sessionName,
            "llm-fail",
            s"err=${error.getMessage.take(80)}"
          )
          // Auto-retry transient LLM failures (bad_record_mac, connection
          // resets, timeouts, empty streams): re-dispatch from the last
          // checkpoint up to LlmFailRetryMax times. A transient blip must not
          // kill a sub-agent mid-task (observed: bad_record_mac killed
          // Explorer 3× in a row, losing the task each time). Permanent/fatal
          // errors fail immediately — retrying won't heal auth/model errors.
          val retryable = error match
            case e: FallbackExhaustedError =>
              // Retry only if every attempt was transient/unknown — any
              // permanent failure anywhere means retry is pointless.
              e.attempts.forall(a => a.permanence.forall(_ == ErrorPermanence.Transient))
            case _: ToolPipelineError => false
            case _ => Fallback.classifyError(error).permanence == ErrorPermanence.Transient
          if retryable && state.llmFailRetries < LlmFailRetryMax then
            val retryState = state.withLlmFailRetries(state.llmFailRetries + 1)
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "llm-fail-retry",
              s"err=${error.getMessage.take(80)} retry=${retryState.llmFailRetries}/$LlmFailRetryMax"
            )
            // The failed LLM call produced no content — re-dispatch with the
            // same messages from the last checkpoint.
            pipeLlmCall(agentDef, resources, depth, parentRef, retryState, replyTo)
          else
            val agentError =
              AgentError(ctx.self.path.name, agentDef.name, depth, AgentErrorType.LlmFailed, error.getMessage)
            val errMsg = error match
              case e: FallbackExhaustedError =>
                val attemptSummaries =
                  e.attempts.map(a => s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}")
                NebflowError.toUserMessage(NebflowError.LlmFailed(e.getMessage, attemptSummaries))
              case e: ToolPipelineError =>
                e.message
              case _ =>
                NebflowError.toUserMessage(
                  NebflowError.Internal(Option(error.getMessage).getOrElse("internalError"))
                )
            for

              _ <- cleanedState.sessionId.fold(IO.unit) { sid =>
                val doneEvent = AgentStreamEvent.Done(None)
                val doneJson = doneEvent.toJson(ctx.self.path.name, false, cleanedState.sessionId)
                ctx.forkTurn(
                  (cleanedState
                    .wsSend(
                      Json.obj(
                        "type" -> "error".asJson,
                        "sessionId" -> cleanedState.sessionId.asJson,
                        "message" -> errMsg.asJson
                      )
                    )
                    .handleErrorWith(_ => IO.unit)) *>
                    cleanedState.wsSend(doneJson).handleErrorWith(_ => IO.unit) *>
                    emitSessionBusy(cleanedState.wsSend, sid, busy = false)
                )
              }
              _ <- cleanedState.sessionId.fold(IO.unit) { sid =>
                // Persist the conversation before failing so the parent can
                // resume from saved history (re-dispatch / fork on this session).
                ctx.forkTurn(
                  (resources.sessionStore.saveMessagesForSession(sid, cleanedState.messages) *>
                    resources.sessionStore.flushIndex)
                    .handleErrorWith(e =>
                      IO(NebflowLogger.forName("nebflow.agent").warn(s"Save failed session: ${e.getMessage}"))
                    )
                )
              }
              _ <- replyTo.traverse_(_ ! AgentEvent.Failed(cleanedState.sessionId.getOrElse(""), agentError))
            yield idle(
              agentDef,
              resources,
              depth,
              parentRef,
              cleanedState.withStatus(AgentStatus.Error(error.getMessage))
            )
            end for
          end if
        end if

      // --- Tools completed ---
      case tc: ToolsComplete =>
        val toolCalls = tc.results.map((call, _) => call)
        val assistantBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
        tc.thinking.foreach(t => assistantBlocks += ContentBlock.Thinking(t, tc.thinkingSignature))
        if tc.originalText.nonEmpty then assistantBlocks += ContentBlock.Text(tc.originalText)
        toolCalls.foreach(c => assistantBlocks += ContentBlock.ToolUse(c.id, c.name, c.input))
        val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks.toList))
        val resultBlocks = tc.results.map { (call, r) =>
          ContentBlock.ToolResult(call.id, r.content, Some(r.isError))
        }
        val resultMsg = Message(MessageRole.User, Right(resultBlocks))
        // Collect image blocks from tool results and inject as a separate user message.
        // For Anthropic: mergeConsecutive merges this with resultMsg → single user message
        //   with tool_result + image blocks (valid Anthropic format).
        // For OpenAI: resultMsg → {role: "tool"} messages, imageMsg → {role: "user"} message
        //   with image_url content (correct OpenAI format for tool results + images).
        val imageBlocks = tc.results.flatMap { (call, r) =>
          r.imageBlocks.getOrElse(List.empty[ContentBlock.Image])
        }
        val imageMsgs =
          if imageBlocks.nonEmpty then List(Message(MessageRole.User, Right(imageBlocks)))
          else Nil
        val baseMessages = tc.compactedMessages.getOrElse(state.messages)
        // Inject ONE queued external event alongside tool results (serial
        // processing, same as pendingImmediateInputs). Multiple simultaneously
        // due events (e.g. scheduled tasks) are consumed strictly one at a time
        // — no concurrency, no aggregation — the remainder stays queued for the
        // next turn boundary.
        val (eventOpt, remainingEvents) = state.execution.pendingEvents.headOption match
          case Some(e) => (Some(e), state.execution.pendingEvents.drop(1))
          case None => (None, state.execution.pendingEvents)
        val eventMessages = eventOpt match
          case Some(e) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "pending-events-injected-at-tools-complete",
              s"events=1 remaining=${remainingEvents.size}"
            )
            List(Message(MessageRole.User, Left(s"<system-reminder>\n${e.payload}\n</system-reminder>")))
          case None => Nil
        // Inject ONE queued immediate input alongside tool results (serial processing).
        // While compaction is in progress, keep inputs queued — injecting mid-compaction
        // risks the input being lost in the summary. CompactionComplete drains them.
        val (immInputOpt, remainingImmInputs) =
          if state.pendingCompaction.isEmpty then
            (state.execution.pendingImmediateInputs.headOption, state.execution.pendingImmediateInputs.drop(1))
          else (None, state.execution.pendingImmediateInputs)
        val immediateMessages = immInputOpt match
          case Some(imm) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "immediate-input-injected-at-tools-complete",
              s"text=${imm.text.take(60)} remaining=${remainingImmInputs.size}"
            )
            List((imm.blocks match
              case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
              case _ => Message(MessageRole.User, Left(imm.text))
            ).copy(source = imm.source))
          case None => Nil
        val immEventIO = immInputOpt match
          case Some(imm) if imm.source.isDefined =>
            emitInjectedUserEvent(state.wsSend, state.sessionId, imm.text, imm.source.get)
          case _ => IO.unit
        val newMessages =
          baseMessages ++ List(assistantMsg, resultMsg) ++ imageMsgs ++ eventMessages ++ immediateMessages
        // Increment delegate count for Delegate/SubTask calls
        val delegateIncrement = toolCalls.count(c => c.name == "Delegate" || c.name == "SubTask")
        val newDelegateCount = state.delegateCount + delegateIncrement
        val updatedState =
          state.copy(execution =
            state.execution
              .copy(
                messages = newMessages,
                // D3: keep a pending permission across the turn boundary — the
                // root holds the sub-agent's Deferred while waiting for the user
                // answer; clearing it here would strand the sub-agent until the
                // 5-minute timeout. Only pendingPermission survives (bounded by
                // PermissionTimeout), pendingAskUserReplyTo still resets.
                interaction = state.execution.interaction.filter(_.pendingPermission.isDefined),
                pendingEvents = remainingEvents,
                pendingImmediateInputs = remainingImmInputs,
                delegateCount = newDelegateCount,
                mailUsedThisTurn = state.mailUsedThisTurn ||
                  tc.results.exists((call, r) => call.name == "Mail" && !r.isError)
              )
          )
        for
          _ <- ctx.forkTurn(
            persistIfSession(resources, updatedState)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Persist session failed: ${e.getMessage}"))
              )
          )
          _ <- immEventIO
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, tc.replyTo)
        yield result

      // --- Interrupt ---
      case AgentCommand.Interrupt() =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "interrupt", "reason=user")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)

          _ <- state.pendingCompaction
            .flatMap(_.replyDeferred)
            .traverse_(d => d.complete(Left("Interrupted by user")).void.handleErrorWith(_ => IO.unit))
          // Back to idle without finishing the turn — clear the busy mark so a
          // interrupted team agent isn't stuck "running" in the Teams panel.
          _ <- markTeamIdle(agentDef, state.sessionId)
        yield
          val interruptedState = state.resetForInterrupt.withPendingCompaction(None)
          idle(agentDef, resources, depth, parentRef, interruptedState)

      // --- Retry: cancel current work, re-dispatch from last checkpoint ---
      case AgentCommand.Retry(reason) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "retry", s"reason=$reason")
        ctx.cancelCurrentTurn() *> (state.lastDispatch match
          case Some(LastDispatch(false, _)) =>
            // Re-dispatch LLM call with same messages
            pipeLlmCall(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              None,
              (ad, r, d, p, s) => processing(ad, r, d, p, s)
            )
          case Some(LastDispatch(true, Some(cr))) =>
            // Re-dispatch tool execution with same LLM result
            pipeToolExecutions(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              cr,
              None,
              (ad, r, d, p, s) => processing(ad, r, d, p, s)
            )
          case _ =>
            // No checkpoint — go to idle
            markTeamIdle(agentDef, state.sessionId) *>
              IO.pure(idle(agentDef, resources, depth, parentRef, state.resetForInterrupt)))

      // --- Supervisor restart ---
      case AgentCommand.RestartAgent(level) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "restart", s"level=${level.toString}")
        for
          _ <- ctx.cancelCurrentTurn()
          _ <- state.pendingCompaction
            .flatMap(_.replyDeferred)
            .traverse_(d => d.complete(Left("Restarted by supervisor")).void.handleErrorWith(_ => IO.unit))
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
          restartState = level match
            case RestartLevel.Soft =>
              state.resetForInterrupt.withPendingCompaction(None)
            case RestartLevel.Rollback =>
              rollbackLastToolCall(state.resetForInterrupt.withPendingCompaction(None))
            case _ =>
              state.resetForInterrupt.withPendingCompaction(None)
          _ <- state
            .wsSend(
              Json.obj(
                "type" -> "agentRestarted".asJson,
                "sessionId" -> state.sessionId.asJson,
                "level" -> level.toString.asJson
              )
            )
            .handleErrorWith(_ => IO.unit)
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, restartState, None)
        yield result
        end for

      // --- Stop ---
      case AgentCommand.Stop(_) =>
        logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "stop", "reason=user")
        for
          _ <- ctx.cancelCurrentTurn()

          _ <- fireLifecycleStopHooks(resources, state)
        yield Behaviors.stopped

      case AgentCommand.ClearReadTracker =>
        state.readTracker.fold(IO.unit)(t => t.clear()) *>
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      case AgentCommand.ResetSession =>
        for

          _ <- state.readTracker.fold(IO.unit)(t => t.clear())
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
        yield
          val resetState = state
            .withMessages(Nil)
            .withLatestUsage(None)
            .withPendingCompaction(None)
            .withCompactionFailures(0)
            .withLastCompactionFailureAt(0L)
            .withRecentMessageIds(Nil)
            .resetToIdle(Nil)
          idle(agentDef, resources, depth, parentRef, resetState)

      // --- ReplaceToolResults ---
      case AgentCommand.ReplaceToolResults(rounds, summary, replyTo) =>
        replaceToolResults(state.messages, rounds, summary) match
          case Right((updatedMessages, count)) =>
            replyTo.complete(Right(count)).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state.withMessages(updatedMessages), pending))
          case Left(err) =>
            replyTo.complete(Left(err)).void.handleErrorWith(_ => IO.unit) *>
              IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- Compaction completed ---
      case AgentCommand.CompactionComplete(result) =>
        if state.pendingCompaction.isEmpty then
          IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))
        else
          val compactionPending = state.pendingCompaction
          result match
            case Right(compactedMessages) =>
              logAgentEvent(
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "compaction-complete",
                s"before=${state.messages.size} after=${compactedMessages.size}"
              )
              val archiveIO = state.sessionId match
                case Some(sid) =>
                  resources.historyArchiver.archiveCompaction(
                    sessionId = sid,
                    sessionName = state.sessionName,
                    agentName = agentDef.name,
                    before = state.messages,
                    after = compactedMessages,
                    mode = compactionPending.map(_.mode).getOrElse("full"),
                    extra = Map("preservedRounds" -> "0")
                  )
                case None => IO.pure(Left("no sessionId"))
              val compactEmitIO = archiveIO
                .flatMap {
                  case Right(archive) =>
                    emitStreamIO(
                      state.wsSend,
                      AgentStreamEvent
                        .CompactComplete(state.messages.size, compactedMessages.size, Some(archive.reportPath)),
                      isSubagent = depth > 0,
                      state.sessionId
                    )
                  case Left(err) =>
                    NebflowLogger.forName("nebflow.agent").warn(s"Compaction archive failed: $err")
                    emitStreamIO(
                      state.wsSend,
                      AgentStreamEvent.CompactComplete(state.messages.size, compactedMessages.size, None),
                      isSubagent = depth > 0,
                      state.sessionId
                    )
                }
                .handleErrorWith(_ => IO.unit)
              val compactedState = state
                .withMessages(compactedMessages)
                .withPendingCompaction(None)
                .withCompactionFailures(0)
                .withEmptyResponseRetries(0)
                .withLatestUsage(None)
              if compactionPending.exists(_.resumeAfterCompact) then
                val stateWithInstruction = compactionPending.flatMap(_.postCompactInstruction) match
                  case Some(instruction) =>
                    compactedState.withMessages(compactedState.messages :+ Message(MessageRole.User, Left(instruction)))
                  case None => compactedState
                ctx.forkTurn(compactEmitIO) *>
                  pipeLlmCall(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    stateWithInstruction,
                    compactionPending.flatMap(_.replyTo)
                  )
              else
                // Drain any immediate inputs queued during compaction — inject the head
                // and continue processing instead of silently dropping them at idle.
                val immInputOpt = compactedState.execution.pendingImmediateInputs.headOption
                val remainingImmInputs = compactedState.execution.pendingImmediateInputs.drop(1)
                for
                  _ <- ctx.forkTurn(compactEmitIO)
                  _ <- ctx.forkTurn(
                    persistIfSession(resources, compactedState)
                      .handleErrorWith(e =>
                        IO(
                          NebflowLogger.forName("nebflow.agent").warn(s"Persist after compact failed: ${e.getMessage}")
                        )
                      )
                  )
                  result <- immInputOpt match
                    case Some(imm) =>
                      logAgentEvent(
                        agentDef,
                        depth,
                        state.sessionId,
                        state.sessionName,
                        "immediate-input-injected-after-compaction",
                        s"text=${imm.text.take(60)} remaining=${remainingImmInputs.size}"
                      )
                      val immMessage = (imm.blocks match
                        case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
                        case _ => Message(MessageRole.User, Left(imm.text))
                      ).copy(source = imm.source)
                      val drainedState = compactedState.copy(execution =
                        compactedState.execution.copy(
                          messages = compactedState.messages :+ immMessage,
                          pendingImmediateInputs = remainingImmInputs
                        )
                      )
                      for
                        _ <- imm.source match
                          case Some(src) => emitInjectedUserEvent(state.wsSend, state.sessionId, imm.text, src)
                          case None => IO.unit
                        res <- pipeLlmCall(agentDef, resources, depth, parentRef, drainedState, None)
                      yield res
                    case None => IO.pure(idle(agentDef, resources, depth, parentRef, compactedState))
                yield result
              end if
            case Left(err) =>
              logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "compaction-failed", s"err=$err")
              val now = System.currentTimeMillis()
              val failedState = state
                .withPendingCompaction(None)
                .withCompactionFailures(state.compactionFailures + 1)
                .withLastCompactionFailureAt(now)
              val baseIO: IO[Unit] = for
                _ <- ctx.forkTurn(
                  emitStreamIO(
                    state.wsSend,
                    AgentStreamEvent.CompactFailed(
                      err,
                      state.compactionFailures + 1,
                      CompactConfig().circuitBreakerMax
                    ),
                    isSubagent = depth > 0,
                    state.sessionId
                  ).handleErrorWith(_ => IO.unit)
                )
                _ <- compactionPending
                  .flatMap(_.replyDeferred)
                  .fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit))
              yield ()
              compactionPending.flatMap(_.replyTo) match
                case Some(replyTo) =>
                  baseIO *> finishTurn(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    failedState,
                    Some(replyTo),
                    s"Context compaction failed: $err. Please start a new session or use /clear to reset.",
                    None,
                    None,
                    textAlreadyStreamed = false,
                    None
                  )
                case None =>
                  if compactionPending.exists(!_.resumeAfterCompact) then
                    baseIO *> IO.pure(idle(agentDef, resources, depth, parentRef, failedState))
                  else baseIO *> IO.pure(processing(agentDef, resources, depth, parentRef, failedState, pending))
              end match
          end match
        end if

      // --- Background task completed while processing ---
      case n: AgentCommand.BackgroundTaskNotification =>
        (ctx.self ! n.toExternalEvent) *> IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- External event while processing ---
      case AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId) =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "external-event-queued",
          s"source=$source type=$eventType"
        )
        val event = AgentCommand.ExternalEvent(source, eventType, payload, metadata, correlationId)
        val updatedExec = state.execution.copy(pendingEvents = state.execution.pendingEvents :+ event)
        // 任务 Q: emit the visible bubble at receive time (the combined
        // <system-reminder> injected later at finishTurn is for the LLM).
        val visSource = visibleExternalEventSource(source, eventType)
        val bubbleIO = visSource match
          case Some(s) => emitInjectedUserEvent(state.wsSend, state.sessionId, payload, s)
          case None => IO.unit
        bubbleIO *>
          emitStream(
            state.wsSend,
            AgentStreamEvent.ExternalEventReceived(source, eventType, correlationId),
            isSubagent = depth > 0,
            state.sessionId
          ) *> IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))

      // --- AskUser from tool ---
      case AgentCommand.AskUser(requestId, items, replyToOpt) =>
        // P2: every agent (root or sub-agent) sends the question straight to
        // the InteractionHub — no ForwardAskUser relay chain. The hub holds
        // replyTo, renders the question in the Nebula window (sessionId =
        // rootSessionId + source attribution) and routes the answers back.
        val srcAgent = agentDef.name
        val srcSession = state.sessionId.getOrElse("")
        val rootSid =
          Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
        val payload = buildAskUserJson(Some(rootSid), srcAgent, items, Some(srcAgent), Some(srcSession))
        val sendIO = resources.interactionHubRef.get.flatMap {
          case Some(hub) =>
            (hub ! InteractionHubCommand.Request(InteractionRequest(
              requestId = requestId,
              kind = InteractionKind.AskUser,
              payload = payload,
              reply = InteractionReply.AskUserReply(replyToOpt),
              rootSessionId = rootSid,
              sourceAgent = srcAgent,
              sourceSession = srcSession
            ))).void
          case None =>
            // Hub not spawned (early boot / tests): cancel the ask so the
            // caller's AskUserQuestionTool `.?` does not hang forever.
            logger.warn(
              s"AskUser dropped: InteractionHub not spawned (requestId=$requestId sourceAgent=$srcAgent)"
            ) *>
              replyToOpt.fold(IO.unit)(replyTo => (replyTo ! Nil))
        }
        sendIO *> IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

      // --- Set permission deferred while processing (P1 fallback, hub absent) ---
      case AgentCommand.SetPermissionDeferred(deferred) =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withPendingPermission(Some(deferred)), pending))

      // --- Bypass toggled while processing ---
      case AgentCommand.SetSafetyMode(mode) =>
        val policy = PermissionPolicy(safetyMode = mode)
        resources.permissionPolicies
          .update(_ + (state.session.rootSessionId -> policy)) *>
          IO.pure(
            processing(
              agentDef,
              resources,
              depth,
              parentRef,
              state.withSafetyMode(nebflow.core.SafetyMode.toString(mode)),
              pending
            )
          )

      // --- Session model switched ---
      case AgentCommand.UpdateContextWindow(window) =>
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withContextWindow(window),
            pending
          )
        )

      // --- Buffer user-initiated messages during processing ---
      case msg: AgentCommand.UserInput =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AgentCommand.SkillActivate =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AgentCommand.AskQuestion =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending :+ msg))
      case msg: AgentCommand.ImmediateInput =>
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "immediate-input-queued",
          s"textLen=${msg.text.length}"
        )
        val updatedExec = state.execution.copy(
          pendingImmediateInputs = state.execution.pendingImmediateInputs :+ msg
        )
        IO.pure(processing(agentDef, resources, depth, parentRef, state.copy(execution = updatedExec), pending))

      // --- Session management (persistent sub-agents) ---
      case AgentCommand.SessionStarted(address, agentName, taskDescription) =>
        val session = AgentSessionInfo(address, agentName, taskDescription, "running")
        IO.pure(
          processing(
            agentDef,
            resources,
            depth,
            parentRef,
            state.withAgentSessions(state.agentSessions :+ session),
            pending
          )
        )

      case AgentCommand.SessionUpdate(address, status) =>
        val updated = state.agentSessions.map(s => if s.address == address then s.copy(status = status) else s)
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withAgentSessions(updated), pending))

      case AgentCommand.SessionClosed(address) =>
        val updated = state.agentSessions.filterNot(_.address == address)
        IO.pure(processing(agentDef, resources, depth, parentRef, state.withAgentSessions(updated), pending))

      case _ =>
        IO.pure(processing(agentDef, resources, depth, parentRef, state, pending))

    // Supervision: on unhandled error, return to idle with safe state
    new Behavior[AgentCommand]:
      def receive(c: ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
        base.receive(c, msg)
      override def onError(c: ActorContext[AgentCommand], err: Throwable): IO[Behavior[AgentCommand]] =
        for
          _ <- c.log.error(s"Agent error in processing, returning to idle: ${err.getMessage}")
          _ <- emitStream(state.wsSend, AgentStreamEvent.Interrupted, isSubagent = depth > 0, state.sessionId)
          _ <- markTeamIdle(agentDef, state.sessionId)
        yield idle(agentDef, resources, depth, parentRef, state.withStatus(AgentStatus.Idle).withInteraction(None))
  end processing
  // ============================================================
  // Mail check — flow agents must call Mail before finishing
  // ============================================================

  /** Check if the conversation contains any SUCCESSFUL Mail tool call. */
  private def hasUsedMail(messages: List[Message]): Boolean =
    val mailToolUseIds = messages
      .flatMap(_.content match
        case Right(blocks) =>
          blocks.collect {
            case ContentBlock.ToolUse(id, name, _) if name == "Mail" => id
          }
        case _ => Nil)
      .toSet
    if mailToolUseIds.isEmpty then false
    else
      // At least one Mail result must NOT be an error
      messages.exists(_.content match
        case Right(blocks) =>
          blocks.exists {
            case tr: ContentBlock.ToolResult if mailToolUseIds.contains(tr.toolUseId) =>
              !tr.isError.getOrElse(false)
            case _ => false
          }
        case _ => false)

  end hasUsedMail

  /** Inject a system reminder telling the agent to use Mail, then trigger a new turn. */
  private def handleMissingMail(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "mail-reminder", "agent finished without Mail")
    val assistantContent = (result.thinking, result.text) match
      case (None, _) => Left(result.text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, result.thinkingSignature)))
      case (Some(t), txt) =>
        Right(List(ContentBlock.Thinking(t, result.thinkingSignature), ContentBlock.Text(txt)))
    val assistantMsg = Message(MessageRole.Assistant, assistantContent)
    val reminderMsg = Message(
      MessageRole.User,
      Left(
        "<system-reminder>\nYou must use the Mail tool to report your result before finishing. Call Mail now with your findings.\n</system-reminder>"
      )
    )
    val newMessages = state.messages ++ List(assistantMsg, reminderMsg)
    val updatedState =
      state.copy(execution = state.execution.copy(messages = newMessages, status = AgentStatus.Processing))
    pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, replyTo)
  end handleMissingMail

  // ============================================================
  // Supervisor restart helpers
  // ============================================================

  /**
   * Rollback the last tool call: truncate messages from the last ToolUse
   *  Assistant message onwards, inject a supervisor error message.
   *  If no tool call is found, returns state unchanged.
   */
  private def rollbackLastToolCall(state: AgentState): AgentState =
    val messages = state.messages
    // Find the index of the last Assistant message containing ToolUse blocks
    val lastToolUseIdx = messages.lastIndexWhere { msg =>
      msg.role == MessageRole.Assistant && {
        msg.content match
          case Right(blocks) => blocks.exists(_.isInstanceOf[ContentBlock.ToolUse])
          case _ => false
      }
    }
    if lastToolUseIdx < 0 then state // No tool call found — nothing to rollback
    else
      // Extract the tool name for the error message
      val toolName = messages(lastToolUseIdx).content match
        case Right(blocks) =>
          blocks.collectFirst { case ContentBlock.ToolUse(_, name, _) => name }.getOrElse("unknown")
        case _ => "unknown"
      // Truncate from the tool call onwards
      val truncated = messages.take(lastToolUseIdx)
      // Inject supervisor message
      val supervisorMsg = Message(
        MessageRole.User,
        Left(
          s"[SUPERVISOR] Your last action ($toolName) was rolled back because it caused a problem. " +
            "Do not repeat the same approach. Try a different strategy."
        )
      )
      state.withMessages(truncated :+ supervisorMsg)
    end if
  end rollbackLastToolCall

  // ============================================================
  // LlmComplete branch selector
  // ============================================================

  private def handleLlmCompleteBranch(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult,
    pending: List[AgentCommand]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    // Two-stage compaction:
    //  - Save turn, agent stopped calling tools → save phase complete → compact turn
    //  - Save turn, agent still calling tools → continue the tool loop
    // Completion signal = system-native turn complete (toolCalls.isEmpty), zero text parsing.
    if state.pendingCompaction.exists(_.phase == CompactionPhase.Save) && result.toolCalls.isEmpty then
      handleSavePhaseComplete(agentDef, resources, depth, parentRef, state, replyTo, pending)
    else if state.pendingCompaction.exists(_.phase == CompactionPhase.Save) && result.toolCalls.nonEmpty then
      pipeToolExecutions(agentDef, resources, depth, parentRef, state.withEmptyResponseRetries(0), result, replyTo)
    else if state.pendingCompaction.isDefined && result.toolCalls.isEmpty && result.text.nonEmpty then
      handleCompactResponse(agentDef, resources, depth, parentRef, state, result.text)
    else if state.pendingCompaction.isDefined && result.toolCalls.nonEmpty then
      handleCompactFailure(agentDef, resources, depth, parentRef, state, "Compact model unexpectedly called tools")
    else if state.askMode.isDefined && result.toolCalls.isEmpty then
      handleAskComplete(agentDef, resources, depth, parentRef, state, result.text, result.model)
    else if result.toolCalls.nonEmpty then
      pipeToolExecutions(agentDef, resources, depth, parentRef, state.withEmptyResponseRetries(0), result, replyTo)
    else if result.text.nonEmpty || result.thinking.nonEmpty then
      // Mail check: flow agents must call Mail before finishing
      if state.expectsMail && !state.mailUsedThisTurn then
        handleMissingMail(agentDef, resources, depth, parentRef, state, replyTo, result)
      else
        finishTurn(
          agentDef,
          resources,
          depth,
          parentRef,
          state.withEmptyResponseRetries(0),
          replyTo,
          result.text,
          result.thinking,
          result.thinkingSignature,
          textAlreadyStreamed = true,
          result.model
        )
    else handleEmptyResponse(agentDef, resources, depth, parentRef, state, replyTo, result)

  // ============================================================
  // Finish turn
  // ============================================================

  private def finishTurn(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    text: String,
    thinking: Option[String] = None,
    thinkingSignature: Option[String] = None,
    textAlreadyStreamed: Boolean = false,
    model: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val isSubagent = parentRef.isDefined
    val sendText = !textAlreadyStreamed && text.nonEmpty
    val assistantContent = (thinking, text) match
      case (None, _) => Left(text)
      case (Some(t), "") => Right(List(ContentBlock.Thinking(t, thinkingSignature)))
      case (Some(t), txt) =>
        Right(List(ContentBlock.Thinking(t, thinkingSignature), ContentBlock.Text(txt)))
    val newMessages = state.messages :+ Message(MessageRole.Assistant, assistantContent)
    val sendTextIO =
      if sendText then
        emitStream(state.wsSend, AgentStreamEvent.TextDelta(text), isSubagent = isSubagent, state.sessionId)
      else IO.unit
    for
      _ <- sendTextIO
      result <- finishTurnCont(
        agentDef,
        resources,
        depth,
        parentRef,
        state,
        replyTo,
        newMessages,
        text,
        model,
        thinking,
        thinkingSignature,
        sendText,
        isSubagent
      )
    yield result

    end for

  end finishTurn

  private def finishTurnCont(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    newMessages: List[Message],
    text: String,
    model: Option[String],
    thinking: Option[String],
    thinkingSignature: Option[String],
    textAlreadyStreamed: Boolean,
    isSubagent: Boolean
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "turn-complete",
      s"msgs=${state.messages.size} textLen=${text.length} textStreamed=$textAlreadyStreamed " +
        s"thinking=${thinking.map(_.length).getOrElse(0)} model=${model.getOrElse("-")}"
    )
    val queuedEvents = state.execution.pendingEvents
    if queuedEvents.nonEmpty then
      val roundCompleteIO: IO[Unit] =
        if !isSubagent then
          state.sessionId.fold(IO.unit)(sid =>
            ctx.forkTurn(
              state
                .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                .handleErrorWith(e =>
                  IO(NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}"))
                )
            )
          )
        else IO.unit
      // Serial processing — consume ONE queued event per turn (chatQueue
      // semantics), keep the rest queued so they're handled one at a time.
      val headEvent = queuedEvents.head
      val remainingEvents = queuedEvents.tail
      val eventMessages = List(
        Message(MessageRole.User, Left(s"<system-reminder>\n${headEvent.payload}\n</system-reminder>"))
      )
      val messagesWithPending = newMessages ++ eventMessages
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "pending-messages-injected",
        s"events=1 remaining=${remainingEvents.size}"
      )
      val updatedState = state.copy(execution =
        ExecutionContext
          .idle(messagesWithPending, state.execution.turnIdx)
          .copy(pendingEvents = remainingEvents, pendingImmediateInputs = state.execution.pendingImmediateInputs)
      )
      for
        _ <- roundCompleteIO
        _ <- if !isSubagent then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true)) else IO.unit
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, messagesWithPending) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        )
        _ <- replyTo.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithPending))
        result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
      yield result
    else if state.pendingCompaction.isEmpty && state.execution.pendingImmediateInputs.nonEmpty then
      // Inject ONE queued immediate input (serial processing).
      val immInput = state.execution.pendingImmediateInputs.head
      val remainingInputs = state.execution.pendingImmediateInputs.tail
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "immediate-input-injected-at-turn-end",
        s"text=${immInput.text.take(60)} remaining=${remainingInputs.size}"
      )
      val roundCompleteIO: IO[Unit] =
        if !isSubagent then
          state.sessionId.fold(IO.unit)(sid =>
            ctx.forkTurn(
              state
                .wsSend(Json.obj("type" -> "roundComplete".asJson, "sessionId" -> sid.asJson))
                .handleErrorWith(e =>
                  IO(NebflowLogger.forName("nebflow.agent").warn(s"roundComplete delivery failed: ${e.getMessage}"))
                )
            )
          )
        else IO.unit
      val immMessage = (immInput.blocks match
        case Some(blocks) if blocks.nonEmpty => Message(MessageRole.User, Right(blocks))
        case _ => Message(MessageRole.User, Left(immInput.text))
      ).copy(source = immInput.source)
      val messagesWithImmediate = newMessages ++ List(immMessage)
      val updatedState = state
        .copy(execution =
          ExecutionContext
            .idle(messagesWithImmediate, state.execution.turnIdx)
            .copy(pendingImmediateInputs = remainingInputs)
        )
      for
        _ <- roundCompleteIO
        _ <- if !isSubagent then state.sessionId.fold(IO.unit)(sid => emitSessionBusy(state.wsSend, sid, busy = true)) else IO.unit
        _ <- immInput.source match
          case Some(src) => emitInjectedUserEvent(state.wsSend, state.sessionId, immInput.text, src)
          case None => IO.unit
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, messagesWithImmediate) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        )
        _ <- replyTo.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), messagesWithImmediate))
        result <- pipeLlmCall(agentDef, resources, depth, parentRef, updatedState, None)
      yield result
    else
      val effectiveInputTokens = state.latestUsage
        .map(_.inputTokens)
        .filter(_ > 0)
        .getOrElse(nebflow.core.compact.TokenEstimator.estimate(state.messages))
      val doneEvent = AgentStreamEvent.Done(
        model.orElse(state.lastModel),
        contextWindow = Some(state.contextWindow),
        inputTokens = Some(effectiveInputTokens),
        compactThreshold = Some(CompactThreshold.thresholdRatio(state.contextWindow))
      )
      val emitDoneIO =
        if isSubagent then
          // Synchronous emit to prevent markIdle/markBusy race condition
          emitStreamIO(state.wsSend, doneEvent, isSubagent = true, state.sessionId)
            .handleErrorWith(e =>
              IO(
                NebflowLogger.forName("nebflow.agent").warn(s"finishTurn: emitDone (sync) failed: ${e.getMessage}")
              ).void
            )
        else
          state.sessionId.fold(IO.unit) { sid =>
            val doneJson = doneEvent.toJson(ctx.self.path.name, false, state.sessionId)
            NebflowLogger
              .forName("nebflow.agent")
              .info(s"finishTurn: sending Done sessionId=$sid jsonLen=${doneJson.noSpaces.length}")
            ctx.forkTurn(
              (state
                .wsSend(doneJson)
                .handleErrorWith(e =>
                  IO(
                    NebflowLogger
                      .forName("nebflow.agent")
                      .warn(s"finishTurn: Done event delivery failed: ${e.getMessage}")
                  )
                ) *>
                emitSessionBusy(state.wsSend, sid, busy = false))
                .handleErrorWith(e =>
                  IO(
                    NebflowLogger
                      .forName("nebflow.agent")
                      .warn(s"finishTurn: Done+sessionBusy chain failed: ${e.getMessage}")
                  )
                )
            )
          }
      for
        _ <- emitDoneIO
        _ <- state.sessionId.fold(IO.unit)(sid =>
          ctx.forkTurn(
            (resources.sessionStore.saveMessagesForSession(sid, newMessages) *>
              resources.sessionStore.flushIndex)
              .handleErrorWith(e =>
                IO(NebflowLogger.forName("nebflow.agent").warn(s"Save/flush session failed: ${e.getMessage}"))
              )
          )
        )
        _ <- replyTo.traverse_(_ ! AgentEvent.Completed(state.sessionId.getOrElse(""), newMessages))
        // Turn fully finished (no pending events / immediate inputs) — back to
        // idle, so clear the team busy mark. The two earlier branches re-enter
        // pipeLlmCall which re-marks busy, so they must NOT clear here.
        _ <- markTeamIdle(agentDef, state.sessionId)
      yield
        val keptInteraction = state.execution.interaction.filter(_.pendingPermission.isDefined)
        val updatedState = state
          .copy(execution =
            ExecutionContext.idle(newMessages, state.execution.turnIdx)
              .copy(
                interaction = keptInteraction,
                // Preserve queue when compaction is in progress — CompactionComplete drains it.
                pendingImmediateInputs = state.execution.pendingImmediateInputs
              )
          )
          .withMailTurnCount(state.mailTurnCount + 1)
        idle(agentDef, resources, depth, parentRef, updatedState)
      end for
    end if
  end finishTurnCont

  // ============================================================
  // Pipe wrappers
  // ============================================================

  /**
   * Team busy signal for the /api/teams/mounted status fallback (busyMap).
   * The frontend prefers realtime agentStart/agentDone events, but on page
   * load / event loss it falls back to the mounted status field — which stays
   * idle forever unless something writes the busyMap. Only team agents
   * (category inferred from `teams/` path by loadAgentFromDir) are shown as
   * team tiles, so only they need marking.
   *
   * Errors are swallowed: a failed status record must never break the turn.
   */
  private def markTeamBusy(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    if agentDef.category == "team" then
      sid.fold(IO.unit)(s => TeamSessionRegistry.markBusy(s).handleErrorWith(_ => IO.unit))
    else IO.unit

  private def markTeamIdle(agentDef: AgentDef, sid: Option[String]): IO[Unit] =
    if agentDef.category == "team" then
      sid.fold(IO.unit)(s => TeamSessionRegistry.markIdle(s).handleErrorWith(_ => IO.unit))
    else IO.unit

  private def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    // Turn start: mark team agent busy. Idempotent (Set), so the recursive
    // multi-tool-call turns that re-enter pipeLlmCall are harmless.
    markTeamBusy(agentDef, state.sessionId) *>
      super.pipeLlmCall(
        agentDef,
        resources,
        depth,
        parentRef,
        state,
        replyTo,
        (ad, r, d, p, s) => processing(ad, r, d, p, s)
      )

  private def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    super.pipeToolExecutions(
      agentDef,
      resources,
      depth,
      parentRef,
      state,
      result,
      replyTo,
      (ad, r, d, p, s) => processing(ad, r, d, p, s)
    )

  // ============================================================
  // Save phase (stage 1) completion — transitions to compact turn
  // ============================================================

  /**
   * Save turn ended naturally (agent stopped calling tools, toolCalls.isEmpty).
   * Switch the pending compaction to Compact phase, inject the compact
   * reminder, and run the existing single-turn summary compaction.
   */
  private def handleSavePhaseComplete(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    pending: List[AgentCommand]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "save-phase-complete")
    val compactState = state
      .withPendingCompaction(state.pendingCompaction.map(_.copy(phase = CompactionPhase.Compact)))
      .withMessages(state.messages :+ CompactService.buildCompactReminder(depth))
    pipeLlmCall(agentDef, resources, depth, parentRef, compactState, replyTo)

  // ============================================================
  // Compact response handlers
  // ============================================================

  private def handleCompactResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    responseText: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val sessionId = state.sessionId.getOrElse(ctx.self.path.name)
    val readPathsIO = state.readTracker
      .map(_.recentFiles(CompactConfig().postCompactMaxFiles).map(_.map(_.toString)))
      .getOrElse(IO.pure(Nil))
    val hookIO = CompactService.runPreCompactHook(state.messages, resources, sessionId)
    val rootIO: IO[String] = state.folderId match
      case Some(fid) =>
        resources.sessionStore.resolveProjectRoot(Some(fid)).map(_.getOrElse(resources.projectRoot.toString))
      case None => IO.pure(resources.projectRoot.toString)
    for _ <- ctx.forkTurn(
        (readPathsIO, hookIO, rootIO)
          .mapN { (readPaths, hookResult, effectiveRoot) =>
            (readPaths, hookResult, effectiveRoot)
          }
          .flatMap { (readPaths, hookResult, effectiveRoot) =>
            hookResult match
              case Left(reason) =>
                ctx.self ! AgentCommand.CompactionComplete(Left(reason))
              case Right(_) =>
                FullCompact.parseResponse(responseText, state.messages, effectiveRoot, readPaths) match
                  case Left(err) =>
                    ctx.self ! AgentCommand.CompactionComplete(Left(err))
                  case Right(compactedMessages) =>
                    val postHookIO = CompactService
                      .runPostCompactHook(state.messages.size, compactedMessages.size, resources, sessionId)
                      .handleErrorWith(_ => IO.unit)
                    ctx
                      .forkTurn(postHookIO)
                      .flatMap(_ => ctx.self ! AgentCommand.CompactionComplete(Right(compactedMessages)))
          }
          .handleErrorWith(e => ctx.self ! AgentCommand.CompactionComplete(Left(e.getMessage)))
      )
    yield processing(agentDef, resources, depth, parentRef, state)

  end handleCompactResponse

  private def handleCompactFailure(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    err: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val pending = state.pendingCompaction
    val now = System.currentTimeMillis()
    logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "compaction-failed", s"err=$err")
    val failedState = state
      .withPendingCompaction(None)
      .withCompactionFailures(state.compactionFailures + 1)
      .withLastCompactionFailureAt(now)
    for
      _ <- ctx.forkTurn(
        emitStreamIO(
          state.wsSend,
          AgentStreamEvent.CompactFailed(err, state.compactionFailures + 1, CompactConfig().circuitBreakerMax),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      _ <- pending
        .flatMap(_.replyDeferred)
        .fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit))
      result <- pending.flatMap(_.replyTo) match
        case Some(replyTo) =>
          finishTurn(
            agentDef,
            resources,
            depth,
            parentRef,
            failedState,
            Some(replyTo),
            s"Context compaction failed: $err.",
            None,
            None,
            textAlreadyStreamed = false,
            None
          )
        case None =>
          if pending.exists(!_.resumeAfterCompact) then
            IO.pure(idle(agentDef, resources, depth, parentRef, failedState))
          else IO.pure(processing(agentDef, resources, depth, parentRef, failedState))
    yield result
    end for
  end handleCompactFailure

  // ============================================================
  // Ask complete
  // ============================================================

  private def handleAskComplete(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    answerText: String,
    model: Option[String]
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val question = state.askMode.getOrElse("")
    val sessionId = state.sessionId.getOrElse(ctx.self.path.name)
    logAgentEvent(
      agentDef,
      depth,
      state.sessionId,
      state.sessionName,
      "ask-complete",
      s"q=${question.take(40)} a=${answerText.take(40)}"
    )
    for
      _ <- ctx.forkTurn(
        (state
          .wsSend(
            Json.obj(
              "type" -> "askDone".asJson,
              "sessionId" -> sessionId.asJson,
              "durationMs" -> 0L.asJson,
              "model" -> model.getOrElse("").asJson
            )
          )
          .handleErrorWith(_ => IO.unit)) *>
          emitSessionBusy(state.wsSend, sessionId, busy = false)
      )
      _ <- ctx.forkTurn(
        resources.sessionStore
          .appendUiMessages(sessionId, List(UiMessage.Ask(question, answerText, Some(0L), model)))
          .handleErrorWith(e => IO(logger.warn(s"Failed to persist ask UiMessage: ${e.getMessage}")))
      )
    yield
      val originalMessages = state.messages.takeWhile(m => !isAskReminder(m))
      val restoredMessages =
        if originalMessages.size == state.messages.size then state.messages.dropRight(1)
        else originalMessages
      idle(
        agentDef,
        resources,
        depth,
        parentRef,
        state.withAskMode(None).withMessages(restoredMessages).resetToIdle(restoredMessages)
      )

    end for

  end handleAskComplete

  private def isAskReminder(msg: Message): Boolean =
    msg.role == MessageRole.User && (msg.content match
      case Left(text) => text.contains("<system-reminder>") && text.contains("ephemeral follow-up question")
      case _ => false)

  // ============================================================
  // Trigger compaction
  // ============================================================

  private def handleTriggerCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    mode: String,
    replyDeferred: Option[cats.effect.Deferred[IO, Either[String, CompactionResult]]],
    resumeAfterCompact: Boolean = true,
    postCompactInstruction: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val config = CompactConfig()
    if state.pendingCompaction.isDefined then
      val reason = "Compaction already in progress"
      for
        _ <- replyDeferred.fold(IO.unit)(d => d.complete(Left(reason)).void.handleErrorWith(_ => IO.unit))
        _ <- ctx.forkTurn(
          emitStreamIO(
            state.wsSend,
            AgentStreamEvent.CompactFailed(reason, state.compactionFailures, config.circuitBreakerMax),
            isSubagent = depth > 0,
            state.sessionId
          ).handleErrorWith(_ => IO.unit)
        )
      yield idle(agentDef, resources, depth, parentRef, state)
    else
      val backoffOk =
        if state.compactionFailures == 0 then true
        else
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          config.isBackoffSatisfied(state.compactionFailures, elapsed)
      if !backoffOk then
        val err = s"Compaction retry backed off (${state.compactionFailures} failures)"
        replyDeferred.fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit)) *>
          IO.pure(idle(agentDef, resources, depth, parentRef, state))
      else if state.compactionFailures >= config.circuitBreakerMax then
        val err = s"Compaction circuit breaker open after ${state.compactionFailures} attempts"
        for
          _ <- replyDeferred.fold(IO.unit)(d => d.complete(Left(err)).void.handleErrorWith(_ => IO.unit))
          _ <- ctx.forkTurn(
            emitStreamIO(
              state.wsSend,
              AgentStreamEvent.CompactFailed(err, state.compactionFailures, config.circuitBreakerMax),
              isSubagent = depth > 0,
              state.sessionId
            ).handleErrorWith(_ => IO.unit)
          )
        yield idle(agentDef, resources, depth, parentRef, state)
      else
        startDirectCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          None,
          (ad, r, d, p, s) => processing(ad, r, d, p, s),
          mode,
          resumeAfterCompact,
          postCompactInstruction
        )
      end if
    end if
  end handleTriggerCompaction

  // ============================================================
  // Empty response handler
  // ============================================================

  private def handleEmptyResponse(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    result: ConsumeResult
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val stopReason = result.stopReason.getOrElse("")
    val isContextError = stopReason.toLowerCase.contains("context") ||
      stopReason.toLowerCase.contains("exceeded") || stopReason.toLowerCase.contains("limit") ||
      stopReason.toLowerCase.contains("too long") || stopReason.toLowerCase.contains("too_long")
    val isMaxTokens = stopReason.equalsIgnoreCase("max_tokens") ||
      stopReason.equalsIgnoreCase("length")
    if isContextError then
      logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "context-exceeded", s"stopReason=$stopReason")
      if state.pendingCompaction.isDefined || state.compactionFailures >= CompactConfig().circuitBreakerMax then
        finishTurn(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          "Context window exceeded and compaction is unavailable. " +
            "Please start a new session or use /clear to reset.",
          None,
          None,
          textAlreadyStreamed = false,
          result.model
        )
      else
        startDirectCompaction(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          (ad, r, d, p, s) => processing(ad, r, d, p, s),
          "full"
        )
      end if
    else if isMaxTokens then
      logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "max-tokens", s"stopReason=$stopReason")
      for
        _ <- ctx.forkTurn(
          state
            .wsSend(Json.obj("type" -> "maxTokens".asJson, "sessionId" -> state.sessionId.asJson))
            .handleErrorWith(_ => IO.unit)
        )
        result <- finishTurn(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          "[Response truncated: max output tokens reached]",
          None,
          None,
          textAlreadyStreamed = false,
          result.model
        )
      yield result
      end for
    else
      val retryCount = state.emptyResponseRetries
      logAgentEvent(
        agentDef,
        depth,
        state.sessionId,
        state.sessionName,
        "empty-response",
        s"stopReason=$stopReason retry=$retryCount/$MaxEmptyResponseRetries " +
          s"usage=${result.usage.map(u => s"in=${u.inputTokens} out=${u.outputTokens}").getOrElse("none")}"
      )
      if retryCount < MaxEmptyResponseRetries then
        val stateForRetry = state.withEmptyResponseRetries(retryCount + 1)
        logger.info(s"Empty response, retrying (${retryCount + 1}/$MaxEmptyResponseRetries) stopReason=$stopReason")
        for
          _ <- ctx.forkTurn(
            emitStreamIO(
              state.wsSend,
              AgentStreamEvent.RetryStatus(
                s"Empty response from LLM, retrying (${retryCount + 1}/$MaxEmptyResponseRetries)..."
              ),
              isSubagent = depth > 0,
              state.sessionId
            ).handleErrorWith(_ => IO.unit)
          )
          result <- pipeLlmCall(agentDef, resources, depth, parentRef, stateForRetry, replyTo)
        yield result
      else
        val errMsg =
          if stopReason.nonEmpty then
            s"LLM returned empty response after $MaxEmptyResponseRetries retries (stopReason: $stopReason)"
          else s"LLM returned empty response with no content after $MaxEmptyResponseRetries retries"
        for _ <- state.sessionId.fold(IO.unit) { sid =>
            val doneEvent = AgentStreamEvent.Done(result.model)
            val doneJson = doneEvent.toJson(ctx.self.path.name, false, state.sessionId)
            ctx.forkTurn(
              (state
                .wsSend(
                  Json.obj("type" -> "error".asJson, "sessionId" -> state.sessionId.asJson, "message" -> errMsg.asJson)
                )
                .handleErrorWith(_ => IO.unit)) *>
                state.wsSend(doneJson).handleErrorWith(_ => IO.unit) *>
                emitSessionBusy(state.wsSend, sid, busy = false)
            )
          }
        yield idle(agentDef, resources, depth, parentRef, state)
      end if
    end if
  end handleEmptyResponse

  // ============================================================
  // Tool result replacement
  // ============================================================

  private def replaceToolResults(
    messages: List[Message],
    rounds: Int,
    summary: String
  ): Either[String, (List[Message], Int)] =
    val allToolUseIds = messages.flatMap {
      case Message(MessageRole.Assistant, Right(blocks), _, _) =>
        blocks.collect { case ContentBlock.ToolUse(id, _, _) => id }
      case _ => Nil
    }.reverse
    if allToolUseIds.isEmpty then Left("No tool call results found in conversation")
    else
      val selectedIds = allToolUseIds.take(rounds).toSet
      if selectedIds.isEmpty then Left("Selected rounds exceed available tool calls")
      else
        val (updated, count) = messages.foldLeft((Vector.empty[Message], 0)) {
          case ((acc, c), msg @ Message(MessageRole.User, Right(blocks), _, _)) =>
            val (newBlocks, nc) = blocks.foldLeft((Vector.empty[ContentBlock], c)) {
              case ((ba, bc), tr: ContentBlock.ToolResult) if selectedIds.contains(tr.toolUseId) =>
                (ba :+ tr.copy(content = s"[Replaced — see RemoveUnnecessary result above]"), bc + 1)
              case ((ba, bc), other) => (ba :+ other, bc)
            }
            (acc :+ msg.copy(content = Right(newBlocks.toList)), nc)
          case ((acc, c), other) => (acc :+ other, c)
        }
        if count == 0 then Left("No matching tool results found")
        else Right((updated.toList, count))
    end if
  end replaceToolResults

end AgentActor
