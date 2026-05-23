package nebflow.core.compact

import io.circe.*
import io.circe.parser.parse

case class CompactConfig(
  // Absolute buffer used as fallback for small-context models
  bufferTokens: Int = 13000,
  // Proportional buffer: max of absolute or 10% of context window
  bufferRatio: Double = 0.10,
  circuitBreakerMax: Int = 3,
  // Minimum delay between compaction retries (exponential backoff base)
  compactionRetryDelayMs: Int = 30000,
  // Emergency truncation: max messages to keep when compaction fails repeatedly
  emergencyKeepMessages: Int = 20,
  // FastMicroCompact: cache TTL in minutes — only fire when cache is cold
  microCacheTtlMinutes: Int = 120,
  // FastMicroCompact: how many recent tool results to keep untouched
  microKeepRecent: Int = 5,
  // Post-compact file restoration
  postCompactMaxFiles: Int = 5,
  postCompactMaxCharsPerFile: Int = 5000,
  postCompactTokenBudget: Int = 50000
):

  /** Buffer that scales with context window (10% min, or fixed 13k for small models). */
  def bufferForWindow(contextWindow: Int): Int =
    math.max(bufferTokens, (contextWindow * bufferRatio).toInt)

  /**
   * Exponential backoff: delay = compactionRetryDelayMs * 2^(failures - 1)
   * Returns 0 for failures=0 (no backoff needed).
   */
  def backoffMs(failures: Int): Long =
    if failures <= 0 then 0L
    else compactionRetryDelayMs.toLong * math.pow(2, failures - 1).toLong

  /** Check whether the elapsed time (ms) satisfies the backoff for the given failure count. */
  def isBackoffSatisfied(failures: Int, elapsedMs: Long): Boolean =
    failures <= 0 || elapsedMs >= backoffMs(failures)
end CompactConfig

object CompactConfig:

  private val configPath = os.home / ".nebflow" / "nebflow.json"

  /** Load CompactConfig from nebflow.json, falling back to defaults. */
  def apply(): CompactConfig =
    if !os.exists(configPath) then new CompactConfig()
    else
      try
        val json = parse(os.read(configPath)).toOption.getOrElse(Json.obj())
        val compact = json.hcursor.downField("compact")
        new CompactConfig(
          microCacheTtlMinutes = compact.downField("microCacheTtlMinutes").as[Int].toOption.getOrElse(120),
          microKeepRecent = compact.downField("microKeepRecent").as[Int].toOption.getOrElse(5),
          circuitBreakerMax = compact.downField("circuitBreakerMax").as[Int].toOption.getOrElse(3),
          bufferTokens = compact.downField("bufferTokens").as[Int].toOption.getOrElse(13000),
          emergencyKeepMessages = compact.downField("emergencyKeepMessages").as[Int].toOption.getOrElse(20)
        )
      catch case _: Exception => new CompactConfig()

end CompactConfig
