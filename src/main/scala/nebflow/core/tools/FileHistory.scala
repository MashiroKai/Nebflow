package nebflow.core.tools

import cats.effect.{IO, Ref}

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

/** VS Code-style file history: snapshots file content before overwrites.
  *
  * Storage layout:
  *   ~/.nebflow/history/{pathHash}/{timestamp}
  *
  * Each file keeps up to `maxEntries` snapshots; oldest are evicted first.
  * Files larger than `maxFileSizeBytes` are skipped.
  */
class FileHistory private (
  historyRoot: Path,
  maxEntries: Int,
  maxFileSizeBytes: Long,
  // In-memory index: path -> sorted list of snapshot timestamps (newest first)
  index: Ref[IO, Map[String, Vector[Long]]]
):

  /** Snapshot a file's current content before it gets overwritten.
    * No-op if the file doesn't exist or exceeds the size limit.
    */
  def snapshot(filePath: Path): IO[Unit] =
    IO.blocking {
      if !Files.exists(filePath) || Files.isDirectory(filePath) then ()
      else if Files.size(filePath) > maxFileSizeBytes then ()
      else
        val key = FileHistory.pathHash(filePath)
        val dir = historyRoot.resolve(key)
        Files.createDirectories(dir)
        val ts = System.currentTimeMillis()
        val dest = dir.resolve(ts.toString)
        Files.copy(filePath, dest)
        // Update in-memory index
        index.update { m =>
          val entries = m.getOrElse(key, Vector.empty)
          val updated = (entries :+ ts).sortBy(-_)
          m.updated(key, if updated.size > maxEntries then updated.take(maxEntries) else updated)
        }
        // Clean up excess snapshots on disk
        cleanupOld(key, dir)
    }

  /** Return the N most recently snapshotted file paths.
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
        Files.walk(historyRoot).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
    } *> index.set(Map.empty)

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private def cleanupOld(key: String, dir: Path): Unit =
    val files = Files.list(dir).iterator().asScala.toList
      .filter(f => Files.isRegularFile(f))
      .sortBy(f => f.getFileName.toString.toLongOption.getOrElse(0L))(Ordering[Long].reverse)
    if files.size > maxEntries then
      files.drop(maxEntries).foreach(f => Files.deleteIfExists(f))

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
      Ref.of[IO, Map[String, Vector[Long]]](Map.empty).map(new FileHistory(historyRoot, maxEntries, maxFileSizeBytes, _))
