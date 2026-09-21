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
  contextWindow: Int = Defaults.ContextWindow,
  vision: Boolean = false,
  capabilities: Set[String] = Set.empty
)

class ProviderRegistry(
  configRef: Ref[IO, NebflowServiceConfig],
  backend: StreamBackend[IO, Fs2Streams[IO]]
):
  private val adaptersRef: Ref[IO, Map[String, ProviderAdapter[IO]]] = Ref.unsafe(Map.empty)

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
              val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
              val (vision, caps) = resolveCapabilities(providerId, modelId, modelConfig)
              Some(ModelCandidate(providerId, provider, modelId, contextWindow, vision, caps))
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
              ModelCandidate(providerId, provider, mc.id, mc.contextWindow, vision, caps)
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
          val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
          val (vision, caps) = resolveCapabilities(providerId, modelId, modelConfig)
          ModelCandidate(providerId, provider, modelId, contextWindow, vision, caps)
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
   *
   * 2026-09-21（作者令 19:16 腿 a）：链尾再追加**储备层**（[[reserveTier]]）——
   * 配置里已存在但 preset 未引用的 provider/model。事故面：候选链恒为 3 条，且
   * 恰是 2026-09-21 同日退化的三条（kimi 5h 额度 / zhipu 5h 上限 / deepseek 传输
   * 层失联）⇒ 17:54 起链上只剩单点。储备层使「有效链深 1」在结构上不可达。
   * 链序语义不变：储备层只是**追加**，不会插到 preferred/fallbacks 之前
   * （REST 的「当前模型」显示取 healthy.head，故主用模型面零改）。
   */
  def getCandidatesForAgent(agentModel: Option[nebflow.shared.AgentModelConfig]): IO[List[ModelCandidate]] =
    val base = agentModel match
      case None | Some(nebflow.shared.AgentModelConfig(None, Nil)) =>
        getCandidates() // no config → global list
      case Some(cfg) =>
        // Build agent chain: [preferred, ...fallbacks]
        val agentChain = cfg.preferred.toList ++ cfg.fallbacks
        for
          resolved <- agentChain.traverse(ref => getCandidateForRef(ref))
          agentCandidates = resolved.flatten
          b <- if agentCandidates.nonEmpty then IO.pure(agentCandidates) else getCandidates()
        yield b
    for
      candidates <- base
      config <- configRef.get
    yield reserveTier(config, candidates)

  /**
   * 储备层（腿 a）：把 `config.llm.providers` 里**已存在但当前链未引用**的
   * provider/model 按确定序（providerId → model id 字典序，非 Map 迭代序）
   * 追加到链尾。
   *
   * 语义边界（逐条申报）：
   *   - 只取配置**实存**项（provider 与 model 都必须已在 `nebflow.json` 里），
   *     绝不凭空造候选；配置不足时链深照实（1 根就是 1 根）；
   *   - 只**追加**、不重排、不去重已引用项之外的东西：与已引用 ref 相同的项被剔除；
   *   - 字典序保证同配置下链序可复现（Map 迭代序不保证），便于取证与断言；
   *   - 与 [[filterKnownProviders]] 正交（储备层全部来自当前 config，必然存活）。
   */
  private[llm] def reserveTier(
    config: NebflowServiceConfig,
    chain: List[ModelCandidate]
  ): List[ModelCandidate] =
    if config.llm.providers.isEmpty then chain
    else
      val referred = chain.map(c => s"${c.providerId}/${c.model}").toSet
      val reserve = config.llm.providers.toList.sortBy(_._1).flatMap { case (providerId, provider) =>
        provider.models
          .sortBy(_.id)
          .filterNot(mc => referred.contains(s"$providerId/${mc.id}"))
          .map { mc =>
            val (vision, caps) = resolveCapabilities(providerId, mc.id, Some(mc))
            ModelCandidate(providerId, provider, mc.id, mc.contextWindow, vision, caps)
          }
      }
      // 同 (providerId, model) 只保留一次（配置里重复声明 model 时也不得重复进链）
      chain ++ reserve.distinctBy(c => s"${c.providerId}/${c.model}")
  end reserveTier

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
   * Reload: re-read config from disk, clear adapter caches and drop
   * per-session model overrides whose provider no longer exists (#33).
   *
   * The in-memory session overrides are `ModelCandidate` snapshots built from
   * the OLD config — after a hot-reload removes a provider, a stale override
   * would raise "Unknown provider" at the adapter on the next request.
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
   * adapter. The request chain simply continues with the remaining
   * candidates — reload-time invalidation ([[reloadConfig]]) is the primary
   * fix; this is the request-time safety net for any stale entry that slips
   * through (e.g. a race between setSessionModel and a concurrent reload).
   */
  def filterKnownProviders(candidates: List[ModelCandidate]): IO[List[ModelCandidate]] =
    configRef.get.map(cfg => candidates.filter(c => cfg.llm.providers.contains(c.providerId)))
end ProviderRegistry
