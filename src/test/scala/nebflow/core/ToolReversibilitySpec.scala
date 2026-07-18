package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.BashTool
import munit.CatsEffectSuite

class ToolReversibilitySpec extends CatsEffectSuite:

  // --- confirm-edits mode (default, safest) ---

  test("confirm-edits: auto-approve Read, Glob, Grep") {
    assertEquals(ToolReversibility.isReversible("Read", JsonObject.empty, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("Glob", JsonObject.empty, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("Grep", JsonObject.empty, SafetyMode.ConfirmEdits), true)
  }

  test("confirm-edits: require confirmation for Write and Edit") {
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, SafetyMode.ConfirmEdits), false)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, SafetyMode.ConfirmEdits), false)
  }

  test("confirm-edits: auto-approve new/unknown tools (ScriptTool, MCP, etc.)") {
    assertEquals(ToolReversibility.isReversible("SomeNewTool", JsonObject.empty, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("mcp__server__tool", JsonObject.empty, SafetyMode.ConfirmEdits), true)
  }

  test("confirm-edits: auto-approve safe Bash") {
    val input = JsonObject("command" -> "ls -la".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input, SafetyMode.ConfirmEdits), true)
  }

  test("confirm-edits: require approval for dangerous Bash") {
    val input = JsonObject("command" -> "rm -rf /tmp/test".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input, SafetyMode.ConfirmEdits), false)
  }

  // --- auto-edits mode ---

  test("auto-edits: auto-approve Write and Edit") {
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, SafetyMode.AutoEdits), true)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, SafetyMode.AutoEdits), true)
  }

  test("auto-edits: still check Bash") {
    val safe = JsonObject("command" -> "ls -la".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", safe, SafetyMode.AutoEdits), true)
    val dangerous = JsonObject("command" -> "rm -rf /tmp/test".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", dangerous, SafetyMode.AutoEdits), false)
  }

  // --- auto-all mode ---

  test("auto-all: everything is reversible") {
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty, SafetyMode.AutoAll), true)
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty, SafetyMode.AutoAll), true)
    val dangerous = JsonObject("command" -> "rm -rf /".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", dangerous, SafetyMode.AutoAll), true)
    assertEquals(ToolReversibility.isReversible("SomeNewTool", JsonObject.empty, SafetyMode.AutoAll), true)
  }

  // --- Curl ---

  test("auto-approve Curl GET in all modes except auto-all still works") {
    val input = JsonObject("url" -> "https://example.com".asJson, "method" -> "GET".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input, SafetyMode.ConfirmEdits), true)
    assertEquals(ToolReversibility.isReversible("Curl", input, SafetyMode.AutoEdits), true)
  }

  test("require approval for Curl POST in confirm-edits") {
    val input = JsonObject("url" -> "https://example.com".asJson, "method" -> "POST".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input, SafetyMode.ConfirmEdits), false)
  }

  // --- BashTool.isDangerous (unchanged) ---

  test("isDangerous detects pkill") {
    assert(BashTool.isDangerous("pkill -f java"))
  }

  test("isDangerous detects git push --force") {
    assert(BashTool.isDangerous("git push --force origin main"))
  }

  test("isDangerous detects DROP TABLE") {
    assert(BashTool.isDangerous("""psql -c "DROP TABLE users;""""))
  }

  test("dangerLevel 3 for rm -rf /") {
    assertEquals(BashTool.dangerLevel("rm -rf /"), 3)
  }

  test("dangerLevel 0 for ls") {
    assertEquals(BashTool.dangerLevel("ls -la"), 0)
  }

end ToolReversibilitySpec
