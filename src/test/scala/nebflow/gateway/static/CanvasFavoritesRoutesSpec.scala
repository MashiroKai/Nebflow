package nebflow.gateway.static

import nebflow.gateway.*

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * 文档收藏引擎侧归一化纯函数钉（项 5 · 2026-11-23）：
 *  - 非绝对路径 / 空路径 / 非对象条目丢弃；
 *  - 同 path 去重（保首见）；
 *  - 缺失 title 回落 basename；savedAt 缺省 0；
 *  - 整表截上限 MaxEntries（200，与前端 FAVORITES_MAX 同口径）。
 */
class CanvasFavoritesRoutesSpec extends FunSuite:

  private def entry(path: String, title: Option[String] = None): Json =
    Json.obj("path" -> path.asJson) .deepMerge(
      title.fold(Json.obj())(t => Json.obj("title" -> t.asJson)))

  test("非法条目丢弃：相对路径 / 空串 / 非对象") {
    val out = CanvasFavoritesRoutes.normalizeList(Vector(
      entry("relative/a.md"), entry("   "), Json.fromString("oops"), entry("/abs/ok.md")))
    assertEquals(out.size, 1)
    assertEquals(out.head.hcursor.downField("path").as[String].toOption, Some("/abs/ok.md"))
  }

  test("同 path 去重，保首见") {
    val out = CanvasFavoritesRoutes.normalizeList(Vector(
      entry("/x/a.md", Some("first")), entry("/x/a.md", Some("second"))))
    assertEquals(out.size, 1)
    assertEquals(out.head.hcursor.downField("title").as[String].toOption, Some("first"))
  }

  test("缺 title 回落 basename；savedAt 缺省 0") {
    val out = CanvasFavoritesRoutes.normalizeList(Vector(entry("/x/dir/notes.md")))
    assertEquals(out.head.hcursor.downField("title").as[String].toOption, Some("notes.md"))
    assertEquals(out.head.hcursor.downField("savedAt").as[Long].toOption, Some(0L))
  }

  test("整表截上限 MaxEntries") {
    val raw = Vector.tabulate(CanvasFavoritesRoutes.MaxEntries + 25)(i => entry(s"/f/$i.md"))
    assertEquals(CanvasFavoritesRoutes.normalizeList(raw).size, CanvasFavoritesRoutes.MaxEntries)
  }

