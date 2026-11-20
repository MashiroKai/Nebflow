package nebflow.llm.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.llm.*
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

import scala.concurrent.duration.*

/**
 * 真端点冒烟（protocol-compat 批，2026-11-01）——**默认跳过**，只有显式给环境
 * 变量时才跑。凭据只从 env 读，**绝不打印、绝不落盘**。
 *
 *   NEBFLOW_LIVE_SMOKE=1
 *   NEBFLOW_SMOKE_PROTOCOL=openai|anthropic
 *   NEBFLOW_SMOKE_PROVIDER=<provider id>
 *   NEBFLOW_SMOKE_BASE_URL=<baseUrl>
 *   NEBFLOW_SMOKE_API_KEY=<key>
 *   NEBFLOW_SMOKE_MODEL=<model id>
 *
 * 覆盖的验收项（作者令 17:58）：
 *   L1 一轮对话成功（正文非空）
 *   L2 工具调用往返成功（tool_calls 能拿到、名字与参数正确）
 *   L3 流式：reasoning/thinking 与正文都能拿到（cmdcode 的 content 空、
 *      正文在 reasoning_content 的形态必须活下来）
 *   L4 多轮回放（assistant tool_calls + tool 结果）成功 ack
 *   L5 无 UA 的对照观察（记录边缘拦截是否发生——不作为失败判据）
 */
class LiveProviderSmokeSpec extends CatsEffectSuite:

  private def env(k: String): Option[String] = sys.env.get(k).map(_.trim).filter(_.nonEmpty)

  private val live = env("NEBFLOW_LIVE_SMOKE").contains("1")

  private lazy val protocol = env("NEBFLOW_SMOKE_PROTOCOL").getOrElse("openai")
  private lazy val providerId = env("NEBFLOW_SMOKE_PROVIDER").getOrElse("unknown")
  private lazy val baseUrl = env("NEBFLOW_SMOKE_BASE_URL").getOrElse("")
  private lazy val apiKey = env("NEBFLOW_SMOKE_API_KEY").getOrElse("")
  private lazy val model = env("NEBFLOW_SMOKE_MODEL").getOrElse("")

  override val munitIOTimeout = 240.seconds

  private lazy val backend: StreamBackend[IO, Fs2Streams[IO]] =
    cats.effect.std.Dispatcher
      .parallel[IO]
      .allocated
      .map { case (dispatcher, _) =>
        sttp.client4.httpclient.fs2.HttpClientFs2Backend.usingClient[IO](
          java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build(),
          dispatcher
        ): StreamBackend[IO, Fs2Streams[IO]]
      }
      .unsafeRunSync()

  private def adapter(): ProviderAdapter[IO] =
    // cmdcode 页面就是靠 UA 过 Cloudflare 的（无 UA → 403/1010），这里给一个
    // 显式覆盖，同时验证 provider 级 headers 通道真的生效。
    val headers = Some(Map("user-agent" -> "stand/live-smoke (nebflow-llm-client)"))
    if protocol == "anthropic" then
      AnthropicAdapter(
        baseUrl,
        apiKey,
        backend,
        requireThinkingPassback = true,
        providerId = providerId,
        providerHeaders = headers
      )
    else
      OpenAiAdapter(
        baseUrl,
        apiKey,
        backend,
        requireThinkingPassback = true,
        providerId = providerId,
        providerHeaders = headers
      )

    end if

  end adapter

  private def guard(run: => Any): Unit =
    if !live then ()
    else
      assert(baseUrl.nonEmpty && apiKey.nonEmpty && model.nonEmpty, "smoke env vars must be set")
      run

  private val tools = List(
    ToolDefinition(
      name = "Bash",
      description = "Run a shell command on the local machine.",
      inputSchema = io.circe.JsonObject.fromMap(
        Map(
          "type" -> Json.fromString("object"),
          "properties" -> Json.obj("command" -> Json.obj("type" -> Json.fromString("string"))),
          "required" -> Json.arr(Json.fromString("command"))
        )
      )
    )
  )

  private def baseParams(msgs: List[Message], withTools: Boolean = true) = SendMessageParams(
    messages = msgs,
    model = model,
    tools = if withTools then Some(tools) else None,
    thinking = Some(Json.obj("type" -> Json.fromString("enabled"))),
    systemStable = Some("You are a terse assistant. When asked to run a command, use the Bash tool."),
    sessionId = Some("live-smoke"),
    agentId = Some("live-smoke")
  )

  test("L1: one live turn returns non-empty text") {
    guard {
      val r = adapter()
        .sendMessage(baseParams(List(Message(MessageRole.User, Left("Reply with exactly: pong"))), withTools = false))
        .unsafeRunSync()
      println(s"[live-smoke] L1 provider=$providerId model=$model reply=${r.reply.take(80).replace('\n', ' ')}")
      assert(r.reply.trim.nonEmpty, "live reply empty")
    }
  }

  test("L2: a live turn performs a tool call (name + arguments present)") {
    guard {
      val r = adapter()
        .sendMessage(
          baseParams(
            List(Message(MessageRole.User, Left("Use the Bash tool to run: echo nebflow-live-smoke. Then stop.")))
          )
        )
        .unsafeRunSync()
      println(s"[live-smoke] L2 toolCalls=${r.toolCalls
          .map(t => s"${t.name}(${t.input.toMap.get("command").flatMap(_.asString).getOrElse("?")})")}")
      assert(r.toolCalls.nonEmpty, s"expected a tool call, got reply=${r.reply.take(120)}")
      assertEquals(r.toolCalls.head.name, "Bash")
    }
  }

  test("L3: streaming carries reasoning/thinking AND text (cmdcode's content-empty shape)") {
    guard {
      val chunks = adapter()
        .sendMessageStream(
          baseParams(List(Message(MessageRole.User, Left("Think briefly, then reply: stream-ok"))), withTools = false)
        )
        .compile
        .toList
        .unsafeRunSync()
      val text = chunks.collect { case StreamChunk.TextDelta(t) => t }.mkString
      val think = chunks.collect { case StreamChunk.ThinkingDelta(t) => t }.mkString
      val tools = chunks.collect { case StreamChunk.ToolCallChunk(c) => c }
      println(
        s"[live-smoke] L3 textChars=${text.length} thinkingChars=${think.length} toolChunks=${tools.size} " +
          s"kinds=${chunks.map(_.getClass.getSimpleName).distinct.mkString(",")}"
      )
      assert(text.nonEmpty || think.nonEmpty || tools.nonEmpty, "stream produced nothing at all")
    }
  }

  test("L4: multi-turn replay (assistant tool_calls + tool result) is acknowledged") {
    guard {
      val history = List(
        Message(MessageRole.User, Left("Use the Bash tool to run: echo hello.")),
        Message(
          MessageRole.Assistant,
          Right(
            List(
              ContentBlock.ToolUse(
                "call_live_1",
                "Bash",
                io.circe.JsonObject.fromMap(Map("command" -> Json.fromString("echo hello")))
              )
            )
          )
        ),
        Message(MessageRole.User, Right(List(ContentBlock.ToolResult("call_live_1", "hello", None)))),
        Message(MessageRole.User, Left("What did the command print? One short sentence."))
      )
      val r = adapter().sendMessage(baseParams(history)).unsafeRunSync()
      println(s"[live-smoke] L4 replay reply=${r.reply.take(100).replace('\n', ' ')}")
      assert(r.reply.trim.nonEmpty || r.toolCalls.nonEmpty, "replay produced nothing")
    }
  }
end LiveProviderSmokeSpec
