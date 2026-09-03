package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.std.Queue
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*

import java.nio.file.*
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

/** Structured tool-execution JSONL log（审计 20260903 §5 方案 B）.
  *
  * Output directory: ~/.nebflow/logs/tools/
  *   - {date}.jsonl — one line per tool execution (success AND failure)
  *
  * Fixed keys per line (always present; Option values null when absent):
  *   ts / tool / agent / sessionId / kind / isError / elapsedMs /
  *   errorText / inputSummary / resultChars / requestId
  *
  * Key properties vs the legacy nebflow.log text line (which stays untouched):
  *   - errorText is the FULL result content (no 100-char truncation)
  *   - requestId correlates with the router JSONL LLM request entries
  *   - one JSON object per line → jq-able failure-rate views (方案 C 前置)
  *
  * Write path is ASYNC and never blocks tool execution: entries go through a
  * bounded queue to a background fiber; on overflow the entry is DROPPED with
  * a WARN (telemetry loss beats blocking the main path). flushSync() drains
  * synchronously (tests + JVM shutdown hook).
  *
  * Retention: aligned with the router logs — same shared constant
  * ([[LlmLogWriter.retentionDays]], currently 3), same once-per-day prune
  * cadence, reusing the tested date-prefix deletion scan.
  */
object ToolsLogWriter:

  private val logger = NebflowLogger.forName("nebflow.tools.logger")

  /** Runtime toggle (mirrors LlmLogWriter). When false, log() is a no-op. */
  private val enabled = AtomicBoolean(true)
  def setEnabled(v: Boolean): Unit = enabled.set(v)
  def isEnabled: Boolean = enabled.get()

  /** Bounded queue capacity — overflow drops + WARN, never blocks. */
  private val Capacity = 4096

  /** Retention days — SHARED constant with the router logs (方案 B 验收:
    * 默认值 = router 现行保留天数). */
  private[core] val retentionDays: Int = LlmLogWriter.retentionDays

  // ── Test hooks (spec harnesses redirect dir / clock) ─────────────────

  private val dirOverride = AtomicReference[Option[Path]](None)
  private val clockOverride = AtomicReference[Option[() => Instant]](None)

  /** Redirect the output directory (spec harness / isolated instance). */
  private[nebflow] def setDirForTest(p: Path): Unit = dirOverride.set(Some(p))
  private[nebflow] def resetDirForTest(): Unit = dirOverride.set(None)

  /** Inject the clock (ts + daily file naming + prune cutoff). */
  private[nebflow] def setClockForTest(f: () => Instant): Unit = clockOverride.set(Some(f))
  private[nebflow] def resetClockForTest(): Unit = clockOverride.set(None)

  /** Artificial write delay (ms) — async non-blocking proof in specs. */
  private val writeDelayMsForTest = AtomicLong(0)
  private[nebflow] def setWriteDelayMsForTest(ms: Long): Unit = writeDelayMsForTest.set(ms)

  private[nebflow] def capacityForTest: Int = Capacity

  private def clock(): Instant = clockOverride.get().fold(Instant.now())(_())

  private def logDir: Path =
    dirOverride.get().getOrElse(Paths.get(System.getProperty("user.home"), ".nebflow", "logs", "tools"))

  /** Fixed-millisecond ISO8601 UTC — lexicographically sortable. */
  private val tsFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  // ── Public API ───────────────────────────────────────────────────────

  /** Record one tool execution. Called from AgentCore's tool choke points.
    * NEVER throws and NEVER blocks the caller beyond a queue tryOffer. */
  def log(
    tool: String,
    agent: Option[String],
    sessionId: Option[String],
    kind: Option[String],
    isError: Boolean,
    elapsedMs: Long,
    errorText: String,
    inputSummary: String,
    resultChars: Int,
    requestId: Option[String]
  ): IO[Unit] =
    if !enabled.get() then IO.unit
    else
      // ts captured at enqueue (execution time), file date at write time —
      // both from the (injectable) clock, mirroring LlmLogWriter semantics.
      val ts = tsFormat.format(clock())
      val entry = Json.obj(
        "ts" -> ts.asJson,
        "tool" -> tool.asJson,
        "agent" -> agent.asJson,
        "sessionId" -> sessionId.asJson,
        "kind" -> kind.asJson,
        "isError" -> isError.asJson,
        "elapsedMs" -> elapsedMs.asJson,
        "errorText" -> errorText.asJson,
        "inputSummary" -> inputSummary.asJson,
        "resultChars" -> resultChars.asJson,
        "requestId" -> requestId.asJson
      )
      ensureWorker *> queue.tryOffer(entry).flatMap {
        case true => IO.delay(pendingWrites.incrementAndGet()).void
        case false =>
          // Overflow: drop + WARN — telemetry loss never blocks the tool path.
          IO.delay(logger.warnSync(
            s"ToolsLogWriter: queue full ($Capacity) — dropping tool log line for $tool"
          ))
      }

  /** Synchronous drain — best-effort flush on shutdown; specs use it to make
    * the async write observable before asserting file contents. Pauses the
    * worker, drains, then waits for any item an in-flight take already pulled —
    * deterministic: every line offered BEFORE flushSync started is on disk
    * when it returns. */
  private[nebflow] def flushSync(): Unit =
    flushing.set(true)
    try
      var more = true
      while more do
        queue.tryTake.unsafeRunSync()(using global) match
          case Some(json) => appendJsonl(json)
          case None => more = false
      // Wait out any in-flight worker write (same lock appendJsonl holds).
      writeLock.synchronized(())
      var waits = 0
      while pendingWrites.get() > 0 && waits < 2000 do
        Thread.sleep(5)
        waits += 1
    finally flushing.set(false)

  // ── Async pipeline: bounded queue + background fiber ─────────────────

  private val queue: Queue[IO, Json] = Queue.bounded[IO, Json](Capacity).unsafeRunSync()(using global)

  private val workerStarted = AtomicBoolean(false)

  /** Flush barrier: while true the worker does not take from the queue. */
  private val flushing = AtomicBoolean(false)

  /** Offered-but-not-yet-written lines — lets flushSync wait out items an
    * in-flight worker fiber has already taken from the queue. */
  private val pendingWrites = AtomicLong(0)

  private def workerLoop: IO[Unit] =
    (IO.blocking {
      while flushing.get() do Thread.sleep(5)
    } *> queue.take.flatMap(json => IO.blocking(appendJsonl(json)))).foreverM

  /** Start the writer fiber exactly once (fiber runs on the global runtime;
    * take/append are per-line, so the loop itself never fails). */
  private def ensureWorker: IO[Unit] =
    IO(workerStarted.compareAndSet(false, true)).ifM(workerLoop.start.void, IO.unit)

  // JVM shutdown: best-effort flush of whatever is still queued.
  locally {
    Runtime.getRuntime.addShutdownHook(new Thread(
      () => { try flushSync() catch case _: Throwable => () },
      "tools-log-flush"
    ))
  }

  // ── JSONL append + daily prune (mirrors LlmLogWriter) ────────────────

  private val writeLock = new Object

  private def appendJsonl(json: Json): Unit = writeLock.synchronized {
    try
      if writeDelayMsForTest.get() > 0 then Thread.sleep(writeDelayMsForTest.get())
      val date = clock().toString.take(10) // yyyy-MM-dd
      val path = logDir.resolve(s"$date.jsonl")
      Files.createDirectories(logDir)
      Files.write(
        path,
        (json.noSpaces + "\n").getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      maybePrune()
    catch case e: Exception => logger.warnSync(s"ToolsLogWriter: ${e.getMessage}")
    finally pendingWrites.decrementAndGet()
  }

  // Once-per-day prune (double-checked, mirrors LlmLogWriter.maybePrune).
  private val lastPruneDate = AtomicReference("")

  private def maybePrune(): Unit =
    val today = clock().toString.take(10)
    if lastPruneDate.get() != today then
      writeLock.synchronized {
        if lastPruneDate.get() != today then
          pruneOldLogs()
          lastPruneDate.set(today)
      }

  /** Delete tools JSONL files older than the shared retention window. Reuses the
    * tested date-prefix deletion scan from LlmLogWriter (tools files carry no
    * object refs — the scan's ref collection simply never triggers here). */
  private def pruneOldLogs(): Unit =
    try
      val cutoff = clock().minusSeconds(retentionDays * 86400L).toString.take(10)
      val usedHashes = scala.collection.mutable.Set.empty[String]
      LlmLogWriter.scanFullLogsForRefs(logDir, cutoff, usedHashes, 64L * 1024 * 1024)
      logger.infoSync(s"Tools log retention: pruned files older than $cutoff")
    catch case e: Exception => logger.warnSync(s"Tools log retention error: ${e.getMessage}")

end ToolsLogWriter
