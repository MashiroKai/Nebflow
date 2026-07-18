package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.agent.PromptSections.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.tools.*
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

private[agent] trait AgentCore:

  protected val MaxDepth = 5

  /**
   * Tools removed from sub-agents (depth > 0): user-interaction tools that
   * don't make sense in an autonomous sub-agent context.
   */
  private val SubagentBlockedTools = Set("TaskCreate", "TaskUpdate", "TaskList", "AskUserQuestion")

  /** Internal tools excluded from List("*") wildcard expansion. Only injected explicitly by pipeline verify. */
  private val InternalTools = Set("FlowVerify")

  private val lifecycleLog = NebflowLogger.forName("nebflow.agent.lifecycle")

  private[agent] type ProcessingFn =
    (AgentDef, SharedResources, Int, Option[ActorRef[AgentCommand]], AgentState) => Behavior[AgentCommand]

  private def shortUuid(id: String): String =
    if id.startsWith("agent-") then id.drop(6).take(8)
    else if id == "-" || id == "system" then id
    else id.take(8)

  protected def logAgentEvent(
    agentDef: AgentDef,
    depth: Int,
    sessionId: Option[String],
    sessionName: Option[String],
    event: String,
    detail: String = ""
  )(using ctx: ActorContext[AgentCommand]): Unit =
    val sid = shortUuid(sessionId.getOrElse("-"))
    val sname = sessionName.getOrElse("-")
    val who = if depth == 0 then s"${agentDef.name}-${shortUuid(ctx.self.path.name)}" else s"subagent-${agentDef.name}"
    val logCtx = lifecycleLog.ctxPrefix(who, s"$sname/$sid")
    lifecycleLog.infoSync(if detail.nonEmpty then s"$logCtx event=$event detail=$detail" else s"$logCtx event=$event")

  protected def persistIfSession(resources: SharedResources, state: AgentState): IO[Unit] =
    state.sessionId match
      case Some(sid) => resources.sessionStore.saveMessagesForSession(sid, state.messages)
      case None => IO.unit

  protected def maybeAutoCompact(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): Option[IO[Behavior[AgentCommand]]] =
    if state.pendingCompaction.isDefined then None
    else
      val config = CompactConfig()
      val backoffOk =
        if state.compactionFailures == 0 then true
        else
          val elapsed = System.currentTimeMillis() - state.lastCompactionFailureAt
          val ok = config.isBackoffSatisfied(state.compactionFailures, elapsed)
          if !ok then
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "auto-compact-skipped",
              s"backoff=${config.backoffMs(state.compactionFailures)}ms elapsed=${elapsed}ms failures=${state.compactionFailures}"
            )
          ok
      if !backoffOk then None
      else if state.compactionFailures >= config.circuitBreakerMax then
        logAgentEvent(
          agentDef,
          depth,
          state.sessionId,
          state.sessionName,
          "auto-compact-skipped",
          s"circuitBreakerOpen failures=${state.compactionFailures} max=${config.circuitBreakerMax}"
        )
        None
      else
        val inputTokensOpt = state.latestUsage.map(_.inputTokens)
        val threshold = state.contextWindow - config.bufferForWindow(state.contextWindow)
        val shouldCompact = inputTokensOpt match
          case Some(inputTokens) if inputTokens > 0 && inputTokens > threshold =>
            Some(s"inputTokens=$inputTokens threshold=$threshold")
          case _ =>
            val estimated = TokenEstimator.estimate(state.messages)
            if estimated > threshold then
              Some(s"estimated=$estimated threshold=$threshold (provider did not report inputTokens)")
            else None
        shouldCompact match
          case Some(detail) =>
            logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "auto-compact-trigger", detail)
            Some(startDirectCompaction(agentDef, resources, depth, parentRef, state, replyTo, processing, "full"))
          case None => None

      end if

  protected def startDirectCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn,
    mode: String
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val jobId = s"compact-${java.util.UUID.randomUUID().toString.take(8)}"
    val pending = CompactionJob(jobId, mode, None, replyTo)
    val compactState = state
      .withPendingCompaction(Some(pending))
      .withMessages(state.messages :+ CompactService.buildCompactReminder())
    for
      _ <- ctx.forkTurn(
        emitStreamIO(
          state.wsSend,
          AgentStreamEvent.CompactStart(
            mode,
            state.latestUsage.map(_.inputTokens),
            Some(state.contextWindow - CompactConfig().bufferForWindow(state.contextWindow))
          ),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      result <- pipeLlmCall(agentDef, resources, depth, parentRef, compactState, replyTo, processing)
    yield result

    end for

  end startDirectCompaction

  protected def pipeLlmCall(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    maybeAutoCompact(agentDef, resources, depth, parentRef, state, replyTo, processing) match
      case Some(ioBehavior) => ioBehavior
      case None =>
        val isCompactTurn = state.pendingCompaction.isDefined
        val isAskTurn = state.askMode.isDefined
        val tools = if isCompactTurn then Some(Nil) else buildToolList(agentDef, depth)
        val isSubagent = depth > 0
        val sessionIdOpt = state.sessionId
        val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
          val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
          state.wsSend(AgentStreamEvent.RetryStatus(msg).toJson(ctx.self.path.name, isSubagent, sessionIdOpt))
        val turnId = state.currentTurnId + 1
        val microResult = if isCompactTurn || isAskTurn then None else FastMicroCompact(state.messages)
        val stateForLlm = microResult match
          case Some(compacted) =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "fast-micro-compact",
              s"before=${state.messages.size} after=${compacted.size}"
            )
            state.copy(execution = state.execution.copy(messages = compacted)).withCurrentTurnId(turnId)
          case None => state.withCurrentTurnId(turnId)

        val llmIo = for
          turnCtx <- ContextRefresher.refreshTurn(stateForLlm, resources, agentDef)
          freshDef = turnCtx.agentDef
          voiceEnabled <- resources.voiceMutedRef.get.map(!_)
          allowedTools = buildAllowedToolSet(freshDef, depth)
          devInfo = deviceInfoBlock
          sessionsText = formatAgentSessions(stateForLlm.agentSessions)
          promptCtx = PromptContext(
            availableTools = allowedTools,
            depth = depth,
            voiceEnabled = voiceEnabled,
            hasDevices = devInfo.nonEmpty,
            deviceInfo = devInfo,
            hasActiveSessions = sessionsText.nonEmpty,
            agentSessionsText = sessionsText,
            language = stateForLlm.language,
            chatWidth = state.session.chatWidth,
            envInfo = Repl.buildEnvInfo(state.session.chatWidth),
            skillCatalog = turnCtx.skillCatalog,
            rulesMd = turnCtx.rulesMd
          )
          systemStable = buildSystemPrompt(freshDef, turnCtx.systemPrefix, promptCtx)
          isUserTurn = stateForLlm.messages.lastOption.exists(m => m.role == MessageRole.User && m.content.isLeft)
          reminders = SystemReminders.collectAll(isUserTurn)
          _ <- turnCtx.branchChange match
            case Some(_) =>
              ctx.self ! AgentCommand.UpdateGitBranch(turnCtx.currentBranch)
              // Persist git branch change so it survives restarts
              stateForLlm.sessionId.traverse_(sid =>
                resources.sessionStore.updateGitBranch(sid, turnCtx.currentBranch)
                  .handleErrorWith(e =>
                    IO(NebflowLogger.forName("nebflow.agent").warn(s"Failed to persist gitBranch: ${e.getMessage}")))
              )
            case None =>
              IO.whenA(turnCtx.currentBranch != stateForLlm.gitBranch)(
                ctx.self ! AgentCommand.UpdateGitBranch(turnCtx.currentBranch)
              )
          loggedReminders <- SystemReminders.logAndReturn(reminders)
          allReminders = loggedReminders ++ turnCtx.branchChange.toList
          remindersText = SystemReminder.renderAll(allReminders)
          dynamicMsg =
            if isCompactTurn || isAskTurn then Nil
            else if remindersText.nonEmpty then List(Message(MessageRole.User, Left(remindersText)))
            else Nil
          modelDescs <- if isCompactTurn then IO.pure(Nil) else resources.providerRegistry.getAllModelsDetailed()
          freshTools =
            if isCompactTurn then Some(Nil) else enrichDelegateTools(buildToolList(freshDef, depth), modelDescs)
          // Memory auto-read: inject synthetic Read for memory files
          memoryMsgs = MemoryAutoRead.buildMessages(freshDef.name, stateForLlm.folderId, stateForLlm.sessionId)
          _ <- stateForLlm.liveFileTracker
            .traverse_(t => MemoryAutoRead.register(t, freshDef.name, stateForLlm.folderId, stateForLlm.sessionId))
          // Live file patching: update tool_result content for changed live files
          baseMessages = memoryMsgs ++ stateForLlm.messages
          patchedMessages <- stateForLlm.liveFileTracker
            .fold(IO.pure(baseMessages))(_.patchMessages(baseMessages))
          request = LlmRequest(
            messages = patchedMessages ++ dynamicMsg,
            sessionId = stateForLlm.sessionId.getOrElse(ctx.self.path.name),
            agentId = freshDef.name,
            tools = freshTools,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = Some(nebflow.llm.ThinkingConfig.toLlmJson(turnCtx.thinkingConfig)),
            systemStable = Some(systemStable)
          )
          result <- resources.llm
            .sendStream(request, onAttempt = Some(onAttemptCb))
            .through(streamEmitter(stateForLlm.wsSend, isSubagent, sessionIdOpt, isAskTurn, isCompactTurn))
            .compile
            .toList
            .flatMap { chunks =>
              val cr = aggregateChunks(chunks)
              LlmLogWriter.log(
                request,
                chunks,
                cr.text,
                cr.toolCalls,
                cr.thinking,
                cr.stopReason,
                cr.usage,
                cr.model,
                isSubagent,
                isCompactTurn
              ) *> IO.pure(cr)
            }
            .attempt
          _ <- result match
            case Right(r) => ctx.self ! LlmComplete(r, replyTo, turnId)
            case Left(e) => ctx.self ! LlmFailed(e, replyTo, turnId)
        yield ()

        val agentStartIO =
          if depth > 0 && !isCompactTurn && !isAskTurn then
            emitStream(
              state.wsSend,
              AgentStreamEvent.AgentStart(agentDef.name, agentDef.description, state.sessionName),
              isSubagent = true,
              state.sessionId
            )
          else IO.unit
        for
          _ <- agentStartIO
          _ <- ctx.forkTurn(llmIo.handleErrorWith { e =>
            IO.delay(NebflowLogger.forName("nebflow.agent").warn(s"pipeLlmCall failed: ${e.getMessage}"))
              .flatMap(_ => ctx.self ! LlmFailed(e, replyTo, turnId))
          })
        yield processing(
          agentDef,
          resources,
          depth,
          parentRef,
          stateForLlm.withLastDispatch(Some(LastDispatch(isToolExecution = false)))
        )

  protected def pipeToolExecutions(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    result: ConsumeResult,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    val allowedTools = buildAllowedToolSet(agentDef, depth)
    val (filteredCalls, droppedCalls) = result.toolCalls.partition(tc => allowedTools.contains(tc.name))
    if droppedCalls.nonEmpty then
      NebflowLogger
        .forName("nebflow.agent")
        .warn(s"Tool calls filtered (not in allowed set): ${droppedCalls.map(_.name).distinct.mkString(", ")}")
    val nextTurnIdx = state.turnIdx + 1
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)
    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    val io = for
      freshProjectRoot <- ContextRefresher.resolveProjectRootForTool(state, resources, agentDef)
      effectiveProjectRoot = freshProjectRoot.getOrElse(resources.projectRoot.toString)
      toolCtx = ToolContext(
        projectRoot = effectiveProjectRoot,
        llm = Some(resources.llm),
        sessionStore = Some(resources.sessionStore),
        agentActorRef = Some(ctx.self),
        contextWindow = state.contextWindow,
        sessionId = state.sessionId,
        sessionName = state.sessionName,
        taskStore = Some(resources.taskStore),
        wsSend = Some(state.wsSend),
        readTracker = state.readTracker,
        fileHistory = state.fileHistory,
        parentRef = parentRef,
        depth = depth,
        agentDef = Some(agentDef),
        agentLibrary = Some(resources.agentLibrary),
        askSemaphore = Some(resources.askSemaphore),
        fileLockManager = Some(resources.fileLockManager),
        fileChangeTracker = Some(resources.fileChangeTracker),
        hookEngine = resources.hookEngine,
        hookContext =
          HookContext(sessionId = state.sessionId, projectRoot = effectiveProjectRoot, cwd = effectiveProjectRoot),
        folderId = state.folderId,
        mailboxAddress = state.session.sessionId,
        sharedResources = Some(resources),
        actorSystem = Some(ctx.system),
        messages = state.messages,
        liveFileTracker = state.liveFileTracker
      )
      freshResults <- filteredCalls.parTraverse { call =>
        val skipStreaming = call.name == "AskUserQuestion"
        val callCtx = toolCtx.copy(toolCallId = call.id)
        (if !skipStreaming then
           emitStreamIO(
             state.wsSend,
             AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(call)),
             isSubagent,
             sessionIdOpt
           )
         else IO.unit) *>
          (if ToolReversibility.isReversible(call.name, call.input, nebflow.core.SafetyMode.fromString(state.safetyMode)) then executeTool(call, callCtx)
           else askUserPermission(call, state, permissionDeferredRef, callCtx))
            .map(r => (call, r))
            .attempt
            .map {
              case Right(pair) => pair
              case Left(e) => (call, ToolExecResult(s"Tool error: ${e.getMessage}", isError = true))
            }
            .flatTap { (call, r) =>
              if call.name != "AskUserQuestion" then
                val summary = summarizeToolResult(call, r.content)
                val frontendContent = r.frontendContent.getOrElse(r.content)
                emitStreamIO(
                  state.wsSend,
                  AgentStreamEvent.ToolEnd(
                    nebflow.core.summarizeToolCall(call),
                    summary,
                    frontendContent,
                    r.isError,
                    input = Some(call.input)
                  ),
                  isSubagent,
                  sessionIdOpt
                )
              else IO.unit
            }
            .flatMap { (call, r) =>
              ToolResultGuard.guardResult(call, r, state.sessionId.getOrElse("default")).map(r => (call, r))
            }
      }
      guardedBatch <- ToolResultGuard.guardBatch(freshResults, state.sessionId.getOrElse("default"))
      droppedResults = droppedCalls.map(call =>
        (call, ToolExecResult(s"Tool not available: ${call.name}", isError = true))
      )
      _ <- ctx.self ! ToolsComplete(
        guardedBatch ++ droppedResults,
        result.text,
        replyTo,
        None,
        result.thinking,
        result.thinkingSignature
      )
    yield ()

    for _ <- ctx.forkTurn(io.handleErrorWith { e =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        NebflowLogger.forName("nebflow.agent").warn(s"pipeToolExecutions failed (this should be rare): $msg")
        ctx.self ! LlmFailed(
          ToolPipelineError(s"Tool execution pipeline failed: $msg"),
          replyTo,
          state.currentTurnId
        )
      })
    yield
      val updatedState = state
        .copy(execution = state.execution.copy(turnIdx = nextTurnIdx))
        .withLastDispatch(Some(LastDispatch(isToolExecution = true, Some(result))))
      processing(agentDef, resources, depth, parentRef, updatedState)

  end pipeToolExecutions

  private def askUserPermission(
    call: ToolCall,
    state: AgentState,
    permissionDeferredRef: Ref[IO, Option[cats.effect.Deferred[IO, Boolean]]],
    toolCtx: ToolContext
  )(using ctx: ActorContext[AgentCommand]): IO[ToolExecResult] =
    permissionDeferredRef.modify {
      case existing @ Some(_) =>
        (existing, IO.pure(ToolExecResult("Another permission request is already pending", isError = true)))
      case None =>
        val deferred = cats.effect.Deferred.unsafe[IO, Boolean]
        (
          Some(deferred),
          IO {
            val summary = nebflow.core.summarizeToolCall(call)
            val dangerLevel =
              if call.name == "Bash" then
                call.input("command").flatMap(_.asString).map(nebflow.core.tools.BashTool.dangerLevel).getOrElse(0)
              else if call.name == "Curl" then
                call.input("method").flatMap(_.asString).map(_.toUpperCase) match
                  case Some(m) if !Set("GET", "HEAD", "OPTIONS").contains(m) => 2
                  case _ => 0
              else 1
            Json.obj(
              "type" -> "askPermission".asJson,
              "sessionId" -> state.sessionId.asJson,
              "toolName" -> call.name.asJson,
              "summary" -> summary.asJson,
              "input" -> call.input.asJson,
              "dangerLevel" -> dangerLevel.asJson
            )
          }.flatMap { permJson =>
            // Sub-agents forward permission to parent agent so the answer
            // (routed by sessionId to the root agent) reaches the right Deferred.
            val sendPermission =
              if state.depth > 0 && toolCtx.parentRef.isDefined then
                toolCtx.parentRef.get ! AgentCommand.ForwardPermission(deferred, permJson)
              else (ctx.self ! AgentCommand.SetPermissionDeferred(deferred)) *> state.wsSend(permJson)
            for
              _ <- sendPermission
              approved <- deferred.get
              result <-
                if approved then executeTool(call, toolCtx)
                else IO.pure(ToolExecResult("Permission denied by user", isError = true))
            yield result
          }
        )
    }.flatten

  protected def executeTool(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] =
    ToolRegistry.TOOL_MAP.get(call.name) match
      case Some(tool) =>
        val summary = tool.summarize(call.input)
        val logger = NebflowLogger.forName("nebflow.handlers")
        val agentName = ctx.agentDef.map(_.name).getOrElse("-")
        val sessionName = ctx.sessionName.getOrElse("-")
        val logCtx = logger.ctxPrefix(agentName, sessionName)
        val hookEngine = ctx.hookEngine
        val hookCtx = ctx.hookContext
        IO.delay(System.nanoTime()).flatMap { start =>
          hookEngine
            .beforeTool(call.name, call.input, hookCtx)
            .flatMap { preResult =>
              if preResult.decision == HookDecision.Block then
                val blockMsg = preResult.reason.getOrElse(s"Tool ${call.name} blocked by hook")
                logger.info(s"$logCtx Hook blocked $summary: $blockMsg")
                IO.pure(ToolExecResult(blockMsg, isError = true))
              else
                val finalInput = preResult.updatedInput.getOrElse(call.input)
                val deviceOpt = finalInput("device").flatMap(_.asString).filter(_.nonEmpty).filter(_ != "local")
                val execIO: IO[ToolExecResult] = deviceOpt match
                  case Some(deviceName) if RemoteExecutor.current.isDefined =>
                    val remoteInput = finalInput.remove("device")
                    logger.info(s"$logCtx Remote tool: [${deviceName}] ${tool.summarize(remoteInput)}")
                    RemoteExecutor.current.get.execute(deviceName, call.name, remoteInput, Some(ctx)).flatMap {
                      case Right(result) =>
                        hookEngine.afterTool(call.name, finalInput, result, true, hookCtx).map { postResult =>
                          val hookSuffix = postResult.additionalContext.getOrElse("")
                          ToolExecResult(
                            result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                            frontendContent = Some(result)
                          )
                        }
                      case Left(err) =>
                        hookEngine.afterToolFailure(call.name, finalInput, err.message, hookCtx).map { postResult =>
                          val appended = postResult.additionalContext match
                            case Some(r) => s"${err.message}\n\n$r"
                            case None => err.message
                          ToolExecResult(appended, isError = true)
                        }
                    }
                  case _ =>
                    tool.call(finalInput, ctx).flatMap {
                      case Left(err) =>
                        hookEngine.afterToolFailure(call.name, finalInput, err.message, hookCtx).map { postResult =>
                          val appended = postResult.additionalContext match
                            case Some(r) => s"${err.message}\n\n$r"
                            case None => err.message
                          ToolExecResult(appended, isError = true)
                        }
                      case Right(result) =>
                        val isCard = call.name == "Card"
                        val isFileEdit = call.name == "Edit" || call.name == "Write"
                        hookEngine.afterTool(call.name, finalInput, result, true, hookCtx).map { postResult =>
                          val hookSuffix = postResult.additionalContext.getOrElse("")
                          val llmContent = if isCard then
                            val title = call.input("title").flatMap(_.asString).getOrElse("")
                            s"Card${if title.nonEmpty then s" ($title)" else ""} rendered"
                          else if isFileEdit then nebflow.core.summarizeToolResult(call, result)
                          else result
                          ToolExecResult(
                            llmContent + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                            frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""))
                          )
                        }
                    }
                execIO.handleErrorWith {
                  case _: UserAbort => IO.raiseError(new UserAbort())
                  case e => IO.pure(ToolExecResult(s"Tool execution error: ${e.getMessage}", isError = true))
                }
            }
            .flatTap { result =>
              val elapsed = (System.nanoTime() - start) / 1_000_000
              if result.isError then
                logger.warn(s"$logCtx Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
              else logger.info(s"$logCtx Tool $summary OK (${elapsed}ms)")
            }
        }
      case None => IO.pure(ToolExecResult(s"No such tool available: ${call.name}", isError = true))

  protected def buildAllowedToolSet(agentDef: AgentDef, depth: Int = 0): Set[String] =
    val base = agentDef.tools match
      case Nil => Set.empty[String]
      case List("*") => ToolRegistry.ALL_TOOLS.map(_.name).filterNot(InternalTools.contains).toSet
      case names => names.toSet
    val depthFiltered =
      if depth >= nebflow.core.tools.DelegateTool.MaxDepth then base - "Delegate" - "MountFlow" else base
    if depth > 0 then depthFiltered -- SubagentBlockedTools else depthFiltered

  end buildAllowedToolSet

  protected def buildToolList(agentDef: AgentDef, depth: Int = 0): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef, depth)
    Some(ToolRegistry.ALL_TOOLS.filter(t => allowedSet.contains(t.name)))

  private def enrichDelegateTools(
    tools: Option[List[ToolDefinition]],
    models: List[(String, String, Option[String])]
  ): Option[List[ToolDefinition]] =
    if models.isEmpty then tools
    else
      tools.map(_.map { td =>
        if td.name == "Delegate" then
          val modelList = models
            .map { case (ref, _, desc) =>
              s"  - $ref" + desc.map(d => s": $d").getOrElse("")
            }
            .mkString("\n")
          td.copy(description =
            td.description + s"\n\nAvailable models (pass as the `model` parameter in \"provider/model\" format):\n$modelList"
          )
        else td
      })

  protected def emitStream(
    wsSend: io.circe.Json => IO[Unit],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    val eventName = event.getClass.getSimpleName
    val json = event.toJson(ctx.self.path.name, isSubagent, sessionId)
    ctx.forkTurn(
      wsSend(json).handleErrorWith(e =>
        IO(NebflowLogger.forName("nebflow.agent").warn(s"emitStream($eventName) failed: ${e.getMessage}"))
      )
    )

  /**
   * Direct wsSend for use inside Fiber context (pipeLlmCall, pipeToolExecutions).
   * Do NOT call from receive handlers — use emitStream instead (non-blocking forkTurn wrapper).
   */
  protected def emitStreamIO(
    wsSend: io.circe.Json => IO[Unit],
    event: AgentStreamEvent,
    isSubagent: Boolean = true,
    sessionId: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    wsSend(event.toJson(ctx.self.path.name, isSubagent, sessionId))

  protected def streamEmitter(
    wsSend: io.circe.Json => IO[Unit],
    isSubagent: Boolean = true,
    sessionId: Option[String] = None,
    isAskMode: Boolean = false,
    isCompactTurn: Boolean = false
  )(using ctx: ActorContext[AgentCommand]): fs2.Pipe[IO, StreamChunk, StreamChunk] =
    stream =>
      stream.evalTap {
        case StreamChunk.TextDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          val json =
            if isAskMode then
              Json.obj("type" -> "askTextDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
            else if isSubagent then AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)
        case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          val json =
            if isSubagent then AgentStreamEvent.Thinking.toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)
        case StreamChunk.ToolCallStart(name) if name != "AskUserQuestion" && !isCompactTurn =>
          val json =
            if isSubagent then AgentStreamEvent.ToolCallDetected(name).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
          wsSend(json)
        case StreamChunk.ToolCallChunk(tc) if tc.name != "AskUserQuestion" && !isCompactTurn =>
          val json =
            if isSubagent then
              AgentStreamEvent.ToolStart(nebflow.core.summarizeToolCall(tc)).toJson(ctx.self.path.name, true, None)
            else
              Json.obj(
                "type" -> "toolStart".asJson,
                "sessionId" -> sessionId.asJson,
                "label" -> nebflow.core.summarizeToolCall(tc).asJson
              )
          wsSend(json)
        case StreamChunk.ToolArgDelta(toolName, delta) if delta.nonEmpty && !isCompactTurn && !isSubagent =>
          val json = Json.obj(
            "type" -> "toolArgDelta".asJson,
            "sessionId" -> sessionId.asJson,
            "toolName" -> toolName.asJson,
            "delta" -> delta.asJson
          )
          wsSend(json)
        case _ => IO.unit
      }

  protected def aggregateChunks(chunks: List[StreamChunk]): ConsumeResult =
    val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
    val thinking = chunks.collect { case StreamChunk.ThinkingDelta(d) => d }.mkString
    val thinkingSignature = chunks.collectFirst { case StreamChunk.ThinkingSignature(s) => s }
    val toolCalls = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    val usage = chunks
      .collectFirst { case StreamChunk.Done(_, Some(u), _, _) if u.inputTokens > 0 => u }
      .orElse(chunks.collectFirst { case StreamChunk.Done(_, u, _, _) => u }.flatten)
    val stopReason = chunks.collectFirst { case StreamChunk.Done(sr, _, _, _) => sr }.flatten
    val model = chunks.collectFirst { case StreamChunk.Done(_, _, Some(meta), _) =>
      s"${meta.providerId}/${meta.model}"
    }
    val contextWindow = chunks.collectFirst { case StreamChunk.Done(_, _, _, cw) => cw }.flatten
    ConsumeResult(
      text,
      toolCalls,
      Nil,
      stopReason,
      usage,
      Option.when(thinking.nonEmpty)(thinking),
      thinkingSignature,
      model,
      contextWindow
    )

  end aggregateChunks

  protected def buildSystemPrompt(
    agentDef: AgentDef,
    systemPrefix: String,
    ctx: PromptContext
  ): String =
    val rawPrompt = if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt else Repl.loadSystemPrompt()
    val cleanedPrompt = PromptSections.stripAllMigrated(rawPrompt)
    val conditionalBlocks = PromptSections.buildConditionalBlocks(ctx)
    val separator = if conditionalBlocks.nonEmpty then "\n\n" else ""
    s"$systemPrefix$cleanedPrompt$separator$conditionalBlocks"

  end buildSystemPrompt

  /** Format active persistent sub-agent sessions for system prompt injection. */
  protected def formatAgentSessions(sessions: List[AgentSessionInfo]): String =
    if sessions.isEmpty then ""
    else
      val lines = sessions.map: s =>
        s"${s.address} — ${s.agentName}: ${s.taskDescription} (${s.status})"
      "# Active Sessions\n\n" + lines.mkString("\n") +
        "\n\nUse Mail to send follow-up instructions to any session above."

  @volatile private var deviceInfoCache: (Long, String) = (0L, "")

  private def deviceInfoBlock: String =
    val now = System.currentTimeMillis()
    val (lastUpdate, cached) = deviceInfoCache
    if now - lastUpdate < 30000 && cached.nonEmpty then cached
    else
      val refreshed = RemoteExecutor.current
        .flatMap(_.neblinkServiceOpt)
        .flatMap { ms =>
          try
            import cats.effect.unsafe.implicits.global
            val id = ms.identity.unsafeRunSync()
            val peersList = ms.peers.unsafeRunSync()
            val localStr =
              s"local (${id.deviceName})" +
                (if id.userDescription.nonEmpty then s" -${id.userDescription}" else "")
            val peerStrs =
              peersList.map { p =>
                p.deviceName + (if p.userDescription.nonEmpty then s" -${p.userDescription}" else "")
              }
            val allDevices = (localStr :: peerStrs).mkString("; ")
            val deviceHint =
              if peersList.nonEmpty then
                "\nEach tool accepts a `device` parameter. Select the appropriate device for each task."
              else ""
            Some(s"$allDevices$deviceHint")
          catch case _: Exception => None
        }
        .getOrElse("")
      deviceInfoCache = (now, refreshed)
      refreshed

    end if

  end deviceInfoBlock

  protected def summarizeToolResult(call: ToolCall, result: String): String =
    nebflow.core.summarizeToolResult(call, result)

end AgentCore
