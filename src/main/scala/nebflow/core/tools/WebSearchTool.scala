package nebflow.core.tools

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{JsonObject, parser}
import nebflow.shared.{Branding, HttpUtils, SharedBackend}
import sttp.client4.*

import scala.concurrent.duration.*

object WebSearchTool extends Tool:
  val FETCH_TIMEOUT = 10_000 // per-engine timeout
  val DEFAULT_MAX_CHARS = 15_000
  val BATCH_SIZE = 3 // race this many engines at a time

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.tools.websearch")

  /**
   * Anti-scraping garbage fingerprints (WebSearch P0, C4). A result entry
   * whose text hits one of these is an anti-bot interstitial (JS
   * cookie/captcha machinery), not a search result — dropping the entry
   * beats shipping it to the agent as a "successful" result (2026-08-22
   * Sogou incident: a checkSNUID cookie script was extracted and passed
   * through as the Sogou "results" with zero user-visible anomaly).
   * Lowercase contains-match; entries are dropped individually so a single
   * false positive (e.g. a JS tutorial snippet) cannot kill a whole batch.
   */
  private val GarbageFingerprints: List[String] = List(
    "checksnuid",
    "document.cookie",
    "window.location",
    "location.href",
    "antispider",
    // 2026-11-22 end-to-end evaluation (.stand/tmp/websearch-eval/probe2.log):
    // Sogou switched to a "SourceVerifyCode" captcha variant. Real page text:
    //   "返回首页>> IP：210.45.118.3 … SourceVerifyCode：fbe5087f4e21 From：www.sogou.com
    //    此验证码用于确认这些请求是您的正常行为而不是自动程序发出的，需要您协助验证。
    //    验证码： 提交 …"
    // None of the 2026-08-22 fingerprints matched, so the entire captcha page was
    // shipped to the agent as a "successful" Sogou result (6/6 queries, "可用 0 条").
    // The 2026-08-22 fix added literals for one captcha variant; this set covers the
    // CN interstitial vocabulary (Sogou SourceVerifyCode / 安全验证, Baidu wappass).
    "sourceverifycode",
    "此验证码用于确认",
    "请输入验证码",
    "验证码",
    "安全验证",
    "captcha",
    "wappass"
  )

  /**
   * Drop garbage entries from an extraction. Returns the cleaned text —
   * empty when EVERYTHING was garbage, which makes searchOne treat the
   * engine as failed (batch-level degradation per C4: "parsed something
   * trustworthy" is the bar, not "parsed something").
   */
  private[tools] def stripGarbageResults(text: String, engine: String): String =
    val entries = text.split("\n\n")
    val kept = entries.filter { entry =>
      val lower = entry.toLowerCase
      val garbage = GarbageFingerprints.exists(lower.contains)
      if garbage then
        // Synchronous context (searchOne runs inside IO.blocking) — the
        // IO-returning warn would be built-then-discarded (dead-logging trap).
        logger.warnSync(
          s"dropped anti-scraping garbage result from $engine " +
            s"(fingerprint hit): ${entry.replaceAll("\\s+", " ").take(120)}"
        )
      !garbage
    }
    kept.mkString("\n\n")

  end stripGarbageResults

  /**
   * Tier 3 provenance annotation — the builtin aggregation is a zero-config
   * fallback, not a product-grade search (keyless HTML scraping, no SLA).
   * Every Tier 3 result says so; provider-routed results carry their own
   * "Search source: provider:<id>" header instead.
   */
  private[tools] def tier3Annotation(academic: Boolean): String =
    if academic then "Source: builtin academic API (non-guaranteed fallback tier)"
    else
      "Source: builtin aggregation (non-guaranteed — keyless HTML scraping, no SLA; verify before relying on results)"

  case class SearchEngine(name: String, url: String, region: String)

  /**
   * General engines that serve anti-bot interstitial pages in the CN network
   * (Sogou antispider / SourceVerifyCode captcha, Baidu wappass captcha —
   * verified 2026-08-25 and again 2026-11-22). They stay in the roster (their
   * behavior may change; a pinned engine= is still honored) but racePlan()
   * never schedules them in the first racing group.
   *
   * 2026-11-22 audit: this set used to be declared and NEVER read (grep found
   * one hit: the declaration itself), while orderedEngines fixed Sogou into
   * slot 3 of a BATCH_SIZE=3 race. A captcha page answering in 1.0 s beat a
   * real page answering in 2.6 s, so 6/6 general queries returned Sogou's
   * captcha page labelled "OK". Declaration must now equal effect: the
   * partition below is the only ordering authority.
   */
  private val ANTI_BOT_PRONE: Set[String] = Set("Sogou", "Baidu")

  // Historical engine priority (2026-08-25, empirical): 360 was the only
  // consistently reachable general engine in the CN network (467KB real
  // results); DDG is reachable through the platform proxy; Sogou/Baidu serve
  // anti-bot pages; WeChat results are JS-rendered (no parseable <a href^=http>
  // links). These lists now express LANGUAGE preference only — anti-bot-prone
  // engines are partitioned out of the primary group below.
  private val ENGLISH_FIRST: List[String] = List("360", "DuckDuckGo", "Sogou", "Baidu", "WeChat")
  private val CHINESE_FIRST: List[String] = List("360", "Sogou", "Baidu", "WeChat", "DuckDuckGo")

  val ENGINES: List[SearchEngine] = List(
    SearchEngine("360", "https://www.so.com/s?q={keyword}", "cn"),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/html/?q={keyword}", "global"),
    SearchEngine("Sogou", "https://sogou.com/web?query={keyword}", "cn"),
    SearchEngine("Baidu", "https://www.baidu.com/s?wd={keyword}", "cn"),
    SearchEngine("WeChat", "https://wx.sogou.com/weixin?type=2&query={keyword}", "cn"),
    // Academic API engines (return structured JSON/XML, not HTML)
    SearchEngine("arXiv", "http://export.arxiv.org/api/query", "academic"),
    SearchEngine("Crossref", "https://api.crossref.org/works", "academic")
  )

  /**
   * Engine ordering by query language. The language lists express preference
   * only: the result is PARTITIONED so anti-bot-prone engines come after every
   * engine that can plausibly return real results. A known-dead slot must never
   * occupy a racing position it can win on latency alone (2026-11-22 audit:
   * Sogou's 1.0 s captcha page beat 360's 2.6 s real page 6/6).
   * Returns only the general (non-academic) engines.
   */
  private[tools] def orderedEngines(query: String): List[SearchEngine] =
    val latin = query.count(c => c.isLetter && c < 128)
    val total = query.count(_.isLetter)
    val englishHeavy = total == 0 || latin.toDouble / total > 0.5
    val names = if englishHeavy then ENGLISH_FIRST else CHINESE_FIRST
    val (deferred, primary) = names.flatMap(n => engineMap.get(n)).partition(e => ANTI_BOT_PRONE.contains(e.name))
    primary ::: deferred

  /**
   * Racing plan for a general query: real-result-capable engines race first, in
   * BATCH_SIZE groups; anti-bot-prone engines are scheduled ONLY in the trailing
   * groups, so they act as a last-resort fallback and never as a competitor that
   * can win the race on latency. Extracted (pure) so the invariant is testable
   * without network.
   */
  private[tools] def racePlan(query: String): List[List[SearchEngine]] =
    val (deferred, primary) = orderedEngines(query).partition(e => ANTI_BOT_PRONE.contains(e.name))
    val primaryBatches = if primary.nonEmpty then primary.grouped(BATCH_SIZE).toList else Nil
    primaryBatches ::: deferred.grouped(BATCH_SIZE).toList

  private val engineMap: Map[String, SearchEngine] = ENGINES.map(e => e.name -> e).toMap
  private val ACADEMIC_NAMES = Set("arXiv", "Crossref")

  val name = "WebSearch"

  val description = """Search the web using multiple search engines (no API key required).

Available engines (default: auto-select via batch racing):
- General: Sogou, 360, DuckDuckGo, Baidu, WeChat Articles
- Academic: arXiv, Crossref

Academic engines for research paper search:
- arXiv: preprint server, best for physics/CS/math papers. Returns title, authors, year, abstract, DOI.
- Crossref: DOI registry, best for finding exact publication metadata. Returns title, authors, year, journal, DOI.
- Specify engine="arXiv" or engine="Crossref" for academic queries.
- For general web search, omit engine or specify a general engine.

Usage:
- For academic paper search, prefer engine="arXiv" or engine="Crossref" over general web search.
- Only use WebSearch and WebFetch for accessing information beyond your training data.
- Do NOT generate or guess URLs unless you are confident they help the user with programming tasks.
- When the user provides a URL, use WebFetch to retrieve its contents rather than guessing what's there.
- Web search results may contain outdated information — verify critical facts before relying on them.
- IMPORTANT: You MUST include a "Sources:" section listing all relevant URLs"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "query" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "minLength" -> io.circe.Json.fromInt(2),
          "description" -> "The search query".asJson
        ),
        "engine" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.fromValues(ENGINES.map(_.name.asJson)),
          "description" -> "Search engine to use. Defaults to auto-racing all general engines.".asJson
        ),
        "max_results" -> io.circe.Json.obj(
          "type" -> "number".asJson,
          "description" -> "Max result characters to return (default 15000)".asJson,
          "minimum" -> io.circe.Json.fromInt(100)
        ),
        "allowed_domains" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "items" -> io.circe.Json.obj("type" -> "string".asJson),
          "description" -> "Only include results from these domains".asJson
        ),
        "blocked_domains" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "items" -> io.circe.Json.obj("type" -> "string".asJson),
          "description" -> "Exclude results from these domains".asJson
        )
      ),
      "required" -> io.circe.Json.arr("query".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val query = input("query").flatMap(_.asString).getOrElse("")
    val engine = input("engine").flatMap(_.asString).getOrElse("auto")
    val short = if query.length > 120 then query.take(117) + "..." else query
    s"""WebSearch("$short", engine=$engine)"""

  def summarizeResult(input: JsonObject, result: String): String =
    val head = result.split("\n").headOption.getOrElse("done")
    if head.startsWith("Search engine:") then head
    else head.take(80)

  // ── Result extraction per engine ──────────────────────────────────

  private def extractSearchResults(html: String, engine: String): String =
    if engine.contains("DuckDuckGo") then extractDuckDuckGo(html)
    else if engine.contains("Bing") then extractBing(html)
    else if engine.contains("Baidu") then extractBaidu(html)
    else extractGenericLinks(html)

  /** DuckDuckGo HTML endpoint returns clean <div class="result"> blocks. */
  private def extractDuckDuckGo(html: String): String =
    val results = scala.collection.mutable.ListBuffer.empty[String]
    // Each result block: <div class="result"> ... </div> (before next result or end)
    val blockPat = """<div class="result[^"]*"[^>]*>([\s\S]*?)(?=<div class="result|\z)""".r
    blockPat.findAllMatchIn(html).take(8).foreach { m =>
      val block = m.group(1)
      // Title + link: <a class="result__a" href="URL">Title</a>
      val linkPat = """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""".r
      val snippetPat = """<a[^>]*class="result__snippet"[^>]*>([\s\S]*?)</a>""".r
      linkPat.findFirstMatchIn(block).foreach { lm =>
        val url = lm.group(1)
        val title = lm.group(2).replaceAll("<[^>]+>", "").trim
        val snippet = snippetPat.findFirstMatchIn(block).map(_.group(1).replaceAll("<[^>]+>", "").trim).getOrElse("")
        if title.nonEmpty && url.startsWith("http") then results += s"**$title**\n$url\n$snippet"
      }
    }
    if results.nonEmpty then results.mkString("\n\n")
    else extractGenericLinks(html)

  end extractDuckDuckGo

  /** Bing results: <li class="b_algo"> blocks. */
  private def extractBing(html: String): String =
    val results = scala.collection.mutable.ListBuffer.empty[String]
    val bingBlock = """<li class="b_algo"[^>]*>([\s\S]*?)</li>""".r
    bingBlock.findAllMatchIn(html).take(5).foreach { m =>
      val block = m.group(1)
      val linkPat = """<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""".r
      val snippetPat = """<p[^>]*>([\s\S]*?)</p>""".r
      linkPat.findFirstMatchIn(block).foreach { lm =>
        val url = lm.group(1)
        val title = lm.group(2).replaceAll("<[^>]+>", "").trim
        val snippet = snippetPat.findFirstMatchIn(block).map(_.group(1).replaceAll("<[^>]+>", "").trim).getOrElse("")
        if title.nonEmpty && url.startsWith("http") then results += s"**$title**\n$url\n$snippet"
      }
    }
    if results.nonEmpty then results.mkString("\n\n")
    else extractGenericLinks(html)

  end extractBing

  /** Baidu results: <div class="result"> blocks with c-container class. */
  private def extractBaidu(html: String): String =
    val results = scala.collection.mutable.ListBuffer.empty[String]
    // Baidu uses various class combinations: result c-container, result-op c-container, etc.
    val baiduBlock = """<div[^>]*class="[^"]*result[^"]*c-container[^"]*"[^>]*>([\s\S]*?)(?=</div>\s*</div>|$)""".r
    baiduBlock.findAllMatchIn(html).take(5).foreach { m =>
      val block = m.group(1)
      // Baidu wraps links: <a href="URL"><em>Keyword</em>Rest</a>
      val linkPat = """<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""".r
      linkPat.findFirstMatchIn(block).foreach { lm =>
        val url = lm.group(1)
        val title = lm.group(2).replaceAll("<[^>]+>", "").trim
        if title.nonEmpty && url.startsWith("http") then results += s"**$title**\n$url"
      }
    }
    if results.nonEmpty then results.mkString("\n\n")
    else extractGenericLinks(html)

  end extractBaidu

  /** Generic fallback: extract <a> links with meaningful text. */
  private def extractGenericLinks(html: String): String =
    val results = scala.collection.mutable.ListBuffer.empty[String]
    val linkRegex = """<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""".r
    var count = 0
    linkRegex.findAllMatchIn(html).foreach { m =>
      if count < 8 then
        val href = m.group(1)
        val text = m.group(2).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim
        val skipDomains = Set("bing", "baidu", "google", "duckduckgo", "sogou", "yahoo")
        if text.length > 12 && href.startsWith("http") && !skipDomains.exists(href.contains) then
          results += s"**$text**\n$href"
          count += 1
    }
    if results.nonEmpty then results.mkString("\n\n")
    else HttpUtils.stripHtmlTags(html).take(DEFAULT_MAX_CHARS)
  end extractGenericLinks

  // ── Navigation/boilerplate filtering ──────────────────────────────

  /**
   * Chrome a search page carries around its result list. An interstitial page
   * yields ONLY these, and because their link text is longer than the 12-char
   * floor, extractGenericLinks returns them as if they were results — which is
   * one of the two reasons probe2.log's Sogou captcha page passed the length
   * check ("返回首页>>", "意见反馈", "企业推广", "关于搜狗", "免责声明" are all
   * extractable links on that page). Chinese markers are contains-matched
   * (real link text is "返回首页>>"), English ones must equal the whole title
   * so a genuine result like "Sign in with Google best practices" survives.
   */
  private val NavBoilerplateCn = List(
    "返回首页",
    "返回主页",
    "意见反馈",
    "企业推广",
    "关于搜狗",
    "关于我们",
    "免责声明",
    "上一页",
    "下一页",
    "查看更多",
    "更多>>",
    "相关搜索"
  )

  private val NavBoilerplateEn = Set(
    "sign in",
    "sign-in",
    "login",
    "log in",
    "register",
    "privacy policy",
    "terms of use",
    "about us",
    "feedback",
    "advanced search",
    "settings",
    "help",
    "home"
  )

  /** Engine-owned hosts whose root page is a navigation target, not a result. */
  private val EngineHosts: Set[String] =
    Set(
      "sogou.com",
      "www.sogou.com",
      "wx.sogou.com",
      "baidu.com",
      "www.baidu.com",
      "so.com",
      "www.so.com",
      "duckduckgo.com",
      "www.duckduckgo.com",
      "html.duckduckgo.com"
    )

  private def entryTitle(entry: String): String =
    entry.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("").replace("*", "").trim

  private def entryUrl(entry: String): String =
    entry.linesIterator.map(_.trim).find(_.startsWith("http")).getOrElse("")

  /** A bare engine root (scheme://host/ with no path or query) carries no result. */
  private def isBareEngineRoot(url: String): Boolean =
    try
      val u = java.net.URI.create(url)
      val host = Option(u.getHost).map(_.toLowerCase).getOrElse("").stripPrefix("www.")
      val path = Option(u.getPath).getOrElse("")
      val query = Option(u.getQuery).getOrElse("")
      EngineHosts.contains(host) && (path.isEmpty || path == "/") && query.isEmpty
    catch case _: Exception => false

  private[tools] def isNavigationEntry(entry: String): Boolean =
    val title = entryTitle(entry)
    if title.isEmpty then false
    else
      val lower = title.toLowerCase
      val cnHit = NavBoilerplateCn.exists(title.contains)
      val enHit = NavBoilerplateEn.contains(lower.stripSuffix("»").trim)
      cnHit || enHit || isBareEngineRoot(entryUrl(entry))

  /**
   * Drop navigation/boilerplate entries from an extraction. Runs AFTER the
   * garbage-fingerprint strip: fingerprints catch anti-bot machinery, this
   * catches the "a page with no results still has a header and a footer"
   * residue. Both feed the same contract — an engine whose extraction loses
   * everything is FAILED, not silently returned as a thin "success".
   */
  private[tools] def dropNavigationEntries(text: String, engine: String): String =
    text
      .split("\n\n")
      .filter { entry =>
        val nav = entry.trim.nonEmpty && isNavigationEntry(entry)
        if nav then
          logger.warnSync(
            s"dropped navigation entry from $engine (boilerplate link, not a result): " +
              entry.replaceAll("\\s+", " ").take(120)
          )
        !nav
      }
      .mkString("\n\n")

  end dropNavigationEntries

  // ── Single-engine fetch ───────────────────────────────────────────

  /**
   * P1 error classification (1d, 2026-08-25): replace the opaque
   * "No meaningful results" with the actual failure mode so the agent/user
   * can act on it (anti-bot page → try another engine / pinned engine;
   * JS-rendered → the engine is unusable by keyless scraping).
   *
   * 2026-11-22: the captcha branch now covers the CN interstitial vocabulary
   * observed in probe2.log (`SourceVerifyCode`, `此验证码用于确认`, `安全验证`)
   * and not just the literal `验证码` of the older Sogou variant.
   */
  private[tools] def classifyEmpty(html: String, engine: SearchEngine): String =
    val lower = html.toLowerCase
    val mode =
      if lower.contains("antispider") then "anti-bot blocked (antispider)"
      else if isCaptchaPage(lower) then "anti-bot blocked (captcha)"
      else if engine.name == "WeChat" || lower.contains("javascript:void(0)") then "no parseable links (JS-rendered)"
      else "no parseable results"
    s"${engine.name}: $mode"

  private def isCaptchaPage(lowerHtml: String): Boolean =
    lowerHtml.contains("wappass") ||
      lowerHtml.contains("captcha") ||
      lowerHtml.contains("验证码") ||
      lowerHtml.contains("sourceverifycode") ||
      lowerHtml.contains("安全验证")

  /**
   * Minimum usable result entries for an engine response to count as a result
   * list. Threshold rationale: a real search page returns 5–8 result blocks in
   * this codebase's extractors (probe2.log: arXiv 8, Crossref 8, 360 8); a
   * page with 0–1 entries is a header/footer residue or the plain-text
   * fallback (extractGenericLinks' stripHtmlTags tail), never a result list.
   * The DDG region-selector page and the Sogou captcha page both fell in that
   * bucket while being reported as successful answers.
   */
  private[tools] val MIN_USABLE_ENTRIES = 2

  /**
   * Verdict on one engine's response, before it can be raced as a success.
   * Extracts → strips anti-bot garbage → drops navigation residue → requires a
   * credible result list. Left(reason) means "this engine failed"; the caller
   * moves on to the next engine instead of shipping the page text.
   *
   * Negative controls are pinned in WebSearchToolAntiBotSpec: the real Sogou
   * SourceVerifyCode page must be Left; real arXiv/Crossref result payloads
   * must be Right.
   */
  private[tools] def assessEngineResult(engine: SearchEngine, html: String): Either[String, String] =
    val extracted = extractSearchResults(html, engine.name)
    val cleaned = dropNavigationEntries(stripGarbageResults(extracted, engine.name), engine.name)
    val entries = cleaned.split("\n\n").count(_.trim.nonEmpty)
    if cleaned.trim.isEmpty || cleaned.trim.length < 50 then Left(classifyEmpty(html, engine))
    else if entries < MIN_USABLE_ENTRIES then
      logger.warnSync(
        s"$engine response has only $entries usable entr${if entries == 1 then "y" else "ies"} " +
          "(below the result-list floor) — treating the engine as failed"
      )
      Left(
        s"${engine.name}: only $entries usable result${if entries == 1 then "" else "s"} " +
          s"(< $MIN_USABLE_ENTRIES) — no parseable result list"
      )
    else Right(cleaned)

  end assessEngineResult

  private def searchOne(query: String, engine: SearchEngine): Either[String, (SearchEngine, String)] =
    try
      val url = engine.url.replace("{keyword}", java.net.URLEncoder.encode(query, "UTF-8"))
      val backend = SharedBackend.instance
      val request = SharedBackend.BrowserHeaders
        .foldLeft(
          basicRequest
            .get(uri"$url")
            .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
            .readTimeout(FETCH_TIMEOUT.millis)
        ) { case (req, (k, v)) => req.header(k, v) }
        .response(asStringAlways)

      val response = request.send(backend)

      if !response.code.isSuccess then Left(s"${engine.name}: HTTP ${response.code}")
      else assessEngineResult(engine, response.body).map(text => (engine, text))
    catch
      case e: Exception =>
        val raw = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        val classified =
          if raw.toLowerCase.contains("timed out") || raw.toLowerCase.contains("timeout") then
            s"${engine.name}: timeout${if engine.name == "DuckDuckGo" then " (proxy required)" else ""}"
          else s"${engine.name}: ${raw.take(120)}"
        Left(classified)

  // ── Academic API search ────────────────────────────────────────────

  /** Search academic API (arXiv/Semantic Scholar/Crossref). Returns structured paper results. */
  private def searchAcademic(query: String, engine: SearchEngine): Either[String, (SearchEngine, String)] =
    try
      val backend = SharedBackend.instance
      val result: Either[String, String] = engine.name match
        case "arXiv" =>
          // Limit to 5 most important terms to avoid over-constrained AND queries
          val arxivQ = query.split("\\s+").take(5).map(w => s"all:$w").mkString("+AND+")
          val url = s"${engine.url}?search_query=$arxivQ&start=0&max_results=8&sortBy=relevance"
          val resp = basicRequest.get(uri"$url").readTimeout(20_000.millis).response(asStringAlways).send(backend)
          Right(if resp.code.isSuccess then extractArxivResults(resp.body) else "")

        case "Semantic Scholar" =>
          val encoded = java.net.URLEncoder.encode(query, "UTF-8")
          val url = s"${engine.url}?query=$encoded&fields=title,abstract,year,authors,externalIds,openAccessPdf&limit=8"
          // Optional API key for higher rate limits (https://www.semanticscholar.org/product/api#api-key-form)
          val apiKey = sys.env.getOrElse("SEMANTIC_SCHOLAR_API_KEY", "")
          val baseReq = basicRequest.get(uri"$url").readTimeout(20_000.millis).response(asStringAlways)
          val reqWithKey = if apiKey.nonEmpty then baseReq.header("x-api-key", apiKey) else baseReq
          val resp1 = reqWithKey.send(backend)
          if resp1.code.isSuccess then Right(extractSemanticScholarResults(resp1.body))
          else if resp1.code.code == 429 then
            // Rate limited: wait and retry once
            Thread.sleep(5000)
            val resp2 = reqWithKey.send(backend)
            if resp2.code.isSuccess then Right(extractSemanticScholarResults(resp2.body))
            else
              Left(
                "Semantic Scholar rate limited (429). Try engine=arXiv or engine=Crossref instead. " +
                  "For reliable access, apply for a free API key at https://www.semanticscholar.org/product/api#api-key-form " +
                  "and set SEMANTIC_SCHOLAR_API_KEY env var."
              )
          else Right("")

        case "Crossref" =>
          val encoded = java.net.URLEncoder.encode(query, "UTF-8")
          val url =
            s"${engine.url}?query=$encoded&rows=8&select=DOI,title,author,published-print,container-title,abstract"
          val resp = basicRequest
            .get(uri"$url")
            .header("User-Agent", Branding.academicSearchUserAgent)
            .readTimeout(20_000.millis)
            .response(asStringAlways)
            .send(backend)
          Right(if resp.code.isSuccess then extractCrossrefResults(resp.body) else "")

        case other => Left(s"Unknown academic engine: $other")

      result.flatMap { resultText =>
        if resultText.trim.isEmpty then Left(s"${engine.name}: No results or API error")
        else Right((engine, resultText))
      }
    catch case e: Exception => Left(s"${engine.name}: ${e.getMessage.take(120)}")

  /** Parse arXiv Atom XML feed into readable result format. */
  private def extractArxivResults(xml: String): String =
    val entryPat = """<entry>([\s\S]*?)</entry>""".r
    val results = entryPat
      .findAllMatchIn(xml)
      .flatMap { m =>
        val e = m.group(1)
        val title = """<title[^>]*>([\s\S]*?)</title>""".r
          .findFirstMatchIn(e)
          .map(_.group(1).trim.replaceAll("\\s+", " "))
          .getOrElse("")
        val summary = """<summary>([\s\S]*?)</summary>""".r
          .findFirstMatchIn(e)
          .map(_.group(1).trim.replaceAll("\\s+", " ").take(400))
          .getOrElse("")
        val link =
          """<link[^>]*href="([^"]+)"[^>]*rel="alternate"""".r.findFirstMatchIn(e).map(_.group(1)).getOrElse("")
        val published = """<published>([^<]+)</published>""".r.findFirstMatchIn(e).map(_.group(1).take(4)).getOrElse("")
        val authors = """<name>([^<]+)</name>""".r.findAllMatchIn(e).map(_.group(1)).toList.take(5).mkString(", ")
        val doi = """<arxiv:doi[^>]*>([^<]+)""".r.findFirstMatchIn(e).map(d => s" DOI: ${d.group(1)}").getOrElse("")
        if title.nonEmpty then Some(s"**$title**\n$link\nAuthors: $authors ($published)$doi\n$summary") else None
      }
      .toList
    if results.nonEmpty then results.mkString("\n\n") else ""

  end extractArxivResults

  /** Parse Semantic Scholar JSON response. */
  private def extractSemanticScholarResults(json: String): String =
    parser.parse(json).toOption.flatMap(_.hcursor.downField("data").as[List[io.circe.Json]].toOption) match
      case Some(papers) =>
        papers
          .take(8)
          .flatMap { paper =>
            val h = paper.hcursor
            val title = h.downField("title").as[String].toOption.getOrElse("")
            val year = h.downField("year").as[Int].map(_.toString).toOption.getOrElse("")
            val abstract_ = h.downField("abstract").as[String].toOption.getOrElse("").take(400)
            val authors = h
              .downField("authors")
              .as[List[io.circe.Json]]
              .toOption
              .getOrElse(Nil)
              .flatMap(_.hcursor.downField("name").as[String].toOption)
              .take(5)
              .mkString(", ")
            val paperId = h.downField("paperId").as[String].toOption.getOrElse("")
            val doi =
              h.downField("externalIds").downField("DOI").as[String].toOption.map(d => s" DOI: $d").getOrElse("")
            val oaPdf =
              h.downField("openAccessPdf").downField("url").as[String].toOption.map(u => s" OA PDF: $u").getOrElse("")
            val url = s"https://www.semanticscholar.org/paper/$paperId"
            if title.nonEmpty then Some(s"**$title**\n$url\nAuthors: $authors ($year)$doi$oaPdf\n$abstract_") else None
          }
          .mkString("\n\n")
      case None => ""

  /** Parse Crossref JSON response. */
  private def extractCrossrefResults(json: String): String =
    parser
      .parse(json)
      .toOption
      .flatMap(_.hcursor.downField("message").downField("items").as[List[io.circe.Json]].toOption) match
      case Some(items) =>
        items
          .take(8)
          .flatMap { item =>
            val h = item.hcursor
            val title = h.downField("title").as[List[String]].toOption.flatMap(_.headOption).getOrElse("")
            val doi = h.downField("DOI").as[String].toOption.getOrElse("")
            val year = h
              .downField("published-print")
              .downField("date-parts")
              .as[List[List[Int]]]
              .toOption
              .flatMap(_.headOption)
              .flatMap(_.headOption)
              .map(_.toString)
              .getOrElse("")
            val container = h.downField("container-title").as[List[String]].toOption.flatMap(_.headOption).getOrElse("")
            val authors = h
              .downField("author")
              .as[List[io.circe.Json]]
              .toOption
              .getOrElse(Nil)
              .flatMap { a =>
                a.hcursor.downField("family").as[String].toOption
              }
              .take(5)
              .mkString(", ")
            val url = s"https://doi.org/$doi"
            if title.nonEmpty then Some(s"**$title**\n$url\nAuthors: $authors ($year)\nJournal: $container") else None
          }
          .mkString("\n\n")
      case None => ""

  // ── Truncate & filter ─────────────────────────────────────────────

  private def truncate(text: String, max: Int): String =
    if text.length <= max then text else text.take(max) + "\n\n[Result truncated]"

  private def filterDomains(result: String, allowed: List[String], blocked: List[String]): String =
    var r = result
    if blocked.nonEmpty then r = r.split("\n").filter(l => !blocked.exists(l.contains)).mkString("\n")
    if allowed.nonEmpty then r = r.split("\n").filter(l => allowed.exists(l.contains)).mkString("\n")
    r

  // ── Main call: parallel batch search ──────────────────────────────

  /**
   * P1 race semantics (fast-fail poison fix, 2026-08-25): all runs in a
   * batch execute CONCURRENTLY; the FIRST SUCCESS wins and cancels the
   * still-running peers; a failure does NOT cancel its peers — the old
   * `IO.race(...).map(_.merge)` let the fastest failure (Sogou's 415B
   * antispider page) murder a slow real result (360's 467KB page).
   * Only when EVERY run in the batch has failed does the batch count as
   * failed, with all errors aggregated. Extracted at object level so the
   * race semantics are unit-testable with scripted IOs (no network).
   */
  private[tools] def raceFirstSuccess(
    runs: List[IO[Either[String, (SearchEngine, String)]]]
  ): IO[Either[String, (SearchEngine, String)]] =
    if runs.isEmpty then IO.pure(Left("empty engine batch"))
    else
      for
        success <- Deferred[IO, (SearchEngine, String)]
        errors <- Ref.of[IO, Vector[String]](Vector.empty)
        done <- Deferred[IO, Unit]
        remaining <- Ref.of[IO, Int](runs.size)
        countdown = remaining.modify(n => (n - 1, n == 1)).flatMap {
          case true => done.complete(()).void
          case false => IO.unit
        }
        fibers <- runs.traverse { run =>
          (run.attempt.flatMap {
            case Right(Right(res)) => success.complete(res).void
            case Right(Left(err)) => errors.update(_ :+ err)
            case Left(ex) =>
              errors.update(_ :+ Option(ex.getMessage).map(_.take(120)).getOrElse(ex.getClass.getSimpleName))
          } *> countdown).start
        }
        // Race waits for the first terminal signal: a success (Left branch)
        // OR every run finishing (done, Right branch). The winner is
        // NONDETERMINISTIC when the main fiber is descheduled past the slowest
        // run — both Deferreds complete before IO.race even starts, and the
        // internal winner can be `done` even though a success was recorded.
        // Discarding the outcome would resurrect the fast-fail poison (a
        // completed success thrown away for an error aggregate). So the race
        // result is only a wake-up signal: success.tryGet is authoritative —
        // if a success ever landed, it WINS over the error aggregate (qa
        // backend FAIL 2026-08-30: P1-1 red 4x on load avg 10+; fixed here,
        // not by widening test sleeps).
        _ <- IO.race(success.get, done.get)
        _ <- fibers.traverse_(_.cancel)
        result <- success.tryGet.flatMap {
          case Some(res) => IO.pure(Right(res))
          case None => errors.get.map(errs => Left(errs.mkString("\n")))
        }
      yield result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val query = input("query").flatMap(_.asString).getOrElse("")
    val maxChars =
      input("max_results").flatMap(_.asNumber).flatMap(_.toInt).map(Math.max(100, _)).getOrElse(DEFAULT_MAX_CHARS)
    val blocked = input("blocked_domains").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
    val allowed = input("allowed_domains").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
    val engineName = input("engine").flatMap(_.asString)

    // Academic engines: direct API call, no racing
    if engineName.exists(ACADEMIC_NAMES.contains) then
      val engine = engineMap(engineName.get)
      IO.blocking(searchAcademic(query, engine)).map {
        case Right((e, text)) =>
          Right(s"Search engine: ${e.name}\n${tier3Annotation(academic = true)}\n\n${truncate(text, maxChars)}")
        case Left(err) =>
          Left(ToolError(err))
      }
    else
      // General engines: batch racing
      val enginesToTry: List[SearchEngine] = engineName match
        case Some(name) =>
          engineMap.get(name).toList ++ orderedEngines(query).filter(e =>
            e.name != name && !ACADEMIC_NAMES.contains(e.name)
          )
        case None => orderedEngines(query)

      // When a specific engine is specified, try it ALONE first (batch size 1),
      // so it doesn't lose a race to faster-but-lower-quality engines.
      // Fall back to batch racing only if the specified engine fails.
      val batches: List[List[SearchEngine]] = engineName match
        case Some(_) if enginesToTry.nonEmpty =>
          List(enginesToTry.head) :: enginesToTry.tail.grouped(BATCH_SIZE).toList
        case _ =>
          // Auto mode: anti-bot-prone engines are scheduled only in trailing
          // groups (racePlan) — they are a fallback, never a race competitor.
          racePlan(query)

      def raceBatch(batch: List[SearchEngine]): IO[Either[String, (SearchEngine, String)]] =
        raceFirstSuccess(batch.map(e => IO.blocking(searchOne(query, e))))

      def tryBatches(remaining: List[List[SearchEngine]], accErrors: Vector[String]): IO[Either[ToolError, String]] =
        remaining match
          case Nil =>
            IO.pure(
              Left(
                ToolError(
                  s"All search engines failed.\n${accErrors.take(8).mkString("\n")}${
                      if accErrors.length > 8 then s"\n... and ${accErrors.length - 8} more" else ""
                    }"
                )
              )
            )
          case batch :: rest =>
            raceBatch(batch).flatMap {
              case Right((engine, text)) =>
                var result = truncate(text, maxChars)
                result = filterDomains(result, allowed, blocked)
                IO.pure(
                  Right(s"Search engine: ${engine.name}\n${tier3Annotation(academic = false)}\n\n$result")
                )
              case Left(err) =>
                tryBatches(rest, accErrors :+ err)
            }

      tryBatches(batches, Vector.empty)
    end if
  end call

end WebSearchTool
