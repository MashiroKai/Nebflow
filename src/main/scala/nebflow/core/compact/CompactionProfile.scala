package nebflow.core.compact

/**
 * Determines compaction behavior for an agent based on its role in the flow hierarchy.
 *
 * Profiles select the pre-compaction hook and compact-reminder prompt template.
 * They no longer control additional triggers — all extraction (memory/progress/skill)
 * happens as a pre-compaction step, not via independent timers or turn hooks.
 *
 * 2026-08-31 memory-system redesign: only the Root profile has a hook anymore
 * (NebulaMemoryHook, merged-write fact extraction → User.md). Manager/Worker
 * hooks are retired — team agents have no memory to write to.
 *
 * 2026-09-03 per-level compaction prompts: Dispatcher / ProjectNode cover the
 * new Project+Node architecture sessions (both spawn at depth 1). They are
 * routed by the cross-layer sessionId prefix contract (see fromDepth) and get
 * NoOpHook via PreCompactionHooks' default case — dispatcher/node sessions
 * have no memory (2026-08-31 ruling).
 *
 *  - Root:        NebulaMemoryHook (fact extraction → User.md)
 *  - Manager:     NoOpHook
 *  - Worker:      NoOpHook
 *  - Dispatcher:  NoOpHook (project dispatcher session, dispatcher-*)
 *  - ProjectNode: NoOpHook (project flow node session, node-*)
 *  - Legacy:      NoOpHook
 */
enum CompactionProfile:
  case Root, Manager, Worker, Dispatcher, ProjectNode, Legacy

object CompactionProfile:

  /**
   * Infer profile from agent depth, lead role (B5) and sessionId prefix.
   *  depth 0 = Nebula (root)
   *  depth 1 = one level below the root. New-architecture sessions are
   *            identified FIRST by the cross-layer sessionId prefix contract
   *            (ProjectActor.DispatcherSessionPrefix / NodeEngine.SessionPrefix,
   *            same precedent as AgentControlTool.isProjectFlowSession) — the
   *            prefix MUST take precedence over isLead: a node running an
   *            agent whose name happens to be a legacy team lead would
   *            otherwise wrongly receive the Manager prompt. Remaining
   *            depth-1 sessions follow the legacy routing: team lead
   *            (Manager) vs everyone else (team members, legacy flow dag-*,
   *            delegate-, subtask- → Worker).
   *  depth 2+ = worker agent
   *
   * `isLead` matters only at depth 1: team members and the team Manager sit
   * at the same depth, so depth alone cannot tell them apart.
   */
  def fromDepth(depth: Int, isLead: Boolean = false, sessionId: Option[String] = None): CompactionProfile =
    if depth == 0 then Root
    else if depth == 1 then
      // New-architecture sessions first: prefix contract from
      // ProjectActor.DispatcherSessionPrefix / NodeEngine.SessionPrefix.
      // MUST take precedence over isLead — a node running an agent whose name
      // happens to be a legacy team lead would otherwise get Manager.
      if sessionId.exists(_.startsWith(nebflow.core.project.ProjectActor.DispatcherSessionPrefix)) then Dispatcher
      else if sessionId.exists(_.startsWith(nebflow.core.project.NodeEngine.SessionPrefix)) then ProjectNode
      else if isLead then Manager
      else Worker
    else Worker
