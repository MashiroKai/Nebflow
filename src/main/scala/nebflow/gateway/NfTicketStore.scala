package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.parser.parse
import nebflow.core.PathUtil

import java.security.SecureRandom
import java.util.Base64

/** A short-lived, path-bound read credential for `GET /api/nf-file`.
  *
  * `/api/nf-file` used to be gated by the global gateway token, which is
  * permanent and path-agnostic: anyone holding it could read any path the
  * extension whitelist allows, forever. The C batch replaces that leg with
  * an opaque, per-path, TTL-bounded ticket minted by `POST /api/nf-ticket`
  * (authenticated by the same global token, so the ticket is strictly
  * narrower than what it replaces).
  *
  * `remaining` is the request budget (R4 = unlimited inside the TTL; the
  * field is carried so a future limited-count mode only has to change the
  * default value — the check below already branches on it).
  *
  * @param realPath  the `toRealPath`-normalized path this ticket unlocks
  * @param expiresAt epoch millis; at/after it the ticket is refused
  * @param remaining requests left, or [[NfTicketStore.Unlimited]]
  * @param sessionId issuing session (diagnostics only — never a root constraint)
  */
final case class NfTicket(realPath: String, expiresAt: Long, remaining: Int, sessionId: String)

/** What the issuer hands back to the caller (opaque token + its claims). */
final case class IssuedTicket(token: String, expiresAt: Long, remaining: Int)

/** Stateful ticket store for the `/api/nf-file` credential leg.
  *
  * Shape mirrors [[RateLimiter]] (`ratelimit.scala`): a `Ref[IO, Map[...]]`
  * behind a private constructor with an IO-returning `create`. All state
  * transitions happen inside a single `Ref.modify` so concurrent requests
  * cannot double-spend or observe a half-applied expiry sweep.
  *
  * TTL is `nfFile.ticketTtlSeconds` in `nebflow.json` (default
  * [[NfTicketStore.DefaultTtlSeconds]]). It is re-read from disk
  * (throttled, mtime-gated) on the issue path, so tuning it does not need a
  * restart — the same "config on disk is the authority" posture as the
  * `toolResultTtl` block, minus a WS surface (this batch deliberately adds
  * zero WS commands).
  */
class NfTicketStore private (
  ticketsRef: Ref[IO, Map[String, NfTicket]],
  ttlSecondsRef: Ref[IO, Long],
  ttlProbeRef: Ref[IO, NfTicketStore.TtlProbe]
):

  def ttlSeconds: IO[Long] = ttlSecondsRef.get

  /** Force the effective TTL (tests, and any future hot-reload surface). */
  def setTtlSeconds(seconds: Long): IO[Unit] =
    ttlSecondsRef.set(if seconds <= 0 then NfTicketStore.DefaultTtlSeconds else seconds)

  /** Re-read `nfFile.ticketTtlSeconds` from the data root's `nebflow.json`.
    *
    * Throttled to one stat/read per [[NfTicketStore.TtlProbeIntervalMs]] and
    * guarded by file mtime, so the issue path pays one `stat` at most. A
    * missing file, unreadable file or malformed value keeps the current TTL
    * (fail-safe: never silently becomes 0 = refuse everything).
    */
  def refreshTtlFromConfig(): IO[Unit] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ttlProbeRef.get.flatMap { probe =>
        if now - probe.checkedAtMs < NfTicketStore.TtlProbeIntervalMs then IO.unit
        else
          IO.blocking {
            val cfg = PathUtil.configJsonWritePath(PathUtil.dataRoot)
            val mtime = if os.exists(cfg) then os.mtime(cfg) else -1L
            val read = if mtime >= 0 then Some(os.read(cfg)) else None
            (mtime, read)
          }.attempt.flatMap {
            case Left(_) =>
              ttlProbeRef.set(NfTicketStore.TtlProbe(now, probe.mtimeMs))
            case Right((mtime, contents)) =>
              val next =
                if mtime == probe.mtimeMs then IO.unit
                else
                  contents match
                    case None => IO.unit
                    case Some(raw) =>
                      NfTicketStore.parseTtl(raw) match
                        case Some(ttl) => ttlSecondsRef.set(ttl)
                        case None      => IO.unit
              next *> ttlProbeRef.set(NfTicketStore.TtlProbe(now, mtime))
          }
      }
    }

  /** Mint a ticket for an already-validated real path.
    *
    * Also opportunistically sweeps expired tickets (the store is tiny and
    * issue is the natural chokepoint; no background fiber is added).
    */
  def issue(sessionId: String, realPath: String): IO[IssuedTicket] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ttlSecondsRef.get.flatMap { ttl =>
        val expiresAt = now + ttl * 1000L
        val token = NfTicketStore.newToken()
        val ticket = NfTicket(realPath, expiresAt, NfTicketStore.Unlimited, sessionId)
        ticketsRef
          .update { current =>
            val live = current.filter { case (_, t) => t.expiresAt > now }
            live.updated(token, ticket)
          }
          .as(IssuedTicket(token, expiresAt, ticket.remaining))
      }
    }

  /** Drop expired tickets; returns how many were swept. */
  def sweep(): IO[Int] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ticketsRef.modify { current =>
        val live = current.filter { case (_, t) => t.expiresAt > now }
        (live, current.size - live.size)
      }
    }

  /** Single-transition verification (R5 = ticket-only).
    *
    * `Left(reason)` reasons — all rendered as 403 by the read endpoint:
    *   - `invalid-ticket`  unknown opaque value (never issued, or swept)
    *   - `expired`         TTL elapsed
    *   - `exhausted`       request budget spent (unreachable while R4 stands)
    *   - `path-mismatch`   ticket is valid but bound to another path
    */
  def verifyAndConsume(token: String, realPath: String): IO[Either[String, NfTicket]] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ticketsRef.modify { current =>
        current.get(token) match
          case None =>
            (current, Left("invalid-ticket"))
          case Some(t) if t.expiresAt <= now =>
            (current - token, Left("expired"))
          case Some(t) if t.realPath != realPath =>
            (current, Left("path-mismatch"))
          case Some(t) if t.remaining == 0 =>
            (current - token, Left("exhausted"))
          case Some(t) =>
            val next =
              if t.remaining > 0 then t.copy(remaining = t.remaining - 1) else t
            (current.updated(token, next), Right(next))
      }
    }

  /** Live ticket count (diagnostics / tests). */
  def size: IO[Int] = ticketsRef.get.map(_.size)

