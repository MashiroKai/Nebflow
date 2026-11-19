package nebflow.gateway

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all.*
import fs2.compression.Compression
import org.http4s.*
import org.http4s.headers.*
import org.typelevel.ci.CIString

/**
 * P0 gzip middleware for the static-resource tree (esbuild-design.md 决策 3).
 *
 * Wraps the routes mounted at "/" so that text assets (js/css/html/svg/json)
 * are served with `Content-Encoding: gzip` to clients that accept it.
 *
 * Exclusions (决策 3 排除清单):
 *  - `/ws` WebSocket upgrade: explicit path guard (101 responses would also be
 *    filtered out by the status check below — defense in depth).
 *  - SSE/streaming: those endpoints live under "/api" (ChatRoutes SSE,
 *    presence WS), which is NOT wrapped by this middleware — excluded
 *    structurally, no per-route logic needed.
 *  - Already-compressed binaries (png/ico/woff2/...): excluded by the
 *    compressible content-type whitelist (text / js / json / xml / svg only).
 *
 * ETag/Vary correctness (决策 3 验收要点): a gzipped representation differs
 * byte-wise from the identity representation, so a strong ETag must not be
 * shared between them. Verified against http4s 0.23.30 source:
 * StaticFile.etagMatch compares FULL EntityTags including the weakness flag,
 * so weakening to W/"..." would make every revalidation a miss. STRONG tags
 * are therefore stripped (clients fall back to Last-Modified revalidation —
 * StaticFile's If-Modified-Since path is independent of ETag — so 304
 * revalidation keeps working for every static file; pinned by the integration
 * tests). A WEAK tag, by contrast, is content-coding agnostic by definition
 * (RFC 9110 §8.8.3) and is KEPT, so the index route's own conditional handling
 * survives the coding switch (静态面重验证批 2026-11-18). Every compressible
 * response (compressed or not, 200 or 304) carries `Vary: Accept-Encoding` so
 * caches never mix the two representations.
 *
 * HEAD requests are passed through untouched: ember's HEAD handling expects
 * the origin body untouched, and browsers revalidate with GET anyway.
 */
object GzipMiddleware:

  private val VaryAcceptEncoding: Header.Raw =
    Header.Raw(CIString("Vary"), "Accept-Encoding")

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli(req => routes(req).map(transform(req, _)))

  private def transform(req: Request[IO], resp: Response[IO]): Response[IO] =
    if req.method != Method.GET || req.pathInfo.renderString.startsWith("/ws") then resp
    else
      resp.status match
        case Status.Ok if hasCompressibleContentType(resp) =>
          if acceptsGzip(req) && resp.headers.get[`Content-Encoding`].isEmpty then compress(resp)
          else
            // Compressible resource but the client does not accept gzip (or
            // the origin already encoded the body): still advertise Vary so
            // caches keep identity and gzipped representations apart.
            resp.putHeaders(VaryAcceptEncoding)
        case Status.NotModified =>
          // 304s for compressible resources must also carry Vary. The bare 304
          // produced by StaticFile has no Content-Type, so add it
          // unconditionally — conservative and always cache-correct.
          resp.putHeaders(VaryAcceptEncoding)
        case _ => resp

  private def acceptsGzip(req: Request[IO]): Boolean =
    req.headers.get[`Accept-Encoding`].exists(_.satisfiedBy(ContentCoding.gzip))

  private def hasCompressibleContentType(resp: Response[IO]): Boolean =
    resp.contentType.exists { ct =>
      val mt = ct.mediaType
      mt.mainType == "text" ||
      mt.mainType == "application" && Set("javascript", "json", "xml").contains(mt.subType) ||
      mt.mainType == "image" && mt.subType == "svg+xml"
    }

  private def compress(resp: Response[IO]): Response[IO] =
    // Byte length changes under compression: drop Content-Length so the
    // response switches to chunked framing instead of being truncated.
    //
    // Validator handling (静态面重验证批 2026-11-18): the gzipped representation
    // differs byte-wise from the identity one, so a STRONG ETag must not be
    // shared between them — it is stripped, and the StaticFile family keeps
    // revalidating through Last-Modified/If-Modified-Since (StaticFile's
    // etagMatch compares EntityTags including the weakness flag, so weakening
    // those would make every revalidation a miss). A WEAK validator is
    // explicitly content-coding agnostic (RFC 9110 §8.8.3: the weak comparison
    // ignores the coding), so one is KEPT — that is what carries the index
    // route's `no-cache` + ETag 304 across the compressed face without ever
    // re-sending the body.
    val base =
      if weakEtagPresent(resp) then resp else resp.removeHeader[ETag]
    base
      .removeHeader[`Content-Length`]
      .putHeaders(`Content-Encoding`(ContentCoding.gzip), VaryAcceptEncoding)
      .withBodyStream(resp.body.through(Compression.forSync[IO].gzip()))
  end compress

  /** A weak (`W/"…"`) validator is safe to keep on the compressed face. */
  private def weakEtagPresent(resp: Response[IO]): Boolean =
    resp.headers.get(CIString("ETag")).exists(_.head.value.startsWith("W/"))

end GzipMiddleware
