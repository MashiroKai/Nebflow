package nebflow.core

import cats.effect.{IO, Ref}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

class FileChangeTracker(
  projectRoot: String,
  snapshotRef: Ref[IO, Map[String, Long]],
  modifiedByAgent: Ref[IO, Set[(String, Long)]]
):
  private val ExcludedDirs = Set(
    ".git", "target", ".bsp", ".metals", ".bloop", ".idea",
    ".vscode", "node_modules", ".DS_Store", ".claude", "dist"
  )

  private val rootPath = Paths.get(projectRoot).toAbsolutePath.normalize

  private def scanFiles(): Map[String, Long] =
    Files.walk(rootPath)
      .iterator().asScala
      .filter(Files.isRegularFile(_))
      .filter { p =>
        val rel = rootPath.relativize(p).toString
        !rel.split(java.io.File.separator).headOption.exists(ExcludedDirs.contains)
      }
      .map { p =>
        val rel = rootPath.relativize(p).toString
        val modTime = try Files.getLastModifiedTime(p).toMillis catch case _: Exception => 0L
        rel -> modTime
      }
      .toMap

  def checkChanges(): IO[Option[SystemReminder]] =
    for
      oldSnapshot <- snapshotRef.get
      agentMods <- modifiedByAgent.get
      newSnapshot <- IO.blocking(scanFiles())
      _ <- snapshotRef.set(newSnapshot)
      _ <- modifiedByAgent.set(Set.empty) // Clear after snapshot update

      // Build a set of paths modified by agent (for filtering)
      agentPaths = agentMods.map(_._1)

      // Find files that changed externally (not by agent)
      changed = (newSnapshot.toSet -- oldSnapshot.toSet)
        .filter { case (path, _) => !agentPaths.contains(path) }
        .map(_._1)
        .toList
        .sorted

      // Also find deleted files
      deleted = (oldSnapshot.keySet -- newSnapshot.keySet)
        .filterNot(agentPaths.contains)
        .toList
        .sorted
    yield
      val allChanges = changed.map("modified: " + _) ++ deleted.map("deleted: " + _)
      if allChanges.isEmpty then None
      else Some(SystemReminders.fileChanges(allChanges))

  def recordAgentModification(path: String): IO[Unit] =
    IO.blocking {
      val absPath = Paths.get(path).toAbsolutePath.normalize
      val rel = rootPath.relativize(absPath).toString
      val modTime = try Files.getLastModifiedTime(absPath).toMillis catch case _: Exception => System.currentTimeMillis()
      (rel, modTime)
    }.flatMap { entry =>
      modifiedByAgent.update(_ + entry)
    }

object FileChangeTracker:
  def create(projectRoot: String): IO[FileChangeTracker] =
    for
      initialSnapshot <- IO {
        val rootPath = Paths.get(projectRoot).toAbsolutePath.normalize
        Files.walk(rootPath)
          .iterator().asScala
          .filter(Files.isRegularFile(_))
          .filter { p =>
            val rel = rootPath.relativize(p).toString
            !rel.split(java.io.File.separator).headOption.exists(
              Set(".git", "target", ".bsp", ".metals", ".bloop", ".idea",
                ".vscode", "node_modules", ".DS_Store", ".claude", "dist").contains
            )
          }
          .map { p =>
            val rel = rootPath.relativize(p).toString
            val modTime = try Files.getLastModifiedTime(p).toMillis catch case _: Exception => 0L
            rel -> modTime
          }
          .toMap
      }
      snapshotRef <- Ref.of[IO, Map[String, Long]](initialSnapshot)
      agentRef <- Ref.of[IO, Set[(String, Long)]](Set.empty)
    yield new FileChangeTracker(projectRoot, snapshotRef, agentRef)
