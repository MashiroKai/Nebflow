package nebflow.llm

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.Defaults

import scala.collection.immutable.Queue
import scala.concurrent.duration.*

/**
 * Per-provider concurrency gate (P0 API 并发管理, 2026-08-18).
 *
 * Bounds the number of in-flight LLM requests per provider (concurrency) and
 * optionally the requests per minute (RPM), with FIFO queueing: when the gate
 * is saturated a request WAITS for a permit instead of firing straight into
 * the provider's rate limiter (which previously caused 7-way parallel retry
 * storms — issue #19 family). Pure cats-effect; no actor involvement.
 *
 * Semantics (design §4.1-4.2, gate-wedge 2026-08-20):
 *  - acquire returns a [[ConcurrencyPermit]] once a slot frees up, or fails
 *    with [[QueueTimeout]] after `queueTimeout` — Transient, falls back to the
 *    next provider (never a hard failure by itself). The timeout bounds the
 *    WHOLE gate stay (queue wait + RPM window wait): #296 wanted queueing
 *    without immediate failure, and infinite waiting is the opposite failure
 *    mode — an 8.5h permits=0 wedge (gate-wedge report E2) where queued
 *    requests died silently with zero SSE traces and the stream watchdog
 *    (armed only AFTER acquire) never fired.
 *  - maxConcurrency == 0 means unlimited concurrency (RPM still applies).
 *  - FIFO fairness: permits are handed out in arrival order.
 *  - release returns the permit and dispatches the next waiter.
 *
 * Cancellation safety (gate-wedge P0-2, 2026-08-20): a cancelled acquire
 * (Stop/abort/stuck-cancel while queued, granted-but-not-yet-running, or
 * inside the RPM wait) restores its accounting exactly once — cleanup is
 * idempotent, the enqueue happens inside an uncancelable region (so "not in
 * waiting" always means "dispatched"), and the restore clamps at
 * maxConcurrency so no double-restore can inflate capacity. The waiter
 * tracer fiber is cancelled on every exit path (a leaked tracer printed
 * "granted(rpm-wait)" every 5s for 8 hours in the incident — E8).
 *
 * Clock robustness (gate-wedge P0-2): the RPM window tolerates wall-clock
 * steps backwards (future-stamped entries are dropped, per-iteration sleep is
 * capped at one window) so a permit-holder can never park in waitForWindow
 * for more than ~one window per iteration.
 *
 * Not thread-hostile: state is a single atomic Ref; dispatch decisions happen
 * inside `modify` so concurrent acquire/release cannot double-issue permits.
 */
