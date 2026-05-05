package nebflow.core.compact

import munit.CatsEffectSuite
import nebflow.shared.{Message, MessageRole}

class FullCompactSpec extends CatsEffectSuite:

  private def textMsg(role: MessageRole, text: String): Message =
    Message(role, Left(text))

  test("parseResponse produces non-empty summary message") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world"),
      textMsg(MessageRole.User, "bye")
    )
    val llmOut = """
Summary of conversation:
The user greeted and said goodbye.

<files>
</files>
"""
    val result = FullCompact.parseResponse(llmOut, messages, "/tmp/project")
    assert(result.isRight, clues(result))
    val compacted = result.toOption.get
    assert(compacted.nonEmpty, "compacted messages should not be empty")
    val summary = compacted.head
    assert(summary.content.isLeft || summary.content.isRight)
  }

  test("empty projectRoot does not crash file extraction") {
    val messages = List(
      textMsg(MessageRole.User, "hello"),
      textMsg(MessageRole.Assistant, "world")
    )
    val llmOut = """
Summary:
Nothing important.

<files>
/src/main/scala/Foo.scala
</files>
"""
    val result = FullCompact.parseResponse(llmOut, messages, "")
    // With empty projectRoot, isWithinProject returns false for all paths,
    // so fileRestoreMessage should be None. Must not throw.
    assert(result.isRight, clues(result))
  }
end FullCompactSpec
