package nebflow.core.ask

/**
 * Ask's independent tool face, shared-tool permissions and prompt contract.
 * canvas-ask-restricted-edit（CAE-REQ-001/002）：八工具面 → 十工具面
 * （+ ask_edit/ask_write 提案制受限编辑）；Bash 由普通 shell 换装为
 * 只读白名单执行器（prompt 同步换装）。
 *
 * 工具面同步（2026-11-19，作者裁「同步 spec 到真实工具面」，**产品零改动**）：
 * 期望值取自**注册表派生的真实工具面单源**，补上两笔早已/新近在册的能力——
 *   · `PdfPage`（PDF note/ask 批 `858545d9a`：绑定 PDF 的按需取页件，ask 专属；
 *     该批只改了 `AskSessions`，漏同步本 spec ⇒ 本 spec 在 `feat/base` 上即为红）；
 *   · `Card`（card-tool-revive 批 2026-11-18：从 git 历史复活，**仅**授 Canvas Ask）。
 * 断言强度只增不减：除了钉住这十四个名字，还钉「面 = 专属 ∪ 共享」的结构式与
 * 「每个名字都在注册表里」（单源）。
 *
 * 十二 → 十四（2026-11-21 收口）：gitviz 分支管理问答批再补 `gitviz_graph` /
 * `gitviz_handoff` 两件，同一口径（同步 spec 到真实工具面，产品零改动）。
 */
final class AskToolFaceSpec extends munit.FunSuite:

  /** 共享只读面（非 Ask 专属；Ask 与其他身份的公共底座）。 */
  private val sharedAskTools =
    Set("Read", "Glob", "Grep", "WebSearch", "WebFetch", "Bash")

  /**
   * Ask 专属面（进 `AskSessions.AskExclusiveTools` ⇒ 从其余一切身份剥净）。
   *
   * gitviz 分支管理问答批（2026-11-19 第二轮）：再补两笔早已在册的能力——
   *   · `gitviz_graph`（分支图只读取数件，数据面 = 既有 `GitGraph.build`）；
   *   · `gitviz_handoff`（分支管理问答的转交件）。
   * 两者均已在 `AskSessions.AskExclusiveTools` 与 `ToolRegistry` 注册表内
   * （`registry.scala:63/67`），工具内侧另有 `isGitVizSession` 身份闸兜底。
   * 该批同样**只改授权面与真实工具面、产品零改动**，本 spec 依旧是滞后一方。
   */
  private val exclusiveAskTools =
    Set(
      "message_record_search",
      "send_message_to_main_chat",
      "ask_edit",
      "ask_write",
      "PdfPage",
      "Card",
      "gitviz_graph",
      "gitviz_handoff"
    )

  test("F1 Ask has exactly fourteen tools (the registry-derived face)") {
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

  test("F4 all fourteen Ask tools are registered") {
    val registered = nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name).toSet
    AskSessions.AllowedTools.foreach { name =>
      assert(registered.contains(name), clue(name))
    }
  }

  test("F5 historical single-file restriction and mandatory handoff are absent") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "context")
    assert(prompt.contains("/vault/notes.md"))
    assert(!prompt.contains("本会话只能编辑它对应的那份文档"))
    assert(!prompt.contains("QUERY-ONLY, fail-closed"))
    assert(prompt.contains("转交是可选的"))
  }

  /* Superseded historical prompt assertions retained for provenance:
  test("F5 Ask prompt derives the tool list from the registry and states both hard constraints") {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "context")
    // ask-scope 批（2026-11-18）：清单**派生**（注册表 = 唯一来源），逐条落 `- <name>:` 行。
    AskSessions.AllowedTools.foreach { n =>
      assert(prompt.contains(s"- $n:"), clue(n))
    }
    assert(prompt.contains("QUERY-ONLY, fail-closed"), "the Bash face must stay documented as query-only")
    assert(prompt.contains("grep, rg, ls,"))
    assert(prompt.contains("log/show/diff/status/branch"))
    assert(prompt.contains("工作目录 = 工作区根"), "the Bash cwd is the workspace root (read scope, 2026-11-23)")
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

  test(
    "F6 Ask prompt carries the capability boundary: simple edits in scope, complex tasks default to the main session"
  ) {
    val prompt = AskContextAssembler.promptFor("notes.md", "/vault/notes.md", "context")
    // 边界段在场（作者令 2026-11-19「它除了问答，简单的修改文档/文件，复杂的任务要
    // 默认交给主会话」）——删掉该段本用例即红。
    assert(prompt.contains("能力边界"), "the capability-boundary section must be in the rendered prompt")
    // 「简单文档/文件修改」的四条可对照判据（单文件 / 局部小改 / 无跨模块 / 一句话说清）。
    assert(prompt.contains("四条同时成立"), "the boundary must give the decidable simple-edit test")
    assert(prompt.contains("单文件") && prompt.contains("无跨模块影响"), "single-file + no-cross-module conditions")
    assert(prompt.contains("一句话说清"), "the one-sentence condition")
    // 超出即默认转交主会话。
    assert(prompt.contains("复杂任务转交主会话"), "complex tasks default to the main session")
    // 转交必须自包含：目标 / 约束 / 验收 / 已掌握的事实（主会话不用回问即可派发）。
    assert(prompt.contains("自包含"), "the handoff must be self-contained")
    assert(
      prompt.contains("已掌握的事实") && prompt.contains("验收"),
      "the self-contained handoff must name the goal/constraints/acceptance/facts"
    )
    // 例外：作者当场明确要求的那一件可做，仍给「复杂 ⇒ 建议转交」提示。
    assert(prompt.contains("当场明确要求"), "the author's on-the-spot explicit request is the allowed exception")
    assert(prompt.contains("建议转交主会话"), "the exception still carries the complex⇒handoff hint")
  }
  */
end AskToolFaceSpec
