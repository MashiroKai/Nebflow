package nebflow.social

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.CredentialFileAcl
import nebflow.shared.{NebflowLogger, SharedBackend}
import sttp.client4.*

import java.nio.file.{Files, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.duration.*

/**
 * Weixin outbound leg — engine → side-car send face (weixin-outbound batch,
 * 2026-10-07). The receiver-side counterpart of [[WeixinIlinkBridgePlugin]]'s
 * inbound `deliver`: a session reply leaves the engine through HERE, reaches
 * the side-car's authenticated `POST /send`, and from there the iLink
 * `sendmessage` wire (the side-car implements that half; this class never
 * speaks iLink).
 *
 * Security design (batch report §0 — THIS file implements it verbatim):
 *
 *   - The SEND TOKEN is minted by the engine ([[ensureToken]]: 32 random
 *     bytes, base64url, `<dataRoot>/secrets/weixin-ilink-send-token`, owner-only
 *     from the first byte — the F1 discipline, the definer's own shape) and
 *     presented by the engine on every call (`Authorization: Bearer …`).
 *     Credential-face reconciliation (the definer = [[WeixinIngressToken]],
 *     landed mid-flight): ONE system, ONE instance per leg — same generator,
 *     same storage discipline, same well-formedness rule, same constant-time
 *     verify family; the definer's doc pins their token as authorizing exactly
 *     the ingress POST, so this leg carries a SIBLING instance rather than a
 *     reuse that would rewrite their scope. Env spellings live on the side-car
 *     side (`WEIXIN_SEND_TOKEN[_FILE]`) — 候与入站批对账归一.
 *   - The bot_token NEVER passes through here: the side-car reads it from the
 *     host account file it already mirrors. This class's zero-secret surface is
 *     one file path and one bearer header; neither value is ever logged,
 *     echoed, or rendered (log lines carry reasons and HTTP codes only).
 *   - Retry boundary: NONE on this side. A timed-out flush may already have
 *     been accepted by iLink, so a re-send could duplicate the reply; the
 *     side-car owns the (network-class-only) retry budget. A failure here is
 *     ONE contained WARN line — never an exception, never a queue (v1).
 *
 * Failure vocabulary (design §0.6, the inbound `sidecar_unreachable` family):
 *   transport error ⇒ `sidecar_unreachable`; HTTP 401 ⇒ `shim_auth_rejected`;
 *   HTTP 503 ⇒ `shim_token_unconfigured`; non-2xx otherwise ⇒
 *   `delivery_failed:<detail>`.
 *
 * Every config read is COLD (per send): `sidecar_url` and the token file are
 * re-read once per completed turn, so a saved URL or a re-minted token applies
 * without a restart — the same cold-read shape the bridge's default-session
 * arm uses.
 */
object WeixinOutboundAdapter:

  private val logger = NebflowLogger.forName("nebflow.social.weixin-outbound")

  /** The reply-flush HTTP read timeout. Arithmetic (design §0.4): the
    * side-car's worst case is attempts×timeout+backoff ≈ 2×15s+1s = 31s, so
    * 40s covers the side-car's whole budget with headroom. */
  val ReadTimeout: FiniteDuration = 40.seconds

  /** Secret file holding the shared token for THIS leg. Family-aligned with
    * the inbound batch's landed `weixin-ilink-ingress-token`
    * ([[WeixinIngressToken]] — the credential-face DEFINER): one mint/storage/
    * verify discipline, one instance PER LEG (their doc pins their token as
    * authorizing exactly the ingress POST and nothing else, so this leg's
    * credential is a sibling instance, never a reuse that would rewrite their
    * scope). Name + env spellings: 候与入站批对账归一. */
  val TokenFileName: String = "weixin-ilink-send-token"

  def tokenPath(root: os.Path): os.Path = root / "secrets" / TokenFileName

  /** The observable outcome of one send. `code` is the side-car's HTTP status
    * (-1 = transport-level failure); `reason` is a stable vocabulary string,
    * safe for logs. Never carries a credential. */
  final case class SendResult(ok: Boolean, delivered: Boolean, messageId: Option[String], code: Int, reason: String)

  /** The send seam: `(to, text) → reading`. Production = [[sendLive]]; the
    * offline spec substitutes a recorder so no test opens a socket. */
  type Send = (String, String) => IO[SendResult]

  // ───────────────────────── shared token face ─────────────────────────

  /** Read the stored token, if any (blank file = absent). */
  def readToken(root: os.Path): IO[Option[String]] = IO.blocking {
    val p = tokenPath(root).toNIO
    if !Files.exists(p) then None
    else
      val v = os.read(tokenPath(root)).trim
      if v.isEmpty then None else Some(v)
  }

  /**
   * The engine-side mint (design §0.2): ensure the shared token exists and
   * return it. Idempotent — an existing WELL-FORMED token is read, never
   * rotated (rotation would desync a running side-car); an empty/corrupt
   * residue is regenerated IN PLACE (narrow-before-plaintext, inode preserved)
   * — the same self-heal rule the definer's [[WeixinIngressToken.ensure]]
   * pins. A NEW file is created owner-only from its very first byte (the
   * SocialChannels `createOwnerOnly` discipline, F1), then narrowed again
   * through the shared [[CredentialFileAcl]] module as the authoritative step.
   * `Left` carries the reason; the caller decides.
   */
  def ensureToken(root: os.Path): IO[Either[String, String]] = IO.blocking {
    val p = tokenPath(root).toNIO
    if Files.exists(p) && isWellFormed(os.read(tokenPath(root)).trim) then
      Right(os.read(tokenPath(root)).trim)
    else
      val token = mint()
      try
        Files.createDirectories(p.getParent)
        if Files.exists(p) then CredentialFileAcl.restrict(p) // narrow the residue BEFORE the plaintext
        else createOwnerOnly(p)
        Files.write(p, token.getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        CredentialFileAcl.restrict(p)
        logger.infoSync(s"weixin outbound: send token ${if Files.exists(p) then "regenerated" else "minted"} at secrets/$TokenFileName (owner-only)")
        Right(token)
      catch
        case e: Exception =>
          try Files.deleteIfExists(p)
          catch case _: Exception => ()
          Left(s"could not mint the send token: ${e.getClass.getSimpleName}: ${e.getMessage}")
  }

  /** The token shape this module mints and accepts: 43 base64url chars (32
    * bytes, unpadded) — byte-for-byte the definer's [[WeixinIngressToken]]
    * well-formedness rule, so the family is verifiable by one eye. */
  private val TokenPattern = "^[A-Za-z0-9_-]{43}$"

  private def isWellFormed(s: String): Boolean = s.nonEmpty && s.matches(TokenPattern)

  /** Created `rw-------` from its first byte on POSIX; on a filesystem without
    * the POSIX attribute view, a plain create immediately followed by the
    * shared restrict BEFORE any plaintext (the definer's fallback shape). */
  private def createOwnerOnly(p: java.nio.file.Path): Unit =
    try
      Files.createFile(
        p,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(CredentialFileAcl.PosixMode))
      )
    catch
      case _: UnsupportedOperationException =>
        Files.createFile(p)
        CredentialFileAcl.restrict(p)

  private def mint(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  // ───────────────────────── request face ─────────────────────────
  /** The `/send` request body. Pure so the offline spec can pin the wire shape
    * without a socket. Deliberately minimal: the side-car owns everything else
    * (account selection, client id, retry). */
  def buildSendJson(to: String, text: String): Json =
    Json.obj("to" -> to.asJson, "text" -> text.asJson)

  /** The sidecar base for one send: the card's stored `sidecar_url`, else the
    * default the scan-bind control leg also uses. Cold read — one small file
    * read per completed turn. */
  def sidecarBase(root: os.Path): String =
    val fields = SocialChannels.readChannels(root).hcursor
      .downField("channels").downField(WeixinIlinkBridgePlugin.Name).downField("fields").focus.getOrElse(Json.obj())
    fields.hcursor.downField("sidecar_url").as[String].toOption
      .map(_.trim).filter(_.nonEmpty)
      .getOrElse(WeixinIlinkScanBind.SidecarDefaultBase)

  /** Response → reading (pure). 200 carries the side-car's own
    * delivered/message_id verdict; everything else maps to the vocabulary. */
  def parseSendResponse(status: Int, body: String): SendResult =
    if status == 200 then
      io.circe.parser.parse(body).toOption match
        case Some(j) =>
          val delivered = j.hcursor.downField("delivered").as[Boolean].getOrElse(false)
          val messageId = j.hcursor.downField("message_id").as[String].toOption
          if delivered then SendResult(true, true, messageId, status, "delivered")
          else SendResult(false, false, None, status, "delivery_failed: side-car answered ok without delivered")
        case None => SendResult(false, false, None, status, "delivery_failed: unparseable side-car response")
    else if status == 401 then SendResult(false, false, None, status, "shim_auth_rejected")
    else if status == 503 then SendResult(false, false, None, status, "shim_token_unconfigured")
    else
      val detail = io.circe.parser.parse(body).toOption
        .flatMap(_.hcursor.downField("error").as[String].toOption).getOrElse(body.take(120))
      SendResult(false, false, None, status, s"delivery_failed: $detail")

  /**
   * The production send: one POST to the side-car, cold token + cold
   * sidecar_url read, one attempt, total containment (every failure is a
   * [[SendResult]] reading — never a raise). The credential appears only in
   * the Authorization header; nothing here logs or echoes it.
   */
  def sendLive(root: os.Path): Send = (to, text) =>
    for
      token <- readToken(root)
      result <- token match
        case None =>
          IO.pure(SendResult(false, false, None, -1,
            s"shim_token_unconfigured: no token at secrets/$TokenFileName — the outbound leg stays closed"))
        case Some(t) =>
          val base = sidecarBase(root).stripSuffix("/")
          IO.blocking {
            val resp = basicRequest
              .post(uri"$base/send")
              .header("Content-Type", "application/json")
              .header("Authorization", s"Bearer $t")
              .body(buildSendJson(to, text).noSpaces)
              .readTimeout(ReadTimeout)
              .response(asStringAlways)
            val r = resp.send(SharedBackend.instance)
            parseSendResponse(r.code.code, r.body)
          }.handleErrorWith(e =>
            // The engine does NOT retry (design §0.4: an ambiguous timeout may
            // already be a delivered message; a re-send could duplicate it).
            IO.pure(SendResult(false, false, None, -1,
              s"sidecar_unreachable: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")))
    yield result

end WeixinOutboundAdapter
