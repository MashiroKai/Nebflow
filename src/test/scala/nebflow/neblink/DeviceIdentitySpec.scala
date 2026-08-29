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
