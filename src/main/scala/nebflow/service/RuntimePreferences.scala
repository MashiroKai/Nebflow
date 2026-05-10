package nebflow.service

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{ApprovalDecision, PermissionPolicy}
import nebflow.shared.{MtimeCache, MtimeFileCache}

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
  thinkingConfig: Option[ThinkingConfig] = None,
  language: Option[String] = None,
  disabledMcpServers: Set[String] = Set.empty
)

object RuntimePreferences:

  given Encoder[RuntimePreferences] = Encoder.instance { rp =>
    Json.obj(
      "permissionPolicy" -> rp.permissionPolicy.asJson,
      "thinkingConfig" -> rp.thinkingConfig.asJson,
      "language" -> rp.language.asJson,
      "disabledMcpServers" -> rp.disabledMcpServers.asJson
    )
  }

  given Decoder[RuntimePreferences] = Decoder.instance { c =>
    for
      policy <- c.downField("permissionPolicy").as[Option[PermissionPolicy]].map(_.getOrElse(PermissionPolicy.default))
      thinking <- c.downField("thinkingConfig").as[Option[ThinkingConfig]]
      language <- c.downField("language").as[Option[String]]
      disabledMcp <- c.downField("disabledMcpServers").as[Option[Set[String]]].map(_.getOrElse(Set.empty))
    yield RuntimePreferences(policy, thinking, language, disabledMcp)
  }

  val default: RuntimePreferences = RuntimePreferences()

end RuntimePreferences

// ============================================================
// Runtime Preferences Service (mtime-cached)
// ============================================================

/**
 * Manages runtime preferences with mtime-based caching.
 * The preferences file is re-read only when its modification time changes.
 * Writes immediately persist to disk and invalidate the cache.
 */
class RuntimePreferencesService private (
  private val fileCache: MtimeFileCache[RuntimePreferences]
):

  private def load: IO[RuntimePreferences] =
    fileCache.get.map(_.getOrElse(RuntimePreferences.default))

  def getAll: IO[RuntimePreferences] = load

  def getPolicy: IO[PermissionPolicy] = load.map(_.permissionPolicy)

  def setPolicy(policy: PermissionPolicy): IO[Unit] =
    load.flatMap { current =>
      RuntimePreferencesService.save(current.copy(permissionPolicy = policy))
    } *> fileCache.invalidate

  def getThinking: IO[Option[ThinkingConfig]] = load.map(_.thinkingConfig)

  def setThinking(tc: Option[ThinkingConfig]): IO[Unit] =
    load.flatMap { current =>
      RuntimePreferencesService.save(current.copy(thinkingConfig = tc))
    } *> fileCache.invalidate

  def getLanguage: IO[Option[String]] = load.map(_.language)

  def setLanguage(lang: Option[String]): IO[Unit] =
    load.flatMap { current =>
      RuntimePreferencesService.save(current.copy(language = lang))
    } *> fileCache.invalidate

  def getDisabledMcpServers: IO[Set[String]] = load.map(_.disabledMcpServers)

  def setMcpServerEnabled(serverId: String, enabled: Boolean): IO[Unit] =
    load.flatMap { current =>
      val updated = if enabled then current.disabledMcpServers - serverId else current.disabledMcpServers + serverId
      RuntimePreferencesService.save(current.copy(disabledMcpServers = updated))
    } *> fileCache.invalidate

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

  private def parsePreferences(content: String): RuntimePreferences =
    decode[RuntimePreferences](content).getOrElse(RuntimePreferences.default)

  private def save(rp: RuntimePreferences): IO[Unit] =
    IO.blocking {
      try os.write.over(preferencesPath, rp.asJson.spaces2, createFolders = true)
      catch case _: Exception => ()
    }

  def create: IO[RuntimePreferencesService] =
    // Handle legacy migration: if preferences.json doesn't exist but legacy does, migrate once
    IO.blocking {
      if !os.exists(preferencesPath) && os.exists(legacyPolicyPath) then
        val legacyPolicy = decode[PermissionPolicy](os.read(legacyPolicyPath)).getOrElse(PermissionPolicy.default)
        os.write.over(
          preferencesPath,
          RuntimePreferences(permissionPolicy = legacyPolicy).asJson.spaces2,
          createFolders = true
        )
    }.map { _ =>
      new RuntimePreferencesService(
        MtimeCache.file[RuntimePreferences](preferencesPath, parsePreferences)
      )
    }

end RuntimePreferencesService
