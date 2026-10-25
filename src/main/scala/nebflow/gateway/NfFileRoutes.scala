/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.gateway.NfFilePolicy.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

object NfFileRoutes:

  /**
   * 判据拒的日志可见性（Pop 图片 403 批 2026-10-24）：修前 `/api/nf-file` 的
   * 403 全族（verdict 判据拒 + 票拒）**零日志**——前端只回「Failed to render:
   * HTTP 403」，网关侧没有任何留痕可取证（作者报障「怎么老是出现 Failed to
   * render: HTTP 403」，宿主日志 grep 零命中即此缺口）。本 logger 名下
   * （`nebflow.nf-file`）对每个 403 写一行可 grep 的 `nf-file denied:`
   * （reason/status/path/remote 四键，ticket 本体永不落日志——T2 凭据纪律）。
   * 401（缺票参数）**刻意不记**：它是铸票面降级（F2/F5）的设计信号，量大的
   * 会话级噪音，无诊断价值。
   */
  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.nf-file")

  /** 单行可 grep 的判据拒日志（spec NfFileDenyLogSpec 按本形状钉死）。 */
  private[gateway] def denyLogLine(status: Int, reason: String, path: String, remote: String): String =
    s"nf-file denied: status=$status reason=$reason path=$path remote=$remote"

  /** 出向响应纯化 + 可见性日志：denied 出向原样，日志 fire-and-forget。 */
  private def deniedWithLog(resp: NfVerdict.Denied, path: String, req: Request[IO]): IO[Response[IO]] =
    logger
      .warn(denyLogLine(resp.status.code, resp.reason, path, req.remoteAddr.map(_.toString).getOrElse("unknown")))
      .as(nfDenied(resp))

  /**
   * Plain-text response builder.
   *
   * Deliberately NOT `Response.withEntity(String)` / the dsl generators:
   * this file imports `org.http4s.circe.CirceEntityCodec.*`, whose
   * `circeEntityEncoder[String]` (circe has an `Encoder[String]`) takes
   * precedence over http4s' own text encoder, so `withEntity("prose")` emits
   * a JSON-quoted string (`"prose"`). That is invisible to a status-only
   * assertion but wrong for a diagnostic body — the reason text is meant to
   * be read by a human and grepped by a spec.
   */
  private def nfText(status: Status, body: String): Response[IO] =
    Response[IO](status)
      .putHeaders(`Content-Type`(MediaType.text.plain, Charset.`UTF-8`))
      .withBodyStream(fs2.Stream.emit(body).through(fs2.text.utf8.encode))

  /**
   * Render a refusal. The `reason` rides in both the body and a header so
   * clients (and specs) can branch without parsing prose.
   */
  private def nfDenied(resp: NfVerdict.Denied): Response[IO] =
    nfText(resp.status, s"${resp.reason}: ${resp.message}")
      .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Nf-Reason"), resp.reason))
      .putHeaders(cacheHeader("no-store"))

  /**
   * #538 (2026-10-19, 图片打开慢量化批): deterministic browser caching for the
   * byte legs. The route always carried `ETag` + `Last-Modified` (StaticFile)
   * and answers `304` to `If-None-Match` / `If-Modified-Since` (verified), but
   * it never emitted `Cache-Control` — so repeat opens of the SAME ticketed
   * URL fell to per-browser HEURISTIC freshness (RFC 9111 §4.2.1: 10% of
   * `now - Last-Modified`, i.e. seconds for a freshly generated plot), which
   * on the relay leg (neblink.nebflow.space) re-pulls the full body through
   * the tunnel on every open. An explicit `private, max-age=300` makes the
   * repeat-open path deterministic: within 5 min the body is served from the
   * browser cache with ZERO byte transit; after that, one conditional round
   * trip buys a 304 (bytes never re-cross the tunnel while unchanged).
   *
   * `private` (never a shared/CDN cache) is mandatory: the URL carries the
   * per-path ticket credential. max-age 300 stays far below the ticket TTL
   * (default 1800s), so a cached entry can never outlive its credential.
   */
  private def cacheHeader(value: String): org.http4s.Header.Raw =
    org.http4s.Header.Raw(org.typelevel.ci.CIString("Cache-Control"), value)

  /**
   * #750 (2026-10-23, nf-file 媒体面高载 503 硬失败批): the overload refusal —
   * a RETRYABLE answer instead of a hang. 修前形态：网关对文件腿无限接纳，算力
   * 被并行构建饿死时请求排队不前，上游（relay ingress）先放弃并向浏览器回裸
   * 503。现在在飞超预算的请求拿到的是结构化的过载语义：
   * `429 + Retry-After: 1`（[[NfServeGate.RetryAfterSeconds]]）+ `X-Nf-Reason:
   * overloaded`（`nfDenied` 的 reason-head 同族）+ `no-store`（过载回答永远
   * 不可缓存）。429 经 relay 原样转发，前端的取数退避腿（nfFileCache.js）按
   * 头短退避后恰一次重试。
   */
  private def nfOverloaded: Response[IO] =
    nfText(
      Status.TooManyRequests,
      "server busy: the file leg is at its in-flight budget — retry shortly"
    ).putHeaders(
      Header.Raw(org.typelevel.ci.CIString("Retry-After"), NfServeGate.RetryAfterSeconds),
      Header.Raw(org.typelevel.ci.CIString("X-Nf-Reason"), "overloaded"),
      cacheHeader("no-store")
    )

  private val NfCacheControl: String = "private, max-age=300"

  // ────────────────────────────────────────────────────────────────────────
  // HTTP Range support (video-canvas batch, 2026-10-06 author order 13:24).
  //
  // The serving leg below used to be a bare `StaticFile.fromPath(_, Some(req))`.
  // Passing the request was widely believed to turn Range negotiation on
  // (FileTypeRegistry.scala and the pptx recon memo both said so) — it does
  // NOT: in http4s 0.23.30 `StaticFile` contains zero Range handling (no
  // `headers/Range` reference, no Accept-Ranges emission — verified against
  // the jar and pinned by NfFileRoutesSpec). A Canvas <video> therefore got
  // the whole file as one 200: playback worked, but every progress-bar seek
  // re-fetched from byte 0.
  //
  // Single byte-range negotiation lives here now: `bytes=a-b` / `bytes=a-` /
  // `bytes=-n` → 206 Partial Content + Content-Range + Accept-Ranges. A
  // MULTI-range or non-bytes Range, and anything malformed, is IGNORED per
  // RFC 9110 §14.2 (the route answers 200 with the full body — the receiving
  // <video> copes) rather than half-implemented; If-Range is likewise ignored
  // (unconditional serving). 416 with `Content-Range: bytes *\/size` for a
  // satisfiable-looking but out-of-bounds range.
  // ────────────────────────────────────────────────────────────────────────

  /**
   * Parsed single byte range: inclusive `start`..`end` against a known size.
   *
   * Syntax check vs satisfiability are deliberately ONE function over `size`:
   * the route calls it twice (syntax against `Long.MaxValue` to tell "no
   * usable Range" from "a Range we must answer", then against the real file
   * size to resolve the slice) so a syntactically-fine but out-of-bounds
   * range lands on 416 while a malformed/multi-range header lands on the
   * RFC's ignore-and-serve-200.
   */
  private def parseByteRange(value: String, size: Long): Option[(Long, Long)] =
    val m = """^bytes=(\d*)-(\d*)$""".r.findFirstMatchIn(value.trim)
    m.flatMap { m =>
      val (first, last) = (m.group(1), m.group(2))
      if first.isEmpty && last.isEmpty then None
      else if first.isEmpty then
        // suffix form `bytes=-n`: the FINAL n bytes
        last.toLongOption.collect { case n if n > 0 => (math.max(size - n, 0L), size - 1) }
      else
        first.toLongOption.collect {
          case s if s < size =>
            val end = last.toLongOption.fold(size - 1)(e => math.min(e, size - 1))
            (s, end)
        }
    }.filter((start, end) => start <= end)

  end parseByteRange

  /** The byte-range the request carries, if any. */
  private def requestedRange(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("Range")).map(_.head.value)

  /**
   * Content-Type derived the same way StaticFile derives it (MediaType by
   * extension from the shared MimeDB), so a 206 slice and the 200 whole
   * always agree. None → no header (StaticFile's own fallback shape).
   */
  private def contentTypeFor(real: fs2.io.file.Path): Option[`Content-Type`] =
    val name = real.toString
    val dot = name.lastIndexOf('.')
    if dot < 0 then None
    else MediaType.forExtension(name.substring(dot + 1).toLowerCase).map(mt => `Content-Type`(mt))

  /**
   * Serve `bytes start..end` (inclusive) of the file with 206 + Content-Range.
   * The stream is exactly `end-start+1` bytes (`readRange` takes an
   * END-EXCLUSIVE bound) and Content-Length is set to match, so Ember emits a
   * plain fixed-length body.
   */
  private def serveRange(real: fs2.io.file.Path, size: Long, start: Long, end: Long): Response[IO] =
    val len = end - start + 1
    Response[IO](Status.PartialContent)
      .putHeaders(
        org.http4s.headers
          .`Content-Range`(org.http4s.RangeUnit.Bytes, org.http4s.headers.Range.SubRange(start, end), Some(size)),
        org.http4s.headers.`Accept-Ranges`.bytes,
        cacheHeader(NfCacheControl),
        Header.Raw(org.typelevel.ci.CIString("Content-Length"), len.toString)
      )
      .withBodyStream(fs2.io.file.Files[IO].readRange(real, 64 * 1024, start, end + 1))

  /**
   * Serves whitelisted local files from disk for card iframes
   * (GET /api/nf-file?path=xxx&ticket=xxx). Supports Range requests for
   * video seeking. Standalone (zero class deps) so it is directly
   * unit-testable (NfFileRoutesSpec); composed ahead of the instance
   * routes like uploadsRoutes.
   *
   * 2026-09-11 (C batch, R5 = ticket-only): the global gateway token leg is
   * GONE — a permanent, path-agnostic credential is exactly what this batch
   * removes. A request must carry a ticket minted by `POST /api/nf-ticket`
   * for this precise realpath. Missing ticket → 401; unknown/expired/
   * mismatched → 403 with `reason`. The route body has no `handleErrorWith`
   * (it used to be three total functions), so the new resolution step is an
   * explicit `IO.blocking` + in-function try — never a throwing lambda.
   *
   * 2026-10-06 (video-canvas batch): single byte-range negotiation added
   * (206/Content-Range/Accept-Ranges) — see the Range block comment above.
   *
   * 2026-10-23 (#750, nf-file 媒体面高载 503 硬失败批): the serving leg is now
   * admitted through a bounded in-flight budget ([[NfServeGate]]). 参数快检
   * (400/401) 在闸外（廉价、不占预算）；获准后才走 verdict/ticket 判据链（既有
   * ticket-only 鉴权路径逐字节不变——被 429 的请求不铸票不耗票）。预算在飞闸
   * 的释放点是**响应体终点**（`body.onFinalize`：完整/出错/客户端断开三种终态
   * 都触发）或 handler 失败（`attempt` 左支）——预算真正约束的是并发字节推送，
   * 不是 handler 返回时刻。过载回答 = [[nfOverloaded]]（429 + Retry-After），
   * 永不裸 503。
   */
  def nfFileRoutes(
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized(),
    home: java.nio.file.Path = NfFilePolicy.processHome,
    gate: NfServeGate = NfServeGate.fromConfig()
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    // LAN 直传预检腿（console-fastpath 批 2026-10-23·腿③大文件 LAN 直传）：
    // 跨源 fetch（门户 https 页 → http://<lan-ip>:<port>/api/nf-file?…&ticket=…）
    // 属 Private-Network 请求，Chromium 先发 OPTIONS 预检——此前该路径落空（404）
    // ⇒ LAN 直传必败。应答 ACAO `*` + PNA 放行 + 读数头暴露面；凭据语义零变化
    // （ticket 仍是唯一凭据，它在 URL query 里，cookie 跨源本就带不过去）。
    case OPTIONS -> Root / "api" / "nf-file" =>
      NoContent().map(_.putHeaders(NfFileCorsHeaders*))

    case req @ GET -> Root / "api" / "nf-file" =>
      val rawPath = req.params.get("path").getOrElse("")
      val ticket = req.params.get("ticket").getOrElse("")
      // 全出向（含 400/401/403/416/429）统一补 CORS/PNA 头：LAN 直传腿的
      // preflight 只对 OPTIONS 发，但最终响应缺 ACAO 头浏览器照样撕响应——
      // 出向统一补是唯一不会在某条错误腿上断直传的面。
      val corsWrap = (io: IO[Response[IO]]) => io.map(_.putHeaders(NfFileCorsHeaders*))
      corsWrap(
          if rawPath.isEmpty then BadRequest("Missing 'path' parameter")
        else if ticket.isEmpty then
          // Not the DSL `Unauthorized`: that constructor demands a
          // WWW-Authenticate challenge, and this route has no auth scheme to
          // advertise (the ticket is an opaque bearer value, not Basic/Digest).
          IO.pure(nfText(Status.Unauthorized, "Missing 'ticket' parameter").putHeaders(cacheHeader("no-store")))
        else
          gate.tryAcquire.flatMap {
            case false => IO.pure(nfOverloaded)
            case true =>
              nfFileVerdictTolerant(rawPath, policy, home)
                .flatMap {
                  case denied: NfVerdict.Denied => deniedWithLog(denied, rawPath, req)
                  case NfVerdict.Allowed(real, _) =>
                    store.verifyAndConsume(ticket, real.toString).flatMap {
                      case Left(reason) =>
                        deniedWithLog(NfVerdict.Denied(Status.Forbidden, reason, "ticket rejected"), rawPath, req)
                      case Right(_) =>
                        val fsReal = fs2.io.file.Path(real.toString)
                        // Stage 1: syntax probe against MaxValue — separates "carries a
                        // usable single byte-range" from "no/malformed/multi Range".
                        val range = requestedRange(req).flatMap(parseByteRange(_, Long.MaxValue))
                        range match
                          case Some(_) =>
                            // Stage 2: satisfiability against the REAL size (the file may
                            // have changed or vanished since the verdict — re-reading is
                            // the honest answer, a stale size would corrupt Content-Range).
                            IO.blocking(java.nio.file.Files.size(real)).attempt.flatMap {
                              case Left(_) => IO.pure(Response[IO](Status.NotFound))
                              case Right(size) =>
                                requestedRange(req).flatMap(parseByteRange(_, size)) match
                                  case Some((start, end)) =>
                                    val base = serveRange(fsReal, size, start, end)
                                    IO.pure(contentTypeFor(fsReal).fold(base)(base.putHeaders(_)))
                                  case None =>
                                    // Well-formed-looking but unsatisfiable against the
                                    // real size → 416 (RFC 9110 §15.5.17).
                                    IO.pure(
                                      Response[IO](Status.RangeNotSatisfiable)
                                        .putHeaders(
                                          Header.Raw(org.typelevel.ci.CIString("Content-Range"), s"bytes */$size")
                                        )
                                        .putHeaders(org.http4s.headers.`Accept-Ranges`.bytes)
                                        .putHeaders(cacheHeader("no-store"))
                                    )
                            }
                          case None =>
                            // No (usable single) Range: the unchanged whole-file leg, plus
                            // the Accept-Ranges advertisement the <video> progress bar
                            // consults before bothering to seek.
                            StaticFile
                              .fromPath(fsReal, Some(req))
                              .map { r =>
                                // Cache-Control only on the cacheable positives (200 body /
                                // 304 revalidation, which must restate the policy); the 404
                                // leg stays untouched (minimal-diff: pre-#538 shape).
                                val withCache =
                                  if r.status == Status.Ok || r.status == Status.NotModified then
                                    r.putHeaders(cacheHeader(NfCacheControl))
                                  else r
                                val withAR = withCache.putHeaders(org.http4s.headers.`Accept-Ranges`.bytes)
                                contentTypeFor(fsReal).fold(withAR)(withAR.putHeaders(_))
                              }
                              .getOrElseF(NotFound())
                        end match
                    }
                }
                // Permit lifecycle: handler failure ⇒ release + rethrow (the request
                // never got a body to finalize); a produced response ⇒ release bound
                // to the body stream's termination (complete / error / client abort).
                .attempt
                .flatMap {
                  case Left(e) => gate.release *> IO.raiseError(e)
                  case Right(resp) => IO.pure(resp.withBodyStream(resp.body.onFinalize(gate.release)))
                }
          }
    ) // corsWrap(…) — 全出向补 CORS/PNA 头（LAN 直传腿）
  }

  /**
   * LAN 直传腿的 CORS/PNA 头集（console-fastpath 批 2026-10-23·腿③）：
   * ACAO `*`（凭据 = URL 内 ticket，与 nf-reach 同族纪律）+ PNA 放行 +
   * 前端读数面（Content-Length 进度 / Content-Range seek / Retry-After 退避 /
   * X-Nf-Reason 诊断头都要 Expose 才读得到——跨源默认只暴露 safelist）。
   */
  private val NfFileCorsHeaders: List[Header.ToRaw] = List(
    Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Origin"), "*"),
    Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Private-Network"), "true"),
    Header.Raw(
      org.typelevel.ci.CIString("Access-Control-Expose-Headers"),
      "content-length, content-range, accept-ranges, last-modified, etag, retry-after, x-nf-reason"
    )
  )

  /**
   * C2-2: `POST /api/nf-ticket` — mint short-lived, per-path read tickets.
   *
   * Body `{sessionId, paths[]}`. Every path goes through the SAME verdict as
   * the read endpoint (C1-5), so an unreadable path can never be ticketed;
   * rejections come back in `rejected[]` with their `reason` instead of
   * failing the whole batch (the frontend degrades per path — §4.3 F4).
   *
   * Auth (phase 3b): the unified GatewayAuth criterion under WsAuth —
   * session cookie first, then Bearer (file token); a presented-but-invalid
   * rail falls through to the next one. (Stage D retired the legacy cookie
   * and token query rails.) The frontend sends no explicit credential —
   * the session cookie rides along on the same-origin fetch.
   */
  def nfTicketRoutes(
    tokenRef: cats.effect.Ref[IO, String],
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized(),
    sessions: GatewaySessionStore,
    home: java.nio.file.Path = NfFilePolicy.processHome
  ): HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "api" / "nf-ticket" =>
    val authorized =
      tokenRef.get.flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.WsAuth))
    authorized.flatMap {
      case None => Forbidden("Invalid token")
      case Some(_) =>
        req.as[Json].attempt.flatMap {
          case Left(e) =>
            BadRequest(s"malformed ticket request body: ${e.getMessage}")
          case Right(body) =>
            val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("")
            val paths = body.hcursor
              .downField("paths")
              .as[List[String]]
              .getOrElse(Nil)
              .filter(_.nonEmpty)
              .distinct
            // Re-read `nfFile.ticketTtlSeconds` (throttled + mtime-gated) so a
            // config edit takes effect without a restart (R3).
            store.refreshTtlFromConfig() *>
              paths
                .traverse { p =>
                  nfFileVerdictTolerant(p, policy, home).flatMap {
                    case NfVerdict.Allowed(real, _) =>
                      // size 并键（console-fastpath 批 2026-10-23·腿③）：浏览端
                      // 拿它做 LAN 直传阈值判定（≥4MiB 且近路在册 ⇒ 直连取）。
                      // verdict 与 stat 之间文件可能消失/变化——stat 失败回 0
                      // （= 未知尺寸，前端按不直传处理，诚实降级）。
                      IO.blocking(java.nio.file.Files.size(real)).attempt.map(_.getOrElse(0L)).flatMap {
                        size =>
                          store.issue(sessionId, real.toString).map { issued =>
                            Right(
                              p,
                              Json.obj(
                                "t" -> issued.token.asJson,
                                "exp" -> issued.expiresAt.asJson,
                                "uses" -> issued.remaining.asJson,
                                "size" -> size.asJson
                              )
                            )
                          }
                      }
                    case denied: NfVerdict.Denied =>
                      IO.pure(Left(p, Json.obj("path" -> p.asJson, "reason" -> denied.reason.asJson)))
                  }
                }
                .map { results =>
                  val tickets = results.collect { case Right((p, j)) => p -> j }
                  val rejected = results.collect { case Left((_, j)) => j }
                  Json.obj(
                    "tickets" -> Json.obj(tickets*),
                    "rejected" -> Json.arr(rejected*)
                  )
                }
                .flatMap(Ok(_))
        }
    }
  }

  /**
   * R9 = O-A: `GET /api/nf-authcheck` — the cookie-reachability probe target.
   *
   * Replaces the old "bare `/api/nf-file` → 400 = cookie works" signal, which
   * the ticket leg turns into 401 (a status that overlaps the genuine
   * failure case, so the old probe could no longer distinguish anything).
   * This route touches ZERO disk and has no other side effect: 204 when the
   * presented credential validates, 403 otherwise.
   *
   * Phase 3b: validated through the unified GatewayAuth criterion (WsAuth —
   * session cookie > Bearer; the probe fetch sends no explicit credential,
   * so it exercises exactly the cookie path). A success answer additionally
   * carries the READ-ONLY response
   * header `x-<brand>-credential` (GatewayAuth.CredentialHeader) whose value
   * is the rail name that hit — the B' frontend purge hook consumes ONLY the
   * `session` value ("the HttpOnly session cookie is verified working");
   * the status-code contract is unchanged.
   *
   * Session TTL batch (2026-10-21): a SESSION-rail hit also slides the
   * BROWSER cookie — the 204 re-emits the same session id with a fresh
   * `Max-Age=<absolute TTL>` (GatewaySessionStore.newCookie, the frozen
   * attribute set). The value never changes (sliding renewal is
   * server-side); this refreshes the jar's expiry so an actively used
   * console never hits the cookie's fixed 30d cliff. Bearer hits (CLI /
   * scripts) deliberately emit nothing — there is no session record behind
   * them. `secure` recomputed per #522 from the consuming origin (https or
   * tunnel-marked), never from a stored flag — the exact mint-site
   * expression, so a slid cookie can never disagree with the jar's leg.
   */
  def nfAuthcheckRoutes(tokenRef: cats.effect.Ref[IO, String], sessions: GatewaySessionStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ GET -> Root / "api" / "nf-authcheck" =>
      tokenRef.get
        .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.WsAuth))
        .flatMap {
          case None => Forbidden("Invalid token")
          case Some(cred) =>
            // Session TTL batch (2026-10-21): slide the browser cookie on a
            // session-rail hit (same id, fresh Max-Age; see the doc above).
            // `secure` recomputed from the consuming origin, the mint-site
            // expression verbatim (#522) — never from a stored flag.
            val secure =
              req.uri.scheme.exists(_.value.equalsIgnoreCase("https")) ||
                nebflow.shared.TunnelOriginMark.isMarked(req)
            val slid = cred match
              case Credential.ViaSession(rec) => Some(GatewaySessionStore.newCookie(rec.sessionId, secure = secure))
              case _                          => None
            NoContent().map(resp =>
              slid.fold(resp)(resp.addCookie).putHeaders(
                Header.Raw(org.typelevel.ci.CIString(GatewayAuth.CredentialHeader), cred.railName)
              )
            )
        }
    }

  /**
   * `GET /api/nf-reach` — the unauthenticated reachability probe (phase 4 LAN
   * leg, plan §4.2; the portal page's same-machine detector fetches it
   * cross-origin from the portal domain, so the answer must be browser-readable).
   *
   * Always 204 + the brand self-evidence header `x-<brand>-gateway: 1`. Zero
   * credentials, zero enumeration, zero session: the disclosure surface is
   * exactly "a nebflow gateway listens on this port" — equivalent to what a
   * refused WS handshake already discloses to anyone who can reach the port.
   * CORS is open BY DESIGN here (a probe the browser cannot read is useless):
   * any origin, GET/OPTIONS, and the PNA preflight header is answered so
   * Chromium's private-network access check passes for loopback targets.
   */
  def nfReachRoutes: HttpRoutes[IO] =
    val reachCorsHeaders: List[Header.ToRaw] = List(
      Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Origin"), "*"),
      Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Methods"), "GET, OPTIONS"),
      Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Headers"), "content-type"),
      // Without Expose-Headers the answer's status is readable but the brand
      // header is filtered out of the page's view (T-3 chromium reading) — the
      // shape check ("204 AND the self-evidence header") needs it exposed.
      Header.Raw(
        org.typelevel.ci.CIString("Access-Control-Expose-Headers"),
        s"x-${nebflow.shared.Branding.lowerName}-gateway"
      ),
      Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Private-Network"), "true")
    )
    HttpRoutes.of[IO] {
      case OPTIONS -> Root / "api" / "nf-reach" =>
        NoContent().map(_.putHeaders(reachCorsHeaders*))
      case GET -> Root / "api" / "nf-reach" =>
        NoContent().map(
          _.putHeaders(
            (Header.Raw(
              org.typelevel.ci.CIString(s"x-${nebflow.shared.Branding.lowerName}-gateway"),
              "1"
            ) :: reachCorsHeaders)*
          )
        )
    }
  end nfReachRoutes

end NfFileRoutes
