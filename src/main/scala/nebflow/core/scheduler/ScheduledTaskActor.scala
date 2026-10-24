package nebflow.core.scheduler

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
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
//   - Due TODOS (kind=todo, 2026-10-24 双面化) are aggregated into ONE digest
//     ExternalEvent (busy → queued at next turn boundary, idle → immediate),
//     then each due todo's surface time is bumped to tomorrow 09:00. A done /
//     cancel removes the todo from storage ⇒ never surfaces again.
//   - notifyTaskChange() triggers an immediate check (best-effort, non-blocking).
//   - Simpler and more reliable than the old Deferred-based approach, which had
//     a race condition between consuming the old signal and setting the new one.
// ============================================================

object ScheduledTaskService:

  /**
   * 待办浮出 digest 文案（纯函数，spec 钉内容契约）：含每条待办的 id（销账
   * 句柄）、内容与创建日期，结尾给出 done/cancel 用法。todos 为空 ⇒ 空串
   * （调用方不发）。
   */
  def digestPayload(todos: List[ScheduledTask], now: Long): String =
    if todos.isEmpty then ""
    else
      val zone = ZoneId.systemDefault()
      val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)
      val lines = todos.zipWithIndex.map { case (t, i) =>
        s"${i + 1}. [id: ${t.id}] ${t.content.take(200)}（创建于 ${fmt.format(Instant.ofEpochMilli(t.createdAt))}）"
      }
      val header =
        s"[待办浮出] 你有 ${todos.size} 项未完成待办（引擎侧持久存储，会话压缩/重启不丢失，每日早晨浮出一次直至处理）："
      val tail =
        "\n处理方式：已完成 → Schedule {action:\"done\", id:\"<id>\"} 销账（不再浮出）；不再需要 → {action:\"cancel\", id:\"<id>\"} 取消。现在请逐条处置。"
      ((header +: lines) :+ tail).mkString("\n")
  end digestPayload

