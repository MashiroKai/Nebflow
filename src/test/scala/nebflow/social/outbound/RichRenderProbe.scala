package nebflow.social.outbound

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

/**
 * Manual live probe for the rich-content render leg (richcontent batch,
 * 2026-09-30). NOT a test and NOT part of `sbt test`'s discovery: it is a minimal
 * `main` invoked by the batch's manual evidence step, living in Test scope so
 * none of it reaches the shipped artifact (same shape as
 * [[nebflow.social.FeishuLiveProbe]]).
 *
 * Why a separate `main` rather than `sbt run`: `sbt run` boots the whole Nebflow
 * application (gateway ports, host data root). The batch forbids that, so this
 * entry point does exactly one thing and owns its own lifecycle:
 *
 *   · the Chrome child runs in the FOREGROUND under an explicit bound and is
 *     force-killed and reaped before this method returns — no `nohup`, no `&`, no
 *     resident process (HC-2);
 *   · the temporary HTML page and the per-render Chrome profile are deleted in a
 *     `finally`;
 *   · the last statement is `System.exit(0)` — the ≤120 s self-exit obligation.
 *     A returned-from `main` is not enough once a browser has been spawned, and
 *     on this host Chrome does not exit on its own (see [[RichRenderer]]), so the
 *     probe both reaps the child and exits explicitly;
 *   · the product lands under the caller-supplied isolated face, never in the repo
 *     (HC-4) and never in the real HOME (D-3);
 *   · no credential is involved anywhere: this probe never touches the Feishu
 *     SDK, so there is nothing to mask.
 *
 * 🔴 D-3 isolation precheck: this probe REFUSES to run unless the caller passes an
 * isolated home face explicitly, and records that face plus the real HOME before
 * any browser starts, so a render leg can never write into the operator's real
 * home unnoticed.
 *
 * Modes:
 *   render <isolatedHomeDir>   — render one card page and measure the product
 */
