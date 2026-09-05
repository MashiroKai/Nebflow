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
  /** Owning team (frontmatter `audience`): real team name, e.g. nebflow-project — not a namespace. */
  audience: Option[String] = None,
  /** Last date the content was verified against reality (frontmatter `last_verified`, YYYY-MM-DD). */
  lastVerified: Option[String] = None,
  /** Lifecycle state: draft / active / deprecated (frontmatter `status`). */
  status: Option[String] = None,
  /** Successor skill for a deprecated one (frontmatter `replaced_by`). */
  replacedBy: Option[String] = None,
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
      "audience" -> s.audience.asJson,
      "lastVerified" -> s.lastVerified.asJson,
      "status" -> s.status.asJson,
      "replacedBy" -> s.replacedBy.asJson,
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
    // s-interpolation: documents the REAL data dir (brand.conf homeDirName;
    // the "nebflow/visual-style" example stays — it is a live namespace
    // skill, a data fact rather than a brand string).
    s"""---
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
      |Skills live at `~/${nebflow.core.Branding.homeDirName}/skills/<skill-name>/`. The `name` field in frontmatter must match the directory name. Namespaced skills live two levels deep: `~/${nebflow.core.Branding.homeDirName}/skills/<ns>/<name>/SKILL.md` — their identifier is the relative path (`<ns>/<name>`, e.g. `nebflow/visual-style`), and agent.json `skills` entries must use the full path.
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
      || `audience` | No | — | Team this skill semantically belongs to — real team name (e.g. `nebflow-project`), not a namespace; subscription stays per-agent |
      || `last_verified` | No | — | Date (YYYY-MM-DD) the content was last verified against reality; audited when older than 90 days |
      || `status` | No | active | Lifecycle state: `draft` / `active` / `deprecated` |
      || `replaced_by` | No | — | Successor skill identifier, shown to subscribers when this skill is deprecated |
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
      |
      |## Evidence (user-ruling provenance)
      |
      |When a skill originates from a user correction, add a `## Evidence` section:
      |
      |- Quote the user **verbatim**, one entry per ruling, with source and timestamp — e.g. `("以后弹窗都用毛玻璃" — input_history.jsonl, 2026-08-14)`
      |- The practice itself belongs in the body (Steps/Rules), never inside Evidence — Evidence is provenance, not instruction
      |- Nebula's final review spot-checks Evidence against the recorded user input; missing or paraphrased entries fail the audit
      |""".stripMargin

  // ============================================================
  // Skill Catalog (for progressive disclosure)
  // ============================================================

  /**
   * Skills that are always appended to any injected catalog, even when no agent
   * declared them (R3 §3.3-1: the discovery/creation meta entry point). Only
   * takes effect when a catalog is produced at all — an agent with an empty
   * skills declaration still gets no catalog section.
   */
  val alwaysVisible: List[String] = List("skill-creator")

  /**
   * Build a skill catalog containing only the skills declared by the agent.
   * Empty list → empty string (no injection, no global fallback).
   * The wildcard "*" subscribes to every skill in the library (same semantics as
   * the flows whitelist) — used by orchestrators that need the full index.
   *
   * Mirrors the modelInvocable rule of the (removed) global catalog: skills
   * marked `disable-model-invocation: true` are slash-command-only and stay
   * out even when the agent declares them. when_to_use frontmatter is kept
   * in the entry — it is the anti-misuse metadata the author wrote for
   * exactly this moment.
   */
  def buildPerAgentCatalog(skillNames: List[String]): IO[String] =
    if skillNames.isEmpty then IO.pure("")
    else
      listSkills().map { allSkills =>
        val visibleIn: SkillInfo => Boolean = s => s.modelInvocable && s.description.nonEmpty
        val declared =
          if skillNames.contains("*") then allSkills.filter(visibleIn)
          else skillNames.flatMap(allSkills.map(s => s.name -> s).toMap.get).filter(visibleIn)
        // Bootstrap entry points are appended unconditionally (dedup when declared)
        val appended = allSkills
          .filter(s => alwaysVisible.contains(s.name) && visibleIn(s))
          .filterNot(s => declared.exists(_.name == s.name))
        val visible = declared ++ appended
        if visible.isEmpty then ""
        else
          val entries = visible.map { s =>
            val when = s.whenToUse.filter(_.nonEmpty).map(w => s" [when: $w]").getOrElse("")
            s"- ${s.name}: ${s.description.take(200)}$when"
          }.mkString("\n")
          s"""# Skills
             |
             |Skills live at ~/${nebflow.core.Branding.homeDirName}/skills/<name>/SKILL.md. When a task matches a skill, read its file for detailed instructions, scripts, and resources.
             |
             |$entries""".stripMargin
      }

  // buildPerAgentFlowCatalog retired 2026-09-06 (tool-face batch): the
  // FlowTrigger tool it advertised is gone, so the per-agent flows whitelist
  // catalog has no consumer. ContextRefresher now injects an empty
  // flowCatalog section (same pattern as the D.1-12 skill-catalog stop).

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
   * Delete a user-level skill by its identifier ("name" or "ns/name").
   * Only skills under ~/.nebflow/skills/ can be deleted (source = "user").
   * Project-level and legacy command skills are managed via version control.
   * Returns true if the skill was found and deleted, false otherwise.
   */
  def deleteSkill(name: String): IO[Boolean] = IO.delay {
    val segments = name.split('/').map(_.trim).filter(_.nonEmpty).toList
    // Namespaced ids resolve to nested dirs; refuse traversal segments
    val skillDir =
      if segments.exists(s => s == "." || s == "..") then None
      else Some(segments.foldLeft(userSkillsDir)((d, seg) => d / seg))
    skillDir match
      case Some(dir) if os.isDir(dir) =>
        os.remove.all(dir)
        logger.info(s"Deleted skill '$name' from $dir")
        true
      case _ =>
        logger.warn(s"Cannot delete skill '$name': directory not found or not a user-level skill")
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

  /** Project-level skill directories to scan, in priority order. Both the
    * brand dir name and the hardcoded legacy ".nebflow" are listed — project
    * dirs belong to the user's repo and are never migrated, so rename-day
    * projects on either name keep loading (distinct collapses the current
    * identical pair). */
  private def projectSkillPaths: List[os.Path] =
    val cwd = os.pwd
    List(
      cwd / nebflow.core.Branding.homeDirName / "skills",
      cwd / ".nebflow" / "skills",
      cwd / ".claude" / "skills"
    ).distinct

  /** Project-level legacy command directories to scan. */
  private def projectCommandPaths: List[os.Path] =
    val cwd = os.pwd
    List(
      cwd / nebflow.core.Branding.homeDirName / "commands",
      cwd / ".nebflow" / "commands",
      cwd / ".claude" / "commands"
    ).distinct

  private def loadProjectSkills(): List[SkillInfo] =
    projectSkillPaths.flatMap(dir => loadFromSkillsDir(dir, "project"))

  private def loadLegacyCommands(): List[SkillInfo] =
    projectCommandPaths.flatMap(dir => loadFromCommandsDir(dir))

  // ============================================================
  // Directory format: skill-name/SKILL.md or skill-name/skill.md
  // ============================================================

  /**
   * Scan a skills directory two levels deep:
   *   - flat:      <dir>/<name>/SKILL.md         → identifier = frontmatter name (fallback dir name)
   *   - namespace: <dir>/<ns>/<name>/SKILL.md    → identifier = relative path "<ns>/<name>"
   * A namespace directory may itself contain a SKILL.md (then it is ALSO a flat skill) —
   * both load. Deeper nesting is ignored, matching the previous single-level behavior.
   */
  private def loadFromSkillsDir(dir: os.Path, source: String): List[SkillInfo] =
    if !os.isDir(dir) then Nil
    else
      os.list(dir)
        .filter(sub => os.isDir(sub) && sub.baseName != "_example")
        .flatMap { subDir =>
          // Prefer SKILL.md (Claude Code convention), fall back to skill.md (Nebflow convention)
          val flat = resolveSkillFile(subDir).map { f =>
            parseSkillFile(f, source, Some(subDir.baseName), relativeName = None)
          }
          // Namespaced skills: the relative path is the authoritative identifier —
          // a mismatched frontmatter name must not break subscription by path.
          val nested = os.list(subDir)
            .filter(nameDir => os.isDir(nameDir) && nameDir.baseName != "_example")
            .flatMap { nameDir =>
              val relName = s"${subDir.baseName}/${nameDir.baseName}"
              resolveSkillFile(nameDir).map { f =>
                parseSkillFile(f, source, Some(relName), relativeName = Some(relName))
              }
            }
          flat ++ nested
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

  private def parseSkillFile(
    filePath: os.Path,
    source: String,
    nameOverride: Option[String],
    relativeName: Option[String] = None
  ): SkillInfo =
    val content = os.read(filePath)
    val fm = extractFrontmatter(content)

    // Consistency rule: flat skill name = frontmatter name (fallback: dir name);
    // namespaced skill name = relative path "<ns>/<name>" (frontmatter cannot override).
    val name = relativeName match
      case Some(rel) => rel
      case None => extractField(fm, "name").orElse(nameOverride).getOrElse(filePath.baseName)

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

    // Lifecycle / governance metadata (optional, consumed by skill audit)
    val audience = extractField(fm, "audience")
    val lastVerified = extractField(fm, "last_verified")
    val status = extractField(fm, "status")
    val replacedBy = extractField(fm, "replaced_by")

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
      audience = audience,
      lastVerified = lastVerified,
      status = status,
      replacedBy = replacedBy,
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
