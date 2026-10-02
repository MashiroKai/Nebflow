package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json

/**
 * Hosted TypeSafe JeV provider (Face A / P1-A1).
 *
 * Wire: `POST <endpoint>` with `state` / `model` / `questions`, bearer
 * credential, one batched round trip for the whole question set. The endpoint
 * is singular by vendor design — every JeV model shares `POST /v1/systemone`.
 *
 * The credential is held in memory for the lifetime of this object and is only
 * ever placed into the `Authorization` header (see
 * [[DecisionHttpTransport.post]]); it is never logged and never rendered into
 * a diagnostic.
 */
final class TypesafeJevProvider(
  endpoint: String,
  model: String,
  token: String,
  timeoutMs: Long,
  health: Option[DecisionHealthPort]
) extends DecisionProvider[IO]:

  def id: String = DecisionProvider.TypesafeJev

  def predict(req: DecisionRequest): IO[Either[DecisionError, DecisionResponse]] =
    val m = req.model.filter(_.nonEmpty).getOrElse(model)
    val body = JevWireCodec.requestBody(req, m).noSpaces
    DecisionHttpTransport
      .post(endpoint, token, body, timeoutMs)
      .map(_.flatMap(JevWireCodec.parseResponse))
      .flatTap(outcome => DecisionHttpTransport.record(health, outcome))

object TypesafeJevProvider:

  /**
   * Build a provider from a resolved settings record.
   *
   * A missing credential is NOT an error at construction time: the caller
   * decides whether an unconfigured provider means "fall back" (fail-open,
   * the adjudicated #3 posture) or "refuse". Returning the provider lets the
   * caller keep one code path and let the call fail with [[DecisionError.Auth]]
   * if it is genuinely attempted without a credential.
   */
  def apply(settings: JevSettings): TypesafeJevProvider =
    new TypesafeJevProvider(
      endpoint = settings.endpoint,
      model = settings.model,
      token = settings.token.getOrElse(""),
      timeoutMs = settings.timeoutMs,
      health = settings.health
    )
