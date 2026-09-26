package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

/**
 * The text-stream engine behind the WS `textWindow` / `textIndex` /
 * `textSearch` / `textCancel` legs (design card §三.4/§三.5).
 *
 * Extracted verbatim from `WebSocketRoutes` (state + methods moved together,
 * zero behavior change): owns the per-request cancellation flags, the
 * concurrent-scan cap, the sparse-index LRU and the in-flight index-build
 * gates. One instance per `WebSocketRoutes` — same lifecycle as before.
 *
 * The render-path switch: text files ABOVE `TextStream.ThresholdBytes` (8MiB,
 * server single point of truth) open as a read-only virtual-scroll view fed by
 * on-demand windows instead of one whole `content` frame. NOT a second open
 * gate — `MaxPopReadFileBytes` below stays the only size limit on this path.
 */
final class TextStreamSearch:

  /**
   * Per-request cancellation flags. `textCancel` flips the flag; the index
   * builder and the scan loop check it between chunks (cooperative cancel, so a
   * cancelled stream stops at the next 256KiB boundary).
   */
  private val textStreamCancels: Ref[IO, Map[String, Ref[IO, Boolean]]] = Ref.unsafe(Map.empty)

  /**
   * In-flight scan count, capped at `TextStream.MaxConcurrentScans` — the pull
   * protocol already limits one window per tab, this bounds the server side when
   * several tabs search at once.
   */
  private val textStreamScans: Ref[IO, Int] = Ref.unsafe(0)

  /**
   * Sparse-index LRU, bounded to `TextStream.MaxCachedIndexes` entries and keyed
   * by (path, size, mtimeMs): `size`/`mtimeMs` changing IS the invalidation.
   */
  private val textStreamIndexes: Ref[IO, Vector[(TextStream.IndexKey, TextStream.SparseIndex)]] =
    Ref.unsafe(Vector.empty)

  /**
   * In-flight index builds, keyed by the same (path, size, mtimeMs). Without
   * this, the window request that needs `firstLine` and the parallel `textIndex`
   * request of the same open would both scan a 100MiB file.
   */
  private val textStreamIndexBuilds
    : Ref[IO, Map[TextStream.IndexKey, Deferred[IO, Either[Throwable, TextStream.SparseIndex]]]] =
    Ref.unsafe(Map.empty)

  /**
   * Read [start, start+count) without ever holding the whole file: one bounded
   * array + a positional channel read. Short reads (EOF) are returned as-is.
   */
  def readRangeBytes(p: os.Path, start: Long, count: Int): IO[Array[Byte]] =
    IO.blocking {
      val bb = java.nio.ByteBuffer.allocate(math.max(count, 0))
      val ch = java.nio.channels.FileChannel.open(p.toNIO, java.nio.file.StandardOpenOption.READ)
      try
        var pos = start
        var done = false
        while !done && bb.hasRemaining do
          val n = ch.read(bb, pos)
          if n <= 0 then done = true else pos += n
        bb.flip()
        val out = new Array[Byte](bb.remaining())
        bb.get(out)
        out
      finally ch.close()
    }

  /**
   * Count newlines in [from, to) chunk by chunk — the `firstLine` refinement for
   * a window that does not start on an index anchor. Memory stays O(chunk); the
   * span is bounded by the distance from the nearest stride anchor, which for
   * line-aligned requests is one window or less.
   */
  private def countNewlinesBetween(p: os.Path, from: Long, to: Long): IO[Long] =
    def loop(offset: Long, acc: Long): IO[Long] =
      if offset >= to then IO.pure(acc)
      else
        val n = math.min(TextStream.ScanChunkBytes.toLong, to - offset).toInt
        readRangeBytes(p, offset, n).flatMap { chunk =>
          loop(offset + chunk.length, acc + TextStream.countNewlines(chunk, chunk.length))
        }
    loop(from, 0L)

  /**
   * The text-stream legs' shared admission ruling — deliberately the SAME
   * reachable surface as today's text leg (`pop.readFile`): absolute path,
   * exists, regular file, ≤ the 100MB open gate. No credential-namespace check
   * and no extension whitelist, so streaming opens NO new surface (card §二.3 ②).
   * Binary files are refused: they have their own byte leg.
   */
  def resolveStreamPath(rawPath: String): IO[os.Path] =
    for
      _ <- IO.raiseUnless(PathUtil.isAbsolute(rawPath))(new RuntimeException("path must be absolute"))
      p = PathUtil.resolvePath(rawPath)
      _ <- IO.raiseUnless(os.exists(p))(new RuntimeException(s"file not found: $rawPath"))
      _ <- IO.raiseUnless(os.isFile(p))(new RuntimeException("path is not a regular file"))
      size <- IO.blocking(os.size(p))
      _ <- IO.raiseWhen(size > TextStreamSearch.MaxPopReadFileBytes)(
        new RuntimeException(s"file exceeds ${TextStreamSearch.MaxPopReadFileBytes / (1024 * 1024)}MB limit")
      )
      ext = rawPath.split('.').lastOption.getOrElse("").toLowerCase
      _ <- IO.raiseWhen(nebflow.core.workspace.FileTypeRegistry.detect(ext).binary)(
        new RuntimeException("not a text file")
      )
    yield p

  private def textStreamCancelled(reqId: String): IO[Boolean] =
    if reqId.isEmpty then IO.pure(false)
    else
      textStreamCancels.get.flatMap { m =>
        m.get(reqId) match
          case Some(flag) => flag.get
          case None => IO.pure(false)
      }

  /** Scan a file chunk by chunk into a sparse index. Cancellable between chunks. */
  private def buildTextIndex(p: os.Path, size: Long, reqId: String): IO[TextStream.SparseIndex] =
    val builder = new TextStream.IndexBuilder()
    def loop(offset: Long): IO[TextStream.SparseIndex] =
      textStreamCancelled(reqId).flatMap { stop =>
        if stop || offset >= size then IO.pure(builder.result)
        else
          val n = math.min(TextStream.ScanChunkBytes.toLong, size - offset).toInt
          readRangeBytes(p, offset, n).flatMap { chunk =>
            builder.feed(chunk, chunk.length)
            loop(offset + chunk.length)
          }
      }
    loop(0L)

  /** Cached index for (path, size, mtimeMs), de-duplicating concurrent builds. */
  def textIndexFor(p: os.Path, size: Long, mtimeMs: Long, reqId: String): IO[TextStream.SparseIndex] =
    val key = TextStream.IndexKey(p.toString, size, mtimeMs)
    textStreamIndexes.get.flatMap { entries =>
      TextStream.lruGet(entries, key)._1 match
        case Some(idx) => textStreamIndexes.update(es => TextStream.lruPut(es, key, idx)).as(idx)
        case None =>
          Deferred[IO, Either[Throwable, TextStream.SparseIndex]].flatMap { gate =>
            type Gate = Deferred[IO, Either[Throwable, TextStream.SparseIndex]]
            textStreamIndexBuilds
              .modify[Either[Gate, Gate]] { m =>
                m.get(key) match
                  case Some(existing) => (m, Left(existing))
                  case None => (m + (key -> gate), Right(gate))
              }
              .flatMap {
                case Left(existing) => existing.get.rethrow
                case Right(mine) =>
                  buildTextIndex(p, size, reqId)
                    .flatTap(idx => textStreamIndexes.update(es => TextStream.lruPut(es, key, idx)))
                    .attempt
                    .flatTap(res => mine.complete(res))
                    .rethrow
                    .onCancel(mine.complete(Left(new RuntimeException("index build was cancelled"))).void)
                    .guarantee(textStreamIndexBuilds.update(_ - key))
              }
          }
    }

  end textIndexFor

  /**
   * Exact line number of the first line in a window starting at `startByte`:
   * the index anchor gives the line exactly when the request is anchor-aligned
   * (jump case, O(1)); otherwise the residual span is counted.
   */
  def textFirstLine(p: os.Path, index: TextStream.SparseIndex, startByte: Long): IO[Long] =
    val (anchorOffset, anchorLine) = index.anchorFor(startByte)
    if anchorOffset >= startByte then IO.pure(anchorLine)
    else countNewlinesBetween(p, anchorOffset, startByte).map(n => TextStream.firstLineFrom(anchorLine, n))

  def acquireScanSlot: IO[Boolean] =
    textStreamScans.modify(n => if n < TextStream.MaxConcurrentScans then (n + 1, true) else (n, false))

  def releaseScanSlot: IO[Unit] = textStreamScans.update(n => math.max(0, n - 1))

  /**
   * Allocate the per-request cancel flag and register it (textSearch leg).
   * `cancel` flips it; the scan loop checks it between chunks.
   */
  def registerCancel(reqId: String): IO[Ref[IO, Boolean]] =
    for
      flag <- Ref[IO].of(false)
      _ <- textStreamCancels.update(_ + (reqId -> flag))
    yield flag

  /** Drop the per-request cancel flag (scan finished or failed). */
  def unregisterCancel(reqId: String): IO[Unit] = textStreamCancels.update(_ - reqId)

  /**
   * Flip a registered request's cancel flag; unknown ids are no-ops
   * (textCancel leg).
   */
  def cancel(reqId: String): IO[Unit] =
    textStreamCancels.get.flatMap { m =>
      m.get(reqId) match
        case Some(flag) => flag.set(true)
        case None => IO.unit
    }

  private def hitsJson(hits: Vector[TextStream.Hit]): Json =
    Json.arr(
      hits.map(h =>
        Json.obj("line" -> Json.fromLong(h.line), "col" -> Json.fromLong(h.col), "text" -> Json.fromString(h.text))
      )*
    )

  /**
   * Stream the literal scan in `ScanChunkBytes` reads, emitting `textSearchHit`
   * frames of ≤ `TextStream.HitsPerFrame` hits. Stops on cancel, on EOF or on the
   * hit cap — every stop is reported as `truncated` (never a silent partial).
   */
  def runTextSearch(
    wsSend: Json => IO[Unit],
    reqId: String,
    tabId: String,
    p: os.Path,
    size: Long,
    scanner: TextStream.LiteralScanner,
    flag: Ref[IO, Boolean]
  ): IO[TextStream.SearchOutcome] =
    def sendBatch(batch: Vector[TextStream.Hit]): IO[Unit] =
      wsSend(
        Json.obj(
          "type" -> "textSearchHit".asJson,
          "reqId" -> reqId.asJson,
          "tabId" -> tabId.asJson,
          "hits" -> hitsJson(batch)
        )
      )
    def flush(hits: Vector[TextStream.Hit]): IO[Unit] =
      hits.grouped(TextStream.HitsPerFrame).toVector.foldLeft(IO.unit)((acc, b) => acc *> sendBatch(b))
    def loop(offset: Long): IO[TextStream.SearchOutcome] =
      flag.get.flatMap { stop =>
        if stop || offset >= size || scanner.truncated then
          IO(scanner.finish()) *> flush(scanner.drainHits())
            .as(TextStream.SearchOutcome(scanner.scannedBytes, scanner.totalHits, stop || scanner.truncated))
        else
          val n = math.min(TextStream.ScanChunkBytes.toLong, size - offset).toInt
          readRangeBytes(p, offset, n).flatMap { chunk =>
            scanner.feed(chunk, chunk.length)
            flush(scanner.drainHits()) *> loop(offset + chunk.length)
          }
      }
    loop(0L)
  end runTextSearch

