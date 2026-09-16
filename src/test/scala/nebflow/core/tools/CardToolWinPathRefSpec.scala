package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * winpath batch (2026-09-17) — a Windows drive-letter reference in a Card is an
 * ABSOLUTE path and must be classified as one.
 *
 * KAI 实测（Windows 实例，2026-09-16）：
 *   `<img src="C:/Users/Kai/Downloads/bethe_bloch_ionization.svg">`
 *   ⇒ `warnings[0].reason = "unresolvable"`, `detail = "relative references are
 *   never resolved …"` — while the SAME file addressed as `~/Downloads/…`
 *   inlined successfully. So the gate did not fail to FIND the file; it never
 *   looked — it classified a drive-letter absolute path as a relative reference.
 *
 * Pre-fix mechanism (`CardTool.decideRef`): the gate was
 * `!value.startsWith("~") && !value.startsWith("/")`, i.e. the accepted anchor
 * set was exactly POSIX-absolute + `~`. A drive-letter path matched neither.
 *
 * 判据为**分类层**读数（本 spec 不依赖 Windows 文件真实存在）：macOS/Linux 上
 * `Paths.get("C:/…")` 只是首段名为 `C:` 的相对路径，无法造出「真的 `C:/`」夹具，
 * 所以这里断言的是「跨过分类门、进入既有 probe/inline 路径」这一可判读事实：
 *   - `resolvedPath` 出现（`resolvePath` 返回 `Some`）⇒ 已进入解析路径；
 *   - `reason` 为 `not-found`（而非 `unresolvable`）⇒ 已进入 probe 分支；
 *   - 相对引用仍为 `unresolvable` + `detail` 含 "relative"（拒绝语义一字不动）。
 * 「KAI 侧真实 `C:/…` 文件端到端内联成功」是设备侧待验项，不在本 spec 断言面内。
 *
 * 与 `PathUtil.isAbsolute`（`core/paths.scala:21-24`，POSIX/盘符/UNC 三形态）的取舍：
 * 本面**不复用**该判定，理由是它会把 UNC（`\\server\share\…`，网络语义，本批明示
 * 不扩面）与盘符相对形态（`C:x.png`，即「相对服务器 cwd 解析」——正是
 * `CardTool` 拒绝相对引用所要挡住的语义）一并放行。本面只承认**带分隔符的盘符
 * 绝对路径**（`C:/…`、`C:\…`），UNC 单列一条**明确拒绝**（且不再报成 relative）。
 */
