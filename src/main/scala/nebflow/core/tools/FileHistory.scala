package nebflow.core.tools

import cats.effect.{IO, Ref}

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

/**
 * VS Code-style file history: snapshots file content before overwrites.
 *
 * Storage layout:
 *   ~/.nebflow/history/{pathHash}/{timestamp}           — snapshot content
 *   ~/.nebflow/history/{pathHash}/{timestamp}.identity   — agent identity (optional)
 *
 * Each file keeps up to `maxEntries` snapshots; oldest are evicted first.
 * Files larger than `maxFileSizeBytes` are skipped.
 *
 * The .identity file records which agent/session made the edit, enabling
 * cross-agent attribution in file history.
 */
class FileHistory private (
  private[tools] val historyRoot: Path,
  maxEntries: Int,
  maxFileSizeBytes: Long,
  // In-memory index: path -> sorted list of snapshot timestamps (newest first)
  index: Ref[IO, Map[String, Vector[Long]]]
):

  /**
   * Snapshot a file's current content before it gets overwritten.
   * No-op if the file doesn't exist or exceeds the size limit.
   *
   * @param filePath the file to snapshot
   * @param identity optional agent mailbox address (e.g. "Nebula/a296f1da/my-project")
   */
  def snapshot(filePath: Path, identity: Option[String] = None): IO[Unit] =
    // Phase 1: Copy file to history dir (blocking I/O)
    IO.blocking {
      if !Files.exists(filePath) || Files.isDirectory(filePath) then None
      else if Files.size(filePath) > maxFileSizeBytes then None
      else
        val key = FileHistory.pathHash(filePath)
        val dir = historyRoot.resolve(key)
        Files.createDirectories(dir)
        // Use nanoTime for unique filenames (monotonic, nanosecond precision)
        val ts = System.nanoTime()
        val dest = dir.resolve(ts.toString)
        Files.copy(filePath, dest)
        // Write identity metadata alongside the snapshot (if provided)
        identity.foreach { id =>
          val metaDest = dir.resolve(s"$ts.identity")
          Files.writeString(metaDest, id)
        }
        Some((key, dir, ts))
    }.flatMap {
      case None => IO.unit
      case Some((key, dir, ts)) =>
        // Phase 2: Update in-memory index (Ref operation, not blocking)
        index.update { m =>
          val entries = m.getOrElse(key, Vector.empty)
          val updated = (entries :+ ts).sortBy(-_)
          m.updated(key, if updated.size > maxEntries then updated.take(maxEntries) else updated)
        } *>
          // Phase 3: Clean up excess files on disk (blocking)
          IO.blocking(cleanupOld(key, dir))
    }

  /**
   * Return the N most recently snapshotted file paths.
   * Uses a reverse mapping (hash -> path) maintained on first snapshot.
   */
  def recentFiles(n: Int): IO[List[Path]] =
    // We don't store original paths on disk (only hashes), so this is best-effort
    // from the in-memory index. For the compact use case, ReadTracker is still the
    // primary source of recent files. This method is here for future use.
    IO.pure(Nil)

  /** Clear all history. */
  def clear(): IO[Unit] =
    IO.blocking {
      if Files.exists(historyRoot) then
        Files
          .walk(historyRoot)
          .sorted(java.util.Comparator.reverseOrder())
          .iterator()
          .asScala
          .foreach(Files.deleteIfExists)
    } *> index.set(Map.empty)

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private def cleanupOld(key: String, dir: Path): Unit =
    val files = Files
      .list(dir)
      .iterator()
      .asScala
      .toList
      .filter(f => Files.isRegularFile(f) && !f.getFileName.toString.endsWith(".identity"))
      .sortBy(f => f.getFileName.toString.toLongOption.getOrElse(0L))(Ordering[Long].reverse)
    if files.size > maxEntries then files.drop(maxEntries).foreach { f =>
      Files.deleteIfExists(f)
      // Also clean up associated .identity file
      val identityFile = dir.resolve(s"${f.getFileName}.identity")
      Files.deleteIfExists(identityFile)
    }

end FileHistory

object FileHistory:

  val DefaultMaxEntries = 50
  val DefaultMaxFileSizeBytes = 1024L * 1024 // 1 MB

  private def pathHash(path: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(path.toString.getBytes("UTF-8"))
    md.digest().take(16).map(b => String.format("%02x", b)).mkString

  def create(
    historyRoot: Path = Paths.get(System.getProperty("user.home"), ".nebflow", "history"),
    maxEntries: Int = DefaultMaxEntries,
    maxFileSizeBytes: Long = DefaultMaxFileSizeBytes
  ): IO[FileHistory] =
    IO.blocking(Files.createDirectories(historyRoot)) *>
      Ref
        .of[IO, Map[String, Vector[Long]]](Map.empty)
        .map(new FileHistory(historyRoot, maxEntries, maxFileSizeBytes, _))
end FileHistory
