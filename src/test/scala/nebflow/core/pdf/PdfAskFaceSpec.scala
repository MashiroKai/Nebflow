package nebflow.core.pdf

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.ask.{AskContextAssembler, AskGatewayServices, AskSessionRegistry, AskServices, AskSessions}
import nebflow.core.tools.{PdfPageTool, ToolContext, ToolRegistry}
import nebflow.shared.PathUtil

/**
 * PDF note/ask 批（2026-11-18）· ask 面接线：PDF 可问闸、工具挂载面、PDF 模式
 * 提示词、以及智能体侧取页工具 PdfPage 的行为契约。
 *
 * PdfPage 的判定全部走真文件系统（fixture 见 PdfFeatureSpec 的说明）：绑定表
 * 落真盘、缓存条目落真盘、页文真读回。身份双保险的红腿（非 ask 会话 / 无绑定 /
 * 绑定非 PDF）与绿腿（取页带页码报头）都在这里。
 */
class PdfAskFaceSpec extends CatsEffectSuite:

  private val fixtureScript = os.pwd / "scripts" / "tests" / "pdf_fixture.py"
  private val python = PdfParser.pythonBin()

  private def pythonReady: Boolean =
    os.exists(fixtureScript) &&
      scala.util
        .Try(os.proc(python, "-c", "import pdfplumber, pypdf").call(check = false).exitCode == 0)
        .getOrElse(false)

  private lazy val fixtureDir: os.Path =
    val dir = os.temp.dir(prefix = "pdf-fx-face-")
    os.proc(python, fixtureScript.toString, dir.toString).call(check = false)
    dir

  /** 进程级状态的三件恢复（dataRoot / ask services / 共享缓存）——测完还原。 */
  private def withIsolatedRoot[A](label: String)(body: os.Path => A): A =
    val root = os.temp.dir(prefix = s"pdf-face-$label-")
    val savedRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    try body(root)
    finally
      PathUtil.setDataRoot(savedRoot)
      AskGatewayServices.uninstall()
      PdfCache.forgetShared(root)

  /** 最小假检索源（PdfPage 只用 registry；sources/prompts 仅装配件形参）。 */
  private def installAskServices(registry: AskSessionRegistry): Unit =
    val noop = new nebflow.core.ask.AskContextSources:
      def fileStat(absPath: String): IO[Option[(Long, Long)]] = IO.pure(None)
      def fileText(absPath: String): IO[Option[(String, Long, Long)]] = IO.pure(None)
      def graphNeighbors(absPath: String): IO[List[nebflow.core.ask.AskNeighbor]] = IO.pure(Nil)
      def recallHits(query: String): IO[List[nebflow.core.ask.AskRecallHit]] = IO.pure(Nil)
      def recallDbMtime: IO[Long] = IO.pure(0L)
      def graphVersion: IO[String] = IO.pure("")
    AskGatewayServices.install(AskServices(registry, noop, nebflow.core.ask.AskPromptCache()))

  private def ctxOf(sid: String): ToolContext =
    ToolContext(projectRoot = os.pwd.toString, sessionId = Some(sid))

  // ── 闸与挂载面 ────────────────────────────────────────────────────────────

  test("the ask gate accepts .pdf (case-insensitive) and still refuses other types") {
    assertEquals(AskSessions.isAskable("/docs/report.pdf"), true)
    assertEquals(AskSessions.isPdf("/docs/REPORT.PDF?x=1"), true)
    assertEquals(AskSessions.isAskable("/docs/report.docx"), false)
    assertEquals(AskSessions.isAskable("/docs/report.pdf.exe"), false)
  }

  test("PdfPage rides the closed ask face: registered, ask-exclusive, allowed") {
    assertEquals(ToolRegistry.TOOL_MAP.contains("PdfPage"), true, "PdfPage must be registered")
    assertEquals(AskSessions.AskExclusiveTools.contains("PdfPage"), true, "stripped from non-ask roles")
    assertEquals(AskSessions.AllowedTools.contains("PdfPage"), true, "present on the ask face")
  }

  test("the PDF mode prompt addendum mandates page citations; md prompts are unchanged") {
    val pdfPrompt = AskContextAssembler.promptFor("report.pdf", "/docs/report.pdf", "CTX")
    assert(pdfPrompt.contains("PDF 模式"), "the addendum must be appended for .pdf")
    assert(pdfPrompt.contains("PdfPage"), "the addendum must name the page tool")
    assert(pdfPrompt.contains("引用具体页码"), "citing page numbers is the end-to-end judgement")
    val mdPrompt = AskContextAssembler.promptFor("note.md", "/docs/note.md", "CTX")
    assert(!mdPrompt.contains("PDF 模式"), "non-PDF sessions must be byte-identical to before")
  }

  // ── PdfPage 工具：红腿（身份双保险） ────────────────────────────────────

  test("PdfPage refuses non-ask sessions, unbound sessions and non-PDF bindings") {
    withIsolatedRoot("refuse") { root =>
      val registry = new AskSessionRegistry(root)
      installAskServices(registry)
      val mdBinding = registry.bind((root / "a.md").toString, "a.md").unsafeRunSync()
      val io = for
        nonAsk <- PdfPageTool.call(JsonObject("page" -> 1.asJson), ctxOf("some-session"))
        _ = assert(nonAsk.isLeft)
        _ = assert(nonAsk.left.toOption.exists(_.message.contains("PDF_PAGE_FORBIDDEN")))
        noBinding <- PdfPageTool.call(JsonObject("page" -> 1.asJson), ctxOf("ask-deadbeef"))
        _ = assert(noBinding.isLeft)
        _ = assert(
          noBinding.left.toOption.exists(_.message.contains("no file binding")),
          s"expected no-binding refusal, got: $noBinding"
        )
        wrongType <- PdfPageTool.call(JsonObject("page" -> 1.asJson), ctxOf(mdBinding.id))
        _ = assert(wrongType.isLeft)
        _ = assert(wrongType.left.toOption.exists(_.message.contains("not a PDF")))
        noPage <- PdfPageTool.call(JsonObject.empty, ctxOf(mdBinding.id))
        _ = assert(noPage.isLeft)
        _ = assert(noPage.left.toOption.exists(_.message.contains("page")))
      yield ()
      // 求值必须在 withIsolatedRoot 的 install/uninstall 括号内（finally 会在
      // munit 求值懒 IO 之前 uninstall——eager 求值是本测试的正确性前提）。
      io.unsafeRunSync()
    }
  }

  // ── PdfPage 工具：绿腿（真解析 + 真取页 + 页码报头） ─────────────────────

  test("PdfPage hands the model the page Markdown with a page-number header") {
    assume(pythonReady, "python3 + pdfplumber/pypdf are required for the PDF fixtures")
    withIsolatedRoot("happy") { root =>
      val registry = new AskSessionRegistry(root)
      installAskServices(registry)
      val pdf = fixtureDir / "simple.pdf"
      val binding = registry.bind(pdf.toString, "simple.pdf").unsafeRunSync()
      val io = for
        p2 <- PdfPageTool.call(JsonObject("page" -> 2.asJson), ctxOf(binding.id))
        _ = assert(p2.isRight, s"page 2 read failed: $p2")
        head = p2.toOption.get
        _ = assert(head.contains("p.2"), s"the header must cite the page number: ${head.take(80)}")
        _ = assert(head.contains("第 2 页"), s"the header must cite the page in Chinese too: ${head.take(80)}")
        _ = assert(head.contains("simple.pdf"), "the header must name the document")
        // The fixture's page 2 carries the table cell r2c2 — real page body, not a stub.
        _ = assert(head.toLowerCase.contains("r2c2"), "the page body must be the parsed Markdown")
        // Out of range is a readable refusal naming the real bound.
        p9 <- PdfPageTool.call(JsonObject("page" -> 9.asJson), ctxOf(binding.id))
        _ = assert(p9.isLeft)
        _ = assert(p9.left.toOption.exists(_.message.contains("1..3")), s"the bound must be readable: ${p9}")
      yield ()
      io.unsafeRunSync()
    }
  }

  test("PdfPage reports an encrypted PDF as a readable failure, never as an empty page") {
    assume(pythonReady, "python3 + pdfplumber/pypdf are required for the PDF fixtures")
    withIsolatedRoot("encrypted") { root =>
      val registry = new AskSessionRegistry(root)
      installAskServices(registry)
      val pdf = fixtureDir / "encrypted.pdf"
      val binding = registry.bind(pdf.toString, "encrypted.pdf").unsafeRunSync()
      val io = for
        r <- PdfPageTool.call(JsonObject("page" -> 1.asJson), ctxOf(binding.id))
        _ = assert(r.isLeft)
        _ = assert(r.left.toOption.exists(_.message.contains("PDF_ENCRYPTED")), s"failure must be readable: $r")
        _ = assert(r.left.toOption.exists(_.message.toLowerCase.contains("unreadable")))
      yield ()
      io.unsafeRunSync()
    }
  }
end PdfAskFaceSpec
