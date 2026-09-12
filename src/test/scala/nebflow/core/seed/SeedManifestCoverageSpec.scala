package nebflow.core.seed

import munit.FunSuite

import java.net.JarURLConnection
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * K9 防漂移断言（**2026-09-12 重写：播种面与默认启用集解耦**）。
 *
 * ==旧口径（seed7 批 2026-09-11 `60539c6a`，本文件所取代的口径）==
 * 判据 = `seed/plugins/` 资源树 **==** manifest `items` 的 `plugins:*`（双向差集 ∅）。
 * 它把两个语义不同的集合绑成了相等关系：
 *  - `seed/plugins/` 资源树 = **可手动装全集**（作者裁定「其余插件保留可手动装」）；
 *  - `manifest.items` = **默认预装/播种集**（`SeedService.runSeed` 只遍历 items：
 *    fresh home 的预装面、`reconcilePlugins` 的迭代面都是它）。
 * 为让「树 ⊆ manifest」这一方向为绿，必须把 8 个种子目录**全部**登记为默认预装 ⇒ 同一
 * 提交把默认集由 3 悄悄扩为 8，实质推翻 09-08 `d4baab03`「默认启用集收缩」的意图
 * （作者 2026-09-12 报障逐字：「我们的种子插件怎么多了这么多，我只要 slideblocks /
 * visual-report / nebflow-plugin-creator 这三种」）。归因链见取证报告
 * `~/.nebflow/docs/Nebflow/20260912_130117_plugin-face-forensics__chain-n-bb115537.md` §A-3。
 *
 * ==新口径（本文件）==
 * 两个集合**解耦**：资源树允许 ⊃ 默认预装集（不默认预装 ≠ 不可手动装），断言只锚三条
 * 可判定的不变量——
 *  a. **悬空**：manifest 每条 `plugins:*` 在种子树中必须有实件（含 `plugin.json` 的目录）。
 *     悬空条目 ⇒ `SeedService.installPluginFromSeed` 只会 WARN "no seed resources — skipped"，
 *     默认集结构性缺失且无可见信号 = 红；
 *  b. **畸形目录**：种子树中每个目录必须是合法插件目录（含 `plugin.json`）。无 `plugin.json`
 *     的目录既不是可手动装的有效插件，也会让 `SeedService.resourceDirList`（anchor =
 *     `plugin.json`）返回空 ⇒ 静默 skipped = 红；
 *  c. **默认预装集恰为 `{slideblocks, visual-report, nebflow-plugin-creator}`**（写死条目：
 *     增删任何一条即红——这一条是作者「只要这三种」意图的机器判据）。
 * 被去掉的只有旧口径的「树 ⊆ manifest」方向：非默认集的种子树目录**允许存在**（它们属
 * 可手动装全集），故本次回退默认集**零删除插件文件**。
 *
 * 形态：单元测试（`sbt test` 可跑，CI ci.yml 的 `Test` job 直接 `sbt test`）——无网络、
 * 无外部状态、确定性红绿，红 = 非零退出码，天然进 CI 门。零副作用：只读 classpath
 * （`seed/plugins/` 与 `seed/manifest.json`），不写盘、不碰 `PathUtil.dataRoot`、不起 gateway。
 */
