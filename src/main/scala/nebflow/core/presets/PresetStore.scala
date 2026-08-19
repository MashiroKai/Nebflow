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
  name: String, // key in the presets map, also the display label
  description: String = "",
  preferred: Option[String] = None,
  fallbacks: List[String] = Nil
)

object ModelPreset:

  given Encoder[ModelPreset] = Encoder.instance { p =>
    Json.obj(
      "name" -> p.name.asJson,
      "description" -> p.description.asJson,
      "preferred" -> p.preferred.asJson,
      "fallbacks" -> p.fallbacks.asJson
    )
  }

  // Backward-compatible: ignores a legacy "displayName" field if present in old JSON.
  given Decoder[ModelPreset] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[Option[String]].map(_.getOrElse(""))
      preferred <- c.downField("preferred").as[Option[String]]
      fallbacks <- c.downField("fallbacks").as[Option[List[String]]].map(_.getOrElse(Nil))
    yield ModelPreset(name, description, preferred, fallbacks)
  }

end ModelPreset

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
 * INVARIANT (#311, 2026-08-19): a usable default preset ALWAYS exists once any
 * model is configured. The file is created/seeded the moment the user saves
 * their first provider/model (settings updateConfig → [[ensureDefaultPreset]],
 * also enforced at gateway boot), and [[load]] repairs a dangling or
 * chain-less defaultPreset before anyone reads it.
 *
 * Resolution priority (highest → lowest):
 *   1. Explicit preset name (agent.json `"preset": "vision"`)
 *   2. Legacy per-agent `model` field (preferred/fallbacks)
 *   3. Default preset — TERMINAL.
 *
 * The old level-4 fallback to the global chain (nebflow.json `llm.model`) is
 * REMOVED: it silently routed agents to a model the user never selected in the
 * preset UI (2026-08-19 preset-display mismatch incident — the global
 * default resolved to a chain the settings UI never showed). `llm.model` now
 * only SEEDS the initial preset; it is never a live fallback. (The provider
 * registry's all-candidates list remains as a last-resort error path when a
 * preset's refs are ALL unresolvable against the provider config — a loud,
 * different failure, not a silent preference.)
 *
 * The store reads the file fresh on every call (small file, rare writes) so
 * edits take effect on the next `refreshTurn` without restart or cache
 * invalidation.
 */
class PresetStore(
    configPath: os.Path = PathUtil.dataRoot / "model-presets.json",
    globalChainProvider: () => List[String] = () => PresetStore.readGlobalChainDefault()
):

  private val logger = NebflowLogger.forName("nebflow.preset")

  private def globalChain: List[String] = globalChainProvider()

  /**
   * Idempotent invariant enforcer: the preset file exists and its
   * defaultPreset carries a model chain. Call after the first provider/model
   * save (updateConfig / onboarding) and at gateway boot. A healthy file is
   * untouched (no write); an absent/empty/broken file is created or repaired.
   */
  def ensureDefaultPreset(): Unit = load()

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
          // Validate/repair defaultPreset if it dangles or carries no chain
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
   * which priority level was used: "preset" | "legacy-model" |
   * "default-preset". There is NO "global" level anymore — level 3 is
   * terminal (see class doc; #311 removed the silent global-chain fallback).
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
            // 3. Default preset — terminal. load() guarantees it carries a
            // chain whenever any model is configured; the getOrElse only
            // matters on a fully unconfigured install (no providers saved
            // yet), where the empty chain defers to the registry's
            // all-candidates path at request time.
            val dp = file.presets.getOrElse(file.defaultPreset, ModelPreset(file.defaultPreset))
            (AgentModelConfig(dp.preferred, dp.fallbacks), "default-preset")

    end match

  end resolve

  /**
   * Resolve an explicit preset name for a tool-level override (Delegate/SubTask
   * `preset` param). Unlike [[resolve]] — which falls through on a dangling
   * reference — this fails with the available preset list so the caller can
   * surface a self-describing error the LLM can self-heal from (retry with a
   * valid name).
   */
  def resolveExplicit(presetName: String): Either[String, AgentModelConfig] =
    load().presets.get(presetName) match
      case Some(p) if p.preferred.isDefined || p.fallbacks.nonEmpty =>
        Right(AgentModelConfig(p.preferred, p.fallbacks))
      case Some(_) =>
        Left(s"Preset '$presetName' is defined but has no model chain (preferred/fallbacks empty). Available presets: ${availableNames}")
      case None =>
        Left(s"Preset '$presetName' not found. Available presets: ${availableNames}")

  /** Sorted, comma-joined preset names for self-describing errors. */
  def availableNames: String =
    load().presets.keys.toList.sorted.mkString(", ")

  /** Build the initial PresetFile from the global model chain in nebflow.json. */
  private def initFromFile(): PresetFile =
    val globalChain = this.globalChain
    val general = ModelPreset(
      name = "general",
      description = "默认方案：跟随全局模型链",
      preferred = globalChain.headOption,
      fallbacks = globalChain.drop(1)
    )
    PresetFile(defaultPreset = "general", presets = Map("general" -> general))

  /**
   * Enforce the invariant on a parsed file: defaultPreset exists AND carries
   * a model chain (#311 — a chain-less default is treated exactly like a
   * dangling one).
   *
   * Healthy files pass through untouched (no write). A broken default is
   * re-pointed at the first preset that has a chain; if none does, the file is
   * re-seeded from the global llm.model chain — which at that point is the
   * provider model the user just configured. A fully unconfigured install
   * (global chain empty too) is left as-is: nothing better exists to write,
   * and re-saving an identical empty file on every load would be churn.
   */
  private def repair(f: PresetFile): PresetFile =
    def hasChain(p: ModelPreset): Boolean = p.preferred.isDefined || p.fallbacks.nonEmpty
    f.presets.get(f.defaultPreset) match
      case Some(p) if hasChain(p) => f
      case _ =>
        f.presets.find((_, p) => hasChain(p)) match
          case Some((name, _)) =>
            val fixed = f.copy(defaultPreset = name)
            save(fixed)
            fixed
          case None =>
            val chain = globalChain
            if chain.isEmpty then f
            else
              val init = initFromFile()
              save(init)
              init
end PresetStore

object PresetStore:

  /**
   * Read the global model chain from nebflow.json (`llm.model.default` +
   * `llm.model.fallbacks`). SEED ONLY (#311) — never a live fallback for
   * agent resolution.
   */
  private def readGlobalChainDefault(): List[String] =
    val nebflowJson = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(nebflowJson) then Nil
    else
      decode[NebflowServiceConfig](os.read(nebflowJson)) match
        case Right(cfg) => cfg.llm.model.default :: cfg.llm.model.fallbacks
        case Left(_) =>
          // Fallback: raw JSON parse
          io.circe.parser
            .parse(os.read(nebflowJson))
            .toOption
            .flatMap(_.hcursor.downField("llm").downField("model").focus)
            .map { modelJson =>
              val default = modelJson.hcursor.downField("default").as[Option[String]].toOption.flatten.getOrElse("")
              val fallbacks =
                modelJson.hcursor.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
              (default :: fallbacks).filter(_.nonEmpty)
            }
            .getOrElse(Nil)

    end if

end PresetStore
