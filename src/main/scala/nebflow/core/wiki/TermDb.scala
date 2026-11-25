package nebflow.core.wiki

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/**
 * 词条索引库的**引擎读侧**（term-system v2 · ②，2026-11-05）。
 *
 * 库是单一 SQLite 文件 `<dataRoot>/wiki-index/terms.db`，由 `tools/stand-terms
 * rebuild` 全量重建（写侧只归 CLI，引擎**只读**）。本类回答作者那句话的两半：
 *   · 「引擎剥离出词条之后建立索引，然后方便去查询」——按名/别名/项目标签/主题
 *     标签/文档过滤的词条检索；
 *   · 「查询词条的时候是会返回其原文档的信息的」——每条结果都带**原文档**的
 *     项目、vault 相对路径、标题、doc_type、status、绝对路径。
 *
 * 四条设计约束（缺一条就会把「背景面」变成故障源）：
 *   1. **只读打开**：`SQLiteConfig.setReadOnly(true)`（open_mode=READONLY）。库
 *      不存在时 sqlite 直接开失败 ⇒ 走降级，而不是顺手建一个空库。
 *   2. **优雅降级**：库缺失 / schema_version 不认识 / 任何读失败 ⇒ 返回空结果 +
 *      一条 WARN（每种失败签名只报一次，不刷日志），**绝不抛到路由**——词条检索
 *      是背景面，接口不能因为它 500。
 *   3. **不持长连接**：每次查询开一条连接、`finally` 关掉；重活进 `IO.blocking`。
 *   4. **schema 只读不写**：列名照 CLI 落地的那份 DDL 读，本类不改一个列。
 *
 * 与图谱/索引腿的分工：`/api/wiki/graph` 与 `/api/wiki/term` 走**文档即事实源**的
 * 解析链（`WikiIndex` / `TermIndex`），本库是它的**派生物**——两者不互相同步、
 * 互不依赖；库没重建只是查不到新词条，不会让图谱写错。
 */