final class ConcurrencyGate private[llm] (
  providerId: String,
  maxConcurrency: Int,
  rpm: Option[Int],
  queueTimeout: FiniteDuration,
  rpmWindow: FiniteDuration = Defaults.LlmRpmWindowSec.seconds,
  private[llm] val clockMs: IO[Long] = IO.realTime.map(_.toMillis),
  private[llm] val tracerPeriod: FiniteDuration = 5.seconds
):

  private case class St(
    permits: Int,
    waiting: Queue[Deferred[IO, Unit]],
    window: List[Long]
  )

  private val state: Ref[IO, St] =
    Ref.unsafe(St(permits = maxConcurrency, waiting = Queue.empty, window = Nil))

  /** Live tracer-fiber count (gate-wedge observability: leak detector). */
  private[llm] val tracersRef: Ref[IO, Int] = Ref.unsafe(0)

  private val gateLogger = NebflowLogger.forName("nebflow.llm.gate")

  /** Try to hand the next permit to the head of the queue (if any).
    *
    * Uncancelable: the dequeue and the deferred completion must not be split
    * by a cancellation — a fiber cancelled between the two would strand the
    * dequeued waiter forever (its own cleanup never runs because IT was never
    * cancelled). */
  private def dispatch: IO[Unit] =
    IO.uncancelable(_ =>
      state
        .modify { s =>
          if s.permits > 0 && s.waiting.nonEmpty then
            val (head, rest) = s.waiting.dequeue
            (s.copy(permits = s.permits - 1, waiting = rest), Some(head))
          else (s, None)
        }
        .flatMap {
          case Some(d) => d.complete(()).void.handleErrorWith(_ => IO.unit)
          case None    => IO.unit
        }
    )

  /**
   * Sliding-window RPM check (window = Defaults.LlmRpmWindowSec). Called with a
   * permit already held; sleeps exactly until the oldest entry slides out of the
   * window (no busy polling). The timestamp is recorded atomically with the
   * check so concurrent callers can't exceed the limit.
   *
   * gate-wedge P0-2 clock hardening:
   *  - entries stamped in the FUTURE (wall clock stepped backwards — NTP
   *    correction after a host sleep) are dropped instead of pinning the window
   *    for (future − now): the incident showed multi-hour "rpm-wait" parking;
   *  - a single iteration never sleeps longer than one full window — the loop
   *    re-evaluates at least once per window, so no clock pathology can hold
   *    a permit hostage for hours.
   */
  private def waitForWindow: IO[Unit] =
    rpm match
      case None => IO.unit
      case Some(limit) =>
        def loop: IO[Unit] =
          clockMs.flatMap { now =>
            val windowMs = rpmWindow.toMillis
            state
              .modify { s =>
                val window = s.window.filter(t => t <= now && now - t < windowMs)
                if window.length < limit then (s.copy(window = now :: window), None)
                else
                  val oldest = window.minOption.getOrElse(now)
                  val sleepMs = math.min(oldest + windowMs - now, windowMs)
                  (s.copy(window = window), Some(sleepMs))
              }
              .flatMap {
                case None => IO.unit
                case Some(sleepMs) => IO.sleep(sleepMs.max(1L).millis) >> loop
              }
          }
        loop

  /** Structured state snapshot for WARN lines (gate-wedge P2 observability:
    * one line must be enough to tell leak vs rpm-wait vs wedge apart). */
  private def snapshotDetail(s: St, now: Long): String =
    val oldestAge = s.window.filter(_ <= now).minOption.map(t => s"${(now - t) / 1000}s").getOrElse("-")
    s"permits=${s.permits} waiters=${s.waiting.size} window=${s.window.length} oldestAge=$oldestAge"

  /** One-shot gate snapshot WARN (rate-limited by construction: only on
    * queue-timeout / clamp paths). */
  private def snapshotWarn: IO[Unit] =
    clockMs.flatMap { now =>
      state.get.map(s => snapshotDetail(s, now))
    }.flatMap(detail => gateLogger.warn(s"gate[$providerId] state: $detail (maxConcurrency=$maxConcurrency queueTimeoutMs=${queueTimeout.toMillis})"))
      .handleErrorWith(_ => IO.unit)

  /**
   * Non-blocking queue probe: true if an acquire right now would not queue —
   * a permit is free AND the RPM window has capacity. Used by the persistence
   * integration (interface.scala) to decide whether to write the request to
   * LlmQueueStore: only QUEUED requests must survive a restart; immediate
   * grants need no persistence. maxConcurrency=0 never queues (RPM still
   * checked). Best-effort probe: the authoritative enforcement with atomic
   * timestamp recording stays in waitForWindow.
   */
  def tryAcquire: IO[Boolean] =
    clockMs.flatMap { now =>
      val windowMs = rpmWindow.toMillis
      state.get.map { s =>
        val slotFree = maxConcurrency == 0 || s.permits > 0
        val rpmOk = rpm.forall(limit => s.window.count(t => t <= now && now - t < windowMs) < limit)
        slotFree && rpmOk
      }
    }

  /**
   * Acquire a permit, queueing (FIFO) until one is free.
   *
   * Queue timeout (gate-wedge P0-1, 2026-08-20): the whole gate stay — queue
   * wait AND RPM window wait — is bounded by `queueTimeout` (default 120s,
   * per-provider `queueTimeoutMs`). #296's "排队不 fallback" is preserved in
   * spirit (queueing is normal and does not fail immediately), but infinite
   * waiting is reverted: a wedged gate (permits leaked to 0) must surface as
   * a QueueTimeout WARN + Transient fallback to the next provider within 120s,
   * not as an 8.5h silent death with zero SSE traces (report §0/§4-P0-1).
   *
   * Waiting >5s is traced every `tracerPeriod` (permits/waiters/window/oldest
   * age/thisWaiter) so the three pathologies are distinguishable from logs:
   *   - permits=0 & waiters>0 sustained → permit leak (release lost)
   *   - thisWaiter=granted(rpm-wait) + huge oldestAge → RPM window pinned
   *   - repeats of QueueTimeout WARNs on one provider → gate wedge
   * Fast path (≤5s) logs nothing.
   */
  def acquire: IO[ConcurrencyPermit] =
    if maxConcurrency == 0 then
      IO.race(waitForWindow, IO.sleep(queueTimeout))
        .flatMap {
          case Left(())  => IO.unit
          case Right(_) => snapshotWarn *> IO.raiseError(new QueueTimeout(providerId, queueTimeout.toMillis))
        }
        .as(new ConcurrencyPermit(this, limited = false))
    else
      Deferred[IO, Unit].flatMap { d =>
        IO.ref(false).flatMap { cleanedRef =>
          // gate-wedge P0-2: exactly-once cleanup. Error path (QueueTimeout),
          // cancellation path (Stop/abort while queued / granted / rpm-waiting)
          // and CancellationException all funnel here; a second run is a no-op.
          val cleanup: IO[Unit] =
            cleanedRef.modify { case done => (true, !done) }.flatMap {
              case false => IO.unit // already cleaned
              case true =>
                state
                  .modify { s =>
                    if s.waiting.exists(_ eq d) then (s.copy(waiting = s.waiting.filterNot(_ eq d)), Option.empty[Boolean])
                    else
                      // Not in waiting ⟹ dispatch dequeued us (the enqueue
                      // commits inside the uncancelable region below, so this
                      // inference is exact) ⟹ we own/owned a slot: restore it.
                      // Clamp at maxConcurrency: a double-restore must never
                      // inflate capacity (masked by the clamp + WARN).
                      val next = math.min(s.permits + 1, maxConcurrency)
                      (s.copy(permits = next), Some(next == s.permits))
                  }
                  .flatMap {
                    case Some(true) =>
                      gateLogger.warn(
                        s"gate[$providerId] permit restore clamped at maxConcurrency=$maxConcurrency — double-restore suppressed"
                      )
                    case _ => IO.unit
                  } *> dispatch
            }
          def traceLoop: IO[Unit] =
            IO.sleep(tracerPeriod) *> clockMs.flatMap { now =>
              state.get.flatMap { s =>
                val mine = if s.waiting.exists(_ eq d) then "queued" else "granted(rpm-wait)"
                gateLogger.warn(
                  s"gate[$providerId] acquire waiting: ${snapshotDetail(s, now)} thisWaiter=$mine " +
                    s"(queueTimeout=${queueTimeout.toSeconds}s)"
                )
              }
            } >> traceLoop
          val body: IO[Unit] =
            // gate-wedge P0-2: enqueue inside an uncancelable region — once the
            // acquire has begun waiting, the enqueue has necessarily committed,
            // so cleanup never restores a permit for a never-enqueued waiter.
            IO.uncancelable(_ => state.update(s => s.copy(waiting = s.waiting.enqueue(d)))) *>
              dispatch *>
              traceLoop.start.flatMap { tracer =>
                tracersRef.update(_ + 1) *>
                  IO.race(
                    d.get *> waitForWindow,
                    IO.sleep(queueTimeout)
                  )
                    // gate-wedge P0-2 (E8): the tracer dies on EVERY exit —
                    // grant, queue-timeout, error and cancellation alike. The
                    // old `tracer.cancel` after `d.get` was unreachable on
                    // cancellation, leaking 5s-period ghost logs for 8 hours.
                    .guarantee(tracersRef.update(_ - 1) *> tracer.cancel.void.handleErrorWith(_ => IO.unit))
                    .flatMap {
                      case Left(())  => IO.unit
                      case Right(_) =>
                        snapshotWarn *> IO.raiseError(new QueueTimeout(providerId, queueTimeout.toMillis))
                    }
              }
          body
            .onError(_ => cleanup)
            .onCancel(cleanup)
            .as(new ConcurrencyPermit(this, limited = true))
        }
      }

  /** Release a held permit, dispatching the next waiter. Uncancelable so the
    * increment and the dispatch cannot be split by a cancellation (a waiter
    * would sleep until its queue timeout for no reason). */
  private[llm] def release(limited: Boolean): IO[Unit] =
    if !limited then IO.unit
    else IO.uncancelable(_ => state.update(s => s.copy(permits = s.permits + 1)) *> dispatch)

object ConcurrencyGate:

  /** Resolve effective gate parameters from provider config (None = default). */
  def fromProvider(providerId: String, p: ProviderConfig): ConcurrencyGate =
    val concurrency = p.maxConcurrency.getOrElse(Defaults.LlmMaxConcurrencyDefault)
    val timeout = p.queueTimeoutMs
      .map(ms => ms.toLong.millis)
      .getOrElse(Defaults.LlmQueueTimeoutMs.millis)
    new ConcurrencyGate(providerId, concurrency, p.rpm, timeout)

/** Raised when a gated request waited too long for a permit (or an RPM window
  * slot). Transient — the provider is BUSY (not down): the fallback chain
  * should try the next provider without retrying this one or marking it down.
  * Also the wedge detector: repeated QueueTimeouts on one provider mean the
  * gate itself lost permits (gate-wedge report E2). */
class QueueTimeout(val providerId: String, waitedMs: Long)
    extends RuntimeException(s"provider '$providerId' concurrency queue: waited ${waitedMs}ms without a permit")

/** A held concurrency slot. Release it (via `release`) when the LLM call done. */
final class ConcurrencyPermit private[llm] (gate: ConcurrencyGate, limited: Boolean):
  def release: IO[Unit] = gate.release(limited)
