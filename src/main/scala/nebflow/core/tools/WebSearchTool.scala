package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.{HttpUtils, SharedBackend}
import sttp.client4.*

import scala.concurrent.duration.*

object WebSearchTool extends Tool:
  val FETCH_TIMEOUT = 10_000 // per-engine timeout
  val DEFAULT_MAX_CHARS = 15_000
  val BATCH_SIZE = 3 // race this many engines at a time

  case class SearchEngine(name: String, url: String, region: String)

  // Ordered by reliability: proven engines first, niche/slow ones last
  val ENGINES: List[SearchEngine] = List(
    SearchEngine("Bing INT", "https://cn.bing.com/search?q={keyword}&ensearch=1", "global"),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/html/?q={keyword}", "global"),
    SearchEngine("Bing CN", "https://cn.bing.com/search?q={keyword}&ensearch=0", "cn"),
    SearchEngine("Baidu", "https://www.baidu.com/s?wd={keyword}", "cn"),
    SearchEngine("Sogou", "https://sogou.com/web?query={keyword}", "cn"),
    SearchEngine("Startpage", "https://www.startpage.com/sp/search?query={keyword}", "global"),
    SearchEngine("Brave", "https://search.brave.com/search?q={keyword}", "global"),
    SearchEngine("Qwant", "https://www.qwant.com/?q={keyword}", "global"),
    SearchEngine("360", "https://www.so.com/s?q={keyword}", "cn"),
    SearchEngine("Toutiao", "https://so.toutiao.com/search?keyword={keyword}", "cn"),
    SearchEngine("Ecosia", "https://www.ecosia.org/search?q={keyword}", "global"),
    SearchEngine("Yahoo", "https://search.yahoo.com/search?p={keyword}", "global"),
    SearchEngine("WeChat", "https://wx.sogou.com/weixin?type=2&query={keyword}", "cn"),
    SearchEngine("Jisilu", "https://www.jisilu.cn/explore/?keyword={keyword}", "cn"),
    SearchEngine("WolframAlpha", "https://www.wolframalpha.com/input?i={keyword}", "global")
  )

  private val engineMap: Map[String, SearchEngine] = ENGINES.map(e => e.name -> e).toMap

  val name = "WebSearch"

  val description = """Search the web using multiple search engines (no API key required).

Available engines (default: Bing INT):
- Global: Bing INT, DuckDuckGo, Startpage, Brave, Qwant, Ecosia, Yahoo, WolframAlpha
- CN: Bing CN, Baidu, Sogou, 360, Toutiao, WeChat Articles, Jisilu

Usage:
- Only use WebSearch and WebFetch for accessing information beyond your training data.
- Do NOT generate or guess URLs unless you are confident they help the user with programming tasks.
- When the user provides a URL, use WebFetch to retrieve its contents rather than guessing what's there.
- Web search results may contain outdated information — verify critical facts before relying on them.
- Provides up-to-date information for current events and recent data
- Use for facts, news, documentation lookups
- Supports search operators: site:, filetype:, "exact match", -exclude, OR
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
          "description" -> "Search engine to use. Defaults to Bing INT.".asJson
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
    val engine = input("engine").flatMap(_.asString).getOrElse("Bing INT")
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

  // ── Single-engine fetch ───────────────────────────────────────────

  private def searchOne(query: String, engine: SearchEngine): Either[String, (SearchEngine, String)] =
    try
      val url = engine.url.replace("{keyword}", java.net.URLEncoder.encode(query, "UTF-8"))
      val backend = SharedBackend.instance
      val request = basicRequest
        .get(uri"$url")
        .header("User-Agent", SharedBackend.UserAgent)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
        .readTimeout(FETCH_TIMEOUT.millis)
        .response(asStringAlways)

      val response = request.send(backend)

      if !response.code.isSuccess then Left(s"${engine.name}: HTTP ${response.code}")
      else
        val html = response.body
        val text = extractSearchResults(html, engine.name)
        if text.trim.isEmpty || text.trim.length < 50 then Left(s"${engine.name}: No meaningful results")
        else Right((engine, text))
    catch case e: Exception => Left(s"${engine.name}: ${e.getMessage.take(120)}")

  // ── Truncate & filter ─────────────────────────────────────────────

  private def truncate(text: String, max: Int): String =
    if text.length <= max then text else text.take(max) + "\n\n[Result truncated]"

  private def filterDomains(result: String, allowed: List[String], blocked: List[String]): String =
    var r = result
    if blocked.nonEmpty then r = r.split("\n").filter(l => !blocked.exists(l.contains)).mkString("\n")
    if allowed.nonEmpty then r = r.split("\n").filter(l => allowed.exists(l.contains)).mkString("\n")
    r

  // ── Main call: parallel batch search ──────────────────────────────

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val query = input("query").flatMap(_.asString).getOrElse("")
    val maxChars =
      input("max_results").flatMap(_.asNumber).flatMap(_.toInt).map(Math.max(100, _)).getOrElse(DEFAULT_MAX_CHARS)
    val blocked = input("blocked_domains").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
    val allowed = input("allowed_domains").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)

    // Build ordered engine list: user-specified engine first, then the rest
    val enginesToTry: List[SearchEngine] = input("engine").flatMap(_.asString) match
      case Some(name) => engineMap.get(name).toList ++ ENGINES.filter(_.name != name)
      case None => ENGINES

    // Split into batches, each batch raced in parallel
    val batches = enginesToTry.grouped(BATCH_SIZE).toList

    def raceBatch(batch: List[SearchEngine]): IO[Either[String, (SearchEngine, String)]] =
      val ios: List[IO[Either[String, (SearchEngine, String)]]] =
        batch.map(e => IO.blocking(searchOne(query, e)))
      // Race all IOs in the batch: first to finish (success or failure) wins
      ios.reduce[IO[Either[String, (SearchEngine, String)]]] { (a, b) =>
        IO.race(a, b).map(_.merge)
      }

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
              IO.pure(Right(s"Search engine: ${engine.name}\n\n$result"))
            case Left(err) =>
              tryBatches(rest, accErrors :+ err)
          }

    tryBatches(batches, Vector.empty)
  end call

end WebSearchTool
