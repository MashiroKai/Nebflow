package nebflow.gateway.static

import nebflow.gateway.*

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/**
 * 细粒度单元（static 模块）：缓存协商（ETag/304）+ gzip 中间件的逐分支行为 +
 * 门户 URL 的纯解析核。
 *
 * 既有 `HttpCacheSpec` / `GzipMiddlewareSpec` / `PortalUrlSpec` 走的是整条静态树
 * 或真装配。这里把每个分支单独钉死：一张请求头矩阵 × 一个响应头断言。
 */
class StaticUnitSpec extends CatsEffectSuite:

  private def header(resp: Response[IO], name: String): Option[String] =
    resp.headers.get(CIString(name)).map(_.head.value)

  private def hdr(name: String, value: String): Header.Raw = Header.Raw(CIString(name), value)

  // ── HttpCache：内容寻址 ETag ───────────────────────────────────────────

  test("ofJson/ofSeed: quoted 32-hex tags, stable for the same input"):
    val body = Json.obj("a" -> 1.asJson, "b" -> "x".asJson)
    val tag = HttpCache.ofJson(body)
    assert(tag.matches("\"[0-9a-f]{32}\""), s"unexpected tag shape: $tag")
    assertEquals(HttpCache.ofJson(body), tag, "same payload ⇒ same ETag")
    assertEquals(HttpCache.ofSeed("updatedAt:7"), HttpCache.ofSeed("updatedAt:7"))
    assert(HttpCache.ofSeed("updatedAt:7").matches("\"[0-9a-f]{32}\""))

  test("ofJson/ofSeed: different content or seed ⇒ different ETag"):
    assertNotEquals(HttpCache.ofJson(Json.obj("a" -> 1.asJson)), HttpCache.ofJson(Json.obj("a" -> 2.asJson)))
    assertNotEquals(HttpCache.ofSeed("updatedAt:7"), HttpCache.ofSeed("updatedAt:8"))

  // ── HttpCache.conditionalOk：200/304 矩阵 ──────────────────────────────

  private val Body = Json.obj("ok" -> true.asJson)
  private val Etag = HttpCache.ofJson(Body)
  private val Get = Request[IO](Method.GET, Uri.unsafeFromString("/api/x"))

  private def conditional(ifNoneMatch: Option[String]): IO[Response[IO]] =
    val req = ifNoneMatch.fold(Get)(v => Get.putHeaders(hdr("If-None-Match", v)))
    HttpCache.conditionalOk(req, Body, Etag)

  test("conditionalOk: no If-None-Match ⇒ 200 + restated ETag + private,no-cache"):
    conditional(None).map { resp =>
      assertEquals(resp.status, Status.Ok)
      assertEquals(header(resp, "ETag"), Some(Etag))
      assertEquals(header(resp, "Cache-Control"), Some("private, no-cache"))
    }

  test("conditionalOk: matching tag / list member / * ⇒ 304 with NO body, headers restated"):
    val cases = List(Etag, s"\"other\", $Etag", "*")
    cases.foldLeft(IO.unit) { (acc, inm) =>
      acc.flatMap(_ =>
        conditional(Some(inm)).flatMap { resp =>
          IO {
            assertEquals(resp.status, Status.NotModified, s"If-None-Match: $inm")
            assertEquals(header(resp, "ETag"), Some(Etag))
            assertEquals(header(resp, "Cache-Control"), Some("private, no-cache"))
          }.flatMap(_ => resp.body.compile.toVector.map(b => assert(b.isEmpty, "304 must carry no body")))
        }
      )
    }

  test("conditionalOk: a different tag ⇒ 200 full body"):
    conditional(Some("\"someone-else\"")).map { resp =>
      assertEquals(resp.status, Status.Ok)
      assertNotEquals(header(resp, "ETag"), Some("\"someone-else\""))
    }

  test("conditionalOk: cacheControl override is written verbatim"):
    HttpCache.conditionalOk(Get.putHeaders(hdr("If-None-Match", Etag)), Body, Etag, "private, max-age=60").map { resp =>
      assertEquals(resp.status, Status.NotModified)
      assertEquals(header(resp, "Cache-Control"), Some("private, max-age=60"))
    }

  // ── GzipMiddleware：只包 GET + 可压缩类型 + 非 /ws ─────────────────────

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "ok" =>
      IO.pure(
        Response[IO](Status.Ok)
          .withEntity("<html/>")
          .putHeaders(hdr("Content-Type", "text/html; charset=utf-8"), hdr("ETag", "\"abc\""))
      )
    case GET -> Root / "bin" =>
      IO.pure(Response[IO](Status.Ok).withEntity("x").putHeaders(hdr("Content-Type", "image/png")))
    case GET -> Root / "nm" =>
      IO.pure(Response[IO](Status.NotModified))
    case POST -> Root / "ok" =>
      IO.pure(Response[IO](Status.Ok).withEntity("x").putHeaders(hdr("Content-Type", "text/plain")))
    case GET -> Root / "ws" / "ok" =>
      IO.pure(Response[IO](Status.Ok).withEntity("x").putHeaders(hdr("Content-Type", "text/plain")))
  }

  private val app = GzipMiddleware(routes)

  private def run(method: Method, path: String, acceptGzip: Boolean): IO[Response[IO]] =
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val req = if acceptGzip then base.putHeaders(hdr("Accept-Encoding", "gzip")) else base
    app.run(req).value.map(_.getOrElse(fail(s"route not matched: $path")))

  test("gzip: compressible GET + gzip client ⇒ Content-Encoding gzip, ETag stripped, Vary set"):
    run(Method.GET, "/ok", acceptGzip = true).map { resp =>
      assertEquals(header(resp, "Content-Encoding"), Some("gzip"))
      assertEquals(header(resp, "ETag"), None, "the gzipped representation must not reuse the strong validator")
      assertEquals(header(resp, "Vary"), Some("Accept-Encoding"))
    }

  test("gzip: compressible GET without gzip client ⇒ identity body + Vary"):
    run(Method.GET, "/ok", acceptGzip = false).map { resp =>
      assertEquals(header(resp, "Content-Encoding"), None)
      assertEquals(header(resp, "ETag"), Some("\"abc\""), "identity representation keeps its ETag")
      assertEquals(header(resp, "Vary"), Some("Accept-Encoding"))
    }

  test("gzip: non-compressible type is passed through untouched"):
    run(Method.GET, "/bin", acceptGzip = true).map { resp =>
      assertEquals(header(resp, "Content-Encoding"), None)
      assertEquals(header(resp, "Vary"), None)
    }

  test("gzip: 304 carries Vary; POST and /ws paths are bypassed"):
    for
      nm <- run(Method.GET, "/nm", acceptGzip = false)
      post <- run(Method.POST, "/ok", acceptGzip = true)
      ws <- run(Method.GET, "/ws/ok", acceptGzip = true)
    yield
      assertEquals(header(nm, "Vary"), Some("Accept-Encoding"))
      assertEquals(header(post, "Content-Encoding"), None)
      assertEquals(header(post, "Vary"), None)
      assertEquals(header(ws, "Content-Encoding"), None)
      assertEquals(header(ws, "Vary"), None)

  // ── PortalUrl：纯解析核 ───────────────────────────────────────────────

  test("isOpenUiLocal: only the literal 'local' (trimmed, case-insensitive) opens the escape hatch"):
    assert(PortalUrl.isOpenUiLocal(Some("local")))
    assert(PortalUrl.isOpenUiLocal(Some("  LOCAL ")))
    assert(!PortalUrl.isOpenUiLocal(Some("remote")))
    assert(!PortalUrl.isOpenUiLocal(None))

  test("withDeviceParam: ?/& selection, blank id no-op, URL-encoded device"):
    assertEquals(PortalUrl.withDeviceParam("https://p.example", None), "https://p.example")
    assertEquals(PortalUrl.withDeviceParam("https://p.example", Some("   ")), "https://p.example")
    assertEquals(PortalUrl.withDeviceParam("https://p.example", Some("dev 1")), "https://p.example?device=dev%201")
    assertEquals(PortalUrl.withDeviceParam("https://p.example/?x=1", Some("d")), "https://p.example/?x=1&device=d")

  test("decide: local escape wins; empty config disables the portal; explicit env beats config"):
    assertEquals(PortalUrl.decide(Some("https://cfg"), None, Some("local"), Some("d")), None)
    assertEquals(PortalUrl.decide(Some(""), None, None, None), None, "explicit empty config ⇒ localhost")
    assertEquals(PortalUrl.decide(Some("https://cfg"), Some("   "), None, None), Some("https://cfg"))
    assertEquals(PortalUrl.decide(Some("https://cfg"), Some("https://env"), None, None), Some("https://env"))
    assertEquals(
      PortalUrl.decide(None, None, None, Some("d")),
      Some(PortalUrl.withDeviceParam(PortalUrl.defaultPortalUrl, Some("d")))
    )

end StaticUnitSpec
