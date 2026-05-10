package nebflow.core.compact

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.core.hooks.*
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * Direct LLM-based context compaction — no sub-agent, no raw JSON serialization.
 *
 * Pre-processes the message history into human-readable conversation text,
 * sends a single non-streaming LLM call with a compact-specific prompt,
 * and parses the structured response into a compacted message list.
 */
object CompactService:

  private val logger = NebflowLogger.forName("nebflow.compact")

  // Maximum chars to keep per tool result in the compact input
  private val ToolResultMaxChars = 2000
  // Maximum total chars to send to the compact LLM call
  private val CompactInputMaxChars = 150_000
  // Maximum messages to include in compact input
  private val CompactInputMaxMessages = 500

  // --- System prompt for compaction (adapted from claude-code) ---

  private val CompactSystemPrompt =
    """You are a helpful AI assistant tasked with summarizing conversations.

CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.
- Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
- You already have all the context you need in the conversation above.
- Tool calls will be REJECTED and will waste your only turn — you will fail the task.
- Your entire response must be plain text: an <analysis> block followed by a <summary> block.

Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts. In your analysis process:

1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
   - The user's explicit requests and intents
   - Your approach to addressing the user's requests
   - Key decisions, technical concepts and code patterns
   - Specific details like file names, full code snippets, function signatures, file edits
   - Errors that you ran into and how you fixed them
   - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
2. Double-check for technical accuracy and completeness.

Your summary must include the following sections:

<summary>
1. Primary Request and Intent:
   [Detailed description of all the user's explicit requests and intents]

2. Key Technical Concepts:
   - [Concept 1]
   - [Concept 2]

3. Files and Code Sections:
   Each file with its path in backticks, line ranges, and what was found/changed.
   - `path/to/file` (line 42-89): description
     ```
     key code snippet
     ```

4. Errors and Fixes:
   - [Detailed description of error]: [How you fixed it]

5. Problem Solving:
   [Description of solved problems and ongoing troubleshooting efforts]

6. All User Messages:
   - [Detailed non-tool-use user message]

7. Pending Tasks:
   - [Task 1]

8. Current Work:
   [Precise description of what was being worked on immediately before this summary request.]

9. Optional Next Step:
   [The single immediate next action. Include direct quotes from the most recent conversation.]
</summary>

After the </summary> tag, list files whose content should be restored after compaction.
These should be files the assistant is actively reading, editing, or will need immediately.
Maximum 5 files.

<files>
path/to/file1
path/to/file2
</files>

Rules:
- Preserve file paths with backticks, always include line numbers when known
- Be specific, not vague
- Preserve all decisions, trade-offs, and user preferences stated
- If the user gave explicit instructions, quote them verbatim
- Keep the summary focused and information-dense
- Write the summary in the SAME language as the user's messages
"""

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  /**
   * Run compaction directly via LLM call.
   *
   * @param messages        Original message history
   * @param resources       Shared resources (LLM handle, project root, etc.)
   * @param sessionId       Session identifier for the LLM call
   * @param readTracker     Optional ReadTracker for automatic file restoration
   * @return Right(compactedMessages) or Left(errorMessage)
   */
  def compact(
    messages: List[Message],
    resources: SharedResources,
    sessionId: String,
    readTracker: Option[nebflow.core.tools.ReadTracker] = None
  ): IO[Either[String, List[Message]]] =
    val hookEngine = resources.hookEngine
    val hookCtx = HookContext(
      sessionId = Some(sessionId),
      projectRoot = resources.projectRoot.toString,
      cwd = resources.projectRoot.toString
    )

    // Fetch recently-read file paths from ReadTracker (for post-compact restoration)
    val readPathsIO = readTracker
      .map(_.recentFiles(CompactConfig().postCompactMaxFiles).map(_.map(_.toString)))
      .getOrElse(IO.pure(Nil))

    // --- PreCompact hook ---
    (readPathsIO, hookEngine.beforeCompact(messages.size, hookCtx))
      .mapN { (readPaths, preResult) =>
        (readPaths, preResult)
      }
      .flatMap { (readPaths, preResult) =>
        if preResult.decision == HookDecision.Block then
          val reason = preResult.reason.getOrElse("Compaction blocked by hook")
          logger.info(s"Compaction blocked by hook: $reason")
          IO.pure(Left(reason))
        else doCompact(messages, resources, sessionId, hookEngine, hookCtx, readPaths)
      }
  end compact

  private val MaxCompactRetries = 2

  private def doCompact(
    messages: List[Message],
    resources: SharedResources,
    sessionId: String,
    hookEngine: HookEngine,
    hookCtx: HookContext,
    recentReadPaths: List[String]
  ): IO[Either[String, List[Message]]] =
    val preprocessed = preprocessMessages(messages)
    val promptText = buildCompactPrompt(preprocessed)

    logger.info(s"Compacting ${messages.size} messages, prompt chars=${promptText.length}")

    val request = LlmRequest(
      messages = List(Message(MessageRole.User, Left(promptText))),
      sessionId = sessionId,
      agentId = "context-compact",
      tools = Some(Nil), // explicitly empty → no tools
      maxTokens = Some(8000), // generous budget for the summary
      systemStable = Some(CompactSystemPrompt)
    )

    def attemptCompact(retriesLeft: Int): IO[Either[String, List[Message]]] =
      resources.llm
        .send(request)
        .flatMap { response =>
          if response.toolCalls.nonEmpty then
            IO.pure(
              Left(s"Compact model unexpectedly returned tool calls: ${response.toolCalls.map(_.name).mkString(", ")}")
            )
          else
            val result =
              FullCompact.parseResponse(response.reply, messages, resources.projectRoot.toString, recentReadPaths)
            result match
              case Left(err) if err.contains("empty response") && retriesLeft > 0 =>
                logger.warn(s"Compact LLM returned empty response, retrying ($retriesLeft left)")
                IO.sleep(1.second) *> attemptCompact(retriesLeft - 1)
              case _ =>
                // --- PostCompact hook ---
                val afterSize = result.fold(_ => messages.size, _.size)
                val tokensSaved = estimateTokensSaved(messages.size, afterSize)
                hookEngine
                  .afterCompact(messages.size, afterSize, tokensSaved, hookCtx)
                  .map { postResult =>
                    result.map { compacted =>
                      postResult.additionalContext match
                        case Some(ctx) =>
                          // Inject additional context as a system message after compaction
                          compacted :+ Message(MessageRole.System, Left(ctx))
                        case None => compacted
                    }
                  }
        }
        .handleErrorWith { e =>
          val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          if retriesLeft > 0 then
            logger.warn(s"Compact LLM call failed: $msg, retrying ($retriesLeft left)")
            // Retry on transient errors
            IO.sleep(2.seconds) *> attemptCompact(retriesLeft - 1)
          else
            logger.warn(s"Compact LLM call failed after all retries: $msg")
            IO.pure(Left(s"Compact LLM call failed: $msg"))
        }

    attemptCompact(MaxCompactRetries)
  end doCompact

  /** Rough token estimate based on message count difference. */
  private def estimateTokensSaved(before: Int, after: Int): Long =
    ((before - after).toLong * 500).max(0) // ~500 tokens per message average

  // ------------------------------------------------------------------
  // Message pre-processing (human-readable text, not raw JSON)
  // ------------------------------------------------------------------

  /**
   * Convert message history into a clean, human-readable conversation transcript
   * suitable for summarization. Removes images, truncates long tool results,
   * skips previously-compacted summaries, and caps total size.
   */
  private def preprocessMessages(messages: List[Message]): String =
    // 1. Drop messages that are themselves compact summaries
    val filtered = messages.filterNot(isCompactSummaryMessage)

    // 2. Convert to text, capping at max messages
    val capped = if filtered.size > CompactInputMaxMessages then
      logger.info(s"Compact input capped from ${filtered.size} to $CompactInputMaxMessages messages")
      filtered.takeRight(CompactInputMaxMessages)
    else filtered

    // 3. Build conversation transcript
    val sb = new StringBuilder()
    for msg <- capped do
      val roleLabel = msg.role match
        case MessageRole.User => "User"
        case MessageRole.Assistant => "Assistant"
        case MessageRole.System => "System"
      sb.append(s"\n--- $roleLabel ---\n")
      sb.append(messageToText(msg))
      sb.append("\n")
    end for

    val result = sb.toString
    // 4. Hard cap on total chars to avoid PTL on the compact request itself
    if result.length > CompactInputMaxChars then
      logger.info(s"Compact input truncated from ${result.length} to $CompactInputMaxChars chars")
      "... [earlier conversation truncated for compaction]\n" +
        result.takeRight(CompactInputMaxChars)
    else result
  end preprocessMessages

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

  /** Render a single message as plain text for the compact prompt. */
  private def messageToText(msg: Message): String =
    msg.content match
      case Left(text) => text
      case Right(blocks) =>
        val sb = new StringBuilder()
        for block <- blocks do
          block match
            case ContentBlock.Text(text) =>
              sb.append(text).append("\n")

            case ContentBlock.Image(_, mediaType) =>
              sb.append(s"[image: $mediaType]\n")

            case ContentBlock.ToolUse(id, name, input) =>
              val inputJson = input.asJson.noSpaces
              // Truncate very long tool inputs (e.g. Write with large content)
              val inputDisplay =
                if inputJson.length > 500 then inputJson.take(500) + s" ... [${inputJson.length - 500} more chars]"
                else inputJson
              sb.append(s"[ToolUse: $name id=$id] $inputDisplay\n")

            case ContentBlock.ToolResult(toolUseId, content, isError) =>
              val prefix = if isError.contains(true) then "[ToolResult ERROR" else "[ToolResult"
              val display =
                if content.length > ToolResultMaxChars then
                  content.take(ToolResultMaxChars) + s"\n... [${content.length - ToolResultMaxChars} more chars]"
                else content
              sb.append(s"$prefix id=$toolUseId]\n$display\n")

            case ContentBlock.Thinking(thinking, _) =>
              sb.append(s"[Thinking]\n$thinking\n")
        end for
        sb.toString
  end messageToText

  /** Wrap the preprocessed transcript with the compact instruction preamble. */
  private def buildCompactPrompt(transcript: String): String =
    s"""Please summarize the following conversation. Follow the instructions in your system prompt.

$transcript

Please provide your summary now."""

end CompactService
