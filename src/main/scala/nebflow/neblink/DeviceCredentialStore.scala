package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.PathUtil

import java.nio.file.attribute.PosixFilePermissions

/**
 * Long-lived per-device NebLink credential, persisted to
 * `~/.nebflow/neblink/device.json` with owner-only permissions (rw-------).
 *
 * Produced by pairing-code enrollment (`POST /api/device/enroll`), the
 * device flow, or the Logto AC+PKCE callback. The raw `deviceToken` is the
 * secret; only its SHA-256 hash is stored on the server, so this file is the
 * only place the live credential exists on the device.
 *
 * `logto` (stage 2, 2026-08-28): the provider refresh token for silent
 * re-login when the deviceToken is rejected. Optional + backward compatible
 * (older files without the block decode unchanged). Logto ROTATES refresh
 * tokens — every refresh must write the latest value back.
 */
case class DeviceCredential(
  serverUrl: String,
  networkId: String,
  deviceId: String,
  deviceToken: String,
  logto: Option[LogtoRefresh] = None
)

/** Provider refresh credential (Logto), with rotation bookkeeping.
  * `idToken` (RP-logout fix, 2026-09-06): the raw id_token from the same
  * token response — carried VERBATIM as `id_token_hint` on the end-session
  * handoff (a valid hint skips the provider's logout confirmation page;
  * a fabricated one is rejected with 400, probed 2026-09-06). Optional +
  * backward compatible: credentials logged in before this field existed
  * decode with None and simply log out via session cookie (+ one confirm
  * screen) until the next login refreshes it. */
case class LogtoRefresh(
  refreshToken: String,
  updatedAt: Long,
  idToken: Option[String] = None
)

object LogtoRefresh:
  /** Hand-written encoder mirrors the DeviceCredential style: absent
    * optional fields stay absent (no nulls in the credential file). */
  given Encoder[LogtoRefresh] = Encoder.instance { r =>
    val base = JsonObject(
      "refreshToken" -> r.refreshToken.asJson,
      "updatedAt" -> r.updatedAt.asJson
    )
    Json.fromJsonObject(
      r.idToken.fold(base)(v => base.add("idToken", v.asJson))
    )
  }
  given Decoder[LogtoRefresh] = Decoder.instance { c =>
    for
      refreshToken <- c.downField("refreshToken").as[String]
      updatedAt <- c.downField("updatedAt").as[Long]
      idToken <- c.downField("idToken").as[Option[String]]
    yield LogtoRefresh(refreshToken, updatedAt, idToken)
  }

object DeviceCredential:
  /** Encoder omits the `logto` block when absent (clean legacy-shape files). */
  given Encoder[DeviceCredential] = Encoder.instance { c =>
    val base = JsonObject(
      "serverUrl" -> c.serverUrl.asJson,
      "networkId" -> c.networkId.asJson,
      "deviceId" -> c.deviceId.asJson,
      "deviceToken" -> c.deviceToken.asJson
    )
    Json.fromJsonObject(
      c.logto.fold(base)(v => base.add("logto", v.asJson))
    )
  }

  /** Decoder tolerates files without `logto` (pre-stage-2). */
  given Decoder[DeviceCredential] = Decoder.instance { c =>
    for
      serverUrl <- c.downField("serverUrl").as[String]
      networkId <- c.downField("networkId").as[String]
      deviceId <- c.downField("deviceId").as[String]
      deviceToken <- c.downField("deviceToken").as[String]
      logto <- c.downField("logto").as[Option[LogtoRefresh]]
    yield DeviceCredential(serverUrl, networkId, deviceId, deviceToken, logto)
  }

  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def credPath = PathUtil.dataRoot / "neblink" / "device.json"

  def load: IO[Option[DeviceCredential]] =
    IO.blocking {
      if os.exists(credPath) then decode[DeviceCredential](os.read(credPath)).toOption
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

  /** Write back the latest rotated refresh token (no-op when nothing is
    * persisted yet — enrollment owns the first write). `newIdToken` REPLACES
    * the stored hint when the provider issued a fresh id_token (refresh
    * grant response) and KEEPS the previous one when absent. */
  def updateLogtoRefresh(refreshToken: String, newIdToken: Option[String] = None): IO[Unit] =
    load.flatMap {
      case Some(cred) =>
        val next = cred.logto match
          case Some(prev) =>
            prev.copy(refreshToken = refreshToken, updatedAt = System.currentTimeMillis(),
              idToken = newIdToken.orElse(prev.idToken))
          case None => LogtoRefresh(refreshToken, System.currentTimeMillis(), newIdToken)
        save(cred.copy(logto = Some(next)))
      case None => IO.unit
    }

  /** Clear the persisted credential (e.g. when unpairing). */
  def clear: IO[Unit] =
    IO.blocking {
      if os.exists(credPath) then os.remove(credPath)
    }.void
end DeviceCredential
