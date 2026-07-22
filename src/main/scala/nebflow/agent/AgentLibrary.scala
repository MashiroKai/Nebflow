package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.Defaults

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

  private def scanDisk(): Map[String, AgentDef] =
    if !os.exists(agentsDir) then Map.empty
    else
      os.list(agentsDir)
        .filter(os.isDir)
        .flatMap { dir =>
          val jsonPath = dir / "agent.json"
          if !os.exists(jsonPath) then None
          else
            parseAgentJson(jsonPath) match
              case Some(j) =>
                val prompt = readSystemMd(dir / "system.md")
                Some(
                  j.name -> AgentDef(
                    name = j.name,
                    description = j.description.getOrElse(""),
                    tools = j.tools,
                    systemPrompt = prompt,
                    avatar = j.avatar,
                    displayName = j.displayName
                  )
                )
              case None =>
                logger.warn(s"Skipping invalid or unreadable: ${jsonPath.toString}")
                None
          end if
        }
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
  tools: List[String] = List("*"),
  mcpServers: Option[List[String]] = None,
  avatar: Option[String] = None
)

private object AgentJson:

  given Decoder[AgentJson] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      displayName <- c.downField("displayName").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      tools <- c.downField("tools").as[Option[List[String]]]
      mcpServers <- c.downField("mcpServers").as[Option[List[String]]]
      avatar <- c.downField("avatar").as[Option[String]]
    yield AgentJson(name, displayName, description, tools.getOrElse(List("*")), mcpServers, avatar)
  }

  given Encoder[AgentJson] = Encoder.instance { j =>
    Json
      .obj(
        "name" -> j.name.asJson,
        "displayName" -> j.displayName.asJson,
        "description" -> j.description.asJson,
        "tools" -> j.tools.asJson,
        "mcpServers" -> j.mcpServers.asJson
      )
      .deepMerge(j.avatar.map(a => Json.obj("avatar" -> a.asJson)).getOrElse(Json.obj()))
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
    displayName = displayName
  )

  def toJson: String =
    val agentJson = AgentJson(name, displayName, Some(description), tools, None, None)
    agentJson.asJson.noSpaces

end SeedAgent

private object Seeds:

  val Nebula = SeedAgent(
    "Nebula",
    Some("Nebula"),
    "AI coding assistant with full tool access",
    List("*"),
    """You are Nebula, an AI coding assistant running inside Nebflow.

## Session Management

- If the user asks for help, direct them to `/help`.
- The Companion (Pickle) is a separate system. When the user addresses Pickle, stay out of the way — respond in one line or less for any part meant for you. Do not explain that you're not Pickle.

## Delegation

When to use ExecuteFlow:
- A task can be broken into independent parts that benefit from focused context.
- You need parallel research on different aspects of a problem.
- A subtask requires deep focus without polluting your main conversation.
- You need a repeatable pipeline with verification and fix loops.

Rules:
- Write flow definitions that are self-contained.
- Flag risks and tradeoffs explicitly.
- Keep the user informed of progress.
- When in doubt, ask rather than assume."""
  )

  val Explorer = SeedAgent(
    "Explorer",
    None,
    "Code exploration and research",
    List("Read", "Glob", "Grep", "Bash", "WebSearch", "WebFetch", "RemoveUnnecessary"),
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
    List("Read", "Write", "Edit", "Bash", "Grep", "Glob", "WebSearch", "WebFetch", "RemoveUnnecessary", "TransferFile"),
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
