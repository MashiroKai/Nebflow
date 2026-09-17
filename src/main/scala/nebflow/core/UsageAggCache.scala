package nebflow.core

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

import java.time.{LocalDateTime, ZoneId}

/**
 * Additive token counters — the merge unit of the incremental aggregate cache.
 *
 * Every field is a plain sum over records, so two disjoint byte ranges of the
 * append-only source merge by component-wise addition (`+`).
 *
 * `costEquivalent` is deliberately NOT a field here. Its formula
 * `(input - cacheRead) + (cacheRead * 0.1).toLong` truncates, so
 * `Σ cost(cell) != cost(Σ cell)` — summing per-cell costs accumulates one
 * truncation error per cell. It must be recomputed exactly once on the merged
 * totals (see [[UsageAggCache.build]]).
 */
final case class UsageCounters(count: Long, input: Long, output: Long, cacheRead: Long, cacheWrite: Long):
  def +(other: UsageCounters): UsageCounters =
    UsageCounters(
      count + other.count,
      input + other.input,
      output + other.output,
      cacheRead + other.cacheRead,
      cacheWrite + other.cacheWrite
    )

object UsageCounters:
  val zero: UsageCounters = UsageCounters(0L, 0L, 0L, 0L, 0L)
  given Encoder[UsageCounters] = deriveEncoder
  given Decoder[UsageCounters] = deriveDecoder

/**
 * One cell of the aggregate cache: a (provider, model, agent) triple inside one
 * local-time hour (the hour key format is the store's `hourKey`).
 *
 * The hour is the time granularity because it is the finest key any query shape
 * needs: `dim=hour` groups by it, `dim=day` groups by its date part, and every
 * other dim only needs it to decide whether a window edge cuts the bucket. A
 * window whose bounds are not hour-aligned is answered by (a) the cells of the
 * hours fully inside the window plus (b) an exact re-read of the (at most two)
 * hours an edge cuts — see `UsageRecordStore.edgeCells`.
 */
final case class UsageAggCell(hourKey: String, provider: String, model: String, agent: String, counters: UsageCounters)

object UsageAggCell:
  given Encoder[UsageAggCell] = deriveEncoder
  given Decoder[UsageAggCell] = deriveDecoder

/** Byte range in the source jsonl that covers every **consumed** line of one local hour. */
final case class HourSpan(start: Long, end: Long)

object HourSpan:
  given Encoder[HourSpan] = deriveEncoder
  given Decoder[HourSpan] = deriveDecoder

/**
 * How far the cache has consumed the append-only source.
 *
 * `byteOffset` (end of the last complete line consumed) is the primary watermark.
 * Timestamps cannot serve as one: the live corpus has 43 rows below a
 * already-seen maximum (max backstep 1511 ms), so "ts <= W is final" is false.
 * `headDigest`/`tailDigest` are 64 KiB digests of `[0, min(64Ki, W))` and
 * `[max(0, W - 64Ki), W)` — the V4 in-place-rewrite probe.
 */
final case class UsageAggWatermark(
  byteOffset: Long,
  lineCount: Long,
  droppedLines: Long,
  maxTimestamp: Long,
  headDigest: String,
  tailDigest: String
)

object UsageAggWatermark:
  given Encoder[UsageAggWatermark] = deriveEncoder
  given Decoder[UsageAggWatermark] = deriveDecoder

/**
 * On-disk form of the persisted aggregate (`<baseDir>/usage-agg-v1.json`), the
 * durable half of the token-panel incremental fix.
 *
 * Invariants: `cells` is sorted by (hourKey, provider, model, agent); every cell
 * hour key has an entry in `hourSpans`; `hourSpans` bounds are byte offsets inside
 * `[0, watermark.byteOffset]`. Violations are treated as corruption (V6) and the
 * cache is rebuilt from the source.
 */
final case class UsageAggCacheFile(
  schemaVersion: Int,
  timezoneId: String,
  watermark: UsageAggWatermark,
  cells: List[UsageAggCell],
  hourSpans: Map[String, HourSpan]
)

object UsageAggCacheFile:
  given Encoder[UsageAggCacheFile] = deriveEncoder
  given Decoder[UsageAggCacheFile] = deriveDecoder

