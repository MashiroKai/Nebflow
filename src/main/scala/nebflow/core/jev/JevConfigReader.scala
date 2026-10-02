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
    JevGateSnapshot(
      configured = true,
      enabled = c.downField("enabled").as[Boolean].toOption.getOrElse(false),
      provider = c.downField("provider").as[String].toOption.getOrElse(""),
      timeoutMs = c.downField("timeoutMs").as[Long].toOption.filter(_ > 0).getOrElse(0L),
      choiceHighBasePolicy = c.downField("choiceHighBasePolicy").as[String].toOption.getOrElse("")
    )

  /** Gate verdict for the current on-disk state (the common call shape). */
  def outcome: IO[JevGateOutcome] = snapshot.map(JevGate.effective)
