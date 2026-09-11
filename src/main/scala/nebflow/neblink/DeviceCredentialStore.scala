package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.{CredentialFileAcl, NebflowLogger, PathUtil}

/**
 * Long-lived per-device NebLink credential, persisted to
 * `~/.nebflow/neblink/device.json` with owner-only access control (POSIX
 * `rw-------`; on Windows a DACL holding a single ACE for the current user —
 * see [[nebflow.core.CredentialFileAcl]] for the Q2/2026-09-11 rationale).
 *
 * Produced by pairing-code enrollment (`POST /api/device/enroll`), the
 * device flow, or the Logto AC+PKCE callback. The raw `deviceToken` is the
 * secret; only its SHA-256 hash is stored on the server, so this file is the
 * only place the live credential exists on the device.
 *
 * `logto` (stage 2, 2026-08-28): the provider-side login credential carried
 * in this file. Since O5 (2026-09-11) the authorize request no longer asks
 * for `offline_access`, so a NEW login receives an id_token but NO refresh
 * token: the block is therefore present when EITHER half exists, and the
 * identity half (see [[LogtoRefresh]]) must not be gated on the refresh half.
 * Optional + backward compatible (older files without the block decode
 * unchanged). Logto ROTATES refresh tokens — every refresh must write the
 * latest value back.
 */
case class DeviceCredential(
  serverUrl: String,
  networkId: String,
  deviceId: String,
  deviceToken: String,
  logto: Option[LogtoRefresh] = None
)

/** Provider login credential (Logto) stored in `device.json` — TWO independent
  * halves:
  *
  *  - `refreshToken` + `updatedAt`: the silent re-login credential (rotation
  *    bookkeeping). `refreshToken == ""` is the EXPLICIT "this device holds no
  *    refresh token" marker (see [[LogtoRefresh.of]]); the key itself is still
  *    written, deliberately, so a decoder that predates this convention keeps
  *    reading the file instead of dropping the whole credential (the schema
  *    only becomes optional on the READ side — see the decoder).
  *  - `idToken`: the raw id_token from the token response. It is the ONLY
  *    account-identity source reachable by the web client (`email` /
  *    `displayName` on `/api/neblink/status`, read-only claim decode) and the
  *    `id_token_hint` of the end-session handoff (a valid hint skips the
  *    provider's logout confirmation page; a fabricated one is rejected with
  *    400, probed 2026-09-06).
  *
  * INDEPENDENCE (2026-09-11, O5 companion fix): identity persistence and the
  * refresh credential are decoupled. O5 removed `offline_access` from the
  * authorize request (`LogtoAuthCode.authorizeUrl`), so every new login
  * persists an id_token WITHOUT a refresh token; a block in that state is the
  * EXPECTED post-O5 shape, not an anomaly (it cannot silent-relogin, and the
  * identity / logout-hint halves work exactly as before).
  *
  * Backward compatible: files written before `idToken` existed decode with
  * None; files written before the O5 fix decode with a real refresh token and
  * keep rotating until the provider invalidates it. */
case class LogtoRefresh(
  refreshToken: String,
  updatedAt: Long,
  idToken: Option[String] = None
):
  /** True when this block actually carries something (at least one of the two
    * halves). Information-free blocks are never written and are normalised
    * away on read, so `DeviceCredential.logto.isDefined` means "there IS a
    * stored login credential". */
  def hasContent: Boolean = refreshToken.nonEmpty || idToken.exists(_.nonEmpty)

object LogtoRefresh:
  /** Build a block from the two independent halves (used by the enrollment
    * path, which is the only writer that can carry either). `None` when
    * neither is known — an information-free block is never persisted. Empty
    * strings are treated as absent (see the case class). */
  def of(
    refreshToken: Option[String],
    idToken: Option[String],
    nowMs: Long = System.currentTimeMillis()
  ): Option[LogtoRefresh] =
    val rt = refreshToken.filter(_.nonEmpty)
    val id = idToken.filter(_.nonEmpty)
    if rt.isDefined || id.isDefined then Some(LogtoRefresh(rt.getOrElse(""), nowMs, id)) else None

  /** Hand-written encoder mirrors the DeviceCredential style: absent optional
    * fields stay absent (no nulls in the credential file), BUT `refreshToken`
    * is always written — an empty string when no token is held. Why not drop
    * the key: a pre-fix decoder requires it (`.as[String]`), so dropping it
    * would make the whole credential undecodable for such a reader. */
  given Encoder[LogtoRefresh] = Encoder.instance { r =>
    val base = JsonObject(
      "refreshToken" -> r.refreshToken.asJson,
      "updatedAt" -> r.updatedAt.asJson
    )
    Json.fromJsonObject(
      r.idToken.fold(base)(v => base.add("idToken", v.asJson))
    )
  }

  /** Read side is OPTIONAL (schema compatibility, 2026-09-11): a `logto`
    * object without `refreshToken` (the shape an identity-only writer would
    * produce) decodes with the empty marker instead of failing the whole
    * file. */
  given Decoder[LogtoRefresh] = Decoder.instance { c =>
    for
      refreshToken <- c.downField("refreshToken").as[Option[String]]
      updatedAt <- c.downField("updatedAt").as[Long]
      idToken <- c.downField("idToken").as[Option[String]]
    yield LogtoRefresh(refreshToken.getOrElse(""), updatedAt, idToken)
  }

