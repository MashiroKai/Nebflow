package nebflow.core.ask

/* ═══════════════════════════════════════════════════════════════════════════
 * ask 换装批工具面 spec（2026-10-31 作者 12:25 裁定：askmode 窗口工具面 =
 * glob/grep/read/websearch/webfetch 只读五件 + 思考低档；取代上午批
 * read_file_content 单件路线——设计 docs/design/20261031_124045_ask-readonly-
 * five-tool-redesign__delegate-nebflow-6cf27f86.zh-CN.md）。
 *
 * 覆盖面：
 *   F1  AllowedTools 封闭枚举逐字钉死 = ask 专属两件 + Subagent 只读五件
 *   F2  read_file_content 退役：白名单/注册面双缺席
 *   F3  AskExclusiveTools 反剥集 ≠ AllowedTools：只含 ask 专属两件，不碰
 *       通用只读五件（防「反剥连坐主会话读面」回归）
 *   F4  只读五件全员注册（ToolRegistry 在册，ask 早退集可解析成 schema）
 *   F5  promptFor：工具面描述与绑定文件绝对路径注入（Read 的锚）
 * ═══════════════════════════════════════════════════════════════════════════ */

final class AskToolFaceSpec extends munit.FunSuite:

  test("F1 AllowedTools = ask 专属两件 + SubagentFixedTools 只读五件（封闭枚举逐字）") {
    assertEquals(
      AskSessions.AllowedTools,
      Set("message_record_search", "send_message_to_main_chat") ++ nebflow.agent.AgentCore.SubagentFixedTools
    )
    assertEquals(
      AskSessions.AllowedTools,
      Set(
        "message_record_search", "send_message_to_main_chat",
        "Read", "Glob", "Grep", "WebSearch", "WebFetch"
      )
    )
  }

  test("F2 read_file_content 退役：白名单缺席 + 注册面缺席") {
    assert(!AskSessions.AllowedTools.contains("read_file_content"), clue(AskSessions.AllowedTools))
    assert(
      !nebflow.core.tools.ToolRegistry.ALL_TOOLS.exists(_.name == "read_file_content"),
      "注册表不得残留 read_file_content（工具对象已删）"
    )
  }

  test("F3 AskExclusiveTools 反剥集只含 ask 专属两件（不碰通用只读五件）") {
    assertEquals(AskSessions.AskExclusiveTools, Set("message_record_search", "send_message_to_main_chat"))
    assert(AskSessions.AskExclusiveTools.subsetOf(AskSessions.AllowedTools))
    // 反剥集与通用只读面零交集——进了反剥集会连主会话读面一起剥掉。
    val genericReadOnly = Set("Read", "Glob", "Grep", "WebSearch", "WebFetch")
    assert(
      AskSessions.AskExclusiveTools.intersect(genericReadOnly).isEmpty,
      clue(AskSessions.AskExclusiveTools)
    )
  }

  test("F4 只读五件在 ToolRegistry 全员在册（ask 早退集可解析）") {
    val registered = nebflow.core.tools.ToolRegistry.ALL_TOOLS.map(_.name).toSet
    Set("Read", "Glob", "Grep", "WebSearch", "WebFetch").foreach { n =>
      assert(registered.contains(n), clue(n))
    }
  }

  test("F5 promptFor：工具面描述五件 + 绑定文件绝对路径注入") {
    val p = AskContextAssembler.promptFor("笔记.md", "/vault/笔记.md", "【问答上下文】")
    assert(p.contains("Read / Glob / Grep"), clue(p.take(200)))
    assert(p.contains("WebSearch / WebFetch"), clue(p))
    assert(p.contains("message_record_search"), clue(p))
    assert(p.contains("send_message_to_main_chat"), clue(p))
    assert(p.contains("/vault/笔记.md"), "绑定文件绝对路径必须进提示词（Read 锚）")
    assert(!p.contains("read_file_content"), "退役工具名不得残留提示词")
  }
end AskToolFaceSpec
