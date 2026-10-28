package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ProviderRegistry}
import nebflow.service.ConfigService
import nebflow.shared.*
import org.http4s.{Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets

/**
 * 卡窗白屏修复批（2026-10-22）—— `/index.html` 入口别名的路由级钉。
 *
 * 背景：独立卡窗（UI 批⑧）曾以 `/index.html?card-window=1&path=…` 开窗，而
 * 本路由表只把 `/` 派给 index（`GET -> Root`）；`/index.html` 落根文件白名单
 * （sw.js/favicon 族）之外 ⇒ 404 + **Content-Length: 0 空体** —— 独立窗渲染
 * 的就是这个空 404 体（作者实测 link.mystand.space 白窗）。本批给
 * `/index.html`（GET/HEAD）加别名 = 与 `/` 同一 brand 注入应答；现役打开链
 * 已改 `/`（canvas.js popoutCardWindow），别名是历史/深链兜底面。
 *
 * 钉面（真实 WebSocketRoutes 路由表驱动，非函数级直调）：
 *  - GET /index.html（带 query）= 200 index（brand 注入在体、no-cache）
 *  - HEAD /index.html = 同状态头形、空体（与 `HEAD -> Root` 同一 parity 纪律）
 *  - GET / = 200（原入口零变化）
 *  - 根文件白名单不扩权：未知根文件照旧 404（sw.js 等在位照旧 200）
 *  - query 原样保留（http4s 按 path 匹配）：card-window 参数不丢 —— 由
 *    tests/cardwin-session-scenarios.spec.mjs（前端网关忠实服）在浏览器侧
 *    全链路回钉。
 */
class IndexEntryAliasSpec extends FunSuite:

  private val TestToken = "test-token-index-alias"
  private val tempRoot: os.Path = os.pwd / "target" / "test-index-entry-alias"
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  private val NullBackend =
    null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  /** 最小 SharedResources（HealthRoutesSpec 先例）：路由表构造只解引用
   *  `.actorSystem`，本 spec 的目标 case（`GET -> Root[/index.html]`）不触碰
   *  任何服务依赖 —— null 即安全。 */
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "index-alias-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = new ProviderRegistry(
        cats.effect.Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        NullBackend
      ),
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private lazy val routes = new WebSocketRoutes(
    wsb = null,
    sessionService = null,
    agentService = null,
    configService = ConfigService,
    configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
      NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    ),
    rateLimiter = null,
    tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
    sessions = GatewaySessionStore.unsafeCreate(),
    fileChangeTracker = null,
    sessionStore = null,
    wsHub = null,
    mcpManager = null,
    sharedResources = mkResources
  )

  private def run(method: Method, path: String): Response[IO] =
    routes.routes(Request[IO](method, Uri.unsafeFromString(path))).value.unsafeRunSync().get

  private def bodyOf(resp: Response[IO]): String =
    new String(resp.body.compile.toList.unsafeRunSync().toArray, StandardCharsets.UTF_8)

  test("GET /index.html (with card-window query) = brand-injected index, no-cache") {
    val resp = run(Method.GET, "/index.html?card-window=1&path=%2Ftmp%2Fx.md")
    assertEquals(resp.status, Status.Ok)
    val body = bodyOf(resp)
    assert(clue(body).startsWith("<!DOCTYPE"), s"body head: ${body.take(40)}")
    assert(body.contains("<script>window.__BRAND__="), "brand script missing — alias must serve the injected index")
    val cc = resp.headers.get(CIString("Cache-Control")).map(_.head.value)
    assertEquals(cc, Some("no-cache"))
  }

  test("HEAD /index.html = same status, empty body (HEAD -> Root parity)") {
    val resp = run(Method.HEAD, "/index.html?card-window=1")
    assertEquals(resp.status, Status.Ok)
    assertEquals(bodyOf(resp), "")
  }

  test("GET / unchanged (original entry)") {
    val resp = run(Method.GET, "/")
    assertEquals(resp.status, Status.Ok)
    assert(bodyOf(resp).startsWith("<!DOCTYPE"))
  }

  test("root-file allowlist neither widened nor broken") {
    assertEquals(run(Method.GET, "/sw.js").status, Status.Ok)
    assertEquals(run(Method.GET, "/favicon.ico").status, Status.Ok)
    // the very miss that produced the empty-404 white window stays a clean miss
    assertEquals(run(Method.GET, "/nope.txt").status, Status.NotFound)
  }

end IndexEntryAliasSpec
