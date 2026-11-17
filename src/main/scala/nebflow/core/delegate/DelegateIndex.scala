package nebflow.core.delegate

import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

import java.sql.{Connection, DriverManager, ResultSet}
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * DelegateIndex —— delegate 身份与轮次台账的 **SQLite 索引持久层**
 * （delegate-lifecycle-reform 阶段③；启动加载清单「delegate 身份与过期清理」行
 * 「重复全目录解码可用有失效策略的摘要」＋ §后续裁决 3「迁移／双区去重语义」）。
 *
 * == 形态（先例对齐，不新造原语）==
 * 单文件库 `<数据根>/delegate-index/delegates.db`——与检索索引
 * [[nebflow.core.search.SearchIndexService]] 的 `<数据根>/search-index/<face>.db`
 * 同款（同一 org.xerial sqlite-jdbc 依赖、同一「单写者 + 惰性对账 + 失败即整体
 * 禁用」纪律），不引第二套存储技术、不加构建依赖。
 *
 * == 两个区（迁移前先验的「双区去重语义」）==
 *   - **身份区** `delegates/<id>.json`：一文件一身份；去重键 = 文件名主干（`<id>`）
 *     **且必须等于**文件内 `id` 字段——两键不等即拒收（[[validateDedup]] 报硬违规，
 *     绝不静默取其一）；
 *   - **台账区** `delegates/<id>.rounds.jsonl`：append-only 多行；去重键 =
 *     `(id, roundNo)`——同轮多行**末行胜**（[[DelegateRoundLedger]] 读面现义），
 *     `kind=stale-round` 审计行不进轮次状态。`round_lines` 存原始行（逐行可对账），
 *     `round_current` 存去重物化（两表同源，去重语义因此可被机械对账）。
 * 两区共键 = delegate id：台账区**绝不新建身份行**（无身份文件的台账 = 孤儿台账，
 * 只列报不入索引），故不再有第二份可漂移的身份真源。
 *
 * == 权威面（正确性永不依赖索引新鲜度）==
 * 源文件恒为权威，本层只读源、只写自己的 `.db`（源零改写）。索引只在**源码在场**
 * 时服务：源码目录缺席 ⇒ 索引两区整体作废并回退目录扫描（不复活被显式清空的
 * 身份）。行级水位 = `(src_mtime, src_size)`：变更/新增重解析，消失删行，坏文件
 * 不建行（与基线 `list()` 的「坏文件跳过」同义）。任何失败 ⇒ `None` → 调用方回退
 * 既有目录扫描（[[DelegateRegistry.list]] 由 inline 编排）；最坏 = 慢，不退化为错。
 *
 * == 能力面 ==
 *   读：[[listRecords]]（启动全量加载面）/ [[currentRoundsOf]]（轮次去重读面）
 *   写：[[onRecordWritten]] / [[onRecordRemoved]] / [[onLedgerAppended]]（既有写点
 *   单缝追加，零新增写面）
 *   运维：[[validateDedup]]（迁移前双区去重验证，只读）/ [[migrate]]（可逆迁移）/
 *   [[verify]]（逐记录 + 计数 + 两窗分区对账）/ [[rollback]]（撤索引回源码）/
 *   [[benchSourceScan]]（对照面：基线全扫，供基准）
 */
