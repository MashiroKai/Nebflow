package nebflow.agent

import munit.FunSuite
import nebflow.shared.*

/**
 * 空白 text 块产生源回归（2026-11-22 修复批）：助手终轮持久化时，空白正文不得落成
 * text 块（否则历史回放即触发 Anthropic 400 `non-whitespace text`）。
 */
class AssistantContentBlankSpec extends FunSuite:

  test("blank text with thinking persists thinking only, never a blank text block") {
    for blank <- List("", " ", "  \n\t") do
      assertEquals(
        AgentFinishTurn.assistantContentOf(Some("plan"), Some("sig"), blank),
        Right(List(ContentBlock.Thinking("plan", Some("sig"))))
      )
  }

  test("blank text without thinking persists no text block") {
    for blank <- List("", "  \n") do assertEquals(AgentFinishTurn.assistantContentOf(None, None, blank), Right(Nil))
  }

  test("real text is kept verbatim alongside thinking") {
    assertEquals(
      AgentFinishTurn.assistantContentOf(Some("plan"), None, "answer"),
      Right(List(ContentBlock.Thinking("plan", None), ContentBlock.Text("answer")))
    )
  }

  test("real text without thinking stays a plain string content") {
    assertEquals(AgentFinishTurn.assistantContentOf(None, None, "answer"), Left("answer"))
  }
end AssistantContentBlankSpec
