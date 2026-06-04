package nebflow.core.skill

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}

final case class SkillInfo(name: String, description: String, filePath: String)

object SkillInfo:

  given Encoder[SkillInfo] = Encoder.instance { s =>
    Json.obj("name" -> s.name.asJson, "description" -> s.description.asJson, "filePath" -> s.filePath.asJson)
  }

final case class SkillContent(content: String, baseDir: String)

object SkillService:
  private val logger = NebflowLogger.forName("nebflow.skill")

  private def skillsDir: os.Path =
    PathUtil.dataRoot / "skills"

  /** Create skills directory and a starter template on first run. */
  def ensureDefaults(): IO[Unit] = IO.delay {
    if !os.isDir(skillsDir) then
      os.makeDir.all(skillsDir)
      val exampleDir = skillsDir / "_example"
      val exampleFile = exampleDir / "skill.md"
      if !os.isFile(exampleFile) then os.write(exampleFile, exampleSkillMd, createFolders = true)
      logger.info(s"Created skills directory at $skillsDir with starter template")
  }

  private val exampleSkillMd: String =
    """---
      |name: example
      |description: An example skill — copy this folder to create your own
      |language: zh
      |---
      |
      |# Example Skill
      |
      |This is a starter template. Replace this content with your own workflow instructions.
      |
      |## Purpose
      |
      |Describe what this skill does in one sentence.
      |
      |## Steps
      |
      |1. Step one
      |2. Step two
      |3. Step three
      |
      |## Rules
      |
      |- Rule one
      |- Rule two
      |
      |## Output
      |
      |Describe the expected output format.
      |""".stripMargin

  /** Scan ~/.nebflow/skills/ for skill directories. Each subdirectory must contain skill.md. */
  def listSkills(): IO[List[SkillInfo]] = IO.delay {
    if !os.isDir(skillsDir) then Nil
    else
      os.list(skillsDir)
        .filter(_.baseName != "_example") // skip starter template
        .flatMap { subDir =>
          if !os.isDir(subDir) then None
          else
            val skillFile = subDir / "skill.md"
            if !os.isFile(skillFile) then None
            else
              readSkillFrontmatter(skillFile).map { case (name, desc) =>
                SkillInfo(name, desc, skillFile.toString)
              }
        }
        .toList
  }

  /** Load full skill content from a skill file path. */
  def loadSkill(filePath: String): IO[Option[SkillContent]] = IO.delay {
    val f = os.Path(filePath)
    if !os.isFile(f) then None
    else
      val content = os.read(f)
      val contentOnly = stripFrontmatter(content)
      Some(SkillContent(contentOnly, (f / os.up).toString))
  }

  /** Parse frontmatter from skill.md: --- yaml --- format, extract name and description. */
  private def readSkillFrontmatter(filePath: os.Path): Option[(String, String)] =
    try
      val content = os.read(filePath)
      val frontmatter = extractFrontmatter(content)
      val name = extractField(frontmatter, "name").getOrElse((filePath / os.up).baseName)
      val description = extractField(frontmatter, "description").getOrElse("")
      Some((name, description))
    catch case _: Exception => None

  private def extractFrontmatter(content: String): String =
    val trimmed = content.trim
    if trimmed.startsWith("---") then
      val end = trimmed.indexOf("---", 3)
      if end > 0 then trimmed.substring(3, end).trim else ""
    else ""

  private def stripFrontmatter(content: String): String =
    val trimmed = content.trim
    if trimmed.startsWith("---") then
      val end = trimmed.indexOf("---", 3)
      if end > 0 then trimmed.substring(end + 3).trim else trimmed
    else trimmed

  private def extractField(frontmatter: String, field: String): Option[String] =
    frontmatter
      .split("\n")
      .map(_.trim)
      .find(_.startsWith(s"$field:"))
      .map(_.drop(field.length + 1).trim)
      .map(_.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'"))
end SkillService
