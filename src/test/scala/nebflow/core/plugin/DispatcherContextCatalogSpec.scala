package nebflow.core.plugin

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * dispatcher-ctx 批（2026-09-05）——DispatcherContextCatalog spec：
 * 分发器上下文目录（插件能力目录）渲染语义。
 *
 * 描述单源批（2026-09-10 作者裁定）改口径：manifest `description` 是唯一描述源，
 * `capability` 键 deprecated（装载不报错、渲染层忽略）。
 *
 * panelscheme 批（2026-09-21）改口径：**Model Preset 场景目录整体退役**——节点
 * 无自有模型方案（节点模型 = 分发器当前方案，派发时 SchemePolicy 解析），NodeEdit
 * `preset` 参数已退役（NODE_PRESET_RETIRED），目录失去唯一消费者。
 *
 * 覆盖：
 * - description 单源渲染（capability 键存在时目录只出 description、键不报错）
 * - 无 capability / 空白 capability → description 正常渲染（向后兼容形态）
 * - 过滤链同源：untrusted 不出现（默认拒绝）；plugins.enabled=false 总闸压制插件段
 * - preset 段退役：render() 不再输出 "# Model Preset Catalog"（= 插件段单段）
 */
class DispatcherContextCatalogSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dispatcher-context-catalog"
  private val originalRoot = PathUtil.dataRoot

  // 隔离 dataRoot（PluginRegistrySpec 先例）：plugins/、nebflow.json、
  // model-presets.json 都落临时目录
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    super.afterAll()

  private def pluginDir(name: String): os.Path =
    val d = tempRoot / "plugins" / name
    os.makeDir.all(d)
    d

  private def writeManifest(dir: os.Path, json: String): Unit =
    os.write.over(dir / "plugin.json", json)

  private def writeSkill(dir: os.Path, skill: String): Unit =
    os.makeDir.all(dir / "skills" / skill)
    os.write.over(
      dir / "skills" / skill / "SKILL.md",
      s"""---
         |name: $skill
         |description: $skill test skill
         |---
         |# $skill
         |body""".stripMargin
    )

  /**
   * 手术式置 flag（不抹 trust 表——approve 会把 trust 写进 nebflow.json，
   * 整体覆写会重演「审批后 setFlag 抹 trust」的踩坑路径）。
   */
  private def setFlagMerged(enabled: Boolean): IO[Unit] =
    IO.blocking {
      val p = tempRoot / "nebflow.json"
      val root =
        if os.exists(p) then
          io.circe.parser.parse(os.read(p)).toOption.flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
        else io.circe.JsonObject.empty
      val plugins = root("plugins")
        .flatMap(_.asObject)
        .getOrElse(io.circe.JsonObject.empty)
        .add("enabled", io.circe.Json.fromBoolean(enabled))
      val next = io.circe.Json.fromJsonObject(root.add("plugins", io.circe.Json.fromJsonObject(plugins)))
      os.write.over(p, next.noSpaces)
    }

  // ── fixtures ─────────────────────────────────────────────
  // cap-plugin：capability（deprecated 键）+ description 并存（目录应只出
  // description、capability 文本不出现、装载零告警）
  private val capPlugin = pluginDir("cap-plugin")

  writeManifest(
    capPlugin,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"cap-plugin","version":"1.0.0",""" +
      """"description":"描述单源探针：本句应出现在目录行",""" +
      """"capability":"旧能力句：已退役，目录不得渲染本句"}"""
  )
  writeSkill(capPlugin, "probe")

  // desc-fallback：无 capability 键（单源形态）→ description 正常渲染
  private val descFallback = pluginDir("desc-fallback")

  writeManifest(
    descFallback,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"desc-fallback","version":"1.0.0",""" +
      """"description":"回落描述：解析方法论能力包"}"""
  )
  writeSkill(descFallback, "fallback-skill")

  // cap-blank：capability 为空白串（存量形态）→ 宽容容忍 + description 正常渲染
  private val capBlank = pluginDir("cap-blank")

  writeManifest(
    capBlank,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"cap-blank","version":"1.0.0",""" +
      """"description":"空白回落描述句","capability":"   "}"""
  )
  writeSkill(capBlank, "blank-skill")

  // blocked-plugin：skill 齐全 + deny-list 封禁 → 目录不得出现（无审批批：受信已不再需要
  // 审批记录，唯一的「点名不可用」手段 = 封禁）
  private val blockedPkg = pluginDir("blocked-plugin")

  writeManifest(
    blockedPkg,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"blocked-plugin","version":"1.0.0",""" +
      """"description":"被封禁插件描述（不应出现）"}"""
  )
  writeSkill(blockedPkg, "never")

  // model-presets.json：含 description 与不含 description 各一
  private def writePresets(): Unit =
    os.write.over(
      tempRoot / "model-presets.json",
      """{"defaultPreset":"general","presets":{""" +
        """"general":{"name":"general","description":"","preferred":"mock/m1","fallbacks":[]},""" +
        """"deep-analyze":{"name":"deep-analyze","description":"深度分析场景：调研/审阅/方案设计节点","preferred":"mock/m1","fallbacks":[]}}}"""
    )

  private def approveAll: IO[Unit] =
    for
      // 先置 flag 再审批——approve 走手术式合并保留 plugins.enabled；反向顺序
      // 且用整体覆写会抹掉 trust 表（本 spec 初版的踩坑路径，留注释防回归）
      _ <- setFlagMerged(true)
      _ <- PluginRegistry.approve("cap-plugin")
      _ <- PluginRegistry.approve("desc-fallback")
      _ <- PluginRegistry.approve("cap-blank")
      _ <- IO(writePresets())
    yield ()

  // ── 渲染规则 ─────────────────────────────────────────────

  test("描述单源：capability 键 deprecated——装载零告警、目录只出 description") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
      warnings <- PluginRegistry.scan().map(_.find(_.name == "cap-plugin").map(_.warnings).getOrElse(Nil))
    yield
      assert(rendered.contains("# Plugin Catalog"), s"header must present: $rendered")
      assert(rendered.contains("- cap-plugin: 描述单源探针：本句应出现在目录行"), s"description line must render: $rendered")
      assert(!rendered.contains("旧能力句"), "deprecated capability text must NOT appear in catalog")
      // capability 键已登记 KnownManifestKeys：存量包带键装载零 unknown-field 告警
      assert(warnings.isEmpty, s"capability key must be tolerated silently, got warnings: $warnings")
  }

  test("无 capability / 空白 capability → description 正常渲染") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
    yield
      assert(rendered.contains("- desc-fallback: 回落描述：解析方法论能力包"), s"single-source line: $rendered")
      assert(rendered.contains("- cap-blank: 空白回落描述句"), s"blank capability tolerated: $rendered")
  }

  test("过滤链同源：封禁包不出现（无审批批：唯一点名阻止手段 = deny-list）") {
    for
      _ <- approveAll
      _ <- PluginBlockPolicy.block("blocked-plugin", "spec", "spec")
      rendered <- DispatcherContextCatalog.render()
      item <- PluginRegistry
        .listWithRejected()
        .map(_._1.find(_.name == "blocked-plugin").map(PluginRegistry.approvalManifest))
      _ <- PluginBlockPolicy.unblock("blocked-plugin", "spec")
      after <- DispatcherContextCatalog.render()
    yield
      assert(!rendered.contains("blocked-plugin"), "a blocked plugin must not appear")
      assert(
        rendered.contains("另有 1 个插件未载入（已封禁 1）"),
        s"the blocked package must be counted in the tail note: $rendered"
      )
      assert(
        item.exists(_.hcursor.downField("blocked").as[Boolean].getOrElse(false)),
        "the blocked flag must be visible in the approval manifest"
      )
      assert(after.contains("blocked-plugin"), s"unblock must restore the catalog line: $after")
  }

  test("在位即信任：无审批记录包出现在目录（default-deny 已取消的正面断言）") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
    yield
      // cap-plugin / desc-fallback / cap-blank 均已 approve，blocked-plugin 未 approve
      // 但**未被封禁** ⇒ 在位即信任 ⇒ 必须出现在目录里
      assert(
        rendered.contains("- blocked-plugin: 被封禁插件描述（不应出现）"),
        s"a record-less (but unblocked) package must render: $rendered"
      )
  }

  test("调试渲染收敛：PluginRegistry.renderCatalog 与注入段插件部分同字节") {
    for
      _ <- approveAll
      viaDispatcher <- DispatcherContextCatalog.render()
      viaDebug <- PluginRegistry.renderCatalog()
    yield
      // D10 双渲染器收敛：插件段字节一致（panelscheme 批后 render() = 插件段的
      // substituteDataRoot 包装，调试输出与其相等——保留前缀断言容真实 data_root 渲染差异）
      assert(
        viaDispatcher.startsWith(viaDebug) || viaDebug.isEmpty,
        s"debug catalog must equal the injected plugin section prefix\n--debug--\n$viaDebug\n--injected--\n$viaDispatcher"
      )
      assert(viaDebug.contains("# Plugin Catalog"), s"debug header must present: $viaDebug")
      assert(viaDebug.contains("- cap-plugin: 描述单源探针：本句应出现在目录行"), s"debug line single-source: $viaDebug")
      assert(!viaDebug.contains("旧能力句"), "debug renderer must ignore deprecated capability too")
  }

  test("panelscheme 退役：preset 场景目录不再渲染（render() = 插件段单段）") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
    yield
      assert(
        !rendered.contains("# Model Preset Catalog"),
        s"preset catalog section is retired (panelscheme 2026-09-21): $rendered"
      )
      assert(!rendered.contains("deep-analyze"), s"preset lines must not leak into the dispatcher prompt: $rendered")
      assert(rendered.contains("# Plugin Catalog"), s"plugin section must still render: $rendered")
  }

  test("plugins.enabled=false → 插件段压制（preset 段已退役，render() 全空）") {
    for
      _ <- approveAll
      _ <- setFlagMerged(false)
      rendered <- DispatcherContextCatalog.render()
      _ <- setFlagMerged(true)
    yield
      assert(!rendered.contains("# Plugin Catalog"), "flag off must suppress plugin section")
      assertEquals(rendered, "", "with the preset section retired, flag-off render must be empty")
  }

end DispatcherContextCatalogSpec
