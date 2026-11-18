package nebflow.core.ask

/** Ask's independent tool face, shared-tool permissions and prompt contract.
  * canvas-ask-restricted-edit（CAE-REQ-001/002）：八工具面 → 十工具面
  * （+ ask_edit/ask_write 提案制受限编辑）；Bash 由普通 shell 换装为
  * 只读白名单执行器（prompt 同步换装）。
  *
  * 工具面同步（2026-11-19，作者裁「同步 spec 到真实工具面」，**产品零改动**）：
  * 期望值取自**注册表派生的真实工具面单源**，补上两笔早已/新近在册的能力——
  *   · `PdfPage`（PDF note/ask 批 `858545d9a`：绑定 PDF 的按需取页件，ask 专属；
  *     该批只改了 `AskSessions`，漏同步本 spec ⇒ 本 spec 在 `feat/base` 上即为红）；
  *   · `Card`（card-tool-revive 批 2026-11-18：从 git 历史复活，**仅**授 Canvas Ask）。
  * 断言强度只增不减：除了钉住这十二个名字，还钉「面 = 专属 ∪ 共享」的结构式与
  * 「每个名字都在注册表里」（单源）。 */
final class AskToolFaceSpec extends munit.FunSuite:

  /** 共享只读面（非 Ask 专属；Ask 与其他身份的公共底座）。 */
  private val sharedAskTools =
    Set("Read", "Glob", "Grep", "WebSearch", "WebFetch", "Bash")

  /** Ask 专属面（进 `AskSessions.AskExclusiveTools` ⇒ 从其余一切身份剥净）。 */
  private val exclusiveAskTools =
    Set("message_record_search", "send_message_to_main_chat", "ask_edit", "ask_write", "PdfPage", "Card")

  test("F1 Ask has exactly twelve tools (the registry-derived face)") {
    assertEquals(
      AskSessions.AllowedTools,
      exclusiveAskTools ++ sharedAskTools
    )
    // 结构式（单源）：ask 面**恰好**是「专属 ∪ 共享」两项，多一个少一个都红。
    assertEquals(AskSessions.AllowedTools, AskSessions.AskExclusiveTools ++ sharedAskTools)
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
      exclusiveAskTools
    )
    assert(AskSessions.AskExclusiveTools.subsetOf(AskSessions.AllowedTools))
    assert(AskSessions.AskExclusiveTools.intersect(sharedAskTools).isEmpty)
    // 直接 Edit/Write 不在 ask 面：受限编辑只走提案制两件套。
    assert(!AskSessions.AllowedTools.contains("Edit"))
    assert(!AskSessions.AllowedTools.contains("Write"))
  }

  test("F4 all twelve Ask tools are registered") {
    val registered = nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name).toSet
    AskSessions.AllowedTools.foreach { name =>
      assert(registered.contains(name), clue(name))
    }
  }

  test("F5 Ask prompt derives the tool list from the registry and states both hard constraints") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "context")
    // ask-scope 批（2026-11-18）：清单**派生**（注册表 = 唯一来源），逐条落 `- <name>:` 行。
    AskSessions.AllowedTools.foreach { n =>
      assert(prompt.contains(s"- $n:"), clue(n))
    }
    assert(prompt.contains("QUERY-ONLY, fail-closed"), "the Bash face must stay documented as query-only")
    assert(prompt.contains("grep, rg, ls,"))
    assert(prompt.contains("log/show/diff/status"))
    assert(prompt.contains("本会话只能编辑它对应的那份文档"), "constraint 1 (bound document only)")
    assert(prompt.contains("复杂任务转交主会话"), "constraint 2 (hand complex tasks to the main session)")
    assert(prompt.contains("task = 做什么"), "the structured handoff fields must be named")
    assert(prompt.contains("expected = 期望产出"), "the structured handoff fields must be named")
    assert(prompt.contains("/vault/notes.md"))
    assert(!prompt.contains("GitQuery"))
    assert(!prompt.contains("read_file_content"))
    assert(!prompt.contains("没有任何写/执行/编排工具"))
    assert(!prompt.contains("- Write:"), "the ask face must not advertise Write")
    assert(!prompt.contains("- Edit:"), "the ask face must not advertise Edit")
    val handoff = nebflow.core.tools.SendMessageToMainChatTool.description
    assert(handoff.contains("only outbound channel"))
    assert(handoff.contains("STRUCTURED handoff request"))
    assert(!handoff.contains("only outbound action"))
  }
end AskToolFaceSpec