end TextStreamSearch

object TextStreamSearch:

  /**
   * The Canvas / file-viewer OPEN gate — the largest file the WS `pop.readFile`
   * leg will serve (2026-09-20 author order: 10MB → 100MB; the author could not
   * open a PDF over 10MB). It is the ONE ruler for the open path on the server
   * side: the frontend pre-check (`web/js/attachmentPreview.js` `MAX_TEXT_BYTES`)
   * mirrors this value, so the two cannot drift.
   *
   * NOT the WS frame cap (`WebSocketRoutes.MaxMessageSize`, inbound), nor
   * `FileRefs.MaxFileSize` (200MB, the Card/Pop reference probe), nor the
   * inline budgets (5MB image / 40k `data:` URI) — those are other surfaces.
   *
   * Cost note: this gate bounds the WS TEXT-content leg only in size, not in
   * streaming (a WS frame carries one whole string). The BINARY leg does not
   * read the bytes at all — it sends metadata and the viewer streams the file
   * from `GET /api/nf-file` (http4s `StaticFile` ⇒ `fs2.io.file.Files.readRange`),
   * so a 100MB PDF never enters this JVM's heap (see the `pop.readFile` case).
   */
  val MaxPopReadFileBytes: Long = 100L * 1024 * 1024

end TextStreamSearch
