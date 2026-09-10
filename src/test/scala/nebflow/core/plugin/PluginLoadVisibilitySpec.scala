package nebflow.core.plugin

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * P1 静默缩容可见性批（2026-09-10）——插件装载健康可见性 spec。
 *
 * 缺陷背景（引擎-描述单源统一报告 §⑥ 单列项）：整包拒载 / 信任未批准 / digest
 * 漂移都会让包从 Plugin Catalog 消失，此前只有逐包 WARN、目录静默缩容（2026-09-06
 * 14/16 整包拒载 → 冷启动分发器目录只剩 2 包；2026-09-08 digest 漂移窗口目录再次
 * 无声缺席）——作者侧无任何聚合可见信号。
 *
 * 覆盖（本批只加可见性，不改装载校验与信任门判定）：
 * - 目录缺席注记：三类计数正确、落在段尾、缺席包本身仍不出行（过滤链未动）
 * - 双渲染器同字节：DispatcherContextCatalog.pluginSection == PluginRegistry.renderCatalog()
 *   （REST GET /plugins/catalog 亦为 renderCatalog 直出，同字节随此断言）
 * - 健康摘要：总包数 / 载入数 / 目录可见数 / 分类计数 + 一行一条明细（包名 + 原因）
 * - 去重与零噪音：同状态只输出一次；全部装载且受信 → 无注记、摘要 None、零输出
 * - 复位后可再出：干净 → 异常复现 → 重新输出
 *
 * fixtures：ok-a / ok-b（装载 + 已审批 → 目录可见）、bad-manifest（$schema 缺失 →
 * 拒载）、never-approved（装载但从未审批）、drifted（审批后内容改动 → digest 漂移）。
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

  // ── fixtures（类别固定：1 拒载 / 1 未批准 / 1 漂移 / 2 受信）────────
  private val okA = writePlugin("ok-a", "可见插件 A")
  private val okB = writePlugin("ok-b", "可见插件 B")
  private val badManifest = writeRejectedPlugin("bad-manifest")
  private val neverApproved = writePlugin("never-approved")
  private val drifted = writePlugin("drifted")

  /** 受信基线：ok-a / ok-b 审批；drifted 审批后改动内容 → digest 漂移（§B.8-3）。 */
  private def establishState(): IO[Unit] =
    for
      _ <- PluginRegistry.approve("ok-a")
      _ <- PluginRegistry.approve("ok-b")
      _ <- PluginRegistry.approve("drifted")
      _ <- IO.sleep(20.millis) // mtime 粒度保险（PluginRegistrySpec 先例）
      _ <- IO.blocking(os.write.append(drifted / "skills" / "howto" / "SKILL.md", "\nmodified after approval\n"))
      _ <- IO(PluginRegistry.invalidateCache())
    yield ()

  private val expectedNote = "另有 3 个插件未载入（装载失败 1 / 信任未批准 1 / digest 漂移 1）"

  // ── 目录缺席注记 ─────────────────────────────────────────

  test("缺席注记：三类计数正确、落在目录段尾、缺席包本身不出行（过滤链未动）") {
    for
      _ <- establishState()
      catalog <- PluginRegistry.renderCatalog()
    yield
      assert(catalog.startsWith(PluginRegistry.CatalogHeader), s"header must stay first: $catalog")
      assert(catalog.contains("- ok-a: 可见插件 A"), s"trusted line must render: $catalog")
      assert(catalog.contains("- ok-b: 可见插件 B"), s"trusted line must render: $catalog")
      assertEquals(catalog.linesIterator.toList.last, expectedNote,
        s"absence note must be aggregated at the section tail: $catalog")
      assert(!catalog.contains("- never-approved:"), s"unapproved plugin must not render a line: $catalog")
      assert(!catalog.contains("- drifted:"), s"drifted plugin must not render a line: $catalog")
      assert(!catalog.contains("- bad-manifest:"), s"rejected plugin must not render a line: $catalog")
  }

  test("双渲染器同字节：DispatcherContextCatalog.pluginSection == renderCatalog()（含注记）") {
    for
      _ <- establishState()
      viaDispatcher <- DispatcherContextCatalog.pluginSection()
      viaDebug <- PluginRegistry.renderCatalog()
    yield
      assertEquals(viaDispatcher, viaDebug,
        "dispatcher injection section and debug renderer must stay byte-identical (note included)")
      assert(viaDebug.contains(expectedNote), s"note must survive both renderers: $viaDebug")
  }

  test("健康摘要：总包数/载入数/目录可见数/分类计数 + 一行一条明细（包名 + 原因）") {
    for
      _ <- establishState()
      health <- PluginRegistry.healthSummary()
    yield
      val text = health.getOrElse(fail("anomalous registry must produce a health summary"))
      val lines = text.linesIterator.toList
      assertEquals(lines.size, 4, s"head + one line per absent package: $text")
      assert(
        lines.head.contains("5 package(s) on disk, 4 loaded, 2 catalog-visible, 3 absent " +
          "(load-failed 1 / unapproved 1 / digest-drift 1)"),
        s"head counts must be complete: ${lines.head}")
      assert(lines.exists(l => l.contains("[load-failed] bad-manifest:") && l.contains("schema")),
        s"rejected package must be listed with its reason: $text")
      assert(lines.exists(l => l.contains("[unapproved] never-approved:") && l.contains("never approved")),
        s"unapproved package must be listed with its reason: $text")
      assert(lines.exists(l => l.contains("[digest-drift] drifted:") && l.contains("digest changed")),
        s"drifted package must be listed with its reason: $text")
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

  test("干净场景零噪音：全部装载且受信 → 无缺席注记、摘要 None、零输出") {
    for
      _ <- establishState() // 保证去重记账处于「有异常」态，随后验证复位
      _ <- IO.blocking {
        os.remove.all(tempRoot / "plugins")
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
      assertEquals(catalog.linesIterator.size, 3, s"header + 2 trusted lines, no note: $catalog")
      assert(!catalog.contains("另有"), s"clean catalog must carry no absence note: $catalog")
      assertEquals(health, None, "clean scenario must yield no health summary (zero noise)")
      assertEquals(state._2, None, "clean scenario must log nothing (zero noise, state reset)")
  }

  test("异常复现：干净复位后再现缺席 → 重新输出（计数不粘滞）") {
    // 承接上一测试的干净态：新造一个缺席包（从未审批）→ 注记与摘要复现
    for
      _ <- IO.blocking(os.makeDir.all(tempRoot / "plugins"))
      _ <- IO.blocking(writePlugin("late-arrival", "迟到未审批插件"))
      _ <- IO(PluginRegistry.invalidateCache())
      catalog <- PluginRegistry.renderCatalog()
      health <- PluginRegistry.healthSummary()
      before = PluginRegistry.healthSummaryLogStateForTest
      _ <- PluginRegistry.logHealthSummary("rescan")
      after = PluginRegistry.healthSummaryLogStateForTest
    yield
      assert(catalog.contains("另有 1 个插件未载入（装载失败 0 / 信任未批准 1 / digest 漂移 0）"),
        s"fresh absence must be annotated again: $catalog")
      assert(health.exists(_.contains("[unapproved] late-arrival:")),
        s"fresh absence must appear in the health summary: $health")
      assertEquals(after._1, before._1 + 1, "state change after clean reset must emit again")
      assert(after._2.isDefined, "emitted body must be retained for the next dedupe comparison")
  }

end PluginLoadVisibilitySpec
