package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * 审计 20260903 子项②——400 Format/重放类失败不参与驱逐（probe 形状取舍面）：
 *
 * 审计实锤（flap 风暴，BJ 09-03 00:59–01:06，7 分钟 35 次）：deepseek thinking
 * 模式要求历史回传 thinking 块 → 跨 provider 会话缺块 → 400 → markDown →
 * 探测「hi」空历史永远成功（p50 0.9s 打回 UP）→ 下一个 fallback 再 400 →
 * DOWN/UP 循环，防线空转。markDown 时记录的失败指纹（thinking 回传类 400）
 * 是「请求形状 vs 该 provider API 契约」问题——provider 秒回 400 恰证明它活着。
 * 探测请求形状对齐真实请求不可行（失败根源在会话历史形状，探测无历史），
 * 故取审计建议方向：**400 Format 类失败不参与驱逐判定**，只降级本次请求换
 * 下一 provider。Auth/404/配额仍驱逐（确证死亡，T3/T4 回归）。
 *
 * 变异验红：classifyError 400 分支还原 evict=true → T1 红（a/m1 变 Down）。
 */
class FormatErrorNoEvictSpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  private val Port400 = 18565
  private val Port401 = 18567
  private val PortOk = 18569

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
  private def textDelta(t: String) =
    s"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$t\"}}\n\n"
  private val okTail =
    Seq(
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  private val deepseek400Body =
    """{"type":"error","error":{"type":"invalid_request_error","message":"The content[].thinking in the thinking mode must be passed back to the API"}}"""

  private def startMock(port: Int, mode: String, hits: java.util.concurrent.atomic.AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          mode match
            case "400" =>
              val body = deepseek400Body.getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "application/json")
              exchange.sendResponseHeaders(400, body.length.toLong)
              exchange.getResponseBody.write(body)
              exchange.close()
            case "401" =>
              val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid api-key"}}""".getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "application/json")
              exchange.sendResponseHeaders(401, body.length.toLong)
              exchange.getResponseBody.write(body)
              exchange.close()
            case _ =>
              val body = (messageStart + textDelta("BB") + okTail).getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
              exchange.sendResponseHeaders(200, 0)
              val os = exchange.getResponseBody
              os.write(body)
              os.flush()
              os.close()
              exchange.close()
      )
      server.start()
      server
    }

  private def mkConfig(failPort: Int, failMode: String, ok: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$failPort",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false)))
          ),
          "b" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$ok",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false)))
          )
        )
      )
    )

  private def runTurn(
      config: NebflowServiceConfig
  ): IO[(Either[Throwable, List[StreamChunk]], Map[String, HealthState], Int)] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      (handle, _, monitor, release) = triple
      req = LlmRequest(
        messages = List(Message(MessageRole.User, Left("hi"))),
        sessionId = "format-e2e",
        agentId = "format-spec-agent",
        agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = List("b/m1")))
      )
      chunks <- handle.sendStream(req).compile.toList.attempt
      states <- monitor.getStates
      _ <- release
    yield (chunks, states, 0)

  test("T1: 400 (thinking-passback shape) does NOT evict the provider — request skips to next") {
    val hitsA = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsB = new java.util.concurrent.atomic.AtomicInteger(0)
    var serverA: HttpServer = null
    var serverB: HttpServer = null
    try
      val program = for
        a <- startMock(Port400, "400", hitsA)
        _ = serverA = a
        b <- startMock(PortOk, "ok", hitsB)
        _ = serverB = b
        result <- runTurn(mkConfig(Port400, "400", PortOk))
      yield result
      val (chunks, states, _) = program.guarantee(IO.blocking { serverA.stop(0); serverB.stop(0) }).unsafeRunSync()
      chunks match
        case Right(list) =>
          val text = list.collect { case StreamChunk.TextDelta(d) => d }.mkString
          assertEquals(text, "BB", s"fallback must serve b's answer, got '$text'")
        case Left(e) => fail(s"400 with evict=false must skip to next provider, got $e")
      // 核心断言（旧行为：a/m1 Down("...thinking must be passed back...")）：
      assert(
        !states.contains("a/m1"),
        s"400 Format must NOT evict the provider (audit #2), got ${states.get("a/m1")}"
      )
      assertEquals(hitsA.get(), 1, "Permanent-class 400 skips without same-provider retry (1 hit)")
    finally
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
  }

  test("T2: classifyError unit — 400 Format evict=false; context-overflow 400 stays Fatal/evict=true") {
    val format = Fallback.classifyError(
      sttp.client4.HttpError(deepseek400Body, sttp.model.StatusCode(400))
    )
    assertEquals(format.reason, FailoverReason.Format)
    assertEquals(format.permanence, ErrorPermanence.Permanent)
    assertEquals(format.evict, false, "400 shape errors must not participate in eviction")

    val overflow = Fallback.classifyError(
      sttp.client4.HttpError(
        """{"error":{"message":"This model's maximum context length is 128000 tokens"}}""",
        sttp.model.StatusCode(400)
      )
    )
    assertEquals(overflow.permanence, ErrorPermanence.Fatal)
    assertEquals(overflow.evict, true, "context overflow aborts the whole chain — eviction stays")

    val auth = Fallback.classifyError(sttp.client4.HttpError("unauthorized", sttp.model.StatusCode(401)))
    assertEquals(auth.reason, FailoverReason.Auth)
    assertEquals(auth.evict, true, "Auth remains a confirmed-death eviction")

    val notFound = Fallback.classifyError(sttp.client4.HttpError("model gone", sttp.model.StatusCode(404)))
    assertEquals(notFound.reason, FailoverReason.ModelNotFound)
    assertEquals(notFound.evict, true, "404 remains a confirmed-death eviction")
  }

  test("T3-regression: 401 Auth still evicts via markDown (确证死亡不回归)") {
    val hitsA = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsB = new java.util.concurrent.atomic.AtomicInteger(0)
    var serverA: HttpServer = null
    var serverB: HttpServer = null
    try
      val program = for
        a <- startMock(Port401, "401", hitsA)
        _ = serverA = a
        b <- startMock(PortOk, "ok", hitsB)
        _ = serverB = b
        result <- runTurn(mkConfig(Port401, "401", PortOk))
      yield result
      val (chunks, states, _) = program.guarantee(IO.blocking { serverA.stop(0); serverB.stop(0) }).unsafeRunSync()
      chunks match
        case Right(list) =>
          val text = list.collect { case StreamChunk.TextDelta(d) => d }.mkString
          assertEquals(text, "BB")
        case Left(e) => fail(s"401 must fall back to b, got $e")
      // 旧行为保持：Auth → markDown。
      states.get("a/m1") match
        case Some(HealthState.Down(_, _)) => () // evicted as before
        case other => fail(s"401 must still mark the provider Down (confirmed death), got $other")
    finally
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
  }
end FormatErrorNoEvictSpec