/**
 * Pure half of the incremental aggregate cache: key derivation, cell/span merge,
 * window classification and the aggregate builder. No IO — every function here is
 * directly unit-testable (and is what the equivalence spec drives).
 */
object UsageAggCache:

  val SchemaVersion: Int = 1

  /** The dimensions `aggregate` accepts (anything else falls back to totals only). */
  val Dims: Set[String] = Set("provider", "model", "agent", "hour", "day")

  private val HourKeyPattern = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}$".r

  /**
   * Local-time hour key — byte-for-byte the format the full-recompute path uses
   * (`UsageRecordStore.hourKey`); the equivalence spec pins the two together.
   */
  def hourKey(ts: Long, zone: ZoneId): String =
    val z = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), zone)
    f"${z.getYear}%04d-${z.getMonthValue}%02d-${z.getDayOfMonth}%02dT${z.getHour}%02d"

  /** Day key of a cell: the date part of its hour key (same local calendar). */
  def dayKey(hourKey: String): String = hourKey.take(10)

  def isHourKey(s: String): Boolean = HourKeyPattern.matches(s)

  /** Local epoch-millis bounds `[start, end)` of an hour key. */
  def hourBounds(hourKey: String, zone: ZoneId): (Long, Long) =
    val y = hourKey.substring(0, 4).toInt
    val mo = hourKey.substring(5, 7).toInt
    val d = hourKey.substring(8, 10).toInt
    val h = hourKey.substring(11, 13).toInt
    val start = LocalDateTime.of(y, mo, d, h, 0, 0).atZone(zone)
    (start.toInstant.toEpochMilli, start.plusHours(1).toInstant.toEpochMilli)

  def countersOf(r: LlmUsageRecord): UsageCounters =
    UsageCounters(
      count = 1L,
      input = r.inputTokens.toLong,
      output = r.outputTokens.toLong,
      cacheRead = r.cacheReadTokens.toLong,
      cacheWrite = r.cacheWriteTokens.toLong
    )

  def cellOf(r: LlmUsageRecord, hourKey: String): UsageAggCell =
    UsageAggCell(hourKey, r.provider, r.model, r.agent, countersOf(r))

  /** Merge two disjoint cell tables (prefix + delta); output order is the on-disk invariant. */
  def mergeCells(base: List[UsageAggCell], delta: List[UsageAggCell]): List[UsageAggCell] =
    val acc = scala.collection.mutable.LinkedHashMap.empty[(String, String, String, String), UsageCounters]
    def add(c: UsageAggCell): Unit =
      val k = (c.hourKey, c.provider, c.model, c.agent)
      acc.update(k, acc.getOrElse(k, UsageCounters.zero) + c.counters)
    base.foreach(add)
    delta.foreach(add)
    acc.toList
      .sortBy { case ((h, p, m, a), _) => (h, p, m, a) }
      .map { case ((h, p, m, a), c) => UsageAggCell(h, p, m, a, c) }

  /** Merge two span tables: the union of the byte ranges carrying each hour key. */
  def mergeSpans(base: Map[String, HourSpan], delta: Map[String, HourSpan]): Map[String, HourSpan] =
    val acc = scala.collection.mutable.LinkedHashMap.empty[String, HourSpan]
    def add(k: String, s: HourSpan): Unit =
      acc.update(k, acc.get(k) match
        case Some(o) => HourSpan(math.min(o.start, s.start), math.max(o.end, s.end))
        case None => s)
    base.foreach { case (k, s) => add(k, s) }
    delta.foreach { case (k, s) => add(k, s) }
    acc.toMap

  private def fullyInside(hourKey: String, from: Option[Long], to: Option[Long], zone: ZoneId): Boolean =
    val (hs, he) = hourBounds(hourKey, zone)
    from.forall(hs >= _) && to.forall(he <= _)

  private def intersects(hourKey: String, from: Option[Long], to: Option[Long], zone: ZoneId): Boolean =
    val (hs, he) = hourBounds(hourKey, zone)
    from.forall(he > _) && to.forall(hs < _)

  /**
   * Cells that answer the window on their own: their hour lies fully inside
   * `[from, to)`, so every record in them satisfies the time predicate.
   */
  def interiorCells(
    cache: UsageAggCacheFile,
    from: Option[Long],
    to: Option[Long],
    zone: ZoneId
  ): List[UsageAggCell] =
    if from.isEmpty && to.isEmpty then cache.cells
    else cache.cells.filter(c => fullyInside(c.hourKey, from, to, zone))

  /**
   * Hour keys a window edge cuts: they have consumed lines, they intersect the
   * window, and they are not fully inside it. Their cells must be replaced (not
   * supplemented) by an exact re-read, otherwise the partial hour would be
   * counted with records outside the window.
   */
  def edgeHours(
    hours: Iterable[String],
    from: Option[Long],
    to: Option[Long],
    zone: ZoneId
  ): List[String] =
    if from.isEmpty && to.isEmpty then Nil
    else
      hours.iterator
        .filter(h => intersects(h, from, to, zone) && !fullyInside(h, from, to, zone))
        .toList
        .sorted

  /**
   * Build the aggregate from ready-to-sum cells. Mirrors the full-recompute path
   * exactly: filter (equality on provider/model/agent) → group by dim → sort by key
   * → totals → cost recomputed on the merged totals.
   */
  def build(
    cells: Iterable[UsageAggCell],
    dim: Option[String],
    provider: Option[String],
    model: Option[String],
    agent: Option[String]
  ): UsageAggregate =
    val filtered = cells.filter { c =>
      provider.forall(_ == c.provider) && model.forall(_ == c.model) && agent.forall(_ == c.agent)
    }
    val dimKey: UsageAggCell => String = dim.map(_.toLowerCase) match
      case Some("provider") => c => c.provider
      case Some("model") => c => c.model
      case Some("agent") => c => c.agent
      case Some("hour") => c => c.hourKey
      case Some("day") => c => dayKey(c.hourKey)
      case _ => _ => "" // totals only
    val buckets: List[UsageBucket] = dim match
      case Some(d) if Dims.contains(d.toLowerCase) =>
        filtered
          .groupBy(dimKey)
          .toList
          .sortBy(_._1)
          .map { case (key, cs) =>
            val t = cs.foldLeft(UsageCounters.zero)(_ + _.counters)
            UsageBucket(
              key = key,
              count = t.count.toInt,
              inputTokens = t.input,
              outputTokens = t.output,
              cacheReadTokens = t.cacheRead,
              cacheWriteTokens = t.cacheWrite
            )
          }
      case _ => Nil
    val total = filtered.foldLeft(UsageCounters.zero)(_ + _.counters)
    val costEquivalent = (total.input - total.cacheRead) + (total.cacheRead * 0.1).toLong
    UsageAggregate(
      totalInput = total.input,
      totalOutput = total.output,
      totalCacheRead = total.cacheRead,
      totalCacheWrite = total.cacheWrite,
      count = total.count.toInt,
      costEquivalent = costEquivalent,
      buckets = buckets
    )

