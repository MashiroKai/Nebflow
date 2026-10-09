package nebflow.core.tools

import cats.effect.IO
import cats.syntax.foldable.toFoldableOps
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.scheduler.{ScheduledTask, ScheduledTaskService, ScheduledTaskStore}

import java.time.*

object ScheduleTool extends Tool:
  val name = "Schedule"

  val description =
    """Schedule a task to fire at a specified time, optionally recurring — or manage existing
tasks (list / cancel). At the scheduled time the content is injected into your conversation
as an instruction — you then execute it.

## When to Use

- 定时汇报 — schedule a progress report for a specific time ("at 17:00, summarize today's work")
- 定时维护 — periodic maintenance ("every Monday morning, review and clean up memory")
- 闲时执行 — defer a non-urgent task to an idle window ("at 23:00 when the user is idle, consolidate memory")
- 高峰规避 — avoid peak LLM pricing hours ("at 09:00 tomorrow, run the batch analysis")
- 用户提醒 — remind the user about something at a future time

## Actions (optional `action` field, default "create")

- **create** (default): schedule new content at triggerAt. If `name` is set, creating with a
  name that already exists REPLACES the existing task instead of stacking a duplicate
  (upsert). ALWAYS use a fixed, descriptive name for routine/recurring tasks (e.g.
  "dream-daily-review") — re-arming the same routine after a restart then converges to a
  single copy instead of stacking duplicates.
- **list**: show ALL pending tasks across all sessions — id, name, trigger time, repeat,
  status (active / PAUSED). Call this before re-arming a routine task: if it already exists,
  don't create a copy.
- **cancel**: remove a task by id (works across sessions). A cancelled task never fires and
  disappears from list immediately.

## Fields

- **action** (optional): "create" (default) | "list" | "cancel"
- **content** (create): Task content — a natural-language instruction describing what you
  should do when the task fires. Write it as a direct command you would execute ("汇报 XX 进度",
  "整理记忆", "review the latest commits"). Include any necessary context (which project, what scope).
- **triggerAt** (create): When the task should fire. A JSON integer (epoch ms) or a QUOTED
  JSON string — NEVER a bare unquoted ISO literal (that is invalid JSON). Must be in the
  future. Accepted string forms, in parser priority order:
  1. "1791612000000" — epoch ms as a 13-digit digit-string (= 2026-10-10T14:00:00+08:00)
  2. ISO 8601 WITH zone — "2026-10-10T14:00:00+08:00" or "2026-10-10T06:00:00Z" (safest —
     no ambiguity; use dashes, never slashes)
  3. ISO 8601 / date WITHOUT zone — "2026-10-10T14:00", "2026-10-10 14:00",
     "2026-10-10 14:00:30", "2026-10-10" — interpreted in the system timezone
  4. Natural language, exactly these shapes: "21:30" / "at 21:30" (today, or tomorrow if
     already past), "tomorrow" (= 09:00), "tomorrow at 21:00", "in 30 minutes",
     "in 2 hours", "in 1 day", "in 90m"
  NOT supported — convert to a supported form yourself before calling: weekday names
  ("Monday", "周一"), Chinese time phrases ("明天下午3点" → 15:00), "7pm" (→ "19:00"),
  "next week", slash dates ("2026/10/10"). Only the bare "HH:mm" form auto-rolls to the
  next day when already past; an absolute form lying in the past is REJECTED — roll it
  forward yourself.
- **repeat** (create, optional): "hourly" | "daily" | "weekly" — omit for a one-shot task
  (a literal "once" is tolerated and treated as omitted). This is NOT cron: the next fire =
  previous triggerAt + 1h / 24h / 7d, so the clock time of your FIRST triggerAt is the
  anchor — set it to the next occurrence of the wanted time (e.g. an every-day-09:00
  routine armed at 14:00 → triggerAt "tomorrow at 09:00", repeat "daily").
- **name** (create, recommended): Stable identifier for upsert. Same name replaces the old
  task (across all sessions). Required practice for recurring/routine tasks; omit for one-offs.
- **id** (cancel): Task id to cancel — from action=list.

## Establish "now" before calling (top failure cause)

Your context does NOT show the current date/time — never invent an absolute date.
- Easiest: use a relative form ("in 30 minutes", "21:30") — no date arithmetic needed, and
  a past "HH:mm" rolls to tomorrow automatically.
- For an absolute timestamp, first get the real clock: Bash {command:"date '+%s %z'"} gives
  epoch SECONDS (×1000 = ms) and the local UTC offset (e.g. +0800 → "+08:00").
- Every Schedule rejection embeds the server's current time (formatted; parse errors also
  carry raw epoch ms and zone id) — recalibrate from that line instead of guessing again.

## Examples

{"action":"create", "content":"汇总今日 commit 并向用户汇报进度", "triggerAt":"in 30 minutes", "name":"evening-commit-digest"}
{"action":"create", "content":"整理记忆并输出明日三件事", "triggerAt":"tomorrow at 09:00", "repeat":"daily", "name":"morning-review"}
{"action":"create", "content":"提醒用户 review PR #42", "triggerAt":"2026-10-10T17:00:00+08:00", "name":"pr42-review-reminder"}
{"action":"list"}
{"action":"cancel", "id":"af17698a"}

## Common Mistakes (each costs a wasted round-trip)

- Invented absolute date (you cannot see today) → use a relative form, or `date '+%s %z'` first.
- Bare unquoted ISO literal in the arguments JSON → always a number or a quoted string.
- Absolute triggerAt in the past → only "HH:mm" auto-rolls; roll absolute forms forward yourself.
- repeat "weekly" assumed to mean "every Monday" → it means +7d from your anchor; put the
  first triggerAt on the wanted weekday.
- Routine duplicated after a restart → you omitted `name`, so upsert could not converge it.

## Notes

- The task fires as an ExternalEvent in the current session; the injected content is the
  instruction you receive.
- For recurring tasks, the next fire time is computed from the previous triggerAt (not wall clock), maintaining consistent intervals."""

  private val validRepeat = Set("hourly", "daily", "weekly")
  private val validActions = Set("create", "list", "cancel")

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "action" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("create".asJson, "list".asJson, "cancel".asJson),
          "description" -> "create (default) = schedule a task; list = show all pending tasks across sessions; cancel = remove a task by id".asJson
        ),
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create) The instruction to execute when the task fires — a direct, self-contained command (\"汇报今日 commit 进度\"); include whatever context it needs.".asJson
        ),
        "triggerAt" -> Json.obj(
          "type" -> "string".asJson,
          "description" ->
            """(create) When the task fires. Schema type is string, but a bare JSON integer (epoch ms) is also accepted — never a bare unquoted ISO literal (invalid JSON). String forms: "2026-10-10T14:00:00+08:00" (ISO with zone; dashes not slashes), "tomorrow at 09:00", "in 30 minutes", "21:30" (auto-rolls to tomorrow when already past). Must be in the future — if unsure of today's date, use a relative form or get the time via Bash first.""".asJson
        ),
        "repeat" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("hourly".asJson, "daily".asJson, "weekly".asJson),
          "description" -> "(create) \"hourly\" | \"daily\" | \"weekly\". Omit for one-shot (\"once\" is tolerated = omitted). Not cron: next fire = previous triggerAt + 1h/24h/7d — the first triggerAt's clock time is the anchor, so set it to the next occurrence of the wanted time.".asJson
        ),
        "name" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(create) Stable identifier — same name replaces the existing task instead of stacking (upsert). Use a fixed name for routine/recurring tasks.".asJson
        ),
        "id" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "(cancel) Task id to cancel — from action=list.".asJson
        )
      )
      // required 刻意留空（2026-09-06）：action=list/cancel 不携带 content/triggerAt，
      // 分 action 的必填校验在 call 内做并给可自愈错误。
    )
  )

  def summarize(input: JsonObject): String =
    val action = input("action").flatMap(_.asString).getOrElse("create")
    action match
      case "list" => "Schedule(list)"
      case "cancel" =>
        s"Schedule(cancel ${input("id").flatMap(_.asString).getOrElse("?")})"
      case _ =>
        val content = input("content").flatMap(_.asString).getOrElse("")
        val repeat = input("repeat").flatMap(_.asString).getOrElse("once")
        val triggerAt = input("triggerAt").flatMap(_.asNumber.flatMap(_.toLong)).getOrElse(0L)
        val nameTag = input("name").flatMap(_.asString).filter(_.nonEmpty).map(n => s" name=$n").getOrElse("")
        s"Schedule(create$nameTag $repeat @ $triggerAt: $content)"

  def summarizeResult(input: JsonObject, result: String): String =
    val action = input("action").flatMap(_.asString).getOrElse("create")
    action match
      case "list" =>
        if result.startsWith("Pending") || result.toLowerCase.contains("no pending") then "listed" else "failed"
      case "cancel" => if result.startsWith("Cancelled") then "cancelled" else "failed"
      case _ =>
        if result.startsWith("Scheduled task") then if result.contains("replaced") then "replaced" else "scheduled"
        else "failed"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val action = input("action").flatMap(_.asString).getOrElse("create")
    if !validActions.contains(action) then
      IO.pure(
        Left(
          ToolError(
            s"Unknown action \"$action\" — expected one of ${validActions.toList.sorted.mkString(", ")} " +
              "(omit action for create; use list to inspect, cancel by id)."
          )
        )
      )
    else
      ctx.sharedResources match
        case None => IO.pure(Left(ToolError("SharedResources not available")))
        case Some(sr) =>
          // Phase 5 D 步:scheduledTaskStore / scheduledTaskService 均为 core.scheduler
          // 类型——以底层值直传,不再以 agent 定位器类型流经本文件。
          val taskStore = sr.scheduledTaskStore
          val taskService = sr.scheduledTaskService
          action match
            case "list" => listAction(taskStore)
            case "cancel" => cancelAction(taskStore, taskService, input, ctx)
            case "create" =>
              ctx.sessionId match
                case None => IO.pure(Left(ToolError("No session ID available")))
                case Some(sessionId) => createAction(taskStore, taskService, input, ctx, sessionId)
            case other => IO.pure(Left(ToolError(s"Unhandled action $other"))) // unreachable (guarded above)
    end if
  end call

  // ---------------------------------------------------------------------------
  // action = list — all pending tasks across ALL sessions
  // ---------------------------------------------------------------------------

  private def listAction(taskStore: ScheduledTaskStore): IO[Either[ToolError, String]] =
    taskStore.getAllPendingTasks.map { tasks =>
      if tasks.isEmpty then Right("No pending scheduled tasks.")
      else
        val lines = tasks.sortBy(_.triggerAt).map { t =>
          val status = if t.enabled then "active" else "PAUSED"
          val nm = t.name.getOrElse("-")
          s"- ${t.id} | ${formatTime(t.triggerAt)} | ${t.repeat.getOrElse("once")} | $status | name: $nm | ${t.content.take(60)}"
        }
        Right(
          s"Pending scheduled tasks (${tasks.size}) — cancel via Schedule {action:\"cancel\", id:\"<id>\"}:\n" +
            lines.mkString("\n")
        )
    }

  // ---------------------------------------------------------------------------
  // action = cancel — by id, across sessions
  // ---------------------------------------------------------------------------

  private def cancelAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val idOpt = input("id").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    idOpt match
      case None =>
        IO.pure(Left(ToolError("cancel requires id — call Schedule {action:\"list\"} first, then pass the task's id.")))
      case Some(id) =>
        taskStore.findTaskById(id).flatMap {
          case None =>
            taskStore.getAllPendingTasks.map { pending =>
              val ids = if pending.isEmpty then "(none)" else pending.map(_.id).mkString(", ")
              Left(
                ToolError(
                  s"No scheduled task with id \"$id\". Pending ids: $ids — if this looks stale, call action=list to refresh."
                )
              )
            }
          case Some(t) =>
            for
              _ <- taskStore.deleteTask(t.sessionId, id)
              _ <- taskService match
                case Some(svc) => svc.notifyTaskChange()
                case None => IO.unit
              // Broadcast so the frontend reminder panel updates in real-time
              _ <- ctx.wsSend match
                case Some(send) =>
                  send(
                    Json.obj(
                      "type" -> "scheduledTaskDeleted".asJson,
                      "id" -> id.asJson,
                      "sessionId" -> t.sessionId.asJson
                    )
                  )
                case None => IO.unit
            yield Right(
              s"Cancelled task $id (was ${formatTime(t.triggerAt)}${t.repeat.map(r => s", $r").getOrElse("")}): " +
                s"${t.content.take(60)} — it will not fire."
            )
        }
    end match
  end cancelAction

  // ---------------------------------------------------------------------------
  // action = create — legacy behavior + name upsert
  // ---------------------------------------------------------------------------

  private def createAction(
    taskStore: ScheduledTaskStore,
    taskService: Option[ScheduledTaskService],
    input: JsonObject,
    ctx: ToolContext,
    sessionId: String
  ): IO[Either[ToolError, String]] =
    val content = input("content").flatMap(_.asString).getOrElse("")
    val repeat = input("repeat").flatMap(_.asString).filter(validRepeat.contains)
    val taskName = input("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
    val now = System.currentTimeMillis()

    parseTriggerAt(input("triggerAt"), now, ZoneId.systemDefault()) match
      case Left(msg) => IO.pure(Left(ToolError(msg)))
      case Right(triggerAt) =>
        if content.isBlank then
          IO.pure(
            Left(
              ToolError("content is required and must not be blank (for list/cancel use action=\"list\"/\"cancel\")")
            )
          )
        else if triggerAt <= now then
          IO.pure(
            Left(
              ToolError(
                s"triggerAt must be in the future (given $triggerAt = ${formatTime(triggerAt)}, " +
                  s"current ${formatTime(now)} = $now ms, zone ${ZoneId.systemDefault().getId})."
              )
            )
          )
        else
          val task = ScheduledTask.create(sessionId, content, triggerAt, None, repeat, taskName)
          for
            removed <- taskName match
              case Some(n) => taskStore.upsertTaskByName(task)
              case None => taskStore.addTask(task).as(Nil)
            _ <- taskService match
              case Some(svc) => svc.notifyTaskChange()
              case None => IO.unit
            // Broadcast removed (possibly cross-session) so other panels drop them
            _ <- ctx.wsSend match
              case Some(send) =>
                removed.traverse_(old =>
                  send(
                    Json.obj(
                      "type" -> "scheduledTaskDeleted".asJson,
                      "id" -> old.id.asJson,
                      "sessionId" -> old.sessionId.asJson
                    )
                  )
                )
              case None => IO.unit
            // Broadcast to frontend so the reminder panel updates in real-time
            _ <- ctx.wsSend match
              case Some(send) =>
                send(
                  Json.obj(
                    "type" -> "scheduledTaskCreated".asJson,
                    "sessionId" -> sessionId.asJson,
                    "task" -> Json.obj(
                      "id" -> task.id.asJson,
                      "content" -> task.content.asJson,
                      "triggerAt" -> task.triggerAt.asJson,
                      "createdAt" -> task.createdAt.asJson,
                      "referencePath" -> Json.Null,
                      "repeat" -> task.repeat.asJson,
                      "name" -> task.name.asJson
                    )
                  )
                )
              case None => IO.unit
          yield
            val replacedNote = removed match
              case Nil => ""
              case olds =>
                s" — replaced ${olds.size} existing task(s) with the same name (${olds.map(o => s"${o.id} @ ${formatTime(o.triggerAt)}").mkString(", ")})"
            val nameNote = taskName.map(n => s" [name: $n]").getOrElse("")
            Right(
              s"Scheduled task ${task.id} for ${formatTime(triggerAt)}" +
                repeat.map(r => s" (recurring: $r)").getOrElse("") +
                s"$nameNote: ${content.take(80)}$replacedNote"
            )
          end for
        end if
    end match
  end createAction

  // ---------------------------------------------------------------------------
  // triggerAt parsing: epoch-ms integer, ISO 8601, or natural language
  // ---------------------------------------------------------------------------

  /** Parse the triggerAt JSON value into epoch milliseconds. Pure & testable. */
  def parseTriggerAt(input: Option[Json], now: Long, zone: ZoneId): Either[String, Long] =
    input match
      case None =>
        Left("triggerAt is required: epoch-ms integer, ISO 8601 string, or natural-language time string.")
      case Some(json) =>
        json.asNumber.flatMap(_.toLong) match
          case Some(ms) => Right(ms)
          case None =>
            json.asString match
              case None =>
                Left(
                  s"triggerAt must be an epoch-ms integer or a time string, got JSON ${json.getClass.getSimpleName}."
                )
              case Some(raw) => parseTimeString(raw, now, zone)

  private def parseTimeString(raw: String, now: Long, zone: ZoneId): Either[String, Long] =
    val s = raw.trim.replaceAll("\\s+", " ")
    if s.isEmpty then Left("triggerAt string is empty.")
    // epoch-ms passed as a string ("1755298800000")
    else if s.matches("\\d{13}") then Right(s.toLong)
    else
      val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
      tryIso(s, zone)
        .orElse(tryTimeOfDay(s, nowZdt))
        .orElse(tryTomorrow(s, nowZdt))
        .orElse(tryRelative(s, now)) match
        case Some(ms) => Right(ms)
        case None =>
          Left(
            s"Cannot parse triggerAt: \"$raw\". Use epoch ms, ISO 8601 (e.g. 2026-08-16T21:00:00+08:00), " +
              s"or natural language (\"tomorrow at 21:00\", \"in 2 hours\", \"21:30\"). " +
              s"Current time: ${formatTime(now)} (${now} ms, zone ${zone.getId})."
          )

    end if

  end parseTimeString

  /** ISO 8601 with offset/Z, ISO local (T or space separated), or bare date. */
  private def tryIso(s: String, zone: ZoneId): Option[Long] =
    scala.util
      .Try(OffsetDateTime.parse(s))
      .toOption
      .map(_.toInstant.toEpochMilli)
      .orElse(scala.util.Try(LocalDateTime.parse(s)).toOption.map(_.atZone(zone).toInstant.toEpochMilli))
      .orElse(
        scala.util
          .Try(LocalDateTime.parse(s, SpaceDateTimeFormatter))
          .toOption
          .map(_.atZone(zone).toInstant.toEpochMilli)
      )
      .orElse(scala.util.Try(LocalDate.parse(s)).toOption.map(_.atStartOfDay(zone).toInstant.toEpochMilli))

  private val SpaceDateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

  /** "21:30" / "at 21:30" / "9:05:30" — today, or tomorrow when already past. */
  private val TimeOfDayRegex = """(?:at )?(\d{1,2}):(\d{2})(?::(\d{2}))?""".r

  private def tryTimeOfDay(s: String, nowZdt: ZonedDateTime): Option[Long] =
    s match
      case TimeOfDayRegex(h, m, sec) =>
        val (hh, mm, ss) = (h.toInt, m.toInt, Option(sec).map(_.toInt).getOrElse(0))
        if hh <= 23 && mm <= 59 && ss <= 59 then
          var t = nowZdt.toLocalDate.atTime(hh, mm, ss).atZone(nowZdt.getZone)
          if !t.toInstant.isAfter(nowZdt.toInstant) then t = t.plusDays(1)
          Some(t.toInstant.toEpochMilli)
        else None
      case _ => None

  /** "tomorrow" (defaults 09:00), "tomorrow 21:00", "tomorrow at 21:00". */
  private val TomorrowRegex = """tomorrow(?: (?:at )?(\d{1,2}):(\d{2})(?::(\d{2}))?)?""".r

  private def tryTomorrow(s: String, nowZdt: ZonedDateTime): Option[Long] =
    s.toLowerCase match
      case TomorrowRegex(h, m, sec) =>
        val (hh, mm, ss) = (
          Option(h).map(_.toInt).getOrElse(9),
          Option(m).map(_.toInt).getOrElse(0),
          Option(sec).map(_.toInt).getOrElse(0)
        )
        if hh <= 23 && mm <= 59 && ss <= 59 then
          val t = nowZdt.toLocalDate.plusDays(1).atTime(hh, mm, ss).atZone(nowZdt.getZone)
          Some(t.toInstant.toEpochMilli)
        else None
      case _ => None

  /** "in 30 minutes" / "in 2 hours" / "in 1 day" / "in 90m" (optionally "from now"). */
  private val InRegex = """(?i)in (\d+)\s*(minutes?|mins?|m|hours?|hrs?|h|days?|d)(?: from now)?""".r

  private def tryRelative(s: String, now: Long): Option[Long] =
    s match
      case InRegex(n, unit) =>
        val count = n.toLong
        val millis = unit.toLowerCase.take(1) match
          case "m" => count * 60_000L
          case "h" => count * 3_600_000L
          case "d" => count * 86_400_000L
        Some(now + millis)
      case _ => None

  private def formatTime(epochMs: Long): String =
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)

end ScheduleTool
