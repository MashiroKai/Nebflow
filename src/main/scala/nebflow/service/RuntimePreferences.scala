package nebflow.service

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{ApprovalDecision, PermissionPolicy}

// ============================================================
// Thinking Configuration
// ============================================================

case class ThinkingConfig(
  enabled: Boolean = false,
  budgetTokens: Int = 16000
)

object ThinkingConfig:

  given Encoder[ThinkingConfig] = Encoder.instance { tc =>
    Json.obj(
      "enabled" -> tc.enabled.asJson,
      "budgetTokens" -> tc.budgetTokens.asJson
    )
  }

  given Decoder[ThinkingConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
      budgetTokens <- c.downField("budgetTokens").as[Option[Int]].map(_.getOrElse(16000))
    yield ThinkingConfig(enabled, budgetTokens)
  }

  def fromJson(json: Json): Either[String, ThinkingConfig] =
    decode[ThinkingConfig](json.noSpaces).leftMap(_.getMessage)

  def toJson(tc: ThinkingConfig): Json = tc.asJson

  /** Convert to the raw JSON shape expected by LLM adapters (e.g. Anthropic extended thinking). */
  def toLlmJson(tc: ThinkingConfig): Json =
    Json.obj(
      "type" -> "enabled".asJson,
      "budget_tokens" -> tc.budgetTokens.asJson
    )

end ThinkingConfig

// ============================================================
// Runtime Preferences (unified persistent state)
// ============================================================

case class RuntimePreferences(
  permissionPolicy: PermissionPolicy = PermissionPolicy.default,
  thinkingConfig: Option[ThinkingConfig] = None
)

object RuntimePreferences:

  given Encoder[RuntimePreferences] = Encoder.instance { rp =>
    Json.obj(
      "permissionPolicy" -> rp.permissionPolicy.asJson,
      "thinkingConfig" -> rp.thinkingConfig.asJson
    )
  }

  given Decoder[RuntimePreferences] = Decoder.instance { c =>
    for
      policy <- c.downField("permissionPolicy").as[Option[PermissionPolicy]].map(_.getOrElse(PermissionPolicy.default))
      thinking <- c.downField("thinkingConfig").as[Option[ThinkingConfig]]
    yield RuntimePreferences(policy, thinking)
  }

  val default: RuntimePreferences = RuntimePreferences()

end RuntimePreferences

// ============================================================
// Runtime Preferences Service
// ============================================================

class RuntimePreferencesService private (
  stateRef: Ref[IO, RuntimePreferences]
):
  def getAll: IO[RuntimePreferences] = stateRef.get

  def getPolicy: IO[PermissionPolicy] = stateRef.get.map(_.permissionPolicy)

  def setPolicy(policy: PermissionPolicy): IO[Unit] =
    stateRef.update(_.copy(permissionPolicy = policy)) *>
      RuntimePreferencesService.save(stateRef)

  def getThinking: IO[Option[ThinkingConfig]] = stateRef.get.map(_.thinkingConfig)

  def setThinking(tc: Option[ThinkingConfig]): IO[Unit] =
    stateRef.update(_.copy(thinkingConfig = tc)) *>
      RuntimePreferencesService.save(stateRef)

  def shouldApprove(toolName: String): IO[ApprovalDecision] =
    for p <- getPolicy
    yield
      if p.autoApproveAll then ApprovalDecision.Approved
      else if p.blockedTools.contains(toolName) then ApprovalDecision.Blocked(s"$toolName is blocked by policy")
      else if p.autoApproveTools.contains(toolName) then ApprovalDecision.Approved
      else ApprovalDecision.NeedsUserApproval

end RuntimePreferencesService

object RuntimePreferencesService:
  private val preferencesPath: os.Path = os.home / ".nebflow" / "preferences.json"
  private val legacyPolicyPath: os.Path = os.home / ".nebflow" / "permission_policy.json"

  private def loadPreferences: RuntimePreferences =
    try
      if os.exists(preferencesPath) then
        decode[RuntimePreferences](os.read(preferencesPath)).getOrElse(RuntimePreferences.default)
      else if os.exists(legacyPolicyPath) then
        // Migrate from legacy permission_policy.json
        val legacyPolicy = decode[PermissionPolicy](os.read(legacyPolicyPath)).getOrElse(PermissionPolicy.default)
        RuntimePreferences(permissionPolicy = legacyPolicy)
      else RuntimePreferences.default
    catch case _: Exception => RuntimePreferences.default

  private def save(stateRef: Ref[IO, RuntimePreferences]): IO[Unit] =
    stateRef.get.flatMap { rp =>
      IO.blocking {
        try os.write.over(preferencesPath, rp.asJson.spaces2, createFolders = true)
        catch case _: Exception => ()
      }
    }

  def create: IO[RuntimePreferencesService] =
    for
      saved <- IO.blocking(loadPreferences)
      stateRef <- Ref.of[IO, RuntimePreferences](saved)
    yield new RuntimePreferencesService(stateRef)

end RuntimePreferencesService
