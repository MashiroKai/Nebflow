package nebflow.agent

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/**
 * Per-agent model configuration.
 *
 * - `preferred`: model ref (e.g. "zhipu/GLM-5.2") to use as primary candidate
 * - `capabilities`: required capability tags (e.g. ["vision"]) for filtering
 * - `fallbackPolicy`: how to handle fallback
 *   - "prefer-capable" (default): prefer models with matching capabilities, but allow others
 *   - "strict": only use models with matching capabilities
 *   - "any": ignore capabilities, use global fallback chain
 */
case class AgentModelConfig(
  preferred: Option[String] = None,
  capabilities: List[String] = Nil,
  fallbackPolicy: String = "prefer-capable"
)

object AgentModelConfig:
  given Decoder[AgentModelConfig] = Decoder.instance { c =>
    for
      preferred <- c.downField("preferred").as[Option[String]]
      caps <- c.downField("capabilities").as[Option[List[String]]]
      policy <- c.downField("fallbackPolicy").as[Option[String]]
    yield AgentModelConfig(preferred, caps.getOrElse(Nil), policy.getOrElse("prefer-capable"))
  }

  given Encoder[AgentModelConfig] = Encoder.instance { m =>
    Json.obj(
      "preferred" -> m.preferred.asJson,
      "capabilities" -> m.capabilities.asJson,
      "fallbackPolicy" -> m.fallbackPolicy.asJson
    )
  }

  val empty: AgentModelConfig = AgentModelConfig()

/**
 * Suggests model capabilities for an agent role.
 * Used for auto-configuration when creating new agents/teams.
 */
object ModelRoleMatcher:

  private val roleCapabilities: Map[String, List[String]] = Map(
    "Manager" -> List("reasoning"),
    "Backend" -> List("code"),
    "Frontend" -> List("vision", "code"),
    "Docs" -> List("fast"),
    "qa-frontend" -> List("vision"),
    "qa-backend" -> List("code"),
    "prompt-engineer" -> List("reasoning"),
    "tool-engineer" -> List("code")
  )

  /** Get required capabilities for a role name. Returns empty list for unknown roles. */
  def capabilitiesForRole(role: String): List[String] =
    roleCapabilities.getOrElse(role, Nil)

  /**
   * Suggest a model configuration for a given role.
   * Returns AgentModelConfig with required capabilities.
   */
  def suggestForRole(role: String): AgentModelConfig =
    val caps = capabilitiesForRole(role)
    if caps.nonEmpty then
      AgentModelConfig(capabilities = caps, fallbackPolicy = "prefer-capable")
    else
      AgentModelConfig.empty

end ModelRoleMatcher
