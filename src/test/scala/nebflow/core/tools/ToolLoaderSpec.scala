package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import io.circe.Json
import io.circe.syntax.*

class ToolLoaderSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-tool-loader"
  PathUtil.setDataRoot(tempRoot)

  private def toolsDir = tempRoot / "tools"

  /** Reset tools dir and registry state before each test. */
  private def resetState(): IO[Unit] =
    IO.delay {
      if os.exists(toolsDir) then os.remove.all(toolsDir)
      os.makeDir.all(toolsDir)
    } *> ToolLoader.reload().void

  private def writeTool(name: String, toolName: String = null): Unit =
    val actualName = if toolName == null then name else toolName
    val config = Json.obj(
      "name" -> Json.fromString(actualName),
      "description" -> Json.fromString(s"Test tool $actualName"),
      "command" -> Json.fromString("echo hello"),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj()
      )
    )
    os.write(toolsDir / s"$name.json", config.noSpaces)

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
