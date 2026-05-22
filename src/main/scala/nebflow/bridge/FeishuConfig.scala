package nebflow.bridge

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Global Feishu bot credentials — stored in ~/.nebflow/feishu.json */
case class FeishuGlobalConfig(
  appId: String,
  appSecret: String,
  verificationToken: String = "", // for HTTP webhook verification
  encryptKey: String = "", // for event payload decryption (optional)
  allowedUsers: List[String] = Nil, // open_id whitelist; empty = allow all
  enabled: Boolean = true
):
  /** Check if a Feishu user is allowed to interact with the bot. Empty list = allow all. */
  def allowsUser(openId: String): Boolean = allowedUsers.isEmpty || allowedUsers.contains(openId)

object FeishuGlobalConfig:
  private val logger = NebflowLogger.forName("nebflow.bridge.config")
  private val configPath = os.home / ".nebflow" / "feishu.json"

  given Encoder[FeishuGlobalConfig] = Encoder.instance { c =>
    Json.obj(
      "appId" -> c.appId.asJson,
      "appSecret" -> c.appSecret.asJson,
      "verificationToken" -> c.verificationToken.asJson,
      "encryptKey" -> c.encryptKey.asJson,
      "allowedUsers" -> c.allowedUsers.asJson,
      "enabled" -> c.enabled.asJson
    )
  }

  given Decoder[FeishuGlobalConfig] = Decoder.instance { c =>
    for
      appId <- c.downField("appId").as[String]
      appSecret <- c.downField("appSecret").as[String]
      verificationToken <- c.downField("verificationToken").as[Option[String]].map(_.getOrElse(""))
      encryptKey <- c.downField("encryptKey").as[Option[String]].map(_.getOrElse(""))
      allowedUsers <- c.downField("allowedUsers").as[Option[List[String]]].map(_.getOrElse(Nil))
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(true))
    yield FeishuGlobalConfig(appId, appSecret, verificationToken, encryptKey, allowedUsers, enabled)
  }

  def load: IO[Option[FeishuGlobalConfig]] =
    IO.blocking {
      if os.exists(configPath) then Some(os.read(configPath))
      else None
    }.flatMap {
      case Some(raw) =>
        decode[FeishuGlobalConfig](raw) match
          case Right(cfg) if cfg.enabled =>
            IO.pure(Some(cfg))
          case Right(cfg) =>
            IO.pure(None)
          case Left(err) =>
            logger.warn(s"Failed to parse feishu.json: ${err.getMessage}").as(None)
      case None =>
        IO.pure(None)
    }

  def save(cfg: FeishuGlobalConfig): IO[Unit] =
    IO.blocking {
      os.write.over(configPath, cfg.asJson.spaces2, createFolders = true)
      // Restrict file permissions to owner-only (rw-------)
      try
        val perms = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(java.nio.file.Paths.get(configPath.toString), perms)
      catch case _: Exception => () // best effort on non-POSIX systems
    }

  /** Generate a template config file if one doesn't exist. */
  def initTemplate: IO[Unit] =
    IO.blocking {
      if !os.exists(configPath) then
        val template = FeishuGlobalConfig(
          appId = "cli_xxx",
          appSecret = "xxx",
          verificationToken = "xxx"
        )
        os.write.over(configPath, template.asJson.spaces2, createFolders = true)
        true
      else false
    }.flatMap { created =>
      if created then logger.info(s"Created template config at $configPath")
      else IO.unit
    }
end FeishuGlobalConfig
