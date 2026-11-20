package nebflow.agent

import java.nio.file.{Files, Path}
import io.circe.parser
import io.circe.syntax.*
import nebflow.core.AtomicJson

/** Durable per-session report identity reservation. Callers must provide a stable logical slot. */
private[agent] final class ChildReportJournal private (root: Path, session: String):

  private def protocolFile: os.Path =
    val encoded = java.util.Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    os.Path(root.toAbsolutePath) / s"child-requests-$encoded.json"

  private def readProtocol(): io.circe.Json =
    if os.exists(protocolFile) then parser.parse(os.read(protocolFile)).fold(throw _, identity)
    else io.circe.Json.obj("next" -> 1L.asJson, "entries" -> io.circe.Json.obj())

  private def writeProtocol(value: io.circe.Json): Unit =
    os.makeDir.all(protocolFile / os.up)
    AtomicJson.rotateSyncDurable(protocolFile, value.noSpaces)

  def beginRequest(): Long = ChildReportJournal.synchronized {
    val state = readProtocol()
    val next = state.hcursor.get[Long]("next").fold(throw _, identity)
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    require(
      !entries.values.exists(_.hcursor.get[String]("status").toOption.contains("open")),
      "open request must be recovered before allocation"
    )
    val entry = io.circe.Json.obj("status" -> "open".asJson)
    writeProtocol(
      io.circe.Json
        .obj("next" -> (next + 1).asJson, "entries" -> io.circe.Json.fromJsonObject(entries.add(next.toString, entry)))
    )
    next
  }

  def prepareReport(slot: Long, body: String): String = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    val status = entry.hcursor.get[String]("status").fold(throw _, identity)
    val id = s"child-report:$session:$slot"
    if status == "prepared" then
      require(entry.hcursor.get[String]("body").toOption.contains(body), "conflicting report body")
    else
      require(status == "open", "slot is closed")
      val replacement = io.circe.Json.obj("status" -> "prepared".asJson, "body" -> body.asJson, "id" -> id.asJson)
      writeProtocol(
        state.mapObject(_.add("entries", io.circe.Json.fromJsonObject(entries.add(slot.toString, replacement))))
      )
    id
  }

  def openRequests(): List[Long] = ChildReportJournal.synchronized {
    val entries = readProtocol().hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries.toList.collect {
      case (key, entry) if entry.hcursor.get[String]("status").toOption.contains("open") => key.toLong
    }.sorted
  }

  def pendingRecovery(): List[(String, String)] = ChildReportJournal.synchronized {
    val entries = readProtocol().hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries.toList.sortBy(_._1.toLong).flatMap { case (_, entry) =>
      val c = entry.hcursor
      if c.get[String]("status").toOption.exists(s => s == "prepared" || s == "parked") then
        Some((c.get[String]("id").fold(throw _, identity), c.get[String]("body").fold(throw _, identity)))
      else None
    }
  }

  def park(slot: Long): Unit = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    require(entry.hcursor.get[String]("status").toOption.contains("prepared"), "only prepared reports may park")
    writeProtocol(
      state.mapObject(
        _.add(
          "entries",
          io.circe.Json.fromJsonObject(entries.add(slot.toString, entry.mapObject(_.add("status", "parked".asJson))))
        )
      )
    )
  }

  def closeNoReport(slot: Long): Unit = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    require(
      entry.hcursor.get[String]("status").toOption.contains("open"),
      "only open requests can close without report"
    )
    writeProtocol(
      state.mapObject(
        _.add(
          "entries",
          io.circe.Json.fromJsonObject(entries.add(slot.toString, entry.mapObject(_.add("status", "no-report".asJson))))
        )
      )
    )
  }

  def markFlushed(slot: Long): Unit = ChildReportJournal.synchronized {
    val state = readProtocol()
    val entries = state.hcursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    val entry = entries(slot.toString).getOrElse(throw new IllegalArgumentException("unknown slot"))
    val status = entry.hcursor.get[String]("status").fold(throw _, identity)
    require(status == "prepared" || status == "parked" || status == "flushed", "only prepared reports may be flushed")
    writeProtocol(
      state.mapObject(
        _.add(
          "entries",
          io.circe.Json.fromJsonObject(entries.add(slot.toString, entry.mapObject(_.add("status", "flushed".asJson))))
        )
      )
    )
  }

  def reserve(slot: String, body: String): String = ChildReportJournal.synchronized {
    require(slot.nonEmpty && session.nonEmpty, "report slot and session are required")
    val encoded = java.util.Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val file = os.Path(root.toAbsolutePath) / s"child-report-$encoded.json"
    val prior =
      if os.exists(file) then parser.parse(os.read(file)).fold(throw _, identity)
      else io.circe.Json.obj("next" -> 1L.asJson, "entries" -> io.circe.Json.obj())
    val cursor = prior.hcursor
    val next = cursor.get[Long]("next").fold(throw _, identity)
    val entries = cursor.get[io.circe.JsonObject]("entries").fold(throw _, identity)
    entries(slot) match
      case Some(value) =>
        val c = value.hcursor
        val existing = c.get[String]("body").fold(throw _, identity)
        require(existing == body, "conflicting body for report logical slot")
        c.get[String]("id").fold(throw _, identity)
      case None =>
        val id = s"child-report:$session:$next"
        val entry = io.circe.Json.obj("id" -> id.asJson, "body" -> body.asJson)
        val updated = io.circe.Json
          .obj("next" -> (next + 1).asJson, "entries" -> io.circe.Json.fromJsonObject(entries.add(slot, entry)))
        os.makeDir.all(file / os.up)
        AtomicJson.rotateSyncDurable(file, updated.noSpaces)
        id
  }

end ChildReportJournal

private[agent] object ChildReportJournal:
  def open(root: Path, session: String): ChildReportJournal = new ChildReportJournal(root, session)
