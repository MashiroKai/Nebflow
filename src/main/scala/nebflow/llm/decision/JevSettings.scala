package nebflow.llm.decision

import nebflow.shared.{Config, JevConfig, NebflowLogger, PathUtil}

/**
 * Resolved, ready-to-call decision settings (Face A / P1-A2 + A3 consumption).
 *
 * Produced from the top-level `jev` block of `nebflow.json` with the
 * `SearchConfig`-style graceful degrade: `enabled=false`, a missing/empty
 * `keyRef`, or an unresolvable secret all yield `None` — the whole face is
 * skipped and existing users need zero migration.
 *
 * 🔴 Credential discipline: `token` is the ONLY field carrying key material,
 * and it is populated by reading the referenced secret file. It is never
 * logged, never put into a diagnostic, and never rendered into any JSON the
 * caller returns ([[DecisionProviders.resolvedView]] deliberately omits it).
 */
final case class JevSettings(
  provider: String,
  endpoint: String,
  model: String,
  timeoutMs: Long,
  token: Option[String],
  choiceHighBasePolicy: Option[String],
  health: Option[DecisionHealthPort]
)

object JevSettings:

  private val logger = NebflowLogger.forName("nebflow.llm.decision")

  /**
   * Resolve the decision face from the config block.
   *
   * `None` means "the whole feature is off" and is the ZERO-MIGRATION path.
   * Resolution is per call (hot read), matching the `PluginsConfig.enabled`
   * precedent, so toggling the block takes effect without a restart.
   *
   * The `keyRef` is a NAME under `~/.nebflow/secrets/` (the social/daemon
   * reference form: the config stores a pointer, never a value). A keyRef that
   * names an absent file resolves to no token — the provider is still built,
   * and the call fails as [[DecisionError.Auth]] if genuinely attempted.
   */
  def resolve(cfg: Option[JevConfig], health: Option[DecisionHealthPort]): Option[JevSettings] =
    cfg.flatMap { c =>
      if !c.isEnabled then None
      else
        val provider = c.provider.filter(_.nonEmpty).getOrElse(DecisionProvider.TypesafeJev)
        if !DecisionProvider.AllIds.contains(provider) then
          logger.warnSync(s"jev.provider '$provider' is not a known decision provider — decision face skipped")
          None
        else
          val endpoint = c.endpoint
            .filter(_.nonEmpty)
            .getOrElse(
              if provider == DecisionProvider.Laya then DecisionProvider.DefaultLayaEndpoint
              else DecisionProvider.DefaultTypesafeEndpoint
            )
          val model = c.model.filter(_.nonEmpty).getOrElse(DecisionProvider.DefaultModel)
          val timeout = c.timeoutMs.filter(_ > 0).getOrElse(DecisionProvider.DefaultTimeoutMs)
          Some(
            JevSettings(
              provider = provider,
              endpoint = endpoint,
              model = model,
              timeoutMs = timeout,
              token = readSecretToken(c.keyRef),
              choiceHighBasePolicy = c.choiceHighBasePolicy.filter(_.nonEmpty),
              health = health
            )
          )
    }

  /** Read the config block out of the loaded service config (hot read). */
  def fromConfig(health: Option[DecisionHealthPort], configPath: Option[String] = None): Option[JevSettings] =
    val cfg = Config.loadServiceConfig(configPath).jev
    resolve(cfg, health)

  /**
   * Read the named secret's value, if it exists.
   *
   * Failures are non-fatal and credential-free: a missing file, an unreadable
   * file or an empty file all yield `None`, and the log line names the secret
   * NAME only — never the path's content and never the value.
   */
  private[decision] def readSecretToken(keyRef: Option[String]): Option[String] =
    keyRef.filter(_.trim.nonEmpty).flatMap { name =>
      val path = PathUtil.dataRoot / "secrets" / name.trim
      try
        if !os.exists(path) then
          logger.warnSync(s"jev.keyRef '$name' has no secret file under secrets/ — decision calls will fail auth")
          None
        else
          val v = os.read(path).trim
          if v.isEmpty then
            logger.warnSync(s"jev.keyRef '$name' resolves to an empty secret — decision calls will fail auth")
            None
          else Some(v)
      catch
        case e: Exception =>
          logger.warnSync(s"jev.keyRef '$name' is unreadable (${e.getClass.getSimpleName}) — decision face disabled")
          None
    }
