package nebflow.neblink

import nebflow.neblink.core.*
import nebflow.neblink.identity.*
import nebflow.neblink.logto.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.core.root.{AccountProfile, AccountRoot, ActiveAccount}
import nebflow.shared.PathUtil

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * #290 修复批缺口③回归钉（2026-08-28）：gateway 启动 sessionToken 恢复链的
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
    parse(s"""{"deviceToken":"$token","networkId":"net-123","avatarUrl":null,"githubUsername":null}""").toOption.get

  private def configOnDisk: Json =
    parse(os.read(os.Path(tmpDir, os.pwd) / "neblink" / "config.json")).toOption.get

  test("persist writes deviceToken into config.json (startup session-recovery precondition)") {
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()

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

    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()

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
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()
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
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()

    val cred = persistedCredential
    // 身份面（/api/neblink/status 与 logout hint 的取数表达式同形）
    assertEquals(cred.logto.flatMap(_.idToken), Some("post-o5-id"))
    assertEquals(cred.logto.map(_.refreshToken), Some(""))
  }

  test("o5fix: a login carrying no logto data at all must not wipe the stored block (覆盖写不劣于现状)") {
    seedPreO5Credential()
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()

    val cred = persistedCredential
    // kaiauth 修法批 ②（2026-09-16）：`deviceToken` 已**停写**本文件（写侧单源化 ⇒
    // 唯一权威来源 = `config.json`，见 `DeviceCredential` 的 DEPRECATED 注记）。
    // 旧断言（`cred.deviceToken == "tok-device-flow"`）钉的正是被移除的那份死副本，
    // 故迁移为「权威面读得到 + 本文件不再携带副本」——两向都比旧断言更机械。
    assertEquals(
      configOnDisk.hcursor.downField("neblinkServer").downField("deviceToken").as[String].toOption,
      Some("tok-device-flow"),
      "the authoritative outbound source (config.json) must carry the fresh token"
    )
    assert(
      !os.read(deviceCredPath).contains("\"deviceToken\""),
      "the retired deviceToken copy must not be written into neblink/device.json"
    )
    assertEquals(cred.logto.flatMap(_.idToken), Some("legacy-id"), "stored identity was wiped")
    // 显式取舍（见批结果）：存量 pre-O5 refresh 兜底不因「新登录没带刷新令牌」被删。
    assertEquals(cred.logto.map(_.refreshToken), Some("legacy-refresh"))
  }

  test("o5fix: an incoming id_token replaces the stored identity; a foreign serverUrl never carries over") {
    seedPreO5Credential()
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()
    assertEquals(persistedCredential.logto.flatMap(_.idToken), Some("fresh-id"))

    // 换成另一台 server 的登录：旧 server 的凭据不得被当作本机身份继承。
    seedPreO5Credential(server = "https://other.example")
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
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
      }
      .unsafeRunSync()
    assertEquals(persistedCredential.logto, None)
  }

  // ── logout-login-recovery 批（2026-11-08）：平铺档「登出 → 重新登录」闭环 ──────
  //
  // 平铺根是兼容红线档（契约 §0 总则 1：无 layout 标记的根一切字节不变）。账号归属闸
  // 重定形后，平铺登录 = **只落凭据**（device.json + config.json —— 阶梯前多年验证过的
  // 稳态），根层零写（无 layout.json / accounts/ / active-account / 迁移产物）。
  // 本组用例一律**假身份 + 临时数据根**（手工 JWT 形 id_token，零网络、零真实账号、
  // 零真实凭据），钉四件事：
  //   F1 平铺显式登录整链放行（Right），凭据两件落地；
  //   F2 平铺根字节冻结：预置文件逐字节不变 + 根层零新增（「不在本批迁移真实数据」的
  //      强断言——迁移引擎（establishAccountRootAfterSignIn 平铺分支）在本路径上不可达）；
  //   F3 账号档同账号重登放行（登出 → 同账号回来），凭据落账号根、指针保持同账号；
  //   F4 账号档异账号硬拒（Left 带稳定码头），既有凭据与配置零触碰。

  /** 手工 JWT 形 id_token（`decodeIdTokenClaims` 只解 payload 段的 JSON 声明）。 */
  private def fakeIdToken(sub: String, email: String): String =
    val payload = java.util.Base64
      .getUrlEncoder()
      .withoutPadding()
      .encodeToString(s"""{"sub":"$sub","email":"$email"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"hdr.$payload.sig"

  /** 把进程级机器层根钉到临时根上（账号档用例），结束恢复原值并删干净。 */
  private def withPinnedMachineRoot[T](f: os.Path => T): T =
    val prev = PathUtil.dataRoot
    val root = os.Path(Files.createTempDirectory("nb-login-loop"), os.pwd)
    try
      PathUtil.setMachineRoot(root)
      f(root)
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(root)

  private def persistOnce(ms: NeblinkService, idToken: Option[String], explicit: Boolean): IO[Either[String, String]] =
    NeblinkEnrollment
      .persist(
        ms,
        resolvedUrl = "https://neblink.example",
        json = enrollJson("tok-loop"),
        logtoRefresh = None,
        discovery = None,
        gatewayPort = 8099,
        reloginHook = None,
        logtoIdToken = idToken,
        explicitUserAction = explicit
      )
      .attempt
      // 🔴 裸 `identity` 不可用：`nebflow.neblink.identity` 是本文件导入的包名，
      // 裸名解析到包上（E119）。用显式恒等 lambda（fold 的右支 `r => r`）。
      .map(_.fold(e => Left(e.getMessage), r => r))

  private def sha256(p: os.Path): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  /** 根层晋升产物的全集（平铺登录后一个都不许出现）。 */
  private def rootLayerArtifacts(root: os.Path): List[String] =
    List("layout.json", "active-account", "accounts", "migration-report.json", "pending-claim")
      .filter(rel => os.exists(root / os.RelPath(rel)))

  test("F1+F2 平铺档显式登录整链放行：凭据两件落地，根层字节冻结零新增") {
    // beforeEach 的 setDataRoot 即平铺钉死语义（契约 §1）：机器层 == 有效根 == 临时根。
    val root = os.Path(tmpDir, os.pwd)
    assertEquals(nebflow.core.root.RootLadder.resolve(root).name, "flat", "前提：平铺档")
    // 预置：平铺根已有数据（老机器形态）——字节冻结的对照面
    val seeded = root / "sessions" / "s-1.json"
    os.makeDir.all(seeded / os.up)
    os.write.over(seeded, """{"id":"s-1"}""")
    val before = sha256(seeded)

    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
        NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
          persistOnce(ms, idToken = Some(fakeIdToken("logto|user-flat-1", "flat1@example.com")), explicit = true)
        }
      }
      .unsafeRunSync() match
      case Left(err) => fail(s"平铺显式登录必须放行（只落凭据，无换根欠账），实际拒绝: $err")
      case Right(tok) => assertEquals(tok, "tok-loop")

    // F1：凭据两件都在平铺根（有效根 == 机器根）
    assertEquals(
      configOnDisk.hcursor.downField("neblinkServer").downField("deviceToken").as[String].toOption,
      Some("tok-loop"),
      "config.json（出站权威源）必须带新 token"
    )
    val cred = DeviceCredential.load.unsafeRunSync().getOrElse(fail("device.json missing"))
    assertEquals(cred.logto.flatMap(_.idToken), Some(fakeIdToken("logto|user-flat-1", "flat1@example.com")))

    // F2：根层零新增（不迁移、不建账号目录、不写指针/布局/迁移产物）
    assertEquals(
      rootLayerArtifacts(root),
      Nil,
      "平铺登录绝不做根层写——迁移引擎在本路径上不可达（半迁移态的强断言）"
    )
    // F2：预置数据逐字节不变（兼容红线：无 layout 标记的根一切字节不变）
    assertEquals(sha256(seeded), before, "平铺根既有数据必须逐字节不变")
  }

  test("F3 账号档同账号重登放行：凭据落账号根，指针保持同账号（登出→同账号回来）") {
    withPinnedMachineRoot { root =>
      val sub = "logto|user-a"
      AccountRoot.writeLayoutMarker(root)
      val dir = AccountRoot.ensureAccountDir(root, sub)
      AccountRoot.writeProfile(dir, AccountProfile(sub, "a@example.com", "2026-01-01T00:00:00Z", None))
      AccountRoot.writePointer(root, ActiveAccount(sub, "a@example.com", "2026-01-01T00:00:00Z"))
      assertEquals(PathUtil.dataRoot, dir, "前提：账号档，有效根 = 账号根")

      val outcome = Dispatcher
        .parallel[IO]
        .use { dispatcher =>
          NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
            persistOnce(ms, idToken = Some(fakeIdToken(sub, "a@example.com")), explicit = true)
          }
        }
        .unsafeRunSync()
      assertEquals(outcome, Right("tok-loop"), "同账号重登必须放行")

      // 凭据落**账号根**（dataRoot 已是账号目录），身份是本次登录的 sub
      val cred = DeviceCredential.load.unsafeRunSync().getOrElse(fail("device.json missing in account root"))
      assertEquals(cred.logto.flatMap(_.idToken), Some(fakeIdToken(sub, "a@example.com")))
      // 指针保持同账号（账号档重登 = establishPointerAndProfile 幂等就位）
      assertEquals(
        AccountRoot.readPointer(root).map(_.accountSub),
        Some(sub),
        "同账号重登后指针必须仍指向同账号"
      )
    }
  }

  test("F4 账号档异账号硬拒：Left 带稳定码头，既有凭据与配置零触碰") {
    withPinnedMachineRoot { root =>
      val subA = "logto|user-a"
      AccountRoot.writeLayoutMarker(root)
      val dirA = AccountRoot.ensureAccountDir(root, subA)
      AccountRoot.writeProfile(dirA, AccountProfile(subA, "a@example.com", "2026-01-01T00:00:00Z", None))
      AccountRoot.writePointer(root, ActiveAccount(subA, "a@example.com", "2026-01-01T00:00:00Z"))
      // 既有凭据（A 的身份）——在服务创建**之前**种下（createForTest 的 loadOrCreate 会
      // 沿用它，不另铸），硬拒后必须原样。
      val sentinel =
        s"""{"serverUrl":"https://neblink.example","networkId":"net-123","deviceId":"dev-keep","deviceToken":"tok-a","logto":{"refreshToken":"a-refresh","updatedAt":7,"idToken":"a-id"}}"""
      os.write.over(dirA / "neblink" / "device.json", sentinel, createFolders = true)
      val sentinelOnDisk = os.read(dirA / "neblink" / "device.json")

      val outcome = Dispatcher
        .parallel[IO]
        .use { dispatcher =>
          NeblinkService.createForTest(8099, dispatcher, 15.seconds).flatMap { ms =>
            persistOnce(ms, idToken = Some(fakeIdToken("logto|user-b", "b@example.com")), explicit = true)
          }
        }
        .unsafeRunSync()
      assert(
        outcome.left.exists(_.startsWith("account-switch-requires-logout:")),
        s"异账号必须带稳定码头硬拒，实际: $outcome"
      )
      // A 的凭据零触碰（硬拒发生在任何落盘之前）
      assertEquals(os.read(dirA / "neblink" / "device.json"), sentinelOnDisk, "既有凭据不得被触碰")
      // 被拒登录不得写任何配置（B 的 token 无处落地）
      assertEquals(os.exists(dirA / "neblink" / "config.json"), false, "硬拒不得产生配置写入")
      assertEquals(AccountRoot.readPointer(root).map(_.accountSub), Some(subA), "指针必须保持 A")
    }
  }
end NeblinkEnrollmentPersistSpec
