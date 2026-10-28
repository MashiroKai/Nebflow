package nebflow.social.outbound

import cats.effect.unsafe.implicits.global

import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Manual LIVE probe for the web-page shot leg (social-webshot batch, 2026-10-09).
 * NOT a test and NOT part of `sbt test` discovery — a minimal `main` in Test
 * scope, same shape as [[RichRenderProbe]] / [[RichDegradeProbe]].
 *
 * Unlike the offline spec (which injects fakes), this probe runs the REAL
 * production face: [[WebPageShot.NodePlaywright]] spawns the repo's
 * `scripts/social-html-shot.mjs` (playwright from the repo root node_modules,
 * tests' same dependency) and the product is judged by [[RichRenderer.inspect]].
 * It writes its own throwaway html (one dark-styled page, one light page) into a
 * temp dir, so nothing in the repo or the user's home is touched. `System.exit`
 * is the last statement (the ≤120s self-exit obligation).
 *
 * Run: `sbt "Test/runMain nebflow.social.outbound.WebShotProbe"`
 */
object WebShotProbe:

  private def html(dark: Boolean): String =
    val bg = if dark then "#101418" else "#ffffff"
    val fg = if dark then "#e6edf3" else "#111111"
    val box = if dark then "#24292f" else "#f0f2f5"
    s"""<!doctype html><html><head><meta charset="utf-8"><style>
       |body{margin:40px;font-family:-apple-system,sans-serif;background:$bg;color:$fg}
       |section{height:320px;border:2px solid $box;border-radius:8px;padding:16px;margin-bottom:12px}
       |h1{font-size:28px}
       |</style></head><body>
       |<h1>probe diagram (${if dark then "dark" else "light"})</h1>
       |<section>box 1</section><section>box 2</section><section>box 3</section>
       |</body></html>""".stripMargin

  def main(args: Array[String]): Unit =
    val tmp = Files.createTempDirectory("nb-webshot-probe-")
    try
      val shot = new WebPageShot.NodePlaywright()
      val available = shot.available
      println(s"[webshot-probe] available=$available script=${WebPageShot.NodePlaywright.detectScript}")

      var failures = 0
      for dark <- List(true, false) do
        val in = os.Path(tmp.resolve(s"probe-$dark.html"), os.pwd)
        Files.writeString(in.toNIO, html(dark))
        val out = os.Path(tmp.resolve(s"probe-$dark.png"), os.pwd)
        val t0 = System.nanoTime()
        val res = shot.shot(in, out).unsafeRunSync()
        val ms = (System.nanoTime() - t0) / 1000000L
        res match
          case Right(png) =>
            val img = ImageIO.read(png.toNIO.toFile)
            println(s"[webshot-probe] dark=$dark OK ${img.getWidth}x${img.getHeight} " +
              s"${Files.size(png.toNIO)}B in ${ms}ms -> $png")
            if img.getHeight <= 900 then
              println(s"[webshot-probe] FAIL dark=$dark: height ${img.getHeight} <= viewport 900 — fullPage did not happen")
              failures += 1
          case Left(reason) =>
            println(s"[webshot-probe] FAIL dark=$dark: $reason (${ms}ms)")
            failures += 1

      if failures == 0 then println("[webshot-probe] ALL PASS") else println(s"[webshot-probe] FAILURES=$failures")
      if failures == 0 then System.exit(0) else System.exit(1)
    finally
      // best-effort cleanup of the probe's own temp dir
      try
        val stream = Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator()
        while stream.hasNext do Files.deleteIfExists(stream.next())
      catch case _: Throwable => ()
