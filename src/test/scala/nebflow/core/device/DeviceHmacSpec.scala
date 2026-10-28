package nebflow.core.device

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * v2.1 阶段 1 §A.4 HMAC 信任通道的判据面（承重断言）：
 *   - canonical 冻结形（发送侧 p2pExecuteAt / 收端 verifyPeerAccess / DeviceDelegate
 *     三方同一拼装点——禁两套编码的锚）；
 *   - 判据三件套（ts ±90s + nonce + 常数时间比对）经 verifyHeaders 的拒绝矩阵；
 *   - NonceCache 首见/重放/容量淘汰；
 *   - DeviceAuthMode 热读宽容解码（off 缺省 / audit / required / 坏值兜 off）。
 */
class DeviceHmacSpec extends CatsEffectSuite:

  private val key = "shared-secret-01"
  private val deviceId = "dev-alpha"

  test("canonical is the frozen pipe-joined form") {
    assertEquals(
      DeviceHmac.canonical(deviceId, "1700000000", "n-1", "POST", "/api/neblink/remote-exec", "cafe"),
      s"$deviceId|1700000000|n-1|POST|/api/neblink/remote-exec|cafe"
    )
  }

  test("sign/verify roundtrip accepts the exact canonical and rejects tampering") {
    val canon = DeviceHmac.canonical(deviceId, "1700000000", "n-1", "POST", "/p", DeviceHmac.bodySha256("body"))
    val sig = DeviceHmac.sign(key, canon)
    assert(DeviceHmac.verify(key, canon, sig))
    // 常数时间比对同样拒错钥与错串（不是异常，是 false）。
    assert(!DeviceHmac.verify("other-key", canon, sig))
    assert(
      !DeviceHmac.verify(
        key,
        DeviceHmac.canonical(deviceId, "1700000001", "n-1", "POST", "/p", DeviceHmac.bodySha256("body")),
        sig
      )
    )
    assert(!DeviceHmac.verify(key, canon, ""))
  }

  test("tsFresh enforces the ±90s window (unix seconds)") {
    val now = 1_700_000_000_000L
    assert(DeviceHmac.tsFresh("1700000000", now)) // 恰在窗口内
    assert(DeviceHmac.tsFresh(((now - 89_000) / 1000).toString, now))
    assert(!DeviceHmac.tsFresh(((now - 91_000) / 1000).toString, now)) // 超窗（过去）
    assert(!DeviceHmac.tsFresh(((now + 91_000) / 1000).toString, now)) // 超窗（未来）
    assert(!DeviceHmac.tsFresh("not-a-number", now))
  }

  test("bodySha256 of the empty string is the well-known digest (empty bodies still sign)") {
    assertEquals(
      DeviceHmac.bodySha256(""),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
  }

  test("verifyHeaders rejects each missing header, stale ts, empty nonce and mismatch") {
    val now = System.currentTimeMillis()
    val ts = (now / 1000).toString
    val nonce = "nonce-1"
    val body = """{"action":"Bash"}"""
    val sig = DeviceHmac.sign(
      key,
      DeviceHmac.canonical(deviceId, ts, nonce, "POST", "/api/neblink/remote-exec", DeviceHmac.bodySha256(body))
    )

    def verdict(
      t: Option[String],
      n: Option[String],
      s: Option[String],
      b: String = body,
      path: String = "/api/neblink/remote-exec"
    ) =
      DeviceHmac.verifyHeaders(key, deviceId, t, n, s, "POST", path, b, now)

    assertEquals(verdict(None, Some(nonce), Some(sig)), Some("missing x-neblink-ts header"))
    assertEquals(verdict(Some(ts), None, Some(sig)), Some("missing x-neblink-nonce header"))
    assertEquals(verdict(Some(ts), Some(nonce), None), Some("missing x-neblink-signature header"))
    assert(verdict(Some(((now - 91_000) / 1000).toString), Some(nonce), Some(sig)).exists(_.contains("stale")))
    assertEquals(verdict(Some(ts), Some("  "), Some(sig)), Some("empty nonce"))
    assert(verdict(Some(ts), Some(nonce), Some("bad" + sig.drop(3))).exists(_.contains("mismatch")))
    // 体被篡改（签名覆盖 body sha）⇒ mismatch。
    assert(verdict(Some(ts), Some(nonce), Some(sig), b = body + "x").exists(_.contains("mismatch")))
    // path 不符（收端按实际请求 path 计算）⇒ mismatch。
    assert(verdict(Some(ts), Some(nonce), Some(sig), path = "/api/neblink/transfer").exists(_.contains("mismatch")))
    // 正面：全符 ⇒ None。
    assertEquals(verdict(Some(ts), Some(nonce), Some(sig)), None)
  }

  test("NonceCache: first seen passes, same nonce replays, capacity evicts oldest per device") {
    val cache = new NonceCache(capacityPerDevice = 2)
    assert(cache.firstSeen("d1", "a"))
    assert(!cache.firstSeen("d1", "a")) // 窗口内重放
    assert(cache.firstSeen("d1", "b"))
    assert(cache.firstSeen("d1", "c")) // 超容量：最老的 a 被淘汰
    assert(cache.firstSeen("d1", "a")) // 淘汰后 a 重新成为首见（LRU 语义，诚实边界）
    // per-device 隔离：d2 的同一 nonce 与 d1 无关。
    assert(cache.firstSeen("d2", "a"))
    cache.clear()
    assert(cache.firstSeen("d1", "a"))
  }

  // ── DeviceAuthMode 热读（nebflow.json 顶层 deviceAuth 键）──────────────

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-device-hmac"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private def writeConfig(json: String): Unit = os.write.over(tmp / "nebflow.json", json)

  test("DeviceAuthMode.read: absent config = off (byte-identical default)") {
    assertEquals(DeviceAuthMode.read, DeviceAuthMode.Off)
  }

  test("DeviceAuthMode.read: audit / required round-trip; unknown value and bad json fall back to off") {
    writeConfig("""{"deviceAuth": "audit"}""")
    assertEquals(DeviceAuthMode.read, DeviceAuthMode.Audit)
    writeConfig("""{"deviceAuth": "REQUIRED"}""")
    assertEquals(DeviceAuthMode.read, DeviceAuthMode.Required)
    writeConfig("""{"deviceAuth": "yolo"}""")
    assertEquals(DeviceAuthMode.read, DeviceAuthMode.Off)
    writeConfig("""{ not json """)
    assertEquals(DeviceAuthMode.read, DeviceAuthMode.Off)
    writeConfig("""{"deviceAuth": 42}""")
    assertEquals(DeviceAuthMode.read, DeviceAuthMode.Off)
  }

end DeviceHmacSpec
