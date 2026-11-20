package nebflow.core.search

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nebflow.shared.NebflowLogger

import java.sql.{Connection, DriverManager, PreparedStatement}

/**
 * SearchIndexService —— 数据层检索骨架的索引服务单例（设计稿 §4.1-4.4；
 * datalayer-spec 批 2026-10-30，C1+C2 的服务端半边）。
 *
 * == 形态 ==
 *   - 每 face 一个 SQLite 文件：`<dataRoot>/search-index/<face>.db`（设计稿 §4.2
 *     物理形态「单文件多表」起步；目录化分片留作片数上万后的备选，接口不变）。
 *     目录先例对齐 `~/.stand/memory-db/`（stand-recall）。
 *   - 文档主键 = (sid, ord)（会话 id + 行号）；类型片 = doc.kind 投影列
 *     （message/tool/media/file/link——与前端五 tab 同口径），时间片 = ts 投影列
 *     + month_key(sid) 冗余列（月桶封口不可变、当月桶开口增量——设计稿 §4.2）。
 *   - CJK：bigram 影子列 `idx`（[[CjkBigram]] 同变换查询——§4.6 R2）。
 *
 * == 通用纪律落地（§4.1）==
 *   1. 源目录零写入：adapter.extractSession 只读；本服务只写 `<face>.db`。
 *   2. 增量靠写路径钩子：[[markSessionChanged]] 由 SessionStore 既有
 *      sessionChangedHook 单缝驱动（不新增写面）；查询前惰性对账兜漏
 *      （[[reconcileBeforeQuery]]：mtime 落后于索引水位的会话顺手重建——
 *      §4.3「兜底 = 惰性对账」）。
 *   3. 查询必带分片谓词：[[query]] 强制 kinds/sid/since/until 至少其一
 *      （无谓词 ⇒ Left；current 档 scope 谓词天然在场）。
 *
 * == 凭证（§4.4，C6 服务端半边）==
 * 每会话一个单调 `version`（upsert 时 bump）；查询 ETag =
 * `hash(face + 规范化参数 + Σ命中会话version)`——结果集内任何会话变更 ⇒
 * ETag 必变 ⇒ 304 协商精确失效（HttpCache.conditionalOk 复用）。不做内容缓存。
 *
 * == 正确性永不依赖索引新鲜度（§4.6 R1）==
 * 查询失败/索引缺失 ⇒ [[query]] 返回 Left，调用方降级旧全扫路径；索引
 * 陈旧由惰性对账收敛，最坏 = 慢，不退化为错。
 */
