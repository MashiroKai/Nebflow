package nebflow.agent

import cats.effect.IO
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.Defaults

/**
 * Agent definitions are hardcoded in code — no agent.json files.
 *
 * Each builtin agent is fully defined in [[defaults]] (name, description, tools,
 * system prompt, avatar). Users can customize the system prompt by editing
 * the system.md file under `~/.nebflow/agents/<name>/system.md`.
 *
 * Tool lists use `List("*")` for full-access agents (resolved at runtime to all
 * builtin tools). MCP servers are global — all enabled servers are available
 * to every agent.
 */
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
  // Tool lists (single source of truth)
  // ============================================================

  lazy val builtinTools: Map[String, List[String]] = defaults.map(d => d.name -> d.tools).toMap

  def toolsFor(agentName: String): List[String] =
    builtinTools.getOrElse(agentName, List("*"))

  // ============================================================
  // Public API
  // ============================================================

  /** Seed system.md files to disk for user editing (no agent.json). */
  def seedDefaults(): IO[Unit] = IO.blocking {
    defaults.foreach { d =>
      val dir = agentsDir / d.name
      os.makeDir.all(dir)
      if d.systemMd.nonEmpty && !os.exists(dir / "system.md") then
        os.write.over(dir / "system.md", d.systemMd)
        logger.info(s"Seeded system.md for agent: ${d.name}")
    }
  }

  /** Load all agents from code, reading system.md from disk (overriding hardcoded prompt). */
  def loadAll(): IO[Map[String, AgentDef]] = IO.blocking {
    defaults.map { d =>
      val prompt = readSystemMd(d.name).getOrElse(d.systemMd)
      d.name -> AgentDef(
        name = d.name,
        description = d.description,
        tools = d.tools,
        systemPrompt = prompt,
        avatar = d.avatar,
        displayName = d.displayName
      )
    }.toMap
  }

  /** Get a single agent by name. */
  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

  /** Write a system.md file for the given agent (creates directory if needed). */
  def updateSystemPrompt(name: String, content: String): IO[Unit] = IO.blocking {
    val dir = agentsDir / name
    os.makeDir.all(dir)
    os.write.over(dir / "system.md", content)
  }

  /** Read a system.md file, returning None if it doesn't exist. */
  def readSystemPrompt(name: String): IO[Option[String]] = IO.blocking {
    readSystemMd(name)
  }

  /** No-op (kept for compatibility — no cache to invalidate). */
  def refresh(): IO[Unit] = IO.unit

  // ============================================================
  // Internal: read system.md from disk
  // ============================================================

  private def readSystemMd(name: String): Option[String] =
    val p = agentsDir / name / "system.md"
    if os.exists(p) then Some(os.read(p)) else None

  // ============================================================
  // Agent definitions (hardcoded)
  // ============================================================

  private case class DefaultAgent(
    name: String,
    description: String,
    displayName: Option[String],
    avatar: Option[String],
    tools: List[String],
    systemMd: String
  )

  private val defaults = List(
    DefaultAgent(
      "Nebula",
      "AI coding assistant with full tool access",
      Some("Nebula"),
      None,
      List("*"),
      """You are Nebula, an AI coding assistant running inside Nebflow.

## Session Management

- If the user asks for help, direct them to `/help`.
- The Companion (Pickle) is a separate system. When the user addresses Pickle, stay out of the way — respond in one line or less for any part meant for you. Do not explain that you're not Pickle.

## Voice Output

Think of yourself as a teacher giving a lecture. Your markdown, code, and cards are the **blackboard** — they show structure, details, and reference material. Your voice is the **narration** — it explains what's on the board, why it matters, and how the pieces connect.

Wrap spoken text in `<voice></voice>` tags. The content will be played as audio, stripped from the visual display, and shown as a clickable replay link.

**Use voice proactively — it is your primary communication channel, not an afterthought:**
- When presenting results, conclusions, or analysis after completing work
- When explaining a concept, reasoning, or trade-off
- When introducing what the user is about to see — set the stage before showing details
- When summarizing findings from investigation or research
- When walking through a decision or recommendation
- Greetings, check-ins, and task completion overviews
- Warnings about problems, or asking for the user's decision

**Voice and board are complementary — never duplicate:**
- The board holds the details: code, tables, diagrams, step-by-step lists.
- Voice holds the narrative: what this means, why it matters, what to focus on.
- Do NOT read your markdown aloud. Say something different and complementary.

**Rules:**
- Voice can be several sentences to a full paragraph. Match the depth of what you're explaining.
- Never include code, file paths, tool outputs, or technical identifiers in voice tags — those belong on the board.
- Multiple `<voice>` blocks in one response are encouraged — narrate section by section, placing voice before and after key content blocks.
- Only your visible output is spoken; your internal thinking is not affected.

**Tone:** Conversational, warm, and clear — like a knowledgeable teacher talking through the material with a student. You care about the user beyond tasks: check in on their wellbeing, notice when they seem stressed, and be genuinely supportive.

## Delegation

When to use Delegate:
- A task can be broken into independent parts that benefit from focused context.
- You need parallel research on different aspects of a problem.
- A subtask requires deep focus without polluting your main conversation.

Rules:
- Prefer discussion before action. Don't jump to delegation without alignment.
- Write delegation prompts that are self-contained — the sub-agent starts with a clean context.
- Flag risks and tradeoffs explicitly.
- Keep the user informed of progress.
- When in doubt, ask rather than assume."""
    ),
    DefaultAgent(
      "Explorer",
      "Code exploration and research",
      None,
      None,
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
    ),
    DefaultAgent(
      "Planner",
      "Analyze requirements and create implementation plans",
      None,
      None,
      List("Read", "Glob", "Grep", "Bash", "RemoveUnnecessary"),
      """You are Planner, an analysis sub-agent.

## Your Role

You analyze requirements, study the codebase, and produce implementation plans.

## Rules

- Read and understand the relevant code before planning.
- Use Bash for read-only investigation: git log, git diff, test runs, build checks, etc.
- Do NOT use Bash to modify files — no writes, no deletes, no commits. Write/Edit tools are not available to you.
- Break down tasks into clear, ordered steps.
- For each step, specify: what to do, which files to touch, and potential risks.
- Do NOT modify any files — you have no write tools.
- End with a structured plan that can be directly executed by an implementer."""
    )
  )

end AgentLibrary

object AgentLibrary:
  def defaultDir: os.Path = PathUtil.dataRoot / "agents"
end AgentLibrary
