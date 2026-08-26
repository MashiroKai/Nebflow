package nebflow.core

import munit.CatsEffectSuite
import os.*

import java.nio.file.Files

/**
 * F1 (#433): GlobalSafety — the global safety mode read from nebflow.json
 * `safety.defaultMode`. Missing key / unparsable file / unknown value / no
 * file all fall back to ConfirmEdits; a present valid value resolves hot
 * per access (config path resolves per call).
 */
class GlobalSafetySpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = Path(Files.createTempDirectory("nb-globalsafety"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def writeConfig(content: String): Unit =
    os.write.over(tmp / "nebflow.json", content)

  test("missing config file falls back to ConfirmEdits"):
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("config without safety key falls back to ConfirmEdits"):
    writeConfig("""{"providers": {}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("safety.defaultMode auto-all resolves to AutoAll"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}, "providers": {}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("safety.defaultMode auto-edits resolves to AutoEdits"):
    writeConfig("""{"safety": {"defaultMode": "auto-edits"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoEdits))

  test("unknown safety.defaultMode value falls back to ConfirmEdits"):
    writeConfig("""{"safety": {"defaultMode": "yolo"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("unparsable config falls back to ConfirmEdits (never throws)"):
    writeConfig("""{"safety": """)
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("hot-read: value change is visible on the next access without restart"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    for
      before <- GlobalSafety.defaultMode
      _ = writeConfig("""{"safety": {"defaultMode": "auto-all"}}""")
      after <- GlobalSafety.defaultMode
    yield
      assertEquals(before, SafetyMode.ConfirmEdits)
      assertEquals(after, SafetyMode.AutoAll)

end GlobalSafetySpec
