package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.agent.PromptSections.*
import nebflow.core.*
import nebflow.core.compact.*
import nebflow.core.hooks.*
import nebflow.core.tools.*
import nebflow.llm.{Fallback, TurnBudgetExceeded}
import nebflow.shared.*
import nebflow.shared.given

import scala.concurrent.duration.*

private[agent] trait AgentCore:

  protected val MaxDepth = 5

  /**
   * Timeout for permission confirmation. Prevents indefinite session lockup
   * when the user doesn't respond (popup missed, WS issue, away from keyboard).
   */
  private val PermissionTimeout = 5.minutes

  /**
   * Nebula-exclusive tools: only available when agentName == "Nebula".
   * - Schedule: session-scoped scheduled tasks
   * - Delegate: 调度器/根 agent 专用——指派 standalone agent。
   *   Team 成员委派走 SubTaskTool（self-clone + ephemeral）。
   *   Flow 触发不在此列——FlowTrigger 由 agent.json flows 白名单驱动注入。
   * - Issue: 系统反馈收集仅编排者持有（2026-08-25 17:49 裁定）。
   */
  private val NebulaExclusiveTools = AgentCore.NebulaExclusiveTools

  /** Tools available to Nebula and Team Leads, but NOT workers. */
  private val LeadLevelTools = AgentCore.LeadLevelTools

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
      // ── P0-2（2026-08-22 Write-only 循环批）：满上下文硬截断前置 ──
      // 估算/上轮上报超 0.95×window 时，LLM 依赖的压缩路径（save turn 与
      // compact turn 都要带全量消息再调一次 LLM）数学上不可行。原
      // emergencyClean 保底挂在 circuitBreaker 之后且失败计数是内存态
      // （激活-失败-放弃循环中 actor 重建重置，circuitBreakerMax 永远攒不
      // 够）——满死锁下不可达。前置到一切回退条件之前：backoff 未满足也
      // 不能挡（否则 dispatch 裸奔超窗 LLM 调用，intake 后静默死）。
      // estimate 是 500/msg 粗估（可高估）——但正常路径早在 80% 阈值就触发
      // LLM 压缩，能走到 0.95 的只有「压缩持续失败/被卡」，正是 emergency
      // 的既定场景。
      val hardLimit = (state.contextWindow.toDouble * 0.95).toInt
      val reported = state.latestUsage.map(_.inputTokens).filter(_ > 0)
      val estimatedNow = TokenEstimator.estimate(state.messages)
      // Cooldown: emergencyClean keeps the last N messages — if those tails
      // alone still estimate above the limit, an unthrottled guard would
      // re-fire on every dispatch of the SAME turn chain (infinite loop,
      // found by SaveTurnGuardSpec). Re-checking is fine a minute later.
      val cooldownOk =
        System.currentTimeMillis() - state.lastCompactionFailureAt > EmergencyHardGuardCooldownMs
      if (reported.exists(_ > hardLimit) || estimatedNow > hardLimit) && cooldownOk then
        runEmergencyCompact(
          agentDef,
          resources,
          depth,
          parentRef,
          state,
          replyTo,
          processing,
          s"P0-2 hard-guard: estimated=$estimatedNow reported=${reported.getOrElse(0)} hardLimit=$hardLimit messages=${state.messages.size}"
        )
      else
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
          if !config.emergencyAutoFallback then
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
            runEmergencyCompact(
              agentDef,
              resources,
              depth,
              parentRef,
              state,
              replyTo,
              processing,
              s"circuitBreakerOpen failures=${state.compactionFailures} messages=${state.messages.size}"
            )

        else
          val inputTokensOpt = state.latestUsage.map(_.inputTokens)
          // Unified threshold: hardcoded, role-independent (CompactThreshold).
          val threshold = CompactThreshold.threshold(state.contextWindow)
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

  /**
   * Emergency fallback: non-LLM rule-based compaction (extracted 2026-08-22
   * Write-only loop batch). Strips tool results, removes old tool-result
   * pairs, truncates to last N. Two callers:
   *   - P0-2 hard guard: estimated/reported tokens > 0.95×contextWindow — the
   *     LLM-dependent compaction paths are mathematically infeasible there
   *     (both stages resend the full history; the saturated-window call dies
   *     silently after intake — the a5431750 deadlock).
   *   - circuitBreaker open (original behavior, unchanged).
   * withLatestUsage(None) is load-bearing for P0-2: a stale over-limit
   * reported usage would re-trigger the guard right after truncation.
   */
  /** P0-2 hard-guard cooldown: minimum spacing between emergencyClean runs
    * for the SAME session (guards against re-fire loops when the retained
    * tail alone still estimates above 0.95×window). */
  protected val EmergencyHardGuardCooldownMs: Long = 60_000L

  protected def runEmergencyCompact(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn,
    reason: String
  )(using ctx: ActorContext[AgentCommand]): Option[IO[Behavior[AgentCommand]]] =
    val config = CompactConfig()
    logAgentEvent(agentDef, depth, state.sessionId, state.sessionName, "emergency-compact-trigger", reason)
    val (cleaned, desc) = CompactUtils.emergencyClean(state.messages, config.emergencyKeepMessages)
    val emergencyState = state
      .withMessages(cleaned)
      .withCompactionFailures(0)
      .withLatestUsage(None)
      // Timestamp doubles as the P0-2 hard-guard cooldown anchor (compaction
      // failures are reset to 0 above, so the backoff reader is unaffected).
      .withLastCompactionFailureAt(System.currentTimeMillis())
    Some(for
      _ <- ctx.forkTurn(
        emitStreamIO(
          state.wsSend,
          AgentStreamEvent.CompactComplete(state.messages.size, cleaned.size, None),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      _ <- state.sessionId.fold(IO.unit)(sid =>
        ctx.forkTurn(
          resources.historyArchiver
            .archiveCompaction(
              sessionId = sid,
              sessionName = state.sessionName,
              agentName = agentDef.name,
              before = state.messages,
              after = cleaned,
              mode = "emergency",
              extra = Map("description" -> desc, "reason" -> reason)
            )
            .void
            .handleErrorWith(_ => IO.unit)
        )
      )
      result <- pipeLlmCall(agentDef, resources, depth, parentRef, emergencyState, replyTo, processing)
    yield result)

  /**
   * Whether this agent gets a save-memory turn before compaction.
   * Precisely mirrors ContextRefresher's memory-injection condition:
   * Nebula (depth 0) and team agents (Manager/Worker) get memory; standalone
   * and flow agents don't — so no save turn for them (behavior unchanged).
   */
  protected def shouldInjectSaveReminder(agentDef: AgentDef, state: AgentState): IO[Boolean] =
    if agentDef.name == "Nebula" then IO.pure(true)
    else
      state.sessionId match
        case Some(sid) => nebflow.core.flow.TeamSessionRegistry.teamOfSession(sid).map(_.isDefined)
        case None => IO.pure(false)

  /**
   * B5: whether this depth-1 agent is a team lead — a registered Manager
   * session, or name-based lead of any defined team (covers fork/temporary
   * sessions, same fallback as MailTool.canMailNebula). Non-lead depth-1
   * agents (team members, flow agents, delegate sub-agents) compact with
   * the Worker profile instead of Manager.
   */
  protected def isTeamLeadForCompaction(agentDef: AgentDef, sessionId: Option[String]): IO[Boolean] =
    def nameIsLead: IO[Boolean] =
      if agentDef.name.isEmpty then IO.pure(false)
      else nebflow.core.entity.EntityLoader.listTeams().map(_.values.exists(_.lead == agentDef.name))
    sessionId match
      case Some(sid) =>
        nebflow.core.flow.TeamSessionRegistry.isManager(sid).flatMap {
          case true  => IO.pure(true)
          case false => nameIsLead
        }
      case None => nameIsLead

  /** Team Manager task tool gating (2026-08-25): is this session the lead
    * (Manager) of its team? Strict registry lookup only — TeamSessionRegistry
    * .managerMap is populated at mount by FlowTreeActor.createSingleTeamSession
    * (agentName == teamDef.lead). NO nameIsLead fallback (unlike
    * isTeamLeadForCompaction): a team MEMBER whose agent name happens to match
    * some other team's lead must not receive the owner toolset (U2). No
    * separate spawn-time flag needed — the registry is the single source of
    * truth and survives Mail activation / respawn. */
  protected def isTeamLeadStatus(agentDef: AgentDef, sessionId: Option[String]): IO[Boolean] =
    sessionId match
      case Some(sid) => nebflow.core.flow.TeamSessionRegistry.isManager(sid)
      case None => IO.pure(false)

  protected def startDirectCompaction(
    agentDef: AgentDef,
    resources: SharedResources,
    depth: Int,
    parentRef: Option[ActorRef[AgentCommand]],
    state: AgentState,
    replyTo: Option[ActorRef[AgentEvent]],
    processing: ProcessingFn,
    mode: String,
    resumeAfterCompact: Boolean = true,
    postCompactInstruction: Option[String] = None
  )(using ctx: ActorContext[AgentCommand]): IO[Behavior[AgentCommand]] =
    for
      // B5: depth 1 must distinguish lead (Manager profile) from members
      // (Worker profile). Depths 0 / 2+ don't need the lookup.
      isLead <- if depth == 1 then isTeamLeadForCompaction(agentDef, state.sessionId) else IO.pure(false)
      profile = CompactionProfile.fromDepth(depth, isLead)
      hook = PreCompactionHooks.forProfile(profile)

      // ── 1. Role-based pre-compaction extraction (fire-and-forget, non-blocking) ──
      preHookIO = hook
        .run(state.messages, agentDef.name, state.sessionId, None, resources)
        .handleErrorWith(e =>
          IO(lifecycleLog.warn(s"Pre-compaction hook failed for ${agentDef.name}: ${e.getMessage}")).void
        )

      // ── 2. Two-stage compaction ──
      //    Stage 1 (Save): memory-bearing agents (Nebula + team) get a save turn
      //    with tools available to write memory/skills; ending the turn transitions
      //    to stage 2. Other agents go straight to stage 2 (compact) — unchanged.
      jobId = s"compact-${java.util.UUID.randomUUID().toString.take(8)}"
      saveTurn <- shouldInjectSaveReminder(agentDef, state)
      phase = if saveTurn then CompactionPhase.Save else CompactionPhase.Compact
      reminder =
        if saveTurn then CompactService.buildSaveMemoryReminder(depth, isLead)
        else CompactService.buildCompactReminder(depth, isLead)
      pending = CompactionJob(jobId, mode, None, replyTo, resumeAfterCompact, postCompactInstruction, phase)
      firstState = state
        .withPendingCompaction(Some(pending))
        .withMessages(state.messages :+ reminder)
      _ <- ctx.forkTurn(preHookIO)
      _ <- ctx.forkTurn(
        emitStreamIO(
          state.wsSend,
          AgentStreamEvent.CompactStart(
            mode,
            state.latestUsage.map(_.inputTokens),
            Some(CompactThreshold.threshold(state.contextWindow))
          ),
          isSubagent = depth > 0,
          state.sessionId
        ).handleErrorWith(_ => IO.unit)
      )
      result <- pipeLlmCall(agentDef, resources, depth, parentRef, firstState, replyTo, processing)
    yield result

    end for

  end startDirectCompaction

  // ============================================================
  // Reminder refactor (2026-08-20, user ruling — audit
  // docs/Nebflow/20260820_system-reminder-audit.md):
  // time/tasks injection gating + tasks delta state.
  // ============================================================

  /** Min gap between two time reminders on system-event turns (user ruling:
    * 「每小时通知一次。轮次的第一个 user turn 注入等」). Real-user turns
    * always inject. Initialized to construction time: a fresh actor (spawn /
    * restart) stays quiet on system events for the first hour — real user
    * input is the orientation trigger, not background noise. */
  private val TimeReminderMinGapMs = 60 * 60 * 1000L

  @volatile private var lastTimeReminderMs: Long = System.currentTimeMillis()

  /** Previous turn's task lines (id -> "#id [status] subject") for delta
    * detection. Actor-local by design: one AgentActor serves one session, and
    * a restart/compaction (lifecycle) resets it back to a full render. */
  @volatile private var lastTaskLines: Map[String, String] = Map.empty

  private val TaskLineRegex = """^\s*#(\d+)\s+(.+)$""".r

  /** Extract "#id ..." task lines from a renderForPrompt output (headers and
    * fold lines don't match and are ignored). */
  private def parseTaskLines(rendered: String): Map[String, String] =
    rendered.linesIterator.collect {
      case TaskLineRegex(id, rest) => id -> s"#$id $rest".trim
    }.toMap

  /** Full / delta / unchanged task text for this turn's reminder. */
  private def renderTasksForTurn(
    resources: SharedResources,
    sessionId: String,
    lifecycleReset: Boolean
  ): IO[String] =
    resources.taskStore.renderForPrompt(sessionId).map { full =>
      val current = parseTaskLines(full)
      val text =
        if full.isEmpty then
          // Active set became empty — announce the clearing once, then stay quiet.
          if lastTaskLines.nonEmpty then "All tasks done — the task list is now empty."
          else ""
        else if lifecycleReset || lastTaskLines.isEmpty then full
        else if current == lastTaskLines then s"Tasks unchanged (${current.size} active)."
        else renderTaskDelta(lastTaskLines, current)
      lastTaskLines = current
      text
    }

  /** "+ added / ~ changed / - removed" lines against the previous turn. */
  private def renderTaskDelta(oldMap: Map[String, String], newMap: Map[String, String]): String =
    val byId = (k: String) => k.toIntOption.getOrElse(Int.MaxValue)
    val added = newMap.keySet.diff(oldMap.keySet).toList.sortBy(byId)
    val removed = oldMap.keySet.diff(newMap.keySet).toList.sortBy(byId)
    val changed = newMap.collect { case (k, v) if oldMap.get(k).exists(_ != v) => k }.toList.sortBy(byId)
    val sb = new StringBuilder
    sb.append(s"## Task changes (${newMap.size} active)\n")
    added.foreach(id => sb.append(s"+ ${newMap(id)}\n"))
    changed.foreach(id => sb.append(s"~ ${newMap(id)}\n"))
    removed.foreach(id => sb.append(s"- ${oldMap(id)}\n"))
    sb.toString

  /** Devices delta: "+ entry" for new devices, "- entry" for gone ones.
    * Falls back to the full block when only ordering/hints changed (entries
    * identical) so the agent still gets a coherent picture. */
  private def devicesDeltaLines(oldInfo: String, newInfo: String): String =
    def entries(s: String): Vector[String] =
      s.split("[;\n]").map(_.trim).filter(_.nonEmpty).toVector
    val oldE = entries(oldInfo)
    val newE = entries(newInfo)
    val added = newE.filterNot(oldE.contains)
    val removed = oldE.filterNot(newE.contains)
    if added.isEmpty && removed.isEmpty then newInfo
    else (added.map("+" + _) ++ removed.map("-" + _)).mkString("\n")

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
        // Phase-aware compaction: Save turn keeps tools available (memory write),
        // Compact turn disables tools (existing behavior).
        val isCompactTurn = state.pendingCompaction.exists(_.phase == CompactionPhase.Compact)
        val isSaveTurn = state.pendingCompaction.exists(_.phase == CompactionPhase.Save)
        val isAskTurn = state.askMode.isDefined
        val tools = if isCompactTurn then Some(Nil) else buildToolList(agentDef, depth, state.isSubTaskWorker, state.isFlowNode)
        val isSubagent = depth > 0
        val sessionIdOpt = state.sessionId
        // Track the first model that failed (for modelChanged notification)
        val firstFailedModel: cats.effect.Ref[IO, Option[String]] = cats.effect.Ref.unsafe(None)
        val onAttemptCb: FallbackAttempt => IO[Unit] = attempt =>
          // Record the first failed model for modelChanged comparison
          firstFailedModel.get.flatMap {
            case None => firstFailedModel.set(Some(s"${attempt.providerId}/${attempt.model}"))
            case _ => IO.unit
          } *> {
            val msg = attempt.message.getOrElse(s"${attempt.providerId}/${attempt.model} failed, retrying...")
            state.wsSend(AgentStreamEvent.RetryStatus(msg).toJson(ctx.self.path.name, isSubagent, sessionIdOpt))
          }
        val turnId = state.currentTurnId + 1
        // Save turn also skips FastMicroCompact: the agent needs the full history
        // (including old tool results) to extract durable memory entries.
        val microResult = if isCompactTurn || isSaveTurn || isAskTurn then None else FastMicroCompact(state.messages)
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

        // Synchronous context preparation (fast: file reads with mtime cache)
        val contextIo = for
          turnCtx <- ContextRefresher.refreshTurn(stateForLlm, resources, agentDef)
          freshDef = turnCtx.agentDef
          voiceMuted <- resources.voiceMutedRef.get
          voiceEnabled = freshDef.voiceEnabled && !voiceMuted
          // Team Manager task tools (#D 2026-08-25): the team lead (Manager)
          // gets the owner toolset; derived from the registry at turn time so
          // mount/unmount/hot-reload always reflects reality.
          isTeamLead <- isTeamLeadStatus(freshDef, stateForLlm.sessionId)
          // 轨道二 #5: hot-read the dedicatedAgents flag once per turn — flows
          // into tool stripping (T1 leaf display tools) + identity clause.
          guardrailsOn <- nebflow.core.Guardrails.enabled
          allowedTools = buildAllowedToolSet(freshDef, depth, stateForLlm.isSubTaskWorker, isFlowNode = stateForLlm.isFlowNode, isTeamLead = isTeamLead, userFacingNode = stateForLlm.userFacingNode, guardrailsOn = guardrailsOn)
          // #16 observability: one log line per LLM call when MCP tools are
          // injected — names the servers explicitly so phantom-tool suspicion
          // can be settled by grepping the log instead of reconstructing
          // requests. Zero-MCP agents stay silent (no noise).
          mcpInjected = allowedTools.filter(_.startsWith("mcp__"))
          _ = if mcpInjected.nonEmpty then
            val servers = mcpInjected.map(_.split("__").apply(1)).toList.sorted
            NebflowLogger.forName("nebflow.agent.mcp").info(
              s"injecting ${mcpInjected.size} MCP tools from servers: ${servers.mkString(", ")} " +
                s"(agent=${freshDef.name}, session=${stateForLlm.sessionId.getOrElse("-")})"
            )
          devInfo = deviceInfoBlock
          sessionsText = formatAgentSessions(stateForLlm.agentSessions)
          // Lifecycle nodes (new session / compaction / restart): rebuild the
          // whole systemStable and refresh the snapshot — mid-session changes
          // are reported via reminders instead of invalidating the cache.
          // Computed BEFORE the task-list render: a lifecycle reset also resets
          // the tasks delta back to a full render (2026-08-20 refactor).
          isLifecycleRebuild = isCompactTurn || stateForLlm.cachedSystemStable.isEmpty
          // Reminder refactor (2026-08-20, user ruling — see
          // docs/Nebflow/20260820_system-reminder-audit.md): "real user turn"
          // means the triggering message was typed by the user (source=None).
          // Tool-injected User messages (mail/mail-queue/skill/flow/external)
          // carry a source marker — system-event turns. time fires on system
          // events at most once per hour; tasks only on real-user turns.
          lastMsgOpt = stateForLlm.messages.lastOption
          isUserTurn = lastMsgOpt.exists { m =>
            m.role == MessageRole.User && !m.content.toOption.exists(_.exists(_.isInstanceOf[ContentBlock.ToolResult]))
          }
          isRealUserTurn = isUserTurn && lastMsgOpt.exists(_.source.isEmpty)
          isRootAgent = freshDef.name == "Nebula"
          nowMs = System.currentTimeMillis()
          injectTime = isUserTurn && (isRealUserTurn || nowMs - lastTimeReminderMs >= TimeReminderMinGapMs)
          // Tasks: Nebula only, real-user turns only. renderForPrompt returns
          // the FULL list; the delta against the previous turn lives here
          // (unchanged → one line; changed → +/-/~ lines; lifecycle → full).
          taskListText <-
            if isRootAgent && isRealUserTurn then
              stateForLlm.sessionId match
                case Some(sid) => renderTasksForTurn(resources, sid, isLifecycleRebuild)
                case None => IO.pure("")
            else IO.pure("")
          // Env section text (rendered from data.sh + prompt.md) — stays in
          // systemStable only. Reminder refactor (2026-08-20): environment
          // CHANGE reminders are gone (user ruling); chatWidth was removed
          // from the template so this text is constant mid-session anyway.
          envInfo = PromptSections.envInfoSection(
            PromptContext(chatWidth = stateForLlm.session.chatWidth)
          )
          // Snapshot of the dynamic values at systemStable build time.
          currentSnapshot = SystemStableSnapshot(devInfo, sessionsText, stateForLlm.language, envInfo)
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
            agentCategory = freshDef.category,
            agentName = freshDef.name,
            skillCatalog = turnCtx.skillCatalog,
            flowCatalog = turnCtx.flowCatalog,
            teamCatalog = turnCtx.teamCatalog,
            memoryBlock = turnCtx.memoryBlock,
            rulesMd = turnCtx.rulesMd,
            isSubTaskWorker = stateForLlm.isSubTaskWorker,
            guardrailsOn = guardrailsOn,
            isFlowNode = stateForLlm.isFlowNode,
            userFacingNode = stateForLlm.userFacingNode,
            isTeamLead = isTeamLead
          )
          // systemStable: rebuilt only at lifecycle nodes; otherwise reuse the
          // cached string byte-for-byte (provider prefix cache stays hit).
          // Memory block (turnCtx.memoryBlock) is therefore consumed only at
          // rebuild — memory edits take effect at the next lifecycle node.
          // Reminder refactor (2026-08-20): sessions/environment change
          // reminders removed (user ruling) — only devices (delta) and
          // language remain as change notifications.
          (systemStable, changeDevices, changeLanguage) =
            if isLifecycleRebuild then
              (buildSystemPrompt(freshDef, turnCtx.systemPrefix, promptCtx), "", Option.empty[String])
            else
              val cached = stateForLlm.cachedSystemStable.getOrElse(
                buildSystemPrompt(freshDef, turnCtx.systemPrefix, promptCtx)
              )
              val snap = stateForLlm.stableSnapshot.getOrElse(currentSnapshot)
              (
                cached,
                if snap.devices != devInfo then devicesDeltaLines(snap.devices, devInfo) else "",
                if snap.language != stateForLlm.language then stateForLlm.language else None
              )
          reminders <- SystemReminders.collectAllIO(
            isUserTurn,
            resources.scheduledTaskStore,
            stateForLlm.sessionId,
            deviceDelta = changeDevices,
            taskListText = taskListText,
            language = changeLanguage,
            isRootAgent = isRootAgent,
            injectTime = injectTime
          )
          // Branch change: persist synchronously (no async message needed)
          _ <- turnCtx.branchChange match
            case Some(_) =>
              stateForLlm.sessionId.traverse_(sid =>
                resources.sessionStore
                  .updateGitBranch(sid, turnCtx.currentBranch)
                  .handleErrorWith(e =>
                    NebflowLogger.forName("nebflow.agent").warn(s"Failed to persist gitBranch: ${e.getMessage}")
                  )
              )
            case None => IO.unit
          loggedReminders <- SystemReminders.logAndReturn(reminders)
          // The time reminder is PERSISTED as a real User message in the
          // session history: folded into the working state so it flows into
          // the persisted history (persistIfSession saves state.messages) and
          // into the LLM request. Older time reminders are pruned to bound
          // history growth. Dynamic context (peak/idle windows, pending
          // schedules) stays request-only — injected per-turn, never persisted.
          timeReminders = loggedReminders.filter(_.category == "time")
          contextReminders = loggedReminders.filterNot(_.category == "time")
          timeMsg =
            if isCompactTurn || isAskTurn || timeReminders.isEmpty then Nil
            else List(Message(MessageRole.User, Left(SystemReminder.renderAll(timeReminders))))
          // Reminder refactor (2026-08-20): stamp the injection time only when
          // a time reminder actually fires — the ≥1h system-event gap is
          // measured from the last REAL injection, not the last turn.
          _ = if timeMsg.nonEmpty then lastTimeReminderMs = System.currentTimeMillis()
          contextMsg =
            if isCompactTurn || isAskTurn || contextReminders.isEmpty then Nil
            else List(Message(MessageRole.User, Left(SystemReminder.renderAll(contextReminders))))
          branchMsg =
            if isCompactTurn || isAskTurn then Nil
            else turnCtx.branchChange.toList.map(r => Message(MessageRole.User, Left(r.render)))
          stateWithReminder =
            if timeMsg.isEmpty then stateForLlm
            else stateForLlm.withMessages(SystemReminders.pruneTimeReminders(stateForLlm.messages ++ timeMsg))
          // Cache v2: persist the rebuilt systemStable + snapshot in state at
          // lifecycle nodes (next turns reuse it). Non-lifecycle turns keep the
          // existing cache untouched.
          stateWithCache =
            if isLifecycleRebuild then stateWithReminder.withSystemStableCache(systemStable, currentSnapshot)
            else stateWithReminder
          // Maintenance check: every N delegate/flow calls
          maintenanceMsg =
            if MaintenanceService.shouldTrigger(stateWithReminder, depth, isCompactTurn, isAskTurn) then
              List(MaintenanceService.buildReminder(stateWithReminder.delegateCount))
            else Nil
          freshTools =
            if isCompactTurn then Some(Nil)
            else if isSaveTurn then saveTurnTools(freshDef, depth, stateForLlm.isSubTaskWorker, stateForLlm.isFlowNode, isTeamLead)
            else buildToolList(freshDef, depth, stateForLlm.isSubTaskWorker, stateForLlm.isFlowNode, isTeamLead, userFacingNode = stateForLlm.userFacingNode, guardrailsOn = guardrailsOn)
          // 冷启动路由已删除（2026-08-19 用户裁决：「这是错误的，按 preset」）：
          // 它把闲置唤醒/重启后的第一发改道到 LowCost preset，偏离用户设置的
          // preset 链。模型选择现在严格 = freshDef.model（preset 解析结果）。
          // #341 工具结果 TTL 清理（docs/Nebflow/20260820_tool-result-ttl.md）：
          // REQUEST-ONLY——只作用于本次请求的消息副本，stateWithReminder 与
          // 落盘会话零改动（语义三分：显示/LLM 上下文/会话文件）。门控与
          // FastMicroCompact 相同的 turn 排除（压缩/存档/ask 需要全量输入）；
          // keepRecent 窗保证 turn 中途的当前结果永不被清理（构造性安全）。
          // #341 WS 尾巴：配置 Ref 化——每请求读当前值，setToolResultTtl 热更
          // 即时生效（无需重启）。
          ttlCfg <- resources.toolResultTtlRef.get
          ttlCleanedMessages =
            if isCompactTurn || isSaveTurn || isAskTurn then None
            else ToolResultTtl.cleanRequestMessages(stateWithReminder.messages, ttlCfg)
          _ = ttlCleanedMessages.foreach { _ =>
            logAgentEvent(
              agentDef,
              depth,
              state.sessionId,
              state.sessionName,
              "tool-result-ttl",
              "request-only cleanup applied (session history untouched)"
            )
          }
          request = LlmRequest(
            messages = ttlCleanedMessages.getOrElse(stateWithReminder.messages) ++ contextMsg ++ branchMsg ++ maintenanceMsg,
            sessionId = stateForLlm.sessionId.getOrElse(ctx.self.path.name),
            agentId = freshDef.name,
            tools = freshTools,
            maxTokens = Some(resources.agentLibrary.globalMaxTokens),
            thinking = Some(nebflow.llm.ThinkingConfig.toLlmJson(turnCtx.thinkingConfig)),
            systemStable = Some(systemStable),
            agentModel = freshDef.model,
            // WebSearch P0: housekeeping turns (compact/save/ask) opt out of
            // provider-native search injection — a server-side search tool
            // must never leak into summarization or memory-maintenance loops.
            searchAllowed = !(isCompactTurn || isSaveTurn || isAskTurn)
          )
        yield (turnCtx, request, stateWithCache)

        // #22 (2026-08-19): the context build runs INLINE in the actor's
        // message loop — a hang here (file lock, blocking read) freezes the
        // whole actor with zero log artifacts and matches the "turn starts,
        // next request never fires, no error" signature. Bound it: on breach
        // the turn fails loudly via LlmFailed instead of freezing silently.
        val ContextBuildTimeout = 60.seconds

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
          // P0 阶段 3：LLM 调用开始——registry 状态快照置 Processing 并 touch
          // 活动戳（流 chunk 期间由 evalTap 持续刷新，见下方 sendStream 管道）。
          _ <- touchRegistryActivity(resources, sessionIdOpt, AgentStatus.Processing)
          ctxTriple <- contextIo.timeout(ContextBuildTimeout).attempt
          result <- ctxTriple match
            case Right((turnCtx, request, stateWithReminder)) =>
              // Synchronously update gitBranch in state — no async message
              val stateWithBranch = stateWithReminder.withGitBranch(turnCtx.currentBranch)
              ctx.forkTurn(
                  // ── Token 止损（2026-08-18 事故，方案 C）：per-turn LLM 重试预算 ──
                  // 同 turn 内失败重试次数（仅 LlmFailed 分支递增；正常工具循环的
                  // 成功调用不计）超限 → 抛 TurnBudgetExceeded（Permanent 分类，
                  // llm-fail-retry 不会再触发）→ 下方 .attempt 捕获后发 LlmFailed，
                  // turn 快速失败并给用户明确原因。
                  IO.raiseWhen(stateForLlm.execution.llmCallsThisTurn >= Fallback.MaxTurnLlmCalls)(
                    new TurnBudgetExceeded(
                      turnId,
                      stateForLlm.execution.llmCallsThisTurn
                    )
                  ) *>
                    resources.llm
                      .sendStream(request, onAttempt = Some(onAttemptCb))
                      .through(streamEmitter(stateForLlm.wsSend, isSubagent, sessionIdOpt, isAskTurn, isCompactTurn))
                      // P0 阶段 3：每个流 chunk touch 活动戳——流活着 = turn 有活动 =
                      // 不判卡死。Ref.modify 是原子的，按流序逐 chunk 更新，开销可忽略。
                      .evalTap(_ => touchRegistryActivity(resources, sessionIdOpt, AgentStatus.Processing))
                      .compile
                      .toList
                      .flatMap { chunks =>
                        val cr = aggregateChunks(chunks)
                        // Track runtime model for this session
                        val trackModel = sessionIdOpt match
                          case Some(sid) if cr.model.isDefined =>
                            resources.runtimeModels.update(_ + (sid -> cr.model.get))
                          case _ => IO.unit
                        // Broadcast modelChanged if fallback occurred
                        val notifyModelChanged = firstFailedModel.get.flatMap {
                          case Some(oldModel) if cr.model.isDefined && cr.model.get != oldModel =>
                            state.wsSend(
                              io.circe.Json.obj(
                                "type" -> "modelChanged".asJson,
                                "sessionId" -> sessionIdOpt.asJson,
                                "oldModel" -> oldModel.asJson,
                                "newModel" -> cr.model.get.asJson
                              )
                            )
                          case _ => IO.unit
                        }
                        trackModel *> notifyModelChanged *>
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
                      .flatMap {
                        case Right(r) => ctx.self ! LlmComplete(r, replyTo, turnId)
                        case Left(e) => ctx.self ! LlmFailed(e, replyTo, turnId)
                      }
                      .handleErrorWith { e =>
                        NebflowLogger
                          .forName("nebflow.agent")
                          .warn(s"pipeLlmCall failed: ${e.getMessage}")
                          .flatMap(_ => ctx.self ! LlmFailed(e, replyTo, turnId))
                      }
                )
                .map { _ =>
                  processing(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    // Update lastMaintenanceDelegateCount if maintenance was triggered this turn
                    (if MaintenanceService.shouldTrigger(stateWithReminder, depth, isCompactTurn, isAskTurn) then
                       stateWithBranch.withLastMaintenanceDelegateCount(stateWithReminder.delegateCount)
                     else stateWithBranch)
                      .withLastDispatch(Some(LastDispatch(isToolExecution = false)))
                      // 方案 C（2026-08-18 误杀修复）：成功调用不计入预算——llmCallsThisTurn
                      // 仅在 AgentActor 的 LlmFailed 重试分支递增，正常工具循环（读→改→
                      // 编译→再改）每次成功继续调用都不再 +1。
                  )
                }
            case Left(e) =>
              // Context build timed out / failed — fail the turn loudly instead
              // of freezing the actor. State must carry the new turnId or the
              // LlmFailed below is discarded as stale.
              IO(logAgentEvent(
                agentDef,
                depth,
                state.sessionId,
                state.sessionName,
                "context-build-failed",
                s"err=${e.getMessage.take(120)}"
              )) *>
                (ctx.self ! LlmFailed(
                  ToolPipelineError(
                    s"Turn context build failed after ${ContextBuildTimeout.toSeconds}s " +
                      s"(file lock or blocking read?): ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
                  ),
                  replyTo,
                  turnId
                )) *>
                IO.pure(
                  processing(
                    agentDef,
                    resources,
                    depth,
                    parentRef,
                    state.withCurrentTurnId(turnId)
                  )
                )
        yield result

        end for

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
    val nextTurnIdx = state.turnIdx + 1
    val permissionDeferredRef = cats.effect.Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)
    // #12 劝停：同 turn 同工具的用户拒绝计数（per-turn lifecycle 与
    // permissionDeferredRef 一致——turn 结束自然清零）。
    val permissionDenialsRef = cats.effect.Ref.unsafe[IO, Map[String, Int]](Map.empty)
    val isSubagent = depth > 0
    val sessionIdOpt = state.sessionId

    val io = for
      // Executor gate reads the CURRENT def — the same refresh source the
      // per-turn schema build uses (ContextRefresher.loadCurrentDef) — so
      // panel edits to agent.json (flows whitelist, tools) take effect on
      // the running actor instead of waiting for an actor rebuild. This
      // closes the 2026-08-15 follow-up: mid-session flows edits were still
      // gated by the actor-startup snapshot ("Allowed: <stale list>").
      teamNameOpt <- state.sessionId match
        case Some(sid) => nebflow.core.flow.TeamSessionRegistry.teamOfSession(sid)
        case None => IO.pure(None)
      currentDefOpt <- ContextRefresher.loadCurrentDef(teamNameOpt, resources, agentDef)
      effectiveDef = currentDefOpt.getOrElse(agentDef)
      isTeamLead <- isTeamLeadStatus(effectiveDef, state.sessionId)
      guardrailsOn <- nebflow.core.Guardrails.enabled
      allowedTools = buildAllowedToolSet(effectiveDef, depth, state.isSubTaskWorker, state.isFlowNode, isTeamLead, userFacingNode = state.userFacingNode, guardrailsOn = guardrailsOn)
      (filteredCalls, droppedCalls) =
        // WebSearch P0: kimi's native $web_search tool call bypasses the
        // agent-tool whitelist — it is provider-injected (not an agent tool)
        // and MUST pass through; filtering it here would re-create the
        // "Tool not available" retry storm (2026-08-20 qwen incident shape).
        val (kept, dropped) = result.toolCalls.partition(tc =>
          allowedTools.contains(tc.name) || tc.name == nebflow.llm.SearchProviderResolver.KimiWebSearchToolName
        )
        // warnSync: this is a synchronous code path — the plain IO-returning
        // warn would be discarded silently (it was, before 2026-08-15).
        if dropped.nonEmpty then
          NebflowLogger
            .forName("nebflow.agent")
            .warnSync(s"Tool calls filtered (not in allowed set): ${dropped.map(_.name).distinct.mkString(", ")}")
        (kept, dropped)
      freshProjectRoot <- ContextRefresher.resolveProjectRootForTool(state, resources, effectiveDef)
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
        agentDef = Some(effectiveDef),
        agentLibrary = Some(resources.agentLibrary),
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
        bashConfig = resources.bashResilience,
        teamName = teamNameOpt
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
          (
            // WebSearch P0: kimi's native $web_search round-trip — the caller
            // echoes the model's arguments back VERBATIM as the tool result
            // (Moonshot semantics: the search then runs server-side next
            // round). Pure echo, zero side effects — bypasses the permission
            // system (an Ask here would deadlock the round-trip on a
            // synthetic tool the user never configured).
            if call.name == nebflow.llm.SearchProviderResolver.KimiWebSearchToolName then
              IO.delay(
                NebflowLogger.forName("nebflow.agent").infoSync(
                  s"[${agentDef.name}] kimi native web search round-trip (echo ${call.rawArguments
                      .map(_.length)
                      .getOrElse(0)} chars)"
                )
              ).as(ToolExecResult(nebflow.llm.SearchProviderResolver.kimiEchoContent(call)))
            else permissionDecision(resources, state, call)
              .flatMap {
                case PermissionDecision.Allow => executeTool(call, callCtx)
                case PermissionDecision.Deny =>
                  IO.pure(
                    ToolExecResult(s"Tool ${call.name} is denied by the session permission policy", isError = true)
                  )
                case PermissionDecision.Ask => askUserPermission(call, state, permissionDeferredRef, permissionDenialsRef, callCtx)
              }
          )
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
      // ── Block 3 循环检测器（supervision trio §D2-A，2026-08-27）────────
      // guardBatch 之后、ToolsComplete 之前的单一 choke point——一切 kind 的
      // AgentActor 工具轮都过此环。Root 例外（D3）：depth==0 的 L1 降级为
      // L0 警告（root turn 不自动终止，错误由用户裁决）。save-turn 豁免 S2
      // （guardSaveTurn 自有更严的 10 轮预算，裁定②双治理豁免）。
      loopCfg <- nebflow.core.processor.LoopGuard.loadConfig
      loopEvents = (guardedBatch ++ droppedResults).map { (call, r) =>
        nebflow.core.processor.LoopGuard.RoundEvent(
          toolName = call.name,
          args = Json.fromJsonObject(call.input),
          isError = r.isError,
          errorText = if r.isError then r.content.take(160) else "",
          permissionDenied = r.isError &&
            r.content.startsWith(s"Tool ${call.name} is denied by the session permission policy")
        )
      }
      saveTurnNow = state.pendingCompaction.exists(_.phase == CompactionPhase.Save)
      (loopCounters, loopVerdictRaw) = nebflow.core.processor.LoopGuard.evaluate(
        loopEvents,
        // turnKey = 逻辑 turn 纪元（loopTurnKey）——currentTurnId 是每次 dispatch
        // 都 +1 的序号（wiring 实证：一轮工具 = 一个新 currentTurnId，S1 永不
        // 累计、S3 假命中），不能用。
        state.loopTurnKey.toString,
        state.loopCounters,
        loopCfg,
        s2Exempt = saveTurnNow
      )
      loopVerdict = loopVerdictRaw match
        case t: nebflow.core.processor.LoopGuard.Verdict.Terminate if depth == 0 =>
          NebflowLogger.forName("nebflow.agent").warnSync(
            s"[loop-guard] root agent L1 suppressed (turn NOT auto-terminated): ${t.msg}"
          )
          nebflow.core.processor.LoopGuard.Verdict.Warn(t.msg)
        case v => v
      _ <- loopVerdict match
        case f: nebflow.core.processor.LoopGuard.Verdict.Freeze =>
          // L2：单条 ToolsComplete(freezeAfter)——handler 组装/持久化/计数器写回
          // 后不续轮，转冻结（loopDetected 广播 + 父通知 + enterFrozen(Loop)）。
          // 不用独立的 LoopFreezeDetected 消息：ToolsComplete 链式 dispatch 会
          // 递增 currentTurnId，后续消息 stale 丢弃，冻结永不落地（wiring 实证）。
          ctx.self ! ToolsComplete(
            guardedBatch ++ droppedResults,
            result.text,
            replyTo,
            None,
            result.thinking,
            result.thinkingSignature,
            loopCounters = Some(loopCounters),
            freezeAfter = Some(f.msg)
          )
        case t: nebflow.core.processor.LoopGuard.Verdict.Terminate =>
          // L1：turn 以 LoopDetected 失败——不投 ToolsComplete，走 LlmFailed fatal
          // 链（supervisor notify / team 成员父 ExternalEvent(failed) / Done / 持久化）。
          // 计数器（含 terminatedFps）随 LlmFailed 写回——后续 turn 同 fp 复发
          // → recurrence → L2 冻结。
          ctx.self ! AgentCommand.LlmFailed(LoopDetectedError(t.msg), replyTo, state.currentTurnId, Some(loopCounters))
        case w: nebflow.core.processor.LoopGuard.Verdict.Warn =>
          // L0：正常续轮 + loopReminder（下一轮 user system-reminder 注入，D3）
          ctx.self ! ToolsComplete(
            guardedBatch ++ droppedResults,
            result.text,
            replyTo,
            None,
            result.thinking,
            result.thinkingSignature,
            loopReminder = Some(nebflow.core.processor.LoopGuard.reminderMessage(w)),
            loopCounters = Some(loopCounters)
          )
        case nebflow.core.processor.LoopGuard.Verdict.Pass =>
          ctx.self ! ToolsComplete(
            guardedBatch ++ droppedResults,
            result.text,
            replyTo,
            None,
            result.thinking,
            result.thinkingSignature,
            loopCounters = Some(loopCounters)
          )
      // P0 阶段 3：工具执行完成——touch 活动戳（长工具执行期间流已结束，
      // 若无此 touch 会被误判卡死；下轮 LLM 调用开始会再次 mark Processing）。
      // Block 3：同点镜像 loopStreak/loopRounds（AgentControl list 的 loop×N）。
      _ <- touchRegistryActivity(resources, sessionIdOpt, AgentStatus.Processing) *>
        sessionIdOpt.fold(IO.unit) { sid =>
          resources.agentRegistry.update { m =>
            m.get(sid) match
              case Some(rec) =>
                m.updated(sid, rec.copy(
                  loopStreak = loopCounters.streakCount,
                  loopRounds = loopCounters.roundCount
                ))
              case None => m
          }
        }
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
      // loopCounters 不在此写回——它在 forkTurn 的异步 IO 内计算，行为返回时
      // 尚不可见；经 ToolsComplete.loopCounters 由 handler 应用（见上）。
      val updatedState = state
        .copy(execution = state.execution.copy(turnIdx = nextTurnIdx))
        .withLastDispatch(Some(LastDispatch(isToolExecution = true, Some(result))))
      processing(agentDef, resources, depth, parentRef, updatedState)

  end pipeToolExecutions

  /**
   * P2: permission decision reads the root session's PermissionPolicy bucket
   * (permissionPolicies[rootSessionId]) — dynamic inheritance, NOT the
   * per-agent state.safetyMode copy (D5). Decision order: deny → reject
   * (no card); allow → auto-approve; otherwise reversible-by-safetyMode.
   */
  private enum PermissionDecision:
    case Allow, Deny, Ask

  private def permissionDecision(
    resources: SharedResources,
    state: AgentState,
    call: ToolCall
  ): IO[PermissionDecision] =
    resources.permissionPolicies.get.flatMap { policies =>
      val rootSid = Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
      // F1 (#433): a bucket miss no longer silently falls back to the
      // hardcoded ConfirmEdits default — background agents (Mail-activated
      // team members, flow nodes, restored zombies) resolve the GLOBAL
      // safety mode from nebflow.json `safety.defaultMode` instead, so a
      // user-set global auto-all reaches every session source (I1/I2).
      policies.get(rootSid) match
        case Some(policy) =>
          IO.pure(decide(policy, call))
        case None =>
          val permLogger = nebflow.core.NebflowLogger.forName("nebflow.agent.permissions")
          nebflow.core.GlobalSafety.defaultMode.flatMap { mode =>
            permLogger
              .warn(
                s"permission bucket miss for rootSid=${rootSid.take(8)} — falling back to global safety mode " +
                  s"${nebflow.core.SafetyMode.toString(mode)} (#433 F1)"
              )
              .as(decide(nebflow.agent.PermissionPolicy(safetyMode = mode), call))
          }
    }

  private def decide(policy: PermissionPolicy, call: ToolCall): PermissionDecision =
    if policy.deny.contains(call.name) then PermissionDecision.Deny
    else if policy.allow.contains(call.name) then PermissionDecision.Allow
    else if ToolReversibility.isReversible(call.name, call.input, policy.safetyMode) then PermissionDecision.Allow
    else PermissionDecision.Ask

  private def askUserPermission(
    call: ToolCall,
    state: AgentState,
    permissionDeferredRef: Ref[IO, Option[cats.effect.Deferred[IO, Boolean]]],
    permissionDenialsRef: Ref[IO, Map[String, Int]],
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
              "toolName" -> call.name.asJson,
              "summary" -> summary.asJson,
              "input" -> call.input.asJson,
              "dangerLevel" -> dangerLevel.asJson
            )
          }.flatMap { permJson =>
            // P2: every agent (root or sub-agent) sends the request straight to
            // the InteractionHub — no depth/parentRef relay chain. The hub holds
            // the Deferred, renders the card in the Nebula window and routes the
            // answer back by requestId. The 5-minute timeout stays on the
            // requesting side (hub only routes).
            val sourceAgent = toolCtx.agentDef.map(_.name).getOrElse("unknown")
            val sourceSession = state.sessionId.getOrElse("")
            val rootSessionId =
              Option(state.session.rootSessionId).filter(_.nonEmpty).getOrElse(state.sessionId.getOrElse(""))
            for
              _ <- sendPermissionRequest(toolCtx, state, permJson, deferred, sourceAgent, sourceSession, rootSessionId)
              approvedOpt <- deferred.get
                .map(Some(_))
                .timeoutTo(PermissionTimeout, IO.pure(None))
              _ <- permissionDeferredRef.set(None)
              result <- approvedOpt match
                case Some(approved) =>
                  if approved then executeTool(call, toolCtx)
                  else
                    // #12 劝停（用户 2026-08-20 批准）：第 ≥2 次用户拒绝时注入
                    // system-reminder——对齐 retryableHint 模式，防 LLM 无限重试。
                    // 超时不计数（用户未操作≠拒绝，超时消息自带 re-issue 指引）。
                    permissionDenialsRef.modify { m =>
                      val n = m.getOrElse(call.name, 0) + 1
                      (m.updated(call.name, n), n)
                    }.map(n => ToolExecResult(AgentCore.denialMessage(call.name, n), isError = true))
                case None =>
                  // Timeout — dismiss the permission popup on the frontend
                  // (the card is rendered at sessionId = rootSessionId).
                  state
                    .wsSend(
                      Json.obj(
                        "type" -> "permissionExpired".asJson,
                        "sessionId" -> rootSessionId.asJson
                      )
                    )
                    .handleErrorWith(_ => IO.unit) *>
                    IO.pure(
                      ToolExecResult(
                        s"Permission timed out — no response within ${PermissionTimeout.toMinutes} min, auto-denied. Re-issue the command if needed.",
                        isError = true
                      )
                    )
            yield result
            end for
          }
        )
    }.flatten

  /** Send a permission request to the InteractionHub (P2), or fall back to P1 local render. */
  private def sendPermissionRequest(
    toolCtx: ToolContext,
    state: AgentState,
    permJson: Json,
    deferred: cats.effect.Deferred[IO, Boolean],
    sourceAgent: String,
    sourceSession: String,
    rootSessionId: String
  )(using ctx: ActorContext[AgentCommand]): IO[Unit] =
    // Origin attribution for the permission card: team name (authoritative via
    // TeamSessionRegistry) or flow name (parsed from sessionName "flowName/nodeId"
    // set by FlowDagExecutor, confirmed via agentRegistry kind == Flow so a
    // standalone agent whose sessionName happens to contain '/' is not misread).
    val resourcesOpt = toolCtx.sharedResources
    for
      teamOpt <- nebflow.core.flow.TeamSessionRegistry.teamOfSession(sourceSession)
      recordOpt <- resourcesOpt
        .fold(IO.pure(Option.empty[AgentRecord]))(_.agentRegistry.get.map(_.get(sourceSession)))
      flowOpt = (teamOpt, recordOpt, state.sessionName) match
        case (None, Some(rec), Some(sn)) if rec.kind == AgentKind.Flow && sn.contains("/") =>
          Some(sn.takeWhile(_ != '/'))
        case _ => None
      enrichment =
        val teamFld = teamOpt.map(t => Json.obj("sourceTeam" -> t.asJson)).getOrElse(Json.obj())
        val flowFld = flowOpt.map(f => Json.obj("sourceFlow" -> f.asJson)).getOrElse(Json.obj())
        teamFld.deepMerge(flowFld)
      enriched = permJson.deepMerge(enrichment)
      _ <- resourcesOpt.traverse(_.interactionHubRef.get).map(_.flatten).flatMap {
        case Some(hub) =>
          (hub ! InteractionHubCommand.Request(
            InteractionRequest(
              requestId = java.util.UUID.randomUUID().toString.take(8),
              kind = InteractionKind.Permission,
              payload = enriched,
              reply = InteractionReply.PermissionReply(deferred),
              rootSessionId = rootSessionId,
              sourceAgent = sourceAgent,
              sourceSession = sourceSession
            )
          )).void
        case None =>
          // Hub not spawned (early boot / tests): P1 fallback — the requesting
          // agent holds the Deferred itself and renders locally.
          (ctx.self ! AgentCommand.SetPermissionDeferred(deferred)) *>
            state.wsSend(
              enriched.deepMerge(
                Json.obj(
                  "sessionId" -> state.sessionId.asJson,
                  "sourceAgent" -> sourceAgent.asJson,
                  "sourceSession" -> sourceSession.asJson
                )
              )
            )
      }
    yield ()

    end for

  end sendPermissionRequest

  protected def executeTool(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] =
    // WebSearch P0: defense in depth — the turn loop normally handles kimi's
    // native $web_search before the permission gate; if one ever reaches here
    // (e.g. via the deferred-approval path), echo instead of failing it
    // through the malformed/registry checks below.
    if call.name == nebflow.llm.SearchProviderResolver.KimiWebSearchToolName then
      IO.pure(ToolExecResult(nebflow.llm.SearchProviderResolver.kimiEchoContent(call)))
    else
    // Issue #18: adapters mark tool calls whose arguments JSON could not be
    // parsed (rescued past repair). Executing them would surface a misleading
    // "parameter is required" error — report the parse failure itself instead,
    // with the raw arguments, so the LLM can correct its JSON and retry.
    nebflow.llm.providers.ToolInputJson.malformedDetails(call.input, call.name) match
      case Some(msg) => IO.pure(ToolExecResult(msg, isError = true))
      case None =>
        executeToolInner(call, ctx)

  private def executeToolInner(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] =
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
                    // WebSearch P0: Tier 2 routing — capable provider executes
                    // the search natively (SLA-backed, with provenance); any
                    // miss degrades to the builtin aggregation (Tier 3).
                    val baseCall: IO[Either[ToolError, String]] = tool.call(finalInput, ctx)
                    val routedCall: IO[Either[ToolError, String]] =
                      if call.name == "WebSearch" then routeWebSearchThroughProvider(finalInput, ctx, baseCall)
                      else baseCall
                    routedCall.flatMap {
                      case Left(err) =>
                        hookEngine.afterToolFailure(call.name, finalInput, err.message, hookCtx).map { postResult =>
                          val appended = postResult.additionalContext match
                            case Some(r) => s"${err.message}\n\n$r"
                            case None => err.message
                          ToolExecResult(appended, isError = true)
                        }
                      case Right(result) =>
                        val isFileEdit = call.name == "Edit" || call.name == "Write"
                        val imageBlocks = tool.extractImages(finalInput, result)
                        hookEngine.afterTool(call.name, finalInput, result, true, hookCtx).map { postResult =>
                          val hookSuffix = postResult.additionalContext.getOrElse("")
                          val llmContent = if false then
                            val title = call.input("title").flatMap(_.asString).getOrElse("")
                            s"Card${if title.nonEmpty then s" ($title)" else ""} rendered"
                          else if isFileEdit then nebflow.core.summarizeToolResult(call, result)
                          else result
                          ToolExecResult(
                            llmContent + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else ""),
                            frontendContent = Some(result + (if hookSuffix.nonEmpty then s"\n\n$hookSuffix" else "")),
                            imageBlocks = imageBlocks
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

  /** WebSearch P0: Tier 2 routing for the WebSearch tool call. When the
    * session's provider has native search (zhipu/kimi/qwen — resolved from
    * the agent's model chain head) and the call did NOT pin a specific
    * engine, the search runs on the provider; results carry provenance
    * ("Search source: provider:<id>") and structured URLs. Any miss — no
    * capability, no search evidence, or an execution error — degrades to the
    * builtin aggregation (Tier 3, annotated non-guaranteed). An explicitly
    * requested engine (e.g. engine="arXiv") always goes to the builtin path:
    * the caller asked for THAT engine. */
  private def routeWebSearchThroughProvider(
      input: JsonObject,
      ctx: ToolContext,
      fallback: IO[Either[ToolError, String]]
  ): IO[Either[ToolError, String]] =
    val enginePinned = input("engine").flatMap(_.asString).exists(_.trim.nonEmpty)
    val query = input("query").flatMap(_.asString).getOrElse("")
    if enginePinned || query.isBlank then fallback
    else
      nebflow.llm.SearchProviderResolver
        .executeProviderSearchFor(
          query,
          ctx.llm,
          ctx.sessionId.getOrElse(""),
          ctx.agentDef.map(_.name).getOrElse("-"),
          ctx.agentDef.flatMap(_.model),
          health = ctx.sharedResources.map(_.healthMonitor)
        )
        .flatMap {
          case Some(result) => IO.pure(Right(result))
          case None         => fallback
        }

  protected def buildAllowedToolSet(
    agentDef: AgentDef,
    depth: Int = 0,
    isSubTaskWorker: Boolean = false,
    isFlowNode: Boolean = false,
    isTeamLead: Boolean = false,
    userFacingNode: Boolean = false,
    guardrailsOn: Boolean = false
  ): Set[String] =
    val base = agentDef.tools match
      case Nil => Set.empty[String]
      case List("*") => ToolRegistry.ALL_TOOLS.map(_.name).toSet
      case names => names.toSet
    // Fixed tools are auto-injected based on agent category — they don't
    // need to be listed in agent.json. Mail is team-only; FlowReport is
    // flow-only; all agents get base tools (file ops, search, shell). Issue
    // is Nebula-only (2026-08-25 17:49 裁定 — stripped below via
    // NebulaExclusiveTools even when explicitly listed).
    val withBuiltin = base ++ AgentCore.fixedToolsFor(agentDef)
    // FlowTrigger is whitelist-driven (R1 split, NOT Nebula-exclusive): any
    // agent declaring flows in agent.json gets the tool; everyone else is
    // stripped of it (even via "*" or explicit listing — every call would
    // fail the whitelist check anyway).
    val withFlowTrigger =
      if agentDef.flows.nonEmpty then withBuiltin + "FlowTrigger" else withBuiltin - "FlowTrigger"
    val isNebula = agentDef.name == "Nebula"
    val nebulaFiltered = if isNebula then withFlowTrigger else withFlowTrigger -- NebulaExclusiveTools
    // Team Manager task tools (2026-08-25): mechanism-layer grants — a team
    // lead (Manager) gets the full owner set (create/update/list); Nebula
    // gets the read-only list for oversight (no mutation tools); everyone
    // else gets none (U2: members are not injected at all — the panel is the
    // member-visible surface). The grant is the ONLY source: explicitly
    // listing TeamTask* in agent.json grants nothing (the tool name IS the
    // permission boundary, spec §2.1 — a member sneaking TeamTaskCreate into
    // its tools list must not get it).
    val teamTaskGrant =
      if isTeamLead then AgentCore.TeamTaskTools
      else if isNebula then Set("TeamTaskList")
      else Set.empty[String]
    val withTeamTask = (nebulaFiltered -- AgentCore.TeamTaskTools) ++ teamTaskGrant
    // Block 1 (supervision trio §C2, 2026-08-27): AgentControl mechanism-layer
    // grant — a team lead (Manager) gains subtree-scoped control over its own
    // team (members + their sub-agents; the subtree guard in AgentControlTool
    // enforces the scope), Nebula keeps global authority. The grant is the
    // ONLY source: declaring AgentControl in agent.json grants nothing
    // (TeamTaskTools precedent — the tool name IS the permission boundary).
    val controlGrant = if isNebula || isTeamLead then Set("AgentControl") else Set.empty[String]
    val withControl = (withTeamTask -- Set("AgentControl")) ++ controlGrant
    // Task tools: available to Nebula and Team Lead, NOT workers
    val taskFiltered = agentDef.tools match
      case List("*") =>
        if depth >= 2 then withControl -- (LeadLevelTools ++ AgentCore.TeamTaskTools)
        else withControl
      case _ =>
        withControl
    // MCP tools: agents may use MCP tools from explicitly granted servers
    // (mcpServers) plus their own dedicated agent-scoped servers, which are
    // always auto-allowed. Tool names are mcp__<serverId>__<tool>; dedicated
    // servers use serverId "agent-<agentName>-<serverName>".
    val agentOwnPrefix = s"mcp__agent-${agentDef.name}-"
    val mcpFiltered =
      if agentDef.mcpServers.isEmpty then
        taskFiltered.filter(t => !t.startsWith("mcp__") || t.startsWith(agentOwnPrefix))
      else
        val prefixes = agentDef.mcpServers.map(sid => s"mcp__${sid}__")
        taskFiltered.filter(t =>
          !t.startsWith("mcp__") || t.startsWith(agentOwnPrefix) || prefixes.exists(t.startsWith)
        )
    // SubTask workers are leaf agents: no Mail / no further delegation, no
    // FlowTrigger and no FlowExecute (workers don't trigger pipelines nor
    // open flows — a self-cloned team member would otherwise inherit both
    // via fixedToolsFor). Delegate is Nebula-exclusive (filtered above for
    // everyone else); these strips also defend against a worker whose
    // agent.json explicitly lists the tools.
    // #406: one-shot FlowExecute nodes are leaves too — FlowExecute/
    // FlowTrigger/SubTask/Delegate stripped (recursive flow-in-flow guard).
    // Mail stays for team-category nodes (they may Mail the caller's team).
    // Team Manager task tools (#D, 2026-08-25): workers/flow nodes never
    // mutate or even read the team task board — a SubTask worker self-cloned
    // from a Manager would otherwise inherit TeamTask* via the isTeamLead
    // grant. Flow-node stripping is generic leaf isolation (U5 裁定: 安全
    // 隔离, not a flow↔task coupling design).
    val categoryFiltered =
      if isSubTaskWorker then
        mcpFiltered -- (Set("Mail", "SubTask", "Delegate", "FlowTrigger", "FlowExecute") ++ AgentCore.TeamTaskTools)
      else if isFlowNode then
        val leafStripped = mcpFiltered -- (Set("FlowExecute", "FlowTrigger", "SubTask", "Delegate") ++ AgentCore.TeamTaskTools)
        // 轨道二 #5（专用化护栏，设计 §C1）：T1 flow worker 默认剥离面向用户
        // 的展示类工具——引擎级剥离而非提示词恳求（deck-v6 实证：提示词约束在
        // 错位人设下会被推翻）。即使 agent.json 显式声明也扣掉；userFacing:true
        // 节点（白名单）豁免。策略=「写文件给下游」≠「Pop 给用户」。
        if guardrailsOn && !userFacingNode then leafStripped -- nebflow.core.Guardrails.FlowWorkerStrippedTools
        else leafStripped
      // Flow agents have no Mail — flow nodes report via FlowReport, not Mail.
      // Structurally defends against the 08-14 P0 root cause: a flow agent
      // whose agent.json lists Mail (or uses "*") could block forever on a
      // Mail ask (flow callers cannot receive background notifications).
      else if agentDef.category == "flow" then mcpFiltered - "Mail"
      else mcpFiltered
    categoryFiltered

  end buildAllowedToolSet

  protected def buildToolList(
    agentDef: AgentDef,
    depth: Int = 0,
    isSubTaskWorker: Boolean = false,
    isFlowNode: Boolean = false,
    isTeamLead: Boolean = false,
    userFacingNode: Boolean = false,
    guardrailsOn: Boolean = false
  ): Option[List[ToolDefinition]] =
    val allowedSet = buildAllowedToolSet(agentDef, depth, isSubTaskWorker, isFlowNode, isTeamLead, userFacingNode, guardrailsOn)
    Some(ToolRegistry.ALL_TOOLS.flatMap { td =>
      if !allowedSet.contains(td.name) then None
      // R8-P1: flow node agents get their per-node contract (verdict enum +
      // slots schema) appended to the FlowReport description, so the agent
      // knows its allowed values BEFORE the first call instead of discovering
      // them via ToolError round-trips.
      else if td.name == "FlowReport" then
        agentDef.flowContract match
          case Some(contract) => Some(td.copy(description = td.description + "\n\n" + contract.describe))
          case None           => Some(td)
      else Some(td)
    })

  /**
   * Save-phase compaction tools (compaction burn-down, 2026-08-18). The save
   * turn exists to run the memory maintenance cycle — Write/Edit on the
   * memory files, Read for the VERIFY step — but the FULL toolset on a weak
   * default model turns into open-ended exploration (5min+
   * without compactComplete; qa-mini with NO tools finished in 110s). Restrict
   * to the memory-maintenance essentials: nothing that can branch outward
   * (no Bash/Grep/Glob/WebSearch). #438 note: the whitelist tools are
   * mechanism-guaranteed (see impl) — they no longer rely on the six being
   * present in the agent's own toolset.
   */
  protected def saveTurnTools(
      agentDef: AgentDef,
      depth: Int = 0,
      isSubTaskWorker: Boolean = false,
      isFlowNode: Boolean = false,
      isTeamLead: Boolean = false
  ): Option[List[ToolDefinition]] =
    // #438: the save phase is system machinery (memory maintenance), not an
    // agent-capability turn — its [Write, Edit, Read] whitelist is guaranteed
    // at the mechanism layer, independent of the agent's configured tools.
    // 2026-08-28: the six are mechanism-fixed for ALL agents again (user
    // ruling, reverses #438) — the guarantee below is now idempotent with
    // fixedToolsFor, kept as belt-and-suspenders against config anomalies
    // (e.g. seeds-fallback defs, SaveTurnGuardSpec P0-1).
    val allowed = buildToolList(agentDef, depth, isSubTaskWorker, isFlowNode, isTeamLead)
      .getOrElse(Nil)
      .filter(td => SaveTurnToolWhitelist.contains(td.name))
    val guaranteed = ToolRegistry.ALL_TOOLS.filter(td => SaveTurnToolWhitelist.contains(td.name))
    Some((allowed ++ guaranteed).distinctBy(_.name))

  private val SaveTurnToolWhitelist: Set[String] = Set("Write", "Edit", "Read")

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
        NebflowLogger.forName("nebflow.agent").warn(s"emitStream($eventName) failed: ${e.getMessage}")
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
      // ── WS event batching for TextDelta ──────────────────────────────
      // Accumulate text deltas and flush in batches (max 50 deltas or 50ms
      // window). A 1000-token LLM response produces 500-1000 TextDelta chunks;
      // batching reduces WS JSON construction + send from ~800 to ~16 calls.
      // Non-TextDelta events (ToolCall, Thinking, Done) trigger an immediate
      // flush first so event ordering is preserved on the frontend.
      //
      // Thinking deltas are batched the same way (P0-1/P0-2):
      // - sub-agent `agentThinking` carries no payload — all markers in a
      //   window collapse into a single event (pure noise reduction; thinking
      //   phase used to emit 200-1000 WS frames/s/agent)
      // - main-session `thinkingDelta` carries text — deltas in a window are
      //   concatenated into one frame; the frontend rAF-throttles rendering,
      //   so the coarser frames are imperceptible
      //
      // Mutable state is local to this pipe invocation — each agent's stream
      // creates its own accumulators, and fs2 processes elements sequentially.
      val textBuf = new StringBuilder(512)
      var textCount = 0
      val thinkingBuf = new StringBuilder(256)
      var thinkingCount = 0
      var lastFlushMs = System.currentTimeMillis()
      val MaxBatch = 50
      val FlushWindowMs = 50L

      def flushText(): IO[Unit] =
        if textBuf.isEmpty then IO.unit
        else
          val delta = textBuf.toString
          textBuf.setLength(0)
          textCount = 0
          lastFlushMs = System.currentTimeMillis()
          val json =
            if isAskMode then
              Json.obj("type" -> "askTextDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
            else if isSubagent then AgentStreamEvent.TextDelta(delta).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          wsSend(json)

      def flushThinking(): IO[Unit] =
        if thinkingCount == 0 then IO.unit
        else
          val delta = thinkingBuf.toString
          val json =
            if isSubagent then
              // Sub-agents emit agentThinking with a delta field. routeWsSend
              // stamps nodeSessionId; ws.js convertAgentEvent maps agentThinking
              // → thinkingDelta with sessionId = nodeSessionId so the popup view
              // renders the reasoning bubble.
              Json.obj(
                "type" -> "agentThinking".asJson,
                "agentId" -> ctx.self.path.name.asJson,
                "delta" -> delta.asJson,
                "nodeSessionId" -> sessionId.asJson
              )
            else
              Json.obj("type" -> "thinkingDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> delta.asJson)
          thinkingBuf.setLength(0)
          thinkingCount = 0
          lastFlushMs = System.currentTimeMillis()
          wsSend(json)

      // Thinking chunks precede text chunks in an LLM stream; when a window
      // straddles the phase boundary the thinking buffer holds earlier content,
      // so flush thinking before text to preserve event order.
      def flushAll(): IO[Unit] = flushThinking() *> flushText()

      stream.evalTap {
        case StreamChunk.TextDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          textBuf.append(delta)
          textCount += 1
          if textCount >= MaxBatch || System.currentTimeMillis() - lastFlushMs >= FlushWindowMs then flushAll()
          else IO.unit

        // Batch thinking instead of one frame per chunk.
        case StreamChunk.ThinkingDelta(delta) if delta.nonEmpty && !isCompactTurn =>
          // Both main and sub-agent thinking enters the buffer; flush sends
          // thinkingDelta with the session's sessionId so popup views render it.
          thinkingBuf.append(delta)
          thinkingCount += 1
          if thinkingCount >= MaxBatch || System.currentTimeMillis() - lastFlushMs >= FlushWindowMs then flushAll()
          else IO.unit

        case StreamChunk.ToolCallStart(name) if name != "AskUserQuestion" && !isCompactTurn =>
          val json =
            if isSubagent then AgentStreamEvent.ToolCallDetected(name).toJson(ctx.self.path.name, true, None)
            else Json.obj("type" -> "toolCallDetected".asJson, "sessionId" -> sessionId.asJson, "name" -> name.asJson)
          flushAll() *> wsSend(json)

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
          flushAll() *> wsSend(json)

        case StreamChunk.ToolArgDelta(toolName, delta) if delta.nonEmpty && !isCompactTurn && !isSubagent =>
          val json = Json.obj(
            "type" -> "toolArgDelta".asJson,
            "sessionId" -> sessionId.asJson,
            "toolName" -> toolName.asJson,
            "delta" -> delta.asJson
          )
          flushAll() *> wsSend(json)

        // Stream end: flush any remaining buffered text/thinking so no deltas are lost.
        case StreamChunk.Done(_, _, _, _) => flushAll()

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
    // SubTask workers inherit the parent's system.md for domain knowledge but
    // team interaction content is stripped (no team / no Mail / no reporting).
    val base = if ctx.isSubTaskWorker then SubTaskPrompt.stripTeamContent(rawPrompt) else rawPrompt
    val cleanedPrompt = PromptSections.stripAllMigrated(base)
    val conditionalBlocks = PromptSections.buildConditionalBlocks(ctx)
    PromptSections.assembleSystemPrompt(systemPrefix, cleanedPrompt, conditionalBlocks)

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

  /**
   * P0 阶段 3（2026-08-18，设计 §4.4）：更新 agentRegistry 的活动快照。
   * TaskStuckWatcher 读 AgentRecord.status + lastActivityMs 判定卡死——
   * 本 helper 是这两个字段的主要写入点（注册点默认值除外）：
   *   - Processing：AgentCore.pipeLlmCall（LLM 调用开始 + 每个流 chunk）
   *   - Idle：AgentActor.finishTurnCont 回 idle 分支（turn 完成）
   * 补充写入点（#319，2026-08-19）：BashTool.startActivityBridge 在前台命令
   * 有进展（输出/CPU/sleep）时也会刷新 lastActivityMs——防止长前台命令被
   * TaskStuckWatcher 误判卡死。仅 touch 时间戳，不改变 status。
   * 幂等且仅在记录存在时更新（registry 无该 session 时 no-op——不创建幽灵条目）。
   */
  protected def touchRegistryActivity(
    resources: SharedResources,
    sessionId: Option[String],
    status: AgentStatus,
    now: Long = System.currentTimeMillis()
  ): IO[Unit] =
    sessionId.fold(IO.unit) { sid =>
      resources.agentRegistry.modify { m =>
        m.get(sid) match
          case Some(rec) => (m.updated(sid, rec.copy(status = status, lastActivityMs = now)), ())
          case None => (m, ())
      }
    }

end AgentCore

object AgentCore:

  /**
   * #12 劝停: error text for the n-th user denial of `toolName` within one
   * turn. First denial stays minimal; from the second on, inject a
   * system-reminder (retryableHint pattern) so the LLM changes approach
   * instead of re-asking endlessly. Timeout does NOT count — a user who
   * never saw the card is not a denial.
   */
  def denialMessage(toolName: String, n: Int): String =
    if n >= 2 then
      s"Permission denied by user ('$toolName' denied $n times this turn)." +
        "<system-reminder>The user has denied this tool " + n.toString + " times in this turn. " +
        "Repeating the same request will keep being denied. Change the approach, " +
        "ask the user what they want via AskUserQuestion, or report the blocker " +
        "instead of retrying.</system-reminder>"
    else "Permission denied by user"

  /**
   * Nebula-exclusive tools: only available when agentName == "Nebula".
   * - Schedule: session-scoped scheduled tasks
   * - Delegate: 调度器/根 agent 专用——指派 standalone agent。
   *   Team 成员委派走 SubTaskTool（self-clone + ephemeral）。
   *   Flow 触发不在此列——FlowTrigger 由 agent.json flows 白名单驱动注入。
   * - AgentControl: 后台 agent 管控（list/status/cancel/restart，spec §4 安全
   *   边界矩阵——危险能力只交给根调度者）。
   * - Issue: 系统反馈收集仅编排者持有（user ruling 2026-08-25 17:49 工具体系
   *   精简）——非 Nebula agent 声明了也不给（GitHub issue 上报是编排层职责，
   *   worker 的系统性问题走 Mail 上报 Manager/Nebula 转达）。
   */
  val NebulaExclusiveTools = Set(
    "Schedule",
    "Delegate",
    "AgentControl",
    "Issue"
  )

  /** Nebula 的 9 个编排工具（user ruling 2026-08-25 17:49：Nebula 系统固定
    * 为 9 个编排工具）。机制层固定注入——不依赖 agent.json 声明（防面板编辑
    * 误删导致调度器失能），同时也意味着非 Nebula agent 声明这些工具中的
    * Nebula 专属项无效。六件基础工具同样是机制固定（2026-08-28 00:55 用户
    * 裁定，见 BaseTools）。 */
  val NebulaOrchestrationTools = Set(
    "AgentControl",
    "TaskUpdate",
    "Delegate",
    "Pop",
    "AskUserQuestion",
    "TaskCreate",
    "Mail",
    "Schedule",
    "TransferFile"
  )

  /** Team Manager task tools (2026-08-25 team-manager-task-tool): granted to
    * team leads (Manager owner — all three) and Nebula (TeamTaskList only,
    * read-only oversight); stripped from SubTask workers / flow nodes /
    * depth≥2 "*" agents (same LeadLevelTools treatment). */
  val TeamTaskTools = Set("TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList")

  /** Tools available to Nebula and Team Leads, but NOT workers. */
  val LeadLevelTools = Set("TaskCreate", "TaskUpdate")

  /**
   * Base tools always available to ALL agents regardless of category.
   * These are injected automatically — agent.json does not need to list them.
   *
   * Issue was removed (user ruling 2026-08-25 17:49 工具体系精简): system
   * feedback collection is orchestrator-only — see NebulaExclusiveTools.
   */
  val BaseTools = Set(
    "Read",
    "Write",
    "Edit",
    "Glob",
    "Grep",
    "Bash"
  )

  /**
   * Fixed tools for a given agent: base tools plus category-specific tools.
   * These are auto-injected and should NOT be stored in agent.json.
   *
   * - ALL agents (including Nebula, 2026-08-28 00:55 用户裁定「6类工具还是
   *   作为统一的agent都有的工具」— reverses #438): BaseTools (Read/Write/
   *   Edit/Glob/Grep/Bash) are mechanism-fixed for every category. agent.json
   *   tools declarations remain an additional source and coexist idempotently
   *   (Set semantics — duplicates harmless). Nebula's file declaration of the
   *   six (8684acd) stays as a belt-and-suspenders no-op.
   * - Team agents: BaseTools + Mail + SubTask + FlowExecute (user ruling
   *   2026-08-24: team members get SubTask at the mechanism layer — relying
   *   on manual agent.json declarations is error-prone; html-deck-studio
   *   missed it for all four members. #406 extends the same mechanism-layer
   *   injection to FlowExecute: one-shot dynamic flows are a default team
   *   capability, no agent.json declaration needed). SubTask workers and
   *   FlowExecute nodes are still stripped of it downstream (isSubTaskWorker
   *   / isFlowNode leaf rules in buildAllowedToolSet).
   * - Nebula (root orchestrator): BaseTools + Issue + FlowExecute +
   *   NebulaOrchestrationTools (#404/2026-08-25 17:49 ruling, unchanged;
   *   the #438 "six are Nebula's configurable region" semantics was
   *   reversed by user ruling 2026-08-28 00:55 — the six are uniformly
   *   mechanism-fixed again). The root agent dynamically creates flows too.
   * - Flow agents: BaseTools + FlowReport (no Mail, no SubTask — flow nodes
   *   are leaves; FlowReport is injected by execution context for dynamic
   *   flows, see FlowDagExecutor.executeNode)
   * - Standalone agents: BaseTools only (no Mail, no SubTask)
   */
  def fixedToolsFor(agentDef: AgentDef): Set[String] =
    agentDef.category match
      case "team" => BaseTools + "Mail" + "SubTask" + "FlowExecute"
      case "flow" => BaseTools + "FlowReport"
      case _ if agentDef.name == "Nebula" =>
        // 2026-08-28 00:55 用户裁定：六件工具作为所有 agent 统一拥有的机制
        // 固定工具（反转 #438 的 Nebula 默认不配）。agent.json 声明保留为
        // 额外来源（幂等共存）；文件声明兜底（8684acd）不回滚。
        BaseTools ++ Set("Issue", "FlowExecute") ++ NebulaOrchestrationTools
      case _ => BaseTools

end AgentCore
