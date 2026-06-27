package nebflow.mesh

import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

class MeshModelSpec extends CatsEffectSuite:

  // ===== AccountInfo =====

  test("AccountInfo serialization roundtrip") {
    val acc = AccountInfo(
      userId = "user-123",
      username = "testuser",
      sessionToken = "tok-abc-456",
      loggedInAt = 1700000000000L
    )
    val json = acc.asJson.noSpaces
    val decoded = decode[AccountInfo](json)
    assertEquals(decoded, Right(acc), "Roundtrip should preserve all fields")
  }

  test("AccountInfo JSON contains all required fields") {
    val acc = AccountInfo("uid-1", "alice", "secret-token", 1700000000000L)
    val json = acc.asJson
    assert(json.hcursor.downField("userId").as[String].isRight, "Should contain userId")
    assert(json.hcursor.downField("username").as[String].isRight, "Should contain username")
    assert(json.hcursor.downField("sessionToken").as[String].isRight, "Should contain sessionToken")
    assert(json.hcursor.downField("loggedInAt").as[Long].isRight, "Should contain loggedInAt")
  }

  // ===== DeviceIdentity =====

  test("DeviceIdentity serialization roundtrip") {
    val id = DeviceIdentity(
      deviceId = "dev-uuid-1234",
      deviceName = "TestMac (macOS)",
      platform = "macos",
      deviceSecret = "secret-abc-123"
    )
    val json = id.asJson.noSpaces
    val decoded = decode[DeviceIdentity](json)
    assertEquals(decoded, Right(id), "Roundtrip should preserve all fields including deviceSecret")
  }

  test("DeviceIdentity loadOrCreate has non-empty deviceSecret") {
    val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
    assert(id.deviceSecret.nonEmpty, "deviceSecret should be populated after loadOrCreate")
    assert(id.deviceSecret.length >= 36, s"deviceSecret should be at least UUID length, got ${id.deviceSecret.length}")
  }

  test("DeviceIdentity has valid UUID format deviceId after loadOrCreate") {
    val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
    assert(
      id.deviceId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
      s"deviceId should be UUID format, got: ${id.deviceId}"
    )
  }

  test("DeviceIdentity platform is one of expected values") {
    val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
    assert(
      Set("macos", "windows", "linux", "unknown").contains(id.platform),
      s"platform should be recognized OS, got: ${id.platform}"
    )
  }

  // ===== PeerInfo =====

  test("PeerInfo serialization roundtrip") {
    val peer = PeerInfo(
      deviceId = "peer-dev-1",
      deviceName = "Windows-PC (Windows)",
      platform = "windows",
      address = "http://192.168.1.100:8080",
      deviceSecret = "peer-secret-xyz",
      lastSeen = 1700000000000L
    )
    val json = peer.asJson.noSpaces
    val decoded = decode[PeerInfo](json)
    assertEquals(decoded, Right(peer), "Roundtrip should preserve all fields including deviceSecret")
  }

  test("PeerInfo default lastSeen is current time") {
    val before = System.currentTimeMillis()
    val peer = PeerInfo("d", "n", "p", "a")
    val after = System.currentTimeMillis()
    assert(
      peer.lastSeen >= before && peer.lastSeen <= after,
      s"lastSeen ${peer.lastSeen} should be between $before and $after"
    )
  }

  test("PeerInfo default deviceSecret is empty string") {
    val peer = PeerInfo("d", "n", "p", "a")
    assertEquals(peer.deviceSecret, "", "Default deviceSecret should be empty")
  }

  // ===== MeshConfig =====

  test("MeshConfig default values") {
    val cfg = MeshConfig()
    assertEquals(cfg.enabled, false, "Default enabled should be false")
    assertEquals(cfg.syncIntervalSec, 300, "Default sync interval should be 300 seconds")
    assert(cfg.cloudUrl.isEmpty, "Default cloud URL should be None — user configures self-hosted server")
  }

  test("MeshConfig serialization roundtrip") {
    val cfg = MeshConfig(
      enabled = true,
      syncIntervalSec = 600,
      cloudUrl = Some("https://example.com/api")
    )
    val json = cfg.asJson.noSpaces
    val decoded = decode[MeshConfig](json)
    assertEquals(decoded, Right(cfg), "Roundtrip should preserve all fields")
  }

  test("MeshConfig with None cloudUrl") {
    val cfg = MeshConfig(enabled = false, cloudUrl = None)
    val json = cfg.asJson.noSpaces
    val decoded = decode[MeshConfig](json)
    assertEquals(decoded, Right(cfg), "Should handle None cloudUrl")
  }

end MeshModelSpec
