package nebflow.core.search

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * SearchIndexService —— 数据层检索骨架索引服务（datalayer-spec 批 2026-10-30，
 * C1+C2 服务端半边的结构验收）。内存 SQLite（::memory:），零落盘。
 *
 * 验收面（设计稿 §4.1-4.4 + §6 后端判据）：
 *   - 谓词纪律：kinds/sid/since/until 全无 ⇒ Left（§4.1 纪律 3）；
 *   - 类型片/时间片/会话键预过滤（五 tab 查询面 / current 档）；
 *   - 增量 upsert：同会话重抽替换 + version bump（C6 凭证腿）；
 *   - CJK bigram 查询（「检索」phrase）与 ASCII 词查询两侧同变换；
 *   - LIKE 兜底：单字 CJK 查询 degraded=true 且命中；
 *   - 会话删除：行随删；
 *   - 未注册 face ⇒ Left（降级面，调用方回落旧路径）。
 */
class SearchIndexServiceSpec extends CatsEffectSuite:

  private val face = "test-face"

  /** 测试 adapter：内存 doc 存储——extractSession 返回灌入的文档（ reconcile /
   *  sync 路径因此非破坏性：真 adapter 语义 = 从真源抽取，测试用内存源等价）。 */
  private val store = new java.util.concurrent.ConcurrentHashMap[String, List[SearchIndexService.IndexDoc]]()

  private val adapter = new SearchIndexAdapter:
    override val face: String = "test-face"
    override def sessionFingerprint(sessionId: String): Option[String] =
      Option(store.get(sessionId)).map(ds => s"fp:${ds.hashCode()}")
    override def extractSession(sessionId: String, forceFull: Boolean): Option[List[SearchIndexService.IndexDoc]] =
      Option(store.get(sessionId))

  private def doc(sid: String, ord: Int, kind: String, ts: Long, text: String, extra: String = ""): SearchIndexService.IndexDoc =
    SearchIndexService.IndexDoc(sid, ord, kind, ts, text, extra)

  /** 灌库 + 登记 adapter 存储（reconcile 抽取同源）。 */
  private def seed(docs: List[SearchIndexService.IndexDoc]): Unit =
    docs.groupBy(_.sid).foreach { case (sid, ds) => store.put(sid, ds.sortBy(_.ord)) }
    SearchIndexService.unsafeInsertDocs(face, docs)

  override def beforeAll(): Unit =
    SearchIndexService.unsafeResetForTests()
    SearchIndexService.unsafeRegisterInMemory(adapter)

  override def afterAll(): Unit =
    SearchIndexService.unsafeResetForTests()

  private def q(text: String, kinds: List[String] = Nil, sid: Option[String] = None,
                since: Option[Long] = None, until: Option[Long] = None,
                limit: Int = 50, offset: Int = 0): SearchIndexService.Query =
    SearchIndexService.Query(text, kinds, sid, since, until, limit, offset)

  test("谓词纪律：kinds/sid/since/until 全无 ⇒ Left（§4.1 纪律 3）") {
    SearchIndexService.query(face, q("anything")).map { r =>
      assert(r.isLeft, clue(r))
      assert(clue(r.left.getOrElse("")).contains("shard predicate"))
    }
  }

  test("类型片预过滤：kinds=[tool] 只回 tool 片（五 tab 查询面）") {
    seed(List(
      doc("k1", 0, "message", 1000L, "report 讨论"),
      doc("k1", 1, "tool", 0L, "report.pdf generated"),
      doc("k1", 2, "media", 1000L, "photo.png\npng\n/tmp/photo.png")
    ))
    SearchIndexService.query(face, q("report", kinds = List("tool"))).map { r =>
      assert(r.isRight, clue(r))
      val res = r.toOption.get
      assertEquals(res.hits.map(_.kind), List("tool"))
      assertEquals(res.total, 1)
    }
  }

  test("会话键谓词：sid 过滤（current 档语义）+ 跨会话不误伤") {
    seed(List(
      doc("k2a", 0, "message", 1000L, "apple pie"),
      doc("k2b", 0, "message", 2000L, "apple juice")
    ))
    SearchIndexService.query(face, q("apple", sid = Some("k2b"))).map { r =>
      val res = r.toOption.get
      assertEquals(res.hits.map(_.sid), List("k2b"))
      assertEquals(res.total, 1)
    }
  }

  test("时间片谓词：since/until 窗口过滤") {
    seed(List(
      doc("k3", 0, "message", 1000L, "banana old"),
      doc("k3", 1, "message", 5000L, "banana new")
    ))
    SearchIndexService.query(face, q("banana", since = Some(4000L))).map { r =>
      val res = r.toOption.get
      assertEquals(res.hits.map(_.ord), List(1))
    }
  }

  test("CJK bigram 查询：「检索」phrase 命中（两侧同变换）") {
    seed(List(
      doc("k4", 0, "message", 1000L, "聊天记录检索速度")
    ))
    SearchIndexService.query(face, q("检索", kinds = List("message"))).map { r =>
      val res = r.toOption.get
      assertEquals(res.total, 1)
      assertEquals(res.hits.head.sid, "k4")
      assert(!res.degraded)
    }
  }

  test("ASCII 词查询：unicode61 词 token 两侧对齐") {
    seed(List(
      doc("k5", 0, "tool", 0L, "found 1 files via delegate dispatch")
    ))
    SearchIndexService.query(face, q("delegate", kinds = List("tool"))).map { r =>
      assertEquals(r.toOption.get.total, 1)
    }
  }

  test("单字 CJK 查询：LIKE 兜底命中且 degraded=true") {
    seed(List(
      doc("k6", 0, "message", 1000L, "这是一个好方案")
    ))
    SearchIndexService.query(face, q("好", kinds = List("message"))).map { r =>
      val res = r.toOption.get
      assertEquals(res.total, 1)
      assert(res.degraded)
    }
  }

  test("增量 upsert：同会话重抽 = 旧行替换（无重复）+ version bump ⇒ ETag 变化") {
    seed(List(doc("k7", 0, "message", 1000L, "alpha")))
    val etag1F = SearchIndexService.query(face, q("alpha", sid = Some("k7"))).map(_.toOption.get.etag)
    // 同 (sid,ord) 重插（内容变了）——replace 语义
    seed(List(doc("k7", 0, "message", 1000L, "alpha beta")))
    val etag2F = SearchIndexService.query(face, q("alpha", sid = Some("k7"))).map(_.toOption.get)
    for
      etag1 <- etag1F
      res2  <- etag2F
      _      = assertEquals(res2.total, 1)
      _      = assert(clue(res2.etag) != clue(etag1), "version bump ⇒ ETag 必变（§4.4 精确失效）")
      // 旧内容行不得残留：查 beta 只命中一次（同一行替换而非叠加）
      r3    <- SearchIndexService.query(face, q("beta", sid = Some("k7")))
    yield
      assertEquals(r3.toOption.get.total, 1)
  }

  test("会话删除：removeSession 行随删（deleteSession 锚点）") {
    seed(List(doc("k8", 0, "message", 1000L, "gamma")))
    SearchIndexService.removeSession(face, "k8") *>
      SearchIndexService.query(face, q("gamma", kinds = List("message"))).map { r =>
        assertEquals(r.toOption.get.total, 0)
      }
  }

  test("未注册 face ⇒ Left（降级面：调用方回落旧全扫）") {
    SearchIndexService.query("no-such-face", q("x", kinds = List("message"))).map { r =>
      assert(r.isLeft)
    }
  }

  test("空关键词 ⇒ Left") {
    SearchIndexService.query(face, q("  ", kinds = List("message"))).map { r =>
      assert(r.isLeft)
    }
  }

  test("分页：limit/offset 截窗 + total 为未截断计数") {
    val docs = (0 until 10).map(i => doc("k9", i, "message", (i + 1) * 1000L, s"pageable $i")).toList
    SearchIndexService.unsafeInsertDocs(face, docs)
    SearchIndexService.query(face, q("pageable", kinds = List("message"), limit = 3, offset = 4)).map { r =>
      val res = r.toOption.get
      assertEquals(res.hits.length, 3)
      assertEquals(res.total, 10)
    }
  }

end SearchIndexServiceSpec
