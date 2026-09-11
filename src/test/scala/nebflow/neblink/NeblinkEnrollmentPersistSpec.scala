package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/** #290 修复批缺口③回归钉（2026-08-28）：gateway 启动 sessionToken 恢复链的
  * 写入端。链路 = enroll 响应 → NeblinkEnrollment.persist → config.json 的
  * neblinkServer.deviceToken 落盘 → 下次启动 doLogin 走 /api/device/session
  * 交换（NeblinkClientReloginSpec 已钉交换链；联调实证端到端可行）。
  *
  * 本 spec 钉写入端契约：
  *  - persist 后 config.json 含 deviceToken（缺失 = 启动断链，联调实锤形态）；
  *  - 顶层其他段（logto / agentMessaging / enabled 之外的既有键）不被抹掉；
  *  - persisted 凭据经 NeblinkConfig.load 读回一致（round-trip）。
  */
class NeblinkEnrollmentPersistSpec extends FunSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-persist-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def enrollJson(token: String): Json =
    parse(s"""{"deviceToken":"$token","networkId":"net-123","avatarUrl":null,"githubUsername":null}""")
      .toOption
      .get

  private def configOnDisk: Json =
    parse(os.read(os.Path(tmpDir, os.pwd) / "neblink" / "config.json")).toOption.get

  test("persist writes deviceToken into config.json (startup session-recovery precondition)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-persist-1"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None
        )
      }
    }.unsafeRunSync()

    val cfg = configOnDisk
    val server = cfg.hcursor.downField("neblinkServer")
    assertEquals(server.downField("deviceToken").as[String].toOption, Some("tok-persist-1"))
    assertEquals(server.downField("networkId").as[String].toOption, Some("net-123"))
    assertEquals(server.downField("url").as[String].toOption, Some("https://neblink.example"))
    assertEquals(cfg.hcursor.downField("enabled").as[Boolean].toOption, Some(true))
  }

  test("persist preserves unrelated top-level config sections (logto, agentMessaging)") {
    // Pre-seed a config the way a PKCE-enabled install has it.
    val dir = os.Path(tmpDir, os.pwd) / "neblink"
    os.makeDir.all(dir)
    os.write.over(
      dir / "config.json",
      """{"enabled": true,
        | "neblinkServer": {"url":"https://old.example","networkId":"old-net","secret":"s"},
        | "logto": {"endpoint":"https://auth.nebflow.space","clientId":"c","pkceClientId":"pk"},
        | "agentMessaging": {"mode":"ask"}}""".stripMargin
    )

    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-persist-2"),
          logtoRefresh = Some("refresh-tok"),
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None
        )
      }
    }.unsafeRunSync()

    val cfg = configOnDisk
    // neblinkServer swapped, deviceToken present.
    assertEquals(
      cfg.hcursor.downField("neblinkServer").downField("deviceToken").as[String].toOption,
      Some("tok-persist-2")
    )
    // Unrelated sections survive (a wiped logto block silently drops installs
    // back to the legacy login chain — beta.53 regression class).
    val logto = cfg.hcursor.downField("logto")
    assertEquals(logto.downField("endpoint").as[String].toOption, Some("https://auth.nebflow.space"))
    assertEquals(logto.downField("pkceClientId").as[String].toOption, Some("pk"))
    assertEquals(cfg.hcursor.downField("agentMessaging").downField("mode").as[String].toOption, Some("ask"))
    // logtoRefresh round-trips into the logto block's refresh credential slot.
    assertEquals(
      cfg.hcursor.downField("neblinkServer").downField("url").as[String].toOption,
      Some("https://neblink.example")
    )
  }

  test("persisted config reads back via NeblinkConfig.load (round-trip)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-rt"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None
        )
      }
    }.unsafeRunSync()
    val reloaded = NeblinkConfig.load.unsafeRunSync()
    val server = reloaded.neblinkServer.getOrElse(fail("neblinkServer missing after reload"))
    assertEquals(server.deviceToken, Some("tok-rt"))
    assertEquals(server.networkId, "net-123")
  }

  // ── O5 配套修法批（2026-09-11, o5fix）：身份落盘与 refreshToken 解耦 ──────
  // 依据：独立复核 n-c77627a2 §2①（判据链 RestApiRoutes:3188 → LogtoAuthCode:255
  // → NeblinkEnrollment:67-70 → DeviceCredentialStore:77-80 → status 恒空）+
  // §2①-9（`save` 全量覆盖会回收存量身份）。

  private def deviceCredPath: os.Path = os.Path(tmpDir, os.pwd) / "neblink" / "device.json"

  /** 存量 pre-O5 device.json 形态（有 refresh 令牌 + id_token，带 ACL 不敏感）。 */
  private def seedPreO5Credential(server: String = "https://neblink.example"): Unit =
    os.write.over(
      deviceCredPath,
      s"""{"serverUrl":"$server","networkId":"net-123","deviceId":"dev-seed","deviceToken":"tok-old",
         | "logto":{"refreshToken":"legacy-refresh","updatedAt":7,"idToken":"legacy-id"}}""".stripMargin,
      createFolders = true
    )

  private def persistedCredential: DeviceCredential =
    DeviceCredential.load.unsafeRunSync().getOrElse(fail("device.json missing after persist"))

  test("o5fix: persist lands the identity when the login carries an id_token but NO refresh token") {
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-id-only"),
          logtoRefresh = None, // O5 后的常态：provider 不再签发 refresh_token
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None,
          logtoIdToken = Some("post-o5-id")
        )
      }
    }.unsafeRunSync()

    val cred = persistedCredential
    // 身份面（/api/neblink/status 与 logout hint 的取数表达式同形）
    assertEquals(cred.logto.flatMap(_.idToken), Some("post-o5-id"))
    assertEquals(cred.logto.map(_.refreshToken), Some(""))
  }

  test("o5fix: a login carrying no logto data at all must not wipe the stored block (覆盖写不劣于现状)") {
    seedPreO5Credential()
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-device-flow"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None
        )
      }
    }.unsafeRunSync()

    val cred = persistedCredential
    assertEquals(cred.deviceToken, "tok-device-flow")
    assertEquals(cred.logto.flatMap(_.idToken), Some("legacy-id"), "stored identity was wiped")
    // 显式取舍（见批结果）：存量 pre-O5 refresh 兜底不因「新登录没带刷新令牌」被删。
    assertEquals(cred.logto.map(_.refreshToken), Some("legacy-refresh"))
  }

  test("o5fix: an incoming id_token replaces the stored identity; a foreign serverUrl never carries over") {
    seedPreO5Credential()
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-refresh-identity"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None,
          logtoIdToken = Some("fresh-id")
        )
      }
    }.unsafeRunSync()
    assertEquals(persistedCredential.logto.flatMap(_.idToken), Some("fresh-id"))

    // 换成另一台 server 的登录：旧 server 的凭据不得被当作本机身份继承。
    seedPreO5Credential(server = "https://other.example")
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
        NeblinkEnrollment.persist(
          ms,
          resolvedUrl = "https://neblink.example",
          json = enrollJson("tok-other-server"),
          logtoRefresh = None,
          discovery = None,
          gatewayPort = 8099,
          reloginHook = None
        )
      }
    }.unsafeRunSync()
    assertEquals(persistedCredential.logto, None)
  }
end NeblinkEnrollmentPersistSpec
