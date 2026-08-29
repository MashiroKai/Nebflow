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

  // ===== Logto embedded default (2026-08-28 distribution-gap fix) =====

  test("embeddedDefault carries the product hosted-auth constants") {
    // Product infrastructure constants (public client ids) — pinned here so
    // accidental edits surface in CI. config.json logto block can override.
    assertEquals(LogtoConfig.embeddedDefault.endpoint, "https://auth.neblink.space")
    assertEquals(LogtoConfig.embeddedDefault.pkceClientId, Some("csxh16cas0x03bgk6w7ej"))
  }

  test("effectiveLogto falls back to embeddedDefault when config has no logto block") {
    val cfg = decode[NeblinkConfig]("{}")
    assertEquals(cfg.isRight, true, "empty config object should decode")
    val eff = cfg.map(_.effectiveLogto)
    assertEquals(eff, Right(Some(LogtoConfig.embeddedDefault)))
    // Exact production values, not just object equality (belt and braces).
    eff.foreach(_.foreach { lc =>
      assertEquals(lc.endpoint, "https://auth.neblink.space")
      assertEquals(lc.pkceClientId, Some("csxh16cas0x03bgk6w7ej"))
    })
  }

  test("effectiveLogto prefers an explicit logto block over the embedded default") {
    val json =
      """{"logto":{"endpoint":"https://my-own-logto.example","clientId":"my-dev-app","pkceClientId":"my-pkce-app"}}"""
    val cfg = decode[NeblinkConfig](json)
    assertEquals(cfg.isRight, true, "explicit logto block should decode")
    val eff = cfg.map(_.effectiveLogto)
    assertEquals(
      eff,
      Right(Some(LogtoConfig("https://my-own-logto.example", "my-dev-app", Some("my-pkce-app")))),
      "explicit config.json values must win over the embedded default"
    )
  }

  test("logto block with only endpoint + pkceClientId decodes (clientId optional)") {
    // 2026-08-30 login-blocked root cause: the decoder required `clientId`,
    // so a hand-written {endpoint, pkceClientId} block failed the WHOLE logto
    // decode -> raw logto=None -> PKCE callback reported "Logto 登录未配置"
    // even though /auth/start worked via the embedded default.
    val json = """{"logto":{"endpoint":"https://auth.neblink.space","pkceClientId":"csxh16cas0x03bgk6w7ej"}}"""
    val cfg = decode[NeblinkConfig](json)
    assertEquals(cfg.isRight, true, "clientId-less logto block must decode")
    cfg.foreach { c =>
      assertEquals(c.logto.isDefined, true, "logto block must not silently vanish")
      assertEquals(c.logto.map(_.clientId), Some(""))
      assertEquals(c.logto.flatMap(_.pkceClientId), Some("csxh16cas0x03bgk6w7ej"))
      // The PKCE callback now reads effectiveLogto — must resolve to the
      // explicit block (endpoint + pkceClientId intact).
      assertEquals(c.effectiveLogto, c.logto)
    }
  }

  test("raw logto field stays None without a block (device-flow legacy proxy unaffected)") {
    // The device-flow endpoints keep reading raw `logto` so their
    // no-provider branch (neblink-server device proxy) stays reachable —
    // only the PKCE chain gets the embedded-default fallback.
    val cfg = decode[NeblinkConfig]("{}")
    assertEquals(cfg.map(_.logto), Right(None))
  }

end NeblinkModelSpec
