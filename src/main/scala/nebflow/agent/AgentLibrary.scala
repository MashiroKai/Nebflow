package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.presets.PresetStore
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.{AgentModelConfig, Defaults}

import scala.util.Try

// Agent definitions loaded from disk (~/.nebflow/agents/<name>/agent.json + system.md).
//
// Only Nebula is hardcoded as a fallback — if its disk files are missing or
// corrupted, the code definition keeps the system alive. All other agents
// are defined exclusively on disk; deleting their directory removes them.
class AgentLibrary(
  agentsDir: os.Path,
  serviceConfig: Option[NebflowServiceConfig] = None
):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  def globalContextWindow: Int =
    serviceConfig match
      case None => Defaults.ContextWindow
      case Some(cfg) =>
        val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.default)
        val provider = cfg.llm.providers
          .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        provider.models.find(_.id == modelId).map(_.contextWindow).getOrElse(Defaults.ContextWindow)

  def globalMaxTokens: Int =
    serviceConfig match
      case None => Defaults.MaxTokens
      case Some(cfg) =>
        val (providerId, modelId) = Config.parseModelRef(cfg.llm.model.default)
        val provider = cfg.llm.providers
          .getOrElse(providerId, throw new RuntimeException(s"Unknown provider: $providerId"))
        provider.models.find(_.id == modelId).map(_.maxTokens).getOrElse(Defaults.MaxTokens)

  // ============================================================
  // Public API
  // ============================================================

  /** Seed agent.json + system.md for default agents (first install only). */
  def seedDefaults(): IO[Unit] = IO.blocking {
    Seeds.all.foreach { agent =>
      val dir = agentsDir / agent.name
      os.makeDir.all(dir)
      // Write agent.json if it doesn't exist (don't overwrite user edits)
      val jsonPath = dir / "agent.json"
      if !os.exists(jsonPath) then
        os.write.over(jsonPath, agent.toJson)
        logger.info(s"Seeded agent.json for: ${agent.name}")
      // Write system.md if it doesn't exist
      if agent.systemPrompt.nonEmpty && !os.exists(dir / "system.md") then
        os.write.over(dir / "system.md", agent.systemPrompt)
        logger.info(s"Seeded system.md for: ${agent.name}")
    }
  }

  // Load all agents from disk. Scans all agent.json files in the agents directory.
  // Nebula is guaranteed to exist — falls back to code definition if
  // missing or corrupted on disk.
  def loadAll(): IO[Map[String, AgentDef]] = IO.blocking {
    val diskAgents = scanDisk()

    // Ensure Nebula always exists (system survival guarantee)
    if diskAgents.contains(Seeds.Nebula.name) then diskAgents
    else
      logger.warn("Nebula not found on disk — using code fallback")
      diskAgents + (Seeds.Nebula.name -> Seeds.Nebula.toAgentDef)
  }

  /** Get a single agent by name. */
  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

  /** Write a system.md file for the given agent. */
  def updateSystemPrompt(name: String, content: String): IO[Unit] = IO.blocking {
    val dir = agentsDir / name
    os.makeDir.all(dir)
    os.write.over(dir / "system.md", content)
  }

  /**
   * Update the tools list in agent.json. Reads the existing file, filters out
   * fixed tools (auto-injected by system) and wildcard, writes only the
   * configurable tools.
   */
  def updateTools(name: String, tools: List[String]): IO[Unit] = IO.blocking {
    val jsonPath = agentsDir / name / "agent.json"
    if os.exists(jsonPath) then
      val json = os.read(jsonPath)
      io.circe.parser.parse(json).toOption match
        case Some(parsed) =>
          // Filter out fixed tools and wildcard — they're auto-injected
          val defn = loadFromDir(agentsDir / name).getOrElse(AgentDef(name = name, description = ""))
          val fixed = AgentCore.fixedToolsFor(defn)
          val configurable = tools.filterNot(t => t == "*" || fixed.contains(t))
          val updated = parsed.deepMerge(io.circe.Json.obj("tools" -> configurable.asJson))
          os.write.over(jsonPath, updated.noSpaces)
        case None => () // skip if unparseable
  }

  /**
   * Update the model configuration in agent.json. Reads the existing file,
   *  merges the "model" field, and writes it back.
   *
   * @return true if updated, false if agent.json not found.
   * @throws RuntimeException if the existing JSON is corrupt.
   */
  def updateModel(name: String, config: AgentModelConfig): IO[Boolean] = IO.blocking {
    val jsonPath = agentsDir / name / "agent.json"
    if !os.exists(jsonPath) then false
    else
      val json = os.read(jsonPath)
      io.circe.parser.parse(json) match
        case Right(parsed) =>
          val updated = parsed.deepMerge(io.circe.Json.obj("model" -> config.asJson))
          os.write.over(jsonPath, updated.noSpaces)
          true
        case Left(err) =>
          throw new RuntimeException(s"Failed to parse agent.json for '$name': ${err.getMessage}")
  }

  /** Read a system.md file. */
  def readSystemPrompt(name: String): IO[Option[String]] = IO.blocking {
    val p = agentsDir / name / "system.md"
    if os.exists(p) then Some(os.read(p)) else None
  }

  /** Reload from disk (no cache, so it's a no-op). */
  def refresh(): IO[Unit] = IO.unit

  // ============================================================
  // Disk scanning
  // ============================================================

  /**
   * Load a single agent from a directory containing agent.json + system.md.
   *  Shared between global scanning and project-level overrides.
   */
  def loadFromDir(dir: os.Path): Option[AgentDef] =
    val jsonPath = dir / "agent.json"
    if !os.exists(jsonPath) then None
    else
      parseAgentJson(jsonPath) match
        case Some(j) =>
          val prompt = readSystemMd(dir / "system.md")
          // Resolve preset/legacy model into the final AgentModelConfig.
          // Priority: explicit preset > legacy model > default preset > global chain.
          val (resolvedModel, _) = PresetStore().resolve(j.preset, j.model)
          Some(
            AgentDef(
              name = j.name,
              description = j.description.getOrElse(""),
              tools = j.tools,
              systemPrompt = prompt,
              avatar = j.avatar,
              displayName = j.displayName,
              voiceEnabled = j.voice.getOrElse(false),
              model = Some(resolvedModel),
              preset = j.preset,
              category = j.category.getOrElse("standalone"),
              mcpServers = j.mcpServers.getOrElse(Nil),
              skills = j.skills.getOrElse(Nil),
              flows = j.flows.getOrElse(Nil)
            )
          )
        case None =>
          logger.warn(s"Skipping invalid or unreadable: ${jsonPath.toString}")
          None

    end if

  end loadFromDir

  /**
   * Load a project-specific agent from a folder directory.
   *  Looks in `~/.nebflow/folders/<folderId>/agents/<name>/`.
   *  Returns None if no folder agent directory exists.
   */
  def loadFolderAgent(folderId: String, name: String): IO[Option[AgentDef]] = IO.blocking {
    val dir = PathUtil.dataRoot / "folders" / folderId / "agents" / name
    if !os.isDir(dir) then None
    else loadFromDir(dir)
  }

  private def scanDisk(): Map[String, AgentDef] =
    if !os.exists(agentsDir) then Map.empty
    else
      os.list(agentsDir)
        .filter(os.isDir)
        .flatMap { dir => loadFromDir(dir).map(d => d.name -> d) }
        .toMap

  private def parseAgentJson(path: os.Path): Option[AgentJson] =
    for
      content <- Try(os.read(path)).toOption
      agentJson <- io.circe.parser.decode[AgentJson](content).toOption
    yield
      logger.info(s"Loaded agent: ${agentJson.name}")
      agentJson

  private def readSystemMd(path: os.Path): String =
    if os.exists(path) then os.read(path) else ""

