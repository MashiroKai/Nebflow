package nebflow.llm.decision

import nebflow.shared.*

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
   * reference form: the config stores a pointer, never a value).
   *
   * 四修③（2026-10-07 修复令「腿生命周期挂 secrets 在位性」）：`keyRef` 指向的
   * secret 件缺失/空/不可读 ⇒ **本腿自动关闭**（resolve = None，WARN 在案）——
   * 调用方（判定机 jevLeg / JevAllocator）直接走兜底腿，不再构造注定 403 的
   * provider 白烧一次调用。热读口径下补上 secret 件即**自动恢复**（下一次
   * resolve 现读在位性，无需重启）。keyRef 未配置（None/空白）= 不期望凭据的
   * 形态，腿照常开启（既有语义不变）。
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
          val token = readSecretToken(c.keyRef)
          val keyRefExpected = c.keyRef.map(_.trim).exists(_.nonEmpty)
          if keyRefExpected && token.isEmpty then
            // 四修③：凭据在位性不满足 ⇒ 腿自动关闭（细节 WARN 已由
            // readSecretToken 落；此处落「腿级」关闭行——审计可查腿开/腿关）。
            logger.warnSync(
              s"jev.keyRef '${c.keyRef.get.trim}' secret absent — decision leg CLOSED (fallback leg takes over); restoring the secret re-opens the leg on the next call (hot read)"
            )
            None
          else
            Some(
              JevSettings(
                provider = provider,
                endpoint = endpoint,
                model = model,
                token = token,
                choiceHighBasePolicy = c.choiceHighBasePolicy.filter(_.nonEmpty),
                health = health
              )
            )
          end if
        end if
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
end JevSettings
