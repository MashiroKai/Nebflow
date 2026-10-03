package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * PopTool — pop-upgrade batch (2026-10-03 作者令「去掉card工具,然后对Pop工具
 * 进行升级」+「Pop要支持一次工具调用多个Pop文件」).
 *
 * The tool's faces, pinned end-to-end through call():
 *  - **payload face**: files/media produce a `___POP_JSON___{…}` result (NO WS
 *    message, NO Canvas tab) whose items render in the chat (media stack /
 *    file cards); the model face ([[modelFacingResult]]) is a small projection.
 *  - **URL face**: a single HTTP/HTTPS `filePath` still opens a Canvas tab via
 *    the `popFile` WS message and returns plain text.
 *  - **batch face**: `filePath` accepts a string or an array (≤ MaxPopFiles).
 *  - **identity gate** (2026-09-10 作者裁定, unchanged): call 最前的两道同真判据
 *    ——① ctx.agentDef 名为 Nebula；② ctx.depth == 0。agentDef=None fail-closed。
 *    拒答含逐字作者文案 + POP_NEBULA_ONLY，先于任何副作用。
 *
 * The per-item probing/inlining/budget/decoding details are pinned in
 * `PopToolFileRefSpec` + `FileRefsInlineBudgetSpec` + `FileRefsServabilityScopeSpec`.
 */
