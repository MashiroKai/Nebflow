package nebflow.core.skill

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

import java.time.LocalDate

/**
 * skill audit (eco #8-D): reverse index of agent.json skills declarations,
 * orphan detection (model-invocable, zero subscribers, no wildcard), and
 * stale detection (last_verified older than 90 days). Report-only.
 */
class SkillAuditSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-skill-audit"
  PathUtil.setDataRoot(tempRoot)
  private val skillsDir = tempRoot / "skills"
  private val today = LocalDate.of(2026, 8, 15)

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(skillsDir)

  private def writeSkill(
    name: String,
    description: String = "Some skill",
    lastVerified: Option[String] = None,
    disableModelInvocation: Boolean = false
  ): Unit =
    val dir = name.split('/').foldLeft(skillsDir: os.Path)((d, seg) => d / seg)
    os.makeDir.all(dir)
    val fm = List(
      s"name: $name",
      s"description: $description"
    ) ++ lastVerified.map(v => s"last_verified: $v").toList ++
      (if disableModelInvocation then List("disable-model-invocation: true") else Nil)
    os.write(dir / "SKILL.md", fm.mkString("---\n", "\n", "\n---\n\n# body\n"))

  private def writeAgentJson(path: String, skills: List[String]): Unit =
    val f = tempRoot / os.RelPath(path)
    os.makeDir.all(f / os.up)
    val skillsJson = skills.map(n => "\"" + n + "\"").mkString("[", ",", "]")
    os.write(f, "{\"name\":\"x\",\"skills\":" + skillsJson + "}")

  private def writeAgentJsonNull(path: String): Unit =
    val f = tempRoot / os.RelPath(path)
    os.makeDir.all(f / os.up)
    os.write(f, "{\"name\":\"x\",\"skills\":null}")

  test("maps subscribers from global and team agent.json"):
    writeSkill("s1")
    writeSkill("s2")
    writeAgentJson("agents/G/agent.json", List("s1"))
    writeAgentJson("teams/T/agents/A/agent.json", List("s1", "s2"))
    val r = SkillAudit.run(today).unsafeRunSync()
    assertEquals(r.subscribersBySkill("s1").map(s => (s.agent, s.scope)), List(("G", "global"), ("A", "T")))
    assertEquals(r.subscribersBySkill("s2").map(s => (s.agent, s.scope)), List(("A", "T")))
    assertEquals(r.agentsScanned, 2)
    assertEquals(r.agentsWithDeclarations, 2)

  test("skills null or empty counts as scanned but undeclared"):
    writeSkill("s1")
    writeAgentJsonNull("agents/N/agent.json")
    writeAgentJson("agents/M/agent.json", List.empty)
    val r = SkillAudit.run(today).unsafeRunSync()
    assertEquals(r.agentsScanned, 2)
    assertEquals(r.agentsWithDeclarations, 0)
    assertEquals(r.orphanSkills.map(_.name), List("s1"))

  test("wildcard subscriber suppresses orphans and is listed"):
    writeSkill("used")
    writeSkill("unused")
    writeAgentJson("agents/Nebula/agent.json", List("*"))
    val r = SkillAudit.run(today).unsafeRunSync()
    assertEquals(r.wildcardSubscribers.map(_.agent), List("Nebula"))
    assertEquals(r.orphanSkills, Nil)

  test("orphan = model-invocable and zero subscribers; skill-creator and hidden exempt"):
    writeSkill("lonely")
    writeSkill("skill-creator")
    writeSkill("hidden-away", disableModelInvocation = true)
    writeSkill("loved")
    writeAgentJson("agents/F/agent.json", List("loved"))
    val r = SkillAudit.run(today).unsafeRunSync()
    assertEquals(r.orphanSkills.map(_.name), List("lonely"))

  test("namespaced skill subscription is tracked by relative id"):
    writeSkill("nebflow/visual-style")
    writeAgentJson("agents/F/agent.json", List("nebflow/visual-style"))
    val r = SkillAudit.run(today).unsafeRunSync()
    assertEquals(r.subscribersBySkill("nebflow/visual-style").map(_.agent), List("F"))
    assert(r.orphanSkills.isEmpty)

  test("stale = last_verified older than 90 days; fresh, undated, and unparseable are not"):
    writeSkill("old", lastVerified = Some("2026-01-01"))
    writeSkill("edge", lastVerified = Some(today.minusDays(90).toString))
    writeSkill("fresh", lastVerified = Some("2026-08-01"))
    writeSkill("undated")
    writeSkill("garbage", lastVerified = Some("not-a-date"))
    val r = SkillAudit.run(today).unsafeRunSync()
    assertEquals(r.staleSkills.map(_.skill.name), List("old"))
    assertEquals(r.staleSkills.head.ageDays, 226L)
    assertEquals(r.skillsWithoutVerifiedDate, 2)
end SkillAuditSpec
