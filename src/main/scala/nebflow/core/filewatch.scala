package nebflow.core

import cats.effect.{IO, Ref}

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

class FileChangeTracker private (
  projectRoot: String,
  snapshotRef: Ref[IO, Map[String, Long]],
  modifiedByAgent: Ref[IO, Set[(String, Long)]],
  lastCheckRef: Ref[IO, Long]
):

  private val rootPath = Paths.get(projectRoot).toAbsolutePath.normalize

  private val DebounceMs: Long = 5 * 1000L // 5 seconds

  private def scanFiles(): Map[String, Long] =
    val stream = Files.walk(rootPath)
    try
      stream
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filter { p =>
          val rel = rootPath.relativize(p).toString
          val segments = rel.split(java.util.regex.Pattern.quote(java.io.File.separator))
          !segments.exists(FileChangeTracker.ExcludedDirs.contains) &&
          !FileChangeTracker.ExcludedFiles.contains(segments.last)
        }
        .map { p =>
          val rel = rootPath.relativize(p).toString
          val modTime =
            try Files.getLastModifiedTime(p).toMillis
            catch case _: Exception => 0L
          rel -> modTime
        }
        .toMap
    finally stream.close()

  /** Stat only files present in the previous snapshot. O(n) where n = snapshot size. */
  private def statKnown(known: Map[String, Long]): Map[String, Long] =
    known.flatMap { case (rel, _) =>
      val abs = rootPath.resolve(rel)
      try
        val mtime = Files.getLastModifiedTime(abs).toMillis
        Some(rel -> mtime)
      catch case _: Exception => None // deleted or unreadable
    }

  /**
   * Quick scan: stat only files from the previous snapshot.
   * Detects modifications and deletions, but NOT new files.
   * Much faster than full walk for large projects.
   */
  private def quickScan(known: Map[String, Long]): Map[String, Long] =
    statKnown(known)

  def checkChanges(): IO[Option[SystemReminder]] =
    for
      lastCheck <- lastCheckRef.get
      now <- IO(System.currentTimeMillis())
      result <-
        if now - lastCheck < DebounceMs then IO.pure(None)
        else
          for
            oldSnapshot <- snapshotRef.get
            // Bug 2 fix: atomically drain agentMods
            agentMods <- modifiedByAgent.getAndSet(Set.empty)
            // Bug 3 fix: stat only known files instead of full walk
            newSnapshot <- IO.blocking(statKnown(oldSnapshot))
            // Detect new files: files that appeared on disk but weren't in oldSnapshot.
            // Only do a full walk occasionally (every 60s) to keep this cheap.
            fullScanNeeded = oldSnapshot.nonEmpty &&
              now - lastCheck > 60 * 1000L
            updatedSnapshot <-
              if fullScanNeeded then IO.blocking(scanFiles())
              else IO.pure(newSnapshot)
            _ <- snapshotRef.set(updatedSnapshot)
            _ <- lastCheckRef.set(now)

            // Bug 4 fix: filter by (path, mtime) pair, not just path
            agentEntries = agentMods

            changed = (updatedSnapshot.toSet -- oldSnapshot.toSet)
              .filter { entry => !agentEntries.contains(entry) }
              .map(_._1)
              .toList
              .sorted

            deleted = (oldSnapshot.keySet -- updatedSnapshot.keySet)
              .filterNot(path => agentEntries.exists(_._1 == path))
              .toList
              .sorted
          yield
            val allChanges = changed.map("modified: " + _) ++ deleted.map("deleted: " + _)
            if allChanges.isEmpty then None
            else Some(SystemReminders.fileChanges(allChanges))
    yield result

  def recordAgentModification(path: String): IO[Unit] =
    IO.blocking {
      val absPath = Paths.get(path).toAbsolutePath.normalize
      val rel = rootPath.relativize(absPath).toString
      val modTime =
        try Files.getLastModifiedTime(absPath).toMillis
        catch case _: Exception => System.currentTimeMillis()
      (rel, modTime)
    }.flatMap { entry =>
      modifiedByAgent.update(_ + entry)
    }

end FileChangeTracker

object FileChangeTracker:

  val ExcludedDirs = Set(
    ".git",
    "target",
    ".bsp",
    ".metals",
    ".bloop",
    ".idea",
    ".vscode",
    "node_modules",
    ".claude",
    "dist"
  )
  val ExcludedFiles = Set(".DS_Store")

  def scanProject(projectRoot: String): Map[String, Long] =
    val rootPath = Paths.get(projectRoot).toAbsolutePath.normalize
    val stream = Files.walk(rootPath)
    try
      stream
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filter { p =>
          val rel = rootPath.relativize(p).toString
          val segments = rel.split(java.util.regex.Pattern.quote(java.io.File.separator))
          !segments.exists(ExcludedDirs.contains) &&
          !ExcludedFiles.contains(segments.last)
        }
        .map { p =>
          val rel = rootPath.relativize(p).toString
          val modTime =
            try Files.getLastModifiedTime(p).toMillis
            catch case _: Exception => 0L
          rel -> modTime
        }
        .toMap
    finally stream.close()

  def create(projectRoot: String): IO[FileChangeTracker] =
    for
      initialSnapshot <- IO.blocking(scanProject(projectRoot))
      snapshotRef <- Ref.of[IO, Map[String, Long]](initialSnapshot)
      agentRef <- Ref.of[IO, Set[(String, Long)]](Set.empty)
      lastCheckRef <- Ref.of[IO, Long](0L)
    yield new FileChangeTracker(projectRoot, snapshotRef, agentRef, lastCheckRef)
end FileChangeTracker
