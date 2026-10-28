package nebflow.shared

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import nebflow.shared.{Defaults, PathUtil}

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

/**
 * One entry of `llm.providers.*.models[]`.
 *
 * maxcfg batch (2026-09-16, author ruling): the `maxTokens` key was REMOVED
 * from the user-configurable face — the output cap is now an internal engine
 * constant (`Defaults.MaxTokens` / `Defaults.MaxThinkingBudget`). The decoder
 * is derived, so circe silently ignores a legacy `maxTokens` key still present
 * in an existing `nebflow.json` (no error, no migration needed).
 *
 * visionfix batch (2026-09-23, author ruling): the `vision` key was REMOVED on
 * the same terms — it fed a per-candidate vision bit whose consumers are all
 * deleted, and the capability face is now pessimistic-free (images are always
 * sent). Because the decoder is derived, a legacy inline `"vision"` key in an
 * existing `nebflow.json` is silently ignored: no error, no migration, and the
 * on-disk format stays valid (there is no Encoder for these types — the config
 * is written by raw-JSON deep merge, so nothing re-serializes the field back).
 *
 * @param modelMaxContext
 *   案② B3（`chain-llmstall-fix`，2026-09-21）：provider 侧**真值上界**（该 model 实际
 *   可受理的上下文长度）。持久化字段，由模型列举端点上报的 `context_length` /
 *   `context_window` 经前端写回自动填充（见 `RestApiRoutes.extractModels` +
 *   `sidebar.js` 的 `fillContextIfEmpty`）。
 *   - **缺席 ⇒ `None` ⇒ 生效值逐字等于 `contextWindow`**（旧配置 / provider 未上报
 *     时的现行为，向后兼容的硬约束——derived decoder 容忍字段缺失）；
 *   - 存在时**只可能压低**生效窗口（`min(configured, modelMaxContext)`，取数单点
 *     `ProviderRegistry.effectiveContextWindow`），不可能放大；
 *   - 与 `contextWindow` 的分工：前者 = 用户的愿望上界（UI 可改），本字段 = 物理
 *     上界（机器上报，不应手改）。
 */
case class ModelConfig(
  id: String,
  contextWindow: Int = Defaults.ContextWindow,
  description: Option[String] = None,
  capabilities: Option[List[String]] = None,
  modelMaxContext: Option[Int] = None,
  /**
   * thinking-config 批（2026-10-28，设计 §4.1）：model 级 thinking 层（三层继承
   * 的最优先层，逐字段就近）。全字段 Option ⇒ 派生 decoder 容忍缺字段，旧配置
   * 解析行为逐字节不变。解析单点 = [[ThinkingMode.resolveEffective]]。
   */
  thinking: Option[ThinkingLayerConfig] = None,
  /**
   * 思考面能力类显式覆盖（设计 §4.2：启发式只影响缺省，显式配置永远赢）。
   * 缺省 = `ThinkingFamily.forModel(id)` 家族启发（AnthropicAdapter 单点取）。
   */
  thinkingKind: Option[ThinkingKind] = None
)

object ModelConfig:
  given Decoder[ModelConfig] = deriveDecoder[ModelConfig]

case class ProviderConfig(
  baseUrl: String,
  apiKey: String,
  protocol: LlmProtocol,
  models: List[ModelConfig] = Nil,
  // Anthropic-protocol only: replay unsigned thinking blocks from assistant
  // history back to the API. DeepSeek's Anthropic-compatible endpoint REQUIRES
  // thinking blocks to be passed back in thinking mode (even without a
  // signature), while real Anthropic REJECTS them without one. Defaults per
  // providerId in ProviderRegistry; explicit config wins. thinking-config 批
  // （2026-10-28）：缺省判据从 providerId=="deepseek" 扩为「providerId 或任一
  // 模型 id 命中 DeepSeek 族」（[[ThinkingFamily.isDeepSeekFamily]]）——现配
  // cmdcode（服务 deepseek 模型族）由此根治 false 缺省的 400 隐患。
  requireThinkingPassback: Option[Boolean] = None,
  /** provider 级 thinking 层（三层继承中段；见 [[ModelConfig.thinking]]）。 */
  thinking: Option[ThinkingLayerConfig] = None,
  /** provider 级能力类覆盖（对该 provider 全部模型生效；model 级更优先）。 */
  thinkingKind: Option[ThinkingKind] = None
)

object ProviderConfig:
  given Decoder[ProviderConfig] = deriveDecoder[ProviderConfig]

