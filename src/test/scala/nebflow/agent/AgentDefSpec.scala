package nebflow.agent

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

class AgentDefSpec extends CatsEffectSuite:

  test("AgentLibrary.loadAll returns all builtin agents") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.keySet.contains("Nebula"), s"Should contain Nebula: ${result.keySet}")
    assert(result.keySet.contains("Jarvis"), s"Should contain Jarvis: ${result.keySet}")
    assert(result.keySet.size >= 5, s"Should have at least 5 agents: ${result.keySet}")
  }

  test("AgentLibrary.loadAll populates tools from code") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    val nebula = result("Nebula")
    assertEquals(nebula.tools, List("*"))
    val jarvis = result("Jarvis")
    assert(jarvis.tools.contains("Delegate"))
    assert(jarvis.tools.contains("Mail"))
    assert(!jarvis.tools.contains("Write"))
  }

  test("AgentLibrary.loadAll reads systemPrompt from defaults") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    val nebula = result("Nebula")
    assert(nebula.systemPrompt.nonEmpty, "systemPrompt should be populated from defaults")
  }

  test("AgentLibrary.seedDefaults writes system.md for editing") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    val nebulaMd = tmpDir / "Nebula" / "system.md"
    assert(os.exists(nebulaMd), "system.md should be seeded")
    assert(os.read(nebulaMd).nonEmpty, "system.md should have content")
    // No agent.json should exist
    val nebulaJson = tmpDir / "Nebula" / "agent.json"
    assert(!os.exists(nebulaJson), "agent.json should NOT be seeded")
  }

  test("AgentLibrary.seedDefaults is idempotent") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    val firstMd = os.read(tmpDir / "Nebula" / "system.md")
    lib.seedDefaults().unsafeRunSync()
    val secondMd = os.read(tmpDir / "Nebula" / "system.md")
    assertEquals(firstMd, secondMd)
  }

  test("User-edited system.md overrides hardcoded prompt") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    // User edits system.md
    val nebulaDir = tmpDir / "Nebula"
    os.write.over(nebulaDir / "system.md", "Custom prompt for testing.")
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result("Nebula").systemPrompt, "Custom prompt for testing.")
  }

  test("builtinTools map covers all defaults") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val tools = lib.builtinTools
    assert(tools.contains("Nebula"))
    assert(tools.contains("Jarvis"))
    assert(tools("Nebula") == List("*"))
  }

  test("toolsFor returns wildcard for unknown agents") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    assertEquals(lib.toolsFor("Nebula"), List("*"))
    assertEquals(lib.toolsFor("UnknownAgent"), List("*"))
  }

  test("updateSystemPrompt writes system.md") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.updateSystemPrompt("Nebula", "New prompt.").unsafeRunSync()
    val md = os.read(tmpDir / "Nebula" / "system.md")
    assertEquals(md, "New prompt.")
  }

end AgentDefSpec
