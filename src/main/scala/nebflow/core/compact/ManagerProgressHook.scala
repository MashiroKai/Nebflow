package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.*

/**
 * Pre-compaction hook for Team Managers (depth 1).
 *
 * Replaces MailTurnCompaction's per-turn extraction: instead of running
 * after every mail turn, a progress summary is built heuristically (no LLM
 * call) right before compaction and appended to the manager's memory.md.
 */
object ManagerProgressHook extends PreCompactionHook:
  private val logger = NebflowLogger.forName("nebflow.prehook.manager")

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    if messages.size < 10 then IO.unit
    else
      val summary = MailTurnCompaction.buildProgressSummary(messages)
      appendToMemory(agentName, teamName, summary)

  /** Append a progress note under `## Project Progress` in the agent's memory.md. */
  private def appendToMemory(agentName: String, teamName: Option[String], note: String): IO[Unit] =
    IO.blocking {
      val memPath = teamName match
        case Some(tn) => PathUtil.dataRoot / "teams" / tn / "agents" / agentName / "memory.md"
        case None     => PathUtil.dataRoot / "agents" / agentName / "memory.md"
      val existing = if os.exists(memPath) then os.read(memPath) else ""
      val sectionMarker = "## Project Progress"
      val updated =
        if existing.contains(sectionMarker) then
          val idx = existing.indexOf(sectionMarker)
          val (before, after) = existing.splitAt(idx + sectionMarker.length)
          before + "\n" + note + after
        else existing + "\n\n" + sectionMarker + "\n" + note
      os.write.over(memPath, updated, createFolders = true)
      logger.info(s"Appended progress summary to $memPath")
    }.void
end ManagerProgressHook
