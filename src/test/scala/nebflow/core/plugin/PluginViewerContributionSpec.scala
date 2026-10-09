package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * canvas-media Wave1 (2026-10-05, OD-3=K-1 + OD-2=V-C): org.nebflow/viewers.json
 * contribution loading and its inline serving face.
 *
 * Covers:
 * - K-1 declaration form: manifest `extensions["org.nebflow/viewers"]` (a
 *   string, same shape as org.nebflow/tools) names the file — ZERO manifest
 *   schema change (KnownManifestKeys untouched).
 * - Default-file fallback: org.nebflow/viewers.json present without any
 *   manifest declaration is picked up.
 * - The §6.4 false-green trap: viewers.json must NOT warn as an unknown
 *   org.nebflow entry (the allowlist extension), while a genuinely unknown
 *   sibling still does.
 * - Component-level degrade: declared-but-missing / unparseable / non-object
 *   / over-cap ⇒ warn + viewerContributions=None, plugin STILL loads (same
 *   §6.2 boundary as an invalid mcp.json component).
 * - V-C inline face: approvalManifest carries viewerContributions for
 *   trusted packages only; a blocked package inlines null (fail-closed).
 */
class PluginViewerContributionSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-viewers"
  private val originalRoot = PathUtil.dataRoot

  // 隔离 dataRoot（PluginRegistrySpec 先例）：plugins/ 与 nebflow.json 都落临时目录
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private def pluginDir(name: String): os.Path =
    val d = tempRoot / "plugins" / name
    os.makeDir.all(d)
    d

  private def writeManifest(dir: os.Path, name: String, extensions: String = ""): Unit =
    os.write.over(
      dir / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$name fixture plugin"$extensions}"""
    )

  private def writeSkill(dir: os.Path, skill: String): Unit =
    os.makeDir.all(dir / "skills" / skill)
    os.write.over(
      dir / "skills" / skill / "SKILL.md",
      s"""---
         |name: $skill
         |description: $skill test skill
         |---
         |# $skill""".stripMargin
    )

  private def writeViewers(dir: os.Path, body: String): Unit =
    os.makeDir.all(dir / "org.nebflow")
    os.write.over(dir / "org.nebflow" / "viewers.json", body)

  private def viewerDecl(name: String, ext: String): Json =
    Json.obj(
      "viewers" -> List(
        Json.obj(
          "name" -> name.asJson,
          "label" -> name.asJson,
          "extensions" -> List(s".$ext".asJson).asJson,
          "binary" -> false.asJson,
          "priority" -> 10.asJson
        )
      ).asJson
    )

  // ── fixtures ─────────────────────────────────────────────
  // declared：K-1 声明形态（manifest extensions 指定文件名）
  // fallback：未声明，org.nebflow/viewers.json 默认名回落 + 一个真未知兄弟条目
  // declared-missing：声明了但文件不存在（组件级降级）
  // bad-json / not-object / oversized：内容面降级三形态
  // blockable：完好的贡献，供封禁测试（内联面 fail-closed）

  private val declared = pluginDir("declared")
  writeManifest(
    declared,
    "declared",
    extensions = ""","extensions":{"org.nebflow/viewers":"viewers.json"}"""
  )
  writeSkill(declared, "howto")
  writeViewers(declared, viewerDecl("demo", "demo").noSpaces)

  private val fallback = pluginDir("fallback")
  writeManifest(fallback, "fallback")
  writeSkill(fallback, "explore")
  writeViewers(fallback, viewerDecl("demo2", "nfo").noSpaces)
  os.write.over(fallback / "org.nebflow" / "extra.json", "{}") // 真未知兄弟条目

  private val declaredMissing = pluginDir("declared-missing")
  writeManifest(
    declaredMissing,
    "declared-missing",
    extensions = ""","extensions":{"org.nebflow/viewers":"viewers.json"}"""
  )
  writeSkill(declaredMissing, "s1")

  private val badJson = pluginDir("bad-json")
  writeManifest(badJson, "bad-json")
  writeSkill(badJson, "s1")
  writeViewers(badJson, "not json at all {")

  private val notObject = pluginDir("not-object")
  writeManifest(notObject, "not-object")
  writeSkill(notObject, "s1")
  writeViewers(notObject, "[1, 2, 3]")

  private val oversized = pluginDir("oversized")
  writeManifest(oversized, "oversized")
  writeSkill(oversized, "s1")
  writeViewers(oversized, "x" * (PluginRegistry.MaxViewerDeclarationBytes + 1024))

  private val blockable = pluginDir("blockable")
  writeManifest(blockable, "blockable")
  writeSkill(blockable, "s1")
  writeViewers(blockable, viewerDecl("demo3", "srt").noSpaces)

  // 隔离 nebflow.json（封禁表读写落临时根）
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def scan: IO[List[PluginRegistry.PluginDef]] = PluginRegistry.scan()

  private def manifestOf(name: String): IO[io.circe.Json] =
    scan.map(_.find(_.name == name)).map {
      case Some(p) => PluginRegistry.approvalManifest(p)
      case None    => fail(s"'$name' must be loaded")
    }

  // ── K-1 装载（声明形态 + 默认回落）────────────────────────

  test("K-1 declared form: extensions[org.nebflow/viewers] names the file, content parsed verbatim") {
    for loaded <- scan
    yield
      val p = loaded.find(_.name == "declared").getOrElse(fail("'declared' must load"))
      assertEquals(p.viewerContributions, Some(viewerDecl("demo", "demo")), "parsed content must ride verbatim")
      assert(
        p.warnings.forall(!_.contains("viewers")),
        s"a healthy contribution must not warn: ${p.warnings}"
      )
  }

  test("default-file fallback: org.nebflow/viewers.json without a manifest declaration is picked up") {
    scan.map(_.find(_.name == "fallback")).map {
      case Some(p) =>
        assertEquals(p.viewerContributions, Some(viewerDecl("demo2", "nfo")))
        // §6.4 假绿硬前置：viewers.json 绝不能当未知条目告警；真未知兄弟（extra.json）必须告警
        assert(
          p.warnings.exists(_.contains("extra.json")),
          s"the genuinely unknown sibling must still warn: ${p.warnings}"
        )
        assert(
          p.warnings.forall(w => !w.contains("ignored unknown org.nebflow entry 'viewers.json'")),
          s"viewers.json must be allowlisted (silent-contribution trap): ${p.warnings}"
        )
      case None => fail("'fallback' must load")
    }
  }

  // ── 组件级降级（§6.2 边界：组件 invalid，插件继续装载）─────

  test("declared-but-missing: plugin still loads, contribution ignored with the declare-miss warning") {
    scan.map(_.find(_.name == "declared-missing")).map {
      case Some(p) =>
        assertEquals(p.viewerContributions, None)
        assert(
          p.warnings.exists(w => w.contains("org.nebflow/viewers") && w.contains("ignored")),
          s"the declare-miss warning must fire: ${p.warnings}"
        )
      case None => fail("a missing contribution file must NOT reject the plugin (component-level degrade)")
    }
  }

  test("unparseable viewers.json: warn + None, plugin still loads") {
    scan.map(_.find(_.name == "bad-json")).map {
      case Some(p) =>
        assertEquals(p.viewerContributions, None)
        assert(p.warnings.exists(_.contains("unparseable")), s"parse warning must fire: ${p.warnings}")
      case None => fail("'bad-json' must load (degrade, not reject)")
    }
  }

  test("non-object viewers.json: warn + None, plugin still loads") {
    scan.map(_.find(_.name == "not-object")).map {
      case Some(p) =>
        assertEquals(p.viewerContributions, None)
        assert(
          p.warnings.exists(_.contains("top-level object")),
          s"shape warning must fire: ${p.warnings}"
        )
      case None => fail("'not-object' must load (degrade, not reject)")
    }
  }

  test("oversized viewers.json: over the inline cap ⇒ warn + None, plugin still loads") {
    scan.map(_.find(_.name == "oversized")).map {
      case Some(p) =>
        assertEquals(p.viewerContributions, None)
        assert(
          p.warnings.exists(w => w.contains("inline declaration cap") && w.contains("plugin-assets")),
          s"cap warning must fire and point at the asset route: ${p.warnings}"
        )
      case None => fail("'oversized' must load (degrade, not reject)")
    }
  }

  // ── V-C 内联面（仅受信包；封禁包 fail-closed）──────────────

  test("V-C inline: a trusted package's approvalManifest carries viewerContributions") {
    manifestOf("declared").map { m =>
      val c = m.hcursor.downField("viewerContributions")
      assertEquals(
        c.downField("viewers").as[List[io.circe.Json]].toOption.map(_.map(_.hcursor.downField("name").as[String].toOption)),
        Some(List(Some("demo"))),
        "the declaration must ride inline with GET /api/plugins"
      )
    }
  }

  test("V-C fail-closed: a blocked package inlines null viewerContributions") {
    for
      _ <- PluginBlockPolicy.block("blockable", "spec denial", "spec")
      blocked <- manifestOf("blockable")
      _ <- PluginBlockPolicy.unblock("blockable", "spec")
      restored <- manifestOf("blockable")
    yield
      assertEquals(
        blocked.hcursor.downField("viewerContributions").focus,
        Some(io.circe.Json.Null),
        "a blocked package must never inline third-party content (fail-closed)"
      )
      assert(
        restored.hcursor.downField("viewerContributions").focus.exists(!_.isNull),
        "unblock must restore the inline declaration"
      )
  }

end PluginViewerContributionSpec
