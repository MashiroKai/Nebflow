package nebflow.llm

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import nebflow.core.PathUtil
import nebflow.shared.Defaults

enum LlmProtocol:
  case Anthropic, OpenAI

  def name: String = this match
    case Anthropic => "anthropic"
    case OpenAI => "openai"

object LlmProtocol:

  given Decoder[LlmProtocol] = Decoder.decodeString.emap {
    case "anthropic" => Right(Anthropic)
    case "openai" => Right(OpenAI)
    case other => Left(s"Unknown protocol: $other")
  }

case class ModelConfig(
  id: String,
  maxTokens: Int = Defaults.MaxTokens,
  contextWindow: Int = Defaults.ContextWindow,
  description: Option[String] = None,
  vision: Option[Boolean] = None,
  capabilities: Option[List[String]] = None
)

object ModelConfig:
  given Decoder[ModelConfig] = deriveDecoder[ModelConfig]

case class ProviderConfig(
  baseUrl: String,
  apiKey: String,
  protocol: LlmProtocol,
  models: List[ModelConfig] = Nil,
  // Anthropic-protocol only: replay unsigned thinking blocks from assistant
  // history back to the API. DeepSeek's Anthropic-compatible endpoint REQUIRES
  // thinking blocks to be passed back in thinking mode (even without a
  // signature), while real Anthropic REJECTS them without one. Defaults per
  // providerId in ProviderRegistry; explicit config wins.
  requireThinkingPassback: Option[Boolean] = None,
  // P0 API 并发管理（2026-08-18）：per-provider concurrency gate. All three
  // default to None = use Defaults (see gate.scala) — explicit `0` on
  // maxConcurrency means unlimited. deriveDecoder requires defaults for
  // backward compatibility with existing config files.
  maxConcurrency: Option[Int] = None,
  rpm: Option[Int] = None,
  queueTimeoutMs: Option[Int] = None,
  // P0 并发管理阶段 2（2026-08-18）：queued 请求落盘，重启不丢。默认开；
  // 关闭则排队项仅存内存（重启丢失）。
  queuePersist: Option[Boolean] = None
)

object ProviderConfig:
  given Decoder[ProviderConfig] = deriveDecoder[ProviderConfig]

case class ModelChainConfig(
  default: String,
  fallbacks: List[String] = Nil
)

object ModelChainConfig:

  given Decoder[ModelChainConfig] = Decoder.instance { c =>
    val defaultOpt = c.downField("default").as[Option[String]]
    val primaryOpt = c.downField("primary").as[Option[String]]
    val default = (defaultOpt, primaryOpt) match
      case (Right(Some(d)), _) => Right(d)
      case (Right(None), Right(Some(p))) => Right(p)
      case (Right(None), Right(None)) =>
        Left(io.circe.DecodingFailure("Missing field 'default' (or legacy 'primary')", c.history))
      case (Left(err), _) => Left(err)
      case (_, Left(err)) => Left(err)
    for
      d <- default
      fallbacks <- c.downField("fallbacks").as[Option[List[String]]].map(_.getOrElse(Nil))
    yield ModelChainConfig(d, fallbacks)
  }

end ModelChainConfig

case class McpServerConfig(
  command: Option[String] = None,
  args: Option[List[String]] = None,
  env: Option[Map[String, String]] = None,
  url: Option[String] = None,
  headers: Option[Map[String, String]] = None,
  enabled: Option[Boolean] = None
)

object McpServerConfig:
  given Decoder[McpServerConfig] = deriveDecoder[McpServerConfig]

  extension (cfg: McpServerConfig) def isEnabled: Boolean = cfg.enabled.getOrElse(true)

case class SearchConfig(
  provider: String,
  apiKey: String,
  engine: Option[String] = None,
  model: Option[String] = None
)

object SearchConfig:
  given Decoder[SearchConfig] = deriveDecoder[SearchConfig]

case class ServiceLlmConfig(
  providers: Map[String, ProviderConfig],
  model: ModelChainConfig
)

object ServiceLlmConfig:
  given Decoder[ServiceLlmConfig] = deriveDecoder[ServiceLlmConfig]

case class ThinkingConfig(
  enabled: Boolean = true,
  budgetTokens: Int = 32000
)

