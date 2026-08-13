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

  def forProfile(profile: CompactionProfile): PreCompactionHook =
    profile match
      case CompactionProfile.Root => NebulaMemoryHook
      case CompactionProfile.Manager => ManagerProgressHook
      case CompactionProfile.Worker => WorkerSkillHook
      case CompactionProfile.Legacy => NoOpHook

/** No-op hook for Legacy/unprofiled agents. */
object NoOpHook extends PreCompactionHook:

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] = IO.unit
