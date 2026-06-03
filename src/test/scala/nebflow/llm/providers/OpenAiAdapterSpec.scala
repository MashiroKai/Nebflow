package nebflow.llm.providers

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.llm.SendMessageParams
import nebflow.shared.*
import munit.CatsEffectSuite

class OpenAiAdapterSpec extends CatsEffectSuite:

  private val adapter = new OpenAiAdapter("https://api.example.com/v1", "test-key", null)

  // ====== toOpenAiMessages ======

  test("toOpenAiMessages: simple text message") {
    val result = adapter.toOpenAiMessages(List(Message(MessageRole.User, Left("Hello"))))
    assertEquals(result.size, 1)
    assertEquals(result.head.hcursor.downField("role").as[String].toOption, Some("user"))
    assertEquals(result.head.hcursor.downField("content").as[String].toOption, Some("Hello"))
  }

  test("toOpenAiMessages: system messages filtered") {
    val result = adapter.toOpenAiMessages(List(
      Message(MessageRole.System, Left("sys")),
      Message(MessageRole.User, Left("hi"))
    ))
    assertEquals(result.size, 1)
  }

  test("toOpenAiMessages: tool_use -> tool_calls array, content=null") {
    val result = adapter.toOpenAiMessages(List(
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.ToolUse("call_1", "Read", JsonObject("file_path" -> "/test.txt".asJson))
      )))
    ))
    assertEquals(result.size, 1)
    val m = result.head
    assertEquals(m.hcursor.downField("role").as[String].toOption, Some("assistant"))
    assert(m.hcursor.downField("content").as[Option[String]].toOption.flatten.isEmpty)
    assertEquals(m.hcursor.downField("tool_calls").as[List[Json]].toOption.map(_.size), Some(1))
  }

  test("toOpenAiMessages: text + tool_use") {
    val result = adapter.toOpenAiMessages(List(
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.Text("Reading file."),
        ContentBlock.ToolUse("call_1", "Read", JsonObject("file_path" -> "/t.txt".asJson))
      )))
    ))
    assertEquals(result.size, 1)
    assertEquals(result.head.hcursor.downField("content").as[String].toOption, Some("Reading file."))
  }

  test("toOpenAiMessages: CRITICAL - multiple tool_results => SEPARATE tool messages") {
    val result = adapter.toOpenAiMessages(List(
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("c1", "content A"),
        ContentBlock.ToolResult("c2", "content B"),
        ContentBlock.ToolResult("c3", "content C")
      )))
    ))
    assertEquals(result.size, 3, s"Must produce 3 separate tool messages, got ${result.size}")
    val ids = result.map(_.hcursor.downField("tool_call_id").as[String].toOption.getOrElse(""))
    assertEquals(ids, List("c1", "c2", "c3"))
    val contents = result.map(_.hcursor.downField("content").as[String].toOption.getOrElse(""))
    assertEquals(contents, List("content A", "content B", "content C"))
  }

  test("toOpenAiMessages: full multi-turn conversation") {
    val result = adapter.toOpenAiMessages(List(
      Message(MessageRole.User, Left("Read the file")),
      Message(MessageRole.Assistant, Right(List(
        ContentBlock.ToolUse("call_abc", "Read", JsonObject("file_path" -> "/test.txt".asJson))
      ))),
      Message(MessageRole.User, Right(List(
        ContentBlock.ToolResult("call_abc", "file contents")
      ))),
      Message(MessageRole.Assistant, Right(List(ContentBlock.Text("Done."))))
    ))
    assertEquals(result.size, 4)
    val roles = result.map(_.hcursor.downField("role").as[String].toOption.getOrElse(""))
    assertEquals(roles, List("user", "assistant", "tool", "assistant"))
  }

  // ====== buildSystemMessage ======

  test("buildSystemMessage: combines stable + dynamic") {
    val params = SendMessageParams(Nil, "gpt-4o",
      systemStable = Some("Be helpful."), systemDynamic = Some("Time: 12:00"))
    val result = adapter.buildSystemMessage(params)
    assert(result.isDefined)
    val content = result.get.hcursor.downField("content").as[String].toOption.getOrElse("")
    assert(content.contains("Be helpful.") && content.contains("Time: 12:00"))
  }

  // ====== extractToolCalls (non-streaming) ======

  test("extractToolCalls: single tool call") {
    val json = parse("""{"choices":[{"message":{"tool_calls":[
      {"id":"call_1","type":"function","function":{"name":"Read","arguments":"{\"file_path\":\"/t.txt\"}"}}
    ]}}]}""").toOption.get
    val tcs = adapter.extractToolCalls(json)
    assertEquals(tcs.size, 1)
    assertEquals(tcs.head.id, "call_1")
    assertEquals(tcs.head.name, "Read")
    assertEquals(tcs.head.input("file_path").flatMap(_.asString), Some("/t.txt"))
  }

  test("extractToolCalls: multiple tool calls") {
    val json = parse("""{"choices":[{"message":{"tool_calls":[
      {"id":"c1","type":"function","function":{"name":"Read","arguments":"{}"}},
      {"id":"c2","type":"function","function":{"name":"Grep","arguments":"{}"}}
    ]}}]}""").toOption.get
    val tcs = adapter.extractToolCalls(json)
    assertEquals(tcs.size, 2)
    assertEquals(tcs(0).name, "Read")
    assertEquals(tcs(1).name, "Grep")
  }

  // ====== Streaming: processOpenAiData ======

  test("processOpenAiData: tool call start") {
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](Map.empty)
    val data = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"Read","arguments":""}}]},"finish_reason":null}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
        fs <- state.get
    yield
      assertEquals(chunks.size, 1)
      assert(chunks.head.isInstanceOf[StreamChunk.ToolCallStart])
      assertEquals(chunks.head.asInstanceOf[StreamChunk.ToolCallStart].name, "Read")
      assert(fs.contains(0))
      assertEquals(fs(0)._1, "call_abc")
  }

  test("processOpenAiData: CRITICAL - empty delta + finish_reason=tool_calls flushes state") {
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](
      Map(0 -> ("call_abc", "Read", new StringBuilder("{\"file_path\":\"/test.txt\"}")))
    )
    val data = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
        fs <- state.get
    yield
      assert(chunks.size >= 2, s"Expected ToolCallChunk+Done, got ${chunks.size}: $chunks")
      val hasTC = chunks.exists(_.isInstanceOf[StreamChunk.ToolCallChunk])
      assert(hasTC, "Missing ToolCallChunk")
      val hasDone = chunks.exists(_.isInstanceOf[StreamChunk.Done])
      assert(hasDone, "Missing Done")
      assert(fs.isEmpty, "State should be cleared")
  }

  test("processOpenAiData: finish_reason=tool_calls with tool_calls in delta") {
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](
      Map(0 -> ("call_1", "Read", new StringBuilder("{\"file_path\":")))
    )
    val data = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"/t.txt\"}"}}]},"finish_reason":"tool_calls"}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      val hasTC = chunks.exists(_.isInstanceOf[StreamChunk.ToolCallChunk])
      assert(hasTC, "Expected ToolCallChunk")
  }

  test("processOpenAiData: usage-only chunk") {
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](Map.empty)
    val data = """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20}}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      assertEquals(chunks.size, 1)
      val done = chunks.head.asInstanceOf[StreamChunk.Done]
      assert(done.usage.isDefined)
      assertEquals(done.usage.get.inputTokens, 100)
  }

  test("processOpenAiData: text delta") {
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](Map.empty)
    val data = """{"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      assertEquals(chunks.size, 1)
      assertEquals(chunks.head.asInstanceOf[StreamChunk.TextDelta].delta, "Hello")
  }

  test("processOpenAiData: reasoning_content => ThinkingDelta") {
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](Map.empty)
    val data = """{"choices":[{"index":0,"delta":{"reasoning_content":"thinking..."},"finish_reason":null}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      assertEquals(chunks.size, 1)
      assertEquals(chunks.head.asInstanceOf[StreamChunk.ThinkingDelta].delta, "thinking...")
  }

end OpenAiAdapterSpec
