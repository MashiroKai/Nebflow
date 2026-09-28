package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.*

/**
 * Route-level tests for WebSocketRoutes.assetsRoutes (P1: the /assets
 * wildcard for the esbuild dist tree) and the hasBundledDist probe.
 *
 * Fixtures: src/test/resources/web-dist/assets/ contains real classpath
 * resources for this spec. Deliberately NO web-dist/index.html exists on the
 * test classpath, so hasBundledDist stays false and the "/" entry keeps
 * serving the dev tree — the probe and the route are tested independently.
 */
class AssetsRoutesSpec extends CatsEffectSuite:

  private val routes: HttpRoutes[IO] = WebSocketRoutes.assetsRoutes

  private def get(path: String): IO[Option[Response[IO]]] =
    routes(Request[IO](Method.GET, Uri.unsafeFromString(path))).value

  test("serves a bundled asset from web-dist/assets (single segment)") {
    get("/assets/test-asset.js").flatMap {
      case Some(resp) =>
        for bytes <- resp.body.compile.toVector
        yield
          assertEquals(resp.status, Status.Ok)
          assertEquals(new String(bytes.toArray, "UTF-8"), "console.log(\"assets fixture entry\");")
          // hashed names follow the same no-cache discipline as jsRoutes
          assertEquals(
            resp.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value),
            Some("no-cache")
          )
      case None => fail("route fell through for /assets/test-asset.js")
    }
  }

  test("serves nested chunks at any depth (web-dist/assets/chunks)") {
    get("/assets/chunks/nested-1.js").map {
      case Some(resp) => assertEquals(resp.status, Status.Ok)
      case None       => fail("route fell through for nested chunk")
    }
  }

  test("trailing slash (directory request) is rejected") {
    get("/assets/chunks/").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("should have matched the /assets prefix")
    }
  }

  test("path traversal is rejected") {
    get("/assets/..%2F..%2Fsecret.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("should have matched the /assets prefix")
    }
  }

  test("backslash in a segment is rejected") {
    get("/assets/chunks%5Cevil.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("should have matched the /assets prefix")
    }
  }

  test("a missing dist asset 404s instead of falling through") {
    // /assets/app-deadbeef.js exists in no build — dev instances must return
    // a clean 404, and the route must NOT fall through to instance routes
    // (they would 404 too, but via the fileName whitelist — keep it local).
    get("/assets/app-deadbeef.js").map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("prefix-matched request must not fall through")
    }
  }

  test("non-assets paths do not match this route (fall through)") {
    get("/js/main.js").map {
      case Some(_) => fail("assetsRoutes must not own /js paths")
      case None    => () // HttpRoutes fallthrough semantics — correct here
    }
  }

  test("hasBundledDist is false without web-dist/index.html on the classpath") {
    // The test classpath packs web-dist/assets fixtures but NOT the dist
    // index.html — the probe must stay on the dev branch.
    assertEquals(WebSocketRoutes.hasBundledDist, false)
  }

end AssetsRoutesSpec
