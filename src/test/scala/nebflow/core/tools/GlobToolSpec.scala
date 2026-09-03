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

end GlobToolSpec
