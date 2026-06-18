package nebflow.service

import cats.effect.IO
import nebflow.core.PathUtil
import os.Path

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

/**
 * Periodic backup of critical ~/.nebflow files.
 *
 * Backs up: sessions, folders, agent memory, NEBFLOW.md, nebflow.json, auth.json
 * Skips: history, uploads, tasks (regenerable or large), pid files
 * Keeps the latest MaxBackups snapshots, prunes the rest.
 *
 * Layout: backups/daily/YYYYMMDD-HHmmss/ mirrors the ~/.nebflow/ structure.
 */
object NebflowBackup:

  private val backupRoot: Path = os.home / ".nebflowBackup"
  private val MaxBackups = 7 // Keep one week of daily backups

  private val home: Path = PathUtil.dataRoot
  private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  /** File patterns to back up (relative to ~/.nebflow/). */
  private val includePatterns: List[String => Boolean] = List(
    // Top-level config files
    _ == "NEBFLOW.md",
    _ == "nebflow.json",
    _ == "auth.json",
    _ == "input_history.jsonl",
    // Sessions directory
    _.startsWith("sessions/"),
    // Folders directory
    _.startsWith("folders/"),
    // Agent memory files
    _.matches("agents/[^/]+/memory\\.md"),
    _.matches("agents/[^/]+/agent\\.json"),
    _.matches("agents/[^/]+/system\\.md")
  )

  /** File patterns to exclude (even if matched by include). */
  private val excludePatterns: List[String => Boolean] = List(
    _.endsWith(".pid"),
    _.startsWith("sessions/test-"),
    _.startsWith("sessions/tool-test-"),
    _.startsWith("sessions/debug-"),
    _.startsWith("history/"),
    _.startsWith("uploads/"),
    _.startsWith("tasks/"),
    _.startsWith("backups/"),
    _.startsWith("projects/")
  )

  private def shouldBackup(relPath: String): Boolean =
    includePatterns.exists(p => p(relPath)) && !excludePatterns.exists(p => p(relPath))

  /** Run a backup now. Returns the backup directory path, or None if nothing to back up. */
  def run(): IO[Option[Path]] = IO.blocking {
    val ts = LocalDateTime.now(ZoneId.systemDefault()).format(fmt)
    val targetDir = backupRoot / ts
    var count = 0

    def copyFile(relPath: String): Unit =
      val src = home / os.RelPath(relPath)
      if os.exists(src) then
        val dst = targetDir / os.RelPath(relPath)
        os.copy.over(src, dst, createFolders = true)
        count += 1

    // Collect files to back up
    walkHome().foreach { rel =>
      if shouldBackup(rel) then copyFile(rel)
    }

    if count > 0 then
      prune()
      Some(targetDir)
    else
      // Nothing to back up — remove empty dir
      if os.exists(targetDir) then os.remove.all(targetDir)
      None
  }

  /** Walk ~/.nebflow/ and collect relative paths of all files. */
  private def walkHome(): Seq[String] =
    if !os.exists(home) then Seq.empty
    else
      os.walk(home)
        .filter(os.isFile(_))
        .map(_.relativeTo(home).toString)
        .toSeq

  /** List existing backups, newest first. */
  def listBackups(): Seq[Path] =
    if !os.exists(backupRoot) then Seq.empty
    else
      os.list(backupRoot)
        .filter(os.isDir(_))
        .sortBy(_.last)
        .reverse

  /** Prune old backups, keeping only the latest MaxBackups. */
  private def prune(): Unit =
    val all = listBackups()
    if all.length > MaxBackups then
      all.drop(MaxBackups).foreach { d =>
        try os.remove.all(d)
        catch case _: Exception => ()
      }

  /** Check if a backup is needed (no backup in the last 24h). */
  def isNeeded: Boolean =
    listBackups().headOption match
      case None => true
      case Some(latest) =>
        val name = latest.last
        try
          val ts = LocalDateTime.parse(name, fmt)
          val hours = java.time.Duration.between(ts, LocalDateTime.now(ZoneId.systemDefault())).toHours
          hours >= 24
        catch case _: Exception => true // malformed name, run backup

end NebflowBackup
