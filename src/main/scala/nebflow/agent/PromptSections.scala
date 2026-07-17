package nebflow.agent

/**
 * Conditional system prompt sections.
 *
 * Each section is a standalone text block that can be independently included or
 * excluded from the system prompt based on runtime conditions (encapsulated in
 * [[PromptContext]]). This allows the prompt to adapt to user preferences
 * without wasting tokens on instructions that don't apply (e.g. voice
 * instructions when voice is muted).
 *
 * == Adding a new conditional section ==
 *
 * 1. Add a boolean field to [[PromptContext]]
 * 2. Add the section text as a `val` here
 * 3. Add the conditional `if` in [[AgentCore.buildSystemPrompt]]
 */
object PromptSections:

  /** Runtime conditions that determine which sections are included. */
  case class PromptContext(
    voiceEnabled: Boolean
    // Future conditions go here — e.g. ttsConfigured, verboseTools, etc.
  )

  object PromptContext:
    val default: PromptContext = PromptContext(voiceEnabled = true)

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

  /**
   * Remove a `## Section` block from a prompt string.
   * Matches from the header line up to (but not including) the next `## ` header
   * or end of string. Handles backward compatibility: existing system.md files
   * may still contain sections that are now conditionally injected.
   */
  def stripSection(prompt: String, header: String): String =
    val marker = s"## $header"
    prompt.split("(?=\n## )").filterNot(_.trim.startsWith(marker)).mkString

end PromptSections
