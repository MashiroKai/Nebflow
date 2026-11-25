package nebflow.core

import nebflow.shared.{AgentModelConfig, PathUtil}

/**
 * Name-aware model-chain resolution policy (single source of truth for "which
 * model chain does agent X run on").
 *
 * Storage: each agent's chain lives in its own `agent.json` under the `model`
 * key as `{preferred, fallbacks}` ([[AgentModelConfig]]). Resolution:
 *
 *   1. **Own chain** (non-empty `model` key) — settable agents only.
 *   2. **Nebula primary chain** — every other agent (the executor agent and all
 *      custom, team and flow agents)
 *      follows Nebula's chain, re-read fresh on every resolution (per turn
 *      via the agent-def reload path, so /model edits take effect on running
 *      actors without any propagation step).
 *   3. **Seed chain** — when Nebula itself carries no chain: the first
 *      configured provider's first model (a legacy `llm.model` chain in
 *      nebflow.json still wins while it exists, so pre-migration installs
 *      keep their chain).
 *
 * Nebula never follows anyone (its chain IS the primary chain); an absent or
 * unreadable chain degrades to the seed chain — resolution never throws.
 * Non-role agents keep any stored `preset`/`model` references on disk for
 * audit, but the engine does not read them: they follow Nebula uniformly.
 *
 * The settable gate ([[SettableAgents]]) guards every write path: only these
 * roles accept a model-chain write (REST `PUT /api/agents/:name/model`).
 */