end AgentLibrary

// ============================================================
// agent.json schema
// ============================================================

private case class AgentJson(
  name: String,
  displayName: Option[String] = None,
  description: Option[String] = None,
  useWhen: Option[String] = None,
  tools: List[String] = List("*"),
  mcpServers: Option[List[String]] = None,
  avatar: Option[String] = None,
  voice: Option[Boolean] = None,
  model: Option[AgentModelConfig] = None,
  preset: Option[String] = None,
  category: Option[String] = None,
  skills: Option[List[String]] = None,
  flows: Option[List[String]] = None
)

private object AgentJson:

  given Decoder[AgentJson] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      displayName <- c.downField("displayName").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      useWhen <- c.downField("useWhen").as[Option[String]]
      tools <- c.downField("tools").as[Option[List[String]]]
      mcpServers <- c.downField("mcpServers").as[Option[List[String]]]
      avatar <- c.downField("avatar").as[Option[String]]
    yield AgentJson(
      name,
      displayName,
      description,
      useWhen,
      tools.getOrElse(List("*")),
      mcpServers,
      avatar,
      voice = c.downField("voice").as[Option[Boolean]].toOption.flatten,
      model = c.downField("model").as[Option[AgentModelConfig]].toOption.flatten,
      preset = c.downField("preset").as[Option[String]].toOption.flatten,
      category = c.downField("category").as[Option[String]].toOption.flatten,
      skills = c.downField("skills").as[Option[List[String]]].toOption.flatten,
      flows = c.downField("flows").as[Option[List[String]]].toOption.flatten
    )
  }

  given Encoder[AgentJson] = Encoder.instance { j =>
    Json
      .obj(
        "name" -> j.name.asJson,
        "displayName" -> j.displayName.asJson,
        "description" -> j.description.asJson,
        "useWhen" -> j.useWhen.asJson,
        "tools" -> j.tools.asJson,
        "mcpServers" -> j.mcpServers.asJson
      )
      .deepMerge(j.avatar.map(a => Json.obj("avatar" -> a.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.voice.map(v => Json.obj("voice" -> v.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.model.map(m => Json.obj("model" -> m.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.preset.map(p => Json.obj("preset" -> p.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.category.map(c => Json.obj("category" -> c.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.skills.map(s => Json.obj("skills" -> s.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.flows.map(f => Json.obj("flows" -> f.asJson)).getOrElse(Json.obj()))
  }
end AgentJson

// ============================================================
// Seed definitions (for initial install + Nebula fallback)
// ============================================================

private case class SeedAgent(
  name: String,
  displayName: Option[String],
  description: String,
  tools: List[String],
  systemPrompt: String
):

  def toAgentDef: AgentDef = AgentDef(
    name = name,
    description = description,
    tools = tools,
    systemPrompt = systemPrompt,
    displayName = displayName,
    category = "standalone"
  )

  def toJson: String =
    val agentJson = AgentJson(name, displayName, Some(description), None, tools, None, None)
    agentJson.asJson.noSpaces

end SeedAgent

private object Seeds:

  val Nebula = SeedAgent(
    "Nebula",
    Some("Nebula"),
    "Orchestrator — delegates all execution to specialized Teams and Flows",
    List(
      "AskUserQuestion",
      "Bash",
      "Curl",
      "Glob",
      "Grep",
      "Load",
      "Mail",
      "Pop",
      "Read",
      "Schedule",
      "TaskCreate",
      "TaskUpdate",
      "TransferFile",
      "WebFetch",
      "WebSearch"
    ),
    """You are Nebula, the orchestrator of Nebflow. Your role is to understand user intent, route tasks to the right agents, and deliver results — not to implement features yourself.

## Core Principle

You are NOT a worker. You are the bridge between the user and the agent ecosystem. Use Read/Grep/Glob/Bash only for quick exploration to make routing decisions. All implementation work goes through Teams and Flows.

## Task Routing

### Teams (persistent, project-level work)
Teams are long-running agent groups organized by project. Each Team has a Lead who coordinates members internally.
- Load: `Load(type: "team", name: "my-project")` — reads team.json from disk, validates, and mounts. If there are errors, they are reported.
- Define teams by writing `~/.nebflow/teams/<name>/team.json` directly (name, description, lead, members, flows).
- Trigger: `Mail("team-name", "your task")` — Mail the team by its project name. The Lead coordinates internal agents and Mails you back when done.
- Team agents have persistent sessions with memory — they remember past work.

### Flows (one-shot pipelines, no memory)
Flows are task pipelines that run once with fresh context. No memory between runs.
- Trigger: `Mail("flow-name", "task description")` — e.g. `Mail("entity-creator", "Create an agent for ...")`
- The pipeline runs in the background. Result is delivered via Mail when complete.
- Flows are defined in `~/.nebflow/flows/<name>.json` (DAG format).

### When to use what
- **Load + Mail (Team)**: Load a team and Mail its lead for project-level work
- **Mail (Flow)**: One-shot pipeline tasks like code review, entity creation, releases
- **Direct tools**: Quick exploration (Read/Grep/Glob) to understand a problem before routing

## User Interaction
- You are the only agent that talks to the user directly. Be friendly, concise, and proactive.
- Use AskUserQuestion for decisions that need user input. Don't ask unless truly necessary.
- Keep the user informed of progress. Summarize results when agents complete work.
- When in doubt about routing, make a reasonable decision rather than asking."""
  )

  val Explorer = SeedAgent(
    "Explorer",
    None,
    "Code exploration and research",
    List("Read", "Glob", "Grep", "Bash", "WebSearch", "WebFetch"),
    """You are Explorer, an investigation sub-agent.

## Your Role

You investigate codebases and report findings. You CANNOT modify files, but you CAN run commands to gather information.

## Rules

- Use Read, Grep, Glob to explore the codebase thoroughly.
- Use Bash for read-only investigation commands: git log, git status, git diff, pytest --collect-only, ls, find, wc, etc.
- Do NOT use Bash to modify files — no writes, no deletes, no commits. Write/Edit tools are not available to you.
- Report specific file paths, line numbers, and relevant code snippets.
- Structure your findings clearly: list each discovery with its location.
- When you finish, produce a concise summary of everything you found."""
  )

  val Coder = SeedAgent(
    "Coder",
    Some("Coder"),
    "Deep coding specialist — implementation, debugging, refactoring",
    List("Read", "Write", "Edit", "Bash", "Grep", "Glob", "WebSearch", "WebFetch", "TransferFile"),
    """You are Coder, a deep coding specialist running inside Nebflow.

You are invoked when actual code work is needed — implementation, debugging, refactoring, testing. You are NOT an orchestrator: you do not manage flows, delegate to other agents, or handle high-level user interaction. You receive a coding task and execute it with precision.

## Engineering Philosophy

Apply this in order before acting on any task.

### 1. Understand before acting

Never write code you don't understand. Never fix a bug whose cause you can't explain.

- Read the relevant code path before making changes.
- Trace bugs from symptom to root cause before writing any fix.
- Do not write defensive code for hypothetical failure modes — every guard must be justified by a real, traceable code path.

### 2. Delete what shouldn't exist

- Don't add code "just in case."
- Delete first, then ask if it's needed.
- Unused code only rots.

### 3. Simplify

- The simplest correct solution is the best solution.
- Complexity must be justified, not assumed.

## Code Safety

- Command injection: never interpolate user-controlled strings into shell commands.
- XSS: escape user-controlled data in HTML output.
- SQL injection: use parameterized queries.
- Path traversal: validate file paths.
- Secrets: never hardcode API keys, passwords, or tokens.

Validate at system boundaries, not internal function calls.

## Output Style

- Lead with the answer. Conclusion first.
- Plain language. Define every term on first use.
- Errors: state the problem, explain the cause, say what you'll do, then do it.
- No emoji.

## Git Discipline

- Commit after each meaningful unit of work, not at the end of a marathon.
- Write commit messages that explain why, not what.
- Never commit files with secrets.
- Work on feature branches, never directly on main."""
  )

  /** All seeds for initial installation. Only Nebula is a runtime fallback. */
  val all = List(Nebula, Explorer, Coder)

end Seeds

object AgentLibrary:
  def defaultDir: os.Path = PathUtil.dataRoot / "agents"
end AgentLibrary
