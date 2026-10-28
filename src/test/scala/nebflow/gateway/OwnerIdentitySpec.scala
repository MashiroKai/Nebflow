package nebflow.gateway

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.bridge.OwnerAnchor
import nebflow.neblink.{DeviceCredential, LogtoRefresh}
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64

/**
 * owner 等值判据的引擎侧锚（门户身份透传批·方案 A2/A4，2026-10-28）。
 *
 * 钉三件事：① `anchorFromIdToken` 纯函数面——sub 在场/缺席/空白/坏 token 的
 * decode 形态 + 标签词表（name → email 本地段 → 「门户账号」，sub/email 永不出
 * 本面）；② `localAnchor` 缓存面——token 键控（同 token 复用读数，换 token 重
 * 解码）、无凭据/坏档 fail-open None；③ 锚读数喂 `DeviceProvenance.resolve`
 * 的 owner 等值链在**真实凭据文件**形态下走通（隔离 data-root，零真机读）。
 */
class OwnerIdentitySpec extends FunSuite:

  private def b64url(s: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8))

  /** 铸一枚形似 id_token 的 JWT（解码面只 base64 解 payload，不验签）。 */
  private def idToken(payloadJson: String): String =
    s"${b64url("""{"alg":"HS256","typ":"JWT"}""")}.${b64url(payloadJson)}.sig-not-verified"

  private def claimsJson(pairs: (String, String)*): String =
    pairs.map { case (k, v) => s""""$k":"$v"""" }.mkString("{", ",", "}")

  // ── ① anchorFromIdToken：纯函数面 ──────────────────────────────────────

  test("anchorFromIdToken: sub present ⇒ OwnerAnchor with the name-claim label (sub never leaks into the label)") {
    val anchor = OwnerIdentity.anchorFromIdToken(idToken(claimsJson("sub" -> "sub-owner-1", "name" -> "Mashiro", "email" -> "kaimashiro@qq.com")))
    assertEquals(anchor, Some(OwnerAnchor("sub-owner-1", "Mashiro")))
  }

  test("anchorFromIdToken: missing / blank sub ⇒ None (no anchor ⇒ owner tier absent, default name fallback)") {
    assertEquals(OwnerIdentity.anchorFromIdToken(idToken(claimsJson("name" -> "Mashiro"))), None, "sub 缺席")
    assertEquals(OwnerIdentity.anchorFromIdToken(idToken(claimsJson("sub" -> "   "))), None, "sub 空白")
  }

  test("anchorFromIdToken: malformed token decodes to None (never throws)") {
    assertEquals(OwnerIdentity.anchorFromIdToken("not-a-jwt"), None)
    assertEquals(OwnerIdentity.anchorFromIdToken(""), None)
    assertEquals(OwnerIdentity.anchorFromIdToken("aGkh.sig"), None, "payload 非 JSON")
  }

  test("anchorFromIdToken: label chain name → email local-part → 门户账号 (A4 P0 词表)") {
    assertEquals(
      OwnerIdentity.anchorFromIdToken(idToken(claimsJson("sub" -> "s", "email" -> "kaimashiro@qq.com"))),
      Some(OwnerAnchor("s", "kaimashiro")),
      "name 缺席 ⇒ email 本地段"
    )
    assertEquals(
      OwnerIdentity.anchorFromIdToken(idToken(claimsJson("sub" -> "s"))),
      Some(OwnerAnchor("s", "门户账号")),
      "两源皆缺 ⇒ 兜底词"
    )
  }

  // ── ② localAnchor：缓存 + fail-open（隔离 data-root，零真机读）──────────

  private var tmpDir: Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: munit.BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-owner-identity-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    OwnerIdentity.resetCache()

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    OwnerIdentity.resetCache()
    super.afterEach(context)

  private def persist(idToken: Option[String]): Unit =
    DeviceCredential
      .save(DeviceCredential("https://neblink.example", "n-1", "d-1", "tok", Some(LogtoRefresh("", 0L, idToken))))
      .unsafeRunSync()

  test("localAnchor: reads the persisted id_token → anchor; token change ⇒ re-decode (token-keyed cache); no credential ⇒ None") {
    persist(Some(idToken(claimsJson("sub" -> "sub-A", "name" -> "Mashiro"))))
    assertEquals(OwnerIdentity.localAnchor.unsafeRunSync(), Some(OwnerAnchor("sub-A", "Mashiro")), "首读：解码落锚")

    // 同 token 二读 = 缓存命中（读数恒等；可观测面为值语义）。
    assertEquals(OwnerIdentity.localAnchor.unsafeRunSync(), Some(OwnerAnchor("sub-A", "Mashiro")))

    // 换 token（重新登录轮换）⇒ 缓存键失效 ⇒ 新读数。
    persist(Some(idToken(claimsJson("sub" -> "sub-B", "name" -> "Kai"))))
    assertEquals(OwnerIdentity.localAnchor.unsafeRunSync(), Some(OwnerAnchor("sub-B", "Kai")), "token 变 ⇒ 重解码")

    // 凭据清空（登出形态）⇒ 无锚 ⇒ owner 级整环缺席。
    DeviceCredential.save(DeviceCredential("https://neblink.example", "n-1", "d-1", "tok", None)).unsafeRunSync()
    assertEquals(OwnerIdentity.localAnchor.unsafeRunSync(), None, "无凭据 fail-open None")
  }

  // ── ③ 真实凭据文件形态下的 owner 等值链 ────────────────────────────────

  test("end-to-end anchor → resolve: persisted id_token owner sub matches the session ⇒ 已认证 marker; other sub ⇒ default") {
    persist(Some(idToken(claimsJson("sub" -> "sub-real-owner", "name" -> "Mashiro"))))
    val anchor = OwnerIdentity.localAnchor.unsafeRunSync().getOrElse(fail("expected anchor"))
    val client = nebflow.bridge.ClientDevice("c", Some("mac"), Some("Safari"), None)
    val ownerRead = nebflow.bridge.DeviceProvenance.resolve(
      client, None, Nil,
      Some(nebflow.bridge.SessionIdentity("sub-real-owner", "relay-mint", "s1")), Some(anchor)
    )
    assertEquals(ownerRead.sessionAuthenticated, true)
    assert(nebflow.bridge.DeviceProvenance.marker(ownerRead).contains("Mashiro · Safari（经官网）（已认证）"))

    val otherRead = nebflow.bridge.DeviceProvenance.resolve(
      client, None, Nil,
      Some(nebflow.bridge.SessionIdentity("sub-other", "relay-mint", "s2")), Some(anchor)
    )
    assertEquals(otherRead.display, "未登记-Mac Safari", "非 owner sub ⇒ 默认名兜底逐字")
  }
