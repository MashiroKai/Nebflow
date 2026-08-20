package nebflow.llm.providers

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.llm.SendMessageParams
import nebflow.shared.*
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{GenericRequest, Response, StreamBackend}

import scala.jdk.CollectionConverters.*

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
    val result = adapter.toOpenAiMessages(
      List(
        Message(MessageRole.System, Left("sys")),
        Message(MessageRole.User, Left("hi"))
      )
    )
    assertEquals(result.size, 1)
  }

  test("toOpenAiMessages: tool_use -> tool_calls array, content=null") {
    val result = adapter.toOpenAiMessages(
      List(
        Message(
          MessageRole.Assistant,
          Right(
            List(
              ContentBlock.ToolUse("call_1", "Read", JsonObject("file_path" -> "/test.txt".asJson))
            )
          )
        )
      )
    )
    assertEquals(result.size, 1)
    val m = result.head
    assertEquals(m.hcursor.downField("role").as[String].toOption, Some("assistant"))
    assert(m.hcursor.downField("content").as[Option[String]].toOption.flatten.isEmpty)
    assertEquals(m.hcursor.downField("tool_calls").as[List[Json]].toOption.map(_.size), Some(1))
  }

  test("toOpenAiMessages: text + tool_use") {
    val result = adapter.toOpenAiMessages(
      List(
        Message(
          MessageRole.Assistant,
          Right(
            List(
              ContentBlock.Text("Reading file."),
              ContentBlock.ToolUse("call_1", "Read", JsonObject("file_path" -> "/t.txt".asJson))
            )
          )
        )
      )
    )
    assertEquals(result.size, 1)
    assertEquals(result.head.hcursor.downField("content").as[String].toOption, Some("Reading file."))
  }

  test("toOpenAiMessages: CRITICAL - multiple tool_results => SEPARATE tool messages") {
    val result = adapter.toOpenAiMessages(
      List(
        Message(
          MessageRole.User,
          Right(
            List(
              ContentBlock.ToolResult("c1", "content A"),
              ContentBlock.ToolResult("c2", "content B"),
              ContentBlock.ToolResult("c3", "content C")
            )
          )
        )
      )
    )
    assertEquals(result.size, 3, s"Must produce 3 separate tool messages, got ${result.size}")
    val ids = result.map(_.hcursor.downField("tool_call_id").as[String].toOption.getOrElse(""))
    assertEquals(ids, List("c1", "c2", "c3"))
    val contents = result.map(_.hcursor.downField("content").as[String].toOption.getOrElse(""))
    assertEquals(contents, List("content A", "content B", "content C"))
  }

  test("toOpenAiMessages: full multi-turn conversation") {
    val result = adapter.toOpenAiMessages(
      List(
        Message(MessageRole.User, Left("Read the file")),
        Message(
          MessageRole.Assistant,
          Right(
            List(
              ContentBlock.ToolUse("call_abc", "Read", JsonObject("file_path" -> "/test.txt".asJson))
            )
          )
        ),
        Message(
          MessageRole.User,
          Right(
            List(
              ContentBlock.ToolResult("call_abc", "file contents")
            )
          )
        ),
        Message(MessageRole.Assistant, Right(List(ContentBlock.Text("Done."))))
      )
    )
    assertEquals(result.size, 4)
    val roles = result.map(_.hcursor.downField("role").as[String].toOption.getOrElse(""))
    assertEquals(roles, List("user", "assistant", "tool", "assistant"))
  }

  // ====== buildSystemMessage ======

  test("buildSystemMessage: combines stable + dynamic") {
    val params =
      SendMessageParams(Nil, "gpt-4o", systemStable = Some("Be helpful."), systemDynamic = Some("Time: 12:00"))
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

  test("extractToolCalls: unquoted ISO-8601 literal in arguments is rescued (issue #18)") {
    val args = """{"content":"report","triggerAt":2026-08-18T00:00:00+08:00}"""
    val json = parse(s"""{"choices":[{"message":{"tool_calls":[
      {"id":"call_1","type":"function","function":{"name":"Schedule","arguments":"${args.replace("\\", "\\\\").replace("\"", "\\\"")}"}}
    ]}}]}""").toOption.get
    val tcs = adapter.extractToolCalls(json)
    assertEquals(tcs.size, 1)
    assertEquals(tcs.head.input("triggerAt").flatMap(_.asString), Some("2026-08-18T00:00:00+08:00"))
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
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val data =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"Read","arguments":""}}]},"finish_reason":null}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for
      chunks <- adapter.processOpenAiData(data, state, params)
      fs <- state.get
    yield
      assertEquals(chunks.size, 1)
      assert(chunks.head.isInstanceOf[StreamChunk.ToolCallStart])
      assertEquals(chunks.head.asInstanceOf[StreamChunk.ToolCallStart].name, "Read")
      assert(fs.contains(0))
      assertEquals(fs(0).id, "call_abc")
  }

  test("processOpenAiData: CRITICAL - empty delta + finish_reason=tool_calls flushes state") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](
      Map(0 -> ToolCallEntry("call_abc", "Read", "{\"file_path\":\"/test.txt\"}"))
    )
    val data = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for
      chunks <- adapter.processOpenAiData(data, state, params)
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
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](
      Map(0 -> ToolCallEntry("call_1", "Read", "{\"file_path\":"))
    )
    val data =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"/t.txt\"}"}}]},"finish_reason":"tool_calls"}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      val hasTC = chunks.exists(_.isInstanceOf[StreamChunk.ToolCallChunk])
      assert(hasTC, "Expected ToolCallChunk")
  }

  test("processOpenAiData: usage-only chunk") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
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
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val data = """{"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      assertEquals(chunks.size, 1)
      assertEquals(chunks.head.asInstanceOf[StreamChunk.TextDelta].delta, "Hello")
  }

  test("processOpenAiData: reasoning_content => ThinkingDelta") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val data = """{"choices":[{"index":0,"delta":{"reasoning_content":"thinking..."},"finish_reason":null}]}"""
    val params = SendMessageParams(Nil, "gpt-4o")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      assertEquals(chunks.size, 1)
      assertEquals(chunks.head.asInstanceOf[StreamChunk.ThinkingDelta].delta, "thinking...")
  }

  // ====== Streaming: issue #18 regressions ======

  test("processOpenAiData: unquoted ISO arguments rescued at finish flush (issue #18)") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](
      Map(0 -> ToolCallEntry("call_1", "Schedule", """{"content":"x","triggerAt":2026-08-18T00:00:00+08:00}"""))
    )
    val data = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
    val params = SendMessageParams(Nil, "glm-5.3")
    for chunks <- adapter.processOpenAiData(data, state, params)
    yield
      val tc = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }.head
      assertEquals(tc.input("triggerAt").flatMap(_.asString), Some("2026-08-18T00:00:00+08:00"))
      assert(tc.input(ToolInputJson.RawArgsKey).isEmpty)
  }

  test("processOpenAiData: providers repeating id+name per fragment accumulate all fragments") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val params = SendMessageParams(Nil, "gpt-4o")
    val frag1 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Schedule","arguments":"{\"content\":\"x\","}}]},"finish_reason":null}]}"""
    val frag2 = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Schedule","arguments":"\"triggerAt\":\"in 2 hours\"}"}}]},"finish_reason":null}]}"""
    val finish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
    for
      _ <- adapter.processOpenAiData(frag1, state, params)
      _ <- adapter.processOpenAiData(frag2, state, params)
      chunks <- adapter.processOpenAiData(finish, state, params)
    yield
      val tc = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }.head
      assertEquals(tc.input("content").flatMap(_.asString), Some("x"))
      assertEquals(tc.input("triggerAt").flatMap(_.asString), Some("in 2 hours"))
  }

  // ====== hasReasoningContent (thinking-only non-streaming responses) ======

  test("hasReasoningContent: empty content + reasoning_content is NOT an empty response") {
    val json = parse(
      """{"choices":[{"message":{"content":null,"reasoning_content":"deep thinking..."},"finish_reason":"length"}]}"""
    ).toOption.get
    assert(adapter.hasReasoningContent(json), "reasoning_content should mark the response as non-empty")
  }

  test("hasReasoningContent: empty content + thinking field is NOT an empty response") {
    val json = parse(
      """{"choices":[{"message":{"content":"","thinking":"reasoning trace"},"finish_reason":"length"}]}"""
    ).toOption.get
    assert(adapter.hasReasoningContent(json), "thinking field should mark the response as non-empty")
  }

  test("hasReasoningContent: whitespace-only reasoning counts as empty") {
    val json = parse(
      """{"choices":[{"message":{"content":"","reasoning_content":"   "},"finish_reason":"length"}]}"""
    ).toOption.get
    assert(!adapter.hasReasoningContent(json), "blank reasoning should not rescue an empty response")
  }

  test("hasReasoningContent: truly empty response (no content, no reasoning)") {
    val json = parse("""{"choices":[{"message":{"content":null},"finish_reason":"length"}]}""").toOption.get
    assert(!adapter.hasReasoningContent(json), "no reasoning field means empty response")
  }

  // ====== Streaming: qwen degenerate-fragment guard (2026-08-20 incident) ======
  // Production evidence: DashScope compatible-mode occasionally emits tool-call
  // fragments whose id AND name are literal empty strings while arguments stay
  // valid. Before the guard these accumulated into ToolCall(name=""), which the
  // agent's allowed-tool whitelist dropped as "Tool not available: <empty>" —
  // every tool appeared broken and agents retried in a storm.

  test("processOpenAiData: degenerate qwen start (id=\"\" and name=\"\"\") never creates a tool call") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val params = SendMessageParams(Nil, "qwen3.8-max")
    // Exact production frame shape: empty id + empty name + valid arguments
    val frag1 =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"name":"","arguments":"{\"command\":\"echo test\",\"description\":\"probe\"}"}}]},"finish_reason":null}]}"""
    // Continuation frames (id="", name absent) must not resurrect the call
    val frag2 =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"arguments":""}}]},"finish_reason":null}]}"""
    val finish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""

    // Anti-regression nail for the dead-logging defect (qa 2026-08-20): the
    // orphan warn is the ONLY capture point for DashScope frames that arrive
    // with no valid start (not reproducible locally) — pure chunk assertions
    // cannot tell whether the log line was built-but-discarded. Attach a
    // list-appender and require the WARN to actually fire.
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.llm.openai").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]

    IO
      .delay {
        appender.start()
        lbLogger.addAppender(appender)
      }
      .bracket { _ =>
        for
          c1 <- adapter.processOpenAiData(frag1, state, params)
          c2 <- adapter.processOpenAiData(frag2, state, params)
          c3 <- adapter.processOpenAiData(finish, state, params)
          fs <- state.get
        yield
          // No empty-name ToolCallStart leaked from the degenerate start
          assert(c1.collect { case StreamChunk.ToolCallStart(n) => n }.forall(_.nonEmpty),
            s"degenerate start must not emit ToolCallStart, got $c1")
          // Flush emitted no degenerate ToolCallChunk at all — the call is dropped
          val toolChunks = (c1 ++ c2 ++ c3).collect { case StreamChunk.ToolCallChunk(tc) => tc }
          assert(toolChunks.isEmpty, s"expected no ToolCallChunk from degenerate fragments, got $toolChunks")
          // Stream still terminates cleanly
          assert((c1 ++ c2 ++ c3).exists(_.isInstanceOf[StreamChunk.Done]), "finish must emit Done")
          assert(fs.isEmpty, "state must stay empty")
          // The diagnostic WARN FIRED (not built-and-discarded). With no valid
          // start these frames are ORPHANS — the immediate per-frame path that
          // per-call aggregation cannot cover (0 occurrences in production).
          val warns = appender.list.asScala.filter(_.getLevel == ch.qos.logback.classic.Level.WARN).toList
          assert(
            warns.exists(_.getFormattedMessage.contains("orphan empty-id/name continuation frame dropped")),
            s"expected an orphan WARN log event, got ${warns.map(_.getFormattedMessage)}"
          )
          // No per-call summary without a flushed call; retired wording must not return
          assert(!warns.exists(_.getFormattedMessage.contains("merged into tool")),
            s"no summary WARN expected without a flushed call, got ${warns.map(_.getFormattedMessage)}")
          assert(!warns.exists(_.getFormattedMessage.contains("degenerate tool-call fragment")),
            s"retired per-frame wording must not reappear, got ${warns.map(_.getFormattedMessage)}")
      } { _ => IO.delay(lbLogger.detachAppender(appender)) }
  }

  test("processOpenAiData: late degenerate fragment does NOT clobber a valid started call") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](
      Map(0 -> ToolCallEntry("call_1", "Bash", "{\"command\":\"echo hi\""))
    )
    val params = SendMessageParams(Nil, "qwen3.8-max")
    // Under the pre-guard code this (Some(""),Some("")) fragment hit the
    // replace branch and DESTROYED the valid ("call_1","Bash",…) entry,
    // replacing it with ("","",…) — the whole call then dropped at flush.
    val degenerate =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"name":"","arguments":"}"}}]},"finish_reason":null}]}"""
    val finish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
    for
      _ <- adapter.processOpenAiData(degenerate, state, params)
      fc <- adapter.processOpenAiData(finish, state, params)
    yield
      val tc = fc.collect { case StreamChunk.ToolCallChunk(tc) => tc }.head
      assertEquals(tc.id, "call_1")
      assertEquals(tc.name, "Bash")
      assertEquals(tc.input("command").flatMap(_.asString), Some("echo hi"))
  }

  test("extractToolCalls: non-streaming response with empty-name tool call is dropped") {
    val json = parse("""{"choices":[{"message":{"tool_calls":[
      {"id":"call_1","type":"function","function":{"name":"","arguments":"{\"command\":\"ls\"}"}},
      {"id":"call_2","type":"function","function":{"name":"Grep","arguments":"{}"}}
    ]}}]}""").toOption.get
    val tcs = adapter.extractToolCalls(json)
    assertEquals(tcs.map(_.name), List("Grep"), "empty-name call must be dropped, valid call kept")
  }

  // ====== Streaming: empty-id/name WARN aggregation (2026-08-21 flood) ======
  // qwen streams its argument continuation frames with literal "" id/name —
  // normal wire shape, ~26 frames per tool call. The per-frame WARN flooded
  // logs (peak 1038 lines/min). Aggregation contract: count per call, ONE
  // summary WARN at the finish flush with frame count / arg volume / merge
  // health / session-agent correlation / first raw samples; orphan frames
  // (no valid start) still log immediately.

  test("processOpenAiData: empty-id/name frames aggregate into ONE summary WARN per call at flush") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val params =
      SendMessageParams(Nil, "qwen3.8-max", sessionId = Some("sess-agg-1"), agentId = Some("visual-reviewer"))
    // Valid start frame (real id+name), then five empty-id/name continuation
    // frames carrying the argument stream. 5 frames > 3-sample cap also pins
    // the raw-sample limit.
    val start =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_agg1","type":"function","function":{"name":"Read","arguments":""}}]},"finish_reason":null}]}"""
    val parts = List("""{"command":"ec""", """ho hel""", """lo","fl""", """ag":tr""", """ue}""")
    def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val conts = parts.map { p =>
      s"""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"name":"","arguments":"${esc(p)}"}}]},"finish_reason":null}]}"""
    }
    val finish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""

    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.llm.openai").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]

    IO
      .delay {
        appender.start()
        lbLogger.addAppender(appender)
      }
      .bracket { _ =>
        for
          _ <- adapter.processOpenAiData(start, state, params)
          _ <- conts.traverse_(c => adapter.processOpenAiData(c, state, params))
          fin <- adapter.processOpenAiData(finish, state, params)
          fs <- state.get
        yield
          // Merge semantics preserved (5e109ef8): empty-id/name frames still
          // continue the valid call — flushed ToolCallChunk carries full args.
          val tcs = fin.collect { case StreamChunk.ToolCallChunk(tc) => tc }
          assertEquals(tcs.map(_.name), List("Read"))
          assertEquals(tcs.head.input("command").flatMap(_.asString), Some("echo hello"))
          assertEquals(tcs.head.input("flag").flatMap(_.asBoolean), Some(true))
          assert(fin.exists(_.isInstanceOf[StreamChunk.Done]), "finish must emit Done")
          assert(fs.isEmpty, "state must be cleared after flush")
          // ONE aggregated WARN — not one per frame (the flood defect)
          val warns = appender.list.asScala.filter(_.getLevel == ch.qos.logback.classic.Level.WARN).toList
          assertEquals(warns.size, 1, s"expected exactly 1 summary WARN, got: ${warns.map(_.getFormattedMessage)}")
          val msg = warns.head.getFormattedMessage
          assert(msg.contains("empty-id/name continuation frames merged into tool Read"), s"missing tool name: $msg")
          assert(msg.contains("5 frames"), s"missing frame count: $msg")
          assert(msg.contains(s"${parts.map(_.length).sum} arg chars"), s"missing arg char volume: $msg")
          assert(msg.contains("parsed OK"), s"missing merge health: $msg")
          assert(msg.contains("session sess-agg-1"), s"missing session correlation: $msg")
          assert(msg.contains("agent visual-reviewer"), s"missing agent correlation: $msg")
          assert(msg.contains("first frames:"), s"missing raw samples: $msg")
          // 5 frames but only first 3 samples kept => exactly 2 " | " separators
          assertEquals(msg.count(_ == '|'), 2, s"expected 3 raw samples (2 separators), got: $msg")
      } { _ => IO.delay(lbLogger.detachAppender(appender)) }
  }

  test("processOpenAiData: compliant continuation frames (id/name absent) stay silent — no WARN") {
    val state = Ref.unsafe[IO, Map[Int, ToolCallEntry]](Map.empty)
    val params = SendMessageParams(Nil, "gpt-4o")
    // Standard OpenAI shape: start carries id+name, continuation omits both.
    val start =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_q1","type":"function","function":{"name":"Read","arguments":""}}]},"finish_reason":null}]}"""
    val cont =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\":1}"}}]},"finish_reason":null}]}"""
    val finish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""

    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.llm.openai").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]

    IO
      .delay {
        appender.start()
        lbLogger.addAppender(appender)
      }
      .bracket { _ =>
        for
          _ <- adapter.processOpenAiData(start, state, params)
          _ <- adapter.processOpenAiData(cont, state, params)
          fin <- adapter.processOpenAiData(finish, state, params)
        yield
          val tcs = fin.collect { case StreamChunk.ToolCallChunk(tc) => tc }
          assertEquals(tcs.map(_.name), List("Read"))
          assertEquals(tcs.head.input("a").flatMap(_.as[Int].toOption), Some(1))
          val warns = appender.list.asScala.filter(_.getLevel == ch.qos.logback.classic.Level.WARN).toList
          assert(warns.isEmpty, s"compliant frames must not warn, got: ${warns.map(_.getFormattedMessage)}")
      } { _ => IO.delay(lbLogger.detachAppender(appender)) }
  }

  // ====== sendMessageStream end-to-end: production frame replay ======
  // Drives the full stream plumbing (SSE line parse -> fragment merge ->
  // finish flush -> onFinalize) with the exact qwen wire shapes captured in
  // production: start frame carries real id+name, argument continuation
  // frames carry literal "" id/name (nebflow.log WARN captures, 2026-08-21).

  /** Minimal in-memory StreamBackend serving a canned SSE body. */
  private class CannedSseBackend(body: fs2.Stream[IO, Byte]) extends StreamBackend[IO, Fs2Streams[IO]]:
    def send[T](
        request: GenericRequest[T, Fs2Streams[IO] & sttp.capabilities.Effect[IO]]
    ): IO[Response[T]] =
      IO.pure(
        Response(
          Right(body).asInstanceOf[T],
          sttp.model.StatusCode.Ok,
          "",
          Nil
        )
      )
    def monad: sttp.monad.MonadError[IO] =
      new sttp.client4.impl.cats.CatsMonadError[IO](using IO.asyncForIO)
    def close(): IO[Unit] = IO.unit

  private def sseLines(frames: String*): fs2.Stream[IO, Byte] =
    fs2.Stream
      .emits(frames.map(f => s"data: $f\n\n"))
      .through(fs2.text.utf8.encode)

  // Real production sequence: valid start, then name:"" continuations.
  private val prodStart =
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_r1","type":"function","function":{"name":"Bash","arguments":""}}]},"finish_reason":null}]}"""
  private def prodCont(args: String) =
    s"""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"name":"","arguments":"$args"}}]},"finish_reason":null}]}"""
  private val prodFinish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""

  private def withAppender[A](f: => IO[A]): IO[(A, List[String])] =
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.llm.openai").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    IO
      .delay {
        appender.start()
        lbLogger.addAppender(appender)
      }
      .bracket { _ =>
        f.map(a =>
          a -> appender.list.asScala.toList
            .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
            .map(_.getFormattedMessage)
        )
      } { _ => IO.delay(lbLogger.detachAppender(appender)) }

  test("sendMessageStream: production qwen frame replay aggregates to ONE summary and merges args") {
    val backend = new CannedSseBackend(
      sseLines(
        prodStart,
        prodCont("""{\"command\":\"ec"""),
        prodCont("ho hel"),
        prodCont("""lo\"}"""),
        prodFinish,
        "[DONE]"
      )
    )
    val adapter = new OpenAiAdapter("https://api.example.com/v1", "k", backend)
    val params = SendMessageParams(Nil, "qwen3.8-max", sessionId = Some("sess-e2e"), agentId = Some("vr"))
    for (chunks, warns) <- withAppender(adapter.sendMessageStream(params).compile.toList)
    yield
      val tcs = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
      assertEquals(tcs.map(_.name), List("Bash"))
      assertEquals(tcs.head.input("command").flatMap(_.asString), Some("echo hello"))
      assert(chunks.exists(_.isInstanceOf[StreamChunk.Done]), "stream must terminate with Done")
      assertEquals(warns.size, 1, s"expected exactly 1 summary WARN, got: $warns")
      val msg = warns.head
      assert(msg.contains("merged into tool Bash"), msg)
      assert(msg.contains("3 frames"), msg)
      assert(msg.contains("session sess-e2e"), msg)
      assert(msg.contains("agent vr"), msg)
  }

  test("sendMessageStream: stream dies mid-args — onFinalize flushes the counted summary") {
    // Transport error mid-tool-call: the finish flush never runs; the
    // onFinalize path must still emit the aggregated summary (日志完整性优先).
    val backend = new CannedSseBackend(
      sseLines(prodStart, prodCont("""{\"command\":\"ec"""), prodCont("ho ")) ++
        fs2.Stream.raiseError[IO](new RuntimeException("connection reset mid-args"))
    )
    val adapter = new OpenAiAdapter("https://api.example.com/v1", "k", backend)
    val params = SendMessageParams(Nil, "qwen3.8-max", sessionId = Some("sess-e2e-err"), agentId = Some("vr"))
    val lbLogger =
      org.slf4j.LoggerFactory.getLogger("nebflow.llm.openai").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    IO
      .delay {
        appender.start()
        lbLogger.addAppender(appender)
      }
      .bracket { _ =>
        adapter.sendMessageStream(params).compile.toList.attempt.map { result =>
          assert(result.isLeft, s"stream error must propagate, got $result")
          val warns = appender.list.asScala.toList
            .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
            .map(_.getFormattedMessage)
          assertEquals(warns.size, 1, s"onFinalize must flush exactly 1 summary, got: $warns")
          assert(warns.head.contains("2 frames"), warns.head)
          assert(warns.head.contains("session sess-e2e-err"), warns.head)
          assert(warns.head.contains("not strict JSON"), warns.head) // args incomplete: merge health reflects it
        }
      } { _ => IO.delay(lbLogger.detachAppender(appender)) }
  }

end OpenAiAdapterSpec
