package nebflow.llm

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
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
 * Semantics (design §4.1-4.2):
 *  - acquire returns a [[ConcurrencyPermit]] once a slot frees up, or fails
 *    with [[QueueTimeout]] after `queueTimeout` — Transient, falls back to the
 *    next provider (never a hard failure by itself).
 *  - maxConcurrency == 0 means unlimited concurrency (RPM still applies).
 *  - FIFO fairness: permits are handed out in arrival order.
 *  - release returns the permit and dispatches the next waiter.
 *
 * Not thread-hostile: state is a single atomic Ref; dispatch decisions happen
 * inside `modify` so concurrent acquire/release cannot double-issue permits.
 */
final class ConcurrencyGate private[llm] (
  providerId: String,
  maxConcurrency: Int,
  rpm: Option[Int],
  queueTimeout: FiniteDuration,
  rpmWindow: FiniteDuration = Defaults.LlmRpmWindowSec.seconds
):

  private case class St(
    permits: Int,
    waiting: Queue[Deferred[IO, Unit]],
    window: List[Long]
  )

  private val state: Ref[IO, St] =
    Ref.unsafe(St(permits = maxConcurrency, waiting = Queue.empty, window = Nil))

  /** Try to hand the next permit to the head of the queue (if any). */
  private def dispatch: IO[Unit] =
    state
      .modify { s =>
        if s.permits > 0 && s.waiting.nonEmpty then
          val (head, rest) = s.waiting.dequeue
          (s.copy(permits = s.permits - 1, waiting = rest), Some(head))
        else (s, None)
      }
      .flatMap {
        case Some(d) => d.complete(()).void
        case None    => IO.unit
      }

  /**
   * Sliding-window RPM check (window = Defaults.LlmRpmWindowSec). Called with a
   * permit already held; busy-waits in short sleeps until a slot opens in the
   * window. The timestamp is recorded atomically with the check so concurrent
   * callers can't exceed the limit.
   */
  private def waitForWindow: IO[Unit] =
    rpm match
      case None => IO.unit
      case Some(limit) =>
        def loop: IO[Unit] =
          IO.realTime.map(_.toMillis).flatMap { now =>
            val windowMs = rpmWindow.toMillis
            state
              .modify { s =>
                val window = s.window.filter(t => now - t < windowMs)
                if window.length < limit then (s.copy(window = now :: window), true)
                else (s.copy(window = window), false)
              }
              .flatMap { ok =>
                if ok then IO.unit else IO.sleep(250.millis) *> loop
              }
          }
        loop

  /**
   * Non-blocking queue probe: true if a permit is available immediately (an
   * acquire right now would not queue). Used by the persistence integration
   * (interface.scala) to decide whether to write the request to LlmQueueStore:
   * only QUEUED requests must survive a restart; immediate grants need no
   * persistence. maxConcurrency=0 never queues.
   */
  def tryAcquire: IO[Boolean] =
    if maxConcurrency == 0 then IO.pure(true)
    else state.get.map(_.permits > 0)

  /** Acquire a permit, queueing (FIFO) until one is free or queueTimeout elapses. */
  def acquire: IO[ConcurrencyPermit] =
    if maxConcurrency == 0 then waitForWindow.as(new ConcurrencyPermit(this, limited = false))
    else
      Deferred[IO, Unit].flatMap { d =>
        val body =
          state.update(s => s.copy(waiting = s.waiting.enqueue(d))) *>
            dispatch *>
            d.get
              .timeoutTo(
                queueTimeout,
                IO.raiseError(new QueueTimeout(providerId, queueTimeout.toMillis))
              ) *>
            waitForWindow
        body
          .onError {
            case _: QueueTimeout =>
              // We timed out while still queued — remove ourselves so a later
              // release doesn't hand a permit to a ghost waiter. If dispatch
              // already dequeued us (permits decremented) but hadn't completed
              // the Deferred yet, we are NOT in the queue anymore: restore the
              // slot, since we'll never release it. (If d.get already won the
              // race, the timeout branch is cancelled and onError doesn't run.)
              state.update { s =>
                if s.waiting.exists(_ eq d) then s.copy(waiting = s.waiting.filterNot(_ eq d))
                else s.copy(permits = s.permits + 1)
              }
          }
          .as(new ConcurrencyPermit(this, limited = true))
      }

  /** Release a held permit, dispatching the next waiter. */
  private[llm] def release(limited: Boolean): IO[Unit] =
    if !limited then IO.unit
    else
      state.update(s => s.copy(permits = s.permits + 1)) *> dispatch

object ConcurrencyGate:

  /** Resolve effective gate parameters from provider config (None = default). */
  def fromProvider(providerId: String, p: ProviderConfig): ConcurrencyGate =
    val concurrency = p.maxConcurrency.getOrElse(Defaults.LlmMaxConcurrencyDefault)
    val timeout = p.queueTimeoutMs
      .map(ms => ms.toLong.millis)
      .getOrElse(Defaults.LlmQueueTimeoutMs.millis)
    new ConcurrencyGate(providerId, concurrency, p.rpm, timeout)

/** Raised when a gated request waited too long for a permit. Transient —
  * the provider is BUSY (not down): the fallback chain should try the next
  * provider without retrying this one or marking it down. */
class QueueTimeout(val providerId: String, waitedMs: Long)
    extends RuntimeException(s"provider '$providerId' concurrency queue: waited ${waitedMs}ms without a permit")

/** A held concurrency slot. Release it (via `release`) when the LLM call done. */
final class ConcurrencyPermit private[llm] (gate: ConcurrencyGate, limited: Boolean):
  def release: IO[Unit] = gate.release(limited)
