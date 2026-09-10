package nebflow.core.plugin

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import scala.concurrent.duration.*

/**
 * dispatcher-ctx 批（2026-09-05）——DispatcherContextCatalog spec：
 * 分发器上下文双目录（插件能力目录 + 预设场景目录）渲染语义。
 *
 * 描述单源批（2026-09-10 作者裁定）改口径：manifest `description` 是唯一描述源，
 * `capability` 键 deprecated（装载不报错、渲染层忽略）。
 *
 * 覆盖：
 * - description 单源渲染（capability 键存在时目录只出 description、键不报错）
 * - 无 capability / 空白 capability → description 正常渲染（向后兼容形态）
 * - 过滤链同源：untrusted 不出现（默认拒绝）；plugins.enabled=false 总闸压制插件段
 * - 预设场景目录不受 plugins 总闸影响；"name — description" / 无 description 只出 name
 * - 双段拼装：render() 非空段空行相接
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
    os.write.over(dir / "skills" / skill / "SKILL.md",
      s"""---
         |name: $skill
         |description: $skill test skill
         |---
         |# $skill
         |body""".stripMargin)

  /** 手术式置 flag（不抹 trust 表——approve 会把 trust 写进 nebflow.json，
    * 整体覆写会重演「审批后 setFlag 抹 trust」的踩坑路径）。 */
  private def setFlagMerged(enabled: Boolean): IO[Unit] =
    IO.blocking {
      val p = tempRoot / "nebflow.json"
      val root = if os.exists(p) then io.circe.parser.parse(os.read(p)).toOption.flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty) else io.circe.JsonObject.empty
      val plugins = root("plugins").flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
        .add("enabled", io.circe.Json.fromBoolean(enabled))
      val next = io.circe.Json.fromJsonObject(root.add("plugins", io.circe.Json.fromJsonObject(plugins)))
      os.write.over(p, next.noSpaces)
    }

  // ── fixtures ─────────────────────────────────────────────
  // cap-plugin：capability（deprecated 键）+ description 并存（目录应只出
  // description、capability 文本不出现、装载零告警）
  private val capPlugin = pluginDir("cap-plugin")
  writeManifest(capPlugin,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"cap-plugin","version":"1.0.0",""" +
      """"description":"描述单源探针：本句应出现在目录行",""" +
      """"capability":"旧能力句：已退役，目录不得渲染本句"}""")
  writeSkill(capPlugin, "probe")

  // desc-fallback：无 capability 键（单源形态）→ description 正常渲染
  private val descFallback = pluginDir("desc-fallback")
  writeManifest(descFallback,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"desc-fallback","version":"1.0.0",""" +
      """"description":"回落描述：解析方法论能力包"}""")
  writeSkill(descFallback, "fallback-skill")

  // cap-blank：capability 为空白串（存量形态）→ 宽容容忍 + description 正常渲染
  private val capBlank = pluginDir("cap-blank")
  writeManifest(capBlank,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"cap-blank","version":"1.0.0",""" +
      """"description":"空白回落描述句","capability":"   "}""")
  writeSkill(capBlank, "blank-skill")

  // untrusted-plugin：skill 齐全但从不审批 → 目录不得出现
  private val untrusted = pluginDir("untrusted-plugin")
  writeManifest(untrusted,
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"untrusted-plugin","version":"1.0.0",""" +
      """"description":"未信任插件描述（不应出现）"}""")
  writeSkill(untrusted, "never")

  // model-presets.json：含 description 与不含 description 各一
  private def writePresets(): Unit =
    os.write.over(tempRoot / "model-presets.json",
      """{"defaultPreset":"general","presets":{""" +
        """"general":{"name":"general","description":"","preferred":"mock/m1","fallbacks":[]},""" +
        """"deep-analyze":{"name":"deep-analyze","description":"深度分析场景：调研/审阅/方案设计节点","preferred":"mock/m1","fallbacks":[]}}}""")

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
      assert(
        rendered.contains("- cap-plugin: 描述单源探针：本句应出现在目录行"),
        s"description line must render: $rendered")
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

  test("过滤链同源：untrusted 不出现") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
    yield assert(!rendered.contains("untrusted-plugin"), "untrusted plugin must not appear")
  }

  test("调试渲染收敛：PluginRegistry.renderCatalog 与注入段插件部分同字节") {
    for
      _ <- approveAll
      viaDispatcher <- DispatcherContextCatalog.render()
      viaDebug <- PluginRegistry.renderCatalog()
    yield
      // D10 双渲染器收敛：插件段字节一致（render() = 插件段 + 空行 + preset 段，
      // 故调试输出须为 render() 的前缀且止于插件段末尾）
      assert(viaDispatcher.startsWith(viaDebug) || viaDebug.isEmpty,
        s"debug catalog must equal the injected plugin section prefix\n--debug--\n$viaDebug\n--injected--\n$viaDispatcher")
      assert(viaDebug.contains("# Plugin Catalog"), s"debug header must present: $viaDebug")
      assert(viaDebug.contains("- cap-plugin: 描述单源探针：本句应出现在目录行"), s"debug line single-source: $viaDebug")
      assert(!viaDebug.contains("旧能力句"), "debug renderer must ignore deprecated capability too")
  }

  test("预设场景目录：name — description / 无 description 只出 name") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
    yield
      assert(rendered.contains("# Model Preset Catalog"), s"preset header: $rendered")
      assert(
        rendered.contains("- deep-analyze — 深度分析场景：调研/审阅/方案设计节点"),
        s"preset scene line: $rendered")
      assert(rendered.contains("- general\n") || rendered.endsWith("- general"),
        s"description-less preset must render name only: $rendered")
      assert(!rendered.contains("general —"), "no stray description for name-only preset")
  }

  test("双段拼装：render() 非空段以空行相接") {
    for
      _ <- approveAll
      rendered <- DispatcherContextCatalog.render()
    yield
      val pluginIdx = rendered.indexOf("# Plugin Catalog")
      val presetIdx = rendered.indexOf("# Model Preset Catalog")
      assert(pluginIdx >= 0 && presetIdx > pluginIdx, s"both sections present, plugin first: $rendered")
      assert(rendered.contains("\n\n# Model Preset Catalog"), "sections joined by a blank line")
  }

  test("plugins.enabled=false → 插件段压制；预设段不受总闸影响（refuse 空文件误伤）") {
    for
      _ <- approveAll
      _ <- setFlagMerged(false)
      rendered <- DispatcherContextCatalog.render()
      _ <- setFlagMerged(true)
    yield
      assert(!rendered.contains("# Plugin Catalog"), "flag off must suppress plugin section")
      assert(rendered.contains("# Model Preset Catalog"), "preset section is independent of plugins flag")
      assert(rendered.contains("- deep-analyze — "), "preset lines still render under flag off")
  }

end DispatcherContextCatalogSpec
