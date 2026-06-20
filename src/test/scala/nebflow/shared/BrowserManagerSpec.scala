package nebflow.shared

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import scala.annotation.nowarn
import scala.concurrent.duration.{Duration, SECONDS}

@nowarn("cat=deprecation")
class BrowserManagerSpec extends CatsEffectSuite:
  override val munitTimeout: Duration = Duration(120, SECONDS)

  private val ArxivUrl = "https://arxiv.org/abs/2401.00001"
  private val IeeeUrl = "https://ieeexplore.ieee.org/document/9000000"
  private val SdUrl = "https://www.sciencedirect.com/science/article/pii/S0168900223005267"

  private def isChallenge(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required")

  test("arXiv: stealth + DOM-to-Markdown") {
    BrowserManager.fetch(ArxivUrl, headless = true, maxWaitSeconds = 10).map { result =>
      assertEquals(result.status, 200)
      assert(result.content.length > 1000, s"too short: ${result.content.length}")
      assert(result.isMarkdown, "should be markdown format")
      assert(!isChallenge(result.title))
    }
  }

  test("IEEE Xplore: stealth bypass + Markdown extraction") {
    BrowserManager.fetch(IeeeUrl, headless = true, maxWaitSeconds = 15).map { result =>
      assert(result.content.length > 2000, s"too short: ${result.content.length}")
      assert(!isChallenge(result.title))
    }
  }

  test("ScienceDirect: detects Cloudflare challenge") {
    BrowserManager.fetch(SdUrl, headless = true, maxWaitSeconds = 10).map { result =>
      val challenged = isChallenge(result.title) || result.status == 403
      if challenged then println(s"  [expected] Cloudflare challenge: ${result.title}")
      else
        assert(result.content.length > 2000)
        println(s"  [success] ScienceDirect: ${result.title.take(60)}")
    }
  }
end BrowserManagerSpec
