package nebflow.shared

/**
 * Fallback-chain config section — SINGLE SOURCE of "which provider/model does a
 * call try, in which order" (config-single-source batch, author rulings ①③
 * 2026-11-05).
 *
 * Storage: `<dataRoot>/<brand configFileName>` (now `stand.json`) →
 * `llm.fallback` = `{"preferred": "<provider>/<model>", "fallbacks": [...]}` —
 * the SAME shape as an agent's own chain (`agent.json` → `model`), so the panel,
 * the file and the engine all read one vocabulary.
 *
 * Why the section exists at all (事故面): the chain used to live nowhere in the
 * config file — it was assembled from code seeds / per-agent sidecars. The
 * settings panel therefore showed providers that the runtime never used, and a
 * provider "deleted" in the panel could keep being called because nothing in the
 * file said which refs were authoritative. Rule now:
 *
 *   1. `llm.fallback` (when it carries at least one ref) is THE global chain —
 *      [[nebflow.llm.ProviderRegistry.getCandidatesUnfiltered]] reads it first
 *      and only falls back to the Nebula sidecar / seed when it is absent;
 *   2. every ref must resolve against a provider that actually exists in
 *      `llm.providers`. Unresolvable refs are NOT silently skipped — 
 *      [[validate]] returns a readable warning per bad ref and the gateway logs
 *      them at boot ([[logValidation]]);
 *   3. the code-side seeds are at most a FIRST-GENERATION template: once the
 *      section is written into the file it is the single truth.
 */
object FallbackChain:

  /** Config node path of the section (single spelling; panels/docs quote this). */
  final val SectionKey: String = "llm.fallback"

  private val logger = NebflowLogger.forName("nebflow.config.fallback")

  /** Non-empty refs of a chain, `preferred` first, blank/duplicate removed by the caller's needs. */
  def refs(chain: Option[AgentModelConfig]): List[String] =
    chain.toList.flatMap(c => c.preferred.toList ++ c.fallbacks).map(_.trim).filter(_.nonEmpty)

  /** The configured global chain (empty = section absent / carries no ref). */
  def configured(config: NebflowServiceConfig): List[String] = refs(config.llm.fallback)

  /**
   * Readable warnings for a chain section — empty list = healthy.
   *
   * Judgement surface (deliberately small): a ref must be `provider/model`, the
   * provider must exist in `llm.providers`, the provider must not be marked
   * `disabled`, and the model must be declared by that provider. Anything else
   * means "the file promises a candidate the runtime cannot build" — the exact
   * silent-skip class this batch forbids.
   */
  def validate(config: NebflowServiceConfig): List[String] =
    val chainRefs = configured(config)
    if chainRefs.isEmpty then Nil
    else
      val dupes = chainRefs.groupBy(identity).collect { case (r, hits) if hits.size > 1 => r }.toList.sorted
      val perRef = chainRefs.distinct.flatMap { ref =>
        ref.split("/", 2) match
          case Array(providerId, modelId) if providerId.nonEmpty && modelId.nonEmpty =>
            config.llm.providers.get(providerId) match
              case None =>
                List(
                  s"$SectionKey: '$ref' references provider '$providerId' which is not configured " +
                    s"(known: ${config.llm.providers.keys.toList.sorted.mkString(", ")} — the ref will be skipped, not called)"
                )
              case Some(p) if p.disabled.getOrElse(false) =>
                List(s"$SectionKey: '$ref' references provider '$providerId' which is marked disabled:true — it cannot be called")
              case Some(p) if !p.models.exists(_.id == modelId) =>
                List(
                  s"$SectionKey: '$ref' references model '$modelId' which provider '$providerId' does not declare " +
                    s"(declared: ${p.models.map(_.id).sorted.mkString(", ")}) — the ref will be skipped"
                )
              case Some(_) => Nil
          case _ =>
            List(s"$SectionKey: '$ref' is not a 'provider/model' ref")
      }
      perRef ++ dupes.map(r => s"$SectionKey: duplicate ref '$r' (it would be tried twice)")

  /** Log [[validate]] at WARN level (boot face; no-op when healthy). */
  def logValidation(config: NebflowServiceConfig): Unit =
    val warnings = validate(config)
    if warnings.nonEmpty then
      logger.warnSync(
        s"[fallback-chain] ${warnings.size} problem(s) in $SectionKey — the runtime will use only the resolvable refs:"
      )
      warnings.foreach(w => logger.warnSync(s"[fallback-chain] $w"))

  /**
   * Refs that actually resolve to a callable candidate, in chain order (the
   * observability form used by `GET /models/candidates` and the boot log).
   * Mirrors the engine's own resolution: unknown provider / unknown model /
   * disabled provider are dropped (and are exactly what [[validate]] reports).
   */
  def resolvable(config: NebflowServiceConfig): List[(String, String)] =
    configured(config).flatMap { ref =>
      ref.split("/", 2) match
        case Array(providerId, modelId) =>
          config.llm.providers.get(providerId) match
            case Some(p) if !p.disabled.getOrElse(false) && p.models.exists(_.id == modelId) => Some((providerId, modelId))
            case _ => None
        case _ => None
    }
end FallbackChain
