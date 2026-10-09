package nebflow.core.index

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.project.{TaskLedgerHistory, TaskLedgerStore}
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.NebflowLogger

import java.security.MessageDigest
import java.time.Instant

/**
 * TaskSearchIndex —— 任务检索索引（task-rag 批 #1227 / §Batch 7）。
 *
 * ==索引源（原文口径）==
 * TaskLedger 条目（task + notes 摘要）＋ delegate 会话标题/任务书：
 *   - `task:<id>`   ← `TaskLedgerStore`（tasks-v2.json）：标题＋最近 note 时间线
 *     摘要（`TaskLedgerHistory` note 主线，末 3 条各 200 字符）；
 *   - `delegate:<id>` ← `DelegateRegistry`（持久地址簿）：title（任务摘要）＋
 *     关联账本条目标题（任务书）＋ reportDetail；终态申报（reportStatus）变化
 *     ⇒ hash 变化 ⇒ 自然重嵌（delegate 终态增量索引的落地形态）。
 *
 * ==增量索引（原文口径：task 创建/完成/revive、delegate 终态，启动补扫）==
 * 索引是**内容寻址**的：条目文本 hash（含模型 id）未变 ⇒ 向量复用，绝不重复
 * embed。触发面三类：
 *   ① Task 账本写路径后（create/update/complete/close —— update 覆盖 revive：
 *     三态账本无独立 revive 动作，closed→completed 由 complete 承担、重开由
 *     update 承担，见 TaskTool 描述）+ delegate_report 终态写后 —— fire-and-
 * forget `upkeep`（TaskTool / DelegateReportTool 尾挂，绝不阻塞结果）；
 *   ② 启动补扫 = GatewayMain 装配点一次 `upkeep`（后台 fiber，永不阻塞启动）；
 *   ③ 检索前惰性 upkeep（search 自愈——任何漏网的增/改在下次检索前补齐）。
 * 🔴 接缝纪律：#1226 面（DelegateRegistry / DelegateTool / JevAllocator）零触碰
 * —— delegate 侧增量不走 spawn 钩子，靠②③惰性补齐 + delegate_report 工具面
 * 尾挂（tools 层，非 #1226 触碰面）。
 *
 * ==未配 embedding ⇒ 词法降级==
 * `upkeep` 在 embedding 面关时为零副作用快速返回；`search` 走词法打分（ASCII
 * 词元 + CJK 二元组，重叠计分），返回结构逐字段同一。
 *
 * ==可选 Jev 重排==
 * embedding 路径产出 top-k 池后，经 `TaskSearchRerank` 注册器请求重排；未注册
 * /执行体 `None`（Jev 未配/接口不合/判定失败）＝跳过，照常返回（原文「可选」
 * 的落地形态）。词法路径不重排（原文管线序：cosine top-k → 重排）。
 */
