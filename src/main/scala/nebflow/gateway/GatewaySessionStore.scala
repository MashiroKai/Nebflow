package nebflow.gateway

import cats.effect.{IO, Ref}
import nebflow.shared.Branding
import org.http4s.{ResponseCookie, SameSite}

import java.security.SecureRandom
import java.util.Base64

/**
 * One console session record: the value of the HttpOnly session cookie the
 * `?ticket=` redemption face mints (portal-first v3 criterion 4).
 *
 * `sessionId` is a fresh 256-bit random value — deliberately NOT the file
 * token (v3 :71): the cookie leaks nothing even when a browser extension or a
 * bug reads `document.cookie` (the session id alone cannot mint tickets or
 * rotate the token; the AuthAdmin face is file-token Bearer only).
 *
 * `origin` carries the redeemed ticket's origin verbatim: `relay-mint`
 * (portal-minted console ticket) or `local-ticket` (break-glass ticket minted
 * locally via the AuthAdmin face). Revocation on binding loss is
 * origin-scoped ([[GatewaySessionStore.revokeRelayMinted]]) — the
 * local-ticket session IS the break-glass escape hatch (v3 :290) and must
 * survive a revocation.
 */
final case class SessionRecord(
  sessionId: String,
  accountSub: String,
  origin: String,
  boundNetworkId: String,
  createdAt: Long,
  /** Hard ceiling, never extended (30d — session TTL batch 2026-10-21, was v3 :145's 12h). */
  absoluteExpiresAt: Long,
  lastSeenAt: Long,
  /** `lastSeen + idle`; expiry is `min(absolute, sliding)`. */
  slidingExpiresAt: Long
)

/**
 * Diagnostics/CLI view of a live session. `maskedId` is the FIRST 8
 * characters of the session id — enough to target a revocation, never the
 * full credential.
 */
final case class SessionView(
  maskedId: String,
  accountSub: String,
  origin: String,
  boundNetworkId: String,
  createdAt: Long,
  absoluteExpiresAt: Long,
  lastSeenAt: Long,
  slidingExpiresAt: Long
)

/**
 * In-process store for gateway console sessions (phase 3b session rail).
 *
 * Shape discipline copied from [[nebflow.neblink.ConsoleTicketStore]] (which
 * itself mirrors [[NfTicketStore]]): a `Ref[IO, Map]` behind a private
 * constructor, ALL state transitions inside a single `Ref.modify` (concurrent
 * creates / verifications cannot double-spend), and an opportunistic expiry
 * sweep on the write paths. The store is tiny — no background fiber.
 *
 * Persistence: NONE, deliberately. Sliding renewal touches every verified
 * request (a disk write per request for zero benefit), and persisting session
 * credentials would grow the stolen-artifact surface. A gateway restart kills
 * every session; the browser then falls back to the portal ticket leg or the
 * break-glass ticket — that is the registered trade-off (plan §2.6).
 *
 * Constant-time comparison (the `Auth.validateToken` `MessageDigest.isEqual`
 * discipline) is NOT used here, on purpose: session ids are 256-bit random
 * values living at most 30d (originally 12h — the lookup-failure argument is
 * entropy-bound, not lifetime-bound), and the lookup is a plain `Map` hit
 * (same as the ticket stores) — there is no long-lived secret to compare
 * against. The lookup failure mode is a Map miss, not a byte comparison loop.
 */
