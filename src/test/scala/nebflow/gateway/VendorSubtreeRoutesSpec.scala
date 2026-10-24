package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.ci.CIString

/**
 * Route-level tests for StaticRoutes.vendorSubtree: serving the depth ≥ 2
 * shapes under /vendor/ that the directory-specific WebSocketRoutes cases
 * (monaco/pdfjs/fonts) and the single-segment case cannot match.
 *
 * Outage being pinned (canvas-office-render-fix batch, 2026-10-06): the
 * pptx-preview MVP vendored `web/vendor/pptx-renderer/` (7 files, commit
 * 0adfef219) and viewers/pptx.js fetches
 * `/vendor/pptx-renderer/aiden0z-pptx-renderer.browser.es.js` at runtime —
 * with no matching case all 7 files answered 404 on a real instance and the
 * renderer never loaded. Third occurrence of the per-directory outage class
 * on /vendor (monaco → pdfjs → pptx-renderer); the jsRoutes lesson applied.
 *
 * Contract pinned here:
 *  - the vendored pptx-renderer subtree is served at any depth (200) with
 *    the extension-derived Content-Type and `Cache-Control: no-cache`
 *  - other multi-segment vendor shapes (e.g. a hypothetical future vendored
 *    subdirectory) are legal too — the class stays closed
 *  - traversal is rejected: literal ".." segments and backslash forms 404
 *  - missing resources 404; trailing slash (directory request) is rejected
 */
class VendorSubtreeRoutesSpec extends FunSuite:

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    StaticRoutes.vendorSubtree(req).unsafeRunSync()

  private def assertServed(path: String, expectedTypePart: String, expectedCache: String = "no-cache"): Unit =
    val resp = get(path)
    assertEquals(resp.status, Status.Ok, s"expected 200 for $path")
    val contentType = resp.headers.get(CIString("Content-Type")).map(_.head.value)
    assert(
      contentType.exists(_.contains(expectedTypePart)),
      s"expected $expectedTypePart content type for $path, got: $contentType"
    )
    // Bare requests: no-cache (jsRoutes convention). Versioned requests
    // (index-reference form): the content-addressed immutable policy
    // (静态资源缓存击穿批 2026-10-25).
    val cacheControl = resp.headers.get(CIString("Cache-Control")).map(_.head.value)
    assertEquals(cacheControl, Some(expectedCache), s"expected $expectedCache for $path")

  test("versioned request gets the immutable shipped-asset policy (缓存击穿批)") {
    assertServed(
      "/vendor/pptx-renderer/aiden0z-pptx-renderer.browser.es.js?v=0123456789abcdef",
      "javascript",
      StaticRoutes.ShippedAssetCacheControl
    )
  }

  test("serves the vendored pptx-renderer assets (the 404 batch)") {
    assertServed("/vendor/pptx-renderer/aiden0z-pptx-renderer.browser.es.js", "javascript")
    assertServed("/vendor/pptx-renderer/README.md", "markdown")
    assertServed("/vendor/pptx-renderer/THIRD_PARTY_NOTICES.md", "markdown")
    assertServed("/vendor/pptx-renderer/licenses/mtx-decompressor-MPL-2.0.txt", "plain")
  }

  test("serves the extension-less vendored files (LICENSE)") {
    // No extension → StaticFile's octet-stream fallback; the contract here is
    // reachability (200 + a non-empty body), not a specific type.
    val resp = get("/vendor/pptx-renderer/LICENSE")
    assertEquals(resp.status, Status.Ok)
    assert(resp.headers.get(CIString("Content-Length")).map(_.head.value).forall(_.toLong > 0))
  }

  test("other multi-segment vendor shapes stay legal (outage class stays closed)") {
    // Any future vendored subdirectory must not need a fourth hand-written case.
    assertServed("/vendor/pdfjs/pdf.min.js", "javascript")
  }

  test("missing resources 404 at any depth") {
    assertEquals(get("/vendor/pptx-renderer/nope.js").status, Status.NotFound)
    assertEquals(get("/vendor/nope-dir/nope.js").status, Status.NotFound)
  }

  test("rejects traversal via literal dotdot segments") {
    assertEquals(get("/vendor/../auth.json").status, Status.NotFound)
    assertEquals(get("/vendor/pptx-renderer/../README.md").status, Status.NotFound)
    assertEquals(get("/vendor/..").status, Status.NotFound)
  }

  test("rejects backslash segments") {
    assertEquals(get("/vendor/pptx-renderer/a%5Cb.js").status, Status.NotFound)
  }

  test("rejects empty shapes") {
    // "/vendor/" (trailing slash = directory request) is declined.
    assertEquals(get("/vendor/").status, Status.NotFound)
    // Deep trailing slash is a directory request too.
    assertEquals(get("/vendor/pptx-renderer/licenses/").status, Status.NotFound)
  }
end VendorSubtreeRoutesSpec
