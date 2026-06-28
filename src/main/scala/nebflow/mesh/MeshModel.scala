package nebflow.mesh

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.PathUtil

import java.util.UUID

// ===== Mesh Errors =====

/**
 * Structured mesh errors so the gateway can map them to precise HTTP responses and the
 * frontend can react accordingly (e.g. prompt to configure the server URL).
 */
enum MeshError(val message: String, val code: String):
  /** The relay server URL has not been configured. Frontend should ask the user to set it. */
  case CloudUrlNotConfigured extends MeshError("服务器地址未配置", "cloud_url_missing")

  /** Authentication failed (bad credentials / session). */
  case AuthFailed(msg: String) extends MeshError(msg, "auth_failed")

  /** Network-level failure reaching the relay server (timeout, connection refused, DNS). */
  case NetworkError(msg: String) extends MeshError(msg, "network_error")

  /** Any other cloud/relay error surfaced as a generic message. */
  case CloudError(msg: String) extends MeshError(msg, "cloud_error")

object MeshError:

  /** Map a raw exception thrown during a cloud call onto a structured MeshError. */
  def fromThrowable(e: Throwable): MeshError =
    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    msg match
      case m if m.startsWith("Cloud URL not configured") => CloudUrlNotConfigured
      case m
          if m.contains("Invalid username or password") ||
            m.contains("Auth error") || m.contains("Session") =>
        AuthFailed(m)
      case m if m.contains("Cloud API") || m.contains("Cloud error") =>
        CloudError(extractInner(m))
      case _ => NetworkError(msg)

  /** Strip the "Cloud error N: " / "Cloud API N: " prefix to surface the real message. */
  private def extractInner(m: String): String =
    val idx = m.indexOf(": ")
    if idx >= 0 && idx < m.length - 2 then m.substring(idx + 2).trim else m
end MeshError

// ===== Account Info =====

/** Nebflow account credentials — stored in ~/.nebflow/mesh/account.json. */
case class AccountInfo(
  userId: String,
  username: String,
  sessionToken: String,
  loggedInAt: Long = System.currentTimeMillis()
)

object AccountInfo:
  given Encoder[AccountInfo] = deriveEncoder
  given Decoder[AccountInfo] = deriveDecoder

  private val accountPath = PathUtil.dataRoot / "mesh" / "account.json"

  def load: IO[Option[AccountInfo]] =
    IO.blocking {
      if os.exists(accountPath) then decode[AccountInfo](os.read(accountPath)).toOption
      else None
    }

  def save(account: AccountInfo): IO[Unit] =
    IO.blocking {
      os.write.over(accountPath, account.asJson.spaces2, createFolders = true)
      // Restrict file permissions to owner-only (rw-------), same as auth.json
      try
        val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        java.nio.file.Files.setPosixFilePermissions(
          java.nio.file.Paths.get(accountPath.toString),
          perms
        )
      catch case _: Exception => () // best effort on non-POSIX systems
    }

  def clear: IO[Unit] =
    IO.blocking {
      if os.exists(accountPath) then os.remove(accountPath)
    }
end AccountInfo

// ===== Device Identity =====

/** Local device identity — generated once, stored in ~/.nebflow/device.json. */
case class DeviceIdentity(
  deviceId: String,
  deviceName: String,
  platform: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = ""
)