end UsageAggCache

/** Diagnostics of the persisted cache — the deterministic performance readings (J-P1/J-P2). */
final case class UsageCacheDiagnostics(
  cachePath: String,
  cacheFileExists: Boolean,
  schemaVersion: Int,
  timezoneId: String,
  byteOffset: Long,
  lineCount: Long,
  droppedLines: Long,
  maxTimestamp: Long,
  cellCount: Int,
  hourCount: Int,
  sourceSize: Long,
  /** Source bytes consumed by the most recent `ensureCache` (0 = pure cache hit). */
  lastDeltaBytes: Long,
  /** Source bytes consumed by this store instance in total (the lock/single-flight reading). */
  totalSourceBytes: Long,
  /** Times the cache layer threw and the request was served by the full recompute. */
  fallbacks: Long,
  /** `loadAll` calls (whole-ledger reads) — 0 growth over warm requests is the anti-fake judge. */
  fullPathCalls: Long,
  /** Bytes read by whole-ledger reads. */
  fullPathBytes: Long,
  /** Full rebuilds performed by this store instance (V1–V7 exceptions only). */
  rebuilds: Long,
  /** Incremental merges performed by this store instance. */
  increments: Long,
  /** `ensureCache` calls answered without touching the source content. */
  hits: Long
)
