package nebflow.neblink

import cats.effect.unsafe.implicits.global
import io.circe.Decoder
import io.circe.parser.{decode, parse}
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/** O5 配套修法批（2026-09-11, o5fix）— device.json `logto` 块的「身份与 refresh 令牌
  * 解耦」验收钉。
  *
  * 缺陷形态（O5 连带回归，独立复核 n-c77627a2 §2①）：O5 后 authorize 不再请求
  * `offline_access` ⇒ `LogtoAuthCode.parseTokenResponse` 的 refreshToken 恒 None ⇒
  * `NeblinkEnrollment.persist` 的 `logtoBlock = logtoRefresh.map(...)` 恒 None ⇒
  * 「id_token 只在 logto 块内落盘」变成永不落盘 ⇒ `/api/neblink/status` 的
  * `cred.flatMap(_.logto.flatMap(_.idToken))` 恒空 ⇒ 前端换账号记忆（neblink.js:133
  * `if (loggedIn && device.email)`）全灭 + logout `id_token_hint` 恒缺。
  *
  * 本 spec 钉的契约（每条对应一个可二值读的断言）：
  *  R1 身份面：无 refresh 令牌时必须能落盘 id_token，且**生产读者的取数表达式**
  *     （`_.logto.flatMap(_.idToken)`）非空；
  *  R2 文件形态：identity-only 块仍写出 `refreshToken` 键（空串 = 明确「无令牌」标记）
  *     —— 该键的存在正是「旧 decoder 仍能读新文件」的前提；
  *  R3 双向兼容读：pre-fix 的 `LogtoRefresh` / `DeviceCredential` decoder（要求
  *     `refreshToken` 为 String）读新文件成功（旧读新）；新 decoder 读缺键块成功
  *     （schema 可选读）；pre-O5 完整块读回两半（新读旧）；
  *  R4 空块归一：既无 refresh 令牌又无 id_token 的 logto 对象不产生
  *     `logto.isDefined` 的假信号。
  *
  * 红线：本 spec 只落临时 device.json 样本；样本里的 id_token 是**合成串**
  * （非真实凭据），断言里不打印任何令牌值。
  */
