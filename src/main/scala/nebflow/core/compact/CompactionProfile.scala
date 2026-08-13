package nebflow.core.compact

/**
 * Determines compaction behavior for an agent based on its role in the flow hierarchy.
 *
 * Profiles select the pre-compaction hook and compact-reminder prompt template.
 * They no longer control additional triggers — all extraction (memory/progress/skill)
 * happens as a pre-compaction step, not via independent timers or turn hooks.
 *
 *  - Root:    NebulaMemoryHook (fact extraction → User.md)
 *  - Manager: ManagerProgressHook (progress summary → memory.md)
 *  - Worker:  WorkerSkillHook (skill proposals → skill-proposals/)
 *  - Legacy:  NoOpHook
 */
enum CompactionProfile:
  case Root, Manager, Worker, Legacy

object CompactionProfile:

  /**
   * Infer profile from agent depth.
   *  depth 0 = Nebula (root), depth 1 = flow manager, depth 2+ = worker agent.
   */
  def fromDepth(depth: Int): CompactionProfile = depth match
    case 0 => Root
    case 1 => Manager
    case _ => Worker
