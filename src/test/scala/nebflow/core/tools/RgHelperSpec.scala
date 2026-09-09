package nebflow.core.tools

import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * RgHelper 20260909 Glob 修复批回归：
 *  - readWithLimit 超限从「抛异常即死」改为「截断 + truncated 标志」（defect ② 地基）；
 *  - runRg 新增 processCwd（Glob 以搜索根为 rg cwd 搜 "." —— gitignore 锚定
 *    语义以搜索根为基准；单段通配对绝对路径永零命中的根因修复）；
 *  - runRg 新增 truncateOnOverflow：false（Grep）保持硬 ToolError 行为零回归，
 *    true（Glob）截断返回。
 *
 * rg 依赖：与 GlobToolSpec 同先例（工具面真跑 rg；PATH 或 ~/.nebflow/bin）。
 */
class RgHelperSpec extends FunSuite:

  private val utf8 = StandardCharsets.UTF_8

  test("readWithLimit passes small streams through untruncated") {
    val (out, truncated) = RgHelper.readWithLimit(new ByteArrayInputStream("a\nb\nc".getBytes(utf8)))
    assertEquals(out, "a\nb\nc")
    assert(!truncated, "small stream must not be flagged truncated")
  }

  test("readWithLimit truncates at the 500KB cap instead of throwing") {
    val big = ("x" * 199 + "\n") * 5000 // ~1MB of 200B lines
    val (out, truncated) = RgHelper.readWithLimit(new ByteArrayInputStream(big.getBytes(utf8)))
    assert(truncated, "over-cap stream must report truncation")
    assert(out.getBytes(utf8).length <= 500 * 1024, "content stays within the cap")
    assert(out.linesIterator.length >= 2000, "a large prefix of lines is kept")
  }

  /** ~675KB of rg output: 1500 files under a 200-char dir, ~450B path lines each. */
  private def bigFixture(prefix: String): os.Path =
    val tmp = os.Path(java.nio.file.Files.createTempDirectory(prefix))
    val dir = tmp / ("d" * 200)
    (1 to 1500).foreach { i =>
      val pad = "f" * (250 - s"$i".length - 4)
      os.write(dir / s"${pad}$i.txt", "x", createFolders = true)
    }
    tmp

  test("runRg honors processCwd for relative '.' search") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-rghelper"))
    try
      os.write(tmp / "one.txt", "1", createFolders = true)
      os.write(tmp / "sub" / "two.txt", "2", createFolders = true)
      val r = RgHelper.runRg(
        List("--files", "--no-ignore", "--no-messages", "."),
        os.pwd.toString,
        processCwd = Some(tmp.toString)
      )
      r match
        case Right((stdout, _, code, truncated)) =>
          assertEquals(code, 0)
          assert(!truncated)
          val lines = stdout.trim.split("\\n").toList.map(_.stripPrefix("./")).sorted
          assertEquals(lines, List("one.txt", "sub/two.txt"), "relative search must list cwd-relative paths")
        case Left(err) => fail(s"unexpected ToolError: ${err.message}")
    finally os.remove.all(tmp)
  }

  test("runRg truncateOnOverflow=false keeps the hard 500KB ToolError (Grep behavior)") {
    val tmp = bigFixture("nb-rghelper-big")
    try
      val r = RgHelper.runRg(
        List("--files", "--glob", "/**", "--sort=modified", "--color=never", "--no-ignore", "--no-messages", "."),
        os.pwd.toString,
        processCwd = Some(tmp.toString),
        truncateOnOverflow = false
      )
      assert(r.isLeft, "Grep-mode overflow stays a hard error")
      assert(r.swap.toOption.get.message.contains("500KB"), s"${r.swap.toOption.get.message}")
    finally os.remove.all(tmp)
  }

  test("runRg truncateOnOverflow=true returns truncated stdout with the flag (Glob behavior)") {
    val tmp = bigFixture("nb-rghelper-trunc")
    try
      val r = RgHelper.runRg(
        List("--files", "--glob", "/**", "--sort=modified", "--color=never", "--no-ignore", "--no-messages", "."),
        os.pwd.toString,
        processCwd = Some(tmp.toString),
        truncateOnOverflow = true
      )
      r match
        case Right((stdout, _, code, truncated)) =>
          assert(truncated, "over-cap output must be flagged")
          assert(stdout.getBytes(utf8).length <= 500 * 1024 + 300, "stdout stays within cap (line-boundary tolerance)")
          assert(stdout.trim.split("\\n").length > 500, "a large prefix of sorted lines survives")
        case Left(err) => fail(s"unexpected ToolError: ${err.message}")
    finally os.remove.all(tmp)
  }

end RgHelperSpec
