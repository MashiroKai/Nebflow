package nebflow.shared

import io.circe.Encoder
import io.circe.syntax.*
import io.circe.{Decoder, HCursor, Json}

/**
 * 思考模式能力域类型（thinking-config 实施批，2026-10-28；设计
 * `docs/design/20261028_215449_thinking-config-research__delegate-nebflow-ca5f09f4.zh-CN.md`
 * §4.1/§4.2 照案实施，不重设计）。
 *
 * 行业已从「固定 token 预算」迁往「effort 档位」：Anthropic（output_config.effort）、
 * DeepSeek（output_config.effort 三档，budget_tokens 官方明示忽略）、Kimi K3（顶层
 * reasoning_effort 三档，官方明示应移除 thinking 配置）、GLM（reasoning_effort 三档，
 * GLM-5.3 系强制思考不可关）。引擎 2026-08 定型的 budget 单旋钮与四家现役面全部
 * 错位——本文件给出能力类（[[ThinkingKind]]）、逐模型族判据（[[ThinkingFamily]]）、
 * 产品档位（[[ThinkingEffort]]）与三层继承解析（[[ThinkingMode.resolveEffective]]）。
 *
 * 铁律（与设计 §4.2 一致）：**启发式只影响缺省，显式配置永远赢**——
 * `ModelConfig.thinkingKind` / `ProviderConfig.thinkingKind` 显式给出时逐字采用。
 */

/** 产品档位（对齐 Anthropic 五档与各家交集）。`off` 不入本枚举：off 的唯一
  * 表示是 `enabled=false`（设计 §4.1 的 off 档在实现里收敛到开关位，避免同一
  * 语义出现两个可互相矛盾的表示）。
  */
enum ThinkingEffort:
  case Low, Medium, High, Max

  /** wire 名（reasoning_effort / output_config.effort 的档位拼写）。 */
  def wireName: String = this match
    case Low    => "low"
    case Medium => "medium"
    case High   => "high"
    case Max    => "max"

  /** 三档面（GLM / DeepSeek / Kimi K3 官方仅收 low/high/max）的映射：
    * medium→high（DeepSeek 官方映射表；GLM Coding Plan 同型；Kimi 三档面
    * medium→high 为作者已裁定的推荐取）。low/high/max 直通。
    */
  def toThreeTier: ThinkingEffort = this match
    case Medium => High
    case other  => other

object ThinkingEffort:
  given Decoder[ThinkingEffort] = Decoder.decodeString.emap {
    case "low"    => Right(Low)
    case "medium" => Right(Medium)
    case "high"   => Right(High)
    case "max"    => Right(Max)
    case other    => Left(s"Unknown thinking effort: $other (low|medium|high|max)")
  }

  given Encoder[ThinkingEffort] = Encoder.encodeString.contramap(_.wireName)

/** 把产品档位映射到某个能力类的 wire 档位（单点；spec 钉）。 */
object EffortWire:
  /** 三档面（DeepSeek output_config / Kimi K3 顶层 / GLM 顶层）：medium→high。 */
  def threeTier(effort: ThinkingEffort): String = effort.toThreeTier.wireName

/**
 * effort 档位 wire 点亮门（第二段腿，thinking-config 实施批 2026-10-28）。
 *
 * 设计 §6-1：三家 anthropic 兼容面（zhipu / api.kimi.com / cmdcode）对
 * `output_config.effort` / 顶层 `reasoning_effort` 的真实接受度官方无文档 ⇒
 * 逐族用探测定性（空请求/极小请求看 4xx 参数报错，零/极小配额）核定后才置真。
 * 未核定 = false = 该族不发 effort 参数（第一段的「去无效参数」无此依赖）。
 * 每个常量在探测定性后回填证据注释（结论同步回填设计文档 §2.3/§2.4）。
 */