object NfTicketStore:

  /** R4 sentinel: "unlimited requests inside the TTL". */
  val Unlimited: Int = -1

  /** R3 default TTL when `nebflow.json` carries no `nfFile.ticketTtlSeconds`. */
  val DefaultTtlSeconds: Long = 1800L

  /** Config key inside `nebflow.json` (R3). */
  val ConfigKey = "nfFile"

  private val TtlProbeIntervalMs = 5000L

  private final case class TtlProbe(checkedAtMs: Long, mtimeMs: Long)

  private val random = new SecureRandom()

  /** 32 random bytes, base64url, unpadded — opaque, path-free (C-0 = O2). */
  private def newToken(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  /** Pure: pull `nfFile.ticketTtlSeconds` out of a `nebflow.json` body.
    * Non-positive / non-integer / absent → None (caller keeps its value). */
  def parseTtl(configJson: String): Option[Long] =
    parse(configJson).toOption
      .flatMap(_.hcursor.downField(ConfigKey).downField("ticketTtlSeconds").as[Long].toOption)
      .filter(_ > 0)

  def create(ttlSeconds: Long = DefaultTtlSeconds): IO[NfTicketStore] =
    for
      tickets <- Ref.of[IO, Map[String, NfTicket]](Map.empty)
      ttl <- Ref.of[IO, Long](if ttlSeconds > 0 then ttlSeconds else DefaultTtlSeconds)
      probe <- Ref.of[IO, TtlProbe](TtlProbe(0L, -1L))
    yield new NfTicketStore(tickets, ttl, probe)

  /** Construction without an IO context (WebSocketRoutes runs inside a `Ref.unsafe`
    * constructor already — see `rootAgents`). GatewayMain uses this with the
    * TTL read from `nebflow.json`; the no-arg form is the constructor default. */
  def unsafeCreate(ttlSeconds: Long): NfTicketStore =
    new NfTicketStore(
      Ref.unsafe(Map.empty),
      Ref.unsafe(if ttlSeconds > 0 then ttlSeconds else DefaultTtlSeconds),
      Ref.unsafe(TtlProbe(0L, -1L))
    )

  def unsafeDefault(): NfTicketStore = unsafeCreate(DefaultTtlSeconds)

  /** Read the configured TTL once (startup path in GatewayMain), fail-safe. */
  def loadTtlSeconds(): Long =
    try
      val cfg = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      if os.exists(cfg) then parseTtl(os.read(cfg)).getOrElse(DefaultTtlSeconds)
      else DefaultTtlSeconds
    catch case _: Throwable => DefaultTtlSeconds
