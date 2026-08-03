package nebflow.core.compact

/** Determines compaction behavior for an agent based on its role in the flow hierarchy.
 *
 *  Profiles control *additional* triggers beyond the shared token-based compaction:
 *  - Root:    dream mode (idle reflection, fact extraction)
 *  - Manager: mail-turn compaction (progress summary per coordination turn)
 *  - Worker:  experience extraction (skill proposals from completed tasks)
 *  - Legacy:  no additional triggers (backward-compatible fallback)
 *
 *  All profiles share the same token-based compaction (FullCompact + FastMicroCompact)
 *  and emergency fallback (emergencyClean on circuit breaker).
 */
enum CompactionProfile:
  case Root, Manager, Worker, Legacy

object CompactionProfile:
  /** Infer profile from agent depth.
   *  depth 0 = Nebula (root), depth 1 = flow manager, depth 2+ = worker agent.
   */
  def fromDepth(depth: Int): CompactionProfile = depth match
    case 0 => Root
    case 1 => Manager
    case _ => Worker