class SeedManifestCoverageSpec extends FunSuite:

  private val PluginsPrefix = "plugins:"
  private val SeedPluginsResource = "seed/plugins"

  /** 默认预装集（硬编码锚点，作者 2026-09-12 裁定；与此不符即红）。 */
  private val ExpectedDefaultPlugins: Set[String] =
    Set("slideblocks", "visual-report", "nebflow-plugin-creator")

  /** 种子树「目录名 → 是否含 plugin.json」对照表（file 与 jar 双协议，与
    * `SeedService.resourceDirList` 的协议判定同口径：sbt test = file，assembly = jar）。 */
  private def seedTreeDirs(): Map[String, Boolean] =
    val url = Option(getClass.getClassLoader.getResource(SeedPluginsResource)).getOrElse(
      fail(s"classpath resource '$SeedPluginsResource' not found — seed plugin tree missing from test classpath")
    )
    url.getProtocol match
      case "file" =>
        val dir = os.Path(java.nio.file.Paths.get(url.toURI))
        os.list(dir).filter(os.isDir).map(d => d.last -> os.exists(d / "plugin.json")).toMap
      case "jar" =>
        // jar 未必带目录条目 ⇒ 目录名从条目名首段归并，合法性 = 存在 `<name>/plugin.json` 条目
        val entries = jarSeedEntries(url)
        entries.map(_.split('/').head).distinct
          .map(n => n -> entries.contains(s"$n/plugin.json")).toMap
      case other =>
        fail(s"unsupported classpath protocol '$other' for $SeedPluginsResource — cannot enumerate seed tree")

  /** jar 内 `seed/plugins/` 下**文件**条目（rel 形如 `<name>/…`，过滤掉根级散文件）。 */
  private def jarSeedEntries(url: java.net.URL): List[String] =
    val jar = url.openConnection().asInstanceOf[JarURLConnection].getJarFile
    jar.entries().asScala
      .map(_.getName)
      .filter(n => n.startsWith(s"$SeedPluginsResource/") && !n.endsWith("/"))
      .map(_.stripPrefix(s"$SeedPluginsResource/"))
      .filter(_.contains("/"))
      .toList

  /** 种子树中**合法插件目录**名（含 plugin.json 的一级子目录）。 */
  private def seedPluginNames(): Set[String] =
    seedTreeDirs().collect { case (n, true) => n }.toSet

  /** manifest.items（classpath 上的同一份资源，与 `SeedService.loadManifest` 同源）。 */
  private def manifestItems(): List[String] =
    val in = Option(getClass.getClassLoader.getResourceAsStream("seed/manifest.json")).getOrElse(
      fail("classpath resource 'seed/manifest.json' not found")
    )
    try
      val text = new String(in.readAllBytes(), UTF_8)
      val json = io.circe.parser.parse(text).fold(e => fail(s"seed/manifest.json invalid: ${e.getMessage}"), identity)
      json.hcursor.downField("items").as[List[String]]
        .fold(e => fail(s"seed/manifest.json 'items' unreadable: ${e.getMessage}"), identity)
    finally in.close()

  /** manifest 声明的插件名（`plugins:*` 前缀条目）——即**默认预装/播种集**。 */
  private def declaredPluginNames(): Set[String] =
    manifestItems().collect { case id if id.startsWith(PluginsPrefix) => id.stripPrefix(PluginsPrefix) }.toSet

  // ── a. 悬空：manifest ⊆ 种子树（唯一保留的方向）├──────────────
  test("K9-a: every manifest plugins:* item has a seed tree — dangling ids must be empty"):
    val seeded = seedPluginNames()
    val declared = declaredPluginNames()
    val dangling = (declared -- seeded).toList.sorted
    assert(
      dangling.isEmpty,
      s"manifest declares ${dangling.size} plugin item(s) without seed resources " +
        s"(SeedService would WARN 'no seed resources — skipped' and the default set would be " +
        s"structurally absent in fresh/isolated homes): " +
        dangling.map(n => s"plugins:$n").mkString(", ")
    )

  // ── b. 畸形：种子树每个目录都必须是合法插件目录 ──────────────
  test("K9-b: every seed tree dir is a well-formed plugin dir (contains plugin.json)"):
    val tree = seedTreeDirs()
    assert(
      tree.nonEmpty,
      s"seed plugin tree '$SeedPluginsResource' is empty — seed resources missing from test classpath"
    )
    val malformed = tree.collect { case (n, false) => n }.toList.sorted
    assert(
      malformed.isEmpty,
      s"seed plugin tree malformed — ${malformed.size} dir(s) without plugin.json " +
        s"(not manually installable, and SeedService.resourceDirList returns empty for them ⇒ silent skip): " +
        malformed.mkString(", ")
    )

  // ── c. 默认预装集恰为三条（写死锚点）────────────────────────
  test("K9-c: default preinstall set is exactly {slideblocks, visual-report, nebflow-plugin-creator}"):
    val declared = declaredPluginNames()
    val added = (declared -- ExpectedDefaultPlugins).toList.sorted
    val removed = (ExpectedDefaultPlugins -- declared).toList.sorted
    assert(
      declared == ExpectedDefaultPlugins,
      s"default preinstall set (manifest items plugins:*) drifted — " +
        s"expected {${ExpectedDefaultPlugins.toList.sorted.mkString(", ")}}, " +
        s"got {${declared.toList.sorted.mkString(", ")}} " +
        s"(unexpected: ${if added.isEmpty then "∅" else added.mkString(", ")}; " +
        s"missing: ${if removed.isEmpty then "∅" else removed.mkString(", ")}). " +
        "manifest items = 默认预装/播种集（fresh home 预装面 + reconcile 迭代面）；" +
        "增删条目会直接改变新装 home 与既有 home 的默认插件面积——改动前需作者裁定。"
    )

end SeedManifestCoverageSpec
