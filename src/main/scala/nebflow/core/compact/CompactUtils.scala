package nebflow.core.compact

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.*

object CompactUtils:

  private val logger = NebflowLogger.forName("nebflow.compact")

  // Placeholder for stripped tool results
  private val ToolResultPlaceholder = "[Output removed to free context space]"

  /** Persisted-output marker — mirrors ToolResultGuard's tag so previews that
    * were already persisted by the write-path guard are recognized as such
    * (they are small and must NOT be re-persisted or re-stripped). */
  private val PersistedTag = "<persisted-output>"

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
      (ensureHeadUser(phase1, messages.size), desc)
    else
      // Phase 2: Remove oldest tool-result message pairs (user messages containing ToolResult blocks)
      val phase2 = removeOldToolResultMessages(phase1, keepAtEnd)
      val p2Removed = phase1.size - phase2.size
      if p2Removed > 0 then
        logger.info(
          s"Emergency clean phase 2: removed $p2Removed oldest tool-result messages, ${phase2.size} remaining"
        )
      end if

      if phase2.size <= keepAtEnd * 2 then
        val desc = s"Stripped tool results + removed $p2Removed oldest tool-result messages, ${phase2.size} remaining"
        (ensureHeadUser(phase2, messages.size), desc)
      else
        // Phase 3: Keep only last N messages
        val phase3 = phase2.takeRight(keepAtEnd)
        logger.info(s"Emergency clean phase 3: truncated to last $keepAtEnd messages")
        val desc = s"Stripped tool results + removed old messages, kept last $keepAtEnd of ${messages.size}"
        // Orphan guard (2026-08-23 deepseek 422): a bare takeRight can cut
        // between an assistant(tool_use) and its user(tool_result), leaving
        // the result as the HEAD message with no preceding tool_use — Anthropic
        // protocol rejects it ("Each tool_result block must have a
        // corresponding tool_use block"). ensureHeadUser alone misses this
        // because tool_result messages ARE User role. Drop orphaned head
        // results (their tool_use was truncated away, the result is dangling).
        (ensureHeadUser(dropOrphanHeadToolResults(phase3), messages.size), desc)
      end if
    end if
  end emergencyClean

  /** Replace all ToolResult block content with a short placeholder. */
  private def stripToolResults(messages: List[Message]): List[Message] =
    messages.map {
      case msg @ Message(_, Right(blocks), _, _) =>
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

  /**
   * Drop head user messages that contain ONLY orphaned tool_result blocks
   * (their assistant(tool_use) was truncated away by the phase-3 tail cut).
   * Keeps dropping while the head is such a message. A user message that
   * mixes text with tool results is KEPT as-is (it has content beyond the
   * dangling result). Returns the cleaned list.
   */
  private def dropOrphanHeadToolResults(messages: List[Message]): List[Message] =
    def headIsOrphanResult(msgs: List[Message]): Boolean =
      msgs.headOption match
        case Some(Message(MessageRole.User, Right(blocks), _, _)) =>
          blocks.nonEmpty && blocks.forall(_.isInstanceOf[ContentBlock.ToolResult])
        case _ => false
    def go(msgs: List[Message]): List[Message] =
      if headIsOrphanResult(msgs) then go(msgs.tail) else msgs
    go(messages)

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

  // ============================================================
  // #38 Layer B — compact 轮输入的大结果精准剔除（2026-09-01）
  // 单级压缩的 compact turn 之前跳过 FastMicroCompact/ToolResultTtl
  // （"需要全量历史"）→ 大 ToolResult 在压缩路径上零剔除 → 历史 >1M
  // 时 provider 拒绝 compact 请求 → 失败冷却刷新 → 永久死锁。
  // 修复：喂给压缩轮的输入先剔除 >maxChars 的 ToolResult（占位符+落盘
  // 路径），压缩轮只需全文概貌 + 路径引用，不需要大结果本体。
  // 小结果（≤maxChars）与结构原样保留；已落盘预览（<persisted-output>）
  // 本身体积小，不重复处理。产物形态（summary 替换历史）不动。
  // ============================================================

  /** 扫描 messages 中 >maxChars 且未落盘的 ToolResult 全文（写路径守卫
    * 上线前的存量，或任何漏网）。返回 (toolUseId, content) 列表。 */
  private def collectOversized(
    messages: List[Message],
    maxChars: Int
  ): List[(String, String)] =
    messages
      .flatMap(_.content.toOption.toList.flatten)
      .collect {
        case ContentBlock.ToolResult(toolUseId, content, _)
            if content.length > maxChars && !content.startsWith(PersistedTag) =>
          (toolUseId, content)
      }

  /** 确保所有 >maxChars 且未落盘的 ToolResult 全文已写盘。落盘目录与
    * ToolResultGuard 一致：~/.nebflow/tool-results/{sessionId}/{toolUseId}.txt。
    * 写路径已拦住的（<persisted-output> 预览）跳过。 */
  def persistOversizedToolResults(
    messages: List[Message],
    sessionId: String,
    maxChars: Int = Defaults.DefaultMaxResultSizeChars
  ): IO[Unit] =
    collectOversized(messages, maxChars).traverse_ { case (toolUseId, content) =>
      IO.blocking {
        val dir = PathUtil.dataRoot / "tool-results" / sessionId
        os.makeDir.all(dir)
        val path = dir / s"$toolUseId.txt"
        if !os.exists(path) then os.write.over(path, content)
        logger.info(
          s"Compaction pre-pass: persisted oversized ToolResult $toolUseId (${content.length} chars) to $path"
        )
      }
    }

  /** 纯函数：把 >maxChars 且未落盘的 ToolResult content 替换为占位符+落盘
    * 路径提示。小结果、已落盘预览、非 ToolResult 块全部原样保留。 */
  def stripOversizedToolResults(
    messages: List[Message],
    sessionId: String,
    maxChars: Int = Defaults.DefaultMaxResultSizeChars
  ): List[Message] =
    messages.map {
      case msg @ Message(_, Right(blocks), _, _) =>
        val stripped = blocks.map {
          case tr @ ContentBlock.ToolResult(toolUseId, content, _)
              if content.length > maxChars && !content.startsWith(PersistedTag) =>
            val path = s"${PathUtil.dataRoot / "tool-results" / sessionId / s"$toolUseId.txt"}"
            tr.copy(
              content =
                s"""$PersistedTag
                   |Output too large (${content.length} chars). Full output saved to: $path
                   |</persisted-output>""".stripMargin
            )
          case other => other
        }
        msg.copy(content = Right(stripped))
      case other => other
    }

  /** 压缩轮输入预处理：先补落盘（IO），再纯函数剔除。供 AgentCore 调用。 */
  def prepareCompactionInput(
    messages: List[Message],
    sessionId: String,
    maxChars: Int = Defaults.DefaultMaxResultSizeChars
  ): IO[List[Message]] =
    persistOversizedToolResults(messages, sessionId, maxChars)
      .map(_ => stripOversizedToolResults(messages, sessionId, maxChars))

  /**
   * Read a file's content for post-compact restoration, truncated to maxChars.
   * Returns empty string if the file doesn't exist or can't be read.
   */
  def restoreFileContent(path: String, maxChars: Int): String =
    try
      val file = java.nio.file.Paths.get(path.replaceFirst("^~", sys.props("user.home")))
      if !java.nio.file.Files.exists(file) || !java.nio.file.Files.isRegularFile(file) then ""
      else
        val content = new String(java.nio.file.Files.readAllBytes(file), "UTF-8")
        if content.length <= maxChars then content
        else content.take(maxChars) + s"\n... [truncated, ${content.length - maxChars} more chars]"
    catch case _: Exception => ""

end CompactUtils
