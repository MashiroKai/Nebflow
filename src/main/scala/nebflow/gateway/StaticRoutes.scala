/* 从 WebSocketRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import nebflow.core.{Branding, PathUtil}
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
   * screenshots (sensitive). Cookie `nebflow_token` first + `?token=` query
   * fallback (same dual-channel pattern as the /ws route): a malicious page
   * cross-site <img>-probing the localhost gateway gets no SameSite cookie
   * sent, while the same-origin frontend attaches it automatically. The
   * query fallback covers cookie-less contexts (and a stale cookie, same
   * rationale as /ws).
   *
   * Lives in the companion (composed ahead of the instance routes) because
   * it touches none of the class dependencies — testable with just a
   * gateway token.
   */
  def uploadsRoutes(token: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/uploads/") =>
      val cookieToken = req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")
      val paramToken = req.params.get("token").getOrElse("")
      if !(Auth.validateToken(cookieToken, token) || Auth.validateToken(paramToken, token)) then
        Forbidden("Invalid token")
      else
        val segs = req.uri.path.segments.map(_.encoded).toList
        // Path shape is exactly <sid>/<filename> — two segments after "uploads".
        if segs.sizeIs != 3 then NotFound()
        else
          val relParts = segs.drop(1) // drop "uploads"
          // Block path traversal (same guard style as voice-models)
          if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
          else
            val uploadsBase = PathUtil.dataRoot / "uploads"
            val filePath = uploadsBase / os.RelPath(relParts.mkString("/"))
            // Final defense: the resolved path must stay under the base
            if filePath.startsWith(uploadsBase) && os.exists(filePath) && os.isFile(filePath) then
              StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
            else NotFound()
        end if
      end if
  }

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
