package nebflow.mesh

import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class MeshModelSpec extends CatsEffectSuite:

  // ===== FileFingerprint =====

  test("FileFingerprint.compute returns None for non-existent file") {
    val ghost = os.Path(Path.of(s"/tmp/nebflow-ghost-${System.currentTimeMillis()}.md"))
    val result = FileFingerprint.compute(ghost)
    assertEquals(result, None)
  }

  test("FileFingerprint.compute returns Some for existing file") {
    val tmp = Files.createTempFile("nebflow-fp-", ".md")
    try
      Files.writeString(tmp, "hello world")
      val result = FileFingerprint.compute(os.Path(tmp))
      assert(result.isDefined, "Expected Some for existing file")
      val fp = result.get
      assert(fp.mtime > 0, "mtime should be positive")
      assertEquals(fp.size, 11L, "size should match content length")
      assert(fp.hash.nonEmpty, "hash should not be empty")
    finally Files.deleteIfExists(tmp)
  }

  test("FileFingerprint.compute returns correct size for empty file") {
    val tmp = Files.createTempFile("nebflow-fp-empty-", ".md")
    try
      Files.writeString(tmp, "")
      val result = FileFingerprint.compute(os.Path(tmp))
      assert(result.isDefined)
      assertEquals(result.get.size, 0L, "Empty file should have size 0")
      // Hash of empty content should still be computed
      assert(result.get.hash.nonEmpty, "Empty file should still have a hash")
    finally Files.deleteIfExists(tmp)
  }

  test("FileFingerprint.compute returns correct size for binary content") {
    val tmp = Files.createTempFile("nebflow-fp-bin-", ".bin")
    try
      val bytes: Array[Byte] = Array(0x00, 0x01, 0xff.toByte, 0xfe.toByte)
      Files.write(tmp, bytes)
      val result = FileFingerprint.compute(os.Path(tmp))
      assert(result.isDefined)
      assertEquals(result.get.size, 4L)
    finally Files.deleteIfExists(tmp)
  }

  test("FileFingerprint.computeHash is deterministic") {
    val bytes = "test content".getBytes("UTF-8")
    val h1 = FileFingerprint.computeHash(bytes)
    val h2 = FileFingerprint.computeHash(bytes)
    assertEquals(h1, h2, "Same input should produce same hash")
  }

  test("FileFingerprint.computeHash differs for different inputs") {
    val h1 = FileFingerprint.computeHash("content A".getBytes("UTF-8"))
    val h2 = FileFingerprint.computeHash("content B".getBytes("UTF-8"))
    assert(h1 != h2, "Different inputs should produce different hashes")
  }

  test("FileFingerprint.computeHash handles empty input") {
    val hash = FileFingerprint.computeHash(Array.emptyByteArray)
    assertEquals(hash.length, 12, "Empty input should still produce 12-char hash")
  }

  test("FileFingerprint.computeHash length is 12 hex chars (6 bytes)") {
    val hash = FileFingerprint.computeHash("anything".getBytes("UTF-8"))
    assertEquals(hash.length, 12, "Hash should be 12 hex characters (SHA-256 truncated to 6 bytes)")
    assert(hash.forall(c => "0123456789abcdef".contains(c)), "Hash should be lowercase hex")
  }

  test("FileFingerprint serialization roundtrip") {
    val fp = FileFingerprint(mtime = 1700000000000L, size = 1024L, hash = "aabbccddeeff")
    val json = fp.asJson.noSpaces
    val decoded = decode[FileFingerprint](json)
    assertEquals(decoded, Right(fp), "Roundtrip should preserve all fields")
  }

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
    assert(id.deviceId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
      s"deviceId should be UUID format, got: ${id.deviceId}")
  }

  test("DeviceIdentity platform is one of expected values") {
    val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
    assert(Set("macos", "windows", "linux", "unknown").contains(id.platform),
      s"platform should be recognized OS, got: ${id.platform}")
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
    assert(peer.lastSeen >= before && peer.lastSeen <= after,
      s"lastSeen ${peer.lastSeen} should be between $before and $after")
  }

  test("PeerInfo default deviceSecret is empty string") {
    val peer = PeerInfo("d", "n", "p", "a")
    assertEquals(peer.deviceSecret, "", "Default deviceSecret should be empty")
  }

  // ===== SyncDiff =====

  test("SyncDiff serialization roundtrip") {
    val diff = SyncDiff(
      needUpload = List("a.md", "b.md"),
      needDownload = List("c.md"),
      unchanged = List("d.md")
    )
    val json = diff.asJson.noSpaces
    val decoded = decode[SyncDiff](json)
    assertEquals(decoded, Right(diff), "Roundtrip should preserve all fields")
  }

  test("SyncDiff empty lists serialize correctly") {
    val diff = SyncDiff(Nil, Nil, Nil)
    val json = diff.asJson.noSpaces
    val decoded = decode[SyncDiff](json)
    assertEquals(decoded, Right(diff))
  }

  // ===== MeshConfig =====

  test("MeshConfig default values") {
    val cfg = MeshConfig()
    assertEquals(cfg.enabled, false, "Default enabled should be false")
    assertEquals(cfg.syncIntervalSec, 300, "Default sync interval should be 300 seconds")
    assert(cfg.cloudUrl.isDefined, "Default cloud URL should be defined")
    assert(cfg.cloudUrl.get.contains("cloudbase"), "Default cloud URL should point to CloudBase")
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
