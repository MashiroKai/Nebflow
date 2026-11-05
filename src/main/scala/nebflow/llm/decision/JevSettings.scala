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
   *
   * 多 provider（jev-imagegen-align-llm 批 2026-11-02）：顶层 `provider` 是
   * 「当前选中项指针」，`jev.providers.<name>` 是该选中项的字段表。取值口径 =
   * **逐字段三级回落** `providers[选中项].X > 顶层 X > 缺省`——`providers` 缺席
   * 或选中项不在表内即退化为本批之前的纯顶层读法（零迁移、逐字不变）。
   */
  def resolve(cfg: Option[JevConfig], health: Option[DecisionHealthPort]): Option[JevSettings] =
    cfg.flatMap { c =>
      if !c.isEnabled then None
      else
        // ── 选中项解析（jev-imagegen-align-llm 批 2026-11-02）──────────────
        // `jev.providers`（可缺席的新事实源）一旦存在，选中项（顶层 `provider`
        // 指针所指那一条）逐字段优先；缺席/该字段缺位时回落顶层 5 键，再回落
        // 缺省。三级回落 = providers[name].X > c.X > 缺省 —— 旧配置（无
        // providers）走第二、三级，行为逐字不变（零迁移）。
        val entry: Option[nebflow.shared.JevProviderConfig] =
          for
            name <- c.provider.map(_.trim).filter(_.nonEmpty)
            provs <- c.providers
            e <- provs.get(name)
          yield e
        /** 三级回落取值器：选中项字段 > 顶层字段 > None（缺省由调用点给）。
          * 判据与顶层旧读法逐字同源（`filter(_.nonEmpty)`，不改写值本身）。 */
        def pick(fromEntry: nebflow.shared.JevProviderConfig => Option[String], top: Option[String]): Option[String] =
          entry.flatMap(fromEntry).filter(_.nonEmpty).orElse(top.filter(_.nonEmpty))
        val provider = pick(e => Some(e.`type`), c.provider).getOrElse(DecisionProvider.TypesafeJev)
        if !DecisionProvider.AllIds.contains(provider) then
          logger.warnSync(s"jev.provider '$provider' is not a known decision provider — decision face skipped")
          None
        else
          val endpoint = pick(_.endpoint, c.endpoint)
            .getOrElse(
              if provider == DecisionProvider.Laya then DecisionProvider.DefaultLayaEndpoint
              else DecisionProvider.DefaultTypesafeEndpoint
            )
          val model = pick(_.model, c.model).getOrElse(DecisionProvider.DefaultModel)
          val keyRef = pick(_.keyRef, c.keyRef)
          val token = readSecretToken(keyRef)
          val keyRefExpected = keyRef.exists(_.trim.nonEmpty)
          if keyRefExpected && token.isEmpty then
            // 四修③：凭据在位性不满足 ⇒ 腿自动关闭（细节 WARN 已由
            // readSecretToken 落；此处落「腿级」关闭行——审计可查腿开/腿关）。
            logger.warnSync(
              s"jev.keyRef '${keyRef.get.trim}' secret absent — decision leg CLOSED (fallback leg takes over); restoring the secret re-opens the leg on the next call (hot read)"
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
