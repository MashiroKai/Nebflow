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
 * fallback 缝合守卫（2026-08-21，qa 随案发现 + qwen 批生产实证）:
 *
 * 已进入最终聚合的 chunk（TextDelta/ThinkingDelta/ToolCallChunk）被
 * compile.toList 逐个拉取下游，无法撤回。旧代码对 timeout 类错误开了特权
 * （reset locked → 继续换 provider），把下一个 provider 的完整输出缝合进
 * 同一条聚合——用户可见文本重复（生产实证：单流混 qwen tool_use 碎片 +
 * kimi end_turn/usage）。修复后 locked=true 时任何错误（含 timeout）都
 * 终止整条流；locked=false 时 timeout 照常 fallback。
 *
 * E2E 触发机制：per-provider inactivity watchdog（经
 * streamInactivityOverride 缩窗）在 body 停滞时 raise TimeoutException，
 * 其对 main fiber 的取消挂在对端 socket 关闭时落定（SendStreamNoProgressSpec
 * 边界注记的挂起形态 + 有限关闭 = 迟到但确定的浮现，生产 seam 同机制）。
 */
class FallbackSeamSpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  // Distinct ports from every other mock (GateWedge 18341/2, NoProgress 18401/3).
  private val PortStall = 18501
  private val PortOk = 18503
  private val PortNever = 18505

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

  /** mode: "stall" (start + 2 deltas, flush, sleep, abrupt close — mid-stream
    * timeout AFTER content was emitted, locked=true)
    * / "ok" (full well-behaved stream)
    * / "never" (park BEFORE response headers — cancellable, first-token timeout,
    * locked=false). */
  private def startMock(port: Int, mode: String, hits: java.util.concurrent.atomic.AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      // Multi-threaded executor: JDK HttpServer default serializes exchanges.
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes() // JDK requirement
          hits.incrementAndGet()
          mode match
            case "stall" =>
              val head = (messageStart + textDelta("AA") + textDelta("AB")).getBytes(StandardCharsets.UTF_8)
              exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
              exchange.sendResponseHeaders(200, 0) // chunked
              val os = exchange.getResponseBody
              os.write(head)
              os.flush()
              // Body stall: no bytes for 3s (> the 800ms test window), then
              // abrupt close — the watchdog's pending cancellation settles.
              Thread.sleep(3000)
              os.close()
              exchange.close()
            case "never" =>
              // Park BEFORE response headers: the client fiber waits on the
              // async response-header future — a CANCELLABLE park (proven by
              // SendStreamNoProgressSpec "never" mode).
              Thread.sleep(120_000)
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

  private def mkConfig(stall: Int, ok: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$stall",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false))),
            queuePersist = Some(false)
          ),
          "b" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$ok",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", maxTokens = 1024, vision = Some(false))),
            queuePersist = Some(false)
          )
        )
      )
    )

  private def runOneTurn(config: NebflowServiceConfig, sid: String): IO[Either[Throwable, List[StreamChunk]]] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      result <- LlmInterface
        .createLlm(sessionOverrides, None, Some(configRef))
        .flatMap { case (handle, _, _, release) =>
          val req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = sid,
            agentId = "seam-spec-agent",
            agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = List("b/m1")))
          )
          handle.sendStream(req).compile.toList.attempt.guarantee(release)
        }
    yield result

  test("seam guard: mid-stream timeout AFTER emitted content fails the whole stream — no provider switch, no stitched output") {
    val prev = LlmInterface.streamInactivityOverride
    // first-token generous (headers come fast); subsequent 800ms < 3s stall.
    LlmInterface.streamInactivityOverride = Some((10.seconds, 800.millis))
    val hitsA = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsB = new java.util.concurrent.atomic.AtomicInteger(0)
    var serverA: HttpServer = null
    var serverB: HttpServer = null
    try
      val program = for
        a <- startMock(PortStall, "stall", hitsA)
        _ = serverA = a
        b <- startMock(PortOk, "ok", hitsB)
        _ = serverB = b
        t0 <- IO.monotonic
        result <- runOneTurn(mkConfig(PortStall, PortOk), "seam-stall")
        elapsed <- IO.monotonic.map(_ - t0)
        _ <- IO.blocking { serverA.stop(0); serverB.stop(0) }.attempt.void
      yield (result, elapsed)
      val (result, elapsed) = program.unsafeRunSync()
      result match
        case Left(e: java.util.concurrent.TimeoutException) =>
          assert(e.getMessage.contains("inactive"), s"inactivity message expected, got: ${e.getMessage}")
        case Right(chunks) =>
          val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
          fail(
            s"STREAM STITCHED — fallback ran after emitted content: chunks=$chunks text='$text' " +
              s"(provider b hits=${hitsB.get()})"
          )
        case other => fail(s"expected Left(TimeoutException), got $other")
      // The fallback provider was never contacted — the seam guard suppressed the switch.
      assertEquals(hitsB.get(), 0, "provider b must NOT be tried after partial content was emitted")
      assert(elapsed < 20.seconds, s"stream resolved at ${elapsed.toMillis}ms — watchdog must bound it")
    finally
      LlmInterface.streamInactivityOverride = prev
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
  }

  test("seam guard does not over-block: first-token timeout with NO emitted content still falls back to the next provider") {
    val prev = LlmInterface.streamInactivityOverride
    // first-token 800ms < 120s header park; nothing emitted → locked=false.
    LlmInterface.streamInactivityOverride = Some((800.millis, 30.seconds))
    val hitsA = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsB = new java.util.concurrent.atomic.AtomicInteger(0)
    var serverA: HttpServer = null
    var serverB: HttpServer = null
    try
      val program = for
        a <- startMock(PortNever, "never", hitsA)
        _ = serverA = a
        b <- startMock(PortOk, "ok", hitsB)
        _ = serverB = b
        result <- runOneTurn(mkConfig(PortNever, PortOk), "seam-never")
        _ <- IO.blocking { serverA.stop(0); serverB.stop(0) }.attempt.void
      yield result
      val result = program.unsafeRunSync()
      result match
        case Right(chunks) =>
          val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
          assertEquals(text, "BB", s"fallback output must be provider b's complete answer, got '$text'")
          assert(chunks.exists(_.isInstanceOf[StreamChunk.Done]), s"stream must complete with Done, got $chunks")
        case Left(e) => fail(s"locked=false timeout must fall back and succeed, got $e")
      assertEquals(hitsB.get(), 1, "provider b must serve exactly the fallback attempt")
    finally
      LlmInterface.streamInactivityOverride = prev
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
  }

end FallbackSeamSpec
