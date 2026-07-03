package nebflow.shared

import cats.effect.IO
import nebflow.core.NebflowLogger

import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.duration.*

/** Browser fetch result. */
final case class BrowserFetchResult(
  status: Int,
  title: String,
  content: String,
  finalUrl: String,
  isMarkdown: Boolean = false
)

/** Browser fetcher interface for dynamically loaded optional implementations (e.g. PlaywrightFetcher). */
trait BrowserFetcher:
  def fetch(url: String, maxWaitSeconds: Int): IO[BrowserFetchResult]
  def shutdown(): Unit

/**
 * Browser manager (singleton) — fallback fetcher for anti-bot pages.
 *
 * Engine priority:
 *  1. Obscura (Rust headless browser, built-in stealth + DOM-to-Markdown)
 *  2. Playwright (system Chrome, enhanced stealth injection) — only if on classpath
 *  3. Failure (caller handles)
 *
 * Playwright is an optional dependency. If the JAR is not on the classpath,
 * Class.forName on PlaywrightFetcher fails and we silently degrade to Obscura-only.
 */
object BrowserManager:
  private val logger = NebflowLogger.forName("nebflow.browser")

  /**
   * Dynamically detect whether Playwright is available.
   * PlaywrightFetcher imports com.microsoft.playwright.* — if the JAR is absent,
   * loading the class throws NoClassDefFoundError, which we catch here.
   */
  private lazy val playwrightFetcher: Option[BrowserFetcher] =
    try
      val clazz = Class.forName("nebflow.shared.PlaywrightFetcher")
      val module = clazz.getField("MODULE$").get(null).asInstanceOf[BrowserFetcher]
      logger.infoSync("Playwright detected on classpath, available as browser fallback")
      Some(module)
    catch
      case _: NoClassDefFoundError | _: ClassNotFoundException =>
        logger.infoSync("Playwright not on classpath, Obscura-only mode")
        None

  // ── Obscura detection ─────────────────────────────────────────────

  /** Detect whether Obscura is installed. Checked once, result cached. */
  private lazy val obscuraPath: Option[String] =
    try
      val pb = new ProcessBuilder("which", "obscura")
      pb.redirectErrorStream(true)
      val proc = pb.start()
      proc.waitFor(3, TimeUnit.SECONDS)
      val path = new String(proc.getInputStream.readAllBytes()).trim
      if proc.exitValue() == 0 && path.nonEmpty then
        logger.infoSync("Obscura detected", "path" -> path)
        Some(path)
      else None
    catch case _: Exception => None

  /** Fetch a page via Obscura CLI. Returns Markdown. */
  private def fetchWithObscura(url: String, maxWaitSeconds: Int): Option[BrowserFetchResult] =
    obscuraPath.flatMap { exe =>
      try
        val timeoutMs = maxWaitSeconds * 1000
        val cmd = List(exe, "fetch", url, "--stealth", "--timeout", timeoutMs.toString, "--dump", "markdown")
        import scala.jdk.CollectionConverters.*
        val pb = new ProcessBuilder(cmd.asJava)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.waitFor((maxWaitSeconds + 10).toLong, TimeUnit.SECONDS)
        if proc.exitValue() == 0 then
          val output = new String(proc.getInputStream.readAllBytes()).trim
          if output.nonEmpty then
            val title = """(?m)^#\s+(.+)$""".r.findFirstMatchIn(output).map(_.group(1)).getOrElse("")
            logger.infoSync("Obscura fetched", "url" -> url.take(80), "len" -> output.length.toString)
            Some(BrowserFetchResult(200, title, output, url, isMarkdown = true))
          else None
        else None
      catch case _: Exception => None
    }

  // ── Public API ──────────────────────────────────────────────────────

  /** Fetch a page. Tries Obscura first, then Playwright (if available). */
  def fetch(url: String, maxWaitSeconds: Int = 15): IO[BrowserFetchResult] =
    val hardTimeout = (maxWaitSeconds + 15).seconds
    IO.blocking {
      fetchWithObscura(url, maxWaitSeconds)
    }.flatMap {
      case Some(result) => IO.pure(result)
      case None =>
        playwrightFetcher match
          case Some(fetcher) =>
            logger.infoSync("Obscura unavailable, falling back to Playwright", "url" -> url.take(80))
            fetcher.fetch(url, maxWaitSeconds)
          case None =>
            logger.infoSync("Browser fetch failed (no Obscura, no Playwright)", "url" -> url.take(80))
            IO.pure(BrowserFetchResult(0, "Unavailable", s"Browser fetch failed for $url.", url))
    }.timeout(hardTimeout)
      .recover { case _: java.util.concurrent.TimeoutException =>
        logger.infoSync("Browser fetch timed out", "url" -> url.take(80), "timeout" -> hardTimeout.toString)
        BrowserFetchResult(0, "Timeout", s"Page load timed out after $hardTimeout.", url)
      }

  end fetch

  /** Release resources. */
  def shutdown(): Unit =
    playwrightFetcher.foreach(_.shutdown())

end BrowserManager
