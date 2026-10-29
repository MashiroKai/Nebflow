package nebflow.core.wiki

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * #744-B multi-vault service spec — two fixture projects on a temp data root:
 *
 *   alpha  → docs/{a.md,b.md} + sub-vault docs/sub/docs/child.md
 *            a.md links [[b]] (in-vault) and [[sub/docs/child.md]] (cross-vault);
 *            child.md links [[a.md]] (cross-vault back to the root vault).
 *   beta   → no docs/ vault at all (missing-vault face).
 *
 * Pins: per-vault isolation (parent scans never absorb child docs), the vault
 * manifest, drill-in breadcrumbs, external-vs-broken classification, the
 * merged view's byte-compatible shape, and the `__user__` C-batch placeholder.
 */
class WikiServiceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-wiki-service"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "projects")

  private val alphaWs = tempRoot / "alpha-ws"
  private val betaWs = tempRoot / "beta-ws"

  os.makeDir.all(alphaWs / "docs" / "sub" / "docs")
  os.write.over(alphaWs / "docs" / "a.md",
    "# A\n\nSee [[b]] and [[sub/docs/child.md]] and [[GCR]] and [[DCL]].\n")
  os.write.over(alphaWs / "docs" / "b.md",
    "# B\n\nBack to [[a]].\n\nDCL — DOMContentLoaded 事件，本档词条样例。 ^term-dcl\n")
  os.write.over(alphaWs / "docs" / "glossary.md",
    """---
      |type: glossary
      |status: current
      |---
      |
      |# 词汇表
      |
      |GCR（银河宇宙线） — 来自银河系的高能粒子流，词条样例。 ^gcr
      |
      |重试风暴 — 失败触发重试、重试放大失败的反馈循环。 ^retry-storm
      |""".stripMargin)
  os.write.over(alphaWs / "docs" / "sub" / "docs" / "child.md", "# Child\n\nParent [[a.md]].\n")
  os.makeDir.all(betaWs)

  writeProject("alpha", alphaWs)
  writeProject("beta", betaWs)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private def writeProject(name: String, ws: os.Path): Unit =
    os.makeDir.all(tempRoot / "projects" / name)
    val w = ws.toString.replace("\\", "\\\\")
    os.write.over(
      tempRoot / "projects" / name / "project.json",
      s"""{"name":"$name","workspace":"$w","agentFile":"$w/AGENTS.md","createdAt":1}"""
    )

  /** Fresh instance per test — the TTL cache lives on the instance. */
  private def svc = new WikiService()

  private def str(json: io.circe.Json, field: String): Option[String] =
    json.hcursor.downField(field).as[String].toOption

  private def long(json: io.circe.Json, field: String): Option[Long] =
    json.hcursor.downField(field).as[Long].toOption

  test("WS-1 merged view stays byte-compatible in shape and excludes sub-vault docs") {
    svc.graph().map { g =>
      val projects = g.hcursor.downField("projects").as[List[io.circe.Json]].getOrElse(Nil)
      assertEquals(projects.map(p => str(p, "name").get).sorted, List("alpha", "beta"))
      // beta has no vault → stub project (legacy face preserved)
      val beta = projects.find(p => str(p, "name").contains("beta")).get
      assertEquals(beta.hcursor.get[Boolean]("stub").toOption, Some(true))
      // sub-vault isolation: child.md is NOT a real node of the merged graph
      val nodes = g.hcursor.downField("nodes").as[List[io.circe.Json]].getOrElse(Nil)
      val realRels = nodes.filterNot(n => n.hcursor.get[Boolean]("stub").toOption.contains(true))
        .flatMap(n => str(n, "rel")).sorted
      assertEquals(realRels, List("a.md", "b.md", "glossary.md"))
    }
  }

  test("WS-2 project view: isolated root-vault graph + childVaults aggregates + cross-vault stats") {
    svc.graph(Some("alpha")).map { g =>
      assertEquals(g.hcursor.downField("view").downField("project").as[String].toOption, Some("alpha"))
      val crumbs = g.hcursor.downField("view").downField("breadcrumb").as[List[io.circe.Json]].getOrElse(Nil)
      assertEquals(crumbs.map(c => str(c, "name")), List(Some("alpha")))

      val nodes = g.hcursor.downField("nodes").as[List[io.circe.Json]].getOrElse(Nil)
      val realRels = nodes.filterNot(n => n.hcursor.get[Boolean]("stub").toOption.contains(true))
        .flatMap(n => str(n, "rel")).sorted
      assertEquals(realRels, List("a.md", "b.md", "glossary.md")) // child.md isolated

      val children = g.hcursor.downField("childVaults").as[List[io.circe.Json]].getOrElse(Nil)
      assertEquals(children.map(c => str(c, "name")), List(Some("sub")))
      assertEquals(children.map(c => str(c, "rel")), List(Some("sub/docs")))
      assertEquals(children.flatMap(c => long(c, "docCount")), List(1L))

      // stats: a→b, b→a, a→glossary ([[GCR]] term edge), a→b ([[DCL]] term edge) in-vault;
      // a→sub/docs/child.md external
      val stats = g.hcursor.downField("stats")
      assertEquals(stats.downField("docs").as[Long].toOption, Some(3L))
      assertEquals(stats.downField("edges").as[Long].toOption, Some(4L))
      assertEquals(stats.downField("crossVault").as[Long].toOption, Some(1L))
      assertEquals(stats.downField("unresolved").as[Long].toOption, Some(0L))
      // 词条引用批: each doc node embeds its declared terms[]; the [[GCR]] edge
      // resolves to the glossary term's doc+anchor (no phantom gcr.md node)
      val glossTerms = nodes.find(n => str(n, "rel").contains("glossary.md")).get
        .hcursor.downField("terms").as[List[io.circe.Json]].toOption.getOrElse(Nil)
      assertEquals(glossTerms.map(_.hcursor.downField("name").as[String].toOption.get),
        List("GCR（银河宇宙线）", "重试风暴"))
      val gcrEdge = g.hcursor.downField("edges").as[List[io.circe.Json]].getOrElse(Nil)
        .find(e => str(e, "from").exists(_.endsWith(":a.md")) && str(e, "to").exists(_.endsWith(":glossary.md")))
      assertEquals(gcrEdge.flatMap(e => str(e, "anchor")), Some("#^gcr"))
      // the external placeholder rides the nodes with external=true
      val ext = nodes.filter(n => n.hcursor.get[Boolean]("external").toOption.contains(true))
      assertEquals(ext.flatMap(n => str(n, "rel")), List("sub/docs/child.md"))
    }
  }

  test("WS-3 drill-in: sub-vault view has its own graph, breadcrumb back, external parent link") {
    svc.graph(Some("alpha"), Some("sub/docs")).map { g =>
      assertEquals(g.hcursor.downField("view").downField("vault").as[String].toOption, Some("sub/docs"))
      val crumbs = g.hcursor.downField("view").downField("breadcrumb").as[List[io.circe.Json]].getOrElse(Nil)
      assertEquals(crumbs.map(c => (str(c, "name"), str(c, "vault"))),
        List((Some("alpha"), Some("docs")), (Some("sub"), Some("sub/docs"))))

      val nodes = g.hcursor.downField("nodes").as[List[io.circe.Json]].getOrElse(Nil)
      val realRels = nodes.filterNot(n => n.hcursor.get[Boolean]("stub").toOption.contains(true))
        .flatMap(n => str(n, "rel")).sorted
      assertEquals(realRels, List("child.md"))
      val ext = nodes.filter(n => n.hcursor.get[Boolean]("external").toOption.contains(true))
      assertEquals(ext.flatMap(n => str(n, "rel")), List("a.md"))

      val stats = g.hcursor.downField("stats")
      assertEquals(stats.downField("docs").as[Long].toOption, Some(1L))
      assertEquals(stats.downField("crossVault").as[Long].toOption, Some(1L))
      assertEquals(stats.downField("unresolved").as[Long].toOption, Some(0L))
      assertEquals(g.hcursor.downField("childVaults").as[List[io.circe.Json]].getOrElse(Nil), Nil)
    }
  }

  test("WS-4 missing faces: unknown project, unsafe vault rel, vault-less project") {
    svc.graph(Some("nope")).map { g =>
      assertEquals(str(g, "error"), Some("project 'nope' not found"))
    } *>
    svc.graph(Some("alpha"), Some("../escape")).map { g =>
      assertEquals(str(g, "error"), Some("invalid vault path '../escape'"))
    } *>
    svc.graph(Some("beta")).map { g =>
      assert(str(g, "error").exists(_.contains("not found")))
    }
  }

  test("WS-5 __user__ is the reserved C-batch placeholder (empty view, no error)") {
    svc.graph(Some("__user__")).map { g =>
      assertEquals(str(g, "error"), None)
      assertEquals(g.hcursor.downField("reserved").as[Boolean].toOption, Some(true))
      assertEquals(g.hcursor.downField("nodes").as[List[io.circe.Json]].getOrElse(Nil), Nil)
      assertEquals(g.hcursor.downField("stats").downField("docs").as[Long].toOption, Some(0L))
    }
  }

  test("WS-6 vaults manifest: local presence flags, no content scan") {
    svc.vaults.map { v =>
      val vaults = v.hcursor.downField("vaults").as[List[io.circe.Json]].getOrElse(Nil)
      assertEquals(vaults.map(p => str(p, "project").get).sorted, List("alpha", "beta"))
      val alpha = vaults.find(p => str(p, "project").contains("alpha")).get
      assertEquals(alpha.hcursor.get[Boolean]("present").toOption, Some(true))
      assert(str(alpha, "vault").exists(_.endsWith("docs")))
      val beta = vaults.find(p => str(p, "project").contains("beta")).get
      assertEquals(beta.hcursor.get[Boolean]("present").toOption, Some(false))
    }
  }

  private def termOf(json: io.circe.Json): io.circe.Json =
    json.hcursor.downField("term").as[io.circe.Json].toOption.get

  test("WS-7 term lookup API: glossary hit, paren alias, doc-intro hit, scoping, 404 face") {
    // blank name → Left (the route answers 400 before this, the service keeps its own guard)
    svc.term("", None).map {
      case Left(_) => ()
      case other   => fail(s"expected Left, got $other")
    } *>
    // glossary term resolves with definition + location anchor
    svc.term("GCR", None).map {
      case Right(json) =>
        assertEquals(json.hcursor.get[Boolean]("resolved").toOption, Some(true))
        val t = termOf(json)
        assertEquals(str(t, "source"), Some("glossary"))
        assertEquals(str(t, "rel"), Some("glossary.md"))
        assertEquals(str(t, "anchor"), Some("#^gcr"))
        assert(str(t, "definition").exists(_.startsWith("GCR（银河宇宙线）")))
      case other => fail(s"expected Right, got $other")
    } *>
    // paren-inner alias key hits, scoped to the project
    svc.term("银河宇宙线", Some("alpha")).map {
      case Right(json) => assertEquals(str(termOf(json), "anchor"), Some("#^gcr"))
      case other       => fail(s"expected Right, got $other")
    } *>
    // doc-intro term, case-insensitive name
    svc.term("dcl", Some("alpha")).map {
      case Right(json) =>
        val t = termOf(json)
        assertEquals(str(t, "source"), Some("doc"))
        assertEquals(str(t, "rel"), Some("b.md"))
        assertEquals(str(t, "anchor"), Some("#^term-dcl"))
      case other => fail(s"expected Right, got $other")
    } *>
    // unregistered name → Left (route answers 404)
    svc.term("不存在的词", Some("alpha")).map {
      case Left(err) => assert(err.contains("not registered"))
      case other     => fail(s"expected Left, got $other")
    }
  }

  test("WS-8 ambiguity ruling: same-name doc-intro terms in two docs → resolved:false + candidates") {
    // fixture write scoped to this last test: c.md declares its own DCL intro term
    os.write.over(alphaWs / "docs" / "c.md", "# C\n\nDCL — C 档自己的 DCL 词条。 ^term-dcl\n")
    svc.term("DCL", Some("alpha")).map {
      case Right(json) =>
        assertEquals(json.hcursor.get[Boolean]("resolved").toOption, Some(false))
        assertEquals(json.hcursor.get[Boolean]("ambiguous").toOption, Some(true))
        val candidates = json.hcursor.downField("candidates").as[List[io.circe.Json]].getOrElse(Nil)
        assertEquals(candidates.flatMap(c => str(c, "rel")).sorted, List("b.md", "c.md"))
      case other => fail(s"expected Right, got $other")
    }
  }
end WikiServiceSpec
