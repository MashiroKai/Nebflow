package nebflow.shared

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

class BrowserManagerSpec extends CatsEffectSuite:
  private val ArxivUrl = "https://arxiv.org/abs/2401.00001"
  private val IeeeUrl = "https://ieeexplore.ieee.org/document/9000000"
  private val SdUrl = "https://www.sciencedirect.com/science/article/pii/S0168900223005267"

  private def isChallenge(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required")

  test("headless browser fetches arXiv successfully") {
    BrowserManager
      .fetch(ArxivUrl, headless = true, maxWaitSeconds = 10)
      .map { result =>
        assertEquals(result.status, 200)
        assert(result.content.length > 1000, s"content too short: ${result.content.length}")
        assert(!isChallenge(result.title), s"should not be challenge: ${result.title}")
      }
  }

  test("IEEE Xplore via headless browser") {
    BrowserManager
      .fetch(IeeeUrl, headless = true, maxWaitSeconds = 10)
      .map { result =>
        assert(result.content.length > 5000, s"content too short: ${result.content.length}")
        assert(!isChallenge(result.title), s"IEEE should not be challenge: ${result.title}")
      }
  }

  test("ScienceDirect returns content (challenge status logged)") {
    BrowserManager
      .fetch(SdUrl, headless = true, maxWaitSeconds = 10)
      .map { result =>
        val challenged = isChallenge(result.title)
        if challenged then println(s"  [expected on campus VPN-less] challenge page: ${result.title}")
        else
          assert(result.content.length > 5000, s"content too short: ${result.content.length}")
          println(s"  ScienceDirect fetched: ${result.title.take(60)}")
      }
  }
end BrowserManagerSpec
