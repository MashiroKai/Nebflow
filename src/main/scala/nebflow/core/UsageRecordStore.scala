package nebflow.core

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import nebflow.core.NebflowLogger

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.locks.ReentrantLock
import scala.util.control.NonFatal

/**
 * Structured LLM usage record — the input row of the token-consumption
 * dashboard. Captured at every successful LLM call completion (AgentActor
 * LlmComplete): provider / model / agent / session / timestamp + the four
 * token buckets (input, output, cache_read, cache_write).
 *
 * This is a structured telemetry point — NOT a parse of the router sse log.
 * The fields come straight from the existing chain: provider adapters parse
 * usage (TokenUsage, protocol.scala:106), aggregateChunks preserves it, and
 * LlmComplete holds the final ConsumeResult with usage + model.
 */
case class LlmUsageRecord(
  timestamp: Long, // epoch millis
  provider: String, // provider id, e.g. "openai", "deepseek"
  model: String, // model id, e.g. "glm-5.2"
  agent: String, // agent name, e.g. "Backend"
  sessionId: Option[String], // None for unsupervised contexts
  inputTokens: Int,
  outputTokens: Int,
  cacheReadTokens: Int,
  cacheWriteTokens: Int
)

object LlmUsageRecord:
  given Encoder[LlmUsageRecord] = deriveEncoder
  given Decoder[LlmUsageRecord] = deriveDecoder

/** One aggregation bucket (grouped by dimension). */
case class UsageBucket(
  key: String, // e.g. "deepseek", "deepseek-v4-flash", "Backend", "2026-08-18T13"
  count: Int,
  inputTokens: Long,
  outputTokens: Long,
  cacheReadTokens: Long,
  cacheWriteTokens: Long
)

object UsageBucket:
  given Encoder[UsageBucket] = deriveEncoder

/**
 * Aggregate result for GET /api/usage/aggregate.
 *
 * costEquivalent is a rough billed-token equivalent for the INPUT side:
 * inputTokens is the full input bucket and already CONTAINS cacheRead
 * (v1.2 spec §1.2 — adapter normalization; empirically in ≥ cr holds), so
 * the cache-read portion is billed once at 0.1x and the remainder at 1x:
 *   (totalInput - totalCacheRead) + totalCacheRead * 0.1
 * The previous `totalInput + cr * 0.1` billed cacheRead at 1x + 0.1x =
 * 1.1x (double count, inflated ~86% on cache-heavy workloads). Output is
 * deliberately excluded — display-level "total consumption" is
 * input + output (spec v1.2 §2.4); this field is the dashboard's
 * comparable billing unit under the cache-0.1x assumption.
 */
case class UsageAggregate(
  totalInput: Long,
  totalOutput: Long,
  totalCacheRead: Long,
  totalCacheWrite: Long,
  count: Int,
  costEquivalent: Long,
  buckets: List[UsageBucket]
)

object UsageAggregate:
  given Encoder[UsageAggregate] = deriveEncoder