class GatewaySessionStore private (
  sessionsRef: Ref[IO, Map[String, SessionRecord]],
  absoluteTtlSeconds: Long,
  idleTimeoutSeconds: Long
):

  /**
   * Mint a session. Sweeps expired rows in the same `modify` (opportunistic
   * sweep, no background fiber).
   */
  def create(accountSub: String, origin: String, boundNetworkId: String): IO[SessionRecord] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      val sid = GatewaySessionStore.newSessionId()
      val rec = SessionRecord(
        sessionId = sid,
        accountSub = accountSub,
        origin = origin,
        boundNetworkId = boundNetworkId,
        createdAt = now,
        absoluteExpiresAt = now + absoluteTtlSeconds * 1000L,
        lastSeenAt = now,
        slidingExpiresAt = now + idleTimeoutSeconds * 1000L
      )
      sessionsRef
        .modify { current =>
          val live = current.filter { case (_, r) => isLive(r, now) }
          (live.updated(sid, rec), rec)
        }
    }

  /**
   * Single-transition verification + touch: a hit inside BOTH windows
   * advances `lastSeenAt` / `slidingExpiresAt` (capped at the absolute
   * ceiling) in the SAME `modify`; an expired row is deleted in that same
   * transition (expired-then-gone, same shape as the ticket stores); a miss
   * returns `None`.
   */
  def verifyAndTouch(sessionId: String): IO[Option[SessionRecord]] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      sessionsRef.modify { current =>
        current.get(sessionId) match
          case None =>
            (current, None)
          case Some(r) if !isLive(r, now) =>
            (current - sessionId, None)
          case Some(r) =>
            val touched = r.copy(
              lastSeenAt = now,
              slidingExpiresAt = math.min(r.absoluteExpiresAt, now + idleTimeoutSeconds * 1000L)
            )
            (current.updated(sessionId, touched), Some(touched))
      }
    }

  /** Live sessions, masked-id views (CLI / endpoint enumeration face). */
  def list: IO[List[SessionView]] =
    sessionsRef.get.map { current =>
      current.values.toList
        .map(r => SessionView(r.sessionId.take(8), r.accountSub, r.origin, r.boundNetworkId, r.createdAt, r.absoluteExpiresAt, r.lastSeenAt, r.slidingExpiresAt))
        .sortBy(_.createdAt)
    }

  /** Revoke every session whose id starts with `prefix` (masked-id targeting). */
  def revokeByMaskedId(prefix: String): IO[Int] =
    if prefix.isEmpty then IO.pure(0)
    else
      sessionsRef.modify { current =>
        val (hit, rest) = current.partition { case (sid, _) => sid.startsWith(prefix) }
        (rest, hit.size)
      }

  /** Revoke EVERY session (`sessions revoke --all`, the local emergency face). */
  def revokeAll(): IO[Int] = sessionsRef.getAndSet(Map.empty).map(_.size)

  /**
   * Revoke only `origin == relay-mint` sessions — the binding-revocation
   * action (v3 :184 "no 12h residual window"). `local-ticket` sessions
   * survive ON PURPOSE: the break-glass face must stay usable after a
   * revocation (v3 :290). The renewal chain is cut elsewhere: a surviving
   * local session cannot mint tickets (the AuthAdmin face is file-token
   * Bearer only), so "local survives" buys nothing to an attacker.
   */
  def revokeRelayMinted(): IO[Int] =
    sessionsRef.modify { current =>
      val (hit, rest) = current.partition { case (_, r) => r.origin == GatewaySessionStore.OriginRelayMint }
      (rest, hit.size)
    }

  /** Live session count (diagnostics / tests). */
  def size: IO[Int] = sessionsRef.get.map(_.size)

  private def isLive(r: SessionRecord, nowMs: Long): Boolean =
    nowMs < r.absoluteExpiresAt && nowMs < r.slidingExpiresAt

end GatewaySessionStore

