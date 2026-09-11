package nebflow.neblink

import munit.FunSuite
import io.circe.parser.decode
import io.circe.syntax.*
import cats.effect.unsafe.implicits.global

/** DeviceIdentity decode regression (2026-08-30 E2E 403 root cause).
  *
  * deriveDecoder does not honor Scala defaults — a device.json written by
  * hand/backup (no `capabilities` key) silently failed the whole decode and
  * loadOrCreate fell back to a fresh random identity every boot ("403
  * Invalid device credential"; the decode-failure path never persists, so
  * the file stayed pristine and the bug was invisible).
  */
class DeviceIdentitySpec extends FunSuite:

  private val minimal = """{"deviceId":"fb104d44-7438-4e88-845e-c23d44f17b98","deviceName":"nb-e2e-gateway","platform":"macos","deviceSecret":"e2e-secret"}"""

  test("minimal heredoc shape (no capabilities) decodes with defaults") {
    val r = decode[DeviceIdentity](minimal)
    assertEquals(r.isRight, true, s"decode failed: $r")
    r.foreach { id =>
      assertEquals(id.deviceId, "fb104d44-7438-4e88-845e-c23d44f17b98")
      assertEquals(id.deviceSecret, "e2e-secret")
      assertEquals(id.capabilities, Map.empty[String, String])
      assertEquals(id.userDescription, "")
      assertEquals(id.avatarUrl, None)
    }
  }

  test("full gateway-written shape (all fields) still decodes") {
    val raw = """{"deviceId":"d1","deviceName":"n","platform":"macos","deviceSecret":"s","capabilities":{"stt":"browser"},"userDescription":"hi","avatarUrl":"http://x/a.png","githubLogin":"octocat"}"""
    val r = decode[DeviceIdentity](raw)
    assertEquals(r.isRight, true, s"decode failed: $r")
    r.foreach { id =>
      assertEquals(id.capabilities, Map("stt" -> "browser"))
      assertEquals(id.userDescription, "hi")
      assertEquals(id.avatarUrl, Some("http://x/a.png"))
      assertEquals(id.githubLogin, Some("octocat"))
    }
  }

  test("round-trip: encode(minimal-decoded) decodes again") {
    val id = decode[DeviceIdentity](minimal).getOrElse(fail("decode failed"))
    val again = decode[DeviceIdentity](id.asJson.spaces2)
    assertEquals(again.isRight, true, s"round-trip failed: $again")
    assertEquals(again.map(_.deviceId), Right(id.deviceId))
  }

  test("loadOrCreate reads the redirected dataRoot identity (no createNew)") {
    val home = os.temp.dir()
    val prev = nebflow.core.PathUtil.dataRoot
    nebflow.core.PathUtil.setDataRoot(home)
    try
      os.write(home / "device.json", minimal)
      val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
      assertEquals(id.deviceId, "fb104d44-7438-4e88-845e-c23d44f17b98")
      // valid file with secret+name -> must NOT be rewritten
      assertEquals(os.read(home / "device.json"), minimal)
    finally nebflow.core.PathUtil.setDataRoot(prev)
  }

  // ===== 2026-09-11 device_id hardening (machine-code derivation + self-heal) =====

  private def withDataRoot[A](home: os.Path)(f: => A): A =
    val prev = nebflow.core.PathUtil.dataRoot
    nebflow.core.PathUtil.setDataRoot(home)
    try f
    finally nebflow.core.PathUtil.setDataRoot(prev)

  test("deriveDeviceId: same (machineCode, scope) -> same value; different inputs differ") {
    val a1 = DeviceIdentity.deriveDeviceId("machine-code-fixture", "default")
    val a2 = DeviceIdentity.deriveDeviceId("machine-code-fixture", "default")
    assertEquals(a1, a2, "same (machineCode, scope) must derive the same id")
    val otherMachine = DeviceIdentity.deriveDeviceId("machine-code-fixture-2", "default")
    assertNotEquals(a1, otherMachine, "different machine code must derive a different id")
    val otherScope = DeviceIdentity.deriveDeviceId("machine-code-fixture", "/tmp/qa-x")
    assertNotEquals(a1, otherScope, "different scope must derive a different id")
    assert(
      a1.matches("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"),
      s"derived id must be a v5 UUID string, got: $a1"
    )
  }

  test("uuidV5 matches the RFC 4122 name-based (SHA-1) test vector") {
    val dns = java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    assertEquals(
      DeviceIdentity.uuidV5(dns, "www.example.com").toString,
      "2ed6657d-e927-568b-95e1-2665a8aea6a2"
    )
  }

  test("machine code: placeholders / blank are not identifiers (random fallback instead)") {
    assertEquals(DeviceIdentity.sanitizeMachineCode(Some("uninitialized")), None)
    assertEquals(DeviceIdentity.sanitizeMachineCode(Some("  ")), None)
    assertEquals(DeviceIdentity.sanitizeMachineCode(None), None)
    // a real code is only trimmed, never rewritten (determinism depends on it)
    assertEquals(DeviceIdentity.sanitizeMachineCode(Some(" ABC-123 ")), Some("ABC-123"))
  }

  test("scope: default data root -> 'default'; redirected root -> the root path") {
    withDataRoot(os.home / nebflow.core.Branding.homeDirName) {
      assertEquals(DeviceIdentity.isNonDefaultHome, false)
      assertEquals(DeviceIdentity.deviceIdScope, DeviceIdentity.DefaultScope)
    }
    val home = os.temp.dir()
    withDataRoot(home) {
      assertEquals(DeviceIdentity.isNonDefaultHome, true)
      assertEquals(DeviceIdentity.deviceIdScope, home.toString)
      // 隔离实例有意派生不同 id（与作者主客户端不撞身份 / 不互踢）
      assertNotEquals(
        DeviceIdentity.deriveDeviceId("machine-code-fixture", DeviceIdentity.deviceIdScope),
        DeviceIdentity.deriveDeviceId("machine-code-fixture", DeviceIdentity.DefaultScope)
      )
    }
  }

  test("acceptance 1: corrupt device.json -> 3 boots yield the SAME deviceId; corrupt file backed up") {
    val home = os.temp.dir()
    withDataRoot(home) {
      val corrupt = """{"deviceId":"fb104d44-7438-4e88-845e-c23d44f17b98","deviceName":"""
      os.write(home / "device.json", corrupt)
      val ids = (1 to 3).map(_ => DeviceIdentity.loadOrCreate.unsafeRunSync().deviceId)
      assertEquals(ids.distinct.size, 1, s"3 boots after corruption must agree, got $ids")
      // the damaged file is preserved aside (one backup, first boot only)
      val backups = os.list(home).filter(_.last.startsWith("device.json.corrupt-"))
      assertEquals(backups.size, 1, s"expected exactly one backup, got ${backups.map(_.last)}")
      assertEquals(os.read(backups.head), corrupt, "backup must hold the damaged original")
      // and the live file is a decodable identity again (no more sticky re-mint)
      val live = decode[DeviceIdentity](os.read(home / "device.json"))
      assertEquals(live.isRight, true, s"re-minted device.json must decode: $live")
      assertEquals(live.toOption.map(_.deviceId), Some(ids.head))
    }
  }

  test("acceptance 1b: missing device.json -> 3 boots yield the SAME (derived) deviceId") {
    val home = os.temp.dir()
    withDataRoot(home) {
      val ids = (1 to 3).map(_ => DeviceIdentity.loadOrCreate.unsafeRunSync().deviceId)
      assertEquals(ids.distinct.size, 1, s"3 boots on a fresh home must agree, got $ids")
      assert(os.exists(home / "device.json"), "the minted identity must be persisted")
      assertEquals(
        decode[DeviceIdentity](os.read(home / "device.json")).toOption.map(_.deviceId),
        Some(ids.head)
      )
    }
  }

  test("acceptance 5: legacy migrations still persist once (secret / .local name)") {
    val home = os.temp.dir()
    withDataRoot(home) {
      val legacy =
        """{"deviceId":"fb104d44-7438-4e88-845e-c23d44f17b98","deviceName":"mb.local","platform":"macos"}"""
      os.write(home / "device.json", legacy)
      val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
      assertEquals(id.deviceId, "fb104d44-7438-4e88-845e-c23d44f17b98", "id must not be re-minted")
      assertEquals(id.deviceName, "mb", "'.local' mDNS suffix must be stripped")
      assert(id.deviceSecret.nonEmpty, "deviceSecret must be backfilled")
      val saved = decode[DeviceIdentity](os.read(home / "device.json")).toOption
      assertEquals(saved.map(_.deviceSecret.isEmpty), Some(false), "migration must be persisted")
      assertEquals(saved.map(_.deviceName), Some("mb"))
      // second boot: nothing left to migrate -> file untouched
      val afterFirst = os.read(home / "device.json")
      DeviceIdentity.loadOrCreate.unsafeRunSync()
      assertEquals(os.read(home / "device.json"), afterFirst)
    }
  }

  test("acceptance 4 (negative control): isolated root + prod host refused; switch / other hosts pass") {
    val prod = "https://neblink.nebflow.space"
    // isolated data root + production host + no switch -> REFUSED
    assert(nebflow.neblink.EnrollGuard.enrollRefusal(prod, true, false).isDefined)
    // apex domain and subdomains both count as production
    assert(nebflow.neblink.EnrollGuard.enrollRefusal("https://nebflow.space", true, false).isDefined)
    assert(nebflow.neblink.EnrollGuard.enrollRefusal("http://auth.nebflow.space:443", true, false).isDefined)
    // explicit switch -> allowed (old behaviour)
    assertEquals(nebflow.neblink.EnrollGuard.enrollRefusal(prod, true, true), None)
    // default data root -> allowed (zero behaviour change)
    assertEquals(nebflow.neblink.EnrollGuard.enrollRefusal(prod, false, false), None)
    // local / custom host -> allowed (small blast radius)
    assertEquals(nebflow.neblink.EnrollGuard.enrollRefusal("http://127.0.0.1:8095", true, false), None)
    assertEquals(nebflow.neblink.EnrollGuard.enrollRefusal("https://nb.example.com", true, false), None)
    // live read: the default data root must never be refused (author's client)
    withDataRoot(os.home / nebflow.core.Branding.homeDirName) {
      assertEquals(nebflow.neblink.EnrollGuard.enrollRefusal(prod), None)
    }
    // live read: a redirected root is refused for production…
    withDataRoot(os.temp.dir()) {
      assert(nebflow.neblink.EnrollGuard.enrollRefusal(prod).isDefined)
      // …and the refusal is loud, not silent
      assert(nebflow.neblink.EnrollGuard.enrollRefusal(prod).exists(_.contains("NEBFLOW_ALLOW_PROD_ENROLL")))
    }
  }
