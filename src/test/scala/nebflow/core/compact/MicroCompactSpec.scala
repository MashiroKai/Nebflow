package nebflow.core.compact

import munit.CatsEffectSuite
import nebflow.shared.{ContentBlock, Message, MessageRole}
import io.circe.JsonObject

class MicroCompactSpec extends CatsEffectSuite:

  private def textMsg(role: MessageRole, text: String): Message =
    Message(role, Left(text))

  private def blocksMsg(role: MessageRole, blocks: ContentBlock*): Message =
    Message(role, Right(blocks.toList))

  private def toolUseMsg(id: String, name: String): Message =
    blocksMsg(MessageRole.Assistant, ContentBlock.ToolUse(id, name, JsonObject.empty))

  private def toolResultMsg(toolUseId: String, content: String): Message =
    blocksMsg(MessageRole.User, ContentBlock.ToolResult(toolUseId, content))

  // ---------- 顺序性 ----------

  test("rebuild messages in original index order when keep covers scattered indices") {
    val messages = List(
      textMsg(MessageRole.User,     "msg0"),
      textMsg(MessageRole.Assistant, "msg1"),
      textMsg(MessageRole.User,     "msg2"),
      textMsg(MessageRole.Assistant, "msg3"),
      textMsg(MessageRole.User,     "msg4"),
      textMsg(MessageRole.Assistant, "msg5"),
    )
    val llmOut = """<keep>0,2,4</keep><compact start="1" end="1">summary1</compact><compact start="3" end="3">summary2</compact><compact start="5" end="5">summary3</compact>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val compacted = result.toOption.get
    assertEquals(compacted.size, 6)
    assertEquals(extractText(compacted(0)), "msg0")
    assert(extractText(compacted(1)).contains("summary1"))
    assertEquals(extractText(compacted(2)), "msg2")
    assert(extractText(compacted(3)).contains("summary2"))
    assertEquals(extractText(compacted(4)), "msg4")
    assert(extractText(compacted(5)).contains("summary3"))
  }

  test("missing indices are inserted in ascending order, not appended at tail") {
    val messages = List(
      textMsg(MessageRole.User,     "msg0"),
      textMsg(MessageRole.Assistant, "msg1"),
      textMsg(MessageRole.User,     "msg2"),
      textMsg(MessageRole.Assistant, "msg3"),
      textMsg(MessageRole.User,     "msg4"),
      textMsg(MessageRole.Assistant, "msg5"),
    )
    val llmOut = """<keep>2,3</keep>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val compacted = result.toOption.get
    assertEquals(compacted.size, 6)
    assertEquals(extractText(compacted(0)), "msg0")
    assertEquals(extractText(compacted(1)), "msg1")
    assertEquals(extractText(compacted(2)), "msg2")
    assertEquals(extractText(compacted(3)), "msg3")
    assertEquals(extractText(compacted(4)), "msg4")
    assertEquals(extractText(compacted(5)), "msg5")
  }

  test("Keep indices internal disorder is tolerated") {
    val messages = List(
      textMsg(MessageRole.User,     "msg0"),
      textMsg(MessageRole.Assistant, "msg1"),
      textMsg(MessageRole.User,     "msg2"),
    )
    val llmOut = """<keep>2,0,1</keep>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val compacted = result.toOption.get
    assertEquals(compacted.size, 3)
    assertEquals(extractText(compacted(0)), "msg0")
    assertEquals(extractText(compacted(1)), "msg1")
    assertEquals(extractText(compacted(2)), "msg2")
  }

  // ---------- tool_use / tool_result 配对校验 ----------

  test("reject compaction that breaks tool_use/tool_result pairing") {
    val messages = List(
      toolUseMsg("tu-1", "read"),
      toolResultMsg("tu-1", "file content"),
    )
    // Keep the assistant tool_use but compact away the user tool_result.
    // Missing indices are auto-kept, so we must explicitly compact index 1.
    val llmOut = """<keep>0</keep><compact start="1" end="1">summary</compact>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isLeft)
    val err = result.swap.toOption.get
    assert(err.contains("tool_use") || err.contains("pairing"))
  }

  test("accept compaction that keeps tool_use/tool_result together") {
    val messages = List(
      textMsg(MessageRole.User, "before"),
      toolUseMsg("tu-1", "read"),
      toolResultMsg("tu-1", "file content"),
      textMsg(MessageRole.Assistant, "after"),
    )
    val llmOut = """<keep>0</keep><compact start="1" end="2">did tools</compact><keep>3</keep>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight, clues(result))
  }

  // ---------- 正则容错 ----------

  test("tolerate markdown code fence around XML") {
    val messages = List(
      textMsg(MessageRole.User, "a"),
      textMsg(MessageRole.Assistant, "b"),
      textMsg(MessageRole.User, "c"),
    )
    val llmOut = "```xml\n<keep>0,1,2</keep>\n```"
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight, clues(result))
    assertEquals(result.toOption.get.size, 3)
  }

  test("tolerate single quotes in compact attributes") {
    val messages = List(
      textMsg(MessageRole.User, "a"),
      textMsg(MessageRole.Assistant, "b"),
      textMsg(MessageRole.User, "c"),
    )
    val llmOut = """<keep>0</keep><compact start='1' end='2'>summary</compact>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight, clues(result))
    val compacted = result.toOption.get
    assertEquals(compacted.size, 2)
    assert(extractText(compacted(1)).contains("summary"))
  }

  test("tolerate reversed attribute order") {
    val messages = List(
      textMsg(MessageRole.User, "a"),
      textMsg(MessageRole.Assistant, "b"),
    )
    val llmOut = """<compact end='1' start='0'>summary</compact>"""
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isRight, clues(result))
    assertEquals(result.toOption.get.size, 1)
  }

  // ---------- Helpers ----------

  private def extractText(m: Message): String =
    m.content match
      case Left(text) => text
      case Right(blocks) =>
        blocks.collect { case ContentBlock.Text(t) => t }.mkString

  test("empty LLM output returns Left") {
    val messages = List(textMsg(MessageRole.User, "a"))
    val result = MicroCompact.parseResponse("", messages)
    assert(result.isLeft)
  }

  test("LLM output with no valid tags returns Left") {
    val messages = List(textMsg(MessageRole.User, "a"))
    val result = MicroCompact.parseResponse("just some random text", messages)
    assert(result.isLeft)
  }

  test("extra out-of-range indices returns Left") {
    val messages = List(textMsg(MessageRole.User, "a"))
    val llmOut = "<keep>0,5</keep>"
    val result = MicroCompact.parseResponse(llmOut, messages)
    assert(result.isLeft)
    val err = result.swap.toOption.get
    assert(err.contains("out of range"))
  }
