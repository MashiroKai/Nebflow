package nebflow.core.compact

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.hooks.*
import nebflow.shared.{NebflowLogger, *}
import nebflow.prompts.PromptCatalog

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
      case _ => RootCompactReminder // Root (also catches Legacy)
    Message(MessageRole.User, Left(prompt))

  /**
   * Head signature shared by every profile reminder (CompactPreamble's first
   * two lines — stable across profiles because they all prepend the same
   * preamble). Used to recognize a compact reminder inside a message list.
   *
   * 🔴 **派生值，不是手抄副本**（提示词单文件批 2026-11-01）：正文已搬到
   * [[nebflow.prompts.PromptCatalog.Compact]]，判据从 `CompactPreamble` 现取。
   * 手抄一份的话，改 preamble 头条 ⇒ 判据不认识新提示 ⇒ 尾部 reminder 混进
   * 保留轮，链上没有任何机械闸。改 preamble ⇒ 判据自动跟随；
   * 「不许改 preamble 头条」由 `CompactServiceSpec` 的派生断言锁死。
   */
  private val ReminderSignature: String = PromptCatalog.Compact.ReminderSignature

  /**
   * Is this message a compact reminder injected by [[buildCompactReminder]]?
   * FullCompact uses this to exclude the trailing reminder from tail-round
   * preservation — the reminder is the summarization instruction, not
   * conversation content.
   *
   * 判据 CRLF 容错（Windows 测试批 2026-10-25）：reminder 文本来自 catalog 的
   * `CompactPreamble` 三引号字面量，CRLF 检出（Windows 全局 autocrlf）会把 `\r`
   * 编进字面量行尾 ⇒ 首行尾变 `\r\n`，按 `\n` 契约的 startsWith 恒假 ⇒ 尾部
   * reminder 摘不掉、混入保留轮。取签名窗剥 `\r` 后比对，两种检出形态同判
   * （LF 检出零变化；`ReminderSignature` 的派生式同样先剥 `\r`）。
   */
  private def isCompactReminderText(t: String): Boolean =
    t.take(ReminderSignature.length + 2).replace("\r", "").startsWith(ReminderSignature)

  def isCompactReminder(msg: Message): Boolean =
    msg.content match
      case Left(text) => isCompactReminderText(text)
      case Right(blocks) =>
        blocks.exists {
          case ContentBlock.Text(t) => isCompactReminderText(t)
          case _ => false
        }

  // ------------------------------------------------------------------
  // Profile-specific compact prompts
  // ------------------------------------------------------------------

  /** Shared preamble for all profiles. */
  private val CompactPreamble = PromptCatalog.Compact.CompactPreamble

  /** Shared rules + file restoration section. */
  private val CompactEpilogue = PromptCatalog.Compact.CompactEpilogue

  /**
   * Nebula (Root) — the long-lived global orchestrator. Focus: a GLOBAL STATUS
   * LEDGER — every project/task line, in-flight dispatches, verbatim facts and
   * rulings. Process detail of finished sub-tasks is discarded aggressively.
   * 2026-09-03 (per-level compaction): replaces the generic coding-assistant
   * template; durable user facts used to be extracted separately by
   * NebulaMemoryHook before compaction (that production face is retired,
   * govmemory batch — the hook now only raises the post-compaction hygiene
   * signal), so the summary must NOT duplicate them.
   */
  private val RootCompactReminder = PromptCatalog.Compact.RootCompactReminder

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
  private val ManagerCompactReminder = PromptCatalog.Compact.ManagerCompactReminder

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
  private val WorkerCompactReminder = PromptCatalog.Compact.WorkerCompactReminder

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
  private val DispatcherCompactReminder = PromptCatalog.Compact.DispatcherCompactReminder

  /**
   * Node Worker — a one-shot task node inside a project Flow Map.
   * 2026-09-03 (per-level compaction): the node's FINAL OUTPUT TEXT becomes the
   * node result (captured by NodeEngine, delivered downstream), so whatever
   * downstream needs must survive this summary. No memory, no fallback — this
   * summary is the ONLY record of the in-progress task.
   */
  private val NodeCompactReminder = PromptCatalog.Compact.NodeCompactReminder

  // ------------------------------------------------------------------
  // Hooks (PreCompact / PostCompact)
  // ------------------------------------------------------------------

  /**
   * Phase 5 D 步:入参由整只 `resources: SharedResources`(agent 定位器)改为
   * 底层值直传——hookEngine 是 core 类型(HookEngine)、projectRoot 是 os.Path,
   * 均非 agent 符号;行为零差(原字段取用改为参数取用)。
   */
  def runPreCompactHook(
    messages: List[Message],
    hookEngine: HookEngine,
    projectRoot: os.Path,
    sessionId: String
  ): IO[Either[String, Unit]] =
    val hookCtx = HookContext(
      sessionId = Some(sessionId),
      projectRoot = projectRoot.toString,
      cwd = projectRoot.toString
    )
    hookEngine.beforeCompact(messages.size, hookCtx).map { preResult =>
      if preResult.decision == HookDecision.Block then
        val reason = preResult.reason.getOrElse("Compaction blocked by hook")
        logger.info(s"Compaction blocked by hook: $reason")
        Left(reason)
      else Right(())
    }

  end runPreCompactHook

  /** Phase 5 D 步:同 [[runPreCompactHook]],底层值直传。 */
  def runPostCompactHook(
    beforeSize: Int,
    afterSize: Int,
    hookEngine: HookEngine,
    projectRoot: os.Path,
    sessionId: String
  ): IO[Unit] =
    val hookCtx = HookContext(
      sessionId = Some(sessionId),
      projectRoot = projectRoot.toString,
      cwd = projectRoot.toString
    )
    val tokensSaved = ((beforeSize - afterSize).toLong * 500).max(0)
    hookEngine.afterCompact(beforeSize, afterSize, tokensSaved, hookCtx).void

end CompactService
