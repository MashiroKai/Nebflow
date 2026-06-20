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

class ProviderRegistry(
  configRef: Ref[IO, NebflowServiceConfig],
  backend: StreamBackend[IO, Fs2Streams[IO]]
):
  private val adaptersRef: Ref[IO, Map[String, ProviderAdapter[IO]]] = Ref.unsafe(Map.empty)

  private def createAdapter(provider: ProviderConfig): ProviderAdapter[IO] =
    provider.protocol match
      case LlmProtocol.OpenAI => OpenAiAdapter(provider.baseUrl, provider.apiKey, backend)
      case LlmProtocol.Anthropic => AnthropicAdapter(provider.baseUrl, provider.apiKey, backend)

  def getAdapter(providerId: String): IO[ProviderAdapter[IO]] =
    adaptersRef.get.map(_.get(providerId)).flatMap {
      case Some(a) => IO.pure(a)
      case None =>
        configRef.get.flatMap { config =>
          config.llm.providers.get(providerId) match
            case Some(provider) =>
              val adapter = createAdapter(provider)
              adaptersRef.update(_ + (providerId -> adapter)).as(adapter)
            case None =>
              IO.raiseError(new RuntimeException(s"Unknown provider: $providerId"))
        }
    }

  def getCandidates(): IO[List[ModelCandidate]] =
    configRef.get.map { config =>
      val chain = config.llm.model.default :: config.llm.model.fallbacks
      val fromChain = chain.flatMap { ref =>
        // Gracefully skip invalid refs instead of throwing
        try
          val (providerId, modelId) = Config.parseModelRef(ref)
          config.llm.providers.get(providerId) match
            case Some(provider) =>
              val modelConfig = provider.models.find(_.id == modelId)
              val maxTokens = modelConfig.map(_.maxTokens).getOrElse(Defaults.MaxTokens)
              val contextWindow = modelConfig.map(_.contextWindow).getOrElse(Defaults.ContextWindow)
              Some(ModelCandidate(providerId, provider, modelId, maxTokens, contextWindow))
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
              ModelCandidate(providerId, provider, mc.id, mc.maxTokens, mc.contextWindow)
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

  /** List all models with descriptions. Returns (ref, modelId, description) triples. */
  def getAllModelsDetailed(): IO[List[(String, String, Option[String])]] =
    configRef.get.map { config =>
      config.llm.providers.flatMap { case (providerId, provider) =>
        provider.models.map(mc => (s"$providerId/${mc.id}", mc.id, mc.description))
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
          ModelCandidate(providerId, provider, modelId, maxTokens, contextWindow)
        }
      catch case _: Exception => None
    }

  /** Reload: re-read config from disk and clear adapter cache. */
  def reloadConfig(): IO[Unit] =
    for
      newConfig <- IO.blocking {
        try Config.loadServiceConfig()
        catch
          case _: Exception =>
            NebflowServiceConfig(
              llm = ServiceLlmConfig(
                providers = Map.empty,
                model = ModelChainConfig(default = "anthropic/claude-sonnet-4-6")
              )
            )
      }
      _ <- configRef.set(newConfig)
      _ <- adaptersRef.set(Map.empty)
    yield ()
end ProviderRegistry
