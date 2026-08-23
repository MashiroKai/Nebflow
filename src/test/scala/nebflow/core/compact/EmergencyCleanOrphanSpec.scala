package nebflow.core.compact

import munit.FunSuite
import nebflow.shared.*

/**
 * EmergencyClean phase-3 orphan guard (2026-08-23 deepseek 422): a bare
 * takeRight(keepAtEnd) tail cut can leave a user(tool_result) message at the
 * head whose assistant(tool_use) was truncated away. Anthropic protocol
 * rejects orphaned tool_results — the head guard must drop them (ensureHeadUser
 * alone misses this: tool_result messages ARE User role).
 */
class EmergencyCleanOrphanSpec extends FunSuite:

  private def userText(t: String) = Message(MessageRole.User, Left(t))

  private def assistantText(t: String) = Message(MessageRole.Assistant, Left(t))

  private def toolResultMsg(id: String) =
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult(id, "some output", Some(false)))))

  private def assistantToolUseMsg(id: String) =
    Message(
      MessageRole.Assistant,
      Right(List(ContentBlock.Thinking("let me", None), ContentBlock.ToolUse(id, "Read", io.circe.JsonObject.empty)))
    )

  test("phase-3 truncation with orphaned head tool_result drops the dangling head message") {
    // Build a history where the tail cut lands exactly between an
    // assistant(tool_use) and its user(tool_result): the result becomes head
    // with no preceding tool_use in the kept tail.
    val msgs: List[Message] =
      List(userText("old task"), assistantToolUseMsg("call_kept_1")) ++
        List(toolResultMsg("call_kept_1"), userText("more"), assistantText("done"))
    // keepAtEnd=3 → phase3 keeps [toolResultMsg("call_kept_1"), userText("more"), assistantText("done")]
    // — the head is an orphaned tool_result (its tool_use call_kept_1 was cut).
    val (cleaned, desc) = CompactUtils.emergencyClean(msgs, keepAtEnd = 3)
    assert(
      !cleaned.headOption.exists(_.content.toOption.toList.flatten.exists(_.isInstanceOf[ContentBlock.ToolResult])),
      s"head must not be an orphaned tool_result: $cleaned"
    )
    assert(
      cleaned.headOption.exists(_.role == MessageRole.User),
      s"head must still be a user message after orphan drop: $cleaned"
    )
    assert(clue(desc).nonEmpty, "description must explain what happened")
  }

  test("no orphan at head: emergencyClean leaves a normal tail untouched") {
    val msgs: List[Message] =
      List(userText("task"), assistantToolUseMsg("call_a")) ++
        List(toolResultMsg("call_a"), userText("next"), assistantText("ok"))
    // keepAtEnd=4 keeps everything below size-4; here the tail ends with the
    // assistant "ok" — no orphan at head. (Phase-1 may replace result content
    // with a placeholder, but the STRUCTURE — head is user text, tool_result
    // still paired after its tool_use — must be preserved.)
    val (cleaned, _) = CompactUtils.emergencyClean(msgs, keepAtEnd = 4)
    assertEquals(clue(cleaned.size), msgs.size, "no truncation below keepAtEnd")
    assert(
      !cleaned.headOption.exists(_.content.toOption.toList.flatten.exists(_.isInstanceOf[ContentBlock.ToolResult])),
      s"head must not be a tool_result: $cleaned"
    )
    assert(
      cleaned.headOption.exists(_.role == MessageRole.User),
      s"head must be a user message: $cleaned"
    )
  }

  test("mixed text+tool_result head message is kept (only pure-orphan heads drop)") {
    val msgs: List[Message] =
      List(userText("old"), assistantText("interim")) ++
        List(
          Message(
            MessageRole.User,
            Right(List(ContentBlock.Text("context text"), ContentBlock.ToolResult("call_d", "out", Some(false))))
          )
        )
    // keepAtEnd=1 → tail is the mixed message; it has text content so it stays
    val (cleaned, _) = CompactUtils.emergencyClean(msgs, keepAtEnd = 1)
    val headBlocks = cleaned.headOption.flatMap(_.content.toOption)
    assert(headBlocks.exists(_.exists(_.isInstanceOf[ContentBlock.Text])), s"mixed message must survive: $cleaned")
  }

end EmergencyCleanOrphanSpec
