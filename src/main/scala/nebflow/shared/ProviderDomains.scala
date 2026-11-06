package nebflow.shared

/**
 * 服务商域（provider domain）枚举 —— **唯一出生地**（provider-add-tabs 批，
 * 2026-11-06 作者令「把添加服务商统一成标签切换类型 + 单一服务商面板」）。
 *
 * 为什么这枚枚举住在后端：设置页的「服务商」面板要按类型切换字段套（LLM /
 * 生图 / JeV），前端若自持一份域清单就是**第二份硬编码类型枚举**——加一个域时
 * 后端改了、前端忘改，面板会静默少一个类型。故域清单由后端投影
 * （`GET /api/config/provider-types`，见 [[nebflow.service.ConfigService.providerTypes]]）
 * 下发，前端只消费、不拼写。
 *
 * 每个 id 都对应 `nebflow.json` 里一节**真实存在**的配置面（不是自造类型）：
 *   · `llm`      → `llm.providers`      ← [[Config]].llm（ServiceLlmConfig.providers）
 *   · `imagegen` → `imagegen.providers` ← ImageGenConfig.providers
 *   · `jev`      → `jev.providers`      ← JevConfig.providers
 * 单腿配置面（`search` / `embedding` / STT）没有 provider **映射**，没有「添加
 * 服务商」入口，故不入本枚举。
 *
 * 适配器（协议）子枚举同样单源：`imagegen` 取 `ImageGenAdapters.All.map(_.type)`、
 * `jev` 取 `DecisionProvider.AllIds`；`llm` 取 [[LlmProtocol]]（anthropic/openai），
 * 前端不再各写一份拼写表。
 */
object ProviderDomains:

  /** 大语言模型（`llm.providers`：每项 = baseUrl + apiKey + protocol + models[]）。 */
  val Llm: String = "llm"

  /** 生图（`imagegen.providers`：每项 = type + model + baseUrl + keyRef）。 */
  val ImageGen: String = "imagegen"

  /** JeV 分配决策（`jev.providers`：每项 = type + endpoint + model + keyRef）。 */
  val Jev: String = "jev"

  /** 面板标签顺序（= 面板里类型标签的排列序）。 */
  val All: List[String] = List(Llm, ImageGen, Jev)

  /** 该域是否有「启用开关」（imagegen / jev 有 enabled 节键；llm 无）。 */
  def hasEnabledSwitch(domain: String): Boolean =
    domain == ImageGen || domain == Jev
