package nebflow.social.outbound

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.Files

/**
 * Offline spec for the file-class long-shot conversion seam (pop-social-files
 * batch, 2026-10-20).
 *
 * 🔴 NO NODE, NO BROWSER: the conversion scripts are never started here. What
 * this spec pins is the pure decision surface — the convertibility table, the
 * dedup ledger's key semantics, the one-line summarizer, and the script
 * DISCOVERY (a filesystem read, never a process).
 */
class FileLongShotSpec extends CatsEffectSuite:

  private def tmpDir(): os.Path = os.temp.dir(prefix = "nb-longshot-")

  // ───────────────────────── convertibility table ─────────────────────────

  test("FL-1 the convertible table is md/markdown + the web-page family, sharing ONE extension reader") {
    assertEquals(FileLongShot.Extensions, Set("md", "markdown", "html", "htm"))
    assert(FileLongShot.isConvertible(os.Path("/x/a.md", os.pwd)))
    assert(FileLongShot.isConvertible(os.Path("/x/a.markdown", os.pwd)))
    assert(FileLongShot.isConvertible(os.Path("/x/a.html", os.pwd)))
    assert(FileLongShot.isConvertible(os.Path("/x/a.htm", os.pwd)))
    assert(!FileLongShot.isConvertible(os.Path("/x/a.pdf", os.pwd)), "pdf keeps its own document leg (route E)")
    assert(!FileLongShot.isConvertible(os.Path("/x/a.png", os.pwd)), "images never re-encode")
    assert(!FileLongShot.isConvertible(os.Path("/x/a.mdown", os.pwd)), "no suffix leakage")
  }

  test("FL-2 the extension reader is case-insensitive and reads the LAST dot") {
    assertEquals(FileLongShot.extensionOf("Report.MD"), "md")
    assertEquals(FileLongShot.extensionOf("a.b.html"), "html")
    assertEquals(FileLongShot.extensionOf("noext"), "")
  }

  // ───────────────────────── dedup ledger ─────────────────────────

  test("FL-3 dedup: same path+size+mtime ⇒ seen twice; new bytes ⇒ new key; reset clears") {
    FileLongShot.Dedup.reset()
    val dir = tmpDir()
    val md = dir / "report.md"
    Files.write(md.toNIO, "# v1".getBytes("UTF-8"))
    val key = FileLongShot.Dedup.keyOf(md).getOrElse(fail("a readable file always yields a key"))

    assertEquals(FileLongShot.Dedup.seen(key), false, "first push: not seen")
    FileLongShot.Dedup.record(key)
    assertEquals(FileLongShot.Dedup.seen(key), true, "after a successful push: seen")
    assertEquals(FileLongShot.Dedup.seen(key + "|other"), false, "keys do not blur")

    // New bytes (size changes) ⇒ a DIFFERENT key ⇒ not seen.
    Files.write(md.toNIO, "# v2 with more bytes".getBytes("UTF-8"))
    val key2 = FileLongShot.Dedup.keyOf(md).getOrElse(fail("still readable"))
    assert(key2 != key, "size change ⇒ new fingerprint")
    assertEquals(FileLongShot.Dedup.seen(key2), false, "a regenerated file pushes again")

    FileLongShot.Dedup.reset()
    assertEquals(FileLongShot.Dedup.seen(key), false, "reset clears the ledger")
  }

  test("FL-4 dedup keyOf: an unreadable path yields None (不可去重 ⇒ 照常投递)") {
    assertEquals(FileLongShot.Dedup.keyOf(tmpDir() / "gone.md"), None)
  }

  test("FL-5 dedup ledger is bounded (LRU eviction at MaxEntries)") {
    FileLongShot.Dedup.reset()
    val dir = tmpDir()
    val n = FileLongShot.Dedup.MaxEntries + 8
    (1 to n).foreach { i =>
      val p = dir / s"f$i.md"
      Files.write(p.toNIO, s"# $i".getBytes("UTF-8"))
      FileLongShot.Dedup.keyOf(p).foreach(FileLongShot.Dedup.record)
    }
    assertEquals(FileLongShot.Dedup.size, FileLongShot.Dedup.MaxEntries, "the ledger never exceeds its cap")
    val firstKey = FileLongShot.Dedup.keyOf(dir / "f1.md").getOrElse(fail("readable"))
    assertEquals(FileLongShot.Dedup.seen(firstKey), false, "the eldest entries were evicted (LRU)")
    FileLongShot.Dedup.reset()
  }

  // ───────────────────────── one-line summarizer ─────────────────────────

  test("FL-6 summarize (md): the first heading line, de-marked, capped at 48 chars") {
    val dir = tmpDir()
    val md = dir / "report.md"
    Files.write(
      md.toNIO,
      "\n\n## 存储审计：`dataRoot` 子树与附件链路结论\n\n正文第二段。\n".getBytes("UTF-8")
    )
    assertEquals(
      FileLongShot.summarize(md),
      Some("存储审计：dataRoot 子树与附件链路结论".take(48)),
      "first non-blank line, markdown markers stripped"
    )
  }

  test("FL-7 summarize (html): the <title> wins; tag-free fallback otherwise") {
    val dir = tmpDir()
    val withTitle = dir / "t.html"
    Files.write(withTitle.toNIO, "<html><head><title>DOSTEL 审计报告</title></head><body>x</body></html>".getBytes("UTF-8"))
    assertEquals(FileLongShot.summarize(withTitle), Some("DOSTEL 审计报告"))
    val noTitle = dir / "n.html"
    Files.write(noTitle.toNIO, "<html><body><p>裸正文一行</p></body></html>".getBytes("UTF-8"))
    assertEquals(FileLongShot.summarize(noTitle), Some("裸正文一行"), "title-less html falls back to the first text")
  }

  test("FL-8 summarize: unreadable/empty ⇒ None (the text card omits the line, never fails)") {
    assertEquals(FileLongShot.summarize(tmpDir() / "gone.md"), None)
    val empty = tmpDir() / "e.md"
    Files.write(empty.toNIO, Array.empty[Byte])
    assertEquals(FileLongShot.summarize(empty), None)
  }

  // ───────────────────────── unavailable carrier + discovery ─────────────────────────

  test("FL-9 Unavailable never fakes a shot; NodeShot refuses non-convertible classes without a process") {
    FileLongShot.Unavailable.shot(os.Path("/x/a.md", os.pwd), os.Path("/x/out.png", os.pwd)).map {
      case Left(reason) => assertEquals(reason, RichDegrade.Reason.EngineUnavailable)
      case Right(_) => fail("Unavailable must never fake a shot")
    }
    FileLongShot.NodeShot().shot(os.Path("/x/a.xyz", os.pwd), os.Path("/x/out.png", os.pwd)).map {
      case Left(reason) => assert(reason.contains("not a convertible file class"), reason)
      case Right(_) => fail("a non-convertible class must not reach any script")
    }
  }

  test("FL-10 script discovery is the SAME walker as the html shot (one upward walk, two names)") {
    val html = WebPageShot.NodePlaywright.detectScriptNamed("social-html-shot.mjs")
    val md = WebPageShot.NodePlaywright.detectScriptNamed("social-md-shot.mjs")
    // In-repo run: both are found, and both sit in the repo's scripts/ face.
    (html, md) match
      case (Some(h), Some(m)) =>
        assertEquals(h.last, "social-html-shot.mjs")
        assertEquals(m.last, "social-md-shot.mjs")
        assertEquals(h / os.up, m / os.up, "both scripts come from ONE repo root")
      case _ =>
        // Packaged/odd-cwd run: discovery may legitimately miss; the ONLY claim
        // is that the two walkers agree (both miss together, never diverge).
        assertEquals(html, md, "the two discovery reads cannot disagree")
  }

end FileLongShotSpec
