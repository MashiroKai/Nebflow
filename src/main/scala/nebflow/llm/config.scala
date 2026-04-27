package nebflow.llm

import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.parse

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

case class ProviderConfig(
  baseUrl: String,
  apiKey: String,
  protocol: LlmProtocol
)

object ProviderConfig:
  given Decoder[ProviderConfig] = deriveDecoder[ProviderConfig]

case class ModelChainConfig(
  primary: String,
  fallbacks: List[String] = Nil
)

object ModelChainConfig:
  given Decoder[ModelChainConfig] = deriveDecoder[ModelChainConfig]

case class McpServerConfig(
  command: Option[String] = None,
  args: Option[List[String]] = None,
  env: Option[Map[String, String]] = None,
  url: Option[String] = None,
  headers: Option[Map[String, String]] = None
)

object McpServerConfig:
  given Decoder[McpServerConfig] = deriveDecoder[McpServerConfig]

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

case class NebflowServiceConfig(
  llm: ServiceLlmConfig,
  mcpServers: Option[Map[String, McpServerConfig]] = None,
  search: Option[SearchConfig] = None
)

object NebflowServiceConfig:
  given Decoder[NebflowServiceConfig] = deriveDecoder[NebflowServiceConfig]

object Config:
  val NebflowHome: os.Path = os.home / ".nebflow"
  val DefaultConfigPath: os.Path = NebflowHome / "nebflow.json"

  def resolveEnvVars(str: String): String =
    """\$\{([^}]+)\}""".r.replaceAllIn(str, m =>
      sys.env.getOrElse(m.group(1), m.matched)
    )

  def parseModelRef(ref: String): (String, String) =
    val idx = ref.indexOf('/')
    if idx == -1 then
      throw new IllegalArgumentException(s"Invalid model ref \"$ref\", expected \"providerId/modelId\"")
    (ref.take(idx), ref.drop(idx + 1))

  def loadServiceConfig(configPath: Option[String] = None): NebflowServiceConfig =
    val path = configPath match
      case Some(p) => os.Path(p, os.pwd)
      case None => DefaultConfigPath

    if !os.exists(path) then
      throw new RuntimeException(s"Config file not found: $path")

    val raw = os.read(path)
    val json = parse(raw) match
      case Right(j) => j
      case Left(err) => throw new RuntimeException(s"Invalid JSON in config: ${err.message}")

    // Resolve env vars in JSON string values
    val resolvedJson = resolveEnvVarsInJson(json)

    resolvedJson.as[NebflowServiceConfig] match
      case Right(cfg) => cfg
      case Left(err) => throw new RuntimeException(s"Config parse error: ${err.message}")

  private def resolveEnvVarsInJson(json: Json): Json =
    json.fold(
      jsonNull = Json.Null,
      jsonBoolean = b => Json.fromBoolean(b),
      jsonNumber = n => Json.fromJsonNumber(n),
      jsonString = s => Json.fromString(resolveEnvVars(s)),
      jsonArray = arr => Json.fromValues(arr.map(resolveEnvVarsInJson)),
      jsonObject = obj => Json.fromJsonObject(
        io.circe.JsonObject.fromIterable(
          obj.toList.map { case (k, v) => (k, resolveEnvVarsInJson(v)) }
        )
      )
    )
