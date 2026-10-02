package nebflow.social.outbound

import cats.effect.IO
import nebflow.shared.NebflowLogger

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
 * Server-side real rendering for outbound rich content (richcontent batch,
 * 2026-09-30).
 *
 * Route decision is NOT taken here: the design card (`socoutbound-design`
 * §3.6) already fixed it — **A (system Chrome headless subprocess) + D (PNG/JPG
 * passthrough, no render at all) + E (PDF stays the original file)**. This file
 * implements A and the D-side helper [[RichRenderer.isRasterPassthrough]]; E
 * needs no render (a PDF document is sent as the original file).
 *
 * The hard constraints the design card fixes (HC-1…HC-5, §3.6) are carried as
 * executable judgements rather than prose:
 *
 *   - HC-1 real browser, real process: the only production implementation spawns
 *     the system Chrome binary with `--headless --screenshot`. There is no
 *     synthesis path, no placeholder image, no `.svg` renamed `.png`.
 *   - HC-2 foreground and bounded: an explicit wall-clock budget plus
 *     `destroyForcibly`, and **no child is ever left running**. No `nohup`, no
 *     `&`.
 *   - HC-3 success judgement = the `\x89PNG` magic AND declared dimensions AND
 *     more than a handful of distinct pixel colours (a solid colour fails). Any
 *     miss is a RENDER FAILURE and flows into §7.1.
 *   - HC-4 output lands under the caller-supplied temporary face, never in the
 *     repository.
 *   - HC-5 when no renderer is present (a host without Chrome) the leg must
 *     DEGRADE EXPLICITLY — a warn line plus a `[未渲染]` placeholder — and must
 *     never silently pretend a text-only send was the card.
 *
 * 🔴 Measured host behaviour that shapes [[RichRenderer.ChromeRenderer]]: Chrome
 * 154 on this machine writes a VALID screenshot and then **never exits** in
 * headless mode — verified with five flag variants (`--headless`,
 * `--headless=old`, `--headless=new`, `--virtual-time-budget`, plus a
 * `--no-sandbox --disable-software-rasterizer --disable-dev-shm-usage` set); in
 * every case the product was on disk within a second and the process was still
 * alive at 30–60 s. Waiting for process exit would therefore force-kill a
 * perfectly good render and report a false failure. So the render waits on the
 * PRODUCT (appearance + size stability), and the child is unconditionally reaped
 * in a `finally`. That is what makes the leg bounded without discarding real
 * output.
 */
