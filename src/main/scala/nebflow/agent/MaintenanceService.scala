package nebflow.agent

import nebflow.shared.*

/**
 * Memory maintenance reminder — injected every N delegate/flow calls to prompt
 * the main agent to consolidate and prune its memory.
 *
 * Unlike compaction (which disables tools and is reactive), maintenance keeps
 * tools available — the agent uses Edit/Write/RemoveUnnecessary to actively
 * curate its memory.
 */
object MaintenanceService:

  /** Number of delegate/flow calls between maintenance triggers. */
  val MaintenanceInterval: Int = 10

  /**
   * Check whether maintenance should trigger for the given state.
   * Only triggers for the main agent (depth == 0), not during compaction.
   */
  def shouldTrigger(state: AgentState, depth: Int, isCompactTurn: Boolean, isAskTurn: Boolean): Boolean =
    !isCompactTurn && !isAskTurn && depth == 0 &&
    state.delegateCount > 0 &&
    state.delegateCount % MaintenanceInterval == 0 &&
    state.delegateCount != state.lastMaintenanceDelegateCount

  /** Build the maintenance reminder message. */
  def buildReminder(delegateCount: Int): Message =
    Message(MessageRole.User, Left(reminderText(delegateCount)))

  private def reminderText(delegateCount: Int): String =
    s"""<system-reminder>
       |Memory maintenance checkpoint — $delegateCount delegated tasks completed.
       |
       |Review recent activity and perform memory maintenance:
       |
       |1. Consolidate: Extract durable knowledge from recent delegated work. Write short
       |   entries (or long entries with detail files) to the appropriate memory level.
       |2. Prune: Review existing memory entries. Delete or update anything outdated,
       |   contradicted, or no longer relevant. Merge duplicates.
       |3. Reduce: If context is growing, use RemoveUnnecessary on old tool results.
       |
       |After maintenance, briefly report what you updated.
       |</system-reminder>""".stripMargin

end MaintenanceService