class DeviceCredentialIdentitySpec extends FunSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-device-identity-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    // 只删临时目录；不触碰任何真实 ~/.nebflow 数据根。
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def credPath: os.Path = os.Path(tmpDir, os.pwd) / "neblink" / "device.json"

  /** 合成 id_token（结构与真实 id_token 同形，claim 值无意义，非凭据）。 */
  private def syntheticIdToken(email: String): String =
    val payload = Base64.getUrlEncoder.withoutPadding.encodeToString(
      s"""{"email":"$email","name":"Synthetic"}""".getBytes("UTF-8")
    )
    s"header.$payload.signature"

  private def writeRaw(json: String): Unit =
    os.write.over(credPath, json, createFolders = true)

  private def rawOnDisk: String = os.read(credPath)

  private def baseCred(logto: Option[LogtoRefresh]): DeviceCredential =
    DeviceCredential("https://neblink.example", "net-1", "dev-1", "tok-secret", logto)

  // ── pre-fix decoders（本次改动之前的形态，逐字复刻用于兼容读钉） ──────────

  /** 2026-09-06 之后、本批之前的 decoder：`refreshToken` 必填 String。 */
  private val preFixLogtoRefreshDecoder: Decoder[LogtoRefresh] = Decoder.instance { c =>
    for
      refreshToken <- c.downField("refreshToken").as[String]
      updatedAt <- c.downField("updatedAt").as[Long]
      idToken <- c.downField("idToken").as[Option[String]]
    yield LogtoRefresh(refreshToken, updatedAt, idToken)
  }

  /** stage-2 时代（无 idToken 字段）的 decoder：只认 refreshToken + updatedAt。 */
  private val preIdTokenLogtoRefreshDecoder: Decoder[LogtoRefresh] = Decoder.instance { c =>
    for
      refreshToken <- c.downField("refreshToken").as[String]
      updatedAt <- c.downField("updatedAt").as[Long]
    yield LogtoRefresh(refreshToken, updatedAt, None)
  }

  private val preFixCredentialDecoder: Decoder[DeviceCredential] = Decoder.instance { c =>
    for
      serverUrl <- c.downField("serverUrl").as[String]
      networkId <- c.downField("networkId").as[String]
      deviceId <- c.downField("deviceId").as[String]
      deviceToken <- c.downField("deviceToken").as[String]
      logto <- c.downField("logto").as[Option[LogtoRefresh]](
        using Decoder.decodeOption(using preFixLogtoRefreshDecoder)
      )
    yield DeviceCredential(serverUrl, networkId, deviceId, deviceToken, logto)
  }

  // ── R1 身份面 ──────────────────────────────────────────────────────────

  test("R1 identity-only block (id_token, no refresh token) round-trips and feeds the production reader expression") {
    val idTok = syntheticIdToken("o5fix@example.invalid")
    val block = LogtoRefresh.of(None, Some(idTok)).getOrElse(fail("identity-only block was dropped"))
    DeviceCredential.save(baseCred(Some(block))).unsafeRunSync()

    val loaded = DeviceCredential.load.unsafeRunSync().getOrElse(fail("credential did not load"))
    // 生产读者的取数表达式（RestApiRoutes status :715 / logout :839 逐字同形）：
    assertEquals(loaded.logto.flatMap(_.idToken), Some(idTok))
    assert(loaded.logto.isDefined, "logto block must survive without a refresh token")
  }

  test("R1 an id_token alone is enough to create a block; neither half ⇒ no block at all") {
    assert(LogtoRefresh.of(None, Some("id-only")).isDefined)
    assert(LogtoRefresh.of(Some("rt-only"), None).isDefined)
    assertEquals(LogtoRefresh.of(None, None), None)
    // 空串按「缺省」处理（不做伪令牌）
    assertEquals(LogtoRefresh.of(Some(""), Some("")), None)
  }

  // ── R2 文件形态 ────────────────────────────────────────────────────────

  test("R2 the persisted logto block keeps an explicit empty refreshToken marker (not a dropped key)") {
    DeviceCredential
      .save(baseCred(LogtoRefresh.of(None, Some(syntheticIdToken("shape@example.invalid")))))
      .unsafeRunSync()

    val json = parse(rawOnDisk).toOption.getOrElse(fail("device.json is not JSON"))
    val logto = json.hcursor.downField("logto")
    assert(logto.succeeded, "logto block missing")
    assertEquals(
      logto.downField("refreshToken").as[String].toOption,
      Some(""),
      "refreshToken key must stay present as the empty 'no token held' marker"
    )
    assert(logto.downField("updatedAt").as[Long].toOption.isDefined, "updatedAt missing")
    assert(logto.downField("idToken").as[String].toOption.isDefined, "idToken missing")
    // 脱敏：文件里不得出现 offline_access 之类的请求面残留
    assert(!rawOnDisk.contains("offline_access"), "credential file leaked a scope string")
  }

  // ── R3 双向兼容读 ──────────────────────────────────────────────────────

  test("R3 旧读新: a pre-fix DeviceCredential decoder still decodes the new identity-only file") {
    DeviceCredential
      .save(baseCred(LogtoRefresh.of(None, Some(syntheticIdToken("oldreadsnew@example.invalid")))))
      .unsafeRunSync()

    val decoded = decode[DeviceCredential](rawOnDisk)(using preFixCredentialDecoder)
    assert(decoded.isRight, s"pre-fix decoder failed on the new file: ${decoded.fold(_.toString, _ => "ok")}")
    assertEquals(decoded.toOption.flatMap(_.logto).map(_.refreshToken), Some(""))
    assert(decoded.toOption.flatMap(_.logto).flatMap(_.idToken).isDefined, "identity lost on downgrade read")
  }

  test("R3 旧读新（更早的 stage-2 decoder）: refreshToken + updatedAt 两键仍在 ⇒ 仍可解码") {
    DeviceCredential
      .save(baseCred(LogtoRefresh.of(None, Some(syntheticIdToken("stage2read@example.invalid")))))
      .unsafeRunSync()
    val logtoJson = parse(rawOnDisk).toOption.get.hcursor.downField("logto").focus
      .getOrElse(fail("logto block missing"))
    assert(preIdTokenLogtoRefreshDecoder.decodeJson(logtoJson).isRight)
  }

  test("R3 schema 可选读: a logto block WITHOUT the refreshToken key decodes (empty marker)") {
    writeRaw(
      """{"serverUrl":"https://neblink.example","networkId":"net-1","deviceId":"dev-1",
        | "deviceToken":"tok","logto":{"updatedAt":1,"idToken":"synthetic.id.tok"}}""".stripMargin
    )
    val loaded = DeviceCredential.load.unsafeRunSync().getOrElse(fail("file with optional refreshToken did not load"))
    assertEquals(loaded.logto.map(_.refreshToken), Some(""))
    assertEquals(loaded.logto.flatMap(_.idToken), Some("synthetic.id.tok"))
  }

  test("R3 新读旧: the pre-O5 on-disk shape (real refresh token + id_token) still reads back both halves") {
    writeRaw(
      """{"serverUrl":"https://neblink.example","networkId":"net-1","deviceId":"dev-1",
        | "deviceToken":"tok",
        | "logto":{"refreshToken":"legacy-refresh","updatedAt":42,"idToken":"legacy-id"}}""".stripMargin
    )
    val loaded = DeviceCredential.load.unsafeRunSync().getOrElse(fail("legacy credential did not load"))
    assertEquals(loaded.logto.map(_.refreshToken), Some("legacy-refresh"))
    assertEquals(loaded.logto.flatMap(_.idToken), Some("legacy-id"))
    assertEquals(loaded.logto.map(_.updatedAt), Some(42L))
  }

  test("R3 新读旧: pre-stage-2 file without any logto block decodes to None (unchanged)") {
    writeRaw(
      """{"serverUrl":"https://neblink.example","networkId":"net-1","deviceId":"dev-1","deviceToken":"tok"}"""
    )
    val loaded = DeviceCredential.load.unsafeRunSync().getOrElse(fail("legacy file did not load"))
    assertEquals(loaded.logto, None)
  }

  // ── R4 空块归一 ────────────────────────────────────────────────────────

  test("R4 an information-free logto object is normalised away (no false 'stored credential' signal)") {
    writeRaw(
      """{"serverUrl":"https://neblink.example","networkId":"net-1","deviceId":"dev-1",
        | "deviceToken":"tok","logto":{"refreshToken":"","updatedAt":7}}""".stripMargin
    )
    val loaded = DeviceCredential.load.unsafeRunSync().getOrElse(fail("file did not load"))
    assertEquals(loaded.logto, None)

    // 反之：只要有一半内容，块就保留
    val withId = decode[DeviceCredential](
      """{"serverUrl":"https://neblink.example","networkId":"net-1","deviceId":"dev-1",
        | "deviceToken":"tok","logto":{"refreshToken":"","updatedAt":7,"idToken":"x"}}""".stripMargin
    )
    assert(withId.toOption.flatMap(_.logto).isDefined)
  }

  // ── 写盘 round-trip（身份在文件里，且不引入可续期令牌） ───────────────────

  test("R1/R2 save→load round-trip preserves identity and never fabricates a refresh token") {
    val idTok = syntheticIdToken("roundtrip@example.invalid")
    DeviceCredential
      .save(baseCred(LogtoRefresh.of(None, Some(idTok))))
      .unsafeRunSync()
    val first = DeviceCredential.load.unsafeRunSync().getOrElse(fail("load failed"))
    DeviceCredential.save(first).unsafeRunSync()
    val second = DeviceCredential.load.unsafeRunSync().getOrElse(fail("second load failed"))
    assertEquals(second.logto.flatMap(_.idToken), Some(idTok))
    assertEquals(second.logto.map(_.refreshToken), Some(""))

    // ACL 口径不变：POSIX 上仍为 rw-------（与 DeviceCredentialAclSpec T3-R2 同口径）
    if !System.getProperty("os.name").toLowerCase.contains("win") then
      val perms = Files.getPosixFilePermissions(credPath.toNIO)
      assertEquals(
        PosixFilePermissions.toString(perms),
        "rw-------",
        "identity-bearing device.json must stay owner-only"
      )
  }
end DeviceCredentialIdentitySpec
