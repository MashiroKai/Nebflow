package nebflow.gateway.static

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.*
import org.typelevel.ci.CIString

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.GZIPInputStream

/**
 * `/api` 有界 JSON 压缩（手机面板卡顿修复批 2026-11-21 · 根因二a）的入口级验红。
 *
 * 这条腿修前**一律 identity**——历史 10.99MB、任务行 1.1MB 裸传（作者场景过门户
 * 隧道，每跳都过境）。本 spec 钉死四类断言，任何一条退化都会红：
 *
 *  1. **正向**：有界 JSON + `Accept-Encoding: gzip` ⇒ `Content-Encoding: gzip`，
 *     去掉 `Content-Length`（否则截断），带 `Vary`，gunzip 后字节与原体**逐字相等**。
 *  2. **流的反向**：`text/event-stream`（SSE）**绝不**压缩——哪怕客户端接受 gzip。
 *     这是原「gzip 不得包 /api」理由里唯一成立的那条，不许回退。
 *  3. **WS 升级面**：`/ws` 前缀与 101 应答一律穿透（防御纵深）。
 *  4. **验证器纪律**：强 ETag 必须剥（否则 identity/gzip 共用强标签 ⇒ 304 通路
 *     整体失效，比修前更糟）；弱标签（`W/"…"`）必须留（RFC 9110 §8.8.3 弱比较
 *     忽略内容编码）。这两条是本批唯一有争议的取舍，因此逐条钉住。
 *  5. **小体/不接受/非 GET**：不压（压缩壳会让小体更肥，且不该动 POST 的体）。
 */
class ApiGzipMiddlewareSpec extends CatsEffectSuite:

  /** ~46KB of JSON — comfortably above MinCompressBytes and highly compressible. */
  private val bigJson: String =
    "{\"rows\":[" + List.fill(800)("{\"id\":\"delegate-nebflow-abc\",\"status\":\"running\"}").mkString(",") + "]}"

  private def routes: HttpRoutes[IO] =
    ApiGzipMiddleware(HttpRoutes.of[IO] {
      case GET -> Root / "delegates" =>
        Ok(bigJson, `Content-Type`(MediaType.application.json), ETag(EntityTag("strong-v1")))
      case GET -> Root / "chains" =>
        Ok(bigJson, `Content-Type`(MediaType.application.json), ETag(EntityTag("weak-v1", EntityTag.Weak)))
      case GET -> Root / "v1" / "chat" / "stream" =>
        Ok("data: {\"delta\":\"x\"}\n\n", `Content-Type`(MediaType.`text/event-stream`))
      case GET -> Root / "tiny" =>
        Ok("{\"ok\":true}", `Content-Type`(MediaType.application.json))
      case POST -> Root / "v1" / "chat" =>
        Ok(bigJson, `Content-Type`(MediaType.application.json))
      case GET -> Root / "ws" =>
        Ok("upgrade-simulation")
    })

  private def get(path: String, headers: Header.ToRaw*): IO[Response[IO]] =
    routes(Request[IO](Method.GET, Uri.unsafeFromString(path), headers = Headers(headers.toList))).value
      .map(_.getOrElse(fail("route fell through")))

  private def gzipAccepted: `Accept-Encoding` = `Accept-Encoding`(ContentCoding.gzip)

  private def gunzipToString(bytes: Array[Byte]): String =
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    try
      val out = new ByteArrayOutputStream()
      in.transferTo(out)
      out.toString("UTF-8")
    finally in.close()

  test("bounded JSON: gzip when accepted + Content-Length dropped + Vary + body byte-identical") {
    for
      resp <- get("/delegates", gzipAccepted)
      bytes <- resp.body.compile.toVector
      body = gunzipToString(bytes.toArray)
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(resp.headers.get[`Content-Encoding`], Some(`Content-Encoding`(ContentCoding.gzip)))
      assertEquals(resp.headers.get[`Content-Length`], None, "compressed body must switch to chunked framing")
      assert(
        resp.headers.get(CIString("Vary")).exists(_.head.value.contains("Accept-Encoding")),
        "compressed representation must advertise Vary"
      )
      assertEquals(body, bigJson, "compression must be lossless")
  }

  test("strong ETag is stripped, weak ETag is kept (identity cannot share a strong validator)") {
    for
      strong <- get("/delegates", gzipAccepted)
      weak <- get("/chains", gzipAccepted)
    yield
      assertEquals(strong.headers.get(CIString("ETag")), None, "strong ETag must not survive the coding switch")
      assert(
        weak.headers.get(CIString("ETag")).exists(_.head.value.startsWith("W/")),
        "weak ETag is content-coding agnostic and must be kept"
      )
  }

  test("SSE stream is never compressed, even when the client accepts gzip") {
    for
      resp <- get("/v1/chat/stream", gzipAccepted)
      body <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None, "an unbounded SSE stream must stay identity")
      assertEquals(new String(body.toArray, "UTF-8"), "data: {\"delta\":\"x\"}\n\n")
  }

  test("WS upgrade prefix passes through untouched") {
    for
      resp <- get("/ws", gzipAccepted)
      body <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(new String(body.toArray, "UTF-8"), "upgrade-simulation")
  }

  test("small bodies below MinCompressBytes stay identity (gzip shell would grow them)") {
    for
      body <- get("/tiny", gzipAccepted).flatMap(_.body.compile.toVector)
      resp <- get("/tiny", gzipAccepted)
    yield
      assert(
        body.length < ApiGzipMiddleware.MinCompressBytes,
        s"fixture must stay below the ${ApiGzipMiddleware.MinCompressBytes}B threshold, got ${body.length}"
      )
      assertEquals(resp.headers.get[`Content-Encoding`], None, "sub-threshold body must not be compressed")
  }

  test("client that does not accept gzip gets identity plus Vary") {
    for
      resp <- get("/delegates")
      body <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(new String(body.toArray, "UTF-8"), bigJson)
      assert(
        resp.headers.get(CIString("Vary")).exists(_.head.value.contains("Accept-Encoding")),
        "identity variant must also advertise Vary so caches keep the two apart"
      )
  }

  test("non-GET requests are left alone") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString("/v1/chat"), headers = Headers(gzipAccepted))
    for
      resp <- routes(req).value.map(_.getOrElse(fail("route fell through")))
      body <- resp.body.compile.toVector
    yield
      assertEquals(resp.headers.get[`Content-Encoding`], None)
      assertEquals(new String(body.toArray, "UTF-8"), bigJson)
  }

  test("sub-threshold constant is a real byte budget, not a placeholder") {
    // Pins the documented rationale: anything Pillar-2 keys off this value.
    assertEquals(ApiGzipMiddleware.MinCompressBytes, 1024L)
  }

end ApiGzipMiddlewareSpec
