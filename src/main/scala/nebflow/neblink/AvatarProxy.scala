package nebflow.neblink

import cats.effect.IO

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Server-side fetch of the local account avatar, backing the web local-first
  * avatar cache (web/js/avatarCache.js, 2026-09-05 sidebar-restructure 批).
  *
  * Why this exists (2026-09-07 「设置页头像经常加载态」修复): the avatar origin
  * (neblink-server static avatar host) sends NO CORS headers, so the browser's
  * cross-origin `fetch(url, {mode:'cors'})` always failed — the localStorage
  * cache could never populate and every settings-page open fell back to a slow
  * remote `<img>` load. Server-to-server fetch has no CORS, so the gateway
  * proxies the bytes at the same-origin route `GET /api/neblink/avatar`; the
  * client-side cache key stays the remote URL, so identity/invalidation
  * semantics are unchanged.
  *
  * No open-proxy surface: the route fetches ONLY the current identity
  * avatarUrl — the client never supplies a URL. Transport is injected so the
  * contract is unit-tested without network (mirrors LogtoDeviceFlow.Send). */
object AvatarProxy:

  /** One GET: Right((status, contentType, bytes)) / Left(errorMessage). */
  type Fetch = String => IO[Either[String, (Int, Option[String], Array[Byte])]]

  /** Fetch the avatar; non-200 upstream and empty bodies are Left. */
  def fetch(fetchFn: Fetch)(url: String): IO[Either[String, (String, Array[Byte])]] =
    fetchFn(url).map {
      case Left(err) => Left(err)
      case Right((status, contentType, bytes)) =>
        if status == 200 && bytes.nonEmpty then
          Right((contentType.getOrElse("application/octet-stream"), bytes))
        else Left(s"upstream HTTP $status")
    }

  /** Production transport: JDK client, HTTP/1.1, system proxy bypassed, 15s
    * timeouts — the same policy as LogtoDeviceFlow.jdkSend. Transport failures
    * surface as Left so the route degrades to 502 instead of an unhandled IO
    * failure. */
  val jdkFetch: Fetch = url =>
    IO.blocking {
      val client = HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(java.net.ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(15))
        .build()
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .GET()
        .build()
      val resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
      val contentType = Option(resp.headers().firstValue("Content-Type").orElse(null))
      Right((resp.statusCode(), contentType, resp.body()))
    }.handleError(e => Left(Option(e.getMessage).getOrElse("avatar fetch failed")))

end AvatarProxy
