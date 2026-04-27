package nebflow.llm

import nebflow.llm.providers.{AnthropicAdapter, OpenAiAdapter}
import cats.effect.IO

case class ModelCandidate(
  providerId: String,
  provider: ProviderConfig,
  model: String
)

class ProviderRegistry(config: NebflowServiceConfig):
  private var adapters: Map[String, ProviderAdapter[IO]] = Map.empty

  private def createAdapter(provider: ProviderConfig): ProviderAdapter[IO] =
    provider.protocol match
      case LlmProtocol.OpenAI => OpenAiAdapter(provider.baseUrl, provider.apiKey)
      case LlmProtocol.Anthropic => AnthropicAdapter(provider.baseUrl, provider.apiKey)

  def getAdapter(providerId: String): ProviderAdapter[IO] =
    adapters.get(providerId) match
      case Some(a) => a
      case None =>
        val provider = config.llm.providers.getOrElse(providerId,
          throw new RuntimeException(s"Unknown provider: $providerId"))
        val adapter = createAdapter(provider)
        adapters = adapters + (providerId -> adapter)
        adapter

  def getCandidates(): List[ModelCandidate] =
    val chain = config.llm.model.primary :: config.llm.model.fallbacks
    chain.map { ref =>
      val (providerId, modelId) = Config.parseModelRef(ref)
      val provider = config.llm.providers.getOrElse(providerId,
        throw new RuntimeException(s"Model ref \"$ref\" points to unknown provider \"$providerId\""))
      ModelCandidate(providerId, provider, modelId)
    }
