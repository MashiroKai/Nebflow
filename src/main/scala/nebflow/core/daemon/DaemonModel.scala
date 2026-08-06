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
  restartOnExit: Boolean = false
)

object DaemonConfig:
  given Encoder[DaemonConfig] = deriveEncoder

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
    yield DaemonConfig(
      id,
      name,
      command,
      cwd,
      env.getOrElse(Map.empty),
      autoStart.getOrElse(false),
      restartOnExit.getOrElse(false)
    )
  }

end DaemonConfig

/** Runtime status of a managed daemon. */
enum DaemonStatus derives Encoder, Decoder:
  case Stopped, Running, Crashed, Starting

/** Full runtime status of a daemon — returned by REST API. */
case class DaemonState(
  id: String,
  name: String,
  status: DaemonStatus,
  pid: Option[Long] = None,
  startedAt: Option[Long] = None,
  exitCode: Option[Int] = None,
  recentOutput: String = ""
)

object DaemonState:
  given Encoder[DaemonState] = deriveEncoder
  given Decoder[DaemonState] = deriveDecoder

/** Root config file format: { "daemons": [ ... ] } */
case class DaemonConfigFile(daemons: List[DaemonConfig] = Nil)

object DaemonConfigFile:
  given Encoder[DaemonConfigFile] = deriveEncoder
  given Decoder[DaemonConfigFile] = deriveDecoder
