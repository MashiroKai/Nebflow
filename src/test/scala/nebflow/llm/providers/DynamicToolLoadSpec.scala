package nebflow.llm.providers

import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.llm.DynamicToolLoad
import nebflow.llm.SendMessageParams
import nebflow.llm.ToolLoadMode
import nebflow.shared.*

/**
 * 动态工具装载的**机制闸**（dynload 批 2026-11-19）——「工具装载不改变 wire 前缀
 * 字节」的可判定判据 + 负控（朴素追加必红）。
 *
 * 判据（不变量，按腿档）：
 *   G1 Deferred 档（原生 deferred / tool_search）：新工具以 `defer_loading: true`
 *      追加后，**可缓存前缀**（system + 非 deferred 工具 + messages）逐字节不变。
 *   G2 ExecutionGate 档（无原生支持）：新工具**不上 wire** ⇒ wire 工具集逐字节不变，
 *      可缓存前缀不变；装载产物给出供请求尾部通告的工具集。
 *   N1 负控·Deferred：朴素追加（不带 deferred 标记）必改可缓存前缀 ⇒ 红。
 *   N2 负控·ExecutionGate：朴素追加（新工具进 `tools`）必改可缓存前缀 ⇒ 红。
 *
 * 指纹口径 = [[DynamicToolLoad.cacheablePrefixFingerprint]]：按供应商前缀层级
 * （Anthropic 文档 `tools → system → messages`）取「可复用前缀」，deferred 条目
 * **不进 system 前缀**（供应商文档），`cache_control` 为缓存元数据（移动 ≠ 语义
 * 前缀变更）一并剥除。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.llm.providers.DynamicToolLoadSpec"`。
 *
 * 实测佐证（真实 cmdcode/deepseek-v4-flash，2026-11-19 探针）：
 *   S2 逐字节相同       cached_tokens=7040/7051
 *   S3 朴素追加一个工具 cached_tokens=5376/7165  ← 负控的真实现象
 *   S5 wire 不变+尾通告  cached_tokens=7040/7071  ← ExecutionGate 档
 */
