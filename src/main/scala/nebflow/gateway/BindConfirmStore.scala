package nebflow.gateway

import cats.effect.IO
import cats.effect.Ref

import java.security.SecureRandom

/**
 * Host-side pending state for the D6 binding-transfer confirmation code
 * (phase 3c plan §8 component ①).
 *
 * `nebflow bind confirm` mints a 6-digit confirmation code on the OLD host
 * and reports it to the NebLink server over the relay control plane (plan
 * R-8 recommended flow); the server hangs it as the pending confirmation and
 * the portal compares against it when the operator re-binds the seat. The
 * SERVER's pending copy is the authority — this store is the host-side
 * mirror of "what was last reported", kept for diagnostics and repeat-run
 * readouts. It is deliberately IN-MEMORY ONLY: the pending state dies with
 * the gateway process, and a host that cannot keep its tunnel up has no
 * working confirm-code path anyway (the plan routes that case to the 72h
 * grace branch, which the server-side guard owns).
 *
 * Shape discipline mirrors [[nebflow.neblink.ConsoleTicketStore]]: a `Ref`
 * behind a private constructor, every transition inside a single `modify`,
 * an opportunistic expiry sweep on the read path, no background fiber. One
 * pending slot (not a map): a fresh arm REPLACES the previous code — the
 * last code the operator was shown is the only one that can match, host and
 * server agree on "newest report wins" by construction.
 *
 * Zero-echo: the code is printed to the operator's terminal exactly once by
 * the CLI; it is never logged and never re-echoed by any face here.
 */
final case class PendingConfirmation(code: String, expiresAtMs: Long, ttlSec: Long)

class BindConfirmStore private (clock: IO[Long]):

  private val slot: Ref[IO, Option[PendingConfirmation]] = Ref.unsafe(None)

  /**
   * Record a freshly minted code as THE pending confirmation. Replaces any
   * previous entry (single-slot semantics; see class doc).
   */
  def arm(code: String, ttlSec: Long): IO[PendingConfirmation] =
    clock.flatMap { now =>
      val ttl = ttlSec.max(1L)
      val pending = PendingConfirmation(code, now + ttl * 1000L, ttl)
      slot.set(Some(pending)).as(pending)
    }

  /**
   * The live pending confirmation, if one is unexpired (`None` = expired or
   * never armed; an expired entry is swept by the same read).
   */
  def peek(): IO[Option[PendingConfirmation]] =
    clock.flatMap { now =>
      slot.modify { current =>
        val next: (Option[PendingConfirmation], Option[PendingConfirmation]) = current match
          case Some(p) if p.expiresAtMs > now => (Some(p), Some(p))
          case _                              => (None, None) // expired (or absent): sweep + report empty
        next
      }
    }

  /** Drop the pending entry (tests / future revoke face). */
  def clear(): IO[Unit] = slot.set(None)

end BindConfirmStore

object BindConfirmStore:

  /**
   * Pending-confirmation TTL: the plan §8 component ① recommendation
   * (15 minutes — human "walk to the portal and type it" cadence). Carried
   * to the server in the `bind_confirm` report frame so both ends can agree
   * on the window; the server-side guard may clamp it to its own configured
   * ceiling.
   */
  val DefaultTtlSec: Long = 900L

  private val random = new SecureRandom()

  /**
   * One uniformly random 6-digit code over 000000-999999, as a STRING.
   * Leading zeros are real codes — the value must never travel or render as
   * a number (the uplink frame and the CLI copy both keep the string).
   */
  def newCode(): String = f"${random.nextInt(1_000_000)}%06d"

  def unsafeCreate(nowMs: IO[Long] = IO.realTime.map(_.toMillis)): BindConfirmStore =
    new BindConfirmStore(nowMs)

end BindConfirmStore
