package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** kaiauth 修法批 ①（2026-09-16 作者「治本」已批）—— **凭据族 403 自愈腿**的真钉。
  *
  * 缺陷形态（诊断报告 §1-Q3 / §2 表 1）：修前 `reloginAllowed` 只认 `HTTP 401`，而服务端
  * 「凭据被新注册覆盖 / 无凭据行」的踢出语义是 `POST /api/device/session` 的 **403**
  * `{"error":"Invalid device credential"}`（跨仓只读 `neblink-server/src/routes.rs`：
  * `check_device_credential` 非 Ok ⇒ `forbidden(...)`，`NoRow` 与 `Mismatch` 共用同一字面）
  * ⇒ 该 403 **落不到任何自愈腿**，`reloginAllowed(...)` 恒 false ⇒ 每拍只剩
  * `discover → login → 403`。**本钉的判据面 = 进出 hook 的次数**（真服务端语义用夹具复刻，
  * 请求计数由夹具侧记，不读被测算内部字段）。
  *
  * 两侧各自独立（缺一即留盲区）：
  *  - **纯判据层**：`reloginAllowed` 的（状态码 × 调用面 × budget）三维表；
  *  - **全链层**：真 `NeblinkClient` + 真 HTTP 夹具，走 `doLoginUnparked` 的**未改动**
  *    产品代码路径 —— 钉 hook 恰一次、重试的 `allowRelogin=false` 单发不变量、
  *    以及「API 面业务 403 零触碰」（F2 类回归钉）。
  *
  * 边界（作者 2026-09-16 转达 · #685 待裁项① 销项）：**业务类 403 不再自动重登 = 预期
  * 行为**（矩阵另一半 = devoscfix 批的 F-A 收窄），本批**不**把业务 403 纳入自愈。
  */
