package nebflow.core.skill

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil

/**
 * Nested skill namespaces (eco task #8-A): `skills/<ns>/<name>/SKILL.md` loads with
 * the relative path as identifier; flat one-level loading is unchanged; subscription
 * by the namespaced id hits the per-agent catalog; deleteSkill resolves nested ids.
 */
class SkillServiceNamespaceSpec extends FunSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-skill-namespace"
  PathUtil.setDataRoot(tempRoot)
  private val skillsDir = tempRoot / "skills"

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(skillsDir)

  private def writeSkillFile(dir: os.Path, frontmatter: String): Unit =
    os.makeDir.all(dir)
    os.write(dir / "SKILL.md", s"---\n$frontmatter\n---\n\n# body\n")

  test("namespaced skill loads with relative path as identifier"):
    writeSkillFile(
      skillsDir / "nebflow" / "visual-style",
      "name: visual-style\ndescription: Nebflow visual style rules"
    )
    val names = SkillService.listSkills().unsafeRunSync().map(_.name)
    assert(names.contains("nebflow/visual-style"), s"loaded names: $names")
    assert(!names.contains("visual-style"), s"flat name must not leak: $names")

  test("namespace dir with its own SKILL.md loads both flat and nested skills"):
    writeSkillFile(skillsDir / "nebflow", "name: nebflow\ndescription: Namespace-level skill")
    writeSkillFile(
      skillsDir / "nebflow" / "release",
      "name: release\ndescription: Release checklist"
    )
    val names = SkillService.listSkills().unsafeRunSync().map(_.name)
    assert(names.contains("nebflow"), s"flat skill missing: $names")
    assert(names.contains("nebflow/release"), s"nested skill missing: $names")

  test("flat one-level skill behavior is unchanged (frontmatter name wins)"):
    writeSkillFile(skillsDir / "mydir", "name: frontmatter-name\ndescription: Flat skill")
    val skills = SkillService.listSkills().unsafeRunSync()
    val names = skills.map(_.name)
    assert(names.contains("frontmatter-name"), s"frontmatter name should win: $names")
    assert(!names.contains("mydir"), s"dir name should not be used: $names")

  test("agent declaring the namespaced id gets the catalog entry"):
    writeSkillFile(
      skillsDir / "nebflow" / "visual-style",
      "name: visual-style\ndescription: Nebflow visual style rules"
    )
    val catalog = SkillService.buildPerAgentCatalog(List("nebflow/visual-style")).unsafeRunSync()
    assert(catalog.contains("# Skills"), s"catalog header: $catalog")
    assert(catalog.contains("- nebflow/visual-style: Nebflow visual style rules"), s"entry: $catalog")

  test("declaring only the bare leaf name does not match a namespaced skill"):
    writeSkillFile(
      skillsDir / "nebflow" / "visual-style",
      "name: visual-style\ndescription: Nebflow visual style rules"
    )
    val catalog = SkillService.buildPerAgentCatalog(List("visual-style")).unsafeRunSync()
    assertEquals(catalog, "", "leaf name must not resolve the namespaced skill")

  test("deleteSkill removes a nested skill by relative id and refuses traversal"):
    writeSkillFile(
      skillsDir / "nebflow" / "visual-style",
      "name: visual-style\ndescription: Nebflow visual style rules"
    )
    assertEquals(SkillService.deleteSkill("nebflow/visual-style").unsafeRunSync(), true)
    assert(!os.exists(skillsDir / "nebflow" / "visual-style"), "nested dir should be gone")
    // Namespace dir itself remains
    assert(os.exists(skillsDir / "nebflow"))
    assertEquals(SkillService.deleteSkill("../agents").unsafeRunSync(), false, "traversal refused")
    assertEquals(SkillService.deleteSkill("nebflow/ghost").unsafeRunSync(), false, "missing refused")
  test("frontmatter governance fields (audience/last_verified/status/replaced_by) are parsed and encoded"):
    writeSkillFile(
      skillsDir / "governed",
      "name: governed\ndescription: Governed skill\naudience: nebflow\nlast_verified: 2026-08-14\nstatus: deprecated\nreplaced_by: nebflow/visual-style"
    )
    val skill = SkillService.listSkills().unsafeRunSync().find(_.name == "governed").getOrElse(fail("governed not loaded"))
    assertEquals(skill.audience, Some("nebflow"))
    assertEquals(skill.lastVerified, Some("2026-08-14"))
    assertEquals(skill.status, Some("deprecated"))
    assertEquals(skill.replacedBy, Some("nebflow/visual-style"))
    // Encoder carries the fields (REST getSkills / audit JSON consumers)
    assertEquals(skill.asJson.hcursor.downField("audience").as[Option[String]], Right(Some("nebflow")))
    assertEquals(skill.asJson.hcursor.downField("lastVerified").as[Option[String]], Right(Some("2026-08-14")))
    assertEquals(skill.asJson.hcursor.downField("status").as[Option[String]], Right(Some("deprecated")))
    assertEquals(skill.asJson.hcursor.downField("replacedBy").as[Option[String]], Right(Some("nebflow/visual-style")))

  test("governance fields default to None and encode as null (backward compatible)"):
    writeSkillFile(skillsDir / "plain", "name: plain\ndescription: Plain skill")
    val skill = SkillService.listSkills().unsafeRunSync().find(_.name == "plain").getOrElse(fail("plain not loaded"))
    assertEquals(skill.audience, None)
    assertEquals(skill.lastVerified, None)
    assertEquals(skill.status, None)
    assertEquals(skill.replacedBy, None)
end SkillServiceNamespaceSpec
