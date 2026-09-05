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
 * default resolved to a chain the settings UI never showed). `llm.model` is
 * fully retired (#339, D-b): the seed source reads it once for migration,
 * strips it from nebflow.json at boot, and cold starts seed from the first
 * provider's first model instead (D-a — see [[PresetStore.readSeedChain]]).
 * (The provider registry's all-candidates list remains as a last-resort error
 * path when a preset's refs are ALL unresolvable against the provider config —
 * a loud, different failure, not a silent preference.)
 *
 * The store reads the file fresh on every call (small file, rare writes) so
 * edits take effect on the next `refreshTurn` without restart or cache
 * invalidation.
 */
class PresetStore(
    configPath: os.Path = PathUtil.dataRoot / "model-presets.json",
    globalChainProvider: () => List[String] = () => PresetStore.readSeedChain()
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
      // 首载物化竞态防护（trigger-chain-fix fork 化前置硬化）：触发链并行化后并
      // 发节点 spawn 成为常态——check-then-act 下多 fiber 同时 !exists → 并发 save
      // → os.move.over 换名碰撞（实测 FileAlreadyExistsException，spawn 失败）。
      // 类级 synchronized 双检：init 全局串行，败方落入常规读路径。
      PresetStore.synchronized {
        if !os.exists(configPath) then
          val init = initFromFile()
          save(init)
          init
        else loadExisting()
      }
    else loadExisting()

  private def loadExisting(): PresetFile =
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
    // 并发 save 换名碰撞重试（trigger-chain-fix）：并发 repair/init 双写时
    // move.over 对已出现的目标抛 FileAlreadyExistsException——tmp 名含 nanoTime
    // 互不覆盖，短退避重试即收敛；重试耗尽后原样上抛（真异常不留观感）。
    var attempt = 0
    var done = false
    while !done && attempt < 5 do
      try
        os.move.over(tmp, configPath)
        done = true
      catch
        case _: java.nio.file.FileAlreadyExistsException =>
          attempt += 1
          Thread.sleep(5L * attempt)
    if !done then os.move.over(tmp, configPath)

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

  /** Build the initial PresetFile from the seed chain (D-a: llm.model 迁移
    * 优先，否则首 provider 首模型——单元素链，fallbacks 由用户显式决策）。 */
  private def initFromFile(): PresetFile =
    val globalChain = this.globalChain
    val general = ModelPreset(
      name = "general",
      description = "默认方案：初始配置时自动创建",
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
   * re-seeded from the seed chain (D-a) — which at that point is the provider
   * model the user just configured. A fully unconfigured install (seed chain
   * empty too) is left as-is: nothing better exists to write, and re-saving an
   * identical empty file on every load would be churn.
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
   * Preset catalog rendered for tool parameter docs (2026-08-20, user-noticed
   * gap: preset descriptions written in Settings were invisible to agents).
   * Each line: "Name — description" (name only when the preset has no
   * description). Read fresh so tool schemas pick up preset edits without
   * restart — consumers (Delegate/SubTask inputSchema) are rebuilt per LLM
   * call and the store reads the file on every load (small file, rare
   * writes). Any read failure degrades to Nil (catalog simply omitted).
   */
  def catalogLines(): List[String] = catalogLines(new PresetStore())

  /** DI variant for tests (temp config path). */
  def catalogLines(store: PresetStore): List[String] =
    scala.util.Try(store.load()).toOption
      .map(_.presets.values.toList.sortBy(_.name).map { p =>
        val note = p.description.trim
        if note.isEmpty then p.name else s"${p.name} — $note"
      })
      .getOrElse(Nil)

  /**
   * 种子链（D-a，#339）：**llm.model 迁移优先**（服务存量 nebflow.json），
   * 否则 **providers 推导**（首个含模型 provider 的首模型，单元素链）。
   * 冷启动（首配 provider、无 llm.model）自然走后者——与旧前端"首配只设
   * default 不设 fallbacks"行为等价。
   */
  def readSeedChain(): List[String] =
    val fromLlmModel = readGlobalChainDefault()
    if fromLlmModel.nonEmpty then fromLlmModel else readFromProviders()

  /**
   * Read the global model chain from nebflow.json (`llm.model.default` +
   * `llm.model.fallbacks`). MIGRATION ONLY (#339 D-b — llm.model 已退役)：
   * 仅作为迁移种子源与 readSeedChain 的优先分支，不再是任何 live 路径。
   */
  private def readGlobalChainDefault(): List[String] =
    val nebflowJson = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(nebflowJson) then Nil
    else
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

  /**
   * providers 推导种子（D-a）：按 JSON 字段顺序取首个含模型 provider 的
   * 首个模型（单元素链）。**不把全部模型塞进 fallbacks**——那是重新制造
   * "静默路由到用户未选择的模型"（#311 病灶）；fallback 是用户显式决策。
   */
  private def readFromProviders(): List[String] =
    val nebflowJson = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(nebflowJson) then Nil
    else
      val firstRef: Option[String] =
        io.circe.parser
          .parse(os.read(nebflowJson))
          .toOption
          .flatMap(_.hcursor.downField("llm").downField("providers").focus)
          .flatMap(_.asObject)
          .flatMap { providers =>
            // JsonObject 保持插入序——"首个" provider 是用户配置文件里的第一个
            providers.toIterable.view
              .flatMap { (name, pj) =>
                pj.hcursor.downField("models").focus
                  .flatMap(_.asArray)
                  .flatMap(_.headOption)
                  .flatMap(m => m.hcursor.downField("id").as[String].toOption)
                  .map(id => s"$name/$id")
              }
              .take(1)
              .toList
              .headOption
          }
      firstRef.toList

  /**
   * llm.model 一次性迁移（#339 D-b，boot 调用）：**先播种验证、后剥离**。
   *
   * 1. nebflow.json 无 llm.model 节 → false（无事可做）
   * 2. ensureDefaultPreset()（种子源此时仍优先读 llm.model——迁移优先）
   * 3. 默认 preset 不可用（不存在/无链）→ 中止剥离，返回 false（下次启动
   *    幂等重试；字段留存但 decoder 已忽略）
   * 4. 可用 → 从 nebflow.json 剥离 llm.model 节（AtomicJson 原子写），
   *    日志 "migrated llm.model → default preset; field removed"，返回 true
   *
   * 防呆：只在 llm.model 节实际存在时剥离；顶层/llm 节非对象的安全跳过。
   */
  def migrateGlobalModelChain(): Boolean =
    val logger = NebflowLogger.forName("nebflow.preset")
    val nebflowJson = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    val parsedOpt =
      if os.exists(nebflowJson) then io.circe.parser.parse(os.read(nebflowJson)).toOption
      else None
    val hasLlmModel = parsedOpt.exists { j =>
      j.hcursor.downField("llm").focus.flatMap(_.asObject).exists(_.contains("model"))
    }
    if !hasLlmModel then false
    else
      try
        val store = PresetStore()
        store.ensureDefaultPreset()
        val file = store.load()
        val healthy = file.presets
          .get(file.defaultPreset)
          .exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty)
        if !healthy then
          logger.warnSync("llm.model migration deferred: default preset not usable yet; will retry next boot")
          false
        else
          val stripped = for
            root <- parsedOpt.flatMap(_.asObject)
            llm <- root("llm").flatMap(_.asObject) if llm.contains("model")
            newLlm = io.circe.JsonObject.fromIterable(llm.toList.filterNot(_._1 == "model"))
            newRoot = io.circe.JsonObject.fromIterable(
              root.toList.map((k, v) => if k == "llm" then (k, io.circe.Json.fromJsonObject(newLlm)) else (k, v))
            )
          yield io.circe.Json.fromJsonObject(newRoot).noSpaces
          stripped match
            case Some(content) =>
              nebflow.core.AtomicJson.writeSync(nebflowJson, content)
              logger.infoSync("migrated llm.model → default preset; field removed")
              true
            case None => false
      catch case e: Exception =>
        logger.warnSync(s"llm.model migration deferred: ${e.getMessage}")
        false

end PresetStore
