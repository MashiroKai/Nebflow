package nebflow.core

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.AgentModelConfig
import nebflow.core.presets.{ModelChainRegistry, ModelPreset}
import nebflow.shared.{NebflowLogger, PathUtil}

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
 *   - the executor agent (`nebflow`, and the retired `kernel` / `general`
 *     spellings still present on an existing home): stale `preset` keys are
 *     removed (the engine ignored them).
 *   - The preset **definitions** migrate to the chain registry
 *     ([[ModelChainRegistry]], `model-chains.json`) — write-ahead merged
 *     BEFORE the catalog is sealed (R5 write-ahead precedent `c30e6945c`):
 *     a crash between the two is replayed idempotently on the next boot.
 *     The catalog file `model-presets.json` is then sealed in place:
 *     renamed with a timestamp suffix (the 迁移完成戳) so the original data
 *     survives for audit, and a second boot finds nothing at the original
 *     path.
 *
 * Non-role agents' stored references are untouched (audit-only data).
 */
object ModelChainMigration:

  private val logger = NebflowLogger.forName("nebflow.modelchain")

  private val NebulaRef = "Nebula"
  private val DefaultPresetRef = "LowCost"

  /**
   * Role agents whose stored `preset` references the one-time migration cleans
   * up. P1-2 downstream-role-face convergence batch (2026-10-03): the builtin-merge
   * batch merged `kernel` + `general` into [[nebflow.core.entity.BuiltinAgents.ExecutorName]],
   * and the retired spellings here are the OLD-DATA face — an existing home still
   * carries their sidecars and still needs the same stale-key cleanup, so they are
   * derived from the single rename table rather than re-spelled as literals.
   */
  private val RoleAgents: List[String] =
    List(NebulaRef, "project-dispatcher", nebflow.core.entity.BuiltinAgents.ExecutorName)
      ++ nebflow.core.entity.BuiltinAgents.RetiredNames.keys

  /**
   * Nebula's default chain — the shipped default chain, verbatim. Used when
   * Nebula's catalog reference cannot be resolved from the catalog file.
   */
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
    val entries = if os.exists(presetsFile) then readCatalogEntries(presetsFile) else Map.empty
    // 角色引用迁移**无条件**执行（与旧行为一致）：无目录文件的家，Nebula 的
    // LowCost 引用仍落内置默认链、其余引用 key-removal——不能随目录缺席而跳过。
    val catalog = entries
      .map((n, p) => n -> AgentModelConfig(p.preferred, p.fallbacks))
      .filter((_, c) => SchemePolicy.hasChain(c))
    RoleAgents.foreach(name => migrateAgent(name, catalog))
    // 注册表迁移 + 封存只在目录文件实存时进行（幂等的另一半：二次启动无文件 ⇒ no-op）。
    if os.exists(presetsFile) then migrateCatalogToRegistry(presetsFile, entries)

  /**
   * preset 定义 → 链注册表（write-ahead 先行），随后封存原目录文件。
   * 链缺失的 preset 条目不进注册表（与角色引用解析同一过滤，报 WARN）。
   */
  private def migrateCatalogToRegistry(presetsFile: os.Path, entries: Map[String, ModelPreset]): Unit =
    val (chained, chainless) = entries.partition((_, p) => SchemePolicy.hasChain(AgentModelConfig(p.preferred, p.fallbacks)))
    chainless.foreach { (name, _) =>
      logger.warnSync(s"model-chain migration: preset '$name' carries no chain; not migrated to registry")
    }
    if chained.nonEmpty then
      ModelChainRegistry.mergeChains(chained, readDefaultPreset(presetsFile))
      chained.foreach { (name, p) =>
        logger.infoSync(
          s"model-chain migration: preset '$name' -> chain registry " +
            s"(preferred=${p.preferred.getOrElse("-")}, fallbacks=${p.fallbacks.size})"
        )
      }
    sealCatalog(presetsFile)

  /** Read {presetName -> entry} from the catalog file, tolerating absence. */
  private def readCatalogEntries(presetsFile: os.Path): Map[String, ModelPreset] =
    io.circe.parser
      .parse(os.read(presetsFile))
      .toOption
      .flatMap(_.hcursor.downField("presets").focus.flatMap(_.asObject))
      .map { presets =>
        presets.toList.flatMap { (name, pj) =>
          pj.as[ModelPreset].toOption.map(name -> _)
        }.toMap
      }
      .getOrElse(Map.empty)

  /** The catalog's defaultPreset field, tolerating absence ("general" fallback). */
  private def readDefaultPreset(presetsFile: os.Path): String =
    io.circe.parser
      .parse(os.read(presetsFile))
      .toOption
      .flatMap(_.hcursor.downField("defaultPreset").as[Option[String]].toOption.flatten)
      .getOrElse("general")

  private def migrateAgent(name: String, catalog: Map[String, AgentModelConfig]): Unit =
    val jsonPath = PathUtil.dataRoot / "agents" / name / "agent.json"
    if os.exists(jsonPath) then
      io.circe.parser.parse(os.read(jsonPath)).toOption match
        case Some(json) =>
          json.hcursor.downField("preset").as[Option[String]].toOption.flatten.foreach { ref =>
            val resolved: Option[AgentModelConfig] = name match
              case NebulaRef =>
                catalog.get(ref).orElse {
                  if ref == DefaultPresetRef then Some(DefaultNebulaChain) else None
                }
              case _ => None
            val updated = resolved match
              case Some(chain) =>
                stripPresetKey(json).deepMerge(Json.obj("model" -> chain.asJson))
              case None =>
                // Followers become followers; a Nebula reference that cannot be
                // read degrades to seed-chain semantics (key removal only).
                stripPresetKey(json)
            AtomicJson.writeSync(jsonPath, updated.noSpaces)
            logger.infoSync(
              s"model-chain migration: '$name' preset reference '$ref' -> " +
                resolved.fold("follows the Nebula chain")(_ => "own model chain")
            )
          }
        case None =>
          logger.warnSync(s"model-chain migration: skipped unreadable $jsonPath")

    end if

  end migrateAgent

  private def stripPresetKey(json: Json): Json =
    json.asObject.map(obj => Json.fromFields(obj.toMap.removed("preset"))).getOrElse(json)

  /**
   * Seal the catalog file in place: rename with a timestamp suffix. A home
   * already migrated has nothing at the original path — no-op.
   */
  private def sealCatalog(presetsFile: os.Path): Unit =
    if os.exists(presetsFile) then
      val ts = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss")
        .format(java.time.LocalDateTime.now())
      val sealedPath = presetsFile / os.up / s"${presetsFile.last}.sealed-$ts"
      os.move(presetsFile, sealedPath)
      logger.infoSync(s"model-chain migration: preset catalog sealed at ${sealedPath.last}")

end ModelChainMigration
