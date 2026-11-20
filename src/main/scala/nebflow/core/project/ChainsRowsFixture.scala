package nebflow.core.project

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

/**
 * ChainsRowsFixture —— **只读**：把 `ChainsReadFace.rowsJson` 的行投影（真实代码路径）
 * 落成 UI 夹具，供 mock-HTTP 的 browser fixture 在**同一视口**渲染修前 / 修后两态。
 *
 * 产物（`<out>/`）：
 *   - `rows-next.json` = 现行投影（`live` 键**恒在**：running | interrupted）；
 *   - `rows-prev.json` = **退役臂逐字复现**：旧臂只在 agentRegistry 命中时落 `live` 键；
 *     本夹具 registry 为空 ⇒ 全行无 `live` 键 ⇒ 前端 `rowFromRegistry` 兜底 running。
 *     （即：prev = next 去掉 `live` 键——空 registry 下这是旧臂的**逐字**输出形态。）
 *
 * 纪律：零写盘到数据根、零网络、零凭据、零 6138；数据根必须在 /tmp（隔离快照）。
 */
object ChainsRowsFixture:

  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: ChainsRowsFixture <isolated-data-root under /tmp> <out-dir>")
    val root = os.Path(args(0), os.pwd)
    require(
      root.startsWith(os.Path("/tmp")) || root.startsWith(os.Path("/private/tmp")),
      s"refused: isolated /tmp root required (got '$root')"
    )
    PathUtil.setDataRoot(root)
    val out = os.Path(args(1), os.pwd)
    os.makeDir.all(out)

    val records = nebflow.core.delegate.DelegateRegistry.list()
    val adopted = Set.empty[String] // constructed empty snapshot, not a live-instance measurement
    val next = ChainsReadFace.rowsJson(Map.empty, archived = false, adoptedIds = adopted).unsafeRunSync()
    val rows = next.hcursor.downField("rows").as[List[Json]].getOrElse(Nil)
    val running = rows.count(_.hcursor.downField("live").get[String]("status").toOption.contains("running"))
    val prev = rows.map { r =>
      r.asObject.map(o => Json.fromJsonObject(o.remove("live"))).getOrElse(r)
    }
    os.write.over(out / "rows-next.json", Json.obj("rows" -> rows.asJson).noSpaces)
    os.write.over(out / "rows-prev.json", Json.obj("rows" -> prev.asJson).noSpaces)
    println(
      Json
        .obj(
          "root" -> root.toString.asJson,
          "sourceTotal" -> records.size.asJson,
          "projectedRunning" -> running.asJson,
          "constructedAdoptedCount" -> adopted.size.asJson,
          "rowsNext" -> rows.size.asJson,
          "rowsPrev" -> prev.size.asJson,
          "out" -> out.toString.asJson
        )
        .noSpaces
    )
  end main

end ChainsRowsFixture
