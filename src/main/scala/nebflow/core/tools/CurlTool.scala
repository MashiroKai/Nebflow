package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.{Defaults, HttpUtils, SharedBackend}
import sttp.client4.*

import scala.concurrent.duration.*

object CurlTool extends Tool:
  val MAX_RESPONSE_CHARS = 100_000

  val name = "Curl"

  val description = """Make an HTTP request to a URL and return the response.

Usage:
- Only use WebSearch and WebFetch for accessing information beyond your training data. Use Curl for API calls, webhooks, or HTTP-based interactions that require specific headers, methods, or body content.
- Do NOT generate or guess URLs unless you are confident they help the user with programming tasks.
- Supports GET, POST, PUT, PATCH, DELETE and other HTTP methods
- Headers and request body can be provided as JSON
- Automatically formats JSON responses
- Binary responses (images, videos, etc.) are skipped with a summary
- Returns status code, headers, and body"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "url" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The URL to request".asJson),
        "method" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr(
            "GET".asJson,
            "POST".asJson,
            "PUT".asJson,
            "PATCH".asJson,
            "DELETE".asJson,
            "HEAD".asJson,
            "OPTIONS".asJson
          ),
          "description" -> "HTTP method. Defaults to \"GET\".".asJson
        ),
        "headers" -> io.circe.Json
          .obj("type" -> "object".asJson, "description" -> "Optional request headers as key-value pairs".asJson),
        "body" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "Optional request body (raw string)".asJson),
        "timeout" -> io.circe.Json
          .obj("type" -> "number".asJson, "description" -> "Timeout in seconds (default 30, max 300)".asJson)
      ),
      "required" -> io.circe.Json.arr("url".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val method = input("method").flatMap(_.asString).getOrElse("GET").toUpperCase
    val url = input("url").flatMap(_.asString).getOrElse("")
    s"""Curl($method "$url")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") then result.split("\n").headOption.getOrElse(result)
    else result.split("\n").headOption.getOrElse("No response")

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val url = input("url").flatMap(_.asString).getOrElse("")
    val method = input("method").flatMap(_.asString).getOrElse("GET").toUpperCase
    val timeoutSec =
      input("timeout")
        .flatMap(_.asNumber)
        .flatMap(_.toInt)
        .map(t => Math.min(Math.max(1, t), Defaults.CurlMaxTimeoutSec))
        .getOrElse(30)

    // Characters illegal in raw URIs but commonly used in API URLs (e.g. Wikipedia's iiprop=url|size)
    def sanitizeUrl(raw: String): String =
      raw
        .replace("|", "%7C")
        .replace("[", "%5B")
        .replace("]", "%5D")
        .replace("{", "%7B")
        .replace("}", "%7D")
        .replace("<", "%3C")
        .replace(">", "%3E")
        .replace("^", "%5E")
        .replace("`", "%60")
        .replace("\\", "%5C")
        .replace(" ", "%20")

    val sanitizedUrl = sanitizeUrl(url)

    try
      val schemeOk = sanitizedUrl.startsWith("http://") || sanitizedUrl.startsWith("https://")
      if !schemeOk then Left(ToolError(s"Unsupported protocol: URL must start with http:// or https://"))
      else
        val backend = SharedBackend.instance
        val baseReq = method match
          case "GET" => basicRequest.get(uri"$sanitizedUrl")
          case "POST" => basicRequest.post(uri"$sanitizedUrl")
          case "PUT" => basicRequest.put(uri"$sanitizedUrl")
          case "PATCH" => basicRequest.patch(uri"$sanitizedUrl")
          case "DELETE" => basicRequest.delete(uri"$sanitizedUrl")
          case "HEAD" => basicRequest.head(uri"$sanitizedUrl")
          case "OPTIONS" => basicRequest.options(uri"$sanitizedUrl")
          case _ => basicRequest.get(uri"$sanitizedUrl")

        // Apply default User-Agent; let user-provided headers override it
        val withDefaultUA = baseReq.header("User-Agent", SharedBackend.UserAgent)

        val withHeaders = input("headers").flatMap(_.asObject) match
          case Some(headers) =>
            headers.toList.foldLeft(withDefaultUA) { case (req, (k, v)) =>
              v.asString match
                case Some(str) => req.header(k, str, replaceExisting = true)
                case None => req
            }
          case None => withDefaultUA

        val withBody = input("body").flatMap(_.asString) match
          case Some(bodyStr) => withHeaders.body(bodyStr)
          case None => withHeaders

        val request = withBody.response(asStringAlways).readTimeout((timeoutSec * 1000).millis)
        val response = request.send(backend)

        val contentType = response.header("content-type").getOrElse("")

        val bodyText = if HttpUtils.isBinaryContentType(contentType) then
          val size = response.header("content-length").getOrElse("unknown size")
          s"[Binary content: $contentType, $size bytes]"
        else response.body

        val formattedBody =
          if contentType.contains("application/json") && bodyText.nonEmpty then
            try
              io.circe.parser.parse(bodyText) match
                case Right(json) => json.spaces2
                case Left(_) => bodyText
            catch case _: Exception => bodyText
          else bodyText

        val parts = scala.collection.mutable.ListBuffer.empty[String]
        parts += s"Status: ${response.code} ${response.statusText}"

        List("content-type", "content-length", "location").foreach { h =>
          response.header(h).foreach(v => parts += s"$h: $v")
        }

        parts += "---"

        if formattedBody.length > MAX_RESPONSE_CHARS then
          parts += formattedBody.take(MAX_RESPONSE_CHARS)
          parts += s"\n[Response truncated at $MAX_RESPONSE_CHARS chars]"
        else parts += formattedBody

        Right(parts.mkString("\n"))
      end if
    catch
      case e: java.net.http.HttpTimeoutException =>
        Left(ToolError(s"Request timed out after ${timeoutSec}s"))
      case e: Exception =>
        Left(ToolError(s"Request failed: ${e.getMessage}"))
    end try
  }
end CurlTool
