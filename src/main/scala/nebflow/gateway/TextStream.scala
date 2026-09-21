package nebflow.gateway

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
  * Pure function layer of the text-stream read-only protocol (design card
  * `.nebflow/reports/20260920_212955_textstream-design__chain-textstream.md`
  * §五 step ① — window slicing / sparse line index / literal scan).
  *
  * ZERO IO in this file on purpose: every function here takes bytes already in
  * hand, so the whole protocol core is directly unit-testable (`TextStream*Spec`)
  * without a gateway, a filesystem or a socket. The route layer
  * (`WebSocketRoutes.scala`) owns the reads and feeds chunks in.
  *
  * The socket contract (card §三.4, byte-for-byte):
  * {{{
  *   C→S  {type:"textWindow", reqId, tabId, path, mtimeMs, startByte, endByte}
  *   S→C  {type:"textWindow", reqId, tabId, path, mtimeMs, startByte, endByte,
  *         text, firstLine, lineCount, eof}
  *   C→S  {type:"textIndex", reqId, tabId, path, mtimeMs}
  *   S→C  {type:"textIndex", reqId, tabId, path, size, mtimeMs, totalLines, stride, lineStarts[]}
  *   C→S  {type:"textSearch", reqId, tabId, path, mtimeMs, query, caseSensitive, maxHits}
  *   S→C  {type:"textSearchHit", reqId, hits:[{line, col, text}]}      // ≤200/frame
  *   S→C  {type:"textSearchDone", reqId, scannedBytes, hits, truncated}
  *   C→S  {type:"textCancel", reqId}
  * }}}
  *
  * Cost model (why the constants are what they are): a WS text frame carries one
  * whole string, so 100MiB in one frame costs ~1.07GB of peak RSS server side and
  * 106.4M characters on the wire. Everything below keeps the server at O(chunk)
  * memory: windows are sliced, the index and the scan stream chunk by chunk.
  */
