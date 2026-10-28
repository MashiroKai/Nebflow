package nebflow.gateway

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.syntax.literals.uri
import org.typelevel.ci.CIString

/**
 * M2 HTTP 缓存语义小刀（设计 v2 §3.1 ①·六端点共用面）——[[HttpCache]] 条件请求协商。
 *
 * 质量闸映射（缓存语义域）：
 *   - **ETag**：载荷内容寻址（ofJson）与结构键（ofSeed）两来源都稳定、可区分；
 *   - **304**：`If-None-Match` 命中（单值 / 逗号列表 / `*`）⇒ 304 且**重述**
 *     ETag + Cache-Control（304 不带策略头浏览器丢条目语义——v2 §3.1 口径）；
 *   - **200**：未命中 ⇒ 全量 + 同款策略头；`private, no-cache` 缺省
 *     （withAuth 之后 ⇒ 永不进共享缓存，#538 同规）。
 */
class HttpCacheSpec extends CatsEffectSuite:

  private def get(ifNoneMatch: Option[String]): IO[Response[IO]] =
    val toRaw: List[Header.ToRaw] =
      ifNoneMatch.map(h => ("If-None-Match" -> h): Header.ToRaw).toList
    val req = Request[IO](Method.GET, uri"/api/chains", headers = Headers(toRaw))
    HttpCache.conditionalOk(req, io.circe.Json.obj("ok" -> io.circe.Json.True), "\"etag-1\"")

  test("ofJson：载荷寻址——同载荷同标签、异载荷异标签（强语义）") {
    IO {
      val a = io.circe.Json.obj("x" -> io.circe.Json.fromLong(1L))
      val b = io.circe.Json.obj("x" -> io.circe.Json.fromLong(2L))
      assertEquals(HttpCache.ofJson(a), HttpCache.ofJson(io.circe.Json.obj("x" -> io.circe.Json.fromLong(1L))))
      assert(clue(HttpCache.ofJson(a)) != clue(HttpCache.ofJson(b)))
      // 键序不同 = 载荷不同（circe JsonObject 保序）⇒ 标签区分（申报：内容寻址按序列化字节）。
      assert(
        clue(HttpCache.ofJson(io.circe.Json.obj("a" -> io.circe.Json.True, "b" -> io.circe.Json.False)))
          != clue(HttpCache.ofJson(io.circe.Json.obj("b" -> io.circe.Json.False, "a" -> io.circe.Json.True)))
      )
    }
  }

  test("ofSeed：结构键稳定且带引号（flow-map/archive 行的 meta.updatedAt 键族）") {
    IO {
      assertEquals(HttpCache.ofSeed("flow-map:42:7"), HttpCache.ofSeed("flow-map:42:7"))
      assert(HttpCache.ofSeed("flow-map:42:7") != HttpCache.ofSeed("flow-map:43:7"))
      assert(HttpCache.ofSeed("flow-map:42:7").startsWith("\"") && HttpCache.ofSeed("flow-map:42:7").endsWith("\""))
    }
  }

  test("conditionalOk：无 If-None-Match ⇒ 200 全量 + ETag + private,no-cache") {
    get(None).map { r =>
      assertEquals(r.status, org.http4s.Status.Ok)
      assertEquals(r.headers.get(CIString("ETag")).map(_.head.value), Some("\"etag-1\""))
      assertEquals(r.headers.get(CIString("Cache-Control")).map(_.head.value), Some("private, no-cache"))
    }
  }

  test("conditionalOk：命中 ⇒ 304 + ETag/Cache-Control 重述（不带体）") {
    get(Some("\"etag-1\"")).flatMap { r =>
      assertEquals(r.status, org.http4s.Status.NotModified)
      assertEquals(r.headers.get(CIString("ETag")).map(_.head.value), Some("\"etag-1\""))
      assertEquals(r.headers.get(CIString("Cache-Control")).map(_.head.value), Some("private, no-cache"))
      r.body.compile.toVector.map(v => assertEquals(v.size, 0, "304 must not carry a body"))
    }
  }

  test("conditionalOk：If-None-Match 列表（弱比较足够）与 * 通配都命中 304；未列标签 ⇒ 200") {
    for
      listHit <- get(Some("\"old-etag\", \"etag-1\""))
      starHit <- get(Some("*"))
      miss <- get(Some("\"etag-2\""))
    yield
      assertEquals(listHit.status, org.http4s.Status.NotModified)
      assertEquals(starHit.status, org.http4s.Status.NotModified)
      assertEquals(miss.status, org.http4s.Status.Ok)
  }

end HttpCacheSpec
