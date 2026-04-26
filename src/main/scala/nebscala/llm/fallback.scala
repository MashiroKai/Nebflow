package nebscala.llm

import cats.effect.{IO, Temporal}
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.util.Random

enum FailoverReason:
  case Auth, RateLimit, Overloaded, ServerError, ModelNotFound, ProviderError, Format, ConnectionReset, Timeout, EmptyStream, Unknown

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
  timestamp: String
)

case class FallbackResult[T](
  data: T,
  attempts: List[FallbackAttempt],
  usedCandidate: ModelCandidate
)

class FallbackExhaustedError(val attempts: List[FallbackAttempt]) extends Exception:
  override def getMessage: String =
    val summary = attempts.map { a =>
      s"  ${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"
    }.mkString("\n")
    s"All providers failed:\n$summary"

object Fallback:
  private val JitterMinMs = 1000
  private val JitterMaxMs = 3000
  private val DefaultTimeoutMs = 120_000

  def classifyError(error: Throwable): ErrorClassification =
    val msg = error.getMessage.toLowerCase

    if msg.contains("connection reset") || msg.contains("econnreset") || msg.contains("econnrefused") || msg.contains("epipe") || msg.contains("broken pipe") then
      ErrorClassification(FailoverReason.ConnectionReset, ErrorPermanence.Transient, message = Some(error.getMessage))
    else if msg.contains("timeout") || msg.contains("timed out") then
      ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Transient, message = Some(error.getMessage))
    else if msg.contains("auth") || msg.contains("unauthorized") || msg.contains("403") || msg.contains("401") then
      ErrorClassification(FailoverReason.Auth, ErrorPermanence.Permanent, message = Some(error.getMessage))
    else if msg.contains("rate limit") || msg.contains("429") then
      ErrorClassification(FailoverReason.RateLimit, ErrorPermanence.Transient, message = Some(error.getMessage))
    else if msg.contains("overloaded") || msg.contains("529") then
      ErrorClassification(FailoverReason.Overloaded, ErrorPermanence.Transient, message = Some(error.getMessage))
    else if msg.contains("server error") || msg.contains("500") || msg.contains("502") || msg.contains("503") then
      ErrorClassification(FailoverReason.ServerError, ErrorPermanence.Transient, message = Some(error.getMessage))
    else if msg.contains("model not found") || msg.contains("404") then
      ErrorClassification(FailoverReason.ModelNotFound, ErrorPermanence.Permanent, message = Some(error.getMessage))
    else if msg.contains("invalid request") || msg.contains("bad request") || msg.contains("400") then
      ErrorClassification(FailoverReason.Format, ErrorPermanence.Permanent, message = Some(error.getMessage))
    else
      ErrorClassification(FailoverReason.Unknown, ErrorPermanence.Transient, message = Some(error.getMessage))

  private def withTimeoutIO[A](ioa: IO[A], ms: Long): IO[A] =
    ioa.timeout(ms.millis).handleErrorWith { err =>
      IO.raiseError(new Exception(s"timeout", err))
    }

  def sleepWithJitter(minMs: Int, maxMs: Int): IO[Unit] =
    val delay = minMs + Random.nextInt(maxMs - minMs)
    IO.sleep(delay.millis)

  def tryProviderWithFallback[T](
    candidates: List[ModelCandidate],
    action: ModelCandidate => IO[T],
    onAttempt: Option[FallbackAttempt => IO[Unit]] = None
  ): IO[FallbackResult[T]] =
    def loop(
      remaining: List[ModelCandidate],
      attempts: List[FallbackAttempt]
    ): IO[FallbackResult[T]] = remaining match
      case Nil =>
        IO.raiseError(new FallbackExhaustedError(attempts))
      case candidate :: rest =>
        val start = System.currentTimeMillis()
        val attemptIO = withTimeoutIO(action(candidate), DefaultTimeoutMs)

        attemptIO.attempt.flatMap {
          case Right(result) =>
            val attempt = FallbackAttempt(
              candidate.providerId, candidate.model, None, None,
              System.currentTimeMillis() - start, 0,
              java.time.Instant.now().toString
            )
            val newAttempts = attempts :+ attempt
            onAttempt.traverse_(_.apply(attempt)) *>
              IO.pure(FallbackResult(result, newAttempts, candidate))
          case Left(error) =>
            val classification = classifyError(error)
            val durationMs = System.currentTimeMillis() - start

            // Rate limit: retry with jitter
            val withRetry: IO[FallbackResult[T]] =
              if classification.reason == FailoverReason.RateLimit then
                sleepWithJitter(JitterMinMs, JitterMaxMs) *>
                  withTimeoutIO(action(candidate), DefaultTimeoutMs).attempt.flatMap {
                    case Right(result) =>
                      val attempt = FallbackAttempt(
                        candidate.providerId, candidate.model, None, None,
                        System.currentTimeMillis() - start, 1,
                        java.time.Instant.now().toString
                      )
                      val newAttempts = attempts :+ attempt
                      onAttempt.traverse_(_.apply(attempt)) *>
                        IO.pure(FallbackResult(result, newAttempts, candidate))
                    case Left(_) =>
                      val failAttempt = FallbackAttempt(
                        candidate.providerId, candidate.model,
                        Some(classification.reason), Some(classification.permanence),
                        durationMs, 0, java.time.Instant.now().toString
                      )
                      val newAttempts = attempts :+ failAttempt
                      onAttempt.traverse_(_.apply(failAttempt)) *>
                        (if classification.permanence == ErrorPermanence.Permanent then
                          IO.raiseError(new FallbackExhaustedError(newAttempts))
                        else
                          loop(rest, newAttempts))
                  }
              else
                val attempt = FallbackAttempt(
                  candidate.providerId, candidate.model,
                  Some(classification.reason), Some(classification.permanence),
                  durationMs, 0, java.time.Instant.now().toString
                )
                val newAttempts = attempts :+ attempt
                onAttempt.traverse_(_.apply(attempt)) *>
                  (if classification.permanence == ErrorPermanence.Permanent then
                    IO.raiseError(new FallbackExhaustedError(newAttempts))
                  else
                    loop(rest, newAttempts))

            withRetry
        }

    loop(candidates, Nil)