case class ModelChainConfig(
  default: String,
  fallbacks: List[String] = Nil
)

object ModelChainConfig:

  given Decoder[ModelChainConfig] = Decoder.instance { c =>
    val defaultOpt = c.downField("default").as[Option[String]]
    val primaryOpt = c.downField("primary").as[Option[String]]
    val default = (defaultOpt, primaryOpt) match
      case (Right(Some(d)), _) => Right(d)
      case (Right(None), Right(Some(p))) => Right(p)
      case (Right(None), Right(None)) =>
        Left(io.circe.DecodingFailure("Missing field 'default' (alias 'primary' also accepted)", c.history))
      case (Left(err), _) => Left(err)
      case (_, Left(err)) => Left(err)
    for
      d <- default
      fallbacks <- c.downField("fallbacks").as[Option[List[String]]].map(_.getOrElse(Nil))
    yield ModelChainConfig(d, fallbacks)
  }

end ModelChainConfig

/**
 * One MCP server entry of the `mcpServers` map (mcp.json / nebflow.json).
 *
 * R3 (wait-timeout-fix, 2026-09-03 作者裁定②): `timeoutMs` — OPTIONAL
 * per-server tool-call ceiling in milliseconds, applied to `tools/call` only
 * (never to `initialize`/`tools/list`, which keep their fixed 30s
 * infrastructure-probe timeout). Semantics:
 *   - absent / null  → NO call timeout (default): the tool runs to
 *     completion like any built-in slow tool (Bash/Read); a truly wedged
 *     call is caught by the session-level backstops (no-progress ceiling /
 *     TaskStuckWatcher), replacing the removed blanket 120s client hard top.
 *   - set (e.g. 45000) → every tool of THIS server is capped at 45s; on
 *     expiry the call fails with the same error semantics as before
 *     (TimeoutException → ToolError "Error: timeout…" → next LLM turn).
 * Field naming follows the existing `stuckThresholdMs` convention
 * (milliseconds, camelCase). Decoder is derived — fully backward compatible
 * (existing configs without the field decode unchanged).
 */
case class McpServerConfig(
  command: Option[String] = None,
  args: Option[List[String]] = None,
  env: Option[Map[String, String]] = None,
  url: Option[String] = None,
  headers: Option[Map[String, String]] = None,
  enabled: Option[Boolean] = None,
  timeoutMs: Option[Long] = None,
  /**
   * stdio 子进程工作目录。插件 MCP（协议符合度批，Agent Plugins 1.0.0 §11.1-7
   * 「默认以插件根为子进程工作目录」）：插件装载校验把 cwd 归一为
   * ${PLUGIN_ROOT}/${PLUGIN_DATA} 占位形式，PluginMcpManager.acquire 启动前
   * 展开为绝对路径并注入缺省（= 插件根）。全局 nebflow.json MCP 配置此前无此
   * 字段（decoder 派生，缺省 None = 沿用进程 cwd，零行为变化）；若显式配置，
   * 相对路径按进程 cwd 解析（ProcessBuilder 自然语义）。
   */
  cwd: Option[String] = None
)

object McpServerConfig:
  given Decoder[McpServerConfig] = deriveDecoder[McpServerConfig]

  extension (cfg: McpServerConfig) def isEnabled: Boolean = cfg.enabled.getOrElse(true)

/**
 * Standalone search API config — the top-level `search` block of
 * nebflow.json (Tier 2a, P2 2026-08-25). The schema existed but was unwired
 * until P2: a paid search API is billed per call, SEPARATE from model token
 * quotas, so a model-quota DOWN must not take search down with it.
 *
 *   "search": { "provider": "zhipu", "apiKey": "<existing zhipu key>",
 *               "engine": "search_std", "baseUrl": "<optional override>",
 *               "enabled": true }
 *
 * `provider` selects the adapter (zhipu / bocha); `apiKey` reuses the
 * provider's existing key (zhipu: zero new key); `engine` picks the search
 * tier (search_std ¥0.01/call / search_pro …); `baseUrl` overrides the
 * endpoint (smoke tests point it at a local mock — the reason it exists);
 * `enabled=false` or a missing/empty apiKey skips Tier 2a entirely (graceful
 * degrade, existing users need zero migration).
 */
case class SearchConfig(
  provider: String,
  apiKey: String,
  engine: Option[String] = None,
  model: Option[String] = None,
  baseUrl: Option[String] = None,
  enabled: Option[Boolean] = None
)

object SearchConfig:
  given Decoder[SearchConfig] = deriveDecoder[SearchConfig]

