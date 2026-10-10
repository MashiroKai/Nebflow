package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.*
import munit.FunSuite
import nebflow.neblink.NeblinkConfig
import nebflow.shared.{Branding, PathUtil}

import java.nio.file.Files

/**
 * 门户开门（neblink portal MVP，2026-10-10，方案 §3.2 E1/E2）——解析单点判据钉。
 *
 * 契约（方案 §7 冻结点 + 本批任务书）：
 *   · 配置位 = `<dataRoot>/neblink/config.json` 顶层 `portalUrl`（🔴 nebflow.json 的
 *     neblink 块是惰性的——NeblinkModel.scala:617-621 qa 实锤——故不设 nebflow.json 面）；
 *   · 解析降序 = `NEBFLOW_PORTAL_URL` env > config `portalUrl` > 生产缺省
 *     `https://neblink.nebflow.space`（= `Branding.serverUrl` 单源）；
 *   · `NEBFLOW_OPEN_UI=local` ⇒ None（开发态逃生口：调用方回落 localhost 既有行为）；
 *   · config `portalUrl: ""`（空白同）= **显式关门户** ⇒ None（本批任务书「配置关门户
 *     时回落 localhost」）；
 *   · 出口 URL = base + `?device=<deviceId>`（有 deviceId 才带；`?`/`&` 边界按 base
 *     现状判定；deviceId URL 编码）；
 *   · 隔离实例（重定向 data root）：门户 URL 解析**不受** EnrollGuard 门控——开门是
 *     零副作用的浏览器动作（不写凭据、不注册设备），案 a/b① 拦的是入网写面；此处
 *     把该边界**机械钉住**，防日后误加闸把隔离实例的开门打回 localhost。
 */
class PortalUrlSpec extends FunSuite:

  private val Default = Branding.serverUrl
  private val ProdDefault = "https://neblink.nebflow.space"

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
    NeblinkConfig.save(NeblinkConfig(enabled = true, portalUrl = Some("https://neblink.selfhost.example"))).unsafeRunSync()
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