object SchemePolicy:

  val NebulaName = "Nebula"
  val DispatcherName = "project-dispatcher"

  /**
   * The executor role name (single point = [[nebflow.core.entity.BuiltinAgents.ExecutorName]]).
   *
   * P1-2 downstream-role-face convergence batch (2026-10-03): `kernel` and `general`
   * were merged into the single executor agent by the builtin-merge batch
   * (`f646eeeaa`) and left `BuiltinAgents.Names`, so they are no longer ROLES — the
   * set below names the current executor instead of the two retired names.
   */
  val ExecutorName: String = nebflow.core.entity.BuiltinAgents.ExecutorName

  /**
   * Roles whose model chain is user-settable (the /model write gate).
   *
   * 模型粒度批（2026-11-06 20:36 作者令）：五类槽 = main(Nebula) / dispatcher /
   * kernel / node / subagent ——后三者是**虚拟模型槽**（`ModelRoles`），只有
   * model sidecar、不是完整 agent；写闸对它们开放（/model 面板与设置面板按类切
   * ⇒ 同类全部会话一起变）。kernel 曾是本集合成员、builtin-merge 批并进执行
   * agent——本批起 `kernel` 以**独立槽**身份回归（与 2026-10-03 的角色合并不冲突：
   * 那次合并的是运行身份/提示词，这次拆的是模型配置粒度）。
   */
  /**
   * 🔴 `lazy val` 是**初始化顺序纪律**，不是风格选择（模型粒度批实证，2026-11-07）：
   * 本集合要点 [[ModelRoles.Slots]]，而 `ModelRoles.Slots` 的槽名取自本对象的
   * [[NebulaName]] / [[DispatcherName]] ⇒ 若两侧都在 `<clinit>` 里急切求值，谁先被
   * 触达就成环：`SchemePolicy.<clinit>` → `ModelRoles.Slots`（此刻仍 null）→ NPE →
   * `ExceptionInInitializerError`，整个进程的 SchemePolicy/ModelRoles 双双判废
   * （ModelChainRoutesSpec 首轮实跑即此形态，日志 02-modelchain-routes.log）。两侧
   * 同时改 `lazy val`（见 `ModelRoles.Slots`）：任一侧先初始化时都不会在 `<clinit>`
   * 期间反向触达对方，单点真源（槽名不复制字面量）得以保留。
   */
  lazy val SettableAgents: Set[String] =
    Set(NebulaName, DispatcherName, ExecutorName) ++ ModelRoles.Slots.map(_._1)

  /**
   * Settability, with the retired names resolved through the single rename table
   * ([[nebflow.core.entity.BuiltinAgents.resolveRetired]] — the same read-side
   * fallback `EntityLoader.loadAgent` uses).
   *
   * Old-data compatibility (P0-1/P1-2, 2026-10-03): a stored `kernel` / `general`
   * sidecar keeps being honoured and a stale client that still writes the retired
   * name keeps acting on the same role — the pre-merge behaviour of an existing
   * home, so the convergence does NOT silently move those chains onto the Nebula
   * primary chain.
   */
  def isSettable(name: String): Boolean =
    SettableAgents
      .contains(name) || nebflow.core.entity.BuiltinAgents.resolveRetired(name).exists(SettableAgents.contains)

  /** `resolvedFrom` vocabulary (reported by GET /api/agents/:name/model). */
  final val OwnChainSource = "own-chain"
  final val NebulaChainSource = "nebula-chain"
  final val SeedSource = "seed"

  /** A chain is usable when at least one slot carries a ref. */
  def hasChain(c: AgentModelConfig): Boolean = c.preferred.isDefined || c.fallbacks.nonEmpty

  /**
   * Read the raw `model` chain from an agent's agent.json, fresh per call.
   * Missing file / unparseable JSON / absent key => None (fail-safe).
   */
  def ownChainOf(agent: String): Option[AgentModelConfig] =
    val jsonPath = PathUtil.dataRoot / "agents" / agent / "agent.json"
    if !os.exists(jsonPath) then None
    else
      io.circe.parser.parse(os.read(jsonPath)).toOption.flatMap { json =>
        json.hcursor.downField("model").as[AgentModelConfig].toOption
      }

  /** Shared model for lightweight calls; read on each invocation. */
  def smallLlmModel(): AgentModelConfig =
    ownChainOf(ModelRoles.SmallLlmWire).filter(hasChain).getOrElse {
      org.slf4j.LoggerFactory
        .getLogger("nebflow.model.small-llm")
        .warn("Small LLM is not configured; falling back to the main model")
      resolveModel(NebulaName, nebulaChain())._1
    }

  /** Nebula's primary chain — the global default every follower inherits. */
  def nebulaChain(): Option[AgentModelConfig] = ownChainOf(NebulaName)

  /**
   * Seed chain — resolution order (config-single-source batch, author rulings
   * ①③ 2026-11-05):
   *   1. **`llm.fallback` in the config file** (`stand.json`) — the SINGLE
   *      SOURCE of the global chain once it carries a ref. The chain used to be
   *      code-assembled, so the settings panel and the runtime disagreed; the
   *      file is now the truth.
   *   2. legacy `llm.model` chain (installs that predate the field's removal
   *      keep their configured chain),
   *   3. the first provider's first model in config field order
   *      (single-element chain — fallbacks are an explicit user decision, never
   *      auto-populated).
   */
  def readSeedChain(): List[String] =
    readFallbackSection()

  /**
   * `llm.fallback` of the config file (empty when absent / malformed). Hot-read.
   *
   * **fallback 废除批（2026-11-06 20:36 作者令「不再支持模型fallback」）**：本段
   * 退役为「全局默认模型」读面——只取 `preferred` 一根；`fallbacks` 数组**不再被
   * 读取**（存量文件兼容：键还在盘上也不会再喂进任何请求链）。同一裁定的引擎面
   * 见 `FallbackChain.refs`。
   */
  private def readFallbackSection(): List[String] =
    readConfigJson()
      .flatMap(_.hcursor.downField("llm").downField("fallback").focus)
      .map { node =>
        val preferred = node.hcursor.downField("preferred").as[Option[String]].toOption.flatten.getOrElse("")
        List(preferred.trim).filter(_.nonEmpty)
      }
      .getOrElse(Nil)

  /** The config file JSON (single path entry = `PathUtil.configJsonReadPath`). */
  private def readConfigJson(): Option[io.circe.Json] =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    if !os.exists(configPath) then None
    else io.circe.parser.parse(os.read(configPath)).toOption

  /**
   * Resolve the effective model chain for `name` given its own stored chain.
   * Returns `(chain, resolvedFrom)`. Never throws: a missing root, an empty
   * chain or an unconfigured install all degrade to the seed chain.
   */
  def resolveModel(name: String, ownModel: Option[AgentModelConfig]): (AgentModelConfig, String) =
    resolveFromInputs(
      name,
      ownModel,
      nebulaChain(),
      readFallbackSection().headOption.map(r => AgentModelConfig(Some(r), Nil))
    )

  /** Shared selection policy: only an explicit preferred ref authorizes a request. */
  def resolveFromInputs(
    name: String,
    ownModel: Option[AgentModelConfig],
    mainModel: Option[AgentModelConfig],
    globalModel: Option[AgentModelConfig]
  ): (AgentModelConfig, String) =
    def explicit(c: AgentModelConfig): Boolean = c.preferred.exists(_.trim.nonEmpty)
    ownModel
      .filter(explicit)
      .filter(_ => isSettable(name))
      .map(_ -> OwnChainSource)
      .orElse(mainModel.filter(explicit).map(_ -> NebulaChainSource))
      .orElse(globalModel.filter(explicit).map(_ -> SeedSource))
      .getOrElse(AgentModelConfig.empty -> SeedSource)

  /** Historical guess, for diagnostics only; never used to authorize a request. */
  def legacySeedCandidate(): Option[String] =
    readGlobalChainDefault().headOption.orElse(readFromProviders().headOption)

  /** Global model chain from nebflow.json (`llm.model.default` + `fallbacks`). */
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

  end readGlobalChainDefault

  /**
   * Providers-derived seed: first provider (JSON field order) with models,
   * its first model. JsonObject preserves insertion order, so "first" is the
   * first provider in the user's config file.
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
            providers.toIterable.view
              .flatMap { (name, pj) =>
                pj.hcursor
                  .downField("models")
                  .focus
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
    end if
  end readFromProviders

end SchemePolicy
