package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.SharedBackend
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Self-hosted open-source LAYA provider (Face A / P1-A1).
 *
 * Transport is deliberately the SAME HTTP shape as the hosted provider
 * (`POST <endpoint>` with `state` / `model` / `questions`): one transmission
 * face, two deployments. This is also what makes provider choice a PRIVACY
 * choice — LAYA runs on weights in the local Apache-2.0 package and the state
 * never leaves the machine.
 *
 * Three LAYA-specific faces are handled here (card §A.2.3):
 *   - `min_confidence` abstention floor (passed through when configured);
 *   - the LAYA-specific question adaptations ([[LayaRequestAdapter]]);
 *   - `503 + Retry-After`, surfaced as a server-class failure carrying the
 *     retry hint rather than as an opaque error.
 */
final class LayaProvider(
  endpoint: String,
  model: String,
  token: String,
  timeoutMs: Long,
  minConfidence: Option[Double],
  health: Option[DecisionHealthPort]
) extends DecisionProvider[IO]:

  def id: String = DecisionProvider.Laya

  def predict(req: DecisionRequest): IO[Either[DecisionError, DecisionResponse]] =
    val m = req.model.filter(_.nonEmpty).getOrElse(model)
    val body = LayaRequestAdapter.requestBody(req, m, minConfidence).noSpaces
    post(body).map(_.flatMap(JevWireCodec.parseResponse))
      .flatTap(outcome => DecisionHttpTransport.record(health, outcome))

  /**
   * LAYA shares the transport but classifies `503 + Retry-After` itself: the
   * official face advertises a capacity signal there, and folding it into the
   * generic 5xx bucket would discard the only actionable part of the response.
   */
  private def post(body: String): IO[Either[DecisionError, String]] =
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
        else
          val retryAfter = resp.header("Retry-After")
          if resp.code.code == 503 then
            Left(
              DecisionError.Server(
                s"HTTP 503 (capacity)" + retryAfter.map(r => s", Retry-After: $r").getOrElse("") +
                  s": ${resp.body.take(200)}"
              )
            )
          else Left(DecisionHttpTransport.classifyStatus(resp.code.code, resp.body))
      catch
        case e: Exception =>
          Left(DecisionHttpTransport.classifyException(DecisionHttpTransport.messageChainOf(e)))
    }

  /**
   * Probe the LAYA `/health` face (card §A.2.3/§A.4-A6).
   *
   * Returns the raw status code and a short excerpt; never reads or echoes a
   * credential (the probe carries no Authorization header when no token is
   * configured, and the excerpt is a body snippet only).
   */
  def probeHealth: IO[Either[DecisionError, Int]] =
    val healthUrl = endpoint.replaceAll("/predict/?$", "/health")
    IO.blocking {
      try
        val resp = basicRequest.get(uri"$healthUrl").readTimeout(timeoutMs.millis).response(asStringAlways).send(SharedBackend.instance)
        if resp.code.isSuccess then Right(resp.code.code)
        else Left(DecisionHttpTransport.classifyStatus(resp.code.code, resp.body))
      catch
        case e: Exception =>
          Left(DecisionHttpTransport.classifyException(DecisionHttpTransport.messageChainOf(e)))
    }

object LayaProvider:

  def apply(settings: JevSettings): LayaProvider =
    new LayaProvider(
      endpoint = settings.endpoint,
      model = settings.model,
      token = settings.token.getOrElse(""),
      timeoutMs = settings.timeoutMs,
      // The abstention floor is not part of the shared settings record: it is a
      // LAYA-only knob and lives with the LAYA provider rather than widening
      // the common config face.
      minConfidence = None,
      health = settings.health
    )

  def apply(settings: JevSettings, minConfidence: Option[Double]): LayaProvider =
    new LayaProvider(
      endpoint = settings.endpoint,
      model = settings.model,
      token = settings.token.getOrElse(""),
      timeoutMs = settings.timeoutMs,
      minConfidence = minConfidence,
      health = settings.health
    )
