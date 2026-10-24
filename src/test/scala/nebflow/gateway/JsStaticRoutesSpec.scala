package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.ci.CIString

/**
 * Route-level tests for StaticRoutes.jsRoutes: serving ALL of web/js at
 * any depth. Generalized from the per-path routes (in-class single-segment
 * /js, the /js/locales case, the viewers-only route) after the same outage
 * class struck twice — every newly added js subdirectory 404ed all its
 * dynamic imports because http4s DSL matches single path segments only.
 *
 * Contract pinned here:
 *  - single-segment, two-segment (locales/, viewers/) and arbitrary-depth
 *    paths are all legal shapes and 200 when the resource exists
 *  - Cache-Control: bare requests (no version param) stay `no-cache` at EVERY
 *    depth; versioned requests (`?v=<fingerprint>` — the index-reference form,
 *    缓存击穿批 2026-10-25) get the long-lived immutable shipped-asset policy
 *  - traversal is rejected: literal ".." segments never reach the resource
 *    lookup; encoded "%2e%2e" and backslash forms 404 (no such resource)
 *  - missing resources 404 at any depth; "/js" (no slash) is declined
 */
class JsStaticRoutesSpec extends FunSuite:

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    StaticRoutes.jsRoutes(req).value.unsafeRunSync()

  private def assertServed(path: String, expectedCache: String = "no-cache"): Unit =
    val resp = get(path).get
    assertEquals(resp.status, Status.Ok, s"expected 200 for $path")
    val contentType = resp.headers.get(CIString("Content-Type")).map(_.head.value)
    assert(
      contentType.exists(_.contains("javascript")),
      s"expected JS content type for $path, got: $contentType"
    )
    // Bare requests: no-cache at every depth (module-graph imports have no
    // version param — revalidation keeps them fresh). Versioned requests: the
    // content-addressed URL makes the long-lived immutable policy safe.
    val cacheControl = resp.headers.get(CIString("Cache-Control")).map(_.head.value)
    assertEquals(cacheControl, Some(expectedCache), s"expected $expectedCache for $path")

  test("serves single-segment modules with JS content type and no-cache") {
    assertServed("/js/main.js")
  }

  test("versioned request gets the immutable shipped-asset policy (缓存击穿批)") {
    // The URL form index.html emits after versionedIndexHtml: ?v=<16 hex>.
    assertServed("/js/main.js?v=0123456789abcdef", StaticRoutes.ShippedAssetCacheControl)
  }

  test("versioned URL with empty v value stays bare (no-cache)") {
    assertServed("/js/main.js?v=", "no-cache")
  }

  test("serves two-segment modules (locales) — no-cache gap now closed") {
    assertServed("/js/locales/zh-CN.js")
    assertServed("/js/locales/en.js")
  }

  test("serves two-segment modules (viewers)") {
    assertServed("/js/viewers/shared.js")
  }

  test("multi-segment shape is legal; missing resources 404 at any depth") {
    assertEquals(get("/js/nope.js").get.status, Status.NotFound)
    assertEquals(get("/js/viewers/nope.js").get.status, Status.NotFound)
    // Three segments: legal shape, no such resource — proves deep parsing
    // works instead of shape-rejecting.
    assertEquals(get("/js/a/b/c.js").map(_.status), Some(Status.NotFound))
  }

  test("rejects traversal via literal dotdot segments") {
    // The guard must fire before any resource lookup.
    assertEquals(get("/js/../auth.json").map(_.status), Some(Status.NotFound))
    assertEquals(get("/js/../../etc/passwd").map(_.status), Some(Status.NotFound))
    assertEquals(get("/js/viewers/..").map(_.status), Some(Status.NotFound))
  }

  test("rejects an encoded dotdot segment") {
    // Encoded form slips past the literal guard but no such resource exists
    // (fromResource does not URL-decode) — still a 404, never a traversal.
    assertEquals(get("/js/%2e%2e").map(_.status), Some(Status.NotFound))
    assertEquals(get("/js/%2e%2e/auth.json").map(_.status), Some(Status.NotFound))
  }

  test("rejects backslash segments") {
    assertEquals(get("/js/a%5Cb.js").map(_.status), Some(Status.NotFound))
  }

  test("rejects empty shapes") {
    // "/js" (no trailing slash) does not match the route guard — declined.
    assert(get("/js").isEmpty)
    // "/js/" has no file segment after "js".
    assertEquals(get("/js/").map(_.status), Some(Status.NotFound))
    // Trailing slash on a real directory is not a file.
    assertEquals(get("/js/viewers/").map(_.status), Some(Status.NotFound))
  }
end JsStaticRoutesSpec
