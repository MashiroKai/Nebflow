package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import munit.CatsEffectSuite

class AgentDefSpec extends CatsEffectSuite:

  test("AgentDef decodes with all fields") {
    val json = """{
      "name": "db-assistant",
      "description": "Database helper",
      "tools": [],
      "mcpServers": ["postgres", "redis"],
      "avatar": "🗃️",
      "displayName": "DB Assistant"
    }"""
    val result = decode[AgentDef](json)
    assert(result.isRight, s"decode failed: $result")
    val defn = result.toOption.get
    assertEquals(defn.name, "db-assistant")
    assertEquals(defn.mcpServers, List("postgres", "redis"))
    assertEquals(defn.avatar, Some("🗃️"))
    assertEquals(defn.displayName, Some("DB Assistant"))
  }

  test("AgentDef decodes with minimal fields") {
    val json = """{"name":"simple","description":"Simple agent","tools":["Read"]}"""
    val result = decode[AgentDef](json)
    assert(result.isRight, s"decode failed: $result")
    val defn = result.toOption.get
    assertEquals(defn.name, "simple")
    assertEquals(defn.tools, List("Read"))
    assertEquals(defn.mcpServers, Nil)
    assertEquals(defn.avatar, None)
    assertEquals(defn.displayName, None)
  }

  test("AgentLibrary seedDefaults creates Nebula on disk") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    lib.seedDefaults().unsafeRunSync()
    val result = lib.loadAll().unsafeRunSync()
    assert(result.keySet.contains("Nebula"), s"Should contain Nebula: ${result.keySet}")
    assert(result.keySet.size == 1, s"Should have exactly 1 agent: ${result.keySet}")
    val nebula = result("Nebula")
    assert(nebula.configPath.nonEmpty, "configPath should be set")
    assert(nebula.systemPrompt.nonEmpty, "systemPrompt should be seeded")
  }

  test("AgentLibrary seedDefaults is idempotent") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    // Seed twice — second call should be a no-op
    lib.seedDefaults().unsafeRunSync()
    val first = lib.loadAll().unsafeRunSync()
    lib.refresh().unsafeRunSync()
    lib.seedDefaults().unsafeRunSync()
    val second = lib.loadAll().unsafeRunSync()
    assertEquals(first.keySet, second.keySet)
  }

  test("AgentLibrary.loadAll loads agent with mcp config from disk") {
    val dir = os.temp.dir()
    val agentDir = dir / "test-agent"
    os.makeDir.all(agentDir)
    os.write(agentDir / "agent.json", """{"name":"test-agent","description":"Test","tools":[],"mcpServers":["echo"]}""")
    os.write(agentDir / "system.md", "You are a test agent.")
    val lib = new AgentLibrary(dir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.keySet.contains("test-agent"), s"keys: ${result.keySet}")
    assertEquals(result("test-agent").mcpServers, List("echo"))
    assertEquals(result("test-agent").systemPrompt, "You are a test agent.")
  }

  test("User-edited agent.json is preserved across seedDefaults") {
    val dir = os.temp.dir()
    val lib = new AgentLibrary(dir, None)
    // First seed creates default Nebula
    lib.seedDefaults().unsafeRunSync()
    // User edits mcpServers
    val nebulaDir = dir / "Nebula"
    os.write.over(nebulaDir / "agent.json", """{"name":"Nebula","description":"Override","tools":["*"],"mcpServers":["my-mcp"],"displayName":"Nebula","avatar":"<i data-lucide=\"sparkles\"></i>"}""")
    lib.refresh().unsafeRunSync()
    // seedDefaults again should NOT overwrite user changes
    lib.seedDefaults().unsafeRunSync()
    lib.refresh().unsafeRunSync()
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result("Nebula").mcpServers, List("my-mcp"))
    assertEquals(result("Nebula").description, "Override")
  }

end AgentDefSpec
