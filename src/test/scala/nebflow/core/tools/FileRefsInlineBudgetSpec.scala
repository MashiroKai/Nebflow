package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption as SO
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * img-ticket batch i (2026-09-16, #687-C) — the cumulative inline budget.
 *
 * 判据（作者裁定，逐字）：**单次工具调用内所有内联项合计 ≤ 40,000 字符**。单图
 * 5MB 上限不变；两道闸是「与」关系。本 spec 钉住四件事：
 *
 *  1. **常量**：`MaxInlinePayloadChars == 40000`，`MaxEmbedImageSize == 5MB`（后者
 *     本批未动）。
 *  2. **确定性可复算**：费用函数 `dataUriChars(size, ext)` 必须等于真实 `data:`
 *     URI 的字符数（不是估算）；预算按扫描顺序**先到先占**，装不下的项**零扣费**、
 *     不阻断后续（不是「第一个超限就整体停」）。
 *  3. **三点边界**：39,999 / 40,000 / 40,001。
 *  4. **两个消费面**：Card（文档顺序、多张图）与 Pop（`<img src>` 顺序 + 直开图片
 *     腿），以及「超限 ⇒ 回落既有引用腿、计入 `deferred`、不产生告警」。
 *
 * 夹具口径：预算只看**文件大小**，故大件用稀疏文件（只写末字节）构造——与真实
 * 图片在判据上等价，且不占内存、不拖慢 suite。
 */
class FileRefsInlineBudgetSpec extends FunSuite:

  // Phase 5 解耦接线:FileRefs 的端点判据窄端口(生产在 GatewayMain 装配;spec 自接线)。
  nebflow.core.FilePolicyPort.install(nebflow.gateway.NfFilePolicy)

  // ── 夹具 ────────────────────────────────────────────────────────────────

  private val PngPrefix = "data:image/png;base64,"

  private def withTempDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("inline-budget-")
    try f(dir)
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)

  /**
   * A file of exactly `size` bytes; only the last byte is written (sparse), so
   *  `size` is what the budget arithmetic sees.
   */
  private def writeSized(p: Path, size: Int, fill: Byte = 0x44): Path =
    assert(size >= 1, s"a sized fixture must be at least 1 byte, got $size")
    val ch = Files.newByteChannel(p, SO.CREATE, SO.WRITE, SO.TRUNCATE_EXISTING)
    try
      ch.position(size.toLong - 1L)
      ch.write(ByteBuffer.wrap(Array(fill)))
    finally ch.close()
    assertEquals(Files.size(p), size.toLong, s"fixture size: $p")
    p

  private def writeText(p: Path, s: String): Path =
    Files.write(p, s.getBytes(StandardCharsets.UTF_8))

  private def dataUriOf(p: Path): String =
    FileRefs.readAsDataUri(p).fold(detail => fail(s"expected a data URI for $p: $detail"), identity)

  // ── Card harness ────────────────────────────────────────────────────────

  private val cardSentinel = "___CARD_HTML___"
  private val cardCtx = ToolContext(projectRoot = os.pwd.toString)

  private def card(html: String): Json =
    val input = JsonObject("html" -> Json.fromString(html), "title" -> Json.fromString("T"))
    val result = CardTool.call(input, cardCtx).unsafeRunSync().getOrElse(fail("expected Right"))
    assert(result.startsWith(cardSentinel), s"payload must start with the sentinel: ${result.take(60)}")
    io.circe.parser.parse(result.substring(cardSentinel.length)) match
      case Right(json) => json
      case Left(err) => fail(s"everything after the sentinel must be pure JSON: $err")

  private def htmlOf(p: Json): String = p.hcursor.get[String]("html").toOption.getOrElse("")
  private def warningsOf(p: Json): List[Json] = p.hcursor.get[List[Json]]("warnings").toOption.getOrElse(Nil)

  private def countOf(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)
  private def encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  // ── Pop harness (same shape as PopToolFileRefSpec: the identity gate needs a
  //    Nebula root ctx) ─────────────────────────────────────────────────────

  private val nebulaDef = nebflow.agent.AgentDef(name = "Nebula", description = "", tools = Nil)

  private def captureCtx(buf: scala.collection.mutable.ListBuffer[Json]): ToolContext =
    ToolContext(
      sessionId = Some("inline-budget-test"),
      sessionStore = None,
      agentDef = Some(nebulaDef),
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = Some((j: Json) => IO { buf += j }),
      projectRoot = ""
    )

  private def popPath(file: Path): (String, Json) =
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> file.toString.asJson), captureCtx(buf)).unsafeRunSync() match
      case Left(err) => fail(s"Pop failed: ${err.message}")
      case Right(text) => (text, buf.head)

  private def popHtml(dir: Path, html: String): (String, Json, String) =
    val file = dir.resolve("report.html")
    writeText(file, html)
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> file.toString.asJson), captureCtx(buf)).unsafeRunSync() match
      case Left(err) => fail(s"Pop failed: ${err.message}")
      case Right(text) =>
        val item = buf.head.hcursor.downField("item")
        (text, buf.head, item.get[String]("content").toOption.getOrElse(""))

  private def itemField(msg: Json, field: String): Option[String] =
    msg.hcursor.downField("item").get[String](field).toOption

  private def itemCounter(msg: Json, field: String): Int =
    msg.hcursor.downField("item").downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  // ── ① 常量 ──────────────────────────────────────────────────────────────

  test("budget: the cumulative cap is 40,000 chars and the per-image 5MB gate is untouched") {
    assertEquals(FileRefs.MaxInlinePayloadChars, 40000)
    assertEquals(FileRefs.MaxEmbedImageSize, 5L * 1024 * 1024)
    assertEquals(FileRefs.InlineBudget().maxChars, 40000, "the default budget is the shipped constant")
  }

  // ── ② 费用函数 = 真实长度（可复算） ───────────────────────────────────────

  test("cost: dataUriChars(size, ext) is the EXACT data: URI length (recomputable by hand)") {
    withTempDir { dir =>
      for (size, ext) <- List((0, "png"), (1, "png"), (3, "svg"), (64, "jpg"), (14983, "png"), (29984, "png")) do
        val f =
          if size == 0 then Files.createFile(dir.resolve(s"c-$size-$ext.$ext"))
          else writeSized(dir.resolve(s"c-$size.$ext"), size)
        val uri = dataUriOf(f)
        assertEquals(
          FileRefs.dataUriChars(Files.size(f), ext),
          uri.length,
          s"the cost function must be an identity, not an estimate (size=$size ext=$ext)"
        )
        val prefix = s"data:${FileRefs.mimeFromExt(ext)};base64,"
        assertEquals(uri.length, prefix.length + 4 * ((size + 2) / 3))
    }
  }

  // ── ③ 三点边界 ──────────────────────────────────────────────────────────

  test("boundary: 39,999 fits, 40,000 fits exactly, 40,001 is refused (three points)") {
    val b = FileRefs.InlineBudget()
    assertEquals(b.remainingChars, 40000)

    // 39,999 then +1 == exactly 40,000: both accepted.
    assert(b.tryCharge(39999), "39,999 must fit")
    assertEquals(b.usedChars, 39999)
    assertEquals(b.remainingChars, 1)
    assert(b.tryCharge(1), "the charge that lands on exactly 40,000 must fit")
    assertEquals(b.usedChars, 40000)
    assertEquals(b.remainingChars, 0)

    // One more char (i.e. a cumulative 40,001) is refused, and costs nothing.
    assert(!b.tryCharge(1), "40,001 must be refused")
    assertEquals(b.usedChars, 40000, "a refusal must not charge")

    // Same three points as a single charge each.
    val one = FileRefs.InlineBudget()
    assert(one.tryCharge(40000))
    assert(!one.tryCharge(40001))
    assertEquals(one.usedChars, 40000)
  }

  test("first-fit: a refused item charges NOTHING, so a later smaller item still fits") {
    val b = FileRefs.InlineBudget()
    assert(!b.tryCharge(45000), "an item larger than the whole budget can never fit")
    assertEquals(b.usedChars, 0)
    assert(b.tryCharge(20000))
    assert(!b.tryCharge(20001), "20,001 would overflow the remaining 20,000")
    assertEquals(b.usedChars, 20000, "the refused item must not have spent anything")
    assert(b.tryCharge(19999), "a later smaller item still fits — the pass is not prefix-stopped")
    assertEquals(b.usedChars, 39999)
    assert(!b.tryCharge(2))
    assertEquals(b.usedChars, 39999)
    assertEquals(b.remainingChars, 1)
  }

  test("embedImage: NotEmbeddable / OverBudget / Right — and only Right charges the budget") {
    withTempDir { dir =>
      val over5mb = writeSized(dir.resolve("big.png"), 5 * 1024 * 1024 + 1)
      val small = writeText(dir.resolve("small.png"), "abc")
      val budget = FileRefs.InlineBudget()

      assertEquals(
        FileRefs.embedImage(over5mb, budget),
        Left(FileRefs.InlineSkip.NotEmbeddable),
        "over the per-image gate: refused as NotEmbeddable, not as OverBudget"
      )
      assertEquals(budget.usedChars, 0)

      val uri = FileRefs.embedImage(small, budget).fold(skip => fail(s"expected an embed: $skip"), identity)
      assertEquals(uri, dataUriOf(small))
      assertEquals(budget.usedChars, FileRefs.dataUriChars(3, "png"))

      // A budget too small for this very image: refused without charging.
      val tight = FileRefs.InlineBudget(maxChars = 10)
      assertEquals(FileRefs.embedImage(small, tight), Left(FileRefs.InlineSkip.OverBudget))
      assertEquals(tight.usedChars, 0)

      // 5MB exactly is still inside the per-image gate (behaviour unchanged),
      // but far beyond the cumulative cap — the two gates are independent. The
      // over-budget read never touches the bytes (the charge is refused first),
      // so this stays cheap even at 5MB.
      val at5mb = writeSized(dir.resolve("at5mb.png"), 5 * 1024 * 1024)
      assert(FileRefs.isInlineImage(at5mb), "5MB exactly stays inlineable (per-image gate unchanged)")
      assert(!FileRefs.isInlineImage(over5mb), "5MB+1 is refused (per-image gate unchanged)")
      assertEquals(FileRefs.embedImage(at5mb, FileRefs.InlineBudget()), Left(FileRefs.InlineSkip.OverBudget))
    }
  }

  // ── ④ Card：文档顺序 + 累计账 ────────────────────────────────────────────

  test("Card: two images whose total fits the cap are both embedded (39,996 chars)") {
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14982, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14982, 0x42)
      val each = FileRefs.dataUriChars(14982, "png")
      assertEquals(each, 19998, "14,982 bytes ⇒ 19,998 chars of data: URI")
      assert(each * 2 <= FileRefs.MaxInlinePayloadChars, "the fixture pair must fit the cap")

      val p = card(s"""<img src="${a.toString}"/><img src="${b.toString}"/>""")
      assertEquals(countOf(p, "inlined"), 2)
      assertEquals(countOf(p, "proxied"), 0)
      assertEquals(countOf(p, "deferred"), 0)
      assertEquals(warningsOf(p), Nil)
      assert(htmlOf(p).contains(dataUriOf(a)) && htmlOf(p).contains(dataUriOf(b)))
    }
  }

  test("Card: the image past the cap keeps its /api/nf-file reference (40,004 > 40,000 ⇒ deferred=1)") {
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14983, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14983, 0x42)
      val each = FileRefs.dataUriChars(14983, "png")
      assertEquals(each, 20002, "14,983 bytes ⇒ 20,002 chars of data: URI")
      assert(each * 2 > FileRefs.MaxInlinePayloadChars, "the fixture pair must overflow the cap by 4 chars")

      val p = card(s"""<img src="${a.toString}"/><img src="${b.toString}"/>""")
      assertEquals(countOf(p, "inlined"), 1)
      assertEquals(countOf(p, "deferred"), 1, "the over-budget item is counted deferred, not warned")
      assertEquals(countOf(p, "proxied"), 1, "…and it is a proxy reference now — both counters, no overlap")
      assertEquals(countOf(p, "failed"), 0)
      assertEquals(warningsOf(p), Nil, "an over-budget image is not a defect: the reference leg still renders it")
      assert(htmlOf(p).contains(dataUriOf(a)), "the FIRST reference in document order takes the budget")
      assert(htmlOf(p).contains(s"/api/nf-file?path=${encode(b.toString)}"), htmlOf(p).take(200))
      assert(!htmlOf(p).contains(dataUriOf(b)), "the over-budget image must not be embedded")
    }
  }

  test("Card: the cap is spent in DOCUMENT ORDER — reversing the references swaps who is embedded") {
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14983, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14983, 0x42)

      val forward = card(s"""<img src="${a.toString}"/><img src="${b.toString}"/>""")
      val reverse = card(s"""<img src="${b.toString}"/><img src="${a.toString}"/>""")

      assertEquals(countOf(forward, "inlined"), 1)
      assertEquals(countOf(reverse, "inlined"), 1)
      assertEquals(countOf(forward, "deferred"), 1)
      assertEquals(countOf(reverse, "deferred"), 1)

      assert(htmlOf(forward).contains(dataUriOf(a)) && !htmlOf(forward).contains(dataUriOf(b)))
      assert(htmlOf(reverse).contains(dataUriOf(b)) && !htmlOf(reverse).contains(dataUriOf(a)))
      // Same inputs, same rule ⇒ the outcome is a pure function of (order, sizes).
      assertEquals(countOf(forward, "inlined") + countOf(forward, "deferred"), 2)
      assertEquals(countOf(reverse, "inlined") + countOf(reverse, "deferred"), 2)
    }
  }

  test("Card: the budget is PER CALL — two separate calls each get their own 40,000") {
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14983, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14983, 0x42)
      // Each call embeds its own first image: the first call's spend must not
      // leak into the second (a shared/static budget would starve it).
      assertEquals(countOf(card(s"""<img src="${a.toString}"/>"""), "inlined"), 1)
      assertEquals(countOf(card(s"""<img src="${b.toString}"/>"""), "inlined"), 1)
      assertEquals(countOf(card(s"""<img src="${a.toString}"/>"""), "deferred"), 0)
    }
  }

  // ── ⑤ Pop：HTML 面 + 直开图片腿 ──────────────────────────────────────────

  test("Pop HTML face: the image past the cap is counted `deferred`, never warned") {
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14983, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14983, 0x42)
      val (result, msg, content) =
        popHtml(dir, s"""<html><img src="${a.toString}"/><img src="${b.toString}"/></html>""")
      // Pop's counter naming is the shipped one: `fileRefsJson(o.inlined, …)` puts
      // the INLINED count in the `proxied` slot (PopTool.refPayload), and
      // `deferred` counts what the reference leg serves instead. Read them as
      // they ship — this batch adds no new Pop counter, only a second source of
      // `deferred` (the cumulative budget).
      assertEquals(itemCounter(msg, "proxied"), 1)
      assertEquals(itemCounter(msg, "deferred"), 1)
      assertEquals(itemCounter(msg, "failed"), 0)
      assert(content.contains(dataUriOf(a)), "the first image is embedded")
      assert(
        content.contains(b.toString),
        "the over-budget image keeps its raw src for the Canvas /api/nf-file rewrite"
      )
      assert(!content.contains(dataUriOf(b)))
      assert(result.contains("fileRefs:"), "the counters ride in the result line when anything was deferred")
      assert(!result.contains("warnings:"), "a deferred image is not a defect — no warnings section")
    }
  }

  test("Pop direct-open: an image ≤29,982 bytes rides inline; 29,984 bytes is past the cap (metadata-only)") {
    withTempDir { dir =>
      // 29,982 B ⇒ 39,998 chars (fits); 29,984 B ⇒ 40,002 chars (over by 2).
      assertEquals(FileRefs.dataUriChars(29982, "png"), 39998)
      assertEquals(FileRefs.dataUriChars(29984, "png"), 40002)

      val fits = writeSized(dir.resolve("fits.png"), 29982, 0x41)
      val (_, msgFits) = popPath(fits)
      assert(itemField(msgFits, "objectUrl").isDefined, "inside the cap the bytes ride in the payload")

      val over = writeSized(dir.resolve("over.png"), 29984, 0x42)
      val (_, msgOver) = popPath(over)
      assertEquals(
        itemField(msgOver, "objectUrl"),
        None,
        "past the cap: metadata only — the ticket leg fetches it (unchanged from the >5MB path)"
      )
      assertEquals(msgOver.hcursor.downField("item").get[Long]("size").toOption, Some(29984L))
    }
  }
end FileRefsInlineBudgetSpec
