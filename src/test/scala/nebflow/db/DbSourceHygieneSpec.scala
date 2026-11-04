package nebflow.db

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * V7 / R20 —— 机械判据自证（对源码树本身的可上机核对）：
 *  - V7 无静默空视图：db 管辖树 load 路径 `getOrElse(Nil)` / `getOrElse(Json.obj())` == 0
 *    （现状 9 处同形的反面；本库三态读杜绝之）；
 *  - R20 db=唯一写面：db 主树内禁 os-lib 裸写与 Files.write 便捷面（五步写/追加腿是唯一写面）。
 */
class DbSourceHygieneSpec extends FunSuite:

  private val dbSourceRoot: Path =
    Path.of(sys.props("user.dir")).resolve("src").resolve("main").resolve("scala")
      .resolve("nebflow").resolve("db")

  private def mainSources: List[Path] =
    require(Files.isDirectory(dbSourceRoot), s"db source root missing: $dbSourceRoot")
    val walk = Files.walk(dbSourceRoot)
    try walk.iterator().asScala.filter(p =>
      Files.isRegularFile(p) && p.getFileName.toString.endsWith(".scala")).toList
    finally walk.close()

  test("V7: no getOrElse(Nil) / getOrElse(Json.obj()) anywhere in the db tree"):
    val offenders = mainSources.flatMap { f =>
      val rel = dbSourceRoot.relativize(f).toString
      Files.readAllLines(f).asScala.zipWithIndex.collect {
        case (line, idx) if line.contains("getOrElse(Nil)") || line.contains("getOrElse(Json.obj())") =>
          s"$rel:${idx + 1}: ${line.trim}"
      }
    }
    assertEquals(offenders, List.empty[String], "三态读的机械禁令必须零命中")

  test("R20: db tree has zero bare-write call sites (os.write / Files.write / write.over)"):
    val offenders = mainSources.flatMap { f =>
      val rel = dbSourceRoot.relativize(f).toString
      Files.readAllLines(f).asScala.zipWithIndex.collect {
        case (line, idx)
            if line.contains("os.write") || line.contains("Files.write")
              || line.contains("write.over") || line.contains("os.remove") =>
          s"$rel:${idx + 1}: ${line.trim}"
      }
    }
    assertEquals(offenders, List.empty[String],
      "写面单点：只允许 WriteFace 的 FileChannel 五步写/追加腿")

  test("R20: db tree has zero nebflow-internal imports (zero-coupling contract)"):
    val offenders = mainSources.flatMap { f =>
      val rel = dbSourceRoot.relativize(f).toString
      Files.readAllLines(f).asScala.zipWithIndex.collect {
        case (line, idx) if line.trim.startsWith("import nebflow.") &&
          !line.trim.startsWith("import nebflow.db") =>
          s"$rel:${idx + 1}: ${line.trim}"
      }
    }
    assertEquals(offenders, List.empty[String],
      "依赖方向单向：db → 仅 cats-effect/circe/JDK（设计 §3.2a）；nebflow 内部零依赖")

end DbSourceHygieneSpec