object TaskSearchIndex:

  private val logger = NebflowLogger.forName("nebflow.index.search")

  /** 检索返回的一条命中（TaskSearch 工具返回结构的数据面）。 */
  final case class SearchHit(
    entryId: String,
    kind: String,
    /** 账本条目 id（无 # 形态；delegate 命中且已绑任务时携带）。 */
    taskId: String,
    title: String,
    status: String,
    /** 执行器图标键（iconKey = executor id；空 = 无）。 */
    executor: String,
    /** 续聊地址 `delegate:<id>`（空 = 无）。 */
    address: String,
    snippet: String,
    score: Double
  )

  final case class SearchResult(hits: List[SearchHit], mode: String, reranked: Boolean, totalIndexed: Int)

  final case class UpkeepStats(scanned: Int, embedded: Int, removed: Int, mode: String)

  val ModeEmbedding: String = "embedding"
  val ModeLexical: String = "lexical"

  // ── 条目装配（同步、现读；账本与地址簿都是小文件面）──────────────────

  /** 索引条目中间形态（text = 被嵌入的源文本；snippet = 返回面摘录）。 */
  final case class IndexEntry(
    entryId: String,
    kind: String,
    taskId: String,
    title: String,
    status: String,
    executor: String,
    address: String,
    snippet: String,
    text: String
  )

  /** Notes 时间线摘要上限：末 3 条、各 200 字符（索引文本预算——千级条目可控）。 */
  val MaxNotes: Int = 3
  val MaxNoteChars: Int = 200
  val MaxSnippetChars: Int = 160

  /** 组装全量索引条目（账本 + 地址簿；每次现读，零缓存——两盘面本身即热读形态）。 */
  def collectEntriesSync(): List[IndexEntry] =
    val ledger = TaskLedgerStore.open()
    val history = TaskLedgerHistory.open()
    val entries = ledger.entriesSync()
    val delegates = DelegateRegistry.list()
    // 反查表：taskId → 最新 delegate（executor 图标与续聊地址的任务侧投影）。
    val delegateByTask: Map[String, DelegateRegistry.Record] =
      delegates
        .filter(_.taskId.exists(_.trim.nonEmpty))
        .groupBy(_.taskId.get)
        .map { case (t, rs) => t -> rs.maxBy(_.createdAt) }

    val taskEntries = entries.map { e =>
      val notes = history.readFor(e.id, MaxNotes, TaskLedgerHistory.isNoteEvent)
      val noteText = notes.events.flatMap(_.text.map(_.take(MaxNoteChars))).mkString("\n")
      val bound = delegateByTask.get(e.id)
      val text = (List(e.title) ++ List(noteText).filter(_.nonEmpty)).mkString("\n")
      IndexEntry(
        entryId = s"${VectorStore.TaskKind}:${e.id}",
        kind = VectorStore.TaskKind,
        taskId = e.id,
        title = e.title,
        status = e.status,
        executor = bound.map(_.executor).getOrElse(""),
        address = bound.map(d => s"delegate:${d.id}").getOrElse(""),
        snippet = e.title.take(MaxSnippetChars),
        text = text
      )
    }

    val delegateEntries = delegates.map { d =>
      val bookTitle = d.taskId.flatMap(t => ledger.findSync(t).map(_.title)).getOrElse("")
      val reportDetail = d.reportDetail.map(_.take(MaxNoteChars)).getOrElse("")
      val text =
        (List(d.title) ++ List(bookTitle).filter(_.nonEmpty) ++ List(reportDetail).filter(_.nonEmpty)).mkString("\n")
      IndexEntry(
        entryId = s"${VectorStore.DelegateKind}:${d.id}",
        kind = VectorStore.DelegateKind,
        taskId = d.taskId.getOrElse(""),
        title = d.title,
        status = d.reportStatus.getOrElse(""),
        executor = d.executor,
        address = s"delegate:${d.id}",
        snippet = d.title.take(MaxSnippetChars),
        text = text
      )
    }

    taskEntries ++ delegateEntries
  end collectEntriesSync

  // ── 内容指纹与向量数学（纯函数，spec 可驱动）──────────────────────────

  /** 内容指纹 = sha256(model + "\n" + text)；换模型 ⇒ 全量指纹变化 ⇒ 全量重嵌。 */
  def textHash(model: String, text: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s"$model\n$text".getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** 余弦相似度（零向量/维度不合 ⇒ 0）。暴力扫的核，纯函数。 */
  def cosine(a: List[Float], b: List[Float]): Double =
    if a.isEmpty || b.isEmpty || a.length != b.length then 0.0
    else
      var dot = 0.0
      var na = 0.0
      var nb = 0.0
      val it = a.iterator.zip(b.iterator)
      while it.hasNext do
        val (x, y) = it.next()
        dot += x.toDouble * y.toDouble
        na += x.toDouble * x.toDouble
        nb += y.toDouble * y.toDouble
      if na == 0.0 || nb == 0.0 then 0.0 else dot / (math.sqrt(na) * math.sqrt(nb))

  /** 词元化：ASCII 词（字母数字串）＋ CJK 连串的单元/二元组（词法降级打分用）。 */
  def tokenize(s: String): List[String] =
    asciiTokens(s.toLowerCase) ++ cjkTokens(s)

  private def asciiTokens(lower: String): List[String] =
    lower.split("[^a-z0-9_]+").filter(_.nonEmpty).toList

  private def isCjk(c: Char): Boolean =
    (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf)

  private def cjkTokens(s: String): List[String] =
    val runs = s.toCharArray.iterator.filter(isCjk).mkString
    if runs.isEmpty then Nil
    else
      val unigrams = runs.map(_.toString).toList
      val bigrams = runs.sliding(2).map(_.mkString).toList
      (unigrams ++ bigrams).distinct

  /** 词法打分：查询词元在条目文本中的加权重叠（命中词元占比 × 出现饱和度）。 */
  def lexicalScore(queryTokens: List[String], text: String): Double =
    if queryTokens.isEmpty then 0.0
    else
      val tokens = tokenize(text).toSet
      val hits = queryTokens.distinct.count(tokens.contains)
      hits.toDouble / queryTokens.distinct.size.toDouble

  // ── 增量 upkeep ───────────────────────────────────────────────────────

  /** in-flight 合流闸：写路径 fire-and-forget 触发并发时只跑一份。 */
  private val upkeepInFlight = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** 生产形态：热读配置 → 有配置才扫描增量。配置关 = 快速 no-op（词法路径不依赖向量）。 */
  def upkeep(): IO[UpkeepStats] =
    EmbeddingService.resolveSettings() match
      case None => IO.pure(UpkeepStats(0, 0, 0, "off"))
      case Some(st) => upkeepWith(st, texts => EmbeddingService.embed(st, texts))

  /** Fire-and-forget 形态（写路径尾挂）：在飞合流，绝不向调用方上抛。 */
  def upkeepFireAndForget(): IO[Unit] =
    IO(upkeepInFlight.compareAndSet(false, true)).flatMap { claimed =>
      if !claimed then IO.unit
      else
        upkeep().attempt.guarantee(IO(upkeepInFlight.set(false))).flatMap {
          case Right(_) => IO.unit
          case Left(e) =>
            IO(logger.warnSync(s"task index upkeep failed (${e.getClass.getSimpleName}) — ignored")).void
        }
    }

  /**
   * 增量维护（spec 可注入 embed 函数）：扫描条目 → hash 比对 → 只嵌变化者 →
   * 落盘 → 清陈旧。扫描/嵌入/落盘任何一环失败都不清空既有向量（写是原子的，
   * 读容忍——最坏 = 向量略陈旧，词法/余弦照常可用）。
   */
  def upkeepWith(
    settings: EmbeddingService.EmbeddingSettings,
    embed: List[String] => IO[Option[List[List[Float]]]]
  ): IO[UpkeepStats] =
    IO.blocking(collectEntriesSync()).flatMap { entries =>
      val store = VectorStore.read()
      val byId = entries.map(e => e.entryId -> e).toMap
      val changed = entries.filter { e =>
        store.entries.get(e.entryId) match
          case None => true
          case Some(rec) => rec.hash != textHash(settings.model, e.text)
      }
      val staleIds = store.entries.keys.filterNot(byId.contains).toList
      if changed.isEmpty && staleIds.isEmpty then IO.pure(UpkeepStats(entries.size, 0, 0, ModeEmbedding))
      else
        changed
          .sliding(EmbeddingService.MaxBatch, EmbeddingService.MaxBatch)
          .toList
          .traverse { batch =>
            embed(batch.map(_.text)).flatMap {
              case None =>
                // 嵌入失败：本批跳过（保留旧向量——hash 未写回，下轮 upkeep 重试）。
                IO(logger.warnSync(s"task index upkeep: ${batch.size} entries skipped (embed failed)"))
                  .as(Nil: List[(String, VectorStore.VectorRecord)])
              case Some(vectors) =>
                val now = Instant.now().toString
                val records = batch
                  .zip(vectors)
                  .map { case (e, vec) =>
                    e.entryId -> VectorStore.VectorRecord(
                      vector = vec,
                      dim = vec.size,
                      model = settings.model,
                      hash = textHash(settings.model, e.text),
                      kind = e.kind,
                      title = e.title,
                      status = e.status,
                      executor = e.executor,
                      address = e.address,
                      taskId = e.taskId,
                      snippet = e.snippet,
                      updatedAt = now
                    )
                  }
                  .toMap
                IO.pure(records.toList)
            }
          }
          .flatMap { recordLists =>
            val records = recordLists.flatten.toMap
            val putRes = if records.isEmpty then Right(VectorStore.read()) else VectorStore.putAll(records)
            val rmRes = if staleIds.isEmpty then Right(VectorStore.read()) else VectorStore.removeAll(staleIds)
            (putRes, rmRes) match
              case (Right(_), Right(_)) =>
                IO.pure(UpkeepStats(entries.size, records.size, staleIds.size, ModeEmbedding))
              case (Left(a), _) =>
                IO(logger.warnSync(s"task index upkeep write failed: $a"))
                  .as(UpkeepStats(entries.size, 0, 0, ModeEmbedding))
              case (_, Left(b)) =>
                IO(logger.warnSync(s"task index upkeep prune failed: $b"))
                  .as(UpkeepStats(entries.size, records.size, 0, ModeEmbedding))
          }
      end if
    }

  // ── 检索 ──────────────────────────────────────────────────────────────

  /** Top-k 池上限（重排候选池；返回仍截 k）。 */
  private val PoolFactor: Int = 3

  /**
   * 检索主入口：惰性 upkeep → embedding/词法 → （embedding 路径）可选 Jev 重排
   * → top-k。query 空白 = 显式错误。绝不抛：一切降级都落在返回值里。
   */
  def search(
    query: String,
    k: Int = 8,
    doUpkeep: Boolean = true,
    embed: List[String] => IO[Option[List[List[Float]]]] = fallbackEmbed,
    rerank: (String, List[TaskSearchRerank.Candidate]) => IO[Option[List[String]]] = TaskSearchRerank.rerank,
    settings: Option[EmbeddingService.EmbeddingSettings] = EmbeddingService.resolveSettings()
  ): IO[Either[String, SearchResult]] =
    val q = query.trim
    val cappedK = math.max(1, math.min(k, 25))
    if q.isEmpty then IO.pure(Left("TaskSearch: `query` must be non-empty — say WHAT to look for. (TASKSEARCH_QUERY)"))
    else
      val upkeepIo = if doUpkeep then upkeep() else IO.pure(UpkeepStats(0, 0, 0, "skip"))
      upkeepIo.flatMap { _ =>
        val entries = collectEntriesSync()
        val total = entries.size
        settings match
          case Some(settings) =>
            embed(List(q)).flatMap {
              case Some(qvec :: _) =>
                val store = VectorStore.read()
                val scored = entries
                  .flatMap { e =>
                    store.entries.get(e.entryId).map { rec =>
                      e -> cosine(qvec, rec.vector)
                    }
                  }
                  .filter(_._2 > 0.0)
                  .sortBy(-_._2)
                val pool = scored.take(math.max(cappedK, cappedK * PoolFactor))
                if pool.isEmpty then IO.pure(Right(SearchResult(Nil, ModeEmbedding, reranked = false, total)))
                else
                  val candidates = pool.map { case (e, s) => TaskSearchRerank.Candidate(e.entryId, e.text.take(2000)) }
                  rerank(q, candidates).map {
                    case Some(order) if order.nonEmpty =>
                      val rank = order.zipWithIndex.map { case (id, i) => id -> i }.toMap
                      val ordered = pool.sortBy { case (e, s) => rank.getOrElse(e.entryId, Int.MaxValue) }
                      Right(SearchResult(take(cappedK)(ordered.map(hitOf)), ModeEmbedding, reranked = true, total))
                    case _ =>
                      Right(SearchResult(take(cappedK)(pool.map(hitOf)), ModeEmbedding, reranked = false, total))
                  }
              case _ =>
                IO.pure(lexicalResult(q, cappedK, entries, total))
            }
          case None =>
            IO.pure(lexicalResult(q, cappedK, entries, total))
        end match
      }

    end if

  end search

  /** 生产 embed 注入点（热读配置；面关 = None → 词法）。 */
  private def fallbackEmbed(texts: List[String]): IO[Option[List[List[Float]]]] =
    EmbeddingService.resolveSettings() match
      case None => IO.pure(None)
      case Some(st) => EmbeddingService.embed(st, texts)

  private def take[A](n: Int)(xs: List[A]): List[A] = xs.take(n)

  private def hitOf(pair: (IndexEntry, Double)): SearchHit =
    val (e, s) = pair
    SearchHit(
      entryId = e.entryId,
      kind = e.kind,
      taskId = e.taskId,
      title = e.title,
      status = e.status,
      executor = e.executor,
      address = e.address,
      snippet = e.snippet,
      score = math.round(s * 1000.0) / 1000.0
    )

  /** 词法降级结果（打分 = 查询词元重叠；零分命中丢弃）。 */
  private def lexicalResult(q: String, k: Int, entries: List[IndexEntry], total: Int): Either[String, SearchResult] =
    val qTokens = tokenize(q)
    val scored = entries
      .map(e => e -> lexicalScore(qTokens, e.title + "\n" + e.text))
      .filter(_._2 > 0.0)
      .sortBy(-_._2)
      .take(k)
    Right(SearchResult(scored.map(hitOf), ModeLexical, reranked = false, total))

end TaskSearchIndex
