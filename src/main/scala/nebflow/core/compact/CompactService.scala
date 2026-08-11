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
  // Save-memory reminder — stage 1 of the two-stage compaction model
  // ------------------------------------------------------------------

  /**
   * Build the save-memory reminder injected BEFORE the compact reminder
   * (two-stage model). Tools stay available so the agent can Write/Edit
   * durable memory and skills; ending the turn (toolCalls.isEmpty) is the
   * completion signal that transitions to the compact stage.
   *
   * Only agents that receive a memory block (Nebula + team agents, mirroring
   * ContextRefresher's injection condition) get a save turn; others go
   * straight to compact (behavior unchanged).
   */
  def buildSaveMemoryReminder(depth: Int): Message =
    val profile = CompactionProfile.fromDepth(depth)
    val prompt = profile match
      case CompactionProfile.Worker => WorkerSaveMemoryReminder
      case CompactionProfile.Manager => ManagerSaveMemoryReminder
      case _ => RootSaveMemoryReminder
    Message(MessageRole.User, Left(prompt))

  /** Shared preamble for all save-memory profiles. */
  private val SaveMemoryPreamble =
    """<system-reminder>
      |Context compaction is approaching — the conversation is about to be compressed.
      |BEFORE compression, run the MEMORY MAINTENANCE CYCLE on your persistent memory
      |and skills.
      |
      |Tools ARE AVAILABLE this turn. Use the Write or Edit tool on the memory files
      |directly (paths and entry format are in the Memory section of your system prompt).
      |
      |MEMORY MAINTENANCE CYCLE — do all four steps, in order:
      |
      |1. RECORD — save new facts that meet the quality standard below (exactly what
      |   to record is per your profile below).
      |2. ORGANIZE — sort entries into the right sections, merge duplicates, fold
      |   related entries together. Memory is a living document, not an append log.
      |3. VERIFY — spot-check existing entries against current reality. When an entry
      |   matters, confirm it still holds by reading the code/file/config — never
      |   trust old memory blindly.
      |4. CLEAR — delete entries that are outdated, wrong, readable from code, or of
      |   no reuse value (criteria below).
      |
      |MEMORY QUALITY STANDARD — keep an entry ONLY if it meets ALL THREE criteria:
      |- HARD TO OBTAIN: not recoverable with one Read/Grep/git command. Line numbers,
      |  API signatures and implementation details are readable from code — recording
      |  them clutters memory and goes stale. Record decision REASONS and lessons
      |  instead: the "why" that code comments and git history hide.
      |- REUSABLE: will matter for future similar tasks. One-off task details belong
      |  in the compaction summary, not in memory.
      |- CURRENT-STATE-FIRST: memory is NOT authoritative. When an entry contradicts
      |  the code or current reality, reality wins — update or delete the entry.
      |
      |LANGUAGE RULE — write your memory entries in the SAME language as the user's messages.
      |
      |Your ONLY task this turn is to run this cycle with the Write/Edit tools.
      |When the cycle is done, END THIS TURN directly — do not output any extra text.
      |Ending the turn is the completion signal; the system will proceed to compaction
      |automatically.
      |
      |Already-saved information does not need to be repeated in the compaction
      |summary afterwards.
      |""".stripMargin

  /**
   * Root agent (Nebula, depth 0) — the orchestrator.
   *  Focus: user dynamic facts, orchestration knowledge, long-term project state.
   */
  private val RootSaveMemoryReminder = SaveMemoryPreamble +
    """You are the ROOT agent (Nebula). Your profile focus is the USER and ORCHESTRATION:
      |
      |RECORD → ~/.nebflow/User.md (user-level, applies to all agents):
      |- Corrections of your output or approach — record what they wanted instead
      |- Direct instructions that skip your questions — record their default preference
      |- Repeated working style (naming, workflow, tool choices — after 2-3 consistent observations)
      |- Workflow preferences and environment facts (paths, ports, proxies, devices)
      |
      |RECORD → ~/.nebflow/agents/Nebula/memory.md:
      |- Team/flow selection decisions and routing rules that proved effective
      |- Cross-project patterns, division of labor between agents
      |- Which agent handles which task type (observed capabilities)
      |- Long-term project state that outlives this session (active branches,
      |  pending merges, design decisions still in force)
      |
      |VERIFY & CLEAR — root profile:
      |- User facts: confirm paths, ports, devices and tool configs still exist and
      |  still match reality before keeping them — stale environment facts mislead
      |  every agent downstream
      |- Orchestration: confirm routing entries reference teams/flows/agents that
      |  still exist; delete entries whose entities were removed
      |- Project state: drop branches merged, worktrees removed, merges completed
      |- Purge code-readable facts (line numbers, API details) you can Read anytime
      |
      |Skip one-off task details — they belong to the compaction summary, not memory.
      |""".stripMargin

  /**
   * Flow Manager (depth 1) — coordinator within a project/team.
   *  Focus: coordination state and dispatch experience so routing can resume.
   */
  private val ManagerSaveMemoryReminder = SaveMemoryPreamble +
    """You are a FLOW/TEAM MANAGER. Your profile focus is COORDINATION:
      |write to ~/.nebflow/teams/<team>/agents/<name>/memory.md (your own memory file).
      |
      |RECORD:
      |1. PENDING DISPATCHES — what you still await or must send next (the resume
      |   point after compaction)
      |2. KEY DECISIONS & TRADE-OFFS — coordination decisions WITH their reasoning,
      |   so you don't re-litigate them after compaction
      |3. DISPATCH EXPERIENCE — which agents/approaches proved effective for which
      |   task types; routing lessons reusable across flows (not one-off status reports)
      |4. ARTIFACT LOCATIONS — files agents produced (path + what it is), so you can
      |   point users/agents at results without re-searching
      |
      |VERIFY & CLEAR — manager profile:
      |- Statuses: verify done/failed claims against actual artifacts before keeping
      |  them — an unverified "done" (agent claimed it, nothing written) is a trap
      |  for the next session
      |- Artifacts: confirm recorded paths still exist before keeping them
      |- Pending: drop dispatches already resolved; keep only what is truly still awaited
      |- Purge routine tool chatter and transient waiting states — noise, not memory
      |
      |Keep entries durable enough that a fresh session can resume routing without
      |re-reading the whole log.
      |""".stripMargin

  /**
   * Flow Worker (depth 2+) — implementation agent within a flow.
   *  Focus: technical experience (pitfalls, effective practices, tool behavior).
   */
  private val WorkerSaveMemoryReminder = SaveMemoryPreamble +
    """You are a FLOW WORKER. Your profile focus is TECHNICAL EXPERIENCE:
      |write to ~/.nebflow/teams/<team>/agents/<name>/memory.md (your own memory file).
      |
      |RECORD:
      |1. PITFALLS & FIXES — bugs you hit and how you fixed them; root cause matters
      |   more than the exact patch. Include the decision REASON when it is not visible
      |   in code (e.g. why a classification or default was chosen — the kind of fact
      |   you'd only recover from git history)
      |2. EFFECTIVE PRACTICES — approaches that worked in this domain (tests, build, layout)
      |3. TOOL BEHAVIOR — non-obvious tool semantics you discovered (output formats,
      |   gotchas, failure modes) that cost you time
      |4. REUSABLE CAPABILITIES — if this conversation produced a repeatable procedure
      |   (seen 2+ times or clearly generalizable), organize it as a skill following the
      |   skill-creator spec: ~/.nebflow/skills/<kebab-case-name>/SKILL.md with frontmatter
      |   (name + description: what AND when). One skill = one purpose; no one-off skills.
      |
      |VERIFY & CLEAR — worker profile:
      |- Pitfalls: confirm the fix is still in the code before keeping the entry — a
      |  stale "fixed" claim actively misleads (an outdated fallback classification
      |  once led another agent to "fix" code that was already correct)
      |- Code facts: delete entries now readable from code — line numbers, signatures,
      |  mechanics you can Read/Grep in seconds
      |- One-offs: drop findings that won't recur
      |
      |Be concise: keep only what is reusable, not full history.
      |""".stripMargin

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

  /**
   * Root agent (Nebula) — the orchestrator.
   *  Focus: user intent, task routing, flow results, planning state.
   */
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

  /**
   * Flow Manager — coordinator within a project flow.
   *  Focus: dispatch state, agent responses, progress tracking.
   *  The "user" messages are Mail from Nebula or agent responses, not direct user input.
   */
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

  /**
   * Flow Worker — implementation agent within a flow.
   *  Focus: current task, files changed, outcome. Old completed tasks are irrelevant.
   *  Be CONCISE — workers don't need deep historical context.
   */
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