/**
 * JeV decision-allocation config — the top-level `jev` block of nebflow.json
 * (JeV integration Face A / P1-A2; form precedent = [[SearchConfig]] above).
 *
 *   "jev": { "enabled": true, "provider": "typesafe-jev",
 *            "endpoint": "https://api.typesafe.ai/v1/systemone",
 *            "model": "jev-latest", "keyRef": "typesafe-api-key",
 *            "choiceHighBasePolicy": "warn" }
 *
 * ==Every field is optional ON PURPOSE==
 * `SearchConfig` requires `provider` + `apiKey`, so a partially written
 * `search` block fails to decode. The `jev` block must not repeat that: the
 * seed can write a partial block (e.g. only `{"enabled":true}`) during a cold
 * start, and a decode failure there would send `GatewayMain` down the
 * crash-recovery path and restore a `{}` snapshot — the exact failure mode the
 * `ensureLlmDefaults` note on [[NebflowServiceConfig]] documents for `llm`.
 * With everything optional, a partial/seed block decodes to its defaults and
 * the absence of the whole block decodes to `None`.
 *
 * ==Graceful degrade (zero migration)==
 * `enabled=false`, or a missing/empty `keyRef`, or a `keyRef` that resolves to
 * no readable secret all mean "the face is skipped" — existing users need no
 * migration, exactly as with `search`.
 *
 * 🔴 零超时（2026-10-19 作者拍板，「stand 设计不要有任何超时控制」）：本节
 * **不带 `timeoutMs` 配置位**——分配链的 15s 决策预算已拆除（原字段进入
 * config 即被忽略，零迁移）；决策调用完成与否靠监测手段表达（探活面 +
 * 面板可见），不用定时器杀。
 *
 * @param keyRef
 *   NAME of a secret under `~/.nebflow/secrets/` (the social/daemon reference
 *   form: the config stores a POINTER, never a value). Being a pointer rather
 *   than a credential, it is intentionally NOT matched by
 *   `ConfigService.sensitiveKeyPattern`; the pattern still covers any key
 *   adjacent to it that WOULD carry a value (e.g. `apiKey`).
 * @param choiceHighBasePolicy
 *   What to do when a choice question exceeds the provider's precision range
 *   (LAYA degrades above ~20 options). Carried as an opaque policy string for
 *   now: it feeds a WARNING in the adaptation layer, and promoting it to a
 *   typed enum would fix a product decision that is still open.
 */
case class JevConfig(
  enabled: Option[Boolean] = None,
  provider: Option[String] = None,
  endpoint: Option[String] = None,
  model: Option[String] = None,
  keyRef: Option[String] = None,
  choiceHighBasePolicy: Option[String] = None
):
  /**
   * Toggle semantics: absent means OFF is the SAFE default for a face that
   * sends node task text off-machine. (Contrast `plugins.enabled`, which
   * defaults true — there the safe direction is the other way.)
   */
  def isEnabled: Boolean = enabled.getOrElse(false)

object JevConfig:
  given Decoder[JevConfig] = deriveDecoder[JevConfig]

/**
 * Embedding 检索面配置节（task-rag 批 #1227 / §Batch 7）：顶层 `embedding` 块，
 * 驱动 OpenAI 兼容 embeddings 端点（`POST {baseUrl}/embeddings`）。
 *
 *   "embedding": { "enabled": true, "baseUrl": "https://api.openai.com/v1",
 *                  "model": "text-embedding-3-small", "keyRef": "openai-api-key",
 *                  "timeoutMs": 20000 }
 *
 * ==Every field is optional ON PURPOSE==
 * Form precedent = [[JevConfig]] (all-optional, `SearchConfig`-style graceful
 * degrade): a partially written block decodes to its defaults instead of
 * failing the whole service-config read.
 *
 * ==Graceful degrade (zero migration)==
 * `enabled` absent/false, or a missing `baseUrl`/`model`, or a `keyRef` that
 * resolves to no readable secret ⇒ the embedding face is OFF and task search
 * falls back to lexical matching — existing users need zero migration. 🔴
 * Absent means OFF is the SAFE default (same direction as `jev`): the face
 * sends task/notes text off-machine.
 *
 * @param keyRef
 *   NAME of a secret under `~/.nebflow/secrets/` (the `jev` reference form:
 *   the config stores a POINTER, never a value; resolved by the consumer).
 * @param timeoutMs
 *   Per embeddings-call deadline. None → 20000.
 */