object DeviceIdentity:
  given Encoder[DeviceIdentity] = deriveEncoder
  given Decoder[DeviceIdentity] = deriveDecoder

  private val devicePath = PathUtil.dataRoot / "device.json"

  /** Tools to auto-detect on startup. Key = display name, value = command to check. */
  private val detectionTargets = List(
    "python" -> "python3",
    "node" -> "node",
    "git" -> "git",
    "java" -> "java",
    "vivado" -> "vivado",
    "quartus" -> "quartus_sh",
    "sbt" -> "sbt",
    "rust" -> "cargo",
    "go" -> "go",
    "docker" -> "docker"
  )

  private def detectPlatform: String =
    val osName = System.getProperty("os.name", "unknown").toLowerCase
    if osName.contains("mac") then "macos"
    else if osName.contains("win") then "windows"
    else if osName.contains("linux") then "linux"
    else "unknown"

  private def detectDeviceName: String =
    val hostname = Option(System.getenv("HOSTNAME"))
      .orElse(Option(System.getenv("COMPUTERNAME")))
      .orElse(
        try Some(java.net.InetAddress.getLocalHost.getHostName)
        catch case _: Exception => None
      )
      .getOrElse("Unknown")
    val platform = detectPlatform match
      case "macos" => "macOS"
      case "windows" => "Windows"
      case "linux" => "Linux"
      case _ => ""
    hostname

  /** Detect available tools by running `which`/`where`. Returns map of name → path. */
  def detectCapabilities: IO[Map[String, String]] =
    val whichCmd = detectPlatform match
      case "windows" => "where"
      case _ => "which"
    IO.blocking {
      val results = scala.collection.mutable.Map.empty[String, String]
      for (name, binary) <- detectionTargets do
        try
          val proc = new ProcessBuilder(whichCmd, binary).redirectErrorStream(true).start()
          val exited = proc.waitFor()
          if exited == 0 then
            val output = scala.io.Source.fromInputStream(proc.getInputStream).mkString.trim
            val firstLine = output.linesIterator.nextOption().getOrElse("")
            if firstLine.nonEmpty then results(name) = firstLine
          proc.getInputStream.close()
        catch case _: Exception => ()
      results.toMap
    }

  end detectCapabilities

  def loadOrCreate: IO[DeviceIdentity] =
    IO.blocking {
      if os.exists(devicePath) then
        decode[DeviceIdentity](os.read(devicePath)) match
          case Right(d) => ensureSecret(d)
          case Left(_) => createNew()
      else createNew()
    }.flatMap { id =>
      // Persist if file doesn't exist yet, or if we just migrated (added deviceSecret)
      val needsSave = !os.exists(devicePath) ||
        decode[DeviceIdentity](os.read(devicePath)).toOption.exists(_.deviceSecret.isEmpty)
      if needsSave then save(id).as(id) else IO.pure(id)
    }

  def save(identity: DeviceIdentity): IO[Unit] =
    IO.blocking {
      os.write.over(devicePath, identity.asJson.spaces2, createFolders = true)
    }

  private def createNew(): DeviceIdentity =
    DeviceIdentity(
      deviceId = UUID.randomUUID().toString,
      deviceName = detectDeviceName,
      platform = detectPlatform,
      deviceSecret = UUID.randomUUID().toString + UUID.randomUUID().toString
    )

  /** Migrate old DeviceIdentity without deviceSecret — generate one on first load. */
  private def ensureSecret(id: DeviceIdentity): DeviceIdentity =
    if id.deviceSecret.isEmpty then id.copy(deviceSecret = UUID.randomUUID().toString + UUID.randomUUID().toString)
    else id
end DeviceIdentity

// ===== Device Discovery Info =====

/** Device info exchanged during Tailscale discovery (returned by GET /api/mesh/discover). */
case class DeviceDiscoveryInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = ""
)

object DeviceDiscoveryInfo:
  given Encoder[DeviceDiscoveryInfo] = deriveEncoder
  given Decoder[DeviceDiscoveryInfo] = deriveDecoder

// ===== Peer Info =====

case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  lastSeen: Long = System.currentTimeMillis()
)

object PeerInfo:
  given Encoder[PeerInfo] = deriveEncoder

  given Decoder[PeerInfo] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      address <- c.downField("address").as[String]
      deviceSecret <- c.downField("deviceSecret").as[Option[String]].map(_.getOrElse(""))
      capabilities <- c.downField("capabilities").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      userDescription <- c.downField("userDescription").as[Option[String]].map(_.getOrElse(""))
      lastSeen <- c.downField("lastSeen").as[Option[Long]].map(_.getOrElse(System.currentTimeMillis()))
    yield PeerInfo(deviceId, deviceName, platform, address, deviceSecret, capabilities, userDescription, lastSeen)
  }
end PeerInfo

// ===== Mesh Config =====

case class MeshConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 300,
  cloudUrl: Option[String] = None // Self-hosted server URL, configured by user
)

object MeshConfig:
  given Encoder[MeshConfig] = deriveEncoder
  given Decoder[MeshConfig] = deriveDecoder

  private val configPath = PathUtil.dataRoot / "mesh" / "config.json"

  def load: IO[MeshConfig] =
    IO.blocking {
      if os.exists(configPath) then
        decode[MeshConfig](os.read(configPath)) match
          case Right(c) => c
          case Left(_) => MeshConfig()
      else MeshConfig()
    }

  def save(config: MeshConfig): IO[Unit] =
    IO.blocking {
      os.write.over(configPath, config.asJson.spaces2, createFolders = true)
    }
end MeshConfig
