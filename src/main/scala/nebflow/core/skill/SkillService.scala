package nebflow.core.skill

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import nebflow.core.entity.EntityLoader
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
  modelInvocable: Boolean = true,
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
      "modelInvocable" -> s.modelInvocable.asJson,
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

  /** Create user-level skills directory and built-in skills on first run. */
  def ensureDefaults(): IO[Unit] = IO.delay {
    if !os.isDir(userSkillsDir) then
      os.makeDir.all(userSkillsDir)
      logger.info(s"Created skills directory at $userSkillsDir")
    // Starter template
    val exampleFile = userSkillsDir / "_example" / "SKILL.md"
    if !os.isFile(exampleFile) then os.write(exampleFile, exampleSkillMd, createFolders = true)
    // Built-in skill-creator
    val creatorFile = userSkillsDir / "skill-creator" / "SKILL.md"
    if !os.isFile(creatorFile) then
      os.write(creatorFile, skillCreatorMd, createFolders = true)
      logger.info("Created built-in skill-creator skill")
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

  private val skillCreatorMd: String =
    """---
      |name: skill-creator
      |description: Create and update Nebflow skills — directory structure, frontmatter fields, scripts, and best practices. Use when the user asks to create, modify, or learn about skills.
      |language: zh
      |---
      |
      |# Skill Creator
      |
      |You are creating or updating a Nebflow skill. A skill is a reusable capability package that combines prompt instructions with bundled resources (scripts, references, assets).
      |
      |## Standard Directory Structure
      |
      |```
      |skill-name/
      |├── SKILL.md              # Required — frontmatter + instructions
      |├── scripts/              # Optional — executable code (Python, Bash, etc.)
      |├── references/           # Optional — docs loaded into context on demand
      |└── assets/               # Optional — output files (templates, icons, fonts, etc.)
      |```
      |
      |Skills live at `~/.nebflow/skills/<skill-name>/`. The `name` field in frontmatter must match the directory name.
      |
      |## Frontmatter (YAML)
      |
      |Only `name` and `description` are required. The description is the sole text the agent sees in its skill catalog — it must explain both **what** the skill does and **when** to use it.
      |
      || Field | Required | Default | Description |
      ||-------|----------|---------|-------------|
      || `name` | Yes | dir name | Skill identifier, must match directory name |
      || `description` | Yes | — | One-line summary shown in agent catalog. Determines whether the agent recognizes the skill as relevant |
      || `language` | No | — | Preferred response language (zh, en) |
      || `user-invocable` | No | true | Whether users can invoke via `/skill-name` slash command |
      || `disable-model-invocation` | No | false | When true, skill is hidden from agent catalog (slash-command only) |
      || `version` | No | — | Semantic version string |
      || `when_to_use` | No | — | Additional context for when to use this skill |
      || `allowed-tools` | No | all | Comma-separated tool allowlist |
      || `arguments` | No | — | Argument names (YAML block list or comma-separated) |
      |
      |## ${SKILL_DIR} Variable
      |
      |`${SKILL_DIR}` is replaced with the skill's absolute directory path at load time. Use it to reference bundled resources:
      |
      |```bash
      |python ${SKILL_DIR}/scripts/analyze.py --input data.csv
      |cat ${SKILL_DIR}/references/spec.md
      |cp ${SKILL_DIR}/assets/template.tex output/
      |```
      |
      |## Progressive Disclosure
      |
      |1. **Catalog (always in system prompt)**: Agent sees `name` + `description` every turn.
      |2. **Full content (on demand)**: Agent reads `SKILL.md` when it decides the skill is relevant, getting instructions plus access to bundled scripts and resources.
      |
      |## How to Create a Skill
      |
      |1. Choose a kebab-case name (e.g., `code-reviewer`, `api-tester`)
      |2. Create `~/.nebflow/skills/<name>/SKILL.md` with frontmatter + instructions
      |3. Add `scripts/`, `references/`, or `assets/` subdirectories as needed
      |4. Test: ask the agent to use it, or invoke via `/<name>`
      |
      |## Best Practices
      |
      |- **Description is critical** — it is the only signal for the agent to decide relevance. Be specific about what AND when.
      |- **Scripts do real work** — bundle executable scripts in `scripts/` rather than describing steps in prose.
      |- **References for deep context** — put large docs in `references/` that the agent can Read on demand, keeping SKILL.md concise.
      |- **One skill = one purpose** — don't combine unrelated workflows.
      |""".stripMargin

  // ============================================================
  // Skill Catalog (for progressive disclosure)
  // ============================================================

  /** Simple TTL cache to avoid rebuilding the catalog every turn. */
  @volatile private var catalogCache: (Long, String) = (0L, "")
  private val CatalogTtlMs = 3000L

  /**
   * Build a compact skill catalog string for system prompt injection.
   * Only skills with `modelInvocable = true` and a non-empty description are included.
   * The catalog tells the agent what skills exist and where to find them;
   * the agent reads the full skill file when it decides a skill is relevant.
   */
  /**
   * Build a skill catalog containing only the skills declared by the agent.
   * Empty list → empty string (no injection, no global fallback).
   */
  def buildPerAgentCatalog(skillNames: List[String]): IO[String] =
    if skillNames.isEmpty then IO.pure("")
    else
      listSkills().map { allSkills =>
        val skillMap = allSkills.map(s => s.name -> s).toMap
        val visible = skillNames.flatMap(skillMap.get).filter(_.description.nonEmpty)
        if visible.isEmpty then ""
        else
          val entries = visible.map(s => s"- ${s.name}: ${s.description.take(200)}").mkString("\n")
          s"""# Skills
             |
             |Skills live at ~/.nebflow/skills/<name>/SKILL.md. When a task matches a skill, read its file for detailed instructions, scripts, and resources.
             |
             |$entries""".stripMargin
      }

  /**
   * Build a flow catalog containing only the flows declared by the agent.
   * Empty list → empty string (no injection).
   */
  def buildPerAgentFlowCatalog(flowNames: List[String]): IO[String] =
    if flowNames.isEmpty then IO.pure("")
    else
      flowNames.traverse(name => EntityLoader.loadFlow(name)).map { opts =>
        val visible = opts.flatten.filter(_.description.nonEmpty)
        if visible.isEmpty then ""
        else
          val entries = visible.map(f => s"- ${f.name}: ${f.description.take(200)}").mkString("\n")
          s"""# Available Flows
             |
             |Trigger via FlowTrigger(flow="<name>", prompt="<task input>"). The flow runs in the background; its result is delivered to you when it completes.
             |
             |$entries""".stripMargin
      }

  def buildSkillCatalog(currentDelegateCount: Int): IO[String] =
    val now = System.currentTimeMillis()
    if now - catalogCache._1 < CatalogTtlMs then IO.pure(catalogCache._2)
    else
      listSkills().map { skills =>
        val visible = skills
          .filter(s => s.modelInvocable && s.description.nonEmpty)
        val catalog =
          if visible.isEmpty then ""
          else
            val entries = visible
              .map { s =>
                s"- ${s.name}: ${s.description.take(200)}"
              }
              .mkString("\n")
            s"""# Skills
               |
               |Skills live at ~/.nebflow/skills/<name>/SKILL.md. When a task matches a skill, read its file for detailed instructions, scripts, and resources.
               |
               |$entries""".stripMargin
        catalogCache = (now, catalog)
        catalog
      }
    end if
  end buildSkillCatalog

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

  /**
   * Delete a user-level skill by name.
   * Only skills under ~/.nebflow/skills/ can be deleted (source = "user").
   * Project-level and legacy command skills are managed via version control.
   * Returns true if the skill was found and deleted, false otherwise.
   */
  def deleteSkill(name: String): IO[Boolean] = IO.delay {
    val skillDir = userSkillsDir / name
    if os.isDir(skillDir) then
      os.remove.all(skillDir)
      logger.info(s"Deleted skill '$name' from $skillDir")
      true
    else
      logger.warn(s"Cannot delete skill '$name': directory $skillDir not found or not a user-level skill")
      false
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
      .map(v => v.equalsIgnoreCase("true"))
      .getOrElse(true)
    val modelInvocable = extractField(fm, "disable-model-invocation")
      .map(v => !v.equalsIgnoreCase("true"))
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
      modelInvocable = modelInvocable,
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
