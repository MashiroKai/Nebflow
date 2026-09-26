package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.shared.*

/**
 * Pre-compaction hook for the Root agent (Nebula, depth 0).
 *
 * Live duty (single): after a compaction completes, raise the memory-hygiene
 * lifecycle signal ([[nebflow.agent.MemoryHygieneSignal.markCompacted]]) —
 * consumed by ContextRefresher.buildMemoryBlock on the first Nebula injection
 * after the compaction ("T2 sweep" notice). No LLM round, no file writes.
 *
 * Retired with the govmemory batch (author ruling (d)①): the dormant dream
 * production faces — the fact-enqueue entry point, the face/budget routing
 * core (`decideRoute` / `faceRoom` / `pendingBytesByFace`) and their spec
 * (the route spec retired alongside) — plus the dream-mode parsing remnants
 * they were the only consumers of. They had zero production callers since fact
 * extraction was stopped (promptgov batch 3); retiring them now that the
 * memory queue itself is retired closes the last re-wiring path to the old
 * pipeline. Memory data (User.md's legacy dream-extract section, the dream
 * timestamps file, queue.jsonl) is preserved untouched per the
 * zero-deletion rule.
 */
object NebulaMemoryHook extends PreCompactionHook:

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    // Raise the post-compaction consolidation-reminder signal; the consumer is
    // ContextRefresher.buildMemoryBlock (first Nebula injection after compaction).
    IO(nebflow.agent.MemoryHygieneSignal.markCompacted())

end NebulaMemoryHook
