package nebflow.core.entity

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

/**
  * Regression tests for the broken Team catalog injection: team members and
  * leads live under teams/<team>/agents/, but the catalog builder used to be
  * fed ONLY the global agents dir (EntityLoader.listAgents scans
  * ~/.nebflow/agents). Result: every team agent's system prompt rendered
  * "(agent not found)" for the lead and an empty member list — czt-project's
  * Manager read that as "my members don't exist" and reported Mail
  * activation failure without ever attempting a Mail (2026-08-17 report;
  * session history shows zero Mail calls to members, the phrase "agent not
  * found" quoted verbatim from the catalog).
  *
  * ContextRefresher now merges global ++ listTeamAgents(team) — team-local
  * takes precedence, mirroring loadTeamAgent's resolution order. These
  * tests pin the data layer that feeds the merge plus the pure renderer.
  */
class TeamCatalogLocalAgentsSpec extends FunSuite:

  private val tmpDir = os.temp.dir(prefix = "team-catalog-spec")
  // Capture BEFORE any test mutates it; restored in afterAll (tests share
  // PathUtil global state — sequential execution, see build.sbt).
  private val originalRoot = PathUtil.dataRoot

  private def writeAgent(team: String, name: String, description: String): Unit =
    val dir = tmpDir / "teams" / team / "agents" / name
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", s"""{"description": "$description", "useWhen": "use $name"}""")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmpDir)

  test("pure renderer: team-local agents map renders lead and members") {
    val team = TeamDef(
      name = "greek",
      description = "test team",
      lead = "Manager",
      members = List("alpha", "beta")
    )
    val agents = Map(
      "Manager" -> AgentEntry(name = "Manager", description = "the lead", useWhen = "lead tasks"),
      "alpha"   -> AgentEntry(name = "alpha", description = "does alpha", useWhen = "alpha work"),
      "beta"    -> AgentEntry(name = "beta", description = "does beta", useWhen = "beta work")
    )
    val catalog = TeamCatalog.buildCatalog(team, agents, Map.empty)
    assert(catalog.contains("- Manager: the lead"))
    assert(catalog.contains("- alpha: does alpha"))
    assert(catalog.contains("- beta: does beta"))
    assert(!catalog.contains("(agent not found)"))
  }

  test("fixture: listTeamAgents resolves what listAgents cannot — the merge fixes the catalog") {
    PathUtil.setDataRoot(tmpDir)
    val teamDir = tmpDir / "teams" / "czt-fixture"
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      """{"name": "czt-fixture", "description": "fixture team", "lead": "Manager", "members": ["physicist", "writer"]}"""
    )
    writeAgent("czt-fixture", "Manager", "fixture lead")
    writeAgent("czt-fixture", "physicist", "fixture physicist")
    writeAgent("czt-fixture", "writer", "fixture writer")

    val catalogIO = for
      teamOpt <- EntityLoader.loadTeam("czt-fixture")
      global <- EntityLoader.listAgents() // scans the temp global dir: empty
      local <- EntityLoader.listTeamAgents("czt-fixture")
      catalog = teamOpt.map(t => TeamCatalog.buildCatalog(t, global ++ local, Map.empty))
    yield catalog

    catalogIO.unsafeRunSync() match
      case Some(catalog) =>
        assert(catalog.contains("- Manager: fixture lead"))
        assert(catalog.contains("- physicist: fixture physicist"))
        assert(catalog.contains("- writer: fixture writer"))
        assert(!clue(catalog).contains("(agent not found)"))
      case None => fail("fixture team failed to load")
  }

  test("fixture: global ++ local merge prefers team-local on name collision") {
    PathUtil.setDataRoot(tmpDir)
    // Same agent name in both layers — team-local must win, mirroring
    // loadTeamAgent's team-first resolution order.
    writeAgent("czt-fixture", "writer", "TEAM-LOCAL writer")
    val globalDir = tmpDir / "agents" / "writer"
    os.makeDir.all(globalDir)
    os.write.over(globalDir / "agent.json", """{"description": "GLOBAL writer", "useWhen": "x"}""")

    val mergedIO = for
      global <- EntityLoader.listAgents()
      local <- EntityLoader.listTeamAgents("czt-fixture")
    yield global ++ local

    mergedIO.unsafeRunSync().get("writer").map(_.description) match
      case Some(desc) => assertEquals(desc, "TEAM-LOCAL writer")
      case None       => fail("writer missing from merged map")
  }

end TeamCatalogLocalAgentsSpec
