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
  fallbacks: List[String] = Nil,
  capabilities: List[String] = Nil,
  fallbackPolicy: String = "prefer-capable"
)

object AgentModelConfig:
  given Decoder[AgentModelConfig] = Decoder.instance { c =>
    for
      preferred <- c.downField("preferred").as[Option[String]]
      fallbacks <- c.downField("fallbacks").as[Option[List[String]]]
      caps <- c.downField("capabilities").as[Option[List[String]]]
      policy <- c.downField("fallbackPolicy").as[Option[String]]
    yield AgentModelConfig(preferred, fallbacks.getOrElse(Nil), caps.getOrElse(Nil), policy.getOrElse("prefer-capable"))
  }

  given Encoder[AgentModelConfig] = Encoder.instance { m =>
    Json.obj(
      "preferred" -> m.preferred.asJson,
      "fallbacks" -> m.fallbacks.asJson,
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

  /** Only Frontend needs vision capability; all other roles have no special requirements. */
  private val visionRoles: Set[String] = Set("Frontend", "qa-frontend")

  /** Get required capabilities for a role name. Returns empty list for unknown roles. */
  def capabilitiesForRole(role: String): List[String] =
    if visionRoles.contains(role) then List("vision") else Nil

  /**
   * Suggest a model configuration for a given role.
   * Frontend/qa-frontend → vision-capable model preferred.
   * All other roles → no special capability requirements.
   */
  def suggestForRole(role: String): AgentModelConfig =
    val caps = capabilitiesForRole(role)
    if caps.nonEmpty then
      AgentModelConfig(capabilities = caps, fallbackPolicy = "prefer-capable")
    else
      AgentModelConfig.empty

end ModelRoleMatcher
