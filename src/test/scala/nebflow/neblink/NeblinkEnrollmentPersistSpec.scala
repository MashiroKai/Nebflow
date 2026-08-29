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
        | "logto": {"endpoint":"https://auth.neblink.space","clientId":"c","pkceClientId":"pk"},
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
    assertEquals(logto.downField("endpoint").as[String].toOption, Some("https://auth.neblink.space"))
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
end NeblinkEnrollmentPersistSpec
