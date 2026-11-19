package nebflow.shared

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/**
 * Per-agent model configuration.
 *
 * The agent simply declares which models to use — a `preferred` primary and an
 * ordered `fallbacks` list. When the preferred (or any earlier candidate) is
 * unavailable, the system falls through the list in order. No capability
 * matching is performed: the user picks the models, and fallback stays within
 * that user-chosen set.
 *
 * If both fields are empty, the agent follows the Nebula primary chain (its
 * agent.json `model` key, resolved by SchemePolicy; an unconfigured Nebula
 * degrades to the seed chain — the first configured provider's first model).
 * The old nebflow.json `llm.model` global chain is retired (#339): it is
 * tolerated-but-ignored by the decoder and only survives as a seed source.
 */
case class AgentModelConfig(
  preferred: Option[String] = None,
  fallbacks: List[String] = Nil
)

object AgentModelConfig:

  given Decoder[AgentModelConfig] = Decoder.instance { c =>
    for
      preferred <- c.downField("preferred").as[Option[String]]
      fallbacks <- c.downField("fallbacks").as[Option[List[String]]]
    yield AgentModelConfig(preferred, fallbacks.getOrElse(Nil))
  }

  given Encoder[AgentModelConfig] = Encoder.instance { m =>
    Json.obj(
      "preferred" -> m.preferred.asJson,
      "fallbacks" -> m.fallbacks.asJson
    )
  }

  val empty: AgentModelConfig = AgentModelConfig()
end AgentModelConfig
