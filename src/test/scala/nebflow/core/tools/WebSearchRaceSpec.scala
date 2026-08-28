package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** WebSearch P1 (2026-08-25, docs/Nebflow/20260825_websearch-fetch-optimization.md):
  * Tier 3 race-semantics fix (fast-fail poison) + engine priority by query
  * language + error classification. All tests are scripted IOs / pure
  * functions — no network. The real-engine smoke is an isolated-instance
  * acceptance (P1-3/P1-4), not a unit test. */
class WebSearchRaceSpec extends CatsEffectSuite:

  override val munitIOTimeout = 15.seconds

  private val e360 = WebSearchTool.SearchEngine("360", "https://www.so.com/s?q={keyword}", "cn")
  private val eSogou = WebSearchTool.SearchEngine("Sogou", "https://sogou.com/web?query={keyword}", "cn")
  private val eBaidu = WebSearchTool.SearchEngine("Baidu", "https://www.baidu.com/s?wd={keyword}", "cn")
  private val eWeChat = WebSearchTool.SearchEngine("WeChat", "https://wx.sogou.com/weixin?type=2&query={keyword}", "cn")
  private val eDdg = WebSearchTool.SearchEngine("DuckDuckGo", "https://duckduckgo.com/html/?q={keyword}", "global")

  private val ok360 = (e360, "**LangGraph how-to**\nhttps://example.com/langgraph\nMap-reduce fan-out in 3 steps")

  // ── P1-1/P1-2: race semantics (fast-fail poison regression lock) ─────

  test("P1-1: fast failure + slow success → batch returns the success (poison no longer kills peers)") {
    // The old IO.race(...).map(_.merge) semantics: Sogou's instant 415B
    // antispider Left cancels 360's slow-but-real Right. New semantics: the
    // fast Left is recorded, the slow Right still wins.
    val fastFail = IO.pure(Left(s"${eSogou.name}: anti-bot blocked (antispider)"))
    // 500ms (was 150ms): on a loaded CI runner the main fiber can be
    // descheduled between `.start` and `IO.race` — with both Deferreds
    // already complete, the race winner is nondeterministic and `done` won
    // at ~152ms, turning the fast failure into the terminal Left. 500ms
    // gives a 3x+ margin under CI scheduling stalls; munitIOTimeout is 15s.
    val slowSuccess = IO.sleep(500.millis) *> IO.pure(Right(ok360))
    WebSearchTool.raceFirstSuccess(List(fastFail, slowSuccess)).map { r =>
      assert(r.isRight, s"fast failure must not poison the batch, got: $r")
      assertEquals(r.toOption.get._1.name, "360")
      assert(r.toOption.get._2.contains("https://example.com/langgraph"))
    }
  }

  test("P1-1: success short-circuits — a slow peer still failing is cancelled, batch returns at success time") {
    val slowFail = IO.sleep(5.seconds) *> IO.pure(Left("Baidu: slow death"))
    val quickSuccess = IO.sleep(80.millis) *> IO.pure(Right(ok360))
    for
      started <- IO.realTime
      r <- WebSearchTool.raceFirstSuccess(List(slowFail, quickSuccess))
      elapsed <- IO.realTime.map(_ - started)
    yield
      assert(r.isRight, s"must return the quick success, got: $r")
      assert(elapsed < 3.seconds, s"must NOT wait for the slow peer to finish (cancelled), took: $elapsed")
  }

  test("P1-2: all engines fail → batch aggregates EVERY error (not just the first)") {
    val fail1 = IO.pure(Left(s"${eSogou.name}: anti-bot blocked (antispider)"))
    val fail2 = IO.sleep(50.millis) *> IO.pure(Left(s"${eBaidu.name}: anti-bot blocked (captcha)"))
    val fail3 = IO.pure(Left(s"${eDdg.name}: timeout (proxy required)"))
    WebSearchTool.raceFirstSuccess(List(fail1, fail2, fail3)).map { r =>
      assert(r.isLeft)
      val errs = r.left.toOption.get
      assert(errs.contains("Sogou"), s"all errors aggregated, got: $errs")
      assert(errs.contains("Baidu"), s"all errors aggregated, got: $errs")
      assert(errs.contains("DuckDuckGo"), s"all errors aggregated, got: $errs")
    }
  }

  test("P1-2: empty batch → failure (no silent empty success)") {
    WebSearchTool.raceFirstSuccess(Nil).map(r => assert(r.isLeft))
  }

  // ── P1-5: engine priority by query language ──────────────────────────

  test("P1-5: English/technical query → 360 first, DuckDuckGo second (proxy-reachable)") {
    val eng = WebSearchTool.orderedEngines("langchain.com langgraph map-reduce how-to Send API parallelize fan-out")
    assertEquals(eng.head.name, "360")
    assertEquals(eng(1).name, "DuckDuckGo")
  }

  test("P1-5: Chinese query → 360 first, CN engines next") {
    val eng = WebSearchTool.orderedEngines("天气预报 北京 明天 降水概率")
    assertEquals(eng.head.name, "360")
    assertEquals(eng(1).name, "Sogou")
  }

  test("P1-5: mixed query with latin majority still routes English-first (technical intent)") {
    val eng = WebSearchTool.orderedEngines("python 爬虫 playwright 教程")
    assertEquals(eng.head.name, "360")
    assertEquals(eng(1).name, "DuckDuckGo")
  }

  test("P1-5: anti-bot-prone engines sink below the primary batch position") {
    val eng = WebSearchTool.orderedEngines("weather beijing forecast")
    val idx = eng.map(_.name)
    assert(idx.indexOf("Sogou") >= 2, s"Sogou must not occupy the first racing slot, order: $idx")
    assert(idx.indexOf("Baidu") >= 2, s"Baidu must not occupy the first racing slot, order: $idx")
  }

  test("P1-5: academic engines excluded from general ordering") {
    val eng = WebSearchTool.orderedEngines("scala")
    assert(!eng.exists(_.name == "arXiv"))
    assert(!eng.exists(_.name == "Crossref"))
  }

  // ── P1-6: error classification ──────────────────────────────────────

  test("P1-6: Sogou antispider page → classified anti-bot blocked (antispider)") {
    val html = """<html><script>var antispider = true;</script>sogou.com/antispider/</html>"""
    assertEquals(WebSearchTool.classifyEmpty(html, eSogou), "Sogou: anti-bot blocked (antispider)")
  }

  test("P1-6: Baidu wappass captcha page → classified anti-bot blocked (captcha)") {
    val html = """<html>wappass.baidu.com 验证码 <script>showCaptcha()</script></html>"""
    assertEquals(WebSearchTool.classifyEmpty(html, eBaidu), "Baidu: anti-bot blocked (captcha)")
  }

  test("P1-6: WeChat JS-rendered list → classified no parseable links (JS-rendered)") {
    val html = """<a href="javascript:void(0)">article 1</a><a href="javascript:void(0)">article 2</a>"""
    assertEquals(WebSearchTool.classifyEmpty(html, eWeChat), "WeChat: no parseable links (JS-rendered)")
  }

  test("P1-6: plain empty extraction → no parseable results (no misclassification)") {
    assertEquals(WebSearchTool.classifyEmpty("<html><body></body></html>", eDdg), "DuckDuckGo: no parseable results")
  }

end WebSearchRaceSpec
