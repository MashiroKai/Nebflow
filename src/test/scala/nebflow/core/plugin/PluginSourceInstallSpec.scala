package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

import scala.concurrent.duration.*

/**
 * 从源安装流（远程发布源设计稿 §4，P2 验收集成测——本地 http fixture 伺服 fixture 清单+归档，
 * 零 live 探测）：
 *  - **三态 fetcher 各自正反例**：official（索引→清单→归档）/ git（pin clone）/ https（直链）；
 *  - **双值校验失败拒装**：归档 sha256 篡改一字节必拒；内容树 digest 失配必拒（零副作用）；
 *  - **原子切换**：任何拒装路径 plugins/ 零半包、暂存会话零残留（中断面 = staging 在
 *    plugins/ 之外 + 收口单次 os.move）；
 *  - **@version / 缺省通道解析**（A8②）：@version 精确（未知 ⇒ 列可用版本）；缺省 =
 *    清单 versions[0]；通道 = 引擎通道（beta ⇒ index-beta.json / manifest-beta.json）；
 *  - **清单降级容忍**：可选字段缺席照装；**OSS 回落次序**：主端点死 ⇒ 回落命中；全死 ⇒
 *    列端点报错；
 *  - **安装后账本行**：via=official 且 digest 与包相符（git=via=git+pin、https=via=https）；
 *  - 同名已存在即拒（1274-1281 口径）；官方身份闸（id ∉ 索引 ⇒ 拒）。
 */
