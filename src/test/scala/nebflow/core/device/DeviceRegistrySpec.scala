package nebflow.core.device

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * devices.json 配对登记表（v2.1 阶段 1 §A.4 最小引导面）的承重断言：
 *   - 缺席 = 空表（未配对常态，绝不抛）；
 *   - 手写盘面（ops 配对的唯一入口）宽容解码：缺 allowSpawn 键 = true、缺 note
 *     = 空——手工最小 JSON 即可完成配对；
 *   - 坏 JSON = WARN + 空表（信任判据不楔死通道）；
 *   - upsert/remove/secretFor 的幂等与查键语义。
 */
class DeviceRegistrySpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-device-registry"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def registryFile: os.Path = PathUtil.dataRoot / "neblink" / "devices.json"

  test("absent registry = empty list (unpaired default), no throw") {
    assertEquals(DeviceRegistry.loadSync, Nil)
    assertEquals(DeviceRegistry.secretForSync("dev-1"), None)
  }

  test("upsert + secretFor + allowSpawn roundtrip through the real file") {
    assertEquals(
      DeviceRegistry.upsertSync(DeviceEntry("dev-1", "Alpha", "key-1", allowSpawn = true, createdAt = 1L)),
      Right(())
    )
    assertEquals(DeviceRegistry.secretForSync("dev-1"), Some("key-1"))
    // 同 id upsert = 幂等覆盖（不产生重复行）。
    assertEquals(
      DeviceRegistry.upsertSync(DeviceEntry("dev-1", "Alpha-2", "key-1b", allowSpawn = false, createdAt = 2L)),
      Right(())
    )
    val rows = DeviceRegistry.loadSync
    assertEquals(rows.length, 1)
    assertEquals(rows.head.deviceName, "Alpha-2")
    assertEquals(rows.head.allowSpawn, false)
    // removeSync 删行。
    assertEquals(DeviceRegistry.removeSync("dev-1"), Right(()))
    assertEquals(DeviceRegistry.loadSync, Nil)
  }

  test("hand-written minimal JSON decodes tolerantly: allowSpawn defaults true, note defaults empty") {
    os.makeDir.all(registryFile / os.up)
    os.write.over(
      registryFile,
      """[{"deviceId":"dev-9","deviceName":"Remote","sharedKey":"k9","createdAt":123}]"""
    )
    val rows = DeviceRegistry.loadSync
    assertEquals(rows.map(_.copy(createdAt = 123L)), List(DeviceEntry("dev-9", "Remote", "k9", true, 123L, "")))
    assertEquals(DeviceRegistry.entrySync("dev-9").map(_.allowSpawn), Some(true))
  }

  test("corrupt JSON = WARN + empty registry (trust criteria never wedges the channel)") {
    os.makeDir.all(registryFile / os.up)
    os.write.over(registryFile, "{ not json")
    assertEquals(DeviceRegistry.loadSync, Nil)
    assertEquals(DeviceRegistry.secretForSync("dev-9"), None)
  }

  test("empty sharedKey = unpaired (secretFor filters blank keys)") {
    os.makeDir.all(registryFile / os.up)
    os.write.over(
      registryFile,
      """[{"deviceId":"dev-0","deviceName":"Blank","sharedKey":"","createdAt":1}]"""
    )
    assertEquals(DeviceRegistry.secretForSync("dev-0"), None)
  }

end DeviceRegistrySpec
