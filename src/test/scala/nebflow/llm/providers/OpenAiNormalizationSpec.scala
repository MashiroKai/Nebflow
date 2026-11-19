package nebflow.llm.providers

import io.circe.Json
import munit.FunSuite
import nebflow.shared.*

/**
 * protocol-compat 批（2026-11-01）——**openai 面的思考/工具/正文归一化**钉。
 *
 * 背景（作者 17:56、17:58 实测）：cmdcode（OpenAI 协议）返回
 * `choices[0].message.content == ""`，真正内容在 `reasoning_content`；工具调用
 * 与多轮 ack 都必须照常работать。旧实现的三个洞：
 *
 *   O1 `requireThinkingPassback` 在 openai 面**是哑开关**（registry 只交给
 *      AnthropicAdapter）⇒ 配了要回传，代码从不回传 reasoning_content。
 *   O2 一条消息里同时有 tool_result 与正文时，正文被整段**静默丢弃**。
 *   O3 `content` 若是块数组（兼容端点常见）或 `arguments` 是对象，正文/参数
 *      被当成空。
 *
 * 变异验红：把 replayThinking 改回 false → O1 红；删掉 toolResultMessages ++
 * 正文分支 → O2 红；把 textOfMessage 的数组分支删掉 → O3 红。
 */
class OpenAiNormalizationSpec extends FunSuite:

  private def adapter(passback: Boolean): OpenAiAdapter =
    new OpenAiAdapter("https://api.example.com/v1", "k", null, requireThinkingPassback = passback)

  private val history = List(
    Message(MessageRole.User, Left("use the tool")),
    Message(
      MessageRole.Assistant,
      Right(
        List(
          ContentBlock.Thinking("I should call Bash", None),
          ContentBlock.ToolUse("call_1", "Bash", io.circe.JsonObject.fromMap(Map("command" -> Json.fromString("ls"))))
        )
      )
    ),
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult("call_1", "a.txt", None)))),
    Message(MessageRole.User, Left("what did it print?"))
  )

  // ── O1：思考回传 ─────────────────────────────────────────────────────

  test("O1: passback=true replays reasoning_content on the assistant turn") {
    val out = adapter(true).toOpenAiMessages(history)
    val assistant = out.find(m => m.hcursor.downField("role").as[String].toOption.contains("assistant")).get
    assertEquals(
      assistant.hcursor.downField("reasoning_content").as[String].toOption,
      Some("I should call Bash")
    )
    // 工具调用必须同时在场（思考回传不得挤掉 tool_calls）
    assert(assistant.hcursor.downField("tool_calls").focus.exists(_.isArray))
  }

  test("O1b: passback=false leaves the openai wire byte-identical to the old shape") {
    val out = adapter(false).toOpenAiMessages(history)
    val assistant = out.find(m => m.hcursor.downField("role").as[String].toOption.contains("assistant")).get
    assertEquals(assistant.hcursor.downField("reasoning_content").focus, None)
  }

  // ── O2：tool_result + 正文不丢 ────────────────────────────────────────

  test("O2: a message carrying BOTH tool_result and text keeps the text") {
    val msgs = List(
      Message(MessageRole.User, Left("run it")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("c1", "Bash", io.circe.JsonObject.empty)))
      ),
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("c1", "out", None), ContentBlock.Text("and also this note")))
      )
    )
    val out = adapter(false).toOpenAiMessages(msgs)
    val joined = out.map(_.noSpaces).mkString("\n")
    assert(joined.contains("\"role\":\"tool\""), joined)
    assert(joined.contains("and also this note"), s"text must survive next to the tool result: $joined")
  }

  // ── O3：非规范形态的降级 ─────────────────────────────────────────────

  test("O3: a non-streaming `content` array is read as text (compat endpoints)") {
    val json = io.circe.parser
      .parse(
        """{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"parts"},{"type":"text","text":" joined"}]}}]}"""
      )
      .toOption
      .get
    assertEquals(adapter(false).textOfMessage(json), "parts joined")
  }

  test("O3b: tool call `arguments` sent as an OBJECT is still parsed (not silently {})") {
    val json = io.circe.parser
      .parse(
        """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"Bash","arguments":{"command":"ls -la"}}}]}}]}"""
      )
      .toOption
      .get
    val calls = adapter(false).extractToolCalls(json)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.name, "Bash")
    assertEquals(calls.head.input.toMap.get("command").flatMap(_.asString), Some("ls -la"))
  }

  // ── 路由自述 ─────────────────────────────────────────────────────────

  test("O4: the openai system message carries provider/model (route readable by the batch)") {
    val adapterWithRoute = new OpenAiAdapter("https://api.example.com/v1", "k", null, true, "cmdcode")
    val sys = adapterWithRoute
      .buildSystemMessage(
        nebflow.llm.SendMessageParams(
          messages = List(Message(MessageRole.User, Left("hi"))),
          model = "deepseek/deepseek-v4.1-flash",
          systemStable = Some("stable"),
          systemDynamic = Some("dynamic")
        )
      )
      .get
    val text = sys.hcursor.downField("content").as[String].getOrElse("")
    assert(text.startsWith("stable"), "stable prefix must stay first (prompt cache)")
    assert(text.contains("provider=cmdcode"), text)
    assert(text.contains("model=deepseek/deepseek-v4.1-flash"), text)
  }
