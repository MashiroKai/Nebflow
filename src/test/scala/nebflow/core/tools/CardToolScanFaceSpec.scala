package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * toolfail batch (2026-09-11) — the Card scan face and the app-route exemption.
 *
 * The carderr batch pinned `src=`/`href=`; everything else a card can use to
 * reference a local file (`srcset`, CSS `url(...)`, `@import`) stayed outside
 * the face, so those references failed with no warning at all. This suite pins
 * the widened face (same `FileRefs.probeFile`, same warning channel, same
 * `fileRefs` counters) and the exemption that stops the app's own routes from
 * being reported as broken files.
 */
class CardToolScanFaceSpec extends FunSuite:

  private val ctx = ToolContext(projectRoot = os.pwd.toString)
  private val sentinel = "___CARD_HTML___"

  private def card(html: String, title: String = "T"): Json =
    val input = JsonObject("html" -> Json.fromString(html), "title" -> Json.fromString(title))
    val result = CardTool.call(input, ctx).unsafeRunSync().getOrElse(fail("expected Right"))
    assert(result.startsWith(sentinel), s"result must start with the sentinel, got: ${result.take(60)}")
    io.circe.parser.parse(result.substring(sentinel.length)) match
      case Right(json) => json
      case Left(err)   => fail(s"everything after the sentinel must be pure JSON: $err")

  private def htmlOf(p: Json): String = p.hcursor.get[String]("html").toOption.getOrElse("")
  private def warningsOf(p: Json): List[Json] = p.hcursor.get[List[Json]]("warnings").toOption.getOrElse(Nil)
  private def refsOf(p: Json): List[String] =
    warningsOf(p).flatMap(_.hcursor.get[String]("ref").toOption)
  private def reasonsOf(p: Json): List[String] =
    warningsOf(p).flatMap(_.hcursor.get[String]("reason").toOption)
  private def count(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  private def encode(p: String): String = java.net.URLEncoder.encode(p, "UTF-8")

  private def withTempDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("cardscan-")
    try f(dir)
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)

  private def write(p: Path, bytes: String = "x"): Path =
    Files.write(p, bytes.getBytes(StandardCharsets.UTF_8))

  // ── ① srcset ─────────────────────────────────────────────

  test("srcset: each candidate URL is proxied on its own and its descriptor survives"):
    withTempDir { dir =>
      val ok = write(dir.resolve("ok.png"), "one")
      val html =
        s"""<img srcset="${ok.toString} 1x, /tmp/cardscan-missing-2x.png 2x" sizes="100vw"/>"""
      val p = card(html)
      assertEquals(count(p, "proxied"), 1, "the resolvable candidate is proxied")
      assertEquals(count(p, "failed"), 1, "the failing candidate is reported")
      assertEquals(reasonsOf(p), List("not-found"))
      assertEquals(refsOf(p), List("/tmp/cardscan-missing-2x.png"), "the warning carries the candidate URL, not the whole attribute")
      assert(htmlOf(p).contains(s"/api/nf-file?path=${encode(ok.toString)}"), s"rewritten candidate: ${htmlOf(p)}")
      assert(htmlOf(p).contains("1x, /tmp/cardscan-missing-2x.png 2x"), s"descriptors must survive: ${htmlOf(p)}")
    }

  test("srcset: a single candidate without descriptor is proxied"):
    withTempDir { dir =>
      val ok = write(dir.resolve("only.png"), "one")
      val p = card(s"""<img srcset="${ok.toString}"/>""")
      assertEquals(count(p, "proxied"), 1)
      assertEquals(warningsOf(p), Nil)
      assertEquals(htmlOf(p), s"""<img srcset="/api/nf-file?path=${encode(ok.toString)}"/>""")
    }

  test("srcset: a value containing a data: URI is skipped whole (registered boundary)"):
    val html = """<img srcset="data:image/png;base64,AAA 1x, /tmp/cardscan-missing.png 2x"/>"""
    val p = card(html)
    assertEquals(htmlOf(p), html, "an ambiguous srcset is never rewritten")
    assertEquals(warningsOf(p), Nil, "and never warned about")

  test("srcset: the new regex does not over-match `data-srcset` (boundary the legacy src= regex lacks)"):
    val html = """<img data-srcset="/tmp/cardscan-missing.png 1x"/>"""
    val p = card(html)
    assertEquals(warningsOf(p), Nil)
    assertEquals(htmlOf(p), html)

  // ── ② CSS url() and @import ──────────────────────────────

  test("CSS url() in a <style> block is proxied when the file exists"):
    withTempDir { dir =>
      val ok = write(dir.resolve("bg.png"), "one")
      val p = card(s"""<style>.a{background:url(${ok.toString})}</style>""")
      assertEquals(count(p, "proxied"), 1)
      assertEquals(htmlOf(p), s"""<style>.a{background:url(/api/nf-file?path=${encode(ok.toString)})}</style>""")
    }

  test("CSS url() warns on a missing file (quotes preserved)"):
    val p = card("""<style>.a{background:url('/tmp/cardscan-missing-bg.png')}</style>""")
    assertEquals(reasonsOf(p), List("not-found"))
    assertEquals(refsOf(p), List("/tmp/cardscan-missing-bg.png"))
    assertEquals(
      htmlOf(p),
      """<style>.a{background:url('/tmp/cardscan-missing-bg.png')}</style>""",
      "the raw value stays so the card still renders"
    )

  test("CSS url() inside an inline style attribute is scanned too"):
    withTempDir { dir =>
      val ok = write(dir.resolve("tile.png"), "one")
      val p = card(s"""<div style='background-image:url("${ok.toString}")'>x</div>""")
      assertEquals(count(p, "proxied"), 1)
      assertEquals(htmlOf(p), s"""<div style='background-image:url("/api/nf-file?path=${encode(ok.toString)}")'>x</div>""")
    }

  test("bare @import warns on a missing stylesheet; the url() form is proxied"):
    val missing = card("""<style>@import "/tmp/cardscan-missing.css";</style>""")
    assertEquals(reasonsOf(missing), List("not-found"))
    assertEquals(refsOf(missing), List("/tmp/cardscan-missing.css"))

    withTempDir { dir =>
      val css = write(dir.resolve("app.css"), "body{}")
      val ok = card(s"""<style>@import url("${css.toString}");</style>""")
      assertEquals(warningsOf(ok), Nil)
      assertEquals(htmlOf(ok), s"""<style>@import url("/api/nf-file?path=${encode(css.toString)}");</style>""")
    }

  test("zero regression: remote / data / anchor url() values are untouched"):
    val html =
      """<style>.a{background:url(https://example.com/a.png)}.b{background:url(data:image/png;base64,AA)}.c{fill:url(#grad)}</style>"""
    val p = card(html)
    assertEquals(htmlOf(p), html)
    assertEquals(warningsOf(p), Nil)

  // ── ③ app-route exemption ────────────────────────────────

  test("exemption: a failing app-route reference is counted, not reported"):
    val p = card("""<img src="/js/cardscan-missing.png"/><script src="/js/app.js"></script>""")
    assertEquals(warningsOf(p), Nil, "app routes must not be reported as missing files")
    assertEquals(count(p, "exempt"), 2)
    assertEquals(count(p, "failed"), 0)
    assertEquals(count(p, "proxied"), 0)
    assertEquals(htmlOf(p), """<img src="/js/cardscan-missing.png"/><script src="/js/app.js"></script>""")

  test("exemption: root-level app files and every served prefix are recognised"):
    val refs = List("/logo.svg", "/style.css", "/favicon.ico", "/assets/index-abc.js", "/css/chat.css",
      "/vendor/monaco/x.js", "/uploads/s1/a.png", "/agents/x/a.png", "/voice-models/m.bin")
    val html = refs.map(r => s"""<img src="$r"/>""").mkString
    val p = card(html)
    assertEquals(warningsOf(p), Nil, s"all of these are app routes: $refs")
    assertEquals(count(p, "exempt"), refs.size)

  test("exemption: a non-app absolute path is still reported (the exemption is not a blanket silence)"):
    val p = card("""<img src="/__cardscan_no_such_dir__/plot.png"/>""")
    assertEquals(reasonsOf(p), List("not-found"))
    assertEquals(count(p, "exempt"), 0)
    assertEquals(count(p, "failed"), 1)

  test("exemption: a relative app-asset reference stops warning; a plain relative one still does"):
    val app = card("""<script src="js/app.js"></script>""")
    assertEquals(warningsOf(app), Nil, "`js/app.js` reads as the /js/ route")
    assertEquals(count(app, "exempt"), 1)

    val plain = card("""<img src="images/logo.png"/>""")
    assertEquals(reasonsOf(plain), List("unresolvable"), "`images/logo.png` is a real relative-path mistake")
    assertEquals(count(plain, "exempt"), 0)

  test("exemption: the criterion never downgrades a working reference (only Rejects)"):
    // The exemption fires only on a verdict that already failed, so a file that
    // actually exists under an app-route prefix is still probed and proxied.
    val proxy = FileRefs.RefDecision.Proxy("/api/nf-file?path=%2Fjs%2Fx.png")
    assertEquals(
      FileRefs.applyAppRouteExemption("/js/x.png", proxy),
      proxy,
      "a Proxy verdict must pass through untouched"
    )
    assert(
      FileRefs.applyAppRouteExemption("/js/x.png", FileRefs.unresolvable("/js/x.png", "nope")).isInstanceOf[FileRefs.RefDecision.Exempt],
      "only a Reject is downgraded to Exempt"
    )

  test("criterion: appRoute recognises exactly the gateway's own surfaces"):
    assertEquals(FileRefs.appRoute("/js/app.js"), Some("/js/"))
    assertEquals(FileRefs.appRoute("js/app.js"), Some("/js/"), "relative is read as web-root relative")
    assertEquals(FileRefs.appRoute("/logo.svg"), Some("/logo.svg"))
    assertEquals(FileRefs.appRoute("/tmp/plot.png"), None)
    assertEquals(FileRefs.appRoute("//cdn.example.com/js/app.js"), None, "protocol-relative is not an app route")
    assertEquals(FileRefs.appRoute("https://example.com/js/app.js"), None)
    assertEquals(FileRefs.appRoute("/jsx/not-a-route.png"), None, "prefix matching is segment-exact")

  // ── ④ splice safety ──────────────────────────────────────

  test("overlap guard: a later span covered by an earlier one is dropped, never merged"):
    assertEquals(
      CardTool.nonOverlapping(List((0, 10, "a"), (5, 8, "b"), (10, 12, "c"))),
      List((0, 10, "a"), (10, 12, "c"))
    )
    assertEquals(CardTool.nonOverlapping(List((0, 4, "x"))), List((0, 4, "x")))
    assertEquals(CardTool.nonOverlapping(List[(Int, Int, String)]()), Nil)

  test("document-order merge: all five faces in one document stay valid"):
    withTempDir { dir =>
      val img = write(dir.resolve("a.png"), "one")
      val css = write(dir.resolve("b.css"), "body{}")
      val html =
        s"""<link rel="stylesheet" href="${css.toString}"/>""" +
          s"""<img src="${img.toString}" srcset="${img.toString} 2x"/>""" +
          s"""<style>@import "${css.toString}";.z{background:url(${img.toString})}</style>"""
      val p = card(html)
      assertEquals(warningsOf(p), Nil)
      assertEquals(count(p, "proxied"), 5, "href + src + srcset candidate + bare @import + url()")
      assert(htmlOf(p).contains(" 2x"), "the srcset descriptor survives a multi-face rewrite")
    }

  test("payload head contract preserved: fileRefs first, exempt is an added key only"):
    val p = card("""<div>hi</div>""")
    val raw = p.noSpaces
    assert(raw.indexOf("\"fileRefs\"") < raw.indexOf("\"html\""))
    assert(raw.indexOf("\"warnings\"") < raw.indexOf("\"html\""))
    assertEquals(count(p, "proxied"), 0)
    assertEquals(count(p, "failed"), 0)
    assertEquals(count(p, "omitted"), 0)
    assertEquals(count(p, "exempt"), 0)
