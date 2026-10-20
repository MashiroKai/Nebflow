package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.*
import org.http4s.*
import org.http4s.server.Router
import org.typelevel.ci.CIString

/**
 * 同机近路探测面（本地加速 M1 引擎侧批 ②/④；接口契约 = v2 §3.3 步骤1-2/5）：
 *
 *  - **deviceId 身份核对锚**：`GET /api/health` 在**环回腿**上并 `deviceId` 键
 *    （本机已铸身份时）；LAN/公网腿与未铸身份 = 默认载荷逐字节不变（键缺席）
 *    ——deviceId 不因本批变成 LAN 可枚举量，门户读不到 ⇒ 不切换（批 ④ 降级
 *    = 停留门户现状）。
 *  - **CORS 白名单**：只有「环回腿 ∧ Origin = 门户 origin（brand.conf
 *    portalUrl / 门户 env 覆盖）」的请求拿到 CORS 应答（回显 origin + PNA 预
 *    检头，nf-reach 同族）；其他 origin（含任意 localhost 端口页面与公网站点）
 *    零 CORS 头 ⇒ 浏览器读不到探测应答 ⇒ 近路判定不成立。`OPTIONS /health`
 *    预检腿同闸应答（此前落 404 ⇒ Chromium 私网预检必败）。
 *
 * 挂载形状 = 生产同款 `Router("/api" -> routes.routes)`（DeviceFaceHardeningRoutesSpec
 * 先例）；环回/非环回腿用 SpecRequests 的地址族驱动。
 */
