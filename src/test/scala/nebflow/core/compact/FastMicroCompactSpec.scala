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

  // Default cold-after threshold is 30min; use 40min-old timestamps for "cold".
  private val coldTs = System.currentTimeMillis() - 40 * 60 * 1000L

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

  test("fires when cache is cold (old assistant message, > 30min)") {
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
    // 7 tool uses, keepRecent=5 → oldest 2 (tu1, tu2) replaced, tu3-tu7 kept.
    // Savings (tu1+tu2 ~3.7K of ~5K chars) clears the 60% minimum-savings guard.
    val result = FastMicroCompact(messages)
    assert(result.isDefined, "Should fire when cache is cold")
    val toolResults = result.get.flatMap {
      case Message(_, Right(blocks), _, _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil
    }
    assertEquals(toolResults.find(_.toolUseId == "tu1").get.content, "[Output removed to free context space]")
    assertEquals(toolResults.find(_.toolUseId == "tu2").get.content, "[Output removed to free context space]")
    assertEquals(toolResults.find(_.toolUseId == "tu7").get.content, "recent content")
  }

  test("fires for messages with timestamp == 0 (legacy)") {
    // With 7 tool uses and keepRecent=5, oldest 2 get compacted.
    val toolPairs = (1 to 7).toList.flatMap { i =>
      // Oldest results (tu1/tu2) carry heavy content so the shrink clears the
      // minimum-savings guard (replacing 2 of 7 equal-size results would only
      // save ~29%, below the 40% threshold).
      val content = if i <= 2 then s"content $i " * 500 else s"content $i " * 50
      List(
        Message(
          MessageRole.Assistant,
          Right(List(ContentBlock.ToolUse(s"tu$i", "Read", io.circe.JsonObject.empty))),
          0L
        ),
        Message(
          MessageRole.User,
          Right(List(ContentBlock.ToolResult(s"tu$i", content))),
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
    // 10 compactable (Read) + 1 non-compactable (Delegate) = 11 tool uses;
    // keepRecent=5 keeps the last 5 Reads, clears the 5 oldest. Oldest results
    // carry heavy content so the shrink clears the minimum-savings guard.
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs)
    ) ++ (1 to 10).toList.flatMap { i =>
      val content = if i <= 5 then s"read content $i " * 100 else s"recent content $i " * 20
      List(
        assistantToolUse(s"tu$i", "Read", coldTs + i * 1000),
        userToolResult(s"tu$i", content, coldTs + i * 1000 + 500)
      )
    } ++ List(
      assistantToolUse("tu11", "Delegate", coldTs + 11000),
      userToolResult("tu11", "delegate result " * 100, coldTs + 11500),
      textMsg(MessageRole.Assistant, "done", coldTs + 12000)
    )
    val result = FastMicroCompact(messages)
    assert(result.isDefined)
    val toolResults = result.get.flatMap {
      case Message(_, Right(blocks), _, _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil
    }
    // Read tu1 (oldest, compactable) → replaced
    assertEquals(toolResults.find(_.toolUseId == "tu1").get.content, "[Output removed to free context space]")
    // Read tu10 (recent, within keepRecent) → preserved
    assert(toolResults.find(_.toolUseId == "tu10").get.content.startsWith("recent content 10"))
    // Delegate is NOT compactable → preserved even if old
    assertEquals(toolResults.find(_.toolUseId == "tu11").get.content.startsWith("delegate result"), true)
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

  test("skips within the cold-after threshold (boundary)") {
    val coldAfterMs = FastMicroCompact.DefaultColdAfterMs
    // Last assistant within threshold (hot) → skip
    val hotTs = System.currentTimeMillis() - (coldAfterMs - 1000)
    val hotMessages = List(
      textMsg(MessageRole.User, "hello", hotTs - 5000),
      assistantToolUse("tu1", "Read", hotTs - 4000),
      userToolResult("tu1", "big content " * 200, hotTs - 3000),
      textMsg(MessageRole.Assistant, "done", hotTs)
    )
    assertEquals(FastMicroCompact(hotMessages), None, "Within threshold → cache hot → skip")

    // Last assistant just past threshold (cold) → fire. 6 tool uses so
    // keepRecent=5 clears the oldest (tu1, heavy content) and the shrink
    // clears the savings guard (~50%).
    val coldTs2 = System.currentTimeMillis() - (coldAfterMs + 1000)
    val coldMessages = List(
      textMsg(MessageRole.User, "hello", coldTs2 - 10000)
    ) ++ (1 to 6).toList.flatMap { i =>
      val content = if i == 1 then "big content " * 200 else s"content $i " * 50
      List(
        assistantToolUse(s"tu$i", "Read", coldTs2 - 9000 + i * 1000),
        userToolResult(s"tu$i", content, coldTs2 - 8000 + i * 1000)
      )
    } :+ textMsg(MessageRole.Assistant, "done", coldTs2)
    assert(FastMicroCompact(coldMessages).isDefined, "Past threshold → cold → fire")
  }

  test("respects custom coldAfterMs") {
    // Assistant is 20min old: hot for default 30min TTL, cold for 10min TTL.
    val twentyMinAgo = System.currentTimeMillis() - 20 * 60 * 1000L
    val messages = List(
      textMsg(MessageRole.User, "hello", twentyMinAgo - 10000)
    ) ++ (1 to 6).toList.flatMap { i =>
      val content = if i == 1 then "big content " * 300 else s"content $i " * 50
      List(
        assistantToolUse(s"tu$i", "Read", twentyMinAgo - 9000 + i * 1000),
        userToolResult(s"tu$i", content, twentyMinAgo - 8000 + i * 1000)
      )
    } :+ textMsg(MessageRole.Assistant, "done", twentyMinAgo)
    assertEquals(
      FastMicroCompact(messages),
      None,
      "20min idle < default 30min threshold → skip"
    )
    assert(
      FastMicroCompact(messages, coldAfterMs = 10 * 60 * 1000L).isDefined,
      "20min idle > custom 10min threshold → fire"
    )
  }

  test("minimum-savings guard: skips when savings below threshold") {
    // Huge text message dominates; old tool results are tiny → replacing them
    // saves <40% → skip (not worth destroying old results).
    val bigText = "analysis text " * 2000 // ~28K chars
    val messages = List(
      textMsg(MessageRole.User, "hello", coldTs),
      textMsg(MessageRole.User, bigText, coldTs + 500),
      assistantToolUse("tu1", "Read", coldTs + 1000),
      userToolResult("tu1", "tiny", coldTs + 2000),
      assistantToolUse("tu2", "Read", coldTs + 3000),
      userToolResult("tu2", "tiny", coldTs + 4000),
      assistantToolUse("tu3", "Read", coldTs + 5000),
      userToolResult("tu3", "tiny", coldTs + 6000),
      assistantToolUse("tu4", "Read", coldTs + 7000),
      userToolResult("tu4", "tiny", coldTs + 8000),
      assistantToolUse("tu5", "Read", coldTs + 9000),
      userToolResult("tu5", "tiny", coldTs + 10000),
      assistantToolUse("tu6", "Read", coldTs + 11000),
      userToolResult("tu6", "tiny", coldTs + 12000),
      assistantToolUse("tu7", "Read", coldTs + 13000),
      userToolResult("tu7", "tiny", coldTs + 14000),
      textMsg(MessageRole.Assistant, "done", coldTs + 15000)
    )
    val result = FastMicroCompact(messages)
    assertEquals(result, None, "Savings ~0.02% << 40% threshold → skip")
  }
end FastMicroCompactSpec
