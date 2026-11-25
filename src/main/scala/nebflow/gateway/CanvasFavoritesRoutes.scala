/* 文档收藏引擎侧存储（2026-11-23 项 5：收藏不显示根因 = 存储按 origin 隔离的 localStorage，
 * 桌面 localhost 与手机 mystand.space 各一份，跨端必空）。 */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * `/api/canvas-favorites` — Canvas 文档收藏的引擎侧单源存储。
 *
 * - `GET`：`{ "list": [ {path,title,savedAt}, … ] }`；文件缺席 = 空列表（不是错误）。
 * - `PUT` body `{ "list": [...] }`：整表替换。服务端校验 `list` 为数组、条目为对象、`path` 为
 *   非空绝对路径（与前端 normalizeList 同口径）、去重、上限 [[MaxEntries]]；非法条目丢弃、
 *   不因单条脏数据拒整表。写盘 = 临时文件 + 原子 move，不留半截文件。
 *
 * 文件：`<dataRoot>/canvas-favorites.json`（数据根 = 有效根，与其它用户态文件同约定）。
 * 两端（桌面 / 手机）同走本路由 ⇒ 同一份收藏，不再依赖 origin。
 */
private[gateway] object CanvasFavoritesRoutes:

  val MaxEntries: Int = 200

  private def storeFile: os.Path = PathUtil.dataRoot / "canvas-favorites.json"

  /** 单条校验（纯）：返回归一化条目或 None（丢弃）。 */
  def normalizeEntry(item: Json): Option[Json] =
    val c = item.hcursor
    c.downField("path").as[String].toOption.map(_.trim).filter(p => p.nonEmpty && p.startsWith("/")).map { path =>
      val title = c.downField("title").as[String].toOption.map(_.trim).filter(_.nonEmpty)
        .getOrElse(path.split('/').lastOption.getOrElse(path))
      val savedAt = c.downField("savedAt").as[Long].toOption.getOrElse(0L)
      Json.obj("path" -> path.asJson, "title" -> title.asJson, "savedAt" -> savedAt.asJson)
    }

  /** 整表归一化（纯）：去重（按 path，保首见）、丢非法、截上限。 */
  def normalizeList(raw: Vector[Json]): Vector[Json] =
    val seen = scala.collection.mutable.Set.empty[String]
    raw.flatMap(normalizeEntry).filter { e =>
      val p = e.hcursor.downField("path").as[String].getOrElse("")
      if seen.contains(p) then false else { seen += p; true }
    }.take(MaxEntries)

  private def readList(): Vector[Json] =
    val f = storeFile
    if !os.isFile(f) then Vector.empty
    else
      io.circe.parser.parse(os.read(f)).toOption
        .flatMap(_.hcursor.downField("list").focus)
        .flatMap(_.asArray).map(_.toVector).getOrElse(Vector.empty)

  private def writeList(list: Vector[Json]): Unit =
    val f = storeFile
    os.makeDir.all(f / os.up)
    val tmp = f / os.up / (f.last + ".tmp")
    os.write.over(tmp, Json.obj("list" -> Json.arr(list*)).spaces2)
    os.move(tmp, f, replaceExisting = true, atomicMove = true)

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "canvas-favorites" =>
        withAuth(req) {
          IO.blocking(readList()).flatMap(l => Ok(Json.obj("list" -> Json.arr(l*))))
        }
      case req @ PUT -> Root / "canvas-favorites" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            body.hcursor.downField("list").focus.flatMap(_.asArray) match
              case None => BadRequest(Json.obj("error" -> "list must be an array".asJson))
              case Some(arr) =>
                val cleaned = normalizeList(arr.toVector)
                IO.blocking(writeList(cleaned)) *>
                  Ok(Json.obj("ok" -> true.asJson, "count" -> cleaned.size.asJson))
          }
        }
    }

end CanvasFavoritesRoutes
