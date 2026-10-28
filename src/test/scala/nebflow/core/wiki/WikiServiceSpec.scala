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
  os.write.over(alphaWs / "docs" / "a.md", "# A\n\nSee [[b]] and [[sub/docs/child.md]].\n")
  os.write.over(alphaWs / "docs" / "b.md", "# B\n\nBack to [[a]].\n")
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
      assertEquals(realRels, List("a.md", "b.md"))
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
      assertEquals(realRels, List("a.md", "b.md")) // child.md isolated

      val children = g.hcursor.downField("childVaults").as[List[io.circe.Json]].getOrElse(Nil)
      assertEquals(children.map(c => str(c, "name")), List(Some("sub")))
      assertEquals(children.map(c => str(c, "rel")), List(Some("sub/docs")))
      assertEquals(children.flatMap(c => long(c, "docCount")), List(1L))

      // stats: a→b and b→a in-vault edges; a→sub/docs/child.md external
      val stats = g.hcursor.downField("stats")
      assertEquals(stats.downField("docs").as[Long].toOption, Some(2L))
      assertEquals(stats.downField("edges").as[Long].toOption, Some(2L))
      assertEquals(stats.downField("crossVault").as[Long].toOption, Some(1L))
      assertEquals(stats.downField("unresolved").as[Long].toOption, Some(0L))
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
end WikiServiceSpec
