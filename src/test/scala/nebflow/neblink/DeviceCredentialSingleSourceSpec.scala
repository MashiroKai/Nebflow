package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * kaiauth 修法批 ②（2026-09-16 作者「治本」已批）—— **死副本单源化**的真钉。
 *
 * 缺陷形态（诊断报告 §2 / §5）：`<dataRoot>/neblink/device.json` 的 `deviceToken`
 * **只写不读**（写入点两处、4 个读点全部只取 `logto` 块），而出站唯一来源 = `config.json`
 * 的 `neblinkServer.deviceToken` ⇒ 两份不一致时「写盘成功但对发送值无效」**必然**成立，
 * 2026-09-16 的排障正被这份死副本误导。
 *
 * 修法 = **写径收敛单源化**：`deviceToken` 的唯一权威写面 = `config.json`；
 * `neblink/device.json` 侧**停写**该字段（编码器结构性停写 ⇒ 任何构造面都写不出去）。
 * **零删除纪律（迁移式）**：字段不删、文件不删、内容不清洗；旧文件**照旧可解码**
 * （值被读取时**仅忽略**，并发**一次** WARN）。
 *
 * 四条钉：
 *  N5-1 新写入**不含** `deviceToken` 键（身份面 / `logto` 面逐字保真）；
 *  N5-2 **旧文件**（含该键）仍可解码（向后兼容），值不参与任何逻辑，WARN 恰一次；
 *  N5-3 出站凭据**只**来自 `config.json`（盘上旧副本的值零影响）—— 报告 §② 的
 *       9 出站点表「逐行不变」的可测形态；
 *  N5-4 新文件自己也能解码回来（否则 `load` 恒 None ⇒ 身份面/refresh 腿全断）。
 */
class DeviceCredentialSingleSourceSpec extends FunSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-cred-single-source-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    DeviceCredential.resetLegacyTokenWarnForTest()

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def credPath: os.Path = os.Path(tmpDir, os.pwd) / "neblink" / "device.json"
  private def raw: String = os.read(credPath)

  /** 本批**之前**的落盘形态（含被停写的那份副本）。 */
  private def writeLegacyFile(token: String): Unit =
    os.write.over(
      credPath,
      s"""{"serverUrl":"https://neblink.example","networkId":"net-1","deviceId":"dev-1",""" +
        s""""deviceToken":"$token","logto":{"refreshToken":"legacy-refresh","updatedAt":7,"idToken":"legacy-id"}}""",
      createFolders = true
    )

  // ── N5-1 / N5-4 ────────────────────────────────────────────────────────

  test("N5-1: 新写入不含 deviceToken 键，且身份面 / logto 面逐字保真（N5-4 顺带：可解码回来）") {
    DeviceCredential
      .save(
        DeviceCredential(
          "https://neblink.example",
          "net-1",
          "dev-1",
          "tok-secret-must-not-land",
          LogtoRefresh.of(Some("rt-9"), Some("id-9"))
        )
      )
      .unsafeRunSync()

    assert(
      !raw.contains("\"deviceToken\""),
      s"🔴 判据 N5-1：新写入不得含 deviceToken 键。实际：$raw"
    )
    assert(!raw.contains("tok-secret-must-not-land"), "凭据值同样不得落盘（停写 = 值也不写）")
    // 身份面 + logto 面（文件其余面语义**照旧**，禁连带改动）
    val loaded = DeviceCredential.load.unsafeRunSync().getOrElse(fail("the new file must decode"))
    assertEquals(loaded.serverUrl, "https://neblink.example")
    assertEquals(loaded.networkId, "net-1")
    assertEquals(loaded.deviceId, "dev-1")
    assertEquals(loaded.logto.map(_.refreshToken), Some("rt-9"))
    assertEquals(loaded.logto.flatMap(_.idToken), Some("id-9"))
    assertEquals(
      loaded.deviceToken,
      "",
      "被停写字段在新文件里解码为空（缺席 ⇒ 空串；旧 decoder 的取值语义不再被依赖）"
    )
  }

  // ── N5-2：向后兼容解码 + 只忽略 + 一次 WARN ─────────────────────────────

  test("N5-2: 旧文件（含 deviceToken）仍可解码、值被忽略，且 WARN 恰一次") {
    writeLegacyFile("legacy-tok")
    val first = DeviceCredential.load.unsafeRunSync().getOrElse(fail("legacy file must decode"))
    assertEquals(first.deviceId, "dev-1")
    assertEquals(first.logto.map(_.refreshToken), Some("legacy-refresh"))
    assertEquals(first.deviceToken, "legacy-tok", "值照旧解码出来（**只忽略**，不是清掉）")
    assertEquals(DeviceCredential.legacyTokenWarnCount, 1, "旧文件 ⇒ 恰一次 WARN")

    // 再读一次：文件一字节未变，WARN 不得重复
    val second = DeviceCredential.load.unsafeRunSync().getOrElse(fail("legacy file must decode again"))
    assertEquals(second.deviceToken, "legacy-tok")
    assertEquals(DeviceCredential.legacyTokenWarnCount, 1, "WARN 必须是一次性的（不刷屏）")
    assert(raw.contains("\"deviceToken\""), "零删除纪律：旧文件内容不被清洗（本批不碰）")
  }

  test("N5-2b: 无该键的文件不产生 WARN（新形态不是告警面）") {
    DeviceCredential.save(DeviceCredential("https://neblink.example", "net-1", "dev-1", "x")).unsafeRunSync()
    DeviceCredential.load.unsafeRunSync()
    assertEquals(DeviceCredential.legacyTokenWarnCount, 0)
  }

  // ── N5-3：出站唯一来源 = config.json ─────────────────────────────────────

  test("N5-3: 出站凭据只来自 config（盘上旧副本的值零影响）") {
    writeLegacyFile("disk-copy-token")
    val bodies = scala.collection.mutable.ListBuffer.empty[String]
    val cfg = NeblinkServerConfig(
      url = "http://127.0.0.1:1",
      networkId = "net-1",
      secret = "s",
      deviceToken = Some("cfg-token")
    )
    val client = new NeblinkClient(cfg, serverPort = 1):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        IO {
          bodies += body
          Right("""{"token":"t","networkId":"net-1","deviceId":"dev-1","peers":[]}""")
        }
    client.login("dev-1", "qa-host", "macos", Nil).unsafeRunSync()

    assertEquals(bodies.size, 1)
    val body = bodies.head
    assert(body.contains("\"deviceToken\":\"cfg-token\""), s"出站必须用 config 那一份：$body")
    assert(!body.contains("disk-copy-token"), s"🔴 盘上旧副本的值绝不能进请求体：$body")
  }

end DeviceCredentialSingleSourceSpec
