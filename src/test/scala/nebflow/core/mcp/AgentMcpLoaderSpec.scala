package nebflow.core.mcp

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

class AgentMcpLoaderSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-agent-mcp"
  PathUtil.setDataRoot(tempRoot)

  /** Remove the whole data root so each test starts clean. */
  private def resetRoot(): IO[Unit] =
    IO.delay {
      if os.exists(tempRoot) then os.remove.all(tempRoot)
    }

  private def writeAgentJson(agentDir: os.Path, name: String): Unit =
    os.makeDir.all(agentDir)
    os.write(agentDir / "agent.json", Json.obj("name" -> Json.fromString(name)).noSpaces)

  private def writeMcpConfig(mcpDir: os.Path, file: String): Unit =
    val cfg = Json.obj(
      "command" -> Json.fromString("npx"),
      "args" -> Json.arr(Json.fromString("-y"), Json.fromString(s"@server/$file")),
      "env" -> Json.obj("KEY" -> Json.fromString("val"))
    )
    os.makeDir.all(mcpDir)
    os.write(mcpDir / s"$file.json", cfg.noSpaces)

  test("scanAll finds global agent mcp servers (agents/<name>/tools/mcp)"):
    for
      _ <- resetRoot()
      _ <- IO {
        writeAgentJson(tempRoot / "agents" / "mydoc", "mydoc")
        writeMcpConfig(tempRoot / "agents" / "mydoc" / "tools" / "mcp", "docs")
      }
      servers <- AgentMcpLoader.scanAll()
    yield
      assertEquals(servers.size, 1)
      val (agentName, serverId, cfg) = servers.head
      assertEquals(agentName, "mydoc")
      assertEquals(serverId, "agent-mydoc-docs")
      assertEquals(cfg.command, Some("npx"))
      assertEquals(cfg.env, Some(Map("KEY" -> "val")))

  test("scanAll finds team and flow agent mcp servers (nested agents dirs)"):
    for
      _ <- resetRoot()
      _ <- IO {
        writeAgentJson(tempRoot / "teams" / "alpha" / "agents" / "helper", "helper")
        writeMcpConfig(tempRoot / "teams" / "alpha" / "agents" / "helper" / "tools" / "mcp", "tools-server")
        writeAgentJson(tempRoot / "flows" / "beta" / "agents" / "worker", "worker")
        writeMcpConfig(tempRoot / "flows" / "beta" / "agents" / "worker" / "tools" / "mcp", "flow-server")
      }
      servers <- AgentMcpLoader.scanAll()
    yield
      assertEquals(servers.size, 2)
      val ids = servers.map(_._2).toSet
      assertEquals(ids, Set("agent-helper-tools-server", "agent-worker-flow-server"))

  test("agent name is read from agent.json name field, not the directory name"):
    for
      _ <- resetRoot()
      _ <- IO {
        writeAgentJson(tempRoot / "agents" / "dir-a", "canonical-name")
        writeMcpConfig(tempRoot / "agents" / "dir-a" / "tools" / "mcp", "svc")
      }
      servers <- AgentMcpLoader.scanAll()
    yield
      assertEquals(servers.size, 1)
      val (agentName, serverId, _) = servers.head
      assertEquals(agentName, "canonical-name")
      assertEquals(serverId, "agent-canonical-name-svc")

  test("scanAll skips invalid mcp configs and keeps valid ones"):
    for
      _ <- resetRoot()
      _ <- IO {
        writeAgentJson(tempRoot / "agents" / "mydoc", "mydoc")
        writeMcpConfig(tempRoot / "agents" / "mydoc" / "tools" / "mcp", "good")
        os.write(
          tempRoot / "agents" / "mydoc" / "tools" / "mcp" / "bad.json",
          "{ invalid json }"
        )
      }
      servers <- AgentMcpLoader.scanAll()
    yield
      assertEquals(servers.size, 1)
      assertEquals(servers.head._2, "agent-mydoc-good")

  test("scanAll returns empty when no tools/mcp dirs exist (backward compatible)"):
    for
      _ <- resetRoot()
      _ <- IO(os.makeDir.all(tempRoot / "agents" / "plain"))
      servers <- AgentMcpLoader.scanAll()
    yield assertEquals(servers.size, 0)

  test("serverIdFor builds agent-<agent>-<server> id"):
    assertEquals(AgentMcpLoader.serverIdFor("docs", "tools"), "agent-docs-tools")
    assertEquals(AgentMcpLoader.serverIdFor("my-agent", "my-server"), "agent-my-agent-my-server")
end AgentMcpLoaderSpec
