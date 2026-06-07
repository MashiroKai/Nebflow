package nebflow.mesh

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import io.circe.parser.decode
import io.circe.generic.semiauto.*

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

  /** Load existing device identity, or generate a new one. */
  def loadOrCreate: IO[DeviceIdentity] =
    IO.blocking {
      if os.exists(devicePath) then
        decode[DeviceIdentity](os.read(devicePath)) match
          case Right(d) => d
          case Left(_) => createNew()
      else createNew()
    }

  /** Save device identity to disk. */
  def save(identity: DeviceIdentity): IO[Unit] =
    IO.blocking {
      os.write.over(devicePath, identity.asJson.spaces2, createFolders = true)
    }

  /** Set groupId after pairing. */
  def setGroup(identity: DeviceIdentity, groupId: String): IO[DeviceIdentity] =
    val updated = identity.copy(groupId = Some(groupId))
    save(updated).map(_ => updated)

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
  online: Boolean = false,
  lastSeen: Long = 0L,
  address: Option[String] = None
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

// ===== Sync Status =====

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
  cloudBaseUrl: Option[String] = None,
  syncIntervalSec: Int = 300,
  relayPollIntervalSec: Int = 30
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

// ===== Relay Message =====

/** A message relayed through the cloud between devices. */
case class RelayMessage(
  id: String,
  fromDeviceId: String,
  toDeviceId: String,
  payload: RelayPayload,
  createdAt: Long
)

/** Payload types for cross-device communication. */
enum RelayPayload:
  case UserInput(sessionId: String, content: String)
  case ListSessions()
  case SessionList(sessions: List[SessionSummary])
  case ExecuteCommand(sessionId: String, command: String)

case class SessionSummary(
  id: String,
  name: String,
  agentName: Option[String],
  updatedAt: Long
)

object RelayPayload:
  given Encoder[RelayPayload] = Encoder.instance {
    case RelayPayload.UserInput(sid, content) =>
      io.circe.Json.obj("type" -> "userInput".asJson, "sessionId" -> sid.asJson, "content" -> content.asJson)
    case RelayPayload.ListSessions() =>
      io.circe.Json.obj("type" -> "listSessions".asJson)
    case RelayPayload.SessionList(sessions) =>
      io.circe.Json.obj("type" -> "sessionList".asJson, "sessions" -> sessions.asJson)
    case RelayPayload.ExecuteCommand(sid, cmd) =>
      io.circe.Json.obj("type" -> "executeCommand".asJson, "sessionId" -> sid.asJson, "command" -> cmd.asJson)
  }

  given Decoder[RelayPayload] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "userInput" =>
        for
          sid <- c.downField("sessionId").as[String]
          content <- c.downField("content").as[String]
        yield RelayPayload.UserInput(sid, content)
      case "listSessions" =>
        Right(RelayPayload.ListSessions())
      case "sessionList" =>
        c.downField("sessions").as[List[SessionSummary]].map(RelayPayload.SessionList(_))
      case "executeCommand" =>
        for
          sid <- c.downField("sessionId").as[String]
          cmd <- c.downField("command").as[String]
        yield RelayPayload.ExecuteCommand(sid, cmd)
      case other => Left(io.circe.DecodingFailure(s"Unknown relay payload type: $other", c.history))
    }
  }

object SessionSummary:
  given Encoder[SessionSummary] = deriveEncoder
  given Decoder[SessionSummary] = deriveDecoder

object RelayMessage:
  given Encoder[RelayMessage] = deriveEncoder
  given Decoder[RelayMessage] = deriveDecoder
