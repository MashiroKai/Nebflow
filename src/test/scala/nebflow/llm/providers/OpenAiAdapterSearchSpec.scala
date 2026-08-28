package nebflow.llm.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.llm.{ProviderSearchKind, SendMessageParams}
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{GenericRequest, Response, StreamBackend}

/** WebSearch P0 request-construction assertions (E2-1/2/3):
  *   - zhipu: web_search tool APPENDED to the agent tools (deepMerge must
  *     not clobber them — the spec's explicit regression point)
  *   - qwen: enable_search:true, no tools mutation
  *   - kimi: $web_search builtin_function + thinking FORCED disabled (and
  *     reasoning_effort absent) even when the caller asked for thinking
  *   - no capability → request byte-identical to the pre-P0 shape
  *   - extractSearchInfo (zhipu `web_search` / qwen `search_info`)
  *   - extractToolCalls keeps rawArguments byte-faithful (kimi echo source)
  */
class OpenAiAdapterSearchSpec extends CatsEffectSuite:

  /** Capturing in-memory backend: serves a canned JSON response, records the
    * serialized request body of the (non-streaming) sendMessage call. */
  private class CapturingBackend(responseBody: String) extends StreamBackend[IO, Fs2Streams[IO]]:
    @volatile var capturedBody: String = ""
    def send[T](
        request: GenericRequest[T, Fs2Streams[IO] & sttp.capabilities.Effect[IO]]
    ): IO[Response[T]] =
      IO.delay {
        capturedBody = request.body match
          case b: sttp.client4.StringBody => b.s
          case other => other.toString
      } *> IO.pure(
        Response(Right(responseBody).asInstanceOf[T], sttp.model.StatusCode.Ok, "", Nil)
      )
    def monad: sttp.monad.MonadError[IO] =
      new sttp.client4.impl.cats.CatsMonadError[IO](using IO.asyncForIO)
    def close(): IO[Unit] = IO.unit

  private val okResponse = """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""

  private val bashTool = ToolDefinition("Bash", "run shell", JsonObject("type" -> "object".asJson))
  private val readTool = ToolDefinition("Read", "read file", JsonObject("type" -> "object".asJson))
  private val enabledThinking = parse("""{"type":"enabled","budget_tokens":8000}""").toOption

  private def requestBody(p: SendMessageParams): Json =
    val backend = new CapturingBackend(okResponse)
    val adapter = new OpenAiAdapter("https://api.example.com/v1", "k", backend)
    adapter.sendMessage(p).attempt.unsafeRunSync()
    parse(backend.capturedBody).getOrElse(Json.Null)

  // ── E2-1: zhipu ──────────────────────────────────────────────────────

  test("zhipu: web_search appended AFTER agent tools — originals not clobbered (deepMerge order regression)") {
    val body = requestBody(
      SendMessageParams(
        messages = List(Message(MessageRole.User, Left("hi"))),
        model = "glm-5.3",
        tools = Some(List(bashTool, readTool)),
        providerSearch = Some(ProviderSearchKind.ZhipuWebSearchTool)
      )
    )
    val tools = body.hcursor.downField("tools").as[List[Json]].toOption.get
    assertEquals(tools.size, 3, s"expected 2 agent tools + web_search, got $tools")
    assertEquals(tools(0).hcursor.downField("function").downField("name").as[String].toOption, Some("Bash"))
    assertEquals(tools(1).hcursor.downField("function").downField("name").as[String].toOption, Some("Read"))
    assertEquals(tools(2).hcursor.downField("type").as[String].toOption, Some("web_search"))
    // enable:true REQUIRED (omitting it → zhipu 1210, verified live 2026-08-23);
    // search_result:true → response carries the structured web_search field
    // (the Tier 2 evidence source).
    assertEquals(
      tools(2).hcursor.downField("web_search").downField("enable").as[Boolean].toOption,
      Some(true)
    )
    assertEquals(
      tools(2).hcursor.downField("web_search").downField("search_result").as[Boolean].toOption,
      Some(true)
    )
    // no enable_search leakage
    assert(body.hcursor.downField("enable_search").as[Boolean].toOption.isEmpty)
  }

  test("zhipu: no agent tools → web_search becomes the sole tools entry (executor sub-request shape)") {
    val body = requestBody(
      SendMessageParams(
        messages = List(Message(MessageRole.User, Left("hi"))),
        model = "glm-5.3",
        tools = Some(Nil),
        providerSearch = Some(ProviderSearchKind.ZhipuWebSearchTool)
      )
    )
    val tools = body.hcursor.downField("tools").as[List[Json]].toOption.get
    assertEquals(tools.size, 1)
    assertEquals(tools.head.hcursor.downField("type").as[String].toOption, Some("web_search"))
  }

  // ── E2-2: qwen ───────────────────────────────────────────────────────

  test("qwen: enable_search:true present; tools array untouched") {
    val body = requestBody(
      SendMessageParams(
        messages = List(Message(MessageRole.User, Left("hi"))),
        model = "qwen3.8-max",
        tools = Some(List(bashTool)),
        providerSearch = Some(ProviderSearchKind.QwenEnableSearch)
      )
    )
    assertEquals(body.hcursor.downField("enable_search").as[Boolean].toOption, Some(true))
    val tools = body.hcursor.downField("tools").as[List[Json]].toOption.get
    assertEquals(tools.size, 1, "qwen injection must NOT add a tools entry")
    assertEquals(tools.head.hcursor.downField("function").downField("name").as[String].toOption, Some("Bash"))
  }

  // ── E2-3: kimi ───────────────────────────────────────────────────────

  test("kimi: thinking FORCED disabled (reasoning_effort absent) even with enabled thinking; builtin_function appended") {
    val body = requestBody(
      SendMessageParams(
        messages = List(Message(MessageRole.User, Left("hi"))),
        model = "kimi-k3",
        tools = Some(List(bashTool)),
        thinking = enabledThinking,
        providerSearch = Some(ProviderSearchKind.KimiBuiltinWebSearch)
      )
    )
    assertEquals(
      body.hcursor.downField("thinking").downField("type").as[String].toOption,
      Some("disabled"),
      s"thinking must be forced disabled, got ${body.noSpaces.take(300)}"
    )
    assert(
      body.hcursor.downField("reasoning_effort").as[String].toOption.isEmpty,
      "kimi search request must not carry reasoning_effort"
    )
    val tools = body.hcursor.downField("tools").as[List[Json]].toOption.get
    assertEquals(tools.size, 2)
    assertEquals(tools(1).hcursor.downField("type").as[String].toOption, Some("builtin_function"))
    assertEquals(
      tools(1).hcursor.downField("function").downField("name").as[String].toOption,
      Some("$web_search")
    )
  }

  // ── E4: no capability → byte-level equivalent behavior ───────────────

  test("no search: request shape unchanged (tools intact, no enable_search, thinking normal)") {
    val body = requestBody(
      SendMessageParams(
        messages = List(Message(MessageRole.User, Left("hi"))),
        model = "glm-5.3",
        tools = Some(List(bashTool)),
        thinking = enabledThinking
      )
    )
    val tools = body.hcursor.downField("tools").as[List[Json]].toOption.get
    assertEquals(tools.size, 1)
    // GLM thinking takes its native shape (thinking enabled), as before P0.
    assertEquals(body.hcursor.downField("thinking").downField("type").as[String].toOption, Some("enabled"))
    assert(body.hcursor.downField("enable_search").as[Boolean].toOption.isEmpty)
  }

  // ── searchInfo extraction ────────────────────────────────────────────

  private val adapter = new OpenAiAdapter("https://api.example.com/v1", "k", null)

  test("extractSearchInfo: zhipu web_search array") {
    val resp =
      parse("""{"choices":[{"message":{"content":"a"}}],"web_search":[{"title":"t","url":"https://e.com/x"}]}""")
        .toOption
        .get
    val info = adapter.extractSearchInfo(resp)
    assert(info.isDefined)
    assertEquals(info.get.asArray.get.size, 1)
  }

  test("extractSearchInfo: qwen search_info object") {
    val resp =
      parse("""{"choices":[{"message":{"content":"a"}}],"search_info":{"search_results":[{"url":"https://e.com/y"}]}}""")
        .toOption
        .get
    val info = adapter.extractSearchInfo(resp)
    assert(info.isDefined)
    assert(info.get.isObject)
  }

  test("extractSearchInfo: absent on ordinary responses") {
    val resp = parse(okResponse).toOption.get
    assertEquals(adapter.extractSearchInfo(resp), None)
  }

  test("extractSearchInfo: DashScope nests search_info under choices[0].message") {
    val resp = parse(
      """{"choices":[{"message":{"content":"a","search_info":{"search_results":[{"url":"https://e.com/nested"}]}}}]}"""
    ).toOption.get
    val info = adapter.extractSearchInfo(resp)
    assert(info.isDefined, "message-level search_info must be found")
  }

  // ── rawArguments preservation (kimi echo source) ─────────────────────

  test("extractToolCalls: rawArguments preserved byte-faithful") {
    val resp = parse(
      """{"choices":[{"message":{"tool_calls":[{"id":"c1","type":"function","function":{"name":"$web_search","arguments":"{ \"q\" : 1 }"}}]}}]}"""
    ).toOption.get
    val calls = adapter.extractToolCalls(resp)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.rawArguments, Some("""{ "q" : 1 }"""))
  }

end OpenAiAdapterSearchSpec
