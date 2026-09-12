package nebflow.core

import munit.CatsEffectSuite
import os.*

import java.nio.file.Files

/**
 * F1 (#433): GlobalSafety — the global safety mode read from nebflow.json
 * `safety.defaultMode`. A present valid value resolves hot per access (config
 * path resolves per call). 启动默认 = 全部放行 (2026-09-12 作者令): 读不到有效值
 * （缺文件 / 缺键 / 不可解析 / 读取失败）⇒ AutoAll；显式但不可识别的值仍回
 * ConfirmEdits（保守，见 permissions.scala 的取值三档注释）。
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

  test("missing config file falls back to AutoAll (启动默认 = 顶档)"):
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("config without safety key falls back to AutoAll (启动默认 = 顶档)"):
    writeConfig("""{"providers": {}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("safety.defaultMode auto-all resolves to AutoAll"):
    writeConfig("""{"safety": {"defaultMode": "auto-all"}, "providers": {}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

  test("safety.defaultMode auto-edits resolves to AutoEdits"):
    writeConfig("""{"safety": {"defaultMode": "auto-edits"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoEdits))

  test("safety.defaultMode confirm-edits still resolves to ConfirmEdits (配置优先)"):
    writeConfig("""{"safety": {"defaultMode": "confirm-edits"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("unknown safety.defaultMode value falls back to ConfirmEdits"):
    writeConfig("""{"safety": {"defaultMode": "yolo"}}""")
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.ConfirmEdits))

  test("unparsable config falls back to AutoAll (读不到有效值 ⇒ 启动默认; never throws)"):
    writeConfig("""{"safety": """)
    GlobalSafety.defaultMode.map(m => assertEquals(m, SafetyMode.AutoAll))

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