object DelegateIndex:

  /**
   * 类型别名：裸名 `Record` 在 Scala 3 会解析到 `java.lang.Record`（JDK 16+ 自动导入），
   * 本对象内一律经此别名指向注册记录——本层与注册表共用同一份记录形状，不另立模型。
   */
  type Record = DelegateRegistry.Record

  private val logger = NebflowLogger.forName("nebflow.core.delegate.index")

  /** 索引 schema 版本（`meta` 表；与检索索引同款纪律）。 */
  val SchemaVersion: Int = 1

  /** 索引目录名（对齐 `<数据根>/search-index/`）。 */
  val DirName: String = "delegate-index"

  /** 库文件名。 */
  val DbFileName: String = "delegates.db"

  /** 关闸开关：`-Dnebflow.delegate.index.enabled=0` ⇒ 零索引、恒走目录扫描。 */
  val EnabledProp: String = "nebflow.delegate.index.enabled"

  /** 两区名（报告/读数用词）。 */
  val RecordZone: String = "record"
  val LedgerZone: String = "ledger"

  def enabled: Boolean =
    sys.props.get(EnabledProp).forall(v => v != "0" && !v.equalsIgnoreCase("false"))

  def dbPathOf(root: os.Path): os.Path = root / DirName / DbFileName

  def sourceDirOf(root: os.Path): os.Path = root / "delegates"

  def recordFileOf(root: os.Path, id: String): os.Path = sourceDirOf(root) / s"$id.json"

  def ledgerFileOf(root: os.Path, id: String): os.Path = sourceDirOf(root) / s"$id.rounds.jsonl"

  // ── 机械读数（基准 / spec 断言的证据面）─────────────────────────────────

  /**
   * 一次全量加载的机械读数。
   *
   * @param path         `index`（索引加载）/ `source-scan`（基线目录全扫）
   * @param records      返回记录数
   * @param ledgers      本次对账涉及的台账文件数
   * @param sourceParses 本次**源文件**解析次数（启动收益的直接读数）
   * @param sourceStats  本次源文件 stat 次数（失效水位检查）
   * @param dbReads      本次库读语句数
   * @param ms           墙钟毫秒
   */
  final case class LoadStats(
    path: String,
    records: Int,
    ledgers: Int,
    sourceParses: Long,
    sourceStats: Long,
    dbReads: Long,
    ms: Long
  )

  private val sourceParses = new AtomicLong(0L)
  private val sourceStats = new AtomicLong(0L)
  private val dbReadsCounter = new AtomicLong(0L)
  @volatile private var lastLoad: LoadStats = LoadStats("none", 0, 0, 0L, 0L, 0L, 0L)

  /** 上次全量加载读数。 */
  def lastLoadStats: LoadStats = lastLoad

  /** 自上次 [[resetCounters]] 起的源文件解析次数。 */
  def sourceParseCount: Long = sourceParses.get()

  /** 自上次 [[resetCounters]] 起的源文件 stat 次数。 */
  def sourceStatCount: Long = sourceStats.get()

  /** 自上次 [[resetCounters]] 起的库读语句数。 */
  def dbReadCount: Long = dbReadsCounter.get()

  /** 计数归零（spec / 基准的逐段度量口径）。 */
  def resetCounters(): Unit =
    sourceParses.set(0L)
    sourceStats.set(0L)
    dbReadsCounter.set(0L)
    lastLoad = LoadStats("none", 0, 0, 0L, 0L, 0L, 0L)

  // ── 进程内单写者（单连接 + 串行闸；与检索索引同款）───────────────────────

  private final class Handle(val root: os.Path, val conn: Connection)

  private val gate = new Semaphore(1)
  @volatile private var handleOpt: Option[Handle] = None
  @volatile private var disabledFor: Option[os.Path] = None

  /** 关闭当前库（基准的冷开模拟 / 测试隔离；下次访问自动重开）。 */
  def close(): Unit =
    gate.acquire()
    try
      handleOpt.foreach(h => try h.conn.close() catch case _: Throwable => ())
      handleOpt = None
    finally gate.release()

  /** 清空进程内状态（含禁用记忆；测试隔离面）。 */
  private[nebflow] def unsafeResetForTests(): Unit =
    close()
    disabledFor = None

  private def openHandle(root: os.Path): Connection =
    os.makeDir.all(root / DirName)
    val conn = DriverManager.getConnection(s"jdbc:sqlite:${dbPathOf(root).toString}")
    conn.setAutoCommit(false)
    initSchema(conn)
    handleOpt = Some(Handle(root, conn))
    conn

  private def connFor(root: os.Path): Connection =
    handleOpt match
      case Some(h) if h.root == root => h.conn
      case Some(h) =>
        try h.conn.close() catch case _: Throwable => ()
        handleOpt = None
        openHandle(root)
      case None => openHandle(root)

  /** 失败 ⇒ 整体禁用该根（读数回退目录扫描），返回 `None`。 */
  private def attempt[A](root: os.Path)(f: Connection => A): Option[A] =
    if !enabled then None
    else if disabledFor.contains(root) then None
    else
      gate.acquire()
      try Some(f(connFor(root)))
      catch
        case e: Throwable =>
          disabledFor = Some(root)
          logger.warnSync(
            s"[delegate-index] disabled for $root (${e.getClass.getSimpleName}: ${e.getMessage}) — reads fall back to the source scan")
          None
      finally gate.release()

  /** 同上，但内层 `None` 是**正常的回退信号**（不判失败、不禁用）。 */
  private def attemptOpt[A](root: os.Path)(f: Connection => Option[A]): Option[A] =
    attempt(root)(f).flatten

  private def initSchema(conn: Connection): Unit =
    val st = conn.createStatement()
    try
      st.execute(
        """CREATE TABLE IF NOT EXISTS delegates(
          |  id TEXT PRIMARY KEY,
          |  json TEXT NOT NULL,
          |  created_at INTEGER NOT NULL DEFAULT 0,
          |  archived_at INTEGER,
          |  project TEXT,
          |  parent_session_id TEXT,
          |  task_id TEXT,
          |  round_no INTEGER,
          |  disposition TEXT,
          |  pinned INTEGER NOT NULL DEFAULT 0,
          |  src_mtime INTEGER NOT NULL DEFAULT 0,
          |  src_size INTEGER NOT NULL DEFAULT 0,
          |  indexed_at INTEGER NOT NULL DEFAULT 0
          |)""".stripMargin)
      st.execute("CREATE INDEX IF NOT EXISTS idx_delegates_created ON delegates(created_at)")
      st.execute(
        """CREATE TABLE IF NOT EXISTS round_sources(
          |  id TEXT PRIMARY KEY,
          |  src_mtime INTEGER NOT NULL DEFAULT 0,
          |  src_size INTEGER NOT NULL DEFAULT 0
          |)""".stripMargin)
      st.execute(
        """CREATE TABLE IF NOT EXISTS round_lines(
          |  id TEXT NOT NULL,
          |  line_ord INTEGER NOT NULL,
          |  line_no INTEGER NOT NULL DEFAULT 0,
          |  json TEXT NOT NULL,
          |  PRIMARY KEY(id, line_ord)
          |)""".stripMargin)
      st.execute(
        """CREATE TABLE IF NOT EXISTS round_current(
          |  id TEXT NOT NULL,
          |  round_no INTEGER NOT NULL,
          |  kind TEXT NOT NULL DEFAULT 'round',
          |  round_id TEXT NOT NULL DEFAULT '',
          |  started_at INTEGER NOT NULL DEFAULT 0,
          |  settled_at INTEGER,
          |  verdict TEXT,
          |  json TEXT NOT NULL,
          |  PRIMARY KEY(id, round_no)
          |)""".stripMargin)
      st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
      st.execute(s"INSERT OR REPLACE INTO meta VALUES('schema_version','$SchemaVersion')")
      conn.commit()
    finally st.close()

  // ── 读面：启动全量加载 ──────────────────────────────────────────────────

  /**
   * 启动全量加载面：索引在场且源码在场 ⇒ 水位对账后由**索引**给出全量记录；
   * 关闸 / 禁用 / 源码目录缺席 / 任何异常 ⇒ `None`（调用方回退目录扫描）。返回序与
   * 基线 `list()` 同（`createdAt` 倒序，id 为确定性次序键）。
   */
  def listRecords(): Option[List[Record]] =
    val root = PathUtil.dataRoot
    val p0 = sourceParses.get()
    val s0 = sourceStats.get()
    val d0 = dbReadsCounter.get()
    val t0 = System.nanoTime()
    attemptOpt(root) { conn =>
      if !enabled then None
      else
        val dir = sourceDirOf(root)
        val ledgers =
          if !os.exists(dir) then
            // 权威面缺席（显式清空/未创建）：两区作废，绝不复活。
            clearRecords(conn)
            clearLedgers(conn)
            0L
          else reconcile(conn, root, dir)
        val recs = loadRecords(conn)
        dbReadsCounter.incrementAndGet()
        lastLoad = LoadStats(
          "index",
          recs.size,
          ledgers.toInt,
          sourceParses.get() - p0,
          sourceStats.get() - s0,
          dbReadsCounter.get() - d0,
          (System.nanoTime() - t0) / 1000000L
        )
        Some(recs)
    }

  private def loadRecords(conn: Connection): List[Record] =
    query(conn, "SELECT json FROM delegates ORDER BY created_at DESC, id")(rs => rs.getString(1))
      .flatMap(decodeRecord)

  private def decodeRecord(json: String): Option[Record] =
    parse(json).toOption.flatMap(_.as[Record].toOption)

  // ── 对账（水位失效策略）────────────────────────────────────────────────

  /** 身份区 + 台账区对账；返回本轮读到的台账文件数。 */
  private def reconcile(conn: Connection, root: os.Path, dir: os.Path): Long =
    val files = os.list(dir).filter(p => p.ext == "json" && p.last != ".legacy-settled.json")
    val known = query(conn, "SELECT id, src_mtime, src_size FROM delegates")(rs =>
      (rs.getString(1), rs.getLong(2), rs.getLong(3))).map { case (id, m, s) => id -> (m, s) }.toMap
    val seen = scala.collection.mutable.Set.empty[String]
    files.foreach { p =>
      val id = p.last.stripSuffix(".json")
      if DelegateRegistry.validId(id) then
        seen += id
        val (mtime, size) = watermark(p)
        known.get(id) match
          case Some((m, s)) if m == mtime && s == size => () // 水位不变：零解析
          case _ =>
            sourceParses.incrementAndGet()
            readText(p).flatMap(t => parse(t).toOption.flatMap(_.as[Record].toOption)) match
              case Some(rec) if rec.id == id => upsert(conn, id, readText(p).getOrElse(""), rec, mtime, size)
              case Some(rec) =>
                logger.warnSync(
                  s"[delegate-index] identity key mismatch: file '$id.json' declares id='${rec.id}' — row not indexed")
                deleteRecordRow(conn, id)
              case None =>
                logger.warnSync(s"[delegate-index] '$id.json' is not a decodable record — row dropped (scan parity)")
                deleteRecordRow(conn, id)
    }
    known.keySet.diff(seen).foreach { gone =>
      deleteRecordRow(conn, gone)
      deleteLedgerRows(conn, gone)
    }
    var ledgers = 0L
    seen.foreach { id => if syncLedger(conn, root, id) then ledgers += 1L }
    conn.commit()
    ledgers

  /** 文件水位 `(mtime, size)`；读不到 ⇒ `(0, -1)`（永不等于既有水位 ⇒ 必重解析）。 */
  private def watermark(p: os.Path): (Long, Long) =
    sourceStats.incrementAndGet()
    try (os.mtime(p), os.size(p))
    catch case _: Throwable => (0L, -1L)

  private def readText(p: os.Path): Option[String] =
    try Some(os.read(p))
    catch case _: Throwable => None

  private def upsert(conn: Connection, id: String, text: String, rec: Record, mtime: Long, size: Long): Unit =
    val ps = conn.prepareStatement(
      """INSERT INTO delegates(id,json,created_at,archived_at,project,parent_session_id,task_id,round_no,
        |  disposition,pinned,src_mtime,src_size,indexed_at)
        |VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
        |ON CONFLICT(id) DO UPDATE SET json=excluded.json, created_at=excluded.created_at,
        |  archived_at=excluded.archived_at, project=excluded.project,
        |  parent_session_id=excluded.parent_session_id, task_id=excluded.task_id,
        |  round_no=excluded.round_no, disposition=excluded.disposition, pinned=excluded.pinned,
        |  src_mtime=excluded.src_mtime, src_size=excluded.src_size, indexed_at=excluded.indexed_at""".stripMargin)
    try
      ps.setString(1, id)
      ps.setString(2, text)
      ps.setLong(3, rec.createdAt)
      setOptLong(ps, 4, rec.archivedAt)
      ps.setString(5, rec.project.orNull)
      ps.setString(6, rec.parentSessionId)
      ps.setString(7, rec.taskId.orNull)
      setOptInt(ps, 8, rec.roundNo)
      ps.setString(9, rec.disposition.orNull)
      ps.setInt(10, if rec.pinnedAt.isDefined then 1 else 0)
      ps.setLong(11, mtime)
      ps.setLong(12, size)
      ps.setLong(13, System.currentTimeMillis())
      ps.executeUpdate()
    finally ps.close()

  private def setOptLong(ps: java.sql.PreparedStatement, i: Int, v: Option[Long]): Unit =
    v match
      case Some(x) => ps.setLong(i, x)
      case None => ps.setNull(i, java.sql.Types.INTEGER)

  private def setOptInt(ps: java.sql.PreparedStatement, i: Int, v: Option[Int]): Unit =
    v match
      case Some(x) => ps.setInt(i, x)
      case None => ps.setNull(i, java.sql.Types.INTEGER)

  private def deleteRecordRow(conn: Connection, id: String): Unit =
    val ps = conn.prepareStatement("DELETE FROM delegates WHERE id=?")
    try
      ps.setString(1, id)
      ps.executeUpdate()
    finally ps.close()

  private def deleteLedgerRows(conn: Connection, id: String): Unit =
    List("DELETE FROM round_lines WHERE id=?", "DELETE FROM round_current WHERE id=?", "DELETE FROM round_sources WHERE id=?")
      .foreach { sql =>
        val ps = conn.prepareStatement(sql)
        try
          ps.setString(1, id)
          ps.executeUpdate()
        finally ps.close()
      }

  private def clearRecords(conn: Connection): Unit =
    val st = conn.createStatement()
    try st.executeUpdate("DELETE FROM delegates")
    finally st.close()

  private def clearLedgers(conn: Connection): Unit =
    val st = conn.createStatement()
    try
      st.executeUpdate("DELETE FROM round_lines")
      st.executeUpdate("DELETE FROM round_current")
      st.executeUpdate("DELETE FROM round_sources")
    finally st.close()

  // ── 台账区（append-only 行 + per-roundNo 去重物化）────────────────────────

  /**
   * 台账同步（水位失效）：文件不存在 ⇒ 清空该 id 台账区；水位未变 ⇒ 零读；否则重读
   * 全部行（`round_lines` 重建）并按 [[DelegateRoundLedger]] 读面语义去重物化
   * `round_current`（同 roundNo 末行胜；`kind=stale-round` 不入轮次状态）。返回是否读盘。
   */
  private def syncLedger(conn: Connection, root: os.Path, id: String): Boolean =
    val p = ledgerFileOf(root, id)
    if !os.exists(p) then
      deleteLedgerRows(conn, id)
      false
    else
      val (mtime, size) = watermark(p)
      val known = boundQuery(conn, "SELECT src_mtime, src_size FROM round_sources WHERE id=?")(ps => ps.setString(1, id))(
        rs => (rs.getLong(1), rs.getLong(2))).headOption
      known match
        case Some((m, s)) if m == mtime && s == size => false
        case _ =>
          sourceParses.incrementAndGet()
          val lines = try os.read.lines(p).toList catch case _: Throwable => Nil
          deleteLedgerRows(conn, id)
          insertLines(conn, id, lines)
          materializeCurrent(conn, id, lines)
          val ins = conn.prepareStatement("INSERT OR REPLACE INTO round_sources(id,src_mtime,src_size) VALUES(?,?,?)")
          try
            ins.setString(1, id)
            ins.setLong(2, mtime)
            ins.setLong(3, size)
            ins.executeUpdate()
          finally ins.close()
          true

  private def insertLines(conn: Connection, id: String, lines: List[String]): Unit =
    if lines.nonEmpty then
      val ps = conn.prepareStatement("INSERT INTO round_lines(id,line_ord,line_no,json) VALUES(?,?,?,?)")
      try
        lines.zipWithIndex.foreach { case (line, i) =>
          ps.setString(1, id)
          ps.setInt(2, i)
          ps.setInt(3, i + 1)
          ps.setString(4, line)
          ps.addBatch()
        }
        ps.executeBatch()
      finally ps.close()

  /** 去重物化单点：同 roundNo 末行胜（append-only 读面现义）；坏行跳过（fail-soft）。 */
  private def materializeCurrent(conn: Connection, id: String, lines: List[String]): Unit =
    val latest = scala.collection.mutable.LinkedHashMap.empty[Int, DelegateRoundLedger.RoundRow]
    lines.foreach { line =>
      io.circe.parser.decode[DelegateRoundLedger.RoundRow](line.trim).toOption
        .filter(_.kind == DelegateRoundLedger.KindRound)
        .foreach(r => latest.put(r.roundNo, r))
    }
    val ps = conn.prepareStatement(
      """INSERT OR REPLACE INTO round_current(id,round_no,kind,round_id,started_at,settled_at,verdict,json)
        |VALUES(?,?,?,?,?,?,?,?)""".stripMargin)
    try
      latest.values.toList.sortBy(_.roundNo).foreach { r =>
        ps.setString(1, id)
        ps.setInt(2, r.roundNo)
        ps.setString(3, r.kind)
        ps.setString(4, r.roundId)
        ps.setLong(5, r.startedAt)
        setOptLong(ps, 6, r.settledAt)
        ps.setString(7, r.verdict.orNull)
        ps.setString(8, r.asJson.noSpaces)
        ps.executeUpdate()
      }
    finally ps.close()

  /**
   * 轮次去重读面（索引路径）：`None` = 回退文件读。只在**身份与台账源文件都在场**时
   * 服务——孤儿台账仍走文件读，索引不凭空给出无主轮次史。
   */
  def currentRoundsOf(id: String): Option[List[DelegateRoundLedger.RoundRow]] =
    val root = PathUtil.dataRoot
    attemptOpt(root) { conn =>
      val ledger = ledgerFileOf(root, id)
      val identity = recordFileOf(root, id)
      if !enabled || !os.exists(ledger) || !os.exists(identity) then None
      else
        syncLedger(conn, root, id)
        conn.commit()
        dbReadsCounter.incrementAndGet()
        Some(currentRoundsInIndex(conn, id))
    }

  private def currentRoundsInIndex(conn: Connection, id: String): List[DelegateRoundLedger.RoundRow] =
    boundQuery(conn, "SELECT json FROM round_current WHERE id=? ORDER BY round_no")(ps => ps.setString(1, id))(
      rs => rs.getString(1))
      .flatMap(s => io.circe.parser.decode[DelegateRoundLedger.RoundRow](s).toOption)

  // ── 写路径钩子（既有写点单缝追加，零新增写面）───────────────────────────

  /** 记录落盘钩子（[[DelegateRegistry.put]] 调用）：该文件重读入库（同步、失败软化）。 */
  def onRecordWritten(id: String): Unit =
    val root = PathUtil.dataRoot
    attempt(root) { conn =>
      val p = recordFileOf(root, id)
      if os.exists(p) then
        val (mtime, size) = watermark(p)
        readText(p).flatMap(t => parse(t).toOption.flatMap(_.as[Record].toOption)) match
          case Some(rec) if rec.id == id => upsert(conn, id, os.read(p), rec, mtime, size)
          case _ => deleteRecordRow(conn, id)
        conn.commit()
    }
    ()

  /** 记录移除钩子（[[DelegateRegistry.remove]] 调用）：索引行随删（含台账区）。 */
  def onRecordRemoved(id: String): Unit =
    attempt(PathUtil.dataRoot) { conn =>
      deleteRecordRow(conn, id)
      deleteLedgerRows(conn, id)
      conn.commit()
    }
    ()

  /** 台账追加钩子（[[DelegateRoundLedger.appendSync]] 调用）：该 id 台账重同步。 */
  def onLedgerAppended(id: String): Unit =
    val root = PathUtil.dataRoot
    attempt(root) { conn =>
      syncLedger(conn, root, id)
      conn.commit()
    }
    ()

  // ── 运维面：双区去重验证 / 迁移 / 对账 / 撤销 ────────────────────────────

  /** 身份区/台账区计数与去重读数（迁移前必须先出这份）。 */
  final case class DedupReport(
    identityFiles: Int,
    distinctIds: Int,
    idFieldMismatches: List[String],
    duplicateIds: List[String],
    undecodableIdentities: List[String],
    archivedIdentities: Int,
    nonArchivedIdentities: Int,
    ledgerFiles: Int,
    ledgerLines: Int,
    ledgerOrphanIds: List[String],
    ledgerIdsWithIdentity: Int,
    roundLines: Int,
    distinctRounds: Int,
    droppedDuplicateLines: Int,
    staleRoundLines: Int,
    ok: Boolean,
    notes: List[String]
  )

  final case class DriftRow(field: String, expected: Long, actual: Long):
    def delta: Long = actual - expected

  final case class MigrationReport(
    root: String,
    dedup: DedupReport,
    importedIdentities: Int,
    importedLedgers: Int,
    importedLines: Int,
    skipped: List[String],
    drift: List[DriftRow],
    ms: Long
  )

  final case class Mismatch(id: String, zone: String, reason: String)

  final case class VerifyReport(
    root: String,
    sourceIdentities: Int,
    indexIdentities: Int,
    sourceLedgers: Int,
    indexLedgers: Int,
    sourceLines: Int,
    indexLines: Int,
    recordMismatches: List[Mismatch],
    roundMismatches: List[Mismatch],
    extraRows: List[String],
    windowActive: Int,
    windowHistory: Int,
    windowTotal: Int,
    partitionOk: Boolean,
    countsMatch: Boolean
  ):
    def ok: Boolean =
      countsMatch && partitionOk && recordMismatches.isEmpty && roundMismatches.isEmpty && extraRows.isEmpty

  final case class RollbackReport(root: String, dbFile: String, backup: Option[String], dropped: Boolean)

  /** 源目录里的身份文件清单。 */
  private def identityFiles(root: os.Path): List[os.Path] =
    val dir = sourceDirOf(root)
    if !os.exists(dir) then Nil else os.list(dir).filter(p => p.ext == "json" && p.last != ".legacy-settled.json").toList.sortBy(_.last)

  /** 源目录里的台账文件清单。 */
  private def ledgerFiles(root: os.Path): List[os.Path] =
    val dir = sourceDirOf(root)
    if !os.exists(dir) then Nil
    else os.list(dir).filter(_.last.endsWith(".rounds.jsonl")).toList.sortBy(_.last)

  /**
   * 迁移前双区去重语义验证（**只读，零写**）。硬违规（`ok=false`）：身份区去重键
   * 不符（文件名主干 ≠ 记录 id）或同一 id 出现两次 ⇒ 迁移会重复计数/丢行，必须先
   * 处置；不可解码记录同判硬违规（不静默丢弃）。孤儿台账（无身份文件的
   * `.rounds.jsonl`）**不是**硬违规——按「台账区不造身份」只列报。
   */
  def validateDedup(root: os.Path): DedupReport =
    val files = identityFiles(root)
    val ids = scala.collection.mutable.ListBuffer.empty[String]
    val mismatches = scala.collection.mutable.ListBuffer.empty[String]
    val undecodable = scala.collection.mutable.ListBuffer.empty[String]
    var archived = 0
    var unarchived = 0
    files.foreach { p =>
      val stem = p.last.stripSuffix(".json")
      readText(p).flatMap(t => parse(t).toOption.flatMap(_.as[Record].toOption)) match
        case Some(rec) =>
          if rec.id != stem then mismatches += s"$stem -> ${rec.id}" else ids += rec.id
          if rec.archivedAt.isDefined then archived += 1 else unarchived += 1
        case None => undecodable += stem
    }
    val dup = ids.groupBy(identity).filter(_._2.size > 1).keys.toList.sorted
    val ledgers = ledgerFiles(root)
    var lines = 0
    var roundLines = 0
    var staleLines = 0
    var distinctRounds = 0
    val ledgerIds = scala.collection.mutable.ListBuffer.empty[String]
    ledgers.foreach { p =>
      val id = p.last.stripSuffix(".rounds.jsonl")
      ledgerIds += id
      val rows = try os.read.lines(p).toList catch case _: Throwable => Nil
      lines += rows.size
      val parsed = rows.flatMap(l => io.circe.parser.decode[DelegateRoundLedger.RoundRow](l.trim).toOption)
      val rounds = parsed.filter(_.kind == DelegateRoundLedger.KindRound)
      roundLines += rounds.size
      staleLines += parsed.count(_.kind != DelegateRoundLedger.KindRound)
      distinctRounds += rounds.map(_.roundNo).distinct.size
    }
    val idSet = ids.toSet
    val distinctLedgerIds = ledgerIds.toList.distinct
    DedupReport(
      identityFiles = files.size,
      distinctIds = ids.distinct.size,
      idFieldMismatches = mismatches.toList,
      duplicateIds = dup,
      undecodableIdentities = undecodable.toList,
      archivedIdentities = archived,
      nonArchivedIdentities = unarchived,
      ledgerFiles = ledgers.size,
      ledgerLines = lines,
      ledgerOrphanIds = distinctLedgerIds.filterNot(idSet.contains).sorted,
      ledgerIdsWithIdentity = distinctLedgerIds.count(idSet.contains),
      roundLines = roundLines,
      distinctRounds = distinctRounds,
      droppedDuplicateLines = roundLines - distinctRounds,
      staleRoundLines = staleLines,
      ok = mismatches.isEmpty && dup.isEmpty && undecodable.isEmpty,
      notes = List(
        "identity zone: key = filename stem, and it must equal the record's own id field",
        "ledger zone: key = (id, roundNo); last line of a round wins; stale-round audit lines carry no round state",
        "shared key = delegate id; the ledger zone never mints an identity row (orphan ledgers are reported, not adopted)"
      )
    )

  /**
   * 可逆迁移：源文件（身份 JSON + 台账 JSONL）→ 索引。**源零改写**（只读）；先跑
   * [[validateDedup]]，硬违规 ⇒ 拒绝迁移（不落半份数据）。迁移后必须 [[verify]] 逐记录 +
   * 计数 + 两窗对账；撤销 = [[rollback]]（丢索引回源码）。
   *
   * @param expected 期望计数（`identities` / `archived` / `nonArchived` / `ledgers` /
   *                 `ledgerLines`）；未给定者以源侧实际值为基线（漂移恒 0，读数不作假）
   */
  def migrate(root: os.Path, expected: Map[String, Long] = Map.empty): Either[String, MigrationReport] =
    val t0 = System.nanoTime()
    val dedup = validateDedup(root)
    if !dedup.ok then
      Left(
        s"two-zone dedup validation failed (idFieldMismatches=${dedup.idFieldMismatches.size}, " +
          s"duplicateIds=${dedup.duplicateIds.size}, undecodable=${dedup.undecodableIdentities.size}) — migration refused")
    else
      attempt(root) { conn =>
        val files = identityFiles(root)
        val stems = files.map(_.last.stripSuffix(".json"))
        val skipped = scala.collection.mutable.ListBuffer.empty[String]
        // 全量重建（幂等重跑）：先清两区，再以源为准导入
        clearRecords(conn)
        clearLedgers(conn)
        stems.foreach { id =>
          val p = recordFileOf(root, id)
          val text = readText(p)
          text.flatMap(t => parse(t).toOption.flatMap(_.as[Record].toOption)) match
            case Some(r) if r.id == id =>
              val (mtime, size) = watermark(p)
              upsert(conn, id, text.getOrElse(""), r, mtime, size)
            case Some(_) => skipped += s"$id:file/id mismatch"
            case None => skipped += s"$id:undecodable"
        }
        stems.foreach(id => syncLedger(conn, root, id))
        val importedIdentities = count(conn, "SELECT COUNT(*) FROM delegates")
        val archived = count(conn, "SELECT COUNT(*) FROM delegates WHERE archived_at IS NOT NULL")
        val importedLedgers = count(conn, "SELECT COUNT(*) FROM round_sources")
        val importedLines = count(conn, "SELECT COUNT(*) FROM round_lines")
        val ledgerStems = ledgerFiles(root).map(_.last.stripSuffix(".rounds.jsonl")).distinct
        ledgerStems.filterNot(stems.toSet.contains).foreach { id =>
          skipped += s"$id:orphan-ledger (no identity file — not indexed)"
        }
        val actuals = Map(
          "identities" -> importedIdentities.toLong,
          "archived" -> archived.toLong,
          "nonArchived" -> (importedIdentities - archived).toLong,
          "ledgers" -> importedLedgers.toLong,
          "ledgerLines" -> importedLines.toLong
        )
        val drift = List("identities", "archived", "nonArchived", "ledgers", "ledgerLines").map { f =>
          DriftRow(f, expected.getOrElse(f, actuals(f)), actuals(f))
        }
        conn.commit()
        dbReadsCounter.incrementAndGet()
        MigrationReport(
          root.toString,
          dedup,
          importedIdentities,
          importedLedgers,
          importedLines,
          skipped.toList,
          drift,
          (System.nanoTime() - t0) / 1000000L
        )
      }.toRight(s"index unavailable at ${dbPathOf(root)}")

  /**
   * 对账（迁移/回滚/基准的验收面）：**逐记录**（身份区字节 + 解码等价；台账区去重读数
   * 对文件读面）+ **计数**（文件数 = 行数、原始行数 = 行数），另加**两窗分区**完整性
   * （活动窗 + 历史窗 = 总数；双区去重语义迁移后的落地检查）。
   */
  def verify(root: os.Path): Option[VerifyReport] =
    attempt(root) { conn =>
      val files = identityFiles(root)
      val ledgers = ledgerFiles(root)
      val srcIds = files.map(_.last.stripSuffix(".json")).toSet[String]
      val indexRows = query(conn, "SELECT id, json FROM delegates")(rs => (rs.getString(1), rs.getString(2))).toMap
      val recMismatches = scala.collection.mutable.ListBuffer.empty[Mismatch]
      files.foreach { p =>
        val stem = p.last.stripSuffix(".json")
        indexRows.get(stem) match
          case None => recMismatches += Mismatch(stem, RecordZone, "missing index row")
          case Some(stored) =>
            readText(p) match
              case None => recMismatches += Mismatch(stem, RecordZone, "source unreadable")
              case Some(t) =>
                if t.trim != stored.trim then recMismatches += Mismatch(stem, RecordZone, "byte mismatch")
                else if decodeRecord(stored) != parse(t).toOption.flatMap(_.as[Record].toOption) then
                  recMismatches += Mismatch(stem, RecordZone, "decode mismatch")
      }
      val extra = indexRows.keySet.diff(srcIds).toList.sorted
      val srcLedgerIds = ledgers.map(_.last.stripSuffix(".rounds.jsonl")).toSet[String]
      val indexLedgerIds = query(conn, "SELECT id FROM round_sources")(rs => rs.getString(1)).toSet[String]
      val indexLines = count(conn, "SELECT COUNT(*) FROM round_lines")
      val srcLines = ledgers.map(p => try os.read.lines(p).size catch case _: Throwable => 0).sum
      val roundMismatches = scala.collection.mutable.ListBuffer.empty[Mismatch]
      srcLedgerIds.intersect(srcIds).toList.sorted.foreach { id =>
        val fromFile = DelegateRoundLedger.currentRoundsFromFile(id)
        val fromIndex = currentRoundsInIndex(conn, id)
        if fromFile != fromIndex then
          roundMismatches += Mismatch(
            id,
            LedgerZone,
            s"deduped rounds differ (file=${fromFile.size}, index=${fromIndex.size})")
      }
      val readings = indexRows.toList.map { case (id, json) =>
        decodeRecord(json).map(r =>
          DelegateVisibility.read(Some(r), currentRoundsInIndex(conn, id), DelegateVisibility.liveChildrenOf(id)))
      }
      val history = readings.count(_.exists(_.historyWindow))
      val total = readings.size
      VerifyReport(
        root = root.toString,
        sourceIdentities = files.size,
        indexIdentities = indexRows.size,
        sourceLedgers = srcLedgerIds.size,
        indexLedgers = indexLedgerIds.size,
        sourceLines = srcLines,
        indexLines = indexLines,
        recordMismatches = recMismatches.toList,
        roundMismatches = roundMismatches.toList,
        extraRows = extra,
        windowActive = total - history,
        windowHistory = history,
        windowTotal = total,
        partitionOk = readings.forall(_.isDefined),
        countsMatch = files.size == indexRows.size && srcLedgerIds.intersect(srcIds) == indexLedgerIds &&
          srcLines == indexLines && extra.isEmpty
      )
    }

  /**
   * 撤销（回滚）：关闭并**移走**索引库（保留为 `.rollback-<ts>` 备查），源码零写。
   * 撤销后读面自动回退目录扫描（阶段③ 前的基线行为）。
   */
  def rollback(root: os.Path): RollbackReport =
    close()
    val db = dbPathOf(root)
    if !os.exists(db) then RollbackReport(root.toString, db.toString, None, dropped = false)
    else
      val backup = os.Path(db.toString + s".rollback-${System.currentTimeMillis()}")
      try
        os.move(db, backup)
        RollbackReport(root.toString, db.toString, Some(backup.toString), dropped = true)
      catch
        case e: Throwable =>
          logger.warnSync(s"[delegate-index] rollback move failed (${e.getMessage}) — removing instead")
          try
            os.remove(db)
            RollbackReport(root.toString, db.toString, None, dropped = true)
          catch case _: Throwable => RollbackReport(root.toString, db.toString, None, dropped = false)

  // ── 对照面：基线全扫（基准用；与阶段③ 前的生产读面逐行同义）──────────────

  /**
   * 目录全扫（**唯一实现点**，两个消费者）：① [[DelegateRegistry.list]] 的权威回退面
   * （索引缺席/禁用/源码异常时走它，行为与阶段③ 前逐行同源，含坏文件跳过与
   * `createdAt` 倒序）；② [[benchSourceScan]] 的对照面（度量迁移前的启动加载代价）。
   * 同一份实现 ⇒ 「索引路径 == 源码路径」的等值断言（[[verify]] 与 spec）不是自证。
   */
  def scanRecords(root: os.Path): List[Record] =
    try
      val dir = sourceDirOf(root)
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.ext == "json")
          .flatMap { p =>
            sourceParses.incrementAndGet()
            try
              sourceStats.incrementAndGet()
              parse(os.read(p)).toOption.flatMap(_.as[Record].toOption)
            catch case _: Throwable => None
          }
          .toList
          .sortBy(-_.createdAt)
    catch case _: Throwable => Nil

  /**
   * 基线全扫的**计时读数**（基准用）：[[scanRecords]] + 墙钟/解析计数快照。
   */
  def benchSourceScan(root: os.Path): LoadStats =
    val p0 = sourceParses.get()
    val s0 = sourceStats.get()
    val t0 = System.nanoTime()
    val recs = scanRecords(root)
    val stats = LoadStats(
      "source-scan",
      recs.size,
      ledgerFiles(root).size,
      sourceParses.get() - p0,
      sourceStats.get() - s0,
      0L,
      (System.nanoTime() - t0) / 1000000L
    )
    lastLoad = stats
    stats

  // ── JDBC 小工具（单点，零第三方封装）────────────────────────────────────

  private def query[A](conn: Connection, sql: String)(map: ResultSet => A): List[A] =
    val ps = conn.prepareStatement(sql)
    try
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer.empty[A]
      while rs.next() do buf += map(rs)
      buf.toList
    finally ps.close()

  private def boundQuery[A](conn: Connection, sql: String)(bind: java.sql.PreparedStatement => Unit)(
    map: ResultSet => A
  ): List[A] =
    val ps = conn.prepareStatement(sql)
    try
      bind(ps)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer.empty[A]
      while rs.next() do buf += map(rs)
      buf.toList
    finally ps.close()

  private def count(conn: Connection, sql: String): Int =
    query(conn, sql)(rs => rs.getInt(1)).headOption.getOrElse(0)

end DelegateIndex
