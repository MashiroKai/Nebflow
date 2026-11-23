package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, *}
import sttp.client4.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object WebFetchTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.tool.webfetch")
  val FETCH_TIMEOUT = 30_000 // per-request timeout
  val DEFAULT_MAX_CHARS = 20_000
  val DEFAULT_MAX_BYTES = 750_000

  private val CACHE_TTL_MS = 5 * 60 * 1000 // 5 minutes
  private val MAX_CACHE_SIZE = 100

  // LinkedHashMap with LRU eviction — thread-safe under this class's single-threaded access.
  // removeEldestEntry fires automatically on every put, eliminating the need for
  // manual iteration which could race with concurrent access.
  import java.util.{LinkedHashMap, Map as JMap}

  private val cache = new LinkedHashMap[String, (String, Long)](MAX_CACHE_SIZE, 0.75f, true):
    override def removeEldestEntry(eldest: JMap.Entry[String, (String, Long)]): Boolean =
      size() > MAX_CACHE_SIZE

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
- For sites behind anti-bot protection, falls back to Obscura or Playwright
  (if available) for stealth-rendered page extraction.
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
    // Eviction is handled automatically by LinkedHashMap.removeEldestEntry

  // ── Anti-bot detection ─────────────────────────────────────────────

  private def isChallengeTitle(title: String): Boolean =
    val t = title.toLowerCase
    t.contains("just a moment") || title.contains("请稍候") ||
    t.contains("attention required") || t.contains("access denied")

  // ── Soft-failure detection (HTTP 200 that is not the resource) ─────
  //
  // 2026-11-22 end-to-end evaluation (.stand/tmp/websearch-eval/probe3.log):
  // four of eight fetches "succeeded" on pages carrying no usable content —
  // an Earthdata login form (459 chars), an IEEE "JavaScript is disabled" wall
  // (178 chars), an Elsevier redirect stub (15 chars) and a cddis "This page has
  // been moved" notice (217 chars). HTTP 200 is not content: the agent cannot
  // tell "I have the paper" from "I have a login form", which is exactly the
  // failure mode that poisons downstream data collection.
  //
  // Thresholds (deliberately small, so they fire on interstitials only):
  //   * SOFT_FAILURE_MAX_CHARS = 5_000 for JS walls and login walls — every
  //     interstitial in that run was < 500 chars while real content pages were
  //     6.9 KB (JSON) to 8.8 KB (arXiv abstract), so 5 KB separates them with
  //     an order of magnitude of headroom.
  //   * LOGIN_WALL_MAX_CHARS = 3_000: an authentication form is a short page;
  //     a long article that merely links "Sign in" in its header stays OK.
  //   * REDIRECT_SHELL_MAX_CHARS = 500 for meta-refresh/JS redirect stubs.
  //   * MOVED_NOTICE_MAX_CHARS = 1_000 for "This page has been moved" notices.
  //   * MIN_HTML_CONTENT_CHARS = 200: generic floor for text/html pages — a
  //     15-char "# Redirecting" must not be reported as a successful read.
  private[tools] val SOFT_FAILURE_MAX_CHARS = 5_000
  private[tools] val LOGIN_WALL_MAX_CHARS = 3_000
  private[tools] val REDIRECT_SHELL_MAX_CHARS = 500
  private[tools] val MOVED_NOTICE_MAX_CHARS = 1_000
  private[tools] val MIN_HTML_CONTENT_CHARS = 200

  /** A 200-OK response whose content is not the requested resource. */
  private[tools] case class SoftFailure(tag: String, detail: String, cause: String, suggestion: String)

  private val RedirectStubMarkers = List("redirecting", "being redirected", "redirected to", "跳转中", "正在跳转")

  private val MovedNoticeMarkers = List(
    "this page has been moved",
    "page has been moved",
    "has been moved to",
    "page has moved",
    "页面已移动",
    "页面已迁移"
  )

  private val JsRequiredMarkers = List(
    "javascript is disabled",
    "javascript is required",
    "enable javascript",
    "please enable js",
    "requires javascript",
    "we need to verify that you're not a robot",
    "please turn on javascript",
    "请启用javascript",
    "请开启javascript"
  )
  private val LoginWallTitleMarkers = List("login", "log in", "sign in", "signin", "登录", "登陆", "认证")

  private val LoginWallFormMarkers =
    List("username", "password", "sign in", "log in", "please log in", "please sign in", "登录", "用户名", "密码")

  /**
   * Detect "soft failure" pages: HTTP 200 with a payload that is not the
   * resource. Pure (no IO, no network) so the four real samples from the
   * evaluation are pinned as regression cases, alongside negative controls
   * (a real abstract page and a real JSON payload must stay OK).
   */
  private[tools] def detectSoftFailure(
    title: Option[String],
    text: String,
    contentType: String
  ): Option[SoftFailure] =
    val body = text.trim
    val lower = body.toLowerCase
    val titleLower = title.map(_.toLowerCase).getOrElse("")
    val head = lower.take(SOFT_FAILURE_MAX_CHARS)
    def hits(markers: List[String], haystack: String): Boolean = markers.exists(haystack.contains)

    if body.length <= REDIRECT_SHELL_MAX_CHARS && (
        titleLower.contains("redirect") || hits(RedirectStubMarkers, head)
      )
    then
      Some(
        SoftFailure(
          "soft-failure: redirect-loop",
          s"the server returned a redirect shell (${body.length} chars), not the target document",
          "the URL answers with a meta-refresh / JS redirect that this non-JS fetch cannot follow, " +
            "or the redirect chain ends on an interstitial",
          "retry the final URL directly (follow the Location/meta-refresh target), or fetch it with a browser"
        )
      )
    else if body.length <= MOVED_NOTICE_MAX_CHARS && hits(MovedNoticeMarkers, lower) then
      Some(
        SoftFailure(
          "soft-failure: moved",
          s"the server returned a 'this page has been moved' notice (${body.length} chars)",
          "the resource was relocated; the server answers 200 with a stub instead of 301/302",
          "follow the link named in the notice (usually a new host/path) and fetch that URL instead"
        )
      )
    else if body.length <= SOFT_FAILURE_MAX_CHARS && hits(JsRequiredMarkers, titleLower + "\n" + head) then
      Some(
        SoftFailure(
          "soft-failure: js-required",
          s"the server returned a JavaScript wall (${body.length} chars), not the content",
          "the page renders client-side; this fetch only sees the <noscript> fallback " +
            "(common for IEEE, Elsevier, Cloudflare-style challenges)",
          "use the browser fallback (Obscura/Playwright) or an API/OA route (arXiv, Unpaywall, publisher API)"
        )
      )
    else if body.length <= LOGIN_WALL_MAX_CHARS && (
        hits(LoginWallTitleMarkers, titleLower) || lower.take(500).contains("login")
      ) && hits(LoginWallFormMarkers, lower)
    then
      Some(
        SoftFailure(
          "soft-failure: login-wall",
          s"the server returned an authentication page (${body.length} chars), not the resource",
          "the URL requires credentials/session; the redirect went to a login form " +
            "(e.g. Earthdata/URS OAuth) and no credentials were presented",
          "use an authenticated client (token/cookie), or resolve the resource through an open route " +
            "(OA copy, API endpoint, Earthdata token)"
        )
      )
    else if contentType.contains("text/html") && body.length < MIN_HTML_CONTENT_CHARS then
      Some(
        SoftFailure(
          "soft-failure: thin-content",
          s"the page yielded only ${body.length} chars of text (floor is $MIN_HTML_CONTENT_CHARS)",
          "either the page body is almost empty (script-driven shell) or extraction lost the content",
          "treat this as 'no content retrieved'; retry with a browser, a print/summary view, or an API endpoint"
        )
      )
    else None

  end detectSoftFailure

  private def softFailureMessage(f: SoftFailure, status: Int, chars: Int, text: String): String =
    val preview = text.replaceAll("\\s+", " ").trim.take(200)
    s"""${f.tag} (HTTP $status, $chars chars of page text) — ${f.detail}.
       |Cause: ${f.cause}.
       |Suggest: ${f.suggestion}.
       |The HTTP status was a success but this is NOT the requested content. Page text begins: "$preview"""".stripMargin

  // ── HTML extraction ────────────────────────────────────────────────

  private def extractHtmlText(html: String): (Option[String], String, Option[String]) =
    val metaDesc = """<meta[^>]+name="description"[^>]+content="([^"]+)"""".r
      .findFirstMatchIn(html)
      .map(_.group(1).trim)

    val title = "<title[^>]*>([^<]*)</title>".r.findFirstMatchIn(html).map(_.group(1).trim)

    val clean = html
      .replaceAll("""(?i)<script[^>]*>[\s\S]*?</script>""", "")
      .replaceAll("""(?i)<style[^>]*>[\s\S]*?</style>""", "")
      .replaceAll("""(?i)<nav[\s\S]*?</nav>""", "")
      .replaceAll("""(?i)<header[\s\S]*?</header>""", "")
      .replaceAll("""(?i)<footer[\s\S]*?</footer>""", "")
      .replaceAll("""(?i)<aside[\s\S]*?</aside>""", "")
      .replaceAll("""(?i)<!--[\s\S]*?-->""", "")

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

  // ── Output formatting ──────────────────────────────────────────────

  private def buildOutput(title: Option[String], text: String, metaDesc: Option[String], format: String): String =
    val descBlock = metaDesc.filter(_.nonEmpty).map(d => s"> $d\n\n").getOrElse("")
    (title, format) match
      case (Some(t), "markdown") => s"# $t\n\n$descBlock$text"
      case (Some(t), _) => s"$t\n\n$descBlock$text"
      case (_, "markdown") => s"$descBlock$text"
      case _ => s"$descBlock$text"

  // ── Outcome types ──────────────────────────────────────────────────

  private enum HttpOutcome:
    case Success(text: String)
    case NeedBrowser
    case Error(msg: String)

  // ── Layer 1: HTTP fetch ────────────────────────────────────────────

  private def tryHttp(url: String, maxChars: Int, format: String): IO[HttpOutcome] =
    IO.blocking {
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
          s"WebFetch HTTP response",
          "url" -> url.take(80),
          "status" -> response.code.toString,
          "bodyLen" -> response.body.length.toString,
          "elapsedMs" -> elapsed.toString
        )

        // Anti-bot: 403 → try browser
        if response.code.code == 403 then
          logger.infoSync(s"WebFetch 403, will try browser fallback", "url" -> url.take(80))
          HttpOutcome.NeedBrowser
        else if !response.code.isSuccess then HttpOutcome.Error(s"HTTP ${response.code} ${response.statusText}")
        else
          val contentType = response.header("content-type").getOrElse("")
          if HttpUtils.isBinaryContentType(contentType) then
            HttpOutcome.Error(s"[Binary content: $contentType] Skipped binary file.")
          else
            val body = response.body
            if body.length > DEFAULT_MAX_BYTES then
              HttpOutcome.Error(s"Response too large (${body.length} bytes > $DEFAULT_MAX_BYTES)")
            else
              // Parse content
              val (title, text, metaDesc) =
                if contentType.contains("application/json") then
                  try
                    io.circe.parser.parse(body) match
                      case Right(json) => (None, json.spaces2, None)
                      case Left(_) => (None, body, None)
                  catch case _: Exception => (None, body, None)
                else if contentType.contains("text/html") then extractHtmlText(body)
                else (None, body, None)

              // Detect JS challenge page (HTTP 200 but actually a challenge)
              if title.exists(isChallengeTitle) then
                logger.infoSync(
                  s"WebFetch challenge page detected, will try browser",
                  "url" -> url.take(80),
                  "title" -> title.getOrElse("")
                )
                HttpOutcome.NeedBrowser
              else
                // Soft failure: 200 OK but the payload is a wall/stub, not the
                // resource. Reported as an ERROR carrying its cause — never as a
                // silent success (2026-11-22 evaluation: 4 of 8 "OK" fetches were
                // a login form, a JS wall, a redirect stub and a moved notice).
                detectSoftFailure(title, text, contentType) match
                  case Some(f) =>
                    logger.warnSync(
                      s"WebFetch soft failure detected: ${f.tag}",
                      "url" -> url.take(80),
                      "chars" -> text.trim.length.toString,
                      "title" -> title.getOrElse("")
                    )
                    HttpOutcome.Error(softFailureMessage(f, response.code.code, text.trim.length, text))
                  case None =>
                    val output = buildOutput(title, text, metaDesc, format)
                    val (truncated, _) = truncate(output, maxChars)
                    writeCache(url, truncated)
                    logger.infoSync(
                      s"WebFetch HTTP success",
                      "url" -> url.take(80),
                      "resultLen" -> truncated.length.toString
                    )
                    HttpOutcome.Success(truncated)
              end if
            end if
          end if
        end if
      catch
        case e: java.net.http.HttpTimeoutException =>
          HttpOutcome.Error(s"Request timed out after ${FETCH_TIMEOUT / 1000}s")
        case e: javax.net.ssl.SSLHandshakeException =>
          HttpOutcome.Error(s"SSL handshake failed: ${describeError(e)}")
        case e: javax.net.ssl.SSLException =>
          HttpOutcome.Error(s"SSL error: ${describeError(e)}")
        case e: java.net.UnknownHostException =>
          HttpOutcome.Error(s"DNS lookup failed: ${e.getMessage}")
        case e: java.net.ConnectException =>
          HttpOutcome.Error(s"Connection refused: ${e.getMessage}")
        case e: Exception =>
          HttpOutcome.Error(s"Fetch failed: ${describeError(e)}")
      end try
    }
  end tryHttp

  // ── Layer 2: Browser fallback (Obscura → Playwright) ───────────────

  private def tryBrowser(url: String, maxChars: Int, format: String): IO[Either[ToolError, String]] =
    BrowserManager.fetch(url, maxWaitSeconds = 15).map { result =>
      if result.status == 0 then Left(ToolError(s"Anti-bot page could not be fetched: ${result.title}"))
      else
        val (title, text, metaDesc) =
          if result.isMarkdown then (Option(result.title).filter(_.nonEmpty), result.content, None)
          else
            val (t, txt, md) = extractHtmlText(result.content)
            (t.orElse(Option(result.title).filter(_.nonEmpty)), txt, md)
        val output = buildOutput(title, text, metaDesc, format)
        val (truncated, _) = truncate(output, maxChars)
        writeCache(url, truncated)
        logger.infoSync(s"WebFetch browser success", "url" -> url.take(80), "resultLen" -> truncated.length.toString)
        Right(truncated)
    }

  // ── Main entry: layered fallback ───────────────────────────────────

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val url = input("url").flatMap(_.asString).getOrElse("")
    val maxChars =
      input("maxChars").flatMap(_.asNumber).flatMap(_.toInt).map(Math.max(100, _)).getOrElse(DEFAULT_MAX_CHARS)
    val format = input("format").flatMap(_.asString).getOrElse("markdown")

    logger.infoSync(s"WebFetch starting", "url" -> url.take(80), "format" -> format)

    readCache(url) match
      case Some(cached) =>
        logger.infoSync(s"WebFetch cache hit", "url" -> url.take(80))
        val (t, _) = truncate(cached, maxChars)
        IO.pure(Right(s"[Cached] $t"))

      case None =>
        // Layer 1: HTTP
        tryHttp(url, maxChars, format).flatMap {
          case HttpOutcome.Success(text) => IO.pure(Right(text))
          case HttpOutcome.Error(msg) => IO.pure(Left(ToolError(msg)))
          case HttpOutcome.NeedBrowser =>
            // Layer 2: Obscura → Playwright (if available)
            tryBrowser(url, maxChars, format)
        }
    end match
  end call

end WebFetchTool
