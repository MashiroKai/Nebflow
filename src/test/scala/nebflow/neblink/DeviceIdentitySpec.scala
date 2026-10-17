package nebflow.neblink

import munit.FunSuite
import io.circe.parser.decode
import io.circe.syntax.*
import cats.effect.unsafe.implicits.global

/**
 * DeviceIdentity decode regression (2026-08-30 E2E 403 root cause).
 *
 * deriveDecoder does not honor Scala defaults — a device.json written by
 * hand/backup (no `capabilities` key) silently failed the whole decode and
 * loadOrCreate fell back to a fresh random identity every boot ("403
 * Invalid device credential"; the decode-failure path never persists, so
 * the file stayed pristine and the bug was invisible).
 */
class DeviceIdentitySpec extends FunSuite:

  private val minimal =
    """{"deviceId":"fb104d44-7438-4e88-845e-c23d44f17b98","deviceName":"nb-e2e-gateway","platform":"macos","deviceSecret":"e2e-secret"}"""

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
    val raw =
      """{"deviceId":"d1","deviceName":"n","platform":"macos","deviceSecret":"s","capabilities":{"stt":"browser"},"userDescription":"hi","avatarUrl":"http://x/a.png","githubLogin":"octocat"}"""
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
    val prev = nebflow.shared.PathUtil.dataRoot
    nebflow.shared.PathUtil.setDataRoot(home)
    try
      os.write(home / "device.json", minimal)
      val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
      assertEquals(id.deviceId, "fb104d44-7438-4e88-845e-c23d44f17b98")
      // valid file with secret+name -> must NOT be rewritten
      assertEquals(os.read(home / "device.json"), minimal)
    finally nebflow.shared.PathUtil.setDataRoot(prev)
  }

  // ===== 2026-09-11 device_id hardening (machine-code derivation + self-heal) =====

  private def withDataRoot[A](home: os.Path)(f: => A): A =
    val prev = nebflow.shared.PathUtil.dataRoot
    nebflow.shared.PathUtil.setDataRoot(home)
    try f
    finally nebflow.shared.PathUtil.setDataRoot(prev)

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

  test("scope: default data root -> 'default'; redirected root -> the isolated home scope (entropy fix)") {
    withDataRoot(os.home / nebflow.shared.Branding.homeDirName) {
      assertEquals(DeviceIdentity.isNonDefaultHome, false)
      assertEquals(DeviceIdentity.deviceIdScope, DeviceIdentity.DefaultScope)
    }
    val home = os.temp.dir()
    withDataRoot(home) {
      assertEquals(DeviceIdentity.isNonDefaultHome, true)
      val scope = DeviceIdentity.deviceIdScope
      // 熵修复（coldstart DX 批④）：隔离 scope = `home|<uuid>` 摘要形态，不再是裸路径。
      assert(scope.startsWith("home|"), s"隔离 scope 形态: $scope")
      assertNotEquals(scope, home.toString, "隔离 scope 不得是裸路径（历史铸造值不得可复现）")
      assert(!scope.contains(home.toString), s"scope 不得内嵌路径原文: $scope")
      assertNotEquals(scope, DeviceIdentity.DefaultScope)
      // 确定性：同根同 scope（冷启动反复重铸得到稳定身份）。
      assertEquals(scope, DeviceIdentity.deviceIdScope)
      // 隔离实例有意派生不同 id（与作者主客户端不撞身份 / 不互踢）
      assertNotEquals(
        DeviceIdentity.deriveDeviceId("machine-code-fixture", DeviceIdentity.deviceIdScope),
        DeviceIdentity.deriveDeviceId("machine-code-fixture", DeviceIdentity.DefaultScope)
      )
    }
  }

  // ===== coldstart DX 批④（2026-10-14 作者令）：device.json 铸造熵修复 =====
  //
  // 事故链：/tmp/nebflow-coldstart 铸出 6a64d249-…（scope=裸路径），该隔离身份后来
  // 落进默认 home 的 device.json ⇒ 同机多 home 撞同一身份。修复 = 隔离 scope 换形态；
  // 硬约束 = 默认 home 的 scope 语义（`default` + 现派生算法）零漂移。

  test("熵修复: 默认 home 的 scope 语义不漂移（派生算法逐字钉死）") {
    // 该常量 = uuidV5(DeviceIdNamespace, "machine-code-fixture|default") 的现值：
    // 算法（v5/SHA-1）、命名空间、`|` 拼接、默认 scope 字面任一变动都会打破本钉 ——
    // 默认 home 一旦重铸将得到另一个 id（生产宿主换身份 = 事故），故逐字节钉死。
    assertEquals(
      DeviceIdentity.deriveDeviceId("machine-code-fixture", DeviceIdentity.DefaultScope),
      "147e8e20-2bd3-518b-aee0-0027a42b8c02"
    )
  }

  test("熵修复: 同机不同 home scope ⇒ 不同 id（隔离身份按 home 独立）") {
    val homeA = os.temp.dir()
    val homeB = os.temp.dir()
    val scopeA = DeviceIdentity.isolatedHomeScope(homeA)
    val scopeB = DeviceIdentity.isolatedHomeScope(homeB)
    assertNotEquals(scopeA, scopeB, "不同 home ⇒ 不同 scope")
    assertNotEquals(
      DeviceIdentity.deriveDeviceId("machine-code-fixture", scopeA),
      DeviceIdentity.deriveDeviceId("machine-code-fixture", scopeB),
      "同机不同 home 必须派生不同 id"
    )
    // 纯函数：同根重复推导恒等。
    assertEquals(scopeA, DeviceIdentity.isolatedHomeScope(homeA))
    // 形态级不相交：隔离 scope 既非 default 也非裸路径 ⇒ 与历史派生输入空间零交集。
    assert(scopeA.startsWith("home|") && scopeB.startsWith("home|"))
    assert(!DeviceIdentity.DefaultScope.startsWith("home|"))
  }

  test("熵修复: 冷启动事故路径的旧铸造不再可复现（/tmp/nebflow-coldstart ⇒ 换牌）") {
    // 6a64d249-e526-52b1-b5b6-3b19ea029b26 = uuidV5(真机码, "/tmp/nebflow-coldstart")
    // （裸路径 scope 时代的铸造，事故实录见主 home 与 /tmp/nebflow-coldstart 的 device.json）。
    // 新 scope 对同一路径必须给出不同派生 ⇒ 任何新铸都不可能再产出宿主旧身份。
    val collisionPath = os.Path("/tmp/nebflow-coldstart")
    val newScope = DeviceIdentity.isolatedHomeScope(collisionPath)
    assertNotEquals(newScope, "/tmp/nebflow-coldstart", "scope 不得等于裸路径（= 旧铸造输入）")
    assertNotEquals(
      DeviceIdentity.deriveDeviceId("machine-code-fixture", newScope),
      DeviceIdentity.deriveDeviceId("machine-code-fixture", "/tmp/nebflow-coldstart"),
      "新 scope 的派生必须与裸路径 scope 的派生（= 6a64d249 的铸造形态）不同"
    )
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
    withDataRoot(os.home / nebflow.shared.Branding.homeDirName) {
      assertEquals(nebflow.neblink.EnrollGuard.enrollRefusal(prod), None)
    }
    // live read: a redirected root is refused for production…
    withDataRoot(os.temp.dir()) {
      assert(nebflow.neblink.EnrollGuard.enrollRefusal(prod).isDefined)
      // …and the refusal is loud, not silent
      assert(nebflow.neblink.EnrollGuard.enrollRefusal(prod).exists(_.contains("NEBFLOW_ALLOW_PROD_ENROLL")))
    }
  }

  // ===== 案 a（2026-09-20 作者令 · 测试卫生）：隔离实例新铸身份带隔离后缀 =====
  //
  // 事故真形态 = 「同账号 + **同名**第二条设备行」（核查卡 `20260920_214729_seedpath-card`
  // §2 环 1 / §4.2 补强②）：id 早已按 scope 派生，撞的是**显示名**。后缀只作用于新铸，
  // 禁带本机路径（deviceName 随 presence 出网）。

  test("案 a: minted name carries the isolation suffix on a redirected root; id/shape untouched") {
    val scope = "/tmp/nb-iso-fixture"
    val dflt = DeviceIdentity.mintedDeviceName("MyBox", nonDefaultHome = false, scope)
    assertEquals(dflt, "MyBox", "默认 data root：名字逐字不变（零行为变化）")
    val iso = DeviceIdentity.mintedDeviceName("MyBox", nonDefaultHome = true, scope)
    assert(iso.startsWith("MyBox-iso-"), s"隔离后缀形态: $iso")
    assertEquals(iso, DeviceIdentity.mintedDeviceName("MyBox", true, scope), "同一 scope ⇒ 同一后缀（确定性）")
    assertNotEquals(
      iso,
      DeviceIdentity.mintedDeviceName("MyBox", true, "/tmp/nb-iso-other"),
      "不同隔离 home ⇒ 不同后缀（两台隔离实例可分辨）"
    )
    assert(!iso.contains("/") && !iso.contains("tmp"), s"名字会随 presence 出网 ⇒ 禁带路径: $iso")
    val fp = DeviceIdentity.scopeFingerprint(scope)
    assertEquals(fp.length, 8)
    assert(fp.matches("^[0-9a-f]{8}$"), s"指纹 = 8 位十六进制: $fp")
    assertEquals(iso, s"MyBox-iso-$fp")
  }

  test("案 a live: a fresh identity minted on a redirected root is suffixed, pathless, capability-free") {
    val home = os.temp.dir()
    withDataRoot(home) {
      val id = DeviceIdentity.loadOrCreate.unsafeRunSync()
      // 后缀指纹 = 现 scope（熵修复后为 `home|<uuid>` 摘要，不再是裸路径）的指纹。
      val fp = DeviceIdentity.scopeFingerprint(DeviceIdentity.deviceIdScope)
      assert(id.deviceName.endsWith(s"-iso-$fp"), id.deviceName)
      assert(!id.deviceName.contains(home.toString), s"名字里不得出现数据根路径: ${id.deviceName}")
      // id 仍是 UUIDv5(machineCode|scope) 形态（本批不动 id）
      assert(id.deviceId.matches("^[0-9a-f-]{36}$"), id.deviceId)
      // 新铸身份零 capabilities（真机工具路径清单不经铸造面进隔离实例）
      assertEquals(id.capabilities, Map.empty[String, String])
      // 第二次读（既有可解码文件）不改名：loadOnce 的「沿用既有身份」语义未动
      assertEquals(DeviceIdentity.loadOrCreate.unsafeRunSync().deviceName, id.deviceName)
    }
  }
end DeviceIdentitySpec
