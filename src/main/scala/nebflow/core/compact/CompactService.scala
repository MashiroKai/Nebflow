package nebflow.core.compact

import cats.effect.IO
import cats.syntax.all.*
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.core.hooks.*
import nebflow.shared.*

/**
 * Inline context compaction — reuses the agent's own LLM call with cached
 * system prompt and tool definitions.  No separate LLM request is needed;
 * instead a "compact reminder" is injected as a User message, tools are
 * disabled (tools = Nil), and the streaming response is captured as the
 * summary.
 *
 * Benefits over the old approach:
 *   - Cache hit on system prompt + tool definitions (huge token savings)
 *   - No message pre-processing / truncation / rebuilding
 *   - Response streams to the frontend (user sees progress)
 *   - Simpler code path
 */
object CompactService:

  private val logger = NebflowLogger.forName("nebflow.compact")

  // ------------------------------------------------------------------
  // Compact reminder — injected as the last User message
  // ------------------------------------------------------------------

  /**
   * Build the compact reminder message that instructs the model to
   * stop its current task and produce a summary.
   */
  def buildCompactReminder(): Message =
    Message(MessageRole.User, Left(CompactReminderText))

  private val CompactReminderText =
    """<system-reminder>
      |Context compaction required — the conversation has grown too large and must be compressed.
      |Stop your current task. All tools are DISABLED for this turn. TEXT ONLY — no tool calls.
      |
      |Your response must contain exactly two blocks:
      |
      |1. <analysis> block: organize your thoughts about the conversation (internal scratchpad).
      |2. <summary> block with the following sections:
      |
      |LANGUAGE RULE — your summary MUST be written in the SAME language as the user's messages.
      |If the user wrote in Chinese, write your summary in Chinese.
      |If the user wrote in English, write your summary in English.
      |If the user wrote in Japanese, write your summary in Japanese.
      |
      |<summary>
      |1. Primary Request and Intent:
      |   [Detailed description of all the user's explicit requests and intents]
      |
      |2. Key Technical Concepts:
      |   - [Concept 1]
      |   - [Concept 2]
      |
      |3. Files and Code Sections:
      |   Each file with its path in backticks, line ranges, and what was found/changed.
      |   - `path/to/file` (line 42-89): description
      |     ```
      |     key code snippet
      |     ```
      |
      |4. Errors and Fixes:
      |   - [Detailed description of error]: [How you fixed it]
      |
      |5. Problem Solving:
      |   [Description of solved problems and ongoing troubleshooting efforts]
      |
      |6. All User Messages:
      |   - [Detailed non-tool-use user message]
      |
      |7. Pending Tasks:
      |   - [Task 1]
      |
      |8. Current Work:
      |   [Precise description of what was being worked on immediately before this summary request.]
      |
      |9. Optional Next Step:
      |   [The single immediate next action. Include direct quotes from the most recent conversation.]
      |</summary>
      |
      |After the </summary> tag, list files whose content should be restored after compaction.
      |Maximum 5 files.
      |
      |<files>
      |path/to/file1
      |path/to/file2
      |</files>
      |
      |Rules:
      |- Preserve file paths with backticks, always include line numbers when known
      |- Be specific, not vague
      |- Preserve all decisions, trade-offs, and user preferences stated
      |- If the user gave explicit instructions, quote them verbatim
      |- Keep the summary focused and information-dense
      |</system-reminder>""".stripMargin

  // ------------------------------------------------------------------
  // Hooks (PreCompact / PostCompact)
  // ------------------------------------------------------------------

  def runPreCompactHook(
    messages: List[Message],
    resources: SharedResources,
    sessionId: String
  ): IO[Either[String, Unit]] =
    val hookEngine = resources.hookEngine
    val hookCtx = HookContext(
      sessionId = Some(sessionId),
      projectRoot = resources.projectRoot.toString,
      cwd = resources.projectRoot.toString
    )
    hookEngine.beforeCompact(messages.size, hookCtx).map { preResult =>
      if preResult.decision == HookDecision.Block then
        val reason = preResult.reason.getOrElse("Compaction blocked by hook")
        logger.info(s"Compaction blocked by hook: $reason")
        Left(reason)
      else Right(())
    }

  end runPreCompactHook

  def runPostCompactHook(
    beforeSize: Int,
    afterSize: Int,
    resources: SharedResources,
    sessionId: String
  ): IO[Unit] =
    val hookEngine = resources.hookEngine
    val hookCtx = HookContext(
      sessionId = Some(sessionId),
      projectRoot = resources.projectRoot.toString,
      cwd = resources.projectRoot.toString
    )
    val tokensSaved = ((beforeSize - afterSize).toLong * 500).max(0)
    hookEngine.afterCompact(beforeSize, afterSize, tokensSaved, hookCtx).void

end CompactService
