package nebflow.core.wiki

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * 词条索引库**读侧**（`TermDb`）的行为规范（term-system v2 · ②）。
 *
 * 钉的是「引擎读侧」的四条承诺，任何一条破了都是背景面变故障源：
 *   1. **优雅降级**：库不存在 / schema 不认识 ⇒ 空结果 + 不抛（路由因此永不 500），
 *      且**绝不顺手建库**（只读打开，缺库即失败）；
 *   2. **按名点查带回原文档信息**：作者原话「查询词条的时候是会返回其原文档的
 *      信息的」——project / rel / title / doc_type / status / abs_path 六件套；
 *   3. **各查询面**：子串（主名与别名双命中）、项目/主题标签过滤、文档过滤、
 *      limit 截断与 total 不受截断影响、别名回退、引用位置；
 *   4. **观测面**：stats 与 CLI `stats` 同口径。
 *
 * 库用 [[TermFixtureDb]] 在临时目录里现造（不跑 python、不依赖 CLI，零外部依赖）；
 * 「缺库」用同目录里不存在的路径证明，并断言事后目录仍然没有那个文件。
 */
class TermDbSpec extends FunSuite:

  private def tempDb(): os.Path =
    os.temp.dir(prefix = "termdb-spec") / "terms.db"

  private def missingDb(): os.Path =
    os.temp.dir(prefix = "termdb-spec-missing") / "nested" / "terms.db"

  private def db(path: os.Path): TermDb = new TermDb(path)

  // ── 1. 降级闸 ─────────────────────────────────────────────────────

  test("TD-1 库不存在 ⇒ 空结果 + 不抛 + 不建库（路由不会因此 500）") {
    val path = missingDb()
    val d = db(path)
    val res = d.terms().unsafeRunSync()
    assertEquals(res.available, false, "降级必须显式可见（available=false），不能假装查到了 0 条")
    assertEquals(res.total, 0)
    assertEquals(res.terms, Nil)
    assertEquals(d.termByName("任意词条").unsafeRunSync(), Nil)
    assertEquals(d.stats.unsafeRunSync(), None)
    assert(!os.exists(path), "只读打开：缺库绝不能顺手建出一个空库")
  }

  test("TD-2 schema_version 不认识 ⇒ 降级（不猜列、不半读）") {
    val path = tempDb()
    TermFixtureDb.create(path, schemaVersion = "999")
    val res = db(path).terms().unsafeRunSync()
    assertEquals(res.available, false)
    assertEquals(res.terms, Nil)
    assertEquals(db(path).stats.unsafeRunSync(), None)
  }

  // ── 2. 「词条名 → 原文档信息」 ─────────────────────────────────────

  test("TD-3 检索结果带原文档信息 + 三桶标签 + ref_count + updated_at") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val res = db(path).terms().unsafeRunSync()
    assertEquals(res.available, true)
    assertEquals(res.total, 3)
    assertEquals(
      res.terms.map(_.name),
      List("图谱投影（graph projection）", "查询面（query face）", "词条索引库（term index db）"),
      "顺序 = name_key 的 BINARY 序（与 CLI list 同序）"
    )
    val idx = res.terms.find(_.name.startsWith("词条索引库")).get
    assertEquals(idx.doc.project, "stand")
    assertEquals(idx.doc.rel, "glossary.md")
    assertEquals(idx.doc.title, "词汇表")
    assertEquals(idx.doc.docType, "glossary")
    assertEquals(idx.doc.status, "current")
    assertEquals(idx.doc.absPath, "/vault/stand/.stand/docs/glossary.md", "绝对路径要能直接打开")
    assertEquals(idx.anchor, "#^term-index-db")
    assertEquals(idx.source, "glossary")
    assertEquals(idx.tags.project, List("stand"))
    assertEquals(idx.tags.topic, List("term-index"))
    assertEquals(idx.tags.extra, Nil)
    assertEquals(idx.refCount, 1)
    assertEquals(idx.updatedAt, "2026-11-05T12:00:00")
  }

  // ── 3. 各查询面 ──────────────────────────────────────────────────

  test("TD-4 按名点查：主名命中，别名回退命中（含 refs）") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val d = db(path)

    val byName = d.termByName("词条索引库（term index db）").unsafeRunSync()
    assertEquals(byName.map(_.term.name), List("词条索引库（term index db）"))
    assertEquals(byName.head.term.aliases, List("term index db", "词条索引库"), "别名面（库里 term_aliases 全量）")
    assertEquals(
      byName.head.refs.map(r => (r.fromRel, r.line, r.raw, r.resolved)),
      List(("index.md", 8, "词条索引库", true))
    )

    val byAlias = d.termByName("graph projection").unsafeRunSync()
    assertEquals(byAlias.map(_.term.name), List("图谱投影（graph projection）"), "别名精确命中要回退到词条")
    assertEquals(byAlias.head.term.tags.topic, List("graph", "term-index"), "桶内去重排序")
    assertEquals(byAlias.head.term.tags.extra, List("ctx/engine"))
    assertEquals(byAlias.head.refs.map(_.resolved), List(false), "库里 resolved 是抽取出口的判定原文，读侧照传不改写")

    assertEquals(d.termByName("  GRAPH PROJECTION ").unsafeRunSync().size, 1, "查找键大小写与首尾空白不敏感")
    assertEquals(d.termByName("没这个").unsafeRunSync(), Nil)
  }

  test("TD-5 标签过滤：project / topic 命中，三种写法等价，无命中即空") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val d = db(path)

    assertEquals(d.terms(project = Some("stand")).unsafeRunSync().total, 3)
    List("stand", "proj/stand", "#proj/stand", "project/stand").foreach { form =>
      assertEquals(d.terms(project = Some(form)).unsafeRunSync().total, 3, s"--project 形态 '$form' 应等价")
    }
    assertEquals(d.terms(topic = Some("term-index")).unsafeRunSync().total, 3)
    assertEquals(d.terms(topic = Some("#topic/graph")).unsafeRunSync().total, 1)
    assertEquals(d.terms(topic = Some("nonexistent")).unsafeRunSync().total, 0)
    assertEquals(
      d.terms(project = Some("stand"), topic = Some("graph")).unsafeRunSync().terms.map(_.name),
      List("图谱投影（graph projection）"),
      "两个标签是 AND"
    )
  }

  test("TD-6 子串检索：主名与别名双命中，标签过滤可叠加") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val d = db(path)

    assertEquals(d.terms(q = Some("索引")).unsafeRunSync().total, 1)
    assertEquals(
      d.terms(q = Some("query")).unsafeRunSync().terms.map(_.name),
      List("查询面（query face）"),
      "别名子串也要命中（q 不是只有主名）"
    )
    assertEquals(d.terms(q = Some("TERM INDEX")).unsafeRunSync().total, 1, "大小写不敏感")
    assertEquals(d.terms(q = Some("投影"), topic = Some("graph")).unsafeRunSync().total, 1)
    assertEquals(d.terms(q = Some("索引"), topic = Some("graph")).unsafeRunSync().total, 0, "交集为空")
    assertEquals(d.terms(q = Some("%")).unsafeRunSync().total, 0, "LIKE 元字符被转义，不当作通配")
  }

  test("TD-7 文档过滤：精确 rel / basename / 子串三档") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val d = db(path)

    assertEquals(d.terms(doc = Some("glossary.md")).unsafeRunSync().total, 2)
    assertEquals(d.terms(doc = Some("index.md")).unsafeRunSync().terms.map(_.name), List("查询面（query face）"))
    assertEquals(d.terms(doc = Some("gloss")).unsafeRunSync().total, 2, "子串")
    assertEquals(d.terms(doc = Some("nope.md")).unsafeRunSync().total, 0)
  }

  test("TD-8 limit：截断 shown 但 total 是全集（分页不撒谎）") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val d = db(path)

    val one = d.terms(limit = 1).unsafeRunSync()
    assertEquals(one.shown, 1)
    assertEquals(one.total, 3, "total 必须是过滤后的全集，不受 limit 影响")
    assertEquals(d.terms(limit = 0).unsafeRunSync().shown, 3, "limit<=0 = 取默认上限")
    assertEquals(d.terms(limit = 100000).unsafeRunSync().shown, 3, "超上限截到 MaxLimit，不报错")
  }

  // ── 4. 观测面 ────────────────────────────────────────────────────

  test("TD-9 stats 与 CLI 同口径（文档/词条/别名/标签/引用 + 每项目文档数）") {
    val path = tempDb()
    TermFixtureDb.create(path)
    val s = db(path).stats.unsafeRunSync().get
    assertEquals(s.schemaVersion, "1")
    assertEquals(s.lastRebuild, "2026-11-05T12:00:00")
    assertEquals(s.documents, 2)
    assertEquals(s.terms, 3)
    assertEquals(s.aliases, 6)
    assertEquals(s.tags, 9)
    assertEquals(s.refs, 2)
    assertEquals(s.refsResolved, 1)
    assertEquals(s.projects, List(("stand", 2)))
    assertEquals(s.dbPath, path.toString)
  }

  test("TD-10 归一助手与 CLI clean_name / name_key 同口径") {
    assertEquals(TermDb.cleanName("- 词条索引库（term index db）"), "词条索引库（term index db）")
    assertEquals(TermDb.cleanName("* 强调词条 *"), "强调词条")
    assertEquals(TermDb.cleanName("  多   空白  "), "多 空白")
    assertEquals(TermDb.nameKey("  GCR  "), "gcr")
    assertEquals(TermDb.tagValue("#proj/stand", "project"), "stand")
    assertEquals(TermDb.tagValue("proj/stand", "project"), "stand")
    assertEquals(TermDb.tagValue("stand", "project"), "stand")
    assertEquals(TermDb.tagValue("#topic/term-index", "topic"), "term-index")
    assertEquals(TermDb.SupportedSchemas, Set("1"))
  }
end TermDbSpec
