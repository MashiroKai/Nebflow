package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import munit.CatsEffectSuite
import nebflow.shared.Defaults

class AgentDefSpec extends CatsEffectSuite:

  test("AgentDef decodes with all new fields") {
    val json = """{
      "name": "db-assistant",
      "description": "Database helper",
      "modelRoute": "default",
      "contextWindow": 128000,
      "maxTokens": 16384,
      "tools": [],
      "subagents": [],
      "keepAlive": false,
      "systemPrompt": "",
      "configPath": "",
      "mcp": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-postgres"],
        "env": { "DATABASE_URL": "postgresql://localhost/test" }
      },
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
    assert(defn.mcp.isDefined)
    assertEquals(defn.mcp.get.command, Some("npx"))
    assertEquals(defn.mcp.get.args, Some(List("-y", "@modelcontextprotocol/server-postgres")))
    assertEquals(defn.mcp.get.env, Some(Map("DATABASE_URL" -> "postgresql://localhost/test")))
    assert(defn.frontend.isDefined)
    assertEquals(defn.frontend.get.scripts, List("frontend/card.js"))
    assertEquals(defn.frontend.get.styles, List("frontend/card.css"))
    assertEquals(defn.avatar, Some("🗃️"))
    assertEquals(defn.displayName, Some("DB Assistant"))
  }

  test("AgentDef decodes without new optional fields") {
    val json = """{"name":"simple","description":"Simple agent","modelRoute":"default","contextWindow":128000,"maxTokens":16384,"tools":["Read"],"subagents":[],"keepAlive":false,"systemPrompt":"","configPath":""}"""
    val result = decode[AgentDef](json)
    assert(result.isRight, s"decode failed: $result")
    val defn = result.toOption.get
    assertEquals(defn.name, "simple")
    assertEquals(defn.tools, List("Read"))
    assertEquals(defn.mcp, None)
    assertEquals(defn.frontend, None)
    assertEquals(defn.avatar, None)
    assertEquals(defn.displayName, None)
  }

  test("AgentMcpConfig converts to McpServerConfig") {
    val cfg = AgentMcpConfig(
      command = Some("node"),
      args = Some(List("server.js")),
      env = Some(Map("KEY" -> "val")),
      url = None,
      headers = None
    )
    val serverCfg = AgentMcpConfig.toMcpServerConfig(cfg)
    assertEquals(serverCfg.command, Some("node"))
    assertEquals(serverCfg.args, Some(List("server.js")))
    assertEquals(serverCfg.env, Some(Map("KEY" -> "val")))
    assertEquals(serverCfg.url, None)
    assertEquals(serverCfg.headers, None)
  }

  test("AgentMcpConfig with url-based transport") {
    val cfg = AgentMcpConfig(
      url = Some("http://localhost:8080/mcp"),
      headers = Some(Map("Authorization" -> "Bearer token"))
    )
    val serverCfg = AgentMcpConfig.toMcpServerConfig(cfg)
    assertEquals(serverCfg.url, Some("http://localhost:8080/mcp"))
    assertEquals(serverCfg.headers, Some(Map("Authorization" -> "Bearer token")))
    assertEquals(serverCfg.command, None)
  }

  test("FrontendConfig decodes correctly") {
    val json = """{"scripts": ["a.js", "b.js"], "styles": ["c.css"]}"""
    val result = decode[FrontendConfig](json)
    assert(result.isRight, s"decode failed: $result")
    val fe = result.toOption.get
    assertEquals(fe.scripts, List("a.js", "b.js"))
    assertEquals(fe.styles, List("c.css"))
  }

  test("AgentLibrary.loadAll works with no agents directory") {
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None, None)
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result.keySet, Set("context-manage", "Nebula", "Ask"))
  }

  test("AgentLibrary.loadAll loads agent with mcp config from disk") {
    val dir = os.temp.dir()
    val agentDir = dir / "test-agent"
    os.makeDir.all(agentDir)
    // agent.json must include all non-Option fields for circe decode to succeed
    os.write(agentDir / "agent.json",
      """{"name":"test-agent","description":"Test","modelRoute":"default","contextWindow":128000,"maxTokens":16384,"tools":[],"subagents":[],"keepAlive":false,"systemPrompt":"","configPath":"","mcp":{"command":"echo"}}""")
    os.write(agentDir / "system.md", "You are a test agent.")
    val lib = new AgentLibrary(dir, None, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.keySet.contains("test-agent"), s"keys: ${result.keySet}")
    assert(result("test-agent").mcp.isDefined, "mcp should be defined")
    assertEquals(result("test-agent").mcp.get.command, Some("echo"))
    assertEquals(result("test-agent").systemPrompt, "You are a test agent.")
  }

end AgentDefSpec
