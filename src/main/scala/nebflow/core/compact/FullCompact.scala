package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.ReadTracker
import nebflow.shared.*
import nebflow.shared.given

import java.nio.file.{Files, Paths}

object FullCompact:

  private val logger = NebflowLogger.forName("nebflow.compact")

  // Continuation prompt — appended after the summary to prevent the model from
  // apologizing, recapping, or asking the user what to do.
  private val ContinuationPrompt =
    """
      |Continue the conversation from where it left off without asking the user any further questions.
      |Resume directly — do not acknowledge the summary, do not recap what was happening,
      |do not preface with "I'll continue" or similar. Pick up the last task as if the break never happened.
      |""".stripMargin.trim

  /**
   * Parse LLM compaction response and build the compacted message list.
   *
   * The result is: [summaryMessage] + [fileRestoreMessage?]
   *
   * All original messages are replaced by the summary — no recent rounds are preserved.
   * This avoids a compression death-loop when the last few messages contain huge tool results
   * that can't be reduced, making compaction ineffective.
   *
   * @param text            LLM response text (contains <analysis>, <summary>, <files>)
   * @param originalMessages Original message history before compaction
   * @param projectRoot     Project root for file path resolution
   * @param recentReadPaths Recently read file paths (from ReadTracker) for automatic restoration
   * @return Right(compactedMessages) or Left(errorMessage)
   */
  def parseResponse(
    text: String,
    originalMessages: List[Message],
    projectRoot: String = "",
    recentReadPaths: List[String] = Nil
  ): Either[String, List[Message]] =
    if text.isEmpty then Left("Compact LLM returned empty response")
    else
      // 1. Strip <analysis> block (drafting scratchpad)
      val withoutAnalysis = stripAnalysis(text)

      // 2. Extract <summary> content
      val summaryText = extractSummary(withoutAnalysis)

      // 3. Build file restore content from ReadTracker paths
      val fileRestoreSection = buildFileRestoreSection(recentReadPaths, Set.empty, projectRoot)

      // 4. Assemble summary message
      val message = Message(
        MessageRole.User,
        Left(
          s"<context-compact mode=\"full\" preservedRounds=0>" +
            s"Compressed ${originalMessages.size} messages into summary.\n\n" +
            summaryText +
            "\n\n" + ContinuationPrompt +
            fileRestoreSection +
            "\n</context-compact>"
        )
      )

      Right(List(message))
  end parseResponse

  // ------------------------------------------------------------------
  // Text parsing
  // ------------------------------------------------------------------

  /**
   * Strip <analysis>...</analysis> block — it's a drafting scratchpad that
   * improves summary quality but has no value in the final context.
   */
  private def stripAnalysis(text: String): String =
    val result = text.replaceFirst("(?s)<analysis>.*?</analysis>\\s*", "")
    if result.trim.isEmpty then text else result

  /**
   * Extract content from <summary>...</summary> tags.
   * Falls back to full text if no summary tags found.
   */
  private def extractSummary(text: String): String =
    "(?s)<summary>(.*?)</summary>".r.findFirstMatchIn(text) match
      case Some(m) => m.group(1).trim
      case None => text.trim

  // ------------------------------------------------------------------
  // File restoration from ReadTracker
  // ------------------------------------------------------------------

  /**
   * Build file restore section from ReadTracker paths.
   * Each file truncated to postCompactMaxCharsPerFile.
   * Total budget capped by postCompactTokenBudget.
   */
  private def buildFileRestoreSection(
    readPaths: List[String],
    preservedFilePaths: Set[String],
    projectRoot: String
  ): String =
    if readPaths.isEmpty then ""
    else
      val config = CompactConfig()
      val absolutePaths = readPaths
        .map { p =>
          if p.startsWith("/") then p else s"$projectRoot/$p"
        }
        .filter(isWithinProject(_, projectRoot))
        .filterNot(p =>
          preservedFilePaths
            .exists(pp => Paths.get(p).toAbsolutePath.normalize == Paths.get(pp).toAbsolutePath.normalize)
        )

      if absolutePaths.isEmpty then ""
      else
        val sb = new StringBuilder("\n\nRestored file contents after compaction:\n")
        var usedChars = 0
        val budget = config.postCompactTokenBudget * 4 // rough chars-per-token

        for path <- absolutePaths if usedChars < budget do
          val content = readFileContent(path, config.postCompactMaxCharsPerFile)
          if content.nonEmpty then
            val section = s"\n### `$path`\n```\n$content\n```\n"
            if usedChars + section.length <= budget then
              sb.append(section)
              usedChars += section.length

        val result = sb.toString
        if result.trim == "Restored file contents after compaction:" then ""
        else result

      end if

  /** Read file content, truncate to maxChars. Returns empty string on failure. */
  private def readFileContent(path: String, maxChars: Int): String =
    try
      val file = Paths.get(path.replaceFirst("^~", sys.props("user.home")))
      if !Files.exists(file) || !Files.isRegularFile(file) then ""
      else
        val fileSize = Files.size(file)
        // Avoid OOM: skip files larger than 2x the budget (rough bytes-to-chars safety margin)
        if fileSize > maxChars.toLong * 2 then
          // Read only the first maxChars worth of bytes
          val is = Files.newInputStream(file)
          try
            val bytes = new Array[Byte](math.min(fileSize.toInt, maxChars * 3))
            val read = is.read(bytes)
            new String(bytes, 0, read, "UTF-8").take(maxChars) + s"\n... [truncated]"
          finally is.close()
        else
          val content = new String(Files.readAllBytes(file), "UTF-8")
          if content.length <= maxChars then content
          else content.take(maxChars) + s"\n... [truncated, ${content.length - maxChars} more chars]"
      end if
    catch case _: Exception => ""

  /** Check if a path is within the project root directory. */
  private def isWithinProject(path: String, projectRoot: String): Boolean =
    try
      val resolved = Paths.get(path).toAbsolutePath.normalize
      val root = Paths.get(projectRoot).toAbsolutePath.normalize
      resolved.startsWith(root)
    catch case _: Exception => false

  /** Check if a message is a previously-generated compact summary. */
  private def isCompactSummaryMessage(msg: Message): Boolean =
    msg.content match
      case Left(text) =>
        text.startsWith("<context-compact") || text.startsWith("[System: Context was cleaned")
      case Right(blocks) =>
        blocks.exists {
          case ContentBlock.Text(t) => t.startsWith("<context-compact")
          case _ => false
        }

end FullCompact
