package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite

/**
 * toolfail batch (2026-09-11) — Pop's `<img>` pass must fail VISIBLY.
 *
 * The pass used to answer `None` to every question and keep the raw `src`
 * (`replaced.getOrElse(m.group(0))`) with zero feedback: the Canvas iframe then
 * fetched a path that does not exist and the user saw an empty box, while the
 * tool result said `Opened X in Canvas.` (the Card leg had exactly the same
 * defect — carderr batch, merge 52e2f58f).
 *
 * Pinned here:
 *  1. both legs of the visibility channel — the tool result (`warnings:` +
 *     `fileRefs:` lines) and the `popFile` WS item (`item.warnings` /
 *     `item.fileRefs`);
 *  2. the verdict mapping: Reject → warning, Proxy+inlinable → inlined,
 *     Proxy+not-inlinable → `deferred` counter only (no noise), app route →
 *     `exempt` counter only;
 *  3. the resolution policy that is Pop's own (relative refs resolve against
 *     the HTML file's directory, `~` expands) — unchanged;
 *  4. zero regression: a clean HTML Pop is byte-identical to before.
 */
class PopToolFileRefSpec extends FunSuite:

  private val nebulaDef = nebflow.agent.AgentDef(name = "Nebula", description = "", tools = Nil)

  private def tempDir(name: String): os.Path =
    val d = os.pwd / "target" / s"test-poprefs-$name-${java.util.UUID.randomUUID().toString.take(6)}"
    os.makeDir.all(d)
    d

  private def captureCtx(buf: scala.collection.mutable.ListBuffer[Json]): ToolContext =
    ToolContext(
      sessionId = Some("pop-refs-test"),
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

  /** Fresh dir + `report.html` holding `body`; `prepare` may add the files the
    *  HTML references (relative refs resolve against the HTML's own dir, so the
    *  two must share one directory). Returns (result, ws message, content). */
  private def pop(name: String, body: String, prepare: os.Path => Unit = _ => ()): (String, Json, String) =
    val dir = tempDir(name)
    prepare(dir)
    val file = dir / "report.html"
    os.write.over(file, body)
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> file.toString.asJson), captureCtx(buf)).unsafeRunSync() match
      case Left(err)   => fail(s"Pop failed: ${err.message}")
      case Right(text) =>
        assertEquals(buf.size, 1, s"exactly one popFile message expected: $buf")
        val item = buf.head.hcursor.downField("item")
        val content = item.get[String]("content").toOption.getOrElse("")
        (text, buf.head, content)

  private def itemCounters(msg: Json): Json = msg.hcursor.downField("item").downField("fileRefs").focus.getOrElse(Json.Null)
  private def itemWarnings(msg: Json): List[Json] =
    msg.hcursor.downField("item").get[List[Json]]("warnings").toOption.getOrElse(Nil)
  private def counter(p: Json, field: String): Int = p.hcursor.get[Int](field).toOption.getOrElse(-1)

  private def resultCounters(result: String): Json =
    FileRefs.countsIn(result).getOrElse(fail(s"no `fileRefs:` line in result:\n$result"))
  private def resultWarnings(result: String): List[Json] =
    val i = result.indexOf("warnings: ")
    if i < 0 then Nil
    else
      val line = result.substring(i + "warnings: ".length).takeWhile(_ != '\n')
      io.circe.parser.parse(line).toOption.flatMap(_.asArray).getOrElse(Nil).toList

  private def resolvedOf(w: Json): Option[String] = w.hcursor.get[Option[String]]("resolvedPath").toOption.flatten

  // ── ① 正控：仍然内联（零回归） ───────────────────────────

  test("positive control: an existing local image is still inlined, and the result stays clean"):
    val (text, msg, content) = pop(
      "inline",
      """<html><body><img src="shot.png"/></body></html>""",
      dir => os.write.over(dir / "shot.png", Array.fill(8)(0x42.toByte))
    )
    assert(content.contains("data:image/png;base64,"), s"data URI expected:\n$content")
    assertEquals(text, "Opened report.html in Canvas.", "a clean Pop has no extra lines")
    assertEquals(counter(itemCounters(msg), "proxied"), 1)
    assertEquals(counter(itemCounters(msg), "failed"), 0)
    assertEquals(itemWarnings(msg), Nil)

  // ── ② 负控：静默丢弃 → 结构化告警 ────────────────────────

  test("missing absolute image warns in BOTH legs (tool result + popFile item) and keeps the raw src"):
    val dir = tempDir("missing")
    val ghost = s"$dir/ghost.png"
    val (text, msg, content) = pop("missing", s"""<html><img src="$ghost"/></html>""")

    // raw value kept (the HTML still renders, minus the image)
    assert(content.contains(ghost), s"raw src must stay:\n$content")

    // tool result leg
    val counters = resultCounters(text)
    assertEquals(counter(counters, "failed"), 1)
    assertEquals(counter(counters, "proxied"), 0)
    val w = resultWarnings(text).headOption.getOrElse(fail(s"warnings line missing:\n$text"))
    assertEquals(w.hcursor.get[String]("ref").toOption, Some(ghost), "原始串")
    assertEquals(w.hcursor.get[String]("reason").toOption, Some("not-found"), "失败原因")
    assertEquals(resolvedOf(w), Some(ghost), "解析后路径")
    assert(text.startsWith("Opened report.html in Canvas."), s"summary line first:\n$text")

    // WS item leg
    assertEquals(counter(itemCounters(msg), "failed"), 1)
    assertEquals(itemWarnings(msg).size, 1)
    assertEquals(itemWarnings(msg).head.hcursor.get[String]("reason").toOption, Some("not-found"))

  test("`~`-rooted reference reports the path after expansion (原始串 → 解析后路径 differ)"):
    val (text, _, content) = pop("tilde", """<html><img src="~/__poprefs_missing__/plot.png"/></html>""")
    val w = resultWarnings(text).headOption.getOrElse(fail(text))
    assertEquals(w.hcursor.get[String]("ref").toOption, Some("~/__poprefs_missing__/plot.png"))
    assertEquals(
      resolvedOf(w),
      Some(s"${sys.props("user.home")}/__poprefs_missing__/plot.png"),
      "resolvedPath is the post-expansion path"
    )
    assert(content.contains("~/__poprefs_missing__/plot.png"), "the raw value is kept")

  test("aggregate counters: three failures collapse into counted, distinct warnings"):
    val (text, _, _) = pop(
      "agg",
      """<html><img src="/tmp/poprefs-a.png"/><img src="/tmp/poprefs-a.png"/><img src="/tmp/poprefs-b.png"/></html>"""
    )
    val counters = resultCounters(text)
    assertEquals(counter(counters, "failed"), 2, "distinct references")
    assertEquals(resultWarnings(text).size, 2)
    assertEquals(
      resultWarnings(text).map(w => w.hcursor.get[Int]("count").toOption.getOrElse(0)).sum,
      3,
      "the repeated reference carries count=2"
    )

  test("a non-regular file and a non-servable extension are reported with their own reasons"):
    val dir = tempDir("reasons")
    os.makeDir.all(dir / "bundle.png")
    os.write.over(dir / "notes.txt", "not an image")
    val (text, _, _) = pop(
      "reasons",
      s"""<html><img src="$dir/bundle.png"/><img src="$dir/notes.txt"/></html>"""
    )
    assertEquals(
      resultWarnings(text).map(w => w.hcursor.get[String]("reason").toOption.getOrElse("")).sorted,
      List("extension-not-allowed", "not-regular-file")
    )

  // ── ③ 不刷屏：deferred / exempt 只计数 ───────────────────

  test("an oversized image is `deferred`, not warned — /api/nf-file still serves it"):
    val (text, msg, content) = pop(
      "oversize",
      """<html><img src="big.png"/></html>""",
      dir => os.write.over(dir / "big.png", Array.fill(5 * 1024 * 1024 + 1)(0x44.toByte))
    )
    assert(content.contains("""src="big.png""""), s"raw src kept:\n$content")
    assert(!content.contains("base64"), "must not inline")
    val counters = resultCounters(text)
    assertEquals(counter(counters, "deferred"), 1)
    assertEquals(counter(counters, "failed"), 0)
    assert(!text.contains("warnings:"), s"a servable file is not a defect:\n$text")
    assertEquals(counter(itemCounters(msg), "deferred"), 1)

  test("a non-embeddable but proxy-served extension is `deferred`, not warned"):
    val (text, _, _) = pop(
      "tiff",
      """<html><img src="scan.tiff"/></html>""",
      dir => os.write.over(dir / "scan.tiff", Array.fill(8)(0x45.toByte))
    )
    assertEquals(counter(resultCounters(text), "deferred"), 1)
    assertEquals(counter(resultCounters(text), "failed"), 0)

  test("an app-route reference is `exempt`: not inlined, not warned, counted"):
    val (text, msg, content) = pop("approute", """<html><img src="/js/chart-icon.png"/></html>""")
    assert(content.contains("/js/chart-icon.png"), "the raw value is untouched")
    assertEquals(counter(resultCounters(text), "exempt"), 1)
    assertEquals(counter(resultCounters(text), "failed"), 0)
    assert(!text.contains("warnings:"), s"an app route is not a broken file:\n$text")
    assertEquals(counter(itemCounters(msg), "exempt"), 1)

  // ── ④ 零回归 ────────────────────────────────────────────

  test("zero regression: remote URLs stay untouched and produce no counters at all"):
    val (text, msg, content) = pop(
      "remote",
      """<html><img src="https://example.com/a.png"><img src="data:image/png;base64,AAA"></html>"""
    )
    assert(content.contains("https://example.com/a.png"))
    assert(content.contains("data:image/png;base64,AAA"))
    assertEquals(text, "Opened report.html in Canvas.", "nothing to report → no extra lines")
    assertEquals(itemWarnings(msg), Nil)

  test("zero regression: a non-HTML Pop keeps its item shape (no refs keys)"):
    val dir = tempDir("md")
    val md = dir / "notes.md"
    os.write.over(md, "![pic](pic.png)\n")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> md.toString.asJson), captureCtx(buf)).unsafeRunSync()
    val item = buf.head.hcursor.downField("item")
    assertEquals(item.get[String]("itemType").toOption, Some("markdown"))
    assert(item.downField("warnings").focus.isEmpty, "no warnings key for a markdown item")
    assert(item.downField("fileRefs").focus.isEmpty, "no fileRefs key for a markdown item")

  test("summarizeResult surfaces the count (chat header no longer says plain `Opened`)"):
    val dir = tempDir("summarize")
    val ghost = s"$dir/ghost.png"
    val (text, _, _) = pop("summarize", s"""<html><img src="$ghost"/></html>""")
    val input = JsonObject("filePath" -> Json.fromString(s"$dir/report.html"))
    assertEquals(
      PopTool.summarizeResult(input, text),
      "Opened report.html in Canvas — 1 image reference(s) NOT inlined"
    )
    assertEquals(PopTool.summarizeResult(input, "Opened report.html in Canvas."), "Opened report.html in Canvas")

  test("description promises the warning channel and the deferred/exempt counters"):
    val d = PopTool.description
    assert(d.contains("warnings"), "the description must name the warnings field")
    assert(d.contains("fileRefs"), "the description must name the counters")
    assert(d.contains("deferred"), "the delayed path must be documented")
    assert(d.contains("exempt"), "the exemption must be documented")
