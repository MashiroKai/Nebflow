package nebflow.core.scheduler

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.actor.AgentCommand
import nebflow.core.SessionStorePort
import nebflow.shared.{NebflowLogger, UiMessage}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import scala.concurrent.duration.*

// ============================================================
// ScheduledTaskService — robust periodic task firing
//
// Design:
//   - start() launches a background fiber that polls every 10 seconds.
//   - Due tasks are fired, then deleted (one-shot) or rescheduled (recurring).
//   - notifyTaskChange() triggers an immediate check (best-effort, non-blocking).
//   - Simpler and more reliable than the old Deferred-based approach, which had
//     a race condition between consuming the old signal and setting the new one.
// ============================================================

class ScheduledTaskService(
  dispatcher: Dispatcher[IO],
  taskStore: ScheduledTaskStore,
  routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
  broadcast: Json => IO[Unit],
  sessionStore: SessionStorePort
):

  private val logger = NebflowLogger.forName("nebflow.scheduled-task")

  /** Flag to request an immediate check (set by notifyTaskChange, cleared by loop). */
  private val checkNowRef: Ref[IO, Boolean] = Ref.unsafe(false)

  /** Launch the background loop. Call once at startup. */
  def start(): IO[Unit] =
    logger.info("Starting scheduled task service (10s poll interval)") *> loop

  /** Signal that the task set changed — requests an immediate poll. */
  def notifyTaskChange(): IO[Unit] = checkNowRef.set(true)

  // ============================================================
  // Polling loop — every 10 seconds, or sooner if notified
  // ============================================================

  private val PollInterval = 10.seconds

  private def loop: IO[Unit] =
    for
      _ <- fireDueTasks.handleErrorWith(e => logger.warn(s"Scheduled task loop error: ${e.getMessage}").void)
      // Check if we were notified during fireDueTasks — if so, skip the sleep
      notified <- checkNowRef.getAndSet(false)
      sleepTime = if notified then 1.second else PollInterval
      _ <- IO.sleep(sleepTime)
      _ <- loop
    yield ()

  private def fireDueTasks: IO[Unit] =
    for
      due <- taskStore.getAllDueTasks
      _ <- due.toList.traverse_(triggerTask)
    yield ()

  private def triggerTask(task: ScheduledTask): IO[Unit] =
    val formattedTime = formatTime(task.triggerAt)
    val actualTime = formatTime(System.currentTimeMillis())
    val refNote = task.referencePath match
      case Some(path) => s"\n（参考文档: $path）"
      case None => ""
    val payload = s"${task.content}$refNote\n（实际触发 $actualTime，预定 $formattedTime）"

    val event = AgentCommand.ExternalEvent(
      source = "scheduled-task",
      eventType = "trigger",
      payload = payload,
      metadata = io.circe.JsonObject(
        "taskId" -> task.id.asJson,
        "triggerAt" -> task.triggerAt.asJson,
        "referencePath" -> task.referencePath.asJson
      ),
      correlationId = Some(task.id)
    )

    // schedcard 批（2026-10-29，作者令「定时任务卡片防退化 + stand 配色」）：卡片
    // 载荷（id/name/triggerAt/repeat/referencePath）随行进 `schedule` 键（缺席即
    // 不落键，quote/fileChanges 先例同款条件编码）。
    val scheduleMeta =
      val base = JsonObject("id" -> task.id.asJson, "triggerAt" -> task.triggerAt.asJson)
      val withName = task.name.fold(base)(n => base.add("name", n.asJson))
      val withRepeat = task.repeat.fold(withName)(r => withName.add("repeat", r.asJson))
      task.referencePath.fold(withRepeat)(p => withRepeat.add("referencePath", p.asJson))

    for
      _ <- logger.info(
        s"Triggering scheduled task ${task.id} for session ${task.sessionId}: ${task.content.take(60)}"
      )
      // Session fallback: if the original session no longer exists (e.g. after restart),
      // route to the active session instead of dropping the task.
      resolvedSessionId <- sessionStore.getSessionMeta(task.sessionId).flatMap {
        case Some(_) => IO.pure(task.sessionId)
        case None =>
          sessionStore.getActiveId.flatMap { activeId =>
            if activeId.nonEmpty && activeId != task.sessionId then
              logger.info(s"Session ${task.sessionId} not found, falling back to $activeId") *>
                IO.pure(activeId)
            else IO.pure(task.sessionId)
          }
      }
      // Persist the trigger as a user message in session history so it survives
      // page refresh and behaves like a normal user message (shows bubble + busy state).
      // schedcard 批（2026-10-29）：触发行落盘时**必须带注入标记**（`injected=true` +
      // `source="schedule"`）——无标记行会被前端历史恢复渲染成通用绿色用户气泡
      // （= 作者报障的退化态）。渲染分支由前端 `buildInjectedRow` 的
      // `source === 'schedule'` 专属卡接住（live 与两史恢复路径同 builder 同构）。
      _ <- sessionStore.appendUiMessages(
        resolvedSessionId,
        List(
          UiMessage.User(
            payload,
            injected = true,
            timestamp = System.currentTimeMillis(),
            source = Some("schedule"),
            eventType = Some("trigger"),
            schedule = Some(scheduleMeta.asJson)
          )
        )
      )
      // Broadcast the user message so the frontend shows it in real-time.
      // schedcard 批（2026-10-29）：帧形从裸 `userMessage`（前端零消费者——死帧，
      // 卡片只有刷新后才出现）换成**注入 user 帧标准形**（`injected:true` +
      // `source:"schedule"` + `schedule` 载荷）——与 main.js `onMessage('user')`
      // 既有消费管道路径逐字段同形 ⇒ 触发即渲染专属卡，无需刷新。帧键集 =
      // UiMessage.User 编码面 + sessionId（display 路由键），零新协议面。
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "user".asJson,
          "sessionId" -> resolvedSessionId.asJson,
          "text" -> payload.asJson,
          "injected" -> true.asJson,
          "source" -> "schedule".asJson,
          "eventType" -> "trigger".asJson,
          "timestamp" -> System.currentTimeMillis().asJson,
          "schedule" -> scheduleMeta.asJson
        )
      )
      // Route to agent — ExternalEvent so it queues properly if agent is busy
      _ <- routeToAgent(resolvedSessionId, event).handleErrorWith(e =>
        logger.warn(s"Failed to route scheduled task to agent: ${e.getMessage}")
      )
      _ <- task.repeat match
        case Some(r) if r == "hourly" || r == "daily" || r == "weekly" =>
          val nextTriggerAt = nextTriggerTime(task.triggerAt, r)
          taskStore.rescheduleTask(task.sessionId, task.id, nextTriggerAt) *>
            logger.info(s"Rescheduled recurring task ${task.id} (${r}) for ${formatTime(nextTriggerAt)}")
        case _ =>
          // One-shot task: delete from storage so it doesn't clutter the list.
          taskStore.deleteTask(task.sessionId, task.id)
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "scheduledTaskTriggered".asJson,
          "sessionId" -> task.sessionId.asJson,
          "task" -> io.circe.Json.obj(
            "id" -> task.id.asJson,
            "content" -> task.content.asJson,
            "triggerAt" -> task.triggerAt.asJson,
            "referencePath" -> task.referencePath.asJson,
            "repeat" -> task.repeat.asJson,
            "formattedTime" -> formattedTime.asJson
          )
        )
      )
    yield ()

    end for

  end triggerTask

  private def nextTriggerTime(current: Long, repeat: String): Long = repeat match
    case "hourly" => current + 3_600_000L
    case "daily" => current + 86_400_000L
    case "weekly" => current + 604_800_000L
    case other => current // unknown pattern — don't advance (will re-fire immediately)

  private def formatTime(epochMs: Long): String =
    val instant = Instant.ofEpochMilli(epochMs)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    formatter.format(instant)

end ScheduledTaskService
