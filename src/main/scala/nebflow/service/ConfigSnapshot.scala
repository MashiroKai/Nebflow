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

  /** Restore the latest valid snapshot to config path. Returns true if restored.
    *
    * 2026-09-07 插件信任持久化修复（防御纵深）：收窄触发条件——只在当前配置
    * **真正损坏（非法 JSON / 不可读）** 时恢复。一个「合法 JSON 但字段不全」的
    * 配置（如冷启动种子写入的 plugins.trust 对象、无 llm 节）**不是**损坏——用
    * 陈旧 {} 快照打回会摧毁这段时间的可信写入（信任表 / 已配置 provider 等）。
    * 该形态现可被 Config.loadServiceConfig 正常解码（llm 缺省），恢复仅兜底真
    * 损坏文件。
    */
  def restoreLatest(): IO[Boolean] = IO.blocking {
    // Write path (brand name) — restoring is a write, and completes the
    // config-file rename migration like any other write.
    val configPath = PathUtil.configJsonWritePath(PathUtil.dataRoot)
    // 当前配置是否真损坏（而非「合法 JSON、只是字段不全」）。
    val corrupt =
      try
        if os.exists(configPath) then parse(os.read(configPath)).isLeft
        else false
      catch case _: Exception => true // 不可读 → 视为损坏，尝试从快照恢复
    if !corrupt then false
    else
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
