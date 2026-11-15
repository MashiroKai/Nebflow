package nebflow.core.ask

/** Ask's independent tool face, shared-tool permissions and prompt contract. */
final class AskToolFaceSpec extends munit.FunSuite:

  test("F1 Ask has exactly eight tools") {
    assertEquals(
      AskSessions.AllowedTools,
      Set(
        "message_record_search",
        "send_message_to_main_chat",
        "Read",
        "Glob",
        "Grep",
        "WebSearch",
        "WebFetch",
        "Bash"
      )
    )
  }

  test("F2 retired file and git tools are absent from the allowlist and registry") {
    val registered = nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name).toSet
    Set("read_file_content", "GitQuery").foreach { name =>
      assert(!AskSessions.AllowedTools.contains(name), clue(name))
      assert(!registered.contains(name), clue(name))
      assert(!nebflow.core.tools.ToolRegistry.TOOL_MAP.contains(name), clue(name))
    }
  }

  test("F3 Ask-exclusive stripping leaves shared lookup and Bash tools") {
    assertEquals(
      AskSessions.AskExclusiveTools,
      Set("message_record_search", "send_message_to_main_chat")
    )
    assert(AskSessions.AskExclusiveTools.subsetOf(AskSessions.AllowedTools))
    val sharedTools = Set("Read", "Glob", "Grep", "WebSearch", "WebFetch", "Bash")
    assert(AskSessions.AskExclusiveTools.intersect(sharedTools).isEmpty)
  }

  test("F4 all eight Ask tools are registered") {
    val registered = nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name).toSet
    AskSessions.AllowedTools.foreach { name =>
      assert(registered.contains(name), clue(name))
    }
  }

  test("F5 Ask prompt advertises normal Bash without read-only execution guarantees") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "context")
    assert(prompt.contains("Read / Glob / Grep"))
    assert(prompt.contains("WebSearch / WebFetch"))
    assert(prompt.contains("message_record_search"))
    assert(prompt.contains("send_message_to_main_chat"))
    assert(prompt.contains("Bash: run normal shell commands for file and git investigation"))
    assert(prompt.contains("Shell access is not a read-only"))
    assert(prompt.contains("existing execution and permission checks still apply"))
    assert(prompt.contains("only\n  when the user explicitly requests"))
    assert(prompt.contains("/vault/notes.md"))
    assert(!prompt.contains("GitQuery"))
    assert(!prompt.contains("read_file_content"))
    assert(!prompt.contains("没有任何写/执行/编排工具"))
    val handoff = nebflow.core.tools.SendMessageToMainChatTool.description
    assert(handoff.contains("message channel to the main session"))
    assert(handoff.contains("ONLY when the user explicitly asks"))
    assert(!handoff.contains("only outbound action"))
  }
end AskToolFaceSpec
