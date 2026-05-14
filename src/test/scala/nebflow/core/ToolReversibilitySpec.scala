package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

class ToolReversibilitySpec extends CatsEffectSuite:

  // --- Always reversible tools ---

  test("auto-approve Read") {
    assertEquals(ToolReversibility.isReversible("Read", JsonObject.empty), true)
  }

  test("auto-approve Edit and Write") {
    assertEquals(ToolReversibility.isReversible("Edit", JsonObject.empty), true)
    assertEquals(ToolReversibility.isReversible("Write", JsonObject.empty), true)
  }

  test("auto-approve Glob, Grep, WebSearch, WebFetch") {
    assertEquals(ToolReversibility.isReversible("Glob", JsonObject.empty), true)
    assertEquals(ToolReversibility.isReversible("Grep", JsonObject.empty), true)
    assertEquals(ToolReversibility.isReversible("WebSearch", JsonObject.empty), true)
    assertEquals(ToolReversibility.isReversible("WebFetch", JsonObject.empty), true)
  }

  // --- Bash ---

  test("auto-approve safe Bash commands") {
    val input = JsonObject("command" -> "ls -la".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), true)
  }

  test("auto-approve git status") {
    val input = JsonObject("command" -> "git status".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), true)
  }

  test("require approval for rm -rf") {
    val input = JsonObject("command" -> "rm -rf /tmp/test".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), false)
  }

  test("require approval for git push --force") {
    val input = JsonObject("command" -> "git push --force origin main".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), false)
  }

  test("require approval for DROP TABLE") {
    val input = JsonObject("command" -> """psql -c "DROP TABLE users;"""".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), false)
  }

  test("require approval for npm publish") {
    val input = JsonObject("command" -> "npm publish".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), false)
  }

  test("require approval for kubectl delete") {
    val input = JsonObject("command" -> "kubectl delete namespace production".asJson)
    assertEquals(ToolReversibility.isReversible("Bash", input), false)
  }

  // --- Curl ---

  test("auto-approve Curl GET") {
    val input = JsonObject("url" -> "https://example.com".asJson, "method" -> "GET".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input), true)
  }

  test("auto-approve Curl HEAD and OPTIONS") {
    val head = JsonObject("url" -> "https://example.com".asJson, "method" -> "HEAD".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", head), true)
    val opts = JsonObject("url" -> "https://example.com".asJson, "method" -> "OPTIONS".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", opts), true)
  }

  test("auto-approve Curl with no method (defaults to GET)") {
    val input = JsonObject("url" -> "https://example.com".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input), true)
  }

  test("require approval for Curl POST") {
    val input = JsonObject("url" -> "https://example.com".asJson, "method" -> "POST".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", input), false)
  }

  test("require approval for Curl PUT and DELETE") {
    val put = JsonObject("url" -> "https://example.com".asJson, "method" -> "PUT".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", put), false)
    val del = JsonObject("url" -> "https://example.com".asJson, "method" -> "DELETE".asJson)
    assertEquals(ToolReversibility.isReversible("Curl", del), false)
  }

  // --- Unknown tools ---

  test("require approval for unknown tools") {
    assertEquals(ToolReversibility.isReversible("SomeNewTool", JsonObject.empty), false)
  }

end ToolReversibilitySpec
