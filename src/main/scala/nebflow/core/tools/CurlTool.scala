package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.HttpUtils
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import scala.concurrent.duration.*

object CurlTool extends Tool:
  val MAX_RESPONSE_CHARS = 100_000

  val name = "Curl"

  val description = """Make an HTTP request to a URL and return the response.

Usage:
- Use for API calls, webhooks, or any HTTP-based interaction
- Supports GET, POST, PUT, PATCH, DELETE and other HTTP methods
- Headers and request body can be provided as JSON
- Automatically formats JSON responses
- Binary responses (images, videos, etc.) are skipped with a summary
- Returns status code, headers, and body"""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "url" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The URL to request".asJson),
      "method" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("GET".asJson, "POST".asJson, "PUT".asJson, "PATCH".asJson, "DELETE".asJson, "HEAD".asJson, "OPTIONS".asJson), "description" -> "HTTP method. Defaults to \"GET\".".asJson),
      "headers" -> io.circe.Json.obj("type" -> "object".asJson, "description" -> "Optional request headers as key-value pairs".asJson),
      "body" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Optional request body (raw string)".asJson),
      "timeout" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "Timeout in seconds (default 30, max 300)".asJson)
    ),
    "required" -> io.circe.Json.arr("url".asJson)
  ))

  def summarize(input: JsonObject): String =
    val method = input("method").flatMap(_.asString).getOrElse("GET").toUpperCase
    val url = input("url").flatMap(_.asString).getOrElse("")
    val short = if url.length > 50 then url.take(47) + "..." else url
    s"Curl($method $short)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("Error") then result.split("\n").headOption.getOrElse(result)
    else result.split("\n").headOption.getOrElse("No response")

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val url = input("url").flatMap(_.asString).getOrElse("")
    val method = input("method").flatMap(_.asString).getOrElse("GET").toUpperCase
    val timeoutSec = input("timeout").flatMap(_.asNumber).flatMap(_.toInt).map(t => Math.min(Math.max(1, t), 300)).getOrElse(30)

    try
      val parsedUrl = new java.net.URI(url)
      if parsedUrl.getScheme != "http" && parsedUrl.getScheme != "https" then
        Right(s"Unsupported protocol: ${parsedUrl.getScheme}")
      else
        val backend = HttpClientSyncBackend()

        val baseReq = method match
          case "GET" => basicRequest.get(uri"$url")
          case "POST" => basicRequest.post(uri"$url")
          case "PUT" => basicRequest.put(uri"$url")
          case "PATCH" => basicRequest.patch(uri"$url")
          case "DELETE" => basicRequest.delete(uri"$url")
          case "HEAD" => basicRequest.head(uri"$url")
          case "OPTIONS" => basicRequest.options(uri"$url")
          case _ => basicRequest.get(uri"$url")

        val withHeaders = input("headers").flatMap(_.asObject) match
          case Some(headers) =>
            headers.toList.foldLeft(baseReq) { case (req, (k, v)) =>
              v.asString match
                case Some(str) => req.header(k, str)
                case None => req
            }
          case None => baseReq

        val withBody = input("body").flatMap(_.asString) match
          case Some(bodyStr) => withHeaders.body(bodyStr)
          case None => withHeaders

        val request = withBody.response(asStringAlways).readTimeout((timeoutSec * 1000).millis)
        val response = request.send(backend)

        val contentType = response.header("content-type").getOrElse("")

        val bodyText = if HttpUtils.isBinaryContentType(contentType) then
          val size = response.header("content-length").getOrElse("unknown size")
          s"[Binary content: $contentType, $size bytes]"
        else
          response.body

        val formattedBody = if contentType.contains("application/json") && bodyText.nonEmpty then
          try
            io.circe.parser.parse(bodyText) match
              case Right(json) => json.spaces2
              case Left(_) => bodyText
          catch
            case _: Exception => bodyText
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
        else
          parts += formattedBody

        Right(parts.mkString("\n"))
    catch
      case e: java.net.http.HttpTimeoutException =>
        Right(s"Request timed out after ${timeoutSec}s")
      case e: Exception =>
        Right(s"Request failed: ${e.getMessage}")
  }
