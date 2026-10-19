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
   */
  def nfFileRoutes(
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized()
  ): HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ GET -> Root / "api" / "nf-file" =>
    val rawPath = req.params.get("path").getOrElse("")
    val ticket = req.params.get("ticket").getOrElse("")
    if rawPath.isEmpty then BadRequest("Missing 'path' parameter")
    else if ticket.isEmpty then
      // Not the DSL `Unauthorized`: that constructor demands a
      // WWW-Authenticate challenge, and this route has no auth scheme to
      // advertise (the ticket is an opaque bearer value, not Basic/Digest).
      IO.pure(nfText(Status.Unauthorized, "Missing 'ticket' parameter").putHeaders(cacheHeader("no-store")))
    else
      nfFileVerdictTolerant(rawPath, policy).flatMap {
        case denied: NfVerdict.Denied => IO.pure(nfDenied(denied))
        case NfVerdict.Allowed(real, _) =>
          store.verifyAndConsume(ticket, real.toString).flatMap {
            case Left(reason) =>
              IO.pure(nfDenied(NfVerdict.Denied(Status.Forbidden, reason, "ticket rejected")))
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
                              .putHeaders(Header.Raw(org.typelevel.ci.CIString("Content-Range"), s"bytes */$size"))
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
    end if
  }

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
    sessions: GatewaySessionStore
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
                  nfFileVerdictTolerant(p, policy).flatMap {
                    case NfVerdict.Allowed(real, _) =>
                      store.issue(sessionId, real.toString).map { issued =>
                        Right(
                          p,
                          Json.obj(
                            "t" -> issued.token.asJson,
                            "exp" -> issued.expiresAt.asJson,
                            "uses" -> issued.remaining.asJson
                          )
                        )
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
   */
  def nfAuthcheckRoutes(tokenRef: cats.effect.Ref[IO, String], sessions: GatewaySessionStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ GET -> Root / "api" / "nf-authcheck" =>
      tokenRef.get
        .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.WsAuth))
        .flatMap {
          case None => Forbidden("Invalid token")
          case Some(cred) =>
            NoContent().map(
              _.putHeaders(
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
