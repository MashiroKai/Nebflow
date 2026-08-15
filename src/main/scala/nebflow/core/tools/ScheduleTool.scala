package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.scheduler.ScheduledTask

import java.time.{Instant, LocalDate, LocalDateTime, OffsetDateTime, ZoneId, ZonedDateTime}

object ScheduleTool extends Tool:
  val name = "Schedule"

  val description =
    """Schedule a task to fire at a specified time, optionally recurring. At the scheduled
time the content is injected into your conversation as an instruction — you then execute it.

## When to Use

- 定时汇报 — schedule a progress report for a specific time ("at 17:00, summarize today's work")
- 定时维护 — periodic maintenance ("every Monday morning, review and clean up memory")
- 闲时执行 — defer a non-urgent task to an idle window ("at 23:00 when the user is idle, consolidate memory")
- 高峰规避 — avoid peak LLM pricing hours ("at 09:00 tomorrow, run the batch analysis")
- 用户提醒 — remind the user about something at a future time

## Fields

- **content** (required): Task content — a natural-language instruction describing what you
  should do when the task fires. Write it as a direct command you would execute ("汇报 XX 进度",
  "整理记忆", "review the latest commits"). Include any necessary context (which project, what scope).
- **triggerAt** (required): When the task should fire. Must be in the future. Accepted formats:
  1. Epoch milliseconds (UTC), e.g. 1755298800000
  2. ISO 8601 with zone, e.g. "2026-08-16T21:00:00+08:00" (safest — no ambiguity)
  3. ISO 8601 / date without zone, e.g. "2026-08-16T21:00", "2026-08-16 21:00", "2026-08-16" —
     interpreted in the system timezone
  4. Natural language: "21:30" or "at 21:30" (today, or tomorrow if already past),
     "tomorrow" (09:00), "tomorrow at 21:00", "in 30 minutes", "in 2 hours", "in 1 day"
- **repeat** (optional): Recurrence pattern. One of "hourly", "daily", "weekly". Omit for a one-shot task.

## Notes

- The task fires as an ExternalEvent in the current session; the injected content is the
  instruction you receive.
- For recurring tasks, the next fire time is computed from the previous triggerAt (not wall clock), maintaining consistent intervals."""

  private val validRepeat = Set("hourly", "daily", "weekly")

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "content" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Task content — what should happen when the task fires".asJson
        ),
        "triggerAt" -> Json.obj(
          "type" -> Json.arr("integer".asJson, "string".asJson),
          "description" ->
            """When the task fires. Epoch ms (integer), ISO 8601 ("2026-08-16T21:00:00+08:00"), or natural language ("tomorrow at 21:00", "in 2 hours", "21:30"). Must be in the future.""".asJson
        ),
        "repeat" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("hourly".asJson, "daily".asJson, "weekly".asJson),
          "description" -> "Recurrence pattern. Omit for one-shot.".asJson
        )
      ),
      "required" -> Json.arr("content".asJson, "triggerAt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val content = input("content").flatMap(_.asString).getOrElse("")
    val repeat = input("repeat").flatMap(_.asString).getOrElse("once")
    val triggerAt = input("triggerAt").flatMap(_.asNumber.flatMap(_.toLong)).getOrElse(0L)
    s"Schedule($repeat @ $triggerAt: $content)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Scheduled task") then "scheduled"
    else "failed"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case None => IO.pure(Left(ToolError("SharedResources not available")))
      case Some(sr) =>
        ctx.sessionId match
          case None => IO.pure(Left(ToolError("No session ID available")))
          case Some(sessionId) =>
            val content = input("content").flatMap(_.asString).getOrElse("")
            val repeat = input("repeat").flatMap(_.asString).filter(validRepeat.contains)
            val now = System.currentTimeMillis()

            parseTriggerAt(input("triggerAt"), now, ZoneId.systemDefault()) match
              case Left(msg) => IO.pure(Left(ToolError(msg)))
              case Right(triggerAt) =>
                if content.isBlank then IO.pure(Left(ToolError("content is required and must not be blank")))
                else if triggerAt <= now then
                  IO.pure(Left(ToolError(s"triggerAt must be in the future (given $triggerAt = ${formatTime(triggerAt)}, current ${formatTime(now)}).")))
                else
                  val task = ScheduledTask.create(sessionId, content, triggerAt, None, repeat)
                  for
                    _ <- sr.scheduledTaskStore.addTask(task)
                    _ <- sr.scheduledTaskService match
                      case Some(svc) => svc.notifyTaskChange()
                      case None => IO.unit
                    // Broadcast to frontend so the reminder panel updates in real-time
                    _ <- ctx.wsSend match
                      case Some(send) =>
                        send(
                          io.circe.Json.obj(
                            "type" -> "scheduledTaskCreated".asJson,
                            "sessionId" -> sessionId.asJson,
                            "task" -> io.circe.Json.obj(
                              "id" -> task.id.asJson,
                              "content" -> task.content.asJson,
                              "triggerAt" -> task.triggerAt.asJson,
                              "createdAt" -> task.createdAt.asJson,
                              "referencePath" -> io.circe.Json.Null,
                              "repeat" -> task.repeat.asJson
                            )
                          )
                        )
                      case None => IO.unit
                  yield Right(
                    s"Scheduled task ${task.id} for ${formatTime(triggerAt)}" +
                      repeat.map(r => s" (recurring: $r)").getOrElse("") +
                      s": ${content.take(80)}"
                  )
                  end for
                end if
            end match

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
                Left(s"triggerAt must be an epoch-ms integer or a time string, got JSON ${json.getClass.getSimpleName}.")
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

  /** ISO 8601 with offset/Z, ISO local (T or space separated), or bare date. */
  private def tryIso(s: String, zone: ZoneId): Option[Long] =
    scala.util.Try(OffsetDateTime.parse(s)).toOption.map(_.toInstant.toEpochMilli)
      .orElse(scala.util.Try(LocalDateTime.parse(s)).toOption.map(_.atZone(zone).toInstant.toEpochMilli))
      .orElse(
        scala.util.Try(LocalDateTime.parse(s, SpaceDateTimeFormatter)).toOption.map(_.atZone(zone).toInstant.toEpochMilli)
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
        val (hh, mm, ss) = (Option(h).map(_.toInt).getOrElse(9), Option(m).map(_.toInt).getOrElse(0), Option(sec).map(_.toInt).getOrElse(0))
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
