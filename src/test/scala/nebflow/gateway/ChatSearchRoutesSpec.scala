package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.search.SearchIndexService
import nebflow.core.search.chat.ChatSearchAdapter
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{UiMessage, *}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.server.Router
import org.typelevel.ci.CIString

/**
 * ChatSearchRoutes —— `GET /api/search/chat`（datalayer-spec 批 2026-10-30，C2）。
 *
 * 验收面（设计稿 §6 后端判据 + §4.4 凭证）：
 *   - 谓词参数透传：kinds/sid/since/until/limit/offset 进查询；
 *   - 凭证腿：响应 ETag + If-None-Match 命中 ⇒ 304 零体（HttpCache 同款）；
 *   - ETag 精确失效：会话变更（version bump）后同查询 ETag 变化 ⇒ 200；
 *   - 降级面：未注册/谓词缺失 ⇒ 503 + reason，前端回落旧全扫；
 *   - hits 载荷形状：sid/ord/kind/ts/snippet/extra（浮窗 (sid, ord) 定位兼容）。
 */
class ChatSearchRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-chat-search"
  private val tempRoot: os.Path = os.pwd / "target" / "test-chat-search-routes"
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  private val sessionsDir = tempRoot / "sessions"

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
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "chat-search-routes-archives"),
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

  private val app = Router("/api" -> routes.routes).orNotFound

  private val adapter = new ChatSearchAdapter(sessionsDir)

  override def beforeAll(): Unit =
    SearchIndexService.unsafeResetForTests()
    SearchIndexService.register(adapter, tempRoot).unsafeRunSync()
    seed()

  override def afterAll(): Unit =
    SearchIndexService.unsafeResetForTests()
    os.remove.all(tempRoot)

  private def seed(): Unit =
    os.write.over(sessionsDir / "seed-a.ui.json", List(
      UiMessage.User(text = "聊天记录检索很慢", timestamp = 1000L),
      UiMessage.Ai(text = "delegate dispatch note", timestamp = 1100L)
    ).asJson.noSpaces)
    SearchIndexService.unsafeInsertDocs("chat", adapter.extractSession("seed-a").getOrElse(Nil))

  private def req(path: String, ifNoneMatch: Option[String] = None): Request[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
      .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $TestToken"))
    ifNoneMatch.fold(base)(e => base.putHeaders(Header.Raw(CIString("If-None-Match"), e)))

  private def serve(r: Request[IO]): Response[IO] = app(r).unsafeRunSync()

  private def bodyJson(r: Response[IO]): Json =
    io.circe.parser.parse(r.bodyText.compile.string.unsafeRunSync()).getOrElse(Json.Null)

  test("200：kinds 谓词查询 → hits 载荷形状（sid/ord/kind/ts/snippet/extra）") {
    val resp = serve(req("/api/search/chat?q=%E6%A3%80%E7%B4%A2&kinds=message"))
    assertEquals(resp.status, Status.Ok)
    val json = bodyJson(resp)
    val hits = json.hcursor.downField("hits").as[List[Json]].getOrElse(Nil)
    assertEquals(hits.length, 1)
    val h = hits.head.hcursor
    assertEquals(h.downField("sid").as[String].toOption, Some("seed-a"))
    assertEquals(h.downField("ord").as[Int].toOption, Some(0))
    assertEquals(h.downField("kind").as[String].toOption, Some("message"))
    assert(h.downField("snippet").as[String].toOption.exists(_.contains("检索")))
  }

  test("200：ASCII 词查询（delegate）+ ETag 头在场") {
    val resp = serve(req("/api/search/chat?q=delegate&kinds=message"))
    assertEquals(resp.status, Status.Ok)
    assertEquals(bodyJson(resp).hcursor.downField("total").as[Int].toOption, Some(1))
    assert(resp.headers.get(CIString("ETag")).isDefined, "凭证腿：响应必须带 ETag（§4.4）")
  }

  test("304：If-None-Match 命中 ⇒ 零体重发（§6 跨设备判据）") {
    val first  = serve(req("/api/search/chat?q=delegate&kinds=message"))
    val etag   = first.headers.get(CIString("ETag")).map(_.head.value).getOrElse("")
    val second = serve(req("/api/search/chat?q=delegate&kinds=message", Some(etag)))
    assertEquals(second.status, Status.NotModified)
    val body = second.bodyText.compile.string.unsafeRunSync()
    assertEquals(body, "")
  }

  test("ETag 精确失效：会话变更后同查询 ⇒ 200 新 ETag（version bump）") {
    val first = serve(req("/api/search/chat?q=delegate&kinds=message"))
    val etag1 = first.headers.get(CIString("ETag")).map(_.head.value).getOrElse("")
    // 变更会话内容 → version bump
    os.write.over(sessionsDir / "seed-a.ui.json", List(
      UiMessage.User(text = "聊天记录检索很慢", timestamp = 1000L),
      UiMessage.Ai(text = "delegate dispatch note two", timestamp = 1200L)
    ).asJson.noSpaces)
    SearchIndexService.unsafeInsertDocs("chat", adapter.extractSession("seed-a").getOrElse(Nil))
    val second = serve(req("/api/search/chat?q=delegate&kinds=message", Some(etag1)))
    assertEquals(second.status, Status.Ok, "会话变更 ⇒ ETag 必变（§4.4 精确失效）")
  }

  test("会话键谓词：sid 过滤（current 档）") {
    val resp = serve(req("/api/search/chat?q=delegate&sid=seed-a"))
    assertEquals(resp.status, Status.Ok)
    assertEquals(bodyJson(resp).hcursor.downField("total").as[Int].toOption, Some(1))
  }

  test("降级面：无谓词 ⇒ 503 + reason（前端回落旧全扫）") {
    val resp = serve(req("/api/search/chat?q=delegate"))
    assertEquals(resp.status, Status.ServiceUnavailable)
    assert(bodyJson(resp).hcursor.downField("reason").as[String].toOption.exists(_.contains("shard predicate")))
  }

  test("鉴权：无 Bearer ⇒ 403") {
    val resp = app(Request[IO](Method.GET, Uri.unsafeFromString("/api/search/chat?q=x&kinds=message"))).unsafeRunSync()
    assertEquals(resp.status, Status.Forbidden)
  }

end ChatSearchRoutesSpec
