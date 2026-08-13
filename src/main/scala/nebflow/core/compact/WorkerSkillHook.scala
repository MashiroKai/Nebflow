package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.shared.*

/**
 * Pre-compaction hook for Team Workers / Flow agents (depth 2+).
 *
 * Replaces ExperienceExtractor's per-turn extraction: instead of an LLM
 * call after every worker turn, reusable skill proposals are extracted
 * right before compaction — dramatically reducing LLM overhead.
 */
object WorkerSkillHook extends PreCompactionHook:
  private val logger = NebflowLogger.forName("nebflow.prehook.worker")

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    if messages.size < 10 then IO.unit
    else
      ExperienceExtractor
        .extractAndStore(messages, agentName, sessionId, resources)
        .handleErrorWith(e => IO(logger.warn(s"Skill extraction failed: ${e.getMessage}")).void)
end WorkerSkillHook
