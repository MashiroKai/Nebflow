package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 扫描器加固（插件分发与生命周期协议升级草案 §9 A 行「备份目录排除、重复名检测」；
 * §3 安全债务清偿：PluginRegistry.scala:559 未排除点目录、:734-736 目录/manifest 名
 * 不一致仅告警——把原样备份放在 plugins 内会形成第二个同名候选）：
 *  - **点目录与 plugins-backups 排除**：不进扫描面（既不在 plugins 也不在 rejected，
 *    整体隐身——备份不是候选）；
 *  - **重复身份全拒**：两个目录同 manifest 名 ⇒ 双双拒载点名（ambiguous identity），
 *    他包不受牵连；
 *  - **打包规范正字**：包根已知五件常量（[[PluginPackagingSpec.KnownRootEntries]]）与
 *    装载器同源——未知条目仍走 §B.8-5 宽容告警（行为不变，常量单源化）。
 */
class PluginScanHardeningSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path     = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-scan-hardening"))
    PathUtil.setDataRoot(home)

  /** 逐测试隔离根（全列表断言不容跨测试累积夹具）。 */
  override def beforeEach(context: munit.BeforeEach): Unit =
    try os.remove.all(home / "plugins")
    catch case _: Exception => ()
    os.makeDir.all(home / "plugins")
    try os.remove.all(PluginSourceLedger.ledgerDir)
    catch case _: Exception => ()
    PluginRegistry.invalidateCache()
    PluginRegistry.resetProvenanceBackfillGate()
    PluginRegistry.setProvenanceBackfillForTest(None)

  override def afterAll(): Unit =
    PluginRegistry.setProvenanceBackfillForTest(None)
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  private def mkPlugin(dir: os.Path, manifestName: String): os.Path =
    os.makeDir.all(dir / "skills" / "s")
    os.write.over(
      dir / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$manifestName","version":"1.0.0","description":"fixture"}"""
    )
    os.write.over(dir / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nbody\n")
    dir

  // ── 备份目录排除（点目录 + plugins-backups）────────────────────

  test("点目录与 plugins-backups 不进扫描面：备份不再形成第二候选，整体隐身"):
    mkPlugin(home / "plugins" / ".backup-dup", "dup") // 点目录备份（历史形态：备份曾被放进 plugins/）
    mkPlugin(home / "plugins" / "plugins-backups", "backup-candidate") // 备份目录误入 plugins/
    mkPlugin(home / "plugins" / ".hidden", "hidden") // 任意点目录（编辑器/.DS_Store 类噪声同面）
    mkPlugin(home / "plugins" / "normal", "normal")

    PluginRegistry.invalidateCache()
    val plugins = PluginRegistry.scan().unsafeRunSync()
    assertEquals(plugins.map(_.name).sorted, List("normal"), "只有正常包进注册表")

    val (_, rejected) = PluginRegistry.listWithRejected().unsafeRunSync()
    assert(rejected.isEmpty, s"排除目录必须整体隐身（不进拒载面、不刷告警）, got: $rejected")

  test("排除目录里的同名备份不再触发重复身份拒载（排除先于装载）"):
    mkPlugin(home / "plugins" / "real", "real")
    mkPlugin(home / "plugins" / ".backup-real", "real") // 若不先排除，这里会形成第二候选
    PluginRegistry.invalidateCache()
    assertEquals(PluginRegistry.scan().unsafeRunSync().map(_.name), List("real"))

  // ── 重复身份检测 ─────────────────────────────────────────────

  test("重复身份全拒：两个目录同 manifest 名 ⇒ 双双拒载点名目录，他包不受牵连"):
    mkPlugin(home / "plugins" / "dup-a", "dup")
    mkPlugin(home / "plugins" / "dup-b", "dup")
    mkPlugin(home / "plugins" / "solo", "solo")

    PluginRegistry.invalidateCache()
    val plugins = PluginRegistry.scan().unsafeRunSync()
    assertEquals(plugins.map(_.name), List("solo"), "无歧义身份的包照常装载")

    val (_, rejected) = PluginRegistry.listWithRejected().unsafeRunSync()
    assertEquals(rejected.map(_._1), List("dup"), "重复身份按 manifest 名点名")
    val reason = rejected.head._2
    assert(reason.toLowerCase.contains("duplicate"), s"原因必须指名 duplicate identity, got: $reason")
    assert(reason.contains("dup-a") && reason.contains("dup-b"), s"原因必须列出全部冲突目录, got: $reason")

    // resolve 对被拒身份 ⇒ not found（拒载不进注册表）
    PluginRegistry.resolve("dup").unsafeRunSync() match
      case Left(err) => assert(err.contains("not found"))
      case Right(p)  => fail(s"重复身份不得可解析, got: $p")

  // ── 打包规范正字（§9 A 行「确认打包规范」）───────────────────────

  test("打包规范正字：包根已知五件常量与装载器同源，未知条目仍宽容告警"):
    assertEquals(
      PluginPackagingSpec.KnownRootEntries,
      Set("plugin.json", "mcp.json", "skills", "org.nebflow", "Plugin.md"),
      "§10「包根五件约束」的正字清单（装载器 known-set 的公共单源形态）"
    )

    val dir = mkPlugin(home / "plugins" / "spec-pin", "spec-pin")
    os.write.over(dir / "CHANGELOG.md", "# historical entry\n")
    PluginRegistry.invalidateCache()
    PluginRegistry.scan().unsafeRunSync().find(_.name == "spec-pin") match
      case Some(p) =>
        assert(
          p.warnings.exists(_.contains("ignored unknown entry 'CHANGELOG.md'")),
          s"未知条目仍走 §B.8-5 宽容告警（行为不变）, got: ${p.warnings}"
        )
      case None => fail("spec-pin 应照常装载")

end PluginScanHardeningSpec
