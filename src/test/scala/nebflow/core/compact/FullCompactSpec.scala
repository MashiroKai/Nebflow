package nebflow.core.compact

import munit.CatsEffectSuite
import nebflow.shared.{Message, MessageRole}

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

    val result = FullCompact.parseResponse(llmOut, messages, "/tmp/project")
    assert(result.isRight, clues(result))
    val compacted = result.toOption.get
    assert(compacted.size == 1)
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

  test("parseResponse preserves recent rounds for long conversations") {
    val messages = (1 to 10).flatMap { i =>
      List(
        textMsg(MessageRole.User, s"user message $i"),
        textMsg(MessageRole.Assistant, s"assistant reply $i")
      )
    }.toList
    val llmOut = "<summary>1. Primary Request: Multi-turn conversation.</summary>"

    val result = FullCompact.parseResponse(llmOut, messages, "/tmp/project")
    assert(result.isRight)
    val compacted = result.toOption.get
    assert(compacted.size > 1, clues(compacted.size))
    val summaryContent = compacted.head.content.left.getOrElse("")
    assert(summaryContent.contains("<context-compact"))
    assert(summaryContent.contains("preserved recent messages"))
    val preserved = compacted.tail
    assert(preserved.exists(_.textContent.contains("assistant reply 10")), "Should preserve most recent assistant")
    assert(preserved.exists(_.textContent.contains("user message 10")), "Should preserve most recent user")
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

  test("parseResponse does not preserve rounds for short conversations") {
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
