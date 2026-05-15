package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.{HttpUtils, SharedBackend}
import sttp.client4.*

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object WebFetchTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.tool.webfetch")
  val FETCH_TIMEOUT = 30_000 // per-request timeout
  val DEFAULT_MAX_CHARS = 20_000
  val DEFAULT_MAX_BYTES = 750_000

  private val cache = new ConcurrentHashMap[String, (String, Long)]()
  private val CACHE_TTL_MS = 5 * 60 * 1000 // 5 minutes
  private val MAX_CACHE_SIZE = 100

  val name = "WebFetch"

  val description = """Fetch and read a URL, converting the content to text or markdown.

Usage:
- Only use WebSearch and WebFetch for accessing information beyond your training data.
- Do NOT generate or guess URLs unless you are confident they help the user with programming tasks.
- When the user provides a URL, use WebFetch to retrieve its contents rather than guessing what's there.
- Web search results may contain outdated information — verify critical facts before relying on them.
- Use this tool for accessing URLs provided by the user or found via WebSearch
- Supports HTML pages, plain text, JSON, and other text-based content
- Automatically skips binary content (images, videos, archives)
- Returns the content in markdown (default) or plain text format"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "url" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The URL to fetch".asJson),
        "format" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("markdown".asJson, "text".asJson),
          "description" -> "Output format. Defaults to \"markdown\".".asJson
        ),
        "maxChars" -> io.circe.Json
          .obj("type" -> "number".asJson, "description" -> "Maximum characters to return (default 20000)".asJson)
      ),
      "required" -> io.circe.Json.arr("url".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val url = input("url").flatMap(_.asString).getOrElse("")
    s"""WebFetch("$url")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") || result.startsWith("[Binary") then result.split("\n").headOption.getOrElse(result)
    else s"${result.split("\n").length} lines fetched"

  // ── Cache ──────────────────────────────────────────────────────────

  private def readCache(url: String): Option[String] =
    Option(cache.get(url)).filter(_._2 > System.currentTimeMillis()).map(_._1)

  private def writeCache(url: String, value: String): Unit =
    cache.put(url, (value, System.currentTimeMillis() + CACHE_TTL_MS))
    if cache.size() > MAX_CACHE_SIZE then
      val now = System.currentTimeMillis()
      val it = cache.entrySet().iterator()
      while it.hasNext do
        val entry = it.next()
        if entry.getValue._2 <= now then it.remove()

  // ── HTML extraction ────────────────────────────────────────────────

  /**
   * Extract text from HTML with semantic prioritization.
   *  1. Try `<main>` or `<article>` — the primary content
   *  2. Fall back to `<body>`
   *  3. Extract meta description for context
   */
  private def extractHtmlText(html: String): (Option[String], String, Option[String]) =
    // Meta description
    val metaDesc = """<meta[^>]+name="description"[^>]+content="([^"]+)"""".r
      .findFirstMatchIn(html)
      .map(_.group(1).trim)

    // Title
    val title = "<title[^>]*>([^<]*)</title>".r.findFirstMatchIn(html).map(_.group(1).trim)

    // Clean: remove non-content elements
    val clean = html
      .replaceAll("""(?i)<script[^>]*>[\s\S]*?</script>""", "")
      .replaceAll("""(?i)<style[^>]*>[\s\S]*?</style>""", "")
      .replaceAll("""(?i)<nav[\s\S]*?</nav>""", "")
      .replaceAll("""(?i)<header[\s\S]*?</header>""", "")
      .replaceAll("""(?i)<footer[\s\S]*?</footer>""", "")
      .replaceAll("""(?i)<aside[\s\S]*?</aside>""", "")
      .replaceAll("""(?i)<!--[\s\S]*?-->""", "")

    // Priority: main > article > body > whole doc
    val mainContent = raw"<main[^>]*>([\s\S]*)</main>".r.findFirstMatchIn(clean).map(_.group(1))
    val articleContent = raw"<article[^>]*>([\s\S]*)</article>".r.findFirstMatchIn(clean).map(_.group(1))
    val bodyContent = raw"<body[^>]*>([\s\S]*)</body>".r.findFirstMatchIn(clean).map(_.group(1))

    val source = mainContent.orElse(articleContent).getOrElse(bodyContent.getOrElse(clean))

    val text = source
      .replaceAll("""(?i)</(p|div|section|article|main|h[1-6]|li|tr|br|pre|blockquote)>""", "\n")
      .replaceAll("""<[^>]+>""", "")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")
      .replace("&quot;", "\"")
      .replace("&nbsp;", " ")
      .replace("&#39;", "'")
      .replaceAll("\n{3,}", "\n\n")
      .trim

    (title, text, metaDesc)

  end extractHtmlText

  private def truncate(text: String, maxChars: Int): (String, Boolean) =
    if text.length <= maxChars then (text, false)
    else (text.take(maxChars) + "\n\n[Content truncated]", true)

  // ── Main call ──────────────────────────────────────────────────────

  /** Recursively walk the exception cause chain to build a detailed error message. */
  private def describeError(e: Throwable): String =
    val b = new StringBuilder
    var t: Throwable = e
    var depth = 0
    while t != null && depth < 5 do
      if depth > 0 then b.append(" → ")
      b.append(t.getClass.getSimpleName)
      Option(t.getMessage).foreach(m => if m.nonEmpty then b.append(": ").append(m.take(200)))
      t = t.getCause
      depth += 1
    b.toString

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val url = input("url").flatMap(_.asString).getOrElse("")
    val maxChars =
      input("maxChars").flatMap(_.asNumber).flatMap(_.toInt).map(Math.max(100, _)).getOrElse(DEFAULT_MAX_CHARS)
    val format = input("format").flatMap(_.asString).getOrElse("markdown")

    logger.infoSync(s"WebFetch starting", "url" -> url.take(80), "format" -> format, "maxChars" -> maxChars.toString)

    readCache(url) match
      case Some(cached) =>
        logger.infoSync(s"WebFetch cache hit", "url" -> url.take(80))
        val (t, _) = truncate(cached, maxChars)
        Right(s"[Cached] $t")
      case None =>
        val startMs = System.currentTimeMillis()
        try
          val backend = SharedBackend.instance
          val request = SharedBackend.BrowserHeaders
            .foldLeft(
              basicRequest
                .get(uri"$url")
                .readTimeout(FETCH_TIMEOUT.millis)
            ) { case (req, (k, v)) => req.header(k, v) }
            .followRedirects(true)
            .maxRedirects(5)
            .response(asStringAlways)

          val response = request.send(backend)
          val elapsed = System.currentTimeMillis() - startMs

          logger.infoSync(
            s"WebFetch response received",
            "url" -> url.take(80),
            "status" -> response.code.toString,
            "contentType" -> response.header("content-type").getOrElse("N/A"),
            "bodyLen" -> response.body.length.toString,
            "elapsedMs" -> elapsed.toString
          )

          if !response.code.isSuccess then
            logger.warnSync(s"WebFetch non-success status", "url" -> url.take(80), "status" -> response.code.toString)
            Left(ToolError(s"HTTP ${response.code} ${response.statusText}"))
          else
            val contentType = response.header("content-type").getOrElse("")
            if HttpUtils.isBinaryContentType(contentType) then
              logger.warnSync(s"WebFetch binary content skipped", "url" -> url.take(80), "contentType" -> contentType)
              Right(s"[Binary content: $contentType] Skipped binary file.")
            else
              val body = response.body
              if body.length > DEFAULT_MAX_BYTES then
                logger.warnSync(
                  s"WebFetch response too large",
                  "url" -> url.take(80),
                  "bodyLen" -> body.length.toString
                )
                Left(ToolError(s"Response too large (${body.length} bytes > $DEFAULT_MAX_BYTES)"))
              else
                val parseStart = System.currentTimeMillis()
                val result: (Option[String], String, Option[String]) =
                  if contentType.contains("application/json") then
                    try
                      io.circe.parser.parse(body) match
                        case Right(json) => (None, json.spaces2, None)
                        case Left(_) => (None, body, None)
                    catch case _: Exception => (None, body, None)
                  else if contentType.contains("text/html") then extractHtmlText(body)
                  else (None, body, None)
                val (title, text, metaDesc) = result

                val parseElapsed = System.currentTimeMillis() - parseStart
                logger.infoSync(
                  s"WebFetch parsed",
                  "url" -> url.take(80),
                  "title" -> title.getOrElse("N/A"),
                  "textLen" -> text.length.toString,
                  "metaDesc" -> metaDesc.isDefined.toString,
                  "parseMs" -> parseElapsed.toString
                )

                // Build output: title as markdown heading, meta as blockquote, then body
                val descBlock = metaDesc.filter(_.nonEmpty).map(d => s"> $d\n\n").getOrElse("")
                val output = (title, format) match
                  case (Some(t), "markdown") => s"# $t\n\n$descBlock$text"
                  case (Some(t), _) => s"$t\n\n$descBlock$text"
                  case (_, "markdown") => s"$descBlock$text"
                  case _ => s"$descBlock$text"

                val (truncated, _) = truncate(output, maxChars)
                writeCache(url, truncated)
                logger.infoSync(
                  s"WebFetch success",
                  "url" -> url.take(80),
                  "resultLen" -> truncated.length.toString,
                  "totalMs" -> (System.currentTimeMillis() - startMs).toString
                )
                Right(truncated)
              end if
            end if
          end if
        catch
          case e: java.net.http.HttpTimeoutException =>
            logger.errorSync(
              s"WebFetch timeout",
              "url" -> url.take(80),
              "timeoutSec" -> (FETCH_TIMEOUT / 1000).toString
            )
            Left(ToolError(s"Request timed out after ${FETCH_TIMEOUT / 1000}s"))
          case e: javax.net.ssl.SSLHandshakeException =>
            logger.errorSync(s"WebFetch SSL handshake failed: ${describeError(e)}")
            Left(ToolError(s"SSL handshake failed: ${describeError(e)}"))
          case e: javax.net.ssl.SSLException =>
            logger.errorSync(s"WebFetch SSL error: ${describeError(e)}")
            Left(ToolError(s"SSL error: ${describeError(e)}"))
          case e: java.net.UnknownHostException =>
            logger.errorSync(s"WebFetch DNS failed", "url" -> url.take(80), "error" -> e.getMessage)
            Left(ToolError(s"DNS lookup failed: ${e.getMessage}"))
          case e: java.net.ConnectException =>
            logger.errorSync(s"WebFetch connection refused", "url" -> url.take(80), "error" -> e.getMessage)
            Left(ToolError(s"Connection refused: ${e.getMessage}"))
          case e: Exception =>
            val err = describeError(e)
            logger.errorSync(s"WebFetch unexpected error: $err", "url" -> url.take(80))
            Left(ToolError(s"Fetch failed: $err"))
        end try
    end match
  }
end WebFetchTool
