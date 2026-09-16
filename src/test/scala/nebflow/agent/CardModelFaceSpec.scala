package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.{PathUtil, ToolExecResult, ToolsLogWriter}
import nebflow.core.tools.{CardTool, ToolContext, ToolResultGuard}
import nebflow.shared.ToolCall

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * imgticket 批 ii（**模型面-用户面分离**，作者 #687-D 2026-09-16「做」）的引擎面回归钉。
 *
 * 判据面 = `AgentCore.executeTool` 的两条出边（**真链路**，不是直接调 helper）：
 *   - **模型面** = `ToolExecResult.content` —— `AgentActor` 把它做成
 *     `ContentBlock.ToolResult(call.id, r.content, …)` 交给 LLM；
 *   - **用户面** = `ToolExecResult.frontendContent` —— `AgentCore` 的 ToolEnd 发射点
 *     （`:1283` `r.frontendContent.getOrElse(r.content)`）把它放进 WS 帧的 `content`，
 *     前端卡面（`chat.js` / `persistence.js` / `chatSearch.js`）与 `.ui.json` 历史行
 *     全部消费这一条。
 *
 * 缺陷形态（改前）：Card 的唯一返回值是**整张卡片载荷**（HTML 正文 + 内联图 base64），
 * 引擎把它同时交给两侧 ⇒ 模型收到的是「几十万字符的 JSON，被 `ToolResultGuard` 的
 * 50,000 字符阈值截成 2,048 字符预览 + 磁盘副本」，而正文里的 base64 对模型零信息量。
 *
 * 本 spec 钉住的判据（每条都机械可判、且**改前必红**）：
 *   C1 模型面不含 HTML 标签序列 / 不含 `data:image/` / 不含只存在于正文里的标记；
 *   C2 模型面长度**与卡片 HTML 体量无关**（1 KB 体 vs ~1.4 MB 体 ⇒ 长度差 ≤ 8 字符）；
 *   C3 模型面在 `ToolResultGuard.guardResult` 之下**不再触发**持久化（改前：preview +
 *      磁盘副本）；
 *   C4 用户面逐字全文不丢（`frontendContent` == 工具原样载荷，sha256 双侧相等）；
 *   C5 两侧同时成立（模型看不见 ∧ 前端看得见）；
 *   C6 头行摘要（`summarizeResult`）在模型面上仍读得出计数（改前它只认哨兵前缀载荷
 *      —— 模型面改造后若不同步放宽，卡片头会静默丢掉「N file reference(s) NOT proxied」）。
 *   C7 非 Card 工具零影响（内容面恒等 —— `Read` 的 content 与 frontendContent 同值）。
 *
 * 夹具口径（2026-09-17 `cardfacespecfix` 修复批，author #706 方案 A）：内联有**两道
 * 闸** —— 单图 ≤5MB **且** 本次调用内联合计 ≤`FileRefs.MaxInlinePayloadChars`
 * （40,000 字符）。故凡需要「载荷过 50,000 线」的夹具，一律由**大正文**给出（与 C2
 * 同形），内联图一律取预算内的 `writePng(…, 64)`（`data:` URI = 16,582 字符）；
 * 400² 噪点图（`data:` URI = 641,066 字符）会被累计预算挡下、回落 `/api/nf-file`
 * 引用腿，载荷随之缩回线下 ⇒ 夹具静默失效（正是本批修复的红窗）。
 *
 * 离线纪律：零实例、零网络、零 8080；`PathUtil.dataRoot` 与 `ToolsLogWriter` 目录
 * 全部重定向到临时目录（guard 的磁盘副本 / 工具日志**不落宿主数据根**），收尾复原。
 */
