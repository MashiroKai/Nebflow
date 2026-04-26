package nebscala.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebscala.shared.HttpUtils
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import scala.concurrent.duration.*

object WebSearchTool extends Tool:
  val FETCH_TIMEOUT = 20_000
  val DEFAULT_MAX_CHARS = 15_000

  case class SearchEngine(name: String, url: String, region: String)

  val ENGINES: List[SearchEngine] = List(
    SearchEngine("Bing INT", "https://cn.bing.com/search?q={keyword}&ensearch=1", "global"),
    SearchEngine("Bing CN", "https://cn.bing.com/search?q={keyword}&ensearch=0", "cn"),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/html/?q={keyword}", "global"),
    SearchEngine("Baidu", "https://www.baidu.com/s?wd={keyword}", "cn"),
    SearchEngine("Sogou", "https://sogou.com/web?query={keyword}", "cn"),
    SearchEngine("Startpage", "https://www.startpage.com/sp/search?query={keyword}", "global"),
    SearchEngine("Brave", "https://search.brave.com/search?q={keyword}", "global"),
    SearchEngine("Qwant", "https://www.qwant.com/?q={keyword}", "global"),
    SearchEngine("Yahoo", "https://search.yahoo.com/search?p={keyword}", "global"),
    SearchEngine("Ecosia", "https://www.ecosia.org/search?q={keyword}", "global"),
    SearchEngine("360", "https://www.so.com/s?q={keyword}", "cn"),
    SearchEngine("Toutiao", "https://so.toutiao.com/search?keyword={keyword}", "cn"),
    SearchEngine("WeChat", "https://wx.sogou.com/weixin?type=2&query={keyword}", "cn"),
    SearchEngine("Jisilu", "https://www.jisilu.cn/explore/?keyword={keyword}", "cn"),
    SearchEngine("WolframAlpha", "https://www.wolframalpha.com/input?i={keyword}", "global"),
    SearchEngine("Google HK", "https://www.google.com.hk/search?q={keyword}", "global"),
    SearchEngine("Google", "https://www.google.com/search?q={keyword}", "global"),
  )

  val name = "WebSearch"

  val description = """Search the web using multiple search engines (no API key required).

Available engines (default: Bing INT):
- Global: Bing INT, DuckDuckGo, Startpage, Brave, Qwant, Yahoo, Ecosia, WolframAlpha, Google
- CN: Bing CN, Baidu, Sogou, 360, Toutiao, WeChat Articles, Jisilu

Usage:
- Provides up-to-date information for current events and recent data
- Use for facts, news, documentation lookups
- Supports search operators: site:, filetype:, "exact match", -exclude, OR
- IMPORTANT: You MUST include a "Sources:" section listing all relevant URLs"""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "query" -> io.circe.Json.obj("type" -> "string".asJson, "minLength" -> io.circe.Json.fromInt(2), "description" -> "The search query".asJson),
      "engine" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.fromValues(ENGINES.map(_.name.asJson)), "description" -> "Search engine to use. Defaults to Bing INT.".asJson),
      "max_results" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "Max result characters to return (default 15000)".asJson, "minimum" -> io.circe.Json.fromInt(100)),
      "allowed_domains" -> io.circe.Json.obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson), "description" -> "Only include results from these domains".asJson),
      "blocked_domains" -> io.circe.Json.obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson), "description" -> "Exclude results from these domains".asJson)
    ),
    "required" -> io.circe.Json.arr("query".asJson)
  ))

  def summarize(input: JsonObject): String =
    val query = input("query").flatMap(_.asString).getOrElse("")
    val engine = input("engine").flatMap(_.asString).getOrElse("Bing INT")
    val short = if query.length > 50 then query.take(47) + "..." else query
    s"WebSearch($engine): $short"

  def summarizeResult(input: JsonObject, result: String): String =
    result.split("\n").headOption.getOrElse("done")

  private def extractSearchResults(html: String, engine: String): String =
    val results = scala.collection.mutable.ListBuffer.empty[String]

    // Bing extraction
    if engine.contains("Bing") then
      val bingResults = "<li class=\"b_algo\"[\\s\\S]*?</li>".r.findAllMatchIn(html).toList.take(5)
      bingResults.foreach { m =>
        val r = m.matched
        val titleMatch = "<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>".r.findFirstMatchIn(r)
        val snippetMatch = "<p>([\\s\\S]*?)</p>".r.findFirstMatchIn(r)
        titleMatch.foreach { tm =>
          val url = tm.group(1)
          val title = tm.group(2).replaceAll("<[^>]+>", "").trim
          val snippet = snippetMatch.map(_.group(1).replaceAll("<[^>]+>", "").trim).getOrElse("")
          if title.nonEmpty && url.startsWith("http") then
            results += s"**$title**\n$url\n$snippet"
        }
      }

    // Baidu extraction
    if engine.contains("Baidu") && results.isEmpty then
      val baiduResults = "<div class=\"result\"[\\s\\S]*?</div>\\s*</div>".r.findAllMatchIn(html).toList.take(5)
      baiduResults.foreach { m =>
        val r = m.matched
        val titleMatch = "<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>".r.findFirstMatchIn(r)
        titleMatch.foreach { tm =>
          val url = tm.group(1)
          val title = tm.group(2).replaceAll("<[^>]+>", "").trim
          if title.nonEmpty && url.startsWith("http") then
            results += s"**$title**\n$url"
        }
      }

    // Generic link extraction
    if results.isEmpty then
      val linkRegex = "<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>".r
      var count = 0
      linkRegex.findAllMatchIn(html).foreach { m =>
        if count < 8 then
          val href = m.group(1)
          val text = m.group(2).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim
          if text.length > 15 && href.startsWith("http") && !href.contains("bing") && !href.contains("baidu") && !href.contains("google") then
            results += s"**$text**\n$href\n"
            count += 1
      }

    if results.nonEmpty then results.mkString("\n\n")
    else HttpUtils.stripHtmlTags(html).take(DEFAULT_MAX_CHARS)

  private def truncate(text: String, max: Int): String =
    if text.length <= max then text else text.take(max) + "\n\n[Result truncated]"

  private def searchOne(query: String, engine: SearchEngine): Either[String, String] =
    try
      val url = engine.url.replace("{keyword}", java.net.URLEncoder.encode(query, "UTF-8"))
      val backend = HttpClientSyncBackend()
      val request = basicRequest
        .get(uri"$url")
        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
        .readTimeout(FETCH_TIMEOUT.millis)
        .response(asStringAlways)

      val response = request.send(backend)

      if !response.code.isSuccess then
        Left(s"HTTP ${response.code}")
      else
        val html = response.body
        val text = extractSearchResults(html, engine.name)
        if text.trim.isEmpty || text.trim.length < 50 then
          Left("No meaningful results extracted")
        else
          Right(text)
    catch
      case e: Exception => Left(e.getMessage)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val query = input("query").flatMap(_.asString).getOrElse("")
    val maxChars = input("max_results").flatMap(_.asNumber).flatMap(_.toInt).map(Math.max(100, _)).getOrElse(DEFAULT_MAX_CHARS)
    val blocked = input("blocked_domains").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
    val allowed = input("allowed_domains").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)

    val enginesToTry = input("engine").flatMap(_.asString) match
      case Some(name) =>
        ENGINES.find(_.name == name).toList ++ ENGINES.filter(_.name != name)
      case None => ENGINES

    val errors = scala.collection.mutable.ListBuffer.empty[String]

    val found = enginesToTry.iterator.map { engine =>
      searchOne(query, engine) match
        case Right(text) => Some((engine, text))
        case Left(err) =>
          errors += s"${engine.name}: $err"
          None
    }.find(_.isDefined).flatten

    found match
      case Some((engine, text)) =>
        var result = truncate(text, maxChars)
        if blocked.nonEmpty then
          result = result.split("\n").filter(l => !blocked.exists(l.contains)).mkString("\n")
        if allowed.nonEmpty then
          result = result.split("\n").filter(l => allowed.exists(l.contains)).mkString("\n")
        Right(s"Search engine: ${engine.name}\n\n$result")
      case None =>
        Left(ToolError(s"All search engines failed.\n${errors.take(5).mkString("\n")}${if errors.length > 5 then s"\n... and ${errors.length - 5} more" else ""}"))
  }
