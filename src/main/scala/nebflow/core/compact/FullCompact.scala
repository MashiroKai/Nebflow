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
   * Outcome of a successful full-compaction parse.
   *
   * @param messages       summary message + preserved tail rounds (in order)
   * @param preservedRounds ACTUAL number of tail rounds preserved verbatim —
   *                       ≤ CompactConfig.preservedRounds; guardrails (char
   *                       budget / must-summarize-something) may shrink it to 0
   */
  final case class CompactOutcome(
    messages: List[Message],
    preservedRounds: Int
  )

  /**
   * Parse LLM compaction response and build the compacted message list.
   *
   * The result is: [summaryMessage] ++ [preserved tail rounds]
   *
   * 2026-09-07 (尾部保真): the summary no longer replaces the ENTIRE history.
   * The last N conversation rounds (CompactConfig.preservedRounds, default 2)
   * are preserved VERBATIM after the summary — the last user instruction (the
   * active task) rides in those messages. The old all-replaced design
   * (preservedRounds=0, adopted to avoid a compression death-loop when tail
   * rounds carried unshrinkable tool results) is now guarded instead of
   * avoided: Layer B (#38, 2026-09-01) already replaces oversized ToolResults
   * with persisted-output placeholders BEFORE the compact turn, the preserved
   * tail re-caps each ToolResult, the whole tail is char-budgeted, and the cut
   * always summarizes at least one message — a no-shrink compaction (death
   * loop) is impossible by construction. See [[preservedTail]].
   *
   * @param text            LLM response text (contains <analysis>, <summary>, <files>)
   * @param originalMessages Original message history before compaction (the
   *                         compaction input, typically ending with the
   *                         compact reminder — it is excluded here)
   * @param projectRoot     Project root for file path resolution
   * @param recentReadPaths Recently read file paths (from ReadTracker) for automatic restoration
   * @return Right(CompactOutcome) or Left(errorMessage)
   */
  def parseResponseDetailed(
    text: String,
    originalMessages: List[Message],
    projectRoot: String = "",
    recentReadPaths: List[String] = Nil
  ): Either[String, CompactOutcome] =
    if text.isEmpty then Left("Compact LLM returned empty response")
    else
      // 1. Strip <analysis> block (drafting scratchpad)
      val withoutAnalysis = stripAnalysis(text)

      // 2. Extract <summary> content
      val summaryText = extractSummary(withoutAnalysis)

      // 3. Build file restore content from ReadTracker paths
      val fileRestoreSection = buildFileRestoreSection(recentReadPaths, Set.empty, projectRoot)

      // 4. Pick the preserved tail rounds (verbatim, guardrailed)
      val config = CompactConfig()
      val conversation = dropTrailingCompactReminder(originalMessages)
      val (preserved, roundsKept) = preservedTail(conversation, config)

      // 5. Assemble summary message
      val tailNote =
        if roundsKept > 0 then
          s"Last $roundsKept round(s) (${preserved.size} messages) are preserved VERBATIM after " +
            "this summary — they are the live conversation tail (the active task), not to be re-summarized.\n\n"
        else ""
      val message = Message(
        MessageRole.User,
        Left(
          s"<context-compact mode=\"full\" preservedRounds=$roundsKept>" +
            s"Compressed ${conversation.size - preserved.size} messages into summary.\n\n" +
            tailNote +
            summaryText +
            "\n\n" + ContinuationPrompt +
            fileRestoreSection +
            "\n</context-compact>"
        )
      )

      Right(CompactOutcome(message :: preserved, roundsKept))
  end parseResponseDetailed

  /** Compatibility wrapper — discards the preservedRounds count. The actor
    * path uses parseResponseDetailed; specs and demos use this. */
  def parseResponse(
    text: String,
    originalMessages: List[Message],
    projectRoot: String = "",
    recentReadPaths: List[String] = Nil
  ): Either[String, List[Message]] =
    parseResponseDetailed(text, originalMessages, projectRoot, recentReadPaths).map(_.messages)

  /** Read back the truthful preservedRounds value from the label of the
    * compact summary message — the label written by parseResponseDetailed is
    * the single source of truth (kept in sync by FullCompactSpec). Used by
    * the CompactionComplete archive path, which only sees the message list. */
  def preservedRoundsOf(messages: List[Message]): Int =
    messages.headOption
      .flatMap(_.content.left.toOption)
      .flatMap(s => "preservedRounds=(\\d+)".r.findFirstMatchIn(s))
      .map(_.group(1).toInt)
      .getOrElse(0)

  // ------------------------------------------------------------------
  // Tail-fidelity preservation (2026-09-07 压缩摘要尾部保真)
  // ------------------------------------------------------------------

  /** Remove trailing compact reminder(s) — the reminder is the summarization
    * instruction injected by CompactService.buildCompactReminder, not
    * conversation content. */
  private def dropTrailingCompactReminder(messages: List[Message]): List[Message] =
    messages.reverse.dropWhile(CompactService.isCompactReminder).reverse

  /** A round START is a user message carrying text and NO tool_result blocks:
    * genuine user input (chat text, Mail, injected instruction). Tool-result
    * user messages belong to the ongoing round and never start one. Cutting
    * at a round start is orphan-safe by construction — the region head
    * references no tool_use outside the region (the OpenAI adapter has no
    * orphaned-tool_result guard, so this matters beyond Anthropic). */
  private def isRoundStart(msg: Message): Boolean =
    msg.role == MessageRole.User && !hasToolResultBlocks(msg) && {
      msg.content match
        case Left(text) => text.trim.nonEmpty
        case Right(blocks) =>
          blocks.exists {
            case ContentBlock.Text(t) => t.trim.nonEmpty
            case _ => false
          }
    }

  private def hasToolResultBlocks(msg: Message): Boolean =
    msg.content match
      case Right(blocks) => blocks.exists(_.isInstanceOf[ContentBlock.ToolResult])
      case _ => false

  /** Select the preserved tail: the last `config.preservedRounds` rounds,
    * verbatim, under two death-loop guardrails:
    *   1. the cut must summarize at least one message (cutIdx > 0) —
    *      preserving the entire history would make compaction a no-op;
    *   2. the tail must fit `preservedRoundsMaxChars` — over budget, drop the
    *      oldest candidate round and retry; a single round over budget ⇒
    *      preserve none (fall back to pure summary).
    * ToolResult content inside the tail is capped per result (defense in
    * depth — Layer B usually already placeholder-ized oversized results).
    * User text is NEVER truncated. Returns (messages, roundsKept). */
  private def preservedTail(
    conversation: List[Message],
    config: CompactConfig
  ): (List[Message], Int) =
    val roundStarts = conversation.zipWithIndex.collect { case (m, i) if isRoundStart(m) => i }
    val maxK = math.min(config.preservedRounds, roundStarts.size)

    def candidate(k: Int): Option[(List[Message], Int)] =
      val cutIdx = roundStarts(roundStarts.size - k)
      if cutIdx <= 0 then None // guardrail 1
      else
        val capped = conversation.drop(cutIdx).map(capToolResults(_, config))
        if estimateChars(capped) <= config.preservedRoundsMaxChars then Some((capped, k))
        else None // guardrail 2

    (maxK to 1 by -1).flatMap(candidate).headOption.getOrElse((Nil, 0))
  end preservedTail

  /** Cap each ToolResult block's content at config.preservedToolResultMaxChars. */
  private def capToolResults(msg: Message, config: CompactConfig): Message =
    msg.content match
      case Right(blocks) =>
        val capped = blocks.map {
          case tr @ ContentBlock.ToolResult(_, content, _)
              if content.length > config.preservedToolResultMaxChars =>
            tr.copy(
              content = content.take(config.preservedToolResultMaxChars) +
                s"\n... [preserved tail: truncated, ${content.length - config.preservedToolResultMaxChars} more chars]"
            )
          case other => other
        }
        msg.copy(content = Right(capped))
      case _ => msg

  /** Rough char estimate of a message list (mirror of CompactUtils's private
    * estimator; images counted as ~4k chars of transport cost). */
  private def estimateChars(messages: List[Message]): Int =
    messages.map { msg =>
      msg.content match
        case Left(text) => text.length
        case Right(blocks) =>
          blocks.map {
            case ContentBlock.Text(t) => t.length
            case ContentBlock.ToolResult(_, content, _) => content.length
            case ContentBlock.ToolUse(_, _, input) => input.asJson.noSpaces.length
            case ContentBlock.Thinking(t, _) => t.length
            case ContentBlock.Image(_, _) => 4000 // rough
          }.sum
    }.sum

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
