package nebflow.core.daemon

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/** A daemon configuration entry stored in daemons.json. */
case class DaemonConfig(
  id: String,
  name: String,
  command: List[String],
  cwd: Option[String] = None,
  env: Map[String, String] = Map.empty,
  autoStart: Boolean = false,
  restartOnExit: Boolean = false,
  port: Option[Int] = None,
  // --- Crash detection & auto-restart (all optional, backward compatible) ---
  /**
   * Base delay (seconds) for the first auto-restart; doubles per attempt
   *      (exponential backoff), capped at 60s.
   */
  restartBackoffSec: Int = 2,
  /** Give up after this many consecutive crash restarts (crash-loop protection). */
  restartMaxAttempts: Int = 5,
  /**
   * A run longer than this (seconds) resets the consecutive-crash counter,
   *      so a daemon that crashes once a day is never falsely exhausted.
   */
  restartStableWindowSec: Int = 60,
  /**
   * Active health-check interval (seconds): periodically TCP-probes `port`
   *      and treats the daemon as crashed when it stays closed while the process
   *      is alive. 0 disables the active health check (only process-exit
   *      detection remains).
   */
  healthCheckSec: Int = 15,
  /**
   * Optional config-panel declaration (daemonpanel Phase A). Raw JSON — the
   *      declaration is validated by DaemonPanelSchema (closed field-type
   *      whitelist + whole-declaration fail-closed); `None` (or absent key in
   *      daemons.json) means "no config panel" and is the zero-migration
   *      backward-compatible default for every pre-existing config.
   */
  configPanel: Option[Json] = None
)

object DaemonConfig:
  /**
   * Derived encoder, with the `configPanel` key DROPPED when it is None:
   *      a panel-less config must re-serialize byte-for-byte as it always did
   *      (legacy daemons.json is never rewritten with a `"configPanel": null`
   *      key — zero migration).
   */
  given Encoder[DaemonConfig] = deriveEncoder[DaemonConfig].mapJson { json =>
    json.asObject match
      case Some(obj) if obj("configPanel").exists(_.isNull) =>
        Json.fromJsonObject(obj.remove("configPanel"))
      case _ => json
  }

  given Decoder[DaemonConfig] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      name <- c.downField("name").as[String]
      commandRaw <- c.downField("command").as[Json]
      command = commandRaw.asString match
        case Some(s) => s.split("\\s+").toList.filter(_.nonEmpty)
        case None => commandRaw.as[List[String]].getOrElse(Nil)
      cwd <- c.downField("cwd").as[Option[String]]
      env <- c.downField("env").as[Option[Map[String, String]]]
      autoStart <- c.downField("autoStart").as[Option[Boolean]]
      restartOnExit <- c.downField("restartOnExit").as[Option[Boolean]]
      port <- c.downField("port").as[Option[Int]]
      restartBackoffSec <- c.downField("restartBackoffSec").as[Option[Int]]
      restartMaxAttempts <- c.downField("restartMaxAttempts").as[Option[Int]]
      restartStableWindowSec <- c.downField("restartStableWindowSec").as[Option[Int]]
      healthCheckSec <- c.downField("healthCheckSec").as[Option[Int]]
      // Raw declaration JSON; DaemonPanelSchema is its only validator. An
      // explicit JSON null is normalized to None (same as an absent key).
      configPanel <- c.downField("configPanel").as[Option[Json]]
    yield DaemonConfig(
      id,
      name,
      command,
      cwd,
      env.getOrElse(Map.empty),
      autoStart.getOrElse(false),
      restartOnExit.getOrElse(false),
      port,
      restartBackoffSec.getOrElse(2),
      restartMaxAttempts.getOrElse(5),
      restartStableWindowSec.getOrElse(60),
      healthCheckSec.getOrElse(15),
      configPanel.filterNot(_.isNull)
    )
  }

end DaemonConfig

/** Runtime status of a managed daemon. */
enum DaemonStatus:
  case Stopped, Running, Crashed, Starting

object DaemonStatus:
  // Serialize as lowercase string ("running"), matching the frontend's
  // `daemon-status ${status}` CSS class contract. The Scala 3 derived
  // encoder would emit {"Running": {}} which the frontend can't use.
  given Encoder[DaemonStatus] = Encoder.encodeString.contramap(_.toString.toLowerCase)

  given Decoder[DaemonStatus] = Decoder.decodeString.emap(s =>
    DaemonStatus.values.find(_.toString.equalsIgnoreCase(s)).toRight(s"Unknown daemon status: $s")
  )
end DaemonStatus

/** Full runtime status of a daemon — returned by REST API. */
case class DaemonState(
  id: String,
  name: String,
  status: DaemonStatus,
  pid: Option[Long] = None,
  startedAt: Option[Long] = None,
  exitCode: Option[Int] = None,
  recentOutput: String = "",
  command: List[String] = Nil,
  port: Option[Int] = None,
  // Independent reachability probe: true when a TCP connect to `port` succeeds.
  // Decoupled from process status — an externally-started server (not managed by
  // DaemonService) can still be reachable, and a dead port makes a live process
  // non-clickable. None when the config declares no port.
  portOpen: Option[Boolean] = None
)

object DaemonState:
  given Encoder[DaemonState] = deriveEncoder
  given Decoder[DaemonState] = deriveDecoder

/** Root config file format: { "daemons": [ ... ] } */
case class DaemonConfigFile(daemons: List[DaemonConfig] = Nil)

object DaemonConfigFile:
  given Encoder[DaemonConfigFile] = deriveEncoder
  given Decoder[DaemonConfigFile] = deriveDecoder

/**
 * Runtime marker persisted when DaemonService spawns a process
 * (~/.nebflow/daemon-pids.json). Lets a NEW instance identify and kill
 * processes orphaned by an abnormally-killed previous instance (SIGKILL /
 * restart-script escalation) that would otherwise hold the daemon's port
 * forever — the "Dev Server 无法打开" ghost. Removed on clean stop; cleared
 * at boot after reclaim.
 */
case class DaemonPidMarker(
  id: String,
  pid: Long,
  name: String,
  port: Option[Int] = None,
  /** process start epoch-ms, recorded at spawn — pid-reuse guard at reclaim. */
  startedAt: Long = 0L
)

object DaemonPidMarker:
  given Encoder[DaemonPidMarker] = deriveEncoder
  given Decoder[DaemonPidMarker] = deriveDecoder

/** Marker file root format: { "markers": [ ... ] } */
case class DaemonPidMarkerFile(markers: List[DaemonPidMarker] = Nil)

object DaemonPidMarkerFile:
  given Encoder[DaemonPidMarkerFile] = deriveEncoder
  given Decoder[DaemonPidMarkerFile] = deriveDecoder
