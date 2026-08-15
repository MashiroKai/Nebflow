package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.flow.TeamSessionRegistry

/**
 * checkTeamScope cross-team tightening (decision 20): explicit
 * "team/agent" addresses targeting another team are blocked by default for
 * non-lead senders. A team opts in via the rules.md marker
 * `<!-- allow-cross-team-mail: true -->`. Leads always pass. Same-team
 * explicit routes and short names are unaffected.
 */
class MailToolCheckTeamScopeSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-scope"
  PathUtil.setDataRoot(tempRoot)

  private def writeTeam(name: String, lead: String, rules: String = ""): Unit =
    val dir = tempRoot / "teams" / name
    os.makeDir.all(dir)
    os.write.over(
      dir / "team.json",
      s"""{"name": "$name", "description": "test team", "lead": "$lead", "members": []}"""
    )
    if rules.nonEmpty then os.write(dir / "rules.md", rules)

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "teams")
    TeamSessionRegistry.clear.unsafeRunSync()
    writeTeam("myteam", lead = "boss") // rules.md absent → marker off
    writeTeam("other", lead = "chief")

  private def check(address: String, senderName: String = "worker", senderSid: String = "worker-sid") =
    MailTool.checkTeamScope(address, "myteam", senderSid, senderName).unsafeRunSync()

  test("non-lead cross-team explicit route is blocked by default"):
    val res = check("other/Backend")
    assert(res.isDefined, "must be blocked without the rules.md marker")
    assert(res.get.contains("Cross-team"), s"error should explain the block: ${res.get}")
    assert(res.get.contains("Manager"), s"error should point to the escalation path: ${res.get}")

  test("same-team explicit route is unaffected"):
    assertEquals(check("myteam/Backend"), None)

  test("short-name routing is unaffected"):
    assertEquals(check("backend"), None)

  test("rules.md marker opts the team in"):
    writeTeam("myteam", lead = "boss", rules = "# Rules\n\n<!-- allow-cross-team-mail: true -->\n")
    assertEquals(check("other/Backend"), None)

  test("lead by session (registered Manager) always passes"):
    TeamSessionRegistry.registerManager("myteam", "mgr-sid").unsafeRunSync()
    assertEquals(check("other/Backend", senderName = "whoever", senderSid = "mgr-sid"), None)

  test("lead by name (team.json lead, unregistered session) passes"):
    // Fork/temporary sessions carry the lead agentDef but an unregistered sid —
    // name-based fallback (same as canMailNebula).
    assertEquals(check("other/Backend", senderName = "boss", senderSid = "fork-sid"), None)

  test("mailing another team BY NAME stays blocked (pre-existing behavior)"):
    val res = check("other")
    assert(res.isDefined)
    assert(res.get.contains("Cannot mail outside your team"), s"${res.get}")

  test("Nebula routing unchanged: non-lead blocked, lead allowed"):
    val blocked = check("Nebula")
    assert(blocked.isDefined)
    assert(blocked.get.contains("Cannot mail Nebula directly"), s"${blocked.get}")
    TeamSessionRegistry.registerManager("myteam", "mgr-sid").unsafeRunSync()
    assertEquals(check("Nebula", senderSid = "mgr-sid"), None)

  test("unknown target team in explicit route is still treated as cross-team"):
    val res = check("ghost-team/Agent")
    assert(res.isDefined, "conservative: unresolvable team part is not an exemption")
end MailToolCheckTeamScopeSpec
