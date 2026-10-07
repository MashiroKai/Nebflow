package nebflow.social

import cats.effect.IO
import nebflow.core.CredentialFileAcl
import nebflow.shared.NebflowLogger

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.SecureRandom
import java.util.Base64

/**
 * The weixin-ilink ingress token — the shim's credential for the ingest leg
 * (weixin-inbound batch, 2026-10-07). The security design lives in the batch
 * report §1 (`.nebflow/reports/` + `20261007_102314_weixin-inbound__chain-n-6c92ac27.md`),
 * which was written BEFORE this file; this object is the single definition
 * point the outbound batch (weixin-outbound-loop-x1) references without
 * rewriting.
 *
 * One credential, one operation: the token authorizes exactly
 * `POST /api/social/channels/weixin-ilink/ingress` — and nothing else. It is
 * deliberately NOT wired into the global `checkAuth`/`withAuth` gates: track 1
 * of the ingest route stays the admin token (unchanged), track 2 is this token,
 * accepted route-locally ([[nebflow.gateway.PresenceRoutes]] ingress arm), so
 * the least-privilege boundary is a property of placement, not of discipline.
 *
 * Lifecycle (report §1.1):
 *   - minted by the ENGINE at seam-up ([[WeixinIlinkBridgePlugin.start]] →
 *     [[ensure]]): 32 SecureRandom bytes, base64url unpadded — the same
 *     generator family as the gateway admin token (`gateway/auth.scala`);
 *   - stored ONLY at `<dataRoot>/secrets/[[FileName]]`, owner-only (`rw-------`)
 *     from its creation attribute onward — the same F1 discipline as
 *     [[SocialChannels]] (no plaintext instant in a not-yet-narrowed file), and
 *     the narrowing step is the SHARED `CredentialFileAcl`, never a second copy
 *     of the ACL logic;
 *   - delivered by FILE, never over the wire: the shim (same host, same user —
 *     the trust domain it already occupies by reading the host account files)
 *     re-reads the file per POST, so a rotation (in-place rewrite) is picked up
 *     on the next request and a deletion (revocation) closes track 2 at once;
 *   - verified with a constant-time compare ([[accepts]]); an absent file, an
 *     unreadable file and a mismatch all collapse into the same `false` — the
 *     caller answers the byte-identical 403 the admin track fails with, so the
 *     two tracks are indistinguishable from outside (no enumeration oracle);
 *   - [[ensure]] is idempotent (an existing well-formed token is never
 *     rewritten) and self-heals an empty/corrupt residue by minting afresh in
 *     place — an engine restart keeps the value (persistent file ⇒ the shim
 *     re-attaches without re-provisioning), and a token file loss costs nothing
 *     but a new mint at the next seam-up.
 *
 * 🔴 Zero-value logging: no method ever logs the token's content — only the
 * path and the outcome.
 */
object WeixinIngressToken:

  private val logger = NebflowLogger.forName("nebflow.social.weixin-ingress-token")

  /** File name under `<dataRoot>/secrets/`. The shim learns the path out of
    * band (its `WEIXIN_MSGLOOP_TOKEN_FILE`), never from an API response. */
  val FileName = "weixin-ilink-ingress-token"

  def path(root: os.Path): os.Path = root / "secrets" / FileName

  /** Mint-or-keep. Concurrent callers are safe: the create winner owns the
    * file, the loser finds it existing and keeps it — a failure cleanup never
    * deletes a file this call did not create or rewrite. A pre-existing file
    * that is empty or not a well-formed token is residue and is regenerated
    * IN PLACE (narrow-before-plaintext, inode preserved). */
  def ensure(root: os.Path): IO[Unit] = IO.blocking {
    val p = path(root).toNIO
    if Files.exists(p) && isWellFormed(readRaw(p)) then ()
    else
      val regen = Files.exists(p) // rotating residue, not a first mint
      var ours = false // the file at `p` was created or rewritten by THIS call
      try
        Files.createDirectories(p.getParent)
        if regen then CredentialFileAcl.restrict(p) // narrow the residue BEFORE plaintext
        else createOwnerOnly(p)
        ours = true
        Files.write(p, mint().getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        CredentialFileAcl.restrict(p) // the authoritative narrowing step
        logger.info(s"weixin ingress token: ${if regen then "regenerated" else "minted"} $p (owner-only)")
      catch
        case e: Exception =>
          if ours then
            try Files.deleteIfExists(p)
            catch case _: Exception => ()
          throw e
  }

  /** Track-2 verification for the ingest route: does `bearer` match the stored
    * token? Total and side-effect free; EVERY failure mode (no header, no file,
    * unreadable, corrupt, mismatch) is the same `false`, and the caller answers
    * the byte-identical 403 the admin track fails with. */
  def accepts(root: os.Path, bearer: Option[String]): IO[Boolean] =
    bearer match
      case None => IO.pure(false)
      case Some(provided) =>
        IO.blocking(readRaw(path(root).toNIO)).redeem(
          _ => false,
          stored =>
            isWellFormed(stored) && java.security.MessageDigest.isEqual(
              provided.getBytes("UTF-8"),
              stored.getBytes("UTF-8")
            )
        )

  // ───────────────────────────── internals ─────────────────────────────

  /** The token shape this module mints and accepts: 43 base64url chars
    * (32 bytes, unpadded). Anything else reads as corrupt residue. */
  private val TokenPattern = "^[A-Za-z0-9_-]{43}$"

  private def isWellFormed(s: String): Boolean = s.nonEmpty && s.matches(TokenPattern)

  private def readRaw(p: Path): String =
    new String(Files.readAllBytes(p), "UTF-8").trim

  /** Auth.generateToken's generator: 32 SecureRandom bytes, base64url, no pad. */
  private def mint(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  /** Created `rw-------` from its first byte on POSIX (the F1 rule); on a
    * filesystem without POSIX attribute support the plain create is immediately
    * followed by the shared restrict BEFORE any plaintext is written, so the
    * narrowing still precedes the secret on every platform (the same fallback
    * shape [[SocialChannels.createOwnerOnly]] documents). */
  private def createOwnerOnly(p: Path): Unit =
    try
      Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(CredentialFileAcl.PosixMode)))
    catch
      case _: UnsupportedOperationException =>
        Files.createFile(p)
        CredentialFileAcl.restrict(p)

end WeixinIngressToken
