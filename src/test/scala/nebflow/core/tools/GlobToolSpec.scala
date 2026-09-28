package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * Glob pattern 静态前缀修复（20260903）回归：pattern 静态目录前缀（如
 * "src/main/resources/web/js"）改 os.RelPath 多段拼接——此前 os-lib 单段 `/`
 * 拼接对含 "/" 的前缀抛 InvalidSegment，description 鼓励的 "src/**/*.ts" 写法
 * 自身必崩（nebflow.log 当日 5 崩实锤，GlobTool.scala:88）。
 *
 * 覆盖：当日 5 个失败 case 化简/原样形态（含 ** 递归前缀）+ description 原示例
 * 写法 + 三形态回归（显式相对 path / 绝对 path / 默认根）+ ".." 段拒绝（对齐
 * 旧行为：拒，可行动文案；os-lib 原始文案不外泄）。
 *
 * 断言基准：rg 以 user.dir（= sbt 模块根，本仓 checkout）为默认根，
 * src/main/... 与 src/test/... 为实存文件（含本 spec 自身）。
 */
class GlobToolSpec extends FunSuite:

  private def call(pattern: String, path: Option[String] = None): Either[ToolError, String] =
    val obj = path match
      case Some(p) => JsonObject("pattern" -> pattern.asJson, "path" -> p.asJson)
      case None    => JsonObject("pattern" -> pattern.asJson)
    GlobTool.call(obj, ToolContext(projectRoot = os.pwd.toString)).unsafeRunSync()

  // ── 当日 5 崩回归（04:34 / 09:38 / 12:35 / 13:05 / 18:10）──────────

  test("crash 1/5 (04:34 Coder): recursive brace pattern under src/test/scala") {
    val r = call("src/test/scala/**/{Worktree,Glob}*.scala")
    assert(r.isRight, s"InvalidSegment crash regression: $r")
    val out = r.toOption.get
    assert(out.contains("WorktreeParamSpec.scala"), s"expected self-match, got: $out")
  }

  test("crash 2/5 (09:38 Coder): web js recursive pattern") {
    val r = call("src/main/resources/web/js/**/*.js")
    assert(r.isRight, s"InvalidSegment crash regression: $r")
    assert(r.toOption.get.contains("chat.js"), s"expected chat.js, got: ${r.toOption.get.take(300)}")
  }

  test("crash 3/5 (12:35 Coder): scala source recursive prefix pattern") {
    val r = call("src/main/scala/**/InteractionHub*.scala")
    assert(r.isRight, s"InvalidSegment crash regression: $r")
    assert(r.toOption.get.contains("InteractionHub.scala"))
  }

  test("crash 4/5 (13:05 design-engineer): web-wide recursive pattern") {
    val r = call("src/main/resources/web/**/*.js")
    assert(r.isRight, s"InvalidSegment crash regression: $r")
    // web/ 下 .js 共 186 个 > MAX_RESULTS(100)：mtime 截断语义下具体文件不保证在
    // 前 100——断言非空命中即可（崩溃回归是本用例的靶心）。
    val out = r.toOption.get
    assert(out.contains(".js") && !out.startsWith("No files"), s"expected js hits, got: ${out.take(200)}")
  }

  test("crash 5/5 (18:10 dispatcher): multi-segment static prefix, non-recursive tail") {
    val r = call("src/main/resources/web/js/orb*.js")
    assert(r.isRight, s"InvalidSegment crash regression: $r")
    val out = r.toOption.get
    assert(out.contains("orbPresets.js") && out.contains("orbSettingsUI.js"), s"got: $out")
  }

  test("crash-form canonical: 'src/test/scala/**/{A,B}*.scala' shape with ** recursive prefix") {
    val r = call("src/test/scala/**/{Worktree,Path}*.scala")
    assert(r.isRight, s"InvalidSegment crash regression: $r")
    assert(r.toOption.get.contains("WorktreeParamSpec.scala"))
  }

  // ── description 原示例写法必须直接可用 ────────────────────

  test("description example '**/*.js' works from default root") {
    // 默认根含 target/ 下副本 → 断言不崩即可（截断语义另测）
    assert(call("**/*.js").isRight)
  }

  test("description example 'src/**/*.ts' parses (no crash; empty result acceptable)") {
    val r = call("src/**/*.ts")
    assert(r.isRight, s"description example must not crash: $r")
  }

  // ── 三形态回归（显式相对 path / 绝对 path / 默认根）────────────

  test("form 1: explicit relative path param") {
    val r = call("**/NodeEngine.scala", Some("src/main/scala"))
    assert(r.isRight)
    assert(r.toOption.get.contains("NodeEngine.scala"), s"got: ${r.toOption.get.take(300)}")
  }

  test("form 2: absolute path param") {
    val r = call("**/NodeEngine.scala", Some((os.pwd / "src" / "main" / "scala").toString))
    assert(r.isRight)
    assert(r.toOption.get.contains("NodeEngine.scala"))
  }

  test("form 3: default root, pattern carries the directory prefix (fixed path itself)") {
    val r = call("src/main/scala/**/NodeEngine.scala")
    assert(r.isRight)
    assert(r.toOption.get.contains("NodeEngine.scala"))
  }

  // ── ".." 段拒绝（对齐旧行为：拒；可行动文案；os-lib 原文不外泄）──────

  test("'..' segment in static prefix rejected with actionable message (GLOB_PATTERN)") {
    val r = call("src/../leak/**/*.scala")
    assert(r.isLeft, s"'..' traversal must stay rejected: $r")
    val msg = r.swap.toOption.get.message
    assert(msg.contains("GLOB_PATTERN"), s"error code missing: $msg")
    assert(msg.contains("src/../leak"), s"offending prefix must be echoed: $msg")
    assert(!msg.contains("not a valid path segment"), s"os-lib raw message leaked: $msg")
  }

  // ── 20260909 Glob 修复批：单层语义 / 超限截断 / dot 显式 / CJK / head_limit ──

  private def callFull(pattern: String, path: Option[String], headLimit: Option[Int]): Either[ToolError, String] =
    val fields = scala.collection.mutable.ListBuffer[(String, io.circe.Json)]("pattern" -> pattern.asJson)
    path.foreach(p => fields += ("path" -> p.asJson))
    headLimit.foreach(n => fields += ("head_limit" -> n.asJson))
    GlobTool.call(JsonObject.fromIterable(fields.toList), ToolContext(projectRoot = os.pwd.toString)).unsafeRunSync()

  private def withFixture(f: os.Path => Unit): Unit =
    val tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-glob-fix"))
    try f(tmp)
    finally os.remove.all(tmp)

  /** result lines that are actual entries (truncation notes start with "(") */
  private def entryLines(out: String): List[String] =
    out.split("\\n").toList.filter(l => l.nonEmpty && !l.startsWith("("))

  test("'*' is strictly single-level — no recursion into subdirectories (defect ①)") {
    withFixture { root =>
      os.write(root / "top.txt", "a", createFolders = true)
      os.write(root / "sub" / "deep.txt", "b", createFolders = true)
      val out = callFull("*", Some(root.toString), None).toOption.get
      val lines = entryLines(out)
      assert(lines.contains("top.txt"), s"top-level file must be listed: $out")
      assert(!lines.exists(_.contains("deep.txt")), s"'*' must not recurse into sub/: $out")
    }
  }

  test("'*.txt' is anchored to a single level (not basename-recursive)") {
    withFixture { root =>
      os.write(root / "top.txt", "a", createFolders = true)
      os.write(root / "other.txt", "c", createFolders = true)
      os.write(root / "sub" / "deep.txt", "b", createFolders = true)
      val lines = entryLines(callFull("*.txt", Some(root.toString), None).toOption.get).sorted
      assertEquals(lines, List("other.txt", "top.txt"), s"'*.txt' must stay single-level: $lines")
    }
  }

  test("'**' recurses through subdirectories") {
    withFixture { root =>
      os.write(root / "top.txt", "a", createFolders = true)
      os.write(root / "sub" / "deep.txt", "b", createFolders = true)
      val lines = entryLines(callFull("**/*.txt", Some(root.toString), None).toOption.get)
      assert(lines.exists(_.contains("top.txt")) && lines.exists(_.contains("deep.txt")),
        s"'**' must recurse for both: $lines")
    }
  }

  test("static prefix + single-level tail stays within the prefix directory") {
    withFixture { root =>
      os.write(root / "src" / "a.txt", "a", createFolders = true)
      os.write(root / "src" / "sub" / "b.txt", "b", createFolders = true)
      os.write(root / "a.txt", "top", createFolders = true)
      // searchRoot = <root>/src（静态前缀被吃掉），输出相对 searchRoot
      val lines = entryLines(callFull("src/*.txt", Some(root.toString), None).toOption.get)
      assertEquals(lines, List("a.txt"), s"prefix-dir single level only, root a.txt excluded: $lines")
    }
  }

  test("dot-files and dot-dirs are hidden by default (no VCS internals in results)") {
    withFixture { root =>
      os.write(root / "a.txt", "a", createFolders = true)
      os.write(root / ".hidden.txt", "h", createFolders = true)
      os.write(root / ".hdir" / "f.txt", "f", createFolders = true)
      os.write(root / ".git" / "objects" / "packfile", "p", createFolders = true)
      val lines = entryLines(callFull("**/*.txt", Some(root.toString), None).toOption.get)
      assertEquals(lines, List("a.txt"), s"dot entries must be excluded by default: $lines")
    }
  }

  test("explicit dot-segment patterns match worktree-style .git pointer files (defect ③, acceptance)") {
    withFixture { root =>
      os.write(root / "wt-one" / ".git", "gitdir: /somewhere", createFolders = true)
      os.write(root / "wt-two" / ".git", "gitdir: /elsewhere", createFolders = true)
      os.write(root / "plain.txt", "x", createFolders = true)

      val bySeg = entryLines(callFull("*/.git", Some(root.toString), None).toOption.get).sorted
      assertEquals(bySeg, List("wt-one/.git", "wt-two/.git"), s"'*/.git' must hit pointer files: $bySeg")

      val byName = entryLines(callFull(".git", Some(root.toString), None).toOption.get).sorted
      assertEquals(byName, List("wt-one/.git", "wt-two/.git"), s"literal '.git' must hit pointer files: $byName")
    }
  }

  test("explicit dot-dir pattern lists dot-dir contents") {
    withFixture { root =>
      os.write(root / "a.txt", "a", createFolders = true)
      os.write(root / ".dotfile", "d", createFolders = true)
      os.write(root / ".hdir" / "f.txt", "f", createFolders = true)

      val inDotDir = entryLines(callFull(".hdir/*", Some(root.toString), None).toOption.get)
      // 静态前缀 .hdir 被吃为 searchRoot，输出相对该前缀目录（同 crash-5 旧语义）
      assertEquals(inDotDir, List("f.txt"), s"explicit dot-dir pattern must list contents: $inDotDir")

      val dotNames = entryLines(callFull(".*", Some(root.toString), None).toOption.get)
      assertEquals(dotNames, List(".dotfile"), s"'.*' anchors to root-level dot-files: $dotNames")
    }
  }

  test("CJK directory names match in patterns (defect ③ non-ASCII)") {
    withFixture { root =>
      os.write(root / "中文目录" / "内文件.txt", "c", createFolders = true)
      os.write(root / "中文文件.txt", "f", createFolders = true)

      // 静态中文前缀 → searchRoot 吃掉前缀，输出相对前缀目录
      val static = entryLines(callFull("中文目录/*", Some(root.toString), None).toOption.get)
      assertEquals(static, List("内文件.txt"), s"static CJK prefix: $static")

      val seg = entryLines(callFull("*/内文件.txt", Some(root.toString), None).toOption.get)
      assertEquals(seg, List("中文目录/内文件.txt"), s"CJK dir under wildcard segment: $seg")

      val rec = entryLines(callFull("中文*/**", Some(root.toString), None).toOption.get)
      assert(rec.contains("中文目录/内文件.txt"), s"CJK wildcard prefix recursive: $rec")
    }
  }

  test("head_limit pages with exact total note and validates its range") {
    withFixture { root =>
      (1 to 5).foreach(i => os.write(root / s"f$i.txt", "x", createFolders = true))

      val paged = callFull("*.txt", Some(root.toString), Some(2)).toOption.get
      assertEquals(entryLines(paged).length, 2, s"head_limit=2 must return 2 rows: $paged")
      assert(paged.contains("(5 matches in total"), s"note must carry the exact total: $paged")

      val full = callFull("*.txt", Some(root.toString), Some(10)).toOption.get
      assert(!full.contains("matches in total"), s"no note when everything fits: $full")

      val zero = callFull("*.txt", Some(root.toString), Some(0))
      assert(zero.isLeft && zero.swap.toOption.get.message.contains("GLOB_HEAD_LIMIT"), s"$zero")

      val huge = callFull("*.txt", Some(root.toString), Some(10001))
      assert(huge.isLeft && huge.swap.toOption.get.message.contains("GLOB_HEAD_LIMIT"), s"$huge")
    }
  }

  test("oversized result set truncates with unknown-total note instead of erroring (defect ②)") {
    withFixture { root =>
      // 1500 files x ~450B path line ≈ 675KB > 500KB rg cap
      val dir = root / ("d" * 200)
      (1 to 1500).foreach { i =>
        val pad = "f" * (250 - s"$i".length - 4)
        os.write(dir / s"${pad}$i.txt", "x", createFolders = true)
      }
      val r = callFull("**", Some(root.toString), None)
      assert(r.isRight, s"oversized result must truncate, not hard-error: ${r.swap.toOption.get.message}")
      val out = r.toOption.get
      assert(out.contains("500KB"), s"unknown-total note expected: ${out.take(400)}")
      assert(entryLines(out).sizeIs <= 100, s"default head_limit (100) still applies: ${entryLines(out).size}")
    }
  }

end GlobToolSpec
