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

  test("AgentLibrary.loadAll loads builtins from classpath") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.keySet.contains("Nebula"), s"Should contain Nebula: ${result.keySet}")
    assert(result.keySet.size == 1, s"Should have exactly 1 builtin: ${result.keySet}")
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

end AgentDefSpec
