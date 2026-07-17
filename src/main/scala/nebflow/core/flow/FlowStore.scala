package nebflow.core.flow

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil

/**
 * Persistence layer for Flow snapshots.
 *
 * Each flow's state is stored as a JSON file under the session directory:
 *   ~/.nebflow/sessions/<sessionId>/flows/<flowId>.json
 *
 * On restart, SessionStore loads the session, then FlowStore.listRestorable
 * returns flows that were still running (phase != Completed/Failed) and need
 * to be restored.
 */
object FlowStore:
  private val logger = NebflowLogger(getClass)

  private def flowsDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "flows"

  private def flowFile(sessionId: String, flowId: String): os.Path =
    flowsDir(sessionId) / s"$flowId.json"

  /** Save a flow snapshot to disk (atomic write). */
  def save(sessionId: String, flowId: String, snapshot: FlowSnapshot): IO[Unit] =
    val dir = flowsDir(sessionId)
    val file = flowFile(sessionId, flowId)
    val tmp = file / os.up / s"$flowId.json.tmp"
    IO.blocking {
      if !os.exists(dir) then os.makeDir.all(dir)
      os.write.over(tmp, snapshot.asJson.noSpaces)
      os.move.over(tmp, file, replaceExisting = true)
    }.void

  /** Load a flow snapshot from disk. */
  def load(sessionId: String, flowId: String): IO[Option[FlowSnapshot]] =
    val file = flowFile(sessionId, flowId)
    IO.blocking {
      if os.exists(file) then
        val content = os.read(file)
        decode[FlowSnapshot](content) match
          case Right(s) => Some(s)
          case Left(e) =>
            logger.warn(s"Failed to decode flow snapshot $flowId: ${e.getMessage}")
            None
      else None
    }

  /** List all flow IDs for a session. */
  def listFlows(sessionId: String): IO[List[String]] =
    val dir = flowsDir(sessionId)
    IO.blocking {
      if os.exists(dir) then
        os.list(dir)
          .filter(_.last.endsWith(".json"))
          .map(_.last.stripSuffix(".json"))
          .toList
      else Nil
    }

  /** List flow IDs that need restoration (phase is not terminal). */
  def listRestorable(sessionId: String): IO[List[String]] =
    listFlows(sessionId).flatMap { ids =>
      ids.traverseFilter(id =>
        load(sessionId, id).map(_.filter(s => s.phase != "Completed" && s.phase != "Failed").map(_ => id))
      )
    }

  /** Delete a flow snapshot (for completed/failed flows after retention period). */
  def delete(sessionId: String, flowId: String): IO[Unit] =
    val file = flowFile(sessionId, flowId)
    IO.blocking {
      if os.exists(file) then os.remove(file)
    }.void

end FlowStore
