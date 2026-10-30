package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * 官方源读取面（远程发布源设计稿 §3.2，P2 验收「官方索引读取」）：
 *  - **清单 schema 校验**：schema=1 门；未知字段宽容忽略（ReleaseManifest 先例）；
 *    缺字段降级不失败（条目缺 id / 版本条目缺 version ⇒ 跳过该条目，不炸整份清单）；
 *  - **端点派生**：brand.conf 单源（domain / cosBucket）+ beta 同构命名（index-beta.json，
 *    A7① 一机一通道）；fixture 注入走系统属性（nebflow.plugins.indexUrl / indexFallbackUrl，
 *    与 nebflow.updates.manifestUrl 同款口径）；
 *  - **OSS 字节回落双跳**（VersionCheck 读序先例）：主端点不可达/解析失败/schema≠1 ⇒ 回落；
 *    都失败 ⇒ Unreachable 且**逐端点列试过顺序与原因**；主端点成功 ⇒ 回落端点零请求。
 */
class PluginSourceCheckSpec extends FunSuite:

  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.Duration(120, "s")

  // ── 系统属性注入面（逐测清理）────────────────────────────────
  private val propKeys = List("nebflow.plugins.indexUrl", "nebflow.plugins.indexFallbackUrl", "nebflow.plugins.channel")
  private var savedProps: Map[String, Option[String]] = Map.empty

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedProps = propKeys.map(k => k -> Option(System.getProperty(k))).toMap
    propKeys.foreach(System.clearProperty)

  override def afterEach(context: munit.AfterEach): Unit =
    savedProps.foreach {
      case (k, Some(v)) => System.setProperty(k, v)
      case (k, None)    => System.clearProperty(k)
    }

  // ── 解析容忍度 ────────────────────────────────────────────

  test("index 解析正例：全字段 + 未知字段宽容 + 顺序保留"):
    val raw =
      """{"schema":1,"sequence":47,"updatedAt":"2026-10-29T17:00:00Z","futureField":{"x":1},
        |"minCompatibleEngine":"2026.10.1",
        |"plugins":[
        |  {"id":"visual-report","latest":"1.6.0","sequence":45,"manifest":"https://m.example/m.json","extra":true},
        |  {"id":"nebflow-qa","latest":"1.0.1"}
        |]}""".stripMargin
    val idx = PluginSourceCheck.parseIndex(raw).getOrElse(fail("full index must parse"))
    assertEquals(idx.schema, 1)
    assertEquals(idx.sequence, Some(47L))
    assertEquals(idx.minCompatibleEngine, Some("2026.10.1"))
    assertEquals(idx.plugins.map(_.id), List("visual-report", "nebflow-qa"), "条目顺序必须保留（读序语义）")
    assertEquals(idx.plugins(0).manifest, Some("https://m.example/m.json"))
    assertEquals(idx.plugins(0).latest, Some("1.6.0"))
    assertEquals(idx.plugins(1).manifest, None, "缺 manifest 字段 = 合法（派生形态）")

  test("index 解析降级：缺 id 条目跳过；缺 plugins ⇒ 空清单（不失败）；缺 sequence 等全容忍"):
    val idx = PluginSourceCheck.parseIndex("""{"schema":1,"plugins":[{"latest":"1.0"},{"id":"a"},{"nope":1}]}""")
      .getOrElse(fail("index with degraded entries must still parse"))
    assertEquals(idx.plugins.map(_.id), List("a"), "缺 id 条目跳过，带 id 的保留")
    assertEquals(idx.sequence, None)

    val empty = PluginSourceCheck.parseIndex("""{"schema":1}""")
    assert(empty.isDefined, "缺 plugins 数组 = 合法空清单（降级不失败）")
    assertEquals(empty.get.plugins, Nil)

  test("index schema 门：schema 缺失 / ≠1 / 非 JSON / 非 object ⇒ None（该端点按不可用）"):
    assertEquals(PluginSourceCheck.parseIndex("""{"plugins":[]}"""), None, "schema 缺失")
    assertEquals(PluginSourceCheck.parseIndex("""{"schema":2,"plugins":[]}"""), None, "schema=2")
    assertEquals(PluginSourceCheck.parseIndex("not json{"), None)
    assertEquals(PluginSourceCheck.parseIndex("""[1,2]"""), None)

  test("版本清单解析：versions 条目缺 version 跳过；requires 嵌套读入；artifact 字段不齐照常读入"):
    val raw =
      """{"schema":1,"id":"visual-report","future":1,"versions":[
        |  {"version":"1.6.0","publishedAt":"2026-10-29T16:40:00Z",
        |   "requires":{"manifestSchema":"1.0.0","minEngine":"2026.10.1"},
        |   "artifacts":[{"format":"zip","sizeBytes":81234,"sha256":"AA","digest":"bb","url":"https://o/x.zip","mirror":"https://m/x.zip"}]},
        |  {"publishedAt":"no-version-field"},
        |  {"version":"1.5.0"}
        |]}""".stripMargin
    val m = PluginSourceCheck.parseVersionManifest(raw).getOrElse(fail("manifest must parse"))
    assertEquals(m.versions.map(_.version), List("1.6.0", "1.5.0"), "缺 version 条目跳过")
    assertEquals(m.versions(0).minEngine, Some("2026.10.1"))
    assertEquals(m.versions(0).manifestSchema, Some("1.0.0"))
    val a = m.versions(0).artifacts.head
    assertEquals(a.sha256, Some("aa"), "hex 小写归一")
    assertEquals(a.digest, Some("bb"))
    assertEquals(m.versions(1).artifacts, Nil, "缺 artifacts = 空列表（降级不失败）")

  test("artifact 可用性判定：zip（或缺省格式）+ url/sha256/digest 三字段齐备"):
    def a(format: Option[String], url: Option[String], sha: Option[String], digest: Option[String]) =
      PluginSourceCheck.Artifact(format, Some(10L), sha, digest, url, None)
    assert(a(Some("zip"), Some("u"), Some("s"), Some("d")).usableForZipInstall)
    assert(a(None, Some("u"), Some("s"), Some("d")).usableForZipInstall, "格式缺省 = 视为 zip（唯一已知格式）")
    assert(!a(Some("tar.zst"), Some("u"), Some("s"), Some("d")).usableForZipInstall, "未知格式不装（P2 只认 zip）")
    assert(!a(Some("zip"), None, Some("s"), Some("d")).usableForZipInstall)
    assert(!a(Some("zip"), Some("u"), None, Some("d")).usableForZipInstall)
    assert(!a(Some("zip"), Some("u"), Some("s"), None).usableForZipInstall)

  // ── 端点派生（brand.conf 单源 + beta 同构 + 注入面）──────────────

  test("indexUrls：stable/beta 同构命名 + OSS 回落次序 + 属性注入整串替换"):
    val urls = PluginSourceCheck.indexUrls(beta = false)
    assertEquals(urls.size, 2, "官网主端点 + OSS 回落端点")
    assert(urls(0).endsWith("/plugins/index.json"), s"stable 主端点，got: ${urls(0)}")
    assert(urls(0).startsWith("https://"), "主端点走 brand.conf domain（https）")
    assert(urls(1).contains(".oss-cn-hangzhou.aliyuncs.com/plugins/index.json"), s"OSS 回落端点，got: ${urls(1)}")

    val beta = PluginSourceCheck.indexUrls(beta = true)
    assert(beta(0).endsWith("/plugins/index-beta.json"), s"beta 同构命名（A7① 一机一通道），got: ${beta(0)}")
    assert(beta(1).endsWith("/plugins/index-beta.json"))

    System.setProperty("nebflow.plugins.indexUrl", "http://127.0.0.1:1/idx.json")
    System.setProperty("nebflow.plugins.indexFallbackUrl", "http://127.0.0.1:2/idx.json")
    val injected = PluginSourceCheck.indexUrls(beta = true)
    assertEquals(injected, List("http://127.0.0.1:1/idx.json", "http://127.0.0.1:2/idx.json"), "注入面整串替换")

  test("manifestUrls：显式 manifest 独占；派生形态跟随伺服索引目录；官方对补 OSS 镜像"):
    val entry = PluginSourceCheck.IndexEntry("visual-report", None, None, None)
    val explicit = entry.copy(manifest = Some("https://cdn.example/m.json"))
    assertEquals(
      PluginSourceCheck.manifestUrls("https://x.example/plugins/index.json", explicit, beta = false, officialPair = true),
      List("https://cdn.example/m.json"),
      "显式 manifest URL = 唯一端点（不臆造镜像）"
    )
    val derived = PluginSourceCheck.manifestUrls("https://x.example/plugins/index.json", entry, beta = false, officialPair = true)
    assertEquals(
      derived,
      List(
        "https://x.example/plugins/visual-report/manifest.json",
        s"${PluginSourceCheck.ossBase}/plugins/visual-report/manifest.json"
      ),
      "官方对：派生官网形态 + 同路径 OSS 镜像"
    )
    val derivedBeta = PluginSourceCheck.manifestUrls("https://x.example/plugins/index-beta.json", entry, beta = true, officialPair = true)
    assert(derivedBeta(0).endsWith("/visual-report/manifest-beta.json"), s"beta 同构，got: ${derivedBeta(0)}")
    val custom = PluginSourceCheck.manifestUrls("https://internal.example/mirror/index.json", entry, beta = false, officialPair = false)
    assertEquals(
      custom,
      List("https://internal.example/mirror/visual-report/manifest.json"),
      "自定义索引（spec.index 显式）不补 OSS 镜像跳"
    )

  // ── 读序（fixture 伺服：官网→OSS 双跳正反例）─────────────────────

  test("读序正例：主端点成功 ⇒ 回落端点零请求；失败端点记录原因后回落成功"):
    val fixture = FixtureHttp().start()
    try
      fixture.serve("/plugins/index.json", 200, """{"schema":1,"plugins":[{"id":"a"}]}""")
      val res = PluginSourceCheck.fetchIndexAt(List(fixture.base + "/plugins/index.json", fixture.base + "/plugins/index-fallback.json")).unsafeRunSync()
      res match
        case Right((idx, served)) =>
          assertEquals(idx.plugins.map(_.id), List("a"))
          assertEquals(served, fixture.base + "/plugins/index.json")
        case Left(un) => fail(s"expected Right, got: ${un.render}")
      assert(!fixture.requested.contains("/plugins/index-fallback.json"), "主端点成功 ⇒ 回落端点零请求")

      // 主端点 404 ⇒ 回落命中
      val res2 = PluginSourceCheck.fetchIndexAt(List(fixture.base + "/plugins/missing.json", fixture.base + "/plugins/index.json")).unsafeRunSync()
      assert(res2.isRight, s"回落命中：${res2.swap.toOption.map(_.render)}")
      assert(fixture.requested.contains("/plugins/index.json"))
    finally fixture.stop()

  test("读序反例：解析失败/schema≠1 也触发回落；全失败 ⇒ Unreachable 逐端点列原因（有序）"):
    val fixture = FixtureHttp().start()
    try
      fixture.serve("/plugins/index.json", 200, "not json{")
      fixture.serve("/plugins/index-fallback.json", 200, """{"schema":2,"plugins":[]}""")
      val res = PluginSourceCheck.fetchIndexAt(List(
        fixture.base + "/plugins/index.json",
        fixture.base + "/plugins/index-fallback.json"
      )).unsafeRunSync()
      res match
        case Left(un) =>
          assertEquals(un.tried.map(_._1), List(
            fixture.base + "/plugins/index.json",
            fixture.base + "/plugins/index-fallback.json"
          ), "全失败 ⇒ 试过端点有序列出")
          assert(un.tried(0)._2.contains("unparseable"), s"解析失败原因留痕，got: ${un.tried(0)._2}")
          assert(un.tried(1)._2.contains("schema"), s"schema≠1 原因留痕，got: ${un.tried(1)._2}")
          assert(un.render.contains("tried"), "render 给用户可读证据面")
        case Right(_) => fail("双端点都不可用必须 Left")

      // 主端点 schema≠1（合法 JSON 但门不过）⇒ 回落成功
      fixture.serve("/plugins/index.json", 200, """{"schema":9}""")
      fixture.serve("/plugins/index-fallback.json", 200, """{"schema":1,"plugins":[{"id":"ok"}]}""")
      val res2 = PluginSourceCheck.fetchIndexAt(List(
        fixture.base + "/plugins/index.json",
        fixture.base + "/plugins/index-fallback.json"
      )).unsafeRunSync()
      assertEquals(res2.map(_._2), Right(fixture.base + "/plugins/index-fallback.json"), "schema≠1 ⇒ 回落命中")
    finally fixture.stop()

  test("读序反例：死端口 + 404 混合 ⇒ 逐端点原因（连接拒绝 / HTTP 404）"):
    val dead = FixtureHttp.deadPort()
    val fixture = FixtureHttp().start()
    try
      val res = PluginSourceCheck.fetchIndexAt(List(
        s"http://127.0.0.1:$dead/plugins/index.json",
        fixture.base + "/plugins/nothing-here.json"
      )).unsafeRunSync()
      res match
        case Left(un) =>
          assert(un.tried(0)._1.endsWith(s":$dead/plugins/index.json"))
          assert(un.tried(0)._2.nonEmpty, "死端口原因留痕（连接拒绝）")
          assertEquals(un.tried(1)._2, "HTTP 404", "404 原因留痕")
        case Right(_) => fail("必须全败 Left")
    finally fixture.stop()

end PluginSourceCheckSpec
