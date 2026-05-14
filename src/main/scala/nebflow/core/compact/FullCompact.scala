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

  // How many recent "rounds" to preserve verbatim after compaction.
  // A round = one assistant turn (possibly with tool_use) + the corresponding user response.
  val PreserveRecentRounds = 3

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
   * The result is: [summaryMessage] ++ preservedRecentMessages ++ [fileRestoreMessage?]
   *
   * This preserves the last N rounds of original messages so the model doesn't
   * lose its immediate working context (open files, recent tool results, etc.).
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

      // 3. Split original messages: recent rounds to preserve, rest already summarized
      val (preservable, recentToPreserve) = splitRecentRounds(originalMessages, PreserveRecentRounds)

      // 4. Collect file paths already present in preserved messages (skip re-injecting)
      val preservedFilePaths = collectReadFilePaths(recentToPreserve)

      // 5. Build file restore content from ReadTracker paths, skipping duplicates
      val fileRestoreSection = buildFileRestoreSection(recentReadPaths, preservedFilePaths, projectRoot)

      // 6. Assemble summary message
      val message = Message(
        MessageRole.User,
        Left(
          s"<context-compact mode=\"full\" preservedRounds=${PreserveRecentRounds}>" +
            s"Compressed ${originalMessages.size} messages into summary + ${recentToPreserve.size} preserved recent messages.\n\n" +
            summaryText +
            "\n\n" + ContinuationPrompt +
            fileRestoreSection +
            "\n</context-compact>"
        )
      )

      Right(List(message) ++ recentToPreserve)
  end parseResponse

  // ------------------------------------------------------------------
  // Recent round preservation
  // ------------------------------------------------------------------

  /**
   * Split message history into (messagesToSummarize, recentRoundsToPreserve).
   *
   * A "round" starts with an assistant message. We walk backwards from the
   * tail and count N complete rounds (assistant + subsequent user response).
   * System messages in the preserved tail are kept.
   */
  private def splitRecentRounds(
    messages: List[Message],
    roundsToKeep: Int
  ): (List[Message], List[Message]) =
    if roundsToKeep <= 0 || messages.size <= 4 then
      // Too few messages to split meaningfully — summarize everything, preserve nothing
      (messages, Nil)
    else
      // Walk backwards to find the split point
      var roundsFound = 0
      var splitIdx = messages.size
      var i = messages.size - 1
      while i >= 0 && roundsFound < roundsToKeep do
        if messages(i).role == MessageRole.Assistant then
          roundsFound += 1
          splitIdx = i
        i -= 1
      end while

      if roundsFound < roundsToKeep || splitIdx == 0 then
        // Not enough complete rounds — summarize everything
        (messages, Nil)
      else
        val (toSummarize, toPreserve) = messages.splitAt(splitIdx)
        // Filter out any prior compact summaries from the preserved tail
        val cleanPreserve = toPreserve.filterNot(isCompactSummaryMessage)
        (toSummarize, cleanPreserve)
  end splitRecentRounds

  /** Collect file paths that appear as Read tool_use in the given messages. */
  private def collectReadFilePaths(messages: List[Message]): Set[String] =
    messages.flatMap { msg =>
      msg.content match
        case Right(blocks) =>
          blocks.collect { case ContentBlock.ToolUse(_, "Read", input) =>
            input("file_path").flatMap(_.asString)
          }.flatten
        case _ => Nil
    }.toSet

  // ------------------------------------------------------------------
  // Text parsing (unchanged logic, cleaner structure)
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

  /** Strip <files>...</files> tag — no longer used for file restoration. */
  private def stripFiles(text: String): String =
    val startTag = "<files>"
    val endTag = "</files>"
    val startIdx = text.indexOf(startTag)
    val endIdx = text.indexOf(endTag)
    if startIdx < 0 || endIdx < 0 || endIdx <= startIdx then text
    else text.substring(0, startIdx) + text.substring(endIdx + endTag.length)

  // ------------------------------------------------------------------
  // File restoration from ReadTracker
  // ------------------------------------------------------------------

  /**
   * Build file restore section from ReadTracker paths.
   * Skips files already present in the preserved messages.
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

  /** Read file content, truncate to maxChars. Returns empty string on failure. */
  private def readFileContent(path: String, maxChars: Int): String =
    try
      val file = Paths.get(path.replaceFirst("^~", sys.props("user.home")))
      if !Files.exists(file) || !Files.isRegularFile(file) then ""
      else
        val content = new String(Files.readAllBytes(file), "UTF-8")
        if content.length <= maxChars then content
        else content.take(maxChars) + s"\n... [truncated, ${content.length - maxChars} more chars]"
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
