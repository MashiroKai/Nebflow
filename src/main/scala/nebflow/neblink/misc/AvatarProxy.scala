package nebflow.neblink.misc

import nebflow.neblink.core.*

import nebflow.neblink.logto.*
import cats.effect.IO
import nebflow.shared.Branding

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.annotation.tailrec

/**
 * Server-side fetch of the local account avatar, backing the web local-first
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
 * contract is unit-tested without network (mirrors LogtoTransport.Send).
 */
object AvatarProxy:

  /** One GET: Right((status, contentType, bytes)) / Left(errorMessage). */
  type Fetch = String => IO[Either[String, (Int, Option[String], Array[Byte])]]

  /** Fetch the avatar; non-200 upstream and empty bodies are Left. */
  def fetch(fetchFn: Fetch)(url: String): IO[Either[String, (String, Array[Byte])]] =
    fetchFn(url).map {
      case Left(err) => Left(err)
      case Right((status, contentType, bytes)) =>
        if status == 200 && bytes.nonEmpty then Right((contentType.getOrElse("application/octet-stream"), bytes))
        else Left(s"upstream HTTP $status")
    }

  /**
   * Content fingerprint of the proxied bytes — hex sha-256.
   *
   *  sessperf Phase B（2026-09-20，方案卡 §4③）: the web local-first avatar layer
   *  keys its IndexedDB blob entry by the **content** fingerprint, so a source URL
   *  change that yields the same bytes must not re-store（「hash 未变 ⇒ 零重取零
   *  重落盘」）. The gateway is the only party that can compute it (the browser
   *  never gets the remote URL's bytes directly — the avatar origin sends no CORS
   *  headers), so the proxy surfaces it as the `X-Avatar-Sha256` response header
   *  and uses it as the strong ETag（`If-None-Match` ⇒ 304）。
   *
   *  Pure function, zero deps（可单测，与 `fetch` 的注入式契约同精神）。
   */
  def sha256Hex(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /**
   * Production transport: the shared `OutboundHttpClients.Policy.Direct15s`
   * client (HTTP/1.1, system proxy bypassed, 15 s connect) — the same policy
   * as LogtoTransport.jdkSend. One memoized instance instead of a new
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
   *
   * 跳转跟随（2026-11-04 旧域 301 批 —— 这一条把「301 当失败」的缺陷闭掉）:
   *   JDK `HttpClient` 默认 `Redirect.NEVER`，而 301 会原样进 `fetch` 的
   *   `status != 200` 分支 ⇒ 路由 502 `upstream HTTP 301`。线上触发条件已实测：
   *   好友头像 URL 仍指旧域 `mashiro.staging.nebflow.space`，域名迁移后该域
   *   301 到 `staging.mystand.space`（2026-11-04 20:50 CST 实测，见本批报告）
   *   ⇒ 浏览器直连自己会跟，画面看不出坏；**服务端字节缓存永远建不起来**，
   *   每次都要回源。故这里改为**手动跳转循环**（不走 JDK 的
   *   `Redirect.NORMAL`/`ALWAYS`：那两个没有跳数上限也不能做 host 白名单）：
   *     · 跳数上限 [[MaxRedirects]]（固定 3；超限 ⇒ `Left`，绝不打转）；
   *     · 只跟**同 origin** 或**产品域族内**的目标（[[redirectHostFamilies]]：
   *       当前品牌域 `Branding.domain` 单一真源 + 旧服务子域族），其余一律
   *       `Left` —— 代返代理不得被一次跳转改造成开放代理/SSRF 面；
   *     · 拒 https→http 降级与非 http(s) scheme。
   *   起点 URL 本身**不做**白名单（它对端由 NebLink 服务端给出，形态含
   *   GitHub 头像等第三方主机；白名单只约束「跟到哪里」，不约束「取谁」）。
   */
  val jdkFetch: Fetch = url =>
    IO.blocking {
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
      val families = redirectHostFamilies
      def send(uri: URI): HttpResponse[Array[Byte]] =
        val request = HttpRequest
          .newBuilder(uri)
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build()
        client.send(request, HttpResponse.BodyHandlers.ofByteArray())
      @tailrec
      def loop(uri: URI, hops: Int): Either[String, (Int, Option[String], Array[Byte])] =
        val resp = send(uri)
        val location = Option(resp.headers().firstValue("Location").orElse(null)).map(_.trim).filter(_.nonEmpty)
        if isRedirectStatus(resp.statusCode()) && location.nonEmpty then
          if hops >= MaxRedirects then
            Left(s"redirect chain exceeded $MaxRedirects hops (last ${resp.statusCode()} at ${describe(uri)})")
          else
            resolveRedirect(uri, location.get).flatMap(next => redirectAllowed(uri, next, families)) match
              case Left(err) => Left(err)
              case Right(next) => loop(next, hops + 1)
        else
          val contentType = Option(resp.headers().firstValue("Content-Type").orElse(null))
          Right((resp.statusCode(), contentType, resp.body()))
      loop(URI.create(url), 0)
    }.handleError(e => Left(Option(e.getMessage).getOrElse("avatar fetch failed")))

  // ── 跳转策略（纯函数，零 IO —— 与 `fetch` 的注入式契约同精神）────────────

  /** 一次取数最多跟随的跳数（起点不计）。 */
  val MaxRedirects: Int = 3

  /** 带 Location 才跟的 3xx 集合（300/304 不是跳转）。 */
  private val RedirectStatuses: Set[Int] = Set(301, 302, 303, 307, 308)

  /**
   * 该状态码是否属于「跟着 Location 走」的跳转面。公开只为可测（跳转面是
   * 本批缺陷的类级判据：少一种 3xx 就是同一故障重演一个口子）。
   */
  def isRedirectStatus(code: Int): Boolean = RedirectStatuses.contains(code)

  /**
   * 旧**服务子域族**。裸域 `nebflow.space` 是退役的**浏览器入口**
   * （`scripts/check-legacy-domain.mjs` 拦的就是它），与这里无关：本常量只表示
   * 「迁移前的服务子域仍可能 301」这一事实面，`neblink.`/`auth.`/`staging.` 等
   * 服务子域从未随品牌迁移（REBRAND.md 裁定：服务地址不是浏览器入口）。
   * 白名单是**只读跟随面**：它让 301 能被跟到新域，绝不产生任何对外链接。
   */
  val RetiredServiceHostFamily: String = "nebflow.space"

  /** 允许跟随的目标 host 族：当前品牌域（单一真源）+ 旧服务子域族。 */
  def redirectHostFamilies: Seq[String] =
    Seq(Branding.domain, RetiredServiceHostFamily)
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .distinct

  /** host 是否落在某一族内（族本身或其子域）。 */
  def hostInFamilies(host: String, families: Seq[String]): Boolean =
    val h = Option(host).map(_.trim.toLowerCase).getOrElse("")
    h.nonEmpty && families.exists(f => h == f || h.endsWith("." + f))

  private def defaultPortOf(uri: URI): Int =
    Option(uri.getScheme).map(_.toLowerCase) match
      case Some("https") => 443
      case _ => 80

  private def portOf(uri: URI): Int =
    if uri.getPort == -1 then defaultPortOf(uri) else uri.getPort

  /** 同 origin = scheme + host + 有效端口（缺省端口归一）全同。 */
  def sameOrigin(a: URI, b: URI): Boolean =
    Option(a.getScheme).map(_.toLowerCase) == Option(b.getScheme).map(_.toLowerCase) &&
      Option(a.getHost).map(_.toLowerCase) == Option(b.getHost).map(_.toLowerCase) &&
      portOf(a) == portOf(b)

  /** 供错误文本用的 `host:port` —— 不含 query（可能带签名参数）。 */
  private[neblink] def describe(uri: URI): String =
    val host = Option(uri.getHost).getOrElse("?")
    s"$host:${portOf(uri)}"

  /** Location 解析（相对地址按 RFC 3986 相对当前 URL 解析）；畸形 ⇒ Left。 */
  def resolveRedirect(from: URI, location: String): Either[String, URI] =
    val raw = Option(location).map(_.trim).getOrElse("")
    if raw.isEmpty then Left("redirect without Location")
    else
      try
        val next = from.resolve(raw)
        if Option(next.getHost).exists(_.nonEmpty) then Right(next)
        else Left(s"redirect to unusable location: ${describe(next)}")
      catch
        case e: IllegalArgumentException =>
          // 不回显整条 location（可能带签名 query），只点名解析失败。
          Left(s"redirect to malformed location (${Option(e.getMessage).getOrElse("parse error")})")

  /**
   * 跳转目标可跟性判定：同 origin 或产品域族内；拒 scheme 降级/异常 scheme。
   * 返回 `Left` 时**绝不发出**对该目标的请求（开放代理面的红线）。
   */
  def redirectAllowed(from: URI, target: URI, families: Seq[String]): Either[String, URI] =
    val scheme = Option(target.getScheme).map(_.toLowerCase).getOrElse("")
    if !(scheme == "http" || scheme == "https") then Left(s"redirect refused: unsupported scheme (${describe(target)})")
    else if Option(from.getScheme).exists(_.equalsIgnoreCase("https")) && scheme == "http" then
      Left(s"redirect refused: https->http downgrade (${describe(target)})")
    else if sameOrigin(from, target) || hostInFamilies(target.getHost, families) then Right(target)
    else Left(s"redirect refused: ${describe(target)} outside allowlist (same-origin or ${families.mkString("/")})")

end AvatarProxy