class PopToolSpec extends FunSuite:

  // Phase 5 解耦接线:FileRefs 的端点判据窄端口(生产在 GatewayMain 装配;spec 自接线)。
  nebflow.core.FilePolicyPort.install(nebflow.gateway.NfFilePolicy)

  /** Nebula 本体根会话身份（正常放行面）。 */
  private val nebulaDef = nebflow.actor.AgentDef(name = "Nebula", description = "", tools = Nil)

  private def defNamed(name: String): nebflow.actor.AgentDef =
    nebflow.actor.AgentDef(name = name, description = "", tools = Nil)

  private def tempDir(name: String): os.Path =
    val d = os.pwd / "target" / s"test-pop-$name-${java.util.UUID.randomUUID().toString.take(6)}"
    os.makeDir.all(d)
    d

  /**
   * Capture wsSend messages into a buffer. 身份闸批：harness 显式传 Nebula 本体
   * ctx（agentDef=Some(Nebula)、depth=0）。
   */
  private def captureCtx(buf: scala.collection.mutable.ListBuffer[Json]): ToolContext =
    ctxWith(buf, agentDef = Some(nebulaDef), depth = 0)

  /** 身份参数化 harness——闸测试用（同一 wsSend 缓冲，身份/depth 可变）。 */
  private def ctxWith(
    buf: scala.collection.mutable.ListBuffer[Json],
    agentDef: Option[nebflow.actor.AgentDef],
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

  /** Run Pop and return (raw result, ws messages). */
  private def pop(input: JsonObject, buf: scala.collection.mutable.ListBuffer[Json]): Either[ToolError, String] =
    PopTool.call(input, captureCtx(buf)).unsafeRunSync()

  private def payloadOf(result: String): Json =
    assert(result.startsWith(PopTool.Sentinel), s"result must start with the sentinel: ${result.take(80)}")
    io.circe.parser.parse(result.substring(PopTool.Sentinel.length)) match
      case Right(json) => json
      case Left(err)   => fail(s"everything after the sentinel must be pure JSON: $err")

  private def itemsOf(p: Json): List[Json] =
    p.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)

  private def kindsOf(p: Json): List[String] =
    itemsOf(p).flatMap(_.hcursor.get[String]("kind").toOption)

  private def warningsOf(p: Json): List[Json] =
    p.hcursor.downField("warnings").as[List[Json]].toOption.getOrElse(Nil)

  // ====================================================================
  // payload face：文件/媒体 → ___POP_JSON___ 载荷，零 WS 副作用
  // ====================================================================

  test("a single image rides inline as a data: URI (kind=image, src present), zero WS messages"):
    val dir = tempDir("img")
    os.write.over(dir / "shot.png", Array.fill(8)(0x42.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    pop(JsonObject("filePath" -> (dir / "shot.png").toString.asJson), buf) match
      case Right(raw) =>
        assertEquals(buf.size, 0, "files open NO Canvas tab — the payload alone carries the artifact")
        val p = payloadOf(raw)
        assertEquals(kindsOf(p), List("image"))
        val src = itemsOf(p).head.hcursor.get[String]("src").toOption.getOrElse(fail("src expected"))
        assert(src.startsWith("data:image/png;base64,"), src.take(40))
      case Left(err) => fail(s"Pop failed: ${err.message}")

  test("a batch of media + files produces ONE payload with ordered items (multi-file Pop, author addition)"):
    val dir = tempDir("batch")
    os.write.over(dir / "a.png", Array.fill(8)(0x41.toByte))
    os.write.over(dir / "b.mp4", Array.fill(8)(0x42.toByte))
    os.write.over(dir / "report.pdf", Array.fill(8)(0x43.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    pop(
      JsonObject("filePath" -> Json.arr(
        (dir / "a.png").toString.asJson,
        (dir / "b.mp4").toString.asJson,
        (dir / "report.pdf").toString.asJson
      )),
      buf
    ) match
      case Right(raw) =>
        assertEquals(buf.size, 0, "a batch never opens Canvas tabs")
        val p = payloadOf(raw)
        assertEquals(kindsOf(p), List("image", "video", "file"), "input order is the payload order")
        val video = itemsOf(p)(1)
        assertEquals(video.hcursor.get[String]("kind").toOption, Some("video"))
        assert(video.hcursor.get[String]("src").toOption.isEmpty, "a video is never inlined — ticket leg")
        assertEquals(video.hcursor.get[String]("path").toOption, Some((dir / "b.mp4").toString))
        val pdf = itemsOf(p)(2)
        assertEquals(pdf.hcursor.get[String]("itemType").toOption, Some("pdf"), "file cards carry the viewer itemType")
      case Left(err) => fail(s"Pop failed: ${err.message}")

  test("a missing file is a payload WARNING (failed=1), not a tool error — the rest of the batch still shows"):
    val dir = tempDir("missing")
    os.write.over(dir / "here.png", Array.fill(8)(0x42.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    pop(
      JsonObject("filePath" -> Json.arr((dir / "here.png").toString.asJson, (dir / "ghost.png").toString.asJson)),
      buf
    ) match
      case Right(raw) =>
        val p = payloadOf(raw)
        assertEquals(kindsOf(p), List("image"), "the existing file still shows")
        val w = warningsOf(p).headOption.getOrElse(fail("warnings expected"))
        assertEquals(w.hcursor.get[String]("ref").toOption, Some((dir / "ghost.png").toString))
        assertEquals(w.hcursor.get[String]("reason").toOption, Some("not-found"))
        val fileRefs = p.hcursor.downField("fileRefs")
        assertEquals(fileRefs.get[Int]("failed").toOption, Some(1))
      case Left(err) => fail(s"Pop must not hard-fail on a missing batch member: ${err.message}")

  test("input-shape errors are ToolErrors: empty input, a URL inside an array, and over-the-cap batches"):
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    pop(JsonObject.empty, buf) match
      case Left(err) => assert(err.message.contains("filePath"), err.message)
      case Right(_)  => fail("empty input must be refused")

    pop(JsonObject("filePath" -> Json.arr("https://example.com".asJson)), buf) match
      case Right(text) =>
        // A LONE URL in an array behaves like the string form (the Canvas leg) —
        // only a URL MIXED with file paths is refused (the batch leg's error below).
        assertEquals(text, "Opened example.com in Canvas.")
      case Left(err) => fail(s"a lone URL in an array is the URL leg: ${err.message}")

    pop(
      JsonObject("filePath" -> Json.arr("https://example.com".asJson, "/tmp/x.png".asJson)),
      buf
    ) match
      case Left(err) => assert(err.message.contains("cannot be batched"), err.message)
      case Right(_)  => fail("a URL mixed with file paths must be refused")

    val many = Json.arr(List.fill(PopTool.MaxPopFiles + 1)("x.png").map(_.asJson)*)
    pop(JsonObject("filePath" -> many), buf) match
      case Left(err) => assert(err.message.contains("at most"), err.message)
      case Right(_)  => fail("an over-cap batch must be refused")
    assertEquals(buf.size, 1, "only the lone-URL leg sent a popFile frame; the refusals sent none")

  // ====================================================================
  // URL face：单条 HTTP(S) 仍开 Canvas 标签（行为不变）
  // ====================================================================

  test("a single URL still opens the Canvas tab (popFile WS + plain-text result)") {
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    pop(JsonObject("filePath" -> "https://example.com".asJson), buf) match
      case Right(text) =>
        assertEquals(text, "Opened example.com in Canvas.")
        assertEquals(buf.size, 1, "exactly one popFile message for the admitted caller")
        assertEquals(buf.head.hcursor.downField("type").as[String].toOption, Some("popFile"))
        assertEquals(buf.head.hcursor.downField("item").get[String]("itemType").toOption, Some("url"))
      case Left(err) => fail(s"URL Pop failed: ${err.message}")
  }

  test("modelFacingResult: the model face is the URL text for the URL leg, and a projection for the payload") {
    val urlResult = "Opened example.com in Canvas."
    assertEquals(PopTool.modelFacingResult(urlResult), urlResult, "URL leg: identity")

    val dir = tempDir("modelface")
    os.write.over(dir / "shot.png", Array.fill(8)(0x42.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val raw = pop(JsonObject("filePath" -> (dir / "shot.png").toString.asJson), buf).toOption.getOrElse(fail("expected Right"))
    val model = PopTool.modelFacingResult(raw)
    assert(!model.contains(PopTool.Sentinel), "the model face never carries the sentinel")
    assert(!model.contains("base64"), "the model face never carries data URIs")
    val p = io.circe.parser.parse(model).toOption.getOrElse(fail(s"model face must be JSON: $model"))
    assertEquals(p.hcursor.get[Int]("media").toOption, Some(1))
    assertEquals(p.hcursor.get[Int]("files").toOption, Some(0))
    assert(p.hcursor.get[String]("note").toOption.exists(_.contains("act on")), "the projection tells the model what to act on")
  }

  test("summarize: one file names it, a batch names the first file +N"):
    assertEquals(
      PopTool.summarize(JsonObject("filePath" -> "/tmp/shot.png".asJson)),
      "Pop\n  (shot.png)"
    )
    assertEquals(
      PopTool.summarize(JsonObject("filePath" -> Json.arr("/tmp/a.png".asJson, "/tmp/b.png".asJson, "/tmp/c.mp4".asJson))),
      "Pop\n  (a.png +2)"
    )

  // ====================================================================
  // Nebula 专属身份闸（2026-09-10 作者裁定，逐字不变）
  // ====================================================================

  /** 逐字作者文案（裁定原文，不得改述）。 */
  private val VerbatimDeny =
    "Pop 已收归 Nebula 专属；交付物请沿 out 边交给链末端 / Nebula，由 Nebula 决定是否展示"

  private val gateInput = JsonObject("filePath" -> "/nonexistent/pop-gate-probe.html".asJson)

  /**
   * 拒答断言：右值缺席 + 文案命中 + 零副作用。
   *
   * 零副作用两点证明：
   *  - wsSend 缓冲空；
   *  - filePath 指向不存在的路径却报闸文案而非 "not found" ⇒ 闸先于
   *    resolvePath / Files.exists / 文件读（副作用全部未发生）。
   */
  private def assertDenied(
    label: String,
    agentDef: Option[nebflow.actor.AgentDef],
    depth: Int
  ): Unit =
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(gateInput, ctxWith(buf, agentDef, depth)).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains(VerbatimDeny), s"$label: verbatim author text missing in: ${err.message}")
        assert(err.message.contains("POP_NEBULA_ONLY"), s"$label: error code missing in: ${err.message}")
        assert(
          !err.message.toLowerCase.contains("not found"),
          s"$label: gate must run BEFORE path resolution/file read, got: ${err.message}"
        )
        assertEquals(buf.size, 0, s"$label: zero WS side effects expected, got $buf")
      case Right(ok) => fail(s"$label: must be denied, got Right($ok)")

  end assertDenied

  test("identity gate: Nebula root session (depth 0) is admitted — feature intact"):
    val dir = tempDir("gate-allow")
    os.write.over(dir / "shot.png", Array.fill(8)(0x42.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    pop(JsonObject("filePath" -> (dir / "shot.png").toString.asJson), buf) match
      case Right(raw) => assert(raw.startsWith(PopTool.Sentinel), raw.take(60))
      case Left(err)  => fail(s"Nebula root session must be admitted: ${err.message}")
    assertEquals(buf.size, 0, "the admitted file face sends no WS message")

  test("identity gate: non-Nebula agent identities are denied (general / project-dispatcher / dream)"):
    assertDenied("general", Some(defNamed("general")), 0)
    assertDenied("project-dispatcher", Some(defNamed("project-dispatcher")), 0)
    assertDenied("dream", Some(defNamed("dream")), 0)

  test("identity gate: Nebula-derived sessions are denied — node session (depth 1) never counts as Nebula itself"):
    // NodeDef.agent="Nebula" 的节点会话 = depth 1（SandboxPolicy.isSandboxRootSession
    // 同款判据）；SubTask worker / 子 agent 同理 —— 「Nebula 自己」只在 depth==0。
    assertDenied("Nebula node session (depth 1)", Some(nebulaDef), 1)
    assertDenied("Nebula sub-agent (depth 2)", Some(nebulaDef), 2)

  test("identity gate: agentDef=None (REST direct / spec harness) fails closed"):
    // 决策：fail-closed。实测无合法非 agent 调用面被误伤——Pop 不在
    // RemoteExecutor.remoteableTools，remote-exec 接收侧不传 agentDef 也不传 wsSend。
    assertDenied("agentDef=None", None, 0)

  test("identity gate: denial precedes even input validation (no filePath still POP_NEBULA_ONLY)"):
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject.empty, ctxWith(buf, Some(defNamed("general")), 0)).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.contains("POP_NEBULA_ONLY"), err.message)
        assertEquals(buf.size, 0)
      case Right(ok) => fail(s"must be denied before input validation, got Right($ok)")

  test("description states the Nebula-only contract, the node delivery protocol and the pop-upgrade faces"):
    val d = PopTool.description
    assert(d.contains("Nebula-exclusive"), "exclusivity must be stated")
    assert(d.contains("POP_NEBULA_ONLY"), "error code surfaced in the description")
    assert(d.contains("out edge"), "node delivery protocol (hand over along the out edge)")
    assert(
      !d.contains("never hand-draw"),
      "node-facing 'Pop right after generating' guidance must be gone"
    )
    // pop-upgrade 批的面契约
    assert(d.contains("FILE CARD"), "the file-card face must be documented")
    assert(d.contains("stacked card"), "the media stack face must be documented")
    assert(d.contains("AFTER your final text"), "the call-last placement rule must be documented")
    assert(d.contains("array"), "the multi-file batch face must be documented")

  override def afterEach(context: munit.AfterEach): Unit =
    // test artifacts under target/ are cleaned by sbt; nothing else to do
    ()

end PopToolSpec
