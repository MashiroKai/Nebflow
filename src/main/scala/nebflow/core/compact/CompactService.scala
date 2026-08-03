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
   * Different agent roles need different summary focus areas.
   */
  def buildCompactReminder(depth: Int = 0): Message =
    val profile = CompactionProfile.fromDepth(depth)
    val prompt = profile match
      case CompactionProfile.Worker => WorkerCompactReminder
      case CompactionProfile.Manager => ManagerCompactReminder
      case _ => RootCompactReminder
    Message(MessageRole.User, Left(prompt))

  // ------------------------------------------------------------------
  // Profile-specific compact prompts
  // ------------------------------------------------------------------

  /** Shared preamble for all profiles. */
  private val CompactPreamble =
    """<system-reminder>
      |Context compaction required — the conversation has grown too large and must be compressed.
      |Stop your current task. All tools are DISABLED for this turn. TEXT ONLY — no tool calls.
      |
      |Your response must contain exactly two blocks:
      |
      |1. <analysis> block: organize your thoughts about the conversation (internal scratchpad).
      |2. <summary> block (see sections below).
      |
      |LANGUAGE RULE — your summary MUST be written in the SAME language as the user's messages.
      |If the user wrote in Chinese, write your summary in Chinese.
      |If the user wrote in English, write your summary in English.
      |""".stripMargin

  /** Shared rules + file restoration section. */
  private val CompactEpilogue =
    """
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

  /** Root agent (Nebula) — the orchestrator.
   *  Focus: user intent, task routing, flow results, planning state. */
  private val RootCompactReminder = CompactPreamble +
    """<summary>
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
      |""".stripMargin + CompactEpilogue

  /** Flow Manager — coordinator within a project flow.
   *  Focus: dispatch state, agent responses, progress tracking.
   *  The "user" messages are Mail from Nebula or agent responses, not direct user input. */
  private val ManagerCompactReminder = CompactPreamble +
    """You are a FLOW MANAGER. Your conversation consists of Mail messages (task dispatch and agent responses), not direct user chat.
      |Your summary must focus on COORDINATION STATE so you can resume routing seamlessly.
      |
      |<summary>
      |1. Project Goal:
      |   [The overarching task from Nebula/caller that this flow is working on]
      |
      |2. Dispatched Tasks and Results:
      |   For each agent you dispatched to and received a response:
      |   - Agent: [name] → Task: [what was asked] → Status: [done/failed/pending] → Key outcome: [brief result]
      |
      |3. Pending Dispatches:
      |   - [Agent name]: [what's still awaited or needs to be sent]
      |
      |4. Files Modified by Agents:
      |   - `path/to/file` — [which agent changed it, what was changed]
      |
      |5. Key Decisions and Trade-offs:
      |   - [Any architectural/design decisions made during coordination]
      |
      |6. Current Work:
      |   [What you were doing when compaction triggered — synthesizing results? Waiting for an agent?]
      |
      |7. Next Step:
      |   [The immediate next coordination action needed]
      |</summary>
      |""".stripMargin + CompactEpilogue

  /** Flow Worker — implementation agent within a flow.
   *  Focus: current task, files changed, outcome. Old completed tasks are irrelevant.
   *  Be CONCISE — workers don't need deep historical context. */
  private val WorkerCompactReminder = CompactPreamble +
    """You are a FLOW WORKER. Your conversation is task-focused: Mail from manager → your work → Mail result back.
      |Your summary must be CONCISE and ACTION-ORIENTED so you can resume the current task.
      |Discard details of completed previous tasks — only keep what's needed for the CURRENT task.
      |
      |<summary>
      |1. Current Task:
      |   [What the manager asked you to do in the most recent Mail]
      |
      |2. Work Done So Far:
      |   [Files read, changes made, commands run — just the current task, not old ones]
      |   - `path/to/file` (line X-Y): [what was done]
      |
      |3. Key Findings:
      |   - [Important discoveries, errors, or blockers from the current task]
      |
      |4. Pending Steps:
      |   [What remains to be done for the current task]
      |
      |5. Result to Report:
      |   [If the task is complete, what should you Mail back to the manager?]
      |</summary>
      |""".stripMargin + CompactEpilogue

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