class HealthRoutesSpec extends FunSuite:

  private val TestToken = "test-token-health-probe"
  private val tempRoot: os.Path = os.pwd / "target" / "test-health-nearpath"
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private val NullBackend =
    null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "health-nearpath-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = new nebflow.llm.ProviderRegistry(
        cats.effect.Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        NullBackend
      ),
      healthMonitor = nebflow.llm.ProviderHealthMonitor(null),
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private val routes = new RestApiRoutes(
    tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    sharedResources = mkResources,
    sessionStore = null,
    wsRoutes = null
  )

  /** 生产挂载形状：/api 前缀（health 域在级联首位）。 */
  private val app = Router("/api" -> routes.routes).orNotFound

  private def req(
    method: Method = Method.GET,
    path: String = "/api/health",
    origin: Option[String] = None,
    remote: Option[com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]] = Some(SpecRequests.LoopbackV4)
  ): Request[IO] =
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val withO = origin.fold(base)(o => base.putHeaders(Header.Raw(CIString("Origin"), o)))
    SpecRequests.withRemote(withO, remote)

  private def serve(r: Request[IO]): Response[IO] = app(r).unsafeRunSync()

  private def bodyJson(r: Response[IO]): Json =
    io.circe.parser.parse(r.bodyText.compile.string.unsafeRunSync()).getOrElse(Json.Null)

  private def header(r: Response[IO], name: String): Option[String] =
    r.headers.headers.find(h => h.name.toString.equalsIgnoreCase(name)).map(_.value)

  // ── deviceId 身份核对锚（批 ②）──────────────────────────────────────────

  test("loopback leg + enrolled identity: the payload carries deviceId"):
    val id = nebflow.neblink.DeviceIdentity.loadOrCreate.unsafeRunSync()
    val resp = serve(req())
    assertEquals(resp.status, Status.Ok)
    val served = bodyJson(resp).hcursor.downField("deviceId").as[String].toOption
    assertEquals(served, Some(id.deviceId), "the loopback probe must carry the machine identity (v2 §3.3 step 2)")

  test("non-loopback leg: payload unchanged — no deviceId key (not LAN-enumerable)"):
    val _ = nebflow.neblink.DeviceIdentity.loadOrCreate.unsafeRunSync()
    val served = bodyJson(serve(req(remote = Some(SpecRequests.Lan))))
    assert(!served.hcursor.downField("deviceId").succeeded, "LAN leg must not learn the deviceId")

  test("loopback leg + NO identity on the data root: payload unchanged (unenrolled fallback = stay put)"):
    os.remove.all(tempRoot / "device.json")
    val served = bodyJson(serve(req()))
    assertEquals(served.hcursor.downField("status").as[String].toOption, Some("ok"))
    assert(!served.hcursor.downField("deviceId").succeeded, "unenrolled: the probe cannot confirm identity")

  // ── CORS 白名单（批 ② + ④ 降级语义）────────────────────────────────────

  private val PortalOrigin =
    HealthRoutes.canonicalOrigin(nebflow.shared.Branding.portalUrl).getOrElse(sys.error("brand portalUrl must carry an origin"))

  test("loopback + portal origin: CORS echo + PNA answer (the near-path probe is browser-readable)"):
    val resp = serve(req(origin = Some(PortalOrigin)))
    assertEquals(resp.status, Status.Ok)
    assertEquals(header(resp, "Access-Control-Allow-Origin"), Some(PortalOrigin))
    assertEquals(header(resp, "Access-Control-Allow-Private-Network"), Some("true"))
    assertEquals(header(resp, "Vary"), Some("Origin"))

  test("loopback + foreign origin (evil site / other localhost port): NO CORS headers — browser cannot read"):
    for origin <- List("https://evil.example", "http://localhost:9999", "null") do
      val resp = serve(req(origin = Some(origin)))
      assertEquals(header(resp, "Access-Control-Allow-Origin"), None, s"origin=$origin must get no ACAO")
      assertEquals(header(resp, "Access-Control-Allow-Private-Network"), None, s"origin=$origin must get no PNA")

  test("non-loopback + portal origin: NO CORS headers (the probe face is loopback-only)"):
    val resp = serve(req(origin = Some(PortalOrigin), remote = Some(SpecRequests.Lan)))
    assertEquals(header(resp, "Access-Control-Allow-Origin"), None)

  test("no Origin header (server-side watchdog): response shape byte-stable — no CORS headers"):
    val resp = serve(req(origin = None))
    assertEquals(header(resp, "Access-Control-Allow-Origin"), None)
    assertEquals(bodyJson(resp).hcursor.downField("status").as[String].toOption, Some("ok"))

  test("OPTIONS preflight: whitelisted origin gets the full preflight answer; others get a bare 204"):
    val ok = serve(req(method = Method.OPTIONS, origin = Some(PortalOrigin)))
    assertEquals(ok.status, Status.NoContent)
    assertEquals(header(ok, "Access-Control-Allow-Origin"), Some(PortalOrigin))
    assertEquals(header(ok, "Access-Control-Allow-Methods"), Some("GET, OPTIONS"))
    assertEquals(header(ok, "Access-Control-Allow-Private-Network"), Some("true"))
    val bad = serve(req(method = Method.OPTIONS, origin = Some("https://evil.example")))
    assertEquals(bad.status, Status.NoContent)
    assertEquals(header(bad, "Access-Control-Allow-Origin"), None)

  test("canonicalOrigin: default ports collapse, case folds, junk refuses"):
    assertEquals(HealthRoutes.canonicalOrigin("https://Example.com:443/portal"), Some("https://example.com"))
    assertEquals(HealthRoutes.canonicalOrigin("http://example.com:80"), Some("http://example.com"))
    assertEquals(HealthRoutes.canonicalOrigin("https://example.com:8443"), Some("https://example.com:8443"))
    assertEquals(HealthRoutes.canonicalOrigin("about:blank"), None)
    assertEquals(HealthRoutes.canonicalOrigin("not a url"), None)
    // the whitelist itself must contain the brand portal origin
    assert(HealthRoutes.allowedProbeOrigins.contains(PortalOrigin), s"whitelist must hold the portal origin: ${HealthRoutes.allowedProbeOrigins}")
