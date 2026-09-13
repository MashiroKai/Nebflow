package nebflow.core.plugin

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * 插件装载健康可见性 spec（P1 静默缩容可见性批 2026-09-10；**无审批批 2026-09-13
 * 口径更新**）。
 *
 * 缺陷背景：整包拒载 / 封禁 / 内容变更都会让包从 Plugin Catalog 静默消失或悄悄换内容，
 * 此前只有逐包 WARN、目录静默缩容（2026-09-06 14/16 整包拒载 → 冷启动分发器目录只剩
 * 2 包），作者侧无任何聚合可见信号。
 *
 * 无审批批后的分类（本 spec 的断言面）：
 * - **缺席**（不进目录）只剩两类：装载失败 + **封禁**（deny-list）；
 * - **内容已变更**（digest 与审计记录不符）**不是缺席**——包照常装载、照常进目录，
 *   只多一行非拦截提示（目录段尾注记 + 健康摘要 `[content-changed]` 明细 + API 字段）。
 *
 * 覆盖：
 * - 目录缺席注记：分类计数正确（只列非零）、落在段尾、缺席包本身不出行
 * - 内容已变更注记：包仍在目录里（行不去）+ 段尾点名提示
 * - 双渲染器同字节：DispatcherContextCatalog.pluginSection == PluginRegistry.renderCatalog()
 *   （REST GET /plugins/catalog 亦为 renderCatalog 直出，同字节随此断言）
 * - 健康摘要：总包数 / 载入数 / 目录可见数 / 分类计数 + 一行一条明细（包名 + 原因）
 * - 去重与零噪音：同状态只输出一次；全部装载且无变更 → 无注记、摘要 None、零输出
 * - 复位后可再出：干净 → 异常复现 → 重新输出
 *
 * fixtures：ok-a / ok-b（装载 + 有审计记录 → 目录可见）、bad-manifest（$schema 缺失 →
 * 拒载）、blocked（封禁）、drifted（记录后内容改动 → 非拦截提示）、never-recorded
 * （无任何审批记录 ⇒ **在位即信任**，照常可用）。
 */
class PluginLoadVisibilitySpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-plugin-load-visibility"
  private val originalRoot = PathUtil.dataRoot

  // 隔离 dataRoot（PluginRegistrySpec 先例）：plugins/ 与 nebflow.json 都落临时目录
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)
  os.write.over(tempRoot / "nebflow.json", "{}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    super.afterAll()

  private def pluginDir(name: String): os.Path =
    val d = tempRoot / "plugins" / name
    os.makeDir.all(d)
    d

  /** 合法 manifest + 一个 conformant skill → 可装载插件。 */
  private def writePlugin(name: String, description: String = ""): os.Path =
    val d = pluginDir(name)
    val desc = if description.nonEmpty then description else s"$name fixture plugin"
    os.write.over(d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$desc"}""")
    os.makeDir.all(d / "skills" / "howto")
    os.write.over(d / "skills" / "howto" / "SKILL.md",
      s"""---
         |name: howto
         |description: $name test skill
         |---
         |# howto
         |body""".stripMargin)
    d

  /** 缺 $schema → 装载失败（§5.3 required）。 */
  private def writeRejectedPlugin(name: String): os.Path =
    val d = pluginDir(name)
    os.write.over(d / "plugin.json", s"""{"name":"$name","version":"1.0.0"}""")
    os.makeDir.all(d / "skills" / "howto")
    os.write.over(d / "skills" / "howto" / "SKILL.md", "---\nname: howto\ndescription: d\n---\nbody")
    d

  // ── fixtures（类别固定：1 拒载 / 1 封禁 / 1 内容变更 / 3 可见）────────
  private val okA = writePlugin("ok-a", "可见插件 A")
  private val okB = writePlugin("ok-b", "可见插件 B")
  private val badManifest = writeRejectedPlugin("bad-manifest")
  private val neverRecorded = writePlugin("never-recorded", "无记录但在位即受信")
  private val blocked = writePlugin("blocked-pkg", "封禁探针")
  private val drifted = writePlugin("drifted", "内容变更探针")

  /** 固定状态：ok-a / ok-b 有审计记录；drifted 记录后改动（内容变更）；blocked-pkg 封禁。 */
  private def establishState(): IO[Unit] =
    for
      _ <- IO.blocking(os.write.over(tempRoot / "nebflow.json", "{}"))
      _ <- IO(PluginRegistry.invalidateCache())
      _ <- PluginRegistry.approve("ok-a")
      _ <- PluginRegistry.approve("ok-b")
      _ <- PluginRegistry.approve("drifted")
      _ <- IO.sleep(20.millis) // mtime 粒度保险（PluginRegistrySpec 先例）
      _ <- IO.blocking(os.write.append(drifted / "skills" / "howto" / "SKILL.md", "\nmodified after the record\n"))
      _ <- PluginBlockPolicy.block("blocked-pkg", "spec: deny-list probe", "spec")
    yield ()

  private val expectedNote = "另有 2 个插件未载入（装载失败 1 / 已封禁 1）"
  private val expectedChangedNote =
    "另有 1 个插件内容自审批记录后已变更（**不拦截装载**，仅提示核对）：drifted"

  // ── 目录缺席 / 内容变更注记 ─────────────────────────────────

  test("缺席注记：分类计数正确、落在段尾、缺席包本身不出行；内容变更**不缺席**") {
    for
      _ <- establishState()
      catalog <- PluginRegistry.renderCatalog()
    yield
      assert(catalog.startsWith(PluginRegistry.CatalogHeader), s"header must stay first: $catalog")
      assert(catalog.contains("- ok-a: 可见插件 A"), s"visible line must render: $catalog")
      assert(catalog.contains("- ok-b: 可见插件 B"), s"visible line must render: $catalog")
      assert(catalog.contains("- never-recorded: 无记录但在位即受信"),
        s"a record-less package must be in the catalog (presence = trust): $catalog")
      assert(catalog.contains("- drifted: 内容变更探针"),
        s"a content-changed package MUST stay in the catalog (not intercepted): $catalog")
      val lines = catalog.linesIterator.toList
      assertEquals(lines.takeRight(2), List(expectedNote, expectedChangedNote),
        s"aggregated notes must sit at the section tail: $catalog")
      assert(!catalog.contains("- blocked-pkg:"), s"blocked package must not render a line: $catalog")
      assert(!catalog.contains("- bad-manifest:"), s"rejected package must not render a line: $catalog")
  }

  test("双渲染器同字节：DispatcherContextCatalog.pluginSection == renderCatalog()（含注记）") {
    for
      _ <- establishState()
      viaDispatcher <- DispatcherContextCatalog.pluginSection()
      viaDebug <- PluginRegistry.renderCatalog()
    yield
      assertEquals(viaDispatcher, viaDebug,
        "dispatcher injection section and debug renderer must stay byte-identical (notes included)")
      assert(viaDebug.contains(expectedNote), s"absence note must survive both renderers: $viaDebug")
      assert(viaDebug.contains(expectedChangedNote), s"content-changed note must survive both renderers: $viaDebug")
  }

  test("健康摘要：总包数/载入数/目录可见数/分类计数 + 一行一条明细（缺席 + 内容变更）") {
    for
      _ <- establishState()
      health <- PluginRegistry.healthSummary()
    yield
      val text = health.getOrElse(fail("anomalous registry must produce a health summary"))
      val lines = text.linesIterator.toList
      assertEquals(lines.size, 4, s"head + 2 absent + 1 content-changed: $text")
      assert(
        lines.head.startsWith("6 package(s) on disk, 5 loaded, 4 catalog-visible, 2 absent (load-failed 1 / blocked 1), "),
        s"head counts must be complete: ${lines.head}")
      assert(lines.head.endsWith("1 content-changed"), s"head must count content changes: ${lines.head}")
      assert(lines.exists(l => l.contains("[load-failed] bad-manifest:") && l.contains("schema")),
        s"rejected package must be listed with its reason: $text")
      assert(lines.exists(l => l.contains("[blocked] blocked-pkg:") && l.contains("spec: deny-list probe")),
        s"blocked package must be listed with its block reason: $text")
      assert(lines.exists(l => l.contains("[content-changed] drifted:") && l.contains("load-failed") == false
        && l.contains("not intercepted")),
        s"content-changed package must be listed as NON-blocking: $text")
  }

  test("健康摘要去重：同状态重复调用只输出一次（重扫 tick 不刷屏）") {
    for
      _ <- establishState()
      _ <- PluginRegistry.logHealthSummary("startup")
      first = PluginRegistry.healthSummaryLogStateForTest
      _ = assert(first._1 >= 1 && first._2.isDefined, s"anomalous state must emit once: $first")
      _ <- PluginRegistry.logHealthSummary("rescan")
      second = PluginRegistry.healthSummaryLogStateForTest
    yield assertEquals(second, first, "same health state must not be emitted twice")
  }

  // ── 零噪音 / 复位 ─────────────────────────────────────────

  test("干净场景零噪音：全部装载且无变更 → 无注记、摘要 None、零输出") {
    for
      _ <- establishState() // 保证去重记账处于「有异常」态，随后验证复位
      _ <- IO.blocking {
        os.remove.all(tempRoot / "plugins")
        os.write.over(tempRoot / "nebflow.json", "{}")
        writePlugin("ok-a", "可见插件 A")
        writePlugin("ok-b", "可见插件 B")
      }
      _ <- PluginRegistry.approve("ok-a")
      _ <- PluginRegistry.approve("ok-b")
      _ <- IO(PluginRegistry.invalidateCache())
      catalog <- PluginRegistry.renderCatalog()
      health <- PluginRegistry.healthSummary()
      _ <- PluginRegistry.logHealthSummary("startup")
      state = PluginRegistry.healthSummaryLogStateForTest
    yield
      assertEquals(catalog.linesIterator.size, 3, s"header + 2 visible lines, no note: $catalog")
      assert(!catalog.contains("另有"), s"clean catalog must carry no note: $catalog")
      assertEquals(health, None, "clean scenario must yield no health summary (zero noise)")
      assertEquals(state._2, None, "clean scenario must log nothing (zero noise, state reset)")
  }

  test("异常复现：干净复位后内容变更 → 重新输出（非拦截提示同样是健康信号）") {
    // 承接上一测试的干净态：改动已记录的 ok-a ⇒ contentChanged ⇒ 注记与摘要复现
    for
      _ <- IO.sleep(20.millis)
      _ <- IO.blocking(os.write.append(okA / "skills" / "howto" / "SKILL.md", "\nlate change\n"))
      _ <- IO(PluginRegistry.invalidateCache())
      catalog <- PluginRegistry.renderCatalog()
      health <- PluginRegistry.healthSummary()
      before = PluginRegistry.healthSummaryLogStateForTest
      _ <- PluginRegistry.logHealthSummary("rescan")
      after = PluginRegistry.healthSummaryLogStateForTest
    yield
      assert(catalog.contains("- ok-a: 可见插件 A"),
        s"content-changed package must stay visible: $catalog")
      assert(catalog.contains("另有 1 个插件内容自审批记录后已变更（**不拦截装载**，仅提示核对）：ok-a"),
        s"fresh content change must be annotated again: $catalog")
      assert(health.exists(_.contains("[content-changed] ok-a:")),
        s"fresh content change must appear in the health summary: $health")
      assertEquals(after._1, before._1 + 1, "state change after clean reset must emit again")
      assert(after._2.isDefined, "emitted body must be retained for the next dedupe comparison")
  }

end PluginLoadVisibilitySpec
