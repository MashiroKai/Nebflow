package nebflow.core.tools

import munit.FunSuite

/**
 * WebSearch "garbage reported as success" fix (2026-11-22 audit,
 * .stand/tmp/websearch-eval/probe2.log): 6/6 general queries returned Sogou's
 * SourceVerifyCode captcha page labelled `OK … Search engine: Sogou` with zero
 * usable results.
 *
 * Two rules are pinned here, no network involved:
 *   1. NEGATIVE CONTROL (iron rule): the real captcha page must be judged
 *      FAILED, and real arXiv/Crossref result payloads must stay OK.
 *   2. The anti-bot-prone engine set must have EFFECT, not just a declaration:
 *      Sogou/Baidu can never occupy a first-race slot.
 */
class WebSearchToolAntiBotSpec extends FunSuite:

  private val sogou = WebSearchTool.SearchEngine("Sogou", "https://sogou.com/web?query={keyword}", "cn")
  private val baidu = WebSearchTool.SearchEngine("Baidu", "https://www.baidu.com/s?wd={keyword}", "cn")
  private val ddg = WebSearchTool.SearchEngine("DuckDuckGo", "https://duckduckgo.com/html/?q={keyword}", "global")
  private val e360 = WebSearchTool.SearchEngine("360", "https://www.so.com/s?q={keyword}", "cn")

  /**
   * Verbatim tool view of `https://sogou.com/web?query=…` on 2026-11-22
   * 02:45:13 (probe2.log Q1–Q5). The <p> body is the exact extracted text the
   * tool shipped to the agent as a successful Sogou result; the links are the
   * real navigation links that survive extractGenericLinks ("返回首页>>",
   * "企业推广", "关于搜狗").
   */
  private val sogouCaptchaPage =
    """<html><head><title>搜狗搜索</title></head><body>
      |<a href="https://www.sogou.com/">返回首页&gt;&gt;</a>
      |<a href="https://www.sogou.com/web?query=x">企业推广</a>
      |<a href="https://www.sogou.com/docs/about.html">关于搜狗</a>
      |<p>// 按浏览器实际访问路径生成 base，兼容 IAS 对外路径与服务端 ROUTE_PREFIX 不一致 var base = document.createElement('base'); base.href = location.pathname.replace(/\/?$/, '/'); document.head.appendChild(base); 搜狗搜索 返回首页&gt;&gt; IP：210.45.118.3 访问时间：2026.11.22 02:45:13 SourceVerifyCode：fbe5087f4e21 From：www.sogou.com 此验证码用于确认这些请求是您的正常行为而不是自动程序发出的，需要您协助验证。 验证码： 提交 提交后没解决问题？欢迎 反馈 。 企业推广 关于搜狗 免责声明 意见反馈 &amp;copy; 2026 Sogou Inc. - 京ICP证050897号</p>
      |</body></html>""".stripMargin

  /** Verbatim OpenAI abstract page text for arXiv abs/1706.03762 (8837 chars live). */
  private val arxivResultText =
    """**Attention Is All You Need**
      |https://arxiv.org/abs/1706.03762v7
      |Authors: Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit, Llion Jones (2017) DOI: 10.48550/arXiv.1706.03762
      |The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely.
      |
      |**A Solar Radio Dynamic Spectrograph with Flexible Temporal-spectral Resolution**
      |https://arxiv.org/abs/1706.07915v1
      |Authors: Qing-Fu Du, Lei Chen, Yue-Chang Zhao, Xin Li, Yan Zhou (2017) DOI: 10.1088/1674-4527/17/9/98
      |The observation and research of the solar radio emission have unique scientific values in solar and space physics and related space weather forecasting applications.""".stripMargin

  /** Verbatim Crossref result text (probe2.log Q7). */
  private val crossrefResultText =
    """**A Real-time Automated Trigger for Solar Radio Burst Detection from Yamagawa Spectrograph**
      |https://doi.org/10.46620/ursi_rsrc24/1173bht5503
      |Authors: Patra, Oberoi, Kansabanik, Kubo (2024)
      |Journal: Proceedings of the 6th URSI Regional Conference on Radio Science – RCRS 2024
      |
      |**Initial results from the Madurai solar radio spectrograph**
      |https://doi.org/10.1023/a:1005117622716
      |Authors: Shanmugaraju, Umapathy, Balasubramanian, Selvanayagam, Manoharan (1999)""".stripMargin

  /** Verbatim leading text of the DDG region-selector page (probe3.log E2). */
  private val ddgRegionSelectorPage =
    """<html><body><p>NASA CDAWeb OMNI dataset download at DuckDuckGo All Regions Argentina Australia Austria Belgium (fr) Belgium (nl) Brazil Bulgaria Canada (en) Canada (fr) Catalonia Chile China Colombia Croatia Czech Republic Denmark Estonia Finland France Germany Greece Hong Kong Hungary Iceland India (en) Indonesia (en) Ireland Israel (en) Italy Japan Korea Latvia Lithuania Malaysia (en) Mexico Netherlands New Zealand Norway Pakistan (en) Peru Philippines (en) Poland Portugal Romania Russia Saudi Arabia Singapore Slovakia Slovenia South Africa Spain (ca) Spain (es) Sweden Switzerland (de) Switzerland (fr) Taiwan Thailand (en) Turkey US (English) US (Spanish) Ukraine United Kingdom Vietnam (en) Any Time Past Day Past Week Past Month Past Year</p></body></html>"""

  // ── NEGATIVE CONTROL: the real captcha page must FAIL ───────────────

  test("negative control: real Sogou SourceVerifyCode captcha page is judged FAILED (was OK 6/6)") {
    val verdict = WebSearchTool.assessEngineResult(sogou, sogouCaptchaPage)
    assert(verdict.isLeft, s"captcha page must not be a successful result, got: $verdict")
    val reason = verdict.swap.toOption.get
    assert(reason.contains("anti-bot blocked (captcha)"), s"reason must name the mode, got: $reason")
    assert(reason.startsWith("Sogou:"), s"reason must name the engine, got: $reason")
  }

  test("captcha entries are dropped by fingerprint (SourceVerifyCode / 此验证码用于确认 / 安全验证)") {
    val blob =
      "搜狗搜索 返回首页>> SourceVerifyCode：fbe5087f4e21 此验证码用于确认这些请求是您的正常行为而不是自动程序发出的，需要您协助验证。"
    assert(WebSearchTool.stripGarbageResults(blob, "Sogou").trim.isEmpty)
    assert(WebSearchTool.stripGarbageResults("百度安全验证 —— 网络不给力，请稍后重试", "Baidu").trim.isEmpty)
  }

  // ── NEGATIVE CONTROL: real result payloads must stay OK ─────────────

  test("negative control: real arXiv result text passes the new filters untouched") {
    assertEquals(WebSearchTool.stripGarbageResults(arxivResultText, "arXiv"), arxivResultText)
    assertEquals(WebSearchTool.dropNavigationEntries(arxivResultText, "arXiv"), arxivResultText)
  }

  test("negative control: real Crossref result text passes the new filters untouched") {
    assertEquals(WebSearchTool.stripGarbageResults(crossrefResultText, "Crossref"), crossrefResultText)
    assertEquals(WebSearchTool.dropNavigationEntries(crossrefResultText, "Crossref"), crossrefResultText)
  }

  test("real 360 result list (8 real entries, probe3.log E1) is judged OK") {
    val page =
      """<html><body>
        |<a href="https://www.so.com/link?m=uxtfw0eDeSMb">NASA遥感数据批量下载程序_nasa官网omi卫星数据下载windows-CSDN博客</a>
        |<a href="https://www.so.com/link?m=eKuw8n3">NASA环境数据下载 - 简书</a>
        |<a href="https://www.so.com/link?m=e55oBuYh">NASA数据下载-CSDN博客</a>
        |<a href="https://www.so.com/link?m=uTtbQL6G">DC3: NASA DC-8 WAS Data Merge. Version 6.0</a>
        |</body></html>""".stripMargin
    val verdict = WebSearchTool.assessEngineResult(e360, page)
    assert(verdict.isRight, s"real 360 result list must stay OK, got: $verdict")
    assert(verdict.toOption.get.contains("https://www.so.com/link?m="), "redirect shells ARE results for 360")
  }

  test("anti-bot page whose links are navigation residue only is still FAILED") {
    val navOnly =
      """<html><body>
        |<a href="https://www.sogou.com/">返回首页</a>
        |<a href="https://www.sogou.com/web?query=x">企业推广</a>
        |<a href="https://www.sogou.com/feedback">意见反馈</a>
        |</body></html>""".stripMargin
    val verdict = WebSearchTool.assessEngineResult(sogou, navOnly)
    assert(verdict.isLeft, s"navigation-only page is not a result list, got: $verdict")
  }

  test("DDG region-selector page (real, probe3.log E2) is judged FAILED instead of OK") {
    val verdict = WebSearchTool.assessEngineResult(ddg, ddgRegionSelectorPage)
    assert(verdict.isLeft, s"region selector is not a result list, got: $verdict")
  }

  test("navigation entries are dropped, result entries survive (per-entry granularity)") {
    val mixed =
      s"""**返回首页>>**
         |https://www.sogou.com/
         |
         |$arxivResultText""".stripMargin
    val kept = WebSearchTool.dropNavigationEntries(mixed, "Sogou")
    assert(!kept.contains("返回首页"), s"nav entry dropped, got: ${kept.take(80)}")
    assert(kept.contains("Attention Is All You Need"), "real entry survives")
  }

  // ── Declaration must equal effect: prone engines never race first ────

  test("race plan: Sogou/Baidu never appear in the first racing group (any query)") {
    val queries = List(
      "attention is all you need transformer paper",
      "scala 3 given using ambiguity compile error",
      "地磁暴 太阳射电暴 数据下载",
      "天气预报 北京 明天 降水概率",
      "python 爬虫 playwright 教程"
    )
    queries.foreach { q =>
      val plan = WebSearchTool.racePlan(q)
      assert(plan.nonEmpty, s"plan must not be empty for: $q")
      val firstGroup = plan.head.map(_.name)
      assert(
        !firstGroup.exists(n => n == "Sogou" || n == "Baidu"),
        s"anti-bot-prone engine raced first for '$q': $firstGroup"
      )
      assert(firstGroup.nonEmpty, s"first group must carry real-result engines for: $q")
    }
  }

  test("race plan: prone engines are scheduled only after every real-result engine") {
    val plan = WebSearchTool.racePlan("weather beijing forecast")
    val flat = plan.flatten.map(_.name)
    val lastPrimary = flat.indexWhere(n => n == "Sogou" || n == "Baidu")
    // Everything before the first prone engine must be non-prone.
    assert(flat.take(lastPrimary).forall(n => n != "Sogou" && n != "Baidu"), s"order: $flat")
    // And the primary group is exactly the non-prone roster.
    assertEquals(flat.take(lastPrimary).sorted, List("360", "DuckDuckGo", "WeChat").sorted)
  }

  test("orderedEngines still honours language preference inside the primary group") {
    val en = WebSearchTool.orderedEngines("scala 3 compile error")
    val zh = WebSearchTool.orderedEngines("天气预报 北京 明天 降水概率")
    assertEquals(en.head.name, "360")
    assertEquals(zh.head.name, "360")
    // English intent puts DDG directly behind 360; Chinese intent puts WeChat there.
    assertEquals(en(1).name, "DuckDuckGo")
    assertEquals(zh(1).name, "WeChat")
  }

end WebSearchToolAntiBotSpec
