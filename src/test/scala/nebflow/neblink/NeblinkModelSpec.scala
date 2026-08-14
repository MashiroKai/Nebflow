package nebflow.neblink

import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

class NeblinkModelSpec extends CatsEffectSuite:

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

  // ===== NeblinkConfig =====

  test("NeblinkConfig default values") {
    val cfg = NeblinkConfig()
    assertEquals(cfg.enabled, false, "Default enabled should be false")
    assertEquals(cfg.syncIntervalSec, 45, "Default sync interval should be 45 seconds")
  }

  test("NeblinkConfig serialization roundtrip") {
    val cfg = NeblinkConfig(
      enabled = true,
      syncIntervalSec = 600
    )
    val json = cfg.asJson.noSpaces
    val decoded = decode[NeblinkConfig](json)
    assertEquals(decoded, Right(cfg), "Roundtrip should preserve all fields")
  }

end NeblinkModelSpec
