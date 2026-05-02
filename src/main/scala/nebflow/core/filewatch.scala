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
          val segments = rel.split(java.io.File.separator)
          // Exclude files in excluded directories (any depth)
          !segments.exists(FileChangeTracker.ExcludedDirs.contains) &&
          // Exclude specific filenames
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

  def checkChanges(): IO[Option[SystemReminder]] =
    for
      lastCheck <- lastCheckRef.get
      now <- IO(System.currentTimeMillis())
      // Debounce: skip scan if checked within last 5 seconds
      result <-
        if now - lastCheck < DebounceMs then IO.pure(None)
        else
          for
            oldSnapshot <- snapshotRef.get
            agentMods <- modifiedByAgent.get
            newSnapshot <- IO.blocking(scanFiles())
            _ <- snapshotRef.set(newSnapshot)
            _ <- modifiedByAgent.set(Set.empty)
            _ <- lastCheckRef.set(now)

            agentPaths = agentMods.map(_._1)

            changed = (newSnapshot.toSet -- oldSnapshot.toSet)
              .filter { case (path, _) => !agentPaths.contains(path) }
              .map(_._1)
              .toList
              .sorted

            deleted = (oldSnapshot.keySet -- newSnapshot.keySet)
              .filterNot(agentPaths.contains)
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
          val segments = rel.split(java.io.File.separator)
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