case class EmbeddingConfig(
  enabled: Option[Boolean] = None,
  baseUrl: Option[String] = None,
  model: Option[String] = None,
  keyRef: Option[String] = None,
  timeoutMs: Option[Long] = None
):
  /**
   * Toggle semantics: absent means OFF is the safe default for a face that
   * sends task text off-machine (same direction as `JevConfig.isEnabled`).
   */
  def isEnabled: Boolean = enabled.getOrElse(false)

object EmbeddingConfig:
  given Decoder[EmbeddingConfig] = deriveDecoder[EmbeddingConfig]

/**
 * 生图 provider 子节（imagegen 双层批 2026-10-24）——`imagegen.providers.<name>` 项。
 *
 * 🔴 凭据纪律（jev/embedding 同先例）：`keyRef` 是数据根 `secrets/` 下的**名字**
 * （指针，不落值）；解析方现读在位性，缺件/空/不可读 ⇒ 该 provider 腿自动关。
 */
case class ImageGenProviderConfig(
  `type`: String,
  model: String,
  baseUrl: Option[String] = None,
  keyRef: Option[String] = None,
  timeoutMs: Option[Long] = None
)

object ImageGenProviderConfig:
  given Decoder[ImageGenProviderConfig] = deriveDecoder[ImageGenProviderConfig]

/**
 * 生图面顶层节（imagegen 双层批 2026-10-24）——nebflow.json 顶层 `imagegen` 块：
 *
 *   "imagegen": { "enabled": true, "defaultProvider": "zhipu",
 *                 "providers": { "zhipu": { "type": "cogview", "model":
 *                   "cogview-3-flash", "keyRef": "zhipu-api-key" } } }
 *
 * ==Graceful degrade (zero migration)==：`enabled` 缺省 false——面把 prompt 文本送出
 * 本机，缺省必须关（同 `jev`/`embedding` 方向）。
 */
case class ImageGenConfig(
  enabled: Option[Boolean] = None,
  defaultProvider: Option[String] = None,
  providers: Option[Map[String, ImageGenProviderConfig]] = None
):
  def isEnabled: Boolean = enabled.getOrElse(false)

object ImageGenConfig:
  given Decoder[ImageGenConfig] = deriveDecoder[ImageGenConfig]

/**
 * Delegate 判定机 utility model 配置节（worktree-judge-fifo 批 #1224 增补 §一/§3.1
 * —— SmallJudge 的「新可配 utility model」）: 顶层 `judge` 节，全字段可选。
 *
 * @param utilityModel
 *   SmallJudge（LLM 兜底腿）的模型 ref（`providerId/modelId` 形态）。None =
 *   缺省口径「根链首选」（registry 全局候选链首条）。Jev 优先腿不受本键影响
 *   （Jev 走 `jev` 节自带的 provider/model）。
 * @param timeoutMs
 *   判定调用时限（worktree 判定与终态三态判定共用）。None → 默认 30s（判定
 *   有界：spawn 腿与终态腿都绝不因判定机长悬）。
 */
case class JudgeConfig(
  utilityModel: Option[String] = None,
  timeoutMs: Option[Long] = None
)

object JudgeConfig:
  given Decoder[JudgeConfig] = deriveDecoder[JudgeConfig]

/**
 * Collaboration-sync remote 节（batch78 三裁②，BA:330 移出＋参数化）：顶层
 * `collabSync` 节。`remoteUrl` 缺省 `None` ⇒ dispatcher 提示词的协作同步句
 * 整句不渲染（零本地信息缺省面）；配置存在时渲染同步句（句内 remote 名取
 * 配置键名段 `collabSync`，非硬编码）。渲染时现读（BuiltinAgents.DispatcherPrompt
 * 为 def）＋fail-safe（配置读失败等同未配置）。全字段可选：半节解码为缺省
 * 而非抛（同 `jev` / `judge` 节教训）。
 */
case class CollabSyncConfig(
  remoteUrl: Option[String] = None
)

object CollabSyncConfig:
  given Decoder[CollabSyncConfig] = deriveDecoder[CollabSyncConfig]

/**
 * Stream watchdog thresholds override (flow-node supervision P3, 2026-08-26):
 * llm.streamTimeouts { firstTokenSec, inactivitySec, noProgressSec } — each
 * independently optional; None → Defaults. Applied at boot (LlmInterface
 * .applyStreamTimeouts in GatewayMain); config changes take effect on restart.
 */
case class StreamTimeoutsConfig(
  firstTokenSec: Option[Int] = None,
  inactivitySec: Option[Int] = None,
  noProgressSec: Option[Int] = None
)

