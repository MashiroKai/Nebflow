package nebflow.llm

import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Loads and caches model capability metadata from `~/.nebflow/models.json`.
 *
 * Priority for capability resolution:
 *   1. ModelConfig inline fields (nebflow.json)
 *   2. ModelRegistry (models.json)
 *   3. Defaults (vision=false, capabilities=empty)
 *
 * The registry is loaded once at startup and can be reloaded via `reload()`.
 */
object ModelRegistry:

  private val logger = NebflowLogger.forName("nebflow.model-registry")

  case class ModelEntry(
    vision: Boolean = false,
    capabilities: List[String] = Nil
  )
  object ModelEntry:
    given Decoder[ModelEntry] = deriveDecoder[ModelEntry]
    given Encoder[ModelEntry] = deriveEncoder[ModelEntry]

  case class ModelRegistryFile(
    models: Map[String, ModelEntry] = Map.empty,
    capabilityTags: Map[String, CapabilityTag] = Map.empty
  )
  object ModelRegistryFile:
    given Decoder[ModelRegistryFile] = deriveDecoder[ModelRegistryFile]
    given Encoder[ModelRegistryFile] = deriveEncoder[ModelRegistryFile]

  case class CapabilityTag(
    label: String = "",
    description: String = ""
  )
  object CapabilityTag:
    given Decoder[CapabilityTag] = deriveDecoder[CapabilityTag]
    given Encoder[CapabilityTag] = deriveEncoder[CapabilityTag]

  private def configPath: os.Path = PathUtil.dataRoot / "models.json"

  @volatile private var cache: Option[ModelRegistryFile] = None

  private def load(): ModelRegistryFile =
    val path = configPath
    if !os.exists(path) then ModelRegistryFile()
    else
      decode[ModelRegistryFile](os.read(path)) match
        case Right(file) => file
        case Left(err) =>
          logger.warnSync(s"Failed to parse models.json: ${err.getMessage}")
          ModelRegistryFile()

  private def ensureLoaded: ModelRegistryFile =
    cache.getOrElse {
      val loaded = load()
      cache = Some(loaded)
      loaded
    }

  /** Public load — returns the full registry file for API use. */
  def loadForApi: ModelRegistryFile =
    ensureLoaded

  /** Save models to disk and update cache. */
  def save(models: Map[String, ModelEntry]): Unit =
    val current = ensureLoaded
    val updated = current.copy(models = models)
    val path = configPath
    os.write.over(path, updated.asJson.noSpaces)
    cache = Some(updated)
    logger.infoSync(s"Saved models.json: ${models.size} models")

  /** Look up capabilities for a model by `providerId/modelId` key. */
  def lookup(providerId: String, modelId: String): Option[ModelEntry] =
    val key = s"$providerId/$modelId"
    ensureLoaded.models.get(key)

  /** Get all capability tag definitions. */
  def capabilityTags: Map[String, CapabilityTag] =
    ensureLoaded.capabilityTags

  /** Reload from disk (hot reload). */
  def reload(): Unit =
    cache = Some(load())
    logger.infoSync(s"Reloaded models.json: ${cache.map(_.models.size).getOrElse(0)} models")

end ModelRegistry
