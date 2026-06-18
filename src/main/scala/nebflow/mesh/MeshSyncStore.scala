package nebflow.mesh

import nebflow.core.PathUtil

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

/**
 * Manages local sync snapshots — the file fingerprints after the last successful sync.
 * Used to detect which files changed since last sync without re-hashing everything.
 *
 * State stored in ~/.nebflow/mesh/sync-snap.json
 */
class MeshSyncStore private (
  snapshotRef: Ref[IO, Map[String, FileFingerprint]],
  snapshotPath: os.Path
):
  private val logger = NebflowLogger.forName("nebflow.mesh.sync-store")

  /** Get the last-known fingerprint for a file. */
  def getSnapshot(path: String): IO[Option[FileFingerprint]] =
    snapshotRef.get.map(_.get(path))

  /** Get all snapshots. */
  def getAllSnapshots: IO[Map[String, FileFingerprint]] =
    snapshotRef.get

  /** Update snapshot for a file (after successful upload/download). */
  def updateSnapshot(path: String, fp: FileFingerprint): IO[Unit] =
    snapshotRef.update(_ + (path -> fp)) *> flush

  /** Update snapshots for multiple files at once. */
  def updateSnapshots(updates: Map[String, FileFingerprint]): IO[Unit] =
    if updates.isEmpty then IO.unit
    else snapshotRef.update(_ ++ updates) *> flush

  /** Remove snapshot for a deleted file. */
  def removeSnapshot(path: String): IO[Unit] =
    snapshotRef.update(_ - path) *> flush

  /** Persist current snapshots to disk. */
  def flush: IO[Unit] =
    snapshotRef.get.flatMap { snapshots =>
      IO.blocking {
        os.write.over(snapshotPath, snapshots.asJson.spaces2, createFolders = true)
      }
    }

end MeshSyncStore

object MeshSyncStore:
  private val defaultPath = PathUtil.dataRoot / "mesh" / "sync-snap.json"

  def load(path: os.Path = defaultPath): IO[MeshSyncStore] =
    IO.blocking {
      if os.exists(path) then
        decode[Map[String, FileFingerprint]](os.read(path)) match
          case Right(snapshots) => snapshots
          case Left(_) => Map.empty
      else Map.empty
    }.flatMap { snapshots =>
      Ref.of[IO, Map[String, FileFingerprint]](snapshots).map(ref => new MeshSyncStore(ref, path))
    }
end MeshSyncStore
