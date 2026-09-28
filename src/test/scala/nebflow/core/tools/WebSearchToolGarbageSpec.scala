package nebflow.core.tools

import munit.CatsEffectSuite

/** WebSearch P0 (E2-5): anti-scraping garbage fingerprints. The 2026-08-22
  * Sogou incident: a checkSNUID anti-bot cookie script was extracted by the
  * generic link fallback and shipped to the agent as the Sogou "results"
  * (session delegate-Explorer-426be9df, "Search engine: Sogou" success entry
  * containing `function checkSNUID() { ... document.cookie ... }`). */
class WebSearchToolGarbageSpec extends CatsEffectSuite:

  // Abbreviated from the real evidence sample (structure preserved).
  private val sogouGarbage =
    """搜狗搜索 (function () { if (!window.Promise) { document.writeln(' '); } })(); var domain = getDomain(); window.imgCode = -1; (function() { function checkSNUID() { var cookieArr = document.cookie.split('; '), count = 0; for(var i = 0, len = cookieArr.length; i -1) { count++; } } return count > 1; } if(checkSNUID()) { var date = new Date(), expires; date.setTime(date.getTime() -100000); expires = date.toGMTString(); document.cookie = 'SNUID=1;path=/;expires=' + expires; }})();"""

  private val cleanEntry = """**Scala 3.5 release notes**\nhttps://example.com/scala35\nNew syntax features."""

  private def garbageWarns(f: => Unit): List[String] =
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.tools.websearch").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    lbLogger.addAppender(appender)
    try
      f
      import scala.jdk.CollectionConverters.*
      appender.list.asScala.toList
        .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage)
    finally lbLogger.detachAppender(appender)

  test("Sogou checkSNUID sample: entire extraction dropped + WARN emitted") {
    val warns = garbageWarns {
      val cleaned = WebSearchTool.stripGarbageResults(sogouGarbage, "Sogou")
      assert(cleaned.trim.isEmpty, s"anti-bot script must be fully dropped, got: ${cleaned.take(80)}")
    }
    assert(warns.nonEmpty, "garbage drop must WARN (observability)")
    assert(warns.head.contains("Sogou"), s"WARN must name the engine: ${warns.headOption}")
    assert(warns.head.contains("garbage"), s"WARN must say what happened: ${warns.headOption}")
  }

  test("mixed batch: garbage entry dropped, clean entries survive; per-entry granularity") {
    val mixed = s"$cleanEntry\n\n$sogouGarbage\n\n$cleanEntry"
    val cleaned = WebSearchTool.stripGarbageResults(mixed, "Sogou")
    // The two clean entries survive; the JS blob in the middle is gone.
    assert(cleaned.contains("Scala 3.5 release notes"))
    assert(!cleaned.contains("checkSNUID"))
    assert(cleaned.split("\n\n").count(_.nonEmpty) == 2, s"exactly the 2 clean entries, got: $cleaned")
  }

  test("clean extraction passes through untouched (no false positives, no WARN)") {
    val warns = garbageWarns {
      val cleaned = WebSearchTool.stripGarbageResults(cleanEntry, "DuckDuckGo")
      assertEquals(cleaned, cleanEntry)
    }
    assert(warns.isEmpty, s"clean results must not WARN, got: $warns")
  }

  test("batch-level degradation contract: all-garbage batch reads as empty (engine fails downstream)") {
    // searchOne treats a <50-char / empty extraction as engine failure → the
    // batch race moves on to the next engine. The fingerprint filter feeds
    // that contract by returning "" when everything was garbage.
    val cleaned = WebSearchTool.stripGarbageResults(sogouGarbage, "Sogou")
    assert(cleaned.trim.isEmpty || cleaned.trim.length < 50)
  }

  test("Tier 3 annotation: both engine flavors marked non-guaranteed") {
    assert(WebSearchTool.tier3Annotation(academic = false).toLowerCase.contains("non-guaranteed"))
    assert(WebSearchTool.tier3Annotation(academic = true).toLowerCase.contains("non-guaranteed"))
    assert(WebSearchTool.tier3Annotation(academic = false).contains("builtin aggregation"))
  }

end WebSearchToolGarbageSpec
