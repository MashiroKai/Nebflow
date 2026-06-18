package nebflow.service

import cats.effect.IO
import io.circe.parser.parse
import nebflow.core.PathUtil
import os.Path

/**
 * Manages config file snapshots for crash recovery.
 * Saves a backup on every successful config load/update.
 * Restores from latest valid snapshot when config is corrupted.
 */
object ConfigSnapshot:
  private val backupDir: Path = PathUtil.dataRoot / "backups"
  private val MaxSnapshots = 5

  /** Save current config as a timestamped snapshot. */
  def save(): IO[Unit] = IO.blocking {
    val configPath = nebflow.llm.Config.DefaultConfigPath
    if !os.exists(configPath) then ()
    else
      os.makeDir.all(backupDir)
      val ts = java.time.LocalDateTime
        .now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      val snapshot = backupDir / s"nebflow.json.$ts"
      os.copy.over(configPath, snapshot, createFolders = true)
      prune()
  }

  /** Restore the latest valid snapshot to config path. Returns true if restored. */
  def restoreLatest(): IO[Boolean] = IO.blocking {
    val configPath = nebflow.llm.Config.DefaultConfigPath
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
        .filter(_.last.startsWith("nebflow.json."))
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
