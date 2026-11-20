package nebflow.agent

import java.nio.file.{Files, Path}
import io.circe.parser
import io.circe.syntax.*
import nebflow.core.AtomicJson

/** Durable per-session report identity reservation. Callers must provide a stable logical slot. */
private[agent] final class ChildReportJournal private (root: Path, session: String):
  def reserve(slot: String, body: String): String = ChildReportJournal.synchronized {
    require(slot.nonEmpty && session.nonEmpty, "report slot and session are required")
    val encoded = java.util.Base64.getUrlEncoder.withoutPadding()
      .encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val file = os.Path(root.toAbsolutePath) / s"child-report-$encoded.json"
    val prior = if os.exists(file) then parser.parse(os.read(file)).fold(throw _, identity)
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
        val updated = io.circe.Json.obj("next" -> (next + 1).asJson, "entries" -> io.circe.Json.fromJsonObject(entries.add(slot, entry)))
        os.makeDir.all(file / os.up)
        AtomicJson.rotateSyncDurable(file, updated.noSpaces)
        id
  }

private[agent] object ChildReportJournal:
  def open(root: Path, session: String): ChildReportJournal = new ChildReportJournal(root, session)
