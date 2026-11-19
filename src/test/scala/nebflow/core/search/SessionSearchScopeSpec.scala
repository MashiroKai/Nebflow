package nebflow.core.search

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.{CatsEffectSuite, FunSuite}
import nebflow.core.SessionStore
import nebflow.core.search.chat.ChatSearchAdapter
import nebflow.shared.{SessionScope, UiMessage}

import scala.io.Source

/**
 * 会话搜索范围收窄批（2026-11-01，作者 17:29 裁定：「我们的主 agent 的聊天记录
 * 搜索，是不搜索后台 agent 产生的消息的，只搜主会话。」）—— 三个收口点的契约门。
 *
 * 断言面（每层给可复算读数，禁自查自证）：
 *   ① **判据单点**（[[SessionScope]]）：8 个后台族逐族为「后台」，uuid 主会话 /
 *      索引登记名 / team- 路由标记为非后台；空 id 不入面。
 *   ② **漂移门**：`InjectedInboxMirror.SubAgentIdPrefixes`（后端 bg-agent 弹窗族）
 *      逐值 ⊆ 本表；前端 `utils.js#isBgAgentId` 逐值 ⊆ 本表（源码文本对账，先例 =
 *      SubAgentInboxMirrorSpec ③-1）——新增后台族却漏进搜索面必然验红。
 *   ③ **入库收口**（ChatSearchAdapter）：后台会话 extractSession / 指纹 = None
 *      （⇒ 索引侧删旧行）；同关键词的主会话照常抽取。
 *   ④ **查询收口**（SearchIndexService）：**直接灌入**后台会话文档（模拟本批之前
 *      入库的旧库残留）后查询 ⇒ 零命中、total=0；主会话文档照常命中 ⇒ 证明兜底在
 *      查询层 WHERE（不是前端事后剔除），旧库不清也不会漏出。
 *   ⑤ **搜索数据源收口**（SessionStore.listSearchScopeSessions）：盘上主会话 + 三个
 *      后台族会话并存时，搜索范围清单只含主会话；兄弟入口 listSessions /
 *      listSessionsIncludeUnindexed 行为不变（弹窗面不受影响）。
 */