final class TermDb(val dbPath: os.Path):
  import TermDb.*

  // ── 查询面 ────────────────────────────────────────────────────────

  /**
   * 词条检索。
   *
   * @param q       子串（大小写不敏感；命中主名或任一别名，干净名口径）
   * @param project `#proj/<slug>` 标签过滤（接受 `stand` / `proj/stand` / `#proj/stand`）
   * @param topic   `#topic/<slug>` 标签过滤（接受 `term-index` / `#topic/term-index`）
   * @param doc     限定文档：vault 相对路径精确命中，否则按 basename / 子串
   * @param limit   返回条数上限（<=0 取默认，>MaxLimit 截到 MaxLimit）
   */
  def terms(
    q: Option[String] = None,
    project: Option[String] = None,
    topic: Option[String] = None,
    doc: Option[String] = None,
    limit: Int = DefaultLimit
  ): IO[TermQueryResult] =
    withDb("terms") { conn =>
      val (where, params) = filtersOf(q, project, topic, doc)
      val rows = selectRecords(conn, where, params, clampLimit(limit))
      TermQueryResult(available = true, total = count(conn, where, params), shown = rows.size, terms = rows)
    }.map(_.getOrElse(TermQueryResult.unavailable))

  /**
   * 精确名查询（主名优先，退到别名）——「词条名 → 原文档信息 + 标签 + 引用位置」。
   * 返回**全部**命中（跨文档同名不猜：库把候选摊开给调用方，裁定留给上层，
   * 与 `WikiIndex.selectTerm` 的分工一致）。
   */
  def termByName(name: String): IO[List[TermDetail]] =
    withDb("term") { conn =>
      val key = nameKey(name)
      if key.isEmpty then Nil
      else
        var rows = selectBy(conn, "t.name_key = ?", List(key), MaxLimit)
        if rows.isEmpty then
          rows = selectBy(
            conn,
            "EXISTS (SELECT 1 FROM term_aliases a WHERE a.term_id = t.id AND a.alias_key = ?)",
            List(key),
            MaxLimit
          )
        rows.map(r => TermDetail(r, refsOf(conn, r.id)))
    }.map(_.getOrElse(Nil))

  /** 库的观测面（与 CLI `stats` 同口径）。 */
  def stats: IO[Option[TermDbStats]] =
    withDb("stats") { conn =>
      val meta = metaOf(conn)
      val perProject =
        query2(conn, "SELECT project, count(*) AS n FROM documents GROUP BY project ORDER BY project", Nil) { rs =>
          (rs.getString("project"), rs.getInt("n"))
        }
      TermDbStats(
        dbPath = dbPath.toString,
        schemaVersion = meta.getOrElse(SchemaVersionKey, ""),
        lastRebuild = meta.getOrElse(LastRebuildKey, ""),
        documents = scalarInt(conn, "SELECT count(*) FROM documents"),
        terms = scalarInt(conn, "SELECT count(*) FROM terms"),
        aliases = scalarInt(conn, "SELECT count(*) FROM term_aliases"),
        tags = scalarInt(conn, "SELECT count(*) FROM term_tags"),
        refs = scalarInt(conn, "SELECT count(*) FROM term_refs"),
        refsResolved = scalarInt(conn, "SELECT count(*) FROM term_refs WHERE resolved = 1"),
        projects = perProject
      )
    }

  // ── 降级闸 + 连接管理 ─────────────────────────────────────────────

  /**
   * 开库 → 校验 schema → 跑 `f` → 关库。任何一步失败都返回 `None` 并留一条 WARN，
   * 调用方把 `None` 映射成「空结果」。**这是唯一的开库路径**（别在别处
   * `DriverManager.getConnection`，否则降级会漏）。
   */
  private def withDb[A](label: String)(f: Connection => A): IO[Option[A]] =
    IO.blocking {
      val path = dbPath
      if !os.exists(path) then
        degrade(s"$label|missing|$path", s"库不存在（$path）——先跑 tools/stand-terms rebuild")
        None
      else
        var conn: Connection = null
        try
          conn = open(path)
          val version = metaOf(conn).getOrElse(SchemaVersionKey, "")
          if !SupportedSchemas.contains(version) then
            degrade(
              s"$label|schema|$path|$version",
              s"schema_version='$version' 不在已知集合 ${SupportedSchemas.toList.sorted.mkString("/")}（$path）"
            )
            None
          else Some(f(conn))
        catch
          case NonFatal(e) =>
            degrade(
              s"$label|error|$path|${e.getClass.getSimpleName}",
              s"读库失败（$path）：${e.getClass.getSimpleName}: ${msgOf(e)}"
            )
            None
        finally
          if conn != null then
            try conn.close()
            catch case NonFatal(_) => ()
        end try
      end if
    }

  private def open(path: os.Path): Connection =
    val cfg = new org.sqlite.SQLiteConfig()
    cfg.setReadOnly(true) // open_mode = READONLY：缺库即失败，绝不建库
    DriverManager.getConnection("jdbc:sqlite:" + path.toString, cfg.toProperties)

  /** 每种失败签名只 WARN 一次（省日志）；签名含 label + 原因，别把不同故障揉一起。 */
  private def degrade(signature: String, msg: String): Unit =
    if warnOnce.putIfAbsent(signature, java.lang.Boolean.TRUE) == null then
      logger.warnSync(s"wiki.terms: $msg — 降级为空结果（本签名后续不再重复告警）")

  // ── SQL 面 ───────────────────────────────────────────────────────

  private def filtersOf(
    q: Option[String],
    project: Option[String],
    topic: Option[String],
    doc: Option[String]
  ): (List[String], List[Any]) =
    val where = ListBuffer.empty[String]
    val params = ListBuffer.empty[Any]
    q.map(_.trim).filter(_.nonEmpty).foreach { raw =>
      val needle = "%" + likeEscape(nameKey(raw)) + "%"
      where += ("((t.name_key LIKE ? ESCAPE '\\') OR EXISTS (SELECT 1 FROM term_aliases a"
        + " WHERE a.term_id = t.id AND a.alias_key LIKE ? ESCAPE '\\'))")
      params += needle
      params += needle
    }
    project.map(v => tagValue(v, "project")).filter(_.nonEmpty).foreach { v =>
      where += "EXISTS (SELECT 1 FROM term_tags x WHERE x.term_id = t.id AND x.facet = 'project' AND x.value = ?)"
      params += v
    }
    topic.map(v => tagValue(v, "topic")).filter(_.nonEmpty).foreach { v =>
      where += "EXISTS (SELECT 1 FROM term_tags x WHERE x.term_id = t.id AND x.facet = 'topic' AND x.value = ?)"
      params += v
    }
    doc.map(normalizeRel).filter(_.nonEmpty).foreach { d =>
      // 精确 rel → 带路径分隔的 basename（`%/<d>`）→ 子串；三档都不引新语义。
      where += "(d.rel = ? OR d.rel LIKE ? ESCAPE '\\' OR d.rel LIKE ? ESCAPE '\\')"
      params += d
      params += ("%/" + likeEscape(d))
      params += ("%" + likeEscape(d) + "%")
    }
    (where.toList, params.toList)

  end filtersOf

  private def whereSql(where: List[String]): String =
    if where.isEmpty then "" else " WHERE " + where.mkString(" AND ")

  private def count(conn: Connection, where: List[String], params: List[Any]): Int =
    val sql = "SELECT count(*) FROM terms t JOIN documents d ON d.id = t.doc_id" + whereSql(where)
    val st = conn.prepareStatement(sql)
    try
      bind(st, params)
      val rs = st.executeQuery()
      if rs.next() then rs.getInt(1) else 0
    finally st.close()

  private def selectRecords(conn: Connection, where: List[String], params: List[Any], limit: Int): List[TermRecord] =
    val sql = BaseSelect + whereSql(where) + " ORDER BY t.name_key, d.project, d.rel, t.line LIMIT ?"
    hydrate(conn, sql, params, limit)

  private def selectBy(conn: Connection, predicate: String, params: List[Any], limit: Int): List[TermRecord] =
    hydrate(conn, BaseSelect + " WHERE " + predicate + " ORDER BY d.project, d.rel, t.line LIMIT ?", params, limit)

  /** 一次主查询 + 一次别名批量 + 一次标签批量（N+1 免疫），行序照 SQL 给。 */
  private def hydrate(conn: Connection, sql: String, params: List[Any], limit: Int): List[TermRecord] =
    val st = conn.prepareStatement(sql)
    try
      bind(st, params)
      st.setInt(params.size + 1, limit)
      val rs = st.executeQuery()
      val raw = ListBuffer.empty[TermRecord]
      while rs.next() do raw += recordOf(rs)
      val rows = raw.toList
      val ids = rows.map(_.id)
      val aliases = aliasesOf(conn, ids)
      val tags = tagsOf(conn, ids)
      rows.map(r =>
        r.copy(
          aliases = aliases.getOrElse(r.id, Nil),
          tags = tags.getOrElse(r.id, TermTagBuckets.Empty)
        )
      )
    finally st.close()

    end try

  end hydrate

  private def recordOf(rs: ResultSet): TermRecord =
    TermRecord(
      id = rs.getLong("id"),
      name = rs.getString("name"),
      aliases = Nil,
      doc = TermDocMeta(
        project = rs.getString("project"),
        rel = rs.getString("rel"),
        absPath = Option(rs.getString("abs_path")).getOrElse(""),
        title = Option(rs.getString("title")).getOrElse(""),
        docType = Option(rs.getString("doc_type")).getOrElse(""),
        status = Option(rs.getString("status")).getOrElse("")
      ),
      anchor = rs.getString("anchor"),
      line = rs.getInt("line"),
      endLine = rs.getInt("end_line"),
      definition = Option(rs.getString("definition")).getOrElse(""),
      source = rs.getString("source"),
      tags = TermTagBuckets.Empty,
      refCount = rs.getInt("ref_count"),
      updatedAt = Option(rs.getString("updated_at")).getOrElse("")
    )

  private def aliasesOf(conn: Connection, ids: List[Long]): Map[Long, List[String]] =
    if ids.isEmpty then Map.empty
    else
      val out = scala.collection.mutable.LinkedHashMap.empty[Long, ListBuffer[String]]
      query2(
        conn,
        s"SELECT term_id, alias FROM term_aliases WHERE term_id IN (${ids.mkString(",")}) ORDER BY term_id, alias",
        Nil
      ) { rs =>
        out.getOrElseUpdate(rs.getLong("term_id"), ListBuffer.empty) += rs.getString("alias")
      }
      out.map { case (k, v) => k -> v.toList }.toMap

  private def tagsOf(conn: Connection, ids: List[Long]): Map[Long, TermTagBuckets] =
    if ids.isEmpty then Map.empty
    else
      val acc =
        scala.collection.mutable.LinkedHashMap.empty[Long, (ListBuffer[String], ListBuffer[String], ListBuffer[String])]
      query2(
        conn,
        s"SELECT term_id, facet, value FROM term_tags WHERE term_id IN (${ids.mkString(",")}) ORDER BY term_id, facet, value",
        Nil
      ) { rs =>
        val entry = acc.getOrElseUpdate(rs.getLong("term_id"), (ListBuffer.empty, ListBuffer.empty, ListBuffer.empty))
        rs.getString("facet") match
          case WikiIndex.TagFacetProject => entry._1 += rs.getString("value")
          case WikiIndex.TagFacetTopic => entry._2 += rs.getString("value")
          case _ => entry._3 += rs.getString("value")
      }
      acc.map { case (k, (p, t, e)) =>
        k -> TermTagBuckets(p.distinct.sorted.toList, t.distinct.sorted.toList, e.distinct.sorted.toList)
      }.toMap

  private def refsOf(conn: Connection, termId: Long): List[TermRefHit] =
    query2(
      conn,
      "SELECT from_rel, line, raw, resolved FROM term_refs WHERE term_id = ? ORDER BY from_rel, line, raw",
      List(termId)
    ) { rs =>
      TermRefHit(rs.getString("from_rel"), rs.getInt("line"), rs.getString("raw"), rs.getInt("resolved") == 1)
    }

  private def metaOf(conn: Connection): Map[String, String] =
    query2(conn, "SELECT key, value FROM meta", Nil) { rs =>
      rs.getString("key") -> rs.getString("value")
    }.toMap

  private def scalarInt(conn: Connection, sql: String): Int =
    val st = conn.prepareStatement(sql)
    try
      val rs = st.executeQuery()
      if rs.next() then rs.getInt(1) else 0
    finally st.close()

  private def query2[A](conn: Connection, sql: String, params: List[Any])(row: ResultSet => A): List[A] =
    val st = conn.prepareStatement(sql)
    try
      bind(st, params)
      val rs = st.executeQuery()
      val out = ListBuffer.empty[A]
      while rs.next() do out += row(rs)
      out.toList
    finally st.close()

  private def bind(st: PreparedStatement, params: List[Any]): Unit =
    var i = 1
    params.foreach { p =>
      p match
        case n: Int => st.setInt(i, n)
        case l: Long => st.setLong(i, l)
        case s: String => st.setString(i, s)
        case other => st.setObject(i, other)
      i += 1
    }

  private def msgOf(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

end TermDb

object TermDb:

  /** schema 大版本（与 `tools/stand-terms` 的 `SCHEMA_VERSION` 同一个数）。 */
  val SchemaVersionKey = "schema_version"
  val LastRebuildKey = "last_rebuild"
  val SupportedSchemas: Set[String] = Set("1")

  val DbDirName = "wiki-index"
  val DbFileName = "terms.db"

  /** 显式覆盖（隔离实例 / 冒烟 / 指向 CLI 建出来的临时库）：`NEBFLOW_TERMS_DB`。 */
  val EnvOverride = "NEBFLOW_TERMS_DB"

  val DefaultLimit: Int = 50
  val MaxLimit: Int = 500

  private val logger = NebflowLogger.forName("nebflow.wiki.terms")

  /** 进程级 WARN 去重（TermDb 按调用新建，去重表必须挂在伴生对象）。 */
  private val warnOnce = new ConcurrentHashMap[String, java.lang.Boolean]()

  /** 默认库位：与引擎其余派生数据同源（`PathUtil.dataRoot`），不自己拼 `~`。 */
  def defaultPath: os.Path = PathUtil.dataRoot / DbDirName / DbFileName

  /** 有效库路径：`NEBFLOW_TERMS_DB` 显式覆盖优先，否则数据根缺省位。 */
  def resolvePath: os.Path =
    sys.env
      .get(EnvOverride)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(p => os.Path(p, os.pwd))
      .getOrElse(defaultPath)

  /** 每次查询现解一次路径：`setDataRoot` 换根与 env 覆盖都即时生效。 */
  def current: TermDb = new TermDb(resolvePath)

  // ── 归一（与 tools/stand-terms 的 clean_name / name_key 同口径）────────

  /** 列表符号 / 标题符号前缀（`- `、`* `、`+ `、`# `）。 */
  private val ListBulletRe = raw"^[\s#*+\-]+".r

  /** 干净显示名：去列表符号、去包裹强调、空白折叠。 */
  def cleanName(raw: String): String =
    if raw == null then ""
    else
      val t = ListBulletRe.replaceFirstIn(raw.trim, "").trim
      t.stripPrefix("*").stripSuffix("*").trim.replaceAll(raw"\s+", " ")

  /** 查找键：干净名小写（= 精确判定口径，与 CLI `name_key` 逐字节相同）。 */
  def nameKey(raw: String): String = cleanName(raw).toLowerCase

  /** `#proj/stand` / `proj/stand` / `stand` 三写法等价。 */
  def tagValue(raw: String, namespace: String): String =
    var t = Option(raw).getOrElse("").trim
    while t.startsWith("#") do t = t.substring(1)
    val prefixes = namespace match
      case "project" => List("project/", "proj/")
      case "topic" => List("topic/")
      case other => List(other + "/")
    var changed = true
    while changed do
      changed = false
      prefixes.foreach { p =>
        if t.startsWith(p) then
          t = t.substring(p.length); changed = true
      }
    t.trim

  end tagValue

  private def normalizeRel(raw: String): String =
    var t = Option(raw).getOrElse("").trim.replace('\\', '/')
    while t.startsWith("./") do t = t.substring(2)
    t

  /** LIKE 元字符转义（配合 SQL 里的 `ESCAPE '\'`）。 */
  private def likeEscape(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  private def clampLimit(limit: Int): Int =
    if limit <= 0 then DefaultLimit else math.min(limit, MaxLimit)

  /** 主查询列面（**别改名**：`recordOf` 按列标签读）。 */
  private val BaseSelect =
    """SELECT t.id, t.name, t.anchor, t.line, t.end_line, t.definition, t.source, t.updated_at,
      |       d.project, d.rel, d.abs_path, d.title, d.doc_type, d.status,
      |       (SELECT count(*) FROM term_refs r WHERE r.term_id = t.id) AS ref_count
      |  FROM terms t JOIN documents d ON d.id = t.doc_id""".stripMargin
end TermDb

// ── 结果类型（JSON 面 = 路由的唯一输出形状）────────────────────────────

/** 词条的三桶标签，桶内已去重排序（与 `WikiIndex.termTagsJson` 同序）。 */
final case class TermTagBuckets(project: List[String], topic: List[String], extra: List[String]):
  def isEmpty: Boolean = project.isEmpty && topic.isEmpty && extra.isEmpty

  def asJson: Json = Json.obj(
    WikiIndex.TagFacetProject -> project.asJson,
    WikiIndex.TagFacetTopic -> topic.asJson,
    WikiIndex.TagFacetExtra -> extra.asJson
  )

object TermTagBuckets:
  val Empty: TermTagBuckets = TermTagBuckets(Nil, Nil, Nil)

/**
 * **原文档信息**（作者要的那一半：查到词条就带回它声明在哪份文档里）。
 */
final case class TermDocMeta(
  project: String,
  rel: String,
  absPath: String,
  title: String,
  docType: String,
  status: String
):

  def asJson: Json = Json.obj(
    "project" -> project.asJson,
    "rel" -> rel.asJson,
    "abs_path" -> absPath.asJson,
    "title" -> title.asJson,
    "doc_type" -> docType.asJson,
    "status" -> status.asJson
  )

end TermDocMeta

/** 一条词条（含它声明所在文档的元数据、三桶标签、被引用次数）。 */
final case class TermRecord(
  id: Long,
  name: String,
  aliases: List[String],
  doc: TermDocMeta,
  anchor: String,
  line: Int,
  endLine: Int,
  definition: String,
  source: String,
  tags: TermTagBuckets,
  refCount: Int,
  updatedAt: String
):
  def project: String = doc.project
  def rel: String = doc.rel

  def asJson: Json = Json.obj(
    "id" -> id.asJson,
    "name" -> name.asJson,
    "aliases" -> aliases.asJson,
    "project" -> doc.project.asJson,
    "rel" -> doc.rel.asJson,
    "anchor" -> anchor.asJson,
    "line" -> line.asJson,
    "end_line" -> endLine.asJson,
    "definition" -> definition.asJson,
    "source" -> source.asJson,
    "tags" -> tags.asJson,
    "ref_count" -> refCount.asJson,
    "updated_at" -> updatedAt.asJson,
    "doc" -> doc.asJson
  )

end TermRecord

/** 一处引用（谁在哪个文件哪一行引了这条词条）。 */
final case class TermRefHit(fromRel: String, line: Int, raw: String, resolved: Boolean):

  def asJson: Json = Json.obj(
    "from_rel" -> fromRel.asJson,
    "line" -> line.asJson,
    "raw" -> raw.asJson,
    "resolved" -> resolved.asJson
  )

/** 词条 + 它的全部引用位置（「按名点查」的完整面）。 */
final case class TermDetail(term: TermRecord, refs: List[TermRefHit]):
  def asJson: Json = term.asJson.deepMerge(Json.obj("refs" -> refs.map(_.asJson).asJson))

/** 一次检索的结果壳：`available=false` 即降级（库缺失 / schema 不认识 / 读失败）。 */
final case class TermQueryResult(available: Boolean, total: Int, shown: Int, terms: List[TermRecord]):

  def asJson: Json = Json.obj(
    "available" -> available.asJson,
    "count" -> total.asJson,
    "shown" -> shown.asJson,
    "terms" -> terms.map(_.asJson).asJson
  )

object TermQueryResult:
  val unavailable: TermQueryResult = TermQueryResult(available = false, total = 0, shown = 0, terms = Nil)

/** 库的汇总面（文档/词条/别名/标签/引用 + 每项目文档数）。 */
final case class TermDbStats(
  dbPath: String,
  schemaVersion: String,
  lastRebuild: String,
  documents: Int,
  terms: Int,
  aliases: Int,
  tags: Int,
  refs: Int,
  refsResolved: Int,
  projects: List[(String, Int)]
):

  def asJson: Json = Json.obj(
    "db" -> dbPath.asJson,
    "schema_version" -> schemaVersion.asJson,
    "last_rebuild" -> lastRebuild.asJson,
    "documents" -> documents.asJson,
    "terms" -> terms.asJson,
    "aliases" -> aliases.asJson,
    "tags" -> tags.asJson,
    "refs" -> refs.asJson,
    "refs_resolved" -> refsResolved.asJson,
    "projects" -> projects.map { case (p, n) => Json.obj("project" -> p.asJson, "documents" -> n.asJson) }.asJson
  )
end TermDbStats
