package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.ProviderHealthPort
import nebflow.shared.SharedBackend
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Shared sttp transport for the decision providers (Face A / P1-A1).
 *
 * Mirrors the standalone-search leg's direct-call shape
 * (`SearchProviderResolver.executeStandaloneSearch`, `:367`): the shared
 * connection-pooled backend, a per-call read timeout, and HTTP-status error
 * classification. It never touches `LlmHandle`, never spends model tokens and
 * never consults model-provider health — the decision face has its own health
 * channel exactly as search does.
 */
private[decision] object DecisionHttpTransport:

  /**
   * POST a JSON body with a bearer credential and classify the outcome.
   *
   * `bodyForJson`/`parse` are injected so the two providers share one
   * classification path while keeping their own envelope handling.
   *
   * Credential discipline: the token is only ever written into the
   * `Authorization` header. It is never logged, never echoed in an error and
   * never included in the returned payload — the classified diagnostics carry
   * the RESPONSE body excerpt only.
   */
  def post(
    endpoint: String,
    token: String,
    body: String,
    timeoutMs: Long
  ): IO[Either[DecisionError, String]] =
    IO.blocking {
      try
        val request = basicRequest
          .post(uri"$endpoint")
          .header("Authorization", s"Bearer $token")
          .header("Content-Type", "application/json")
          .body(body)
          .readTimeout(timeoutMs.millis)
          .response(asStringAlways)
        val resp = request.send(SharedBackend.instance)
        if resp.code.isSuccess then Right(resp.body)
        else Left(classifyStatus(resp.code.code, resp.body))
      catch
        case e: Exception =>
          Left(classifyException(messageChainOf(e)))
    }

  /**
   * HTTP-status classification (same buckets as `classifyStandaloneError`):
   * 401/403 -> auth, 429 -> quota/rate, 5xx -> server, else transport. The
   * provider's message is carried through (truncated), never swallowed.
   */
  private[decision] def classifyStatus(code: Int, body: String): DecisionError =
    val excerpt = body.take(200)
    if code == 401 || code == 403 then DecisionError.Auth(s"HTTP $code")
    else if code == 429 then DecisionError.Quota(s"HTTP 429: $excerpt")
    else if code >= 500 then DecisionError.Server(s"HTTP $code: $excerpt")
    else DecisionError.Transport(s"HTTP $code: $excerpt")

  /** Exception classification: a timeout reads as a timeout, everything else
    * as transport — mirroring the search leg's message sniffing. */
  private[decision] def classifyException(raw: String): DecisionError =
    val lower = raw.toLowerCase
    if lower.contains("timed out") || lower.contains("timeout") then DecisionError.Timeout(raw.take(120))
    else DecisionError.Transport(raw.take(120))

  /**
   * Flatten an exception's message and its cause chain into one string.
   *
   * The HTTP layer wraps the real failure: a read-timeout surfaces as
   * `Exception when sending request: POST <url>` with the `HttpTimeoutException`
   * nested as the cause, so sniffing only the top message would classify every
   * timeout as a transport error. `Option(e.getMessage)` also drops the class
   * name when the message is null, hiding the only signal left.
   */
  private[decision] def messageChainOf(e: Throwable): String =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    var cur: Throwable = e
    var guard = 0
    while cur != null && guard < 8 do
      parts += Option(cur.getMessage).getOrElse(cur.getClass.getSimpleName)
      cur = cur.getCause
      guard += 1
    parts.mkString(" | ")

  /**
   * Record a call outcome on the decision health channel.
   *
   * A no-op when no monitor is wired (unit tests, headless callers) — the
   * health face is observational, and its absence must never change a verdict.
   */
  def record(health: Option[DecisionHealthPort], outcome: Either[DecisionError, DecisionResponse]): IO[Unit] =
    health match
      case None => IO.unit
      case Some(h) =>
        outcome match
          case Right(_) => h.recordDecisionSuccess()
          case Left(e) => h.recordDecisionFailure(e.message)
