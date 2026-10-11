package nebflow.neblink

import cats.effect.{IO, Ref}

import java.security.SecureRandom
import java.util.Base64

/**
 * One-time console ticket store for the portal mint leg (contract 2.10 /
 * 2.11, phase 3a design section 3.4).
 *
 * This is a PARALLEL store, deliberately not an extension of
 * [[nebflow.gateway.NfTicket]]: the two credentials are semantically
 * heterogeneous (nf-ticket = path-bound read credential for `/api/nf-file`;
 * console ticket = account-bound one-time console entry minted over the
 * relay). What IS mirrored is the shape discipline of
 * [[nebflow.gateway.NfTicketStore]]: a `Ref[IO, Map]` behind a private
 * constructor, all state transitions inside a single `Ref.modify` (concurrent
 * mints / redemptions cannot double-spend), and an opportunistic expiry sweep
 * on the issue path (the store is tiny; no background fiber).
 *
 * One deliberate divergence, registered here: the mirrored
 * `verifyAndConsume` is lazily destructive (a redeemed nf-ticket survives as
 * a `remaining = 0` row until the next attempt), while the contract pins
 * console tickets as "redeemed-then-destroyed" — so THIS store deletes the
 * entry in the SAME transaction as the successful verification.
 *
 * Mint budget: a fixed-window per-account budget (`MintBudgetWindowMs` /
 * `MintBudgetPerWindow`, implementation constants, not contract values) — the
 * HOST-side half of the `rate_limited` error enum; the server keeps its own
 * per-user budget on the mint endpoint (contract 3.1).
 */
final case class ConsoleTicket(
  boundHost: String,
  accountSub: String,
  origin: String,
  scope: String,
  expiresAt: Long,
  remaining: Int
)

/** What the mint hands back to the ConsoleTicketResult frame (opaque token + claims). */
final case class IssuedConsoleTicket(token: String, expiresAt: Long, expiresInSec: Long)

class ConsoleTicketStore private (
  ticketsRef: Ref[IO, Map[String, ConsoleTicket]],
  ttlSeconds: Long,
  budgetRef: Ref[IO, ConsoleTicketStore.BudgetWindow]
):

  /**
   * Mint a ticket for the given account subject on this host. Sweeps expired
   * tickets first (single-transaction shape with the insert). The token is
   * 32 random bytes, base64url unpadded — 256 random bits, above the
   * contract's 128-bit floor.
   */
  def issue(accountSub: String, boundHost: String): IO[IssuedConsoleTicket] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      val effectiveTtl = math.min(ttlSeconds, ConsoleTicketStore.MaxTtlSeconds)
      val expiresAt = now + effectiveTtl * 1000L
      val token = ConsoleTicketStore.newToken()
      val ticket = ConsoleTicket(
        boundHost = boundHost,
        accountSub = accountSub,
        origin = "relay-mint",
        scope = "console",
        expiresAt = expiresAt,
        remaining = 1
      )
      ticketsRef
        .update { current =>
          val live = current.filter { case (_, t) => t.expiresAt > now }
          live.updated(token, ticket)
        }
        .as(IssuedConsoleTicket(token, expiresAt, effectiveTtl))
    }

  /**
   * Host-side mint budget (fixed window). `true` = this mint is inside the
   * budget AND the window has been consumed. Implementation constants, not
   * contract values.
   */
  def tryConsumeMintBudget(): IO[Boolean] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      budgetRef.modify { b =>
        if now - b.windowStartMs >= ConsoleTicketStore.MintBudgetWindowMs then
          (ConsoleTicketStore.BudgetWindow(now, 1), true)
        else if b.count < ConsoleTicketStore.MintBudgetPerWindow then
          (ConsoleTicketStore.BudgetWindow(b.windowStartMs, b.count + 1), true)
        else (b, false)
      }
    }

  /** Invalidate every live ticket (BindingControl revoked; returns how many dropped). */
  def revokeAll(): IO[Int] =
    ticketsRef.getAndSet(Map.empty).map(_.size)

  /**
   * Single-transition verification. Redeemed-then-destroyed (contract 2.10):
   * the success branch DELETES the entry in the same `modify` — a second
   * redemption of the same token observes `invalid-ticket`.
   *
   * `Left(reason)` reasons:
   *   - `invalid-ticket` unknown opaque value (never issued, swept, or already redeemed)
   *   - `expired`        TTL elapsed
   */
  def verifyAndConsume(token: String): IO[Either[String, ConsoleTicket]] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ticketsRef.modify { current =>
        current.get(token) match
          case None =>
            (current, Left("invalid-ticket"))
          case Some(t) if t.expiresAt <= now =>
            (current - token, Left("expired"))
          case Some(t) =>
            // Redeemed-then-destroyed: the entry is removed NOW, in the same
            // transition as the successful verification (not lazily).
            (current - token, Right(t))
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

  /** Live ticket count (diagnostics / tests). */
  def size: IO[Int] = ticketsRef.get.map(_.size)

end ConsoleTicketStore

object ConsoleTicketStore:

  /** Default TTL: the LAN fast-leg ceiling of the contract (at most 120 s hard cap). */
  val DefaultTtlSeconds: Long = 30L

  /** Contract 2.10: "TTL at most 120 s". */
  val MaxTtlSeconds: Long = 120L

  /** Host-side mint budget (implementation constants, not contract values). */
  val MintBudgetWindowMs: Long = 60_000L
  val MintBudgetPerWindow: Int = 10

  final case class BudgetWindow(windowStartMs: Long, count: Int)

  private val random = new SecureRandom()

  /** 32 random bytes, base64url, unpadded — opaque, 256 random bits (contract floor is 128). */
  private def newToken(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  def create(ttlSeconds: Long = DefaultTtlSeconds): IO[ConsoleTicketStore] =
    for
      tickets <- Ref.of[IO, Map[String, ConsoleTicket]](Map.empty)
      budget <- Ref.of[IO, BudgetWindow](BudgetWindow(0L, 0))
    yield new ConsoleTicketStore(tickets, effectiveTtl(ttlSeconds), budget)

  /** Sync construction (the tunnel object is created outside an IO constructor). */
  def unsafeCreate(ttlSeconds: Long = DefaultTtlSeconds): ConsoleTicketStore =
    new ConsoleTicketStore(
      Ref.unsafe(Map.empty),
      effectiveTtl(ttlSeconds),
      Ref.unsafe(BudgetWindow(0L, 0))
    )

  private def effectiveTtl(ttlSeconds: Long): Long =
    math.max(1L, math.min(ttlSeconds, MaxTtlSeconds))

end ConsoleTicketStore
