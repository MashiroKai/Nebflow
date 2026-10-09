package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * ReadTool 大文件守卫 spec（大文件批次）：
 *  - 上限显示永不因 Int 整除折叠成 "limit 0MB"（512KB 上限必须显示 0.5MB）
 *  - too-large 报错带完整路径（不只 basename）
 *  - offset/limit/filter 分段读超限文件成功（流式窗口；行号与 suffix 文案
 *    和小文件内存路径同款）
 *  - 无参整读超限文件 → 明确报错
 */
class ReadLargeFileSpec extends FunSuite:

  private val testDir: Path = Files.createTempDirectory("read-large-file-test")

  private def ctx = ToolContext(projectRoot = testDir.toString)

  private def call(input: JsonObject) = ReadTool.call(input, ctx).unsafeRunSync()

  private def jsonInput(path: Path, extra: (String, Json)*): JsonObject =
    JsonObject.fromIterable(List("file_path" -> Json.fromString(path.toString)) ++ extra.toList)

  /** 超限文件（2500 行 ≈ 0.62MB > MAX_FILE_BYTES 512KB），每第 10 行带 ERROR 标记。 */
  private def makeBigFile(name: String): Path =
    val path = testDir.resolve(name)
    val content = (1 to 2500)
      .map { i =>
        if i % 10 == 0 then s"ERROR something bad at line $i"
        else s"line $i: ${"x" * 250}"
      }
      .mkString("\n")
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path

  test("无参整读超限文件：报错带完整路径 + 真实上限，绝不显示 limit 0MB"):
    val path = makeBigFile("big-noarg.log")
    call(jsonInput(path)) match
      case Left(err) =>
        val msg = err.message
        assert(msg.contains(path.toString), s"错误信息必须带完整路径: $msg")
        assert(msg.contains("limit 0.5MB"), s"必须显示真实上限 0.5MB: $msg")
        assert(!msg.contains("limit 0MB"), s"回归钉子: Int 整除上限不得显示 0MB: $msg")
        val expectedSize = f"${Files.size(path).toDouble / 1024 / 1024}%.1f"
        assert(msg.contains(s"(${expectedSize}MB, limit 0.5MB)"), s"体积与上限成对出现: $msg")
        assert(msg.contains("Use offset/limit"), msg)
      case Right(text) => fail(s"超限整读必须报错，却返回了 ${text.length} 字符")

  test("offset/limit 分段读超限文件成功（流式窗口，行号正确）"):
    val path = makeBigFile("big-window.log")
    call(jsonInput(path, "limit" -> Json.fromInt(5))) match
      case Right(text) =>
        val parts = text.split("\n\n") // body + suffix
        assertEquals(parts.length, 2)
        val lines = parts(0).split("\n")
        assertEquals(lines.length, 5)
        assert(lines.head.startsWith("1\tline 1: "), lines.head)
        assert(lines(4).startsWith("5\tline 5: "), lines(4))
        assert(parts(1).contains("(showing 5 of 2500 lines)"), parts(1))
      case Left(err) => fail(s"超限文件分段读必须成功: ${err.message}")

  test("offset 中段窗口：原始行号与内容对齐（普通行 + ERROR 标记行）"):
    val path = makeBigFile("big-offset.log")
    // 行 2000 是 ERROR 标记行（2000 % 10 == 0），窗口覆盖普通行→标记行→普通行
    call(jsonInput(path, "offset" -> Json.fromInt(1999), "limit" -> Json.fromInt(3))) match
      case Right(text) =>
        val parts = text.split("\n\n")
        assertEquals(parts.length, 2)
        val lines = parts(0).split("\n")
        assertEquals(lines.length, 3)
        assert(lines(0).startsWith("1999\tline 1999: "), lines(0))
        assert(lines(1) == "2000\tERROR something bad at line 2000", lines(1))
        assert(lines(2).startsWith("2001\tline 2001: "), lines(2))
        assert(parts(1).contains("(showing 3 of 2500 lines)"), parts(1))
      case Left(err) => fail(err.message)

  test("filter 分段读超限文件：命中行保留原始行号 + matched-lines suffix"):
    val path = makeBigFile("big-filter.log")
    call(jsonInput(path, "filter" -> Json.fromString("ERROR"), "limit" -> Json.fromInt(2))) match
      case Right(text) =>
        val parts = text.split("\n\n")
        assertEquals(parts.length, 2)
        val lines = parts(0).split("\n")
        assertEquals(lines.length, 2)
        assert(lines(0).startsWith("10\tERROR something bad at line 10"), lines(0))
        assert(lines(1).startsWith("20\tERROR something bad at line 20"), lines(1))
        assert(
          parts(1).contains("(showing 2 of 250 matched lines, filter: \"ERROR\" — 250 match(es) in 2500 lines)"),
          parts(1)
        )
      case Left(err) => fail(err.message)

  test("filter 无 limit 读超限文件：全部命中 + 非窗口 suffix"):
    val path = makeBigFile("big-filter-all.log")
    call(jsonInput(path, "filter" -> Json.fromString("ERROR"))) match
      case Right(text) =>
        val parts = text.split("\n\n")
        assertEquals(parts.length, 2)
        assertEquals(parts(0).split("\n").length, 250)
        assert(parts(0).contains("2500\tERROR something bad at line 2500"), parts(0).takeRight(160))
        assert(parts(1).contains("(250 matched lines, filter: \"ERROR\" — 250 match(es) in 2500 lines)"), parts(1))
      case Left(err) => fail(err.message)

  test("offset 越过 EOF：空窗口 + showing-0 suffix"):
    val path = makeBigFile("big-eof.log")
    call(jsonInput(path, "offset" -> Json.fromInt(99999))) match
      case Right(text) =>
        assert(text.contains("(showing 0 of 2500 lines)"), text)
      case Left(err) => fail(err.message)

  test("小文件无参整读不回归（无 suffix、无报错）"):
    val path = testDir.resolve("small.txt")
    Files.write(path, "alpha\nbeta\ngamma".getBytes(StandardCharsets.UTF_8))
    call(jsonInput(path)) match
      case Right(text) =>
        assert(text.startsWith("1\talpha\n2\tbeta\n3\tgamma"), text)
        assert(!text.contains("(showing"), text)
      case Left(err) => fail(err.message)

  test("小文件带 limit 仍走内存路径分页（行为不变）"):
    val path = testDir.resolve("small-limit.txt")
    Files.write(path, "alpha\nbeta\ngamma".getBytes(StandardCharsets.UTF_8))
    call(jsonInput(path, "limit" -> Json.fromInt(2))) match
      case Right(text) =>
        assert(text.startsWith("1\talpha\n2\tbeta"), text)
        assert(text.contains("(showing 2 of 3 lines)"), text)
      case Left(err) => fail(err.message)

  override def afterAll(): Unit =
    try
      Files
        .walk(testDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    catch case _: Exception => ()

end ReadLargeFileSpec
