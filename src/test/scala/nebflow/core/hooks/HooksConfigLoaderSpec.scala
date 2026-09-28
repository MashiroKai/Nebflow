package nebflow.core.hooks

import io.circe.syntax.*
import munit.FunSuite

import java.nio.file.{Files => JFiles}

class HooksConfigLoaderSpec extends FunSuite:

  private var root: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    root = os.Path(JFiles.createTempDirectory("nb-hooks-cfg").toString)

  override def afterEach(context: munit.AfterEach): Unit =
    if os.exists(root) then os.remove.all(root)

  private def writeConfig(json: String): Unit = os.write(root / "nebflow.json", json)

  test("no nebflow.json -> empty config") {
    assertEquals(HooksConfigLoader.load(root), HooksConfig.empty)
  }

  test("no hooks section -> empty config") {
    writeConfig("""{"llm": {"providers": {}}}""")
    assertEquals(HooksConfigLoader.load(root), HooksConfig.empty)
  }

  test("corrupt JSON degrades to empty config, no throw") {
    writeConfig("{ broken !!!")
    assertEquals(HooksConfigLoader.load(root), HooksConfig.empty)
  }

  test("unknown event names are skipped with the rest intact") {
    writeConfig(
      """{
        "hooks": {
          "NoSuchEvent": [{"matcher": "*", "hooks": [{"command": "echo hi"}]}],
          "PostToolUse": [{"matcher": "Edit", "hooks": [{"command": "echo edit-hook"}]}]
        }
      }"""
    )
    val cfg = HooksConfigLoader.load(root)
    assertEquals(cfg.hooks.keySet, Set("PostToolUse"))
    assertEquals(cfg.hooks("PostToolUse").head.matcher, "Edit")
  }

  test("full rule parse: matcher, timeout, continueOnError defaults") {
    writeConfig(
      """{
        "hooks": {
          "PreToolUse": [
            {"matcher": "Edit|Write", "hooks": [
              {"type": "command", "command": "lint.sh", "timeout": 5, "continueOnError": false}
            ]},
            {"hooks": [{"command": "audit.sh"}]}
          ]
        }
      }"""
    )
    val cfg = HooksConfigLoader.load(root)
    val rules = cfg.hooks("PreToolUse")
    assertEquals(rules.length, 2)
    val lint = rules.head.hooks.head
    assertEquals(lint.command, "lint.sh")
    assertEquals(lint.timeout, 5)
    assertEquals(lint.continueOnError, false)
    assertEquals(lint.`type`, "command")
    // rule without matcher defaults to "*"; hookDef without timeout defaults 60 / true
    assertEquals(rules(1).matcher, "*")
    assertEquals(rules(1).hooks.head.timeout, 60)
    assertEquals(rules(1).hooks.head.continueOnError, true)
  }

  test("hooks section with wrong shape (object instead of array) yields no rules") {
    writeConfig("""{"hooks": {"PostToolUse": {"matcher": "*", "hooks": []}}}""")
    val cfg = HooksConfigLoader.load(root)
    assert(cfg.hooks.get("PostToolUse").forall(_.isEmpty))
  }

end HooksConfigLoaderSpec
