package nebflow.core.browser

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite

import java.nio.file.Files

import nebflow.actor.AgentDef
import nebflow.agent.{AgentCore, SharedResources}
import nebflow.core.tools.*

/**
 * browser-verbs-p0 契约（P0 一等工具面）。
 *
 * 钉死：
 *  1. 纯策略：会话名 / ref 形状 / 身份判据 / URL 脱敏 / 页面状态分类（含反爬误报回归）/
 *     填充守卫（凭据红线）/ Chrome 候选路径。
 *  2. 无障碍树：解析 + 渲染（ref 只挂在有 DOM 后端的节点；忽略节点下钻；截断；循环保护）。
 *  3. 注册面：九动词登记于 TOOL_MAP（执行查表）、eq 同实例、requiresTrustedExecutionIdentity；
 *     **不在** builtins 语义面（builtinToolNames 不含）、不在 RootExclusiveTools。
 *  4. 授权面（插件分发裁定，作者 2026-11-22）：挂载态 engineGrantFor ⇒ 九动词；未挂载态 ⇒ 空集；
 *     插件声明越界名字被白名单拒（EngineToolWhitelist）；remoteableTools 仍不含。
 *  5. 调用面：守卫只留身份基本校验（Nebula 根 / 非空 sessionId）；匿名直调 fail-closed；凭据守卫先于会话查找。
 *  6. 注册表文件：与种子 sessions/<name>.json 形状互通；不存在的会话 state=NONE、close 无副作用。
 *  7. 端到端（默认跳过；需设 NEBFLOW_BROWSER_E2E=1 且本机有 Chrome）：open → snapshot → fill → click → state → close。
 */
