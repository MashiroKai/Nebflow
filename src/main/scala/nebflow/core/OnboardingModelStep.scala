package nebflow.core

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.*

/**
 * Onboarding 模型步骤的引擎侧单点（personal-agent 批 2026-10-04，作者第 2 条）。
 *
 * **与设置系统对齐**（硬要求）：不再自由填空——从**已配置 provider** 检出可用模型
 * 列表供选择，并支持设置 context window；**选定模型与窗口都写回与设置面同一个
 * 配置存储**，做到 onboarding 改完、设置面可见且一致，反向亦然（同一存储 =
 * 双向一致的结构保证；spec 以「同一次读写往返」证明）。
 *
 * 两条写回落点（**都是设置面自己在用的面，没有第二个存储**）：
 *   1. **context window**（`llm.providers.<id>.models[].contextWindow`，见
 *      [[applySelectionJson]]）——设置 → provider 面板改的同一字段；
 *   2. **选定模型（模型链）**（`agents/<root>/agent.json` 的 `model` 键，见
 *      [[writeSelectedChain]]）——设置 → `/model` 面板 PUT 的同一字段，
 *      `SchemePolicy.ownChainOf` / `GET /api/agents/:name/model` 的同一读取面。
 *      🔴 少了第 2 条，用户选的大脑**进不了任何配置面**：设置面读到的仍是 seed 链
 *      （`resolvedFrom:"seed"`），选定值只在 `Soul.md` 留成一行标签（round-2 复核
 *      的决定性读数）。窗口与模型必须一起落盘，缺一即「双向一致」不成立。
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
            // 原子改一个字段：只动该 model 的 contextWindow（None = 只校验不改值）。
            // 链面的写回在 [[writeSelectedChain]]（另一个键、另一个文件）。
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

  // ============================================================
  // 选定模型 → 配置存储（模型链面）
  // ============================================================

  /**
   * 选定模型 → `agents/<root>/agent.json` 的 `model` 键（**设置面 `/model` 面板
   * PUT 的同一字段**，`SchemePolicy.ownChainOf` / `GET /api/agents/:name/model`
   * 的同一读取面）。
   *
   * 为什么必须有这一步（round-2 复核的决定性读数）：只写 contextWindow 时，用户
   * 选定的大脑**不进入任何配置面** —— 设置面 `GET /api/agents/Nebula/model` 回
   * `resolvedFrom:"seed"`、`current` 是 seed 链的头（`nebflow.json` 字段序第一个
   * provider 的第一个模型），选定值只在 `Soul.md` 留成一行标签。任务书 §4 第 3 项
   * 「选定模型 + context window 写回同一配置存储」因此只成立一半。
   *
   * **零旁路**：本方法只写设置面自己的那个键；链的解析、消费、clamp 全在既有单点
   * （[[SchemePolicy]] / `ProviderRegistry`），本对象不复制任何算式、不建第二份存储。
   *
   * **不塌缩既有链**（round-2 复核点名的真实危害）：已有链的用户重跑引导时，选定
   * 模型成为 `preferred`，**既有 preferred + fallbacks 全部保留为后备**（去重、
   * 保序）—— fallback 深度是用户显式积累的设置，不得被一次选择清掉。空链用户
   * （全新 home）得到单元素链：fallback 是显式决定，**绝不自动填充**。
   *
   * 幂等：同一 ref 重复选择 ⇒ 链不变（覆盖式写入）。`Ref` 无法解析 ⇒ `Left`
   * （与 [[applySelectionJson]] 同款：不静默新建 provider）。
   */
  def writeSelectedChain(ref: String): IO[Either[String, AgentModelConfig]] =
    IO.blocking {
      val trimmed = ref.trim
      if trimmed.isEmpty then Left("empty model ref")
      else
        try
          Config.parseModelRef(trimmed) // 校验形态；不校验 provider 是否已配置（见下）
          val dir = PathUtil.dataRoot / "agents" / nebflow.actor.RootAgentIdentity.Name
          val jsonPath = dir / "agent.json"
          os.makeDir.all(dir)
          val existing =
            if os.exists(jsonPath) then io.circe.parser.parse(os.read(jsonPath)).toOption.getOrElse(Json.obj())
            else Json.obj()
          val base =
            if existing.asObject.exists(_.contains("name")) then existing
            else existing.deepMerge(Json.obj("name" -> nebflow.actor.RootAgentIdentity.Name.asJson))
          val prior = existing.hcursor.downField("model").as[AgentModelConfig].toOption.getOrElse(AgentModelConfig.empty)
          val next = mergeChain(prior, trimmed)
          AtomicJson.writeSync(jsonPath, base.deepMerge(Json.obj("model" -> next.asJson)).noSpaces)
          Right(next)
        catch
          case e: IllegalArgumentException => Left(Option(e.getMessage).getOrElse(s"invalid model ref '$ref'"))
    }

  /**
   * 把选定 ref 提升为 `preferred`，**既有 preferred + fallbacks 保序留在链上**
   * （选定项从后备里剔除，避免重复）。
   *
   * 空链 ⇒ `AgentModelConfig(preferred = Some(ref), fallbacks = Nil)`——与 seed 链
   * 同款「fallbacks 是显式用户决定，never auto-populated」。
   */
  private[core] def mergeChain(prior: AgentModelConfig, ref: String): AgentModelConfig =
    val carried = (prior.preferred.toList ++ prior.fallbacks).filter(_ != ref).distinct
    AgentModelConfig(preferred = Some(ref), fallbacks = carried)

end OnboardingModelStep
