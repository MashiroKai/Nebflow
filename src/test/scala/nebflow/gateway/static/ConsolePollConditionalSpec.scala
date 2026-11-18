package nebflow.gateway.static

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.*
import org.typelevel.ci.CIString

/**
 * 滚动面重验证批（2026-11-18）· 控制台最高频读面 `/api/subagent-tasks/:id` 的闸。
 *
 * 读数（修前）：`delegateChainProvider.js` 每 5 s 一拍（≈17 280 次/天·开着的
 * 控制台）拉当前 root 的作用域任务行；服务端该腿**无 ETag / 无 Cache-Control**、
 * 前端恒 `cache:'no-store'` ⇒ 每拍全量重发整个行集（且服务端每拍整表重扫
 * `findAllTasks()`，355 文件 / 8.0 MB —— 服务端半边见报告的「需拍板清单」）。
 *
 * 本批落地的是**客户端可自证的那半边**：服务端接条件协商（未变 ⇒ 304 零体），
 * 前端允许缓存参与（存 + 每次重验证，绝不引入启发式新鲜度）。
 *
 *   S1 路由块走 HttpCache.conditionalOk（未变 ⇒ 304）；
 *   S2 前端该腿不再禁缓存，且用 `'no-cache'`（存 + 每次重验证）；
 *   S3 HttpCache 的条件语义本身（命中 ⇒ 304；首次 ⇒ 200 + ETag + private no-cache）。
 *
 * 闸自证：把 conditionalOk 拆掉 ⇒ S1 红；前端退回 `'no-store'` ⇒ S2 红；
 * 删掉 conditionalOk 的 304 支路 ⇒ S3 红。
 */
class ConsolePollConditionalSpec extends FunSuite:

  private def src(rel: String): String =
    val p = os.pwd / os.RelPath(rel)
    assert(os.exists(p), s"source file missing: $p")
    os.read(p)

  test("S1 the 5s console task-poll read face negotiates a conditional GET (HttpCache)") {
    val routes = src("src/main/scala/nebflow/gateway/PresenceRoutes.scala")
    val idx = routes.indexOf("""Root / "subagent-tasks" / rootSessionId""")
    assert(idx >= 0, "the /subagent-tasks/:id read face must exist")
    val block = routes.substring(idx, math.min(routes.length, idx + 1400))
    assert(
      block.contains("HttpCache.conditionalOk"),
      "the console's highest-frequency read face must answer If-None-Match with 304 (zero body when unchanged)"
    )
  }

  test("S2 the console poller lets the HTTP cache participate (revalidate, never heuristic)") {
    val js = src("src/main/resources/web/js/delegateChainProvider.js")
    val idx = js.indexOf("/api/subagent-tasks/")
    assert(idx >= 0, "the scoped-task fetch must exist")
    val block = js.substring(idx, math.min(js.length, idx + 900))
    assert(!block.contains("cache: 'no-store'"), "the 5s poll must not disable the HTTP cache")
    assert(block.contains("cache: 'no-cache'"), "the 5s poll must store + revalidate (If-None-Match)")
  }

  test("S3 HttpCache.conditionalOk: hit ⇒ 304, miss ⇒ 200 + ETag + private no-cache") {
    val body = io.circe.Json.obj("a" -> io.circe.Json.fromInt(1))
    val etag = HttpCache.ofJson(body)
    def call(inm: Option[String]): Response[IO] =
      val base =
        Request[IO](Method.GET, Uri.unsafeFromString("http://127.0.0.1:6138/api/subagent-tasks/node-1"))
      val req = inm.fold(base)(v => base.putHeaders("If-None-Match" -> v))
      HttpCache.conditionalOk(req, body, etag).unsafeRunSync()
    assertEquals(call(Some(etag)).status, Status.NotModified)
    val ok = call(None)
    assertEquals(ok.status, Status.Ok)
    assertEquals(ok.headers.get(CIString("Cache-Control")).map(_.head.value), Some("private, no-cache"))
    assertEquals(ok.headers.get(CIString("ETag")).map(_.head.value), Some(etag))
  }

end ConsolePollConditionalSpec