object StreamTimeoutsConfig:
  given Decoder[StreamTimeoutsConfig] = deriveDecoder[StreamTimeoutsConfig]

case class ServiceLlmConfig(
  // provider 缺失 → 空映射 = 未配置 LLM 的合法中间态（种子写 plugins.trust
  // 时可能只落 plugins 键、无 llm 节；解码缺省此处，见 NebflowServiceConfig.llm）。
  providers: Map[String, ProviderConfig] = Map.empty,
  /**
   * #339 D-b：llm.model 已退役——默认模型唯一来源是 Nebula 主链（agent.json
   * `model` 键；未配 = provider 推导种子链，SchemePolicy 单点）。Option 化的
   * schema 仅容忍存量文件的 llm.model 节（可解析但被忽略）。
   */
  model: Option[ModelChainConfig] = None,
  streamTimeouts: Option[StreamTimeoutsConfig] = None
)

object ServiceLlmConfig:
  given Decoder[ServiceLlmConfig] = deriveDecoder[ServiceLlmConfig]

/**
 * 全局思考配置（`nebflow.json` 顶层 `thinkingConfig` 节）。
 *
 * thinking-config 批（2026-10-28，设计 §4.1/§4.3）：旧键名、字段、缺省全部
 * 不动——本节语义降格为三层继承的「全局缺省层」（继承基座）。新增两个可选
 * 字段：`effort`（产品档位，effort 型 wire 的档位来源）与 `clearThinking`
 * （仅 GLM 保留式思考消费）。decoder 手写容忍缺字段；encoder 手写省略 None
 * 字段 ⇒ 未配新字段时广播/落盘 JSON 与旧形态逐字节一致。
 *
 * @param enabled
 *   off 的唯一表示（产品档位里的 off 收敛到开关位，避免同一语义两处表示）。
 *   注意：强制思考模型（ThinkingKind.Toggle）的 off 在 wire 上兜底按 enabled 发。
 * @param budgetTokens
 *   旧字段语义不变：budget 型 wire（Anthropic ≤4.5 兜底）的数值来源 +
 *   max_tokens 推导输入；对 effort 型面仅作继承基座输入，不再是唯一旋钮。
 * @param effort
 *   产品档位 low|medium|high|max（三层继承逐字段就近）。三档面映射
 *   medium→high 单点 = EffortWire.threeTier。
 * @param clearThinking
 *   GLM 保留式思考（`thinking.clear_thinking:false`）——仅显式配置时上线。
 */
case class ThinkingConfig(
  enabled: Boolean = true,
  budgetTokens: Int = 32000,
  effort: Option[ThinkingEffort] = None,
  clearThinking: Option[Boolean] = None
)

object ThinkingConfig:

  /**
   * 手写 encoder：省略 None 字段——旧值（未配 effort/clearThinking）落盘与
   * 广播 JSON 与 deriveEncoder 旧形态逐字节一致（零破坏硬约束）。
   */
  given io.circe.Encoder[ThinkingConfig] = io.circe.Encoder.instance { tc =>
    val base: List[(String, Json)] = List(
      "enabled" -> tc.enabled.asJson,
      "budgetTokens" -> tc.budgetTokens.asJson
    )
    val extra: List[(String, Json)] = List(
      tc.effort.map(e => "effort" -> e.asJson),
      tc.clearThinking.map(c => "clearThinking" -> c.asJson)
    ).flatten
    io.circe.Json.obj((base ++ extra)*)
  }

  given io.circe.Decoder[ThinkingConfig] = io.circe.Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(true))
      budgetTokens <- c.downField("budgetTokens").as[Option[Int]].map(_.getOrElse(32000))
      effort <- c.downField("effort").as[Option[ThinkingEffort]]
      clearThinking <- c.downField("clearThinking").as[Option[Boolean]]
    yield ThinkingConfig(enabled, budgetTokens, effort, clearThinking)
  }

  /**
   * Convert to the raw JSON shape expected by LLM adapters (e.g. Anthropic extended thinking).
   *
   * 语义形态（thinking-config 批起）：enabled → budget 语义载体
   * `{"type":"enabled","budget_tokens":N}`；disabled → `Json.Null`（= 语义 off）。
   * **线上形态由 adapter 按能力类翻译**（AnthropicAdapter.wireThinkingFragment
   * 单点）：off 在 Budget/Toggle/EffortTopLevel 类上省略键（不再发
   * `"thinking": null`），EffortAnthropic 类发 `{"type":"disabled"}`。
   */
  def toLlmJson(tc: ThinkingConfig): io.circe.Json =
    if tc.enabled then
      io.circe.Json.obj(
        "type" -> "enabled".asJson,
        "budget_tokens" -> tc.budgetTokens.asJson
      )
    else io.circe.Json.Null

  /**
   * 四修④（2026-10-07 修复令）：worker 侧思考预算帽（纯函数——spec 判红锚）。
   * 派生会话（depth>0）不得继承根会话被用户调高的 thinking 预算——钳到
   * [[Defaults.SubagentThinkingBudgetCap]]（32000，UI 客户端缺省形态）以下；
   * 低于帽的配置原样通过（只设上限，不抬线下）。enabled=false 时预算字段
   * 照钳不误——本函数维护的单一不变式是「worker 预算 ≤ 帽」，与开关无关
   * （关着的时候字段不出口，但也不该滞留超帽值待人翻转开关）。
   */
  def cappedForSubagent(tc: ThinkingConfig): ThinkingConfig =
    if tc.budgetTokens > Defaults.SubagentThinkingBudgetCap then
      tc.copy(budgetTokens = Defaults.SubagentThinkingBudgetCap)
    else tc
