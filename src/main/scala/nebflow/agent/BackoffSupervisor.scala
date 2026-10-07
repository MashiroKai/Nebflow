package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * Supervises a child agent actor. On crash (fiber termination), restarts the
 * child with exponential backoff. Mirrors Pekko's BackoffSupervisor pattern
 * but built on Nebflow's self-contained Actor framework.
 *
 * The supervisor is an enhanced adapter: it receives AgentEvent messages from
 * the child (via replyTo), forwards completion/failure to the parent, and
 * auto-restarts the child on unexpected termination (crash).
 *
 * - Watches the pre-spawned child via death watch
 * - On Completed/Failed: forwards ExternalEvent to parent, cleans up (like adapter)
 * - On `Terminated` (crash): waits backoff, respawns child, re-injects prompt
 * - After maxRestarts exceeded: notifies parent via non-retryable ExternalEvent
 *
 * Circuit breaker: if maxRestarts are exceeded within withinTimeRange, the
 * supervisor gives up and notifies the parent agent.
 */
object BackoffSupervisor:

  private val logger = NebflowLogger.forName("nebflow.agent.supervisor")

  /**
   * @param childRef    the pre-spawned child actor (initial spawn done by caller)
   * @param childSpawnFn used to re-spawn the child on restart; receives the
   *                     recovered messages (if any) so the child can resume
   *                     from the crash checkpoint instead of starting fresh
   * @param childName   child actor name (for logging)
   * @param parentRef   parent agent to notify on completion / failure
   * @param description human-readable task description
   * @param agentName   child agent name
   * @param subagentId  session id
   * @param parentSessionId the parent (root) session that owns this task —
   *                        SubAgentTaskStore keys task files by it; must match
   *                        the id used at recordTask time or status updates
   *                        silently write nowhere
   * @param resources   shared resources (for agentRegistry cleanup + session
   *                    store to recover persisted messages on restart)
   * @param initialPrompt the original prompt to re-inject after restart (used
   *                      as fallback when no persisted messages are found)
   * @param source       ExternalEvent source string ("delegate" or "subtask")
   * @param extraMetadata extra metadata for ExternalEvent (e.g. "kind" -> "SubTask")
   */
  def apply(
    childRef: ActorRef[AgentCommand],
    childSpawnFn: (ActorSystem, List[Message]) => IO[ActorRef[AgentCommand]],
    childName: String,
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    parentSessionId: String,
    resources: SharedResources,
    initialPrompt: String,
    source: String,
    extraMetadata: JsonObject = JsonObject.empty,
    wsSend: Option[Json => IO[Unit]] = None,
    minBackoff: FiniteDuration = 5.seconds,
    maxBackoff: FiniteDuration = 60.seconds,
    maxRestarts: Int = 2,
    withinTimeRange: FiniteDuration = 5.minutes
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      // R11 第 4 层（作者裁定 U1=C-a / U8=(ii)）：**仅 Delegate 轨**装配 3600s
      // wall-clock 预算（SubTask/其它 source 零行为变化）。超时走既有 cancel 链
      // ——向本监督者投 AgentEvent.Cancelled(reason="timeout")，其余（父通知 /
      // barrier 释放 / registry 清理 / child Stop）全部复用既有终态路径。
      // 计时生命周期：register（此处）→ pause/resume（ask 两个既有单点）→
      // release（notifyParentAndStop / respawn）。
      val budgetArmedIO: IO[Unit] =
        if source == "delegate" then
          DelegateBudget.register(subagentId)(ctx.self ! AgentEvent.Cancelled(subagentId, "timeout"))
        else IO.unit
      budgetArmedIO *> ctx.watch(childRef) *>
        logger
          .info(s"BackoffSupervisor: watching $childName for crash recovery (maxRestarts=$maxRestarts)")
          .as(
            active(
              childRef,
              childSpawnFn,
              childName,
              parentRef,
              description,
              agentName,
              subagentId,
              parentSessionId,
              resources,
              initialPrompt,
              source,
              extraMetadata,
              wsSend,
              restartCount = 0,
              restartHistory = Nil,
              minBackoff,
              maxBackoff,
              maxRestarts,
              withinTimeRange
            )
          )
    }

  /** Active state: forwards completion/failure to parent, restarts on Terminated. */
  private def active(
    childRef: ActorRef[AgentCommand],
    childSpawnFn: (ActorSystem, List[Message]) => IO[ActorRef[AgentCommand]],
    childName: String,
    parentRef: Option[ActorRef[AgentCommand]],
    description: String,
    agentName: String,
    subagentId: String,
    parentSessionId: String,
    resources: SharedResources,
    initialPrompt: String,
    source: String,
    extraMetadata: JsonObject,
    wsSend: Option[Json => IO[Unit]],
    restartCount: Int,
    restartHistory: List[Long],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    maxRestarts: Int,
    withinTimeRange: FiniteDuration
  ): Behavior[AgentEvent] =
    new Behavior[AgentEvent]:
      def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
        // 四修①（2026-10-07 修复令「空轮≠完成」）的正常完成体（空轮判据不中时
        // 原样走——逐字保持既有行为）。
        def completeNormally(messages: List[Message]): IO[Behavior[AgentEvent]] =
          val text = extractLastAssistantText(messages)
          // trim guard: a whitespace-only tail ("\n\n") is NOT a real output —
          // render "(no text output)" instead of an empty-shell payload
          val payload =
            if text.trim.nonEmpty then s""""$description":\n${text.trim}"""
            else s""""$description" (no text output)"""
          // worktree-judge-fifo 批（#1224）：delegate 轨的 Completed 先过终态
          // 判定机（三态判词）——completed/blocked 照常报父（payload 携带判词
          // 行）；ready-to-merge **停靠**：不报父、不停 actor（合并轮需要
          // delegate 活着自执行合并），预算重挂合并窗新 3600s，超时走下方既有
          // Cancelled 链兜底。subtask 轨零变化。四修②：全会话工具调用计数随行
          // ——终态确定性预检（resultText 空＋零工具 ⇒ 直接判未完成）的输入。
          if source == "delegate" then
            nebflow.core.delegate.DelegateTerminal
              .onCompleted(
                resources = resources,
                delegateId = subagentId,
                parentSessionId = parentSessionId,
                description = description,
                initialPrompt = initialPrompt,
                resultText = text,
                childRef = childRef,
                supervisorRef = ctx.self,
                sessionToolCalls = Some(sessionToolCalls(messages))
              )
              .attempt
              .flatMap {
                case Right(nebflow.core.delegate.DelegateTerminal.Outcome.Release(suffix)) =>
                  notifyParentAndStop("completed", payload + suffix, JsonObject.empty)
                case Right(nebflow.core.delegate.DelegateTerminal.Outcome.HoldForMerge) =>
                  // 合并窗预算重挂（旧窗口随工作相终结释放；DelegateBudget ∈
                  // agent 层——core 钩子只回 Outcome，重挂留在本层，分层纪律）。
                  DelegateBudget
                    .release(subagentId)
                    .*>(DelegateBudget.register(subagentId)(ctx.self ! AgentEvent.Cancelled(subagentId, "merge-window-timeout")))
                    .*>(logger.info(s"BackoffSupervisor: delegate $subagentId held for merge round (fresh merge-window budget armed)"))
                    .as(
                      active(
                        childRef,
                        childSpawnFn,
                        childName,
                        parentRef,
                        description,
                        agentName,
                        subagentId,
                        parentSessionId,
                        resources,
                        initialPrompt,
                        source,
                        extraMetadata,
                        wsSend,
                        restartCount,
                        restartHistory,
                        minBackoff,
                        maxBackoff,
                        maxRestarts,
                        withinTimeRange
                      )
                    )
                case Left(err) =>
                  // 判定钩子自身缺陷绝不吞终态：按既有完成语义报父（fail-open）。
                  logger.warn(s"BackoffSupervisor: terminal judge hook failed for $subagentId: ${err.getMessage} — legacy completed path")
                  notifyParentAndStop("completed", payload, JsonObject.empty)
              }
          else notifyParentAndStop("completed", payload, JsonObject.empty)

        event match
          case AgentEvent.Completed(_, messages) =>
            // 四修①两级处置（作者令「有重启预算→自动重启续推；预算耗尽→终判
            // blocked」）：全会话零工具调用＋（终文空 或 止于 max_tokens 空烧
            // stub）= 空烧形态，NOT a completion。
            //   - 预算未尽 → 走既有 respawn 链（断点续跑＋continue 注入＋
            //     subagentRetry WS 帧＋"restarting" 库态——maxRestarts=2 在挂）；
            //   - 预算耗尽 → delegate 轨照常过终态判定机：确定性预检
            //     （DelegateTerminal，四修②）据「零工具＋空文」直接判 blocked，
            //     不依赖 LLM 判；subtask 轨 failed 终态（failureType=empty-burn）。
            if !isEmptyBurnCompletion(messages) then completeNormally(messages)
            else
              val now = System.currentTimeMillis()
              val recent = restartHistory.filter(t => now - t < withinTimeRange.toMillis)
              if recent.length < maxRestarts then
                logger.warn(
                  s"BackoffSupervisor: child $childName completed EMPTY (no text, zero tool calls — empty burn), " +
                    s"restarting #${recent.length + 1}/$maxRestarts instead of reporting completion"
                )
                respawn(ctx, "empty-completion", now, recent)
              else if source == "delegate" then
                logger.warn(
                  s"BackoffSupervisor: child $childName completed EMPTY with restart budget exhausted ($maxRestarts) — terminal verdict left to the deterministic precheck (blocked)"
                )
                completeNormally(messages)
              else
                logger.warn(
                  s"BackoffSupervisor: child $childName completed EMPTY with restart budget exhausted ($maxRestarts) — honest failed terminal"
                )
                notifyParentAndStop(
                  "failed",
                  s""""$description": empty completion — the worker produced no text and made no tool calls (empty burn), unresolved after $maxRestarts restarts""",
                  JsonObject(
                    "failedSessionId" -> subagentId.asJson,
                    "retryable" -> false.asJson,
                    "failureType" -> "empty-burn".asJson
                  )
                )

          case AgentEvent.Failed(sessionId, error) =>
            val sessionInfo =
              if sessionId.nonEmpty then s" [session=$sessionId]" else ""
            val retryable = error.errorType match
              case AgentErrorType.LlmFailed => true
              case AgentErrorType.Timeout => true
              case _ => false
            notifyParentAndStop(
              "failed",
              s""""$description": ${error.message}$sessionInfo""",
              JsonObject(
                "failedSessionId" -> subagentId.asJson,
                "retryable" -> retryable.asJson,
                "failureType" -> error.errorType.toString.asJson
              )
            )

          case AgentEvent.Cancelled(_, reason) =>
            // AgentControl cancel（spec §3.2）：与自然终态同路清理——父
            // ExternalEvent(source 保持 delegate/subtask → barrier 正确释放)
            // + taskStore cancelled + registry 移除 + child Stop + 自停。
            // 与 Completed/Failed 竞态：mailbox 串行，首个终态胜出，supervisor
            // 停止后其余消息被丢弃（actor 语义），无双重通知。
            val reasonSuffix = if reason.nonEmpty then s" — $reason" else ""
            notifyParentAndStop(
              "cancelled",
              s""""$description": cancelled by Nebula via AgentControl$reasonSuffix""",
              JsonObject(
                "failedSessionId" -> subagentId.asJson,
                "retryable" -> false.asJson,
                "failureType" -> "cancelled".asJson,
                "cancelled" -> true.asJson,
                "reason" -> reason.asJson
              ),
              taskStatusOverride = Some("cancelled")
            )

      override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
        signal match
          case SystemSignal.Terminated(_) =>
            val now = System.currentTimeMillis()
            val recentRestarts = restartHistory.filter(t => now - t < withinTimeRange.toMillis)

            if recentRestarts.length < maxRestarts then
              logger.info(s"BackoffSupervisor: child $childName crashed, restarting #${recentRestarts.length + 1}/$maxRestarts")
              respawn(ctx, "crashed", now, recentRestarts)
            else
              logger.warn(
                s"BackoffSupervisor: child $childName exceeded maxRestarts " +
                  s"($maxRestarts in ${withinTimeRange}), giving up"
              ) *>
                notifyParentAndStop(
                  "failed",
                  s""""$description": agent crashed and could not recover after $maxRestarts restarts""",
                  JsonObject(
                    "failedSessionId" -> subagentId.asJson,
                    "retryable" -> false.asJson,
                    "failureType" -> "supervision-exhausted".asJson
                  )
                )
            end if

      /**
       * 共享 respawn 链（四修① 提取；原 Terminated 腿逐字迁移，行为保持）：
       * 崩溃腿（trigger="crashed"）与空烧续推腿（trigger="empty-completion"，
       * 四修①）共用——指数退避＋断点续跑＋registry 回写＋continue 注入＋预算
       * 重挂，仅日志/审计措辞与续推 nudge 文案随 trigger 分叉。
       *
       * @param trigger "crashed" | "empty-completion"（四修① 空轮≠完成的续推腿）
       */
      private def respawn(
        ctx: ActorContext[AgentEvent],
        trigger: String,
        now: Long,
        recentRestarts: List[Long]
      ): IO[Behavior[AgentEvent]] =
        // Phase 3 去重(行为保持重构,2026-09-25):原三行手写延迟计算
        // (min(minBackoff×2^n, maxBackoff) + 抖动[0,1000)ms)收口为
        // restartDelayMs 纯函数——指数+封顶复用 shared Retry.delayAfter,
        // 事后抖动留站点。delay 语义/单位(ms)不变。
        val delay = restartDelayMs(recentRestarts.length, minBackoff, maxBackoff)
        val newRestartCount = restartCount + 1
        val newHistory = now :: recentRestarts

        val triggerLine =
          if trigger == "crashed" then s"child $childName crashed, restarting #$newRestartCount/$maxRestarts after ${delay}ms backoff"
          else s"child $childName ended empty (no text, zero tool calls — empty burn), pushing again #$newRestartCount/$maxRestarts after ${delay}ms backoff"
        val nudgeText =
          if trigger == "crashed" then
            "[system] Your previous turn was interrupted by a crash. " +
              "Please continue your task from where you left off."
          else
            "[system] Your previous turn ended with NO output and NO tool calls " +
              "(the output budget was likely burned on thinking). Continue the task now: " +
              "use tools to make real progress and produce the deliverable as text."

        // P3.3: emit observability WS event so the frontend can show
        // "sub-agent restarting (1/2)…" in the UI.
        val retryEvent = Json.obj(
          "type" -> "subagentRetry".asJson,
          "agentName" -> agentName.asJson,
          "childSessionId" -> subagentId.asJson,
          "restartCount" -> newRestartCount.asJson,
          "maxRestarts" -> maxRestarts.asJson,
          "backoffMs" -> delay.asJson,
          "description" -> description.asJson
        )
        val notifyIO = wsSend match
          case Some(send) =>
            send(retryEvent).handleErrorWith(e => logger.warn(s"subagentRetry WS event failed: ${e.getMessage}"))
          case None => IO.unit

        for
          _ <- logger.info(s"BackoffSupervisor: $triggerLine")
          _ <- notifyIO
          _ <- resources.subAgentTaskStore
            .updateStatus(
              parentSessionId,
              subagentId,
              "restarting",
              retryCount = Some(newRestartCount)
            )
            .handleErrorWith(e => logger.warn(s"subAgentTaskStore update failed: ${e.getMessage}"))
          _ <- IO.sleep(delay.millis)
          // Spec C2 (restart): registry 记录跨 respawn 刷新——先取崩溃前
          // 记录，spawn 后以新 ref 回写。缺了这步 respawn 的 child 会从
          // agentRegistry 消失（AgentControl list / TaskStuckWatcher 全盲）。
          // 本设计新增项（R2-c §2.3 / §7#4）：崩溃前的槽位清理——旧内核的
          // pending ask 卡是僵尸卡（答了也没人接），respawn 后新 child 会
          // 重新提问（新 requestId）。清理 = 移除全部 sourceSession 命中的
          // hub 槽 + 广播 askUserClosed（前端待办条行消失，重连不再重放）。
          _ <- cleanupPendingAsks(resources, subagentId)
          // 预算：旧通道释放（respawn 视为一次新的执行窗口 → 重新计时）。
          _ <- DelegateBudget.release(subagentId)
          oldRecord <- resources.agentRegistry.get.map(_.get(subagentId))
          // Crash recovery: load persisted messages from the session store
          // so the child can resume from where it left off (断点续跑).
          // If no messages are found (e.g. first turn crashed before any
          // persist), fall back to re-injecting the original prompt.
          recoveredMessages <- resources.sessionStore
            .loadMessagesForSession(subagentId)
            .handleErrorWith(e =>
              logger.warn(s"BackoffSupervisor: failed to load messages for $subagentId: ${e.getMessage}").as(Nil)
            )
          newChild <- childSpawnFn(ctx.system, recoveredMessages)
          _ <- ctx.watch(newChild)
          // Spec C2: 回写 registry（新 ref + supervisor 指向自己 + 活动时
          // 间刷新）。无旧记录（ghost respawn）时按 source 重建最小条目。
          _ <- resources.agentRegistry.update { registry =>
            val fallback = AgentRecord(
              sessionId = subagentId,
              ref = newChild,
              kind = if source == "subtask" then AgentKind.SubTask else AgentKind.Delegate,
              rootSessionId = parentSessionId,
              supervisorRef = Some(ctx.self),
              parentSessionId = parentSessionId
            )
            val refreshed = oldRecord
              .getOrElse(fallback)
              .copy(
                ref = newChild,
                supervisorRef = Some(ctx.self),
                status = AgentStatus.Idle,
                lastActivityMs = System.currentTimeMillis()
              )
            registry.updated(subagentId, refreshed)
          }
          // If we recovered messages, send a "continue" instruction so the
          // child picks up where it left off (crash: plain continue; empty
          // burn: the four-fix ① nudge — act with tools, produce output).
          // Otherwise re-inject the original prompt (fresh start fallback).
          _ <-
            if recoveredMessages.nonEmpty then
              newChild ! AgentCommand.UserInput(nudgeText, Some(ctx.self))
            else newChild ! AgentCommand.UserInput(initialPrompt, Some(ctx.self))
          _ <- logger.info(
            s"BackoffSupervisor: respawned $childName ($trigger), " +
              s"recovered ${recoveredMessages.size} messages" +
              (if recoveredMessages.nonEmpty then " (resuming from checkpoint)"
               else " (re-injected original prompt)")
          )
          // respawn 后重新装配预算（Delegate 轨）——上一条已在释放时退出。
          _ <-
            if source == "delegate" then
              DelegateBudget.register(subagentId)(ctx.self ! AgentEvent.Cancelled(subagentId, "timeout"))
            else IO.unit
        yield active(
          newChild,
          childSpawnFn,
          childName,
          parentRef,
          description,
          agentName,
          subagentId,
          parentSessionId,
          resources,
          initialPrompt,
          source,
          extraMetadata,
          wsSend,
          newRestartCount,
          newHistory,
          minBackoff,
          maxBackoff,
          maxRestarts,
          withinTimeRange
        )
        end for
      end respawn

      /** Notify parent via ExternalEvent, clean up registry, stop self. */
      private def notifyParentAndStop(
        eventType: String,
        payload: String,
        failureMetadata: JsonObject,
        taskStatusOverride: Option[String] = None
      ): IO[Behavior[AgentEvent]] =
        val metadata = JsonObject(
          "description" -> description.asJson,
          "agentName" -> agentName.asJson
        ).deepMerge(extraMetadata).deepMerge(failureMetadata)

        // P3.1: update task store status. taskStatusOverride supports terminal
        // states beyond completed/failed (AgentControl 的 "cancelled")。
        val taskStatus = taskStatusOverride.getOrElse(
          if eventType == "completed" then "completed" else "failed"
        )
        // worktree-judge-fifo 批（#1224）：delegate 轨终态统一清理（幂等）——
        // MergeQueue 出队（合并轮完成/超时/任何终态都要释放队列）+ failed/
        // cancelled 的补判词行（completed 腿的判词行由 DelegateTerminal.onCompleted
        // 写，此处不重复）。subtask 轨零变化。
        val delegateCleanup =
          if source == "delegate" then
            nebflow.core.delegate.DelegateTerminal
              .onTerminalCleanup(resources, subagentId, parentSessionId, taskStatus, eventType)
              .handleErrorWith(e => logger.warn(s"delegate terminal cleanup failed: ${e.getMessage}").void)
          else IO.unit

        val taskUpdate = resources.subAgentTaskStore
          .updateStatus(
            parentSessionId,
            subagentId,
            taskStatus,
            completedAt = Some(System.currentTimeMillis()),
            lastError = if taskStatus != "completed" then Some(payload) else None
          )
          .handleErrorWith(e => logger.warn(s"subAgentTaskStore update failed: ${e.getMessage}"))

        val notify = parentRef match
          case Some(ref) =>
            ref ! AgentCommand.ExternalEvent(
              source = source,
              eventType = eventType,
              payload = payload,
              metadata = metadata,
              correlationId = Some(subagentId)
            )
          case None => IO.unit

        // 终态三清（R2-c §7#4 + 本设计）：① hub 槽（僵尸 ask 卡：内核已死，
        // 卡片再没人能接——全仓唯一的其它清理入口是项目引擎，触及不到内核这种
        // 非项目会话）② 预算通道（不再计时）③ registry 行（既有）。
        // #1224：前置 delegate 清理（队列释放先行，再拆 actor）。
        (delegateCleanup *> notify *> taskUpdate *> cleanupPendingAsks(resources, subagentId) *>
          DelegateBudget.release(subagentId) *>
          resources.agentRegistry.update(_ - subagentId) *>
          (childRef ! AgentCommand.Stop(s"$source-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      end notifyParentAndStop

      /**
       * source-death 清理（InteractionHub.CleanupForSession 的第二个调用点；
       * 第一个是项目引擎 NodeEngine）：终态 / respawn 时清掉该会话的 pending
       * ask 槽并广播 askUserClosed。hub 未装配（早期 boot / 测试）或无槽 = no-op。
       */
      private def cleanupPendingAsks(resources: SharedResources, sessionId: String): IO[Unit] =
        resources.interactionHubRef.get.flatMap {
          case Some(hub) =>
            (hub ! InteractionHubCommand.CleanupForSession(sessionId)).void
              .handleErrorWith(e => logger.warn(s"CleanupForSession($sessionId) failed: ${e.getMessage}"))
          case None => IO.unit
        }

  /**
   * Crash 重启退避延迟(纯函数,单位 ms)。
   *
   * Phase 3 去重(行为保持重构,2026-09-25):指数部分 min(minBackoff ×
   * 2^currentRestarts, maxBackoff) 复用 shared Retry.delayAfter(初值 minBackoff、
   * ×2、封顶 maxBackoff、无抖动);抖动 [0,1000)ms 在封顶**之后**相加——总延迟
   * 可超 maxBackoff 至多 999ms,与公共层 Jitter.Additive「先加抖动再封顶」
   * (llm/fallback 口径)不同,故抖动留在本站点。监督循环本体(watch/Terminated、
   * 重启计数、消息重注入、maxRestarts 放弃)是 actor 监督语义、IO 重试组合子的
   * 超集,保持原样。jitterMs 可注入做确定性测试
   * (BackoffSupervisorRestartDelaySpec)。
   */
  private[agent] def restartDelayMs(
    currentRestarts: Int,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    jitterMs: () => Long = () => java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 1000)
  ): Long =
    Retry
      .delayAfter(
        initialDelay = minBackoff,
        failedAttemptNo = currentRestarts + 1,
        multiplier = 2.0,
        maxDelay = maxBackoff
      )
      .toMillis + jitterMs()

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

  // ── 四修①（2026-10-07 修复令「空轮≠完成」）判据纯函数（spec 判红锚） ──

  /** 全会话工具调用计数（Completed 事件的 messages = 全会话历史；spec 锚）。 */
  private[agent] def sessionToolCalls(messages: List[Message]): Int =
    messages.flatMap(_.content.toOption).flatten.count(_.isInstanceOf[ContentBlock.ToolUse])

  /**
   * 空烧完成判据：全会话零工具调用 **且** 终文不可用——终文为空（thinking-only
   * / 静默空轮，`turn-ended-empty` 形态）或止于 max_tokens 空烧 stub
   * （[[Defaults.MaxTokensStubText]]，与 handleEmptyResponse 收尾文案同串）。
   * 有真实终文（哪怕零工具）不算空烧——那是判定机的正常输入。
   */
  private[agent] def isEmptyBurnCompletion(messages: List[Message]): Boolean =
    if sessionToolCalls(messages) > 0 then false
    else
      val last = extractLastAssistantText(messages)
      last.isEmpty || last.trim == Defaults.MaxTokensStubText

end BackoffSupervisor
