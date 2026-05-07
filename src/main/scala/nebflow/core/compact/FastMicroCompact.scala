package nebflow.core.compact

import nebflow.shared.*

/**
 * Rule-driven micro compaction — no LLM call needed.
 *
 * Fires ONLY when the prompt cache is cold (distance from last assistant
 * message exceeds the configured TTL). Replaces old compactable tool_result
 * content with a placeholder, preserving the most recent N results.
 *
 * Directly mutates the message list — callers should write the result back
 * to state.messages.
 */
object FastMicroCompact:

  private val Placeholder = "[Output removed to free context space]"

  private val CompactableTools = Set(
    "Read", "Bash", "Grep", "Glob", "WebSearch", "WebFetch", "Curl",
    "Edit", "Write"
  )

  /**
   * Run fast micro compaction if conditions are met.
   *
   * @return Some(compacted) if compaction fired, None if skipped (cache still hot or nothing to compact)
   */
  def apply(
    messages: List[Message],
    cacheTtlMinutes: Int = CompactConfig().microCacheTtlMinutes,
    keepRecent: Int = CompactConfig().microKeepRecent
  ): Option[List[Message]] =
    if messages.isEmpty then None
    else
      // Check cache TTL: find last assistant message timestamp
      val lastAssistantTs = messages.collect {
        case m if m.role == MessageRole.Assistant && m.timestamp > 0 => m.timestamp
      }.maxOption

      // Skip if cache is still hot (last assistant within TTL)
      // timestamp == 0 (old messages without ts) → treat as cold, compress directly
      lastAssistantTs match
        case Some(ts) if (System.currentTimeMillis() - ts) < cacheTtlMinutes * 60_000L =>
          None // cache is hot, skip
        case _ =>
          doCompact(messages, keepRecent)

  private def doCompact(messages: List[Message], keepRecent: Int): Option[List[Message]] =
    // Collect compactable tool_use IDs in order of appearance
    val allToolUseIds = messages.flatMap {
      case Message(MessageRole.Assistant, Right(blocks), _) =>
        blocks.collect {
          case ContentBlock.ToolUse(id, name, _) if CompactableTools.contains(name) => id
        }
      case _ => Nil
    }

    if allToolUseIds.size <= keepRecent then None
    else
      val keepSet = allToolUseIds.takeRight(keepRecent).toSet
      val clearSet = allToolUseIds.filterNot(keepSet.contains).toSet

      if clearSet.isEmpty then None
      else
        val result = messages.map {
          case msg @ Message(MessageRole.User, Right(blocks), _) =>
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

        if changed then Some(result) else None
      end if
    end if
  end doCompact

end FastMicroCompact
