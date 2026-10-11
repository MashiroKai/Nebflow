package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, `Content-Type`}

/**
 * Batch B2 ("立项（最小形态）") — the OpenAI wire-format shim + its alias
 * route. Two families of assertions:
 *
 *   1. `OpenAiChatShim` pure mapping (OpenAI body ⇄ in-tree case classes), with
 *      the discriminating failures that prove the shim's predicates have
 *      resolution — a refusal must be an explicit `Left`, never a silently
 *      dropped field.
 *   2. `ChatRoutes.routes` — the alias path is registered on the SAME routes
 *      value as the in-tree paths and sits behind the SAME auth gate, and the
 *      two existing paths are unchanged.
 */
class OpenAiChatShimSpec extends CatsEffectSuite:

  private val Token = "test-token-b2"
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](Token)

  private class ScriptedLlm(resp: LlmResponse) extends LlmHandle[IO]:
    val seen = cats.effect.Ref.unsafe[IO, List[LlmRequest]](Nil)

    def send(req: LlmRequest): IO[LlmResponse] =
      seen.update(req :: _).as(resp)

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.empty

  private def mkResponse(reply: String = "hi"): LlmResponse =
    LlmResponse(
      reply = reply,
      toolCalls = Nil,
      usage = Some(TokenUsage(inputTokens = 7, outputTokens = 3, cacheReadTokens = Some(2))),
      meta = LlmMeta(
        sessionId = "s1",
        agentId = "a1",
        providerId = "paid",
        model = "paid-model",
        durationMs = 12L
      )
    )

  /**
   * Wire-faithful request builder: raw UTF-8 bytes + an explicit
   * `application/json` content type.
   *
   * Deliberately NOT `withEntity(String)` / `withEntity(Array[Byte])` with the
   * ambient implicits: a scope importing `org.http4s.circe.CirceEntityCodec.*`
   * also has http4s-circe's `jsonEncoderOf[F, A: Encoder]`, which wins for
   * those types and encodes the payload **as a JSON value** — a `String`
   * becomes a JSON string literal (`"{\"model\":…}"`) and an `Array[Byte]`
   * becomes a JSON array of numbers (`[123,34,…]`). Both were measured with a
   * body probe during this batch. Passing the byte-array encoder explicitly
   * sends the bytes verbatim, which is what an OpenAI client does.
   */
  private def req(body: String, withAuth: Boolean = true): Request[IO] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString("/v1/chat/completions"))
      .withEntity(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))(
        EntityEncoder.byteArrayEncoder[IO]
      )
      .withContentType(`Content-Type`(MediaType.application.json))
    if withAuth then base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, Token)))
    else base

  /** Same, for an arbitrary path (the in-tree control case). */
  private def rawJsonRequest(path: String, body: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withEntity(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))(
        EntityEncoder.byteArrayEncoder[IO]
      )
      .withContentType(`Content-Type`(MediaType.application.json))

  /**
   * Read a response body as parsed JSON through the same explicit parser the
   * route uses — independent of the ambient implicit `EntityDecoder[Json]`
   * (which resolves to the JSON-string decoder on this spec's import scope;
   * measured with a body probe during this batch).
   */
  private def respJson(resp: Response[IO]): IO[Json] =
    resp.bodyText.compile.string.map(raw =>
      io.circe.parser.parse(raw) match
        case Right(j) => j
        case Left(e) => fail(s"non-JSON response body: $e — raw=$raw")
    )

  // ── 1. request mapping ───────────────────────────────────────────────────

  test("request: OpenAI body maps to LlmRequest (messages / model / identity)") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(
        Json.obj("role" -> "system".asJson, "content" -> "be brief".asJson),
        Json.obj("role" -> "user".asJson, "content" -> "hello".asJson)
      ),
      "session_id" -> "wechat-1".asJson,
      "agent_id" -> "wechat-agent".asJson
    )
    OpenAiChatShim.toLlmRequest(body) match
      case Left(r) => fail(s"unexpected refusal: ${r.code} ${r.message}")
      case Right(wire) =>
        assertEquals(wire.sessionId, "wechat-1")
        assertEquals(wire.agentId, "wechat-agent")
        assertEquals(wire.messages.map(_.role), List(MessageRole.System, MessageRole.User))
        assertEquals(wire.messages.map(_.textContent), List("be brief", "hello"))
        assertEquals(wire.tools, None)
  }

  test("request: the OpenAI model IS carried — it becomes the request's agent-model chain") {
    // Rework after the round-1 verdict `fail` (n-29f6d0ab): the previous形态
    // validated `model` and then dropped it, so every caller-supplied model
    // resolved to the same global preset. This assertion is the discriminating
    // one — it must FAIL if `model` stops reaching the carrier.
    def wired(m: String) =
      OpenAiChatShim.toLlmRequest(
        Json.obj(
          "model" -> m.asJson,
          "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
          "session_id" -> "s".asJson,
          "agent_id" -> "a".asJson
        )
      )

    wired("paid/paid-model") match
      case Left(r) => fail(s"unexpected refusal: ${r.code}")
      case Right(wire) =>
        assertEquals(
          wire.agentModel,
          Some(AgentModelConfig(preferred = Some("paid/paid-model"), fallbacks = Nil))
        )

    // Two DIFFERENT model refs must produce two DIFFERENT carriers — this is the
    // exact property the round-1 verdict measured as absent.
    val a = wired("paid/paid-model").map(_.agentModel)
    val b = wired("other/other-model").map(_.agentModel)
    assertNotEquals(a, b, "distinct model refs must not collapse onto one carrier")
  }

  test("request: a body with no model field is refused, never silently defaulted") {
    val body = Json.obj(
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    OpenAiChatShim.toLlmRequest(body) match
      case Left(r) => assertEquals(r.code, "invalid_model")
      case Right(w) =>
        fail(s"a missing model must be refused, not served by the global preset: ${w.agentModel}")
  }

  test("request: content parts — text + inline image data URL become blocks") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(
        Json.obj(
          "role" -> "user".asJson,
          "content" -> Json.arr(
            Json.obj("type" -> "text".asJson, "text" -> "what is this".asJson),
            Json.obj(
              "type" -> "image_url".asJson,
              "image_url" -> Json.obj("url" -> "data:image/png;base64,AAAA".asJson)
            )
          )
        )
      ),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    OpenAiChatShim.toLlmRequest(body) match
      case Left(r) => fail(s"unexpected refusal: ${r.code}")
      case Right(wire) =>
        val blocks = wire.messages.head.content.getOrElse(fail("expected blocks"))
        assertEquals(
          blocks,
          List(ContentBlock.Text("what is this"), ContentBlock.Image("AAAA", "image/png"))
        )
  }

  test("request: pure sampling knobs are accepted and declared not-carried") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson,
      "temperature" -> 0.2.asJson,
      "top_p" -> 0.9.asJson,
      "max_tokens" -> 32.asJson,
      "n" -> 1.asJson,
      "stream" -> false.asJson
    )
    val ignored = List("temperature", "top_p", "max_tokens", "n", "stream")
    assert(
      ignored.forall(OpenAiChatShim.ignoredFields.contains(_)),
      s"every accepted knob must be in the declared not-carried set: ${OpenAiChatShim.ignoredFields}"
    )
    assert(OpenAiChatShim.toLlmRequest(body).isRight, "knobs must not be refused")
  }

  test("refusal: every other value of stream is refused, not dropped") {
    val base = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    // truthy in every shape (bool / string / number) must be refused — missing
    // any of them would silently turn a streaming caller into a blocking one.
    List(
      Json.obj("stream" -> true.asJson),
      Json.obj("stream" -> "true".asJson),
      Json.obj("stream" -> 1.asJson)
    ).foreach { extra =>
      assert(
        OpenAiChatShim.toLlmRequest(base.deepMerge(extra)).isLeft,
        s"$extra must be refused"
      )
    }
  }

  // ── 1b. discriminating refusals (the shim's predicates must be able to fail)

  test("refusal: absent session_id / agent_id — no invented placeholder identity") {
    val noSession = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "agent_id" -> "a".asJson
    )
    OpenAiChatShim.toLlmRequest(noSession) match
      case Left(r) =>
        assertEquals(r.status, 400)
        assertEquals(r.code, "missing_identity")
        assertEquals(r.param, Some("session_id"))
      case Right(_) => fail("a body without session_id must be refused")

    val noAgent = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson
    )
    OpenAiChatShim.toLlmRequest(noAgent) match
      case Left(r) => assertEquals(r.param, Some("agent_id"))
      case Right(_) => fail("a body without agent_id must be refused")
  }

  test("refusal: empty-string identity is refused the same as an absent one") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "".asJson,
      "agent_id" -> "   ".asJson
    )
    assert(OpenAiChatShim.toLlmRequest(body).isLeft, "blank identity must be refused")
  }

  test("refusal: missing or empty messages") {
    val missing = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    OpenAiChatShim.toLlmRequest(missing) match
      case Left(r) =>
        assertEquals(r.code, "invalid_messages")
        assertEquals(r.param, Some("messages"))
      case Right(_) => fail("a body without messages must be refused")

    val empty = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    assert(OpenAiChatShim.toLlmRequest(empty).isLeft, "an empty messages array must be refused")
  }

  test("refusal: model form — absent, blank, or a bare OpenAI name") {
    def body(model: Option[String]) =
      val base = Json.obj(
        "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
        "session_id" -> "s".asJson,
        "agent_id" -> "a".asJson
      )
      model.fold(base)(m => base.deepMerge(Json.obj("model" -> m.asJson)))

    List(None, Some(""), Some("gpt-4o")).foreach { m =>
      OpenAiChatShim.toLlmRequest(body(m)) match
        case Left(r) =>
          assertEquals(r.code, "invalid_model", s"model=$m must be refused on form")
          assertEquals(r.param, Some("model"))
        case Right(_) => fail(s"model=$m must be refused")
    }
  }

  test("refusal: stream=true (SSE is out of this face's scope)") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson,
      "stream" -> true.asJson
    )
    OpenAiChatShim.toLlmRequest(body) match
      case Left(r) =>
        assertEquals(r.code, "unsupported_stream")
        assertEquals(r.param, Some("stream"))
      case Right(_) => fail("stream=true must be refused, not silently dropped")
  }

  test("refusal: tools / tool_choice / legacy functions") {
    val base = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    val cases = List(
      Json.obj("tools" -> Json.arr(Json.obj("type" -> "function".asJson))),
      Json.obj("tool_choice" -> "auto".asJson),
      Json.obj("functions" -> Json.arr(Json.obj("name" -> "f".asJson))),
      Json.obj("function_call" -> "auto".asJson)
    )
    cases.foreach { extra =>
      OpenAiChatShim.toLlmRequest(base.deepMerge(extra)) match
        case Left(r) => assertEquals(r.code, "unsupported_tools", s"extra=$extra")
        case Right(_) => fail(s"$extra must be refused, not silently dropped")
    }
  }

  test("refusal: n > 1 and response_format / logprobs") {
    val base = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(Json.obj("role" -> "user".asJson, "content" -> "x".asJson)),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    val n2 = base.deepMerge(Json.obj("n" -> 2.asJson))
    OpenAiChatShim.toLlmRequest(n2) match
      case Left(r) => assertEquals(r.code, "unsupported_n")
      case Right(_) => fail("n=2 must be refused")

    List(
      Json.obj("response_format" -> Json.obj("type" -> "json_object".asJson)),
      Json.obj("logprobs" -> true.asJson)
    ).foreach { extra =>
      assert(OpenAiChatShim.toLlmRequest(base.deepMerge(extra)).isLeft, s"$extra must be refused")
    }
  }

  test("refusal: role=tool and assistant tool_calls (no in-tree carrier on this face)") {
    val base = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    val toolRole = base.deepMerge(
      Json.obj(
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "tool".asJson,
            "content" -> "result".asJson,
            "tool_call_id" -> "call_1".asJson
          )
        )
      )
    )
    OpenAiChatShim.toLlmRequest(toolRole) match
      case Left(r) =>
        assertEquals(r.code, "invalid_role")
        assertEquals(r.param, Some("messages[0].role"))
      case Right(_) => fail("role=tool must be refused")

    val assistantCalls = base.deepMerge(
      Json.obj(
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "assistant".asJson,
            "content" -> Json.Null,
            "tool_calls" -> Json.arr(Json.obj("id" -> "call_1".asJson, "type" -> "function".asJson))
          )
        )
      )
    )
    OpenAiChatShim.toLlmRequest(assistantCalls) match
      case Left(r) => assertEquals(r.code, "unsupported_tool_roundtrip")
      case Right(_) => fail("assistant tool_calls must be refused")
  }

  test("refusal: remote image URL is not fetched (only inline data: URLs are carried)") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(
        Json.obj(
          "role" -> "user".asJson,
          "content" -> Json.arr(
            Json.obj(
              "type" -> "image_url".asJson,
              "image_url" -> Json.obj("url" -> "https://example.com/a.png".asJson)
            )
          )
        )
      ),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    OpenAiChatShim.toLlmRequest(body) match
      case Left(r) => assertEquals(r.code, "unsupported_image_url")
      case Right(_) => fail("a remote image URL must be refused, not silently dropped")
  }

  test("refusal: unknown content part type") {
    val body = Json.obj(
      "model" -> "paid/paid-model".asJson,
      "messages" -> Json.arr(
        Json.obj(
          "role" -> "user".asJson,
          "content" -> Json.arr(Json.obj("type" -> "input_audio".asJson))
        )
      ),
      "session_id" -> "s".asJson,
      "agent_id" -> "a".asJson
    )
    OpenAiChatShim.toLlmRequest(body) match
      case Left(r) => assertEquals(r.code, "invalid_part")
      case Right(_) => fail("an unknown part type must be refused")
  }

  test("refusal: error envelope is OpenAI-shaped and carries the field pointer") {
    val r = OpenAiChatShim.Rejected(Some("messages"), "invalid_messages", "bad")
    val env = OpenAiChatShim.errorEnvelope(r).hcursor
    assertEquals(env.downField("error").downField("type").as[String], Right("invalid_request_error"))
    assertEquals(env.downField("error").downField("param").as[String], Right("messages"))
    assertEquals(env.downField("error").downField("code").as[String], Right("invalid_messages"))
    assert(env.downField("error").downField("message").as[String].isRight)
  }

  // ── 2. response mapping ──────────────────────────────────────────────────

  test("response: LlmResponse maps to choices[] with the serving model and usage") {
    val out = OpenAiChatShim.toOpenAiResponse(mkResponse(reply = "hello there")).hcursor
    assertEquals(out.downField("object").as[String], Right("chat.completion"))
    assertEquals(out.downField("model").as[String], Right("paid/paid-model"))
    assertEquals(out.downField("choices").downArray.downField("index").as[Int], Right(0))
    assertEquals(
      out.downField("choices").downArray.downField("message").downField("role").as[String],
      Right("assistant")
    )
    assertEquals(
      out.downField("choices").downArray.downField("message").downField("content").as[String],
      Right("hello there")
    )
    assertEquals(
      out.downField("choices").downArray.downField("finish_reason").as[String],
      Right("stop")
    )
    assertEquals(out.downField("usage").downField("prompt_tokens").as[Int], Right(7))
    assertEquals(out.downField("usage").downField("completion_tokens").as[Int], Right(3))
    assertEquals(out.downField("usage").downField("total_tokens").as[Int], Right(10))
    assertEquals(
      out.downField("usage").downField("prompt_tokens_details").downField("cached_tokens").as[Int],
      Right(2)
    )
    assert(out.downField("id").as[String].exists(_.startsWith("chatcmpl-")), "an id must be minted")
  }

  test("response: tool calls render as function tool_calls with a JSON-string arguments") {
    val resp = mkResponse(reply = "").copy(
      toolCalls = List(
        ToolCall("call_1", "read_file", io.circe.JsonObject("path" -> Json.fromString("/tmp/x")))
      )
    )
    val out = OpenAiChatShim.toOpenAiResponse(resp).hcursor
    val choice = out.downField("choices").downArray
    assertEquals(choice.downField("finish_reason").as[String], Right("tool_calls"))
    val call = choice.downField("message").downField("tool_calls").downArray
    assertEquals(call.downField("id").as[String], Right("call_1"))
    assertEquals(call.downField("type").as[String], Right("function"))
    assertEquals(call.downField("function").downField("name").as[String], Right("read_file"))
    assertEquals(
      call.downField("function").downField("arguments").as[String],
      Right("""{"path":"/tmp/x"}""")
    )
    // no text content alongside tool calls ⇒ null, per the OpenAI shape
    assert(
      choice.downField("message").downField("content").focus.contains(Json.Null),
      "content must be null when only tool calls were produced"
    )
  }

  test("response: absent usage omits the field instead of inventing zeros") {
    val json = OpenAiChatShim.toOpenAiResponse(mkResponse().copy(usage = None))
    assert(json.hcursor.downField("usage").focus.isEmpty, "usage must be absent when unknown")
  }

  // ── 3. the alias route: same routes value, same auth gate ────────────────

  test("route: alias path requires auth exactly like the in-tree paths") {
    val routes = new ChatRoutes(new ScriptedLlm(mkResponse()), TokenRef).routes
    val body =
      """{"model":"paid/paid-model","messages":[{"role":"user","content":"x"}],"session_id":"s","agent_id":"a"}"""
    List("/v1/chat", "/v1/chat/completions")
      .traverse { path =>
        routes(
          Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)
        ).value.map(resp => (path, resp.map(_.status)))
      }
      .map { got =>
        got.foreach {
          case (path, Some(status)) => assertEquals(status, Status.Unauthorized, s"path=$path")
          case (path, None) => fail(s"path=$path fell through the route table")
        }
      }
  }

  test("route: with a valid token the alias answers 200 in the OpenAI shape") {
    val llm = new ScriptedLlm(mkResponse(reply = "pong"))
    val routes = new ChatRoutes(llm, TokenRef).routes
    val body =
      """{"model":"paid/paid-model","messages":[{"role":"user","content":"ping"}],"session_id":"s","agent_id":"a"}"""
    routes(req(body)).value.flatMap {
      case None => fail("the alias route fell through")
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        respJson(resp).map { json =>
          val c = json.hcursor
          assertEquals(c.downField("object").as[String], Right("chat.completion"))
          assertEquals(
            c.downField("choices").downArray.downField("message").downField("content").as[String],
            Right("pong")
          )
        }
    }
  }

  test("route: the alias hands the caller's model to the LLM handle (end to end)") {
    // Round-1 verdict的线上读数之一：三个不同 `model` 值在 mock 侧收到的完全相同。
    // 这条断言把「线上」那一读搬进 spec——handle 收到的 agentModel 必须随 wire 上的
    // `model` 变化，否则返工未落实。
    val llm = new ScriptedLlm(mkResponse(reply = "pong"))
    val routes = new ChatRoutes(llm, TokenRef).routes
    def call(m: String) =
      routes(
        req(
          s"""{"model":"$m","messages":[{"role":"user","content":"ping"}],"session_id":"s","agent_id":"a"}"""
        )
      ).value.flatMap {
        case Some(resp) if resp.status == Status.Ok => IO.unit
        case other => IO.raiseError(new AssertionError(s"unexpected route outcome: $other"))
      }
    for
      _ <- call("paid/paid-model")
      _ <- call("other/other-model")
      seen <- llm.seen.get
    yield assertEquals(
      seen.reverse.map(_.agentModel),
      List(
        Some(AgentModelConfig(preferred = Some("paid/paid-model"), fallbacks = Nil)),
        Some(AgentModelConfig(preferred = Some("other/other-model"), fallbacks = Nil))
      ),
      "the two distinct wire models must reach the handle as two distinct carrier values"
    )
  }

  test("route: a refused body answers 400 with the OpenAI error envelope") {
    val routes = new ChatRoutes(new ScriptedLlm(mkResponse()), TokenRef).routes
    val body = """{"model":"paid/paid-model","messages":[{"role":"user","content":"x"}]}"""
    routes(req(body)).value.flatMap {
      case None => fail("the alias route fell through")
      case Some(resp) =>
        assertEquals(resp.status, Status.BadRequest)
        respJson(resp).map { json =>
          val c = json.hcursor
          assertEquals(c.downField("error").downField("param").as[String], Right("session_id"))
          assertEquals(c.downField("error").downField("code").as[String], Right("missing_identity"))
        }
    }
  }

  test("route: a non-JSON body answers 400 with the invalid_json envelope") {
    val routes = new ChatRoutes(new ScriptedLlm(mkResponse()), TokenRef).routes
    routes(req("not json at all")).value.flatMap {
      case None => fail("the alias route fell through")
      case Some(resp) =>
        assertEquals(resp.status, Status.BadRequest)
        respJson(resp).map { json =>
          assertEquals(json.hcursor.downField("error").downField("code").as[String], Right("invalid_json"))
        }
    }
  }

  test("route: the in-tree path keeps its own semantics (no OpenAI keys added)") {
    val routes = new ChatRoutes(new ScriptedLlm(mkResponse(reply = "pong")), TokenRef).routes
    // Note the role of this body in the test: it exercises the IN-TREE decode
    // path (`req.as[LlmRequest]`), which requires `searchAllowed` to be present
    // on the wire (the derived decoder has no default for it — pre-existing
    // behaviour, unchanged by this batch; the alias shim does not go through
    // this decoder).
    val body =
      """{"messages":[{"role":"user","content":"ping"}],"sessionId":"s","agentId":"a","searchAllowed":true}"""
    val inTree = rawJsonRequest("/v1/chat", body)
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, Token)))
    routes(inTree).value.flatMap {
      case None => fail("/v1/chat fell through")
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        respJson(resp).map { json =>
          val c = json.hcursor
          assertEquals(c.downField("reply").as[String], Right("pong"))
          assert(c.downField("choices").focus.isEmpty, "/v1/chat must not gain an OpenAI choices field")
        }
    }
  }

  test("route: an unknown path under /v1/chat is still a fall-through (404)") {
    val routes = new ChatRoutes(new ScriptedLlm(mkResponse()), TokenRef).routes
    routes(req("{}").withUri(Uri.unsafeFromString("/v1/chat/other"))).value.map {
      case None => ()
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
    }
  }
end OpenAiChatShimSpec