object RichRenderer:

  private val logger = NebflowLogger.forName("nebflow.social.outbound.render")

  /** HC-2: the bounded wall clock for one render. Measured on this host: the
    *  product is on disk in well under a second, so 30 s is ~30× headroom. The
    *  budget bounds the PRODUCT wait, not the (never-arriving) process exit. */
  val DefaultTimeoutMs: Long = 30000L

  /** How long the product's size must hold still before it is treated as
    *  complete. Chrome writes the file in one pass, so one quiet interval is
    *  enough; the guard exists so a partially-written file is never judged. */
  val StableWindowMs: Long = 150L

  /** HC-3: the content floor, over DISTINCT DECODED PIXEL COLOURS (not file
    *  bytes — see [[RichRenderer.inspect]] for why the byte-statistics version
    *  does not work). The design card's probe produced a full-colour page; a
    *  single-colour bitmap produces exactly 1. The threshold is deliberately tiny
    *  (any real content clears it) so it cannot be mistaken for a quality score —
    *  it only rejects empty/solid output. */
  val MinDistinctPixels: Int = 5

  /** Outcome of a render attempt. Both branches are readings; a failure is never
    *  thrown past this boundary — the caller needs to degrade, not to crash a
    *  turn.
    *
    * `reason` carries a value from the CLOSED vocabulary of design card §7.1
    * (see [[RichDegrade.Reason]]), because that string is what the recipient
    * sees; `detail` carries the technical explanation, which goes to the log.
    * Keeping them separate is what lets the user-visible line stay matchable
    * while the operator still gets the specifics. */
  final case class RenderResult(
      ok: Boolean,
      path: Option[os.Path],
      bytes: Long,
      width: Int,
      height: Int,
      reason: String,
      detail: String
  ):
    def toJson: io.circe.Json =
      io.circe.Json.obj(
        "ok" -> io.circe.Json.fromBoolean(ok),
        "path" -> path.map(p => io.circe.Json.fromString(p.toString)).getOrElse(io.circe.Json.Null),
        "bytes" -> io.circe.Json.fromLong(bytes),
        "width" -> io.circe.Json.fromInt(width),
        "height" -> io.circe.Json.fromInt(height),
        "reason" -> io.circe.Json.fromString(reason),
        "detail" -> io.circe.Json.fromString(detail)
      )

  object RenderResult:
    /** A failure with the closed-vocabulary reason and the technical detail. */
    def failed(reason: String, detail: String): RenderResult =
      RenderResult(false, None, 0L, 0, 0, reason, detail)

    /** Failures whose reason is not specifically a timeout or a bad product —
      *  i.e. "the engine could not do it on this host". */
    def engineUnavailable(detail: String): RenderResult =
      failed(RichDegrade.Reason.EngineUnavailable, detail)

    def timedOut(detail: String): RenderResult =
      failed(RichDegrade.Reason.RenderTimeout, detail)

    /** The product exists but failed HC-3. */
    def invalidProduct(path: os.Path, bytes: Long, w: Int, h: Int, detail: String): RenderResult =
      RenderResult(false, Some(path), bytes, w, h, RichDegrade.Reason.RenderInvalid, detail)

  /** A refusal that is ALSO logged. The log line is a signal, never a substitute
    *  for the reading handed back: design card §7.3 #4 forbids the three swallow
    *  idioms (`case _ => ()`, `case _ => IO.unit`, "only `logger.warn`"), and
    *  this helper exists so no call site is tempted into one. The line carries a
    *  path and a reason, zero credentials. */
  private def logged(r: RenderResult): RenderResult =
    // `warnSync` (not `warn`): this helper is called from synchronous contexts, and
    // `NebflowLogger.warn` returns an `IO[Unit]` — a bare `logger.warn(...)`
    // statement evaluates to that uncalled IO and the line never reaches the log.
    logger.warnSync(s"outbound render failed path=${r.path.map(_.toString).getOrElse("<none>")} reason=${r.reason} detail=${r.detail}")
    r

  /**
   * The rendering seam. Production = [[ChromeRenderer]]; the offline spec injects
   * stubs so no test starts a browser or touches the network.
   */
  trait Renderer:
    def render(html: String, outPng: os.Path, width: Int, height: Int): IO[RenderResult]

    /** Whether this renderer can actually run here. `false` drives HC-5. */
    def available: Boolean

  /** The injected "no renderer on this host" implementation (HC-5). */
  object Unavailable extends Renderer:
    def available: Boolean = false
    def render(html: String, outPng: os.Path, width: Int, height: Int): IO[RenderResult] =
      IO.pure(RenderResult.engineUnavailable("rendering engine unavailable on this host"))

  /**
   * Route A — the system Chrome binary, `--headless --screenshot`.
   *
   * Honours the design card's requirement to reuse the existing detection logic:
   * the candidate list below is the same three locations the in-repo
   * `nebflow.shared.PlaywrightFetcher.detectChannel` probes
   * (`PlaywrightFetcher.scala:49-60`). That helper is `private` to its object and
   * answers `"chrome"` for Playwright rather than a binary path, so it is
   * mirrored — not re-derived — here; the mirror is reported as an open item.
   */
  final class ChromeRenderer(
      binary: Option[os.Path] = ChromeRenderer.detect,
      timeoutMs: Long = DefaultTimeoutMs,
      stableWindowMs: Long = StableWindowMs
  ) extends Renderer:

    def available: Boolean = binary.exists(p => Files.isExecutable(p.toNIO))

    def render(html: String, outPng: os.Path, width: Int, height: Int): IO[RenderResult] =
      binary match
        case None =>
          IO.pure(logged(RenderResult.engineUnavailable("no Chrome binary detected on this host")))
        case Some(bin) if !Files.isExecutable(bin.toNIO) =>
          IO.pure(logged(RenderResult.engineUnavailable(s"$bin is not executable")))
        case Some(bin) =>
          IO.blocking {
            val tmpHtml = Files.createTempFile("nb-outbound-render-", ".html")
            // HC-4 + D-3: Chrome insists on a profile directory, and left to its
            // own devices it would create one under the operator's real HOME. It
            // is pinned NEXT TO the product — inside the caller-supplied
            // temporary face — and removed in the `finally`. A render leg
            // therefore writes nothing into the real home.
            Files.createDirectories(outPng.toNIO.getParent)
            val profileDir = Files.createTempDirectory(outPng.toNIO.getParent, "nb-chrome-profile-")
            var proc: Process = null
            try
              Files.writeString(tmpHtml, html)
              // A stale file from an earlier render must never be mistaken for
              // this render's product.
              Files.deleteIfExists(outPng.toNIO)

              val cmd = List(
                bin.toString,
                "--headless",
                "--disable-gpu",
                "--hide-scrollbars",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-background-networking",
                s"--user-data-dir=$profileDir",
                s"--window-size=$width,$height",
                s"--screenshot=${outPng.toString}",
                s"file://$tmpHtml"
              )
              val pb = new ProcessBuilder(cmd.asJava)
              pb.redirectErrorStream(true)
              // 🔴 The child's chatter is DISCARDED, and this is load-bearing:
              // draining the stream with `readAllBytes()` blocks until the child
              // closes it, and Chrome (measured above) never exits — so draining
              // would hang the leg until the timeout and then force-kill a render
              // that had in fact succeeded. Not reading it at all keeps the wait
              // bounded by the clock rather than by Chrome's lifetime.
              pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)

              val started = System.nanoTime()
              proc = pb.start()

              // Wait on the PRODUCT: it must appear and then hold its size for a
              // whole stable window. See the file header for why process exit is
              // not the signal.
              val deadlineNanos = started + timeoutMs * 1000000L
              var lastSize = -1L
              var stable = false
              while !stable && System.nanoTime() < deadlineNanos do
                Thread.sleep(stableWindowMs)
                if Files.exists(outPng.toNIO) then
                  val sz = Files.size(outPng.toNIO)
                  if sz > 0L && sz == lastSize then stable = true else lastSize = sz
              val elapsedMs = (System.nanoTime() - started) / 1000000L

              if !stable then
                logged(RenderResult.timedOut(
                  s"no stable render product within ${timeoutMs}ms (last size ${if lastSize < 0 then "absent" else s"$lastSize bytes"})"))
              else
                inspect(outPng, width, height) match
                  case Right(r)  => r.copy(detail = s"${r.detail} (stable after ${elapsedMs}ms)")
                  case Left(err) => logged(err)
            catch
              case e: Throwable =>
                logged(RenderResult.engineUnavailable(
                  s"render leg threw ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))
            finally
              // 🔴 The single point that guarantees no browser outlives this call.
              // Chrome does not exit on its own here, so the child is reaped
              // unconditionally whether we succeeded, timed out, or threw.
              if proc != null then
                if proc.isAlive then proc.destroyForcibly()
                try proc.waitFor(5, TimeUnit.SECONDS) catch case _: InterruptedException => ()
              Files.deleteIfExists(tmpHtml)
              deleteRecursively(profileDir)
          }
          // Belt for the blocking wrapper: an interruption must still surface as a
          // reading rather than as a failed turn.
          .handleErrorWith(e =>
            IO.pure(logged(RenderResult.engineUnavailable(
              s"render leg aborted: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))))

  object ChromeRenderer:

    /** Mirrors `PlaywrightFetcher.detectChannel:49-60` candidate locations. */
    def detect: Option[os.Path] =
      val osName = System.getProperty("os.name").toLowerCase
      val candidates: List[os.Path] =
        if osName.contains("mac") then
          List(
            os.Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", os.pwd),
            os.Path("/Applications/Chromium.app/Contents/MacOS/Chromium", os.pwd)
          )
        else if osName.contains("win") then
          val pf = Option(System.getenv("ProgramFiles")).getOrElse("C:\\Program Files")
          List(os.Path(s"$pf\\Google\\Chrome\\Application\\chrome.exe", os.pwd))
        else
          List(
            os.Path("/usr/bin/google-chrome", os.pwd),
            os.Path("/usr/bin/google-chrome-stable", os.pwd),
            os.Path("/usr/bin/chromium", os.pwd),
            os.Path("/usr/bin/chromium-browser", os.pwd)
          )
      candidates.find(p => Files.isExecutable(p.toNIO))

  /** Best-effort recursive delete for the per-render Chrome profile. Bounded and
    *  silent on failure: the directory lives in the caller's temporary face, so a
    *  leftover is a tidiness issue (the caller's TTL sweeper collects it), never a
    *  lease on a live process. */
  private def deleteRecursively(dir: Path): Unit =
    try
      if Files.exists(dir) then
        val stream = Files.walk(dir)
        try
          val it = stream.sorted(java.util.Comparator.reverseOrder()).iterator()
          while it.hasNext do
            try Files.deleteIfExists(it.next()) catch case _: java.io.IOException => ()
        finally stream.close()
    catch case _: java.io.IOException => ()

  /**
   * HC-3, the success judgement, as a reading over a produced file: PNG magic +
   * declared dimensions + a content floor. Shared by the production renderer and
   * the spec so both sides judge identically — a spec that judged differently
   * from production would prove nothing.
   *
   * 🔴 The content floor is measured on DECODED PIXELS, not on the file's byte
   * statistics. Counting distinct bytes in the file does not work: PNG compresses
   * its rows, so even a perfectly uniform image yields a large stream of varying
   * filter and entropy bytes — measured here, a 32×24 pure-white PNG carries well
   * over the old floor as distinct byte values and sailed past it.
   * The judgement therefore decodes the image and counts distinct RGB values.
   *
   * A file that cannot be decoded at all is a render failure, not a pass.
   */
  def inspect(p: os.Path, expectedWidth: Int, expectedHeight: Int): Either[RenderResult, RenderResult] =
    if !Files.exists(p.toNIO) then Left(RenderResult.engineUnavailable(s"no render product at $p"))
    else
      val bytes = Files.readAllBytes(p.toNIO)
      val size = bytes.length.toLong
      val magicOk = bytes.length >= 8 &&
        (bytes(0) & 0xff) == 0x89 && bytes(1) == 'P'.toByte && bytes(2) == 'N'.toByte && bytes(3) == 'G'.toByte
      if !magicOk then
        Left(RenderResult.invalidProduct(p, size, 0, 0,
          "render output is not a PNG (the first four bytes are not \\x89PNG)"))
      else
        val (w, h) = pngSize(bytes)
        if w != expectedWidth || h != expectedHeight then
          Left(RenderResult.invalidProduct(p, size, w, h,
            s"render output geometry $w×$h does not match the declared ${expectedWidth}×${expectedHeight}"))
        else
          distinctPixels(p) match
            case None =>
              Left(RenderResult.invalidProduct(p, size, w, h,
                "render output carries a PNG header but could not be decoded as an image"))
            case Some(distinct) if distinct <= MinDistinctPixels =>
              Left(RenderResult.invalidProduct(p, size, w, h,
                s"render output is empty/solid: $distinct distinct pixel colour(s), floor is > $MinDistinctPixels"))
            case Some(distinct) =>
              Right(RenderResult(true, Some(p), size, w, h, "",
                s"PNG $w×$h, $size bytes, $distinct distinct pixel colours"))

  /** Distinct RGB values in a decoded image, or `None` if it will not decode. */
  private def distinctPixels(p: os.Path): Option[Int] =
    try
      val img = javax.imageio.ImageIO.read(p.toNIO.toFile)
      if img == null then None
      else
        val seen = scala.collection.mutable.HashSet.empty[Int]
        val w = img.getWidth
        val h = img.getHeight
        var y = 0
        while y < h && seen.size <= MinDistinctPixels + 1 do
          var x = 0
          while x < w && seen.size <= MinDistinctPixels + 1 do
            seen += (img.getRGB(x, y) & 0x00ffffff)
            x += 1
          y += 1
        Some(seen.size)
    catch case _: Throwable => None

  /** IHDR width/height, big-endian at offsets 16..24 of a PNG. */
  private def pngSize(b: Array[Byte]): (Int, Int) =
    def be32(o: Int): Int =
      ((b(o) & 0xff) << 24) | ((b(o + 1) & 0xff) << 16) | ((b(o + 2) & 0xff) << 8) | (b(o + 3) & 0xff)
    (be32(16), be32(20))

  /**
   * Route D: when the product IS already a raster image (PNG/JPG), it is sent
   * as-is. Handing those bytes to a renderer would be pure loss — and the "no
   * mock image" rule means a real image must never be replaced by a synthesized
   * one. Reuses `nebflow.core.tools.ImageInject.imageExtensions`, so "is this a
   * raster image" has exactly one answer in this repo.
   */
  def isRasterPassthrough(path: String): Boolean =
    val lower = path.toLowerCase
    nebflow.core.tools.ImageInject.imageExtensions.keys.exists(lower.endsWith)

end RichRenderer
