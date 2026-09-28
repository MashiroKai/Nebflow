package nebflow.core.compact

import nebflow.shared.*

/**
 * Rule-driven micro compaction — no LLM call needed.
 *
 * Fires ONLY when the prompt cache is cold (distance from last assistant
 * message exceeds the cold-after threshold). Replaces old compactable
 * tool_result content with a placeholder, preserving the most recent N results.
 *
 * The default cold-after threshold (30min) is aligned with the measured
 * provider prompt-cache TTL (2026-08-18 analysis: <5min 92.8% hit → 5-30min
 * 53.3% → >30min 4.4%, so cache is effectively gone after ~30min). Previously
 * hardcoded to 2h, which left the critical 30min-2h window unshrunken: the
 * wake-up request re-sent full history at full price with nothing compacted.
 *
 * Directly mutates the message list — callers should write the result back
 * to state.messages.
 */
object FastMicroCompact:

  private val Placeholder = "[Output removed to free context space]"

  /** Default cold-cache threshold: aligned with measured provider cache TTL (~30min). */
  val DefaultColdAfterMs: Long = 30 * 60 * 1000L

  // Hardcoded: keep the most recent 5 tool results untouched
  private val KeepRecent: Int = 5

  /**
   * Minimum-savings guard: only replace old tool results when the shrunk list
   * is at most this fraction of the original. Replacing results with
   * placeholders destroys information (the agent can re-Read, but that costs a
   * round-trip); firing for tiny savings is not worth the loss.
   */
  val MinSavingsRatio: Double = 0.6

  private val CompactableTools = Set(
    "Read",
    "Bash",
    "Grep",
    "Glob",
    "WebSearch",
    "WebFetch",
    "Curl",
    "Edit",
    "Write"
  )

  /**
   * Run fast micro compaction if conditions are met.
   *
   * @param messages    the message list to inspect
   * @param coldAfterMs idle distance (from last assistant message) after which
   *                    the cache is considered cold. Defaults to 30min.
   * @return Some(compacted) if compaction fired, None if skipped (cache still hot, nothing to compact, or savings below threshold)
   */
  def apply(messages: List[Message], coldAfterMs: Long = DefaultColdAfterMs): Option[List[Message]] =
    if messages.isEmpty then None
    else
      // Check cache TTL: find last assistant message timestamp
      val lastAssistantTs = messages.collect {
        case m if m.role == MessageRole.Assistant && m.timestamp > 0 => m.timestamp
      }.maxOption

      // Skip if cache is still hot (last assistant within threshold)
      // timestamp == 0 (old messages without ts) → treat as cold, compress directly
      lastAssistantTs match
        case Some(ts) if (System.currentTimeMillis() - ts) < coldAfterMs =>
          None // cache is hot, skip
        case _ =>
          doCompact(messages)

  private def doCompact(messages: List[Message]): Option[List[Message]] =
    // Collect compactable tool_use IDs in order of appearance
    val allToolUseIds = messages.flatMap {
      case Message(MessageRole.Assistant, Right(blocks), _, _) =>
        blocks.collect {
          case ContentBlock.ToolUse(id, name, _) if CompactableTools.contains(name) => id
        }
      case _ => Nil
    }

    if allToolUseIds.size <= KeepRecent then None
    else
      val keepSet = allToolUseIds.takeRight(KeepRecent).toSet
      val clearSet = allToolUseIds.filterNot(keepSet.contains).toSet

      if clearSet.isEmpty then None
      else
        val result = messages.map {
          case msg @ Message(MessageRole.User, Right(blocks), _, _) =>
            val newBlocks = blocks.map {
              case tr: ContentBlock.ToolResult if clearSet.contains(tr.toolUseId) && tr.content != Placeholder =>
                tr.copy(content = Placeholder)
              case other => other
            }
            msg.copy(content = Right(newBlocks))
          case other => other
        }

        // Check if anything actually changed
        val changed = messages.zip(result).exists { (before, after) =>
          before.content != after.content
        }

        if changed then
          // Minimum-savings guard: skip if the shrink is below threshold —
          // destroying old tool results for <40% savings isn't worth it.
          val originalSize = roughSize(messages)
          val newSize = roughSize(result)
          if originalSize > 0 && newSize.toDouble / originalSize > MinSavingsRatio then None
          else Some(result)
        else None
      end if
    end if
  end doCompact

  /** Cheap token-proxy for the savings guard: sum of text/result content lengths. */
  private def roughSize(messages: List[Message]): Long =
    messages.foldLeft(0L) { (acc, m) =>
      acc + (m.content match
        case Left(text) => text.length.toLong
        case Right(blocks) =>
          blocks.foldLeft(0L) { (bacc, b) =>
            bacc + (b match
              case tr: ContentBlock.ToolResult => tr.content.length.toLong
              case tu: ContentBlock.ToolUse => (tu.name.length + io.circe.Json.fromJsonObject(tu.input).noSpaces.length).toLong
              case other => other.toString.length.toLong
            )
          }
      )
    }

end FastMicroCompact
