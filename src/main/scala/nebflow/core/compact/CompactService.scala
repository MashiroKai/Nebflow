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
  def buildCompactReminder(
    depth: Int = 0,
    isLead: Boolean = false,
    sessionId: Option[String] = None
  ): Message =
    val profile = CompactionProfile.fromDepth(depth, isLead, sessionId)
    val prompt = profile match
      case CompactionProfile.ProjectNode => NodeCompactReminder
      case CompactionProfile.Dispatcher => DispatcherCompactReminder
      case CompactionProfile.Worker => WorkerCompactReminder
      case CompactionProfile.Manager => ManagerCompactReminder
      case _ => NebulaCompactReminder // Root (also catches Legacy)
    Message(MessageRole.User, Left(prompt))

  /** Head signature shared by every profile reminder (CompactPreamble's first
    * two lines — stable across profiles because they all prepend the same
    * preamble). Used to recognize a compact reminder inside a message list. */
  private val ReminderSignature = "<system-reminder>\nContext compaction required"

  /** Is this message a compact reminder injected by [[buildCompactReminder]]?
    * FullCompact uses this to exclude the trailing reminder from tail-round
    * preservation — the reminder is the summarization instruction, not
    * conversation content. */
  def isCompactReminder(msg: Message): Boolean =
    msg.content match
      case Left(text) => text.startsWith(ReminderSignature)
      case Right(blocks) =>
        blocks.exists {
          case ContentBlock.Text(t) => t.startsWith(ReminderSignature)
          case _ => false
        }

  // ------------------------------------------------------------------
  // Profile-specific compact prompts
  // ------------------------------------------------------------------

  /** Shared preamble for all profiles. */
  private val CompactPreamble =
    """<system-reminder>
      |Context compaction required — the conversation has grown too large and must be compressed.
      |Stop your current task. All tools are DISABLED for this turn. TEXT ONLY — no tool calls.
      |
      |CRITICAL: your response TEXT must be non-empty. Write the <summary> in the text
      |body of your reply, NOT inside the thinking/reasoning block — a response whose
      |text is empty (thinking-only) is treated as a compaction failure and retried.
      |
      |Your response must contain exactly two blocks:
      |
      |1. <analysis> block: organize your thoughts about the conversation (internal scratchpad).
      |2. <summary> block (see sections below).
      |
      |LANGUAGE RULE — your summary MUST be written in the SAME language as the user's messages.
      |If the user wrote in Chinese, write your summary in Chinese.
      |If the user wrote in English, write your summary in English.
      |
      |TAIL FIDELITY RULE — the LAST user message before this summary request is
      |the ACTIVE TASK: it may be unfinished, and nothing else carries it. Your
      |summary MUST include (a) a verbatim or near-verbatim quote of that last
      |user message, and (b) its processing status: not yet started / in
      |progress / dispatched (to whom, awaiting what). Put both at the HEAD of
      |your Current-Work / Current-Task section. Never omit or dilute the tail
      |instruction on the grounds that it is "already covered" or "obvious" —
      |after compaction, this summary is the ONLY record of it.
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
      |- Image attachments: for every image in the history (marked `[用户附加图片: path]`),
      |  keep one line in the summary in this exact format:
      |  [图片: <path> | <one-sentence description of the image, or 未描述>]
      |  Describe the content if you can see the image; otherwise write 未描述.
      |  Images are dropped from context after compaction — this line is the only
      |  way to remember them and re-Read them later.
      |</system-reminder>""".stripMargin

  /**
   * Nebula (Root) — the long-lived global orchestrator. Focus: a GLOBAL STATUS
   * LEDGER — every project/task line, in-flight dispatches, verbatim facts and
   * rulings. Process detail of finished sub-tasks is discarded aggressively.
   * 2026-09-03 (per-level compaction): replaces the generic coding-assistant
   * template; durable user facts are extracted separately by NebulaMemoryHook
   * before compaction, so the summary must NOT duplicate them.
   */
  private val NebulaCompactReminder = CompactPreamble +
    """You are NEBULA — the global orchestrator. Your session is long-lived and
      |spans every project, team and task line. After compaction you resume
      |steering ALL of them from this summary alone (your durable user facts are
      |extracted separately into memory before compaction — do not duplicate
      |them here).
      |
      |Your summary must read like a GLOBAL STATUS LEDGER, not a coding log.
      |Discard aggressively: completed sub-task process details, long tool
      |outputs, and exploratory back-and-forth whose conclusion is already
      |captured below. Keep the conclusion, drop the journey.
      |
      |<summary>
      |1. Global Mission Board:
      |   For EACH active project/task line (one bullet per line):
      |   - [Project/team name]: [status: in-flight / awaiting-review / done / failed]
      |     → [what was last dispatched, to whom, and what is expected back]
      |
      |2. In-Flight Dispatches (waiting on results):
      |   - [Team/project/agent] ← [what was asked] → [expected deliverable;
      |     any threshold/retry/escalation rule attached to it]
      |
      |3. Facts, Decisions and Rulings (preserve VERBATIM):
      |   - [User decisions, acceptance verdicts, corrections — quote exactly]
      |
      |4. Pending / Blocked Items and their Gates:
      |   - [Item]: [what gates it — e.g. waiting for X before dispatching Y;
      |     retry policy; when to escalate to the user]
      |
      |5. Completed This Session (one line each, no process detail):
      |   - [Task line]: [final outcome + key artifact path if any]
      |
      |6. User Preferences Stated This Session:
      |   - [Working-style instructions, language, tool preferences]
      |
      |7. Current Work:
      |   [What you were doing immediately before this summary request. OPEN this
      |    section with a VERBATIM quote of the LAST user instruction and its
      |    processing status (TAIL FIDELITY RULE), then your own in-flight action.]
      |
      |8. Next Step:
      |   [The single immediate orchestration action. Include direct quotes from
      |    the most recent user instruction if relevant.]
      |</summary>
      |""".stripMargin + CompactEpilogue

  /**
   * Flow Manager — coordinator within a project flow.
   *  Focus: dispatch state, agent responses, progress tracking.
   *  The "user" messages are Mail from Nebula or agent responses, not direct user input.
   *
   * 2026-08-31 (single-stage compaction): this summary is the ONLY recovery
   * carrier for coordination state — the save turn and team memory are gone.
   * Dispatch state MUST be complete enough to resume routing from a fresh
   * context (no memory fallback).
   */
  private val ManagerCompactReminder = CompactPreamble +
    """You are a FLOW MANAGER. Your conversation consists of Mail messages (task dispatch and agent responses), not direct user chat.
      |Your summary must focus on COORDINATION STATE so you can resume routing seamlessly.
      |
      |IMPORTANT: you have NO persistent memory fallback after compaction — this
      |summary is the ONLY record of your coordination state. Preserve every
      |in-flight dispatch, awaited result, and pending decision in full.
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
      |   [What you were doing when compaction triggered — synthesizing results?
      |    Waiting for an agent? OPEN this section with a VERBATIM quote of the
      |    LAST instruction/Mail you received and its processing status
      |    (TAIL FIDELITY RULE).]
      |
      |7. Next Step:
      |   [The immediate next coordination action needed]
      |</summary>
      |""".stripMargin + CompactEpilogue

  /**
   * Flow Worker — implementation agent within a flow.
   *  Focus: current task, files changed, outcome. Old completed tasks are irrelevant.
   *  Be CONCISE — workers don't need deep historical context.
   *
   * 2026-08-31 (single-stage compaction): this summary is the ONLY recovery
   * carrier for the in-progress task — the save turn and team memory are gone.
   * Unfinished-task state MUST be preserved in full; nothing may be deferred
   * to a memory file that no longer exists.
   */
  private val WorkerCompactReminder = CompactPreamble +
    """You are a FLOW WORKER. Your conversation is task-focused: Mail from manager → your work → Mail result back.
      |Your summary must be CONCISE and ACTION-ORIENTED so you can resume the current task.
      |Discard details of completed previous tasks — only keep what's needed for the CURRENT task.
      |
      |IMPORTANT: you have NO persistent memory fallback after compaction — this
      |summary is the ONLY record of your in-progress task. Preserve the
      |unfinished-task state IN FULL: files touched (with line ranges), partial
      |changes, blockers, and the exact next step. Never write "see memory" or
      |assume anything survives outside this summary.
      |
      |<summary>
      |1. Current Task:
      |   [The most recent Mail/instruction from the manager — QUOTE IT VERBATIM
      |    (TAIL FIDELITY RULE), then state its status: not yet started /
      |    in progress / blocked (by what)]
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

  /**
   * Project Dispatcher — single-session task dispatcher for one project.
   * 2026-09-03 (per-level compaction): the Flow Map on disk (flow-map.json:
   * nodes, wiring, statuses, results) is the AUTHORITATIVE state and survives
   * this session. The summary does NOT replace the Flow Map — it only carries
   * what THIS trigger changed and what remains to wrap up.
   * 2026-08-31 (single-stage compaction): the dispatcher has no memory —
   * this summary is the only in-session recovery carrier for the current
   * trigger; project state lives in the Flow Map.
   */
  private val DispatcherCompactReminder = CompactPreamble +
    """You are a PROJECT DISPATCHER — a single-session task dispatcher for one
      |project. The Flow Map on disk (flow-map.json: nodes, wiring, statuses,
      |results) is the AUTHORITATIVE state of this project — it survives after
      |this session dies. This summary does NOT replace the Flow Map; it only
      |needs to carry enough for you to finish the CURRENT trigger cleanly
      |(finalize wiring, answer a re-entry injection, wrap up). Never copy the
      |whole Flow Map into this summary — call NodeList fresh if you need it.
      |
      |IMPORTANT: you have NO persistent memory fallback after compaction — this
      |summary is your ONLY in-session record. Preserve every trigger task,
      |topology change, and unfinished gap in full.
      |
      |<summary>
      |1. Trigger Task(s) (this session, one bullet per injection round):
      |   - [The task text injected by Nebula/ProjectActor — quote verbatim;
      |     for re-entry rounds, state which node triggered it and why]
      |
      |2. Topology Changes Made This Session (per NodeEdit executed):
      |   - [nodeId/name]: agent=[agent], task=[yes/no], in=[upstream ids],
      |     out=[downstream id or "Nebula"] → [created / rewired / cancelled]
      |
      |3. Nodes Started or Affected This Session:
      |   - [nodeId/name]: [status at last NodeList — running/pending/blocked/cancelled]
      |
      |4. Unfinished Work (gaps the next trigger resumes from):
      |   - [Node/edge planned but NOT created or wired, and why — name the exact gap]
      |
      |5. Worktree / Git Actions:
      |   - [worktrees created or merged this session; git commands with outcomes]
      |
      |6. Wrap-up State:
      |   [What remains before this trigger is fully settled — final NodeList
      |   self-check done? any node still expected to start?]
      |</summary>
      |""".stripMargin + CompactEpilogue

  /**
   * Node Worker — a one-shot task node inside a project Flow Map.
   * 2026-09-03 (per-level compaction): the node's FINAL OUTPUT TEXT becomes the
   * node result (captured by NodeEngine, delivered downstream), so whatever
   * downstream needs must survive this summary. No memory, no fallback — this
   * summary is the ONLY record of the in-progress task.
   */
  private val NodeCompactReminder = CompactPreamble +
    """You are a NODE WORKER — a one-shot task node inside a project Flow Map.
      |Your final output text becomes the node result and is delivered to the
      |downstream node, so anything downstream needs from your work must survive
      |this summary. You have NO persistent memory and NO memory fallback after
      |compaction — this summary is the ONLY record of your in-progress task.
      |Never write "see memory" or assume anything survives outside it.
      |
      |Your ONE job after compaction: resume exactly the task below and finish it.
      |
      |<summary>
      |1. Task Goal (IMMUTABLE — do not reinterpret, narrow, or expand it):
      |   [The task text this node was started with — quote verbatim, including
      |   acceptance criteria and constraints]
      |
      |2. Completed So Far (evidence-based):
      |   - `path/to/file` (line X-Y): [what was done]
      |   - Commits: [hash + one-line message; branch/worktree if any]
      |   - Verification: [commands run + actual results — tests passed/failed,
      |     build ok, screenshots taken]
      |
      |3. Blockers and Lessons:
      |   - [What blocked you and how you worked around it; failed approaches
      |     with WHY they failed — they must not be retried blindly]
      |
      |4. Remaining Steps (ordered):
      |   1. [next concrete action]
      |   2. [...]
      |
      |5. Result Statement So Far:
      |   [If you had to report now: the one-paragraph result downstream would
      |   receive, plus what is still missing from it]
      |</summary>
      |
      |Discard freely: dead-end exploration without a lesson, verbose tool
      |outputs, and completed-and-verified details beyond the evidence lines
      |above.
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
