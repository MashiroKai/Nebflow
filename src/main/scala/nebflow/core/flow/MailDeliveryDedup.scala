package nebflow.core.flow

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}
import nebflow.shared.Defaults

/**
 * Fingerprint dedup for the queue-delivery layer (P0 投递层指纹去重).
 *
 * Root cause this plugs: the SAME queue mail can be injected twice into the
 * recipient agent. The proven replay path — MailTool's restart recovery sends
 * a MailQueued trigger for the disk head after a JVM restart, and if the item
 * had already drained just before the restart the identical content lands as a
 * second turn (double LLM calls, double tool work). Sender-side retry logic is
 * deliberately untouched — this is a delivery-layer backstop only.
 *
 * Fingerprint: SHA-256(sender | recipientSessionId | message content).
 * Within `Defaults.MailDedupWindowMs` (30 min) a repeated fingerprint is
 * consumed (removed from the queue + WS dequeued) but NOT injected; a WARN is
 * logged and the dedup counter incremented. Outside the window the identical
 * content delivers normally — legitimate re-sends are never eaten.
 *
 * Storage decision: fingerprint→timestamp map is PERSISTED to disk
 * (~/.nebflow/mail-dedup.json, AtomicJson tmp+ATOMIC_MOVE). An in-memory-only
 * map dies at the restart boundary — exactly where root cause #1 strikes — so
 * persistence is mandatory, not optional. The in-process Ref is a fast cache;
 * first access after a restart loads the disk file (expired entries pruned).
 * Persistence is best-effort: a failed write logs and does not block delivery
 * (availability over dedup — worst case degrades to the pre-fix behavior).
 */
object MailDeliveryDedup:
  private val logger = NebflowLogger.forName("nebflow.flow.maildedup")

  /** SHA-256 hex of sender + recipient + content. Same triple → same fp. */
  def fingerprint(from: String, recipientSessionId: String, message: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(s"$from|$recipientSessionId|$message".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xff}%02x").mkString

  private def storeFile: os.Path = PathUtil.dataRoot / "mail-dedup.json"

  // In-process cache; survives only within one JVM. `loaded` guarantees the
  // disk file is read at most once per process (first delivery after boot).
  private val cache = Ref.unsafe[IO, Map[String, Long]](Map.empty)
  private val loaded = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Cumulative count of suppressed duplicate deliveries (observability, task
    * requirement "WARN + dedup count"). Monotonic within the process. */
  private val suppressedCount = new java.util.concurrent.atomic.AtomicLong(0L)

  /** Total suppressed duplicates since process start. */
  def suppressedTotal: Long = suppressedCount.get()

  private def loadDiskIntoCache(now: Long): IO[Unit] =
    val file = storeFile
    IO.blocking {
      if !os.exists(file) then Map.empty[String, Long]
      else
        decode[Map[String, Long]](os.read(file)) match
          case Right(m) => m
          case Left(_)  => Map.empty // corrupt file — start fresh
    }.flatMap { disk =>
      // Prune expired entries on load; merge with anything already recorded in
      // memory this process. Right-biased union: mem entries are always from
      // this JVM (recorded after boot), disk entries from before — on a key
      // collision the in-memory (newer) timestamp wins, so a concurrent early
      // delivery is never clobbered by stale disk state.
      cache.update { mem =>
        def fresh(ts: Long): Boolean = now - ts < Defaults.MailDedupWindowMs
        disk.filter((_, ts) => fresh(ts)) ++ mem.filter((_, ts) => fresh(ts))
      }
    }.handleErrorWith(e => logger.warn(s"MailDeliveryDedup load failed: ${e.getMessage}"))

  private def persist(now: Long): IO[Unit] =
    cache.get.flatMap { m =>
      def fresh(ts: Long): Boolean = now - ts < Defaults.MailDedupWindowMs
      val cleaned = m.filter((_, ts) => fresh(ts))
      AtomicJson.write(storeFile, cleaned.asJson.noSpaces)
    }.handleErrorWith(e => logger.warn(s"MailDeliveryDedup persist failed: ${e.getMessage}"))

  /**
   * Record a delivery attempt for `fp` at time `now`.
   * Returns true = deliver (recorded); false = duplicate inside the window
   * (caller must consume the queue item without injecting it).
   *
   * `now` is parameterized so specs can exercise window expiry without sleeps.
   *
   * V13 (2026-09-03): the consult is now scoped to the REPLAY WINDOW. The
   * proven duplicate source is MailTool's restart recovery re-firing the disk
   * head — that re-fire can only inject within seconds of the recipient
   * session's activation. Deliveries arriving OUTSIDE the replay window
   * (`withinReplayWindow = false`, computed by the caller from the recipient's
   * AgentRecord.startedAt) skip both the consult and the recording: a
   * legitimate same-content re-send delivers unconditionally. Callers that
   * cannot date the delivery (default `true`) keep the pre-V13 always-consult
   * behavior — conservative fallback, existing component specs unchanged.
   */
  def tryDeliver(
    recipientSessionId: String,
    fp: String,
    now: Long = System.currentTimeMillis(),
    withinReplayWindow: Boolean = true
  ): IO[Boolean] =
    if !withinReplayWindow then
      // Outside the replay window: not a restart-replay shape — deliver
      // without consulting (and without recording) the fingerprint ledger.
      logger.debug(s"[mail-dedup] outside replay window — deliver unconditionally (recipient=${recipientSessionId.take(8)})").as(true)
    else
      for
        _ <- if loaded.compareAndSet(false, true) then loadDiskIntoCache(now) else IO.unit
        allowed <- cache.modify { m =>
          m.get(fp) match
            case Some(ts) if now - ts < Defaults.MailDedupWindowMs => (m, false)
            case _                                                 => (m + (fp -> now), true)
        }
        _ <-
          if allowed then persist(now)
          else
            val n = suppressedCount.incrementAndGet()
            logger.warn(
              s"[mail-dedup] duplicate queue delivery suppressed (recipient=${recipientSessionId.take(8)}, fp=${fp.take(12)}…, window=${Defaults.MailDedupWindowMs / 60000}min, suppressedTotal=$n)"
            ) *> persist(now)
      yield allowed

  /** TEST-ONLY hook: drop the in-memory cache + loaded flag (simulates a
   * restart while the disk file survives). Production code never calls this. */
  def reset(): Unit =
    cache.set(Map.empty).void.unsafeRunSync()(using cats.effect.unsafe.implicits.global)
    loaded.set(false)

end MailDeliveryDedup
