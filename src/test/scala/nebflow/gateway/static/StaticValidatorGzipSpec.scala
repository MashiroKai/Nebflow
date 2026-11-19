package nebflow.gateway.static

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/**
 * 静态面重验证批（2026-11-18）· GzipMiddleware 的验证器纪律半边。
 *
 * 决策：强验证器**不得**跨内容编码共享（gzip 体与 identity 体逐字节不同）⇒ 继续
 * 剥除；弱验证器**定义上**与编码无关（RFC 9110 §8.8.3 弱比较忽略编码）⇒ **保留**
 * ——index 腿的 `no-cache` + ETag 304 因此能在压缩面成立，同时 StaticFile 全族
 * （强标签）行为零变化。
 *
 * 闸自证：把 `weakEtagPresent` 分支去掉（回到恒剥除）⇒ W1 红；把剥除也去掉
 * （恒保留）⇒ W2 红。
 */
class StaticValidatorGzipSpec extends FunSuite:

  private def gzipGet(resp: Response[IO]): Response[IO] =
    val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root => IO.pure(resp) }
    val req = Request[IO](Method.GET, Uri.unsafeFromString("http://127.0.0.1:6138/"))
      .putHeaders("Accept-Encoding" -> "gzip")
    GzipMiddleware(routes).apply(req).value.unsafeRunSync().getOrElse(fail("no response"))

  private def htmlEntity(etag: String): Response[IO] =
    Response[IO](Status.Ok)
      .withEntity("<html>hi</html>")
      .putHeaders("Content-Type" -> "text/html; charset=utf-8", "ETag" -> etag)

  test("W1 a WEAK validator survives the gzip switch (the index 304 leg)") {
    val out = gzipGet(htmlEntity("W/\"abc123\""))
    assertEquals(out.headers.get(CIString("Content-Encoding")).map(_.head.value), Some("gzip"))
    assertEquals(out.headers.get(CIString("ETag")).map(_.head.value), Some("W/\"abc123\""))
    assertEquals(out.headers.get(CIString("Vary")).map(_.head.value), Some("Accept-Encoding"))
  }

  test("W2 a STRONG validator is still stripped on the gzip face (StaticFile family unchanged)") {
    val out = gzipGet(htmlEntity("\"abc123\""))
    assertEquals(out.headers.get(CIString("Content-Encoding")).map(_.head.value), Some("gzip"))
    assertEquals(out.headers.get(CIString("ETag")).map(_.head.value), None)
  }

end StaticValidatorGzipSpec
