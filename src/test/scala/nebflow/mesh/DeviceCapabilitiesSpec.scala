package nebflow.mesh

import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Tests for device capabilities and new DeviceIdentity/PeerInfo fields. */
class DeviceCapabilitiesSpec extends CatsEffectSuite:

  // ===== DeviceIdentity with capabilities =====

  test("DeviceIdentity with capabilities serialization roundtrip") {
    val id = DeviceIdentity(
      deviceId = "dev-123",
      deviceName = "TestMac (macOS)",
      platform = "macos",
      deviceSecret = "secret",
      capabilities = Map("python" -> "/usr/bin/python3", "git" -> "/usr/bin/git"),
      userDescription = "校园网环境，可访问学术数据库"
    )
    val json = id.asJson.noSpaces
    val decoded = decode[DeviceIdentity](json)
    assertEquals(decoded, Right(id), "Roundtrip should preserve capabilities and userDescription")
  }

  test("DeviceIdentity default capabilities is empty map") {
    val id = DeviceIdentity("d", "n", "p", "s")
    assertEquals(id.capabilities, Map.empty, "Default capabilities should be empty")
    assertEquals(id.userDescription, "", "Default userDescription should be empty")
  }

  test("DeviceIdentity JSON contains capabilities field") {
    val id = DeviceIdentity("d", "n", "p", "s", Map("vivado" -> "/opt/xilinx/vivado"), "Lab PC")
    val json = id.asJson
    assert(json.hcursor.downField("capabilities").as[Map[String, String]].isRight, "JSON should contain capabilities")
    assert(json.hcursor.downField("userDescription").as[String].isRight, "JSON should contain userDescription")
  }

  test("DeviceIdentity old JSON without capabilities fails decode — handled by loadOrCreate migration") {
    // deriveDecoder doesn't use default values for missing fields.
    // This is OK: loadOrCreate catches decode failure and creates a fresh identity.
    val oldJson = """{"deviceId":"d","deviceName":"n","platform":"p","deviceSecret":"s"}"""
    val decoded = decode[DeviceIdentity](oldJson)
    // Decoding fails because capabilities/userDescription are missing
    assert(decoded.isLeft, "Old JSON without capabilities should fail to decode (handled by migration)")
  }

  // ===== detectCapabilities =====

  test("detectCapabilities returns a map (may be empty on CI)") {
    val caps = DeviceIdentity.detectCapabilities.unsafeRunSync()
    // We can't guarantee any specific tool is installed, but the method should not crash
    // and should return a Map. On most dev machines, at least git or python should exist.
    assert(caps != null, "detectCapabilities should return a non-null map")
    // On typical dev machines, git is usually present
    if caps.nonEmpty then assert(caps.values.forall(_.nonEmpty), "All detected paths should be non-empty")
  }

  // ===== PeerInfo with capabilities =====

  test("PeerInfo with capabilities serialization roundtrip") {
    val peer = PeerInfo(
      deviceId = "peer-1",
      deviceName = "Lab PC (Windows)",
      platform = "windows",
      address = "http://10.0.0.5:8080",
      deviceSecret = "peer-secret",
      capabilities = Map("vivado" -> "C:\\xilinx\\vivado.bat", "python" -> "C:\\Python39\\python.exe"),
      userDescription = "校园网，有 Vivado 2023.2"
    )
    val json = peer.asJson.noSpaces
    val decoded = decode[PeerInfo](json)
    assertEquals(decoded, Right(peer), "Roundtrip should preserve capabilities and userDescription")
  }

  test("PeerInfo old JSON without capabilities decodes with defaults") {
    // The relay server may not return capabilities/userDescription/lastSeen for old records.
    // The decoder should tolerate missing fields with sensible defaults.
    val oldJson =
      """{"deviceId":"d","deviceName":"n","platform":"p","address":"a","deviceSecret":"s"}"""
    val decoded = decode[PeerInfo](oldJson)
    assert(decoded.isRight, "Old PeerInfo JSON without optional fields should decode with defaults")
    val pi = decoded.toOption.get
    assertEquals(pi.capabilities, Map.empty[String, String])
    assertEquals(pi.userDescription, "")
  }

  // ===== Relay data types =====

  test("RelayCommand decodes from JSON") {
    val json = """{"relayId":"r-1","fromDeviceId":"dev-a","action":"Bash","params":{"command":"ls"}}"""
    val decoded = decode[nebflow.mesh.RelayCommand](json)
    assert(decoded.isRight, "RelayCommand should decode")
    val cmd = decoded.toOption.get
    assertEquals(cmd.relayId, "r-1")
    assertEquals(cmd.action, "Bash")
    assertEquals(cmd.fromDeviceId, "dev-a")
  }

end DeviceCapabilitiesSpec
