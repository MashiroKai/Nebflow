package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * 踢旧批（2026-09-14，作者 17:07 裁定 C+B·客户端一刀）—— **案 C**（显式登录放行）
 * 与**护栏回归**的机械钉。
 *
 * 事实底（取证报告 `20260914_170224_kickold-forensics__chain-n-07d47ec4.md`）：
 * 「登录失败 设备注册未完成」= 客户端本地隔离护栏（EnrollGuard）的拒绝，判据 =
 * 非默认 data root ∧ 目标 host 属生产域 ∧ 无 `NEBFLOW_ALLOW_PROD_ENROLL`；默认
 * data root 下恒不触发。设计目标是「防护栏对**显式用户登录**放行，自动入网仍拦」。
 *
 * 本 spec 在**真非默认 data root**（PathUtil.setDataRoot 重定向）下跑真判据：
 *   ① 纯函数：explicitUserAction=true ⇒ None（放行）；不传/false ⇒ 与原语义逐字相同；
 *   ② live：非默认 root + 生产域 host ⇒ 自动路径 Some（拦），显式路径 None（放行）；
 *   ③ persist 咽喉：同环境 + explicitUserAction=false ⇒ Left（**自动/隐式入网仍被拦**
 *      —— 放宽的缓释条件，缺此钉 = 放宽过度）；explicitUserAction=true ⇒ Right；
 *   ④ 停摆解除只挂在显式路径上：自动 persist 不解除隧道停摆，显式 persist 解除。
 */