object TextStream:

  /** The render-path switch, NOT an open gate (card §三.3).
    *
    * `MaxPopReadFileBytes` (100MB) is the gate that decides whether a file may be
    * opened at all and is untouched; this constant only decides WHICH rendering
    * path an open text file takes. Deliberately NOT configurable in v1: the
    * threshold is a server single point of truth so the frontend cannot drift
    * against a second copy of the ruler. Measured cost at 100MiB ⇒ +1.07GB peak
    * RSS / 106.4M chars / 1431ms scales linearly: 8MiB ⇒ ~+85MB / ~8.5M chars /
    * ~115ms (both ends imperceptible), 16MiB ⇒ ~+170MB (borderline), 32MiB ⇒
    * ~+340MB (over budget).
    */
  val ThresholdBytes: Long = 8L * 1024 * 1024

  /** Lines between two sparse-index anchors (lines 1, 1+stride, 1+2*stride, …).
    * 100MiB / ~40B per line ≈ 2.6M lines ⇒ ~2.6K anchors ⇒ ~30KB of JSON. */
  val StrideLines: Int = 1024

  /** Window size the client asks for (it may ask for less; the server caps). */
  val DefaultWindowBytes: Int = 256 * 1024

  /** Hard cap on one window request — a client asking for more gets an error
    * frame rather than a silent clamp (no silent truncation anywhere in this
    * protocol). */
  val MaxWindowBytes: Int = 1024 * 1024

  /** Read/scan granularity. Bounds server memory to one chunk. */
  val ScanChunkBytes: Int = 256 * 1024

  val DefaultMaxHits: Int = 5000
  val MaxAllowedMaxHits: Int = 100000

  /** Hits per `textSearchHit` frame (card §三.1). */
  val HitsPerFrame: Int = 200

  /** Concurrent scans server-wide (card §三.1: per tab ≤1 by construction of the
    * pull protocol, plus a global cap so several tabs cannot thrash the disk). */
  val MaxConcurrentScans: Int = 2

  /** Index cache bound (card §三.5): keyed by (path, size, mtimeMs). */
  val MaxCachedIndexes: Int = 16

  /** Characters of the matched line echoed in a hit (bounded: a hit list must
    * never carry a 100MiB single line). */
  val MaxHitTextChars: Int = 200

  /** Per-request timeout (card §三.5: a failed window is a VISIBLE error with a
    * manual retry — never a silent hang). */
  val RequestTimeout: FiniteDuration = 10.seconds

  val Utf8: java.nio.charset.Charset = StandardCharsets.UTF_8

  /** Cache key: the file identity a window/index/scan belongs to. `size`+`mtimeMs`
    * changing invalidates both the cached index and every loaded client window. */
  final case class IndexKey(path: String, size: Long, mtimeMs: Long)

  /** Sparse line index: `lineStarts(i)` is the byte offset of line
    * `1 + i * stride` (1-based lines). */
  final case class SparseIndex(stride: Int, lineStarts: Vector[Long], totalLines: Long):
    require(stride > 0, "stride must be positive")

    /** Line number of anchor `i` (1-based). */
    def anchorLineOf(i: Int): Long = 1L + i.toLong * stride

    /** Largest anchor at or before `byteOffset` ⇒ (anchorOffset, anchorLine).
      * The caller refines the line by counting newlines in the residual span. */
    def anchorFor(byteOffset: Long): (Long, Long) =
      if lineStarts.isEmpty then (0L, 1L)
      else
        var lo = 0
        var hi = lineStarts.length - 1
        var best = 0
        while lo <= hi do
          val mid = (lo + hi) >>> 1
          if lineStarts(mid) <= byteOffset then
            best = mid
            lo = mid + 1
          else hi = mid - 1
        (lineStarts(best), anchorLineOf(best))

    /** Largest anchor whose line is at or before `line` ⇒ (anchorOffset, anchorLine).
      * This is the jump primitive: the client binary-searches the index, asks for
      * the window starting at that anchor, then counts newlines inside the window
      * to land on the exact line. */
    def anchorForLine(line: Long): (Long, Long) =
      val target = math.max(1L, line)
      val i =
        if totalLines <= 0 then 0
        else math.min(((target - 1) / stride).toInt, math.max(lineStarts.length - 1, 0))
      (lineStarts.lift(i).getOrElse(0L), anchorLineOf(i))

  /** One served window (`text`) plus the metadata the client needs to place it. */
  final case class Window(text: String, lineCount: Long, eof: Boolean)

  /** One search hit. `col` is the exact 1-based BYTE column of the match inside
    * its line; `text` is the matched line's content, bounded to `MaxHitTextChars`
    * and `…`-suffixed when the line continues past it. A hit is emitted once its
    * line's echo is final: at the terminating `\n`, or as soon as the excerpt
    * bound is reached. `drainHits` also flushes hits of the current unterminated
    * line with the bytes seen so far — at the caller's final (EOF) drain that IS
    * the complete line; a mid-scan drain of a line straddling a chunk boundary
    * carries a true prefix of it (line/col stay exact). */
  final case class Hit(line: Long, col: Long, text: String)

  /** Terminal state of one scan. `truncated` is always explicit — a capped or
    * cancelled scan never looks like a complete one (card §三.1). */
  final case class SearchOutcome(scannedBytes: Long, totalHits: Long, truncated: Boolean)

  /** The open-leg decision (card §三.3). Binary files never stream — they keep
    * their `/api/nf-file` byte leg; text files above the threshold stream. */
  def isStream(isBinary: Boolean, size: Long): Boolean = !isBinary && size > ThresholdBytes

  /** Decode bytes to text. NEVER throws: malformed UTF-8 becomes U+FFFD
    * (`String(byte[], Charset)` uses a REPLACE decoder), which is exactly the
    * per-window robustness the CRLF / BOM / invalid-UTF-8 fixtures check. A byte
    * range that splits a multi-byte character shows one replacement char at the
    * seam — invisible in practice, never fatal. */
  def decode(bytes: Array[Byte], len: Int = -1): String =
    val n = if len >= 0 then math.min(len, bytes.length) else bytes.length
    new String(bytes, 0, n, Utf8)

  /** Lines contained in a window text: newline count, plus 1 when the text does
    * not end on a line break (a window may end mid-line; that partial line still
    * occupies a row). Empty text ⇒ 0. */
  def lineCountOf(text: String): Long =
    if text.isEmpty then 0L
    else
      var i = 0
      var n = 0L
      while i < text.length do
        if text.charAt(i) == '\n' then n += 1
        i += 1
      if text.charAt(text.length - 1) == '\n' then n else n + 1

  /** Build the served window value. `endByte` is the absolute end of the byte
    * range actually read (startByte + bytes read). */
  def buildWindow(bytes: Array[Byte], len: Int, endByte: Long, size: Long): Window =
    val text = decode(bytes, len)
    Window(text, lineCountOf(text), endByte >= size)

  def countNewlines(bytes: Array[Byte], len: Int = -1): Long =
    val n = if len >= 0 then math.min(len, bytes.length) else bytes.length
    var i = 0
    var c = 0L
    while i < n do
      if bytes(i) == '\n'.toByte then c += 1
      i += 1
    c

  /** `firstLine` refinement: the anchor's exact line plus the newlines between
    * the anchor and the window start. */
  def firstLineFrom(anchorLine: Long, newlinesInSpan: Long): Long = anchorLine + newlinesInSpan

  /** Build a `SparseIndex` from a whole byte body (used by specs and by any
    * caller that already holds the bytes; the route layer uses `IndexBuilder`
    * and streams the file instead). */
  def indexOf(bytes: Array[Byte], stride: Int = StrideLines): SparseIndex =
    val b = new IndexBuilder(stride)
    b.feed(bytes, bytes.length)
    b.result

  /** Incremental sparse-index builder — O(1) memory, no IO. Anchors land on the
    * line starts of lines 1, 1+stride, 1+2*stride, …; anchors past the end of
    * the file (a file ending exactly on a stride boundary) are dropped, so every
    * anchor names a line that exists. */
  final class IndexBuilder(val stride: Int = StrideLines):
    require(stride > 0, "stride must be positive")
    private val anchors = scala.collection.mutable.ArrayBuffer[Long](0L)
    private var offset = 0L
    private var newlines = 0L
    private var lastByte = -1
    private var seenAny = false

    def feed(chunk: Array[Byte], len: Int): Unit =
      val n = math.min(len, chunk.length)
      var i = 0
      while i < n do
        val b = chunk(i)
        seenAny = true
        lastByte = b & 0xff
        if b == '\n'.toByte then
          newlines += 1
          // the next line (number newlines+1) starts at offset+i+1
          if newlines % stride == 0 then anchors += (offset + i + 1)
        i += 1
      offset += n

    /** Bytes fed so far. */
    def fedBytes: Long = offset

    /** 1-based line count; a trailing newline does not open an extra empty line. */
    def totalLines: Long =
      if !seenAny then 0L
      else newlines + (if lastByte == '\n'.toByte then 0L else 1L)

    def result: SparseIndex =
      val total = totalLines
      val trimmed =
        if total <= 0 then Vector(0L)
        else anchors.toVector.zipWithIndex.takeWhile { case (_, i) => 1L + i.toLong * stride <= total }.map(_._1)
      SparseIndex(stride, if trimmed.isEmpty then Vector(0L) else trimmed, total)

  /** Streaming literal scanner — O(1) memory, no IO, cancellable by the caller
    * (it simply stops feeding).
    *
    * · Literal substring only; NO regex (a pathological backtrack cannot be
    *   priced, card §三.1). v1 scope is deliberate.
    * · Case folding is ASCII-only; non-ASCII bytes compare byte-exact.
    * · Matches may be found in any chunk: up to `needle.length - 1` trailing
    *   bytes are held back and re-examined with the next chunk.
    * · Stops accepting hits at `maxHits`: the FIRST match beyond the cap sets
    *   `truncated` (never a silent cap); a scan that ends exactly AT the cap is
    *   complete, not truncated.
    * · Overlapping matches each count (advance by one byte per hit), so
    *   "aa" in "aaa" is 2 hits.
    * · A hit's echo is its whole line (bounded): emission waits for the line's
    *   terminating newline or the excerpt bound, so the echo is invariant under
    *   chunking; `drainHits` flushes the unterminated tail line (see `Hit`).
    */
  final class LiteralScanner(
    query: String,
    caseSensitive: Boolean = false,
    maxHits: Int = TextStream.DefaultMaxHits
  ):
    require(query.nonEmpty, "query must be non-empty")
    require(maxHits > 0, "maxHits must be positive")

    private val needle: Array[Byte] =
      val q = if caseSensitive then query else query.toLowerCase
      q.getBytes(Utf8)
    require(needle.nonEmpty, "query must not be empty after encoding")

    private val gap = math.max(needle.length - 1, 0)
    private val buf = new Array[Byte](ScanChunkBytes + gap + 8)
    private val prefix = new Array[Byte](MaxHitTextChars + 1)
    private val hits = scala.collection.mutable.ArrayBuffer.empty[Hit]
    private val pendingCols = scala.collection.mutable.ArrayBuffer.empty[Long]
    private var bufLen = 0
    private var base = 0L      // absolute offset of buf(0)
    private var fed = 0L       // absolute bytes accepted from the caller
    private var line = 1L      // 1-based line of the byte at buf(0)… see lineStart
    private var lineStart = 0L // absolute offset where the current line begins
    private var prefixLen = 0
    private var prefixFull = false
    private var total = 0L
    private var truncatedFlag = false

    /** Bytes accepted so far (= how far the scan got). */
    def scannedBytes: Long = fed

    def totalHits: Long = total

    def truncated: Boolean = truncatedFlag

    /** Hits found but not yet drained by the caller. */
    def bufferedHits: Int = hits.length

    private inline def asciiLower(b: Int): Int = if b >= 'A'.toInt && b <= 'Z'.toInt then b + 32 else b

    private def matchesAt(i: Int): Boolean =
      var k = 0
      var ok = true
      while ok && k < needle.length do
        val a = buf(i + k) & 0xff
        val b = needle(k) & 0xff
        ok = if caseSensitive then a == b else asciiLower(a) == asciiLower(b)
        k += 1
      ok

    /** Emit the pending hits of the current line with its (final or partial)
      * echo. Invariant: `pendingCols` is non-empty only while `!prefixFull` —
      * once the excerpt bound is reached the echo is final and hits emit
      * immediately. */
    private def emitPending(continues: Boolean): Unit =
      if pendingCols.nonEmpty then
        val head = new String(prefix, 0, prefixLen, Utf8)
        val text = if continues then head + "…" else head
        val ln = line
        pendingCols.foreach(c => hits += Hit(ln, c, text))
        pendingCols.clear()

    /** Feed one chunk. Once `truncated` is set the scanner ignores further input. */
    def feed(chunk: Array[Byte], len: Int): Unit =
      if truncatedFlag then ()
      else
        val n = math.min(len, chunk.length)
        if n <= 0 then ()
        else
          System.arraycopy(chunk, 0, buf, bufLen, n)
          bufLen += n
          fed += n
          // positions i with i + needle.length <= bufLen are complete; the rest
          // stay buffered for the next chunk
          val processLen = math.max(0, bufLen - needle.length + 1)
          var i = 0
          while i < processLen && !truncatedFlag do
            if matchesAt(i) then
              if total >= maxHits then truncatedFlag = true // first hit beyond the cap
              else
                total += 1
                pendingCols += base + i - lineStart + 1 // 1-based byte column
                if prefixFull then emitPending(continues = true) // echo already final
            // line bookkeeping AFTER the match so a match is attributed to the
            // line it starts on
            val b = buf(i)
            if b == '\n'.toByte then
              emitPending(continues = prefixFull) // the line's echo is now final
              line += 1
              lineStart = base + i + 1
              prefixLen = 0
              prefixFull = false
            else if !prefixFull then
              if prefixLen < MaxHitTextChars then
                prefix(prefixLen) = b
                prefixLen += 1
              else
                prefixFull = true
                emitPending(continues = true) // bounded excerpt is final from here on
            i += 1
          // hold back the tail that a next chunk could complete
          val keep = bufLen - processLen
          if keep > 0 then System.arraycopy(buf, processLen, buf, 0, keep)
          bufLen = keep
          base += processLen

    /** Take the hits accumulated since the last drain (frame batching). Hits of
      * the current unterminated line are flushed with the bytes seen so far —
      * at the caller's final (EOF) drain that is the complete line. */
    def drainHits(): Vector[Hit] =
      emitPending(continues = false)
      val out = hits.toVector
      hits.clear()
      out

    /** Terminal state of the scan so far. */
    def outcome: SearchOutcome = SearchOutcome(fed, total, truncatedFlag)

  // ── Bounded index cache (pure LRU over an immutable vector) ───────────────

  /** Look up `key`, returning the value and the refreshed (most-recent-last) entry
    * list. A miss changes nothing. */
  def lruGet[V](entries: Vector[(IndexKey, V)], key: IndexKey): (Option[V], Vector[(IndexKey, V)]) =
    entries.indexWhere(_._1 == key) match
      case -1 => (None, entries)
      case i  => (Some(entries(i)._2), entries.patch(i, Nil, 1) :+ entries(i))

  /** Insert (or refresh) `key`, evicting the least recently used entry beyond
    * `max`. */
  def lruPut[V](
    entries: Vector[(IndexKey, V)],
    key: IndexKey,
    value: V,
    max: Int = MaxCachedIndexes
  ): Vector[(IndexKey, V)] =
    val without = entries.filterNot(_._1 == key)
    val grown = without :+ (key -> value)
    if grown.length <= math.max(max, 1) then grown else grown.drop(grown.length - math.max(max, 1))

end TextStream
