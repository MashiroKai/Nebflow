package nebflow.llm

import io.circe.{Json, JsonObject}
import nebflow.shared.ToolDefinition

/**
 * 动态工具装载 —— 腿能力档（#2638 / dynload 批 2026-11-19）。
 *
 * == 为什么需要它 ==
 * 「agent 主动加载含工具插件」会让会话的工具集在**会话中途**增长。工具定义是
 * 供应商前缀缓存的一部分（Anthropic：`tools → system → messages`；OpenAI/DeepSeek
 * 隐式前缀缓存），朴素地把新工具追加进 wire 的 `tools` 数组会**改掉缓存前缀字节**
 * ⇒ 整段历史失去命中（生产实测 `config-changed input=186066 read=3072`；本批
 * 探针 S3 复现：追加一个工具后读回 5376/7165）。
 *
 * == 两条等价形态（按腿能力选定，无第三条路）==
 *   - [[ToolLoadMode.Deferred]]（原生 deferred / tool_search）：新工具以
 *     `defer_loading: true` 形态追加——供应商文档明确「Deferred tools are not
 *     included in the system-prompt prefix」，故可缓存前缀字节不动（本批探针
 *     C1–C5：追加 deferred 工具后 `cache_read_input_tokens` 稳定 17062）。
 *   - [[ToolLoadMode.ExecutionGate]]（无原生支持）：新工具**不上 wire**，改为在
 *     请求尾部（request-only 尾巴）附带装载通告；授权在**执行门**按名字裁决。
 *     前缀字节零变化（本批探针 S5/S6：`cached_tokens` 稳定 7040）；模型仍可对
 *     通告的工具发起调用（T2），引擎按名执行、执行门闸验授权。
 *
 * == 唯一的装载入口 ==
 * 任何「新工具进入可见面」的路径都必须经 [[DynamicToolLoad.plan]]——禁止第二套
 * 装载逻辑（追加点漂移会让前缀字节再次编辑，破坏本不变量）。
 */
enum ToolLoadMode:
  /** 原生 deferred / tool_search：deferred 条目不进 system 前缀，可缓存前缀不动。 */
  case Deferred

  /**
   * 无原生支持：新工具不上 wire（前缀字节零变化），执行门按名授权。
   * 本档**要求**模型可见的尾部通告存在——否则工具不可调（不许静默降级：
   * 装载点必须同时产 [[DynamicToolLoad.Plan.advertisedTools]]，由调用方落到
   * 请求尾部；缺通告 = 装载不可用）。
   */
  case ExecutionGate

object ToolLoadMode:

  /**
   * Anthropic tool-search 支持模型（官方「supported models」表，前缀匹配）：
   * `claude-sonnet-5` / `claude-haiku-5` / `claude-opus-5` 族。Opus 4.1 及更早**不**支持。
   */
  val AnthropicToolSearchModels: List[String] =
    List("claude-sonnet-5", "claude-haiku-5", "claude-opus-5")

  /** 纯函数定档：协议 + 模型 id。不在能力表内 ⇒ [[ToolLoadMode.ExecutionGate]]（fail-closed）。 */
  def forLeg(protocol: String, modelId: Option[String]): ToolLoadMode =
    if protocol.equalsIgnoreCase("anthropic") && isAnthropicToolSearchModel(modelId) then
      ToolLoadMode.Deferred
    else ToolLoadMode.ExecutionGate

  /**
   * 纯函数定档（仅模型 id）：给不持有协议串的装配点用（如 `buildToolList` 只有
   * `agentDef.model`）。Anthropic 模型 id 族自带 `claude-` 前缀，其余一律
   * [[ToolLoadMode.ExecutionGate]]，与 [[forLeg]] 在现行配置面上等价。
   */
  def forModel(modelId: Option[String]): ToolLoadMode =
    if isAnthropicToolSearchModel(modelId) then ToolLoadMode.Deferred
    else ToolLoadMode.ExecutionGate

  private def isAnthropicToolSearchModel(modelId: Option[String]): Boolean =
    val m = modelId.getOrElse("").trim.toLowerCase
    m.nonEmpty && AnthropicToolSearchModels.exists(m.startsWith)

