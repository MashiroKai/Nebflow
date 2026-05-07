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
      "frontend": {
        "scripts": ["frontend/card.js"],
        "styles": ["frontend/card.css"]
      },
      "avatar": "🗃️",
      "displayName": "DB Assistant"
    }"""
    val result = decode[AgentDef](json)
    assert(result.isRight, s"decode failed: $result")
    val defn = result.toOption.get
    assertEquals(defn.name, "db-assistant")
    assertEquals(defn.mcpServers, List("postgres", "redis"))
    assert(defn.frontend.isDefined)
    assertEquals(defn.frontend.get.scripts, List("frontend/card.js"))
    assertEquals(defn.frontend.get.styles, List("frontend/card.css"))
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
    assertEquals(defn.frontend, None)
    assertEquals(defn.avatar, None)
    assertEquals(defn.displayName, None)
    // contextWindow / maxTokens are not in AgentDef — resolved at runtime from nebflow.json
  }

  test("FrontendConfig decodes correctly") {
    val json = """{"scripts": ["a.js", "b.js"], "styles": ["c.css"]}"""
    val result = decode[FrontendConfig](json)
    assert(result.isRight, s"decode failed: $result")
    val fe = result.toOption.get
    assertEquals(fe.scripts, List("a.js", "b.js"))
    assertEquals(fe.styles, List("c.css"))
  }

  test("AgentLibrary.loadAll loads builtins from classpath") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.keySet.contains("Nebula"), s"Should contain Nebula: ${result.keySet}")
    assert(result.keySet.contains("context-manage"), s"Should contain context-manage: ${result.keySet}")
    assert(result.keySet.contains("Ask"), s"Should contain Ask: ${result.keySet}")
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