/**
 * File-based append-only store for LlmUsageRecord.
 *
 * Layout: `<dataRoot>/usage-records/usage-records.jsonl` — one JSON object
 * per line, appended under a ReentrantLock (the load→modify→save races that
 * hit SubAgentTaskStore in the 11:08 incident are impossible here since
 * append is a single atomic write, but the lock keeps concurrent appenders
 * from interleaving partial lines).
 *
 * ## Incremental aggregation (tokenpanel-incremental, 2026-09-17)
 *
 * `aggregate` no longer re-reads the whole ledger per request. The aggregation
 * of the consumed prefix lives in `<baseDir>/usage-agg-v1.json` ([[UsageAggCacheFile]]),
 * keyed by (provider, model, agent, local hour) cells; a request consumes only the
 * bytes appended since the watermark and merges them into that table. Opening the
 * token panel therefore costs O(new records) instead of O(whole history), and no
 * longer grows linearly with the ledger.
 *
 * Design invariants:
 *   - the only write path is `record` → `os.write.append` (pure append), so the
 *     consumed prefix `[0, W)` never changes and the byte offset `W` is an exact
 *     watermark (timestamps are NOT — the live corpus has out-of-order rows);
 *   - every cell counter is additive, so the cache merge is component-wise addition;
 *     `costEquivalent` is recomputed on the merged totals (truncation is not additive);
 *   - the cache may only make things **slower, never wrong**: missing / stale /
 *     corrupt / foreign cache ⇒ full rebuild; any cache-layer exception ⇒ fall back
 *     to [[aggregateFull]] (today's behaviour);
 *   - invalidation is per §6.3 R1/R2 + V1–V7 of the diagnosis report
 *     (`.nebflow/reports/20260917_tokenpanel-diag.md`); a partial-hour window edge
 *     is answered by an exact re-read of that hour's byte span, never by a
 *     whole-ledger rescan.
 *
 * ## Locking (fix of the `withLock` read-path defect)
 *
 * The append guard is a JVM `ReentrantLock` held for the duration of the append
 * only. The read path does NOT use it: the old `loadAll` wrapped the *construction*
 * of an `IO` in `withLock` and then `.flatten`-ed it, so the actual read ran after
 * the lock was released — 8 concurrent aggregate requests each read the whole ledger
 * (~72 MB) in parallel. Reads now go through [[readGuard]] (a JVM `Semaphore(1)`
 * acquired around the *executed* body), and the cache advance is additionally
 * single-flight: it runs under that guard and re-checks the memo, so concurrent
 * callers reuse the one advance instead of duplicating it.
 */
