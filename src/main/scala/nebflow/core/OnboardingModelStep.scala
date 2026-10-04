package nebflow.core

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.*

/**
 * Onboarding 模型步骤的引擎侧单点（personal-agent 批 2026-10-04，作者第 2 条）。
 *
 * **与设置系统对齐**（硬要求）：不再自由填空——从**已配置 provider** 检出可用模型
 * 列表供选择，并支持设置 context window；两者都写回**与设置面同一个配置存储**
 * （`NebflowServiceConfig.llm.providers`），做到 onboarding 改完、设置面可见且一致，
 * 反向亦然（同一存储 = 双向一致的结构保证；spec 以「同一次读写往返」证明）。
 *
 * 数据源复用设置面既有两个通道：
 *   1. **已配置面**：`config.llm.providers`（设置面板保存的就是它）——离线可用，
 *      onboarding 的模型列表主路径走这条（用户已经填过 provider 就能选）。
 *   2. **在线探测面**：`POST /api/provider/models`（`PresenceRoutes`）——设置面板
 *      用它拉某个 provider 的实时模型列表。本对象不重复实现探测，只把「已配置
 *      列表」读成 onboarding 能用的形状（同一个列表数据源，不造第二份）。
 *
 * **context window 接入注入预算**（⑥）：值写进 provider 的
 * `models[].contextWindow`，随后经既有单点链
 * `ProviderRegistry.effectiveContextWindow` → `ModelCandidate.contextWindow` →
 * `AgentState.contextWindow` → `MemoryBudget.injectionCapBytes` 生效。
 * 🔴 **不另立旁路**：本对象不复制 clamp 算式，只写配置字段。
 */