end ThinkingConfig

case class NebflowServiceConfig(
  // 2026-09-07 插件信任持久化修复：llm 缺省 = 未配置 LLM 的合法中间态。种子在
  // 冷启动（空 home）把 plugins.trust 写进一个无 llm 节的配置（mutateNebflowJson
  // 首写 Json.obj() 骨架）；若无缺省，重启时 decode 抛 "Missing required field
  // '.llm'" → GatewayMain 走 crash-recovery → restoreLatest 用冷启动期的 {} 快照
  // 打回 → 信任表被抹掉。配合 Config.ensureLlmDefaults 缺省注入 + 本默认值，使
  // 该形态解码为 defaultServiceConfig 等价物，不再触发恢复。
  llm: ServiceLlmConfig = ServiceLlmConfig(providers = Map.empty),
  mcpServers: Option[Map[String, McpServerConfig]] = None,
  search: Option[SearchConfig] = None,
  thinkingConfig: Option[ThinkingConfig] = None,
  // P0 阶段 3（2026-08-18）：TaskStuckWatcher 卡死判定阈值（ms）。
  // None → Defaults.StuckThresholdMs（10min）。显式配置可收紧（测试/调试）。
  stuckThresholdMs: Option[Long] = None,
  /**
   * 冻结调度（freeze-schedule，#337 黑名单语义）：顶层 workSchedule 节原样 JSON
   * （键名保留前端契约，语义=冻结时段，支持跨午夜）——FreezeSchedule.load
   * fail-safe 解析（非法配置视为关闭）。updateConfig 深合并保留未提及顶层键。
   */
  workSchedule: Option[io.circe.Json] = None,
  /**
   * Bash 卡死防护（#26 前台直跑）：bashBackgroundHardTimeoutMs（默认 30min
   * 硬超时起点）、bashStuckWindowSec（默认 120s 停滞窗口）、
   * bashHealthCheckIntervalSec（默认 30s 健康检查间隔，测试/冒烟可注入小值
   * 加速验证）——只服务显式 run_in_background 后台任务。None → Defaults 值。
   *
   * main-agent-nonblocking 批（2026-10-21 作者裁定）新增
   * `bashForegroundWaitBudgetMs`：前台等待预算 ms（默认 120s，作者可调）——
   * 前台命令超预算即自动转后台（不杀、完成照旧通知），主 agent 前台零阻塞。
   * 实例级一份（→ BashResilienceConfig.foregroundWaitBudgetMs → 全部会话）。
   */
  bashBackgroundHardTimeoutMs: Option[Long] = None,
  bashStuckWindowSec: Option[Int] = None,
  bashHealthCheckIntervalSec: Option[Int] = None,
  bashForegroundWaitBudgetMs: Option[Long] = None,
  /**
   * 工具结果 TTL 清理（#341，tool-result-ttl 设计件）：顶层
   * toolResultTtl 节原样 JSON——ToolResultTtlConfig.load fail-safe 解析（非法
   * 配置视为关闭）。默认关（enabled=false）。request-only 清理，会话文件不动。
   */
  toolResultTtl: Option[io.circe.Json] = None,
  /**
   * 执行环境 provider 配置节（§G.1；拆围栏批 S3 起语义 = design §4.2 的
   * `sandbox.provider`）：顶层 sandbox 节原样 JSON——SandboxConfig.load fail-safe
   * 解析（absent → 缺省 provider=host / enabled=true）。provider 取值非法或
   * container/auto 未实现 ⇒ 显式失败（不静默回落宿主执行）；enabled=false 保留为
   * 旧行为回退点（§4.5）。
   */
  sandbox: Option[io.circe.Json] = None,
  /**
   * LLM 日志记录开关持久化（2026-09-13「默认关」批，D-A）：顶层 `llmLog` 节
   * 原样 JSON——`LlmLogWriter.loadEnabled` fail-safe 解析（缺失 / 非法 ⇒ None
   * ⇒ 保持默认关）。None（既有安装无落盘值）与 `{"enabled":false}` 行为等价；
   * 仅用户显式开/关（WS `setLlmLog`）才写入本键。
   */
  llmLog: Option[io.circe.Json] = None,
  /**
   * daemon 配置面板（daemonpanel Phase A）的顶层 `daemonPanel` 节原样 JSON。
   * 现读唯一消费者 = `DaemonPanelSchema.allowWeb`：`kind:"web"` 逃生口**默认
   * 关闭**，只有本节显式 `{"allowWeb":true}` 才生效（缺省/非法 ⇒ 拒绝，fail-closed）。
   * 开关本身是作者动作，agent 改它会留下可见 diff。无效值不影响其它键解码。
   */
  daemonPanel: Option[io.circe.Json] = None,
  /**
   * JeV 决策分配面（Face A / P1-A2）的顶层 `jev` 节——见 [[JevConfig]]。
   * `None` = 未配置 ⇒ 整个面短路走现行路径（零迁移）；本节**全字段可选**，
   * 种子中间态（只落 `{"enabled":true}` 等半节）解码为缺省而非抛
   * `Missing required field` ⇒ 防 GatewayMain 走 crash-recovery 用 `{}` 快照
   * 打回（同 `llm` 节的既有教训）。
   */
  jev: Option[JevConfig] = None,
  /**
   * Delegate 判定机 utility model 节（worktree-judge-fifo 批 #1224 增补）——见
   * [[JudgeConfig]]。`None` = 未配置 ⇒ SmallJudge 走「根链首选」缺省、Jev 优先
   * 腿照常由 `jev` 节决定；全字段可选，半节解码为缺省而非抛（同 `jev` 节教训）。
   */
  judge: Option[JudgeConfig] = None,
  /**
   * Collaboration-sync remote 节（batch78 三裁②，BA:330 移出＋参数化）——见
   * [[CollabSyncConfig]]。`None` = 未配置 ⇒ dispatcher 提示词的协作同步句整句
   * 不渲染；渲染时现读，配置改动即席生效（无重启门槛）。
   */
  collabSync: Option[CollabSyncConfig] = None,
  /**
   * Embedding 检索面节（task-rag 批 #1227 / §Batch 7）——见 [[EmbeddingConfig]]。
   * `None` = 未配置 ⇒ embedding 面关、任务检索走词法降级（零迁移）；全字段
   * 可选，半节解码为缺省而非抛（同 `jev` / `judge` 节教训）。
   */
  embedding: Option[EmbeddingConfig] = None,
  /**
   * 生图面节（imagegen 双层批 2026-10-24）——顶层 `imagegen` 块。`None` = 未配置 ⇒
   * 生图面关（腿①工具提交即给可行动配置文案；腿②路由 503——零迁移安全缺省，方向
   * 同 `jev`/`embedding`）；全字段可选、半节解码为缺省而非抛（同 `jev` 节教训）。
   * 适配器形态按协议划分：`cogview` / `openai-images`（gpt-image-1 与一切 OpenAI
   * 兼容 images 端点，含豆包 Seedream/火山方舟兼容模式）/ `imagen`（预留位）。
   */
  imagegen: Option[ImageGenConfig] = None
)

