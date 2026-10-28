package nebflow.llm.decision

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.jev.CatalogEntry

/**
 * 双模式批（2026-10-28 · 设计 §7 插件三层规范）——**分配问对面 + 校验脚本**层：
 *
 *  - T3 catalogText（JevAllocator/LlmAllocator 共用的分配问对面渲染单点）：
 *    keywords/useWhen 增量行随块；缺席 = 块形逐字节不变。
 *  - T4 validate_plugin.py C17/C18 增量软检查：description>200 → WARN；
 *    Plugin.md 引用 SKILL.md 断链 → WARN（[WARN] 永不阻断；无引用/无 Plugin.md =
 *    PASS）。
 */
class PluginCatalogLayerSpec extends FunSuite:

  // ── T3 分配问对面 ───────────────────────────────────────────────────────────

  test("T3a catalogText 缺席增量键 = 旧块形逐字节") {
    val text = JevAllocator.catalogText(List(CatalogEntry("pkg-a", "cap statement")))
    assertEquals(text, "### pkg-a\ncap statement", "the legacy block shape is untouched")
  }

  test("T3b catalogText 在键 = keywords/use when 增量行随块") {
    val text = JevAllocator.catalogText(
      List(
        CatalogEntry("pkg-a", "cap statement", List("alpha", "beta"), Some("when the task needs it")),
        CatalogEntry("pkg-b", "other", Nil, None)
      )
    )
    assert(text.contains("### pkg-a\ncap statement\nkeywords: alpha, beta\nuse when: when the task needs it"), text)
    assert(text.endsWith("### pkg-b\nother"), s"absent-key blocks are byte-identical: $text")
  }

  // ── T4 validate_plugin.py 增量软检查（子进程真跑脚本）───────────────────────

  private val tmp: os.Path = os.temp.dir(prefix = "nb-desc-layer-py")
  private val script: os.Path =
    os.pwd / "src" / "main" / "resources" / "seed" / "plugins" / "nebflow-plugin-creator" / "skills" / "plugin-packaging" / "scripts" / "validate_plugin.py"

  override def afterAll(): Unit =
    os.remove.all(tmp)

  /** 最小合规包（M1-M9 骨架——本 spec 只读 C17/C18 行）。 */
  private def mkPkg(name: String, description: String, pluginMd: Option[String]): os.Path =
    val d = tmp / name
    os.makeDir.all(d / "skills" / "s")
    os.write.over(
      d / "plugin.json",
      s"""{"$$schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"$name",
         |"version":"1.0.0","description":"$description","author":{"name":"N"}}""".stripMargin
    )
    os.write.over(d / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nbody")
    pluginMd.foreach(md => os.write.over(d / "Plugin.md", md))
    d

  private def runValidator(pkg: os.Path): String =
    os.proc("python3", script.toString, pkg.toString, "--profile", "creator")
      .call(stdout = os.Pipe, stderr = os.Pipe, check = false)
      .out
      .text()

  test("T4a C17: description>200 chars lands a WARN (never a FAIL); ≤200 stays PASS") {
    val longDesc = "定位句——节点获得能力。" + "细节" * 98 + "。适用于测试。" // 200+ chars
    val out = runValidator(mkPkg("c17-long", longDesc, None))
    assert(out.contains("[WARN] C17"), s"the soft WARN must appear: ${out.linesIterator.filter(_.contains("C17")).mkString("\n")}")
    val shortDesc = "短包——节点获得能力。适用于测试。不适用于生产。内含 skills：s。"
    val out2 = runValidator(mkPkg("c17-short", shortDesc, None))
    assert(
      !out2.linesIterator.exists(l => l.startsWith("[WARN] C17") || l.startsWith("[FAIL] C17")),
      "a short description must not trip C17"
    )
  }

  test("T4b C18: a broken SKILL.md reference in Plugin.md lands a WARN; live refs and no-Plugin.md pass") {
    val broken = mkPkg(
      "c18-broken",
      "引用断链包——节点获得能力。适用于测试。不适用于生产。内含 skills：s。",
      Some("See [s](skills/s/SKILL.md) and also skills/ghost/SKILL.md for details.")
    )
    val out = runValidator(broken)
    assert(out.contains("[WARN] C18") && out.contains("skills/ghost/SKILL.md"), out.linesIterator.filter(_.contains("C18")).mkString("\n"))

    val live = mkPkg(
      "c18-live",
      "引用完好包——节点获得能力。适用于测试。不适用于生产。内含 skills：s。",
      Some("See skills/s/SKILL.md for details.")
    )
    val out2 = runValidator(live)
    assert(out2.contains("[PASS] C18"), out2.linesIterator.filter(_.contains("C18")).mkString("\n"))

    val bare = mkPkg("c18-bare", "无 Plugin.md 包——节点获得能力。适用于测试。不适用于生产。内含 skills：s。", None)
    val out3 = runValidator(bare)
    assert(out3.contains("[PASS] C18"), out3.linesIterator.filter(_.contains("C18")).mkString("\n"))
  }
end PluginCatalogLayerSpec
