/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import nebflow.shared.{Branding, PathUtil}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import scala.io.Source

object StaticRoutes:

  /**
   * P1: true when the esbuild production bundle is packed on the classpath
   * (sbt -Dnebflow.webdist=1 assembly mounts build/ as a resource dir).
   * Lazy — the classpath is fixed for the JVM's lifetime, so this resolves
   * once. Single switch point for the static tree: only the "/" entry
   * chooses between dist and dev sources; all other static routes keep
   * serving the web/ source tree (vendor passthrough is byte-identical in
   * both trees, and the C1 source-mode contract requires web/ paths to stay
   * green on prod instances too).
   */
  lazy val hasBundledDist: Boolean =
    getClass.getClassLoader.getResource("web-dist/index.html") != null

  /**
   * L1 rebrand: the frontend brand contract. window.__BRAND__ is the only
   * brand source web/ may read; fields are append-only across rebrand
   * batches (initial contract: productName, lowerName, domain; L3 batch 3
   * appended homeDirName for the frontend's own legacy-path messaging;
   * 2026-09-01 login-chain fix appended profileUrl — the frontend's ONLY
   * URL input, consumed by activityBar.js to build the profile link).
   * `domain` carries the default value (nebflow.space); a `NEBFLOW_BRAND_DOMAIN`
   * env override may replace it (single-domain, 2026-09-07 naming ruling) —
   * display-only, never consumed to build a URL.
   *
   * circe handles JSON string escaping; the serialized blob additionally
   * escapes the forward slash of "</" because it is inlined inside a
   * script element (script-tag breakout hardening; JSON permits `\/`).
   */
  private[gateway] def brandScriptTag: String =
    s"""<script>window.__BRAND__=${brandScriptJson(
        Branding.productName,
        Branding.lowerName,
        Branding.domain,
        Branding.homeDirName,
        Branding.profileUrl
      )};</script>"""
  end brandScriptTag

  /**
   * Serialize the contract JSON (values injected for testability of the
   * escaping hardening).
   */
  private[gateway] def brandScriptJson(
    productName: String,
    lowerName: String,
    domain: String,
    homeDirName: String,
    profileUrl: String
  ): String =
    Json
      .obj(
        "productName" -> Json.fromString(productName),
        "lowerName" -> Json.fromString(lowerName),
        "domain" -> Json.fromString(domain),
        "homeDirName" -> Json.fromString(homeDirName),
        "profileUrl" -> Json.fromString(profileUrl)
      )
      .noSpaces
      .replace("</", "<\\/")

  /**
   * Insert `snippet` directly before the LAST closing head tag — the one
   * structural anchor every index variant (dev source, esbuild dist) is
   * guaranteed to carry, emitted lowercase. None when the tag is absent:
   * callers serve the original bytes rather than guessing a fallback
   * position (a broken template should be visible, not papered over).
   */
  private[gateway] def injectBeforeHeadClose(html: String, snippet: String): Option[String] =
    val idx = html.lastIndexOf("</head>")
    if idx < 0 then None
    else Some(html.substring(0, idx) + snippet + html.substring(idx))
  end injectBeforeHeadClose

  /**
   * L1 rebrand: serve the index entry with the brand script injected.
   * Blocking classpath read of a tiny resource (a few KB) per request —
   * the same order of cost as the StaticFile.fromResource lookup it
   * replaces. Response contract: no-cache, no validators (the body is
   * content-generated, not a static file), text/html in UTF-8. The gzip
   * middleware wraps this route from the Router "/" mount, so it sees the
   * final injected bytes — compression order is correct by construction.
   *
   * The entity is written as a raw byte stream with an explicit
   * Content-Length — deliberately NOT Ok(String)/withEntity(String):
   * this file imports org.http4s.circe.CirceEntityCodec.* for the JSON
   * endpoints, and that import's String entity encoder (lexical scope)
   * wins over http4s' built-in one (implicit scope), silently encoding
   * the whole HTML as a JSON string literal (body starts with '"',
   * quotes escaped throughout — the page never boots). Raw bytes bypass
   * entity-encoder resolution entirely; pinned at the HTTP response
   * layer by IndexWithBrandServeSpec.
   */
  def indexWithBrand(indexResource: String): IO[Response[IO]] =
    IO.blocking(readClasspathResourceUtf8(indexResource)).flatMap {
      case None => NotFound()
      case Some(html) =>
        val served = injectBeforeHeadClose(html, brandScriptTag).getOrElse(html)
        val bytes = served.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        IO.pure(
          Response[IO](Status.Ok)
            .withBodyStream(Stream.emits(bytes))
            .withContentType(`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
            .putHeaders(
              "Content-Length" -> bytes.length.toString,
              "Cache-Control" -> "no-cache"
            )
        )
    }
  end indexWithBrand

  private def readClasspathResourceUtf8(name: String): Option[String] =
    Option(getClass.getClassLoader.getResourceAsStream(name)).map { in =>
      val source = Source.fromInputStream(in, "UTF-8")
      try source.mkString
      finally in.close()
    }

  /**
   * P1: serve the bundled asset tree (any depth under the /assets prefix —
   * hashed entry and lazy chunks produced by build-web.mjs). Guard pattern
   * copied from jsRoutes: trailing-slash (directory request) is rejected,
   * path traversal (`..` and backslash) is rejected, and the lookup is a
   * plain classpath resource join. Serves ONLY the web-dist tree — the
   * hashed names are a dist-build artifact and never exist in dev sources.
   * In dev every /assets request 404s; harmless, the dev index never
   * references the /assets prefix.
   *
   * Standalone (zero class deps) so it is directly unit-testable; composed
   * ahead of the instance routes like jsRoutes.
   */
  def assetsRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/assets/") =>
      if req.uri.path.endsWithSlash then NotFound()
      else
        val relParts = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "assets"
        if relParts.isEmpty || relParts.last.isEmpty then NotFound()
        else if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile
            .fromResource(s"web-dist/assets/$path", Some(req))
            .map(_.putHeaders("Cache-Control" -> "no-cache"))
            .getOrElseF(NotFound())
        end if
      end if
  }

  /**
   * G1: serve user-uploaded attachments from
   * `~/.nebflow/uploads/<sid>/<file>`. Restored session history (ui.json)
   * records attachments as {name,type,path}; without a route serving the
   * uploads dir those images are unrenderable after a restart.
   *
   * Authenticated — unlike the voice-models route, uploads are user
   * screenshots (sensitive). Phase 3b: the unified GatewayAuth criterion
   * under StaticAuth — session cookie first, then the legacy `nebflow_token`
   * cookie, then the `?token=` fallback (the two pre-3b legs unchanged, the
   * session rail on top; a presented-but-invalid rail falls through instead
   * of rejecting). A malicious page cross-site <img>-probing the localhost
   * gateway still gets no SameSite cookie sent, while the same-origin
   * frontend attaches the session cookie automatically; the fallbacks cover
   * cookie-less contexts exactly as before.
   *
   * Lives in the companion (composed ahead of the instance routes) because
   * it touches none of the class dependencies — testable with just a
   * gateway token.
   */
  def uploadsRoutes(tokenRef: cats.effect.Ref[IO, String], sessions: GatewaySessionStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> _ if req.uri.path.renderString.startsWith("/uploads/") =>
        tokenRef.get
          .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.StaticAuth))
          .flatMap {
            case None => Forbidden("Invalid token")
            case Some(_) =>
              uploadsServe(req)
          }
    }

  /**
   * The serving leg of uploadsRoutes (verbatim from the pre-3b body): the
   * percent-decode + traversal-guard + realpath-containment chain. Split out
   * only so the criterion wrapper above stays readable — behavior is
   * byte-identical.
   */
  private def uploadsServe(req: Request[IO]): IO[Response[IO]] =
    // Percent-DECODE each segment before the lookup (refresh-image fix,
    // 2026-10-09): the wire path is always percent-encoded (browsers encode
    // <img src> per WHATWG; the frontend's attachmentImageUrl
    // encodeURIComponent's each part) while the on-disk name is the decoded
    // form. Matching on `encoded` looked up the literal `%E6…%20…` spelling
    // and 404'd every upload whose name has non-ASCII characters or spaces —
    // i.e. every macOS screenshot (「截屏2026-10-09 07.10.22.jpg」), which is
    // exactly why restored history showed broken-image placeholders while the
    // pre-refresh live row (inline data: preview) rendered fine.
    // `Segment.decoded` defaults (bytecode-verified, 0.23.30): UTF-8,
    // plusIsSpace=false (a literal `+` stays `+` on a path — query-string
    // form semantics must NOT leak here), nothing skipped. Decoding happens
    // BEFORE the guards below so a `%2E%2E` evasion decodes INTO them.
    val segs = req.uri.path.segments.map(s => s.decoded()).toList
    // Path shape is exactly <sid>/<filename> — two segments after "uploads".
    if segs.sizeIs != 3 then NotFound()
    else
      val relParts = segs.drop(1) // drop "uploads"
      // Block path traversal (same guard style as voice-models). Post-decode
      // additions: a segment that DECODES to something carrying `/` (from
      // %2F — no real POSIX filename contains one, and `../uploads/…` would
      // re-enter the base through os.RelPath up-segments) or NUL (from %00 —
      // java.nio would throw InvalidPathException → 500) can only be an
      // evasion, so it gets the same uniform 404 the old literal lookup
      // produced for both.
      if relParts
          .exists(s => s == ".." || s.contains("\\") || s.contains('/') || s.contains('\u0000'))
      then NotFound()
      else
        val uploadsBase = PathUtil.dataRoot / "uploads"
        val filePath = uploadsBase / os.RelPath(relParts.mkString("/"))
        // Final defense: the resolved path must stay under the base
        if filePath.startsWith(uploadsBase) && os.exists(filePath) && os.isFile(filePath) then
          StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
        else NotFound()
    end if

  /**
   * canvas-media Wave1 (2026-10-05, OD-2=V-C): tokenized static route for
   * plugin EXECUTOR assets — `/api/plugin-assets/<name>/<rel…>`. The small
   * viewers.json control-plane declaration rides inline with
   * `GET /api/plugins` (PluginRegistry.approvalManifest) instead; everything
   * heavy (wasm bundles, renderer scripts) is served here so the 6s plugins
   * poll never re-ships plugin bytes.
   *
   * Contract (canvas-media plan §5.3; guard style copied from
   * uploadsRoutes + the WebSocketRoutes /agents/ precedent, tightened to
   * realpath containment):
   *  - Trusted plugins ONLY. `trustedPluginDir` resolves the registry
   *    entry's directory; unknown or blocked names get the same uniform
   *    404 as a missing file (no existence oracle for blocked packages).
   *  - Token REQUIRED: the same StaticAuth rails as uploadsRoutes
   *    (session cookie > legacy `nebflow_token` cookie > `?token=` fallback;
   *    a cross-site <img>-probe gets no SameSite cookie; the same-origin
   *    frontend attaches the session cookie automatically; the fallbacks
   *    cover cookie-less contexts).
   *  - Manual segment parsing (http4s DSL matches single segments only):
   *    trailing slash rejected, `..` and backslash segments rejected in
   *    the plugin name and the relative parts, and the FINAL defense is
   *    realpath containment under the plugin dir — the shared
   *    PluginRegistry.containedUnder fence (single judge, no second
   *    policy), so a symlink planted inside the package cannot point this
   *    route outside the plugin root.
   *
   * Standalone (the registry lookup is injected) so it is directly
   * unit-testable (PluginAssetsRoutesSpec), like uploadsRoutes; composed
   * into the /api Router in GatewayMain.
   *
   * Mount-form agnostic path source (plugin-assets-404-fix, 2026-10-07): the
   * guard and the segment parse read `req.pathInfo` — the path RELATIVE TO
   * THE CURRENT MOUNT — never `req.uri.path`, which keeps the absolute form.
   * This closed the production 404 seam: the route composes under
   * GatewayMain's `Router("/api" -> …)`, and http4s hands mounted routes a
   * request whose `uri.path` still carries the `/api` prefix while
   * `pathInfo` is the stripped form — the same form the DSL cases of every
   * sibling route in that Router (ConfigRoutes `Root / "config"`, …) match
   * on. A guard written against `uri.path` therefore NEVER matched in
   * production (`/api/plugin-assets/…` answered 404 on every real instance:
   * the frontend Plugin.md probe URL) while the direct-call spec family
   * (unmounted, pathInfo == uri.path) stayed green, masking the seam.
   * Reading `pathInfo` serves BOTH mount forms with one judge: prefixed
   * under the Router (production), bare when composed directly (specs).
   */
  def pluginAssetsRoutes(
    tokenRef: cats.effect.Ref[IO, String],
    sessions: GatewaySessionStore,
    trustedPluginDir: String => IO[Option[os.Path]]
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> _ if req.pathInfo.renderString.startsWith("/plugin-assets/") =>
        tokenRef.get
          .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.StaticAuth))
          .flatMap {
            case None => Forbidden("Invalid token")
            case Some(_) =>
              // Trailing slash = directory request — this route serves files only.
              if req.pathInfo.endsWithSlash then NotFound()
              else pluginAssetsServe(req, trustedPluginDir)
          }
    }

  /** The serving leg of pluginAssetsRoutes (verbatim from the pre-3b body). */
  private def pluginAssetsServe(
    req: Request[IO],
    trustedPluginDir: String => IO[Option[os.Path]]
  ): IO[Response[IO]] =
    val segs = req.pathInfo.segments.map(_.encoded).toList
    // Shape: /plugin-assets/<name>/<rel…> — at least one rel segment.
    if segs.sizeIs < 3 || relPartsEmpty(segs.drop(2)) then NotFound()
    else
      val pname = segs(1)
      val relParts = segs.drop(2)
      // Block path traversal in the plugin name and the relative parts
      if pname.contains("..") || pname.contains("\\") || relParts.exists(s => s == ".." || s.contains("\\"))
      then NotFound()
      else
        trustedPluginDir(pname).flatMap {
          case None => NotFound()
          case Some(dir) =>
            val filePath = dir / os.RelPath(relParts.mkString("/"))
            // Final defense: realpath containment under the plugin dir
            if nebflow.core.plugin.PluginRegistry.containedUnder(dir, filePath) && os.exists(filePath) && os
                .isFile(filePath)
            then StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
            else NotFound()
        }
    end if

  /** jsRoutes-style emptiness guard: no segments at all, or a trailing empty one. */
  private def relPartsEmpty(relParts: List[String]): Boolean =
    relParts.isEmpty || relParts.last.isEmpty

  /**
   * Vendor SUBTREE passthrough (canvas-office-render-fix batch, 2026-10-06)
   * — the depth ≥ 2 shapes under /vendor/ that none of the WebSocketRoutes
   * vendor cases match. The pptx-preview MVP vendored
   * `web/vendor/pptx-renderer/` (7 files, commit 0adfef219) and its viewer
   * fetches the renderer library (aiden0z-pptx-renderer.browser.es.js) under
   * that directory at runtime; the directory-specific
   * cases (monaco/pdfjs/fonts) plus the single-segment case cannot match a
   * 3-segment non-monaco/pdfjs path, so all 7 files answered 404 on a real
   * instance and the renderer never loaded. That is the SAME outage class
   * the [[jsRoutes]] scaladoc documents for /js ("every new directory needed
   * its own hand-written case") — third occurrence on /vendor (monaco →
   * pdfjs → pptx-renderer) — so instead of a fourth per-directory case, the
   * remaining vendor tree is served here, exactly like jsRoutes serves the
   * remaining js tree.
   *
   * Scope is unchanged in kind: `web/vendor/` holds only app-shipped public
   * assets (the same classpath tree the single-segment case already serves
   * one level of). Precedence over monaco/pdfjs/fonts/single-file paths is
   * defined by the caller's case position in WebSocketRoutes (this function
   * is the gap-filler and accepts any depth); guards are the monaco/pdfjs
   * precedent (traversal rejected, trailing slash rejected) plus jsRoutes'
   * `no-cache` — a rebuilt classpath vendor tree must be picked up by the
   * browser, and StaticFile's ETag keeps the revalidation a cheap 304.
   * Content-Type still derives from the file extension via StaticFile.
   *
   * Standalone (zero class deps) so it is directly unit-testable
   * (VendorSubtreeRoutesSpec), like jsRoutes.
   */
  def vendorSubtree(req: Request[IO]): IO[Response[IO]] =
    if req.uri.path.endsWithSlash then NotFound()
    else
      val segs = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "vendor"
      if segs.isEmpty || segs.last.isEmpty then NotFound()
      else if segs.exists(s => s == ".." || s.contains("\\")) then NotFound()
      else
        val path = segs.mkString("/")
        StaticFile
          .fromResource(s"web/vendor/$path", Some(req))
          .map(_.putHeaders("Cache-Control" -> "no-cache"))
          .getOrElseF(NotFound())
      end if
    end if
  end vendorSubtree

  /**
   * Serves everything under web/js at ANY depth. The frontend's ES modules
   * live at /js/<file>.js, /js/locales/<lang>.js and /js/viewers/<name>.js,
   * and any future subdirectory — this closes the "add a js subdirectory →
   * every dynamic import 404s" outage class for good. The per-path routes
   * it replaces (in-class single-segment /js, the /js/locales case, and the
   * viewers-only route from 4281f720) each fixed one symptom after the
   * fact: http4s DSL matches single path segments, so every new directory
   * needed its own hand-written case.
   *
   * Manual segment parsing (same pattern and guard style as the monaco
   * route): ".." and backslash segments are rejected; everything else is
   * joined and looked up as a classpath resource. no-cache at EVERY depth —
   * rebuilt classpath resources must be picked up by the browser during
   * development (this also fixes locales, which used to ship without it).
   *
   * Standalone (zero class deps) so it is directly unit-testable
   * (JsStaticRoutesSpec); composed ahead of the instance routes like
   * uploadsRoutes.
   */
  def jsRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/js/") =>
      // Trailing slash = directory request (e.g. /js/viewers/) — this route
      // serves files only; without the guard StaticFile happily serves the
      // classpath directory entry itself (200 with junk).
      if req.uri.path.endsWithSlash then NotFound()
      else
        val relParts = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "js"
        if relParts.isEmpty || relParts.last.isEmpty then NotFound()
        else if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile
            .fromResource(s"web/js/$path", Some(req))
            .map(_.putHeaders("Cache-Control" -> "no-cache"))
            .getOrElseF(NotFound())
        end if
      end if
  }

end StaticRoutes
