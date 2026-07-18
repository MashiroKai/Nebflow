package nebflow.agent

/**
 * Conditional system prompt sections.
 *
 * Each section is a self-describing block that knows its own inclusion condition
 * and sort order. All conditional content in the system prompt — tool guides,
 * feature-flag instructions, environment info, device/session/language blocks —
 * lives here as a registered section. The caller builds a [[PromptContext]] from
 * runtime state, then calls [[buildConditionalBlocks]] to get the concatenated
 * text. Adding a new section is purely additive: drop it into `all` with an
 * `order` value and a `condition` — no changes to `buildSystemPrompt` needed.
 *
 * == Adding a new conditional section ==
 *
 * 1. If the condition depends on a new field, add it to [[PromptContext]]
 * 2. Add the section to [[PromptSections.all]] with an appropriate order value
 * 3. That's it — `buildSystemPrompt` picks it up automatically
 *
 * == Order ranges ==
 *
 *   100-199  — fixed foundational sections (env info)
 *   400-499  — tool-dependent sections
 *   500-599  — feature-flag sections (voice)
 *   600-699  — runtime-state sections (devices, sessions, language)
 *   800-899  — catalog sections (skills)
 *   900+     — project rules and fallback
 */
object PromptSections:

  // ============================================================
  // PromptContext — runtime state that determines which sections
  // are included and provides data for dynamic content.
  //
  // Lightweight flags and pre-rendered strings. "Heavy" dynamic content
  // (device list, agent sessions, env info) is rendered by the caller
  // *before* constructing the context — sections just read from it.
  // ============================================================

  case class PromptContext(
    availableTools: Set[String] = Set.empty,
    depth: Int = 0,
    voiceEnabled: Boolean = true,
    hasDevices: Boolean = false,
    hasActiveSessions: Boolean = false,
    hasSkills: Boolean = false,
    language: Option[String] = None,
    chatWidth: Int = 0,
    /** Pre-rendered environment info table (from Repl.buildEnvInfo). */
    envInfo: String = "",
    /** Pre-rendered device info block (from AgentCore.deviceInfoBlock). */
    deviceInfo: String = "",
    /** Pre-rendered skill catalog (from SkillService.buildSkillCatalog). */
    skillCatalog: String = "",
    /** Pre-rendered memory block (from ContextRefresher.buildMemoryBlock). */
    memoryBlock: String = "",
    /** Pre-rendered active-sessions block (from formatAgentSessions). */
    agentSessionsText: String = "",
    /** Inherited project rules text (from folder chain). */
    rulesMd: Option[String] = None
  )

  object PromptContext:
    val empty: PromptContext = PromptContext()

  // ============================================================
  // PromptSection — a self-describing conditional prompt block
  // ============================================================

  /**
   * A single section of the system prompt that knows its own inclusion
   * condition and ordering.
   *
   * Static sections override [[content]]. Dynamic sections (whose text
   * depends on runtime data) override [[render]].
   */
  trait PromptSection:
    /** Sort order in the final prompt (smaller = earlier). */
    def order: Int
    /** Whether this section should be included given the current context. */
    def shouldInclude(ctx: PromptContext): Boolean
    /** Static content. Override [[render]] for dynamic content. */
    def content: String = ""
    /** Render the section text, possibly using runtime context. */
    def render(ctx: PromptContext): String = content

  object PromptSection:
    /** Create a static conditional section. */
    def apply(
      ord: Int,
      condition: PromptContext => Boolean,
      body: String
    ): PromptSection = new PromptSection:
      val order = ord
      def shouldInclude(ctx: PromptContext) = condition(ctx)
      override val content = body

    /** Create a section that is always included. */
    def fixed(ord: Int, body: String): PromptSection =
      apply(ord, _ => true, body)

    /** Create a dynamic section whose text depends on runtime context. */
    def dynamic(
      ord: Int,
      condition: PromptContext => Boolean,
      renderer: PromptContext => String
    ): PromptSection = new PromptSection:
      val order = ord
      def shouldInclude(ctx: PromptContext) = condition(ctx)
      override def render(ctx: PromptContext) = renderer(ctx)

  /** Quick helper: condition that requires one or more tools to be available. */
  def requiresTools(names: String*): PromptContext => Boolean =
    ctx => names.forall(ctx.availableTools.contains)

  // ============================================================
  // Section text constants
  // ============================================================

  /** Teaches the LLM when to use AskUserQuestion tool and dependsOn. */
  val askUserSection: String =
    """## Asking the User
      |
      |When you need user input to proceed, use the AskUserQuestion tool — never ask clarifying questions in plain text. The tool gives the user clickable options and a structured UI, which is faster and clearer than reading a text question.
      |
      |**Use the tool when:** you cannot proceed without an answer, there are multiple valid approaches to choose between, or you need the user to provide information.
      |
      |**Don't use the tool when:** you can make a reasonable decision yourself. Just proceed and let the user correct course if needed.
      |
      |**Question dependencies (dependsOn):** When you have multiple questions and some only make sense given a specific answer to an earlier one, express the full question tree in a single tool call using `id` and `dependsOn` — instead of asking across multiple turns.
      |
      |Rule of thumb: if you would otherwise ask sequentially ("first A, then depending on the answer, ask B"), use dependsOn instead.
      |
      |Common scenarios:
      |- Stack choice: ask "Which language?" (id: lang) and "Which framework?" (dependsOn: lang=Python → Django/FastAPI; lang=Rust → Actix/Axum)
      |- Deployment: ask "Deploy where?" (id: target) and if Vercel → "Custom domain?", if Docker → "Port mapping?"
      |- Testing: ask "Test type?" (id: test) and if Unit → "Mock library?", if Integration → "Test database?"
      |
      |Independent questions don't need dependsOn — just include them all in one call.""".stripMargin

  /** Injected after the agent prompt when voice output is enabled. */
  val voiceSection: String =
    """## Voice Output
      |
      |Think of yourself as a teacher giving a lecture. Your markdown, code, and cards are the **blackboard** — they show structure, details, and reference material. Your voice is the **narration** — it explains what's on the board, why it matters, and how the pieces connect.
      |
      |Wrap spoken text in `<voice></voice>` tags. The content will be played as audio, stripped from the visual display, and shown as a clickable replay link.
      |
      |**Use voice proactively — it is your primary communication channel, not an afterthought:**
      |- When presenting results, conclusions, or analysis after completing work
      |- When explaining a concept, reasoning, or trade-off
      |- When introducing what the user is about to see — set the stage before showing details
      |- When summarizing findings from investigation or research
      |- When walking through a decision or recommendation
      |- Greetings, check-ins, and task completion overviews
      |- Warnings about problems, or asking for the user's decision
      |
      |**Voice and board are complementary — never duplicate:**
      |- The board holds the details: code, tables, diagrams, step-by-step lists.
      |- Voice holds the narrative: what this means, why it matters, what to focus on.
      |- Do NOT read your markdown aloud. Say something different and complementary.
      |
      |**Rules:**
      |- Voice can be several sentences to a full paragraph. Match the depth of what you're explaining.
      |- Never include code, file paths, tool outputs, or technical identifiers in voice tags — those belong on the board.
      |- Multiple `<voice>` blocks in one response are encouraged — narrate section by section, placing voice before and after key content blocks.
      |- Only your visible output is spoken; your internal thinking is not affected.
      |
      |**Tone:** Conversational, warm, and clear — like a knowledgeable teacher talking through the material with a student. You care about the user beyond tasks: check in on their wellbeing, notice when they seem stressed, and be genuinely supportive.""".stripMargin

  /** Injected when the Read tool is available. Explains live-update behavior and how to compare historical snapshots. */
  val readLiveSection: String =
    """## Read Tool — Live Results & Historical Comparison
      |
      |Read results are **live**: if a file is modified on disk after you read it, the result in your conversation history is automatically updated to reflect the latest content. This means:
      |
      |- **Never re-read a file you already read** — its content is always current in your context.
      |- **You cannot trust a Read result as a frozen snapshot.** If you need to compare the "before" and "after" states of a file (e.g. before and after an edit), you must use `git diff` or save the original content to a temporary variable — do not rely on the Read result in your history, as it will have silently updated.
      |- **Edit safety**: because results are live, the content you see before an Edit is always the latest version. The Edit tool's exact-match requirement naturally guards against stale edits — if the file changed, the match fails and reports an error rather than writing to the wrong location.
      |- **Multi-instance awareness**: if another process (e.g. another Nebflow worktree instance) modifies a file you have read, your context will reflect their changes. Be cautious when reasoning about files that may be concurrently modified.""".stripMargin

  // ============================================================
  // Section registry
  // ============================================================

  val all: List[PromptSection] = List(
    // --- Fixed foundational sections ---
    PromptSection.dynamic(
      100,
      condition = _.envInfo.nonEmpty,
      renderer = _.envInfo
    ),

    // --- Tool-dependent sections ---
    PromptSection(
      400,
      condition = requiresTools("AskUserQuestion"),
      body = askUserSection
    ),
    PromptSection(
      410,
      condition = requiresTools("Read"),
      body = readLiveSection
    ),

    // --- Feature-flag sections ---
    PromptSection(
      500,
      condition = _.voiceEnabled,
      body = voiceSection
    ),

    // --- Runtime-state sections ---
    PromptSection.dynamic(
      600,
      condition = _.hasDevices,
      renderer = ctx => s"# Devices\n\n${ctx.deviceInfo}"
    ),
    PromptSection.dynamic(
      610,
      condition = _.hasActiveSessions,
      renderer = _.agentSessionsText
    ),
    PromptSection.dynamic(
      620,
      condition = _.language.isDefined,
      renderer = ctx => languageBlock(ctx.language.get)
    ),

    // --- Catalog sections ---
    PromptSection.dynamic(
      800,
      condition = _.skillCatalog.nonEmpty,
      renderer = _.skillCatalog
    ),
    PromptSection.dynamic(
      810,
      condition = _.memoryBlock.nonEmpty,
      renderer = _.memoryBlock
    ),

    // --- Project rules ---
    PromptSection.dynamic(
      900,
      condition = _.rulesMd.isDefined,
      renderer = ctx => s"## Project Rules\n\n${ctx.rulesMd.get}"
    )
  )

  /** Render the language instruction block for the given language. */
  private def languageBlock(lang: String): String =
    s"# Language\n" +
      s"- Respond in $lang.\n" +
      s"- When creating tasks (TaskCreate), the `subject` and `activeForm` fields MUST be in $lang.\n" +
      s"- When writing to memory files (Agent/Session/User memory), all content MUST be in $lang.\n" +
      s"- All user-visible text must be in $lang."

  /**
   * Build the conditional blocks string from the registry.
   * Filters by shouldInclude, sorts by order, renders each section,
   * and joins with double newlines.
   */
  def buildConditionalBlocks(ctx: PromptContext): String =
    all
      .filter(_.shouldInclude(ctx))
      .sortBy(_.order)
      .map(_.render(ctx))
      .filter(_.nonEmpty)
      .mkString("\n\n")

  /**
   * Remove a `## Section` block from a prompt string.
   * Matches from the header line up to (but not including) the next `## ` header
   * or end of string. Handles backward compatibility: existing system.md files
   * may still contain sections that are now conditionally injected.
   */
  def stripSection(prompt: String, header: String): String =
    val marker = s"## $header"
    prompt.split("(?=\n## )").filterNot(_.trim.startsWith(marker)).mkString

  /** Strip all sections that have been migrated to conditional injection. */
  def stripAllMigrated(prompt: String): String =
    stripSection(prompt, "Voice Output")

end PromptSections
