package nebflow.llm.decision

import cats.effect.IO

/**
 * Decision provider factory (Face A / P1-A1).
 *
 * One place turns resolved [[JevSettings]] into a live provider, so the
 * P2 gate and the P3 allocation point both obtain a provider the same way and
 * neither needs to know the concrete class.
 */
object DecisionProviders:

  /**
   * Build the provider named by the settings.
   *
   * Returns `None` when the provider id is unknown — the caller treats that
   * exactly like "unconfigured" and falls through to the OFF path (fail-open,
   * the adjudicated #3 posture: a JeV failure must never wedge dispatch).
   */
  def build(settings: JevSettings): Option[DecisionProvider[IO]] =
    settings.provider match
      case DecisionProvider.TypesafeJev => Some(TypesafeJevProvider(settings))
      case DecisionProvider.Laya => Some(LayaProvider(settings))
      case _ => None

  /**
   * Resolve settings and build in one step.
   *
   * `None` = the whole face is off (unconfigured block / toggle off / unknown
   * provider) and the caller must take the byte-identical legacy path.
   */
  def resolve(health: Option[DecisionHealthPort], configPath: Option[String] = None): Option[DecisionProvider[IO]] =
    JevSettings.fromConfig(health, configPath).flatMap(build)

  /**
   * Credential-free view of the resolved settings, for diagnostics and
   * visibility events.
   *
   * 🔴 `token` is deliberately ABSENT from this projection — it is the one
   * field carrying key material, and no diagnostic (log line, event payload,
   * error message) may carry it. The projection exists so "log the settings"
   * cannot accidentally log the credential.
   */
  def resolvedView(settings: JevSettings): Map[String, String] =
    Map(
      "provider" -> settings.provider,
      "endpoint" -> settings.endpoint,
      "model" -> settings.model,
      "timeoutMs" -> settings.timeoutMs.toString,
      "keyRefResolved" -> settings.token.isDefined.toString,
      "choiceHighBasePolicy" -> settings.choiceHighBasePolicy.getOrElse("-")
    )
