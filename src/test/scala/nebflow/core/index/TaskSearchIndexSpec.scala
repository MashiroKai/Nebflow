package nebflow.core.index

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.project.TaskLedgerStore
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * task-rag 批（#1227 / §Batch 7）验收：索引面。
 *
 * 中心性质：索引源 = TaskLedger 条目（task + notes 摘要）＋ delegate 会话
 * 标题/任务书；增量 = 内容 hash 判据（未变不重嵌、变化重嵌、消失清除——启动
 * 补扫与写后触发共用同一 upkeep 核）；未配 embedding = 词法降级；余弦暴力扫
 * 纯函数可证。🔴 本 spec 零真实 embedding 端点：fixture 向量注入（embed 函数
 * 参数面），fixture 设置（resolveSettings 的配置解析另有 EmbeddingServiceSpec）。
 */
class TaskSearchIndexSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-tidx-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  /** fixture 设置与嵌入函数：含 "alpha" ⇒ vecA；含 "beta" ⇒ 与 vecA 夹角 0.9 的
    * vecB；否则 vecC（可判序的确定性映射——alpha 查询命中序 = a1(1.0) → b*(0.9)）。 */
  private val fixtureSettings = EmbeddingService.EmbeddingSettings(
    baseUrl = "https://fixture.invalid/v1",
    model = "fixture-emb",
    token = None,
    timeoutMs = 1000
  )

  private val vecA = List(1.0f, 0.0f, 0.0f)
  private val vecB = List(0.9f, 0.43589f, 0.0f)
  private val vecC = List(0.0f, 0.0f, 1.0f)
  private def fixtureEmbed(texts: List[String]): IO[Option[List[List[Float]]]] =
    IO.pure(
      Some(texts.map { t =>
        if t.contains("alpha") then vecA
        else if t.contains("beta") then vecB
        else vecC
      })
    )

  private def createTask(title: String): String =
    TaskLedgerStore.open().createSyncReturningId(title = title).fold(e => fail(e.message), identity)

  // ── 条目装配 ──────────────────────────────────────────────────────────

  test("collectEntriesSync: ledger entries (task + notes) and delegate records both indexed") {
    withTempDataRoot { tmp =>
      IO {
        val id = createTask("Fix the login timeout regression")
        TaskLedgerStore.open().appendNoteSync(id, "root cause: token refresh races the session sweep")
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-kernel-abc12345",
            executor = "zcode",
            cwd = "/tmp/ws",
            project = None,
            taskId = Some(id),
            parentSessionId = "root-1",
            title = "Migrate the cache layer",
            createdAt = 1L
          )
        )
        val entries = TaskSearchIndex.collectEntriesSync()
        val taskEntry = entries.find(_.entryId == s"task:$id")
        val delegateEntry = entries.find(_.entryId == "delegate:delegate-kernel-abc12345")

        // task 面：标题 + note 摘要进索引文本；绑定 delegate 的图标/地址投影
        assert(taskEntry.isDefined)
        assertEquals(taskEntry.get.kind, "task")
        assertEquals(taskEntry.get.taskId, id)
        assertEquals(taskEntry.get.status, "open")
        assert(taskEntry.get.text.contains("Fix the login timeout regression"))
        assert(taskEntry.get.text.contains("token refresh races"), "notes 摘要进索引文本")
        assertEquals(taskEntry.get.executor, "zcode", "任务侧图标 = 绑定 delegate 的执行器投影")
        assertEquals(taskEntry.get.address, "delegate:delegate-kernel-abc12345", "S14 反转后语义不变：address = delegate:<id>")

        // delegate 面：标题 + 任务书（账本标题）
        assert(delegateEntry.isDefined)
        assertEquals(delegateEntry.get.kind, "delegate")
        assertEquals(delegateEntry.get.executor, "zcode")
        assertEquals(delegateEntry.get.address, "delegate:delegate-kernel-abc12345")
        assert(delegateEntry.get.text.contains("Migrate the cache layer"), "会话标题进索引文本")
        assert(delegateEntry.get.text.contains("Fix the login timeout regression"), "任务书（账本标题）进索引文本")
        assertEquals(delegateEntry.get.status, "", "无申报 = 状态空（live 状态以内存为准，索引不冒充）")

        // 终态申报后：状态进条目（delegate 终态增量索引的数据面）
        DelegateRegistry.updateReport("delegate-kernel-abc12345", "blocked", "waiting for the schema migration", "")
        val after = TaskSearchIndex.collectEntriesSync().find(_.entryId == "delegate:delegate-kernel-abc12345")
        assertEquals(after.get.status, "blocked")
        assert(after.get.text.contains("waiting for the schema migration"), "申报 detail 进索引文本")
      }
    }.unsafeRunSync()
  }

  // ── 纯函数 ────────────────────────────────────────────────────────────

  test("textHash: content+model keyed; model change ⇒ hash change") {
    val h1 = TaskSearchIndex.textHash("m1", "text")
    assertEquals(h1, TaskSearchIndex.textHash("m1", "text"), "同文同模同 hash")
    assert(h1 != TaskSearchIndex.textHash("m1", "text2"), "文本变 ⇒ hash 变")
    assert(h1 != TaskSearchIndex.textHash("m2", "text"), "模型变 ⇒ hash 变（全量重嵌判据）")
  }

  test("cosine: identical = 1, orthogonal = 0, dimension mismatch = 0") {
    val v = List(1.0f, 2.0f, 3.0f)
    assertEquals(TaskSearchIndex.cosine(v, v.map(_ * 2)), 1.0, "方向相同 = 1（幅值无关）")
    assertEquals(TaskSearchIndex.cosine(List(1.0f, 0.0f), List(0.0f, 1.0f)), 0.0)
    assertEquals(TaskSearchIndex.cosine(v, List(1.0f, 0.0f)), 0.0, "维度不合 = 0")
    assertEquals(TaskSearchIndex.cosine(Nil, Nil), 0.0, "零向量 = 0")
  }

  test("tokenize + lexicalScore: ASCII words and CJK bigrams both match") {
    val toks = TaskSearchIndex.tokenize("Fix the Login-Timeout regression 登录超时")
    assert(toks.contains("login"))
    assert(toks.contains("timeout"))
    assert(toks.contains("登录"), "CJK 单字")
    assert(toks.contains("录超"), "CJK 二元组")
    val score = TaskSearchIndex.lexicalScore(toks, "the login timeout 登录超时 bug")
    assert(score > 0.0 && score <= 1.0)
    assertEquals(TaskSearchIndex.lexicalScore(Nil, "x"), 0.0, "空查询 = 0")
    assertEquals(TaskSearchIndex.lexicalScore(List("zzz"), "nothing here"), 0.0, "零重叠 = 0")
  }

  // ── 增量 upkeep（fixture 嵌入）────────────────────────────────────────

  test("upkeep: backfill embeds new entries; rerun is a no-op (hash unchanged); changes re-embed; stale ids pruned") {
    withTempDataRoot { tmp =>
      IO {
        // 启动补扫（面开形态）：空索引 → 全量嵌入
        val id1 = createTask("alpha task one")
        val st1 = TaskSearchIndex.upkeepWith(fixtureSettings, fixtureEmbed).unsafeRunSync()
        assertEquals(st1.mode, TaskSearchIndex.ModeEmbedding)
        assertEquals(st1.embedded, 1, "新条目全嵌")
        val after1 = VectorStore.read()
        assertEquals(after1.entries.keySet, Set(s"task:$id1"))

        // 重跑：hash 未变 ⇒ 零重嵌（增量判据）
        val st2 = TaskSearchIndex.upkeepWith(fixtureSettings, fixtureEmbed).unsafeRunSync()
        assertEquals(st2.embedded, 0, "hash 未变不重嵌")

        // 文本变化 ⇒ 重嵌该条
        TaskLedgerStore.open().appendNoteSync(id1, "alpha follow-up note changes the text")
        val st3 = TaskSearchIndex.upkeepWith(fixtureSettings, fixtureEmbed).unsafeRunSync()
        assertEquals(st3.embedded, 1, "变化条目重嵌")

        // 条目消失 ⇒ 陈旧向量清除
        TaskLedgerStore.open().closeSync(id1)
        // close 只变状态不改标题——手工删文件模拟条目从账本消失（TTL 清理同效果）
        os.remove(tmp / "tasks-v2.json")
        val st4 = TaskSearchIndex.upkeepWith(fixtureSettings, fixtureEmbed).unsafeRunSync()
        assertEquals(st4.removed, 1, "陈旧 id 清除")
        assertEquals(VectorStore.read().entries.keySet, Set.empty)
      }
    }.unsafeRunSync()
  }

  test("upkeep with embedding off is a fast no-op (lexical path needs no vectors)") {
    withTempDataRoot { tmp =>
      IO {
        createTask("alpha task")
        // fixture 设置面开时先建了向量；off 形态 = 不扫描不写
        val before = VectorStore.read().entries.size
        val st = TaskSearchIndex.upkeep().unsafeRunSync() // 无配置 dataRoot ⇒ off
        assertEquals(st.mode, "off")
        assertEquals(st.embedded, 0)
        assertEquals(VectorStore.read().entries.size, before)
      }
    }.unsafeRunSync()
  }

  test("upkeep embed failure keeps existing vectors (skip batch, retry next round)") {
    withTempDataRoot { tmp =>
      IO {
        val id = createTask("alpha task")
        val st1 = TaskSearchIndex.upkeepWith(fixtureSettings, fixtureEmbed).unsafeRunSync()
        assertEquals(st1.embedded, 1)
        // 文本变化 + 嵌入失败 ⇒ 旧向量保留、hash 未写回（下轮重试）
        TaskLedgerStore.open().appendNoteSync(id, "alpha change")
        val failing: List[String] => IO[Option[List[List[Float]]]] = _ => IO.pure(None)
        val st2 = TaskSearchIndex.upkeepWith(fixtureSettings, failing).unsafeRunSync()
        assertEquals(st2.embedded, 0)
        val rec = VectorStore.read().entries(s"task:$id")
        assertEquals(rec.hash, TaskSearchIndex.textHash("fixture-emb", "alpha task"), "hash 未写回")
      }
    }.unsafeRunSync()
  }

  // ── 检索 ──────────────────────────────────────────────────────────────

  test("search (embedding path): cosine top-k over fixture vectors; rerank reorders, skip keeps order") {
    withTempDataRoot { tmp =>
      IO {
        val a1 = createTask("alpha first")
        val b1 = createTask("beta second")
        val b2 = createTask("beta third")
        TaskSearchIndex.upkeepWith(fixtureSettings, fixtureEmbed).unsafeRunSync()

        // 查询 "alpha" ⇒ vecA：余弦序 = a1(1.0) → b1(0.9) → b2(0.9)（同分稳序）
        val noRerank: (String, List[TaskSearchRerank.Candidate]) => IO[Option[List[String]]] =
          (_, _) => IO.pure(None)
        val res = TaskSearchIndex
          .search("alpha", k = 3, doUpkeep = false, embed = fixtureEmbed, rerank = noRerank,
            settings = Some(fixtureSettings))
          .unsafeRunSync()
          .fold(e => fail(e), identity)
        assertEquals(res.mode, TaskSearchIndex.ModeEmbedding)
        assertEquals(res.reranked, false, "重排面 None（未配 Jev/接口不合）＝跳过，照常返回")
        assertEquals(res.hits.map(_.taskId), List(a1, b1, b2), "余弦暴扫序")
        val h = res.hits.head
        assertEquals(h.entryId, s"task:$a1")
        assertEquals(h.title, "alpha first")
        assertEquals(h.status, "open")
        assertEquals(h.score, 1.0)

        // 重排：注册器返回排列 ⇒ 命中重排（纯序换，零增删）；reranked=true
        val order = List(s"task:$b2", s"task:$a1", s"task:$b1")
        val rer: (String, List[TaskSearchRerank.Candidate]) => IO[Option[List[String]]] =
          (_, _) => IO.pure(Some(order))
        val res2 = TaskSearchIndex
          .search("alpha", k = 3, doUpkeep = false, embed = fixtureEmbed, rerank = rer,
            settings = Some(fixtureSettings))
          .unsafeRunSync()
          .fold(e => fail(e), identity)
        assertEquals(res2.reranked, true)
        assertEquals(res2.hits.map(_.taskId), List(b2, a1, b1), "返回 = 重排排列（候选池内）")
        assertEquals(res2.hits.map(_.entryId).toSet, res.hits.map(_.entryId).toSet, "重排不增删命中")
      }
    }.unsafeRunSync()
  }

  test("search (lexical fallback): unconfigured embedding still returns the same hit shape") {
    withTempDataRoot { tmp =>
      IO {
        val id = createTask("Fix the login timeout")
        // 无配置（resolveSettings = None）⇒ 词法降级
        val res = TaskSearchIndex.search("login timeout", k = 5).unsafeRunSync().fold(e => fail(e), identity)
        assertEquals(res.mode, TaskSearchIndex.ModeLexical)
        assertEquals(res.reranked, false, "词法路径不重排（原文管线序：cosine → 重排）")
        assert(res.hits.nonEmpty)
        val hit = res.hits.find(_.taskId == id)
        assert(hit.isDefined, s"词法命中应含目标条目: ${res.hits.map(_.taskId)}")
        assertEquals(hit.get.kind, "task")
        assertEquals(hit.get.title, "Fix the login timeout")
        assertEquals(hit.get.entryId, s"task:$id")
      }
    }.unsafeRunSync()
  }

  test("search: blank query is an explicit error; zero matches is an empty hit list") {
    withTempDataRoot { tmp =>
      IO {
        assert(TaskSearchIndex.search("   ").unsafeRunSync().isLeft)
        val res = TaskSearchIndex.search("zzzyyxxx").unsafeRunSync().fold(e => fail(e), identity)
        assertEquals(res.hits, Nil)
      }
    }.unsafeRunSync()
  }

end TaskSearchIndexSpec
