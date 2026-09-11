package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * PopTool — HTML 图片内联（#15, 方案 A — commit 5d95194b）+ Nebula 专属身份闸
 * （2026-09-10 作者裁定「我觉得把pop工具给nebula专属吧」）。
 *
 * Agent HTML reports reference local screenshots via file:///abs, /abs,
 * relative, or ~/ paths. The Canvas iframe cannot load those (file:// blocked
 * by browser policy, bare paths unresolved), so PopTool pre-processes HTML
 * content: local <img src> values that exist, are embeddable image formats,
 * and are ≤5MB become base64 data URIs. Everything else is left untouched.
 *
 * 身份闸（本批）：call 最前的两道同真判据——① ctx.agentDef 名为 Nebula；
 * ② ctx.depth == 0（Nebula 本体根会话，排除 NodeDef.agent="Nebula" 的节点会话
 * depth=1 / SubTask worker / 子 agent / 一切派生子会话）。agentDef=None
 * （REST 直调 / spec harness）fail-closed 一律拒。拒答含逐字作者文案 +
 * POP_NEBULA_ONLY。身份闸先于任何副作用（filePath 解析 / 文件读 / HTML 内联 /
 * WS 发送）——用「不存在的路径仍报 POP_NEBULA_ONLY 而非 File not found」+
 * 「wsSend 零消息」证明。
 *
 * These specs pin the behavior end-to-end through call() + a captured wsSend.
 */