class CardToolWinPathRefSpec extends FunSuite:

  private val ctx = ToolContext(projectRoot = os.pwd.toString)
  private val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Call the tool and decode the payload (same shape CardToolFileRefSpec pins). */
  private def card(html: String): Json =
    val input = JsonObject("html" -> Json.fromString(html), "title" -> Json.fromString("T"))
    val result = CardTool.call(input, ctx).unsafeRunSync().getOrElse(fail("expected Right"))
    assert(result.startsWith("___CARD_HTML___"), s"sentinel contract: ${result.take(60)}")
    io.circe.parser.parse(result.substring("___CARD_HTML___".length)) match
      case Right(json) => json
      case Left(err)   => fail(s"payload must be pure JSON: $err")

  private def htmlOf(p: Json): String = p.hcursor.get[String]("html").toOption.getOrElse("")
  private def warningsOf(p: Json): List[Json] = p.hcursor.get[List[Json]]("warnings").toOption.getOrElse(Nil)
  private def reasonOf(p: Json): String =
    warningsOf(p).headOption.flatMap(_.hcursor.get[String]("reason").toOption).getOrElse("")
  private def detailOf(p: Json): String =
    warningsOf(p).headOption.flatMap(_.hcursor.get[String]("detail").toOption).getOrElse("")
  private def resolvedOf(p: Json): Option[String] =
    warningsOf(p).headOption.flatMap(_.hcursor.get[Option[String]]("resolvedPath").toOption.flatten)
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

  /** A `C:/…` tree UNDER THE CURRENT WORKING DIRECTORY. On a POSIX JVM
    *  `Paths.get("C:/…")` is a relative path whose first segment is the literal
    *  name `C:`, so a real file can live at `<cwd>/C:/Users/you/…`. That is
    *  exactly what the fix must let through — the reference is CLASSIFIED, never
    *  rewritten, and the existing probe/inline path then runs unchanged. On a
    *  Windows JVM `C:/…` would address the real drive C:, so the test is
    *  skipped there (`assume`). */
  private def withDriveFile[A](f: (String, Path) => A): A =
    val root = Paths.get("C:")
    assert(!Files.exists(root), "precondition: no `C:` entry in the working directory")
    val dir = root.resolve("Users").resolve("you").resolve("cardref-win")
    Files.createDirectories(dir)
    val file = dir.resolve("plot.png")
    Files.write(file, Array[Byte](0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))
    try f("C:/Users/you/cardref-win/plot.png", file)
    finally deleteRecursively(root)

  // ── ① 盘符绝对路径 = 绝对（N1；macOS 上可判的分类层读数） ──────────────

  test("KAI's exact reference: `C:/…svg` is classified ABSOLUTE, not relative"):
    // Verbatim shape from KAI's Windows instance (2026-09-16).
    val p = card("""<img src="C:/Users/Kai/Downloads/bethe_bloch_ionization.svg"/>""")
    assertEquals(reasonOf(p), "not-found", "must reach the file probe (a drive-letter path is absolute)")
    assert(
      resolvedOf(p).isDefined,
      s"resolvedPath must be present — the value entered resolvePath: ${warningsOf(p)}"
    )
    assert(!detailOf(p).contains("relative"), s"a drive-letter path is NOT a relative reference: ${detailOf(p)}")
    assertEquals(htmlOf(p), """<img src="C:/Users/Kai/Downloads/bethe_bloch_ionization.svg"/>""")
    assertEquals(refs(p, "proxied"), 0)
    assertEquals(refs(p, "failed"), 1)

  test("`C:\\…` (backslash form) is classified ABSOLUTE too"):
    val p = card("""<img src="C:\Users\Kai\Downloads\bethe_bloch_ionization.svg"/>""")
    assertEquals(reasonOf(p), "not-found", "both Windows separators must be accepted")
    assert(resolvedOf(p).isDefined, s"resolvedPath must be present: ${warningsOf(p)}")
    assert(!detailOf(p).contains("relative"), s"backslash drive path is absolute: ${detailOf(p)}")

  test("lowercase drive letter and another drive are accepted as well"):
    assertEquals(reasonOf(card("""<img src="d:/data/plot.png"/>""")), "not-found")
    assertEquals(reasonOf(card("""<img src="Z:\plots\spectrum.svg"/>""")), "not-found")

  test("an extension-less drive path is warned, not silently dropped"):
    // Same treatment as the POSIX-anchored `/Users/you/notes` shape: path-shaped
    // enough to be classified, then refused by the extension whitelist.
    val p = card("""<img src="C:/Users/you/notes"/>""")
    assertEquals(reasonOf(p), "extension-not-allowed")
    assert(!detailOf(p).contains("relative"), detailOf(p))

  // ── ② 端到端（POSIX 上的等价夹具）：盘符引用真的走到内联 ─────────────

  test("a drive-letter reference to an existing file is embedded (whole path runs)"):
    assume(!isWindows, "on Windows `C:/…` addresses the real drive C:, not a cwd-relative tree")
    withDriveFile { (ref, _) =>
      val p = card(s"""<img src="$ref" alt="plot"/>""")
      assertEquals(warningsOf(p), Nil, "a resolvable drive-letter reference must NOT warn")
      assertEquals(refs(p, "inlined"), 1, "it must reach the inline policy, not stop at classification")
      assertEquals(refs(p, "failed"), 0)
      assert(htmlOf(p).startsWith("""<img src="data:image/png;base64,"""), htmlOf(p))
    }

  test("a drive-letter reference to a non-image existing file is proxied"):
    assume(!isWindows, "on Windows `C:/…` addresses the real drive C:, not a cwd-relative tree")
    val root = Paths.get("C:")
    val dir = root.resolve("Users").resolve("you").resolve("cardref-win")
    try
      Files.createDirectories(dir)
      val js = dir.resolve("data.json")
      Files.write(js, "{}".getBytes(StandardCharsets.UTF_8))
      val p = card("""<img src="C:/Users/you/cardref-win/data.json"/>""")
      assertEquals(warningsOf(p), Nil)
      assertEquals(refs(p, "proxied"), 1)
      assertEquals(
        htmlOf(p),
        s"""<img src="/api/nf-file?path=${java.net.URLEncoder.encode("C:/Users/you/cardref-win/data.json", "UTF-8")}"/>"""
      )
    finally deleteRecursively(root)

  // ── ③ N2 不回归：相对引用拒绝语义一字不动 ─────────────────────────────

  test("N2: relative references are still rejected as relative"):
    val shapes = List("images/logo.png", "./x.png", "../x.png", "sub/dir/x.svg", "logo.png")
    shapes.foreach { s =>
      val p = card(s"""<img src="$s"/>""")
      assertEquals(reasonOf(p), "unresolvable", s"'$s' must stay a relative mistake")
      assert(detailOf(p).contains("relative"), s"'$s' must name the relative rule: ${detailOf(p)}")
      assertEquals(resolvedOf(p), None, s"'$s' must never resolve")
    }

  test("N2: a drive-RELATIVE path (`C:x.png`) is still relative"):
    // `C:x.png` is resolved against the current directory on drive C: — a
    // relative reference in the sense this gate refuses (the server's cwd must
    // never enter the URL space). Only the separator forms are absolute.
    val p = card("""<img src="C:plot.png"/>""")
    assertEquals(reasonOf(p), "unresolvable")
    assert(detailOf(p).contains("relative"), s"drive-relative stays relative: ${detailOf(p)}")

  test("N2: `\\foo\\x.png` (no drive letter) is not treated as absolute"):
    val p = card("""<img src="\Users\you\x.png"/>""")
    assertEquals(reasonOf(p), "unresolvable")
    assert(detailOf(p).contains("relative"), s"no drive letter ⇒ not absolute: ${detailOf(p)}")

  test("N2: POSIX absolute and `~` behaviour unchanged"):
    val abs = card("""<img src="/tmp/__cardref_win_missing__/plot.png"/>""")
    assertEquals(reasonOf(abs), "not-found")
    assertEquals(resolvedOf(abs), Some("/tmp/__cardref_win_missing__/plot.png"))

    val tilde = card("""<img src="~/__cardref_win_missing__/plot.png"/>""")
    assertEquals(reasonOf(tilde), "not-found")
    assertEquals(
      resolvedOf(tilde),
      Some(s"${sys.props("user.home")}/__cardref_win_missing__/plot.png"),
      "~ expansion unchanged"
    )

  // ── ④ UNC：明确拒绝，且不再报成 relative ──────────────────────────────

  test("UNC (`\\\\server\\share\\…`) is refused explicitly — never as `relative`"):
    val p = card("""<img src="\\server\share\plot.png"/>""")
    assertEquals(reasonOf(p), "unresolvable")
    assert(!detailOf(p).contains("relative"), s"UNC is not a relative mistake: ${detailOf(p)}")
    assert(detailOf(p).toLowerCase.contains("unc"), s"the message must name the UNC rule: ${detailOf(p)}")
    assertEquals(htmlOf(p), """<img src="\\server\share\plot.png"/>""", "raw value kept")

  test("protocol-relative `//server/share/…` stays out of the file face entirely"):
    // `//host/path` is the browser's protocol-relative URL form, not a Windows
    // UNC share — it must keep being ignored (never probed, never warned), or
    // every protocol-relative CDN reference in a card would start warning.
    val html = """<img src="//cdn.example.com/a.png"/><link rel="stylesheet" href="//cdn.example.com/s.css"/>"""
    val p = card(html)
    assertEquals(warningsOf(p), Nil)
    assertEquals(htmlOf(p), html)

  // ── ⑤ 文案与实际行为一致 ─────────────────────────────────────────────

  test("copy: the relative-rejection detail teaches the Windows drive form"):
    val d = detailOf(card("""<img src="images/logo.png"/>"""))
    assert(d.contains("/Users/you/"), s"the POSIX example must stay: $d")
    assert(d.contains("~/"), s"the `~` example must stay: $d")
    assert(d.contains("""C:\Users\you"""), s"a Windows drive example must be present: $d")

  test("copy: the tool description teaches the Windows drive form"):
    // description = baseDescription + the user-editable ~/.nebflow/card-design-prompt.md,
    // so only claims that live in baseDescription are asserted (same boundary
    // CardToolFileRefSpec registers).
    val d = CardTool.description
    assert(d.contains("""C:\Users\you"""), s"the description must show the Windows drive example: $d")
    assert(d.contains("/Users/you/project/plot.png"), "the POSIX example must stay")
    assert(d.contains("`~` expands to the user's home directory"), "the `~` semantics must stay documented")

  // ── ⑥ 变异面锚点：分类判定本身（host 无关的纯字符串读数） ──────────────

  test("classification is a pure string verdict (same answer on every host OS)"):
    // These are the shapes the gate must separate; they are checked through the
    // tool so the assertion survives any internal refactor of the predicate.
    val anchored = List("C:/x.svg", "C:\\x.svg", "d:/x.svg", "/abs/x.svg", "~/x.svg")
    val notAnchored = List("images/logo.png", "./x.png", "C:x.svg", "\\x.svg", "\\\\srv\\share\\x.svg")
    anchored.foreach { s =>
      val p = card(s"""<img src="$s"/>""")
      assert(
        resolvedOf(p).isDefined,
        s"'$s' must be classified as an anchored (absolute) reference and enter resolvePath"
      )
    }
    notAnchored.foreach { s =>
      val p = card(s"""<img src="$s"/>""")
      assertEquals(resolvedOf(p), None, s"'$s' must never enter resolvePath")
    }

  // ── ⑦ 分类判定的纯字符串矩阵（无夹具、无 OS 分支 ⇒ 三平台同判读） ───────

  test("predicate: drive-letter absolute paths are anchored; relative shapes are not"):
    val anchored =
      List("C:/x.svg", """C:\x.svg""", "d:/x.svg", """Z:\plot.png""", "C:\\Users\\Kai/x.svg", "/abs/x.svg", "~/x.svg")
    anchored.foreach(s => assert(FileRefs.isAnchoredRefPath(s), s"'$s' must be anchored (absolute)"))
    val refused = List(
      "images/logo.png",
      "./x.png",
      "../x.png",
      "logo.png",
      "C:x.svg", // drive-relative — resolved against the server cwd: still refused
      "C:",
      """\x.svg""", // rooted but drive-less — not one of the recognized absolute forms
      """\\srv\share\x.svg""" // UNC: refused explicitly (never resolved)
    )
    refused.foreach(s => assert(!FileRefs.isAnchoredRefPath(s), s"'$s' must NOT be anchored"))

  test("predicate: the drive separators are `C:/` and `C:\\` — nothing else"):
    assert(FileRefs.isWindowsDriveAbsolute("C:/x.svg"))
    assert(FileRefs.isWindowsDriveAbsolute("""C:\x.svg"""))
    assert(FileRefs.isWindowsDriveAbsolute("z:/x"))
    assert(!FileRefs.isWindowsDriveAbsolute("C:x.svg"), "drive-relative is not a drive-absolute")
    assert(!FileRefs.isWindowsDriveAbsolute("/x.svg"))
    assert(!FileRefs.isWindowsDriveAbsolute("~/x.svg"))
    assert(!FileRefs.isWindowsDriveAbsolute("xc:/x.svg"), "the colon must sit at position 1")
    assert(!FileRefs.isWindowsDriveAbsolute(":C/x.svg"))

  test("predicate: UNC is the backslash form only — `//host` stays a URL"):
    assert(FileRefs.isUncPath("""\\srv\share\x.svg"""))
    assert(FileRefs.isUncPath("""\\192.168.1.10\public\x.png"""))
    assert(!FileRefs.isUncPath("//cdn.example.com/x.svg"), "protocol-relative URL, not a share")
    assert(!FileRefs.isUncPath("/x.svg"))
    assert(!FileRefs.isUncPath("""\srv\share\x.svg"""), "one backslash is not a UNC root")

  test("predicate: an extension-less drive path is still path-shaped (warned, not silent)"):
    assert(FileRefs.looksLikeFilePath("""C:\Users\you\notes"""))
    assert(FileRefs.looksLikeFilePath("C:/Users/you/notes"))
    // unchanged boundary: an extension-less relative string stays ignored
    assert(!FileRefs.looksLikeFilePath("notes"))
    assert(FileRefs.looksLikeFilePath("images/logo.png"))