object ThinkingEffortGate:

  /** deepseek 族 `output_config.effort`：官方 anthropic 面字段支持表明示
    * Supported，但（a）现配端点 cmdcode 经探测定性**没有可服务的 anthropic 面**
    * （POST /provider/v1/messages → 400 「Model "deepseek/deepseek-v4.1-flash" is
    * not supported on this endpoint. Use /provider/v1/chat/completions for OpenAI
    * and OSS models.」，2026-10-29 探测）；（b）官方 anthropic 端点无凭据不可探测。
    * 该族 wire（EffortAnthropic）现无活配置 ⇒ 门保持关；未来接 anthropic 协议
    * deepseek 端点时凭官方文档 + 探测置真。
    */
  val deepseekEffort: Boolean = false

  /** kimi K3 族顶层 `reasoning_effort`：**已点亮**（探测定性 2026-10-29，
    * api.kimi.com/coding/v1/messages：`reasoning_effort:"high"` → 200、
    * `reasoning_effort:"medium"` → 200（零 400 参数报错；探测证据
    * reports/20261029_thinking-config-probe-evidence.md）。platform.kimi.com
    * 官方文档原生面语义 = low/high/max，medium 由 EffortWire.threeTier 客户端
    * 映射 high（作者裁定取 high）。
    */
  val kimiK31Effort: Boolean = true

  /** glm 族顶层 `reasoning_effort`：**已点亮**（探测定性 2026-10-29，
    * open.bigmodel.cn/api/anthropic/v1/messages：`reasoning_effort:"high"` → 200、
    * `"medium"` → 200（零 400 参数报错；同上证据文件）。智谱官方映射
    * medium→high（Coding Plan 面文档），EffortWire.threeTier 客户端先行映射。
    */
  val glmEffort: Boolean = true

  /**
   * kind × 模型 × 档位 → 本请求要 deepMerge 的 effort 片段（None = 不发）。
   * 族判据与 [[ThinkingFamily.forModel]] 同表（glm 顶层 / K3 顶层 /
   * deepseek output_config；kimi-for-coding 族无 effort 面，永不确定真）。
   */
  def effortFragment(kind: ThinkingKind, model: String, effort: Option[ThinkingEffort]): Option[Json] =
    val m = model.toLowerCase
    (kind, effort) match
      case (ThinkingKind.EffortAnthropic, Some(e)) if deepseekEffort =>
        Some(Json.obj("output_config" -> Json.obj("effort" -> EffortWire.threeTier(e).asJson)))
      case (ThinkingKind.EffortTopLevel, Some(e)) if kimiK31Effort =>
        Some(Json.obj("reasoning_effort" -> EffortWire.threeTier(e).asJson))
      case (ThinkingKind.Toggle, Some(e)) if glmEffort && m.contains("glm") =>
        Some(Json.obj("reasoning_effort" -> EffortWire.threeTier(e).asJson))
      case _ => None
end ThinkingEffortGate

/**
 * 模型思考面的能力类（设计 §4.2 wire 翻译表的行；AnthropicAdapter 单点翻译）。
 */
enum ThinkingKind:
  /** Anthropic ≤4.5 语义：`thinking:{type:enabled, budget_tokens:N}`；off → 省略键
    * （修掉旧 `"thinking": null` 线上形态）。
    */
  case Budget
  /** DeepSeek 族 anthropic 面：`thinking:{type:enabled}`——**不发 budget_tokens**
    * （官方字段支持表白纸黑字 "budget_tokens is ignored"）；off → `thinking:{type:disabled}`
    * （官方支持关）。effort 档位走 `output_config.effort`（核定后点亮）。
    */
  case EffortAnthropic
  /** 强制思考开关型（GLM-5.3 系、kimi-for-coding/k2.x 系）：`thinking:{type:enabled}`，
    * **不发 budget_tokens**；off 是错误路径（disabled→400）→ 引擎兜底按 enabled 发。
    */
  case Toggle
  /** Kimi K3 系：**完全不发 thinking 配置**（官方迁移指引明示 K3 应移除）；
    * 深度 = 顶层 `reasoning_effort`（核定后点亮）。
    */
  case EffortTopLevel

object ThinkingKind:
  given Decoder[ThinkingKind] = Decoder.decodeString.emap {
    case "budget"          => Right(Budget)
    case "effortAnthropic" => Right(EffortAnthropic)
    case "toggle"          => Right(Toggle)
    case "effortTopLevel"  => Right(EffortTopLevel)
    case other =>
      Left(s"Unknown thinkingKind: $other (budget|effortAnthropic|toggle|effortTopLevel)")
  }

/**
 * 逐模型族判据（缺省启发式单点；显式 `thinkingKind` 配置永远赢）。
 *
 * 前缀表（设计 §4.2：glm / kimi / deepseek 前缀 + anthropic 兜底 budget 型）：
 *  - `glm`                       → Toggle（GLM-5.3 系强制思考；5.2 系可关但默认开，
 *                                  兜底按强制处理是保守方向，5.2 用户可显式配 kind）
 *  - `deepseek`                  → EffortAnthropic（官方 anthropic 面：budget 被忽略）
 *  - `k3`（kimi-k3.1-* 等）      → EffortTopLevel（K3 迁移指引：移除 thinking 配置）
 *  - `kimi`（kimi-for-coding 等）→ Toggle（k2.7-code 系：type 仅 enabled，disabled→400）
 *  - 其余                        → Budget（真 Anthropic / 未知端点的兼容兜底）
 */
object ThinkingFamily:

  def forModel(model: String): ThinkingKind =
    val m = model.toLowerCase
    if m.contains("glm") then ThinkingKind.Toggle
    else if m.contains("deepseek") then ThinkingKind.EffortAnthropic
    else if m.contains("k3") then ThinkingKind.EffortTopLevel
    else if m.contains("kimi") then ThinkingKind.Toggle
    else ThinkingKind.Budget

  /** DeepSeek 模型族判定（requireThinkingPassback 的代码面判据——旁支修复：
    * 旧启发式只认 providerId=="deepseek"，现配 providerId 是 cmdcode（服务
    * deepseek 模型族）⇒ 缺省 false ⇒ 跨 provider 历史回退存在 400 隐患）。
    */
  def isDeepSeekFamily(model: String): Boolean = model.toLowerCase.contains("deepseek")

