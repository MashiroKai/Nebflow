package nebflow.pilogic

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import munit.CatsEffectSuite

import scala.jdk.CollectionConverters.*

/** 源码卫生机械判据（对位 db 线 DbSourceHygieneSpec 先例）：A-3 无硬编码帽 / A-4 无全局态 /
  * 边界禁反向 import。只扫主源码 .scala 的非注释行（行注释、星号续行、块注释标记起头的行被滤除）。 */
class PilogicHygieneSpec extends CatsEffectSuite:

  private val dir = Paths.get("src", "main", "scala", "nebflow", "pilogic")

  private def codeLines(): Vector[String] =
    Files.list(dir).iterator().asScala
      .filter(_.getFileName.toString.endsWith(".scala"))
      .toVector
      .sorted
      .flatMap(p => Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toVector)
      .map(_.trim)
      .filterNot(l => l.startsWith("//") || l.startsWith("*") || l.startsWith("/*"))

  test("A-3 无硬编码上限：maxTurns/MaxTurn*/Budget* = 数字 字面量零命中"):
    val cap = raw"(?i)maxTurns?\s*=\s*[0-9]|MaxTurn[A-Za-z]*\s*=\s*[0-9]|Budget\w*\s*=\s*[0-9]".r
    val bad = codeLines().filter(l => cap.findFirstIn(l).isDefined)
    assertEquals(bad, Vector.empty, s"A-3 判红：${bad}")

  test("A-4 无全局态：@volatile / Ref.unsafe / ConcurrentHashMap 零命中"):
    val bad = codeLines().filter(l =>
      l.contains("@volatile") || l.contains("Ref.unsafe") || l.contains("ConcurrentHashMap"),
    )
    assertEquals(bad, Vector.empty, s"A-4 判红：${bad}")

  test("边界：禁 import nebflow.{agent,core,gateway,llm,db}（叶子性，设计 §Ⅳ）"):
    val forbidden = raw"^import nebflow\.(agent|core|gateway|llm|db)\.".r
    val bad = codeLines().filter(l => forbidden.findFirstIn(l).isDefined)
    assertEquals(bad, Vector.empty, s"边界判红：${bad}")