object DeviceCredential:
  /** Encoder omits the `logto` block when absent (clean legacy-shape files).
    * Post-O5 the block is written for identity-only credentials too (id_token
    * present, refresh token absent) — see [[LogtoRefresh]]. */
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

  /** Decoder tolerates files without `logto` (pre-stage-2), and normalises an
    * information-free block away: a `logto` object carrying neither a refresh
    * token nor an id_token stores nothing, and keeping it would make
    * `logto.isDefined` a false "there is a stored credential" signal for every
    * reader (status identity, logout hint, silent re-login). */
  given Decoder[DeviceCredential] = Decoder.instance { c =>
    for
      serverUrl <- c.downField("serverUrl").as[String]
      networkId <- c.downField("networkId").as[String]
      deviceId <- c.downField("deviceId").as[String]
      deviceToken <- c.downField("deviceToken").as[String]
      logto <- c.downField("logto").as[Option[LogtoRefresh]]
    yield DeviceCredential(serverUrl, networkId, deviceId, deviceToken, logto.filter(_.hasContent))
  }

  private val log = NebflowLogger.forName("nebflow.neblink.devicecred")

  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def credPath = PathUtil.dataRoot / "neblink" / "device.json"

  def load: IO[Option[DeviceCredential]] =
    IO.blocking {
      if os.exists(credPath) then decode[DeviceCredential](os.read(credPath)).toOption
      else None
    }

  def save(cred: DeviceCredential): IO[Unit] =
    save(cred, CredentialFileAcl.systemPort, CredentialFileAcl.currentOsName)

  /** Seam overload (T3, 2026-09-11): `aclPort` + `osName` are parameters so the
    * branch selection is unit-testable on macOS (no NTFS ACL view, no icacls).
    * Public [[save]] delegates with the production port and the live `os.name`,
    * so production behaviour is identical.
    *
    * DESTRUCTIVE BY DESIGN: the whole file is replaced (`os.write.over`), so
    * fields absent from `cred` disappear. Callers that only know PART of the
    * credential (enrollment: it receives the login's refresh token / id_token
    * as two independent options) must merge with [[load]] first; the merge rule
    * lives in `NeblinkEnrollment.persist`.
    *
    * The ACL failure path is deliberately non-fatal (the credential is already
    * on disk) but never silent: a warning is logged, because "could not narrow
    * the ACL" is exactly the state in which the device token is readable by
    * other principals (Q2 defect). */
  private[neblink] def save(
    cred: DeviceCredential,
    aclPort: CredentialFileAcl.Port,
    osName: String
  ): IO[Unit] =
    IO.blocking {
      os.write.over(credPath, cred.asJson.spaces2, createFolders = true)
      // Owner-only access control: rw------- on POSIX, single-owner DACL on
      // Windows (the platform where the old POSIX call was a silent no-op).
      try
        CredentialFileAcl.restrict(java.nio.file.Paths.get(credPath.toString), osName, aclPort)
        None
      catch
        case e: Exception =>
          Some(s"os=$osName ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
    }.flatMap {
      case None => IO.unit
      case Some(msg) =>
        log.warn(
          s"device.json owner-only ACL not applied ($msg) — the credential file may be " +
            "readable by principals other than the current user"
        )
    }

  /** Write back the latest rotated refresh token (no-op when nothing is
    * persisted yet — enrollment owns the first write). `newIdToken` REPLACES
    * the stored hint when the provider issued a fresh id_token (refresh
    * grant response) and KEEPS the previous one when absent. An
    * identity-only block (no refresh token) is filled in place: rotation is
    * the only way such a block can gain a refresh credential.
    *
    * NOTE (2026-09-11): this write is a FULL-FILE rewrite
    * ([[save]] → `os.write.over`), so any writer that rebuilds the credential
    * from scratch must merge with the stored file instead of dropping what it
    * was not given — see `NeblinkEnrollment.persist`. */
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
