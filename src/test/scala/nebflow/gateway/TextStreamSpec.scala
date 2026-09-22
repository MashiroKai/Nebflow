package nebflow.gateway

import munit.FunSuite

/**
  * Window slicing / sparse index / open-leg decision — the pure core of the
  * text-stream protocol (design card §五 step ①, `TextStream.scala`).
  *
  * Why these assertions exist rather than "it compiles":
  *   · the open-leg decision is the ONE place that routes a file to the streaming
  *     path; an off-by-one on the threshold silently sends 100MiB through the old
  *     single-frame leg (the R1/R7 failure mode the card names).
  *   · `IndexBuilder` is streamed chunk-by-chunk in production but whole-buffer in
  *     these tests — the equivalence assertion below is what makes the streaming
  *     form trustworthy.
  *   · `firstLine` is index-anchor math; a wrong anchor makes every gutter number
  *     in the read-only view wrong (R4).
  */
class TextStreamSpec extends FunSuite:

  private def bytes(s: String): Array[Byte] = s.getBytes(TextStream.Utf8)

  // ── open-leg decision ──────────────────────────────────────────────────────

  test("threshold: ≤8MiB stays direct, >8MiB streams, binary never streams"):
    val t = TextStream.ThresholdBytes
    assertEquals(t, 8L * 1024 * 1024)
    assert(!TextStream.isStream(isBinary = false, size = 0L))
    assert(!TextStream.isStream(isBinary = false, size = t), "exactly the threshold stays direct")
    assert(TextStream.isStream(isBinary = false, size = t + 1), "one byte over streams")
    assert(TextStream.isStream(isBinary = false, size = 100L * 1024 * 1024))
    assert(!TextStream.isStream(isBinary = true, size = 100L * 1024 * 1024), "binary keeps its nf-file leg")

  test("pit ① — the streaming descriptor always carries a non-empty itemType"):
    // The descriptor frame must never look like the "empty content, empty
    // itemType" shape canvas.js re-fetches on (canvas.js:984) — that is the
    // self-sustaining refetch loop the card calls out. Every extension that can
    // take the streaming path must resolve to a non-empty registry itemType.
    val exts = List("txt", "log", "md", "json", "csv", "py", "js", "ts", "sh", "yaml", "", "zzz-unknown")
    exts.foreach { ext =>
      val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
      assert(entry.itemType.nonEmpty, s"itemType must be non-empty for extension '$ext'")
      assert(!entry.binary, s"'$ext' must not be classified binary (it is a text extension)")
    }

  // ── sparse index ───────────────────────────────────────────────────────────

  test("index: anchors land on every stride-th line start and totalLines is exact"):
    val stride = 4
    // 9 lines "L1\nL2\n…\nL9\n" ⇒ lines 1,5,9 get anchors
    val text = (1 to 9).map(i => s"L$i").mkString("\n") + "\n"
    val idx = TextStream.indexOf(bytes(text), stride)
    assertEquals(idx.totalLines, 9L)
    assertEquals(idx.stride, stride)
    assertEquals(idx.lineStarts.length, 3)
    assertEquals(idx.lineStarts(0), 0L)
    // "L1\n" is 3 bytes ⇒ line 5 starts at 4*3 = 12
    assertEquals(idx.lineStarts(1), 12L)
    assertEquals(idx.lineStarts(2), 24L)
    assertEquals(idx.anchorLineOf(0), 1L)
    assertEquals(idx.anchorLineOf(1), 5L)
    assertEquals(idx.anchorLineOf(2), 9L)

  test("index: a trailing newline does not open a phantom line, anchors past EOF are dropped"):
    val stride = 4
    // exactly 4 lines, ending on a newline: the 5th anchor would point at EOF
    val text = "a\nb\nc\nd\n"
    val idx = TextStream.indexOf(bytes(text), stride)
    assertEquals(idx.totalLines, 4L)
    assertEquals(idx.lineStarts, Vector(0L), "no anchor past the last real line")

  test("index: streaming the same body in chunks equals building it whole"):
    val stride = 8
    val body = bytes((1 to 200).map(i => s"line-$i padding").mkString("\n"))
    val whole = TextStream.indexOf(body, stride)
    val b = new TextStream.IndexBuilder(stride)
    var off = 0
    val sizes = List(1, 7, 13, 64, 5, 400, 3, 1024, 999)
    var si = 0
    while off < body.length do
      val n = math.min(sizes(si % sizes.length), body.length - off)
      b.feed(body.slice(off, off + n), n)
      off += n
      si += 1
    assertEquals(b.result, whole, "chunked index build must be identical to the whole-body build")
    assertEquals(b.fedBytes, body.length.toLong)

  test("index: empty body has zero lines and a usable anchor"):
    val idx = TextStream.indexOf(Array.emptyByteArray)
    assertEquals(idx.totalLines, 0L)
    assertEquals(idx.lineStarts, Vector(0L))

  test("index: anchorFor is the largest anchor ≤ the offset; anchorForLine is the jump primitive"):
    val stride = 4
    val idx = TextStream.indexOf(bytes((1 to 12).map(i => s"L$i").mkString("\n") + "\n"), stride)
    assertEquals(idx.anchorFor(0L), (0L, 1L))
    assertEquals(idx.anchorFor(11L), (0L, 1L))
    assertEquals(idx.anchorFor(12L), (12L, 5L))
    assertEquals(idx.anchorFor(9999L), (idx.lineStarts.last, idx.anchorLineOf(idx.lineStarts.length - 1)))
    assertEquals(idx.anchorForLine(1L), (0L, 1L))
    assertEquals(idx.anchorForLine(4L), (0L, 1L), "line 4 belongs to the anchor-1 interval")
    assertEquals(idx.anchorForLine(5L), (12L, 5L))
    assertEquals(idx.anchorForLine(99L), (idx.lineStarts.last, idx.anchorLineOf(idx.lineStarts.length - 1)),
      "a jump past EOF clamps to the last anchor")

  // ── window slicing ─────────────────────────────────────────────────────────

  test("window: text decodes, lineCount counts whole and partial lines, eof tracks the file end"):
    val all = bytes("aa\nbb\ncc\n")
    val w = TextStream.buildWindow(all.slice(0, 6), 6, 6L, all.length.toLong)
    assertEquals(w.text, "aa\nbb\n")
    assertEquals(w.lineCount, 2L)
    assert(!w.eof)

    val tail = TextStream.buildWindow(all.slice(6, 9), 3, 9L, all.length.toLong)
    assertEquals(tail.text, "cc\n")
    assertEquals(tail.lineCount, 1L)
    assert(tail.eof)

    val partial = TextStream.buildWindow(bytes("aa\nbb"), 5, 5L, 10L)
    assertEquals(partial.lineCount, 2L, "a window ending mid-line still occupies that row")
    assert(!partial.eof)

    assertEquals(TextStream.lineCountOf(""), 0L)
    assertEquals(TextStream.countNewlines(bytes("a\nb\nc")), 2L)

  test("window: invalid UTF-8 and a split multi-byte character decode with replacement, never throw"):
    val raw = Array[Byte](0x61, 0xff.toByte, 0xfe.toByte, 0x0a, 0xe4.toByte, 0xb8.toByte) // "a", bad, bad, \n, partial 中
    val w = TextStream.buildWindow(raw, raw.length, raw.length.toLong, raw.length.toLong)
    assert(w.text.contains("\ufffd"), "malformed bytes must surface as the replacement char")
    assert(w.text.startsWith("a"))
    assert(w.eof)

  test("firstLine refinement is anchor line + newlines in the residual span"):
    assertEquals(TextStream.firstLineFrom(1L, 0L), 1L)
    assertEquals(TextStream.firstLineFrom(1025L, 7L), 1032L)

  // ── index cache (LRU) ──────────────────────────────────────────────────────

  test("index cache: hit refreshes recency, miss is a no-op, eviction drops the LRU entry"):
    val k1 = TextStream.IndexKey("/a", 1L, 1L)
    val k2 = TextStream.IndexKey("/b", 1L, 1L)
    val k3 = TextStream.IndexKey("/c", 1L, 1L)
    val idxA = TextStream.SparseIndex(1024, Vector(0L), 1L)
    val idxB = TextStream.SparseIndex(1024, Vector(0L), 2L)
    val idxC = TextStream.SparseIndex(1024, Vector(0L), 3L)

    var entries = Vector.empty[(TextStream.IndexKey, TextStream.SparseIndex)]
    assertEquals(TextStream.lruGet(entries, k1), (None, entries))

    entries = TextStream.lruPut(entries, k1, idxA, 2)
    entries = TextStream.lruPut(entries, k2, idxB, 2)
    val (hit, refreshed) = TextStream.lruGet(entries, k1)
    assertEquals(hit, Some(idxA))
    entries = refreshed

    entries = TextStream.lruPut(entries, k3, idxC, 2)
    assertEquals(entries.length, 2)
    assertEquals(TextStream.lruGet(entries, k2)._1, None, "k2 was the least recently used")
    assertEquals(TextStream.lruGet(entries, k1)._1, Some(idxA))
    assertEquals(TextStream.lruGet(entries, k3)._1, Some(idxC))