end ScheduledTaskService

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

  /** One poll cycle — exposed private[scheduler] for spec. */
  private[scheduler] def fireDueTasks: IO[Unit] =
    for
      due <- taskStore.getAllDueTasks
      _ <- due.toList.traverse_(triggerTask)
      dueTodos <- taskStore.getAllDueTodos
      _ <- surfaceTodos(dueTodos)
    yield ()

  // ------------------------------------------------------------
  // Session resolution + injection (shared by both faces)
  // ------------------------------------------------------------

  /** 原会话仍在 ⇒ 原会话；否则回退当前活跃会话（重启/重开后的兜底，双面共用）。 */
  private def resolveSession(sessionId: String): IO[String] =
    sessionStore.getSessionMeta(sessionId).flatMap {
      case Some(_) => IO.pure(sessionId)
      case None =>
        sessionStore.getActiveId.flatMap { activeId =>
          if activeId.nonEmpty && activeId != sessionId then
            logger.info(s"Session $sessionId not found, falling back to $activeId") *>
              IO.pure(activeId)
          else IO.pure(sessionId)
        }
    }

  /** Inject as a persisted user message + route to the agent as ExternalEvent
    * (queues if busy, fires on the next turn boundary when idle). */
  private def injectExternal(resolvedSessionId: String, payload: String, event: AgentCommand.ExternalEvent): IO[Unit] =
    for
      // Persist the trigger as a user message in session history so it survives
      // page refresh and behaves like a normal user message (shows bubble + busy state).
      _ <- sessionStore.appendUiMessages(
        resolvedSessionId,
        List(UiMessage.User(payload, timestamp = System.currentTimeMillis()))
      )
      // Broadcast the user message so the frontend shows it in real-time
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "userMessage".asJson,
          "sessionId" -> resolvedSessionId.asJson,
          "content" -> payload.asJson,
          "timestamp" -> System.currentTimeMillis().asJson
        )
      )
      // Route to agent — ExternalEvent so it queues properly if agent is busy
      _ <- routeToAgent(resolvedSessionId, event).handleErrorWith(e =>
        logger.warn(s"Failed to route scheduled task to agent: ${e.getMessage}")
      )
    yield ()

  // ------------------------------------------------------------
  // Face 1: 定时任务（现状 + 主动性增强①条件触发/②错过补偿）
  // ------------------------------------------------------------

  /** 已知 repeat 的周期长度（ms）。 */
  private def nextPeriodMs(repeat: String): Option[Long] = repeat match
    case "hourly" => Some(3_600_000L)
    case "daily" => Some(86_400_000L)
    case "weekly" => Some(604_800_000L)
    case _ => None

  /**
   * ②错过补偿（2026-10-24）：重复任务错过的周期**不逐次补发**——本次注入即
   * 补射，next 直接快进到第一个未来槽位（改前 = 每错过一个周期原地补一炮的
   * 连环风暴）。正常晚点（<1 周期）行为逐字不变。
   */
  private def snapNextTrigger(current: Long, repeat: String, now: Long): Long =
    val first = nextTriggerTime(current, repeat)
    nextPeriodMs(repeat).fold(first) { p =>
      var n = first
      while n <= now do n += p
      n
    }

  /** 错过的完整周期数（<1 = 只是晚点，不算错过）。 */
  private def missedPeriods(triggerAt: Long, repeat: String, now: Long): Long =
    nextPeriodMs(repeat).fold(0L)(p => math.max(0L, (now - triggerAt) / p))

  /**
   * ①条件触发（2026-10-24）：`sh -c` 探针，退出码 0 = 条件为真。60s 硬超时
   * /启动失败一律按未满足（空闲安静红线：条件不真绝不注入占位消息）。
   */
  private def conditionMet(cmd: String): IO[Boolean] =
    IO.blocking {
      val pb = new ProcessBuilder("sh", "-c", cmd)
      pb.redirectErrorStream(true)
      val p = pb.start()
      val finished = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
      if !finished then
        p.destroyForcibly()
        logger.warnSync(s"Condition probe timed out (60s) — treated as unmet: ${cmd.take(80)}")
        false
      else p.exitValue() == 0
    }.handleErrorWith(e => logger.warn(s"Condition probe failed — treated as unmet: ${e.getMessage}").as(false))

  /** 一次性条件任务的复查间隔 / 观察窗（超窗未满足 → PAUSED，list 可见）。 */
  private val ConditionRetryMs = 30 * 60 * 1000L
  private val ConditionWatcherTtlMs = 24 * 3_600_000L

  /** 条件未满足——静默顺延（零注入）：repeat 跳下一周期；一次性 30min 复查，24h 超窗转 PAUSED。 */
  private def deferTask(task: ScheduledTask): IO[Unit] =
    val now = System.currentTimeMillis()
    task.repeat.filter(nextPeriodMs(_).isDefined) match
      case Some(r) =>
        val next = snapNextTrigger(task.triggerAt, r, now)
        taskStore.rescheduleTask(task.sessionId, task.id, next) *>
          logger.info(s"Condition unmet for ${task.id} ($r) — skipped to next cycle ${formatTime(next)}")
      case None =>
        if now - task.createdAt > ConditionWatcherTtlMs then
          taskStore.toggleTask(task.sessionId, task.id).void *>
            logger.warn(
              s"Condition watcher ${task.id} unmet for >24h — auto-PAUSED (visible via list; re-arm or cancel it)"
            )
        else
          val next = now + ConditionRetryMs
          taskStore.rescheduleTask(task.sessionId, task.id, next) *>
            logger.info(s"Condition unmet for ${task.id} — re-check at ${formatTime(next)}")
    end match
  end deferTask

  private def triggerTask(task: ScheduledTask): IO[Unit] =
    task.condition.filter(_.trim.nonEmpty) match
      case Some(cmd) =>
        conditionMet(cmd).flatMap {
          case true  => fireTask(task, extraNote = "（条件满足后触发）")
          case false => deferTask(task)
        }
      case None => fireTask(task, extraNote = catchupNote(task))

  /** ②补跑标记：错过的周期 ≥1 才标（正常晚点不打扰）。 */
  private def catchupNote(task: ScheduledTask): String =
    val missed = missedPeriods(task.triggerAt, task.repeat.getOrElse(""), System.currentTimeMillis())
    if missed >= 1 then s"（补跑：错过 $missed 个完整周期，本次补射一次，不逐次补发）" else ""

  private def fireTask(task: ScheduledTask, extraNote: String): IO[Unit] =
    val formattedTime = formatTime(task.triggerAt)
    val actualTime = formatTime(System.currentTimeMillis())
    val refNote = task.referencePath match
      case Some(path) => s"\n（参考文档: $path）"
      case None => ""
    val payload = s"${task.content}$refNote$extraNote\n（实际触发 $actualTime，预定 $formattedTime）"

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

    for
      _ <- logger.info(
        s"Triggering scheduled task ${task.id} for session ${task.sessionId}: ${task.content.take(60)}"
      )
      resolvedSessionId <- resolveSession(task.sessionId)
      _ <- injectExternal(resolvedSessionId, payload, event)
      _ <- task.repeat.filter(nextPeriodMs(_).isDefined) match
        case Some(r) =>
          // ②错过补偿：next 快进到第一个未来槽位（改前 = 原地 +1 周期，宕机
          // 多日则连环补发风暴）；正常晚点时结果与旧算法逐值一致。
          val nextTriggerAt = snapNextTrigger(task.triggerAt, r, System.currentTimeMillis())
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

  end fireTask

  // ------------------------------------------------------------
  // Face 2: 待办周期浮出（双面化 2026-10-24）
  //
  // 到期待办 ⇒ 一条汇总 digest 以 ExternalEvent 注入（忙时排队/闲时直投，
  // 即「每日早晨一次 + idle 窗口插入」的实现面）；随后把每个到期待办的下次
  // 浮出时间推到明晨 09:00。销账/取消 = 从存储删除 ⇒ 永不再浮出。
  // ------------------------------------------------------------

  private def surfaceTodos(dueTodos: List[ScheduledTask]): IO[Unit] =
    if dueTodos.isEmpty then IO.unit
    else
      for
        allTodos <- taskStore.getAllTodos
        now = System.currentTimeMillis()
        payload = ScheduledTaskService.digestPayload(allTodos, now)
        _ <- if payload.isEmpty then IO.unit else surfaceDigest(dueTodos, allTodos, payload, now)
      yield ()

  private def surfaceDigest(
    dueTodos: List[ScheduledTask],
    allTodos: List[ScheduledTask],
    payload: String,
    now: Long
  ): IO[Unit] =
    for
      _ <- logger.info(
        s"Surfacing ${dueTodos.size} due todo(s), digest covers ${allTodos.size} unfinished: " +
          dueTodos.map(_.id).mkString(", ")
      )
      // 与定时任务同款会话兜底：原会话不在（重启/重开）⇒ 活跃会话接手，
      // 待办不因会话消亡而烂在库里。
      resolvedSessionId <- resolveSession(dueTodos.head.sessionId)
      event = AgentCommand.ExternalEvent(
        source = "todo-surface",
        eventType = "digest",
        payload = payload,
        metadata = io.circe.JsonObject(
          "todoIds" -> dueTodos.map(_.id).asJson,
          "digestTodoIds" -> allTodos.map(_.id).asJson
        ),
        correlationId = dueTodos.headOption.map(_.id)
      )
      _ <- injectExternal(resolvedSessionId, payload, event)
      // 只顺延**到期**的待办（未到期的首次浮出时间不动——它明晨自会浮出）。
      nextAt = ScheduledTask.nextTodoSurfaceAt(now, ZoneId.systemDefault())
      _ <- dueTodos.traverse_(t => taskStore.rescheduleTask(t.sessionId, t.id, nextAt))
      _ <- broadcast(
        io.circe.Json.obj(
          "type" -> "todoSurfaced".asJson,
          "sessionId" -> resolvedSessionId.asJson,
          "todoIds" -> dueTodos.map(_.id).asJson,
          "nextSurfaceAt" -> nextAt.asJson
        )
      )
    yield ()
  end surfaceDigest

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