/** provider/model 级 `thinking` 层节点：全字段 Option——「未设的字段逐项上溯，
  * 不整块覆盖」（设计 §4.1 按字段就近继承）的精确表示。decoder 派生 ⇒ 旧配置
  * 解析行为逐字节不变。
  */
case class ThinkingLayerConfig(
  enabled: Option[Boolean] = None,
  budgetTokens: Option[Int] = None,
  effort: Option[ThinkingEffort] = None,
  clearThinking: Option[Boolean] = None
)

object ThinkingLayerConfig:
  given Decoder[ThinkingLayerConfig] = io.circe.generic.semiauto.deriveDecoder[ThinkingLayerConfig]

/**
 * 三层继承解析（model > provider > 全局；逐字段就近，设计 §4.1）。
 * 旧单旋钮迁移等价：两层都缺省 ⇒ 逐字等于全局节。
 */
object ThinkingMode:

  def resolveEffective(
    model: Option[ThinkingLayerConfig],
    provider: Option[ThinkingLayerConfig],
    global: ThinkingConfig
  ): ThinkingConfig =
    def pick[A](f: ThinkingLayerConfig => Option[A]): Option[A] =
      model.flatMap(f).orElse(provider.flatMap(f))
    ThinkingConfig(
      enabled = pick(_.enabled).getOrElse(global.enabled),
      budgetTokens = pick(_.budgetTokens).getOrElse(global.budgetTokens),
      effort = pick(_.effort).orElse(global.effort),
      clearThinking = pick(_.clearThinking).orElse(global.clearThinking)
    )

  // ── per-model thinking 层的持久化 merge（设置面板写路；纯函数，spec 钉）────

  /**
   * 模型层 patch：`Some(None)` = 清除该键（回到继承），`None` = 不动，`Some(Some(v))`
   * = 设值。decoder 区分「键缺席」与「显式 null」（circe 的 Option decoder 把两者
   * 都折成 None，所以手写）。
   */
  case class ThinkingLayerPatch(
    enabled: Option[Option[Boolean]] = None,
    effort: Option[Option[ThinkingEffort]] = None
  )

  object ThinkingLayerPatch:
    given Decoder[ThinkingLayerPatch] = Decoder.instance { c =>
      for
        enabled <- optPatchField[Boolean](c, "enabled")
        effort <- optPatchField[ThinkingEffort](c, "effort")
      yield ThinkingLayerPatch(enabled, effort)
    }

    private def optPatchField[A: Decoder](c: HCursor, key: String): Decoder.Result[Option[Option[A]]] =
      c.downField(key).focus match
        case None            => Right(None) // 键缺席 = 不动
        case Some(Json.Null) => Right(Some(None)) // 显式 null = 清除（回继承）
        case Some(node)      => node.as[A].map(a => Some(Some(a)))

  /** patch 应用到单个 model 节点：只动 thinking 键内被 patch 的键；patch 后节点
    * 为空 ⇒ 整个 thinking 键摘除（= 完全继承）。
    */
  private[shared] def applyLayerPatch(modelNode: Json, patch: ThinkingLayerPatch): Json =
    val existing = modelNode.hcursor.downField("thinking").focus.getOrElse(Json.obj())
    var obj = existing.asObject.getOrElse(io.circe.JsonObject.empty)
    patch.enabled.foreach {
      case Some(b) => obj = obj.add("enabled", b.asJson)
      case None    => obj = obj.remove("enabled")
    }
    patch.effort.foreach {
      case Some(e) => obj = obj.add("effort", e.asJson)
      case None    => obj = obj.remove("effort")
    }
    if obj.isEmpty then modelNode.mapObject(_.remove("thinking"))
    else modelNode.mapObject(_.add("thinking", Json.fromJsonObject(obj)))

  /**
   * 把模型层 patch 落进整份 nebflow.json（纯函数）：定点改
   * `llm.providers.<p>.models[id==m].thinking`，其余字节不动。未知 provider /
   * model ⇒ Left（fail-loud，不静默）。
   */
  def patchLayerIntoConfig(
    config: Json,
    providerId: String,
    modelId: String,
    patch: ThinkingLayerPatch
  ): Either[String, Json] =
    for
      providerNode <- config.hcursor.downField("llm").downField("providers").downField(providerId).focus
        .toRight(s"unknown provider: $providerId")
      models <- providerNode.hcursor.downField("models").as[List[Json]].left
        .map(_ => s"provider $providerId has no models array")
      hit = models.exists(_.hcursor.downField("id").as[String].toOption.contains(modelId))
      _ <- Either.cond(hit, (), s"unknown model: $modelId")
      patchedModels = models.map { m =>
        if m.hcursor.downField("id").as[String].toOption.contains(modelId) then applyLayerPatch(m, patch) else m
      }
      patchedProvider = providerNode.mapObject(_.add("models", Json.fromValues(patchedModels)))
    yield
      config.mapObject { top =>
        val llm = top("llm").getOrElse(Json.obj()).mapObject { llmObj =>
          val providers = llmObj("providers").getOrElse(Json.obj()).mapObject(_.add(providerId, patchedProvider))
          llmObj.add("providers", providers)
        }
        top.add("llm", llm)
      }
end ThinkingMode