class EnrollGuardExplicitLoginSpec extends FunSuite:

  private val ProdUrl = "https://nebflow.space"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-enroll-guard-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def enrollJson(token: String): Json =
    parse(s"""{"deviceToken":"$token","networkId":"net-123","avatarUrl":null,"githubUsername":null}""").toOption.get

  // ── ① 纯判据 ────────────────────────────────────────────

  test("pure: explicitUserAction releases the gate; the automatic decision is byte-identical to the pre-案-C one") {
    // 三条件同时成立 = 修前的拒绝态
    val auto = EnrollGuard.enrollRefusal(ProdUrl, nonDefaultHome = true, explicitAllow = false)
    assert(auto.isDefined, "automatic enroll on an isolated root against prod must still be refused")
    // 显式用户动作放行（同一个判据输入，只多一个标记）
    assertEquals(
      EnrollGuard.enrollRefusal(ProdUrl, nonDefaultHome = true, explicitAllow = false, explicitUserAction = true),
      None
    )
    assertEquals(EnrollGuard.enrollRefusal(ProdUrl, true, false, false), auto)
    // 其余三个条件不变（默认 home / 显式开关 / 非生产域）
    assertEquals(EnrollGuard.enrollRefusal(ProdUrl, nonDefaultHome = false, explicitAllow = false), None)
    assertEquals(EnrollGuard.enrollRefusal(ProdUrl, nonDefaultHome = true, explicitAllow = true), None)
    assertEquals(EnrollGuard.enrollRefusal("http://127.0.0.1:9095", nonDefaultHome = true, explicitAllow = false), None)
    // 子域仍属生产域
    assert(EnrollGuard.enrollRefusal("https://auth.nebflow.space", true, false).isDefined)
  }

  // ── ② live 判据（本 spec 全程跑在重定向的 data root 上）────

  test("live: on a non-default data root the automatic path is refused and the explicit path passes") {
    assertEquals(DeviceIdentity.isNonDefaultHome, true, "this spec must run on a redirected data root")
    val auto = EnrollGuard.enrollRefusal(ProdUrl)
    assert(auto.isDefined, "live automatic decision must refuse")
    assert(auto.get.contains("isolated data root"), s"reason must name the cause: ${auto.get}")
    assert(auto.get.contains(EnrollGuard.AllowProdEnrollEnv), "reason must name the escape hatch")
    assertEquals(EnrollGuard.enrollRefusal(ProdUrl, explicitUserAction = true), None)
  }

  // ── ③ persist 咽喉（自动仍拦 / 显式放行）─────────────────

  test("persist: the automatic path is still refused on an isolated root against prod (regression pin)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8095, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = ProdUrl,
          json = enrollJson("tok-auto"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8095,
          reloginHook = None
        )
      }
    }.map { out =>
      out match
        case Left(err) => assert(err.contains("isolated data root"), s"left must carry the guard reason: $err")
        case Right(v)  => fail(s"automatic persist must be refused, got $v")
    }.unsafeRunSync()
    // 零落盘：拒绝路径不得留下 device.json / config
    assert(!os.exists(os.Path(tmpDir, os.pwd) / "neblink" / "device.json"), "a refused enroll must not write a credential")
  }

  test("persist: the explicit user action passes the gate and lands the credential (案 C 红→绿)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8095, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = ProdUrl,
          json = enrollJson("tok-explicit"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8095,
          reloginHook = None,
          explicitUserAction = true
        )
      }
    }.map { out =>
      assertEquals(out, Right("tok-explicit"), "the explicit login must be released and persisted")
    }.unsafeRunSync()
    // kaiauth 修法批 ②（2026-09-16）：落地面改判 —— **权威写面**是 `config.json` 的
    // `neblinkServer.deviceToken`（出站点实际发送的那一份），`neblink/device.json` 侧
    // **停写**该字段（见 `DeviceCredential` 的 DEPRECATED 注记）。旧断言
    // （`cred.map(_.deviceToken) == Some("tok-explicit")`）钉的正是被移除的那份死副本；
    // 新断言改读出站点 + 断言盘上不含副本 ⇒ 覆盖面更宽（旧断言只证副本存在）。
    val cfg = NeblinkConfig.load.unsafeRunSync()
    assertEquals(
      cfg.neblinkServer.flatMap(_.deviceToken),
      Some("tok-explicit"),
      "the authoritative outbound source (config.json) must carry the token"
    )
    val cred = DeviceCredential.load.unsafeRunSync()
    assertEquals(cred.map(_.networkId), Some("net-123"), "the identity face must still land")
    assert(
      !os.read(os.Path(tmpDir, os.pwd) / "neblink" / "device.json").contains("\"deviceToken\""),
      "the retired deviceToken copy must not be written into neblink/device.json"
    )
  }

  // ── ④ 停摆解除只挂在显式路径 ─────────────────────────────

  test("park: an automatic persist does NOT lift the post-kick park; an explicit persist does") {
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8095, dispatcher, 15.seconds).flatMap { ms =>
        val tunnel = new NeblinkRelayTunnel(ms, () => IO.pure(None: Option[String]))(dispatcher)
        ms.setRelayTunnel(tunnel)
        val localUrl = "http://127.0.0.1:9095" // 非生产域：护栏不参与，单独验停摆耦合
        for
          _ <- tunnel.noteServerDisconnect()
          parked <- IO(tunnel.parkedAfterKick)
          _ <- IO(assert(parked, "the disconnect must park"))
          // 自动路径（silent relogin / device-flow poll 的等价形状）
          auto <- NeblinkEnrollment.persist(
            ms,
            resolvedUrl = localUrl,
            json = enrollJson("tok-auto-2"),
            logtoRefresh = None,
            discovery = None,
            gatewayPort = 8095,
            reloginHook = None
          )
          _ <- IO(assertEquals(auto, Right("tok-auto-2"), "non-prod host is not gated in either path"))
          _ <- IO(assert(tunnel.parkedAfterKick, "an automatic persist must NOT lift the park"))
          // 显式路径
          _ <- NeblinkEnrollment.persist(
            ms,
            resolvedUrl = localUrl,
            json = enrollJson("tok-explicit-2"),
            logtoRefresh = None,
            discovery = None,
            gatewayPort = 8095,
            reloginHook = None,
            explicitUserAction = true
          )
          _ <- IO(assertEquals(tunnel.parkedAfterKick, false, "an explicit persist must lift the park"))
        yield ()
      }
    }.unsafeRunSync()
  }
end EnrollGuardExplicitLoginSpec
