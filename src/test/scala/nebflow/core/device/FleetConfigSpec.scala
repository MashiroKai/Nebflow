package nebflow.core.device

import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * FleetConfig 单测（台账 #913 ③，2026-10-26）——`fleet.enforceExplicitDevice`
 * 开关的热读宽容面 + 策略文本二态：
 *   - readSettings：缺文件 / 坏 JSON / 缺键 / 好值 四态（宽容读，绝不抛）；
 *   - explicitDeviceRefusal：文案列设备名、点名会话默认设备、空册降级；
 *   - agentsMdSection：开关关 = 空串（模板字节零变化），开 = 带 Fleet device discipline 节。
 */
class FleetConfigSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-fleet-config"

  // dataRoot 全局态：类级重定向一次（f1cd3709 rule——dataRoot 可重定向，测试各自独立）
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private def writeConfig(json: String): Unit =
    os.write.over(tempRoot / "nebflow.json", json, createFolders = true)

  // ── readSettings 四态 ─────────────────────────────────────────────

  test("readSettings: missing config file → Default (flag off, never throws)"):
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    assertEquals(FleetConfig.readSettings, FleetConfig.Default)
    assertEquals(FleetConfig.readSettings.enforceExplicitDevice, false)

  test("readSettings: corrupt JSON → Default (fail-closed tolerant, never throws)"):
    writeConfig("{not valid json,,,")
    assertEquals(FleetConfig.readSettings, FleetConfig.Default)

  test("readSettings: fleet block absent / flag absent → off"):
    writeConfig("""{"executor":{"default":"nebflow"}}""")
    assertEquals(FleetConfig.readSettings.enforceExplicitDevice, false)
    writeConfig("""{"fleet":{"other":"x"}}""")
    assertEquals(FleetConfig.readSettings.enforceExplicitDevice, false)

  test("readSettings: fleet.enforceExplicitDevice=true reads true (hot read)"):
    writeConfig("""{"fleet":{"enforceExplicitDevice":true}}""")
    assertEquals(FleetConfig.readSettings.enforceExplicitDevice, true)
    // 热读：改回 false 立即可读（无进程级缓存语义——memo 只有 2s 窗口且在本单测不经过）
    writeConfig("""{"fleet":{"enforceExplicitDevice":false}}""")
    assertEquals(FleetConfig.readSettings.enforceExplicitDevice, false)

  // ── 强制模式报错文案（纯函数）────────────────────────────────────

  test("explicitDeviceRefusal: lists local + peers and the escape hatch"):
    val msg = FleetConfig.explicitDeviceRefusal("Bash", "KAI", List("Mac"), None)
    assert(msg.startsWith("Bash refused: missing required `device` parameter"), msg)
    assert(msg.contains("fleet.enforceExplicitDevice is ON"), msg)
    assert(msg.contains("KAI (= local)"), "local device listed with its name")
    assert(msg.contains("Mac"), "peer listed")
    assert(msg.contains("device=\"local\""), "explicit local escape hatch documented")

  test("explicitDeviceRefusal: empty roster degrades honestly (roster unavailable)"):
    val msg = FleetConfig.explicitDeviceRefusal("Read", "", Nil, None)
    assert(msg.contains("local only (device roster unavailable"), msg)

  test("explicitDeviceRefusal: session default device is named as the explicit value to pass"):
    val msg = FleetConfig.explicitDeviceRefusal("Grep", "KAI", Nil, Some("Mac"))
    assert(msg.contains("This session's default device is \"Mac\""), msg)
    assert(msg.contains("device=\"Mac\""), msg)

  // ── AGENTS.md 模板节二态 ─────────────────────────────────────────

  test("agentsMdSection: flag OFF → empty string (template byte-identical, backward compat)"):
    // 基模板（ProjectCreateService.AgentMdTemplate）= 空串：节空 ⇒ 装配形与基座
    // 字节相等（跨包判据在 ProjectCreateService 面的 spec 钉）。
    assertEquals(FleetConfig.agentsMdSection(false), "")

  test("agentsMdSection: flag ON → carries the discipline section"):
    val sec = FleetConfig.agentsMdSection(true)
    assert(sec.startsWith("## Fleet device discipline"), sec)
    assert(sec.contains("fleet.enforceExplicitDevice"), sec)
    assert(sec.contains("Bash / Read / Write / Edit / Glob / Grep"), sec)
    assert(sec.contains("Project(action=\"list\")"), "device-annotated project query face named")

  // ── schema 面与引擎行为同源（augmentSchema 二态；经 2s memo 热读）──

  test("augmentSchema: device description follows the live flag (off = verbatim legacy copy)"):
    import nebflow.core.tools.RemoteExecutor
    import io.circe.Json
    import io.circe.syntax.*
    // 起点置 OFF 并越过 2s memo 窗
    writeConfig("""{"fleet":{"enforceExplicitDevice":false}}""")
    Thread.sleep(2100)
    val schema = Json.obj(
      "type" -> "object".asJson,
      "properties" -> Json.obj("command" -> Json.obj("type" -> "string".asJson))
    )
    val off = RemoteExecutor.augmentSchema("Bash", schema.asObject.get)
    val offDesc = off("properties").flatMap(_.hcursor.downField("device").downField("description").as[String].toOption)
    assertEquals(
      offDesc,
      Some("Target device name. Use the device's name from the available devices list. Defaults to local device if omitted."),
      "flag off = legacy wording byte-identical (backward compat)"
    )
    // 非 remoteable 工具不受影响（无 device 键）
    val untouched = RemoteExecutor.augmentSchema("Mail", schema.asObject.get)
    assert(untouched("properties").flatMap(_.asObject).exists(!_.contains("device")))

    // 置 ON + 越窗 ⇒ REQUIRED 措辞（与引擎拒绝行为同源）
    writeConfig("""{"fleet":{"enforceExplicitDevice":true}}""")
    Thread.sleep(2100)
    val on = RemoteExecutor.augmentSchema("Bash", schema.asObject.get)
    val onDesc = on("properties").flatMap(_.hcursor.downField("device").downField("description").as[String].toOption)
    assert(onDesc.exists(_.contains("REQUIRED")), onDesc.toString)
    assert(onDesc.exists(_.contains("fleet.enforceExplicitDevice is ON")), onDesc.toString)

    // 恢复缺省（不留全局态污染其他 suite）
    writeConfig("""{}""")
    Thread.sleep(2100)
    val restored = RemoteExecutor.augmentSchema("Bash", schema.asObject.get)
    assert(
      restored("properties")
        .flatMap(_.hcursor.downField("device").downField("description").as[String].toOption)
        .exists(_.contains("Defaults to local device")),
      "restore to default"
    )

end FleetConfigSpec
