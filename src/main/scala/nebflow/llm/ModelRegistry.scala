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
 *   2. ModelRegistry (models.json) — `ModelEntry.vision` is Option: Some(x) is
 *      an explicit human annotation, None means "unknown"
 *   3. Defaults (capabilities=empty)
 *
 * visionfix (甲): `ModelEntry.vision` remains the human annotation (REST PUT
 * from the settings UI), but it no longer feeds the send path — there is no
 * per-candidate vision bit any more, so an annotation can only be read as a
 * fact by the UI. Resolution therefore covers `capabilities` only.
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
   * visionfix (甲): `persistVision` was deleted here. It was the single write-back
   * path that turned a keyword-heuristic mis-diagnosis into a durable
   * `vision: false` annotation in `models.json`, surviving restarts. Its only
   * caller was EmptyCompletionTracker.setVisionOverrideFalse, also deleted.
   * ⇒ after this batch there is NO producer that writes `vision` back, so the
   * heuristic can no longer be persisted as if it were an authoritative fact.
   *
   * `ModelEntry.vision` itself, `save`, and the REST channel are deliberately
   * KEPT: they are the human annotation surface, and they are what keeps the
   * on-disk `models.json` format unchanged.
   */

  /** Get all capability tag definitions. */
  def capabilityTags: Map[String, CapabilityTag] =
    ensureLoaded.capabilityTags

  /** Reload from disk (hot reload). */
  def reload(): Unit =
    cache = Some(load())
    logger.infoSync(s"Reloaded models.json: ${cache.map(_.models.size).getOrElse(0)} models")

end ModelRegistry
