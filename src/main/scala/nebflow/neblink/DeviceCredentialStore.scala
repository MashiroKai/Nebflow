package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.PathUtil

import java.nio.file.attribute.PosixFilePermissions

/** Long-lived per-device NebLink credential, persisted to
  * `~/.nebflow/neblink/device.json` with owner-only permissions (rw-------).
  *
  * Produced by pairing-code enrollment (`POST /api/device/enroll`). The raw
  * `deviceToken` is the secret; only its SHA-256 hash is stored on the server,
  * so this file is the only place the live credential exists on the device.
  */
case class DeviceCredential(
  serverUrl: String,
  networkId: String,
  deviceId: String,
  deviceToken: String
)

object DeviceCredential:
  given Encoder[DeviceCredential] = deriveEncoder
  given Decoder[DeviceCredential] = deriveDecoder

  private val credPath = PathUtil.dataRoot / "neblink" / "device.json"

  def load: IO[Option[DeviceCredential]] =
    IO.blocking {
      if os.exists(credPath) then
        decode[DeviceCredential](os.read(credPath)).toOption
      else None
    }

  def save(cred: DeviceCredential): IO[Unit] =
    IO.blocking {
      os.write.over(credPath, cred.asJson.spaces2, createFolders = true)
      // Restrict file permissions to owner-only (rw-------), mirroring Auth.
      try
        val perms = PosixFilePermissions.fromString("rw-------")
        java.nio.file.Files.setPosixFilePermissions(
          java.nio.file.Paths.get(credPath.toString),
          perms
        )
      catch case _: Exception => () // best effort on non-POSIX systems
    }

  /** Clear the persisted credential (e.g. when unpairing). */
  def clear: IO[Unit] =
    IO.blocking {
      if os.exists(credPath) then os.remove(credPath)
    }.void
