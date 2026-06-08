package nebflow.mesh

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}

import java.util.UUID

// ===== Device Identity =====

/** Local device identity — generated once, stored in ~/.nebflow/device.json. */
case class DeviceIdentity(
  deviceId: String,
  deviceName: String,
  platform: String,
  groupId: Option[String] = None
)

object DeviceIdentity:
  given Encoder[DeviceIdentity] = deriveEncoder
  given Decoder[DeviceIdentity] = deriveDecoder

  private val devicePath = os.home / ".nebflow" / "device.json"

  /** Detect current platform name. */
  private def detectPlatform: String =
    val osName = System.getProperty("os.name", "unknown").toLowerCase
    if osName.contains("mac") then "macos"
    else if osName.contains("win") then "windows"
    else if osName.contains("linux") then "linux"
    else "unknown"

  /** Detect a human-readable device name. */
  private def detectDeviceName: String =
    val hostname = Option(System.getenv("HOSTNAME"))
      .orElse(Option(System.getenv("COMPUTERNAME")))
      .getOrElse("Unknown")
    val platform = detectPlatform match
      case "macos" => "macOS"
      case "windows" => "Windows"
      case "linux" => "Linux"
      case _ => ""
    s"$hostname ($platform)"

  /** Load existing device identity, or generate and persist a new one. */
  def loadOrCreate: IO[DeviceIdentity] =
    IO.blocking {
      if os.exists(devicePath) then
        decode[DeviceIdentity](os.read(devicePath)) match
          case Right(d) => d
          case Left(_) => createNew()
      else createNew()
    }.flatMap { id =>
      // Persist new identity so deviceId survives restarts
      if !os.exists(devicePath) then save(id).as(id) else IO.pure(id)
    }

  /** Save device identity to disk. */
  def save(identity: DeviceIdentity): IO[Unit] =
    IO.blocking {
      os.write.over(devicePath, identity.asJson.spaces2, createFolders = true)
    }

  private def createNew(): DeviceIdentity =
    DeviceIdentity(
      deviceId = UUID.randomUUID().toString,
      deviceName = detectDeviceName,
      platform = detectPlatform
    )
end DeviceIdentity

// ===== Peer Info =====

/** Information about a remote Nebflow peer device. */
case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,
  lastSeen: Long = System.currentTimeMillis()
)

object PeerInfo:
  given Encoder[PeerInfo] = deriveEncoder
  given Decoder[PeerInfo] = deriveDecoder

// ===== File Fingerprint =====

/** Fingerprint for detecting file changes during sync. */
case class FileFingerprint(
  mtime: Long,
  size: Long,
  hash: String
)

object FileFingerprint:
  given Encoder[FileFingerprint] = deriveEncoder
  given Decoder[FileFingerprint] = deriveDecoder

  /** Compute fingerprint for a local file. Returns None if file doesn't exist. */
  def compute(path: os.Path): Option[FileFingerprint] =
    if !os.exists(path) then None
    else
      val stat = os.stat(path)
      val content = os.read.bytes(path)
      val hash = computeHash(content)
      Some(FileFingerprint(stat.mtime.toMillis, stat.size, hash))

  /** SHA-256 first 12 hex chars — same algorithm as MemoryStore.contentHash. */
  def computeHash(bytes: Array[Byte]): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    digest.digest().take(6).map(b => String.format("%02x", b)).mkString

end FileFingerprint

// ===== Sync Diff =====

/** Result of comparing local vs remote fingerprints. */
case class SyncDiff(
  needUpload: List[String],
  needDownload: List[String],
  unchanged: List[String]
)

object SyncDiff:
  given Encoder[SyncDiff] = deriveEncoder
  given Decoder[SyncDiff] = deriveDecoder

// ===== Mesh Config =====

/** Mesh configuration stored in ~/.nebflow/mesh/config.json. */
case class MeshConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 300
)

object MeshConfig:
  given Encoder[MeshConfig] = deriveEncoder
  given Decoder[MeshConfig] = deriveDecoder

  private val configPath = os.home / ".nebflow" / "mesh" / "config.json"

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
