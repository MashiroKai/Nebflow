package nebflow.core.flow

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Persists the list of mounted pipelines per session.
 *
 * Only stores pipeline instance names → flow definition names.
 * Run state (step progress, results) is NOT persisted — pipelines
 * reset to idle on restart and can be re-triggered.
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/pipelines.json
 */
object PipelineStore:
  private val logger = NebflowLogger(getClass)

  case class PipelineEntry(name: String, flowName: String)

  given Encoder[PipelineEntry] = Encoder.instance { e =>
    Json.obj("name" -> e.name.asJson, "flowName" -> e.flowName.asJson)
  }

  given Decoder[PipelineEntry] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      flowName <- c.downField("flowName").as[String]
    yield PipelineEntry(name, flowName)
  }

  private def sessionDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId

  private def storeFile(sessionId: String): os.Path =
    sessionDir(sessionId) / "pipelines.json"

  /** Save the list of mounted pipelines (atomic write). */
  def save(sessionId: String, entries: List[PipelineEntry]): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val dir = sessionDir(sessionId)
      val file = storeFile(sessionId)
      val tmp = dir / "pipelines.json.tmp"
      IO.blocking {
        if !os.exists(dir) then os.makeDir.all(dir)
        os.write.over(tmp, Json.obj("pipelines" -> entries.asJson).noSpaces)
        os.move.over(tmp, file, replaceExisting = true)
      }.void

  /** Load the list of mounted pipelines. Returns empty if not found. */
  def load(sessionId: String): IO[List[PipelineEntry]] =
    val file = storeFile(sessionId)
    IO.blocking {
      if os.exists(file) then
        val content = os.read(file)
        parser.decode[List[PipelineEntry]](content) match
          case Right(entries) => entries
          case Left(e) =>
            logger.warn(s"Failed to decode pipelines.json for session $sessionId: ${e.getMessage}")
            Nil
      else Nil
    }

  /** Delete the store file (used when session is deleted). */
  def delete(sessionId: String): IO[Unit] =
    val file = storeFile(sessionId)
    IO.blocking {
      if os.exists(file) then os.remove(file)
    }.void

end PipelineStore
