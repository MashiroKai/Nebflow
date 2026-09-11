package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * carderr batch (2026-09-11) — a Card's local file reference must fail VISIBLY.
 *
 * Author report (2026-09-11 11:57): a card with
 * `<img src="~/projects/gamma-telescope/reports/…svg">` rendered as a blank box
 * and the tool result said nothing. Empirically: `~` expansion works, but the
 * expanded path did not exist (the workspace lives under
 * `~/.nebflow/projects/gamma-telescope/`), and the old `tryEmbed` returned
 * `None` silently — raw value kept, zero feedback (evidence:
 * `.nebflow/evidence/20260911_carderr-impl/`).
 *
 * What is pinned here:
 *  1. positive control — `~`/absolute refs to existing files are proxied;
 *  2. every rejection branch produces a structured warning that is visible in
 *     the tool result (ref → resolvedPath → reason) and counted in `fileRefs`;
 *  3. zero regression — clean cards produce no warnings and no HTML rewrite;
 *  4. the `___CARD_HTML___` sentinel contract — nothing but JSON after it.
 */
class CardToolFileRefSpec extends FunSuite:

  private val ctx = ToolContext(projectRoot = os.pwd.toString)
  private val sentinel = "___CARD_HTML___"

  /** Call the tool and decode the payload; also pins the sentinel contract. */
  private def card(html: String, title: String = "T"): Json =
    val input = JsonObject("html" -> Json.fromString(html), "title" -> Json.fromString(title))
    val result = CardTool.call(input, ctx).unsafeRunSync().getOrElse(fail("expected Right"))
    assert(result.startsWith(sentinel), s"result must start with the sentinel, got: ${result.take(60)}")
    val jsonText = result.substring(sentinel.length)
    io.circe.parser.parse(jsonText) match
      case Right(json) => json
      case Left(err)   => fail(s"everything after the sentinel must be pure JSON (frontend JSON.parse): $err")

  private def htmlOf(p: Json): String = p.hcursor.get[String]("html").toOption.getOrElse("")
  private def warningsOf(p: Json): List[Json] = p.hcursor.get[List[Json]]("warnings").toOption.getOrElse(Nil)
  private def reasonOf(p: Json): String = warningsOf(p).headOption.flatMap(_.hcursor.get[String]("reason").toOption).getOrElse("")
  private def detailOf(p: Json): String = warningsOf(p).headOption.flatMap(_.hcursor.get[String]("detail").toOption).getOrElse("")
  private def resolvedOf(p: Json): Option[String] =
    warningsOf(p).headOption.flatMap(_.hcursor.get[Option[String]]("resolvedPath").toOption.flatten)
  private def countOf(p: Json): Int = warningsOf(p).headOption.flatMap(_.hcursor.get[Int]("count").toOption).getOrElse(-1)
  private def refs(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  private def deleteRecursively(p: Path): Unit =
    if Files.exists(p) then
      Files
        .walk(p)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)

  /** Temp file with an allowed extension (`.png`) in the default temp dir. */
  private def withTempFile[A](f: Path => A): A =
    val file = Files.createTempFile("cardref-", ".png")
    try f(file)
    finally Files.deleteIfExists(file)

  /** Temp file inside the user's home directory — the only way to exercise `~`. */
  private def withHomeFile[A](f: (Path, String) => A): A =
    val home = Paths.get(sys.props("user.home"))
    val file = Files.createTempFile(home, "cardref-home-", ".png")
    try f(file, s"~/${file.getFileName.toString}")
    finally Files.deleteIfExists(file)

  private def encode(p: String): String = java.net.URLEncoder.encode(p, "UTF-8")

  // ── ① 正控 ──────────────────────────────────────────────

  test("positive control: `~`-rooted existing image is proxied (author's ref shape)"):
    withHomeFile { (file, ref) =>
      val p = card(s"""<img src="$ref" alt="plot"/>""")
      assertEquals(warningsOf(p), Nil, "a healthy reference must NOT warn")
      assertEquals(refs(p, "proxied"), 1)
      assertEquals(refs(p, "failed"), 0)
      assertEquals(htmlOf(p), s"""<img src="/api/nf-file?path=${encode(file.toString)}" alt="plot"/>""")
    }

  test("positive control: absolute `/`-rooted existing file is proxied"):
    withTempFile { file =>
      val p = card(s"""<img src="${file.toString}"/>""")
      assertEquals(warningsOf(p), Nil)
      assertEquals(refs(p, "proxied"), 1)
      assertEquals(htmlOf(p), s"""<img src="/api/nf-file?path=${encode(file.toString)}"/>""")
    }

  test("positive control: `href` stylesheet reference is proxied too"):
    val dir = Files.createTempDirectory("cardref-css")
    val css = dir.resolve("style.css")
    Files.write(css, "body{}".getBytes(StandardCharsets.UTF_8))
    try
      val p = card(s"""<link rel="stylesheet" href="${css.toString}"/>""")
      assertEquals(warningsOf(p), Nil)
      assertEquals(htmlOf(p), s"""<link rel="stylesheet" href="/api/nf-file?path=${encode(css.toString)}"/>""")
    finally deleteRecursively(dir)

  // ── ② 负控（核心：告警可见） ─────────────────────────────

  test("negative control: missing `~/…` image warns not-found and keeps the raw value"):
    val p = card("""<img src="~/__carderr_missing_dir__/plot.png"/>""")
    assertEquals(refs(p, "proxied"), 0)
    assertEquals(refs(p, "failed"), 1)
    assertEquals(reasonOf(p), "not-found")
    assertEquals(
      resolvedOf(p),
      Some(s"${sys.props("user.home")}/__carderr_missing_dir__/plot.png"),
      "resolvedPath must show the path after `~` expansion"
    )
    assert(detailOf(p).contains("nearest existing parent directory"), s"diagnostic hint missing: ${detailOf(p)}")
    assertEquals(
      htmlOf(p),
      """<img src="~/__carderr_missing_dir__/plot.png"/>""",
      "a rejected reference keeps its raw value (the card must still render)"
    )

  test("negative control: the author's exact reference shape reports not-found"):
    // Verbatim from the author's card (session 5cc7590a, 2026-09-11 11:33) —
    // the real file lives at ~/.nebflow/projects/gamma-telescope/reports/…,
    // so the expanded `~/projects/…` path does not exist.
    val ref = "~/projects/gamma-telescope/reports/20260911_113327_博士课题方向-场景矩阵图.svg"
    val p = card(s"""<img src="$ref" style="width:100%;height:auto;display:block" alt="场景矩阵"/>""")
    assertEquals(refs(p, "failed"), 1)
    assert(
      !Files.exists(Paths.get(s"${sys.props("user.home")}/projects/gamma-telescope")),
      "precondition: ~/projects/gamma-telescope must not exist on this machine"
    )
    assertEquals(reasonOf(p), "not-found")
    assertEquals(resolvedOf(p), Some(s"${sys.props("user.home")}/projects/gamma-telescope/reports/20260911_113327_博士课题方向-场景矩阵图.svg"))
    assert(htmlOf(p).contains(ref), "the raw reference stays in the HTML so the placeholder can name it")

  test("relative reference warns unresolvable (never proxied, never silent)"):
    val p = card("""<img src="images/logo.png"/>""")
    assertEquals(reasonOf(p), "unresolvable")
    assert(detailOf(p).contains("relative"), s"detail must name the relative-path rule: ${detailOf(p)}")
    assertEquals(resolvedOf(p), None)
    assertEquals(htmlOf(p), """<img src="images/logo.png"/>""")

  test("template placeholder warns unresolvable"):
    val p = card("""<img src="/tmp/${run_id}/plot.png"/>""")
    assertEquals(reasonOf(p), "unresolvable")
    assert(detailOf(p).contains("template"), s"detail must name the placeholder: ${detailOf(p)}")

  test("unparseable path warns unresolvable"):
    val nul = 0.toChar.toString
    val p = card(s"""<img src="/tmp/carderr${nul}bad.png"/>""")
    assertEquals(reasonOf(p), "unresolvable")

  test("non-whitelisted extension warns extension-not-allowed"):
    val dir = Files.createTempDirectory("cardref-txt")
    val txt = dir.resolve("notes.csv")
    Files.write(txt, "a,b".getBytes(StandardCharsets.UTF_8))
    try
      val p = card(s"""<img src="${txt.toString}"/>""")
      assertEquals(reasonOf(p), "extension-not-allowed")
      assertEquals(resolvedOf(p), Some(txt.toString))
      assertEquals(htmlOf(p), s"""<img src="${txt.toString}"/>""")
    finally deleteRecursively(dir)

  test("oversized file warns size-exceeded"):
    val file = Files.createTempFile("cardref-big-", ".png")
    val raf = new RandomAccessFile(file.toFile, "rw")
    try raf.setLength(201L * 1024 * 1024)
    finally raf.close()
    try
      val p = card(s"""<img src="${file.toString}"/>""")
      assertEquals(reasonOf(p), "size-exceeded")
      assert(detailOf(p).contains("200MB"), s"detail must name the limit: ${detailOf(p)}")
    finally Files.deleteIfExists(file)

  test("directory with an allowed extension warns not-regular-file"):
    val dir = Files.createTempDirectory("cardref-dir-")
    val asFile = dir.resolve("bundle.png")
    Files.createDirectory(asFile)
    try
      val p = card(s"""<img src="${asFile.toString}"/>""")
      assertEquals(reasonOf(p), "not-regular-file")
      assertEquals(resolvedOf(p), Some(asFile.toString))
    finally deleteRecursively(dir)

  test("structured warning carries ref / resolvedPath / reason / detail / count"):
    val p = card("""<img src="~/__carderr_missing_dir__/plot.png"/>""")
    val warning = warningsOf(p).headOption.getOrElse(fail("expected one warning"))
    assertEquals(warning.hcursor.get[String]("ref").toOption, Some("~/__carderr_missing_dir__/plot.png"))
    assertEquals(warning.hcursor.get[String]("reason").toOption, Some("not-found"))
    assert(warning.hcursor.get[String]("detail").toOption.exists(_.nonEmpty))
    assertEquals(warning.hcursor.get[Int]("count").toOption, Some(1))

  test("identical broken references collapse into one warning with a count"):
    val p = card("""<img src="~/__carderr_missing_dir__/a.png"/><img src="~/__carderr_missing_dir__/a.png"/>""")
    assertEquals(warningsOf(p).size, 1)
    assertEquals(countOf(p), 2)
    assertEquals(refs(p, "failed"), 1, "fileRefs.failed counts distinct references")

  test("warning list is capped; the remainder is counted in fileRefs.omitted"):
    val refs21 = (1 to 21).map(i => s"""<img src="~/__carderr_missing_dir__/f$i.png"/>""").mkString
    val p = card(refs21)
    assertEquals(warningsOf(p).size, 20)
    assertEquals(refs(p, "failed"), 21)
    assertEquals(refs(p, "omitted"), 1)

  // ── ③ 零回归 ────────────────────────────────────────────

  test("zero regression: remote / data / anchor / mailto references produce no warnings"):
    val html =
      """<div>hi</div><img src="https://example.com/a.png"/><img src="data:image/png;base64,AAA"/>""" +
        """<a href="#section">s</a><a href="mailto:x@y.com">m</a><script src="https://cdn.example.com/x.js"></script>"""
    val p = card(html)
    assertEquals(warningsOf(p), Nil)
    assertEquals(refs(p, "failed"), 0)
    assertEquals(refs(p, "proxied"), 0)
    assertEquals(htmlOf(p), html, "no rewrite for non-local references")

  test("zero regression: an already-proxied /api/nf-file URL is not re-reported"):
    val html = """<img src="/api/nf-file?path=%2Ftmp%2Fplot.png"/>"""
    val p = card(html)
    assertEquals(warningsOf(p), Nil)
    assertEquals(htmlOf(p), html)

  test("zero regression: a card whose local references all resolve produces no warnings"):
    withTempFile { file =>
      val html = s"""<div>x</div><img src="${file.toString}"/><img src="${file.toString}"/>"""
      val p = card(html)
      assertEquals(warningsOf(p), Nil)
      assertEquals(refs(p, "proxied"), 2)
      assertEquals(refs(p, "failed"), 0)
    }

  test("zero regression: sentinel + title contract unchanged for a clean card"):
    val p = card("<div>hi</div>", title = "My Card")
    assertEquals(p.hcursor.get[String]("title").toOption, Some("My Card"))
    assertEquals(p.hcursor.get[String]("html").toOption, Some("<div>hi</div>"))
    assertEquals(CardTool.summarizeResult(JsonObject("title" -> Json.fromString("My Card")), s"${sentinel}${p.noSpaces}"), "My Card rendered")

  // ── ④ 附件：匹配顺序 bug（修复前后可判红） ────────────────

  test("href-before-src cards no longer blow up (match merge is in document order)"):
    withTempFile { src =>
      val dir = Files.createTempDirectory("cardref-mix")
      val css = dir.resolve("style.css")
      Files.write(css, "body{}".getBytes(StandardCharsets.UTF_8))
      try
        val p = card(s"""<link rel="stylesheet" href="${css.toString}"/><img src="${src.toString}"/>""")
        assertEquals(refs(p, "proxied"), 2, "both references are replaced")
        assertEquals(warningsOf(p), Nil)
        assert(htmlOf(p).contains(s"""/api/nf-file?path=${encode(css.toString)}"""))
        assert(htmlOf(p).contains(s"""/api/nf-file?path=${encode(src.toString)}"""))
      finally deleteRecursively(dir)
    }

  test("payload leads with fileRefs/warnings so the 2048-char guard preview keeps them visible"):
    // ToolResultGuard.persistAndReplace replaces the LLM-visible content of a
    // >50K result with its first 2048 chars + a persisted-file pointer, so a
    // trailing warning section would fall out of the model's view on big cards.
    val huge = "<div>" + ("x" * 4000) + "</div>"
    val p = card(s"""<img src="~/__carderr_missing_dir__/plot.png"/>$huge""")
    val raw = p.noSpaces
    assert(raw.indexOf("\"fileRefs\"") < raw.indexOf("\"html\""), "fileRefs must precede html")
    assert(raw.indexOf("\"warnings\"") < raw.indexOf("\"html\""), "warnings must precede html")
    assert(raw.indexOf("not-found") < 2048, "the reason must sit inside the guard preview window")
    assert(htmlOf(p).contains(huge), "the large body still round-trips")

  test("summarizeResult surfaces the unresolved-reference count"):
    val p = card("""<img src="~/__carderr_missing_dir__/plot.png"/>""")
    val summary = CardTool.summarizeResult(JsonObject("title" -> Json.fromString("T")), s"${sentinel}${p.noSpaces}")
    assertEquals(summary, "T rendered — 1 file reference(s) NOT proxied")

  test("description promises the warning channel (tool description stays in sync)"):
    // description = baseDescription + the user-editable ~/.nebflow/card-design-prompt.md,
    // so only claims that live in baseDescription are asserted here — the runtime
    // copy is host state (drift registered in the batch report, not pinned here).
    val d = CardTool.description
    assert(d.contains("warnings"), "the description must name the warnings field")
    // 数据根渲染（home 硬编码 → 运行时动态化批 2026-09-11）：描述里的路径 token
    // 改为运行期插值 —— 断言跟随渲染值（默认 home 下恰为旧字面 `~/.nebflow`；
    // 同 JVM 内其它 suite 换根时描述随之变化，故不再钉死字面）。
    val dataRoot = nebflow.core.PathUtil.dataRootRenderValue
    assert(d.contains(s"$dataRoot/projects/<name>/"), "the description must teach the workspace path shape")
    assert(d.contains("fileRefs"), "the description must name the fileRefs counters")
