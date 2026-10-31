package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import nebflow.shared.Branding
import org.http4s.{ResponseCookie, SameSite}

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

object SessionRecord:
  /** Snapshot (de)serialization for the optional persistence file — field names are the wire. */
  given io.circe.Codec[SessionRecord] = io.circe.generic.semiauto.deriveCodec

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
 * Persistence: OPTIONAL file snapshot (portal 会话短命根修批, 2026-10-31 —
 * 「引擎重启不杀 portal 会话」, #908 修法②). The pre-batch trade-off (a
 * gateway restart kills every session; plan §2.6) is what the portal console
 * user hit as 「登录已失效」 after every engine restart: the relay session
 * outlives the host, so every forwarded call 401'd until a manual re-entry.
 * The snapshot restores live rows across restarts:
 *   - structural changes (create / revocations) write through immediately;
 *   - sliding touches only mark dirty — a 60s daemon flushes them, so the
 *     per-request cost stays the in-memory Ref (no disk write per request,
 *     the original objection);
 *   - the file is written temp-then-atomic-move with 0600 perms; a corrupt or
 *     absent file loads as an empty store (fail-open, never blocks boot).
 * `None` (the default, and every spec assembly) keeps the pure in-memory
 * shape byte-for-byte.
 */
class GatewaySessionStore private (
  sessionsRef: Ref[IO, Map[String, SessionRecord]],
  absoluteTtlSeconds: Long,
  idleTimeoutSeconds: Long,
  persistenceFile: Option[os.Path]
):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[GatewaySessionStore])
  /** Sliding-touch dirtiness — the daemon's flush signal (write amplification guard). */
  private val dirty = new AtomicBoolean(false)
  /** One daemon thread per persistent store; no-op (None) for in-memory assemblies. */
  private val flusher: Option[ScheduledThreadPoolExecutor] = persistenceFile.map { _ =>
    val ex = new ScheduledThreadPoolExecutor(1, r =>
      new Thread(r, "gateway-session-snapshot") { setDaemon(true) })
    ex.setRemoveOnCancelPolicy(true)
    ex.scheduleWithFixedDelay(() => flushIfDirty(), 60, 60, TimeUnit.SECONDS)
    ex
  }

  private def markDirty(): Unit = dirty.set(true)

  private def flushIfDirty(): Unit =
    if dirty.getAndSet(false) then
      sessionsRef.get
        .map(rows => writeSnapshot(rows.values))
        .handleErrorWith(e => IO(log.warn("session snapshot flush failed", e)))
        .unsafeRunSync()

  /** Snapshot the current rows to the persistence file (atomic, best-effort). */
  private def writeSnapshot(rows: Iterable[SessionRecord]): Unit =
    persistenceFile.foreach { f =>
      try
        // Temp file as a SIBLING of the target (never a child — f is the file
        // path itself), then atomic move into place.
        val tmp = f / os.up / os.RelPath(f.last + ".tmp")
        os.write.over(tmp, io.circe.syntax.EncoderOps(rows.toVector).asJson.noSpaces, createFolders = true)
        if !scala.util.Properties.isWin then
          java.nio.file.Files.setPosixFilePermissions(
            tmp.toNIO, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        os.move(tmp, f, replaceExisting = true, atomicMove = true)
      catch
        case e: Throwable =>
          log.warn("session snapshot write failed: {}", e.toString)
    }

  /** Write-through after a structural mutation (create/revoke). */
  private def persistNow: IO[Unit] =
    persistenceFile match
      case None => IO.unit
      case Some(_) => sessionsRef.get.flatMap(rows => IO(writeSnapshot(rows.values)))

  /** Daemon lifecycle hook (tests shut their store down; assemblies live for the process). */
  def shutdown(): Unit = flusher.foreach(_.shutdownNow())

  /** Test seam: run the daemon's flush path synchronously (its real cadence is 60s). */
  private[gateway] def flushIfDirtyForTest(): Unit = flushIfDirty()

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
        .flatTap(_ => persistNow)
    }

  /**
   * Single-transition verification + touch: a hit inside BOTH windows
   * advances `lastSeenAt` / `slidingExpiresAt` (capped at the absolute
   * ceiling) in the SAME `modify`; an expired row is deleted in that same
   * transition (expired-then-gone, same shape as the ticket stores); a miss
   * returns `None`. Sliding touches never write through — they only mark the
   * snapshot dirty (the 60s daemon flushes it).
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
    }.flatTap(hit => IO(markDirty()).whenA(hit.isDefined && persistenceFile.isDefined))

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
      }.flatTap(n => persistNow.whenA(n > 0))

  /** Revoke EVERY session (`sessions revoke --all`, the local emergency face). */
  def revokeAll(): IO[Int] =
    sessionsRef.getAndSet(Map.empty).map(_.size).flatTap(n => persistNow.whenA(n > 0))

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
    }.flatTap(n => persistNow.whenA(n > 0))

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
    idleTimeoutSeconds: Long = IdleTimeoutSeconds,
    persistenceFile: Option[os.Path] = None
  ): IO[GatewaySessionStore] =
    IO(unsafeCreate(absoluteTtlSeconds, idleTimeoutSeconds, persistenceFile))

  /** Sync construction (assembly code runs outside an IO constructor; same as the ticket stores). */
  def unsafeCreate(
    absoluteTtlSeconds: Long = AbsoluteTtlSeconds,
    idleTimeoutSeconds: Long = IdleTimeoutSeconds,
    persistenceFile: Option[os.Path] = None
  ): GatewaySessionStore =
    val log = org.slf4j.LoggerFactory.getLogger(classOf[GatewaySessionStore])
    val initial = persistenceFile match
      case None => Map.empty[String, SessionRecord]
      case Some(f) => loadSnapshot(f, log)
    new GatewaySessionStore(
      Ref.unsafe(initial),
      absoluteTtlSeconds,
      idleTimeoutSeconds,
      persistenceFile
    )

  /**
   * Boot-time snapshot load: parse the file, keep ONLY rows still live right
   * now (both windows) — a session that died quietly from disuse must not be
   * resurrected by a restart. A missing/corrupt/unreadable file is fail-open:
   * an empty store and a WARN line, never a boot failure.
   */
  private def loadSnapshot(f: os.Path, log: org.slf4j.Logger): Map[String, SessionRecord] =
    if !os.exists(f) then Map.empty
    else
      try
        val parsed = io.circe.parser.decode[Vector[SessionRecord]](os.read(f))
        parsed match
          case Left(err) =>
            log.warn("gateway session snapshot unreadable (starting empty): {}", err.toString)
            Map.empty
          case Right(rows) =>
            val now = System.currentTimeMillis()
            val live = rows.collect { case r if r.absoluteExpiresAt > now && r.slidingExpiresAt > now => r.sessionId -> r }.toMap
            if live.nonEmpty then log.info("gateway sessions: restored {} session(s) from {}", live.size, f)
            live
      catch case e: Throwable =>
        log.warn("gateway session snapshot load failed (starting empty): {}", e.toString)
        Map.empty

end GatewaySessionStore
