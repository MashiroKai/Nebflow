package nebflow.llm

import cats.effect.{Deferred, Fiber, IO, Ref}
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.shared.Defaults

import java.util.concurrent.CancellationException
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
   * permit already held; sleeps exactly until the oldest entry slides out of the
   * window (no busy polling). The timestamp is recorded atomically with the
   * check so concurrent callers can't exceed the limit.
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
                if window.length < limit then (s.copy(window = now :: window), None)
                // Window full: sleep until the oldest entry expires, not a fixed
                // 250ms poll — one wakeup per RPM wait instead of ~16.
                else (s.copy(window = window), Some(window.minOption.getOrElse(now) + windowMs - now))
              }
              .flatMap {
                case None => IO.unit
                case Some(sleepMs) => IO.sleep(sleepMs.max(1L).millis) >> loop
              }
          }
        loop

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
    IO.realTime.map(_.toMillis).flatMap { now =>
      val windowMs = rpmWindow.toMillis
      state.get.map { s =>
        val slotFree = maxConcurrency == 0 || s.permits > 0
        val rpmOk = rpm.forall(limit => s.window.count(t => now - t < windowMs) < limit)
        slotFree && rpmOk
      }
    }

  /**
   * Acquire a permit, queueing (FIFO) until one is free. No queue timeout —
   * the acquire waits indefinitely for a permit (user #296 追加, 2026-08-19):
   * 排队等待正常，不 fallback。Provider 真故障时 LLM 请求本身的超时
   * (首 token 90s / 空闲 60s) 会触发 fallback，排队层不需要超时兜底。
   * LLM timeout 从 stream 开始计时（acquire 之后），不受排队影响。
   *
   * #22 (2026-08-19): 无限等待必须留痕——排队 >5s 起每 5s 输出一次 gate
   * 状态快照（permits/waiters/本请求是否仍在队列）。三次今日 turn 静默死亡
   * 现场的法证链都终止于「usage record 已落盘 → 下一个请求从未发出」，gate
   * 楔死是头号嫌疑但无日志可证。留痕后三种病理可直接区分：
   *   - permits=0 & waiters>0 持续 → permit 泄漏（release 丢失）
   *   - thisWaiter=granted(rpm-wait) → RPM 窗口等待卡死（时钟回拨类）
   *   - 完全无等待日志 → 卡点在 gate 之外（inline 上下文构建段）
   * 正常快路径（≤5s 获得许可）零日志零开销——tracer 只在等待超 5s 后启动。
   */
  def acquire: IO[ConcurrencyPermit] =
    if maxConcurrency == 0 then waitForWindow.as(new ConcurrencyPermit(this, limited = false))
    else
      Deferred[IO, Unit].flatMap { d =>
        // Remove a dead waiter. Still queued → drop it from the FIFO; already
        // dequeued by dispatch (we hold the slot but never got out) → restore
        // the permit. Runs on error AND fiber cancellation (Stop/abort while
        // queued or in the RPM wait): without the cancel path a cancelled
        // acquire leaves a ghost Deferred in `waiting`, and dispatch keeps
        // handing permits to listeners that never release them — permits
        // exhaust one per cancelled request. The restored permit dispatches
        // the next waiter immediately, not on the next release.
        val cleanup: IO[Unit] =
          state.update { s =>
            if s.waiting.exists(_ eq d) then s.copy(waiting = s.waiting.filterNot(_ eq d))
            else s.copy(permits = s.permits + 1)
          } *> dispatch
        val gateLogger = NebflowLogger.forName("nebflow.llm.gate")
        def traceLoop: IO[Unit] =
          IO.sleep(5.seconds) *> state.get.flatMap { s =>
            val mine = if s.waiting.exists(_ eq d) then "queued" else "granted(rpm-wait)"
            gateLogger.warn(
              s"gate[$providerId] acquire waiting: permits=${s.permits} waiters=${s.waiting.size} " +
                s"thisWaiter=$mine (no queue timeout by design, #296)"
            )
          } >> traceLoop
        val body =
          state.update(s => s.copy(waiting = s.waiting.enqueue(d))) *>
            dispatch *>
            (for
              // 独立 tracer fiber：d.get 完成即取消，不影响许可等待本身
              // （绝不能 race/timeout d.get——那会取消等待并触发 cleanup 语义）。
              tracer <- traceLoop.start
              _ <- d.get
              _ <- tracer.cancel
            yield ()) *>
            waitForWindow
        body
          .onError {
            case _: CancellationException => cleanup
          }
          .onCancel(cleanup)
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
