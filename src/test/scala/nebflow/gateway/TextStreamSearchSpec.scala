package nebflow.gateway

import munit.FunSuite

/**
 * Literal scanner of the text-stream search leg (design card §三.1 / §五 step ①).
 *
 * The scanner runs over a 100MiB file in 256KiB chunks, so the cases that matter
 * are the ones a whole-buffer implementation would never hit: a match straddling
 * a chunk seam, a line break straddling a seam (line/col bookkeeping), a hit list
 * that is capped (must say `truncated`), and a "line" that is the whole file (the
 * hit echo must stay bounded). Byte-exact line/col is what R5 compares against
 * ground truth, so every case pins the exact numbers.
 */
class TextStreamSearchSpec extends FunSuite:

  private def bytes(s: String): Array[Byte] = s.getBytes(TextStream.Utf8)

  private def scanAll(
    body: String,
    query: String,
    caseSensitive: Boolean = true,
    maxHits: Int = 1000,
    chunk: Int = Int.MaxValue
  ): TextStream.SearchOutcome =
    val sc = new TextStream.LiteralScanner(query, caseSensitive, maxHits)
    val all = bytes(body)
    var off = 0
    while off < all.length do
      val n = math.min(chunk, all.length - off)
      sc.feed(java.util.Arrays.copyOfRange(all, off, off + n), n)
      off += n
    sc.finish() // end of input, then drain for parity with the route layer
    sc.drainHits()
    sc.outcome

  end scanAll

  private def hitsOf(
    body: String,
    query: String,
    caseSensitive: Boolean = true,
    maxHits: Int = 1000,
    chunk: Int = Int.MaxValue
  ): Vector[TextStream.Hit] =
    val sc = new TextStream.LiteralScanner(query, caseSensitive, maxHits)
    val all = bytes(body)
    val out = Vector.newBuilder[TextStream.Hit]
    var off = 0
    while off < all.length do
      val n = math.min(chunk, all.length - off)
      sc.feed(java.util.Arrays.copyOfRange(all, off, off + n), n)
      off += n
      out ++= sc.drainHits()
    sc.finish() // end of input: finalize the unterminated last line
    out ++= sc.drainHits()
    out.result()

  end hitsOf

  test("hits carry the exact 1-based byte line and column"):
    val body = "alpha\nbeta TOKEN\ngamma TOKEN x\n"
    assertEquals(
      hitsOf(body, "TOKEN"),
      Vector(
        TextStream.Hit(2L, 6L, "beta TOKEN"),
        TextStream.Hit(3L, 7L, "gamma TOKEN x")
      )
    )
    assertEquals(scanAll(body, "TOKEN").totalHits, 2L)
    assertEquals(scanAll(body, "TOKEN").truncated, false)
    assertEquals(scanAll(body, "TOKEN").scannedBytes, bytes(body).length.toLong)

  test("a match straddling a chunk seam is still found (held-back tail)"):
    val body = "x" * 100 + "TOKEN" + "y" * 100
    val whole = hitsOf(body, "TOKEN")
    assertEquals(whole.length, 1)
    assertEquals(whole.head.col, 101L)
    // feed byte-by-byte: the worst-case seam
    assertEquals(hitsOf(body, "TOKEN", chunk = 1), whole)
    // and in awkward chunk sizes that split the token every which way
    Vector(2, 3, 4, 7, 16, 33, 102, 103).foreach { c =>
      assertEquals(hitsOf(body, "TOKEN", chunk = c), whole, s"chunk=$c must not lose the seam hit")
    }

  test("line numbers stay exact when the newline itself straddles a seam"):
    val body = "aaa\nbbb\nccc TOKEN\n"
    Vector(1, 2, 3, 4, 5, 8, 13).foreach { c =>
      assertEquals(
        hitsOf(body, "TOKEN", chunk = c).map(h => (h.line, h.col)),
        Vector((3L, 5L)),
        s"chunk=$c split the newlines; line/col must still be exact"
      )
    }

  test("case folding: insensitive by default, exact when caseSensitive"):
    val body = "Token token TOKEN\n"
    assertEquals(hitsOf(body, "token", caseSensitive = false).map(_.line), Vector(1L, 1L, 1L))
    assertEquals(hitsOf(body, "token", caseSensitive = true).map(_.col), Vector(7L))
    // non-ASCII folding is deliberately byte-exact (ASCII-only fold, documented)
    val uni = "ÄTOKEN\n"
    assertEquals(hitsOf(uni, "ä", caseSensitive = true).length, 0)
    assertEquals(hitsOf(uni, "Ä", caseSensitive = true).length, 1)

  test("maxHits caps the scan and reports truncation explicitly (never silent)"):
    val body = (1 to 50).map(i => s"hit$i").mkString("\n") // 50 hits of 'hit'
    val capped = scanAll(body, "hit", maxHits = 10)
    assertEquals(capped.totalHits, 10L)
    assert(capped.truncated, "a capped scan must say truncated=true")
    val exact = scanAll(body, "hit", maxHits = 50)
    assertEquals(exact.totalHits, 50L)
    assert(!exact.truncated)
    assertEquals(exact.scannedBytes, bytes(body).length.toLong)

  test("no hit ⇒ zero hits, full scan, not truncated (the negative case)"):
    val body = "aaa\nbbb\nccc\n"
    val none = scanAll(body, "zzz")
    assertEquals(none.totalHits, 0L)
    assert(!none.truncated)
    assertEquals(none.scannedBytes, bytes(body).length.toLong)

  test("literal, not regex: metacharacters match themselves; overlapping matches each count"):
    assertEquals(hitsOf("a.c\nabc\n", ".").length, 1, "'.' is a literal dot, not a wildcard")
    assertEquals(hitsOf("aaa", "aa").map(_.col), Vector(1L, 2L), "overlapping starts each count")

  test("a single-line file: hits still get exact columns and the echo stays bounded"):
    val body = ("z" * 5000) + "TOKEN" + ("z" * 100)
    val h = hitsOf(body, "TOKEN", chunk = 512)
    assertEquals(h.length, 1)
    assertEquals(h.head.line, 1L)
    assertEquals(h.head.col, 5001L)
    assert(h.head.text.endsWith("…"), "an excerpt shorter than the line must be marked")
    assert(h.head.text.length <= TextStream.MaxHitTextChars + 1)

  test("CRLF and an invalid-UTF-8 byte before the match do not shift line/col"):
    val body = "a\r\nb\r\nTOKEN\r\n"
    val h = hitsOf(body, "TOKEN")
    assertEquals(h.map(x => (x.line, x.col)), Vector((3L, 1L)))
    val raw = Array[Byte](0x61, 0x0a, 0xff.toByte, 0x0a) ++ bytes("TOKEN\n")
    val sc = new TextStream.LiteralScanner("TOKEN", caseSensitive = true)
    sc.feed(raw, raw.length)
    sc.finish() // end of input: run the held-back tail ("KEN\n") through bookkeeping
    assertEquals(sc.drainHits().map(x => (x.line, x.col)), Vector((3L, 1L)))

  test("draining is incremental — frames never repeat hits"):
    val body = (1 to 20).map(i => ("pad" * 20) + s"hit$i").mkString("\n")
    val sc = new TextStream.LiteralScanner("hit", caseSensitive = true, maxHits = 100)
    val all = bytes(body)
    var off = 0
    var seen = 0
    while off < all.length do
      val n = math.min(64, all.length - off)
      sc.feed(java.util.Arrays.copyOfRange(all, off, off + n), n)
      seen += sc.drainHits().length
      off += n
    sc.finish() // end of input: the last line has no trailing newline
    seen += sc.drainHits().length
    assertEquals(seen, 20)
    assertEquals(sc.totalHits, 20L)
    assertEquals(sc.drainHits().length, 0, "drain clears the buffer")

  test("an empty query is rejected at construction (the route layer reports it as an error frame)"):
    intercept[IllegalArgumentException](new TextStream.LiteralScanner(""))
end TextStreamSearchSpec
