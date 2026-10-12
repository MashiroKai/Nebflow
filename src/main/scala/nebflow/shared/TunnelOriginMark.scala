package nebflow.shared

import org.http4s.Request

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-self-signed tunnel-origin mark (introduced in stage C of the token
 * retirement route): lets the gateway tell "this request crossed the portal
 * tunnel" apart from "this request arrived from a direct client".
 *
 * Why a mark is needed at all — the two tunnel transports look different:
 *  - HTTP: the tunnel bridge calls the lifted app IN PROCESS
 *    ([[nebflow.neblink.PortalTunnelBridge]] builds the `Request[IO]` itself),
 *    so `req.remote` is None — already a mechanical signal a direct client
 *    cannot produce (a server-accepted request always carries one);
 *  - WebSocket: the bridge dials a REAL loopback socket
 *    (`ws://127.0.0.1:<port>`), so the server sees remote = 127.0.0.1 —
 *    indistinguishable from a direct local client. The handshake headers are
 *    the browser's own (minus the dropped hop-by-hop/upgrade set), so no
 *    header shape separates the two either. The injected mark is the ONLY
 *    tunnel discriminator on this face.
 *
 * Mechanics: at boot the tunnel assembly arms a fresh ≥256-bit random value
 * ([[arm]]); the bridge injects it as [[headerName]] on every forwarded
 * request — after first dropping any same-named client-supplied header, so a
 * request-side value is either the bridge's own or a guess. A direct client
 * cannot know the in-process value, so a MATCHING header proves tunnel origin;
 * a garbage value simply reads as "not marked" (harmless — it only weakens
 * the sender's own request).
 *
 * Consumer status (stage D): the sole consumer, the GatewayAuth origin gate
 * over the token query / legacy cookie rails, left with those rails when
 * stage D retired them. The arm + inject machinery is RETAINED UNREAD as the
 * tunnel-hardening batch's keep-face: tunnel-origin discrimination is
 * expected to return there (e.g. per-face tunnel policy), and until that
 * ruling the mark asserts nothing — a request authenticates identically
 * whether marked or not.
 */
object TunnelOriginMark:

  /** Brand-derived header name — same discipline as GatewayAuth.CredentialHeader. */
  val headerName: String = s"x-${Branding.lowerName}-tunnel-mark"

  /** 32 random bytes = 256 bits of entropy, rendered as 64 lowercase hex chars. */
  private val MarkBytes = 32

  private val value = new AtomicReference[Option[String]](None)
  private val rng = new SecureRandom()

  /**
   * Arm the mark with a fresh random value. Called by the tunnel assembly —
   * the segment that wires the portal bridge, BEFORE any bridge can serve —
   * so a serving bridge never observes the unarmed state. Idempotent: the
   * first value wins and the mark is read-only afterwards.
   */
  def arm(): Unit =
    val bytes = new Array[Byte](MarkBytes)
    rng.nextBytes(bytes)
    val hex = bytes.map(b => f"${b & 0xff}%02x").mkString
    value.compareAndSet(None, Some(hex))
    ()

  /** The armed value; None until [[arm]] — an unarmed mark matches nothing. */
  def armedValue: Option[String] = value.get

  /** True iff the request carries the CURRENTLY armed mark header value. */
  def isMarked(req: Request[?]): Boolean =
    armedValue match
      case None => false
      case Some(v) =>
        req.headers.headers.exists(h => h.name.toString.equalsIgnoreCase(headerName) && h.value == v)

  /**
   * The (name, value) pair the bridge injects once armed; None before [[arm]]
   * keeps unarmed wirings (early-boot, tests) inert.
   */
  def injectHeader: Option[(String, String)] = armedValue.map(headerName -> _)

end TunnelOriginMark
