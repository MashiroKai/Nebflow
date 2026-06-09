package nebflow.core.skill

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}

// --- Data models ---

final case class SkillInfo(
  name: String,
  description: String,
  filePath: String,
  whenToUse: Option[String] = None,
  allowedTools: List[String] = Nil,
  argumentHint: Option[String] = None,
  argumentNames: List[String] = Nil,
  userInvocable: Boolean = true,
  version: Option[String] = None,
  /** Where this skill was loaded from: "user" | "project" | "commands" */
  source: String = "user"
)

object SkillInfo:

  given Encoder[SkillInfo] = Encoder.instance { s =>
    Json.obj(
      "name" -> s.name.asJson,
      "description" -> s.description.asJson,
      "filePath" -> s.filePath.asJson,
      "whenToUse" -> s.whenToUse.asJson,
      "allowedTools" -> s.allowedTools.asJson,
      "argumentHint" -> s.argumentHint.asJson,
      "argumentNames" -> s.argumentNames.asJson,
      "userInvocable" -> s.userInvocable.asJson,
      "version" -> s.version.asJson,
      "source" -> s.source.asJson
    )
  }
end SkillInfo

final case class SkillContent(content: String, baseDir: String)

// --- Skill loading service ---

object SkillService:
  private val logger = NebflowLogger.forName("nebflow.skill")

  private def userSkillsDir: os.Path =
    PathUtil.dataRoot / "skills"

  /** Create user-level skills directory and a starter template on first run. */
  def ensureDefaults(): IO[Unit] = IO.delay {
    if !os.isDir(userSkillsDir) then
      os.makeDir.all(userSkillsDir)
      val exampleDir = userSkillsDir / "_example"
      val exampleFile = exampleDir / "skill.md"
      if !os.isFile(exampleFile) then os.write(exampleFile, exampleSkillMd, createFolders = true)
      logger.info(s"Created skills directory at $userSkillsDir with starter template")
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

  // ============================================================
  // Public API
  // ============================================================

  /**
   * List all available skills from all sources, deduplicated by name.
   * Priority (highest first): user-level > project-level > legacy commands.
   */
  def listSkills(): IO[List[SkillInfo]] = IO.delay {
    val userSkills = loadFromSkillsDir(userSkillsDir, "user")
    val projectSkills = loadProjectSkills()
    val legacyCommands = loadLegacyCommands()

    val all = userSkills ++ projectSkills ++ legacyCommands
    // Deduplicate: first occurrence wins (user > project > commands)
    val seen = scala.collection.mutable.Set[String]()
    all.filter { skill =>
      if seen.contains(skill.name) then false
      else
        seen.add(skill.name); true
    }
  }

  /** Load full skill content from a skill file path. */
  def loadSkill(filePath: String): IO[Option[SkillContent]] = IO.delay {
    val f = os.Path(filePath)
    if !os.isFile(f) then None
    else
      val raw = os.read(f)
      val contentOnly = stripFrontmatter(raw)
      val baseDir = (f / os.up).toString
      // Substitute ${SKILL_DIR} with the skill's directory
      val substituted = contentOnly.replace("${SKILL_DIR}", baseDir)
      Some(SkillContent(substituted, baseDir))
  }

  // ============================================================
  // Multi-source loading
  // ============================================================

  /** Project-level skill directories to scan, in priority order. */
  private def projectSkillPaths: List[os.Path] =
    val cwd = os.pwd
    List(
      cwd / ".nebflow" / "skills",
      cwd / ".claude" / "skills"
    )

  /** Project-level legacy command directories to scan. */
  private def projectCommandPaths: List[os.Path] =
    val cwd = os.pwd
    List(
      cwd / ".nebflow" / "commands",
      cwd / ".claude" / "commands"
    )

  private def loadProjectSkills(): List[SkillInfo] =
    projectSkillPaths.flatMap(dir => loadFromSkillsDir(dir, "project"))

  private def loadLegacyCommands(): List[SkillInfo] =
    projectCommandPaths.flatMap(dir => loadFromCommandsDir(dir))

  // ============================================================
  // Directory format: skill-name/SKILL.md or skill-name/skill.md
  // ============================================================

  private def loadFromSkillsDir(dir: os.Path, source: String): List[SkillInfo] =
    if !os.isDir(dir) then Nil
    else
      os.list(dir)
        .filter(sub => os.isDir(sub) && sub.baseName != "_example")
        .flatMap { subDir =>
          // Prefer SKILL.md (Claude Code convention), fall back to skill.md (Nebflow convention)
          val skillFile = resolveSkillFile(subDir)
          skillFile.map { f =>
            parseSkillFile(f, source, Some(subDir.baseName))
          }
        }
        .toList

  /**
   * Find the skill file in a directory.
   * Priority: SKILL.md > skill.md
   */
  private def resolveSkillFile(dir: os.Path): Option[os.Path] =
    val upper = dir / "SKILL.md"
    if os.isFile(upper) then Some(upper)
    else
      val lower = dir / "skill.md"
      if os.isFile(lower) then Some(lower)
      else None

  // ============================================================
  // Legacy commands format: single .md files (Claude Code /commands/)
  // ============================================================

  private def loadFromCommandsDir(dir: os.Path): List[SkillInfo] =
    if !os.isDir(dir) then Nil
    else
      os.list(dir)
        .filter(f => os.isFile(f) && f.ext == "md")
        .flatMap { f =>
          val skillName = f.baseName // filename without .md extension
          Some(parseSkillFile(f, "commands", Some(skillName)))
        }
        .toList

  // ============================================================
  // Frontmatter parsing
  // ============================================================

  private def parseSkillFile(filePath: os.Path, source: String, nameOverride: Option[String]): SkillInfo =
    val content = os.read(filePath)
    val fm = extractFrontmatter(content)

    val name = extractField(fm, "name")
      .orElse(nameOverride)
      .getOrElse(filePath.baseName)

    val description = extractField(fm, "description").getOrElse("")
    val whenToUse = extractField(fm, "when_to_use").orElse(extractField(fm, "when-to-use"))
    val argumentHint = extractField(fm, "argument-hint")
    val version = extractField(fm, "version")
    val userInvocable = extractField(fm, "user-invocable")
      .map(v => v == "true" || v == "true")
      .getOrElse(true)

    val allowedTools = extractListField(fm, "allowed-tools")
    val argumentNames = extractListField(fm, "arguments")

    SkillInfo(
      name = name,
      description = description,
      filePath = filePath.toString,
      whenToUse = whenToUse,
      allowedTools = allowedTools,
      argumentHint = argumentHint,
      argumentNames = argumentNames,
      userInvocable = userInvocable,
      version = version,
      source = source
    )

  end parseSkillFile

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
      .find(line => line.startsWith(s"$field:") || line.startsWith(s"$field :"))
      .map { line =>
        val idx = line.indexOf(':')
        line.substring(idx + 1).trim
      }
      .map(_.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'"))
      .filter(_.nonEmpty)

  /**
   * Parse a YAML list field from frontmatter.
   * Supports two formats:
   *   allowed-tools: tool1, tool2, tool3
   *   arguments:
   *     - arg1
   *     - arg2
   */
  private def extractListField(frontmatter: String, field: String): List[String] =
    // Try comma-separated inline format first: "field: a, b, c"
    extractField(frontmatter, field) match
      case Some(inline) if inline.nonEmpty =>
        inline.split(",").map(_.trim).filter(_.nonEmpty).toList
      case _ =>
        // Try YAML block list format:
        //   arguments:
        //     - arg1
        //     - arg2
        val lines = frontmatter.split("\n")
        val fieldStart = lines.indexWhere(l =>
          val trimmed = l.trim
          trimmed.startsWith(s"$field:") || trimmed.startsWith(s"$field :")
        )
        if fieldStart < 0 then Nil
        else
          lines
            .drop(fieldStart + 1)
            .map(_.trim)
            .takeWhile(l => l.startsWith("- "))
            .map(_.stripPrefix("- ").trim)
            .filter(_.nonEmpty)
            .toList

end SkillService
