package nebflow.core.seed

import munit.FunSuite

import java.net.JarURLConnection
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * K9 防漂移断言（seed7 批，2026-09-11）：资源树 ⊃ 播种面。
 *
 * 判据：`src/main/resources/seed/plugins/` 下的每个插件目录（磁盘上的种子插件树）**必须**全部出现在
 * `seed/manifest.json` 的 `items` 的 `plugins:*` 条目里——否则该插件永不播种
 * （fresh home 结构性缺失）、也永不 reconcile（`SeedService.reconcilePlugins` 只遍历
 * manifest items），且没有任何可见信号。
 *
 * 双向断言（集合相等 = 差集 ∅）：
 *  ① 正向：种子树 ⊆ manifest ⇒ 无「树里有、清单里没有」（永不播种的静默缺口）
 *  ② 反向：manifest ⊆ 种子树 ⇒ 无「清单里有、树里没有」（拼写错/删目录后的悬空条目，
 *     `SeedService.seedPlugin` 对它只会 WARN "has no seed resources — skipped"）
 *
 * 形态选择（判据要求「去掉待补 5 项 ⇒ 必红」）：
 *  - 选 ①单元测试：sbt test 可跑（CI ci.yml 的 `Test` job 直接 `sbt test`），
 *    无网络、无外部状态、确定性红绿；红 = 非零退出码，天然进 CI 门。
 *  - 未选 ②启动期 WARN：只进日志、不构成可判定红（需另配校验脚本才满足判据），
 *    且要改 `SeedService.scala`（本批改动面之外的主源码文件）。
 *
 * 零副作用：只读 classpath（`seed/plugins/` 与 `seed/manifest.json`），不写盘、
 * 不碰 `PathUtil.dataRoot`、不起 gateway。
 */
class SeedManifestCoverageSpec extends FunSuite:

  private val PluginsPrefix = "plugins:"

  /** seed/plugins/ 下的插件名（含 plugin.json 的子目录即插件）。file 协议（sbt
    * target/classes）与 jar 协议（assembly）双支持——同 `SeedService.resourceDirList`
    * 的协议判定口径。树中不合形态的目录（无 plugin.json）直接判失败：那是
    * `seedPlugin` 的静默 WARN 路径（无种子资源 → skipped），不该藏在断言背后。 */
  private def seedPluginNames(): List[String] =
    val loader = getClass.getClassLoader
    val url = Option(loader.getResource("seed/plugins")).getOrElse(
      fail("classpath resource 'seed/plugins' not found — seed plugin tree missing from test classpath")
    )
    url.getProtocol match
      case "file" =>
        val dir = os.Path(java.nio.file.Paths.get(url.toURI))
        val dirs = os.list(dir).filter(os.isDir).toList
        val malformed = dirs.filterNot(d => os.exists(d / "plugin.json")).map(_.last).sorted
        assert(
          malformed.isEmpty,
          s"seed plugin tree malformed — dir(s) without plugin.json: ${malformed.mkString(", ")}"
        )
        dirs.map(_.last).sorted
      case "jar" =>
        val jar = url.openConnection().asInstanceOf[JarURLConnection].getJarFile
        jar.entries().asScala
          .map(_.getName)
          .filter(n => n.startsWith("seed/plugins/") && n.endsWith("/plugin.json"))
          .map(n => n.stripPrefix("seed/plugins/").stripSuffix("/plugin.json"))
          .filterNot(_.contains("/"))
          .toList.distinct.sorted
      case other =>
        fail(s"unsupported classpath protocol '$other' for seed/plugins — cannot enumerate seed tree")

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

  /** manifest 声明的插件名（`plugins:*` 前缀条目）。 */
  private def declaredPluginNames(): List[String] =
    manifestItems().collect { case id if id.startsWith(PluginsPrefix) => id.stripPrefix(PluginsPrefix) }.sorted

  // ── ① 正向：种子树 ⊆ manifest（永不播种的静默缺口）├────────
  test("K9: every seed plugin is declared in manifest.items (plugins:*) — missing set must be empty"):
    val seeded = seedPluginNames()
    val declared = declaredPluginNames()
    val missing = seeded.toSet.diff(declared.toSet).toList.sorted
    assert(
      missing.isEmpty,
      s"seed/plugins has ${seeded.size} plugin(s), manifest declares ${declared.size}: " +
        s"${missing.size} item(s) never seeded and never reconciled (structurally absent in fresh/isolated homes): " +
        missing.map(n => s"plugins:$n").mkString(", ")
    )

  // ── ② 反向：manifest ⊆ 种子树（悬空条目 = 拼写错/目录被删）─
  test("K9 reverse: every manifest plugins:* item has a seed tree (no dangling ids)"):
    val seeded = seedPluginNames()
    val declared = declaredPluginNames()
    val dangling = declared.toSet.diff(seeded.toSet).toList.sorted
    assert(
      dangling.isEmpty,
      s"manifest declares ${dangling.size} plugin item(s) without seed resources " +
        s"(SeedService.seedPlugin would WARN 'no seed resources — skipped'): " +
        dangling.map(n => s"plugins:$n").mkString(", ")
    )

end SeedManifestCoverageSpec