object SearchIndexService:

  private val logger = NebflowLogger.forName("nebflow.search.index")

  // ── 数据面 ──────────────────────────────────────────────────────────────

  /**
   * 一条检索文档。kind ∈ message|tool|media|file|link（类型片）；ts = epoch ms
   * （时间片；0 = 无时间锚——工具/代理行取回复锚由 adapter 负责）；ord = 行号
   * （(sid, ord) 定位，命中跳转/浮窗直接兼容）；text = 可检索文本（**原始文本**，
   * bigram 变换在索引层做——口径单点）；extra 按 kind 携带投影字段
   * （JSON 字符串；media/file/link = popMediaIndex 条目形状的超集，
   * message/tool = {agent,tool}）。
   */
  final case class IndexDoc(
    sid: String,
    ord: Int,
    kind: String,
    ts: Long,
    text: String,
    extra: String = "",
    /**
     * 月桶覆盖（检索全覆盖批 2026-11-07）：None = 用会话桶（历史行为）；Some(v) =
     *  逐文档写死（`Some("")` = 空桶）。压缩前归档段（来源不在回放窗口内、时间跨
     *  多月）必须写空桶，否则会被查询侧的时间窗月桶粗筛按会话当前桶误杀
     *  （见 runQuery 的 monthPreFilter 注：'' 被视作「未知/旧桶行，保留」）。
     */
    monthKey: Option[String] = None
  )

  /** 一条命中（查询返回面）。 */
  final case class Hit(
    sid: String,
    ord: Int,
    kind: String,
    ts: Long,
    snippet: String,
    extra: String,
    stableId: String = "",
    occurrenceCount: Int = 1
  )

  final case class Cursor(ts: Long, stableId: String, generation: String)

  /** 查询参数。谓词纪律：kinds/sid/since/until 至少一个。 */
  final case class Query(
    text: String,
    kinds: List[String] = Nil, // 非空 = 类型片谓词
    sid: Option[String] = None, // current 档 = 会话键谓词
    since: Option[Long] = None,
    until: Option[Long] = None,
    limit: Int = 50,
    offset: Int = 0,
    cursor: Option[Cursor] = None
  )

  final case class QueryResult(
    hits: List[Hit],
    total: Int,
    etag: String,
    degraded: Boolean = false,
    indexGeneration: String = "",
    nextCursor: Option[Cursor] = None,
    generationChanged: Boolean = false
  )

  // ── 常量 ────────────────────────────────────────────────────────────────

  /**
   * 索引文本行级 cap（未决项①裁定：cap 2000 字/行档——设计稿 §7 推荐缺省；
   *  超长工具输出只搜前 2000 字；sys.props 可切全文本档，不回写源数据）。
   */
  val IndexTextCapChars: Int =
    sys.props.getOrElse("nebflow.search.index.capChars", "2000").toInt

  /** snippet 投影长度（返回面摘录）。 */
  val SnippetChars: Int = 160

  /**
   * 单次查询的候选行扫描上限（SQL 有界面；去重与游标分页在其后统一做）。
   *  为什么需要：分页与去重都在**后置**统一做（stableId 归并 + (ts,stableId) 定序），
   *  SQL 侧若也减 offset 会双重扣减而跳行；但完全不设上限则大库查询会把整表拉进内存。
   *  2000 远高于单页可见量（limit ≤ 200），也覆盖「同一条消息多副本」的膨胀倍数。
   */
  val RowScanCap: Int = 2000

  /** 全部已知类型片。 */
  val AllKinds: Set[String] = Set("message", "tool", "media", "file", "link")

  private val SchemaVersion = 1

  // ── 状态（单例；进程内单写者与网关单进程匹配——§4.2）──────────────────────

  private final case class FaceState(
    conn: Connection,
    adapter: SearchIndexAdapter
  )

  private val faces = new java.util.concurrent.ConcurrentHashMap[String, FaceState]()
  @volatile private var disabledReason: Option[String] = None
  private val workPermit = new java.util.concurrent.Semaphore(1)

  /** 取 face 状态；未注册 ⇒ None。 */
  private def faceOf(face: String): Option[FaceState] =
    Option(faces.get(face))

  // ── 测试缝 ────────────────────────────────────────────────────────────────

  /** 当前行数（读数/测试断言用）。 */
  private[nebflow] def docCount(face: String): Int =
    faceOf(face) match
      case None => -1
      case Some(fs) =>
        val st = fs.conn.createStatement()
        try
          val rs = st.executeQuery("SELECT COUNT(*) FROM docs")
          if rs.next() then rs.getInt(1) else -1
        finally st.close()

  /** 测试用：FTS 影子行数。 */
  private[nebflow] def ftsCount(face: String): Int =
    faceOf(face) match
      case None => -1
      case Some(fs) =>
        val st = fs.conn.createStatement()
        try
          val rs = st.executeQuery("SELECT COUNT(*) FROM docs_fts")
          if rs.next() then rs.getInt(1) else -1
        finally st.close()

  private[nebflow] def unsafeExecuteForTests(face: String, sql: String): Unit =
    val conn = faceOf(face).get.conn
    val st = conn.createStatement()
    try
      st.execute(sql)
      conn.commit()
    finally st.close()

  /** 测试缝：直接灌文档（绕过 adapter——纯结构测试用）。 */
  private[nebflow] def unsafeInsertDocs(face: String, docs: List[IndexDoc]): Unit =
    faceOf(face).foreach { fs =>
      // 整批一次 replace：replaceSessionDocs 先 DELETE 同 sid 旧行——
      // 逐 doc 调用会把同 sid 的先行 doc 删掉（测试隔离面，非生产路径）。
      docs.groupBy(_.sid).foreach { case (sid, ds) =>
        replaceSessionDocs(fs, sid, "test", 0L, Some(ds.sortBy(_.ord)))
      }
    }

  /** 重置全部 face（测试隔离——只清测试注册的内存库，生产注册幂等不受影响）。 */
  private[nebflow] def unsafeResetForTests(): Unit =
    faces.clear()
    disabledReason = None

  /** 测试缝：内存库（jdbc:sqlite::memory:），不落盘；schema 与生产同一份。 */
  private[nebflow] def unsafeRegisterInMemory(adapter: SearchIndexAdapter): Unit =
    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    conn.setAutoCommit(false)
    initSchema(conn)
    faces.put(adapter.face, FaceState(conn, adapter))

  // ── 注册 / 建库 ─────────────────────────────────────────────────────────

  /**
   * 注册 adapter 并冷建/对账。幂等：重复注册同 face = 忽略。任何失败 ⇒
   *  该 face 整体禁用（disabledReason），查询一律 Left——调用方降级旧路径，
   *  正确性不受影响（§4.6 R1）。
   */
  def register(adapter: SearchIndexAdapter, dataRoot: os.Path): IO[Unit] =
    if faces.containsKey(adapter.face) then IO.unit
    else
      IO.blocking {
        try
          val dir = dataRoot / "search-index"
          os.makeDir.all(dir)
          val dbPath = dir / s"${adapter.face}.db"
          val conn = DriverManager.getConnection(s"jdbc:sqlite:${dbPath.toString}")
          conn.setAutoCommit(false)
          initSchema(conn)
          faces.put(adapter.face, FaceState(conn, adapter))
          logger.infoSync(s"search-index: face '${adapter.face}' opened at $dbPath (cap=$IndexTextCapChars chars/row)")
        catch
          case e: Exception =>
            disabledReason = Some(s"${adapter.face}: ${e.getClass.getSimpleName}: ${e.getMessage}")
            logger.warnSync(s"search-index DISABLED for face '${adapter.face}': ${e.getMessage}")
      }

  private def initSchema(conn: Connection): Unit =
    val st = conn.createStatement()
    try
      st.execute("""CREATE TABLE IF NOT EXISTS docs(
          |  sid TEXT NOT NULL,
          |  ord INTEGER NOT NULL,
          |  kind TEXT NOT NULL,
          |  ts INTEGER NOT NULL DEFAULT 0,
          |  month_key TEXT NOT NULL DEFAULT '',
          |  text TEXT NOT NULL,
          |  idx TEXT NOT NULL,
          |  extra TEXT NOT NULL DEFAULT '',
          |  rowid INTEGER PRIMARY KEY AUTOINCREMENT
          |)""".stripMargin)
      st.execute("CREATE INDEX IF NOT EXISTS idx_docs_kind_ts ON docs(kind, ts)")
      st.execute("CREATE INDEX IF NOT EXISTS idx_docs_sid ON docs(sid)")
      st.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS docs_fts USING fts5(
          |  idx, content='docs', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2'
          |)""".stripMargin)
      st.execute("""CREATE TABLE IF NOT EXISTS sessions_meta(
          |  sid TEXT PRIMARY KEY,
          |  fingerprint TEXT NOT NULL,
          |  mtime INTEGER NOT NULL,
          |  version INTEGER NOT NULL DEFAULT 1
          |)""".stripMargin)
      st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
      st.execute(s"INSERT OR REPLACE INTO meta VALUES('schema_version','$SchemaVersion')")
      conn.commit()
    finally st.close()
    end try
  end initSchema

  // ── 写路径钩子（§4.3：既有锚点追加一步，零新增写放大风险点）─────────────────

  /**
   * 会话变更钩子（SessionStore.sessionChangedHook 单缝驱动——既有机制，不
   * 新增写面）。flush 本来就是 500ms 批量去抖的，索引更新搭同一班车；钩子
   * fire-and-forget，绝不向调用方上抛（与 TaskSearchIndex.upkeepFireAndForget
   * 同款纪律）。索引落后由查询前惰性对账收敛。
   */
  def markSessionChanged(sid: String): Unit =
    val it = faces.values().iterator()
    while it.hasNext do
      val fs = it.next()
      syncSessionIO(fs, sid, forceFull = true).attempt
        .flatMap {
          case Right(_) => IO.unit
          case Left(e) =>
            IO(
              logger.warnSync(
                s"search-index: incremental sync of $sid failed (${e.getClass.getSimpleName}) — lazy reconcile will catch up"
              )
            )
        }
        .go()

  end markSessionChanged

  /** 给 IO 一个 fire-and-forget 落点（调用点保持干净；runtime 经 global 隐式）。 */
  private implicit class IoFireForget[A](io: IO[A]):
    def go(): Unit = io.unsafeRunAndForget()

  // ── 增量 / 对账 ─────────────────────────────────────────────────────────

  /**
   * 单会话 upsert：旧行 DELETE + 新行重插（会话级语义——§4.3；同会话行号
   *  稳定，整会话替换最简单且幂等）。forceFull=false 且指纹+mtime 未变 ⇒
   *  零操作。
   */
  private def syncSessionIO(fs: FaceState, sid: String, forceFull: Boolean): IO[Unit] =
    IO.blocking {
      workPermit.acquire()
      try
        val fp = fs.adapter.sessionFingerprint(sid)
        val mtime = adapterMtime(fs.adapter, sid)
        val upToDate =
          !forceFull && fp.isDefined && mtime.isDefined && {
            val ps = fs.conn.prepareStatement("SELECT fingerprint, mtime FROM sessions_meta WHERE sid=?")
            try
              ps.setString(1, sid)
              val rs = ps.executeQuery()
              if rs.next() then rs.getString(1) == fp.get && rs.getLong(2) >= mtime.get
              else false
            finally ps.close()
          }
        if !upToDate then
          val docsOpt = fs.adapter.extractSession(sid, forceFull)
          replaceSessionDocs(fs, sid, fp.getOrElse(""), mtime.getOrElse(0L), docsOpt)
      finally workPermit.release()
      end try
    }

  /** adapter 会话 mtime（对账水位；只用于「索引落后于真源」判向）。 */
  private def adapterMtime(a: SearchIndexAdapter, sid: String): Option[Long] =
    a.sessionMtimeOpt(sid)

  /** 整会话替换（DELETE + INSERT + FTS 影子 + meta bump，一个事务）。 */
  private def replaceSessionDocs(
    fs: FaceState,
    sid: String,
    fingerprint: String,
    mtime: Long,
    docsOpt: Option[List[IndexDoc]]
  ): Unit =
    try replaceSessionDocsTransaction(fs, sid, fingerprint, mtime, docsOpt)
    catch
      case scala.util.control.NonFatal(e) =>
        try fs.conn.rollback()
        catch case scala.util.control.NonFatal(rollbackError) => e.addSuppressed(rollbackError)
        throw e

  private def replaceSessionDocsTransaction(
    fs: FaceState,
    sid: String,
    fingerprint: String,
    mtime: Long,
    docsOpt: Option[List[IndexDoc]]
  ): Unit =
    val conn = fs.conn
    // 1) 删旧（内容表 + FTS 影子行）
    val del = conn.prepareStatement("DELETE FROM docs WHERE sid=?")
    try
      del.setString(1, sid)
      del.executeUpdate()
    finally del.close()
    docsOpt.foreach { docs =>
      if docs.nonEmpty then
        val mk = monthKeyOf(fs.adapter, sid)
        // 2) 插内容行
        val ins =
          conn.prepareStatement("INSERT INTO docs(sid,ord,kind,ts,month_key,text,idx,extra) VALUES(?,?,?,?,?,?,?,?)")
        try
          docs.foreach { d =>
            ins.setString(1, d.sid)
            ins.setInt(2, d.ord)
            ins.setString(3, d.kind)
            ins.setLong(4, d.ts)
            // 月桶：逐文档覆盖优先（归档段恒空桶），否则用会话桶（历史行为不变）。
            ins.setString(5, d.monthKey.getOrElse(mk))
            ins.setString(6, d.text)
            ins.setString(7, "") // idx 列废弃——FTS 影子表承载 bigram
            ins.setString(8, d.extra)
            ins.addBatch()
          }
          ins.executeBatch()
        finally ins.close()
        end try
        // 2b) 刚插入的行 = 该 sid 全集（DELETE 已清旧）；rowid 升序与插入序
        //     一一对应（单连接单写者，无并发插入）。
        val ridBuf = scala.collection.mutable.ListBuffer.empty[Long]
        val sel = conn.createStatement()
        try
          val rs = sel.executeQuery(s"SELECT rowid FROM docs WHERE sid='${sid.replace("'", "''")}' ORDER BY rowid")
          while rs.next() do ridBuf += rs.getLong(1)
        finally sel.close()
        // 3) FTS 影子行——独立语句逐行 INSERT（**不 batch**）：docs_fts 是
        //    external-content 表，对内容表的每行 INSERT 触发器同步维护影子行，
        //    同批多次写触发器互相覆盖（实测 batch 只留最后一行）；逐行 INSERT
        //    让触发器逐行生效，随后再以显式 (rowid, idx) 写入做 bigram 变换
        //    （触发器已写入的原始行会被同 rowid 覆盖——external-content 表
        //    的 INSERT OR REPLACE 语义）。
        docs.zip(ridBuf).foreach { case (d, rid) =>
          val fIns = conn.prepareStatement("INSERT OR REPLACE INTO docs_fts(rowid, idx) VALUES(?, ?)")
          try
            fIns.setLong(1, rid)
            fIns.setString(2, CjkBigram.transform(d.text))
            fIns.executeUpdate()
          finally fIns.close()
        }
    }
    // 4) bump version（凭证腿：Σversion 进 ETag——§4.4）
    val up = conn.prepareStatement("""INSERT INTO sessions_meta(sid,fingerprint,mtime,version) VALUES(?,?,?,1)
        |ON CONFLICT(sid) DO UPDATE SET fingerprint=excluded.fingerprint,
        |  mtime=excluded.mtime, version=sessions_meta.version+1""".stripMargin)
    try
      up.setString(1, sid)
      up.setString(2, fingerprint)
      up.setLong(3, mtime)
      up.executeUpdate()
    finally up.close()
    bumpGeneration(conn)
    conn.commit()

  end replaceSessionDocsTransaction

  /** Canonical identity for both live results and future materialized hits. */
  private[search] def stableId(sid: String, kind: String, ts: Long, text: String, extra: String): String =
    val role =
      io.circe.parser.parse(extra).toOption.flatMap(_.hcursor.downField("role").as[String].toOption).getOrElse("")
    val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    val semanticExtra = io.circe.parser
      .parse(extra)
      .toOption
      .map { j =>
        List("type", "source", "name", "ext", "ref")
          .map(k => j.hcursor.get[String](k).getOrElse(""))
          .map(s => s"${s.length}:$s")
          .mkString
      }
      .getOrElse("")
    val content = if kind == "message" then normalized else normalized + "\u0000" + semanticExtra
    val material = List(sid, role, ts.toString, kind, content).map(s => s"${s.length}:$s").mkString
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(material.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  end stableId

  private def bumpGeneration(conn: Connection): Unit =
    val st = conn.createStatement()
    try
      st.executeUpdate("INSERT OR REPLACE INTO meta(key,value) VALUES('index_generation', lower(hex(randomblob(16))))")
    finally st.close()

  private def generation(conn: Connection): String =
    val st = conn.createStatement()
    try
      val rs = st.executeQuery("SELECT value FROM meta WHERE key='index_generation'")
      if rs.next() then rs.getString(1) else "initial"
    finally st.close()

  /** 月时间片键（会话桶）：当月 = "current"，历史 = yyyymm（§4.2）。 */
  private def monthKeyOf(a: SearchIndexAdapter, sid: String): String =
    a.monthKey(sid)

  // ── 查询（§4.1 ③：分片预过滤 → FTS 匹配 → 排序/摘要 → 分页）───────────────

  /**
   * 分面查询主入口。绝不抛：一切失败落在 Left（调用方降级旧路径）。
   * 谓词纪律：kinds/sid/since/until 至少一个 ⇒ 否则 Left（§4.1 纪律 3）。
   */
  def query(face: String, q: Query): IO[Either[String, QueryResult]] =
    faceOf(face) match
      case None =>
        IO.pure(Left(disabledReason.filter(_.startsWith(face)).getOrElse(s"face '$face' not registered")))
      case Some(fs) =>
        (reconcileBeforeQuery(fs, q.sid) *> IO.blocking {
          workPermit.acquire()
          try runQuery(fs, q)
          finally workPermit.release()
        }).attempt.map {
          case Right(r) => r
          case Left(e) =>
            logger.warnSync(s"search-index query failed (${e.getClass.getSimpleName}: ${e.getMessage})")
            Left(s"index query failed: ${e.getClass.getSimpleName}")
        }

  /** Reconcile enumerated sources before querying; failures trigger the caller's fallback. */
  private def reconcileBeforeQuery(fs: FaceState, sid: Option[String]): IO[Unit] =
    sid match
      case Some(s) => syncSessionIO(fs, s, forceFull = false)
      case None =>
        IO.blocking(fs.adapter.sessionIdsForReconcile).flatMap {
          case Some(sids) => sids.foldLeft(IO.unit)((io, s) => io *> syncSessionIO(fs, s, forceFull = false))
          case None => IO.unit
        }

  /** Do not expose an incomplete or unimplemented projection as an active search result. */
  private def projectionReady(conn: Connection): Boolean =
    val st = conn.createStatement()
    try
      val exists = st.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='search_index_meta'")
      val present = exists.next()
      exists.close()
      if !present then false
      else
        val rs = st.executeQuery("SELECT key,value FROM search_index_meta WHERE key IN ('phase','active_generation')")
        var phase = ""
        var active = ""
        while rs.next() do
          if rs.getString(1) == "phase" then phase = rs.getString(2)
          else active = rs.getString(2)
        phase == "done" && active.nonEmpty && active != "legacy"
    finally st.close()

  end projectionReady

  private def runQuery(fs: FaceState, q: Query): Either[String, QueryResult] =
    if projectionReady(fs.conn) then Left("inverted search projection is not yet implemented")
    else runLegacyQuery(fs, q)

  private def runLegacyQuery(fs: FaceState, q: Query): Either[String, QueryResult] =
    val hasPredicate = q.kinds.nonEmpty || q.sid.isDefined || q.since.isDefined || q.until.isDefined
    if q.text.trim.isEmpty then Left("query text must be non-empty")
    else if !hasPredicate then Left("query must carry a shard predicate (kinds/sid/since/until)")
    else
      val (andExpr, orExpr, likePattern) = CjkBigram.ftsQuery(q.text.trim)
      if andExpr.isEmpty && likePattern.isEmpty then Left("query has no indexable terms")
      else
        // 谓词预过滤 WHERE（分片预过滤——类型片/时间片/会话键；§4.1 ③）
        val where = new StringBuilder(" WHERE 1=1")
        val params = scala.collection.mutable.ListBuffer.empty[Any]
        q.sid.foreach { s =>
          where.append(" AND d.sid=?"); params += s
        }
        if q.kinds.nonEmpty then
          where.append(" AND d.kind IN (" + q.kinds.map(_ => "?").mkString(",") + ")")
          params ++= q.kinds
        q.since.foreach { v =>
          where.append(" AND d.ts>=?"); params += v
        }
        q.until.foreach { v =>
          where.append(" AND d.ts<=?"); params += v
        }
        // 检索面会话范围（会话搜索范围收窄批 2026-11-01，作者令「只搜主会话」）：
        // adapter 声明的排除前缀落进 WHERE —— 本批之前入库的后台 agent 会话
        // 文档在任何查询形状（current 档 / all 档 / 各类型片 / 分页）下都不出
        // 结果，且 total 用同一谓词计数（不虚高）；残留随会话重建清零。
        fs.adapter.excludedSidPrefixes.foreach { prefix =>
          where.append(" AND d.sid NOT LIKE ? ESCAPE '\\'")
          params += (escapeLike(prefix) + "%")
        }

        /** 谓词参数绑到 [start+1 .. start+params.size]（MATCH 参数占首位时 start=1）。 */
        def bindBase(ps: PreparedStatement, start: Int): Unit =
          params.zipWithIndex.foreach { case (p, i) =>
            p match
              case s: String => ps.setString(start + i + 1, s)
              case l: Long => ps.setLong(start + i + 1, l)
              case n: Int => ps.setLong(start + i + 1, n.toLong)
              case other => ps.setString(start + i + 1, other.toString)
          }

        def fetch(sql: String, bindExtra: PreparedStatement => Unit): List[Hit] =
          val ps = fs.conn.prepareStatement(sql)
          try
            bindExtra(ps)
            val rs = ps.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[Hit]
            while rs.next() do
              val sid = rs.getString(1)
              val kind = rs.getString(3)
              val ts = rs.getLong(4)
              val text = rs.getString(5)
              val extra = rs.getString(6)
              val id = stableId(sid, kind, ts, text, extra)
              buf += Hit(sid, rs.getInt(2), kind, ts, text.take(SnippetChars), extra, id)
            buf.toList
          finally ps.close()
        end fetch

        def execMatch(expr: String): List[Hit] =
          // FTS5 MATCH 谓词 = 表级约束，必须直接挂在 docs_fts 后，普通谓词
          // 以 AND 追加（SQL 序：docs_fts MATCH ? AND d.kind IN …）；绑定序同 SQL 序。
          // **不在这里分页**：stableId 归并 + (ts,stableId) 游标是唯一分页单点，
          // SQL 只取有界候选集（RowScanCap）——两处各减一次 offset 会跳行。
          val w = s" WHERE docs_fts MATCH ?${where.toString.replace(" WHERE 1=1", "")}"
          fetch(
            s"""SELECT d.sid,d.ord,d.kind,d.ts,d.text,d.extra FROM docs d
               |JOIN docs_fts f ON f.rowid=d.rowid$w
               |ORDER BY d.ts DESC, d.ord DESC LIMIT ?""".stripMargin,
            ps =>
              ps.setString(1, expr)
              bindBase(ps, 1)
              ps.setInt(params.size + 2, RowScanCap)
          )
        end execMatch

        def execLike(pattern: String): List[Hit] =
          // pattern 来自 CjkBigram.ftsQuery 的 likePattern（"%单字%"，% 是通配）。
          // 只转义字面段、保留 % 通配——整串 escapeLike 会把通配吃掉（曾致
          // LIKE 退化为整串等值匹配，单字 CJK 永远 0 命中）。
          val likeParam = pattern.split("%", -1).map(escapeLike).mkString("%")
          val w = s"${where} AND d.text LIKE ? ESCAPE '\\'"
          fetch(
            s"""SELECT d.sid,d.ord,d.kind,d.ts,d.text,d.extra FROM docs d$w
               |ORDER BY d.ts DESC, d.ord DESC LIMIT ?""".stripMargin,
            ps =>
              bindBase(ps, 0)
              ps.setString(params.size + 1, likeParam)
              ps.setInt(params.size + 2, RowScanCap)
          )

        /**
         * FTS 全零时的**原文子串**腿（不是并集，只在前腿零命中时单独跑一次）。
         *  为什么必须有：ftsQuery 把 ASCII 非字母数字一律剥掉（`cursor-race` →
         *  `cursorrace`），而入库文本里的连字符仍在 unicode61 的词里 ⇒ FTS 零命中，
         *  用户搜原文却什么都拿不到。这里用**已转义**的整串作单参数 LIKE，只转义
         *  字面段、不拆 % 通配（与 execLike 区分，避免二次转义）。
         */
        def execLiteral(text: String): List[Hit] =
          val likeParam = "%" + escapeLike(text) + "%"
          val w = s"${where} AND d.text LIKE ? ESCAPE '\\'"
          fetch(
            s"""SELECT d.sid,d.ord,d.kind,d.ts,d.text,d.extra FROM docs d$w
               |ORDER BY d.ts DESC, d.ord DESC LIMIT ?""".stripMargin,
            ps =>
              bindBase(ps, 0)
              ps.setString(params.size + 1, likeParam)
              ps.setInt(params.size + 2, RowScanCap)
          )

        // 检索阶梯（作者 2026-11-21 晨间裁定「速度优先、废除并集」）：
        // FTS 全词 AND → FTS 放宽 OR → 单字 CJK 的 LIKE 兜底 → 原文子串腿。
        // **不做并集**——并集要把 MATCH 与 LIKE 两腿在同一 SQL 里 OR 起来，大库上
        // 等于整表 LIKE 扫，与「速度优先」相悖；FTS 有命中的前提下一律以 FTS 为准
        // （词边界语义），只有 FTS 整条阶梯零命中才下探到原文子串一次。
        val (candidates, degraded) =
          if andExpr.nonEmpty then
            val h1 = execMatch(andExpr)
            if h1.nonEmpty then (h1, false)
            else if orExpr.nonEmpty then
              val h2 = execMatch(orExpr)
              if h2.nonEmpty then (h2, false)
              else if likePattern.nonEmpty then (execLike(likePattern), true)
              else (execLiteral(q.text.trim), true)
            else if likePattern.nonEmpty then (execLike(likePattern), true)
            else (execLiteral(q.text.trim), true)
          else if likePattern.nonEmpty then (execLike(likePattern), true)
          else (execLiteral(q.text.trim), true)

        // 消息级归并（stableId 相同 = 同一条消息的多份副本）+ 代际游标分支：
        // occurrenceCount 记副本份数，对外 total 报**消息级**条数。
        val unique = candidates
          .groupBy(_.stableId)
          .values
          .map { copies =>
            val representative = copies.minBy(h => (h.sid, h.ord, h.extra))
            representative.copy(occurrenceCount = copies.size)
          }
          .toList
          .sortBy(h => (-BigInt(h.ts), h.stableId))
        val gen = generation(fs.conn)
        val changed = q.cursor.exists(_.generation != gen)
        val remaining =
          if changed then unique
          else
            q.cursor.fold(unique) { c =>
              unique.dropWhile(h => h.ts > c.ts || (h.ts == c.ts && h.stableId <= c.stableId))
            }
        // 显式 offset 只在「无游标且游标未失效」时参与（游标面自带位次，重复减会跳行）。
        val window = if q.cursor.isEmpty && !changed then remaining.drop(math.max(0, q.offset)) else remaining
        val limit = math.max(1, math.min(q.limit, 200))
        val pageHits = window.take(limit)
        val next = if window.size > limit then pageHits.lastOption.map(h => Cursor(h.ts, h.stableId, gen)) else None
        Right(
          QueryResult(
            pageHits,
            unique.size,
            etagOf(q, pageHits, versionsOf(fs.conn, pageHits.map(_.sid).distinct), gen),
            degraded,
            gen,
            next,
            changed
          )
        )
      end if
    end if
  end runLegacyQuery

  private def escapeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  /**
   * 查询凭证 ETag（§4.4）：结构键 = face + 规范化参数 + Σ(命中会话 version)。
   *  命中集内任何会话变更 ⇒ 其 version bump ⇒ ETag 必变 ⇒ 304 协商精确失效。
   *  零命中时退化为参数键（新数据进索引后 version 从 0→1，Σ 变化仍触发失效——
   *  由 reconcileBeforeQuery 的 meta 行保证：新会话首 sync 即建 meta）。
   */
  private def etagOf(q: Query, hits: List[Hit], versions: List[Long], generation: String): String =
    val norm =
      s"v1|${q.text.trim.toLowerCase}|${q.kinds.sorted.mkString(",")}|${q.sid.getOrElse("")}|" +
        s"${q.since.getOrElse(0L)}|${q.until.getOrElse(0L)}"
    val seed =
      if hits.nonEmpty then
        norm + "|" + q.cursor.toString + "|" + q.offset + "|" + hits.map(_.stableId).mkString(";") + "|" + versions
          .mkString(",")
      else norm + "|" + versions.mkString(",") + "|" + q.cursor.toString + "|" + q.offset
    val sha = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest((seed + "|" + generation).getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
      .take(32)
    "\"" + sha + "\""

  end etagOf

  /** 命中会话的版本号（ETag 的 Σversion 腿；缺行 = 0）。 */
  private def versionsOf(conn: Connection, sids: List[String]): List[Long] =
    sids.map { sid =>
      val ps = conn.prepareStatement("SELECT version FROM sessions_meta WHERE sid=?")
      try
        ps.setString(1, sid)
        val rs = ps.executeQuery()
        if rs.next() then rs.getLong(1) else 0L
      finally ps.close()
    }

  // ── 冷建 / 重建（§4.3：boot 后台 fiber，不阻塞启动；零超时红线——不设
  //    wall-clock 超时，进度用日志表达）─────────────────────────────────────

  /**
   * 冷建/全量对账：扫 adapter 全量会话源，缺失/陈旧者重建。后台 fiber 调用；
   *  不阻塞 boot；建库期间查询走旧全扫路径（行为不变，只是不快——灰度零风险）。
   */
  def fullRebuild(adapterFace: String, allSids: List[String]): IO[Unit] =
    faceOf(adapterFace) match
      case None => IO.unit
      case Some(fs) =>
        def loop(remaining: List[String], done: Int): IO[Unit] =
          remaining match
            case Nil => IO.unit
            case sid :: rest =>
              syncSessionIO(fs, sid, forceFull = false).attempt.flatMap {
                case Right(_) => loop(rest, done + 1)
                case Left(e) =>
                  IO(logger.warnSync(s"search-index rebuild: $sid failed (${e.getClass.getSimpleName}) — skipped"))
                    .flatMap(_ => loop(rest, done + 1))
              }
        val total = allSids.length
        loop(allSids, 0).flatMap { _ =>
          IO(logger.infoSync(s"search-index rebuild: face '$adapterFace' reconciled $total session(s)"))
        }

  /** 会话删除：索引行随删（deleteSession 锚点——§4.3 三处写路径之一）。 */
  def removeSession(face: String, sid: String): IO[Unit] =
    faceOf(face) match
      case None => IO.unit
      case Some(fs) =>
        IO.blocking {
          workPermit.acquire()
          try
            val del = fs.conn.prepareStatement("DELETE FROM docs WHERE sid=?")
            try
              del.setString(1, sid)
              del.executeUpdate()
            finally del.close()
            val delm = fs.conn.prepareStatement("DELETE FROM sessions_meta WHERE sid=?")
            try
              delm.setString(1, sid)
              delm.executeUpdate()
            finally delm.close()
            bumpGeneration(fs.conn)
            fs.conn.commit()
          catch
            case scala.util.control.NonFatal(e) =>
              try fs.conn.rollback()
              catch case scala.util.control.NonFatal(rollbackError) => e.addSuppressed(rollbackError)
              throw e
          finally workPermit.release()
          end try
        }.attempt
          .flatMap {
            case Right(_) => IO.unit
            case Left(e) => IO(logger.warnSync(s"search-index removeSession $sid failed: ${e.getMessage}"))
          }

end SearchIndexService