class PopToolSpec extends FunSuite:

  /** Nebula 本体根会话身份（正常放行面）。 */
  private val nebulaDef = nebflow.agent.AgentDef(name = "Nebula", description = "", tools = Nil)

  private def defNamed(name: String): nebflow.agent.AgentDef =
    nebflow.agent.AgentDef(name = name, description = "", tools = Nil)

  private def tempDir(name: String): os.Path =
    val d = os.pwd / "target" / s"test-pop-$name-${java.util.UUID.randomUUID().toString.take(6)}"
    os.makeDir.all(d)
    d

  private def writePng(p: os.Path, bytes: Int = 8): Unit =
    // minimal valid-enough PNG header (content is irrelevant — only the bytes
    // must round-trip through base64)
    val data = Array.fill(bytes)(0x42.toByte)
    os.write.over(p, data)

  /** Capture wsSend messages into a Ref-like buffer.
    *
    * 2026-09-10 身份闸批：harness 显式传 Nebula 本体 ctx（agentDef=Some(Nebula)、
    * depth=0）——闸的 fail-closed 语义下 agentDef=None 会被拒，旧 harness 的
    * None 不再是合法的内联行为测试上下文。 */
  private def captureCtx(buf: scala.collection.mutable.ListBuffer[Json]): ToolContext =
    ctxWith(buf, agentDef = Some(nebulaDef), depth = 0)

  /** 身份参数化 harness——闸测试用（同一 wsSend 缓冲，身份/depth 可变）。 */
  private def ctxWith(
    buf: scala.collection.mutable.ListBuffer[Json],
    agentDef: Option[nebflow.agent.AgentDef],
    depth: Int
  ): ToolContext =
    ToolContext(
      sessionId = Some("pop-test"),
      sessionStore = None,
      agentDef = agentDef,
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = depth,
      messages = Nil,
      wsSend = Some((j: Json) => IO { buf += j }),
      projectRoot = ""
    )

  private def contentOf(buf: scala.collection.mutable.ListBuffer[Json]): String =
    assertEquals(buf.size, 1, s"exactly one popFile message expected: $buf")
    val item = buf.head.hcursor.downField("item")
    item.get[String]("itemType").toOption.getOrElse("") match
      case "html" => item.get[String]("content").toOption.getOrElse("")
      case other  => fail(s"expected html itemType, got $other")

  test("file:/// absolute image src is inlined as a base64 data URI"):
    val dir = tempDir("file-abs")
    writePng(dir / "shot.png")
    val html = dir / "report.html"
    os.write.over(html, s"""<html><body><img src="file://${dir.toString}/shot.png"></body></html>""")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync() match
      case Right(_) =>
        val c = contentOf(buf)
        assert(c.contains("src=\"data:image/png;base64,"), s"data URI expected:\n$c")
        assert(!c.contains("file://"), s"original file:// src must be replaced:\n$c")
      case Left(err) => fail(s"Pop failed: ${err.message}")

  test("relative image src resolves against the HTML's directory"):
    val dir = tempDir("relative")
    writePng(dir / "pic.png")
    val html = dir / "report.html"
    os.write.over(html, """<html><body><img src='pic.png'></body></html>""")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync()
    val c = contentOf(buf)
    assert(c.contains("src='data:image/png;base64,"), s"single-quoted src must inline too:\n$c")

  test("~ expansion: ~/ path in an img src resolves to the user's home"):
    val home = sys.props("user.home")
    val abs = s"$home/.nebflow-pop-test-tmp.png"
    os.write.over(os.Path(abs), Array.fill(4)(0x43.toByte))
    try
      val html = tempDir("tilde") / "report.html"
      os.write.over(html, """<html><img src="~/.nebflow-pop-test-tmp.png"></html>""")
      val buf = scala.collection.mutable.ListBuffer.empty[Json]
      PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync()
      val c = contentOf(buf)
      assert(c.contains("data:image/png;base64,"), s"~ path must expand and inline:\n$c")
    finally os.remove.all(os.Path(abs))

  test("remote http(s) URLs are left untouched"):
    val dir = tempDir("remote")
    val html = dir / "report.html"
    os.write.over(
      html,
      """<html><img src="https://example.com/a.png"><img src="http://cdn.example.net/b.jpg"></html>"""
    )
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync()
    val c = contentOf(buf)
    assert(c.contains("https://example.com/a.png"), s"remote src must stay:\n$c")
    assert(c.contains("http://cdn.example.net/b.jpg"), s"remote src must stay:\n$c")
    assert(!c.contains("base64"), s"nothing to embed:\n$c")

  test("missing file and non-image extension keep their original src (2026-09-11: no longer silently)"):
    // toolfail batch: both cases are now REPORTED in the tool result
    // (`warnings` + `fileRefs`) instead of being dropped without a trace — the
    // content behaviour is unchanged, the reporting is pinned in
    // PopToolFileRefSpec.
    val dir = tempDir("skip")
    val html = dir / "report.html"
    os.write.over(
      html,
      s"""<html><img src="${dir.toString}/ghost.png"><img src="${dir.toString}/notes.txt"></html>"""
    )
    os.write.over(dir / "notes.txt", "not an image")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val result = PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync()
    val c = contentOf(buf)
    assert(c.contains("ghost.png"), s"missing file keeps original src:\n$c")
    assert(c.contains("notes.txt"), s"non-image keeps original src:\n$c")
    assert(!c.contains("base64"), s"no embedding expected:\n$c")
    result match
      case Right(text) =>
        assert(text.contains("warnings: "), s"both references must be reported:\n$text")
        assert(text.contains("not-found") && text.contains("extension-not-allowed"), text)
      case Left(err) => fail(s"Pop failed: ${err.message}")

  test("oversized image (>5MB) keeps its original src"):
    val dir = tempDir("oversize")
    os.write.over(dir / "big.png", Array.fill(5 * 1024 * 1024 + 1)(0x44.toByte))
    val html = dir / "report.html"
    os.write.over(html, """<html><img src="big.png"></html>""")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync()
    val c = contentOf(buf)
    assert(c.contains("""src="big.png"""") | c.contains("src=\"big.png\""), s"oversize keeps src:\n$c")
    assert(!c.contains("base64"), s"oversize must not embed:\n$c")

  test("markdown files are not pre-processed (scope: html itemType only)"):
    val dir = tempDir("md-scope")
    writePng(dir / "pic.png")
    val md = dir / "notes.md"
    os.write.over(md, "![pic](pic.png)\n")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> md.toString.asJson), captureCtx(buf)).unsafeRunSync()
    assertEquals(buf.size, 1)
    val c = buf.head.hcursor.downField("item").get[String]("content").toOption.getOrElse("")
    assert(c.contains("(pic.png)"), s"markdown content passes through unmodified:\n$c")

  test("missing file returns an error and sends nothing"):
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> "/nonexistent/pop-x.html".asJson), captureCtx(buf)).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains("not found") || err.message.contains("File not found"), err.message)
        assertEquals(buf.size, 0, "no WS message on error")
      case Right(_) => fail("expected an error for a missing file")

  // ====================================================================
  // Nebula 专属身份闸（2026-09-10 作者裁定）
  // ====================================================================

  /** 逐字作者文案（裁定原文，不得改述）。 */
  private val VerbatimDeny =
    "Pop 已收归 Nebula 专属；交付物请沿 out 边交给链末端 / Nebula，由 Nebula 决定是否展示"

  private val gateInput = JsonObject("filePath" -> "/nonexistent/pop-gate-probe.html".asJson)

  /** 拒答断言：右值缺席 + 文案命中 + 零副作用。
    *
    * 零副作用两点证明：
    *  - wsSend 缓冲空（无 popFile 消息）；
    *  - filePath 指向不存在的路径却报闸文案而非 "File not found" ⇒ 闸先于
    *    resolvePath / Files.exists / 文件读 / HTML 内联（副作用全部未发生）。 */
  private def assertDenied(
    label: String,
    agentDef: Option[nebflow.agent.AgentDef],
    depth: Int
  ): Unit =
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(gateInput, ctxWith(buf, agentDef, depth)).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains(VerbatimDeny), s"$label: verbatim author text missing in: ${err.message}")
        assert(err.message.contains("POP_NEBULA_ONLY"), s"$label: error code missing in: ${err.message}")
        assert(!err.message.toLowerCase.contains("not found"),
          s"$label: gate must run BEFORE path resolution/file read, got: ${err.message}")
        assertEquals(buf.size, 0, s"$label: zero WS side effects expected, got $buf")
      case Right(ok) => fail(s"$label: must be denied, got Right($ok)")

  test("identity gate: Nebula root session (depth 0) is admitted — feature intact"):
    val dir = tempDir("gate-allow")
    val html = dir / "report.html"
    os.write.over(html, "<html><body>hi</body></html>")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> html.toString.asJson), ctxWith(buf, Some(nebulaDef), 0)).unsafeRunSync() match
      case Right(msg) => assert(msg.contains("Canvas"), msg)
      case Left(err)  => fail(s"Nebula root session must be admitted: ${err.message}")
    assertEquals(buf.size, 1, "exactly one popFile message for the admitted caller")
    assertEquals(buf.head.hcursor.downField("type").as[String].toOption, Some("popFile"))

  test("identity gate: non-Nebula agent identities are denied (general / project-dispatcher / dream)"):
    assertDenied("general", Some(defNamed("general")), 0)
    assertDenied("project-dispatcher", Some(defNamed("project-dispatcher")), 0)
    assertDenied("dream", Some(defNamed("dream")), 0)

  test("identity gate: Nebula-derived sessions are denied — node session (depth 1) never counts as Nebula itself"):
    // NodeDef.agent="Nebula" 的节点会话 = depth 1（SandboxPolicy.isNebulaRootSession
    // 同款判据）；SubTask worker / 子 agent 同理 —— 「Nebula 自己」只在 depth==0。
    assertDenied("Nebula node session (depth 1)", Some(nebulaDef), 1)
    assertDenied("Nebula sub-agent (depth 2)", Some(nebulaDef), 2)

  test("identity gate: agentDef=None (REST direct / spec harness) fails closed"):
    // 决策：fail-closed。实测无合法非 agent 调用面被误伤——Pop 不在
    // RemoteExecutor.remoteableTools（RemoteExecutor.scala:658），remote-exec
    // 接收侧（RestApiRoutes.scala:1154）不传 agentDef 也不传 wsSend。
    assertDenied("agentDef=None", None, 0)

  test("identity gate: denial precedes even input validation (no filePath still POP_NEBULA_ONLY)"):
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject.empty, ctxWith(buf, Some(defNamed("general")), 0)).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains("POP_NEBULA_ONLY"), err.message)
        assertEquals(buf.size, 0)
      case Right(ok) => fail(s"must be denied before input validation, got Right($ok)")

  test("description states the Nebula-only contract and the node delivery protocol"):
    assert(PopTool.description.contains("Nebula-exclusive"), "exclusivity must be stated")
    assert(PopTool.description.contains("POP_NEBULA_ONLY"), "error code surfaced in the description")
    assert(PopTool.description.contains("out edge"), "node delivery protocol (hand over along the out edge)")
    assert(!PopTool.description.contains("never hand-draw"),
      "node-facing 'Pop right after generating' guidance must be gone")

  override def afterEach(context: munit.AfterEach): Unit =
    // test artifacts under target/ are cleaned by sbt; nothing else to do
    ()

end PopToolSpec
