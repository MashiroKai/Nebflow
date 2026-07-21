package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite

class AgentDefSpec extends CatsEffectSuite:

  test("loadAll returns Nebula even on empty disk (code fallback)"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("Nebula"), "Nebula must always exist")
    assert(result("Nebula").tools == List("*"))

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

  test("seedDefaults writes agent.json AND system.md"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    assert(os.exists(tmpDir / "Nebula" / "agent.json"), "agent.json should be seeded")
    assert(os.exists(tmpDir / "Nebula" / "system.md"), "system.md should be seeded")
    assert(os.exists(tmpDir / "Explorer" / "agent.json"), "Explorer agent.json should be seeded")
    assert(os.exists(tmpDir / "Coder" / "agent.json"), "Coder agent.json should be seeded")

  test("seedDefaults does not overwrite existing agent.json"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    // First seed
    lib.seedDefaults().unsafeRunSync()
    // User customizes agent.json
    os.write.over(
      tmpDir / "Nebula" / "agent.json",
      Json
        .obj(
          "name" -> "Nebula".asJson,
          "tools" -> List("Read").asJson
        )
        .noSpaces
    )
    // Second seed — should NOT overwrite
    lib.seedDefaults().unsafeRunSync()
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result("Nebula").tools, List("Read"), "User customization should be preserved")

  test("seedDefaults is idempotent for system.md"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    val firstMd = os.read(tmpDir / "Nebula" / "system.md")
    lib.seedDefaults().unsafeRunSync()
    val secondMd = os.read(tmpDir / "Nebula" / "system.md")
    assertEquals(firstMd, secondMd)

  test("system.md overrides seeded prompt"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    os.write.over(tmpDir / "Nebula" / "system.md", "Custom prompt for testing.")
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result("Nebula").systemPrompt, "Custom prompt for testing.")

  test("updateSystemPrompt writes system.md"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.updateSystemPrompt("Nebula", "New prompt.").unsafeRunSync()
    val md = os.read(tmpDir / "Nebula" / "system.md")
    assertEquals(md, "New prompt.")

  test("Nebula fallback when agent.json is corrupted"):
    val tmpDir = os.temp.dir()
    val nebulaDir = tmpDir / "Nebula"
    os.makeDir.all(nebulaDir)
    os.write.over(nebulaDir / "agent.json", "{invalid json}")
    os.write.over(nebulaDir / "system.md", "Prompt from disk.")
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("Nebula"), "Nebula should fall back to code definition")
    // Corrupted agent.json → skip disk, use code fallback (with code prompt)
    assert(result("Nebula").systemPrompt.nonEmpty)

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
    assert(result.contains("Nebula"), "Nebula must coexist with custom agents")

end AgentDefSpec
