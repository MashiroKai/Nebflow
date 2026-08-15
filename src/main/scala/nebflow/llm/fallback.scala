package nebflow.llm

import cats.effect.{IO, Temporal}
import cats.syntax.all.*
import nebflow.shared.*

import scala.concurrent.duration.*
import scala.util.Random

class FallbackExhaustedError(val attempts: List[FallbackAttempt]) extends Exception:

  override def getMessage: String =
    val summary = attempts
      .map { a =>
        s"  ${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"
      }
      .mkString("\n")
    s"All providers failed:\n$summary"

/**
 * Raised when the all-Down gate ([[ProviderHealthMonitor.waitForAnyUp]]) timed
 * out waiting for any provider to recover. Deliberately NOT a
 * `java.util.concurrent.TimeoutException`: that type is classified Permanent
 * (stream-first-token timeouts skip retries by design, 0bf832c0), which would
 * kill the agent's llm-fail retry loop exactly when providers are recovering.
 * This error means "waited, none recovered yet" — the canonical transient
 * case, so [[Fallback.classifyError]] maps it to Transient and the 3-attempt
 * llm-fail-retry backstop fires.
 */
class AllProvidersDownTimeout(val waitedMs: Long) extends RuntimeException(
  s"all providers down: none recovered within ${waitedMs}ms"
)

case class FallbackResult[T](
  data: T,
  attempts: List[FallbackAttempt],
  usedCandidate: ModelCandidate
)

