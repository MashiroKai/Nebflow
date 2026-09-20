package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.seed.SeedService

import java.nio.file.Files

/**
 * 官方包身份脚手架（P0-3）定向验证 —— 基线 §2.4 的「保留前缀 + digest 预置信任」两点的
 * 装载层面孔；可验证里程碑 = **BU A3 冒名拒载（装载错误码断言）**。
 *
 * 覆盖（逐条对应任务书 §二）：
 *  ① 允许列表覆盖真内置官方包 + **digest 算法等价**（`OfficialPackages.digestOf` vs 权威
 *     `PluginRegistry.computeDigest`，对真实包逐包断言——jar: 协议路径的算法不靠注释保证）；
 *  ② **BU A3 红验（改后绿）**：`nebflow-` 前缀 + digest ∉ 允许列表 ⇒ 拒载 + 错误码逐字
 *     `OFFICIAL_IMPERSONATION` + 拒载后不可分配/不可装载 + 走既有错误面（健康摘要）；
 *  ③ 官方夹具包（digest 命中允许列表）⇒ 装载通过 + `Trusted`；**真内置包同臂**（无测试钩子）；
 *  ④ **fail-safe 回落**：用户手改官方包一字节 ⇒ digest 失配 ⇒ 落回拒载；
 *  ⑤ **第三方零变化对照臂**：非保留前缀的包在「允许列表空 / 非空」两窗口下判定逐条相同；
 *  ⑥ **首启预置信任端到端**：`SeedService.ensureSeeded()` 在 fresh home 装内置官方包 +
 *     落 Trusted 审计记录，且「允许列表 digest == 信任记录 digest == 装后现算 digest」三方一致。
 *
 * 隔离面：全程 `PathUtil.setDataRoot(<临时目录>)`，**零 real-HOME 写面**
 * （`~/.nebflow/plugins/` 与 `~/.nebflow/nebflow.json` 一个字节都不碰）。
 */
class OfficialIdentitySpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  private val OfficialFixture = "nebflow-official-fixture"
  private val ImpostorFixture = "nebflow-impostor-fixture"
  private val ThirdPartyFixture = "third-party-fixture"
  private val FixtureRoot = "official-fixtures"

  /** 真内置官方包样本（seed 资源树里带保留前缀的三件；`nebflow-plugin-creator` 同时在
    * seed manifest 默认预装集内）。 */
  private val BuiltinOfficialSamples =
    List("nebflow-plugin-creator", "nebflow-qa", "nebflow-frontend-dev")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-official-identity"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    OfficialPackages.setAllowlistForTest(None)
    PluginRegistry.invalidateCache()
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  // ── harness ───────────────────────────────────────────────

  /** 空 plugins/ 的干净 home（每次臂前重置 + 清 mtime 缓存）。 */
  private def freshHome(): Unit =
    os.remove.all(home)
    os.makeDir.all(home)
    OfficialPackages.invalidateCache()
    PluginRegistry.invalidateCache()

  private def scan(): (List[PluginRegistry.PluginDef], List[(String, String)]) =
    PluginRegistry.listWithRejected().unsafeRunSync()

  private def digestOfDir(dir: os.Path): String =
    PluginRegistry.computeDigest(dir) match
      case Right((d, _)) => d
      case Left(err) => fail(s"computeDigest failed for $dir: $err")

  private def resourceDir(rel: String): os.Path =
    val url = getClass.getClassLoader.getResource(s"$rel/plugin.json")
    assert(url != null, s"'$rel' is not on the test classpath (expected under src/test/resources)")
    os.Path(java.nio.file.Paths.get(url.toURI)) / os.up

  /** 夹具包安装到隔离 home（**禁落真 `~/.nebflow/plugins/`**）。 */
  private def installFixture(name: String): os.Path =
    val dest = home / "plugins" / name
    os.makeDir.all(dest / os.up)
    os.copy(resourceDir(s"$FixtureRoot/$name"), dest,
      createFolders = true, mergeFolders = true, replaceExisting = true)
    PluginRegistry.invalidateCache()
    dest

  /** 从 seed 资源树手工安装一个真内置包（与 `SeedService` 同源字节；不启动种子链）。 */
  private def installBuiltin(name: String): os.Path =
    val dest = home / "plugins" / name
    os.makeDir.all(dest / os.up)
    os.copy(resourceDir(s"seed/plugins/$name"), dest,
      createFolders = true, mergeFolders = true, replaceExisting = true)
    PluginRegistry.invalidateCache()
    dest

  // ── ① 允许列表 + 算法等价 ─────────────────────────────────

  test("allowlist covers the distribution's built-in official packages and digestOf == computeDigest") {
    val table = OfficialPackages.allowlist()
    assert(table.nonEmpty, "built-in official allowlist is empty — the built-in package directory was not resolved")
    BuiltinOfficialSamples.foreach { name =>
      val dir = resourceDir(s"seed/plugins/$name")
      val authoritative = digestOfDir(dir)
      assertEquals(table.get(name), Some(authoritative),
        s"'$name' is a shipped reserved-prefix package but is missing from (or mismatched in) the official allowlist")
      // 算法等价：classpath/jar 侧实现（digestOf）与装载层权威 walker（computeDigest）逐字相等
      val files = os.walk(dir).filter(os.isFile).toList.map(f => f.relativeTo(dir).toString -> os.read.bytes(f))
      assertEquals(OfficialPackages.digestOf(files), authoritative,
        s"'$name': OfficialPackages.digestOf drifted from PluginRegistry.computeDigest")
    }
    assert(OfficialPackages.isReserved("nebflow-anything"))
    assert(!OfficialPackages.isReserved("slideblocks"))
  }

  // ── ② BU A3 冒名拒载（红验目标）────────────────────────────

  test("A3: reserved prefix + digest outside the allowlist ⇒ refused with OFFICIAL_IMPERSONATION") {
    freshHome()
    // 允许列表只认官方夹具的 digest —— 冒名包（另一个 digest、且不在表内）必须落空
    val preset = Map(OfficialFixture -> digestOfDir(resourceDir(s"$FixtureRoot/$OfficialFixture")))
    OfficialPackages.withAllowlistForTest(preset) {
      installFixture(ImpostorFixture)
      val (loaded, rejected) = scan()
      assertEquals(loaded.map(_.name), Nil, "the impostor package must not load")
      assertEquals(rejected.map(_._1), List(ImpostorFixture))
      val reason = rejected.head._2
      assert(reason.contains("OFFICIAL_IMPERSONATION"),
        s"the rejection reason must carry the literal error code OFFICIAL_IMPERSONATION, got: $reason")

      // 拒载后包状态：不可装载（不在 registry）→ 不可分配
      val resolved = PluginRegistry.resolve(ImpostorFixture).unsafeRunSync()
      assert(resolved.isLeft)
      assert(resolved.swap.toOption.get.contains("PLUGIN_NOT_FOUND"),
        s"a refused package must be unresolvable (PLUGIN_NOT_FOUND), got: ${resolved.swap.toOption.get}")
      assert(!PluginRegistry.contentTrusted(ImpostorFixture).unsafeRunSync())

      // 错误面 = 既有装载错误面（同一 WARN/缺席注记/健康摘要/拒载清单），不新增第四种
      val health = PluginRegistry.healthSummary().unsafeRunSync()
      assert(health.exists(_.contains("OFFICIAL_IMPERSONATION")),
        s"the startup/rescan health summary must surface the refusal, got: $health")
      assert(health.exists(_.contains("load-failed")), s"refusal must classify as the existing load-failed absence: $health")
      val catalog = PluginRegistry.renderCatalog().unsafeRunSync()
      assert(!catalog.contains(ImpostorFixture), s"a refused package must not appear in the plugin catalog: $catalog")
    }
  }

  // ── ③ 官方包（digest 命中）⇒ 装载 + Trusted ─────────────────

  test("official fixture with a matching digest loads and is Trusted") {
    freshHome()
    val dir = installFixture(OfficialFixture)
    val digest = digestOfDir(dir)
    OfficialPackages.withAllowlistForTest(Map(OfficialFixture -> digest)) {
      val (loaded, rejected) = scan()
      assertEquals(rejected, Nil)
      assertEquals(loaded.map(_.name), List(OfficialFixture))
      assert(loaded.head.trust.trusted, "a shipped official package must be trusted without any user approval step")
      assertEquals(loaded.head.digest, digest)
    }
  }

  test("real built-in official package (byte-copied from the seed tree) loads against the real allowlist") {
    freshHome()
    installBuiltin("nebflow-plugin-creator")
    val (loaded, rejected) = scan()
    assertEquals(rejected, Nil, "a byte-identical copy of a shipped official package must not be refused")
    assertEquals(loaded.map(_.name), List("nebflow-plugin-creator"))
    assert(loaded.head.trust.trusted)
  }

  // ── ④ fail-safe 回落 ──────────────────────────────────────

  test("fail-safe: a one-byte hand modification of an official package falls back to refusal") {
    freshHome()
    val dir = installFixture(OfficialFixture)
    val preset = Map(OfficialFixture -> digestOfDir(dir))
    OfficialPackages.withAllowlistForTest(preset) {
      assertEquals(scan()._1.map(_.name), List(OfficialFixture), "precondition: the clean copy loads")
      os.write.append(dir / "skills" / "demo" / "SKILL.md", "\n<!-- tampered -->\n")
      PluginRegistry.invalidateCache()
      val (loaded, rejected) = scan()
      assertEquals(loaded, Nil, "a tampered official package must not load (fail-safe: fall back to refusal)")
      assertEquals(rejected.map(_._1), List(OfficialFixture))
      assert(rejected.head._2.contains("OFFICIAL_IMPERSONATION"), rejected.head._2)
    }
  }

  // ── ⑤ 第三方零变化对照臂 ───────────────────────────────────

  test("third-party packages keep the exact pre-change decision path (no allowlist consultation)") {
    // 纯函数面：非保留前缀恒放行，与允许列表内容无关（含空表/任意 digest）
    assert(OfficialPackages.admits(ThirdPartyFixture, ""))
    assert(OfficialPackages.admits("slideblocks", "deadbeef"))
    assert(OfficialPackages.admits("visual-report", "0" * 64))

    freshHome()
    installFixture(ThirdPartyFixture)
    val withEmpty = OfficialPackages.withAllowlistForTest(Map.empty)(scan())
    PluginRegistry.invalidateCache()
    val withOther = OfficialPackages.withAllowlistForTest(Map(OfficialFixture -> "0" * 64))(scan())
    assertEquals(withEmpty._1.map(_.name), List(ThirdPartyFixture))
    assertEquals(withOther._1.map(_.name), List(ThirdPartyFixture))
    assertEquals(withEmpty._2, Nil)
    assertEquals(withOther._2, Nil)
  }

  // ── ⑥ 首启预置信任（端到端）─────────────────────────────────

  test("first boot installs a built-in official package with a preset Trusted record aligned to the allowlist") {
    freshHome()
    SeedService.ensureSeeded().unsafeRunSync()
    PluginRegistry.invalidateCache()
    val installed = home / "plugins" / "nebflow-plugin-creator"
    assert(os.exists(installed / "plugin.json"), "the default seed set must install nebflow-plugin-creator")

    val table = OfficialPackages.allowlist()
    val allowlisted = table.get("nebflow-plugin-creator")
    assert(allowlisted.nonEmpty, "the shipped package must be in the official allowlist")
    assertEquals(digestOfDir(installed), allowlisted.get, "installed copy must be byte-identical to the shipped package")
    assertEquals(PluginRegistry.trustRecordDigest("nebflow-plugin-creator"), allowlisted,
      "the preset Trusted (audit) record must be written and aligned with the allowlist digest")

    val (loaded, rejected) = scan()
    assert(rejected.isEmpty, s"unexpected refusals after seeding: $rejected")
    assert(loaded.exists(p => p.name == "nebflow-plugin-creator" && p.trust.trusted))
  }
