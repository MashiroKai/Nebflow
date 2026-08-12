package nebflow.core.presets

import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.AgentModelConfig
import nebflow.llm.{Config, NebflowServiceConfig}

/**
 * A named model preset — a reusable `{preferred, fallbacks}` chain stored in
 * `~/.nebflow/model-presets.json`. Agents reference presets by name instead of
 * editing per-agent model lists; the preset is resolved to an
 * [[AgentModelConfig]] at agent-load time so the LLM layer is unchanged.
 */
case class ModelPreset(
  name: String,           // key in the presets map
  displayName: String,    // human-readable label (may be Chinese)
  description: String = "",
  preferred: Option[String] = None,
  fallbacks: List[String] = Nil
)

object ModelPreset:
  given Encoder[ModelPreset] = Encoder.instance { p =>
    Json.obj(
      "name" -> p.name.asJson,
      "displayName" -> p.displayName.asJson,
      "description" -> p.description.asJson,
      "preferred" -> p.preferred.asJson,
      "fallbacks" -> p.fallbacks.asJson
    )
  }
  given Decoder[ModelPreset] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      displayName <- c.downField("displayName").as[Option[String]].map(_.getOrElse(name))
      description <- c.downField("description").as[Option[String]].map(_.getOrElse(""))
      preferred <- c.downField("preferred").as[Option[String]]
      fallbacks <- c.downField("fallbacks").as[Option[List[String]]].map(_.getOrElse(Nil))
    yield ModelPreset(name, displayName, description, preferred, fallbacks)
  }

/** Root structure of `model-presets.json`. */
case class PresetFile(
  defaultPreset: String,
  presets: Map[String, ModelPreset]
)

object PresetFile:
  given Encoder[PresetFile] = Encoder.instance { f =>
    Json.obj(
      "defaultPreset" -> f.defaultPreset.asJson,
      "presets" -> f.presets.asJson
    )
  }
  given Decoder[PresetFile] = Decoder.instance { c =>
    for
      defaultPreset <- c.downField("defaultPreset").as[String]
      presets <- c.downField("presets").as[Option[Map[String, ModelPreset]]].map(_.getOrElse(Map.empty))
    yield PresetFile(defaultPreset, presets)
  }

/**
 * File-backed store for model presets.
 *
 * Resolution priority (highest → lowest):
 *   1. Explicit preset name (agent.json `"preset": "vision"`)
 *   2. Legacy per-agent `model` field (preferred/fallbacks)
 *   3. Default preset (from model-presets.json)
 *   4. Global chain (nebflow.json `llm.model`) — represented as `AgentModelConfig.empty`,
 *      which causes `getCandidatesForAgent` to fall back to the global chain.
 *
 * The store reads the file fresh on every call (small file, rare writes) so
 * edits take effect on the next `refreshTurn` without restart or cache
 * invalidation.
 */
class PresetStore(
  configPath: os.Path = PathUtil.dataRoot / "model-presets.json"
):

  private val logger = NebflowLogger.forName("nebflow.preset")

  /** Load the preset file, initializing it from the global chain on first use. */
  def load(): PresetFile =
    if !os.exists(configPath) then
      val init = initFromFile()
      save(init)
      init
    else
      val raw = os.read(configPath)
      decode[PresetFile](raw) match
        case Right(f) if f.presets.isEmpty =>
          // Empty presets file — re-initialize (e.g. user deleted all presets)
          val init = initFromFile()
          save(init)
          init
        case Right(f) =>
          // Validate/repair defaultPreset if it dangles
          repair(f)
        case Left(err) =>
          logger.warnSync(s"Failed to parse model-presets.json: ${err.getMessage}; re-initializing")
          val init = initFromFile()
          save(init)
          init

  /** Atomically write the preset file (temp + rename). */
  def save(f: PresetFile): Unit =
    os.makeDir.all(configPath / os.up)
    val tmp = configPath / os.up / s".model-presets.${System.nanoTime()}.tmp"
    os.write(tmp, f.asJson.noSpaces)
    os.move.over(tmp, configPath)

  /**
   * Resolve an agent's model configuration given its preset reference and
   * legacy model override.
   *
   * Returns `(AgentModelConfig, resolvedFrom)` where `resolvedFrom` indicates
   * which priority level was used: "preset" | "legacy-model" | "default-preset"
   * | "global".
   */
  def resolve(
    presetName: Option[String],
    legacy: Option[AgentModelConfig]
  ): (AgentModelConfig, String) =
    val file = load()
    // 1. Explicit preset
    presetName.flatMap(file.presets.get) match
      case Some(p) =>
        (AgentModelConfig(p.preferred, p.fallbacks), "preset")
      case None =>
        if presetName.isDefined then
          // Dangling preset reference — fall through with a warning
          logger.warnSync(s"Preset '${presetName.get}' not found; falling back")
        // 2. Legacy model (preferred or fallbacks non-empty)
        legacy match
          case Some(m) if m.preferred.isDefined || m.fallbacks.nonEmpty =>
            (m, "legacy-model")
          case _ =>
            // 3. Default preset
            file.presets.get(file.defaultPreset) match
              case Some(p) if p.preferred.isDefined || p.fallbacks.nonEmpty =>
                (AgentModelConfig(p.preferred, p.fallbacks), "default-preset")
              case _ =>
                // 4. Global chain (empty = let getCandidatesForAgent use global)
                (AgentModelConfig.empty, "global")

  /** Build the initial PresetFile from the global model chain in nebflow.json. */
  private def initFromFile(): PresetFile =
    val globalChain = readGlobalChain()
    val general = ModelPreset(
      name = "general",
      displayName = "通用",
      description = "默认方案：跟随全局模型链",
      preferred = globalChain.headOption,
      fallbacks = globalChain.drop(1)
    )
    PresetFile(defaultPreset = "general", presets = Map("general" -> general))

  /**
   * Read the global model chain from nebflow.json (`llm.model.default` +
   * `llm.model.fallbacks`).
   */
  private def readGlobalChain(): List[String] =
    val nebflowJson = PathUtil.dataRoot / "nebflow.json"
    if !os.exists(nebflowJson) then Nil
    else
      decode[NebflowServiceConfig](os.read(nebflowJson)) match
        case Right(cfg) => cfg.llm.model.default :: cfg.llm.model.fallbacks
        case Left(_) =>
          // Fallback: raw JSON parse
          io.circe.parser.parse(os.read(nebflowJson)).toOption
            .flatMap(_.hcursor.downField("llm").downField("model").focus)
            .map { modelJson =>
              val default = modelJson.hcursor.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
              val fallbacks = modelJson.hcursor.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
              (default :: fallbacks).filter(_.nonEmpty)
            }
            .getOrElse(Nil)

  /** Repair a PresetFile with a dangling defaultPreset by re-initializing. */
  private def repair(f: PresetFile): PresetFile =
    if f.presets.contains(f.defaultPreset) then f
    else if f.presets.nonEmpty then
      val fixed = f.copy(defaultPreset = f.presets.keys.head)
      save(fixed)
      fixed
    else
      val init = initFromFile()
      save(init)
      init

end PresetStore