class DynamicToolLoadSpec extends FunSuite:

  private def anthropic: AnthropicAdapter = new AnthropicAdapter("http://localhost:0", "k", null)
  private def openai: OpenAiAdapter       = new OpenAiAdapter("http://localhost:0", "k", null)

  private def td(name: String, deferred: Boolean = false): ToolDefinition =
    ToolDefinition(name, s"description of $name", JsonObject.empty, deferLoading = deferred)

  private val baseTools = List(td("Read"), td("Bash"))
  private val newTool   = td("plugin_echo")

  private def params(tools: List[ToolDefinition], model: String): SendMessageParams =
    SendMessageParams(
      messages = List(Message(MessageRole.User, Left("hello"))),
      model = model,
      tools = Some(tools),
      systemStable = Some("STABLE-SYSTEM-PROMPT")
    )

  /** 真实 Anthropic 适配器 wire body（断点/缓存标记都在）。 */
  private def anthropicBody(tools: List[ToolDefinition]): Json =
    anthropic.bodyOf(params(tools, "claude-sonnet-5-5"), streaming = false)

  /** 真实 OpenAI 适配器 wire body（tools 顶层键 + search-tools 合并路径）。 */
  private def openaiBody(tools: List[ToolDefinition]): Json =
    val msgs = openai.requestMessages(params(tools, "deepseek/deepseek-v4-flash"), passback = false)
    openai.bodyWithSearchTools(
      Json.obj("model" -> Json.fromString("deepseek/deepseek-v4-flash"), "messages" -> Json.fromValues(msgs)),
      Some(tools),
      None
    )

  private def fp(body: Json): String = DynamicToolLoad.cacheablePrefixFingerprint(body)

  // ── 腿档分类（纯函数）─────────────────────────────────────────────

  test("腿档分类: anthropic tool-search 模型 → Deferred；其余 → ExecutionGate（fail-closed）"):
    assertEquals(ToolLoadMode.forLeg("anthropic", Some("claude-sonnet-5-5")), ToolLoadMode.Deferred)
    assertEquals(ToolLoadMode.forLeg("anthropic", Some("claude-haiku-5-5")), ToolLoadMode.Deferred)
    assertEquals(ToolLoadMode.forModel(Some("claude-opus-5-5")), ToolLoadMode.Deferred)
    // 活跃腿：cmdcode/DeepSeek（openai 协议）无原生 deferred（探针 X3）⇒ ExecutionGate
    assertEquals(ToolLoadMode.forModel(Some("deepseek/deepseek-v4-flash")), ToolLoadMode.ExecutionGate)
    assertEquals(ToolLoadMode.forLeg("openai", Some("claude-sonnet-5-5")), ToolLoadMode.ExecutionGate)
    assertEquals(ToolLoadMode.forModel(None), ToolLoadMode.ExecutionGate)

  // ── G2 ExecutionGate：不上 wire，前缀不变 + 产通告 + 事件 ─────────

  test("G2 ExecutionGate: 装载后 wire 工具集逐字节不变、可缓存前缀不变、产尾部通告、事件在场"):
    val plan = DynamicToolLoad.plan("s-exec", ToolLoadMode.ExecutionGate, baseTools, List(newTool))
    assertEquals(plan.wireTools.map(_.name), List("Read", "Bash"), "新工具不得进 wire（前缀不变量）")
    assertEquals(plan.wireTools, baseTools, "wire 工具集必须逐字段不变")
    assertEquals(plan.advertisedTools.map(_.name), List("plugin_echo"), "必须产尾部通告集")
    assertEquals(plan.event.mode, ToolLoadMode.ExecutionGate)
    assertEquals(plan.event.addedTools, List("plugin_echo"))
    assertEquals(plan.event.wireToolsBefore, 2)
    assertEquals(plan.event.wireToolsAfter, 2)
    // 真实适配器 wire body：装载前后可缓存前缀逐字节相等
    assertEquals(
      fp(openaiBody(plan.wireTools)),
      fp(openaiBody(baseTools)),
      "ExecutionGate 装载后 openai 面可缓存前缀必须不变"
    )
    assertEquals(
      fp(anthropicBody(plan.wireTools)),
      fp(anthropicBody(baseTools)),
      "ExecutionGate 装载后 anthropic 面可缓存前缀必须不变"
    )

  test("N2 负控 ExecutionGate: 朴素追加（新工具进 tools）必改可缓存前缀 ⇒ 红"):
    val naive = baseTools ++ List(newTool) // 无装载函数：直接进 wire
    assertNotEquals(
      fp(openaiBody(naive)),
      fp(openaiBody(baseTools)),
      "朴素追加必须改变可缓存前缀（负控红线；真实读数 S3: 7040→5376）"
    )

  // ── G1 Deferred：defer_loading 追加，前缀不变 ────────────────────

  test("G1 Deferred: 追加为 defer_loading 后，可缓存前缀逐字节不变"):
    val plan = DynamicToolLoad.plan("s-def", ToolLoadMode.Deferred, baseTools, List(newTool))
    assertEquals(plan.wireTools.map(_.name), List("Read", "Bash", "plugin_echo"))
    assert(plan.wireTools.last.deferLoading, "新装载工具必须带 deferred 标记")
    assert(plan.advertisedTools.isEmpty, "Deferred 档不产尾通告（工具已在 tools 面上）")
    assertEquals(
      fp(anthropicBody(plan.wireTools)),
      fp(anthropicBody(baseTools)),
      "Deferred 装载后可缓存前缀必须不变（deferred 条目不进前缀）"
    )
    // 机制为真：wire 上确实带 defer_loading，且缓存断点在最后一个非 deferred 工具上
    val tools = anthropicBody(plan.wireTools).hcursor.downField("tools").focus.get.asArray.get
    assert(tools.exists(_.hcursor.downField("defer_loading").as[Boolean].getOrElse(false)),
      "wire 必须真的带 defer_loading（不是指纹侧的假设）")
    val marked = tools.count(_.hcursor.downField("cache_control").focus.isDefined)
    assertEquals(marked, 1, "断点唯一")
    val echo = tools.find(_.hcursor.get[String]("name").toOption.contains("plugin_echo")).get
    assert(echo.hcursor.downField("cache_control").focus.isEmpty, "deferred 条目不得带缓存断点")

  test("N1 负控 Deferred: 朴素追加（不带 deferred 标记）必改可缓存前缀 ⇒ 红"):
    val naive = baseTools ++ List(td("plugin_echo")) // deferLoading = false
    assertNotEquals(
      fp(anthropicBody(naive)),
      fp(anthropicBody(baseTools)),
      "朴素追加（非 deferred）必须改变可缓存前缀（负控红线）"
    )

  // ── 保真：无新装载时逐字节零变化（既有行为不回归）──────────────────

  test("零变化: 无新装载时 wire body 与不带装载函数逐字节相同（既有行为不回归）"):
    for (tools <- List[List[ToolDefinition]](Nil, baseTools)) do
      val plan = DynamicToolLoad.plan("s", ToolLoadMode.forModel(Some("deepseek/deepseek-v4-flash")), tools, Nil)
      assertEquals(plan.wireTools, tools)
      assertEquals(plan.advertisedTools, Nil)
      assertEquals(anthropicBody(plan.wireTools).noSpaces, anthropicBody(tools).noSpaces)
      assertEquals(openaiBody(plan.wireTools).noSpaces, openaiBody(tools).noSpaces)
    // 无 deferred ⇒ 断点仍落在最后一个工具（与 dynload 前逐字节一致）
    val body = anthropicBody(baseTools)
    val tools = body.hcursor.downField("tools").focus.get.asArray.get
    assert(tools.last.hcursor.downField("cache_control").focus.isDefined)

  // ── 通告渲染（ExecutionGate 档的尾部通告）────────────────────────

  test("ExecutionGate 通告渲染: 含工具名/描述/schema，且为可加尾块"):
    val text = DynamicToolLoad.advertisementText(List(newTool))
    assert(text.contains("plugin_echo"), "通告必须点名工具")
    assert(text.contains("description of plugin_echo"), "通告必须带描述")
    assert(text.contains("<system-reminder>"), "通告须是可加的尾块形态")

end DynamicToolLoadSpec