object GatewaySessionStore:

  /**
   * Absolute TTL: the 30d hard ceiling (session TTL batch 2026-10-21, was
   * v3 :145's 12h), never renewed. The value aligns with the chain's other
   * 30d anchors — the Logto SSO `_session` cookie and the relay refresh
   * cookie both live 30d, so an engine cookie outliving them would buy zero
   * usability while extending the stolen-cookie exposure.
   */
  val AbsoluteTtlSeconds: Long = 2592000L

  /**
   * Sliding idle window (plan §2.3), capped by the absolute ceiling. 7d
   * (was 2h): every verified request renews it (`verifyAndTouch`), so an
   * actively used console is never idle-kicked; a week of total inactivity
   * is still a hard stop. The 30d − 7d gap is deliberate defense-in-depth:
   * a session that died quietly from disuse cannot be resurrected by an
   * attacker who later finds the stale cookie (the browser already dropped
   * it, and the store row expired-then-gone).
   */
  val IdleTimeoutSeconds: Long = 604800L

  /** Session origins, carried verbatim from the redeemed ticket. */
  val OriginRelayMint: String = "relay-mint"
  val OriginLocalTicket: String = "local-ticket"

  /**
   * Session cookie name, derived from the brand single source (same
   * discipline as the brand-injected `window.__BRAND__`): the server writes
   * and reads ONE computed name, so rebrand day cannot drift a second
   * hardcoded literal into the codebase. Distinct from the legacy
   * `nebflow_token` cookie by construction (criterion 4's HttpOnly assertion
   * is then immune to same-name jar interference).
   */
  val CookieName: String = s"${Branding.lowerName}_session"

  /**
   * The session cookie, with the frozen attribute set (v3 :145): HttpOnly +
   * SameSite=Lax + host-only (no `domain`) + `Path=/` + `Max-Age=<absolute
   * TTL>` (today 2592000 = 30d), plus `Secure` on the faces where the
   * consuming browser origin is HTTPS (tunnel-marked relay legs and direct
   * https). #522: on a plain-http direct leg (the local console, e.g. Safari
   * at http://127.0.0.1:8080) the `Secure` flag MUST be omitted — WebKit
   * drops Secure cookies delivered over plain http (no localhost exemption,
   * unlike Chrome/Firefox), which silently bricked session seating on the
   * local console: every later request (WS handshake + REST) answered 403
   * while the user was in fact seated.
   *
   * Emission points (session TTL batch 2026-10-21): the 303 redemption
   * response (mint, [[nebflow.gateway.ConsoleTicketRoutes]]) AND the
   * `GET /api/nf-authcheck` 204 when the session rail hit (the browser-side
   * slide). The VALUE never changes over the session's life (sliding renewal
   * is server-side) — the re-emissions only refresh the browser jar's
   * `Max-Age`, so an actively used console never hits the cookie's fixed
   * expiry. Both faces recompute `secure` from the consuming origin, never
   * from a stored flag.
   */
  def newCookie(sessionId: String, secure: Boolean = true): ResponseCookie =
    ResponseCookie(
      name = CookieName,
      content = sessionId,
      expires = None,
      maxAge = Some(AbsoluteTtlSeconds),
      domain = None, // host-only: never set a Domain attribute
      path = Some("/"),
      sameSite = Some(SameSite.Lax),
      secure = secure,
      httpOnly = true,
      extension = None
    )

  private val random = new SecureRandom()

  /** 32 random bytes, base64url, unpadded — 256 random bits (Auth.generateToken shape). */
  private def newSessionId(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  def create(
    absoluteTtlSeconds: Long = AbsoluteTtlSeconds,
    idleTimeoutSeconds: Long = IdleTimeoutSeconds
  ): IO[GatewaySessionStore] =
    Ref.of[IO, Map[String, SessionRecord]](Map.empty)
      .map(new GatewaySessionStore(_, absoluteTtlSeconds, idleTimeoutSeconds))

  /** Sync construction (assembly code runs outside an IO constructor; same as the ticket stores). */
  def unsafeCreate(
    absoluteTtlSeconds: Long = AbsoluteTtlSeconds,
    idleTimeoutSeconds: Long = IdleTimeoutSeconds
  ): GatewaySessionStore =
    new GatewaySessionStore(Ref.unsafe(Map.empty), absoluteTtlSeconds, idleTimeoutSeconds)

end GatewaySessionStore
