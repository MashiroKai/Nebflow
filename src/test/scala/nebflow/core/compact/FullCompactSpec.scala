package nebflow.core.compact

import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{ContentBlock, Message, MessageRole}

class FullCompactSpec extends CatsEffectSuite:

  private def textMsg(role: MessageRole, text: String): Message =
    Message(role, Left(text))

  test("parseResponse strips <analysis> and extracts <summary>") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world"),
      textMsg(MessageRole.User, "bye")
    )
    val llmOut =
      """<analysis>
        |Let me think about this conversation...
        |The user said hello and bye.
        |</analysis>
        |
        |<summary>
        |1. Primary Request and Intent:
        |   User greeted and said goodbye.
        |
        |2. Key Technical Concepts:
        |   - None
        |
        |3. Files and Code Sections:
        |   - None
        |
        |4. Errors and Fixes:
        |   - None
        |
        |5. Problem Solving:
        |   Simple greeting exchange.
        |
        |6. All User Messages:
        |   - hello
        |   - bye
        |
        |7. Pending Tasks:
        |   - None
        |
        |8. Current Work:
        |   None
        |
        |9. Optional Next Step:
        |   None
        |</summary>
        |
        |<files>
        |</files>
        |""".stripMargin

    val result = FullCompact.parseResponseDetailed(llmOut, messages, "/tmp/project")
    assert(result.isRight, clues(result))
    val outcome = result.toOption.get
    // 2 rounds in history; preserving both would summarize nothing → only the
    // LAST round (User "bye") is kept: summary + 1 preserved message.
    assert(outcome.preservedRounds == 1)
    val compacted = outcome.messages
    assert(compacted.size == 2)
    val content = compacted.head.content.left.getOrElse("")
    // analysis should NOT be in the output
    assert(!content.contains("<analysis>"), "Analysis block should be stripped")
    assert(!content.contains("Let me think"), "Analysis content should be stripped")
    // summary content should be present
    assert(content.contains("User greeted"), "Summary content should be present")
    assert(content.contains("Primary Request"), "Section header should be present")
  }

  test("parseResponse handles response without <analysis> tag") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world")
    )
    val llmOut =
      """<summary>
        |1. Primary Request: User said hello.
        |</summary>
        |<files>
        |</files>
        |""".stripMargin

    val result = FullCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val content = result.toOption.get.head.content.left.getOrElse("")
    assert(content.contains("User said hello"))
  }

  test("parseResponse handles response without <summary> tag (fallback)") {
    val messages = List(
      textMsg(MessageRole.User, "hello")
    )
    val llmOut = "Simple summary: user said hello."

    val result = FullCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val content = result.toOption.get.head.content.left.getOrElse("")
    assert(content.contains("user said hello"))
  }

  test("parseResponse extracts file paths from <files> tag") {
    val messages = List(
      textMsg(MessageRole.User, "fix the bug")
    )
    val llmOut =
      """<summary>
        |1. Primary Request: Fix the bug in auth.ts
        |</summary>
        |<files>
        |src/auth.ts
        |src/utils.ts
        |</files>
        |""".stripMargin

    // Use a temp dir as project root — files won't exist, but paths should be extracted
    val result = FullCompact.parseResponse(llmOut, messages, "/nonexistent/project")
    assert(result.isRight)
    val content = result.toOption.get.head.content.left.getOrElse("")
    // The <files> block should be stripped from summary
    assert(!content.contains("<files>"))
  }

  test("parseResponse returns Left for empty input") {
    val result = FullCompact.parseResponse("", Nil)
    assert(result.isLeft)
  }

  test("parseResponse preserves the last 2 rounds VERBATIM after the summary (尾部保真)") {
    val messages = (1 to 10).flatMap { i =>
      List(
        textMsg(MessageRole.User, s"user message $i"),
        textMsg(MessageRole.Assistant, s"assistant reply $i")
      )
    }.toList
    val llmOut = "<summary>1. Primary Request: Multi-turn conversation.</summary>"

    val result = FullCompact.parseResponseDetailed(llmOut, messages, "/tmp/project")
    assert(result.isRight)
    val outcome = result.toOption.get
    // Default preservedRounds=2: rounds 9 and 10 (4 messages) kept verbatim.
    assert(outcome.preservedRounds == 2)
    assertEquals(outcome.messages.size, 5) // summary + 4 preserved
    val summaryContent = outcome.messages.head.content.left.getOrElse("")
    assert(summaryContent.contains("<context-compact"))
    assert(summaryContent.contains("preservedRounds=2"))
    // Preserved tail is verbatim, in order, and the LAST user instruction is intact.
    val tail = outcome.messages.tail
    assertEquals(
      tail.map(_.role),
      List(MessageRole.User, MessageRole.Assistant, MessageRole.User, MessageRole.Assistant)
    )
    assertEquals(
      tail.map(_.content.left.getOrElse("")),
      List("user message 9", "assistant reply 9", "user message 10", "assistant reply 10")
    )
    // Readback helper agrees with the label (archive path relies on it).
    assertEquals(FullCompact.preservedRoundsOf(outcome.messages), 2)
  }

  test("preserved tail keeps the last user instruction + its tool chain intact (real-form messages)") {
    // Realistic worker history: two rounds, each with assistant tool_use and
    // user tool_result; the compaction reminder trails the history (as
    // startDirectCompaction appends it) and must be EXCLUDED from the tail.
    val bigResult = "x" * 20000 // oversized → per-result cap must truncate
    val messages = List(
      textMsg(MessageRole.User, "任务A：修复登录 bug"),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.Text("先看日志"), ContentBlock.ToolUse("t1", "Bash", JsonObject("cmd" -> "ls".asJson))))
      ),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("t1", "output-A")))),
      textMsg(MessageRole.Assistant, "分析完成"),
      textMsg(MessageRole.User, "任务B：还要加上日志（最后下达的任务）"),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("t2", "Read", JsonObject("path" -> "/tmp/f".asJson))))
      ),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("t2", bigResult)))),
      textMsg(MessageRole.Assistant, "继续处理"),
      CompactService.buildCompactReminder(depth = 1, isLead = false) // trailing reminder
    )
    val llmOut = "<summary>1. Current Task: 任务B（摘要转述）.</summary>"

    val result = FullCompact.parseResponseDetailed(llmOut, messages)
    assert(result.isRight)
    val outcome = result.toOption.get
    // Two rounds total: preserving BOTH would summarize nothing (guardrail 1),
    // so exactly the LAST round is kept verbatim — the active task survives.
    assertEquals(outcome.preservedRounds, 1)

    val tail = outcome.messages.tail
    // Round-2 head is the LAST user instruction, VERBATIM, as a real message.
    assertEquals(tail.head.content.left.getOrElse(""), "任务B：还要加上日志（最后下达的任务）")
    // tool_use → tool_result pairing preserved inside the tail.
    val tailToolUse = tail(1).content.toOption.get.collectFirst { case tu: ContentBlock.ToolUse => tu }
    assert(tailToolUse.exists(_.id == "t2"), "assistant tool_use t2 must survive in the tail")
    val tailResult = tail(2).content.toOption.get.collectFirst { case tr: ContentBlock.ToolResult => tr }
    assert(tailResult.exists(_.toolUseId == "t2"), "tool_result t2 must survive, paired")
    // Oversized result capped with a truncation marker (NOT the raw 20k body).
    assert(tailResult.get.content.contains("[preserved tail: truncated"))
    assert(tailResult.get.content.length < 21000)
    // The compaction reminder must not leak into the preserved tail.
    assert(!tail.exists(m => CompactService.isCompactReminder(m)))
    assert(
      !outcome.messages.exists(_.content.left.getOrElse("").contains("Context compaction required")),
      "reminder text must be fully excluded"
    )
    // Summary label is truthful.
    assertEquals(FullCompact.preservedRoundsOf(outcome.messages), 1)
  }

  test("single-round history preserves nothing — compaction must shrink (death-loop guard)") {
    val messages = List(
      textMsg(MessageRole.User, "唯一任务"),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("t1", "Read", JsonObject.empty)))
      ),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("t1", "out"))))
    )
    val llmOut = "<summary>Brief summary.</summary>"
    val result = FullCompact.parseResponseDetailed(llmOut, messages)
    assert(result.isRight)
    val outcome = result.toOption.get
    // Only one round exists; preserving it would keep 100% of the history and
    // the next auto-compaction would loop forever. Guardrail: keep nothing.
    assertEquals(outcome.preservedRounds, 0)
    assertEquals(outcome.messages.size, 1)
    assert(outcome.messages.head.content.left.getOrElse("").contains("preservedRounds=0"))
  }

  test("last round alone over the char budget ⇒ preserve none (budget guardrail)") {
    val messages = List(
      textMsg(MessageRole.User, "第一轮任务"),
      textMsg(MessageRole.Assistant, "ack"),
      textMsg(MessageRole.User, "x" * 70000), // one round, over preservedRoundsMaxChars
      textMsg(MessageRole.Assistant, "done")
    )
    val llmOut = "<summary>Brief summary.</summary>"
    val result = FullCompact.parseResponseDetailed(llmOut, messages)
    assert(result.isRight)
    val outcome = result.toOption.get
    assertEquals(outcome.preservedRounds, 0)
    assertEquals(outcome.messages.size, 1)
  }

  test("parseResponse includes continuation prompt in summary") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world"),
      textMsg(MessageRole.User, "bye")
    )
    val llmOut = "<summary>Brief summary.</summary>"
    val result = FullCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val content = result.toOption.get.head.content.left.getOrElse("")
    assert(content.contains("Continue the conversation"))
    assert(content.contains("do not acknowledge the summary"))
  }

  test("parseResponse returns single summary message for short conversations") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world")
    )
    val llmOut = "<summary>Brief summary.</summary>"
    val result = FullCompact.parseResponse(llmOut, messages)
    assert(result.isRight)
    val compacted = result.toOption.get
    assert(clue(compacted.size) == 1)
  }

  test("parseResponse accepts recentReadPaths for file restoration") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world"),
      textMsg(MessageRole.User, "bye")
    )
    val llmOut = "<summary>Brief summary.</summary>"
    val result = FullCompact.parseResponse(llmOut, messages, "/tmp/nonexistent", List("/tmp/nonexistent/file.txt"))
    assert(result.isRight)
    val content = result.toOption.get.head.content.left.getOrElse("")
    assert(!content.contains("Restored file contents"))
  }
end FullCompactSpec
