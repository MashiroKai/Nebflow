package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.SessionStore
import nebflow.core.compact.HistoryArchiver
import nebflow.core.search.SearchIndexService
import nebflow.core.search.chat.ChatSearchAdapter
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{UiMessage, *}
import org.http4s.*
import org.http4s.server.Router
import org.typelevel.ci.CIString

/**
 * 会话搜索范围收窄批（2026-11-01，作者 17:29 令）——**路由层端到端**收口门。
 *
 * 本 spec 用**真路由栈**（RestApiRoutes + 真 SessionStore + 真 ChatSearchAdapter +
 * 真 sqlite 索引）验证「双引擎、两个场景」四条判据，不打桩、不夹具伪造：
 *
 *   引擎甲（服务端索引 / `GET /api/search/chat`）
 *     A1 只在后台 agent 会话里出现的词 ⇒ hits=0 且 total=0；
 *     A2 只在主会话里出现的词 ⇒ hits=1（收窄没有把主会话一起砍掉）。
 *   引擎乙（旧全扫 / `GET /api/sessions?includeUnindexed=1` + 逐会话 history）
 *     B1 搜索范围会话清单里没有任何后台 agent 族；
 *     B2 主会话仍在清单里（跨日/跨重启与时间无关——清单来源是盘 + 索引）；
 *     B3 兄弟入口不受影响：`includeBgAgents=1` 显式取全量时后台族照旧在场
 *        （bg-agent / flow 弹窗面口径不变）。
 *   入库侧（boot 全量对账）
 *     C1 本批之前入库的后台会话残留行：查询层零漏出（A1 已覆盖），且
 *        `fullRebuild` 之后索引库里这些行**物理清零**（docCount 读数）。
 *
 * 历史保护（作者令「主会话自身的历史消息一条不少」）：主会话用**真 appendUiMessages
 * 落盘**（跨批/跨重启即盘上真源），收窄判据只看会话来源、不看时间窗，因此更早批次的
 * 行照旧可搜——A2 命中的正是这样一条落盘历史。
 */
class SearchScopeRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-search-scope"
  private val tempRoot: os.Path = os.pwd / "target" / "test-search-scope-routes"
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "sessions")

  private val sessionsDir = tempRoot / "sessions"
  private val store       = SessionStore(sessionsDir, tempRoot / "tasks")
  private val adapter     = new ChatSearchAdapter(sessionsDir)

  /** 两只关键词：分开种在主会话 / 后台 agent 会话里，互不交叉。 */
  private val MainKw = "主会专属词灯塔"
  private val BgKw   = "后台专属词流星"

  private val NullBackend =
    null.asInstanceOf[sttp.client4.StreamBackend[IO, sttp.capabilities.fs2.Fs2Streams[IO]]]

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = store,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "search-scope-routes-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = new nebflow.llm.ProviderRegistry(
        cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
          NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
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
    sessionStore = store,
    wsRoutes = null
  )

  private val app = Router("/api" -> routes.routes).orNotFound

  /** 后台 agent 族会话（真落盘形态：只写盘，不进索引——与生产同形）。 */
  private val bgIds = List(
    "delegate-nebflow-1a2b3c4d",
    "subtask-2b3c4d5e",
    "node-3c4d5e6f",
    "subagent-4d5e6f70"
  )

  private var mainId: String = ""

  override def beforeAll(): Unit =
    store.load.unsafeRunSync()
    // 主会话：走真 SessionStore（登记进 _index.json + appendUiMessages 真落盘）
    val meta = store.createSession("收窄批主会话").unsafeRunSync()
    mainId = meta.id
    store
      .appendUiMessages(mainId, List(
        UiMessage.User(text = s"更早批次的历史行：$MainKw", timestamp = 1000L),
        UiMessage.Ai(text = "主会话回答", timestamp = 1100L)
      ))
      .unsafeRunSync()
    store.flushPendingUiWrites.unsafeRunSync()
    // 后台 agent 会话：只写 .ui.json（索引从不登记——生产同形）
    bgIds.foreach { sid =>
      os.write(sessionsDir / s"$sid.ui.json",
        List(UiMessage.User(text = s"后台过程行：$BgKw", timestamp = 2000L),
             UiMessage.Ai(text = "后台回答", timestamp = 2100L)).asJson.noSpaces)
    }
    // 索引入口：注册真 adapter（tempRoot 下落 search-index/chat.db）
    SearchIndexService.unsafeResetForTests()
    SearchIndexService.register(adapter, tempRoot).unsafeRunSync()
    // 旧库残留：本批之前入库的后台会话文档（直接灌库 = 模拟改前索引）
    SearchIndexService.unsafeInsertDocs("chat", bgIds.map { sid =>
      SearchIndexService.IndexDoc(sid = sid, ord = 0, kind = "message", ts = 2000L,
        text = s"旧库残留 $BgKw", extra = """{"role":"ai"}""")
    })
    // 主会话入索引（真 rebuild 路径 —— A2 的前提）
    SearchIndexService.fullRebuild("chat", List(mainId)).unsafeRunSync()

  override def afterAll(): Unit =
    SearchIndexService.unsafeResetForTests()
    os.remove.all(tempRoot)

  private def req(path: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(path))
      .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $TestToken"))

  private def serve(path: String): Json =
    val body = app(req(path)).unsafeRunSync().bodyText.compile.string.unsafeRunSync()
    io.circe.parser.parse(body).getOrElse(Json.Null)

  private def hits(path: String): (List[String], Int) =
    val j = serve(path)
    val h = j.hcursor.downField("hits").as[List[Json]].getOrElse(Nil)
    (h.flatMap(_.hcursor.downField("sid").as[String].toOption), j.hcursor.downField("total").as[Int].getOrElse(-1))

  private def sessionIds(path: String): List[String] =
    serve(path).hcursor.downField("sessions").as[List[Json]].getOrElse(Nil)
      .flatMap(_.hcursor.downField("id").as[String].toOption)

  // ── 引擎甲：索引查询 API ──────────────────────────────────────────────────

  test("A1 索引引擎：后台 agent 专属词 ⇒ 零结果（total=0，旧库残留也不漏出）") {
    val (sids, total) = hits(s"/api/search/chat?q=${enc(BgKw)}&kinds=message&limit=200")
    assertEquals(sids, Nil)
    assertEquals(total, 0)
  }

  test("A2 索引引擎：主会话专属词 ⇒ 命中（历史行照旧可搜）") {
    val (sids, total) = hits(s"/api/search/chat?q=${enc(MainKw)}&kinds=message&limit=200")
    assertEquals(sids, List(mainId))
    assertEquals(total, 1)
  }

  test("A3 current 档（浮钮面板 scope=current）：即便 sid 明指后台会话也零命中") {
    val (sids, total) = hits(s"/api/search/chat?q=${enc(BgKw)}&kinds=message&sid=${enc(bgIds.head)}")
    assertEquals(sids, Nil)
    assertEquals(total, 0)
  }

  // ── 引擎乙：旧全扫会话清单 ────────────────────────────────────────────────

  test("B1/B2 旧全扫引擎：搜索范围清单只有主会话；后台 agent 族一个不出") {
    val ids = sessionIds("/api/sessions?includeUnindexed=1")
    assert(ids.contains(mainId), clue(s"主会话必须在搜索范围内：$ids"))
    bgIds.foreach(sid => assert(!ids.contains(sid), clue(s"后台会话漏进搜索范围：$sid")))
  }

  test("B3 兄弟入口不变：includeBgAgents=1 显式取全量（bg-agent/flow 弹窗面）") {
    val ids = sessionIds("/api/sessions?includeUnindexed=1&includeBgAgents=1")
    assert(ids.contains(mainId))
    bgIds.foreach(sid => assert(ids.contains(sid), clue(s"全量档缺 $sid")))
  }

  test("B4 侧边栏路径（无参数）不受影响：索引全量、零后台族") {
    val ids = sessionIds("/api/sessions")
    assert(ids.contains(mainId))
    bgIds.foreach(sid => assert(!ids.contains(sid)))
  }

  // ── 入库侧：boot 全量对账把残留行物理清零 ────────────────────────────────

  test("C1 fullRebuild 后索引库只剩主会话行（旧库残留物理清零）") {
    val before = SearchIndexService.docCount("chat")
    // 全量对账清单 = 盘上全部会话（生产形态：listSessionsIncludeUnindexed）
    val allSids = store.listSessionsIncludeUnindexed.unsafeRunSync().map(_.id)
    assert(allSids.exists(bgIds.contains), clue("夹具应含后台族，否则本断言无意义"))
    SearchIndexService.fullRebuild("chat", allSids).unsafeRunSync()
    val after = SearchIndexService.docCount("chat")
    assert(before > after, clue(s"残留行应被清零：before=$before after=$after"))
    // 清零之后仍只有主会话的两条历史行可命中（收窄不误伤主会话）
    val (_, mainTotal) = hits(s"/api/search/chat?q=${enc(MainKw)}&kinds=message")
    assertEquals(mainTotal, 1)
    val (_, bgTotal) = hits(s"/api/search/chat?q=${enc(BgKw)}&kinds=message")
    assertEquals(bgTotal, 0)
  }

  /** query-string 百分号编码（CJK/大小写安全；只编码保留字符）。 */
  private def enc(s: String): String =
    java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

  // ── 证据导出（实拍用）：四条真响应体落 evidence/search-scope/api/ ──────────

  test("E1 证据导出：真响应体落盘（前端实拍回放用，非手写夹具）") {
    val dir = os.pwd / "evidence" / "search-scope" / "api"
    os.makeDir.all(dir)
    def raw(path: String): String =
      app(req(path)).unsafeRunSync().bodyText.compile.string.unsafeRunSync()
    val files = Map(
      "sessions-search-scope.json" -> raw("/api/sessions?includeUnindexed=1"),
      "sessions-full.json"         -> raw("/api/sessions?includeUnindexed=1&includeBgAgents=1"),
      "history-main.json"          -> raw(s"/api/sessions/${enc(mainId)}/history"),
      "search-main.json"           -> raw(s"/api/search/chat?q=${enc(MainKw)}&kinds=message&limit=200"),
      "search-bg.json"             -> raw(s"/api/search/chat?q=${enc(BgKw)}&kinds=message&limit=200")
    )
    files.foreach { case (name, body) => os.write.over(dir / name, body) }
    // 摘要读数：实拍脚本据此断言（与上面 A/B 组同源）
    os.write.over(dir / "README.txt",
      s"""# 会话搜索范围收窄批 · 真响应体（由 SearchScopeRoutesSpec E1 导出）
         |主会话 id = $mainId
         |主会话关键词 = $MainKw（search-main.json 命中数 = 1）
         |后台 agent 关键词 = $BgKw（search-bg.json 命中数 = 0）
         |后台 agent 会话（只在 sessions-full.json 里出现）= ${bgIds.mkString(", ")}
         |""".stripMargin)
    assert(os.exists(dir / "search-bg.json"))
  }

end SearchScopeRoutesSpec
