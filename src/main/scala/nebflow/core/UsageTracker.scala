package nebflow.core

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.NebflowLogger

import java.time.*

case class ActivityRecord(timestamp: Long, sessionId: String, eventType: String)

object ActivityRecord:

  given Encoder[ActivityRecord] = Encoder.instance(r =>
    io.circe.Json
      .obj("timestamp" -> r.timestamp.asJson, "sessionId" -> r.sessionId.asJson, "eventType" -> r.eventType.asJson)
  )

  given Decoder[ActivityRecord] = Decoder.instance(c =>
    for
      ts <- c.downField("timestamp").as[Long]; sid <- c.downField("sessionId").as[String];
      et <- c.downField("eventType").as[String]
    yield ActivityRecord(ts, sid, et)
  )

case class TimeWindow(dayOfWeek: Int, startHour: Int, durationHours: Int)

object TimeWindow:

  given Encoder[TimeWindow] = Encoder.instance(w =>
    io.circe.Json.obj(
      "dayOfWeek" -> w.dayOfWeek.asJson,
      "startHour" -> w.startHour.asJson,
      "durationHours" -> w.durationHours.asJson
    )
  )

  given Decoder[TimeWindow] = Decoder.instance(c =>
    for
      d <- c.downField("dayOfWeek").as[Int]; h <- c.downField("startHour").as[Int];
      dur <- c.downField("durationHours").as[Int]
    yield TimeWindow(d, h, dur)
  )

end TimeWindow

case class UsagePattern(
  hourlyActivity: Vector[Double],
  dailyActivity: Vector[Double],
  idleWindows: List[TimeWindow],
  totalRecords: Int,
  lastUpdated: Long
):
  def isReliable: Boolean = totalRecords > 500

  def isInIdleWindow(now: Long): Boolean =
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    val dow = zdt.getDayOfWeek.getValue % 7
    val hour = zdt.getHour
    idleWindows.exists(w => w.dayOfWeek == dow && hour >= w.startHour && hour < w.startHour + w.durationHours)

object UsagePattern:

  given Encoder[UsagePattern] = Encoder.instance(p =>
    io.circe.Json.obj(
      "hourlyActivity" -> p.hourlyActivity.asJson,
      "dailyActivity" -> p.dailyActivity.asJson,
      "idleWindows" -> p.idleWindows.asJson,
      "totalRecords" -> p.totalRecords.asJson,
      "lastUpdated" -> p.lastUpdated.asJson
    )
  )

  given Decoder[UsagePattern] = Decoder.instance(c =>
    for
      ha <- c.downField("hourlyActivity").as[Vector[Double]]
      da <- c.downField("dailyActivity").as[Vector[Double]]
      iw <- c.downField("idleWindows").as[List[TimeWindow]]
      tr <- c.downField("totalRecords").as[Int]
      lu <- c.downField("lastUpdated").as[Long]
    yield UsagePattern(ha, da, iw, tr, lu)
  )
  val empty = UsagePattern(Vector.fill(24)(0.0), Vector.fill(7)(0.0), Nil, 0, 0L)

end UsagePattern

object UsageTracker:
  private val logger = NebflowLogger.forName("nebflow.usage")
  private val logPath = PathUtil.dataRoot / "usage-log.jsonl"
  private val patternPath = PathUtil.dataRoot / "usage-pattern.json"
  private val MaxRecords = 10000
  private val TrimTo = 5000

  def record(eventType: String, sessionId: String): IO[Unit] = IO.blocking {
    val entry = ActivityRecord(System.currentTimeMillis(), sessionId, eventType)
    os.write.append(logPath, entry.asJson.noSpaces + "\n", createFolders = true)
  }.void

  def analyzePattern(): IO[UsagePattern] = IO
    .blocking {
      if !os.exists(logPath) then UsagePattern.empty
      else
        val lines = os.read.lines(logPath)
        val records = lines.flatMap(line => decode[ActivityRecord](line).toOption).toList

        val hourlyCounts = new Array[Int](24)
        val dailyCounts = new Array[Int](7)
        records.foreach { r =>
          val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.timestamp), ZoneId.systemDefault())
          hourlyCounts(zdt.getHour) += 1
          dailyCounts(zdt.getDayOfWeek.getValue % 7) += 1
        }
        val maxH = math.max(1, hourlyCounts.max)
        val maxD = math.max(1, dailyCounts.max)
        val hourly = hourlyCounts.map(_.toDouble / maxH).toVector
        val daily = dailyCounts.map(_.toDouble / maxD).toVector

        val idleHours = hourly.zipWithIndex.filter(_._1 < 0.2).map(_._2).toList
        val idleWindows = groupConsecutiveHours(idleHours)
          .filter(_.durationHours >= 2)
          .flatMap(w => (0 to 6).map(dow => TimeWindow(dow, w.startHour, w.durationHours)))

        val pattern = UsagePattern(hourly, daily, idleWindows, records.size, System.currentTimeMillis())
        try os.write.over(patternPath, pattern.asJson.spaces2, createFolders = true)
        catch case _: Exception => ()

        // Trim JSONL if too large
        if records.size > MaxRecords then
          val trimmed = lines.takeRight(TrimTo)
          os.write.over(logPath, trimmed.mkString("\n") + "\n")

        pattern
    }
    .handleErrorWith(e => IO(logger.warn(s"analyzePattern failed: ${e.getMessage}")).as(UsagePattern.empty))

  /** Load the last persisted pattern from disk without recomputing from the log. */
  def loadPattern(): IO[UsagePattern] = IO.blocking {
    if !os.exists(patternPath) then UsagePattern.empty
    else decode[UsagePattern](os.read(patternPath)).getOrElse(UsagePattern.empty)
  }.handleErrorWith(e => IO(logger.warn(s"loadPattern failed: ${e.getMessage}")).as(UsagePattern.empty))

  private def groupConsecutiveHours(hours: List[Int]): List[TimeWindow] =
    hours.foldLeft(List.empty[TimeWindow]) { (acc, h) =>
      acc.lastOption match
        case Some(w) if w.startHour + w.durationHours == h => acc.init :+ w.copy(durationHours = w.durationHours + 1)
        case _ => acc :+ TimeWindow(0, h, 1)
    }
end UsageTracker