class SessionSearchScopeSpec extends CatsEffectSuite:

  private val root = os.temp.dir(deleteOnExit = true) / "session-scope-spec"
  private val sessionsDir = root / "sessions"

  override def beforeEach(context: BeforeEach): Unit =
    os.remove.all(root)
    os.makeDir.all(sessionsDir)

  // ── ① 判据单点 ────────────────────────────────────────────────────────────

  test("① 判据：8 个后台族逐族 = 后台；uuid 主会话 / 索引登记名 = 主会话") {
    val bg = List(
      "delegate-nebflow-1a2b3c4d", "subtask-1a2b3c4d", "subagent-1a2b3c4d",
      "workflow-1a2b3c4d", "node-1a2b3c4d", "dispatcher-p1", "external-codex-7cf37283",
      "dag-release-be-coder-1a2b3c"
    )
    bg.foreach(sid => assert(SessionScope.isBackgroundAgentSession(sid), clue(sid)))
    bg.foreach(sid => assert(!SessionScope.isMainSession(sid), clue(sid)))

    val main = List(
      "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff", // 主会话（uuid）
      "team-abc",                              // MailTool 前端路由标记，非会话族
      "ask-verify-001"                         // 非族前缀 ⇒ 保守留面（宁多不漏）
    )
    main.foreach(sid => assert(SessionScope.isMainSession(sid), clue(sid)))

    // 空 id：无从判定 ⇒ 不入面（索引层不建空 sid 文档）
    assert(!SessionScope.isMainSession(""))
  }

  // ── ② 漂移门 ──────────────────────────────────────────────────────────────

  private def read(rel: String): String =
    val p = os.pwd / os.RelPath(rel).segments
    assert(os.exists(p), s"源码不存在：$p（本 spec 必须在仓根运行）")
    val src = Source.fromFile(p.toIO, "UTF-8")
    try src.mkString
    finally src.close()

  test("② 漂移门：后端 bg-agent 弹窗族 ⊆ 搜索排除表（逐值）") {
    val popupFamilies = nebflow.agent.InjectedInboxMirror.SubAgentIdPrefixes
    popupFamilies.foreach { p =>
      assert(SessionScope.BackgroundAgentPrefixes.contains(p), clue(s"漏收 $p"))
    }
    // 表本体 pin（改表 ⇒ 必须同步本 spec + 批报告收口点说明）
    assertEquals(
      SessionScope.BackgroundAgentPrefixes,
      List("delegate-", "subtask-", "subagent-", "workflow-", "node-", "dispatcher-", "external-", "dag-")
    )
  }

  test("② 漂移门：前端 utils.js#isBgAgentId 逐值 ⊆ 搜索排除表（源码文本对账）") {
    val js = read("src/main/resources/web/js/utils.js")
    val start = js.indexOf("export function isBgAgentId")
    assert(start >= 0, "utils.js 里找不到 isBgAgentId（前端判据改名/移位 ⇒ 本门需重新 pin）")
    val body = js.substring(start, math.min(js.length, start + 800))
    val found = "id\\.startsWith\\(['\"]([a-z-]+)['\"]\\)".r
      .findAllMatchIn(body).map(_.group(1)).toList.distinct
    assert(found.nonEmpty, s"未从 isBgAgentId 解析出前缀：${body.take(200)}")
    found.foreach { p =>
      assert(SessionScope.BackgroundAgentPrefixes.contains(p), clue(s"前端 bg 族 $p 未进搜索排除表"))
    }
  }

  // ── ③ 入库收口（adapter） ─────────────────────────────────────────────────

  test("③ 入库收口：后台会话不入索引（extractSession/指纹 = None）；主会话照常抽取") {
    val adapter = new ChatSearchAdapter(sessionsDir)
    val kw = "口径关键词OVERRUN"
    def write(sid: String): Unit =
      os.write(sessionsDir / s"$sid.ui.json",
        List[UiMessage](UiMessage.User(text = kw, timestamp = 1000L)).asJson.noSpaces)

    write("delegate-nebflow-1a2b3c4d")
    write("d7c3cb54-dd90-4f3f-bbf6-e7898c425cff")

    assertEquals(adapter.extractSession("delegate-nebflow-1a2b3c4d"), None)
    assertEquals(adapter.sessionFingerprint("delegate-nebflow-1a2b3c4d"), None)
    assertEquals(adapter.sessionMtime("delegate-nebflow-1a2b3c4d"), None)
    assertEquals(adapter.monthKey("delegate-nebflow-1a2b3c4d"), "")

    val mainDocs = adapter.extractSession("d7c3cb54-dd90-4f3f-bbf6-e7898c425cff").getOrElse(Nil)
    assertEquals(mainDocs.count(_.kind == "message"), 1)
    assert(mainDocs.exists(_.text.contains(kw)))
    // adapter 把排除表交给查询层（同一张表，禁副本）
    assertEquals(adapter.excludedSidPrefixes, SessionScope.BackgroundAgentPrefixes)
  }

  // ── ④ 查询收口（旧库残留兜底） ────────────────────────────────────────────

  test("④ 查询收口：旧库残留（后台会话文档）零命中、total=0；主会话照常命中") {
    val adapter = new ChatSearchAdapter(sessionsDir)
    val kw = "残留关键词彗星"
    os.write(sessionsDir / "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff.ui.json",
      List[UiMessage](UiMessage.User(text = s"main $kw", timestamp = 2000L)).asJson.noSpaces)

    SearchIndexService.unsafeResetForTests()
    SearchIndexService.unsafeRegisterInMemory(adapter)
    // 主会话：走 adapter 正常抽取
    SearchIndexService.unsafeInsertDocs("chat",
      adapter.extractSession("d7c3cb54-dd90-4f3f-bbf6-e7898c425cff").getOrElse(Nil))
    // 后台会话：**绕过 adapter 直接灌库** = 模拟本批之前入库的旧库残留行
    SearchIndexService.unsafeInsertDocs("chat", List(
      SearchIndexService.IndexDoc(sid = "delegate-nebflow-1a2b3c4d", ord = 0, kind = "message",
        ts = 1000L, text = s"delegate $kw", extra = """{"role":"ai"}"""),
      SearchIndexService.IndexDoc(sid = "node-1a2b3c4d", ord = 0, kind = "message",
        ts = 1100L, text = s"node $kw", extra = """{"role":"ai"}""")
    ))

    for
      all   <- SearchIndexService.query("chat", SearchIndexService.Query(kw, kinds = List("message")))
      main  <- SearchIndexService.query("chat", SearchIndexService.Query(kw, kinds = List("message")))
      cur   <- SearchIndexService.query("chat", SearchIndexService.Query(kw, sid = Some("node-1a2b3c4d")))
    yield
      assertEquals(all.toOption.get.hits.map(_.sid), List("d7c3cb54-dd90-4f3f-bbf6-e7898c425cff"))
      assertEquals(all.toOption.get.total, 1)   // total 与 hits 同谓词计数，不虚高
      // current 档（浮钮面板 scope=current）同样收口：即便 sid 明指后台会话也零命中
      assertEquals(cur.toOption.get.hits, Nil)
      assertEquals(cur.toOption.get.total, 0)
      assert(main.toOption.get.hits.nonEmpty)
  }

  // ── ⑤ 搜索数据源收口（session 清单） ──────────────────────────────────────

  test("⑤ 数据源收口：搜索范围清单只含主会话；兄弟入口（侧边栏 / 弹窗全量）行为不变") {
    val store = new SessionStore(sessionsDir, root / "tasks")
    def write(sid: String, name: String): Unit =
      os.write(sessionsDir / s"$sid.ui.json",
        List[UiMessage](UiMessage.User(text = name, timestamp = 1000L)).asJson.noSpaces)

    write("d7c3cb54-dd90-4f3f-bbf6-e7898c425cff", "主会话")
    write("delegate-nebflow-1a2b3c4d", "后台 delegate")
    write("node-1a2b3c4d", "后台 node")
    write("subagent-1a2b3c4d", "后台 subagent")

    for
      scope <- store.listSearchScopeSessions
      full  <- store.listSessionsIncludeUnindexed
      index <- store.listSessions
    yield
      // 搜索范围：只剩主会话（跨日/跨重启无关——清单来源是盘 + 索引，不按时间过滤）
      assertEquals(scope.map(_.id), List("d7c3cb54-dd90-4f3f-bbf6-e7898c425cff"))
      // 兄弟入口：全量并集不受影响（bg-agent / flow 弹窗面口径不变）
      assertEquals(full.map(_.id).sorted, List(
        "d7c3cb54-dd90-4f3f-bbf6-e7898c425cff", "delegate-nebflow-1a2b3c4d",
        "node-1a2b3c4d", "subagent-1a2b3c4d").sorted)
      // 侧边栏（索引全量；本夹具 index 为空 → 空表，恒不等于 full）
      assertEquals(index, Nil)
  }

end SessionSearchScopeSpec
