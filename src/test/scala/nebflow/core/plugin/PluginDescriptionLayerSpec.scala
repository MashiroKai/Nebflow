package nebflow.core.plugin

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 双模式批（2026-10-28 · 设计 §7 插件三层规范）——manifest 增量键 + 目录行增量
 * 渲染 spec：
 *
 *  - T1 useWhen/keywords 容忍读法：在键透传进 PluginDef；缺键 = None/Nil（旧行为
 *    逐字节）；值类型错 = 忽略不拒载（增量键永不变成 fatal 面）。
 *  - T2 catalogLine 增量渲染：两键缺席 = 行形与改前逐字相同；在键 = 增量段随行。
 */
class PluginDescriptionLayerSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-desc-layer"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "plugins")
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private def skillPlugin(dirname: String, manifest: String): os.Path =
    val d = tempRoot / "plugins" / dirname
    os.makeDir.all(d / "skills" / "s")
    os.write.over(d / "plugin.json", manifest)
    os.write.over(
      d / "skills" / "s" / "SKILL.md",
      """---
        |name: s
        |description: fixture skill
        |---
        |body""".stripMargin
    )
    d

  private def loadOne(dirname: String): IO[Option[PluginRegistry.PluginDef]] =
    PluginRegistry.scan().map(_.find(_.name == dirname))

  // ── T1 增量键容忍读法 ───────────────────────────────────────────────────────

  test("T1a useWhen/keywords 在键：透传进 PluginDef") {
    skillPlugin(
      "desc-layer-full",
      """{"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"desc-layer-full",
        |"version":"1.0.0","description":"desc layer full fixture——定位句。",
        |"keywords":["alpha","beta"],"useWhen":"when testing the layer","author":{"name":"N"}}""".stripMargin
    )
    loadOne("desc-layer-full").map { p =>
      val defn = p.getOrElse(fail("plugin must load"))
      assertEquals(defn.keywords, List("alpha", "beta"))
      assertEquals(defn.useWhen, Some("when testing the layer"))
    }
  }

  test("T1b 缺键：useWhen=None keywords=Nil（旧行为逐字节；装载不受扰）") {
    skillPlugin(
      "desc-layer-bare",
      """{"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"desc-layer-bare",
        |"version":"1.0.0","description":"desc layer bare fixture——定位句。","author":{"name":"N"}}""".stripMargin
    )
    loadOne("desc-layer-bare").map { p =>
      val defn = p.getOrElse(fail("plugin must load"))
      assertEquals(defn.keywords, Nil)
      assertEquals(defn.useWhen, None)
    }
  }

  test("T1c useWhen 值类型错：容忍忽略（None），装载继续（增量键永不 fatal）") {
    skillPlugin(
      "desc-layer-badtype",
      """{"$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"desc-layer-badtype",
        |"version":"1.0.0","description":"desc layer badtype fixture——定位句。","useWhen":42,"author":{"name":"N"}}""".stripMargin
    )
    loadOne("desc-layer-badtype").map { p =>
      val defn = p.getOrElse(fail("a wrong-typed incremental key must NOT refuse the load"))
      assertEquals(defn.useWhen, None, "wrong type = tolerated to None")
    }
  }

  // ── T2 catalogLine 增量渲染 ────────────────────────────────────────────────

  private def mkDef(name: String, kw: List[String], uw: Option[String]): PluginRegistry.PluginDef =
    PluginRegistry.PluginDef(
      name = name,
      version = "1.0.0",
      description = "cap statement",
      author = "",
      keywords = kw,
      useWhen = uw,
      skills = Nil,
      mcpServers = Map.empty,
      toolsExtension = Nil,
      digest = "d",
      fileCount = 1,
      warnings = Nil,
      trust = PluginRegistry.TrustStatus.Trusted(0L, "d"),
      dir = "/tmp/x"
    )

  test("T2a catalogLine 缺席增量键 = 旧行形逐字节（容忍读法的渲染侧保证）") {
    val line = PluginRegistry.catalogLine(mkDef("pkg-a", Nil, None))
    assertEquals(line, "- pkg-a: cap statement [skills: - | mcp: -]", "the legacy line shape is untouched")
  }

  test("T2b catalogLine 在键 = 增量段随行（keywords 与 use when），[skills] 尾缀保持末位") {
    val line = PluginRegistry.catalogLine(mkDef("pkg-b", List("alpha", "beta"), Some("when the task needs it")))
    assert(line.contains(" — keywords: alpha, beta"), line)
    assert(line.contains(" — use when: when the task needs it"), line)
    assert(line.endsWith("[skills: - | mcp: -]"), s"the structural tail stays last: $line")
    val kwOnly = PluginRegistry.catalogLine(mkDef("pkg-c", List("alpha"), None))
    assert(kwOnly.contains(" — keywords: alpha") && !kwOnly.contains("use when"), kwOnly)
    val uwOnly = PluginRegistry.catalogLine(mkDef("pkg-d", Nil, Some("when alone")))
    assert(!uwOnly.contains("keywords") && uwOnly.contains(" — use when: when alone"), uwOnly)
  }
end PluginDescriptionLayerSpec
