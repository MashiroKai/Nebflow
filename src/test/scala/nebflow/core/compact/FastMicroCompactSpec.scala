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

  test("skips when cache is hot (recent assistant message)") {
    val now = System.currentTimeMillis()
    val messages = List(
      textMsg(MessageRole.User, "hello", now - 5000),
      assistantToolUse("tu1", "Read", now - 4000),
      userToolResult("tu1", "file content here" * 100, now - 3000),
      textMsg(MessageRole.Assistant, "I read the file", now - 1000)
    )
    val result = FastMicroCompact(messages, cacheTtlMinutes = 10)
    assertEquals(result, None)
  }

  test("fires when cache is cold (old assistant message)") {
    val coldTs = System.currentTimeMillis() - 20 * 60 * 1000L // 20 minutes ago
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "very long content " * 100, coldTs + 2000),
      assistantToolUse("tu2", "Grep", coldTs + 3000),
      userToolResult("tu2", "grep results " * 50, coldTs + 4000),
      assistantToolUse("tu3", "Read", coldTs + 5000),
      userToolResult("tu3", "recent content", coldTs + 6000),
      textMsg(MessageRole.Assistant, "done", coldTs + 7000)
    )
    val result = FastMicroCompact(messages, cacheTtlMinutes = 10, keepRecent = 1)
    assert(result.isDefined, "Should fire when cache is cold")
    val compacted = result.get
    // tu1 and tu2 should be replaced, tu3 kept (most recent)
    val toolResults = compacted.flatMap {
      case Message(_, Right(blocks), _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil
    }
    assertEquals(toolResults.find(_.toolUseId == "tu1").get.content, "[Output removed to free context space]")
    assertEquals(toolResults.find(_.toolUseId == "tu2").get.content, "[Output removed to free context space]")
    assertEquals(toolResults.find(_.toolUseId == "tu3").get.content, "recent content")
  }

  test("fires for messages with timestamp == 0 (legacy)") {
    val messages = List(
      Message(MessageRole.User, Left("hello"), 0L),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("tu1", "Read", io.circe.JsonObject.empty))),
        0L
      ),
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("tu1", "content " * 100))),
        0L
      ),
      Message(MessageRole.Assistant, Left("done"), 0L)
    )
    val result = FastMicroCompact(messages, cacheTtlMinutes = 10, keepRecent = 0)
    // With keepRecent=0, all tool results should be replaced
    // But keepRecent is effectively clamped — need at least 0 keepRecent means all can be cleared
    assert(result.isDefined, "Should fire for timestamp=0 (legacy) messages")
  }

  test("skips when fewer compactable tools than keepRecent") {
    val coldTs = System.currentTimeMillis() - 20 * 60 * 1000L
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "content", coldTs + 2000),
      textMsg(MessageRole.Assistant, "done", coldTs + 3000)
    )
    val result = FastMicroCompact(messages, cacheTtlMinutes = 10, keepRecent = 5)
    assertEquals(result, None, "Only 1 tool result, keepRecent=5 → nothing to compact")
  }

  test("only compacts CompactableTools, not others") {
    val coldTs = System.currentTimeMillis() - 20 * 60 * 1000L
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "read content " * 100, coldTs + 2000),
      assistantToolUse("tu2", "Delegate", coldTs + 3000),
      userToolResult("tu2", "delegate result " * 100, coldTs + 4000),
      textMsg(MessageRole.Assistant, "done", coldTs + 5000)
    )
    val result = FastMicroCompact(messages, cacheTtlMinutes = 10, keepRecent = 0)
    assert(result.isDefined)
    val toolResults = result.get.flatMap {
      case Message(_, Right(blocks), _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil
    }
    // Read is compactable, should be replaced
    assertEquals(toolResults.find(_.toolUseId == "tu1").get.content, "[Output removed to free context space]")
    // Delegate is NOT compactable, should be preserved
    assertEquals(toolResults.find(_.toolUseId == "tu2").get.content.startsWith("delegate result"), true)
  }

  test("does not re-replace already-placeholdered results") {
    val coldTs = System.currentTimeMillis() - 20 * 60 * 1000L
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "[Output removed to free context space]", coldTs + 2000),
      textMsg(MessageRole.Assistant, "done", coldTs + 3000)
    )
    val result = FastMicroCompact(messages, cacheTtlMinutes = 10, keepRecent = 0)
    // Content is already the placeholder, so nothing actually changes → None
    assertEquals(result, None)
  }
end FastMicroCompactSpec
