package nebflow.core

import cats.effect.{IO, Ref}
import nebflow.shared.{NebflowLogger, SystemReminder}

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
    FileChangeTracker.scanTree(rootPath)

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
            else
              val fileList = allChanges
                .take(20)
                .mkString(
                  "\n  - ",
                  "\n  - ",
                  if allChanges.length > 20 then s"\n  ... and ${allChanges.length - 20} more" else ""
                )
              Some(
                SystemReminder(
                  "fileChanges",
                  s"The following files were modified externally since the last message:$fileList"
                )
              )
            end if
    yield result

  def recordAgentModification(path: String): IO[Unit] =
    IO.blocking {
      val absPath = Paths.get(path).toAbsolutePath.normalize
      // relativize fails on Windows when file is on a different drive (C: vs D:);
      // fall back to absolute path in that case.
      val rel =
        try rootPath.relativize(absPath).toString
        catch case _: Exception => absPath.toString
      val modTime =
        try Files.getLastModifiedTime(absPath).toMillis
        catch case _: Exception => System.currentTimeMillis()
      (rel, modTime)
    }.flatMap { entry =>
      modifiedByAgent.update(_ + entry)
    }

end FileChangeTracker

object FileChangeTracker:

  // .nebflow is Nebflow's own process directory (task state, evidence,
  // worktrees, logs) that lives inside project roots; on a real deployment it
  // can hold hundreds of thousands of files, turning every full walk below
  // into seconds of synchronous stat work on the calling thread. Process
  // material is not user project content, so external-change reminders do not
  // cover it. Read by both scanProject (initial snapshot) and the instance
  // scanFiles (periodic full scan).
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
    "dist",
    ".nebflow"
  )
  val ExcludedFiles = Set(".DS_Store")

  // ── Boot-walk budget + root guard (boot-OOM attribution fix, 2026-10-25) ──
  //
  // WHY: the gateway creates the tracker over `System.getProperty("user.dir")`
  // (GatewayMain boot). That is only "the project root" when the launcher
  // happens to cd into one. The 2026-10-25 production boot chain
  // (launchd WorkingDirectory=/Users/<user> → a wrapper that never cd's)
  // booted with user.dir = THE USER'S HOME DIRECTORY, so the initial snapshot
  // walk crawled the entire home tree — stat + relativize + Pattern.quote per
  // file — measured at ~3GB/s of allocation in a 2G heap (reproduced as E11:
  // 96 GC pauses in the first 4.6s; the production cluster of the same night
  // died at +15-17s with a ZGC allocation-stall OOM).
  //
  // Two independent guards:
  //   ① isUnscannableRoot — the user home and filesystem roots are never
  //     walked; `create` yields an EMPTY snapshot (fail-soft: statKnown /
  //     quickScan on an empty map are O(1), and the periodic full scan only
  //     fires when the snapshot is non-empty, so a refused tracker stays
  //     refused — no reminder spam, no repeated walk attempts).
  //   ② walk budget — ANY walk is capped by file count and wall clock
  //     (system-property tunable for tests); breaching it discards the scan
  //     with the SAME fail-soft contract as a traversal error (Map.empty), so
  //     no root can ever cost unbounded boot time or memory.
  private def maxScanFiles: Int =
    sys.props.getOrElse("nebflow.filewalk.maxFiles", "200000").toInt

  private def maxScanMillis: Long =
    sys.props.getOrElse("nebflow.filewalk.maxMillis", "10000").toLong

  private class ScanBudgetExceeded extends RuntimeException

  private[core] def isUnscannableRoot(p: Path): Boolean =
    val norm = p.toAbsolutePath.normalize
    val home = Paths.get(System.getProperty("user.home")).toAbsolutePath.normalize
    norm == home || Option(norm.getParent).isEmpty

  /**
   * Full scan of the project tree with excluded directories PRUNED from the
   * traversal itself (walkFileTree + SKIP_SUBTREE), not filtered afterwards.
   * This is the load-bearing difference vs the previous Files.walk form:
   * Files.walk descends into every directory and post-filters entries, so a
   * name exclusion alone never saved the walk cost — on a real deployment the
   * .nebflow subtree alone accounted for 96% of entries and ~16s per scan.
   *
   * Result-set semantics are unchanged from the pre-prune filter form: a file
   * is kept iff it is a regular file, no path segment is in ExcludedDirs
   * (file-level check retained for non-directory matches such as a worktree's
   * `.git` file), and its name is not in ExcludedFiles. Any traversal error
   * discards the whole scan (Map.empty), same fail-soft contract as before.
   */
  private[core] def scanTree(rootPath: Path): Map[String, Long] =
    try
      val collected = scala.collection.mutable.LinkedHashMap.empty[String, Long]
      var scanned = 0
      val deadline = System.nanoTime() + maxScanMillis * 1000000L
      def budgetBreached: Boolean =
        scanned > maxScanFiles || System.nanoTime() > deadline
      val visitor = new java.nio.file.SimpleFileVisitor[Path]:
        override def preVisitDirectory(
          dir: Path,
          attrs: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult =
          scanned += 1
          if budgetBreached then throw new ScanBudgetExceeded
          // The start root itself is never subject to its own exclusion list.
          if dir == rootPath || !ExcludedDirs.contains(dir.getFileName.toString) then
            java.nio.file.FileVisitResult.CONTINUE
          else java.nio.file.FileVisitResult.SKIP_SUBTREE
        override def visitFile(
          file: Path,
          attrs: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult =
          scanned += 1
          if budgetBreached then throw new ScanBudgetExceeded
          val rel = rootPath.relativize(file).toString
          val segments = rel.split(java.util.regex.Pattern.quote(java.io.File.separator))
          if Files.isRegularFile(file) &&
            !segments.exists(ExcludedDirs.contains) &&
            !ExcludedFiles.contains(segments.last)
          then
            val modTime =
              try Files.getLastModifiedTime(file).toMillis
              catch case _: Exception => 0L
            collected.update(rel, modTime)
          java.nio.file.FileVisitResult.CONTINUE
        end visitFile
      Files.walkFileTree(rootPath, visitor)
      collected.toMap
    catch case _: Exception => Map.empty

  def scanProject(projectRoot: String): Map[String, Long] =
    scanTree(Paths.get(projectRoot).toAbsolutePath.normalize)

  def create(projectRoot: String): IO[FileChangeTracker] =
    val root = Paths.get(projectRoot).toAbsolutePath.normalize
    if isUnscannableRoot(root) then
      NebflowLogger.forName("nebflow.core.FileChangeTracker").warn(
        s"FileChangeTracker: refusing to walk '$projectRoot' (user home / filesystem root) — " +
          "external-change reminders stay disabled for this launch; start the engine from a project directory to enable them."
      ) *>
        Ref.of[IO, Map[String, Long]](Map.empty).flatMap { snapshotRef =>
          Ref.of[IO, Set[(String, Long)]](Set.empty).flatMap { agentRef =>
            Ref.of[IO, Long](0L).map { lastCheckRef =>
              new FileChangeTracker(projectRoot, snapshotRef, agentRef, lastCheckRef)
            }
          }
        }
    else
      for
        initialSnapshot <- IO.blocking(scanProject(projectRoot))
        snapshotRef <- Ref.of[IO, Map[String, Long]](initialSnapshot)
        agentRef <- Ref.of[IO, Set[(String, Long)]](Set.empty)
        lastCheckRef <- Ref.of[IO, Long](0L)
      yield new FileChangeTracker(projectRoot, snapshotRef, agentRef, lastCheckRef)
end FileChangeTracker
