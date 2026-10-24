package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.*
import munit.FunSuite
import nebflow.neblink.NeblinkConfig
import nebflow.shared.{Branding, PathUtil}

import java.nio.file.Files

/** Portal resolution remains separate from relay and account transport. */
class PortalUrlSpec extends FunSuite:

  private val Default = Branding.portalUrl
  private val ProdDefault = "https://mystand.space/portal"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-portal-url-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 纯解析核（env 注入式，测试不经真实 sys.env）────────────────────────

  test("decide: no env, no config → the production portal default") {
    assertEquals(PortalUrl.decide(None, None, None, None), Some(Default))
    assertEquals(PortalUrl.decide(None, None, None, Some("dev-1")), Some(s"$Default?device=dev-1"))
  }

  test("decide: NEBFLOW_OPEN_UI=local disables the portal open (dev escape → localhost fallback)") {
    assertEquals(PortalUrl.decide(None, None, Some("local"), None), None)
    // 大写变体同义；且压过一切其他配置
    assertEquals(
      PortalUrl.decide(Some("https://cfg.example"), Some("https://env.example"), Some("LOCAL"), None),
      None
    )
    // 其他取值 ≠ off（未知值不触发逃生口）
    assertEquals(PortalUrl.decide(None, None, Some("portal"), None), Some(Default))
  }

  test("decide: NEBFLOW_PORTAL_URL env wins over config and default") {
    assertEquals(
      PortalUrl.decide(Some("https://cfg.example"), Some("https://env.example"), None, None),
      Some("https://env.example")
    )
    assertEquals(
      PortalUrl.decide(None, Some("https://env.example"), None, Some("dev-9")),
      Some("https://env.example?device=dev-9")
    )
    // 空串 env = 未设（不遮蔽 config）
    assertEquals(
      PortalUrl.decide(Some("https://cfg.example"), Some("  "), None, None),
      Some("https://cfg.example")
    )
  }

  test("decide: config portalUrl wins over the default; blank string = explicit OFF (localhost fallback)") {
    assertEquals(PortalUrl.decide(Some("https://cfg.example"), None, None, None), Some("https://cfg.example"))
    assertEquals(PortalUrl.decide(Some(""), None, None, None), None, "portalUrl:\"\" must fall back to localhost")
    assertEquals(PortalUrl.decide(Some("   "), None, None, None), None, "whitespace-only must fall back to localhost")
  }

  test("decide: ?device= appended (encoding + ?/& boundary); absent/blank deviceId → bare URL") {
    assertEquals(PortalUrl.decide(None, None, None, Some("dev-1")), Some(s"$Default?device=dev-1"))
    assertEquals(
      PortalUrl.decide(None, Some("https://p.example/?x=1"), None, Some("a b/c")),
      Some("https://p.example/?x=1&device=a%20b%2Fc")
    )
    assertEquals(PortalUrl.decide(None, None, None, Some("")), Some(Default))
    assertEquals(PortalUrl.decide(None, None, None, None), Some(Default))
  }

  test("default is the frozen production portal URL (方案 §7 契约冻结)") {
    assertEquals(Default, ProdDefault)
  }

  test("focus includes the persisted device id without creating or rewriting identity") {
    val root = os.Path(tmpDir, os.pwd)
    val device = root / "device.json"
    assertEquals(PortalUrl.focusUrl("http://localhost:8199").unsafeRunSync(), Default)
    assert(!os.exists(device))
    val raw = """{"deviceId":"existing-device","deviceName":"Migrated Mac","platform":"macos","deviceSecret":"kept"}"""
    os.write(device, raw)
    assertEquals(PortalUrl.focusUrl("http://localhost:8199").unsafeRunSync(), s"$Default?device=existing-device")
    assertEquals(os.read(device), raw)
  }

  test("no-browser local focus leaves session ticket minting disabled") {
    NeblinkConfig.save(NeblinkConfig(portalUrl = Some(""))).unsafeRunSync()
    val local = "http://localhost:8199"
    assertEquals(PortalUrl.focusUrl(local, authenticateLocal = false).unsafeRunSync(), local)
  }

  // ── 配置腿（真盘：重定向 data root 上的 config.json 读写）────────────────

  test("NeblinkConfig: portalUrl decodes when present; absent/null → None") {
    assertEquals(decode[NeblinkConfig]("""{"enabled":true}""").toOption.get.portalUrl, None)
    assertEquals(decode[NeblinkConfig]("""{"portalUrl":null}""").toOption.get.portalUrl, None)
    assertEquals(
      decode[NeblinkConfig]("""{"portalUrl":"https://cfg.example"}""").toOption.get.portalUrl,
      Some("https://cfg.example")
    )
  }

  test("portalUrl survives NeblinkConfig.save/load round-trip (self-hosted override surface)") {
    NeblinkConfig
      .save(NeblinkConfig(enabled = true, portalUrl = Some("https://neblink.selfhost.example")))
      .unsafeRunSync()
    val reloaded = NeblinkConfig.load.unsafeRunSync()
    assertEquals(reloaded.portalUrl, Some("https://neblink.selfhost.example"))
    // 磁盘形态：顶层键（非嵌套块）——写进 JSON 顶层才被本判据读到
    val raw = os.read(os.Path(tmpDir, os.pwd) / "neblink" / "config.json")
    val cursor = parse(raw).toOption.get.hcursor
    assertEquals(cursor.downField("portalUrl").as[String].toOption, Some("https://neblink.selfhost.example"))
  }

  // ── 隔离实例边界（重定向 data root 上 live 判据）────────────────────────

  test("isolated data root: the portal default still resolves — the browser open is side-effect free") {
    // 空 config + 重定向 root：开门目标照常解析（不回落 localhost、不被闸拦）。
    // 判据依据：开门 = 浏览器导航，零本地写、零注册副作用；EnrollGuard 家族
    // （案 a / 案 b①）拦的是入网/注册写面。此处机械钉边界，防误加闸。
    assertEquals(
      PathUtil.dataRoot.toNIO.toAbsolutePath.normalize.toString,
      os.Path(tmpDir, os.pwd).toNIO.toAbsolutePath.normalize.toString,
      "spec must run on a redirected (isolated) data root"
    )
    val live = PortalUrl.openUrl(None).unsafeRunSync()
    assertEquals(live, Some(Default))
  }
end PortalUrlSpec