class BrowserVerbsSpec extends CatsEffectSuite:

  private val nineNames = BrowserVerbNames.All.toSet

  test("new screenshot file is owner read-write only on POSIX") {
    val dir = Files.createTempDirectory("bu-private-shot")
    val file = dir.resolve("shot.png")
    try
      if Files.getFileStore(dir).supportsFileAttributeView("posix") then
        BrowserSessions.writePrivateScreenshot(file, Array[Byte](1, 2, 3))
        import scala.jdk.CollectionConverters.*
        import java.nio.file.attribute.PosixFilePermission.*
        assertEquals(Files.getPosixFilePermissions(file).asScala.toSet, Set(OWNER_READ, OWNER_WRITE))
        assertEquals(Files.readAllBytes(file).toList, List[Byte](1, 2, 3))
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
  }

  // ══════════ 1. 纯策略 ══════════

  test("会话名：小写字母数字连字符下划线，1–40 位，禁路径字符") {
    assert(BrowserPolicy.validSessionName("sep-server_1"))
    assert(!BrowserPolicy.validSessionName("../etc"))
    assert(!BrowserPolicy.validSessionName("Upper"))
    assert(!BrowserPolicy.validSessionName(""))
    assert(!BrowserPolicy.validSessionName("a" * 41))
  }

  test("ref 形状：仅 e + 数字") {
    assertEquals(BrowserPolicy.parseRef("e12"), Some("e12"))
    assertEquals(BrowserPolicy.parseRef("e"), None)
    assertEquals(BrowserPolicy.parseRef("#submit"), None)
    assertEquals(BrowserPolicy.parseRef("e1 "), None)
  }

  test("身份基本校验（插件分发裁定）：Nebula 根或带会话身份即过；匿名直调 fail-closed") {
    assert(BrowserPolicy.callerAllowed(isNebulaRoot = true, sessionId = None))
    assert(BrowserPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("delegate-abc")))
    assert(BrowserPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("node-7")))
    assert(!BrowserPolicy.callerAllowed(isNebulaRoot = false, sessionId = Some("   ")))
    assert(!BrowserPolicy.callerAllowed(isNebulaRoot = false, sessionId = None))
  }

  test("URL 脱敏：查询串整段替换，无查询串原样") {
    assertEquals(BrowserPolicy.redactUrl("https://a.example/x?token=abc"), "https://a.example/x?…")
    assertEquals(BrowserPolicy.redactUrl("https://a.example/x"), "https://a.example/x")
  }

  test("页面状态分类：拦截 / 登录 / 验证码 / 正常；探针 marker 在正文充实时不误报（回归）") {
    assertEquals(BrowserPolicy.classify(List("cloudflare"), 80, hasPassword = false, "https://x/a", hasCaptcha = false), "ANTIBOT")
    assertEquals(BrowserPolicy.classify(Nil, 3000, hasPassword = true, "https://x/a", hasCaptcha = false), "LOGIN")
    assertEquals(BrowserPolicy.classify(Nil, 3000, hasPassword = false, "https://x/login?next=1", hasCaptcha = false), "LOGIN")
    assertEquals(BrowserPolicy.classify(Nil, 3000, hasPassword = false, "https://x/a", hasCaptcha = true), "CAPTCHA")
    // 文章页内嵌 PerimeterX 探针但正文充实：marker 只随报告，不改变状态（与种子 pageState 同口径）
    assertEquals(BrowserPolicy.classify(List("perimeterx"), 3000, hasPassword = false, "https://x/a", hasCaptcha = false), "OK")
    assertEquals(BrowserPolicy.classify(List("challenge-text"), 9000, hasPassword = false, "https://x/a", hasCaptcha = false), "ANTIBOT")
    assertEquals(BrowserPolicy.classify(Nil, 3000, hasPassword = false, "https://x/a", hasCaptcha = false), "OK")
  }

  test("凭据守卫：secret: 保留语法拒绝，且拒绝文案不回显输入") {
    val refused = BrowserPolicy.fillRefusal("secret:github-pat")
    assert(refused.isDefined)
    assert(!refused.get.contains("github-pat"), "拒绝文案不得回显输入")
    assertEquals(BrowserPolicy.fillRefusal("hello world"), None)
  }

  test("Chrome 候选：环境变量覆盖优先；mac / linux 内置位置") {
    val none: String => Option[String] = _ => None
    assertEquals(BrowserPolicy.chromeCandidates(none, "Mac OS X").head, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    assertEquals(BrowserPolicy.chromeCandidates(none, "Linux").head, "/usr/bin/google-chrome")
    val ov: String => Option[String] = k => if k == "NEBFLOW_BROWSER_USE_CHROME" then Some("/x/chrome") else None
    assertEquals(BrowserPolicy.chromeCandidates(ov, "Mac OS X").head, "/x/chrome")
  }

  // ══════════ 2. 无障碍树 ══════════

  private def axNode(id: String, role: String, name: String, bid: Option[Int], children: List[String], ignored: Boolean = false): Json =
    Json.obj(
      "nodeId" -> id.asJson,
      "ignored" -> ignored.asJson,
      "role" -> Json.obj("type" -> "role".asJson, "value" -> role.asJson),
      "name" -> Json.obj("type" -> "computedString".asJson, "value" -> name.asJson),
      "backendDOMNodeId" -> bid.fold(Json.Null)(_.asJson),
      "childIds" -> children.asJson
    )

  private val sampleTree: List[Json] = List(
    axNode("1", "RootWebArea", "Demo", Some(10), List("2", "3", "4")),
    axNode("2", "generic", "", None, List("5")),
    axNode("3", "button", "Login", Some(20), Nil),
    axNode("4", "group", "hidden", Some(25), List("6"), ignored = true),
    axNode("5", "link", "Docs", Some(30), Nil),
    axNode("6", "textbox", "Email", Some(40), Nil)
  )

  test("AX 解析：字段拍平（role/name 取 value，backendDOMNodeId 可缺）") {
    val parsed = BrowserPolicy.parseAx(sampleTree)
    assertEquals(parsed.head, BrowserPolicy.AxNode("1", ignored = false, "RootWebArea", "Demo", Some(10), List("2", "3", "4")))
    assertEquals(parsed(1).backendId, None)
  }

  test("AX 渲染：ref 仅挂 DOM 后端节点；结构节点与忽略节点只下钻不出行；DFS 编号") {
    val r = BrowserPolicy.renderAx(BrowserPolicy.parseAx(sampleTree), 50)
    assertEquals(
      r.text.linesIterator.toList,
      List(
        "- RootWebArea \"Demo\" [ref=e1]",
        "  - link \"Docs\" [ref=e2]",
        "  - button \"Login\" [ref=e3]",
        "  - textbox \"Email\" [ref=e4]"
      )
    )
    assertEquals(r.refs, Map("e1" -> 10, "e2" -> 30, "e3" -> 20, "e4" -> 40))
    assert(!r.truncated)
  }

  test("AX 渲染：达到 maxNodes 即截断并注明；refs 不超上限") {
    val r = BrowserPolicy.renderAx(BrowserPolicy.parseAx(sampleTree), 2)
    assert(r.truncated)
    assertEquals(r.refs.size, 2)
    assert(r.text.contains("truncated at 2 nodes"))
  }

  test("AX 渲染：循环引用由 seen 兜住（不死循环）；空树有显式文案") {
    val cyc = BrowserPolicy.parseAx(List(axNode("1", "group", "x", Some(1), List("1"))))
    assertEquals(BrowserPolicy.renderAx(cyc, 10).text, "- group \"x\" [ref=e1]")
    assertEquals(BrowserPolicy.renderAx(Nil, 10).text, "(empty accessibility tree)")
  }

  // ══════════ 3. 注册面 ══════════

  test("注册面：九动词进 TOOL_MAP，eq 同实例，ALL_TOOLS 暴露 schema，均要求可信执行身份") {
    assertEquals(nineNames.size, 9)
    nineNames.foreach { n =>
      assert(ToolRegistry.TOOL_MAP.contains(n), s"$n must be registered")
      assert(ToolRegistry.TOOL_MAP(n).requiresTrustedExecutionIdentity, s"$n requires trusted identity")
      assert(ToolRegistry.ALL_TOOLS.exists(_.name == n), s"$n must expose schema")
    }
    assert(ToolRegistry.TOOL_MAP("browser_click") eq BrowserClickTool)
    assert(ToolRegistry.TOOL_MAP("browser_close") eq BrowserCloseTool)
  }

  test("schema：fill 必填 ref+text；snapshot 可选 max_nodes；每个动词都带 session 参数") {
    val fill = BrowserFillTool.inputSchema
    assertEquals(fill("required").flatMap(_.asArray).map(_.toList.flatMap(_.asString)), Some(List("ref", "text")))
    val snap = BrowserSnapshotTool.inputSchema
    assert(snap("properties").flatMap(_.asObject).exists(_.contains("max_nodes")))
    nineNames.foreach { n =>
      val t = ToolRegistry.TOOL_MAP(n)
      assert(
        t.inputSchema("properties").flatMap(_.asObject).exists(_.contains("session")),
        s"$n must accept session"
      )
    }
  }

  // ══════════ 4. 授权面 ══════════

  test("授权（默认面）：九动词不在 RootExclusiveTools、不在 builtins 清单，任何身份默认面无名字") {
    assert(nineNames.intersect(AgentCore.RootExclusiveTools).isEmpty, "不得再经防声明逃逸集授能")
    assert(nineNames.intersect(ToolRegistry.builtinToolNames.toSet).isEmpty, "builtins 清单不得含浏览器动词")
    assert(!nebflow.core.plugin.PluginRegistry.BuiltinToolWhitelist.exists(nineNames.contains))
    assert(RemoteExecutor.remoteableTools.intersect(nineNames).isEmpty, "remoteableTools 不得含浏览器动词")
  }

  test("授权（挂载态）：挂载 browser-use（plugin_browser-use_engine 前缀来源）⇒ 九动词全授予") {
    val granted = nebflow.core.plugin.PluginRegistry.engineGrantFor(List("plugin_browser-use_engine"))
    assertEquals(granted, nineNames)
  }

  test("授权（分域，回灌 8f3521b6a）：未登记插件的 _engine 前缀不串授浏览器九动词") {
    // 旧实现对任一 `_engine` 前缀授予整张白名单；分域后，未登记插件一个不授。
    assertEquals(nebflow.core.plugin.PluginRegistry.engineGrantFor(List("plugin_other-plugin_engine")), Set.empty[String])
    assertEquals(nebflow.core.plugin.PluginRegistry.EngineToolsByPlugin("browser-use"), nineNames)
  }

  test("授权（未挂载态）：无引擎前缀来源 ⇒ 空集；他插件 MCP 前缀不误判为引擎挂载") {
    assertEquals(nebflow.core.plugin.PluginRegistry.engineGrantFor(Nil), Set.empty[String])
    assertEquals(
      nebflow.core.plugin.PluginRegistry.engineGrantFor(List("plugin_x_playwright", "agent-Nebula_s")),
      Set.empty[String]
    )
  }

  test("授权（声明白名单）：插件声明越界名字被 EngineToolWhitelist 排除，Delegate 等编排名永不可授") {
    val declared = List("browser_open", "Delegate", "Bash")
    val kept = declared.filter(nebflow.core.plugin.PluginRegistry.EngineToolWhitelist.contains)
    assertEquals(kept, List("browser_open"))
  }

  test("授权（纵深）：挂载态授予的九动词对非引擎身份守卫仍只留身份基本校验（匿名拒）") {
    assert(!BrowserPolicy.callerAllowed(isNebulaRoot = false, sessionId = None))
  }

  // ══════════ 5. 调用面 fail-closed ══════════

  private def ctxOf(agentName: Option[String], sessionId: Option[String]): ToolContext =
    ToolContext(
      projectRoot = "/tmp",
      sessionId = sessionId,
      agentDef = agentName.map(n => AgentDef(name = n, description = "", tools = Nil))
    )

  test("调用面：匿名直调（无会话身份且非 Nebula 根）→ BROWSER_FORBIDDEN，不触碰浏览器") {
    BrowserClickTool.call(JsonObject("ref" -> "e1".asJson), ctxOf(Some("general"), None)).map { res =>
      assert(res.isLeft)
      assert(res.left.toOption.get.message.contains("BROWSER_FORBIDDEN"))
    }
  }

  test("调用面：会话名非法 → 显式拒绝（路径字符不得进入注册表文件名）") {
    BrowserStateTool.call(JsonObject("session" -> "../x".asJson), ctxOf(Some("Nebula"), None)).map { res =>
      assert(res.isLeft)
      assert(res.left.toOption.get.message.contains("invalid session name"))
    }
  }

  test("凭据守卫先于会话查找：fill 输入不存在的 secret 引用即刻统一拒绝，不回显、不尝试连接浏览器") {
    // 名称必须确定不存在（用户真实 secrets 库里若有同名项，本用例会误触发会话路径）。
    BrowserFillTool
      .call(JsonObject("ref" -> "e1".asJson, "text" -> "secret:cubu-no-such-secret-xyz".asJson), ctxOf(Some("Nebula"), None))
      .map { res =>
        assert(res.isLeft)
        val msg = res.left.toOption.get.message
        assert(msg.contains("secret:"), msg)
        assert(!msg.contains("cubu-no-such-secret-xyz"), "错误文案不得回显输入")
      }
  }

  // ══════════ 6. 注册表文件与离线态 ══════════

  test("注册表：记录往返；兼容种子 sessions/<name>.json 形状") {
    val home = Files.createTempDirectory("bv-home")
    val rec = BrowserRecord("sep", 9333, Some(4242L), "/chrome", "/p/sep", "2026-11-22T00:00:00Z")
    BrowserSessions.writeRecord(home, rec)
    assertEquals(BrowserSessions.readRecord(home, "sep"), Some(rec))
    Files.writeString(
      home.resolve("sessions").resolve("seedy.json"),
      """{"name":"seedy","port":9222,"pid":1,"chromePath":"/c","userDataDir":"/u","startedAt":"x"}"""
    )
    assertEquals(BrowserSessions.readRecord(home, "seedy").map(_.port), Some(9222))
    assertEquals(BrowserSessions.readRecord(home, "missing"), None)
  }

  test("离线态：无记录 → state=NONE；close 不存在的会话无副作用") {
    val home = Files.createTempDirectory("bv-empty")
    assert(BrowserSessions.state("nope", home).contains("\"NONE\""))
    assert(BrowserSessions.close("nope", home).contains("nothing to close"))
  }

  // ══════════ 7. 端到端（默认跳过） ══════════

  test("E2E（需 NEBFLOW_BROWSER_E2E=1 与本机 Chrome）：open → snapshot → fill → click → state → close") {
    assume(sys.env.contains("NEBFLOW_BROWSER_E2E"), "live Chrome e2e disabled (set NEBFLOW_BROWSER_E2E=1)")
    val home = Files.createTempDirectory("bv-e2e")
    val html =
      "<html><head><title>bv-e2e</title></head><body>" +
        "<label>Name <input type=\"text\" id=\"n\"></label>" +
        "<button onclick=\"document.title='clicked'\">Go</button></body></html>"
    val page = home.resolve("p.html")
    Files.writeString(page, html)
    val url = page.toUri.toString
    val refRe = """\[ref=(e\d+)\]""".r
    def refOf(snap: String, role: String): String =
      snap.linesIterator
        .find(_.contains(s"- $role"))
        .flatMap(line => refRe.findFirstMatchIn(line).map(_.group(1)))
        .getOrElse("")
    for
      _ <- IO.blocking(BrowserSessions.open("e2e", Some(url), home))
      snap <- IO.blocking(BrowserSessions.snapshot("e2e", 50, home))
      _ <- IO.blocking(BrowserSessions.fill("e2e", refOf(snap, "textbox"), "hello", home))
      _ <- IO.blocking(BrowserSessions.click("e2e", refOf(snap, "button"), home))
      st <- IO.blocking(BrowserSessions.state("e2e", home))
      closed <- IO.blocking(BrowserSessions.close("e2e", home))
    yield
      assert(refOf(snap, "textbox").nonEmpty && refOf(snap, "button").nonEmpty, snap)
      assert(st.contains("\"alive\" : true"), st)
      assert(closed.contains("pid_gone=true") && closed.contains("port_listener=false"), closed)
  }

end BrowserVerbsSpec
