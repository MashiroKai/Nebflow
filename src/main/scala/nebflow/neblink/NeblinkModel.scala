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
    Option(System.getenv("HOSTNAME"))
      .orElse(Option(System.getenv("COMPUTERNAME")))
      .orElse(
        try Some(java.net.InetAddress.getLocalHost.getHostName)
        catch case _: Exception => None
      )
      .map(_.stripSuffix(".local")) // macOS mDNS returns "hostname.local"
      .getOrElse("Unknown")

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

/** Device info exchanged during Tailscale discovery (returned by GET /api/neblink/discover).
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
  syncIntervalSec: Int = 300
)

object NeblinkConfig:
  given Encoder[NeblinkConfig] = deriveEncoder
  given Decoder[NeblinkConfig] = deriveDecoder

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
