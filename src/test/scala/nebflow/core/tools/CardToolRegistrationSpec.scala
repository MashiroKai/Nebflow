package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.AgentCore

/**
 * Card 工具解封恢复（2026-09-05 08:40 作者裁定）——注册与授能面契约。
 *
 * commit 793f62c1（2026-08-11）曾整体删除 CardTool（471 行）+ 前端消费面；
 * 本批恢复后端工具与注册表注册。钉死四点：
 *  1. 注册表含 Card（schema 可被 LLM 发现）
 *  2. Nebula 固定面携带 Card（NebulaOrchestrationTools 十五件之一）
 *  3. dispatcher / general / legacy team / legacy flow / catch-all 均不授 Card
 *     （Card 是 Nebula 专属授能；org.nebflow/tools 插件白名单不含 Card，
 *     BuiltinToolWhitelist 断言钉死插件通道）
 *  4. 调用面健康：缺 html 参数报 ToolError；合法输入产出 ___CARD_HTML___ 哨兵
 *     （前端 iframe 消费面本批不恢复——运行时形态 = 后端可调用 + LLM 返回
 *     summary 结果，chat 可视渲染待前端批）
 */
class CardToolRegistrationSpec extends FunSuite:

  private def mkDef(name: String, tools: List[String] = Nil) =
    nebflow.agent.AgentDef(name = name, description = "", tools = tools)

  // ── 注册面 ──────────────────────────────────────────────

  test("ToolRegistry contains Card (解封恢复，2026-09-05)"):
    assert(ToolRegistry.TOOL_MAP.contains("Card"), "Card must be registered")
    assert(ToolRegistry.TOOL_MAP("Card") eq CardTool, "registry entry is CardTool")
    assert(ToolRegistry.ALL_TOOLS.exists(_.name == "Card"), "ALL_TOOLS exposes Card schema")

  test("Card is NOT in the plugin builtin whitelist (org.nebflow/tools 不可授)"):
    assert(
      !nebflow.core.plugin.PluginRegistry.BuiltinToolWhitelist.contains("Card"),
      "插件通道不得授 Card（Card 仅 Nebula 固定面携带）"
    )

  // ── 授能面 ──────────────────────────────────────────────

  test("Nebula fixed set carries Card; dispatcher/general do not"):
    assert(AgentCore.fixedToolsFor(mkDef("Nebula")).contains("Card"), "Nebula 携带 Card")
    assert(!AgentCore.fixedToolsFor(mkDef("project-dispatcher")).contains("Card"), "dispatcher 不授 Card")
    assert(!AgentCore.fixedToolsFor(mkDef("general")).contains("Card"), "general 不授 Card")
    // legacy team/flow/catch-all 不授 Card 的断言在 AllowedToolSetSpec
    // （legacyFixedTools 为 private[agent]，须同包断言）。

  // ── 调用面 ──────────────────────────────────────────────

  private val emptyCtx = ToolContext(projectRoot = os.pwd.toString)

  test("Card call without html -> ToolError"):
    val result = CardTool.call(io.circe.JsonObject.empty, emptyCtx).unsafeRunSync()
    assert(result.isLeft, "missing html must yield ToolError")
    assert(result.swap.toOption.get.message.contains("html"), "error message mentions the html parameter")

  test("Card call with html -> ___CARD_HTML___ sentinel payload"):
    val input = io.circe.JsonObject("html" -> io.circe.Json.fromString("<div>hi</div>"), "title" -> io.circe.Json.fromString("T"))
    val result = CardTool.call(input, emptyCtx).unsafeRunSync()
    val payload = result.getOrElse(fail("expected Right"))
    assert(payload.startsWith("___CARD_HTML___"), s"sentinel prefix, got: ${payload.take(40)}")
    assert(payload.contains(""""title":"T"""") || payload.contains("""\"title\":\"T\""""), "title round-trips in payload")
