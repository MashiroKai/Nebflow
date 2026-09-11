package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.actor.*
import nebflow.core.NebflowLogger
import nebflow.shared.{Message, MessageRole}

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
        logger.info(s"BackoffSupervisor: watching $childName for crash recovery (maxRestarts=$maxRestarts)").as(
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
        event match
          case AgentEvent.Completed(_, messages) =>
            val text = extractLastAssistantText(messages)
            // trim guard: a whitespace-only tail ("\n\n") is NOT a real output —
            // render "(no text output)" instead of an empty-shell payload
            val payload =
              if text.trim.nonEmpty then s""""$description":\n${text.trim}"""
              else s""""$description" (no text output)"""
            notifyParentAndStop("completed", payload, JsonObject.empty)

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
            val currentRestarts = recentRestarts.length

            if currentRestarts < maxRestarts then
              val backoffMs = math.min(
                minBackoff.toMillis * (1L << currentRestarts),
                maxBackoff.toMillis
              )
              val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 1000)
              val delay = backoffMs + jitter
              val newRestartCount = restartCount + 1
              val newHistory = now :: recentRestarts

              logger.info(
                s"BackoffSupervisor: child $childName crashed, restarting " +
                  s"#$newRestartCount/$maxRestarts after ${delay}ms backoff"
              )

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
                  send(retryEvent).handleErrorWith(e =>
                    logger.warn(s"subagentRetry WS event failed: ${e.getMessage}")
                  )
                case None => IO.unit

              for
                _ <- logger.info(
                  s"BackoffSupervisor: child $childName crashed, restarting " +
                    s"#$newRestartCount/$maxRestarts after ${delay}ms backoff"
                )
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
                // child picks up where it left off. Otherwise re-inject the
                // original prompt (fresh start fallback).
                _ <- if recoveredMessages.nonEmpty then
                  newChild ! AgentCommand.UserInput(
                    "[system] Your previous turn was interrupted by a crash. " +
                      "Please continue your task from where you left off.",
                    Some(ctx.self)
                  )
                else
                  newChild ! AgentCommand.UserInput(initialPrompt, Some(ctx.self))
                _ <- logger.info(
                  s"BackoffSupervisor: respawned $childName, " +
                    s"recovered ${recoveredMessages.size} messages" +
                    (if recoveredMessages.nonEmpty then " (resuming from checkpoint)" else " (re-injected original prompt)")
                )
                // respawn 后重新装配预算（Delegate 轨）——上一条已在释放时退出。
                _ <- if source == "delegate" then
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
        (notify *> taskUpdate *> cleanupPendingAsks(resources, subagentId) *>
          DelegateBudget.release(subagentId) *>
          resources.agentRegistry.update(_ - subagentId) *>
          (childRef ! AgentCommand.Stop(s"$source-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      end notifyParentAndStop

      /** source-death 清理（InteractionHub.CleanupForSession 的第二个调用点；
        * 第一个是项目引擎 NodeEngine）：终态 / respawn 时清掉该会话的 pending
        * ask 槽并广播 askUserClosed。hub 未装配（早期 boot / 测试）或无槽 = no-op。 */
      private def cleanupPendingAsks(resources: SharedResources, sessionId: String): IO[Unit] =
        resources.interactionHubRef.get.flatMap {
          case Some(hub) =>
            (hub ! InteractionHubCommand.CleanupForSession(sessionId)).void
              .handleErrorWith(e => logger.warn(s"CleanupForSession($sessionId) failed: ${e.getMessage}"))
          case None => IO.unit
        }

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

end BackoffSupervisor
