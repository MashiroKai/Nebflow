package nebflow.core.flow

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil

/**
 * Persistence layer for FlowTree snapshots.
 *
 * The entire tree state (all branches) is stored as a single JSON file per session:
 *   ~/.nebflow/sessions/<sessionId>/flow-tree.json
 *
 * Uses atomic write (tmp -> rename) to prevent corruption on crash.
 */
object FlowTreeStore:
  private val logger = NebflowLogger(getClass)

  private def sessionDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId

  private def snapshotFile(sessionId: String): os.Path =
    sessionDir(sessionId) / "flow-tree.json"

  /** Save the complete tree snapshot (atomic write: tmp -> rename). */
  def save(snapshot: FlowTreeSnapshot): IO[Unit] =
    val dir = sessionDir(snapshot.sessionId)
    val file = snapshotFile(snapshot.sessionId)
    val tmp = dir / "flow-tree.json.tmp"
    IO.blocking {
      if !os.exists(dir) then os.makeDir.all(dir)
      os.write.over(tmp, snapshot.asJson.noSpaces)
      os.move.over(tmp, file, replaceExisting = true)
    }.void

  /** Load the tree snapshot for a session. Returns None if not found. */
  def load(sessionId: String): IO[Option[FlowTreeSnapshot]] =
    val file = snapshotFile(sessionId)
    IO.blocking {
      if os.exists(file) then
        val content = os.read(file)
        decode[FlowTreeSnapshot](content) match
          case Right(s) => Some(s)
          case Left(e) =>
            logger.warn(s"Failed to decode flow tree snapshot for session $sessionId: ${e.getMessage}")
            None
      else None
    }

  /** Delete the tree snapshot (used when session is deleted). */
  def delete(sessionId: String): IO[Unit] =
    val file = snapshotFile(sessionId)
    IO.blocking {
      if os.exists(file) then os.remove(file)
    }.void

end FlowTreeStore