class CardModelFaceSpec extends FunSuite:

  private val sentinel = "___CARD_HTML___"

  /** 只可能出现在卡片 HTML **正文本体**里的串——模型面出现它即判红。 */
  private val bodyMarker = "CARD-BODY-MARKER-7c1f9a"

  private var tmpDir: Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-card-model-face-")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    ToolsLogWriter.setDirForTest(tmpDir)
    super.beforeEach(context)

  override def afterEach(context: AfterEach): Unit =
    ToolsLogWriter.flushSync()
    ToolsLogWriter.resetDirForTest()
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // AgentCore.executeTool is protected; expose via minimal stub (ToolsLogAgentCoreSpec pattern)
  private object CoreProbe extends AgentCore:
    def exec(call: ToolCall, ctx: ToolContext): IO[ToolExecResult] = executeTool(call, ctx)

  private def ctx: ToolContext = ToolContext(projectRoot = tmpDir.toString, sessionId = Some("sess-cardface"))

  // ── fixtures ────────────────────────────────────────────────────────────

  /** 真 PNG（`Random(42)` ⇒ 逐次确定；噪点 ⇒ 不可压缩，尺寸可预期）。 */
  private def writePng(name: String, side: Int): Path =
    val img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val rnd = new java.util.Random(42L)
    var y = 0
    while y < side do
      var x = 0
      while x < side do
        img.setRGB(x, y, rnd.nextInt() & 0xffffff)
        x += 1
      y += 1
    val p = tmpDir.resolve(name)
    javax.imageio.ImageIO.write(img, "png", p.toFile)
    p

  private def card(input0: (String, String)*): JsonObject = JsonObject.fromIterable(input0.map((k, v) => k -> Json.fromString(v)))

  private def callOf(input: JsonObject, id: String = "call-card-1"): ToolCall =
    ToolCall(id = id, name = "Card", input = input)

  /** 引擎面真跑一次 `Card`：返回 (模型面结果, 完整 ToolExecResult)。 */
  private def runCard(input: JsonObject, id: String = "call-card-1"): (String, ToolExecResult) =
    val call = callOf(input, id)
    val res = CoreProbe.exec(call, ctx).unsafeRunSync()
    assert(!res.isError, s"Card failed: ${res.content.take(200)}")
    (res.content, res)

  /** 工具自己产出的**原样载荷**（不经引擎）——用户面逐字比对的基准值。 */
  private def rawPayload(input: JsonObject): String =
    CardTool.call(input, ctx).unsafeRunSync().fold(e => fail(s"Card returned Left: ${e.message}"), identity)

  private def sha256(s: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

  private def htmlChars(face: String): Int =
    io.circe.parser
      .parse(face)
      .toOption
      .flatMap(_.hcursor.get[Int]("htmlChars").toOption)
      .getOrElse(fail(s"model face has no htmlChars: ${face.take(200)}"))

  private val tagRe = "<[a-zA-Z/!][^>]*>".r

  // ── C1 / C2 模型面收敛（本批核心判据）────────────────────────────────────

  test("C1: model face carries no HTML body — no tag sequence, no data: URI, no body marker") {
    // 夹具口径（2026-09-17 cardfacespecfix 修复批）：内联有**两道闸**——单图 ≤5MB
    // **且**本次调用内联合计 ≤40,000 字符。⇒「过线」不再靠超大图（400² 噪点图的
    // data URI = 641,066 字符撞累计预算 ⇒ 回落引用腿 ⇒ 载荷缩回 50,000 线下），
    // 改由**大正文**给出（与 C2 同形）；图取预算内的小图 ⇒ 仍真内联、`data:` 面非空。
    val png = writePng("inline.png", 64) // data URI = 16,582 字符（≤40,000，稳过累计预算）
    val html =
      s"""<div class="wrap"><h1>report</h1><img src="$png" style="width:100%"><p>$bodyMarker${"x" * 120_000}</p></div>"""
    val input = card("html" -> html, "title" -> "Big Report")
    val (face, res) = runCard(input)

    val raw = rawPayload(input)
    println(s"[CARD-FACE-READING] C1 modelFaceChars=${face.length} payloadChars=${raw.length} face=${face.take(400)}")
    assert(raw.length > 50_000, s"fixture must exceed the guard threshold (was ${raw.length})")
    assert(raw.contains("data:image/png;base64,"), "fixture must really carry an inline image")

    assertEquals(tagRe.findFirstIn(face), None, s"model face must carry no HTML tag sequence: ${face.take(300)}")
    assert(!face.contains("data:image/"), s"model face must carry no data: URI: ${face.take(300)}")
    assert(!face.contains(bodyMarker), s"model face must not carry the body text: ${face.take(300)}")
    assert(!face.contains("<img"), "no tag fragment either")
    assert(face.length <= 2_000, s"model face must be a summary (was ${face.length} chars)")
    assert(face.length < 50_000, "model face stays under the guard threshold by construction")
    // 用户面：同一张卡仍带着全文（两侧同时成立 = C5）
    assertEquals(res.frontendContent.getOrElse(""), raw)
    assert(res.frontendContent.get.contains(bodyMarker) && res.frontendContent.get.contains("data:image/png;base64,"))
    assert(
      res.frontendContent.get.length > 100 * face.length,
      s"user face (${res.frontendContent.get.length}) must dwarf the model face (${face.length})"
    )
  }

  test("C2: model face length is independent of the card HTML body size (1 KB vs ~1.4 MB)") {
    val smallHtml = s"<div>$bodyMarker</div>"
    val bigHtml = s"<div>$bodyMarker${"x" * 1_400_000}</div>"
    val small = card("html" -> smallHtml, "title" -> "T")
    val big = card("html" -> bigHtml, "title" -> "T")
    val (faceSmall, resSmall) = runCard(small, "call-small")
    val (faceBig, _) = runCard(big, "call-big")
    val rawBig = rawPayload(big)
    assert(rawBig.length > 1_400_000, "big fixture must really be ~1.4 MB")
    val delta = math.abs(faceBig.length - faceSmall.length)
    // 唯一允许的差异 = htmlChars 的位数（1 KB → 4 位，1.4 MB → 7 位）
    assert(delta <= 8, s"model face must not grow with the body (delta=$delta)\nsmall=$faceSmall\nbig=$faceBig")
    assert(faceBig.length < 50_000, s"big card's model face must stay a summary (was ${faceBig.length})")
    assert(math.abs(htmlChars(faceBig) - htmlChars(faceSmall)) > 100_000, "the fixtures must differ in body size")
    // 无引用的卡：用户面正文与输入 html **逐字相等**（卡片正文零改写）
    val payloadSmall = resSmall.frontendContent.getOrElse(fail("frontendContent must be set"))
    val htmlOut = io.circe.parser
      .parse(payloadSmall.substring(sentinel.length))
      .fold(e => fail(s"payload json: $e"), identity)
      .hcursor
      .get[String]("html")
      .toOption
      .getOrElse(fail("payload html"))
    assertEquals(sha256(htmlOut), sha256(smallHtml), "verbatim body: ref-free card html is unrewritten")
  }

  // ── C3 guard 不再触发（改前：preview + 磁盘副本）──────────────────────────

  test("C3: guarded model face stays intact and persists no disk copy (pre-change: preview + file)") {
    // 过线载荷同 C1 = **大正文**（小图仍在 40,000 累计预算内、真内联）：本用例的判据
    // （guard 不再触发）只有载荷**真的过了 50,000 线**才非真空，故前提写成断言。
    val png = writePng("guard.png", 64)
    val input = card("html" -> s"""<div><img src="$png"><p>$bodyMarker${"x" * 120_000}</p></div>""", "title" -> "Guarded")
    val call = callOf(input, "call-guard")
    val payload = rawPayload(input)
    assert(payload.length > 50_000, s"fixture must exceed the guard threshold (was ${payload.length})")
    val res = CoreProbe.exec(call, ctx).unsafeRunSync()
    val guarded = ToolResultGuard.guardResult(call, res, "sess-cardface").unsafeRunSync()

    assert(guarded.content.length <= 50_000, s"model face must not be guard-replaced (was ${guarded.content.length})")
    assert(!guarded.content.startsWith("<persisted-output>"), s"guard must not fire on Card: ${guarded.content.take(120)}")
    val persisted = tmpDir.resolve("tool-results").resolve("sess-cardface")
    val files = if Files.isDirectory(persisted) then Files.list(persisted).iterator().asScala.toList else Nil
    assert(files.isEmpty, s"no disk copy for a card's model face: $files")
    assertEquals(guarded.frontendContent.getOrElse(""), rawPayload(input))
  }

  // ── C4 / C5 用户面逐字全文（硬）──────────────────────────────────────────

  test("C4: user face keeps the payload verbatim — both sides' sha256 equal, fixture hash stable") {
    // 逐字全文的「全文」同样由**大正文**给出（小图在 40,000 累计预算内 ⇒ 真内联），
    // 于是本用例同时捏住「正文标记 + 内联图 data URI」两件（见下方两条断言）。
    val png = writePng("verbatim.png", 64)
    val html = s"""<div><img src="$png"><p>$bodyMarker${"x" * 120_000}</p></div>"""
    val input = card("html" -> html, "title" -> "Verbatim")
    val raw = rawPayload(input)
    val raw2 = rawPayload(input)
    assert(raw.length > 50_000, s"fixture must really carry a large body (was ${raw.length})")
    assertEquals(sha256(raw), sha256(raw2), "payload construction is deterministic (no clock/random in the payload)")

    val (_, res) = runCard(input)
    val front = res.frontendContent.getOrElse(fail("frontendContent must be set"))
    assert(front.startsWith(sentinel), s"user face keeps the card sentinel: ${front.take(40)}")
    assertEquals(sha256(front), sha256(raw), "user face == tool payload byte-for-byte (sha256)")
    assertEquals(front, raw)
    // 逐字全文：正文标记 + 内联图 data URI 一个不少
    val parsed = io.circe.parser.parse(front.substring(sentinel.length)).fold(e => fail(s"payload json: $e"), identity)
    val htmlOut = parsed.hcursor.get[String]("html").toOption.getOrElse(fail("payload html"))
    assert(htmlOut.contains(bodyMarker), "verbatim body text survives")
    assert(htmlOut.contains("data:image/png;base64,"), "inline image survives")
    assertEquals(parsed.hcursor.get[String]("title").toOption, Some("Verbatim"))
    // 读数落盘（改前/改后两次运行的同一组值 ⇒ 用户面逐字等价）
    println(s"[CARD-FACE-READING] userFaceChars=${front.length} userFaceSha256=${sha256(front)}")
    println(s"[CARD-FACE-READING] htmlChars=${htmlOut.length} htmlSha256=${sha256(htmlOut)}")
  }

  // ── C6 头行摘要不再静默丢计数 ────────────────────────────────────────────

  test("C6: the chat-header summary still reads the counters off the model face") {
    val missing = tmpDir.resolve("nope-does-not-exist.png").toString
    val input = card("html" -> s"""<div><img src="$missing"><p>$bodyMarker</p></div>""", "title" -> "Warned")
    val (face, _) = runCard(input)
    val raw = rawPayload(input)
    // 真载荷导出（M3 渲染读面的输入）：改前/改后同一输入 ⇒ 同一卡面
    println(s"[CARD-FACE-PAYLOAD] ${raw}")
    assert(raw.contains("\"failed\":1"), s"fixture must carry one failed reference: ${raw.take(300)}")
    val fromFace = CardTool.summarizeResult(input, face)
    val fromRaw = CardTool.summarizeResult(input, raw)
    assert(fromFace.contains("NOT proxied"), s"counters must still be readable from the model face: $fromFace")
    assertEquals(fromFace, fromRaw, "header summary is byte-identical on both faces")
  }

  // ── C7 非 Card 工具零影响 ────────────────────────────────────────────────

  test("C7: a non-Card tool's two faces stay identical (content == frontendContent)") {
    val f = tmpDir.resolve("plain.txt")
    Files.writeString(f, "hello model face")
    val call = ToolCall(id = "call-read-1", name = "Read", input = JsonObject("file_path" -> f.toString.asJson))
    val res = CoreProbe.exec(call, ctx).unsafeRunSync()
    assert(!res.isError, s"Read failed: ${res.content.take(200)}")
    assertEquals(res.content, res.frontendContent.getOrElse(fail("frontendContent must be set")))
    assert(res.content.contains("hello model face"), "other tools are untouched")
  }
end CardModelFaceSpec
