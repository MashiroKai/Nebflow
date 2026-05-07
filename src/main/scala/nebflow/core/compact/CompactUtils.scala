package nebflow.core.compact

import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*

object CompactUtils:

  private val logger = NebflowLogger.forName("nebflow.compact")

  /** Replace Image blocks with placeholder text to avoid sending base64 to SubAgent. */
  def stripImages(messages: List[Message]): List[Message] =
    messages.map {
      case msg @ Message(_, Right(blocks)) =>
        val stripped = blocks.map {
          case ContentBlock.Image(_, mediaType) => ContentBlock.Text(s"[image: $mediaType]")
          case other => other
        }
        msg.copy(content = Right(stripped))
      case other => other
    }

  // Placeholder for stripped tool results
  private val ToolResultPlaceholder = "[Output removed to free context space]"

  /**
   * Emergency context cleanup — progressive, non-destructive approach.
   * Does NOT require LLM. Applied when compaction fails repeatedly.
   *
   * Priority (each step only runs if previous was insufficient):
   *   1. Strip large ToolResult content (keep tool_use/tool_result structure)
   *   2. Remove oldest tool-result message pairs entirely
   *   3. Keep only last N messages
   *
   * @param messages   current message history
   * @param keepAtEnd  minimum messages to keep at the tail (default 20)
   * @return cleaned messages + description of what was done
   */
  def emergencyClean(
    messages: List[Message],
    keepAtEnd: Int = 20
  ): (List[Message], String) =

    // Phase 1: Replace ToolResult content with placeholder
    val phase1 = stripToolResults(messages)
    val p1Freed = estimateChars(messages) - estimateChars(phase1)
    if p1Freed > 0 then
      logger.info(s"Emergency clean phase 1: stripped tool result content, freed ~${p1Freed / 1024}k chars")
    end if

    // If we removed enough, stop here
    if phase1.size <= keepAtEnd * 2 then
      val desc =
        if p1Freed > 0
        then s"Stripped ${countToolResults(phase1)} tool results, kept all ${phase1.size} messages"
        else s"No tool results to strip, ${phase1.size} messages kept"
      return (ensureHeadUser(phase1, messages.size), desc)
    end if

    // Phase 2: Remove oldest tool-result message pairs (user messages containing ToolResult blocks)
    val phase2 = removeOldToolResultMessages(phase1, keepAtEnd)
    val p2Removed = phase1.size - phase2.size
    if p2Removed > 0 then
      logger.info(s"Emergency clean phase 2: removed $p2Removed oldest tool-result messages, ${phase2.size} remaining")
    end if

    if phase2.size <= keepAtEnd * 2 then
      val desc = s"Stripped tool results + removed $p2Removed oldest tool-result messages, ${phase2.size} remaining"
      return (ensureHeadUser(phase2, messages.size), desc)
    end if

    // Phase 3: Keep only last N messages
    val phase3 = phase2.takeRight(keepAtEnd)
    logger.info(s"Emergency clean phase 3: truncated to last $keepAtEnd messages")
    val desc = s"Stripped tool results + removed old messages, kept last $keepAtEnd of ${messages.size}"
    (ensureHeadUser(phase3, messages.size), desc)
  end emergencyClean

  /** Replace all ToolResult block content with a short placeholder. */
  private def stripToolResults(messages: List[Message]): List[Message] =
    messages.map {
      case msg @ Message(_, Right(blocks)) =>
        val stripped = blocks.map {
          case tr: ContentBlock.ToolResult =>
            tr.copy(content = ToolResultPlaceholder)
          case other => other
        }
        msg.copy(content = Right(stripped))
      case other => other
    }

  /**
   * Remove the oldest user messages that contain ToolResult blocks,
   * and their corresponding assistant messages (the ones with matching ToolUse).
   * Preserves recent conversation and non-tool messages.
   */
  private def removeOldToolResultMessages(messages: List[Message], keepAtEnd: Int): List[Message] =
    // Identify indices of user messages containing ToolResult blocks
    val toolResultIndices = messages.zipWithIndex.collect {
      case (msg, idx) if msg.role == MessageRole.User && hasToolResults(msg) => idx
    }

    // We want to remove the OLDEST tool-result messages, keeping at least `keepAtEnd` messages at the end
    val safeEnd = messages.size - keepAtEnd
    val removableIndices = toolResultIndices.filter(_ < safeEnd).toSet

    // Build a set of tool_use IDs being removed so we can also remove the corresponding ToolUse blocks
    val removedToolUseIds = messages.zipWithIndex
      .collect {
        case (msg, idx) if removableIndices.contains(idx) =>
          msg.content.toOption.toList.flatten.collect { case ContentBlock.ToolResult(id, _, _) =>
            id
          }
      }
      .flatten
      .toSet

    // Filter: remove tool-result messages, and strip matching ToolUse blocks from assistant messages
    messages.zipWithIndex.flatMap { case (msg, idx) =>
      if removableIndices.contains(idx) then Nil
      else if msg.role == MessageRole.Assistant && removedToolUseIds.nonEmpty then
        msg.content match
          case Right(blocks) =>
            val filtered = blocks.filter {
              case ContentBlock.ToolUse(id, _, _) => !removedToolUseIds.contains(id)
              case _ => true
            }
            // If the assistant message only had ToolUse blocks and all were removed, drop it entirely
            val hasContent = filtered.exists {
              case ContentBlock.ToolUse(_, _, _) => false
              case ContentBlock.Text(t) if t.trim.isEmpty => false
              case ContentBlock.Thinking(t, _) if t.trim.isEmpty => false
              case _ => true
            }
            if hasContent then List(msg.copy(content = Right(filtered)))
            else Nil
          case _ => List(msg)
      else List(msg)
    }
  end removeOldToolResultMessages

  private def hasToolResults(msg: Message): Boolean =
    msg.content match
      case Right(blocks) => blocks.exists(_.isInstanceOf[ContentBlock.ToolResult])
      case _ => false

  private def countToolResults(messages: List[Message]): Int =
    messages
      .flatMap(_.content.toOption.toList.flatten)
      .count(_.isInstanceOf[ContentBlock.ToolResult])

  /** Rough char estimate of message list. */
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
            case ContentBlock.Image(_, _) => 100 // rough
          }.sum
    }.sum

  /**
   * Ensure the message list starts with a user message (required by most LLM APIs).
   * If the first message is not user role, prepend a system marker.
   */
  private def ensureHeadUser(messages: List[Message], originalSize: Int): List[Message] =
    messages.headOption match
      case Some(head) if head.role == MessageRole.User => messages
      case _ =>
        val marker = Message(
          MessageRole.User,
          Left(
            s"[System: Context was cleaned to free space. Reduced from $originalSize to ${messages.size} messages.]"
          )
        )
        marker +: messages

end CompactUtils
