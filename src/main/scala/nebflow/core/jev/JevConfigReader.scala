package nebflow.core.jev

import cats.effect.IO
import io.circe.Json
import nebflow.shared.PathUtil

/**
 * Hot-read snapshot of the `jev` block (Face C / P2).
 *
 * Mirrors `PluginsConfig.enabled` (`core/plugin/PluginsConfig.scala:20`):
 * every dispatch reads the file afresh, so a toggle change takes effect
 * without a restart — which is exactly what "the switch only affects future
 * dispatches" requires.
 *
 * This reads ONLY scalar settings. It never reads a secret value: `keyRef` is
 * a pointer and the token is resolved by the allocation face, so the gate
 * layer — which runs on every dispatch — carries no credential at all.
 */
object JevConfigReader:

  /**
   * Read the current `jev` block state.
   *
   * Every malformed shape degrades to [[JevGateSnapshot.Off]] rather than
   * throwing: a broken config must not wedge dispatch, and the OFF path is
   * byte-identical to the pre-JeV behaviour, so degrading is the safe side.
   */
  def snapshot: IO[JevGateSnapshot] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(configPath) then JevGateSnapshot.Off
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("jev").focus)
          .flatMap(_.asObject)
          .map(readBlock)
          .getOrElse(JevGateSnapshot.Off)
    }

  private def readBlock(o: io.circe.JsonObject): JevGateSnapshot =
    val c = Json.fromJsonObject(o).hcursor
    // 零超时（2026-10-19 作者拍板）：`timeoutMs` 配置位已拆——旧文件里的残留键
    // 在此被忽略（deriveDecoder/downField 只读在册键，零迁移）。
    JevGateSnapshot(
      configured = true,
      enabled = c.downField("enabled").as[Boolean].toOption.getOrElse(false),
      provider = c.downField("provider").as[String].toOption.getOrElse(""),
      choiceHighBasePolicy = c.downField("choiceHighBasePolicy").as[String].toOption.getOrElse("")
    )

  /** Gate verdict for the current on-disk state (the common call shape). */
  def outcome: IO[JevGateOutcome] = snapshot.map(JevGate.effective)
end JevConfigReader
