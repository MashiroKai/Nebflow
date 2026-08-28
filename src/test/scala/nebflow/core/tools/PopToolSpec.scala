package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * PopTool HTML local-image inlining (#15, 方案 A — commit 5d95194b).
 *
 * Agent HTML reports reference local screenshots via file:///abs, /abs,
 * relative, or ~/ paths. The Canvas iframe cannot load those (file:// blocked
 * by browser policy, bare paths unresolved), so PopTool pre-processes HTML
 * content: local <img src> values that exist, are embeddable image formats,
 * and are ≤5MB become base64 data URIs. Everything else is left untouched.
 *
 * These specs pin the behavior end-to-end through call() + a captured wsSend.
 */
class PopToolSpec extends FunSuite:

  private def tempDir(name: String): os.Path =
    val d = os.pwd / "target" / s"test-pop-$name-${java.util.UUID.randomUUID().toString.take(6)}"
    os.makeDir.all(d)
    d

  private def writePng(p: os.Path, bytes: Int = 8): Unit =
    // minimal valid-enough PNG header (content is irrelevant — only the bytes
    // must round-trip through base64)
    val data = Array.fill(bytes)(0x42.toByte)
    os.write.over(p, data)

  /** Capture wsSend messages into a Ref-like buffer. */
  private def captureCtx(buf: scala.collection.mutable.ListBuffer[Json]): ToolContext =
    ToolContext(
      sessionId = Some("pop-test"),
      sessionStore = None,
      agentDef = None,
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
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

  test("missing file and non-image extension are silently skipped"):
    val dir = tempDir("skip")
    val html = dir / "report.html"
    os.write.over(
      html,
      s"""<html><img src="${dir.toString}/ghost.png"><img src="${dir.toString}/notes.txt"></html>"""
    )
    os.write.over(dir / "notes.txt", "not an image")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    PopTool.call(JsonObject("filePath" -> html.toString.asJson), captureCtx(buf)).unsafeRunSync()
    val c = contentOf(buf)
    assert(c.contains("ghost.png"), s"missing file keeps original src:\n$c")
    assert(c.contains("notes.txt"), s"non-image keeps original src:\n$c")
    assert(!c.contains("base64"), s"no embedding expected:\n$c")

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

  override def afterEach(context: munit.AfterEach): Unit =
    // test artifacts under target/ are cleaned by sbt; nothing else to do
    ()

end PopToolSpec