object Fallback:
  private val JitterMinMs = 1000
  private val JitterMaxMs = 3000
  private val DefaultTimeoutMs = Defaults.LlmTimeoutMs
  val MaxRetries: Int = 1
  val InitialBackoffMs: Long = 1000L
  val MaxBackoffMs: Long = 10000L

  def classifyError(error: Throwable): ErrorClassification =
    // Check for structured sttp4 HttpError first
    error match
      case e: sttp.client4.HttpError[?] =>
        val c = e.statusCode.code
        val reason = c match
          case 401 | 403 => FailoverReason.Auth
          case 404 => FailoverReason.ModelNotFound
          case 429 => FailoverReason.RateLimit
          case 500 | 502 | 503 => FailoverReason.ServerError
          case 529 => FailoverReason.Overloaded
          case 400 => FailoverReason.Format
          case _ => FailoverReason.ProviderError
        // Context overflow affects all providers — abort immediately.
        // Other 400 errors are permanent for this provider but may not affect others.
        val msgLower = Option(e.getMessage).map(_.toLowerCase).getOrElse("")
        val isContextOverflow = msgLower.contains("context_length_exceeded")
          || msgLower.contains("maximum context length")
          || msgLower.contains("reduce the length of the messages")
        val permanence = c match
          case 400 if isContextOverflow => ErrorPermanence.Fatal
          case 401 | 403 | 404 | 400 => ErrorPermanence.Permanent
          case _ => ErrorPermanence.Transient
        ErrorClassification(reason, permanence, Some(c), Some(error.getMessage))
      case e: AllProvidersDownTimeout =>
        ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Transient, message = Some(e.getMessage))
      case _: java.util.concurrent.TimeoutException =>
        ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Permanent, message = Some("timeout"))
      case _ =>
        val msg = Option(error.getMessage).map(_.toLowerCase).getOrElse("")
        if msg.contains("connection reset") || msg.contains("econnreset") || msg.contains("econnrefused") || msg
            .contains(
              "epipe"
            ) || msg.contains("broken pipe") || msg.contains("chunked") || msg.contains("invalid chunk") || msg
            .contains("transfer encoding") || msg.contains("reading_length")
        then
          ErrorClassification(
            FailoverReason.ConnectionReset,
            ErrorPermanence.Transient,
            message = Some(error.getMessage)
          )
        else if msg.contains("timeout") || msg.contains("timed out") then
          ErrorClassification(FailoverReason.Timeout, ErrorPermanence.Permanent, message = Some(error.getMessage))
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
        else if msg.contains("empty response") || msg.contains("no content") then
          ErrorClassification(FailoverReason.EmptyStream, ErrorPermanence.Permanent, message = Some(error.getMessage))
        else ErrorClassification(FailoverReason.Unknown, ErrorPermanence.Transient, message = Some(error.getMessage))
        end if

  end classifyError

  private def withTimeoutIO[A](ioa: IO[A], ms: Long): IO[A] =
    ioa.timeout(ms.millis)

  def sleepWithJitter(minMs: Int, maxMs: Int): IO[Unit] =
    val delay = minMs + java.util.concurrent.ThreadLocalRandom.current().nextInt(maxMs - minMs)
    IO.sleep(delay.millis)

  def tryProviderWithFallback[T](
    candidates: List[ModelCandidate],
    action: ModelCandidate => IO[T],
    maxRetries: Int = MaxRetries,
    onAttempt: Option[FallbackAttempt => IO[Unit]] = None,
    onProviderExhausted: Option[ModelCandidate => IO[Unit]] = None
  ): IO[FallbackResult[T]] =

    def tryWithRetry(
      candidate: ModelCandidate,
      retriesLeft: Int,
      backoffMs: Long,
      priorFailures: List[FallbackAttempt]
    )(fallback: List[FallbackAttempt] => IO[FallbackResult[T]]): IO[FallbackResult[T]] =
      val start = System.currentTimeMillis()
      withTimeoutIO(action(candidate), DefaultTimeoutMs).attempt.flatMap {
        case Right(result) =>
          val attempt = FallbackAttempt(
            candidate.providerId,
            candidate.model,
            None,
            None,
            System.currentTimeMillis() - start,
            maxRetries - retriesLeft,
            java.time.Instant.now().toString
          )
          val successNotify =
            if priorFailures.nonEmpty then
              onAttempt.traverse_(
                _.apply(
                  attempt.copy(
                    reason = Some(FailoverReason.Unknown),
                    message = Some(s"Switched to ${candidate.providerId}/${candidate.model} successfully")
                  )
                )
              )
            else IO.unit
          successNotify *> IO.pure(FallbackResult(result, priorFailures :+ attempt, candidate))
        case Left(error) =>
          val classification = classifyError(error)
          val durationMs = System.currentTimeMillis() - start
          val failAttempt = FallbackAttempt(
            candidate.providerId,
            candidate.model,
            Some(classification.reason),
            Some(classification.permanence),
            durationMs,
            maxRetries - retriesLeft,
            java.time.Instant.now().toString,
            classification.message.orElse(Option(error.getMessage))
          )
          onAttempt.traverse_(_.apply(failAttempt))
          val allFailures = priorFailures :+ failAttempt

          val notifyExhausted = onProviderExhausted.traverse_(_.apply(candidate))

          classification.permanence match
            case ErrorPermanence.Fatal =>
              // Error affects all providers (e.g. context overflow) — abort immediately
              notifyExhausted *> IO.raiseError(new FallbackExhaustedError(allFailures))
            case ErrorPermanence.Permanent =>
              notifyExhausted *> fallback(allFailures)
            case ErrorPermanence.Transient =>
              if retriesLeft > 0 then
                val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 2000)
                val delay = math.min(backoffMs + jitter, MaxBackoffMs)
                IO.sleep(delay.millis) *>
                  tryWithRetry(candidate, retriesLeft - 1, backoffMs * 2, allFailures)(fallback)
              else notifyExhausted *> fallback(allFailures)
      }
    end tryWithRetry

    def loop(remaining: List[ModelCandidate], attempts: List[FallbackAttempt]): IO[FallbackResult[T]] =
      remaining match
        case Nil =>
          IO.raiseError(new FallbackExhaustedError(attempts))
        case candidate :: rest =>
          tryWithRetry(candidate, maxRetries, InitialBackoffMs, attempts)(failures => loop(rest, failures))

    loop(candidates, Nil)
  end tryProviderWithFallback
end Fallback
