package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * GrepTool「xerj 借鉴两小步」（2026-10-21 记忆检索批 · Grep 优化提案落地件）：
 *
 *  ① opt-in `ranked`（files_with_matches）：命中计数降序稳定排序——相关性排序
 *     是 xerj 这类语料检索引擎的第一产出（BM25 ranked hits），而 rg 裸walk 序
 *     对 agent 无信息量。实现 = 同一次遍历改走 `--count-matches` + 纯函数
 *     稳定排序，wire 契约（纯路径行）零变化；默认 false = 行为零回归。
 *  ② `--max-columns-preview`：命中行超 --max-columns 时给截断预览而非整行隐藏
 *     ——「命中不隐身」原则，CJK 长行（会话日志/JSONL）受益最直接。
 *
 * 纯函数件（parseCountLine / rankCountLines）直接断言；工具面件真跑 rg
 * （RgHelperSpec 同先例：PATH 或 ~/.nebflow/bin）。
 */
class GrepToolRankedSpec extends FunSuite:

  // ── 纯函数：parseCountLine ──────────────────────────────────────────────

  test("parseCountLine: path:count 形态解析") {
    assertEquals(GrepTool.parseCountLine("src/a.scala:3"), Some(("src/a.scala", 3)))
    assertEquals(GrepTool.parseCountLine("/abs/path b.md:0"), Some(("/abs/path b.md", 0)))
  }

  test("parseCountLine: 非形态行拒绝（无冒号 / 尾段非数字）") {
    assertEquals(GrepTool.parseCountLine("no-colon-here"), None)
    assertEquals(GrepTool.parseCountLine("path:notanumber"), None)
    assertEquals(GrepTool.parseCountLine(""), None)
  }

  // ── 纯函数：rankCountLines ──────────────────────────────────────────────

  test("rankCountLines: 计数降序，同计数保持原序（稳定）") {
    val in = List("b.scala:1", "a.scala:3", "c.scala:1", "d.scala:2")
    assertEquals(
      GrepTool.rankCountLines(in),
      List("a.scala", "d.scala", "b.scala", "c.scala"),
      "3→2→1(先 b 后 c，稳定 tiebreak)"
    )
  }

  test("rankCountLines: 非形态行置尾保留（防御 rg 版本差异，不参与排序）") {
    val in = List("weird-line", "a.scala:2", "b.scala:1")
    assertEquals(GrepTool.rankCountLines(in), List("a.scala", "b.scala", "weird-line"))
  }

  // ── 工具面（真跑 rg）：ranked 端到端 ────────────────────────────────────

  private def call(pattern: String, path: String, extra: (String, io.circe.Json)*): Either[ToolError, String] =
    val base: Seq[(String, io.circe.Json)] = Seq(
      "pattern" -> pattern.asJson,
      "path" -> path.asJson,
      "output_mode" -> "files_with_matches".asJson
    )
    GrepTool.call(JsonObject.fromIterable(base ++ extra), ToolContext(projectRoot = path)).unsafeRunSync()

  test("ranked=true: 多命中文件排前，输出仍是纯路径行") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-grep-ranked"))
    try
      os.write(
        tmp / "many.txt",
        """needle one
        |filler
        |needle two
        |filler
        |needle three""".stripMargin,
        createFolders = true
      )
      os.write(tmp / "one.txt", "needle once only", createFolders = true)
      os.write(tmp / "none.txt", "nothing here", createFolders = true)
      val r = call("needle", tmp.toString, "ranked" -> true.asJson)
      assert(r.isRight, s"ranked call failed: $r")
      val lines = r.toOption.get.split("\n").filter(_.nonEmpty).toList
      assert(lines.nonEmpty)
      assert(lines.head.contains("many.txt"), s"most-matching file must rank first, got: $lines")
      assert(lines.exists(_.contains("one.txt")), s"single-match file still present: $lines")
      assert(!lines.exists(_.contains("none.txt")), "non-matching file must not appear")
      assert(!lines.exists(_.contains(":")), s"wire contract = pure paths, got: $lines")
      // 默认（ranked 缺省 false）：两文件都在，零回归
      val r2 = call("needle", tmp.toString)
      val l2 = r2.toOption.get.split("\n").filter(_.nonEmpty).toList
      assertEquals(l2.size, 2, s"default mode unchanged: $l2")
    finally os.remove.all(tmp)
    end try
  }

  // ── 工具面（真跑 rg）：长行预览（命中不隐身）────────────────────────────

  test("max-columns-preview: 超 500 列命中行给预览而非整行隐藏") {
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-grep-preview"))
    try
      val longLine = "前缀可见" + "梅" * 600 + "needle" + "梅" * 20
      os.write(tmp / "long.txt", longLine, createFolders = true)
      val obj = JsonObject.fromIterable(
        Seq(
          "pattern" -> "needle".asJson,
          "path" -> tmp.toString.asJson,
          "output_mode" -> "content".asJson
        )
      )
      val r = GrepTool.call(obj, ToolContext(projectRoot = tmp.toString)).unsafeRunSync()
      assert(r.isRight, s"content call failed: $r")
      val out = r.toOption.get
      // rg 版本差异：新 marker = "[Omitted long matching line]"，旧 = "[... omitted end of long line]"
      // ——断言只钉「有省略标记 + 预览在标记前 + CJK 头可见」，不钉 marker 具体文案。
      assert(out.toLowerCase.contains("omitted"), s"rg max-columns omission marker expected: ${out.take(120)}")
      assert(!out.strip().startsWith("["), s"preview must precede the marker: ${out.take(120)}")
      assert(out.contains("前缀可见"), s"CJK preview head must be visible: ${out.take(120)}")
    finally os.remove.all(tmp)
    end try
  }

end GrepToolRankedSpec