object ThinkingConfig:
  given io.circe.Encoder[ThinkingConfig] = io.circe.generic.semiauto.deriveEncoder

  given io.circe.Decoder[ThinkingConfig] = io.circe.Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(true))
      budgetTokens <- c.downField("budgetTokens").as[Option[Int]].map(_.getOrElse(32000))
    yield ThinkingConfig(enabled, budgetTokens)
  }

  /** Convert to the raw JSON shape expected by LLM adapters (e.g. Anthropic extended thinking). */
  def toLlmJson(tc: ThinkingConfig): io.circe.Json =
    if tc.enabled then
      io.circe.Json.obj(
        "type" -> "enabled".asJson,
        "budget_tokens" -> tc.budgetTokens.asJson
      )
    else io.circe.Json.Null
end ThinkingConfig

case class NebflowServiceConfig(
  llm: ServiceLlmConfig,
  mcpServers: Option[Map[String, McpServerConfig]] = None,
  search: Option[SearchConfig] = None,
  thinkingConfig: Option[ThinkingConfig] = None,
  // P0 阶段 3（2026-08-18）：TaskStuckWatcher 卡死判定阈值（ms）。
  // None → Defaults.StuckThresholdMs（10min）。显式配置可收紧（测试/调试）。
  stuckThresholdMs: Option[Long] = None
)

object NebflowServiceConfig:
  given Decoder[NebflowServiceConfig] = deriveDecoder[NebflowServiceConfig]

object Config:
  // def (not val): PathUtil.dataRoot and the config-file dual-read must be
  // resolved per access — an object val would freeze the first-touched
  // dataRoot and break test isolation (setDataRoot after first access).
  def NebflowHome: os.Path = PathUtil.dataRoot
  def DefaultConfigPath: os.Path = PathUtil.configJsonReadPath(NebflowHome)

  private val envVarLogger = nebflow.core.NebflowLogger.forName("nebflow.config")

  def resolveEnvVars(str: String): String =
    """\$\{([^}]+)\}""".r.replaceAllIn(
      str,
      m =>
        val key = m.group(1)
        sys.env.get(key) match
          case Some(value) => java.util.regex.Matcher.quoteReplacement(value)
          case None =>
            envVarLogger.infoSync(
              "Environment variable $" + key + " not found in config value, replacing with empty string"
            )
            ""
    )

  def parseModelRef(ref: String): (String, String) =
    val idx = ref.indexOf('/')
    if idx == -1 then throw new IllegalArgumentException(s"Invalid model ref \"$ref\", expected \"providerId/modelId\"")
    (ref.take(idx), ref.drop(idx + 1))

  def loadServiceConfig(configPath: Option[String] = None): NebflowServiceConfig =
    val path = configPath match
      case Some(p) => os.Path(p, os.pwd)
      case None => DefaultConfigPath

    if !os.exists(path) then defaultServiceConfig
    else
      val raw = os.read(path).trim
      if raw.isEmpty || raw == "{}" || raw.replace("\n", "").replace("\r", "").trim == "{}" then defaultServiceConfig
      else loadFromJson(raw)

  private def loadFromJson(raw: String): NebflowServiceConfig =
    val json = parse(raw) match
      case Right(j) => j
      case Left(err) => throw new RuntimeException(s"Invalid JSON in config: ${err.message}")

    // Resolve env vars in JSON string values
    val resolvedJson = resolveEnvVarsInJson(json)

    resolvedJson.as[NebflowServiceConfig] match
      case Right(cfg) => cfg
      case Left(err) =>
        val path = io.circe.CursorOp.opsToPath(err.history)
        throw new RuntimeException(s"Config parse error at '$path': ${err.message}")

  /** Minimal default config used when no config file exists. */
  private lazy val defaultServiceConfig: NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map.empty,
        model = ModelChainConfig(default = "anthropic/claude-sonnet-4-6")
      )
    )

  private def resolveEnvVarsInJson(json: Json): Json =
    json.fold(
      jsonNull = Json.Null,
      jsonBoolean = b => Json.fromBoolean(b),
      jsonNumber = n => Json.fromJsonNumber(n),
      jsonString = s => Json.fromString(resolveEnvVars(s)),
      jsonArray = arr => Json.fromValues(arr.map(resolveEnvVarsInJson)),
      jsonObject = obj =>
        Json.fromJsonObject(
          io.circe.JsonObject.fromIterable(
            obj.toList.map { case (k, v) => (k, resolveEnvVarsInJson(v)) }
          )
        )
    )
end Config
