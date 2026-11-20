package nebflow.gateway.rest

import nebflow.gateway.*

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.wiki.TermFixtureDb
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization

import scala.concurrent.duration.*

/**
 * 词条索引库**路由面**的最小断言（term-system v2 · ②）。
 *
 * 路由是背景面，钉三条：
 *   1. **鉴权纪律照邻居**：无令牌 ⇒ 403 + 业务体零执行；
 *   2. **降级不 500**：库缺失 ⇒ 200 + `available:false` + 空列表（`TermDb` 的
 *      降级契约在 wire 上可见），库就位后同一路由立刻返回真实结果（含**原文档
 *      信息**与三桶 tags）；
 *   3. **单查/图谱面的 tags 不依赖库**：`GET /wiki/term` 与 `/wiki/graph` 的
 *      `tags` 来自 `WikiTerm.tags`（文档即事实源）——这是作者那条「库里没有
 *      也要有」的落点。
 *
 * 依赖注入按邻居先例（`PerceptionRoutesAuthGateSpec`）：只构造路由表，null 掉
 * 词条域不触的依赖。数据根在套件内换成临时目录（`Test / parallelExecution := false`，
 * 与 `WikiServiceSpec` 同法），因此 `TermDb.current` 的缺省位 = 临时数据根。
 *
 * 套件内测试**按声明序执行**（munit 顺序语义）：先证明「无库」，再造库，其后
 * 各条查真数据。
 */
class WikiTermsRoutesSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val TestToken = "test-token-wiki-terms"
  private val tempRoot: os.Path = os.pwd / "target" / "test-wiki-terms"
  private val originalRoot = PathUtil.dataRoot

  private def bearer(req: Request[IO]): Request[IO] =
    req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, TestToken)))

  private def mkRoutes(): RestApiRoutes =
    PathUtil.setDataRoot(tempRoot)
    new RestApiRoutes(
      tokenRef = cats.effect.Ref.unsafe[IO, String](TestToken),
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = None,
      ttsService = None
    )

  /** 路径形态 = Router 相对（生产挂载 `Router("/api" -> restApiRoutes.routes)`）。 */
  private def uri(path: String): Uri = Uri.unsafeFromString(path)

  private def call(path: String, auth: Boolean = true): IO[(Status, Json)] =
    val req0 = Request[IO](Method.GET, uri(path))
    val req = if auth then bearer(req0) else req0
    mkRoutes().routes(req).value.map(_.getOrElse(fail(s"route fell through: $path"))).flatMap { resp =>
      resp.as[Json].map(body => (resp.status, body))
    }

  private def str(j: Json, field: String): Option[String] =
    j.hcursor.downField(field).as[String].toOption

  private def lst[T](j: Json, field: String)(using d: io.circe.Decoder[T]): List[T] =
    j.hcursor.downField(field).as[List[T]].toOption.getOrElse(Nil)

  override def beforeAll(): Unit =
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "projects")
    // 一个本地工程 + 根 vault（`GET /wiki/term` 的索引腿要有得扫）
    val ws = tempRoot / "termsroute-ws"
    os.makeDir.all(ws / "docs")
    os.write.over(
      ws / "docs" / "glossary.md",
      """---
        |type: glossary
        |status: current
        |---
        |
        |# 词汇表
        |
        |GCR（银河宇宙线） — 来自银河系的高能粒子流。 #proj/stand #topic/term-index #ctx/engine ^gcr
        |""".stripMargin
    )
    val name = "termsroute"
    os.makeDir.all(tempRoot / "projects" / name)
    val w = ws.toString.replace("\\", "\\\\")
    os.write.over(
      tempRoot / "projects" / name / "project.json",
      s"""{"name":"$name","workspace":"$w","agentFile":"$w/AGENTS.md","createdAt":1}"""
    )
    PathUtil.setDataRoot(tempRoot)
  end beforeAll

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  // ── 1. 鉴权纪律 ──────────────────────────────────────────────────

  test("WT-1 无令牌 ⇒ 403「Unauthorized」（业务体零执行）") {
    call("/wiki/terms", auth = false).map { case (status, body) =>
      assertEquals(status, Status.Forbidden)
      assertEquals(str(body, "error"), Some("Unauthorized"))
    }
  }

  // ── 2. 降级不 500 ───────────────────────────────────────────────

  test("WT-2 库缺失 ⇒ 200 + available:false + 空列表（降级在 wire 上可见）") {
    val dbPath = tempRoot / "wiki-index" / "terms.db"
    assert(!os.exists(dbPath), "本套件此前的测试不得留下库文件")
    call("/wiki/terms?q=zzz").map { case (status, body) =>
      assertEquals(status, Status.Ok, "库缺失绝不能让词条检索 500")
      assertEquals(body.hcursor.get[Boolean]("available").toOption, Some(false))
      assertEquals(body.hcursor.get[Int]("count").toOption, Some(0))
      assertEquals(lst[Json](body, "terms"), Nil)
      assertEquals(str(body, "db"), Some(dbPath.toString), "缺省位 = 数据根下的 wiki-index/terms.db")
    }
  }

  test("WT-3 库就位 ⇒ 同一路由立刻返回词条 + 原文档信息 + 三桶 tags") {
    val dbPath = tempRoot / "wiki-index" / "terms.db"
    TermFixtureDb.create(dbPath)
    call("/wiki/terms?topic=%23topic/graph").map { case (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body.hcursor.get[Boolean]("available").toOption, Some(true))
      assertEquals(body.hcursor.get[Int]("count").toOption, Some(1), "#topic/graph 只命中多标签那条")
      val term = lst[Json](body, "terms").head
      assertEquals(str(term, "name"), Some("图谱投影（graph projection）"))
      assertEquals(str(term, "anchor"), Some("#^term-graph-projection"))
      assertEquals(
        term.hcursor.downField("tags").downField("topic").as[List[String]].toOption,
        Some(List("graph", "term-index"))
      )
      assertEquals(
        term.hcursor.downField("tags").downField("extra").as[List[String]].toOption,
        Some(List("ctx/engine"))
      )
      assertEquals(term.hcursor.get[Int]("ref_count").toOption, Some(1))
      val doc = term.hcursor.downField("doc")
      assertEquals(doc.downField("project").as[String].toOption, Some("stand"))
      assertEquals(doc.downField("rel").as[String].toOption, Some("glossary.md"))
      assertEquals(doc.downField("title").as[String].toOption, Some("词汇表"))
      assertEquals(doc.downField("doc_type").as[String].toOption, Some("glossary"))
      assertEquals(doc.downField("status").as[String].toOption, Some("current"))
      assert(doc.downField("abs_path").as[String].toOption.exists(_.endsWith("glossary.md")))
    }
  }

  test("WT-4 筛选参数原样回显 + limit 生效（一眼看出查的是什么）") {
    call("/wiki/terms?q=query&project=stand&limit=1").map { case (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body.hcursor.get[Int]("count").toOption, Some(1), "q=query 命中 doc 腿那条（别名 query face）")
      assertEquals(body.hcursor.get[Int]("shown").toOption, Some(1))
      val filters = body.hcursor.downField("filters")
      assertEquals(filters.downField("q").as[String].toOption, Some("query"))
      assertEquals(filters.downField("project").as[String].toOption, Some("stand"))
      assertEquals(filters.downField("limit").as[Int].toOption, Some(1))
      assertEquals(lst[Json](body, "terms").headOption.flatMap(t => str(t, "rel")), Some("index.md"))
    } *> call("/wiki/terms?limit=1").map { case (_, body) =>
      assertEquals(body.hcursor.get[Int]("count").toOption, Some(3), "total 是全集")
      assertEquals(body.hcursor.get[Int]("shown").toOption, Some(1), "shown 受 limit 截断")
    }
  }

  test("WT-5 按名点查：命中带 refs；库可用但没这个名 ⇒ 404；缺 name ⇒ 400") {
    call("/wiki/terms/lookup?name=graph%20projection").map { case (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body.hcursor.get[Int]("count").toOption, Some(1))
      val hit = lst[Json](body, "results").head
      assertEquals(str(hit, "name"), Some("图谱投影（graph projection）"))
      assertEquals(lst[Json](hit, "refs").map(r => str(r, "from_rel")), List(Some("index.md")), "别名命中也要带回引用位置")
    } *> call("/wiki/terms/lookup?name=%E6%B2%A1%E8%BF%99%E4%B8%AA").map { case (status, body) =>
      assertEquals(status, Status.NotFound)
      assertEquals(body.hcursor.get[Boolean]("available").toOption, Some(true), "库可用才敢报 404")
    } *> call("/wiki/terms/lookup").map { case (status, body) =>
      assertEquals(status, Status.BadRequest)
      assertEquals(str(body, "error"), Some("name is required"))
    }
  }

  test("WT-6 stats 面与库同口径") {
    call("/wiki/terms/stats").map { case (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body.hcursor.get[Int]("documents").toOption, Some(2))
      assertEquals(body.hcursor.get[Int]("terms").toOption, Some(3))
      assertEquals(str(body, "schema_version"), Some("1"))
      assertEquals(
        lst[Json](body, "projects")
          .map(p => (str(p, "project"), p.hcursor.get[Int]("documents").toOption)),
        List((Some("stand"), Some(2)))
      )
    }
  }

  // ── 3. 单查/图谱面的 tags 不依赖库 ───────────────────────────────

  test("WT-7 GET /wiki/term 的 term.tags 来自 WikiTerm.tags（与库无关的那条腿）") {
    call("/wiki/term?name=GCR&project=termsroute").map { case (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body.hcursor.get[Boolean]("resolved").toOption, Some(true))
      val term: Json = body.hcursor.downField("term").as[Json].toOption.getOrElse(Json.Null)
      assertEquals(str(term, "name"), Some("GCR（银河宇宙线）"))
      assertEquals(term.hcursor.downField("tags").downField("project").as[List[String]].toOption, Some(List("stand")))
      assertEquals(
        term.hcursor.downField("tags").downField("topic").as[List[String]].toOption,
        Some(List("term-index"))
      )
      assertEquals(
        term.hcursor.downField("tags").downField("extra").as[List[String]].toOption,
        Some(List("ctx/engine"))
      )
    }
  }

  test("WT-8 图谱投影 nodes[].terms[] 带三桶 tags（termJson 单一出口）") {
    call("/wiki/graph?project=termsroute").map { case (status, body) =>
      assertEquals(status, Status.Ok)
      val nodes = lst[Json](body, "nodes")
      val real = nodes.filterNot(n => n.hcursor.get[Boolean]("stub").toOption.contains(true))
      val terms = real.flatMap(n => lst[Json](n, "terms"))
      assertEquals(terms.flatMap(t => str(t, "name")), List("GCR（银河宇宙线）"))
      val tags = terms.head.hcursor.downField("tags")
      assertEquals(tags.downField("project").as[List[String]].toOption, Some(List("stand")))
      assertEquals(tags.downField("topic").as[List[String]].toOption, Some(List("term-index")))
      assertEquals(tags.downField("extra").as[List[String]].toOption, Some(List("ctx/engine")))
    }
  }
end WikiTermsRoutesSpec
