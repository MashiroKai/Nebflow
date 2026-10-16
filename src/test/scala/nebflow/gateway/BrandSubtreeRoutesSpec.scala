package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.ci.CIString

/**
 * Route-level tests for StaticRoutes.brandSubtree: serving the rebrand asset
 * slot tree under `web/brand/` at any depth (stand-logo-slotting batch,
 * 2026-10-15).
 *
 * Outage being pinned: the optional __BRAND__ logoUrl/logoDarkUrl reference
 * slots (brand-switch-console batch) are free strings; on switch day they
 * point at the slotted stand kit (/brand/stand/…). Without a matching route
 * every /brand/… reference 404s — the same per-directory outage class
 * vendorSubtree closed for /vendor (third occurrence there: monaco → pdfjs →
 * pptx-renderer; this route closes it for /brand before it can happen).
 *
 * Contract pinned here:
 *  - the slotted stand kit is served at depth 3 (200) with the
 *    extension-derived Content-Type and `Cache-Control: no-cache`
 *  - the slot tree is served at any depth (the class stays closed)
 *  - traversal is rejected: literal ".." segments and backslash forms 404
 *  - missing resources 404; trailing slash (directory request) is rejected
 */
class BrandSubtreeRoutesSpec extends FunSuite:

  private def get(path: String) =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(path))
    StaticRoutes.brandSubtree(req).unsafeRunSync()

  private def assertServed(path: String, expectedTypePart: String): Unit =
    val resp = get(path)
    assertEquals(resp.status, Status.Ok, s"expected 200 for $path")
    val contentType = resp.headers.get(CIString("Content-Type")).map(_.head.value)
    assert(
      contentType.exists(_.contains(expectedTypePart)),
      s"expected $expectedTypePart content type for $path, got: $contentType"
    )
    // no-cache: a rebuilt classpath slot tree must be picked up by the
    // browser (ETag revalidation keeps it a cheap 304) — jsRoutes convention.
    val cacheControl = resp.headers.get(CIString("Cache-Control")).map(_.head.value)
    assertEquals(cacheControl, Some("no-cache"), s"expected no-cache for $path")

  test("serves the slotted stand kit logo files (the switch-day references)") {
    assertServed("/brand/stand/logo/logo-512-light.png", "image/png")
    assertServed("/brand/stand/logo/logo-512-dark.png", "image/png")
  }

  test("serves the slot tree at any depth (outage class stays closed)") {
    // Any future brand subdirectory must not need another hand-written case.
    assertServed("/brand/stand/logo/logo-512-light.png", "image/png")
  }

  test("missing resources 404 at any depth") {
    assertEquals(get("/brand/stand/logo/nope.png").status, Status.NotFound)
    assertEquals(get("/brand/nope-dir/nope.png").status, Status.NotFound)
  }

  test("rejects traversal via literal dotdot segments") {
    assertEquals(get("/brand/../auth.json").status, Status.NotFound)
    assertEquals(get("/brand/stand/../stand/logo-512-light.png").status, Status.NotFound)
    assertEquals(get("/brand/..").status, Status.NotFound)
  }

  test("rejects trailing slash (directory request)") {
    assertEquals(get("/brand/stand/").status, Status.NotFound)
    assertEquals(get("/brand/stand/logo/").status, Status.NotFound)
  }

  test("rejects backslash segments") {
    // Encoded form (vendor-subtree pattern): a RAW backslash is rejected by
    // http4s URI parsing before the route ever runs, so the pin uses %5C —
    // it must land on 404 (no such resource), never leak a file.
    assertEquals(get("/brand/stand%5Clogo/logo-512-light.png").status, Status.NotFound)
  }