object NebflowServiceConfig:
  given Decoder[NebflowServiceConfig] = deriveDecoder[NebflowServiceConfig]

object Config:
  // def (not val): PathUtil.dataRoot and the config-file dual-read must be
  // resolved per access — an object val would freeze the first-touched
  // dataRoot and break test isolation (setDataRoot after first access).
  def NebflowHome: os.Path = PathUtil.dataRoot
  def DefaultConfigPath: os.Path = PathUtil.configJsonReadPath(NebflowHome)

  private val envVarLogger = nebflow.shared.NebflowLogger.forName("nebflow.config")

  def resolveEnvVars(str: String): String =
    """\$\{([^}]+)\}""".r.replaceAllIn(
      str,
      m =>
        val key = m.group(1)
        sys.env.get(key) match
          case Some(value) => java.util.regex.Matcher.quoteReplacement(value)
          case None =>
            envVarLogger.infoSync(
              "Environment variable $" + key + " not found in config value, replacing with empty string"
            )
            ""
    )

  def parseModelRef(ref: String): (String, String) =
    val idx = ref.indexOf('/')
    if idx == -1 then throw new IllegalArgumentException(s"Invalid model ref \"$ref\", expected \"providerId/modelId\"")
    (ref.take(idx), ref.drop(idx + 1))

  def loadServiceConfig(configPath: Option[String] = None): NebflowServiceConfig =
    val path = configPath match
      case Some(p) => os.Path(p, os.pwd)
      case None => DefaultConfigPath

    if !os.exists(path) then defaultServiceConfig
    else
      val raw = os.read(path).trim
      if raw.isEmpty || raw == "{}" || raw.replace("\n", "").replace("\r", "").trim == "{}" then defaultServiceConfig
      else loadFromJson(raw)

  private def loadFromJson(raw: String): NebflowServiceConfig =
    val json = parse(raw) match
      case Right(j) => j
      case Left(err) => throw new RuntimeException(s"Invalid JSON in config: ${err.message}")

    // Resolve env vars in JSON string values
    val resolvedJson = resolveEnvVarsInJson(json)
    // 2026-09-07 插件信任持久化修复：合法中间态（种子写 plugins.trust 时可能无
    // llm 节、或 llm 无 providers）缺省注入，使解码成功而非抛
    // "Missing required field '.llm'"——否则 GatewayMain 走 crash-recovery 用
    // 冷启动期的 {} 快照打回 nebflow.json，信任表被抹掉。
    val normalized = ensureLlmDefaults(resolvedJson)

    normalized.as[NebflowServiceConfig] match
      case Right(cfg) => cfg
      case Left(err) =>
        val path = io.circe.CursorOp.opsToPath(err.history)
        throw new RuntimeException(s"Config parse error at '$path': ${err.message}")

  end loadFromJson

  /**
   * 仅当 llm 节缺席、或 llm 存在但 providers 缺席时补一个空 providers；
   * 其余字段（含非法形态）原样保留——非法形态仍会正常解码失败，不被掩盖。
   *
   * 🔴 公开（而非 private）：任何**从原始 JSON 直接解**
   * `NebflowServiceConfig` 的调用点都必须先过这里，否则冷启动中间态
   * （种子只写 `plugins.trust`、无 `llm` 节）会因 `deriveDecoder` **不认**
   * case class 默认值而解失败。实测缺陷（personal-agent 批）：WS 面
   * `getOnboardingModels` 直接 raw-as 解码 ⇒ 全新 home 上回
   * `{type:"error","message":"current config is unreadable"}`，引导的模型步骤
   * 在主路径（首装）上直接断掉。单一实现对源 = [[loadFromJson]] 亦走本函数。
   */
  private[nebflow] def ensureLlmDefaults(json: Json): Json =
    json.asObject match
      case None => json
      case Some(obj) =>
        val llmNorm = obj("llm") match
          case None =>
            // 顶层无 llm 节（种子中间态）：注入空 providers 的 llm
            Json.obj("providers" -> Json.obj())
          case Some(l) =>
            l.asObject match
              case Some(llmObj) if !llmObj.contains("providers") =>
                Json.fromJsonObject(llmObj.add("providers", Json.obj()))
              case _ => l // 已含 providers 或非对象 → 原样
        Json.fromJsonObject(obj.add("llm", llmNorm))

  /** Minimal default config used when no config file exists. */
  private lazy val defaultServiceConfig: NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map.empty
        // #339：占位 llm.model default 已删——未配置时默认 preset 链为空，
        // registry 走"首 provider 首模型"兜底（与首配前的真实状态一致）
      )
    )

  private def resolveEnvVarsInJson(json: Json): Json =
    json.fold(
      jsonNull = Json.Null,
      jsonBoolean = b => Json.fromBoolean(b),
      jsonNumber = n => Json.fromJsonNumber(n),
      jsonString = s => Json.fromString(resolveEnvVars(s)),
      jsonArray = arr => Json.fromValues(arr.map(resolveEnvVarsInJson)),
      jsonObject = obj =>
        Json.fromJsonObject(
          io.circe.JsonObject.fromIterable(
            obj.toList.map { case (k, v) => (k, resolveEnvVarsInJson(v)) }
          )
        )
    )
end Config
