package nebflow.core.wiki

import java.sql.{Connection, DriverManager}
import scala.util.Using

/**
 * 测试用的词条索引库构造器（**只读侧 spec 的输入**）。
 *
 * 库是 CLI（`tools/stand-terms`）的产物，引擎侧不写它——所以这里的 DDL 是
 * **照抄写侧 schema 的测试替身**（列名逐字相同，改列名要两边一起改，`TermDb`
 * 的 `recordOf` 按列标签读）。刻意不 import CLI：它是 python 脚本，测试要零外部
 * 依赖（不跑 python3、不依赖插件脚本路径）。
 *
 * 数据形状 = 作者那句话的验收面：两条文档（glossary + 普通 doc）、三条词条
 * （多标签 / 别名 / 引用位置），供各查询面断言。
 */
object TermFixtureDb:

  /** 与 `tools/stand-terms` 的 `SCHEMA_SQL` 同形（视图略去：读侧不依赖它）。 */
  private val Ddl: List[String] = List(
    "PRAGMA foreign_keys = ON",
    """CREATE TABLE IF NOT EXISTS meta (
      |    key   TEXT PRIMARY KEY,
      |    value TEXT NOT NULL DEFAULT ''
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS documents (
      |    id         INTEGER PRIMARY KEY,
      |    project    TEXT NOT NULL,
      |    rel        TEXT NOT NULL,
      |    abs_path   TEXT NOT NULL DEFAULT '',
      |    title      TEXT NOT NULL DEFAULT '',
      |    doc_type   TEXT NOT NULL DEFAULT '',
      |    status     TEXT NOT NULL DEFAULT '',
      |    mtime      INTEGER NOT NULL DEFAULT 0,
      |    size       INTEGER NOT NULL DEFAULT 0,
      |    term_count INTEGER NOT NULL DEFAULT 0,
      |    scanned_at TEXT NOT NULL DEFAULT '',
      |    UNIQUE(project, rel)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS terms (
      |    id         INTEGER PRIMARY KEY,
      |    doc_id     INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
      |    name       TEXT NOT NULL,
      |    name_key   TEXT NOT NULL,
      |    anchor     TEXT NOT NULL,
      |    line       INTEGER NOT NULL,
      |    end_line   INTEGER NOT NULL,
      |    definition TEXT NOT NULL DEFAULT '',
      |    source     TEXT NOT NULL,
      |    raw_name   TEXT NOT NULL DEFAULT '',
      |    updated_at TEXT NOT NULL DEFAULT '',
      |    UNIQUE(doc_id, anchor)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS term_aliases (
      |    term_id   INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
      |    alias     TEXT NOT NULL,
      |    alias_key TEXT NOT NULL,
      |    PRIMARY KEY(term_id, alias_key)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS term_tags (
      |    term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
      |    facet   TEXT NOT NULL,
      |    value   TEXT NOT NULL,
      |    PRIMARY KEY(term_id, facet, value)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS term_refs (
      |    id          INTEGER PRIMARY KEY,
      |    term_id     INTEGER REFERENCES terms(id) ON DELETE CASCADE,
      |    from_doc_id INTEGER REFERENCES documents(id) ON DELETE CASCADE,
      |    from_rel    TEXT NOT NULL DEFAULT '',
      |    line        INTEGER NOT NULL DEFAULT 0,
      |    raw         TEXT NOT NULL DEFAULT '',
      |    resolved    INTEGER NOT NULL DEFAULT 0
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_terms_key ON terms(name_key)",
    "CREATE INDEX IF NOT EXISTS idx_tags      ON term_tags(facet, value)",
    "CREATE INDEX IF NOT EXISTS idx_refs_term ON term_refs(term_id)",
    "CREATE INDEX IF NOT EXISTS idx_docs_rel  ON documents(project, rel)"
  )

  /** 三条词条 + 两条文档 + 两处引用：`stand` 项目里的词条索引样例。 */
  def create(path: os.Path, schemaVersion: String = "1", lastRebuild: String = "2026-11-05T12:00:00"): Unit =
    os.makeDir.all(path / os.up)
    Using.resource(DriverManager.getConnection("jdbc:sqlite:" + path.toString)) { conn =>
      Ddl.foreach(sql => exec(conn, sql))
      insert(conn, schemaVersion, lastRebuild)
    }

  private def exec(conn: Connection, sql: String): Unit =
    Using.resource(conn.createStatement())(_.execute(sql))

  private def insert(conn: Connection, schemaVersion: String, lastRebuild: String): Unit =
    Using.resource(conn.prepareStatement("INSERT INTO meta (key, value) VALUES (?, ?)")) { ps =>
      List(
        "schema_version" -> schemaVersion,
        "last_rebuild" -> lastRebuild,
        "rebuild_source" -> "spec-fixture"
      ).foreach { case (k, v) =>
        ps.setString(1, k)
        ps.setString(2, v)
        ps.addBatch()
      }
      ps.executeBatch()
    }

    Using.resource(conn.prepareStatement(
      "INSERT INTO documents (id, project, rel, abs_path, title, doc_type, status, mtime, size, term_count, scanned_at)"
        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) { ps =>
      List(
        (1L, "stand", "glossary.md", "/vault/stand/.stand/docs/glossary.md", "词汇表", "glossary", "current", 2),
        (2L, "stand", "index.md", "/vault/stand/.stand/docs/index.md", "索引说明", "note", "current", 1)
      ).foreach { case (id, project, rel, abs, title, dtype, status, n) =>
        ps.setLong(1, id); ps.setString(2, project); ps.setString(3, rel); ps.setString(4, abs)
        ps.setString(5, title); ps.setString(6, dtype); ps.setString(7, status)
        ps.setLong(8, 1762345678000L); ps.setLong(9, 512L); ps.setInt(10, n)
        ps.setString(11, lastRebuild)
        ps.addBatch()
      }
      ps.executeBatch()
    }

    Using.resource(conn.prepareStatement(
      "INSERT INTO terms (id, doc_id, name, name_key, anchor, line, end_line, definition, source, raw_name, updated_at)"
        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) { ps =>
      val rows = List(
        (1L, 1L, "词条索引库（term index db）", "词条索引库（term index db）", "#^term-index-db", 9,
          "词条抽取后的可查询派生物。", "glossary"),
        (2L, 1L, "图谱投影（graph projection）", "图谱投影（graph projection）", "#^term-graph-projection", 11,
          "图谱视图里的节点与边。", "glossary"),
        (3L, 2L, "查询面（query face）", "查询面（query face）", "#^term-query-face", 12,
          "对外暴露的词条查询接口。", "doc")
      )
      rows.foreach { case (id, docId, name, key, anchor, line, defn, source) =>
        ps.setLong(1, id); ps.setLong(2, docId); ps.setString(3, name); ps.setString(4, key)
        ps.setString(5, anchor); ps.setInt(6, line); ps.setInt(7, line)
        ps.setString(8, defn); ps.setString(9, source); ps.setString(10, name)
        ps.setString(11, lastRebuild)
        ps.addBatch()
      }
      ps.executeBatch()
    }

    Using.resource(conn.prepareStatement(
      "INSERT INTO term_aliases (term_id, alias, alias_key) VALUES (?,?,?)")) { ps =>
      List(
        (1L, "词条索引库"), (1L, "term index db"),
        (2L, "图谱投影"), (2L, "graph projection"),
        (3L, "查询面"), (3L, "query face")
      ).foreach { case (id, alias) =>
        ps.setLong(1, id); ps.setString(2, alias); ps.setString(3, alias.toLowerCase)
        ps.addBatch()
      }
      ps.executeBatch()
    }

    Using.resource(conn.prepareStatement(
      "INSERT INTO term_tags (term_id, facet, value) VALUES (?,?,?)")) { ps =>
      List(
        (1L, "project", "stand"), (1L, "topic", "term-index"),
        (2L, "project", "stand"), (2L, "topic", "graph"), (2L, "topic", "term-index"),
        (2L, "extra", "ctx/engine"),
        (3L, "project", "stand"), (3L, "topic", "term-index"), (3L, "extra", "ctx/http")
      ).foreach { case (id, facet, value) =>
        ps.setLong(1, id); ps.setString(2, facet); ps.setString(3, value)
        ps.addBatch()
      }
      ps.executeBatch()
    }

    Using.resource(conn.prepareStatement(
      "INSERT INTO term_refs (id, term_id, from_doc_id, from_rel, line, raw, resolved) VALUES (?,?,?,?,?,?,?)")) { ps =>
      List(
        (1L, 1L, 2L, "index.md", 8, "词条索引库", 1),
        (2L, 2L, 2L, "index.md", 8, "图谱投影", 0)
      ).foreach { case (id, termId, fromDoc, rel, line, raw, resolved) =>
        ps.setLong(1, id); ps.setLong(2, termId); ps.setLong(3, fromDoc)
        ps.setString(4, rel); ps.setInt(5, line); ps.setString(6, raw); ps.setInt(7, resolved)
        ps.addBatch()
      }
      ps.executeBatch()
    }