class PluginSourceInstallSpec extends FunSuite:

  override val munitTimeout: FiniteDuration = Duration(240, "s")

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path     = os.Path("/tmp")
  private val fixtureRoot: os.Path = os.pwd / "target" / "plugin-source-install"

  private val propKeys = List("nebflow.plugins.indexUrl", "nebflow.plugins.indexFallbackUrl", "nebflow.plugins.channel")
  private var savedProps: Map[String, Option[String]] = Map.empty

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-source-install"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")
    os.remove.all(fixtureRoot)
    os.makeDir.all(fixtureRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    propKeys.foreach {
      case k if savedProps.get(k).flatten.isDefined => System.setProperty(k, savedProps(k).get)
      case k                                        => System.clearProperty(k)
    }
    try os.remove.all(home)
    catch case _: Exception => ()
    try os.remove.all(fixtureRoot)
    catch case _: Exception => ()

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedProps = propKeys.map(k => k -> Option(System.getProperty(k))).toMap
    propKeys.foreach(System.clearProperty)
    // 通道钉 stable（dev 构建的 VERSION 自报 beta；A8② 缺省通道 = 引擎通道 ⇒ 夹具按 stable 伺服。
    // beta 通道面由显式 beta=true 的专项用例覆盖）
    System.setProperty("nebflow.plugins.channel", "stable")
    // 每测一个干净的安装面（plugins/ + 账本 + 暂存 + 缓存/回填闸）
    os.remove.all(home / "plugins"); os.makeDir.all(home / "plugins")
    os.remove.all(PluginSourceLedger.ledgerDir)
    os.remove.all(home / ".plugin-staging")
    PluginRegistry.invalidateCache()
    PluginRegistry.resetProvenanceBackfillGate()

  override def afterEach(context: munit.AfterEach): Unit =
    savedProps.foreach {
      case (k, Some(v)) => System.setProperty(k, v)
      case (k, None)    => System.clearProperty(k)
    }

  // ── 夹具构建 ──────────────────────────────────────────────

  private val Canonical = PluginRegistry.CanonicalSchema

  /** 包内容树（与提取产物同构：相对路径 → 字节）。 */
  private def treeFor(name: String, marker: String = "body"): Map[String, Array[Byte]] = Map(
    "plugin.json" ->
      s"""{"$$schema":"$Canonical","name":"$name","version":"1.0.0","description":"fixture"}""".getBytes("UTF-8"),
    "skills/s/SKILL.md" ->
      s"---\nname: s\ndescription: fixture\n---\n$marker\n".getBytes("UTF-8")
  )

  private def zipOf(files: Map[String, Array[Byte]]): Array[Byte] =
    val bos = new java.io.ByteArrayOutputStream()
    val zos = new java.util.zip.ZipOutputStream(bos)
    files.toList.sortBy(_._1).foreach { (name, content) =>
      zos.putNextEntry(new java.util.zip.ZipEntry(name))
      zos.write(content)
      zos.closeEntry()
    }
    zos.close()
    bos.toByteArray

  /** 内容树 digest 基准：建真目录 → PluginRegistry.computeDigest（与提取产物同算法同输入）。 */
  private def treeDigest(files: Map[String, Array[Byte]]): String =
    val dir = os.Path(Files.createTempDirectory("nb-digest-probe"))
    try
      files.foreach { (rel, bytes) =>
        val p = dir / os.SubPath(rel)
        os.makeDir.all(p / os.up)
        os.write.over(p, bytes)
      }
      PluginRegistry.computeDigest(dir).toOption.get._1
    finally os.remove.all(dir)

  private def sha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  private def manifestFor(name: String): String =
    s"""{"$$schema":"$Canonical","name":"$name","version":"1.0.0","description":"fixture"}"""

  // ── 断言助手 ──────────────────────────────────────────────

  private def stagingResidue: List[String] =
    val root = home / ".plugin-staging"
    if os.isDir(root) then os.list(root).map(_.last).toList else Nil

  private def ledgerRows: List[String] =
    if os.isDir(PluginSourceLedger.ledgerDir) then os.list(PluginSourceLedger.ledgerDir).filter(_.ext == "json").map(_.last).toList else Nil

  private def installedNames: List[String] =
    if os.isDir(home / "plugins") then os.list(home / "plugins").map(_.last).toList.sorted else Nil

  private def installedDigest(name: String): String =
    PluginRegistry.computeDigest(home / "plugins" / name).toOption.get._1

  private def assertNoSideEffects(before: List[String]): Unit =
    assertEquals(installedNames, before, "拒装路径必须零副作用（plugins/ 逐目录不变）")
    assertEquals(stagingResidue, Nil, "拒装路径必须零暂存残留（会话 finally 清理）")
    assertEquals(ledgerRows, Nil, "拒装路径零账本行")

  /** 注入官方源端点：primary（可死）/ fallback（可死）+ 伺服内容。 */
  private def setEndpoints(primary: String, fallback: String): Unit =
    System.setProperty("nebflow.plugins.indexUrl", primary)
    System.setProperty("nebflow.plugins.indexFallbackUrl", fallback)

  /** 标准官方源夹具：index + manifest（多版本可选）+ 归档。返回 (fixture, id, zipBytes)。 */
  private def serveOfficial(
      id: String,
      versions: List[String],
      mutate: Map[String, Array[Byte]] => Map[String, Array[Byte]] = identity,
      manifestOverride: Option[String => String] = None,
      indexOverride: Option[String] = None,
      beta: Boolean = false
  ): (FixtureHttp, String, Array[Byte]) =
    val fixture = FixtureHttp().start()
    val files = mutate(treeFor(id))
    val zip = zipOf(files)
    val digest = treeDigest(files)
    val sha = sha256Hex(zip)
    val suffix = if beta then "-beta" else ""
    def artifactJson(version: String): String =
      val url = s"${fixture.base}/plugins/$id/files/$id-$version.zip"
      fixture.serveBytes(s"/plugins/$id/files/$id-$version.zip", 200, zip)
      s"""{"format":"zip","sizeBytes":${zip.length},"sha256":"$sha","digest":"$digest","url":"$url",""" +
        s""""mirror":"http://127.0.0.1:1/mirror-must-not-be-hit.zip"}"""
    val versionsJson = versions.map { v =>
      s"""{"version":"$v","publishedAt":"2026-10-29T16:40:00Z","notesUrl":"https://example/notes",""" +
        s""""requires":{"manifestSchema":"1.0.0","minEngine":"2026.10.1"},"artifacts":[${artifactJson(v)}]}"""
    }.mkString(",")
    val manifest = manifestOverride match
      case Some(f) => f(id)
      case None    => s"""{"schema":1,"id":"$id","sequence":45,"versions":[$versionsJson]}"""
    fixture.serve(s"/plugins/$id/manifest$suffix.json", 200, manifest)
    val index = indexOverride.getOrElse {
      s"""{"schema":1,"sequence":47,"plugins":[{"id":"$id","latest":"${versions.head}","sequence":45}]}"""
    }
    fixture.serve(s"/plugins/index$suffix.json", 200, index)
    (fixture, id, zip)
  end serveOfficial

  // ── official 腿：happy path / 版本解析 / 账本行 ────────────────

  test("official happy path: add <id> ⇒ 装成 + via=official 账本 digest 与包相符 + 零暂存残留"):
    val fixture0 = FixtureHttp.deadPort()
    val (fixture, id, _) = serveOfficial("official-fixture-pkg", List("2.0.0", "1.0.0"))
    try
      setEndpoints(s"http://127.0.0.1:$fixture0/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.isRight, s"install must succeed, got: ${res.swap.toOption.getOrElse("")}")
      assert(installedNames == List(id), s"包落盘 plugins/<id>，got: $installedNames")
      assert(res.toOption.get.contains("presence = trust"), "成功文案保留在位即信任口径")

      // 账本行：via=official 且 digest 与包相符（任务验收原文）
      PluginSourceLedger.query(id) match
        case PluginSourceLedger.Known(e) =>
          assertEquals(e.via, PluginSourceLedger.Via.Official)
          assertEquals(e.kind, PluginSourceLedger.Kind.Official)
          assertEquals(e.version, "2.0.0", "缺省 = 清单 versions[0]（新→旧第一条）")
          assertEquals(e.digest, installedDigest(id), "账本 digest == 现算内容树 digest")
          assert(e.url.exists(_.endsWith(s"$id-2.0.0.zip")), s"url = 归档端点，got: ${e.url}")
        case PluginSourceLedger.UnknownSource(r) => fail(s"expected Known, got: $r")

      // 装载面零改动：注册表扫描能看到（在位即信任）
      val scanned = PluginRegistry.scan().unsafeRunSync().map(_.name)
      assert(scanned.contains(id), s"scan 必须看到新包（在位即信任），got: $scanned")

      assertEquals(stagingResidue, Nil, "成功路径暂存会话零残留")
    finally fixture.stop()

  test("@version 精确解析；未知版本 ⇒ SOURCE_VERSION_NOT_FOUND 列可用版本（零副作用）"):
    val (fixture, id, _) = serveOfficial("versioned-fixture-pkg", List("2.0.0", "1.0.0"))
    try
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val before = installedNames

      val old = PluginSourceInstall.installFromArg(s"$id@1.0.0").unsafeRunSync()
      assert(old.isRight, s"@1.0.0 必须精确装旧版，got: ${old.swap.toOption.getOrElse("")}")
      PluginSourceLedger.query(id) match
        case PluginSourceLedger.Known(e) => assertEquals(e.version, "1.0.0", "@version 精确指定")
        case u                           => fail(s"expected Known, got: $u")

      // 重装同名 = 拒（先清掉再测未知版本路径，保持零副作用断言干净）
      os.remove.all(home / "plugins" / id)
      PluginSourceLedger.query(id) // 账本行仍在——不影响；清账本做零副作用断言
      os.remove.all(PluginSourceLedger.ledgerDir)

      val missing = PluginSourceInstall.installFromArg(s"$id@9.9.9").unsafeRunSync()
      val msg = missing.left.toOption.getOrElse(fail("unknown version must be refused"))
      assert(msg.contains(PluginSourceInstall.CodeVersionNotFound), s"错误码逐字，got: $msg")
      assert(msg.contains("9.9.9") && msg.contains("2.0.0") && msg.contains("1.0.0"), s"列可用版本，got: $msg")
      assertNoSideEffects(before)
    finally fixture.stop()

  // ── 双值校验（篡改一字节必拒）与围栏 ──────────────────────────

  test("双值①：归档 sha256 失配 ⇒ 拒装零副作用（服务端字节被篡改一字节的等价形态）"):
    val (fixture, id, zip) = serveOfficial("sha-fixture-pkg", List("1.0.0"))
    try
      // 清单声明一个错误 sha（wire 上是真实归档字节 ⇒ 解出内容 digest 是真值 ⇒ 只让①失败）
      fixture.serveBytes("/plugins/sha-zip", 200, zip)
      fixture.serve(
        "/plugins/sha-fixture-pkg/manifest.json",
        200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${zip.length},""" +
          s""""sha256":"${"f" * 64}","digest":"${treeDigest(treeFor(id))}","url":"${fixture.base}/plugins/sha-zip"}]}]}"""
      )
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val before = installedNames
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      val msg = res.left.toOption.getOrElse(fail("must be refused"))
      assert(msg.contains(PluginSourceInstall.CodeSha256Mismatch), s"双值①错误码逐字，got: $msg")
      assertNoSideEffects(before)
    finally fixture.stop()

  test("双值②：内容树 digest 失配 ⇒ 拒装（归档字节对但解出内容 ≠ pin——重打包等价形态）"):
    val (fixture, id, zip) = serveOfficial("digest-fixture-pkg", List("1.0.0"))
    try
      fixture.serveBytes("/plugins/digest-fixture-pkg/files/pkg.zip", 200, zip)
      val realSha = sha256Hex(zip)
      val staleDigest = "0" * 64
      fixture.serve(
        "/plugins/digest-fixture-pkg/manifest.json",
        200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${zip.length},""" +
          s""""sha256":"$realSha","digest":"$staleDigest","url":"${fixture.base}/plugins/digest-fixture-pkg/files/pkg.zip"}]}]}"""
      )
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val before = installedNames
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      val msg = res.left.toOption.getOrElse(fail("must be refused"))
      assert(msg.contains(PluginSourceInstall.CodeDigestMismatch), s"双值②错误码逐字，got: $msg")
      assertNoSideEffects(before)
    finally fixture.stop()

  test("sizeBytes 咬合：声明字节数 ≠ 实际 ⇒ SOURCE_SIZE_MISMATCH 拒装"):
    val (fixture, id, zip) = serveOfficial("size-fixture-pkg", List("1.0.0"))
    try
      fixture.serveBytes("/plugins/size-fixture-pkg/files/pkg.zip", 200, zip)
      fixture.serve(
        "/plugins/size-fixture-pkg/manifest.json",
        200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${zip.length + 1},""" +
          s""""sha256":"${sha256Hex(zip)}","digest":"${treeDigest(treeFor("size-fixture-pkg"))}",""" +
          s""""url":"${fixture.base}/plugins/size-fixture-pkg/files/pkg.zip"}]}]}"""
      )
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val before = installedNames
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.left.toOption.exists(_.contains(PluginSourceInstall.CodeSizeMismatch)), s"got: $res")
      assertNoSideEffects(before)
    finally fixture.stop()

  test("zip 围栏：.. 逃逸条目 / 绝对路径条目 ⇒ SOURCE_ARCHIVE_FENCE 拒装；包根无 manifest ⇒ LAYOUT 拒"):
    val before = installedNames
    List(
      ("../evil.txt", "escape", PluginSourceInstall.CodeArchiveFence),
      ("/abs.txt", "abs", PluginSourceInstall.CodeArchiveFence),
      ("a/../../out.txt", "dotdot", PluginSourceInstall.CodeArchiveFence)
    ).foreach { (badName, label, code) =>
      val (fixture, id, _) = serveOfficial(s"fence-$label-pkg", List("1.0.0"))
      try
        val malicious = zipOf(treeFor(s"fence-$label-pkg") + (badName -> "evil".getBytes))
        fixture.serveBytes("/plugins/escape-zip", 200, malicious)
        fixture.serve(
          s"/plugins/fence-$label-pkg/manifest.json",
          200,
          s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${malicious.length},""" +
            s""""sha256":"${sha256Hex(malicious)}","digest":"${"9" * 64}","url":"${fixture.base}/plugins/escape-zip"}]}]}"""
        )
        setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
        val res = PluginSourceInstall.installFromArg(s"fence-$label-pkg").unsafeRunSync()
        val msg = res.left.toOption.getOrElse(fail(s"[$label] must be refused"))
        assert(msg.contains(code), s"[$label] 围栏错误码逐字，got: $msg")
        assert(msg.contains(badName.take(8)), s"[$label] 点名违规条目，got: $msg")
      finally fixture.stop()
    }
    // 包根平铺布局：plugin.json 在子目录 ⇒ LAYOUT 拒
    val (fixture2, id2, _) = serveOfficial("layout-fixture-pkg", List("1.0.0"))
    try
      val wrapped = zipOf(Map("pkg/plugin.json" -> treeFor(id2)("plugin.json"), "pkg/skills/s/SKILL.md" -> treeFor(id2)("skills/s/SKILL.md")))
      fixture2.serveBytes("/plugins/wrapped.zip", 200, wrapped)
      fixture2.serve(
        "/plugins/layout-fixture-pkg/manifest.json",
        200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${wrapped.length},""" +
          s""""sha256":"${sha256Hex(wrapped)}","digest":"${treeDigest(Map("plugin.json" -> treeFor(id2)("plugin.json"), "skills/s/SKILL.md" -> treeFor(id2)("skills/s/SKILL.md")))}",""" +
          s""""url":"${fixture2.base}/plugins/wrapped.zip"}]}]}"""
      )
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture2.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id2).unsafeRunSync()
      assert(res.left.toOption.exists(_.contains(PluginSourceInstall.CodeArchiveLayout)), s"got: $res")
    finally fixture2.stop()
    assertNoSideEffects(before)

  // ── 原子切换（中断不留半包的结构保证）─────────────────────────

  test("原子性：拒装全程 plugins/ 无新目录；暂存根在 plugins/ 之外"):
    val (fixture, id, zip) = serveOfficial("atomic-fixture-pkg", List("1.0.0"))
    try
      fixture.serve(
        "/plugins/atomic-fixture-pkg/manifest.json",
        200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${zip.length},""" +
          s""""sha256":"${"f" * 64}","digest":"${"e" * 64}","url":"${fixture.base}/plugins/atomic-zip"}]}]}"""
      )
      fixture.serveBytes("/plugins/atomic-zip", 200, zip)
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.isLeft, "sha 篡改必须拒")
      assert(!os.exists(home / "plugins" / id), "半包不得出现在 plugins/ 扫描面")
      assert(!installedNames.contains(id))
      assertEquals(stagingResidue, Nil, "暂存会话清理（中断面 = staging 之外 + finally）")
      // 暂存根是 plugins/ 的兄弟目录（.plugin-staging），不在扫描面
      assert(
        !(home / ".plugin-staging").toString.startsWith((home / "plugins").toString),
        "暂存根必须在 plugins/ 之外（半装态零扫描面的前提）"
      )
    finally fixture.stop()

  test("归档 mirror 兜底：主 url 404 ⇒ mirror 命中（双候选次序）"):
    val fixture = FixtureHttp().start()
    try
      val id = "mirror-fixture-pkg"
      val files = treeFor(id)
      val zip = zipOf(files)
      fixture.serveBytes("/mirror/pkg.zip", 200, zip)
      fixture.serve("/plugins/index.json", 200, s"""{"schema":1,"plugins":[{"id":"$id"}]}""")
      fixture.serve(s"/plugins/$id/manifest.json", 200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${zip.length},""" +
          s""""sha256":"${sha256Hex(zip)}","digest":"${treeDigest(files)}","url":"${fixture.base}/plugins/missing-path.zip",""" +
          s""""mirror":"${fixture.base}/mirror/pkg.zip"}]}]}""")
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.isRight, s"mirror 兜底必须命中，got: ${res.swap.toOption.getOrElse("")}")
      assert(fixture.requested.contains("/plugins/missing-path.zip"), "主 url 先试")
      assert(fixture.requested.contains("/mirror/pkg.zip"), "mirror 兜底命中")
      assert(installedNames.contains(id))
    finally fixture.stop()

  // ── 官方身份闸 / 同名拒覆盖 / artifact 不可用 ──────────────────

  test("官方身份闸：id ∉ 索引 ⇒ SOURCE_ID_NOT_IN_INDEX"):
    val (fixture, _, _) = serveOfficial("gate-fixture-pkg", List("1.0.0"))
    try
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg("not-in-index-pkg").unsafeRunSync()
      val msg = res.left.toOption.getOrElse(fail("must be refused"))
      assert(msg.contains(PluginSourceInstall.CodeIdNotInIndex), s"got: $msg")
      assert(msg.contains("identity gate"), s"点名身份闸，got: $msg")
      assertNoSideEffects(installedNames)
    finally fixture.stop()

  test("同名已存在即拒（1274-1281 口径逐字保持）：第二次安装 Left + 原包字节不动"):
    val (fixture, id, _) = serveOfficial("dup-fixture-pkg", List("1.0.0"))
    try
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val first = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(first.isRight)
      val digestBefore = installedDigest(id)
      val second = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      val msg = second.left.toOption.getOrElse(fail("second install must be refused"))
      assert(msg.contains("already exists") && msg.contains("refusing to overwrite"), s"现状口径文案，got: $msg")
      assertEquals(installedDigest(id), digestBefore, "原包字节零触碰")
    finally fixture.stop()

  test("artifact 不可用（缺 digest 字段）⇒ SOURCE_ARTIFACT_UNUSABLE"):
    val (fixture, id, zip) = serveOfficial("artifact-fixture-pkg", List("1.0.0"))
    try
      fixture.serve(
        "/plugins/artifact-fixture-pkg/manifest.json",
        200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"format":"zip","sizeBytes":${zip.length},""" +
          s""""sha256":"${sha256Hex(zip)}","url":"${fixture.base}/plugins/atomic-zip"}]}]}"""
      )
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.left.toOption.exists(_.contains(PluginSourceInstall.CodeArtifactUnusable)), s"got: $res")
      assertNoSideEffects(installedNames)
    finally fixture.stop()

  // ── 降级容忍 / OSS 回落次序 / beta 通道 ───────────────────────

  test("清单降级容忍：index 条目只有 id、manifest 只有 schema+versions[version+artifacts] ⇒ 照装"):
    val (fixture, id, zip) = serveOfficial("degraded-fixture-pkg", List("1.0.0"))
    try
      fixture.serveBytes("/plugins/degraded-zip", 200, zip)
      fixture.serve(s"/plugins/$id/manifest.json", 200,
        s"""{"schema":1,"versions":[{"version":"1.0.0","artifacts":[{"sizeBytes":${zip.length},"sha256":"${sha256Hex(zip)}","digest":"${treeDigest(treeFor(id))}","url":"${fixture.base}/plugins/degraded-zip"}]}]}""")
      fixture.serve("/plugins/index.json", 200, s"""{"schema":1,"plugins":[{"id":"$id"}]}""")
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.isRight, s"降级清单照装，got: ${res.swap.toOption.getOrElse("")}")
      assert(installedNames.contains(id))
    finally fixture.stop()

  test("OSS 回落次序：主端点死 ⇒ 回落命中（请求只落到 fixture）；双端点死 ⇒ 列端点报错"):
    val (fixture, id, _) = serveOfficial("fallback-fixture-pkg", List("1.0.0"))
    try
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"${fixture.base}/plugins/index.json")
      val res = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      assert(res.isRight, s"主死回落命中，got: ${res.swap.toOption.getOrElse("")}")
      assert(fixture.requested.contains("/plugins/index.json"), "索引从回落端点取")

      // 双端点死 ⇒ Unreachable 列试过端点
      setEndpoints(s"http://127.0.0.1:1/plugins/index.json", s"http://127.0.0.1:2/plugins/index.json")
      val res2 = PluginSourceInstall.installFromArg(id).unsafeRunSync()
      val msg = res2.left.toOption.getOrElse(fail("must fail"))
      assert(msg.contains(PluginSourceInstall.CodeUnreachable), s"got: $msg")
      assert(msg.contains("127.0.0.1:1") && msg.contains("127.0.0.1:2"), s"列两个试过端点，got: $msg")
    finally fixture.stop()

  test("beta 通道（A7①/A8②）：beta=true ⇒ index-beta.json + manifest-beta.json 同构派生"):
    val (fixture, id, _) = serveOfficial("beta-fixture-pkg", List("1.0.0"), beta = true)
    try
      setEndpoints(s"http://127.0.0.1:1/plugins/index-beta.json", s"${fixture.base}/plugins/index-beta.json")
      val res = PluginSourceInstall.installOfficial(id, None, beta = true, explicitIndex = None).unsafeRunSync()
      assert(res.isRight, s"got: ${res.swap.toOption.getOrElse("")}")
      assert(fixture.requested.contains("/plugins/index-beta.json"), "beta 索引")
      assert(fixture.requested.contains(s"/plugins/$id/manifest-beta.json"), "beta 清单同构派生")
      assert(!fixture.requested.contains("/plugins/index.json"), "stable 端点零请求（一机一通道）")
    finally fixture.stop()

  // ── --from 三态 spec 直读 ───────────────────────────────────

  test("--from official（显式 index）：只请求该索引，不臆造 OSS 镜像跳"):
    val (fixture, id, _) = serveOfficial("from-official-pkg", List("1.0.0"))
    try
      // 端点属性**不注入**——默认端点（mystand.space）不得被请求
      val spec =
        s"""{"kind":"official","index":"${fixture.base}/plugins/custom-index.json","id":"$id"}"""
      fixture.serve("/plugins/custom-index.json", 200, s"""{"schema":1,"plugins":[{"id":"$id","manifest":"${fixture.base}/plugins/from-official-pkg/manifest.json"}]}""")
      val res = PluginSourceInstall.installFromSpecJson(spec).unsafeRunSync()
      assert(res.isRight, s"got: ${res.swap.toOption.getOrElse("")}")
      assert(fixture.requested.contains("/plugins/custom-index.json"))
      assert(fixture.requested.contains("/plugins/from-official-pkg/manifest.json"), "显式 manifest 端点")
      assert(!fixture.requested.contains("/plugins/index.json"), "默认索引零请求")
    finally fixture.stop()

  test("--from https：直链归档 + 双值 ⇒ via=https 账本；sha 失配拒装"):
    val fixture = FixtureHttp().start()
    try
      val id = "https-fixture-pkg"
      val files = treeFor(id)
      val zip = zipOf(files)
      fixture.serveBytes("/direct/pkg.zip", 200, zip)
      val goodSpec =
        s"""{"kind":"https","archive":"${fixture.base}/direct/pkg.zip","sha256":"${sha256Hex(zip)}",""" +
          s""""sizeBytes":${zip.length},"digest":"${treeDigest(files)}"}"""
      val res = PluginSourceInstall.installFromSpecJson(goodSpec).unsafeRunSync()
      assert(res.isRight, s"got: ${res.swap.toOption.getOrElse("")}")
      PluginSourceLedger.query(id) match
        case PluginSourceLedger.Known(e) =>
          assertEquals(e.via, PluginSourceLedger.Via.Https, "https 腿 = via=https（additive 枚举值）")
          assertEquals(e.kind, PluginSourceLedger.Kind.Https)
          assertEquals(e.digest, installedDigest(id))
        case u => fail(s"expected Known, got: $u")

      // sha 失配拒装（一字节篡改 = 重算 sha 后声明值不变）
      os.remove.all(home / "plugins" / id)
      val tampered = zip ++ Array[Byte](0)
      fixture.serveBytes("/direct/tampered.zip", 200, tampered)
      val badSpec =
        s"""{"kind":"https","archive":"${fixture.base}/direct/tampered.zip","sha256":"${sha256Hex(zip)}","digest":"${treeDigest(files)}"}"""
      val res2 = PluginSourceInstall.installFromSpecJson(badSpec).unsafeRunSync()
      assert(res2.left.toOption.exists(_.contains(PluginSourceInstall.CodeSha256Mismatch)), s"got: $res2")
      assert(!installedNames.contains(id), "拒装零半包")
    finally fixture.stop()

  test("--from 非法 spec ⇒ SPEC_ 错误码透传（P1 解析面）"):
    val res = PluginSourceInstall.installFromSpecJson("""{"kind":"svn","url":"x"}""").unsafeRunSync()
    assert(res.left.toOption.exists(_.contains(PluginSourceSpec.CodeKindUnknown)), s"got: $res")

  // ── git 腿（本地仓 clone，零网络）────────────────────────────

  private def git(args: String*)(cwd: os.Path): String =
    val res = os.proc(Seq("git") ++ args).call(check = false, cwd = cwd)
    assert(res.exitCode == 0, s"git ${args.mkString(" ")} failed: ${res.err.text()}")
    res.out.text().trim

  private def mkRepo(name: String, commits: Int = 1): os.Path =
    val repo = fixtureRoot / "repos" / name
    os.remove.all(repo)
    os.makeDir.all(repo / "skills" / "s")
    os.write.over(repo / "plugin.json", manifestFor(name))
    os.write.over(repo / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nc0\n")
    git("init", "-q")(repo)
    git("config", "user.email", "spec@example.com")(repo)
    git("config", "user.name", "spec")(repo)
    git("add", "-A")(repo)
    git("commit", "-q", "-m", "c0")(repo)
    if commits > 1 then
      os.write.over(repo / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nc1\n")
      git("add", "-A")(repo)
      git("commit", "-q", "-m", "c1")(repo)
    repo

  test("git 腿正例：add <repo>.git#tag ⇒ pin clone 落盘 + via=git + pin=Commit(tag, head) + 包树无 .git"):
    val repo = mkRepo("git-fixture-repo")
    git("tag", "v1")(repo)
    val head = git("rev-parse", "HEAD")(repo)
    PluginRegistry.invalidateCache()
    val res = PluginSourceInstall.installFromArg(s"$repo/.git#v1").unsafeRunSync()
    assert(res.isRight, s"got: ${res.swap.toOption.getOrElse("")}")
    PluginSourceLedger.query("git-fixture-repo") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.via, PluginSourceLedger.Via.Git)
        assertEquals(e.kind, PluginSourceLedger.Kind.Git)
        assertEquals(e.pin, Some(PluginSourceLedger.Pin.Commit(Some("v1"), head)), "pin = tag + 解引用 commit")
        assertEquals(e.digest, installedDigest("git-fixture-repo"))
        assertEquals(e.url, Some(s"$repo/.git"))
      case u => fail(s"expected Known, got: $u")
    // 包树零仓元数据（.git 剔除——扫描/digest 面）
    val installedFiles = os.walk(home / "plugins" / "git-fixture-repo").map(_.relativeTo(home / "plugins" / "git-fixture-repo").toString)
    assert(!installedFiles.exists(_.startsWith(".git")), s".git 必须剔除，got: $installedFiles")

  test("git 腿反例：无 pin ⇒ GIT_PIN_REQUIRED（浮动 HEAD 退役）；tag 被挪 ⇒ GIT_TAG_MOVED"):
    val repo = mkRepo("git-nopin-repo")
    git("tag", "v1")(repo)
    val headAtTag = git("rev-parse", "HEAD")(repo)

    // ① 浮动 HEAD 禁令
    PluginRegistry.invalidateCache()
    val nopin = PluginSourceInstall.installFromArg(s"$repo/.git").unsafeRunSync()
    val nopinMsg = nopin.left.toOption.getOrElse(fail("must refuse"))
    assert(nopinMsg.contains(PluginSourceInstall.CodeGitPinRequired), s"got: $nopinMsg")
    assert(nopinMsg.contains("#"), "文案给 #tag 写法指引")

    // ② tag 被挪（tag 复解引用 ≠ pin commit）
    git("tag", "-f", "v1")(repo) // HEAD 不变（单 commit 仓挪不动）⇒ 先加第二个 commit
    os.write.over(repo / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nmoved\n")
    git("add", "-A")(repo)
    git("commit", "-q", "-m", "moved")(repo)
    git("tag", "-f", "v1")(repo) // tag 现在指向新 commit
    PluginRegistry.invalidateCache()
    val spec =
      s"""{"kind":"git","url":"$repo/.git","pin":{"tag":"v1","commit":"$headAtTag"}}"""
    val moved = PluginSourceInstall.installFromSpecJson(spec).unsafeRunSync()
    val movedMsg = moved.left.toOption.getOrElse(fail("must refuse"))
    assert(movedMsg.contains(PluginSourceInstall.CodeGitTagMoved), s"got: $movedMsg")
    assert(!installedNames.contains("git-nopin-repo"), "拒装零半包")

  test("git 腿反例：tag 不存在 ⇒ GIT_TAG_NOT_FOUND；path 子目录缺失 ⇒ 拒"):
    val repo = mkRepo("git-missing-repo")
    PluginRegistry.invalidateCache()
    val missing = PluginSourceInstall.installFromArg(s"$repo/.git#no-such-tag").unsafeRunSync()
    assert(missing.left.toOption.exists(_.contains(PluginSourceInstall.CodeGitTagNotFound)), s"got: $missing")

    val head = git("rev-parse", "HEAD")(repo)
    val badPath =
      s"""{"kind":"git","url":"$repo/.git","path":"no/such/dir","pin":{"commit":"$head"}}"""
    val res = PluginSourceInstall.installFromSpecJson(badPath).unsafeRunSync()
    assert(res.left.toOption.exists(_.contains(PluginSourceInstall.CodeArchiveLayout)), s"got: $res")

  // ── 分类器（§4.1 歧义消解）──────────────────────────────────

  test("分类器：本地∧官方 id 双命中 ⇒ 歧义报错；非法形态 ⇒ 可行动指引"):
    val amb = "ambiguity-fixture-pkg"
    os.makeDir.all(os.pwd / amb)
    try
      val res = PluginSourceInstall.installFromArg(amb).unsafeRunSync()
      val msg = res.left.toOption.getOrElse(fail("must refuse as ambiguous"))
      assert(msg.contains("ambiguous"), s"got: $msg")
    finally os.remove.all(os.pwd / amb)

    val bad = PluginSourceInstall.installFromArg("Not a Valid Id!").unsafeRunSync()
    val badMsg = bad.left.toOption.getOrElse(fail("must guide"))
    assert(badMsg.contains("not a valid official plugin id"), s"got: $badMsg")

    val empty = PluginSourceInstall.installFromArg("").unsafeRunSync()
    assert(empty.isLeft, "空参必须拒绝")

end PluginSourceInstallSpec
