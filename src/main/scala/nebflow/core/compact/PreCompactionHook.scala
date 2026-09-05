package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.shared.*

// ═══════════════════════════════════════════════
// PreCompactionHook — pre-compaction extraction hook
// ═══════════════════════════════════════════════

/**
 * Hook executed before LLM compaction runs.
 *
 * Each role (Root/Manager/Worker) has its own hook that extracts durable
 * information (facts / progress / skills) into persistent files before the
 * conversation context is summarised away.
 *
 * Contract:
 *   - Failure must NOT block compaction (caller wraps with handleErrorWith).
 *   - Does not modify `messages` (read-only extraction → write to files).
 *   - May call LLM (independent request, not the agent's main loop).
 */
trait PreCompactionHook:

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit]
end PreCompactionHook

// ─────────────────────────────────────────────
// Registry: select hook by CompactionProfile
// ─────────────────────────────────────────────

object PreCompactionHooks:

  /**
   * 2026-08-31 memory-system redesign: team agents have no memory anymore —
   * ManagerProgressHook / WorkerSkillHook (which appended progress/skill
   * entries into team memory.md) are retired. Only the Root hook
   * (NebulaMemoryHook, merged-write User.md) remains active.
   */
  def forProfile(profile: CompactionProfile): PreCompactionHook =
    profile match
      case CompactionProfile.Root => NebulaMemoryHook
      case _ => NoOpHook

/** No-op hook for Legacy/unprofiled agents. */
object NoOpHook extends PreCompactionHook:

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] = IO.unit
