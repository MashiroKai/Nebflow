package nebflow.mesh

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.PathUtil

import java.util.UUID

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
    s"$hostname ($platform)"

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
  given Decoder[PeerInfo] = deriveDecoder

// ===== File Fingerprint =====

case class FileFingerprint(
  mtime: Long,
  size: Long,
  hash: String
)

object FileFingerprint:
  given Encoder[FileFingerprint] = deriveEncoder
  given Decoder[FileFingerprint] = deriveDecoder

  def compute(path: os.Path): Option[FileFingerprint] =
    if !os.exists(path) then None
    else
      val stat = os.stat(path)
      val content = os.read.bytes(path)
      val hash = computeHash(content)
      Some(FileFingerprint(stat.mtime.toMillis, stat.size, hash))

  def computeHash(bytes: Array[Byte]): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    digest.digest().take(6).map(b => String.format("%02x", b)).mkString

end FileFingerprint

// ===== Cloud File Download =====

case class CloudFileDownload(path: String, content: String, fingerprint: FileFingerprint)

object CloudFileDownload:

  given Decoder[CloudFileDownload] = Decoder.instance { c =>
    for
      path <- c.downField("path").as[String]
      content <- c.downField("content").as[String]
      fpMtime <- c.downField("fingerprint").downField("mtime").as[Long]
      fpSize <- c.downField("fingerprint").downField("size").as[Long]
      fpHash <- c.downField("fingerprint").downField("hash").as[String]
    yield CloudFileDownload(path, content, FileFingerprint(fpMtime, fpSize, fpHash))
  }

/** COS-based download item — path + fileID, content fetched separately via file/download. */
case class CloudFileDownloadItem(path: String, fileID: String)

object CloudFileDownloadItem:

  given Decoder[CloudFileDownloadItem] = Decoder.instance { c =>
    for
      path <- c.downField("path").as[String]
      fileID <- c.downField("fileID").as[String]
    yield CloudFileDownloadItem(path, fileID)
  }

// ===== Sync Diff =====

case class SyncDiff(
  needUpload: List[String],
  needDownload: List[String],
  unchanged: List[String]
)

object SyncDiff:
  given Encoder[SyncDiff] = deriveEncoder
  given Decoder[SyncDiff] = deriveDecoder

// ===== Mesh Config =====

case class MeshConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 300,
  cloudUrl: Option[String] = None  // Self-hosted server URL, configured by user
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
