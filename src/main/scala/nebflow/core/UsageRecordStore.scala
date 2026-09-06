package nebflow.core

import cats.effect.IO
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.NebflowLogger

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.concurrent.locks.ReentrantLock

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
 * from interleaving partial lines; queries read the whole file under the
 * same lock for a consistent snapshot).
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

  private def withLock[A](body: => A): IO[A] = IO.blocking {
    lock.lock()
    try body
    finally lock.unlock()
  }

  /** Append one record. Atomic per-line write; no read-modify-write race. */
  def record(r: LlmUsageRecord): IO[Unit] = withLock {
    lastSeen.put(r.agent, r.timestamp)
    os.write.append(logPath, r.asJson.noSpaces + "\n", createFolders = true)
  }

  /** Epoch millis of the agent's most recent record; 0 if never recorded. */
  def lastActivityMs(agent: String): Long = lastSeen.getOrDefault(agent, 0L)

  /** Load all records in file order (oldest first). */
  def loadAll(): IO[List[LlmUsageRecord]] = withLock {
    IO.blocking {
      if !os.exists(logPath) then Nil
      else
        os.read
          .lines(logPath)
          .flatMap(line => decode[LlmUsageRecord](line).toOption)
          .toList
    }
  }.flatten

  /**
   * Aggregate records over [from, to) with an optional grouping dimension.
   *
   * dim: "provider" | "model" | "agent" | "hour" | "day" | absent (totals only)
   * from/to: epoch millis, inclusive lower / exclusive upper. Both optional.
   * provider/model/agent: optional exact-match filters applied BEFORE grouping
   * (orthogonal to dim — e.g. dim=agent + provider=gw-a groups by agent within
   * provider gw-a's records only). Absent = no filtering on that field.
   */
  def aggregate(
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

end UsageRecordStore
