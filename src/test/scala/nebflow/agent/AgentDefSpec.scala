package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite

class AgentDefSpec extends CatsEffectSuite:

  test("loadAll on an empty disk: no Nebula (no code fallback), loud gap — self-heal is SeedService's duty"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    // govmemory 批（2026-09-25）：代码 fallback（原 Seeds.Nebula）随 Seeds 整体退役。
    // 「Nebula 恒在」的保证点移到 SeedService.ensureSeeded 的缺失自愈（boot 装配点，
    // 先于 agent 装载）——该保证由 SeedAgentSelfHealSpec ①钉住；本条钉 loadAll 自身
    // 的新契约：空盘 ⇒ 空图 + 响亮 WARN（缺口可见，不静默伪造定义）。
    assert(!result.contains("Nebula"), "empty disk must not fabricate a Nebula def (code fallback retired)")
    // The fallback def declared no tools while it existed; the same stays true for
    // any disk-seeded Nebula def (converged name ⇒ tool surface is mechanism-fixed,
    // AgentCore.NebulaOrchestrationTools auto-injected; any `tools` value grants
    // nothing — see ConvergedAgentNames in AgentCore).

  test("loadAll reads agents from disk agent.json"):
    val tmpDir = os.temp.dir()
    val customDir = tmpDir / "CustomAgent"
    os.makeDir.all(customDir)
    os.write.over(
      customDir / "agent.json",
      Json
        .obj(
          "name" -> "CustomAgent".asJson,
          "description" -> "A test agent".asJson,
          "tools" -> List("Read", "Grep").asJson
        )
        .noSpaces
    )
    os.write.over(customDir / "system.md", "You are a custom agent.")
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("CustomAgent"), "Custom agent should be loaded from disk")
    assertEquals(result("CustomAgent").tools, List("Read", "Grep"))
    assertEquals(result("CustomAgent").systemPrompt, "You are a custom agent.")

  // Nebula 首次落盘 seeding 属 SeedService（manifest 种子树）面：
  // 就位 / 幂等 / 零覆盖由 SeedServiceSpec 与 SeedAgentSelfHealSpec 钉住，
  // 本 spec 只保留「盘上定义 vs 代码 fallback」的装载语义。

  test("system.md overrides the on-disk fallback prompt"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    // Simulate a seeded home: agent.json + system.md on disk (seeding itself is
    // SeedService's duty — see SeedServiceSpec / SeedAgentSelfHealSpec).
    val nb = tmpDir / "Nebula"
    os.makeDir.all(nb)
    os.write.over(nb / "agent.json", """{"name":"Nebula","description":"orchestrator"}""")
    os.write.over(nb / "system.md", "Seeded prompt for testing.")
    val seeded = lib.loadAll().unsafeRunSync()
    assertEquals(seeded("Nebula").systemPrompt, "Seeded prompt for testing.")
    // User edits system.md → next load picks the override
    os.write.over(nb / "system.md", "Custom prompt for testing.")
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result("Nebula").systemPrompt, "Custom prompt for testing.")

  test("updateSystemPrompt writes system.md"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.updateSystemPrompt("Nebula", "New prompt.").unsafeRunSync()
    val md = os.read(tmpDir / "Nebula" / "system.md")
    assertEquals(md, "New prompt.")

  test("Nebula dir with a corrupted agent.json: no code fallback — the gap is visible until the seed self-heal"):
    val tmpDir = os.temp.dir()
    val nebulaDir = tmpDir / "Nebula"
    os.makeDir.all(nebulaDir)
    os.write.over(nebulaDir / "agent.json", "{invalid json}")
    os.write.over(nebulaDir / "system.md", "Prompt from disk.")
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    // govmemory 批（2026-09-25）：代码 fallback 退役 ⇒ 损坏文件不伪造定义（缺口由
    // 启动期 SeedService 缺失自愈修复；损坏 ≠ 缺失，故本形态待人工/重装面介入）。
    assert(!result.contains("Nebula"),
      "corrupted agent.json must not fabricate a Nebula def (no silent substitution)")

  test("multiple custom agents loaded from disk"):
    val tmpDir = os.temp.dir()
    for name <- List("AgentA", "AgentB", "AgentC") do
      val dir = tmpDir / name
      os.makeDir.all(dir)
      os.write.over(
        dir / "agent.json",
        Json
          .obj(
            "name" -> name.asJson,
            "tools" -> List("Read").asJson
          )
          .noSpaces
      )
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("AgentA"))
    assert(result.contains("AgentB"))
    assert(result.contains("AgentC"))
    assert(!result.contains("Nebula"), "no dir, no Nebula — seeding is SeedService's duty (not loadAll's)")

end AgentDefSpec
