package nebflow.core.flow

/** Classifies step errors to decide whether a retry is worthwhile.
  *
  * Transient errors (network hiccups, rate limits, timeouts) deserve a retry
  * with exponential backoff. Permanent errors (agent not found, validation
  * failures, unsupported features) should fail immediately — retrying won't
  * change the outcome.
  */
object StepErrorClassifier:

  sealed trait ErrorType:
    def shouldRetry: Boolean
  case object Transient extends ErrorType:
    val shouldRetry = true
  case object Permanent extends ErrorType:
    val shouldRetry = false

  /** Permanent error keywords — retrying will not help. */
  private val permanentPatterns = List(
    "not found",
    "cannot be used",
    "not yet implemented",
    "validation failed",
    "invalid yaml",
    "no default agent",
    "agent not found",
    "missing required",
    "malformed"
  )

  /** Transient error keywords — the operation might succeed on retry. */
  private val transientPatterns = List(
    "timeout", "timed out",
    "rate limit", "429", "too many requests",
    "connection reset", "connection refused", "connection aborted",
    "service unavailable", "503", "502", "bad gateway",
    "internal server error", "500",
    "i/o error", "broken pipe",
    "network", "unreachable",
    "temporarily unavailable",
    "deadline exceeded",
    "stream error",
    "eof",
    "cancelled",
    "interrupted"
  )

  /** Classify an error message as Transient or Permanent.
    *
    * Default policy: unknown errors are treated as Transient (benefit of the
    * doubt — one retry is cheap insurance).
    */
  def classify(error: String): ErrorType =
    val lower = error.toLowerCase

    // Check permanent first — a "not found" inside a "timeout" message is
    // still likely permanent (the resource won't appear by waiting).
    if permanentPatterns.exists(p => lower.contains(p)) then Permanent
    else if transientPatterns.exists(p => lower.contains(p)) then Transient
    else Transient // unknown → optimistic retry

  /** Compute exponential backoff delay in milliseconds for a given retry attempt.
    *
    * Attempt 0 → 2000ms, 1 → 4000ms, 2 → 8000ms, etc.
    * Capped at 30s to avoid excessively long waits.
    */
  def backoffMs(retryAttempt: Int): Long =
    math.min(30000L, (2000L * math.pow(2, retryAttempt)).toLong)

end StepErrorClassifier
