package nebflow.core

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.AgentModelConfig

/**
 * One-time migration from named model presets to per-role model chains.
 *
 * Runs at gateway boot and is idempotent: on a migrated home every step
 * finds nothing to do and no file is written. Actions:
 *
 *   - Nebula: a stored `preset` reference becomes its own `model` chain —
 *     the referenced preset's chain read from the preset catalog file, the
 *     built-in default chain below when the reference is the default one
 *     and the catalog cannot answer, or plain key removal for any other
 *     unreadable reference (seed-chain semantics).
 *   - project-dispatcher: the `preset` key is removed (the agent becomes a
 *     follower of Nebula's chain).
 *   - kernel / general: stale `preset` keys are removed (the engine ignored
 *     them).
 *   - The preset catalog file `model-presets.json` is sealed in place:
 *     renamed with a timestamp suffix so the data survives for audit, and a
 *     second boot finds nothing at the original path.
 *
 * Non-role agents' stored references are untouched (audit-only data).
 */
object ModelChainMigration:

  private val logger = NebflowLogger.forName("nebflow.modelchain")

  private val NebulaRef = "Nebula"
  private val DefaultPresetRef = "LowCost"
  private val RoleAgents = List("Nebula", "project-dispatcher", "kernel", "general")

  /** Nebula's default chain — the shipped default chain, verbatim. Used when
    * Nebula's catalog reference cannot be resolved from the catalog file. */
  val DefaultNebulaChain: AgentModelConfig = AgentModelConfig(
    preferred = Some("zhipu/GLM-5.3-Flash"),
    fallbacks = List(
      "cmdcode/deepseek/deepseek-v4.1-flash",
      "kimi/kimi-k3",
      "deepseek/deepseek-flash"
    )
  )

  /** Entry point; idempotent, safe to call on every boot. */
  def migrateLegacyPresetRefs(): Unit =
    val presetsFile = PathUtil.dataRoot / "model-presets.json"
    val catalog = readCatalog(presetsFile)
    RoleAgents.foreach(name => migrateAgent(name, catalog))
    sealCatalog(presetsFile)

  /** Read {presetName -> chain} from the catalog file, tolerating absence. */
  private def readCatalog(presetsFile: os.Path): Map[String, AgentModelConfig] =
    if !os.exists(presetsFile) then Map.empty
    else
      io.circe.parser
        .parse(os.read(presetsFile))
        .toOption
        .flatMap(_.hcursor.downField("presets").focus.flatMap(_.asObject))
        .map { presets =>
          presets.toList.flatMap { (name, pj) =>
            val preferred = pj.hcursor.downField("preferred").as[Option[String]].toOption.flatten
            val fallbacks = pj.hcursor.downField("fallbacks").as[Option[List[String]]].toOption.flatten.getOrElse(Nil)
            val chain = AgentModelConfig(preferred, fallbacks)
            if SchemePolicy.hasChain(chain) then Some(name -> chain) else None
          }.toMap
        }
        .getOrElse(Map.empty)

  private def migrateAgent(name: String, catalog: Map[String, AgentModelConfig]): Unit =
    val jsonPath = PathUtil.dataRoot / "agents" / name / "agent.json"
    if os.exists(jsonPath) then
      io.circe.parser.parse(os.read(jsonPath)).toOption match
        case Some(json) =>
          json.hcursor.downField("preset").as[Option[String]].toOption.flatten.foreach { ref =>
            val updated = name match
              case NebulaRef =>
                catalog.get(ref).orElse {
                  if ref == DefaultPresetRef then Some(DefaultNebulaChain) else None
                } match
                  case Some(chain) =>
                    stripPresetKey(json).deepMerge(Json.obj("model" -> chain.asJson))
                  case None =>
                    // Unreadable non-default reference: strip the key and let
                    // the seed chain apply (the old dangling-ref behavior).
                    stripPresetKey(json)
              case _ => stripPresetKey(json)
            AtomicJson.writeSync(jsonPath, updated.noSpaces)
            logger.infoSync(
              s"model-chain migration: '$name' preset reference '$ref' -> " +
                (if name == NebulaRef then "own model chain" else "follows the Nebula chain"))
          }
        case None =>
          logger.warnSync(s"model-chain migration: skipped unreadable $jsonPath")

  private def stripPresetKey(json: Json): Json =
    json.asObject.map(obj => Json.fromFields(obj.toMap.removed("preset"))).getOrElse(json)

  /** Seal the catalog file in place: rename with a timestamp suffix. A home
    * already migrated has nothing at the original path — no-op. */
  private def sealCatalog(presetsFile: os.Path): Unit =
    if os.exists(presetsFile) then
      val ts = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss")
        .format(java.time.LocalDateTime.now())
      val sealedPath = presetsFile / os.up / s"${presetsFile.last}.sealed-$ts"
      os.move(presetsFile, sealedPath)
      logger.infoSync(s"model-chain migration: preset catalog sealed at ${sealedPath.last}")

end ModelChainMigration
