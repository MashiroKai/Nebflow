package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.*

/**
 * Mail turn extraction for flow managers (depth == 1).
 *
 * After a mail turn completes (ImmediateInput injected → processing → idle),
 * this module extracts a progress summary and appends it to the agent's
 * memory file. This gives managers a persistent record of coordination
 * turns that survives context compaction.
 *
 * Called as a background fiber from finishTurnCont — does not block the
 * agent's return to idle. Failures are silently swallowed.
 */
object MailTurnCompaction:

  private val logger = NebflowLogger.forName("nebflow.compact")

  /**
   * Extract a progress summary from the completed mail turn and append it
   * to the agent's memory file.
   *
   * @param allMessages  Full message history
   * @param turnStartIdx Index where the mail turn started (the Mail message position)
   * @param turnEndIdx   Index where the mail turn ended (total messages after turn)
   * @param sessionId    Agent session ID
   * @param agentName    Agent name (for memory file path)
   * @param sessionName  Display name for archival
   * @param resources    Shared resources (for archiver)
   */
  def extractAndStore(
    allMessages: List[Message],
    turnStartIdx: Int,
    turnEndIdx: Int,
    sessionId: String,
    agentName: String,
    sessionName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    val turnMessages = allMessages.slice(turnStartIdx, turnEndIdx)
    if turnMessages.size < 2 then IO.unit // too short to summarize
    else
      val summary = buildProgressSummary(turnMessages)
      for
        _ <- appendToAgentMemory(agentName, summary)
        _ <- resources.historyArchiver
          .archiveCompaction(
            sessionId = sessionId,
            sessionName = sessionName,
            agentName = agentName,
            before = turnMessages,
            after = turnMessages, // not compaction — just archival of the turn
            mode = "manager-turn",
            extra = Map("summary" -> summary.take(500))
          )
          .void
          .handleErrorWith(e => IO(logger.warn(s"MailTurn archive failed for $agentName: ${e.getMessage}")).void)
      yield ()

      end for

    end if

  end extractAndStore

  /** Build a text progress summary from the turn's messages.
   *
   * Heuristic extraction (no LLM call):
   * - Request: first User message content (the Mail text)
   * - Actions: count of tool calls by name
   * - Result: last Assistant message text
   */
  def buildProgressSummary(messages: List[Message]): String =
    val firstUserText = messages
      .find(_.role == MessageRole.User)
      .map(extractText)
      .getOrElse("(unknown)")

    val toolCallSummary = messages
      .flatMap(_.content.toOption.toList.flatten)
      .collect { case ContentBlock.ToolUse(_, name, _) => name }
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toList
      .sortBy(-_._2)
      .map { (name, count) => s"  $name ×$count" }
      .mkString("\n")

    val lastAssistantText = messages.reverse
      .find(_.role == MessageRole.Assistant)
      .map(extractText)
      .getOrElse("(no response)")

    val timestamp = java.time.Instant.now().toString
    val dateStr = java.time.LocalDateTime
      .now()
      .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    // Project-level summary format — focuses on progress, decisions, and outcomes
    // rather than mechanical Request/Actions/Result logs
    s"""### [$dateStr] ${firstUserText.linesIterator.nextOption().getOrElse("(task)").take(80)}
       |**Progress**: ${lastAssistantText.take(400)}
       |**Tools**: ${
        if toolCallSummary.nonEmpty then toolCallSummary.replace("\n  ", ", ").replace("  ", "") else "none"
      }
       |""".stripMargin

  end buildProgressSummary

  private def extractText(msg: Message): String =
    msg.content match
      case Left(text) => text
      case Right(blocks) =>
        blocks
          .collect {
            case ContentBlock.Text(t) => t
            case ContentBlock.ToolResult(_, content, _) => content.take(100)
          }
          .mkString(" ")

  /** Append a progress note to the agent's memory file. */
  private def appendToAgentMemory(agentName: String, note: String): IO[Unit] = IO.blocking {
    val memPath = PathUtil.dataRoot / "agents" / agentName / "memory.md"
    val existing = if os.exists(memPath) then os.read(memPath) else ""
    // Append under a "## Project Progress" section
    val sectionMarker = "## Project Progress"
    val updated =
      if existing.contains(sectionMarker) then
        // Append after existing section
        val idx = existing.indexOf(sectionMarker)
        val (before, after) = existing.splitAt(idx + sectionMarker.length)
        before + "\n" + note + after
      else existing + "\n\n" + sectionMarker + "\n" + note
    os.write.over(memPath, updated, createFolders = true)
    logger.info(s"Appended mail turn summary to $memPath")
  }.void

end MailTurnCompaction
