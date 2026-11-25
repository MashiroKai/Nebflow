package nebflow.core.ask

import munit.FunSuite
import nebflow.core.tools.ToolRegistry

/**
 * canvas-ask-restricted-edit（CAE-REQ-001）红面：提案制受限编辑的工具面断言。
 * 本 spec 只用基线既有符号书写，在未实现基线上必须红（10 工具面 vs 旧 8 工具面）。
 */
final class AskEditFaceSpec extends FunSuite:

  test("EF1: ask_edit/ask_write are Ask-exclusive extras") {
    assert(AskSessions.AskExclusiveTools.contains("ask_edit"), "ask_edit must be Ask-exclusive")
    assert(AskSessions.AskExclusiveTools.contains("ask_write"), "ask_write must be Ask-exclusive")
    assert(AskSessions.AllowedTools.contains("ask_edit"))
    assert(AskSessions.AllowedTools.contains("ask_write"))
  }

  // Ordinary Edit/Write authorization is tested through DualModeToolFaceSpec.

  test("EF2: ask_edit/ask_write are registered tools") {
    assert(ToolRegistry.TOOL_MAP.contains("ask_edit"), "ask_edit must be registered in ToolRegistry")
    assert(ToolRegistry.TOOL_MAP.contains("ask_write"), "ask_write must be registered in ToolRegistry")
  }

  test("EF3: prompt retains context without obsolete Bash restriction") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/tmp/notes.md", "CONTEXT-BLOCK")
    assert(!prompt.contains("QUERY-ONLY"))
    assert(prompt.contains("CONTEXT-BLOCK"))
  }
end AskEditFaceSpec
