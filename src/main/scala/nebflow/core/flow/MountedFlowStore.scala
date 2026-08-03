package nebflow.core.flow

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Persists the list of mounted flows per session.
 *
 * Only stores flow instance names → flow definition names.
 * Run state is NOT persisted — flows reset to idle on restart
 * and can be re-triggered.
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/flows.json
 */
object MountedFlowStore:
  private val logger = NebflowLogger(getClass)

  case class MountedFlowEntry(name: String, flowName: String)

  given Encoder[MountedFlowEntry] = Encoder.instance { e =>
    Json.obj("name" -> e.name.asJson, "flowName" -> e.flowName.asJson)
  }

  given Decoder[MountedFlowEntry] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      flowName <- c.downField("flowName").as[String]
    yield MountedFlowEntry(name, flowName)
  }

  private def sessionDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId

  private def storeFile(sessionId: String): os.Path =
    sessionDir(sessionId) / "flows.json"

  /** Save the list of mounted flows (atomic write). */
  def save(sessionId: String, entries: List[MountedFlowEntry]): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val dir = sessionDir(sessionId)
      val file = storeFile(sessionId)
      val tmp = dir / "flows.json.tmp"
      IO.blocking {
        if !os.exists(dir) then os.makeDir.all(dir)
        os.write.over(tmp, Json.obj("flows" -> entries.asJson).noSpaces)
        os.move.over(tmp, file, replaceExisting = true)
      }.void

  /** Load the list of mounted flows. Returns empty if not found. */
  def load(sessionId: String): IO[List[MountedFlowEntry]] =
    val file = storeFile(sessionId)
    IO.blocking {
      if os.exists(file) then
        val content = os.read(file)
        parser
          .decode[Map[String, List[MountedFlowEntry]]](content)
          .toOption
          .flatMap(_.get("flows"))
          .filter(_.nonEmpty)
          .getOrElse:
            parser.decode[List[MountedFlowEntry]](content) match
              case Right(entries) => entries
              case Left(e) =>
                logger.warn(s"Failed to decode flows.json for session $sessionId: ${e.getMessage}")
                Nil
      else Nil
    }

  /** Delete the store file (used when session is deleted). */
  def delete(sessionId: String): IO[Unit] =
    val file = storeFile(sessionId)
    IO.blocking {
      if os.exists(file) then os.remove(file)
    }.void

end MountedFlowStore
