package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.entity.{EntityLoader, TeamDef}

/**
 * LoadTool team validation (loadfix): team-local agents under
 * teams/<x>/agents/ are invisible to EntityLoader.listAgents() — the old
 * validation rejected every reference that only exists inside the team dir,
 * so a freshly created team could never be activated via Load.
 */
class LoadToolTeamValidationSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-load-validation"
  PathUtil.setDataRoot(tempRoot)

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents") // global agents dir exists but is empty

  private def writeTeamJson(name: String, lead: String, members: List[String]): Unit =
    val dir = tempRoot / "teams" / name
    os.makeDir.all(dir)
    os.write(
      dir / "team.json",
      Json
        .obj(
          "name" -> name.asJson,
          "description" -> "test team".asJson,
          "lead" -> lead.asJson,
          "members" -> members.asJson
        )
        .noSpaces
    )

  /** Team-local agent dir with agent.json (json name defaults to dir name). */
  private def writeTeamAgent(team: String, agent: String): Unit =
    val dir = tempRoot / "teams" / team / "agents" / agent
    os.makeDir.all(dir)
    os.write(
      dir / "agent.json",
      Json.obj("name" -> agent.asJson, "description" -> s"$agent agent".asJson).noSpaces
    )

  private def teamDef(name: String, lead: String, members: List[String]): TeamDef =
    TeamDef(name = name, description = "test team", lead = lead, members = members)

  test("team-local lead agent passes validation"):
    writeTeamJson("eco-team", lead = "Manager", members = List("swift-dev"))
    writeTeamAgent("eco-team", "Manager")
    writeTeamAgent("eco-team", "swift-dev")
    val defn = teamDef("eco-team", "Manager", List("swift-dev"))
    val names = LoadTool.teamValidationNames(defn).unsafeRunSync()
    val errors = EntityLoader.validateTeam(defn, names)
    assertEquals(errors, Nil, s"errors: $errors")

  test("team-local members pass even though invisible to listAgents"):
    writeTeamJson("eco-team", lead = "Manager", members = List("swift-dev", "qa-swift"))
    writeTeamAgent("eco-team", "Manager")
    writeTeamAgent("eco-team", "swift-dev")
    writeTeamAgent("eco-team", "qa-swift")
    val defn = teamDef("eco-team", "Manager", List("swift-dev", "qa-swift"))
    val global = EntityLoader.listAgents().unsafeRunSync()
    assert(!global.contains("swift-dev"), "precondition: team-local agent not in global set")
    val errors =
      EntityLoader.validateTeam(defn, LoadTool.teamValidationNames(defn).unsafeRunSync())
    assertEquals(errors, Nil, s"errors: $errors")

  test("truly missing agent still fails validation"):
    writeTeamJson("eco-team", lead = "Manager", members = List("ghost-dev"))
    writeTeamAgent("eco-team", "Manager")
    val defn = teamDef("eco-team", "Manager", List("ghost-dev"))
    val errors =
      EntityLoader.validateTeam(defn, LoadTool.teamValidationNames(defn).unsafeRunSync())
    assertEquals(errors, List("member agent 'ghost-dev' not found"))

  test("missing lead fails validation"):
    writeTeamJson("eco-team", lead = "Manager", members = Nil)
    val defn = teamDef("eco-team", "Manager", Nil)
    val errors =
      EntityLoader.validateTeam(defn, LoadTool.teamValidationNames(defn).unsafeRunSync())
    assertEquals(errors, List("lead agent 'Manager' not found"))

  test("global agents remain valid references (mixed sources)"):
    // lead from global dir, member from team dir
    val globalDir = tempRoot / "agents" / "global-lead"
    os.makeDir.all(globalDir)
    os.write(
      globalDir / "agent.json",
      Json.obj("name" -> "global-lead".asJson, "description" -> "global".asJson).noSpaces
    )
    writeTeamJson("eco-mixed", lead = "global-lead", members = List("local-dev"))
    writeTeamAgent("eco-mixed", "local-dev")
    val defn = teamDef("eco-mixed", "global-lead", List("local-dev"))
    val errors =
      EntityLoader.validateTeam(defn, LoadTool.teamValidationNames(defn).unsafeRunSync())
    assertEquals(errors, Nil)

  test("Load(team=...) with missing member returns the validation error via the tool"):
    writeTeamJson("eco-broken", lead = "Manager", members = List("ghost-dev"))
    writeTeamAgent("eco-broken", "Manager")
    val input = io.circe.JsonObject(
      "type" -> "team".asJson,
      "name" -> "eco-broken".asJson
    )
    val ctx = ToolContext(projectRoot = "")
    val result = LoadTool.call(input, ctx).unsafeRunSync()
    assert(result.isLeft)
    val msg = result.swap.toOption.get.message
    assert(msg.contains("member agent 'ghost-dev' not found"), s"got: $msg")
end LoadToolTeamValidationSpec
