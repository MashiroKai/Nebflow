package nebflow.gateway.static

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets

/**
 * 静态面重验证批（2026-11-18）的 HTTP 层回归闸 —— index 应答的**条件请求**半边。
 *
 * 修前形态（实测于活实例 6138）：`GET /` 只有 `Cache-Control: no-cache`，
 * **既无 ETag 又无 Last-Modified** ⇒ `no-cache` 要求的「用前核对」无验证器可用 ⇒
 * 浏览器每次导航全量重下整页体（dev 树 88 304 B / gzip 33 510 B，隧道腿每跳过境）。
 *
 * 本 spec 直驱**真实路由函数**（不走 mock），断言出向响应层的：
 *   C1 200 带**弱** ETag（弱 = gzip 面可共享，见 StaticValidatorGzipSpec）；
 *   C2 同标签回寄 ⇒ 304 + 同标签 + 零体；
 *   C3 陈旧标签 ⇒ 仍是 200 全量（绝不假 304）；
 *   C4 `*` 通配 ⇒ 304。
 *
 * 闸自证（检测力）：把 ETag/304 支路删掉（回到「无验证器」形态）⇒ C1/C2/C4 全红。
 */
class IndexConditionalServeSpec extends FunSuite:

  private val ETagH = CIString("ETag")
  private val CCH = CIString("Cache-Control")
  private val INM = "If-None-Match"

  private def get(inm: Option[String]): Response[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString("http://127.0.0.1:6138/"))
    val req = inm.fold(base)(v => base.putHeaders(INM -> v))
    StaticRoutes.indexResponseFor(req).unsafeRunSync()

  private def bodyOf(r: Response[IO]): String =
    new String(r.body.compile.toList.unsafeRunSync().toArray, StandardCharsets.UTF_8)

  test("C1 index 200 carries a WEAK ETag validator (a repeat navigation can 304)") {
    val r = get(None)
    assertEquals(r.status, Status.Ok)
    val et = r.headers.get(ETagH).map(_.head.value)
    assert(clue(et).exists(_.startsWith("W/")), s"ETag must be a weak validator, got $et")
    assertEquals(r.headers.get(CCH).map(_.head.value), Some("no-cache"))
    assert(bodyOf(r).nonEmpty, "the 200 body must not be empty")
  }

  test("C2 revalidation with the served tag is 304 + same tag + zero body") {
    val tag = get(None).headers.get(ETagH).map(_.head.value).getOrElse(fail("no ETag on the first 200"))
    val second = get(Some(tag))
    assertEquals(second.status, Status.NotModified)
    assertEquals(second.headers.get(ETagH).map(_.head.value), Some(tag))
    assertEquals(bodyOf(second), "")
  }

  test("C3 a stale validator is never a false 304 (full 200 body)") {
    val r = get(Some("W/\"0000000000000000\""))
    assertEquals(r.status, Status.Ok)
    assert(bodyOf(r).nonEmpty, "stale validator must fall back to the full body")
  }

  test("C4 wildcard If-None-Match matches the current representation") {
    assertEquals(get(Some("*")).status, Status.NotModified)
  }

end IndexConditionalServeSpec
