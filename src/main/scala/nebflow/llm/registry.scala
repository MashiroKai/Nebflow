package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.traverse.*
import nebflow.llm.providers.{AnthropicAdapter, OpenAiAdapter}
import nebflow.shared.Defaults
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

case class ModelCandidate(
  providerId: String,
  provider: ProviderConfig,
  model: String,
  maxTokens: Int = Defaults.MaxTokens,
  contextWindow: Int = Defaults.ContextWindow,
  vision: Boolean = false,
  capabilities: Set[String] = Set.empty
)

class ProviderRegistry(
  configRef: Ref[IO, NebflowServiceConfig],
  backend: StreamBackend[IO, Fs2Streams[IO]]
):
  private val adaptersRef: Ref[IO, Map[String, ProviderAdapter[IO]]] = Ref.unsafe(Map.empty)
  // P0 API 并发管理: per-provider concurrency gates. Built lazily on first
  // use; cleared on reloadConfig() so config changes take effect immediately.
  private val gatesRef: Ref[IO, Map[String, ConcurrencyGate]] = Ref.unsafe(Map.empty)

  def getGate(providerId: String): IO[ConcurrencyGate] =
    gatesRef.get.map(_.get(providerId).map(IO.pure).getOrElse {
      configRef.get.flatMap { config =>
        config.llm.providers.get(providerId) match
          case Some(provider) =>
            val gate = ConcurrencyGate.fromProvider(providerId, provider)
            // gate-wedge: atomic get-or-create — the old get-then-update let
            // concurrent first-use callers each build their OWN gate (one
            // permit set each), so the concurrency limit was not enforced at
            // all during the race window (three concurrent requests → three
            // gates → zero queueing). Publish via modify; racing losers adopt
            // the winner instead of keeping their private instance.
            gatesRef.modify { m =>
              m.get(providerId) match
                case Some(existing) => (m, existing)
                case None           => (m + (providerId -> gate), gate)
            }
          case None =>
            IO.raiseError(new RuntimeException(s"Unknown provider: $providerId"))
        }
    }).flatten

  private def createAdapter(providerId: String, provider: ProviderConfig): ProviderAdapter[IO] =
    provider.protocol match
      case LlmProtocol.OpenAI => OpenAiAdapter(provider.baseUrl, provider.apiKey, backend)
      case LlmProtocol.Anthropic =>
        AnthropicAdapter(
          provider.baseUrl,
          provider.apiKey,
          backend,
          // DeepSeek's thinking mode rejects history replay that omits unsigned
          // thinking blocks (400 invalid_request → provider marked DOWN); real
          // Anthropic is the opposite. Explicit config overrides the default.
          requireThinkingPassback = provider.requireThinkingPassback.getOrElse(providerId == "deepseek")
        )

  def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
    adaptersRef.get.map(_.get(providerId)).flatMap {
      case Some(a) => IO.pure(a)
      case None =>
        configRef.get.flatMap { config =>
          config.llm.providers.get(providerId) match
            case Some(provider) =>
              val adapter = createAdapter(providerId, provider)
              adaptersRef.update(_ + (providerId -> adapter)).as(adapter)
            case None =>
              IO.raiseError(new RuntimeException(s"Unknown provider: $providerId"))
        }
    }

  def getCandidates(): IO[List[ModelCandidate]] =
    configRef.get.map { config =>
      // #339：全局链来源 = 默认 preset（llm.model 已退役）。resolve(None,None)
      // 走 terminal 第 3 级；preset 文件小、每次读新（与 PresetStore 设计一致）。
      // onboarding 探针 probeLlm（不带 agentModel）自动跟随默认 preset——首配
      // 后探针测的正是刚配置的模型。
      val chain =
        try
          val (am, _) = nebflow.core.presets.PresetStore().resolve(None, None)
          am.preferred.toList ++ am.fallbacks
        catch case _: Exception => Nil
      val fromChain = chain.flatMap { ref =>
        // Gracefully skip invalid refs instead of throwing
        try
          val (providerId, modelId) = Config.parseModelRef(ref)
          config.llm.providers.get(providerId) match
            case Some(provider) =>
              val modelConfig = provider.models.find(_.id == modelId)
              val maxTokens = modelConfig.map(_.maxTokens).getOrElse(Defaults.MaxTokens)
              val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
              val (vision, caps) = resolveCapabilities(providerId, modelId, modelConfig)
              Some(ModelCandidate(providerId, provider, modelId, maxTokens, contextWindow, vision, caps))
            case None => None // Skip unknown provider
        catch case _: Exception => None // Skip malformed ref
      }
      // Fallback: if model chain resolves to nothing (e.g. default points to a
      // non-existent provider after initial setup), use the first available model
      // across all providers so the system works out of the box.
      if fromChain.nonEmpty then fromChain
      else
        config.llm.providers.headOption
          .map { case (providerId, provider) =>
            provider.models.headOption.map { mc =>
              val (vision, caps) = resolveCapabilities(providerId, mc.id, Some(mc))
              ModelCandidate(providerId, provider, mc.id, mc.maxTokens, mc.contextWindow, vision, caps)
            }
          }
          .flatten
          .toList
    }

  /** List all available models across all providers. Returns (ref, displayName) pairs. */
  def getAllModels(): IO[List[(String, String)]] =
    configRef.get.map { config =>
      config.llm.providers.flatMap { case (providerId, provider) =>
        if provider.models.nonEmpty then provider.models.map(mc => (s"$providerId/${mc.id}", mc.id))
        else List.empty
      }.toList
    }

  /**
   * List all models with descriptions. Returns (ref, displayLabel, description) triples.
   * displayLabel includes the provider name to ensure uniqueness when multiple
   * providers offer the same model ID (e.g. two providers both have model-x).
   */
  def getAllModelsDetailed(): IO[List[(String, String, Option[String])]] =
    configRef.get.map { config =>
      config.llm.providers.flatMap { case (providerId, provider) =>
        provider.models.map(mc => (s"$providerId/${mc.id}", s"$providerId / ${mc.id}", mc.description))
      }.toList
    }

  /** Build a ModelCandidate from a model ref string (e.g. "openai/gpt-4o"). Returns None gracefully. */
  def getCandidateForRef(ref: String): IO[Option[ModelCandidate]] =
    configRef.get.map { config =>
      try
        val (providerId, modelId) = Config.parseModelRef(ref)
        config.llm.providers.get(providerId).map { provider =>
          val modelConfig = provider.models.find(_.id == modelId)
          val maxTokens = modelConfig.map(_.maxTokens).getOrElse(Defaults.MaxTokens)
          val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
          val (vision, caps) = resolveCapabilities(providerId, modelId, modelConfig)
          ModelCandidate(providerId, provider, modelId, maxTokens, contextWindow, vision, caps)
        }
      catch case _: Exception => None
    }

  /**
   * Get candidate list customized for an agent's model configuration.
   *
   * The agent declares a `preferred` primary and an ordered `fallbacks` list.
   * We build the candidate chain as `[preferred, ...fallbacks]`, resolving each
   * ref gracefully (invalid/unknown refs are skipped). If every ref fails to
   * resolve, we fall back to the global candidate list so the agent still has
   * something to use. No capability filtering is performed — the user picks the
   * models, and fallback stays within that user-chosen set.
   */
  def getCandidatesForAgent(agentModel: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
    agentModel match
      case None | Some(nebflow.shared.AgentModelConfig(None, Nil)) =>
        getCandidates() // no config → global list
      case Some(cfg) =>
        // Build agent chain: [preferred, ...fallbacks]
        val agentChain = cfg.preferred.toList ++ cfg.fallbacks
        for
          resolved <- agentChain.traverse(ref => getCandidateForRef(ref))
          agentCandidates = resolved.flatten
          base <- if agentCandidates.nonEmpty then IO.pure(agentCandidates) else getCandidates()
        yield base

  /**
   * Resolve vision + capabilities for a model.
   * Priority: ModelConfig inline fields > ModelRegistry (models.json) >
   * optimistic default (B3 Phase 1: unannotated models resolve vision=true —
   * a wrong strip is visible and self-corrects via runtime detection, while
   * a wrong pessimistic default silently degrades every image request).
   */
  private def resolveCapabilities(
    providerId: String,
    modelId: String,
    modelConfig: Option[ModelConfig]
  ): (Boolean, Set[String]) =
    val registryEntry = ModelRegistry.lookup(providerId, modelId)
    val vision = modelConfig
      .flatMap(_.vision)
      .orElse(registryEntry.flatMap(_.vision))
      .getOrElse(true)
    val caps = modelConfig
      .flatMap(_.capabilities)
      .orElse(registryEntry.map(_.capabilities))
      .getOrElse(Nil)
      .toSet
    (vision, caps)

  end resolveCapabilities

  /**
   * Reload: re-read config from disk, clear adapter/gate caches and drop
   * per-session model overrides whose provider no longer exists (#33).
   *
   * The in-memory session overrides are `ModelCandidate` snapshots built from
   * the OLD config — after a hot-reload removes a provider, a stale override
   * would raise "Unknown provider" at the adapter/gate on the next request.
   * Dropping it here makes the session follow the new global chain (same
   * semantics as `clearAllSessionModels` at startup — a reload is a soft
   * restart of the model config). Returns the dropped session ids so callers
   * can also clear the persisted session meta (`modelRef`) for UI consistency.
   */
  def reloadConfig(
    overridesRef: Option[Ref[IO, Map[String, ModelCandidate]]] = None
  ): IO[List[String]] =
    for
      newConfig <- IO.blocking {
        try Config.loadServiceConfig()
        catch
          case _: Exception =>
            NebflowServiceConfig(
              llm = ServiceLlmConfig(
                providers = Map.empty
                // #339：占位 llm.model 已删（字段退役）
              )
            )
      }
      _ <- configRef.set(newConfig)
      _ <- adaptersRef.set(Map.empty)
      // Gates derive their params from ProviderConfig — rebuild on reload so
      // maxConcurrency/rpm changes apply without restart (design §4.3).
      _ <- gatesRef.set(Map.empty)
      staleIds <- overridesRef match
        case Some(ref) =>
          ref.modify { overrides =>
            val (keep, drop) = overrides.partition { case (_, c) => newConfig.llm.providers.contains(c.providerId) }
            (keep, drop.keys.toList)
          }
        case None => IO.pure(Nil)
    yield staleIds

  /**
   * Graceful skip for candidates whose provider is not present in the current
   * config (#33). A stale session-model override (set before a hot-reload
   * removed the provider) would otherwise surface "Unknown provider" at the
   * adapter/gate. The request chain simply continues with the remaining
   * candidates — reload-time invalidation ([[reloadConfig]]) is the primary
   * fix; this is the request-time safety net for any stale entry that slips
   * through (e.g. a race between setSessionModel and a concurrent reload).
   */
  def filterKnownProviders(candidates: List[ModelCandidate]): IO[List[ModelCandidate]] =
    configRef.get.map(cfg => candidates.filter(c => cfg.llm.providers.contains(c.providerId)))
end ProviderRegistry
