package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

class ToolLoaderSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-tool-loader"
  PathUtil.setDataRoot(tempRoot)

  private def toolsDir = tempRoot / "tools"

  /** Reset all layer dirs and registry state before each test. */
  private def resetState(): IO[Unit] =
    IO.delay {
      List("tools", "teams", "flows", "agents").foreach { sub =>
        val dir = tempRoot / sub
        if os.exists(dir) then os.remove.all(dir)
      }
      os.makeDir.all(toolsDir)
    } *> ToolLoader.reload().void

  private def writeToolConfig(
    dir: os.Path,
    file: String,
    toolName: String,
    description: String,
    command: String = "echo hello"
  ): Unit =
    val config = Json.obj(
      "name" -> Json.fromString(toolName),
      "description" -> Json.fromString(description),
      "command" -> Json.fromString(command),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj()
      )
    )
    os.makeDir.all(dir)
    os.write(dir / s"$file.json", config.noSpaces)

  end writeToolConfig

  private def writeTool(name: String, toolName: String = null): Unit =
    val actualName = if toolName == null then name else toolName
    writeToolConfig(toolsDir, name, actualName, s"Test tool $actualName")

  test("reload registers new tool from disk"):
    for
      _ <- resetState()
      _ <- IO(writeTool("mytool"))
      _ <- ToolLoader.reload()
      toolMap = ToolRegistry.TOOL_MAP
    yield assert(toolMap.contains("mytool"), "Tool should be registered after reload")

  test("reload picks up modified tool name"):
    for
      _ <- resetState()
      // Initial load with name "tool-v1"
      _ <- IO(writeTool("mytool", "tool-v1"))
      _ <- ToolLoader.reload()
      map1 = ToolRegistry.TOOL_MAP
      _ = assert(map1.contains("tool-v1"), "tool-v1 should be registered")
      _ = assert(!map1.contains("tool-v2"), "tool-v2 should not exist yet")
      // Modify: change name inside JSON
      _ <- IO {
        os.remove(toolsDir / "mytool.json")
        writeTool("mytool", "tool-v2")
      }
      _ <- ToolLoader.reload()
      map2 = ToolRegistry.TOOL_MAP
    yield
      assert(!map2.contains("tool-v1"), "tool-v1 should be unregistered after name change")
      assert(map2.contains("tool-v2"), "tool-v2 should be registered after name change")

  test("reload unregisters tool when JSON file is deleted"):
    for
      _ <- resetState()
      _ <- IO {
        writeTool("tool-a")
        writeTool("tool-b")
      }
      _ <- ToolLoader.reload()
      map1 = ToolRegistry.TOOL_MAP
      _ = assert(map1.contains("tool-a"), "tool-a should be registered")
      _ = assert(map1.contains("tool-b"), "tool-b should be registered")
      // Delete one tool
      _ <- IO(os.remove(toolsDir / "tool-a.json"))
      _ <- ToolLoader.reload()
      map2 = ToolRegistry.TOOL_MAP
    yield
      assert(!map2.contains("tool-a"), "tool-a should be unregistered after file deletion")
      assert(map2.contains("tool-b"), "tool-b should still be registered")

  test("reload skips invalid JSON without crashing"):
    for
      _ <- resetState()
      _ <- IO {
        writeTool("good-tool")
        os.write(toolsDir / "bad-tool.json", "{ invalid json }")
      }
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("good-tool"), "Valid tool should be registered")
      assert(!map.contains("bad-tool"), "Invalid tool should be skipped")

  test("reload does not overwrite built-in tools"):
    for
      _ <- resetState()
      _ <- IO(writeTool("fake-builtin", "Bash")) // name conflicts with built-in
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
      bashTool = map.get("Bash")
    yield assert(bashTool.isInstanceOf[Some[?]], "Built-in Bash should not be overwritten")

  test("reload handles empty tools directory"):
    for
      _ <- resetState()
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield assert(map.contains("Read"), "Built-in tools should still be present")

  // --- Three-layer loading ---

  test("reload loads tools from team layer"):
    for
      _ <- resetState()
      _ <- IO(writeToolConfig(tempRoot / "teams" / "myteam" / "tools", "team-tool", "team-tool", "team-description"))
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("team-tool"), "Team-layer tool should be registered")
      assertEquals(map("team-tool").description, "team-description")

  test("reload loads tools from flow layer"):
    for
      _ <- resetState()
      _ <- IO(writeToolConfig(tempRoot / "flows" / "myflow" / "tools", "flow-tool", "flow-tool", "flow-description"))
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("flow-tool"), "Flow-layer tool should be registered")
      assertEquals(map("flow-tool").description, "flow-description")

  test("conflict priority: flow > team > global"):
    for
      _ <- resetState()
      _ <- IO {
        writeToolConfig(toolsDir, "dup", "dup", "global-desc")
        writeToolConfig(tempRoot / "teams" / "alpha" / "tools", "dup", "dup", "team-desc")
        writeToolConfig(tempRoot / "flows" / "beta" / "tools", "dup", "dup", "flow-desc")
      }
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("dup"))
      assertEquals(map("dup").description, "flow-desc", "Flow layer should override team and global")

  test("conflict priority: team > global"):
    for
      _ <- resetState()
      _ <- IO {
        writeToolConfig(toolsDir, "dup", "dup", "global-desc")
        writeToolConfig(tempRoot / "teams" / "alpha" / "tools", "dup", "dup", "team-desc")
      }
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("dup"))
      assertEquals(map("dup").description, "team-desc", "Team layer should override global")

  test("team-layer tool cannot override built-in"):
    for
      _ <- resetState()
      _ <- IO(writeToolConfig(tempRoot / "teams" / "alpha" / "tools", "fake", "Bash", "team-bash"))
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("Bash"))
      assert(!map("Bash").isInstanceOf[ScriptTool], "Built-in Bash must not be replaced by team tool")

  test("tools unregister cleanly across layers"):
    for
      _ <- resetState()
      _ <- IO {
        writeToolConfig(tempRoot / "teams" / "myteam" / "tools", "team-tool", "team-tool", "team-desc")
        writeToolConfig(tempRoot / "flows" / "myflow" / "tools", "flow-tool", "flow-tool", "flow-desc")
      }
      _ <- ToolLoader.reload()
      map1 = ToolRegistry.TOOL_MAP
      _ = assert(map1.contains("team-tool") && map1.contains("flow-tool"), "Both layer tools should load")
      // Remove team tools dir entirely
      _ <- IO(os.remove.all(tempRoot / "teams" / "myteam"))
      _ <- ToolLoader.reload()
      map2 = ToolRegistry.TOOL_MAP
    yield
      assert(!map2.contains("team-tool"), "Team tool should be unregistered after dir removal")
      assert(map2.contains("flow-tool"), "Flow tool should survive team dir removal")

  // --- $TOOL_DIR environment variable ---

  test("TOOL_DIR env var points at the tool config directory"):
    for
      _ <- resetState()
      _ <- IO(writeToolConfig(toolsDir, "echo-tool", "echo-tool", "echo", """printf '%s' "$TOOL_DIR""""))
      _ <- ToolLoader.reload()
      tool = ToolRegistry.TOOL_MAP("echo-tool")
      result <- tool.call(JsonObject.empty, ToolContext(projectRoot = tempRoot.toString))
    yield assertEquals(result, Right[ToolError, String](toolsDir.toString))

  test("TOOL_DIR is set per-layer for team tools"):
    for
      _ <- resetState()
      teamTools = tempRoot / "teams" / "myteam" / "tools"
      _ <- IO(writeToolConfig(teamTools, "echo-team", "echo-team", "echo", """printf '%s' "$TOOL_DIR""""))
      _ <- ToolLoader.reload()
      tool = ToolRegistry.TOOL_MAP("echo-team")
      result <- tool.call(JsonObject.empty, ToolContext(projectRoot = tempRoot.toString))
    yield assertEquals(result, Right[ToolError, String](teamTools.toString))

  // --- Agent directory tools (three-layer agent dirs) ---

  test("reload registers tools from global agent tools/ subfolders"):
    for
      _ <- resetState()
      _ <- IO(writeToolConfig(tempRoot / "agents" / "myagent" / "tools", "agent-tool", "agent-tool", "agent-desc"))
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("agent-tool"), "Agent-dir tool should be registered")
      assertEquals(map("agent-tool").description, "agent-desc")

  test("reload registers tools from nested team/flow agent tools/ subfolders"):
    for
      _ <- resetState()
      _ <- IO {
        writeToolConfig(
          tempRoot / "teams" / "myteam" / "agents" / "helper" / "tools",
          "team-agent-tool",
          "team-agent-tool",
          "team-agent-desc"
        )
        writeToolConfig(
          tempRoot / "flows" / "myflow" / "agents" / "worker" / "tools",
          "flow-agent-tool",
          "flow-agent-tool",
          "flow-agent-desc"
        )
      }
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield
      assert(map.contains("team-agent-tool"), "Team agent-dir tool should be registered")
      assert(map.contains("flow-agent-tool"), "Flow agent-dir tool should be registered")

  test("conflict priority: team scope > agent dir > global"):
    for
      _ <- resetState()
      _ <- IO {
        writeToolConfig(toolsDir, "dup", "dup", "global-desc")
        writeToolConfig(tempRoot / "agents" / "myagent" / "tools", "dup", "dup", "agent-desc")
        writeToolConfig(tempRoot / "teams" / "alpha" / "tools", "dup", "dup", "team-desc")
      }
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield assertEquals(map("dup").description, "team-desc", "Team scope tool should override agent-dir tool")

  test("$TOOL_DIR is resolved to the config directory at load time"):
    for
      _ <- resetState()
      agentTools = tempRoot / "agents" / "myagent" / "tools"
      _ <- IO(writeToolConfig(agentTools, "deploy", "deploy", "deploy", "node $TOOL_DIR/deploy.cjs"))
      loaded <- ToolLoader.loadAll()
      cfg = loaded.collectFirst { case (c, _) if c.name == "deploy" => c }.get
    yield assertEquals(
      cfg.command,
      s"node ${agentTools.toString}/deploy.cjs",
      "$TOOL_DIR should be replaced with the absolute tools dir"
    )

  test("global tools still work with $TOOL_DIR replacement"):
    for
      _ <- resetState()
      _ <- IO(writeToolConfig(toolsDir, "g-tool", "g-tool", "echo", "echo $TOOL_DIR"))
      loaded <- ToolLoader.loadAll()
      cfg = loaded.collectFirst { case (c, _) if c.name == "g-tool" => c }.get
    yield assertEquals(
      cfg.command,
      s"echo ${toolsDir.toString}",
      "Global tool $TOOL_DIR should resolve to the global tools dir"
    )

  // ============================================================
  // Subdirectory layout: tools/<name>/tool.json
  // ============================================================

  private def writeSubDirTool(
    parentDir: os.Path,
    subDirName: String,
    toolName: String,
    command: String = "echo hello"
  ): Unit =
    val subDir = parentDir / subDirName
    os.makeDir.all(subDir)
    val config = Json.obj(
      "name" -> Json.fromString(toolName),
      "description" -> Json.fromString(s"Subdir tool $toolName"),
      "command" -> Json.fromString(command),
      "inputSchema" -> Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj())
    )
    os.write(subDir / "tool.json", config.noSpaces)

  end writeSubDirTool

  test("subdirectory layout: tools/<name>/tool.json is loaded"):
    for
      _ <- resetState()
      _ <- IO(writeSubDirTool(toolsDir, "issue", "issue"))
      _ <- ToolLoader.reload()
      map = ToolRegistry.TOOL_MAP
    yield assert(map.contains("issue"), "subdirectory tool should be registered")

  test("subdirectory layout: $TOOL_DIR resolves to the subdirectory, not the parent"):
    for
      _ <- resetState()
      _ <- IO(writeSubDirTool(toolsDir, "issue", "issue", "bash $TOOL_DIR/issue.sh"))
      loaded <- ToolLoader.loadAll()
      cfg = loaded.collectFirst { case (c, _) if c.name == "issue" => c }.get
    yield assertEquals(
      cfg.command,
      s"bash ${toolsDir.toString}/issue/issue.sh",
      "$TOOL_DIR should resolve to the subdirectory path"
    )

  test("flat and subdirectory layouts coexist"):
    for
      _ <- resetState()
      _ <- IO {
        writeToolConfig(toolsDir, "flat-tool", "flat-tool", "flat tool")
        writeSubDirTool(toolsDir, "sub-tool", "sub-tool", "sub tool")
      }
      loaded <- ToolLoader.loadAll()
      names = loaded.map(_._1.name).toSet
    yield
      assert(names.contains("flat-tool"), "flat layout tool loaded")
      assert(names.contains("sub-tool"), "subdirectory layout tool loaded")

  test("subdirectory layout: invalid tool.json is skipped, valid ones kept"):
    for
      _ <- resetState()
      _ <- IO {
        writeSubDirTool(toolsDir, "good", "good")
        // Write invalid JSON to a subdir
        val badDir = toolsDir / "bad"
        os.makeDir.all(badDir)
        os.write(badDir / "tool.json", "{ invalid json }")
      }
      loaded <- ToolLoader.loadAll()
      names = loaded.map(_._1.name).toSet
    yield
      assert(names.contains("good"), "valid subdir tool kept")
      assert(!names.contains("bad"), "invalid subdir tool skipped")

  test("subdirectory layout in agent dir: agents/<a>/tools/<name>/tool.json"):
    for
      _ <- resetState()
      agentTools = tempRoot / "agents" / "myagent" / "tools"
      _ <- IO(writeSubDirTool(agentTools, "deploy", "deploy", "node $TOOL_DIR/deploy.cjs"))
      loaded <- ToolLoader.loadAll()
      cfg = loaded.collectFirst { case (c, _) if c.name == "deploy" => c }.get
    yield assertEquals(
      cfg.command,
      s"node ${agentTools.toString}/deploy/deploy.cjs",
      "$TOOL_DIR should resolve to the agent tool subdirectory"
    )
end ToolLoaderSpec