object OnboardingModelStep:

  /** 面板一行模型（camelCase，与设置面 `ProviderRegistry.getAllModelsDetailed` 同形）。 */
  final case class ModelRow(
    ref: String,
    providerId: String,
    modelId: String,
    displayLabel: String,
    contextWindow: Int,
    modelMaxContext: Option[Int],
    effectiveContextWindow: Int
  ):
    def asJson: Json = Json.obj(
      "ref" -> ref.asJson,
      "providerId" -> providerId.asJson,
      "modelId" -> modelId.asJson,
      "displayLabel" -> displayLabel.asJson,
      "contextWindow" -> contextWindow.asJson,
      "modelMaxContext" -> modelMaxContext.asJson,
      "effectiveContextWindow" -> effectiveContextWindow.asJson
    )

  /**
   * **从已配置 provider 检出可用模型**（onboarding 模型步骤的主数据源）。
   *
   * 空列表 = 尚未配置任何 provider ⇒ 前端退到「先去设置里加一个」的引导（不做自由
   * 填空主路径；provider 字段面保留为兜底，见任务书 §2 B）。
   *
   * `effectiveContextWindow` 与引擎生效值同式（`min(configured, modelMaxContext)`）：
   * 同一算式的读写两面，避免面板显示一个数、引擎按另一个数走。
   */
  def listConfiguredModels(cfg: NebflowServiceConfig): List[ModelRow] =
    cfg.llm.providers.toList
      .filter((_, p) => p.models.nonEmpty)
      .flatMap { (providerId, provider) =>
        provider.models.map { mc =>
          val effective = math.min(mc.contextWindow, mc.modelMaxContext.getOrElse(mc.contextWindow))
          ModelRow(
            ref = s"$providerId/${mc.id}",
            providerId = providerId,
            modelId = mc.id,
            displayLabel = s"$providerId / ${mc.id}",
            contextWindow = mc.contextWindow,
            modelMaxContext = mc.modelMaxContext,
            effectiveContextWindow = effective
          )
        }
      }
      .sortBy(r => (r.providerId, r.modelId))

  /** 已配置 provider 名清单（onboarding 显示「从这些服务商里选」）。 */
  def configuredProviders(cfg: NebflowServiceConfig): List[String] =
    cfg.llm.providers.toList.filter((_, p) => p.models.nonEmpty).map(_._1).sorted

  /**
   * 候选选择结果写回**同一配置存储**（JSON 层面）。
   *
   * 入参 = `nebflow.json` 的原始 JSON（[[ConfigService.getConfig]] 读出的同一份），
   * 返回 = 改好 `llm.providers.<providerId>.models[<id>].contextWindow` 的新 JSON，
   * 交由 [[ConfigService.updateConfig]] 落盘。
   *
   * 为什么走 JSON 而不是 case class：`NebflowServiceConfig` 只有 Decoder
   * （`config.scala`），往返会丢掉前端写入的、模型侧不关心的键（`ui` / `plugins`
   * 等）。**同一存储**的正确实现是「原子改一个字段」，不是「重写整个文件」。
   *
   * `contextWindow = None` ⇒ 只校验 ref 存在、不改值（前端只选题不改窗时的形态）。
   * 返回 `Left` 当且仅当 ref 不在已配置 provider 里——**不静默新建 provider**。
   */
  def applySelectionJson(
    configJson: Json,
    ref: String,
    contextWindow: Option[Int]
  ): Either[String, Json] =
    try
      val (providerId, modelId) = Config.parseModelRef(ref)
      val providerJson: Option[Json] =
        configJson.hcursor.downField("llm").downField("providers").downField(providerId).focus
      providerJson.filter(_.isObject) match
        case None => Left(s"unknown provider '$providerId' (not configured)")
        case Some(pj) =>
          val models = pj.hcursor.downField("models").focus.flatMap(_.asArray).getOrElse(Vector.empty)
          if !models.exists(_.hcursor.downField("id").as[String].toOption.contains(modelId)) then
            Left(s"unknown model '$modelId' under provider '$providerId'")
          else
            val updatedModels = models.map { m =>
              if m.hcursor.downField("id").as[String].toOption.contains(modelId) then
                contextWindow.fold(m)(cw => m.deepMerge(Json.obj("contextWindow" -> cw.asJson)))
              else m
            }
            val updatedProvider = pj.asObject
              .map(o => Json.fromJsonObject(o.add("models", Json.arr(updatedModels*))))
              .getOrElse(pj)
            val patched: Json = Json
              .obj("llm" -> Json.obj("providers" -> Json.obj(providerId -> updatedProvider)))
            Right(configJson.deepMerge(patched))
    catch
      case e: IllegalArgumentException => Left(Option(e.getMessage).getOrElse("invalid model ref"))

  /**
   * 候选选择结果写回**同一配置存储**（case class 层面，供已在内存里持有配置的
   * 调用方与 spec 使用）。`Left` 语义与 [[applySelectionJson]] 同。
   */
  def applySelection(
    cfg: NebflowServiceConfig,
    ref: String,
    contextWindow: Option[Int]
  ): Either[String, NebflowServiceConfig] =
    try
      val (providerId, modelId) = Config.parseModelRef(ref)
      cfg.llm.providers.get(providerId) match
        case None => Left(s"unknown provider '$providerId' (not configured)")
        case Some(provider) =>
          if !provider.models.exists(_.id == modelId) then
            Left(s"unknown model '$modelId' under provider '$providerId'")
          else
            val models = provider.models.map { mc =>
              if mc.id == modelId then contextWindow.fold(mc)(cw => mc.copy(contextWindow = cw)) else mc
            }
            val updatedProvider = provider.copy(models = models)
            Right(
              cfg.copy(llm = cfg.llm.copy(providers = cfg.llm.providers.updated(providerId, updatedProvider)))
            )
    catch
      case e: IllegalArgumentException => Left(Option(e.getMessage).getOrElse("invalid model ref"))

  /**
   * 读取某个 ref 的**生效**上下文窗口（clamp 后）。
   *
   * 语义与 [[nebflow.llm.ProviderRegistry.effectiveContextWindow]] 一致（该方法是
   * `private[llm]`，此处按同一算式复算；算式单点仍在 registry，本处只做只读回显，
   * 不参与任何判定）。
   */
  def effectiveContextWindow(cfg: NebflowServiceConfig, ref: String): Option[Int] =
    try
      val (providerId, modelId) = Config.parseModelRef(ref)
      cfg.llm.providers.get(providerId).flatMap(_.models.find(_.id == modelId)).map { mc =>
        math.min(mc.contextWindow, mc.modelMaxContext.getOrElse(mc.contextWindow))
      }
    catch case _: IllegalArgumentException => None

end OnboardingModelStep