object DynamicToolLoad:

  // ── 装载点可观测事件（前缀呼吸可见） ───────────────────────────────

  /**
   * 一次装载的可观测事件——落在装载点日志里，使「前缀呼吸」可查：
   * 装载了哪些工具、腿档、wire 工具数前后、及本次装载的 wire 工具集指纹
   * （对比 [[prefixFingerprint]] 即可判定前缀是否被编辑）。
   */
  final case class LoadEvent(
    sessionId: String,
    mode: ToolLoadMode,
    addedTools: List[String],
    wireToolsBefore: Int,
    wireToolsAfter: Int,
    wireToolsFingerprintAfter: String
  )

  /**
   * 装载计划：真正上 wire 的工具集 + 事件 + （ExecutionGate 档）必须由调用方落到
   * 请求尾部的通告工具集。
   */
  final case class Plan(
    wireTools: List[ToolDefinition],
    event: LoadEvent,
    advertisedTools: List[ToolDefinition]
  )

  // ── 唯一装载入口 ──────────────────────────────────────────────────

  /**
   * **唯一的动态工具装载函数**。给定当前 wire 工具集与本次新装载的工具，返回真正
   * 上 wire 的工具集（+ 事件 + 尾部通告集）。两条档位的产物都满足同一不变量：
   * [[prefixFingerprint]] 装载前后**逐字节相等**。
   *
   * @param sessionId    装载所在会话（事件归属）
   * @param mode         腿档（[[ToolLoadMode.forLeg]] / [[ToolLoadMode.forModel]]）
   * @param currentWire  装载前的 wire 工具集（会话起始冻结集）
   * @param newlyLoaded  本次新装载的工具（插件授予 / MCP 热增）
   */
  def plan(
    sessionId: String,
    mode: ToolLoadMode,
    currentWire: List[ToolDefinition],
    newlyLoaded: List[ToolDefinition]
  ): Plan =
    val added = newlyLoaded.map(_.name)
    mode match
      case ToolLoadMode.ExecutionGate =>
        // 不上 wire：前缀字节零变化；新工具进「尾部通告 + 执行门」通道。
        Plan(
          currentWire,
          LoadEvent(sessionId, mode, added, currentWire.size, currentWire.size, fingerprint(currentWire)),
          newlyLoaded
        )
      case ToolLoadMode.Deferred =>
        // deferred 追加：条目不进 system 前缀 ⇒ 可缓存前缀字节不变。
        val wire = currentWire ++ newlyLoaded.map(_.copy(deferLoading = true))
        Plan(
          wire,
          LoadEvent(sessionId, mode, added, currentWire.size, wire.size, fingerprint(currentWire)),
          Nil
        )

  // ── 尾部通告渲染（ExecutionGate 档）──────────────────────────────

  /**
   * ExecutionGate 档的尾部通告正文：把新装载工具的名字/描述/schema 落成一段可追加
   * 的 `<system-reminder>` 尾块。调用方必须把它放到**请求尾部**（request-only 尾巴，
   * 持久历史断点之后）——追加在尾部不会使更早的前缀失配（探针 S5/S6 实证）。
   *
   * 模型据此对未上 wire 的工具发起调用；引擎按名在执行门（`buildAllowedToolSet`
   * 重评面）裁决授权——这正是「不上 wire、授权判在执行门」的闭环。
   */
  def advertisementText(tools: List[ToolDefinition]): String =
    val sb = new StringBuilder
    sb.append("<system-reminder>\n")
    sb.append(
      "Dynamically loaded tools for this session (not declared in the request's tools array). " +
        "Call them directly by name; authorization is checked when the call executes.\n"
    )
    tools.foreach { t =>
      sb.append(s"- ${t.name}: ${t.description}\n")
      sb.append(s"  parameters: ${Json.fromJsonObject(t.inputSchema).noSpaces}\n")
    }
    sb.append("</system-reminder>")
    sb.toString

  // ── 不变量：可缓存前缀指纹 ────────────────────────────────────────
  /**
   * wire 工具集的稳定指纹（键序固定、`cache_control` 剥除）。装载前后相等 ⇔ 本次
   * 装载没有编辑 wire 工具前缀字节。
   */
  def fingerprint(tools: List[ToolDefinition]): String =
    canonical(Json.fromValues(tools.map(wireToolJson)))

  /**
   * 供应商面「可缓存前缀」的规范化指纹：`system` + **非 deferred** 工具 + `messages`。
   *
   * - deferred 条目按 Anthropic 工具搜索文档**不进 system 前缀** ⇒ 从可复用前缀中剔除；
   * - `cache_control` 是缓存元数据（移动 ≠ 语义前缀变更）⇒ 剔除；
   * - OpenAI 面 system 在 messages 头部（无顶层 `system` 字段），本函数同样成立
   *   （system 缺省为 Null，messages 原样纳入）。
   *
   * 判据（本批闸）：两条档位的装载前后该指纹相等；朴素追加（新工具进 `tools`）必变。
   */
  def cacheablePrefixFingerprint(body: Json): String =
    val sys = body.hcursor.downField("system").focus.getOrElse(Json.Null)
    val tools = body.hcursor
      .downField("tools")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .filterNot(t => t.hcursor.downField("defer_loading").as[Boolean].getOrElse(false))
    val msgs = body.hcursor.downField("messages").focus.getOrElse(Json.Null)
    canonical(
      Json.obj(
        "system" -> sys,
        "tools" -> Json.fromValues(tools),
        "messages" -> msgs
      )
    )

  // ── 内部 ─────────────────────────────────────────────────────────

  /** wire 工具 JSON（键序固定，与适配器产出一致；`defer_loading` 仅在置位时出现）。 */
  private def wireToolJson(t: ToolDefinition): Json =
    val base = Json.obj(
      "name" -> Json.fromString(t.name),
      "description" -> Json.fromString(t.description),
      "input_schema" -> Json.fromJsonObject(t.inputSchema)
    )
    if t.deferLoading then base.deepMerge(Json.obj("defer_loading" -> Json.True)) else base

  /** 规范化（对象键排序、剥 `cache_control`、递归）——稳定的字节表示。 */
  private def canonical(json: Json): String =
    json.fold(
      "null",
      b => if b then "true" else "false",
      n => n.toString,
      s => Json.fromString(s).noSpaces,
      arr => "[" + arr.map(canonical).mkString(",") + "]",
      obj =>
        val entries =
          obj.toList
            .filterNot(_._1 == "cache_control")
            .sortBy(_._1)
            .map { case (k, v) => Json.fromString(k).noSpaces + ":" + canonical(v) }
        "{" + entries.mkString(",") + "}"
    )

end DynamicToolLoad
