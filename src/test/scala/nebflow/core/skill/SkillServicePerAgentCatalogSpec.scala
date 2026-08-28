package nebflow.core.skill

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

/**
 * buildPerAgentCatalog (B2): the opt-in catalog must mirror the global
 * catalog's modelInvocable rule (disable-model-invocation skills are
 * slash-command-only) and keep when_to_use frontmatter in the entry —
 * it is the anti-misuse metadata the skill author wrote for the agent.
 */
class SkillServicePerAgentCatalogSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-skill-catalog"
  PathUtil.setDataRoot(tempRoot)
  private val skillsDir = tempRoot / "skills"

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(skillsDir)

  private def writeSkill(
    name: String,
    description: String,
    whenToUse: String = "",
    disableModelInvocation: Boolean = false
  ): Unit =
    val dir = skillsDir / name
    os.makeDir.all(dir)
    val fmLines = List(
      "---",
      s"name: $name",
      s"description: $description"
    ) ++
      (if whenToUse.nonEmpty then List(s"when_to_use: $whenToUse") else Nil) ++
      (if disableModelInvocation then List("disable-model-invocation: true") else Nil) :+ "---"
    os.write(dir / "SKILL.md", fmLines.mkString("\n") + "\n\n# body\n")
  end writeSkill

  test("empty declared list yields empty catalog"):
    val catalog = SkillService.buildPerAgentCatalog(Nil).unsafeRunSync()
    assertEquals(catalog, "")

  test("declared skill appears with name and description"):
    writeSkill("eco-alpha", "Alpha does analysis")
    val catalog = SkillService.buildPerAgentCatalog(List("eco-alpha")).unsafeRunSync()
    assert(catalog.contains("# Skills"), s"catalog header: $catalog")
    assert(catalog.contains("- eco-alpha: Alpha does analysis"), s"entry: $catalog")

  test("disable-model-invocation skill is excluded even when declared"):
    writeSkill("eco-hidden", "Hidden from model", disableModelInvocation = true)
    val catalog = SkillService.buildPerAgentCatalog(List("eco-hidden")).unsafeRunSync()
    assertEquals(catalog, "", "slash-command-only skill must not be injected")

  test("when_to_use is kept in the entry; omitted when absent"):
    writeSkill("eco-when", "Skill with when", whenToUse = "use for releases only")
    writeSkill("eco-plain", "Skill without when")
    val catalog = SkillService.buildPerAgentCatalog(List("eco-when", "eco-plain")).unsafeRunSync()
    assert(catalog.contains("- eco-when: Skill with when [when: use for releases only]"), s"when segment: $catalog")
    val plainLine = catalog.linesIterator.find(_.startsWith("- eco-plain:")).getOrElse("")
    assert(plainLine.nonEmpty, s"plain skill missing: $catalog")
    assert(!plainLine.contains("[when:"), s"no when segment expected: $plainLine")

  test("declared but non-existent skill name is ignored"):
    writeSkill("eco-real", "Real skill")
    val catalog = SkillService.buildPerAgentCatalog(List("eco-ghost", "eco-real")).unsafeRunSync()
    assert(catalog.contains("eco-real"), s"real skill present: $catalog")
    assert(!catalog.contains("eco-ghost"), s"ghost skill absent: $catalog")

  test("long description is truncated to 200 chars"):
    writeSkill("eco-long", "x" * 300)
    val catalog = SkillService.buildPerAgentCatalog(List("eco-long")).unsafeRunSync()
    val line = catalog.linesIterator.find(_.startsWith("- eco-long:")).getOrElse("")
    // "- eco-long: " prefix (12 chars) + 200 chars of description
    assertEquals(line.length, 12 + 200, s"line len=${line.length}")

  test("all declared skills model-invocable=false yields empty catalog"):
    writeSkill("eco-only-hidden", "Hidden", disableModelInvocation = true)
    val catalog = SkillService.buildPerAgentCatalog(List("eco-only-hidden")).unsafeRunSync()
    assertEquals(catalog, "")

  test("wildcard * subscribes to every model-invocable skill in the library"):
    writeSkill("eco-a", "Alpha")
    writeSkill("eco-hidden", "Hidden from model", disableModelInvocation = true)
    writeSkill("eco-b", "Beta")
    val catalog = SkillService.buildPerAgentCatalog(List("*")).unsafeRunSync()
    assert(catalog.contains("- eco-a: Alpha"), s"eco-a: $catalog")
    assert(catalog.contains("- eco-b: Beta"), s"eco-b: $catalog")
    assert(!catalog.contains("eco-hidden"), s"hidden must stay out even under wildcard: $catalog")

  test("wildcard with only hidden skills yields empty catalog"):
    writeSkill("eco-only-hidden", "Hidden", disableModelInvocation = true)
    val catalog = SkillService.buildPerAgentCatalog(List("*")).unsafeRunSync()
    assertEquals(catalog, "")

  test("skill-creator is appended to an injected catalog even when not declared"):
    writeSkill("eco-x", "X skill")
    writeSkill("skill-creator", "Create and update skills")
    val catalog = SkillService.buildPerAgentCatalog(List("eco-x")).unsafeRunSync()
    assert(catalog.contains("- eco-x: X skill"), s"declared: $catalog")
    assert(catalog.contains("- skill-creator: Create and update skills"), s"bootstrap entry: $catalog")

  test("skill-creator is not duplicated when already declared"):
    writeSkill("eco-y", "Y skill")
    writeSkill("skill-creator", "Create and update skills")
    val catalog = SkillService.buildPerAgentCatalog(List("skill-creator", "eco-y")).unsafeRunSync()
    val creatorLines = catalog.linesIterator.count(_.startsWith("- skill-creator:"))
    assertEquals(creatorLines, 1, s"catalog: $catalog")

  test("skill-creator keeps an otherwise-empty catalog alive (declared skill all hidden)"):
    writeSkill("eco-hidden", "Hidden", disableModelInvocation = true)
    writeSkill("skill-creator", "Create and update skills")
    val catalog = SkillService.buildPerAgentCatalog(List("eco-hidden")).unsafeRunSync()
    assert(catalog.contains("- skill-creator:"), s"catalog: $catalog")
    assert(!catalog.contains("eco-hidden"), s"hidden must stay out: $catalog")
end SkillServicePerAgentCatalogSpec
