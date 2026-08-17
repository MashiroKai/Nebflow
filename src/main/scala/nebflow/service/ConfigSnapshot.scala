package nebflow.service

import cats.effect.IO
import io.circe.parser.parse
import nebflow.core.Branding
import nebflow.core.PathUtil
import os.Path

/**
 * Manages config file snapshots for crash recovery.
 * Saves a backup on every successful config load/update.
 * Restores from latest valid snapshot when config is corrupted.
 */
object ConfigSnapshot:
  // def (not val): dataRoot must resolve per access (test isolation), and
  // the backup dir follows a migrated data root.
  private def backupDir: Path = PathUtil.dataRoot / "backups"
  private val MaxSnapshots = 5

  /** Snapshot file prefix — matches BOTH the brand config name and the
    * hardcoded legacy "nebflow.json." so pre-rename snapshots stay
    * restorable (L3 rebrand compat; identical names collapse to one). */
  private def snapshotPrefixes: List[String] = List(Branding.configFileName + ".", "nebflow.json.")

  /** Save current config as a timestamped snapshot. */
  def save(): IO[Unit] = IO.blocking {
    val configPath = nebflow.llm.Config.DefaultConfigPath
    if !os.exists(configPath) then ()
    else
      os.makeDir.all(backupDir)
      val ts = java.time.LocalDateTime
        .now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      val snapshot = backupDir / s"${Branding.configFileName}.$ts"
      os.copy.over(configPath, snapshot, createFolders = true)
      prune()
  }

  /** Restore the latest valid snapshot to config path. Returns true if restored. */
  def restoreLatest(): IO[Boolean] = IO.blocking {
    // Write path (brand name) — restoring is a write, and completes the
    // config-file rename migration like any other write.
    val configPath = PathUtil.configJsonWritePath(PathUtil.dataRoot)
    snapshots().find { snap =>
      parse(os.read(snap)).isRight
    } match
      case Some(snap) =>
        os.copy.over(snap, configPath, createFolders = true)
        true
      case None => false
  }

  /** List all snapshots, newest first. */
  def snapshots(): Seq[Path] =
    if !os.exists(backupDir) then Seq.empty
    else
      os.list(backupDir)
        .filter(p => snapshotPrefixes.exists(p.last.startsWith))
        .sortBy(_.last)
        .reverse

  /** Keep only the latest MaxSnapshots. */
  private def prune(): Unit =
    val all = snapshots()
    if all.length > MaxSnapshots then
      all
        .drop(MaxSnapshots)
        .foreach(s =>
          try os.remove(s)
          catch case _ => ()
        )
end ConfigSnapshot
