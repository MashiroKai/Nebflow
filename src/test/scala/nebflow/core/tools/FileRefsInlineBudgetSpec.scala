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

  // ── ④ Pop：一次调用内的顺序账（pop-upgrade 批载荷面） ────────────────────

  /** Pop harness (the identity gate needs a Nebula root ctx) → parsed payload. */
  private val nebulaDef = nebflow.actor.AgentDef(name = "Nebula", description = "", tools = Nil)

  private def popPayload(paths: List[Path]): Json =
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val ctx = ToolContext(
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
    val input = JsonObject("filePath" -> Json.arr(paths.map(p => p.toString.asJson)*))
    PopTool.call(input, ctx).unsafeRunSync() match
      case Left(err) => fail(s"Pop failed: ${err.message}")
      case Right(raw) =>
        assert(raw.startsWith(PopTool.Sentinel), s"payload sentinel expected: ${raw.take(60)}")
        io.circe.parser.parse(raw.substring(PopTool.Sentinel.length)) match
          case Right(json) => json
          case Left(err)   => fail(s"payload must be pure JSON: $err")

  private def itemCounts(p: Json): (Boolean, Boolean) =
    val items = p.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
    val srcs = items.map(_.hcursor.get[String]("src").toOption)
    (srcs(0).isDefined, srcs(1).isDefined)

  private def countOf(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  test("Pop batch: the image past the cap is `referenced`, never warned — the FIRST path takes the budget"):
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14983, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14983, 0x42)
      val each = FileRefs.dataUriChars(14983, "png")
      assertEquals(each, 20002, "14,983 bytes ⇒ 20,002 chars of data: URI")
      assert(each * 2 > FileRefs.MaxInlinePayloadChars, "the fixture pair must overflow the cap by 4 chars")

      val p = popPayload(List(a, b))
      assertEquals(countOf(p, "inlined"), 1)
      assertEquals(countOf(p, "referenced"), 1, "the over-budget item rides the ticket leg, counted only")
      assertEquals(countOf(p, "failed"), 0)
      assertEquals(p.hcursor.downField("warnings").as[List[Json]].toOption.getOrElse(Nil), Nil,
        "an over-budget image is not a defect: the ticket leg still renders it")
      assertEquals(itemCounts(p), (true, false), "input order decides who is embedded (first come, first served)")
    }

  test("Pop batch: the budget is PER CALL — two separate calls each get their own 40,000"):
    withTempDir { dir =>
      val a = writeSized(dir.resolve("a.png"), 14983, 0x41)
      val b = writeSized(dir.resolve("b.png"), 14983, 0x42)
      assertEquals(countOf(popPayload(List(a)), "inlined"), 1)
      assertEquals(countOf(popPayload(List(b)), "inlined"), 1)
      assertEquals(countOf(popPayload(List(a)), "referenced"), 0)
    }

  test("Pop direct-open: an image ≤29,982 bytes rides inline; 29,984 bytes is past the cap (ticket leg)"):
    withTempDir { dir =>
      // 29,982 B ⇒ 39,998 chars (fits); 29,984 B ⇒ 40,002 chars (over by 2).
      assertEquals(FileRefs.dataUriChars(29982, "png"), 39998)
      assertEquals(FileRefs.dataUriChars(29984, "png"), 40002)

      val fits = writeSized(dir.resolve("fits.png"), 29982, 0x41)
      val pFits = popPayload(List(fits))
      val itemsFits = pFits.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
      assert(itemsFits.head.hcursor.get[String]("src").toOption.isDefined, "inside the cap the bytes ride in the payload")

      val over = writeSized(dir.resolve("over.png"), 29984, 0x42)
      val pOver = popPayload(List(over))
      val itemsOver = pOver.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
      assertEquals(itemsOver.head.hcursor.get[String]("src").toOption, None,
        "past the cap: metadata only — the ticket leg fetches it")
      assertEquals(itemsOver.head.hcursor.get[Long]("size").toOption, Some(29984L))
      assertEquals(countOf(pOver, "referenced"), 1)
    }
end FileRefsInlineBudgetSpec
