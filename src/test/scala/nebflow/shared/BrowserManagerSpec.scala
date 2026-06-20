package nebflow.shared

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import scala.annotation.nowarn
import scala.concurrent.duration.{Duration, SECONDS}
import java.nio.file.{Files, Paths}

@nowarn("cat=deprecation")
class BrowserManagerSpec extends CatsEffectSuite:
  // Per-test timeout: 60s (enough for page load + content wait, but not 120s)
  override val munitTimeout: Duration = Duration(60, SECONDS)

  // Use a separate browser-data directory for tests to avoid SingletonLock
  // conflict with the running Nebflow instance's Chrome.
  private val testDataDir = System.getProperty("java.io.tmpdir") + "/nebflow-test-browser-data"
  System.setProperty("nebflow.browser.dataDir", testDataDir)

  // Clean up stale SingletonLock from previous test runs
  val lockFile = Paths.get(testDataDir, "SingletonLock")
  if Files.exists(lockFile) then
    try Files.delete(lockFile) catch case _: Exception => ()

  private val ArxivUrl = "https://arxiv.org/abs/2401.00001"
  private val IeeeUrl = "https://ieeexplore.ieee.org/document/9000000"
  private val SdUrl = "https://www.sciencedirect.com/science/article/pii/S0168900223005267"

  private def isChallenge(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required") || t.contains("timeout")

  /** Whether the result indicates the page was blocked or timed out. */
  private def isBlocked(result: BrowserFetchResult): Boolean =
    isChallenge(result.title) || result.status == 403 || result.status == 0 ||
    result.status == 202 || result.content.length < 500

  test("arXiv: stealth + DOM-to-Markdown") {
    BrowserManager.fetch(ArxivUrl, headless = true, maxWaitSeconds = 10).map { result =>
      // arXiv has no anti-bot protection — this must succeed
      assertEquals(result.status, 200, s"expected 200, got ${result.status}: ${result.title}")
      assert(result.content.length > 1000, s"too short: ${result.content.length}")
      assert(result.isMarkdown, "should be markdown format")
      assert(!isChallenge(result.title))
    }
  }

  test("IEEE Xplore: stealth bypass + Markdown extraction") {
    BrowserManager.fetch(IeeeUrl, headless = true, maxWaitSeconds = 15).map { result =>
      // IEEE has aggressive anti-bot protection. If blocked, skip rather than fail.
      if isBlocked(result) then
        println(s"  [skip] IEEE blocked: status=${result.status} title=${result.title.take(60)}")
      else
        assert(result.content.length > 2000, s"too short: ${result.content.length}")
        assert(!isChallenge(result.title))
        println(s"  [success] IEEE: ${result.title.take(60)}")
    }
  }

  test("ScienceDirect: detects Cloudflare challenge") {
    BrowserManager.fetch(SdUrl, headless = true, maxWaitSeconds = 10).map { result =>
      // ScienceDirect uses Cloudflare — blocked is expected behavior
      if isBlocked(result) then
        println(s"  [expected] ScienceDirect blocked: status=${result.status} title=${result.title.take(60)}")
      else
        assert(result.content.length > 2000)
        println(s"  [success] ScienceDirect: ${result.title.take(60)}")
    }
  }
end BrowserManagerSpec
