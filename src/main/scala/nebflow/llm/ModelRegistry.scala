package nebflow.llm

import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * Loads and caches model capability metadata from `~/.nebflow/models.json`.
 *
 * Priority for capability resolution:
 *   1. ModelConfig inline fields (nebflow.json)
 *   2. ModelRegistry (models.json) — `vision` is Option: Some(x) is an
 *      explicit annotation (respected), None means "unknown" and resolves
 *      optimistically to vision=true (B3 Phase 1)
 *   3. Defaults (vision=true for unannotated, capabilities=empty)
 *
 * The registry is loaded once at startup and can be reloaded via `reload()`.
 */
object ModelRegistry:

  private val logger = NebflowLogger.forName("nebflow.model-registry")

  case class ModelEntry(
    vision: Option[Boolean] = None,
    capabilities: List[String] = Nil
  )

  object ModelEntry:
    // Tolerant decoding (same rationale as ModelRegistryFile): partial
    // hand-written entries must load instead of rejecting the whole file.
    given Decoder[ModelEntry] = new Decoder[ModelEntry]:
      def apply(c: io.circe.HCursor): Decoder.Result[ModelEntry] =
        for
          vision <- c.getOrElse[Option[Boolean]]("vision")(None)
          caps <- c.getOrElse[List[String]]("capabilities")(Nil)
        yield ModelEntry(vision, caps)
    given Encoder[ModelEntry] = deriveEncoder[ModelEntry]

  case class ModelRegistryFile(
    models: Map[String, ModelEntry] = Map.empty,
    capabilityTags: Map[String, CapabilityTag] = Map.empty
  )

  object ModelRegistryFile:
    // Tolerant decoding: models.json is machine-written by runtime
    // auto-demotion (B3) but also hand-edited / written by older versions —
    // a missing top-level field falls back to empty instead of rejecting
    // the whole file (which would silently drop every annotation).
    given Decoder[ModelRegistryFile] = new Decoder[ModelRegistryFile]:
      def apply(c: io.circe.HCursor): Decoder.Result[ModelRegistryFile] =
        for
          models <- c.getOrElse[Map[String, ModelEntry]]("models")(Map.empty)
          tags <- c.getOrElse[Map[String, CapabilityTag]]("capabilityTags")(Map.empty)
        yield ModelRegistryFile(models, tags)
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
    AtomicJson.writeSync(path, updated.asJson.noSpaces)
    cache = Some(updated)
    logger.infoSync(s"Saved models.json: ${models.size} models")

  /** Look up capabilities for a model by `providerId/modelId` key. */
  def lookup(providerId: String, modelId: String): Option[ModelEntry] =
    val key = s"$providerId/$modelId"
    ensureLoaded.models.get(key)

  /**
   * Persist a vision annotation for one model (B3 Phase 2: runtime vision
   * overrides are written back to models.json so they survive restarts).
   * Preserves the entry's other fields; creates the entry if absent.
   */
  def persistVision(providerId: String, modelId: String, vision: Boolean): Unit =
    val key = s"$providerId/$modelId"
    val current = ensureLoaded
    val updatedEntry = current.models.get(key) match
      case Some(existing) => existing.copy(vision = Some(vision))
      case None           => ModelEntry(vision = Some(vision))
    val updated = current.copy(models = current.models + (key -> updatedEntry))
    AtomicJson.writeSync(configPath, updated.asJson.noSpaces)
    cache = Some(updated)
    logger.infoSync(s"Persisted vision=$vision for $key in models.json")

  /** Get all capability tag definitions. */
  def capabilityTags: Map[String, CapabilityTag] =
    ensureLoaded.capabilityTags

  /** Reload from disk (hot reload). */
  def reload(): Unit =
    cache = Some(load())
    logger.infoSync(s"Reloaded models.json: ${cache.map(_.models.size).getOrElse(0)} models")

end ModelRegistry
