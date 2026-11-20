package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * 修 2026-10-21「模型供应商恢复后，面板仍显示不可用很久不回绿」腿② spec：
 * **调用成功即清除**（事件驱动，零新定时器）。
 *
 * 病理（修前）：Down 标记只有一条清除路——探测成功（HealthMonitor.probe）。
 * 真实调用成功不写任何信号：本请求入场时的健康过滤快照早于并发请求的失败
 * 判定，provider 在飞行途中被 markDown 后，即使本请求在它身上赢下整个回复，
 * 它也保持 Down 到下一轮探测（120s 周期 + 探测时长）。面板读面
 * （/api/agents/:name/model 的 `current` = filterCandidates 现读；
 * /api/health `providers` = getStates 现读）随之后滞——后端不清，面板永不回绿。
 *
 * 修后断言：
 *   T1 流面：winner 时刻（首个内容 chunk）markUp —— 中途写入的 Down 在成功
 *      时点清除。
 *   T2 非流面：send 成功返回时 markUp —— 同一事件语义。
 *   变异验红：去掉两处 markUp 接线（interface.scala winner evalTap /
 *   send 的 markUp binding）→ 两测各自红（b/m1 滞留 Down）。
 */
class CallSuccessRecoverySpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  // ── mock：首个字节延迟落盘的 Anthropic 面（流式 SSE / 非流 JSON）─────────

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

  private val anthropicJson =
    """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],""" +
      """"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}"""

  /**
   * 200-OK mock that parks `delayMs` before the FIRST byte (the winner moment
   * of the real path), then answers the streaming or the non-streaming face.
   */
  private def startMock(streaming: Boolean, delayMs: Long): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          Thread.sleep(delayMs)
          if streaming then
            val body = (messageStart + textDelta("BB") + okTail).getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.getResponseBody
            os.write(body)
            os.flush()
            os.close()
          else
            val resp = anthropicJson.getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.length.toLong)
            exchange.getResponseBody.write(resp)
          exchange.close()
      )
      server.start()
      server
    }

  private def mkConfig(baseUrl: String): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "b" -> ProviderConfig(
            baseUrl = baseUrl,
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1"))
          )
        )
      )
    )

  private def isDown(states: Map[String, HealthState], ref: String): Boolean =
    states.get(ref).exists {
      case HealthState.Down(_, _) => true
      case _ => false
    }

  // ============================================================
  // T1 — streaming: the winner clears a Down marker written mid-flight
  // ============================================================

  test("T1: streaming winner clears a mid-flight Down marker at success time") {
    var serverB: HttpServer = null
    try
      val program = for
        b <- startMock(streaming = true, delayMs = 400)
        _ = serverB = b
        result <- (for
          configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(s"http://127.0.0.1:${b.getAddress.getPort}"))
          sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
          (handle, _, monitor, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "callwin-stream-spec",
            agentId = "callwin-stream-spec",
            agentModel = Some(AgentModelConfig(preferred = Some("b/m1"), fallbacks = Nil))
          )
          fib <- (handle.sendStream(req).compile.toList: IO[List[StreamChunk]]).start
          // In flight (the provider parks 400ms before its first byte); the
          // entry filter snapshot predates this verdict — exactly the
          // concurrent-markdown race the fix covers.
          _ <- IO.sleep(100.millis)
          _ <- monitor.markDown("b", "m1", "concurrent failure verdict (mid-flight)")
          midFlight <- monitor.getStates
          chunks <- fib.join.flatMap {
            case Outcome.Succeeded(fo) => fo
            case Outcome.Errored(e) => IO.raiseError[List[StreamChunk]](e)
            case Outcome.Canceled() =>
              IO.raiseError[List[StreamChunk]](new IllegalStateException("request fiber canceled"))
          }
          statesAfter <- monitor.getStates
          _ <- release
        yield (chunks, midFlight, statesAfter)).guarantee(IO.blocking(serverB.stop(0)))
      yield result
      val (chunks, midFlight, statesAfter): (List[StreamChunk], Map[String, HealthState], Map[String, HealthState]) =
        program.unsafeRunSync()
      // The request actually WON on b (winner moment fired). Errors surface
      // through join's Errored arm as a thrown exception.
      val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
      assertEquals(text, "BB", s"winner output must be provider b's answer, got '$text'")
      // Setup check: the marker WAS down mid-flight (the test is not vacuous).
      assert(isDown(midFlight, "b/m1"), s"mid-flight snapshot must show b/m1 Down, got ${midFlight.get("b/m1")}")
      // Core assertion (修后): cleared AT the win — not at the next probe cycle.
      // (markUp records an explicit Up entry on transition; the assertion is
      // "no Down marker survives the win", matching the read faces.)
      assert(
        !isDown(statesAfter, "b/m1"),
        s"the win must clear the mid-flight Down marker (调用成功即清除), got ${statesAfter.get("b/m1")}"
      )
    finally if serverB != null then serverB.stop(0)
    end try
  }

  // ============================================================
  // T2 — non-streaming: the successful reply clears the same marker
  // ============================================================

  test("T2: non-streaming send clears a mid-flight Down marker at success time") {
    var serverB: HttpServer = null
    try
      val program = for
        b <- startMock(streaming = false, delayMs = 300)
        _ = serverB = b
        result <- (for
          configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(s"http://127.0.0.1:${b.getAddress.getPort}"))
          sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
          (handle, _, monitor, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "callwin-send-spec",
            agentId = "callwin-send-spec",
            agentModel = Some(AgentModelConfig(preferred = Some("b/m1"), fallbacks = Nil))
          )
          fib <- handle.send(req).start
          _ <- IO.sleep(50.millis)
          _ <- monitor.markDown("b", "m1", "concurrent failure verdict (mid-flight)")
          midFlight <- monitor.getStates
          outcome <- fib.join
          reply <- outcome match
            case Outcome.Succeeded(fo) => fo
            case Outcome.Errored(e) => IO.raiseError(e)
            case Outcome.Canceled() => IO.raiseError(new IllegalStateException("request fiber canceled"))
          statesAfter <- monitor.getStates
          _ <- release
        yield (reply, midFlight, statesAfter)).guarantee(IO.blocking(serverB.stop(0)))
      yield result
      val (reply, midFlight, statesAfter) = program.unsafeRunSync()
      // The reply actually came from b.
      assertEquals(reply.meta.providerId, "b", s"reply must come from provider b, got ${reply.meta.providerId}")
      // Setup check: marked Down mid-flight.
      assert(isDown(midFlight, "b/m1"), s"mid-flight snapshot must show b/m1 Down, got ${midFlight.get("b/m1")}")
      // Core assertion: cleared at success time.
      assert(
        !isDown(statesAfter, "b/m1"),
        s"the successful send must clear the mid-flight Down marker, got ${statesAfter.get("b/m1")}"
      )
    finally if serverB != null then serverB.stop(0)
    end try
  }

end CallSuccessRecoverySpec
