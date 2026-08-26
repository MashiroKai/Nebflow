package nebflow.shared

/** Stream-inactivity timeout (phase-2 watchdog: chunks received, then stalled).
  * Dedicated type so classifyError can distinguish it from a dead-provider
  * firstToken timeout / whole-stream no-progress hang — an upstream stall is
  * usually transient jitter (same-window sibling requests succeed), so it is
  * classified Transient while firstToken/no-progress remain Permanent.
  * Flow-node LLM supervision (2026-08-26 §P1): carrying lastChunkAgeMs lets
  * the agent layer retry the turn from a clean checkpoint (full re-send, no
  * seam stitching) within the existing MaxTurnLlmCalls budget. */
final case class StreamInactivityTimeout(lastChunkAgeMs: Long, message: String)
    extends RuntimeException(message)

enum FailoverReason:

  case Auth, RateLimit, Overloaded, ServerError, ModelNotFound, ProviderError, Format, ConnectionReset, Timeout,
    EmptyStream, CapabilityMismatch, Unknown

enum ErrorPermanence:
  case Transient, Permanent, Fatal

case class ErrorClassification(
  reason: FailoverReason,
  permanence: ErrorPermanence,
  statusCode: Option[Int] = None,
  message: Option[String] = None
)

case class FallbackAttempt(
  providerId: String,
  model: String,
  reason: Option[FailoverReason],
  permanence: Option[ErrorPermanence],
  durationMs: Long,
  retriesUsed: Int,
  timestamp: String,
  message: Option[String] = None
)
