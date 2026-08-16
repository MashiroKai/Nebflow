package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.ci.CIString

/**
  * Route-level tests for WebSocketRoutes.viewersRoutes: serving the Canvas
  * viewer plugin modules from the web/js/viewers directory that fileViewers.js
  * dynamically imports. Regression guard for the plugin-ization outage — the
  * in-class /js route only matches single-segment paths, so without a
  * dedicated route every viewer module 404ed and opening any file failed.
  *
  * Serves static classpath resources only (no dataRoot involved), so no
  * temp-dir fixture is needed — unlike UploadsRoutesSpec.
  */
class ViewersRoutesSpec extends FunSuite:

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    WebSocketRoutes.viewersRoutes(req).value.unsafeRunSync()

  test("serves a viewer module with JS content type and no-cache") {
    val resp = get("/js/viewers/shared.js").get
    assertEquals(resp.status, Status.Ok)
    val contentType = resp.headers.get(CIString("Content-Type")).map(_.head.value)
    assert(
      contentType.exists(_.contains("javascript")),
      s"expected JS content type, got: $contentType"
    )
    // no-cache must match the in-class /js route contract: rebuilt classpath
    // resources have to be picked up by the browser during development.
    val cacheControl = resp.headers.get(CIString("Cache-Control")).map(_.head.value)
    assertEquals(cacheControl, Some("no-cache"))
  }

  test("404s for a missing module") {
    assertEquals(get("/js/viewers/nope.js").get.status, Status.NotFound)
  }

  test("rejects traversal via extra segments") {
    // Four segments — the DSL shape (Root / "js" / "viewers" / file) declines
    // the request (None), so nothing is served and the outer chain 404s.
    assert(get("/js/viewers/../auth.json").isEmpty)
    assert(get("/js/viewers/../../auth.json").isEmpty)
  }

  test("rejects a literal dotdot segment") {
    // Three segments with file = ".." — must not resolve outside the dir.
    assertEquals(get("/js/viewers/..").get.status, Status.NotFound)
  }

  test("rejects an encoded dotdot segment") {
    assertEquals(get("/js/viewers/%2e%2e").get.status, Status.NotFound)
  }

  test("rejects wrong path shape") {
    // Route declines non-matching shapes (None) — never serves them.
    assert(get("/js/viewers").isEmpty)
    assert(get("/js/viewers/a/b.js").isEmpty)
  }