class UsageRecordStore(baseDir: os.Path):

  private val logger = NebflowLogger.forName("nebflow.usage-record")
  private val lock = new ReentrantLock()

  /**
   * In-memory last-activity index: agent name → timestamp of its most recent
   * record. Updated on every [[record]]. General per-agent last-seen index —
   * the cold-start routing that consumed it was removed (2026-08-19, model
   * selection now follows the preset strictly); kept as a cheap extension
   * point for future per-agent activity features.
   */
  private val lastSeen = new java.util.concurrent.ConcurrentHashMap[String, Long]()

  private def logPath: os.Path = baseDir / "usage-records.jsonl"

  // ── append guard ────────────────────────────────────────────────────────────
  /**
   * Run a plain (non-IO) body under the append lock — the lock is held while the
   * body *runs*. Never pass an `IO` body here: that would only build the effect
   * under the lock and run it after release, which is exactly the read-path defect
   * this batch fixed (reads use [[readGuard]] instead).
   */
  private def withAppendLock[A](body: => A): IO[A] = IO.blocking {
    lock.lock()
    try body
    finally lock.unlock()
  }

  /** Append one record. Atomic per-line write; no read-modify-write race. */
  def record(r: LlmUsageRecord): IO[Unit] = withAppendLock {
    lastSeen.put(r.agent, r.timestamp)
    os.write.append(logPath, r.asJson.noSpaces + "\n", createFolders = true)
  }

  /** Epoch millis of the agent's most recent record; 0 if never recorded. */
  def lastActivityMs(agent: String): Long = lastSeen.getOrDefault(agent, 0L)

  // ── read-side mutual exclusion ──────────────────────────────────────────────
  /**
   * Serializes read-side work (whole-ledger reads, cache loads, cache rebuilds).
   *
   * Why a JVM semaphore and not `cats-effect Mutex`: this class is constructed
   * eagerly with `new` (SharedResources.scala), so it has no `IO` context to build a
   * `Mutex` in; the usual `Mutex[IO].memoize.flatten` shim was measured NOT to
   * serialize concurrent `unsafeRunSync()` callers (8 concurrent cold requests
   * produced 8 rebuilds in this batch's single-flight spec). A `Semaphore(1)`
   * acquired and released around the *executed* body — the same shape as
   * [[withAppendLock]], never around an `IO` value — is unambiguous: the body runs
   * while the permit is held, and the permit cannot leak (the thunk inside
   * `IO.blocking` always runs its `finally`, cancellation included).
   */
  private val readSemaphore = new java.util.concurrent.Semaphore(1)

  private def readGuard[A](body: => A): IO[A] = IO.blocking {
    readSemaphore.acquire()
    try body
    finally readSemaphore.release()
  }

  /**
   * Load all records in file order (oldest first).
   *
   * Reference/fallback path: the read runs *inside* the read guard (the defect
   * fixed in this batch was that it ran outside the lock — the old `withLock`
   * wrapped only the construction of an `IO` and `.flatten`-ed it, so the read
   * happened after release). Kept verbatim otherwise — it is the reference
   * implementation the equivalence spec compares the incremental path against.
   */
  def loadAll(): IO[List[LlmUsageRecord]] = readGuard {
    if !os.exists(logPath) then Nil
    else
      fullPathCalls.incrementAndGet()
      fullPathBytes.addAndGet(os.size(logPath))
      os.read
        .lines(logPath)
        .flatMap(line => decode[LlmUsageRecord](line).toOption)
        .toList
  }

  /**
   * Aggregate records over [from, to) with an optional grouping dimension —
   * incremental path (cache) with a full-recompute fallback.
   *
   * dim: "provider" | "model" | "agent" | "hour" | "day" | absent (totals only)
   * from/to: epoch millis, inclusive lower / exclusive upper. Both optional.
   * provider/model/agent: optional exact-match filters applied BEFORE grouping
   * (orthogonal to dim — e.g. dim=agent + provider=gw-a groups by agent within
   * provider gw-a's records only). Absent = no filtering on that field.
   *
   * Request parameters and the `UsageAggregate` response shape are unchanged.
   */
  def aggregate(
    dim: Option[String],
    from: Option[Long],
    to: Option[Long],
    provider: Option[String] = None,
    model: Option[String] = None,
    agent: Option[String] = None
  ): IO[UsageAggregate] =
    aggregateIncremental(dim, from, to, provider, model, agent).handleErrorWith { e =>
      IO.delay {
        fallbacks.incrementAndGet()
        logger.warnSync(
          "usage aggregate cache path failed — falling back to full recompute (slow, not wrong)",
          "error" -> s"${e.getClass.getSimpleName}: ${e.getMessage}"
        )
      } *> aggregateFull(dim, from, to, provider, model, agent)
    }

  /**
   * Reference implementation — today's semantics, whole ledger re-read and
   * re-aggregated per call. Used by the equivalence spec as ground truth, as the
   * fallback when the cache layer fails, and by nothing else on the hot path.
   */
  private[core] def aggregateFull(
    dim: Option[String],
    from: Option[Long],
    to: Option[Long],
    provider: Option[String] = None,
    model: Option[String] = None,
    agent: Option[String] = None
  ): IO[UsageAggregate] =
    loadAll().map { records =>
      val filtered = records.filter { r =>
        val okFrom = from.forall(r.timestamp >= _)
        val okTo = to.forall(r.timestamp < _)
        val okProvider = provider.forall(_ == r.provider)
        val okModel = model.forall(_ == r.model)
        val okAgent = agent.forall(_ == r.agent)
        okFrom && okTo && okProvider && okModel && okAgent
      }
      val dimKey: LlmUsageRecord => String = dim.map(_.toLowerCase) match
        case Some("provider") => r => r.provider
        case Some("model") => r => r.model
        case Some("agent") => r => r.agent
        case Some("hour") => r => hourKey(r.timestamp)
        case Some("day") => r => dayKey(r.timestamp)
        case _ => _ => "" // totals only
      val buckets: List[UsageBucket] = dim match
        case Some(d) if Set("provider", "model", "agent", "hour", "day").contains(d.toLowerCase) =>
          filtered
            .groupBy(dimKey)
            .toList
            .sortBy(_._1)
            .map { case (key, rs) =>
              UsageBucket(
                key = key,
                count = rs.size,
                inputTokens = rs.map(_.inputTokens.toLong).sum,
                outputTokens = rs.map(_.outputTokens.toLong).sum,
                cacheReadTokens = rs.map(_.cacheReadTokens.toLong).sum,
                cacheWriteTokens = rs.map(_.cacheWriteTokens.toLong).sum
              )
            }
        case _ => Nil
      val totalInput = filtered.map(_.inputTokens.toLong).sum
      val totalOutput = filtered.map(_.outputTokens.toLong).sum
      val totalCacheRead = filtered.map(_.cacheReadTokens.toLong).sum
      val totalCacheWrite = filtered.map(_.cacheWriteTokens.toLong).sum
      val costEquivalent = (totalInput - totalCacheRead) + (totalCacheRead * 0.1).toLong
      UsageAggregate(
        totalInput = totalInput,
        totalOutput = totalOutput,
        totalCacheRead = totalCacheRead,
        totalCacheWrite = totalCacheWrite,
        count = filtered.size,
        costEquivalent = costEquivalent,
        buckets = buckets
      )
    }

  private def zoned(ts: Long): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

  private def hourKey(ts: Long): String =
    val z = zoned(ts)
    f"${z.getYear}%04d-${z.getMonthValue}%02d-${z.getDayOfMonth}%02dT${z.getHour}%02d"

  private def dayKey(ts: Long): String =
    val z = zoned(ts)
    f"${z.getYear}%04d-${z.getMonthValue}%02d-${z.getDayOfMonth}%02d"

  // ── persisted aggregate cache ───────────────────────────────────────────────

  private val cachePath: os.Path = baseDir / "usage-agg-v1.json"
  private val ReadChunk: Int = 64 * 1024

  private val digestOfEmpty: String =
    MessageDigest.getInstance("SHA-256").digest(Array.emptyByteArray).map(b => f"${b & 0xff}%02x").mkString

  /** Last validated cache + the file stamps it was validated against. */
  private final case class Memo(
    sourceSize: Long,
    cacheFileExists: Boolean,
    cacheFileSize: Long,
    cacheFileMtime: Long,
    cache: UsageAggCacheFile
  )

  private final case class Delta(
    cells: List[UsageAggCell],
    spans: Map[String, HourSpan],
    lines: Long,
    dropped: Long,
    maxTimestamp: Long,
    consumedEnd: Long
  )

  private val memo = new AtomicReference[Memo](null)
  private val hits = new AtomicLong(0L)
  private val rebuilds = new AtomicLong(0L)
  private val increments = new AtomicLong(0L)
  private val lastDeltaBytes = new AtomicLong(0L)
  private val totalSourceBytes = new AtomicLong(0L)
  private val fallbacks = new AtomicLong(0L)
  private val fullPathCalls = new AtomicLong(0L)
  private val fullPathBytes = new AtomicLong(0L)

  /**
   * Diagnostics of the cache (watermark, cell table size, J-P1/J-P2 counters).
   * Never throws: an unreadable cache reports the empty state instead, so callers
   * (tests, operators) cannot be taken down by a diagnostics call.
   */
  def cacheDiagnostics: IO[UsageCacheDiagnostics] =
    ensureCache
      .map(Option(_))
      .handleErrorWith { e =>
        IO.delay(
          logger.warnSync(
            "usage aggregate cache diagnostics degraded",
            "error" -> s"${e.getClass.getSimpleName}: ${e.getMessage}"
          )
        ) *> IO.pure(None)
      }
      .map(c => diagnosticsOf(c.getOrElse(emptyCache(ZoneId.systemDefault().getId))))

  private def diagnosticsOf(c: UsageAggCacheFile): UsageCacheDiagnostics =
    UsageCacheDiagnostics(
      cachePath = cachePath.toString,
      cacheFileExists = os.exists(cachePath),
      schemaVersion = c.schemaVersion,
      timezoneId = c.timezoneId,
      byteOffset = c.watermark.byteOffset,
      lineCount = c.watermark.lineCount,
      droppedLines = c.watermark.droppedLines,
      maxTimestamp = c.watermark.maxTimestamp,
      cellCount = c.cells.size,
      hourCount = c.hourSpans.size,
      sourceSize = sourceSize,
      lastDeltaBytes = lastDeltaBytes.get(),
      totalSourceBytes = totalSourceBytes.get(),
      fallbacks = fallbacks.get(),
      fullPathCalls = fullPathCalls.get(),
      fullPathBytes = fullPathBytes.get(),
      rebuilds = rebuilds.get(),
      increments = increments.get(),
      hits = hits.get()
    )

  // ── small IO helpers ────────────────────────────────────────────────────────

  private def sourceSize: Long = if os.exists(logPath) then os.size(logPath) else 0L

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** Read at most `len` bytes at `start`; fewer if EOF is reached first. */
  private def readRange(path: os.Path, start: Long, len: Int): Array[Byte] =
    if len <= 0 || !os.exists(path) then Array.emptyByteArray
    else
      val raf = new RandomAccessFile(path.toIO, "r")
      try
        raf.seek(math.max(0L, start))
        val buf = new Array[Byte](len)
        var off = 0
        var n = raf.read(buf, 0, len)
        while n > 0 && off + n < len do
          off += n
          n = raf.read(buf, off, len - off)
        val total = if n > 0 then off + n else off
        if total == len then buf else java.util.Arrays.copyOf(buf, total)
      finally raf.close()

  private def headDigestOf(path: os.Path, w: Long): String =
    if w <= 0 then digestOfEmpty
    else sha256Hex(readRange(path, 0L, math.min(ReadChunk.toLong, w).toInt))

  private def tailDigestOf(path: os.Path, w: Long): String =
    if w <= 0 then digestOfEmpty
    else
      val start = math.max(0L, w - ReadChunk)
      sha256Hex(readRange(path, start, (w - start).toInt))

  /**
   * Stream the source bytes `[start, rawEnd)` (`rawEnd < 0` ⇒ to EOF), decoding
   * complete lines only. A trailing line without `'\n'` is NOT consumed: it is
   * either still being appended or torn, and `record()` always terminates its line.
   * Returns (complete lines seen, end offset of the last complete line).
   */
  private def scanLines(
    path: os.Path,
    start: Long,
    rawEnd: Long
  )(onLine: (Long, Long, Option[LlmUsageRecord]) => Unit): (Long, Long) =
    val raf = new RandomAccessFile(path.toIO, "r")
    try
      raf.seek(start)
      val buf = new Array[Byte](1 << 20)
      val line = new java.io.ByteArrayOutputStream(256)
      var pos = start
      var lineStart = start
      var lines = 0L
      var consumedEnd = start
      var done = false
      while !done do
        val limit =
          if rawEnd < 0 then buf.length
          else math.min(buf.length.toLong, rawEnd - pos).toInt
        if limit <= 0 then done = true
        else
          val n = raf.read(buf, 0, limit)
          if n <= 0 then done = true
          else
            var i = 0
            while i < n do
              val b = buf(i)
              if b == '\n' then
                val end = pos + i + 1
                val text = new String(line.toByteArray, UTF_8).stripSuffix("\r")
                onLine(lineStart, end, decode[LlmUsageRecord](text).toOption)
                line.reset()
                lines += 1
                consumedEnd = end
                lineStart = end
              else line.write(b.toInt)
              i += 1
            pos += n
          end if
        end if
      end while
      (lines, consumedEnd)
    finally raf.close()

    end try

  end scanLines

  /** Consume `[start, EOF)` into additive cells + hour spans. */
  private def scanDelta(path: os.Path, start: Long, zone: ZoneId): Delta =
    if !os.exists(path) then Delta(Nil, Map.empty, 0L, 0L, Long.MinValue, 0L)
    else
      val cells = scala.collection.mutable.HashMap.empty[(String, String, String, String), UsageCounters]
      val spans = scala.collection.mutable.HashMap.empty[String, HourSpan]
      var dropped = 0L
      var maxTs = Long.MinValue
      val (lines, consumedEnd) = scanLines(path, start, -1L) { (ls, le, rec) =>
        rec match
          case None => dropped += 1
          case Some(r) =>
            val hk = UsageAggCache.hourKey(r.timestamp, zone)
            val key = (hk, r.provider, r.model, r.agent)
            cells.update(key, cells.getOrElse(key, UsageCounters.zero) + UsageAggCache.countersOf(r))
            spans.update(
              hk,
              spans.get(hk) match
                case Some(s) => HourSpan(math.min(s.start, ls), math.max(s.end, le))
                case None => HourSpan(ls, le)
            )
            if r.timestamp > maxTs then maxTs = r.timestamp
      }
      Delta(
        cells = cells.toList.map { case ((h, p, m, a), c) => UsageAggCell(h, p, m, a, c) },
        spans = spans.toMap,
        lines = lines,
        dropped = dropped,
        maxTimestamp = maxTs,
        consumedEnd = consumedEnd
      )

  private def applyDelta(base: UsageAggCacheFile, d: Delta, zone: ZoneId): UsageAggCacheFile =
    val w = d.consumedEnd
    UsageAggCacheFile(
      schemaVersion = UsageAggCache.SchemaVersion,
      timezoneId = zone.getId,
      watermark = UsageAggWatermark(
        byteOffset = w,
        lineCount = base.watermark.lineCount + d.lines,
        droppedLines = base.watermark.droppedLines + d.dropped,
        maxTimestamp = math.max(base.watermark.maxTimestamp, d.maxTimestamp),
        headDigest = headDigestOf(logPath, w),
        tailDigest = tailDigestOf(logPath, w)
      ),
      cells = UsageAggCache.mergeCells(base.cells, d.cells),
      hourSpans = UsageAggCache.mergeSpans(base.hourSpans, d.spans)
    )

  end applyDelta

  private def emptyCache(zoneId: String): UsageAggCacheFile =
    UsageAggCacheFile(
      schemaVersion = UsageAggCache.SchemaVersion,
      timezoneId = zoneId,
      watermark = UsageAggWatermark(0L, 0L, 0L, 0L, digestOfEmpty, digestOfEmpty),
      cells = Nil,
      hourSpans = Map.empty
    )

  // ── cache validation: the V1–V7 / R1–R2 rules ───────────────────────────────

  private def atLineBoundary(offset: Long): Boolean =
    readRange(logPath, offset - 1, 1).headOption.contains('\n'.toByte)

  private def cellsWellFormed(cache: UsageAggCacheFile): Boolean =
    val w = cache.watermark.byteOffset
    cache.cells.forall(c => UsageAggCache.isHourKey(c.hourKey) && cache.hourSpans.contains(c.hourKey)) &&
    cache.hourSpans.forall { case (k, s) =>
      UsageAggCache.isHourKey(k) && s.start >= 0 && s.start <= s.end && s.end <= w
    }

  /**
   * Is the cache still a faithful description of the source prefix it claims?
   * `None` = valid. Any reason ⇒ full rebuild (the enumerated exceptions only).
   */
  private def invalidReason(cache: UsageAggCacheFile, size: Long): Option[String] =
    val w = cache.watermark
    val zoneId = ZoneId.systemDefault().getId
    if cache.schemaVersion != UsageAggCache.SchemaVersion then
      Some(s"schemaVersion=${cache.schemaVersion} (expected ${UsageAggCache.SchemaVersion})")
    else if cache.timezoneId != zoneId then Some(s"timezoneId=${cache.timezoneId} (current $zoneId)")
    else if w.byteOffset > size then Some(s"source truncated/rotated: watermark=${w.byteOffset} > size=$size")
    else if w.byteOffset > 0 && !atLineBoundary(w.byteOffset) then
      Some(s"watermark ${w.byteOffset} is not on a line boundary")
    else if w.byteOffset > 0 && headDigestOf(logPath, w.byteOffset) != w.headDigest then
      Some("head digest mismatch (in-place rewrite)")
    else if w.byteOffset > 0 && tailDigestOf(logPath, w.byteOffset) != w.tailDigest then
      Some("tail digest mismatch (in-place rewrite)")
    else if !cellsWellFormed(cache) then Some("cell/hour-span table inconsistent")
    else None

  end invalidReason

  private def quarantine(reason: String): Unit =
    try
      val dest = baseDir / s"usage-agg-v1.json.corrupt-${System.currentTimeMillis()}"
      os.move(cachePath, dest)
      logger.warnSync("usage aggregate cache quarantined", "reason" -> reason, "moved" -> dest.last)
    catch case NonFatal(e) => logger.warnSync("usage aggregate cache quarantine failed", "error" -> e.getMessage)

  private def readCacheFile(): Option[UsageAggCacheFile] =
    if !os.exists(cachePath) then None
    else
      try
        decode[UsageAggCacheFile](os.read(cachePath)) match
          case Right(c) => Some(c)
          case Left(err) => quarantine(s"undecodable: ${err.getMessage}"); None
      catch
        case NonFatal(e) =>
          quarantine(s"unreadable: ${e.getMessage}")
          None

  private def cacheFileStamp: (Boolean, Long, Long) =
    if os.exists(cachePath) then (true, os.size(cachePath), os.mtime(cachePath))
    else (false, 0L, 0L)

  private def cacheFileUnchanged(m: Memo): Boolean =
    val (exists, size, mtime) = cacheFileStamp
    m.cacheFileExists == exists && (!exists || (m.cacheFileSize == size && m.cacheFileMtime == mtime))

  /** Atomic persist: tmp file → fsync → rename over the target. */
  private def persist(cache: UsageAggCacheFile): Unit =
    val tmp = baseDir / s"usage-agg-v1.json.tmp-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}"
    try
      os.makeDir.all(baseDir)
      val bytes = cache.asJson.noSpaces.getBytes(UTF_8)
      Files.write(tmp.toNIO, bytes)
      val ch = FileChannel.open(tmp.toNIO, StandardOpenOption.WRITE)
      try ch.force(true)
      finally ch.close()
      try Files.move(tmp.toNIO, cachePath.toNIO, StandardCopyOption.ATOMIC_MOVE)
      catch
        case NonFatal(_) =>
          // Providers without atomic move support (e.g. an existing target on Windows):
          // degrade to a replacing move rather than losing the cache.
          Files.move(tmp.toNIO, cachePath.toNIO, StandardCopyOption.REPLACE_EXISTING)
    catch
      case NonFatal(e) =>
        logger.warnSync("usage aggregate cache persist failed (served result unaffected)", "error" -> e.getMessage)
        try Files.deleteIfExists(tmp.toNIO)
        catch case NonFatal(_) => ()

    end try

  end persist

  private def setMemo(size: Long, cache: UsageAggCacheFile): Unit =
    val (exists, cSize, cMtime) = cacheFileStamp
    memo.set(Memo(size, exists, cSize, cMtime, cache))

  // ── the cache state machine ─────────────────────────────────────────────────

  /**
   * Fast path: no lock, no source content read when nothing changed — but always
   * re-verifying the 64 KiB head/tail digests, so an in-place rewrite of the
   * ledger (which keeps its size) cannot be missed.
   */
  private def freshMemo: IO[Option[UsageAggCacheFile]] = IO.blocking {
    val m = memo.get
    val size = sourceSize
    if m == null then None
    else if m.sourceSize != size then None
    else if !cacheFileUnchanged(m) then None
    else
      invalidReason(m.cache, size) match
        case Some(_) => None
        case None =>
          hits.incrementAndGet()
          Some(m.cache)
  }

  private def ensureCache: IO[UsageAggCacheFile] =
    freshMemo.flatMap {
      case Some(c) => IO.pure(c)
      case None => readGuard(advanceLocked())
    }

  /**
   * Load / validate / advance, under the read guard. A concurrent caller that
   * already advanced the cache makes this a no-op (single-flight by construction:
   * the first caller rebuilds, the followers reuse the same result).
   */
  private def advanceLocked(): UsageAggCacheFile =
    val size = sourceSize
    val m = memo.get
    if m != null && m.sourceSize == size && cacheFileUnchanged(m) && invalidReason(m.cache, size).isEmpty then
      hits.incrementAndGet()
      m.cache
    else loadOrRebuild(size)

  private def loadOrRebuild(size: Long): UsageAggCacheFile =
    val zone = ZoneId.systemDefault()
    val onDisk = readCacheFile()
    val valid = onDisk.filter { c =>
      invalidReason(c, size) match
        case Some(reason) =>
          logger.warnSync("usage aggregate cache invalid — rebuilding from the ledger", "reason" -> reason)
          false
        case None => true
    }
    valid match
      case Some(c) if c.watermark.byteOffset == size =>
        // R1: no new data — zero source bytes consumed.
        hits.incrementAndGet()
        setMemo(size, c)
        c
      case Some(c) =>
        // R2: consume only the appended span.
        val delta = scanDelta(logPath, c.watermark.byteOffset, zone)
        val merged = applyDelta(c, delta, zone)
        increments.incrementAndGet()
        lastDeltaBytes.set(merged.watermark.byteOffset - c.watermark.byteOffset)
        totalSourceBytes.addAndGet(merged.watermark.byteOffset - c.watermark.byteOffset)
        if delta.lines > 0 then persist(merged)
        setMemo(size, merged)
        merged
      case None =>
        // V1/V2/V3/V4/V5/V6 (+ watermark not on a line boundary): start from zero.
        val delta = scanDelta(logPath, 0L, zone)
        val merged = applyDelta(emptyCache(zone.getId), delta, zone)
        rebuilds.incrementAndGet()
        lastDeltaBytes.set(merged.watermark.byteOffset)
        totalSourceBytes.addAndGet(merged.watermark.byteOffset)
        if delta.lines > 0 then persist(merged)
        setMemo(size, merged)
        merged
    end match
  end loadOrRebuild

  // ── query: cells + exact re-read of the hours a window edge cuts ────────────

  private def aggregateIncremental(
    dim: Option[String],
    from: Option[Long],
    to: Option[Long],
    provider: Option[String],
    model: Option[String],
    agent: Option[String]
  ): IO[UsageAggregate] =
    val zone = ZoneId.systemDefault()
    ensureCache.flatMap { cache =>
      edgeCells(cache, from, to, zone).map { edge =>
        UsageAggCache.build(
          UsageAggCache.interiorCells(cache, from, to, zone) ++ edge,
          dim,
          provider,
          model,
          agent
        )
      }
    }

  end aggregateIncremental

  /**
   * Records of the (at most two) hours a window edge cuts: their cached cell is
   * discarded and rebuilt from the raw byte span of that hour, filtered by the
   * exact timestamp window. Bounded by the hour's own byte span (typically a few
   * hundred lines) — never by the ledger size.
   */
  private def edgeCells(
    cache: UsageAggCacheFile,
    from: Option[Long],
    to: Option[Long],
    zone: ZoneId
  ): IO[List[UsageAggCell]] =
    val hours = UsageAggCache.edgeHours(cache.hourSpans.keySet, from, to, zone)
    if hours.isEmpty then IO.pure(Nil)
    else
      IO.blocking {
        val out = List.newBuilder[UsageAggCell]
        hours.foreach { h =>
          cache.hourSpans.get(h).foreach { span =>
            scanLines(logPath, span.start, span.end) { (_, _, rec) =>
              rec.foreach { r =>
                if UsageAggCache.hourKey(r.timestamp, zone) == h &&
                  from.forall(r.timestamp >= _) &&
                  to.forall(r.timestamp < _)
                then out += UsageAggCache.cellOf(r, h)
              }
            }
          }
        }
        out.result()
      }
    end if
  end edgeCells

end UsageRecordStore