object RichRenderProbe:

  private def now(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS))

  private def write(dir: File, name: String, text: String): Unit =
    val w = new PrintWriter(new File(dir, name), "UTF-8")
    try w.println(text)
    finally w.close()

  private def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")

  /** The card page. Real markup with real CSS so the screenshot has real
    *  structure — a blank page would be rejected by HC-3's distinct-byte floor,
    *  which is exactly why a substantive page is used here. */
  private val CardHtml: String =
    """<!doctype html>
      |<html><head><meta charset="utf-8"><title>nebflow rich outbound probe</title>
      |<style>
      |  body { margin:0; font-family: -apple-system, "Helvetica Neue", Arial, sans-serif; background:#0f172a; }
      |  .card { padding:24px; color:#e2e8f0; }
      |  h1 { font-size:28px; margin:0 0 12px 0; }
      |  .row { display:flex; gap:8px; }
      |  .pill { background:#1d4ed8; color:#fff; padding:6px 12px; border-radius:999px; font-size:13px; }
      |  .grid { margin-top:16px; display:grid; grid-template-columns:repeat(4,1fr); gap:8px; }
      |  .cell { height:48px; border-radius:6px; }
      |</style></head>
      |<body><div class="card">
      |  <h1>Rich outbound render</h1>
      |  <div class="row"><span class="pill">image</span><span class="pill">document</span><span class="pill">card</span></div>
      |  <div class="grid">
      |    <div class="cell" style="background:#ef4444"></div>
      |    <div class="cell" style="background:#22c55e"></div>
      |    <div class="cell" style="background:#eab308"></div>
      |    <div class="cell" style="background:#a855f7"></div>
      |    <div class="cell" style="background:#06b6d4"></div>
      |    <div class="cell" style="background:#f97316"></div>
      |    <div class="cell" style="background:#84cc16"></div>
      |    <div class="cell" style="background:#ec4899"></div>
      |  </div>
      |</div></body></html>""".stripMargin

  /** Count live Chrome processes — the liveness reading for the teardown claim. */
  private def chromeProcs(): Int =
    try
      val pb = new ProcessBuilder(List("pgrep", "-f", "Google Chrome").asJava)
      pb.redirectErrorStream(true)
      val p = pb.start()
      val out = new String(p.getInputStream.readAllBytes(), "UTF-8").trim
      if p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) then
        if out.isEmpty then 0 else out.lines().count().toInt
      else
        p.destroyForcibly(); -1
    catch case _: Throwable => -1

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("help")
    if mode != "render" || args.length < 2 then
      println("usage: RichRenderProbe render <isolatedHomeDir>")
      System.exit(2)
    val homeDir = new File(args(1))
    if !homeDir.isDirectory then
      println(s"[probe] REFUSING: isolated home face '${args(1)}' is not a directory (D-3 precheck)")
      System.exit(2)

    val evidenceDir = new File(
      Option(System.getProperty("nebflow.rich.evidence")).getOrElse(".")
    )
    evidenceDir.mkdirs()

    // ── D-3 isolation precheck, recorded BEFORE anything is spawned ──
    val realHome = Option(System.getenv("HOME")).getOrElse("<unset>")
    val outDir = new File(homeDir, "tmp/outbound-render")
    outDir.mkdirs()
    write(evidenceDir, "render-precheck.json",
      s"""{
         |  "ts": "${now()}",
         |  "isolatedHome": "${esc(homeDir.getAbsolutePath)}",
         |  "realHomeEnv": "${esc(realHome)}",
         |  "isolatedDiffersFromRealHome": ${homeDir.getAbsolutePath != realHome},
         |  "javaHome": "${esc(Option(System.getenv("JAVA_HOME")).getOrElse("<unset>"))}",
         |  "outDir": "${esc(outDir.getAbsolutePath)}"
         |}""".stripMargin)
    println(s"[probe] D-3 PRECHECK isolatedHome=${homeDir.getAbsolutePath} realHome=$realHome " +
      s"differs=${homeDir.getAbsolutePath != realHome}")

    val before = chromeProcs()
    val started = System.currentTimeMillis()
    var exitCode = 0

    val renderer = new RichRenderer.ChromeRenderer()
    val available = renderer.available
    val detected = RichRenderer.ChromeRenderer.detect

    if !available then
      write(evidenceDir, "render-reading.json",
        s"""{"ts":"${now()}","mode":"render","renderOk":false,"detail":"no Chrome binary detected on this host"}""")
      println("[probe] NO-CHROME: no render product can be produced on this host")
      System.exit(1)

    println(s"[probe] renderer=ChromeRenderer available=$available binary=${detected.map(_.toString).getOrElse("<none>")}")

    val outPng = os.Path(new File(outDir, "card-probe.png").toPath, os.pwd)
    try
      val r = renderer.render(CardHtml, outPng, RichFeishuAdapter.DefaultWidth, RichFeishuAdapter.DefaultHeight)
        .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      val elapsed = System.currentTimeMillis() - started
      println(s"[probe] RENDER ok=${r.ok} bytes=${r.bytes} geometry=${r.width}x${r.height} elapsedMs=$elapsed")
      println(s"[probe] RENDER reason=${r.reason} detail=${r.detail}")

      // ── independent measurement, taken from the FILE, outside the production
      // judgement: the production verdict is not evidence of what is on disk, so
      // both are recorded side by side and can be compared.
      val independent =
        if !Files.exists(outPng.toNIO) then "\"file\": \"ABSENT\""
        else
          val bytes = Files.readAllBytes(outPng.toNIO)
          val magicHex = bytes.take(8).map(b => "%02x".format(b & 0xff)).mkString(" ")
          val isPng = bytes.length >= 8 && (bytes(0) & 0xff) == 0x89 && bytes(1) == 'P'.toByte &&
            bytes(2) == 'N'.toByte && bytes(3) == 'G'.toByte
          def be32(o: Int): Int =
            ((bytes(o) & 0xff) << 24) | ((bytes(o + 1) & 0xff) << 16) |
              ((bytes(o + 2) & 0xff) << 8) | (bytes(o + 3) & 0xff)
          val w = be32(16); val h = be32(20)
          // Two independent readings: the raw file's byte statistics (cheap, and
          // deliberately NOT the production criterion — see richrenderer.inspect)
          // and the decoded pixel count (what production actually judges).
          val decoded = javax.imageio.ImageIO.read(outPng.toNIO.toFile)
          val distinctPixels =
            if decoded == null then -1
            else
              val seen = scala.collection.mutable.HashSet.empty[Int]
              var y = 0
              while y < decoded.getHeight do
                var x = 0
                while x < decoded.getWidth do
                  seen += (decoded.getRGB(x, y) & 0x00ffffff)
                  x += 1
                y += 1
              seen.size
          s""""file": "${esc(outPng.toString)}",
             |    "bytes": ${bytes.length},
             |    "magicHex8": "$magicHex",
             |    "isPng": $isPng,
             |    "ihdrWidth": $w,
             |    "ihdrHeight": $h,
             |    "distinctByteValues": ${bytes.distinct.length},
             |    "distinctPixelColours": $distinctPixels,
             |    "geometryMatchesDeclared": ${w == RichFeishuAdapter.DefaultWidth && h == RichFeishuAdapter.DefaultHeight}""".stripMargin

      write(evidenceDir, "render-reading.json",
        s"""{
           |  "ts": "${now()}",
           |  "mode": "render",
           |  "renderer": "RichRenderer.ChromeRenderer",
           |  "binary": "${esc(detected.map(_.toString).getOrElse("<none>"))}",
           |  "declaredGeometry": "${RichFeishuAdapter.DefaultWidth}x${RichFeishuAdapter.DefaultHeight}",
           |  "elapsedMs": $elapsed,
           |  "renderOk": ${r.ok},
           |  "renderBytes": ${r.bytes},
           |  "renderWidth": ${r.width},
           |  "renderHeight": ${r.height},
           |  "renderReason": "${esc(r.reason)}",
           |  "renderDetail": "${esc(r.detail)}",
           |  "minDistinctPixels": ${RichRenderer.MinDistinctPixels},
           |  "timeoutMs": ${RichRenderer.DefaultTimeoutMs},
           |  "stableWindowMs": ${RichRenderer.StableWindowMs},
           |  "chromeProcsBefore": $before,
           |  "independentMeasurement": {
           |    $independent
           |  }
           |}""".stripMargin)
      if !r.ok then exitCode = 1
    catch
      case e: Throwable =>
        write(evidenceDir, "render-reading.json",
          s"""{"ts":"${now()}","mode":"render","exception":"${esc(e.getClass.getName)}: ${esc(Option(e.getMessage).getOrElse(""))}"}""")
        println(s"[probe] EXCEPTION ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}")
        exitCode = 1

    // ── teardown reading: nothing resident may be left behind (HC-2 / D-2) ──
    Thread.sleep(1000)
    val after = chromeProcs()
    write(evidenceDir, "render-teardown.json",
      s"""{
         |  "ts": "${now()}",
         |  "chromeProcsBefore": $before,
         |  "chromeProcsAfter": $after,
         |  "leaked": ${after > before},
         |  "elapsedTotalMs": ${System.currentTimeMillis() - started}
         |}""".stripMargin)
    println(s"[probe] TEARDOWN chromeProcsBefore=$before chromeProcsAfter=$after " +
      s"leaked=${after > before} elapsedTotalMs=${System.currentTimeMillis() - started}")

    println(s"[probe] DONE exit=$exitCode")
    System.exit(exitCode)

end RichRenderProbe
