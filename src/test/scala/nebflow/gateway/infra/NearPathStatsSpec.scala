package nebflow.gateway.infra

import nebflow.gateway.*

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
 * 近路「命中 / 回落」引擎侧可观测面（近路批 2026-11-04）的契约钉：
 *
 *  - **零凭据**：`POST /api/health/nearpath-event` 与 `GET /api/health/nearpath` 无
 *    `withAuth`（与 `/api/health/conn` 同级），无令牌也必须答 204 / 载荷；
 *  - **单增量**：hit / miss 各自自增 + 各自的 lastAt；reason 归一化到
 *    `^[a-z0-9_.:-]{1,64}$`，不合法一律并进 `other`；
 *  - **吃不完的静默丢**：超每 IP 每分钟上限、坏 body、非法 event 一律 204 且不计数；
 *  - **既有载荷零回归**：`/api/health` 的键集合不变（本批只许新增路由，不许改既有键）——
 *    这条是本批最大的风险面（探针/看门狗/门户探测全读它）。
 *
 * 挂载形状 = 生产同款 `Router("/api" -> routes.routes)`（HealthRoutesSpec 同基座）。
 */
class NearPathStatsSpec extends FunSuite:

  private val TestToken = "test-token-nearpath-stats"
  private val tempRoot: os.Path = os.pwd / "target" / "test-nearpath-stats"
  PathUtil.setDataRoot(tempRoot)
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "nearpath-stats-spec-archives"),
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

  private def serve(r: Request[IO]): Response[IO] = app(r).unsafeRunSync()

  private def bodyJson(r: Response[IO]): Json =
    io.circe.parser.parse(r.bodyText.compile.string.unsafeRunSync()).getOrElse(Json.Null)

  private def header(r: Response[IO], name: String): Option[String] =
    r.headers.headers.find(h => h.name.toString.equalsIgnoreCase(name)).map(_.value)

  /** 上报一次事件（`application/json`，生产的真实形态）。 */
  private def report(
    json: String,
    remote: Option[com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]] = Some(SpecRequests.LoopbackV4),
    origin: Option[String] = None,
    contentType: Option[String] = Some("application/json")
  ): Response[IO] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString("/api/health/nearpath-event"))
      .withEntity(json)
    val withCt = contentType.fold(base)(ct => base.putHeaders(Header.Raw(CIString("Content-Type"), ct)))
    val withO = origin.fold(withCt)(o => withCt.putHeaders(Header.Raw(CIString("Origin"), o)))
    serve(SpecRequests.withRemote(withO, remote))

  private def read(
    remote: Option[com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]] = Some(SpecRequests.LoopbackV4),
    origin: Option[String] = None
  ): Response[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString("/api/health/nearpath"))
    val withO = origin.fold(base)(o => base.putHeaders(Header.Raw(CIString("Origin"), o)))
    serve(SpecRequests.withRemote(withO, remote))

  private def counters: Json =
    NearPathStats.resetForTest() // 每个用例从零开始（本对象是进程单例，他套 spec 也会加数）
    NearPathStats.payload

  private def int(j: Json, key: String): Long =
    j.hcursor.downField(key).as[Long].toOption.getOrElse(sys.error(s"missing int key $key in $j"))

  // ── 默认态 & 单增量 ─────────────────────────────────────────────────────

  test("default state: hit=0 miss=0 reasons={} hitLastAt=null missLastAt=null redeemedLoopback=0"):
    val p = counters
    assertEquals(int(p, "hit"), 0L)
    assertEquals(int(p, "miss"), 0L)
    assertEquals(p.hcursor.downField("reasons").as[Json].toOption, Some(Json.obj()))
    assertEquals(p.hcursor.downField("hitLastAt").as[Long].toOption, None)
    assertEquals(p.hcursor.downField("missLastAt").as[Long].toOption, None)
    assertEquals(int(p, "redeemedLoopback"), 0L)

  test("hit / miss increment independently with their own lastAt and reason buckets"):
    NearPathStats.resetForTest()
    val before = System.currentTimeMillis()
    assertEquals(report("""{"event":"hit","reason":"direct","port":6138}""").status, Status.NoContent)
    val hitOnly = NearPathStats.payload
    assertEquals(int(hitOnly, "hit"), 1L)
    assertEquals(int(hitOnly, "miss"), 0L)
    assert(int(hitOnly, "hitLastAt") >= before, "hit must stamp its own lastAt")
    assertEquals(hitOnly.hcursor.downField("missLastAt").as[Long].toOption, None)

    assertEquals(report("""{"event":"miss","reason":"pna-blocked","port":6138}""").status, Status.NoContent)
    assertEquals(report("""{"event":"miss","reason":"pna-blocked"}""").status, Status.NoContent)
    val both = NearPathStats.payload
    assertEquals(int(both, "hit"), 1L)
    assertEquals(int(both, "miss"), 2L)
    assertEquals(
      both.hcursor.downField("reasons").downField("pna-blocked").as[Long].toOption,
      Some(2L)
    )
    assertEquals(both.hcursor.downField("reasons").downField("direct").as[Long].toOption, Some(1L))

  test("zero-credential face: no Authorization header => still 204 (never 403)"):
    NearPathStats.resetForTest()
    val resp = report("""{"event":"hit","reason":"direct"}""")
    assertEquals(resp.status, Status.NoContent, "the event face is unauthenticated (dashboard/watchdog parity)")
    assertEquals(resp.bodyText.compile.string.unsafeRunSync(), "", "204 carries no body")

  // ── reason 归一化 ───────────────────────────────────────────────────────

  test("reason normalization: lowercase kept; junk / oversized / empty / case-only-uppercase -> other"):
    assertEquals(NearPathStats.normalizeReason("direct"), "direct")
    assertEquals(NearPathStats.normalizeReason("ws.blocked:pna"), "ws.blocked:pna")
    assertEquals(NearPathStats.normalizeReason("  Timeout  "), "timeout", "trim + lowercase is the normalization")
    assertEquals(NearPathStats.normalizeReason("pna_blocked-x:1"), "pna_blocked-x:1")
    assertEquals(NearPathStats.normalizeReason(""), "other", "empty reason never enters the map")
    assertEquals(NearPathStats.normalizeReason("has space"), "other")
    assertEquals(NearPathStats.normalizeReason("evil<script>"), "other")
    assertEquals(NearPathStats.normalizeReason("a" * 65), "other", "longer than 64 chars -> other")
    assertEquals(NearPathStats.normalizeReason("a" * 64), "a" * 64)
    // 线上形态：坏 reason 一趟下来只落 other，绝不错键
    NearPathStats.resetForTest()
    assertEquals(report("""{"event":"miss","reason":"BAD reason!"}""").status, Status.NoContent)
    assertEquals(report("""{"event":"miss"}""").status, Status.NoContent, "missing reason -> other, still 204")
    val p = NearPathStats.payload
    assertEquals(p.hcursor.downField("reasons").downField("other").as[Long].toOption, Some(2L))
    assertEquals(int(p, "miss"), 2L)

  // ── 限流 & 坏输入：一律 204 静默丢 ───────────────────────────────────────

  test("per-IP per-minute cap: events above the cap are silently dropped (204, no count, no reason)"):
    NearPathStats.resetForTest()
    val cap = NearPathStats.MaxEventsPerMinute
    // 独立 IP（不被其它用例的窗口影响）
    for _ <- 1 to cap do
      assertEquals(report("""{"event":"hit","reason":"direct"}""", remote = Some(SpecRequests.Public)).status,
        Status.NoContent)
    assertEquals(int(NearPathStats.payload, "hit"), cap.toLong)
    for _ <- 1 to 5 do
      assertEquals(report("""{"event":"hit","reason":"direct"}""", remote = Some(SpecRequests.Public)).status,
        Status.NoContent, "over the cap: still a quiet 204 (the observation face never errors)")
    val p = NearPathStats.payload
    assertEquals(int(p, "hit"), cap.toLong, "over-cap calls must not be counted")
    assertEquals(p.hcursor.downField("reasons").downField("direct").as[Long].toOption, Some(cap.toLong))
    // 另一个 IP 不受影响（闸是 per-IP 的）
    assertEquals(report("""{"event":"hit","reason":"direct"}""", remote = Some(SpecRequests.Lan)).status,
      Status.NoContent)
    assertEquals(int(NearPathStats.payload, "hit"), cap.toLong + 1L)

  test("malformed body / unknown event: 204 and NO count (never a 4xx on the observation face)"):
    NearPathStats.resetForTest()
    assertEquals(report("not json").status, Status.NoContent)
    assertEquals(report("").status, Status.NoContent)
    assertEquals(report("""{"event":"redeem"}""").status, Status.NoContent, "only hit|miss are events")
    assertEquals(report("""{"event":"hit"}""", contentType = None).status, Status.NoContent,
      "content-type agnostic (sendBeacon / text-plain shape must still land)")
    val p = NearPathStats.payload
    assertEquals(int(p, "hit"), 1L, "only the valid one counted")
    assertEquals(int(p, "miss"), 0L)

  // ── 读数端点形状 ────────────────────────────────────────────────────────

  test("GET /api/health/nearpath: exactly the contracted key set, no-store, unauthenticated, read-only"):
    NearPathStats.resetForTest()
    val resp = read()
    assertEquals(resp.status, Status.Ok)
    assertEquals(header(resp, "Cache-Control"), Some("no-store"))
    val p = bodyJson(resp)
    val keys = p.asObject.map(_.keys.toSet).getOrElse(Set.empty)
    assertEquals(keys, Set("hit", "miss", "reasons", "hitLastAt", "missLastAt", "redeemedLoopback"))

  test("redeemedLoopback is orthogonal: only the loopback redemption counter moves it"):
    NearPathStats.resetForTest()
    assertEquals(report("""{"event":"hit","reason":"direct"}""").status, Status.NoContent)
    assertEquals(int(NearPathStats.payload, "redeemedLoopback"), 0L, "a browser hit never implies a ticket redemption")
    NearPathStats.noteRedeemedLoopback()
    NearPathStats.noteRedeemedLoopback()
    assertEquals(int(NearPathStats.payload, "redeemedLoopback"), 2L)
    assertEquals(int(NearPathStats.payload, "hit"), 1L)

  // ── CORS（隧道腿与环回腿两侧都可能来打）──────────────────────────────────

  private val PortalOrigin =
    HealthRoutes.canonicalOrigin(Branding.portalUrl).getOrElse(sys.error("brand portalUrl must carry an origin"))
  private val RelayOrigin =
    HealthRoutes.canonicalOrigin(Branding.serverUrl).getOrElse(sys.error("brand serverUrl must carry an origin"))

  test("CORS: whitelisted portal/relay origins may POST the event on BOTH legs; foreign origins get nothing"):
    NearPathStats.resetForTest()
    for origin <- List(PortalOrigin, RelayOrigin) do
      val viaLoopback = report("""{"event":"hit","reason":"direct"}""", origin = Some(origin))
      assertEquals(header(viaLoopback, "Access-Control-Allow-Origin"), Some(origin), s"$origin on the loopback leg")
      assertEquals(header(viaLoopback, "Access-Control-Allow-Private-Network"), Some("true"))
      val viaTunnel = report("""{"event":"miss","reason":"tunnel-leg"}""", remote = Some(SpecRequests.Lan), origin = Some(origin))
      assertEquals(header(viaTunnel, "Access-Control-Allow-Origin"), Some(origin), s"$origin on the tunnel leg")
      assertEquals(header(viaTunnel, "Access-Control-Allow-Methods").exists(_.contains("POST")), true)
    for bad <- List("https://evil.example", "http://localhost:9999", "null") do
      val resp = report("""{"event":"hit","reason":"direct"}""", origin = Some(bad))
      assertEquals(header(resp, "Access-Control-Allow-Origin"), None, s"origin=$bad must get no ACAO")
      assertEquals(header(resp, "Access-Control-Allow-Private-Network"), None, s"origin=$bad must get no PNA")

  test("CORS on the read face + OPTIONS preflight for both new paths (else the browser cannot read/post at all)"):
    val readResp = read(origin = Some(PortalOrigin))
    assertEquals(header(readResp, "Access-Control-Allow-Origin"), Some(PortalOrigin))
    assertEquals(header(readResp, "Vary"), Some("Origin"))
    assertEquals(header(readResp, "Cache-Control"), Some("no-store"))

    for path <- List("/api/health/nearpath-event", "/api/health/nearpath") do
      val pre = serve(SpecRequests.withRemote(
        Request[IO](Method.OPTIONS, Uri.unsafeFromString(path))
          .putHeaders(Header.Raw(CIString("Origin"), PortalOrigin)),
        Some(SpecRequests.LoopbackV4)
      ))
      assertEquals(pre.status, Status.NoContent, s"$path preflight")
      assertEquals(header(pre, "Access-Control-Allow-Origin"), Some(PortalOrigin), s"$path preflight ACAO")
      assertEquals(header(pre, "Access-Control-Allow-Private-Network"), Some("true"))
      assert(header(pre, "Access-Control-Allow-Methods").exists(_.contains("POST")))
      val anon = serve(SpecRequests.withRemote(
        Request[IO](Method.OPTIONS, Uri.unsafeFromString(path))
          .putHeaders(Header.Raw(CIString("Origin"), "https://evil.example")),
        Some(SpecRequests.LoopbackV4)
      ))
      assertEquals(header(anon, "Access-Control-Allow-Origin"), None, s"$path preflight for a foreign origin")

  // ── 爆炸半径：既有 /api/health 载荷键集合不变 ─────────────────────────────

  test("/api/health payload unchanged: base keys only on a non-loopback leg; nearpath keys never leak in"):
    // 基线五键（HealthPayload.build 的 base；本测试基座不装配 update/throttle/fleet）
    val lan = serve(SpecRequests.withRemote(
      Request[IO](Method.GET, Uri.unsafeFromString("/api/health")),
      Some(SpecRequests.Lan)
    ))
    assertEquals(lan.status, Status.Ok)
    val lanKeys = bodyJson(lan).asObject.map(_.keys.toSet).getOrElse(Set.empty)
    // 基线：base 五键 + providerConcurrency（provider-concurrency 批 · 2026-11-06，
    // spawn 侧钉定的模型链快照读数，随 1198 提交合并进入本线；本测试的爆炸半径
    // 仍是「nearpath 键不得上车」，故把该键计入基线而不放宽其余断言）
    assertEquals(lanKeys, Set("product", "status", "version", "providers", "search", "providerConcurrency"),
      "the LAN/relay payload must stay exactly the baseline payload (no new keys from this batch)")
    // 环回腿只允许多 deviceId 一个键（本地加速批 ② 既有语义）；本批零新增键
    val loop = serve(SpecRequests.withRemote(
      Request[IO](Method.GET, Uri.unsafeFromString("/api/health")),
      Some(SpecRequests.LoopbackV4)
    ))
    val loopKeys = bodyJson(loop).asObject.map(_.keys.toSet).getOrElse(Set.empty)
    assert(loopKeys.diff(lanKeys).subsetOf(Set("deviceId")),
      s"the loopback leg may add only deviceId; got extra keys: ${loopKeys.diff(lanKeys)}")
    val nearPathKeys = Set("hit", "miss", "reasons", "hitLastAt", "missLastAt", "redeemedLoopback")
    assertEquals(loopKeys.intersect(nearPathKeys), Set.empty[String], "nearpath counters never ride the health payload")

  test("/api/health/loopback-info keeps its existing behaviour (session gate, unchanged by this batch)"):
    // `/api/health/conn` 不在本 spec 的射程内：它的应答体要 wsHub/wsRoutes 的活实例
    // （本基座 wsRoutes=null ⇒ 构造期即 NPE），而本批**一个字节都没动那条臂**
    // （`git diff src/main/scala/nebflow/gateway/HealthRoutes.scala` 只有新增臂 +
    // 新助手，见提交回执）。此处钉既有带门面：loopback-info 仍 403 无令牌。
    val info = serve(SpecRequests.withRemote(
      Request[IO](Method.GET, Uri.unsafeFromString("/api/health/loopback-info")),
      Some(SpecRequests.LoopbackV4)
    ))
    assertEquals(info.status, Status.Forbidden, "loopback-info keeps its session gate (unchanged by this batch)")

  // ── 身份像素面（闸③ 腿 B，近路批 2026-11-04 四道闸根因修复）────────────────
  // 判据 = 「环回 socket ∧ expect == 本机 deviceId」；其余一律 404。
  // 这一面是 WebKit 上唯一还能发出去的取证形态（fetch 被 mixed content 拦），
  // 所以它的**反例**比正例更要紧：任何非环回来源（= 远端设备/他人的浏览器）
  // 都拿不到图 ⇒ 前端绝不跳走。

  private def pixel(
    remote: Option[com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]],
    expect: Option[String],
    own: Option[String]
  ): Response[IO] =
    val uri = expect.fold("/api/health/loopback-pixel.png")(
      e => s"/api/health/loopback-pixel.png?expect=$e"
    )
    HealthRoutes.LoopbackPixel
      .reply(SpecRequests.withRemote(Request[IO](Method.GET, Uri.unsafeFromString(uri)), remote), own)
      .unsafeRunSync()

  test("identity pixel: loopback leg + expect == own deviceId => 200 PNG with the sentinel dimensions"):
    val resp = pixel(Some(SpecRequests.LoopbackV4), Some("dev-own"), Some("dev-own"))
    assertEquals(resp.status, Status.Ok)
    assertEquals(header(resp, "Content-Type"), Some("image/png"))
    assertEquals(header(resp, "Cache-Control"), Some("no-store"), "身份换了必须立刻看到新答案")
    val bytes = resp.body.compile.toVector.unsafeRunSync().toArray
    assertEquals(bytes.length, 71, "固定 71 B 哨兵图（进程内常量，零写盘）")
    // PNG 签名 + IHDR 宽高 = 哨兵（前端靠 naturalWidth/Height 判同机，双写常量钉死）
    assertEquals(bytes.take(8).toList, List(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map(_.toByte))
    def be32(off: Int): Int =
      ((bytes(off) & 0xFF) << 24) | ((bytes(off + 1) & 0xFF) << 16) |
        ((bytes(off + 2) & 0xFF) << 8) | (bytes(off + 3) & 0xFF)
    assertEquals(be32(16), HealthRoutes.LoopbackPixel.Width)
    assertEquals(be32(20), HealthRoutes.LoopbackPixel.Height)
    assertEquals(HealthRoutes.LoopbackPixel.Width, 5, "与 web/js/ws.js NEARPATH_PIXEL_W 双写")
    assertEquals(HealthRoutes.LoopbackPixel.Height, 3, "与 web/js/ws.js NEARPATH_PIXEL_H 双写")

  test("identity pixel: expect mismatch / missing expect / no minted identity => 404 (never a body)"):
    assertEquals(pixel(Some(SpecRequests.LoopbackV4), Some("dev-other"), Some("dev-own")).status, Status.NotFound)
    assertEquals(pixel(Some(SpecRequests.LoopbackV4), None, Some("dev-own")).status, Status.NotFound)
    assertEquals(pixel(Some(SpecRequests.LoopbackV4), Some(""), Some("dev-own")).status, Status.NotFound)
    assertEquals(pixel(Some(SpecRequests.LoopbackV4), Some("dev-own"), None).status, Status.NotFound,
      "本机身份未铸 ⇒ 谁都不答（不许凭 expect 自证）")

  test("identity pixel: NON-loopback sources never get the image (remote device / other account / tunnel bridge)"):
    for remote <- List(Some(SpecRequests.Lan), Some(SpecRequests.Public), None) do
      val resp = pixel(remote, Some("dev-own"), Some("dev-own"))
      assertEquals(resp.status, Status.NotFound,
        s"remote=$remote must never prove same-machine (expect matches, leg does not)")
      assertEquals(header(resp, "Access-Control-Allow-Origin"), None, "404 也不带任何 CORS 头")

end NearPathStatsSpec
