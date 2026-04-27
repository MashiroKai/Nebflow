package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.HttpUtils
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import scala.concurrent.duration.*

object WebFetchTool extends Tool:
  val FETCH_TIMEOUT = 30_000
  val DEFAULT_MAX_CHARS = 20_000
  val DEFAULT_MAX_BYTES = 750_000

  private val cache = scala.collection.mutable.Map.empty[String, (String, Long)]
  private val CACHE_TTL_MS = 5 * 60 * 1000 // 5 minutes

  val name = "WebFetch"

  val description = """Fetch and read a URL, converting the content to text or markdown.

Usage:
- Use this tool for accessing URLs provided by the user or found via WebSearch
- Supports HTML pages, plain text, JSON, and other text-based content
- Automatically skips binary content (images, videos, archives)
- Returns the content in markdown (default) or plain text format"""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "url" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The URL to fetch".asJson),
      "format" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("markdown".asJson, "text".asJson), "description" -> "Output format. Defaults to \"markdown\".".asJson),
      "maxChars" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "Maximum characters to return (default 20000)".asJson)
    ),
    "required" -> io.circe.Json.arr("url".asJson)
  ))

  def summarize(input: JsonObject): String =
    val url = input("url").flatMap(_.asString).getOrElse("")
    val short = if url.length > 60 then url.take(57) + "..." else url
    s"WebFetch($short)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") || result.startsWith("[Binary") then result.split("\n").headOption.getOrElse(result)
    else s"${result.split("\n").length} lines fetched"

  private def readCache(url: String): Option[String] =
    cache.get(url).filter(_._2 > System.currentTimeMillis()).map(_._1)

  private def writeCache(url: String, value: String): Unit =
    cache(url) = (value, System.currentTimeMillis() + CACHE_TTL_MS)

  private def extractHtmlText(html: String): (Option[String], String) =
    val title = "<title[^>]*>([^<]*)</title>".r.findFirstMatchIn(html).map(_.group(1).trim)

    val clean = html
      .replaceAll("""(?i)<script[^>]*>[\s\S]*?</script>""", "")
      .replaceAll("""(?i)<style[^>]*>[\s\S]*?</style>""", "")
      .replaceAll("""(?i)<nav[\s\S]*?</nav>""", "")
      .replaceAll("""(?i)<header[\s\S]*?</header>""", "")
      .replaceAll("""(?i)<footer[\s\S]*?</footer>""", "")
      .replaceAll("""(?i)<aside[\s\S]*?</aside>""", "")
      .replaceAll("""(?i)<!--[\s\S]*?-->""", "")

    val body = raw"<body[^>]*>([\s\S]*)</body>".r.findFirstMatchIn(clean).map(_.group(1)).getOrElse(clean)

    val text = body
      .replaceAll("""(?i)</(p|div|section|article|main|h[1-6]|li|tr|br)>""", "\n")
      .replaceAll("""<[^>]+>""", "")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")
      .replace("&quot;", "\"")
      .replace("&nbsp;", " ")
      .replaceAll("\n{3,}", "\n\n")
      .trim

    (title, text)

  private def truncate(text: String, maxChars: Int): (String, Boolean) =
    if text.length <= maxChars then (text, false)
    else (text.take(maxChars) + "\n\n[Content truncated]", true)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val url = input("url").flatMap(_.asString).getOrElse("")
    val maxChars = input("maxChars").flatMap(_.asNumber).flatMap(_.toInt).map(Math.max(100, _)).getOrElse(DEFAULT_MAX_CHARS)
    val format = input("format").flatMap(_.asString).getOrElse("markdown")

    readCache(url) match
      case Some(cached) =>
        val (t, _) = truncate(cached, maxChars)
        Right(s"[Cached] $t")
      case None =>
        try
          val backend = HttpClientSyncBackend()
          val request = basicRequest
            .get(uri"$url")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Nebflow/1.0")
            .header("Accept", "text/markdown, text/html;q=0.9, application/json;q=0.8, text/plain;q=0.7, */*;q=0.1")
            .header("Accept-Language", "en-US,en;q=0.9")
            .readTimeout(FETCH_TIMEOUT.millis)
            .response(asStringAlways)

          val response = request.send(backend)

          if !response.code.isSuccess then
            Right(s"HTTP ${response.code} ${response.statusText}")
          else
            val contentType = response.header("content-type").getOrElse("")
            if HttpUtils.isBinaryContentType(contentType) then
              Right(s"[Binary content: $contentType] Skipped binary file.")
            else
              val body = response.body
              if body.length > DEFAULT_MAX_BYTES then
                Right(s"Response too large (${body.length} bytes > $DEFAULT_MAX_BYTES)")
              else
                val (text, title) = if contentType.contains("application/json") then
                  try
                    io.circe.parser.parse(body) match
                      case Right(json) => (json.spaces2, None)
                      case Left(_) => (body, None)
                  catch
                    case _: Exception => (body, None)
                else if contentType.contains("text/html") then
                  val (t, txt) = extractHtmlText(body)
                  (txt, t)
                else
                  (body, None)

                val output = (title, format) match
                  case (Some(t), "markdown") => s"# $t\n\n$text"
                  case (Some(t), _) => s"$t\n\n$text"
                  case _ => text

                val (truncated, _) = truncate(output, maxChars)
                writeCache(url, truncated)
                Right(truncated)
        catch
          case e: java.net.http.HttpTimeoutException =>
            Right(s"Request timed out after ${FETCH_TIMEOUT / 1000}s")
          case e: Exception =>
            Right(s"Fetch failed: ${e.getMessage}")
  }
