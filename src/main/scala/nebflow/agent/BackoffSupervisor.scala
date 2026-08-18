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
   * @param childSpawnFn used to re-spawn the child on restart
   * @param childName   child actor name (for logging)
   * @param parentRef   parent agent to notify on completion / failure
   * @param description human-readable task description
   * @param agentName   child agent name
   * @param subagentId  session id
   * @param parentSessionId the parent (root) session that owns this task —
   *                        SubAgentTaskStore keys task files by it; must match
   *                        the id used at recordTask time or status updates
   *                        silently write nowhere
   * @param resources   shared resources (for agentRegistry cleanup)
   * @param initialPrompt the original prompt to re-inject after restart
   * @param source       ExternalEvent source string ("delegate" or "subtask")
   * @param extraMetadata extra metadata for ExternalEvent (e.g. "kind" -> "SubTask")
   */
  def apply(
    childRef: ActorRef[AgentCommand],
    childSpawnFn: ActorSystem => IO[ActorRef[AgentCommand]],
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
      ctx.watch(childRef) *>
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
    childSpawnFn: ActorSystem => IO[ActorRef[AgentCommand]],
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
            val payload =
              if text.nonEmpty then s""""$description":\n$text"""
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
                _ <- resources.agentRegistry.update(_ - subagentId)
                newChild <- childSpawnFn(ctx.system)
                _ <- ctx.watch(newChild)
                // Re-inject original prompt with self as replyTo
                _ <- newChild ! AgentCommand.UserInput(initialPrompt, Some(ctx.self))
                _ <- logger.info(s"BackoffSupervisor: respawned $childName, re-injected prompt")
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
        failureMetadata: JsonObject
      ): IO[Behavior[AgentEvent]] =
        val metadata = JsonObject(
          "description" -> description.asJson,
          "agentName" -> agentName.asJson
        ).deepMerge(extraMetadata).deepMerge(failureMetadata)

        // P3.1: update task store status
        val taskStatus = if eventType == "completed" then "completed" else "failed"
        val taskUpdate = resources.subAgentTaskStore
          .updateStatus(
            parentSessionId,
            subagentId,
            taskStatus,
            completedAt = Some(System.currentTimeMillis()),
            lastError = if eventType == "failed" then Some(payload) else None
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

        (notify *> taskUpdate *> resources.agentRegistry.update(_ - subagentId) *>
          (childRef ! AgentCommand.Stop(s"$source-complete")) *>
          IO.pure(Behaviors.stopped[AgentEvent]))
          .handleErrorWith(_ => IO.pure(Behaviors.stopped[AgentEvent]))

      end notifyParentAndStop

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.nonEmpty)
      .getOrElse("")

end BackoffSupervisor
