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
 *  - Root:    NebulaMemoryHook (fact extraction → User.md)
 *  - Manager: NoOpHook
 *  - Worker:  NoOpHook
 *  - Legacy:  NoOpHook
 */
enum CompactionProfile:
  case Root, Manager, Worker, Legacy

object CompactionProfile:

  /**
   * Infer profile from agent depth and lead role (B5).
   *  depth 0 = Nebula (root)
   *  depth 1 = one level below the root — a team lead (Manager) gets Manager,
   *            everyone else at this depth (team members, flow agents,
   *            delegate sub-agents) gets Worker
   *  depth 2+ = worker agent
   *
   * `isLead` matters only at depth 1: team members and the team Manager sit
   * at the same depth, so depth alone cannot tell them apart.
   */
  def fromDepth(depth: Int, isLead: Boolean = false): CompactionProfile = depth match
    case 0 => Root
    case 1 => if isLead then Manager else Worker
    case _ => Worker