class NeblinkCredentialFamily403Spec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 60.seconds

  private val Net = "qa-net"
  private val Dev = "qa-device-1"

  /** 真服务端凭据族拒收原文（跨仓只读 `routes.rs`：403 + 该 JSON；客户端侧的
    * `HTTP <code>: <body>` 折形与 `sendRequest` 逐字同形）。 */
  private val Credential403 = """HTTP 403: {"error":"Invalid device credential"}"""

  // ===== 层 1：纯判据三维表（状态码 × 调用面 × budget）=====

  test("gate: 凭据腿（/api/device/session）的 403 ⇒ 进入重登腿（本批新增的那一半）") {
    assert(NeblinkClient.reloginAllowed(Credential403, allowRelogin = true, credentialLeg = true))
    assert(NeblinkClient.isCredentialFamilyRejection(Credential403))
  }

  test("gate: 非凭据腿（legacy /api/device/login）的 403 ⇒ 不进入（与修前逐字一致）") {
    // legacy 腿的 403（服务端同源只读 routes.rs 的 `forbidden(&err)`）**不属**凭据族
    assert(!NeblinkClient.reloginAllowed("HTTP 403: bad secret", allowRelogin = true, credentialLeg = false))
  }

  test("gate: 调用面缺省 = 非凭据腿 ⇒ 403 不通过（防日后从其它面误用本判据）") {
    assert(!NeblinkClient.reloginAllowed(Credential403, allowRelogin = true))
  }

  test("gate: budget 用尽 ⇒ 凭据腿 403 也不通过（单发不变量在闸这一层就成立）") {
    assert(!NeblinkClient.reloginAllowed(Credential403, allowRelogin = false, credentialLeg = true))
  }

  test("gate: 401 语义逐字不变（与调用面无关）") {
    assert(NeblinkClient.reloginAllowed("HTTP 401: device token rejected", allowRelogin = true))
    assert(NeblinkClient.reloginAllowed("HTTP 401", allowRelogin = true, credentialLeg = true))
    assert(!NeblinkClient.reloginAllowed("HTTP 401", allowRelogin = false, credentialLeg = true))
  }

  test("gate: 传输类失败（无 HTTP 前缀 / 5xx）在凭据腿也不通过") {
    List("request timed out", "HTTP connect timed out", "Connection reset by peer", "HTTP 500: boom").foreach {
      err =>
        assert(!NeblinkClient.reloginAllowed(err, allowRelogin = true, credentialLeg = true), s"[$err]")
    }
  }

  test("N2 面分离: 凭据族 403 不进 API 自愈面，业务 403 也不进；令牌型 403 仍进（逐字不变）") {
    // API 自愈面（withSession）的闸 = sessionRecoverable：只认 401 + **含 token 的** 403。
    assert(!NeblinkClient.sessionRecoverable(Credential403), "凭据族 403 不发生在 API 面 ⇒ 该面保持原判据")
    assert(!NeblinkClient.sessionRecoverable("""HTTP 403: {"error":"not_blocker"}"""))
    assert(!NeblinkClient.sessionRecoverable("""HTTP 403: {"error":"not_friend"}"""))
    assert(NeblinkClient.sessionRecoverable("""HTTP 403: {"error":"Missing or invalid token"}"""))
    assert(NeblinkClient.sessionRecoverable("HTTP 401: x"))
    // isUnauthorized 的 401 语义未被本批触碰
    assert(NeblinkClient.isUnauthorized("HTTP 401: x") && !NeblinkClient.isUnauthorized(Credential403))
  }

  // ===== 层 2：全链（真 HTTP 夹具 + 未改动的产品代码路径）=====

  private def withFixture[A](body: RelayAuthFixtureServer => IO[A]): IO[A] =
    IO.blocking(new RelayAuthFixtureServer()).flatMap { f =>
      f.enrollNetworkId = Net
      // 服务端凭据族形态：该腿上凭据无效 ⇒ **403 + 真服务端信封**（默认仍是 401，
      // 既有 spec 的读数面不受影响）。
      f.sessionRejectStatus = 403
      f.sessionRejectBody = """{"error":"Invalid device credential"}"""
      body(f).guarantee(IO.blocking(f.close()))
    }

  private def cfgFor(fix: RelayAuthFixtureServer, token: String): NeblinkServerConfig =
    NeblinkServerConfig(url = fix.url, networkId = Net, secret = "qa-secret", deviceToken = Some(token))

  test("N1 chain: 凭据腿 403 ⇒ hook 恰一次 + 重试换上服务端当前凭据 ⇒ 会话恢复") {
    withFixture { fix =>
      val stale = fix.registerDevice(Dev, Net) // 客户端持有的（已被下一次 enroll 作废）
      val fresh = fix.registerDevice(Dev, Net) // 服务端**当前**有效的那一行
      val hooks = new AtomicInteger(0)
      val client = new NeblinkClient(
        cfgFor(fix, stale),
        0,
        onDeviceTokenRejected = Some(IO { hooks.incrementAndGet(); Some(fresh) }),
        identity = Some(IO.pure(DeviceIdentity(Dev, "qa-host", "macos")))
      )
      for
        out <- client.login(Dev, "qa-host", "macos", Nil)
        _ <- IO {
          assertEquals(out, Right(Nil), "换上新凭据后会话交换必须成功（Right 而非 Left）")
          assertEquals(hooks.get(), 1, "hook 必须恰调用一次（改前 = 0：403 落不到任何自愈腿）")
          assertEquals(fix.sessionRejections.get(), 1, "恰一次被拒（旧凭据）")
          assertEquals(fix.loginCalls.size(), 2, "恰两次交换尝试：初次 + 单发重试")
          assert(client.currentSessionToken.isDefined, "会话必须建立")
        }
      yield ()
    }
  }

  test("N1 chain: 重试再 403 ⇒ hook 不二次进入（allowRelogin=false 的单发不变量）") {
    withFixture { fix =>
      val stale = fix.registerDevice(Dev, Net)
      fix.registerDevice(Dev, Net) // 服务端当前行换成另一个 ⇒ 客户端无论拿哪个都无法通过
      val hooks = new AtomicInteger(0)
      val client = new NeblinkClient(
        cfgFor(fix, stale),
        0,
        // hook 给的「新」凭据同样不是服务端当前行 ⇒ 重试必再 403
        onDeviceTokenRejected = Some(IO { hooks.incrementAndGet(); Some("dtok-bogus") }),
        identity = Some(IO.pure(DeviceIdentity(Dev, "qa-host", "macos")))
      )
      for
        out <- client.login(Dev, "qa-host", "macos", Nil)
        _ <- IO {
          assert(out.isLeft, s"两次都拒 ⇒ 必须如实 Left，实际 $out")
          assertEquals(hooks.get(), 1, "🔴 单发不变量：重试的 allowRelogin=false 必须让 hook 不再进入")
          assertEquals(fix.sessionRejections.get(), 2, "恰两次被拒（初次 + 一次重试），无第三次腿")
        }
      yield ()
    }
  }

  // ===== N2 全链：API 面业务 403 零触碰（F2 类回归钉）=====

  private val LoginOk =
    """{"token":"fresh-sess","networkId":"qa-net","deviceId":"qa-device-1","peers":[]}"""

  /** API 面子类：`/api/device/session` 成功；其它（业务面）按注入的错误作答。
    * 只替换 [[NeblinkClient.sendRequest]] 这一个既有桩点（与 `NeblinkClientSessionHealSpec`
    * 同手法）⇒ `withSession` / `searchUser` 走**未改动的产品代码**。 */
  private final class ApiFaceStub(apiErr: String, hook: AtomicInteger):
    val logins = new AtomicInteger(0)
    val apiCalls = new AtomicInteger(0)
    val client: NeblinkClient = new NeblinkClient(
      NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = Net, secret = "s", deviceToken = Some("stale")),
      1,
      onDeviceTokenRejected = Some(IO { hook.incrementAndGet(); Some("fresh") }),
      identity = Some(IO.pure(DeviceIdentity(Dev, "qa-host", "macos")))
    ):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        IO {
          if url.contains("/api/device/session") then
            logins.incrementAndGet()
            Right(LoginOk)
          else
            apiCalls.incrementAndGet()
            Left(apiErr)
        }

  test("N2 chain: API 面业务 403 ⇒ 零 hook、零重登，错误原文如实透出") {
    val hooks = new AtomicInteger(0)
    for
      stub <- IO(new ApiFaceStub("""HTTP 403: {"error":"not_blocker"}""", hooks))
      seed <- stub.client.login(Dev, "qa-host", "macos", Nil)
      out <- stub.client.searchUser("alice")
      _ <- IO {
        assertEquals(seed, Right(Nil))
        assertEquals(out, Left("""HTTP 403: {"error":"not_blocker"}"""), "业务 403 必须原样落到报错面")
        assertEquals(hooks.get(), 0, "🔴 业务 403 零触碰（重登会以「同设备一活会话」踢掉自己的旧会话）")
        assertEquals(stub.logins.get(), 1, "不得追加任何会话交换/自愈腿")
      }
    yield ()
  }

  test("N2 chain: API 面令牌型 403 仍走既有自愈腿（不回归）") {
    val hooks = new AtomicInteger(0)
    for
      stub <- IO(new ApiFaceStub("""HTTP 403: {"error":"Missing or invalid token"}""", hooks))
      _ <- stub.client.login(Dev, "qa-host", "macos", Nil)
      out <- stub.client.searchUser("alice")
      _ <- IO {
        assertEquals(out, Left("""HTTP 403: {"error":"Missing or invalid token"}"""))
        assert(stub.logins.get() >= 2, "令牌型 403 ⇒ API 自愈腿照旧跑一次重登（silentRelogin）")
        assertEquals(hooks.get(), 0, "API 自愈腿用的是 login 全链，不经过 deviceToken hook")
      }
    yield ()
  }

end NeblinkCredentialFamily403Spec
