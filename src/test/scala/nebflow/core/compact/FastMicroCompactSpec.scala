package nebflow.core.compact

import munit.CatsEffectSuite
import nebflow.shared.*

class FastMicroCompactSpec extends CatsEffectSuite:

  private def textMsg(role: MessageRole, text: String, ts: Long = System.currentTimeMillis()): Message =
    Message(role, Left(text), ts)

  private def assistantToolUse(toolUseId: String, toolName: String, ts: Long = System.currentTimeMillis()): Message =
    Message(
      MessageRole.Assistant,
      Right(List(ContentBlock.ToolUse(toolUseId, toolName, io.circe.JsonObject.empty))),
      ts
    )

  private def userToolResult(toolUseId: String, content: String, ts: Long = System.currentTimeMillis()): Message =
    Message(
      MessageRole.User,
      Right(List(ContentBlock.ToolResult(toolUseId, content))),
      ts
    )

  // Hardcoded TTL is 2h; use 3h-old timestamps for "cold" cache.
  private val coldTs = System.currentTimeMillis() - 3 * 60 * 60 * 1000L

  test("skips when cache is hot (recent assistant message)") {
    val now = System.currentTimeMillis()
    val messages = List(
      textMsg(MessageRole.User, "hello", now - 5000),
      assistantToolUse("tu1", "Read", now - 4000),
      userToolResult("tu1", "file content here" * 100, now - 3000),
      textMsg(MessageRole.Assistant, "I read the file", now - 1000)
    )
    val result = FastMicroCompact(messages)
    assertEquals(result, None)
  }

  test("fires when cache is cold (old assistant message, > 2h)") {
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "very long content " * 100, coldTs + 2000),
      assistantToolUse("tu2", "Grep", coldTs + 3000),
      userToolResult("tu2", "grep results " * 50, coldTs + 4000),
      assistantToolUse("tu3", "Read", coldTs + 5000),
      userToolResult("tu3", "old content 3", coldTs + 6000),
      assistantToolUse("tu4", "Read", coldTs + 7000),
      userToolResult("tu4", "old content 4", coldTs + 8000),
      assistantToolUse("tu5", "Read", coldTs + 9000),
      userToolResult("tu5", "old content 5", coldTs + 10000),
      assistantToolUse("tu6", "Read", coldTs + 11000),
      userToolResult("tu6", "old content 6", coldTs + 12000),
      assistantToolUse("tu7", "Read", coldTs + 13000),
      userToolResult("tu7", "recent content", coldTs + 14000),
      textMsg(MessageRole.Assistant, "done", coldTs + 15000)
    )
    // 7 tool uses, keepRecent=5 → oldest 2 (tu1, tu2) replaced, tu3-tu7 kept
    val result = FastMicroCompact(messages)
    assert(result.isDefined, "Should fire when cache is cold")
    val toolResults = result.get.flatMap {
      case Message(_, Right(blocks), _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil
    }
    assertEquals(toolResults.find(_.toolUseId == "tu1").get.content, "[Output removed to free context space]")
    assertEquals(toolResults.find(_.toolUseId == "tu2").get.content, "[Output removed to free context space]")
    assertEquals(toolResults.find(_.toolUseId == "tu7").get.content, "recent content")
  }

  test("fires for messages with timestamp == 0 (legacy)") {
    // With 7 tool uses and keepRecent=5, oldest 2 get compacted.
    val toolPairs = (1 to 7).toList.flatMap { i =>
      List(
        Message(
          MessageRole.Assistant,
          Right(List(ContentBlock.ToolUse(s"tu$i", "Read", io.circe.JsonObject.empty))),
          0L
        ),
        Message(
          MessageRole.User,
          Right(List(ContentBlock.ToolResult(s"tu$i", s"content $i " * 50))),
          0L
        )
      )
    }
    val messages = toolPairs :+ Message(MessageRole.Assistant, Left("done"), 0L)
    val result = FastMicroCompact(messages)
    assert(result.isDefined, "Should fire for timestamp=0 (legacy) messages")
  }

  test("skips when fewer compactable tools than keepRecent (5)") {
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "content", coldTs + 2000),
      textMsg(MessageRole.Assistant, "done", coldTs + 3000)
    )
    val result = FastMicroCompact(messages)
    assertEquals(result, None, "Only 1 tool result, keepRecent=5 → nothing to compact")
  }

  test("only compacts CompactableTools, not others") {
    // 6 compactable + 1 non-compactable = 7 tool uses; keepRecent=5 keeps last 5.
    // The 2 oldest compactable (tu1, tu2) get replaced; Delegate (tu7) is preserved.
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "read content 1 " * 100, coldTs + 2000),
      assistantToolUse("tu2", "Read", coldTs + 3000),
      userToolResult("tu2", "read content 2 " * 100, coldTs + 4000),
      assistantToolUse("tu3", "Read", coldTs + 5000),
      userToolResult("tu3", "read content 3", coldTs + 6000),
      assistantToolUse("tu4", "Read", coldTs + 7000),
      userToolResult("tu4", "read content 4", coldTs + 8000),
      assistantToolUse("tu5", "Read", coldTs + 9000),
      userToolResult("tu5", "read content 5", coldTs + 10000),
      assistantToolUse("tu6", "Read", coldTs + 11000),
      userToolResult("tu6", "read content 6", coldTs + 12000),
      assistantToolUse("tu7", "Delegate", coldTs + 13000),
      userToolResult("tu7", "delegate result " * 100, coldTs + 14000),
      textMsg(MessageRole.Assistant, "done", coldTs + 15000)
    )
    val result = FastMicroCompact(messages)
    assert(result.isDefined)
    val toolResults = result.get.flatMap {
      case Message(_, Right(blocks), _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil
    }
    // Read tu1, tu2 are compactable and old → replaced
    assertEquals(toolResults.find(_.toolUseId == "tu1").get.content, "[Output removed to free context space]")
    // Delegate is NOT compactable → preserved even if old
    assertEquals(toolResults.find(_.toolUseId == "tu7").get.content.startsWith("delegate result"), true)
  }

  test("does not re-replace already-placeholdered results") {
    // 6 tool uses, all already placeholdered → nothing changes → None
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs)
    ) ++ (1 to 6).toList.flatMap { i =>
      List(
        assistantToolUse(s"tu$i", "Read", coldTs + i * 1000),
        userToolResult(s"tu$i", "[Output removed to free context space]", coldTs + i * 1000 + 500)
      )
    } :+ textMsg(MessageRole.Assistant, "done", coldTs + 7000)
    val result = FastMicroCompact(messages)
    assertEquals(result, None)
  }
end FastMicroCompactSpec
