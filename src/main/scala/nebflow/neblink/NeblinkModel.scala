package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.PathUtil

import java.util.UUID

// ===== Device Identity =====

/** Local device identity — generated once, stored in ~/.nebflow/device.json. */
case class DeviceIdentity(
  deviceId: String,
  deviceName: String,
  platform: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  avatarUrl: Option[String] = None,
  githubLogin: Option[String] = None
)

object DeviceIdentity:
  given Encoder[DeviceIdentity] = deriveEncoder
  given Decoder[DeviceIdentity] = deriveDecoder

  private val devicePath = PathUtil.dataRoot / "device.json"

  private def detectPlatform: String =
    val osName = System.getProperty("os.name", "unknown").toLowerCase
    if osName.contains("mac") then "macos"
    else if osName.contains("win") then "windows"
    else if osName.contains("linux") then "linux"
    else "unknown"

  private def detectDeviceName: String =
    Option(System.getenv("HOSTNAME"))
      .orElse(Option(System.getenv("COMPUTERNAME")))
      .orElse(
        try Some(java.net.InetAddress.getLocalHost.getHostName)
        catch case _: Exception => None
      )
      .map(_.stripSuffix(".local")) // macOS mDNS returns "hostname.local"
      .getOrElse("Unknown")

  def loadOrCreate: IO[DeviceIdentity] =
    IO.blocking {
      if os.exists(devicePath) then
        decode[DeviceIdentity](os.read(devicePath)) match
          case Right(d) => ensureSecret(ensureCleanDeviceName(d))
          case Left(_) => createNew()
      else createNew()
    }.flatMap { id =>
      // Persist if file doesn't exist yet, or if we just migrated (deviceSecret or deviceName)
      val needsSave = !os.exists(devicePath) ||
        decode[DeviceIdentity](os.read(devicePath)).toOption.exists { saved =>
          saved.deviceSecret.isEmpty || saved.deviceName != id.deviceName
        }
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

  /** Migrate old DeviceIdentity with ".local" suffix in deviceName (macOS mDNS artifact). */
  private def ensureCleanDeviceName(id: DeviceIdentity): DeviceIdentity =
    if id.deviceName.endsWith(".local") then id.copy(deviceName = id.deviceName.stripSuffix(".local"))
    else id
end DeviceIdentity

// ===== Device Discovery Info =====

/**
 * Device info exchanged during NebLink discovery (returned by GET /api/neblink/discover).
 *
 *  Note: userDescription is intentionally NOT included — descriptions are purely local,
 *  never exchanged between devices. See NeblinkService.handleAnnounce.
 */
case class DeviceDiscoveryInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  capabilities: Map[String, String] = Map.empty
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

// ===== Neblink Config =====

case class NeblinkConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 45,
  neblinkServer: Option[NeblinkServerConfig] = None
)

object NeblinkConfig:
  given Encoder[NeblinkConfig] = deriveEncoder

  given Decoder[NeblinkConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
      syncIntervalSec <- c.downField("syncIntervalSec").as[Option[Int]].map(_.getOrElse(45))
      // Backward compat: try "neblinkServer" first, fall back to "coordinator"
      neblinkServer <- c.downField("neblinkServer").as[Option[NeblinkServerConfig]].flatMap {
        case Some(config) => Right(Some(config))
        case None => c.downField("coordinator").as[Option[NeblinkServerConfig]]
      }
    yield NeblinkConfig(enabled, syncIntervalSec, neblinkServer)
  }

  private val configPath = PathUtil.dataRoot / "neblink" / "config.json"

  def load: IO[NeblinkConfig] =
    IO.blocking {
      if os.exists(configPath) then
        decode[NeblinkConfig](os.read(configPath)) match
          case Right(c) => c
          case Left(_) => NeblinkConfig()
      else NeblinkConfig()
    }

  def save(config: NeblinkConfig): IO[Unit] =
    IO.blocking {
      os.write.over(configPath, config.asJson.spaces2, createFolders = true)
    }
end NeblinkConfig

// ===== Peer Description Store =====

/** Persists user-set peer descriptions across restarts. Stored in ~/.nebflow/peer-descriptions.json. */
object PeerDescriptionStore:
  private val path = PathUtil.dataRoot / "peer-descriptions.json"

  def load: IO[Map[String, String]] =
    IO.blocking {
      if os.exists(path) then decode[Map[String, String]](os.read(path)).getOrElse(Map.empty)
      else Map.empty
    }

  def save(descs: Map[String, String]): IO[Unit] =
    IO.blocking {
      os.write.over(path, descs.asJson.spaces2, createFolders = true)
    }
