package nebflow.neblink

import cats.effect.IO

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
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

  /** Content fingerprint of the proxied bytes — hex sha-256.
    *
    *  sessperf Phase B（2026-09-20，方案卡 §4③）: the web local-first avatar layer
    *  keys its IndexedDB blob entry by the **content** fingerprint, so a source URL
    *  change that yields the same bytes must not re-store（「hash 未变 ⇒ 零重取零
    *  重落盘」）. The gateway is the only party that can compute it (the browser
    *  never gets the remote URL's bytes directly — the avatar origin sends no CORS
    *  headers), so the proxy surfaces it as the `X-Avatar-Sha256` response header
    *  and uses it as the strong ETag（`If-None-Match` ⇒ 304）。
    *
    *  Pure function, zero deps（可单测，与 `fetch` 的注入式契约同精神）。 */
  def sha256Hex(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** Production transport: the shared `OutboundHttpClients.Policy.Direct15s`
    * client (HTTP/1.1, system proxy bypassed, 15 s connect) — the same policy
    * as LogtoDeviceFlow.jdkSend. One memoized instance instead of a new
    * `HttpClient` per avatar miss (D5, 2026-09-13). Transport failures surface
    * as Left so the route degrades to 502 instead of an unhandled IO failure.
    *
    * D1 — why HTTP/1.1 here, and what would flip it:
    *   · Evidence: **none on this peer.** The avatar origin is a static host on
    *     the neblink-server side; no reading has ever been taken on this path,
    *     and the "HTTP/2 reuse + TLS 1.3 resumption clash" rationale was copied
    *     in from the 2026-08-11 upstream LLM-gateway incident (commit a9672dc2).
    *     Topology-wise it shares the neblink Caddy front, where the 2026-09-12
    *     probe saw 14/14 HTTP_2 200 with 0 TLS alerts — that is 未证 for the
    *     trap's absence (intermittent symptom, no logs kept), not evidence.
    *   · Judge-red: a reproduced TLS alert / bad_record_mac, or a GOAWAY /
    *     closed-reset bucket for this path in the outbound-failure counters.
    */
  val jdkFetch: Fetch = url =>
    IO.blocking {
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
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
