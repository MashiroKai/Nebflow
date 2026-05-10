package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import nebflow.llm.providers.{AnthropicAdapter, OpenAiAdapter}
import nebflow.shared.Defaults
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

case class ModelCandidate(
  providerId: String,
  provider: ProviderConfig,
  model: String,
  maxTokens: Int = Defaults.MaxTokens,
  contextWindow: Int = Defaults.ContextWindow
)

class ProviderRegistry(config: NebflowServiceConfig, backend: StreamBackend[IO, Fs2Streams[IO]]):
  private val adaptersRef: Ref[IO, Map[String, ProviderAdapter[IO]]] = Ref.unsafe(Map.empty)

  private def createAdapter(provider: ProviderConfig): ProviderAdapter[IO] =
    provider.protocol match
      case LlmProtocol.OpenAI => OpenAiAdapter(provider.baseUrl, provider.apiKey, backend)
      case LlmProtocol.Anthropic => AnthropicAdapter(provider.baseUrl, provider.apiKey, backend)

  def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
    adaptersRef.get.map(_.get(providerId)).flatMap {
      case Some(a) => IO.pure(a)
      case None =>
        val provider =
          config.llm.providers.getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        val adapter = createAdapter(provider)
        adaptersRef.update(_ + (providerId -> adapter)).as(adapter)
    }

  def getCandidates(): List[ModelCandidate] =
    val chain = config.llm.model.default :: config.llm.model.fallbacks
    chain.map { ref =>
      val (providerId, modelId) = Config.parseModelRef(ref)
      val provider = config.llm.providers.getOrElse(
        providerId,
        throw new RuntimeException(s"Model ref \"$ref\" points to unknown provider \"$providerId\"")
      )
      val modelConfig = provider.models.find(_.id == modelId)
      val maxTokens = modelConfig.map(_.maxTokens).getOrElse(Defaults.MaxTokens)
      val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
      ModelCandidate(providerId, provider, modelId, maxTokens, contextWindow)
    }

  /** List all available models across all providers. Returns (ref, displayName) pairs. */
  def getAllModels(): List[(String, String)] =
    config.llm.providers.flatMap { case (providerId, provider) =>
      if provider.models.nonEmpty then provider.models.map(mc => (s"$providerId/${mc.id}", mc.id))
      else List.empty
    }.toList

  /** Build a ModelCandidate from a model ref string (e.g. "openai/gpt-4o"). */
  def getCandidateForRef(ref: String): Option[ModelCandidate] =
    val (providerId, modelId) = Config.parseModelRef(ref)
    config.llm.providers.get(providerId).map { provider =>
      val modelConfig = provider.models.find(_.id == modelId)
      val maxTokens = modelConfig.map(_.maxTokens).getOrElse(Defaults.MaxTokens)
      val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
      ModelCandidate(providerId, provider, modelId, maxTokens, contextWindow)
    }
end ProviderRegistry
