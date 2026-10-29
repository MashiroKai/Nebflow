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

  /**
   * ⑤触发链上下文（作者原令六条之⑤「重复任务再次触发时自动附带上一次执行的
   * 结果摘要，执行者不用从零考古」）：取窗口内**第一条** Ai 行（= 对上次触发的
   * 处理输出），压平换行、掐头 160 字符（超长加省略号）。无 Ai 行（无回复/
   * 纯工具轮）⇒ None（零打扰）。纯函数（now/消息页注入）——spec 钉死。
   */
  private[scheduler] def prevResultSummary(msgs: List[UiMessage], prevFireAt: Long): Option[String] =
    msgs.collectFirst {
      case m: UiMessage.Ai if m.timestamp >= prevFireAt && m.text.trim.nonEmpty =>
        val t = m.text.trim.replace('\n', ' ').replaceAll("\\s+", " ")
        if t.length > 160 then t.take(160) + "…" else t
    }

end ScheduledTaskService

class ScheduledTaskService(
  dispatcher: Dispatcher[IO],
  taskStore: ScheduledTaskStore,
  routeToAgent: (String, AgentCommand.ExternalEvent) => IO[Unit],
  broadcast: Json => IO[Unit],
  sessionStore: SessionStorePort,
  /**
   * 本次引擎启动时刻（错过补偿 2026-10-29 批）：一次性任务的 triggerAt 早于
   * 它 ⇒ 该预定触发点被停机跨过 ⇒ 恢复后补跑并标注「错过后补跑」。默认取构造
   * 时刻（服务只在启动时装配一次，等价引擎 boot）；spec 注入固定值可钉。
   */
  bootAtMs: Long = System.currentTimeMillis()
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
      // ①条件预评：条件任务先评估——Met/无条件 ⇒ 进同窗合并面；Unmet ⇒ 静默顺延
      // （评估器状态在预评时落库，defer 路径不再重复评估）。
      evaluated <- due.toList.traverse(evalConditionForFire)
      (fireable, deferred) = evaluated.partition(_._2)
      _ <- deferred.map(_._1).traverse_(deferTask)
      // ⑥同窗合并（作者原令六条之⑥）：同一次轮询窗口内、同一会话名下的多个
      // 到点触发合并为**一次** ExternalEvent（单任务组逐字走旧路径——存量行为
      // 零变化）。
      grouped <- fireable.map(_._1).traverse(t => resolveSession(t.sessionId).map(sid => (sid, t))).map { pairs =>
        pairs.groupBy(_._1).view.mapValues(_.map(_._2)).toList
      }
      _ <- grouped.toList.traverse_ {
        case (_, single :: Nil) => fireTask(single, extraNote = fireNote(single))
        case (sid, many) if many.size >= 2 => fireMergedGroup(sid, many.sortBy(_.triggerAt))
        case _ => IO.unit // 分组自非空列表而来，空组不可达（守卫留白防穷举告警）
      }
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

  /**
   * Inject as a persisted user message + route to the agent as ExternalEvent
   * (queues if busy, fires on the next turn boundary when idle).
   */
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
   * ①条件触发（2026-10-24 原始语义 + 2026-10-29 评估器批）：条件串按前缀分发
   * 到评估器（无前缀 = `sh -c` 探针兜底，退出码 0 = 真）。评估失败/未满足一律
   * 静默顺延（空闲安静红线：条件不真绝不注入占位消息）；有状态评估器（git-tip
   * 哨兵）返回的新基线持久化到 conditionState（失败不推进基线）。
   */
  private def evaluateCondition(task: ScheduledTask, condition: String): IO[(Boolean, Option[String])] =
    TaskConditionEvaluator
      .dispatch(condition)
      .evaluate(task, condition)
      .map(o => (o.verdict == ConditionVerdict.Met, o.nextState))

  /** 有状态评估器的新基线写回（仅变化时落盘；任务行已被并发删除则静默放过）。 */
  private def persistConditionState(task: ScheduledTask, nextState: Option[String]): IO[Unit] =
    nextState match
      case Some(st) if task.conditionState.forall(_ != st) =>
        taskStore.updateTask(task.sessionId, task.id)(_.copy(conditionState = Some(st))).void
      case _ => IO.unit

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

  /**
   * 触发前条件预评：返回 (任务, 是否可触发)。条件任务在此评估并落评估器状态
   * （Met/Unmet 都落——哨兵基线推进）；无条件任务恒可触发。deferred 的静默顺延
   * 由 deferTask 统一执行（本函数只做判定，不注入）。
   */
  private def evalConditionForFire(task: ScheduledTask): IO[(ScheduledTask, Boolean)] =
    task.condition.filter(_.trim.nonEmpty) match
      case Some(cond) =>
        evaluateCondition(task, cond).flatMap { case (met, nextState) =>
          persistConditionState(task, nextState).as((task, met))
        }
      case None => IO.pure((task, true))

  /** 本次注入的触发注脚：条件任务 vs 补跑标注（错过补偿 ②）。 */
  private def fireNote(task: ScheduledTask): String =
    if task.condition.exists(_.trim.nonEmpty) then "（条件满足后触发）" else catchupNote(task)

  /**
   * ⑤触发链上下文（作者原令六条之⑤「重复任务再次触发时自动附带上一次执行的
   * 结果摘要，执行者不用从零考古」）：例行任务且存在上一轮触发时刻 ⇒ 取本会话
   * 在 [lastFiredAt, now) 窗口内的**第一条** Ai 行（= 对上次触发的处理输出），
   * 掐头 160 字符。取不到（无回复/纯工具轮/历史被压缩）⇒ 无注脚，零打扰。
   */
  private def prevResultNote(task: ScheduledTask): IO[Option[String]] =
    (task.repeat, task.lastFiredAt) match
      case (Some(_), Some(prevFireAt)) =>
        sessionStore
          .getUiMessages(task.sessionId, 0, 0)
          .map { case (msgs, _) => ScheduledTaskService.prevResultSummary(msgs, prevFireAt) }
          .handleErrorWith(e =>
            logger.warn(s"prev-result lookup failed (continuing without summary): ${e.getMessage}").as(None)
          )
      case _ => IO.pure(None)

  /**
   * 触发后的例行任务落账（②快进 + ③空转记账/降频）：预评好的 (streak, crossed)
   * 写回任务行（原子、持锁），next 快进到第一个未来槽位。返回 (新 repeat, crossed)。
   */
  private def settleRecurring(task: ScheduledTask, streakOpt: Option[Int], crossed: Boolean): IO[Unit] =
    val now = System.currentTimeMillis()
    val r = task.repeat.getOrElse("daily")
    val r2 = if crossed then downfreqStep(r) else r
    val nextTriggerAt = snapNextTrigger(task.triggerAt, r2, now)
    taskStore
      .updateTask(task.sessionId, task.id) { t =>
        t.copy(
          triggered = false,
          triggeredAt = None,
          triggerAt = nextTriggerAt,
          repeat = Some(r2),
          idleStreak = streakOpt.getOrElse(t.idleStreak),
          reviewFlag = t.reviewFlag || crossed,
          lastFiredAt = Some(now)
        )
      }
      .void *>
      logger.info(
        s"Rescheduled recurring task ${task.id} ($r${
            if crossed then s" → downfreqed to $r2, reviewFlag set" else ""
          }) for ${formatTime(nextTriggerAt)}"
      )

  end settleRecurring

  private def fireTask(task: ScheduledTask, extraNote: String): IO[Unit] =
    val formattedTime = formatTime(task.triggerAt)
    val refNote = task.referencePath match
      case Some(path) => s"\n（参考文档: $path）"
      case None => ""

    for
      // ③例程自检：先算空转/降频判定（payload 需要带上降频告知）
      streakState <- task.repeat.filter(nextPeriodMs(_).isDefined) match
        case Some(_) =>
          idleRun(task).map { idle =>
            val streak = nextStreak(task, idle)
            val crossed = streak >= IdleStreakThreshold && !task.reviewFlag
            (Some(streak), crossed)
          }
        case None => IO.pure((None, false))
      (streakOpt, crossed) = streakState
      downfreqNote =
        if crossed then
          s"\n（例程自检：连续 ${streakOpt.getOrElse(0)} 次触发无动作，已自动降频「${task.repeat.getOrElse("")}→${downfreqStep(task.repeat.getOrElse("daily"))}」并挂「待审查」——同名 re-arm 可恢复，cancel 销账）"
        else ""
      prevChain <- prevResultNote(task)
      chainNote = prevChain.map(s => s"\n（上一轮结果摘要：$s）").getOrElse("")
      actualTime = formatTime(System.currentTimeMillis())
      payload = s"${task.content}$refNote$extraNote$downfreqNote$chainNote\n（实际触发 $actualTime，预定 $formattedTime）"

      event = AgentCommand.ExternalEvent(
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

      _ <- logger.info(
        s"Triggering scheduled task ${task.id} for session ${task.sessionId}: ${task.content.take(60)}"
      )
      resolvedSessionId <- resolveSession(task.sessionId)
      _ <- injectExternal(resolvedSessionId, payload, event)
      _ <- task.repeat.filter(nextPeriodMs(_).isDefined) match
        case Some(_) => settleRecurring(task, streakOpt, crossed)
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

  /**
   * ⑥同窗合并注入：同一轮询窗口内同一会话名下的 N（≥2）个到点触发合并为**一次**
   * ExternalEvent（作者原令六条之⑥「同一注入窗口多个触发合并为一次
   * ExternalEvent（注意：这是会话注入腿，与社交出站的逐条投递语义无关）」）。
   * 逐任务的后事（例行落账/一次性删除/面板广播）不合并，逐字保留各自语义。
   */
  private def fireMergedGroup(resolvedSessionId: String, tasks: List[ScheduledTask]): IO[Unit] =
    val now = System.currentTimeMillis()
    for
      perTask <- tasks.traverse { t =>
        t.repeat.filter(nextPeriodMs(_).isDefined) match
          case Some(_) =>
            idleRun(t).map { idle =>
              val streak = nextStreak(t, idle)
              val crossed = streak >= IdleStreakThreshold && !t.reviewFlag
              (t, Some(streak), crossed)
            }
          case None => IO.pure((t, None, false))
      }
      // ⑤触发链上下文：同会话共享一份上一轮摘要（只在组头出现一次）
      prevChain <- tasks.find(t => t.repeat.isDefined && t.lastFiredAt.isDefined) match
        case Some(first) => prevResultNote(first)
        case None => IO.pure(None)
      chainNote = prevChain.map(s => s"\n（上一轮结果摘要：$s）").getOrElse("")
      lines = perTask.map { case (t, _, _) =>
        val notes = fireNote(t)
        s"- ${t.content.take(160)}$notes（预定 ${formatTime(t.triggerAt)}）"
      }
      header =
        s"[定时任务批量触发 ×${tasks.size}]（同窗合并注入：本窗口 ${tasks.size} 个到点触发合并为一次，请逐条处置）"
      payload = ((header +: chainNote.toList) ++ lines :+ s"（实际触发 ${formatTime(now)}）").mkString("\n")
      event = AgentCommand.ExternalEvent(
        source = "scheduled-task",
        eventType = "trigger-batch",
        payload = payload,
        metadata = io.circe.JsonObject(
          "taskIds" -> tasks.map(_.id).asJson,
          "merged" -> true.asJson
        ),
        correlationId = None
      )
      _ <- logger.info(
        s"Merged ${tasks.size} due tasks into one batched trigger for session $resolvedSessionId: ${tasks.map(_.id).mkString(", ")}"
      )
      _ <- injectExternal(resolvedSessionId, payload, event)
      _ <- perTask.traverse_ { case (t, streakOpt, crossed) =>
        t.repeat.filter(nextPeriodMs(_).isDefined) match
          case Some(_) => settleRecurring(t, streakOpt, crossed)
          case _ => taskStore.deleteTask(t.sessionId, t.id)
      }
      _ <- tasks.traverse_(t =>
        broadcast(
          io.circe.Json.obj(
            "type" -> "scheduledTaskTriggered".asJson,
            "sessionId" -> t.sessionId.asJson,
            "task" -> io.circe.Json.obj(
              "id" -> t.id.asJson,
              "content" -> t.content.asJson,
              "triggerAt" -> t.triggerAt.asJson,
              "referencePath" -> t.referencePath.asJson,
              "repeat" -> t.repeat.asJson,
              "formattedTime" -> formatTime(t.triggerAt).asJson
            )
          )
        )
      )
    yield ()
    end for

  end fireMergedGroup

  /**
   * ②补跑标记（错过补偿）：重复任务错过 ≥1 完整周期 ⇒ 补射标注；一次性任务
   * 的预定触发点早于本次引擎启动 ⇒ 必然被停机跨过 ⇒「错过后补跑」标注（正常
   * 晚点——运行中 10s 轮询粒度的迟到——不打扰）。
   */
  private def catchupNote(task: ScheduledTask): String =
    val now = System.currentTimeMillis()
    val missed = missedPeriods(task.triggerAt, task.repeat.getOrElse(""), now)
    if missed >= 1 then s"（补跑：错过 $missed 个完整周期，本次补射一次，不逐次补发）"
    else if task.repeat.isEmpty && task.triggerAt < bootAtMs then "（错过后补跑：引擎停机跨过预定触发时间，恢复后自动补跑一次）"
    else ""

  /**
   * ③例程自检降频阈值（2026-10-29）：连续空转（触发窗口内会话零工具动作）
   * 达此次数 ⇒ repeat 阶梯降一级（hourly→daily→weekly，weekly 到底只挂标）并
   * 挂 reviewFlag「待审查」（list 可见；同名 re-arm 恢复 / cancel 销账）。
   */
  val IdleStreakThreshold: Int = 3

  /** repeat 降频阶梯：降一级；weekly 已到底不再降（只挂标）。 */
  private def downfreqStep(repeat: String): String = repeat match
    case "hourly" => "daily"
    case _ => "weekly"

  /**
   * 本轮触发是否空转（三态）：Some(true)=窗口零动作（streak +1）；Some(false)=
   * 有动作（清零）；None=无证据——重启冷启动首周期不奖不罚，streak 保持。
   */
  private def idleRun(task: ScheduledTask): IO[Option[Boolean]] =
    task.lastFiredAt match
      case None => IO.pure(None) // 首个受控周期无判据
      case Some(prev) => SessionToolActivity.idleSince(task.sessionId, prev)

  /** 空转三态 → streak 新值。 */
  private def nextStreak(task: ScheduledTask, idle: Option[Boolean]): Int =
    idle match
      case Some(true) => task.idleStreak + 1
      case Some(false) => 0
      case None => task.idleStreak

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
