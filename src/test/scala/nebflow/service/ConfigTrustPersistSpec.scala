package nebflow.service

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry
import nebflow.core.seed.SeedService
import nebflow.llm.{Config, NebflowServiceConfig, ProviderConfig}

import java.nio.file.Files

/**
 * 插件信任跨重启持久化修复的定向验证（cold-start seed 批 2026-09-07，根因见
 * 上游「调查-插件信任丢失根因」）。
 *
 * 根因链：冷启动在空 home 上，种子先于「llm 已配置」状态自动 approve 插件，
 * 把 plugins.trust 写进一个无 llm 节的配置（{"plugins":{"trust":…}}）；重启时
 * Config.loadServiceConfig() 按 NebflowServiceConfig 解码，因必需字段 'llm'
 * 缺失而抛 RuntimeException → GatewayMain 的 configRef 初始化进入 crash-recovery
 * 分支 → ConfigSnapshot.restoreLatest() 把冷启动期备份下来的 {} 拷回 nebflow.json
 * → 信任表被抹掉。
 *
 * 修复（两处，见对应实现）：
 *   P0 主修：NebflowServiceConfig.llm 缺省（= ServiceLlmConfig(providers = Map.empty)）
 *            使该中间态可解码，不再抛 'Missing required field .llm' → 不触发恢复。
 *   P0 副修：ConfigSnapshot.restoreLatest() 收窄——只在当前配置真正损坏（非法
 *            JSON / 不可读）时恢复；「合法 JSON 但字段不全」不恢复，避免用陈旧 {}
 *            快照打回摧毁可信写。
 */
class ConfigTrustPersistSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-config-trust-persist"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  private def configPath: os.Path = PathUtil.configJsonReadPath(home)

  private def trustOnlyJson(name: String): String =
    s"""{"plugins":{"trust":{"$name":{"sha256":"deadbeef","approvedAt":1757000000,"scope":"all"}}}}"""

  /** 模拟 GatewayMain configRef 初始化的加载/崩溃恢复路径（重启唯一再读点）。 */
  private def simulateRestart(): NebflowServiceConfig =
    try Config.loadServiceConfig()
    catch
      case _: Exception =>
        ConfigSnapshot.restoreLatest().unsafeRunSync()
        Config.loadServiceConfig()

  /** 落一个可被 scan/approve 的最小插件目录。 */
  private def mkPlugin(name: String): Unit =
    val d = home / "plugins" / name
    os.makeDir.all(d / "skills" / "s")
    os.write.over(
      d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","author":{"name":"A"}}""")
    os.write.over(d / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nbody\n")

  // ── P0 主修：无 llm 节的配置可解码（不再抛 'Missing required field .llm'）──

  test("loadServiceConfig decodes a plugins.trust-only config (no llm) without throwing"):
    os.write.over(configPath, trustOnlyJson("demo-plugin"))
    val cfg = Config.loadServiceConfig()
    assertEquals(cfg.llm.providers, Map.empty[String, ProviderConfig], "missing llm must default to empty providers")
    assert(os.read(configPath).contains("trust"), "on-disk config must remain byte-intact (no rewrite by load)")

  test("loadServiceConfig decodes an empty llm block (no providers) without throwing"):
    os.write.over(configPath, """{"llm":{},"plugins":{"trust":{"demo-plugin":{"sha256":"deadbeef","approvedAt":1,"scope":"all"}}}}""")
    val cfg = Config.loadServiceConfig()
    assertEquals(cfg.llm.providers, Map.empty[String, ProviderConfig], "empty llm block must default providers to empty")

  test("loadServiceConfig still decodes a fully configured config unchanged"):
    os.write.over(
      configPath,
      """{"llm":{"providers":{"glm":{"baseUrl":"https://x","apiKey":"k","protocol":"openai","models":[{"id":"m1","maxTokens":4096,"contextWindow":8192}]}},"model":{"default":"glm/m1","fallbacks":[]}}}"""
    )
    val cfg = Config.loadServiceConfig()
    assert(cfg.llm.providers.contains("glm"), "configured provider must be preserved")
    assertEquals(cfg.llm.model.map(_.default), Some("glm/m1"), "configured model chain must be preserved")

  // ── P0 副修：restoreLatest 只在真正损坏时恢复 ─────────────────────────

  test("restoreLatest does NOT restore over a valid-JSON but incomplete config (trust preserved)"):
    os.write.over(configPath, trustOnlyJson("demo-plugin"))
    // 冷启动期备份下来的 {} 快照（模拟 configRef init 在种子前 save 的 {}）
    val snap = home / "backups" / s"nebflow.json.${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}"
    os.makeDir.all(home / "backups")
    os.write.over(snap, "{}")
    val restored = ConfigSnapshot.restoreLatest().unsafeRunSync()
    assertEquals(restored, false, "must NOT restore over a valid-JSON (field-incomplete) config")
    assert(os.read(configPath).contains("trust"), "trust must survive; stale {} snapshot must not clobber it")

  test("restoreLatest DOES restore from snapshot when config is invalid JSON"):
    os.write.over(configPath, "this is not json {")
    val snap = home / "backups" / s"nebflow.json.${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}"
    os.makeDir.all(home / "backups")
    os.write.over(snap, "{}")
    val restored = ConfigSnapshot.restoreLatest().unsafeRunSync()
    assertEquals(restored, true, "invalid-JSON config must be restored from the latest valid snapshot")
    assertEquals(os.read(configPath).trim, "{}", "config must be rolled back to the valid snapshot")

  // ── 端到端：冷启动 seeded trust → 重启 → 仍 trusted ─────────────────

  test("cold-start seeded trust survives restart (stale {} backup must not clobber it)"):
    // 模拟冷启动：Main 先写 {}，configRef init 在种子前 save 出 {} 备份
    os.write.over(configPath, "{}")
    ConfigSnapshot.save().unsafeRunSync()
    // 种子：fresh home → 完整播种 → approve 写 plugins.trust（无 llm 节）
    os.remove.all(home / "projects")
    os.remove.all(home / "agents")
    os.remove.all(home / "plugins")
    os.remove.all(home / ".seed-state.json")
    os.makeDir.all(home / "plugins")
    SeedService.ensureSeeded().unsafeRunSync()
    // 种子后的磁盘形态：trust 已写、llm 节缺席
    val diskAfterSeed = os.read(configPath)
    assert(diskAfterSeed.contains("trust"), "seed must write plugins.trust on disk")
    assert(!diskAfterSeed.contains("\"llm\""), "seeded config must have no llm block (intermediate state)")

    // 模拟重启 configRef 初始化（与 GatewayMain 相同的 try/catch 恢复路径）
    val cfg = simulateRestart()

    // 修复后：loadServiceConfig 不抛 → 未触发 restoreLatest → 信任仍在磁盘
    val diskAfterRestart = os.read(configPath)
    assert(diskAfterRestart.contains("trust"), "trust must survive the restart (fix root cause)")
    assert(!diskAfterRestart.trim.isEmpty && diskAfterRestart.trim != "{}", "config must not be clobbered to {}")
    // 插件仍 trusted（默认插件集 = {visual-report, slideblocks}，manifest 收缩后 explorer-toolkit 不入种子）
    assert(PluginRegistry.resolve("visual-report").unsafeRunSync().isRight,
      "seeded plugin must still resolve as trusted after restart")
    // 解码出的运行时配置 = 默认 llm（无 provider）
    assertEquals(cfg.llm.providers, Map.empty[String, ProviderConfig])

  // ── 接受标准②：用户手动 approve / revoke 同样跨重启持久 ────────────────

  test("user manual approve persists across restart (still trusted)"):
    PluginRegistry.invalidateCache()
    mkPlugin("manual-a")
    val r = PluginRegistry.approve("manual-a").unsafeRunSync()
    assert(r.isRight, s"manual approve must succeed: $r")
    // 手工写入无 llm 节的 nebflow.json 场景也成立：approve 会保留既有 trust、
    // 不改写 llm 节。再走一次重启加载路径。
    simulateRestart()
    PluginRegistry.invalidateCache()
    assert(PluginRegistry.resolve("manual-a").unsafeRunSync().isRight,
      "manually approved plugin must still be trusted after restart")
    assert(os.read(configPath).contains("manual-a"), "trust record must be on disk after restart")

  test("user manual revoke persists across restart (still untrusted)"):
    PluginRegistry.invalidateCache()
    mkPlugin("manual-b")
    assert(PluginRegistry.approve("manual-b").unsafeRunSync().isRight, "approve must succeed")
    val rv = PluginRegistry.revoke("manual-b").unsafeRunSync()
    assert(rv.isRight, s"manual revoke must succeed: $rv")
    assert(!os.read(configPath).contains("manual-b"), "trust record must be gone after revoke (on disk)")
    // 重启加载路径不再触发恢复，revoke 状态保持
    simulateRestart()
    PluginRegistry.invalidateCache()
    assert(PluginRegistry.resolve("manual-b").unsafeRunSync().isLeft,
      "revoked plugin must stay untrusted after restart")
    assert(!os.read(configPath).contains("manual-b"), "revoked trust record must NOT reappear after restart")

end ConfigTrustPersistSpec
