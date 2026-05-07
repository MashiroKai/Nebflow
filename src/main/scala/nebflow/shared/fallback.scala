package nebflow.shared

enum FailoverReason:

  case Auth, RateLimit, Overloaded, ServerError, ModelNotFound, ProviderError, Format, ConnectionReset, Timeout,
    EmptyStream, Unknown

enum ErrorPermanence:
  case Transient, Permanent

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
