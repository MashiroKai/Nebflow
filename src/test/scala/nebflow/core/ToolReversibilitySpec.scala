package nebflow.core

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.BashTool
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

  // --- BashTool.isDangerous ---

  test("isDangerous detects pkill") {
    assert(BashTool.isDangerous("pkill -f java"))
    assert(BashTool.isDangerous("pkill -f nebflow"))
  }

  test("isDangerous detects killall") {
    assert(BashTool.isDangerous("killall java"))
  }

  test("isDangerous detects kill with PID") {
    assert(BashTool.isDangerous("kill 12345"))
    assert(BashTool.isDangerous("kill -9 12345"))
  }

  test("isDangerous detects git checkout branch switch") {
    assert(BashTool.isDangerous("git checkout feature-branch"))
  }

  test("isDangerous allows git checkout -b") {
    assert(!BashTool.isDangerous("git checkout -b new-branch"))
  }

  test("isDangerous detects git switch") {
    assert(BashTool.isDangerous("git switch main"))
  }

  test("isDangerous detects git stash drop/clear") {
    assert(BashTool.isDangerous("git stash drop"))
    assert(BashTool.isDangerous("git stash clear"))
  }

  test("isDangerous detects git rebase") {
    assert(BashTool.isDangerous("git rebase main"))
  }

  test("isDangerous detects git merge") {
    assert(BashTool.isDangerous("git merge feature-branch"))
  }

  test("isDangerous detects systemctl stop") {
    assert(BashTool.isDangerous("systemctl stop nebflow"))
  }

  test("isDangerous detects launchctl stop") {
    assert(BashTool.isDangerous("launchctl stop nebflow"))
  }

  // --- dangerLevel ---

  test("dangerLevel 3 for critical operations") {
    assertEquals(BashTool.dangerLevel("rm -rf /"), 3)
    assertEquals(BashTool.dangerLevel("pkill -f nebflow"), 3)
    assertEquals(BashTool.dangerLevel("killall java"), 3)
  }

  test("dangerLevel 2 for dangerous operations") {
    assertEquals(BashTool.dangerLevel("rm -rf /tmp/test"), 2)
    assertEquals(BashTool.dangerLevel("docker system prune"), 2)
    assertEquals(BashTool.dangerLevel("git reset --hard HEAD"), 2)
  }

  test("dangerLevel 1 for warning operations") {
    assertEquals(BashTool.dangerLevel("git checkout feature-branch"), 1)
    assertEquals(BashTool.dangerLevel("git switch main"), 1)
    assertEquals(BashTool.dangerLevel("git rebase main"), 1)
  }

  test("dangerLevel 0 for safe commands") {
    assertEquals(BashTool.dangerLevel("ls -la"), 0)
    assertEquals(BashTool.dangerLevel("git status"), 0)
    assertEquals(BashTool.dangerLevel("cat file.txt"), 0)
  }

  // --- ToolReversibility ---

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
