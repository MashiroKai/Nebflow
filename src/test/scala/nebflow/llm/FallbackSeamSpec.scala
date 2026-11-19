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
 *
 * **fallback 废除批改写（作者令 2026-11-06 20:36「不再支持模型fallback」，逐条申报）**：
 *   runOneTurn 链深恒 1（`fallbacks = Nil`，b/m1 不再被链引用）+ 8s 有界上界取终局
 *   （全灭闸预算 ProbeIntervalSec=120s；哨兵以 Left 捕获，不使 IO 失败）。seam-guard
 *   用例（stall）本身 ~4s 内以 StreamInactivityTimeout 终局（locked=true 整流失败面
 *   与链深无关），断言原样钉死；「first-token 仍 fallback 到 b」用例**重写**为单候选
 *   静默等待面（软回避 + 不 markDown + b 零命中，「not over-block」核心保留）。原断言
 *   逐字内容见 git 历史。
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

  /**
   * mode: "stall" (start + 2 deltas, flush, sleep, abrupt close — mid-stream
   * timeout AFTER content was emitted, locked=true)
   * / "ok" (full well-behaved stream)
   * / "never" (park BEFORE response headers — cancellable, first-token timeout,
   * locked=false).
   */
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
          end match
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
            models = List(ModelConfig("m1"))
          ),
          "b" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$ok",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1"))
          )
        )
      )
    )

  private def runOneTurn(
    config: NebflowServiceConfig,
    sid: String
  ): IO[(Either[Throwable, List[StreamChunk]], ProviderHealthMonitor)] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      result <- LlmInterface
        .createLlm(sessionOverrides, None, Some(configRef))
        .flatMap { case (handle, _, monitor, release) =>
          val req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = sid,
            agentId = "seam-spec-agent",
            // fallback 废除批（作者令 2026-11-06 20:36）：链深恒 1（b/m1 不再被链引用）
            agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = Nil))
          )
          // fallback 废除批申报：单候选链穷尽后 = 静默等待恢复面（全灭闸，预算
          // ProbeIntervalSec=120s）——给 8s 有界上界取终局；哨兵以 Left 捕获（不使
          // IO 失败）。seam-guard 用例 ~4s 内自带终局，此界不参与其判定。
          handle
            .sendStream(req)
            .compile
            .toList
            .attempt
            .timeoutTo(
              8.seconds,
              IO.pure(
                Left(new RuntimeException("all-down gate exceeded test bound")): Either[Throwable, List[StreamChunk]]
              )
            )
            .map(_ -> monitor)
            .guarantee(release)
        }
    yield result

  test(
    "seam guard: mid-stream timeout AFTER emitted content fails the whole stream — no provider switch, no stitched output"
  ) {
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
        (result, _) <- runOneTurn(mkConfig(PortStall, PortOk), "seam-stall")
        elapsed <- IO.monotonic.map(_ - t0)
        _ <- IO.blocking { serverA.stop(0); serverB.stop(0) }.attempt.void
      yield (result, elapsed)
      val (result, elapsed) = program.unsafeRunSync()
      result match
        case Left(e: nebflow.shared.StreamInactivityTimeout) =>
          // Flow-node supervision P1: phase-2 stalls now raise the dedicated
          // Transient type (seam guard behavior unchanged — whole stream fails,
          // no provider switch after emitted content).
          assert(e.getMessage.contains("inactive"), s"inactivity message expected, got: ${e.getMessage}")
        case Right(chunks) =>
          val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
          fail(
            s"STREAM STITCHED — fallback ran after emitted content: chunks=$chunks text='$text' " +
              s"(provider b hits=${hitsB.get()})"
          )
        case other => fail(s"expected Left(StreamInactivityTimeout), got $other")
      // The fallback provider was never contacted — the seam guard suppressed the switch.
      assertEquals(hitsB.get(), 0, "provider b must NOT be tried after partial content was emitted")
      assert(elapsed < 20.seconds, s"stream resolved at ${elapsed.toMillis}ms — watchdog must bound it")
    finally
      LlmInterface.streamInactivityOverride = prev
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
    end try
  }

  // ============================================================
  // 重写（fallback 废除批）：first-token 超时（locked=false）⇒ 软回避 a，
  // 单候选终局 = 有界静默等待；「falls back to b」交付面退役（b 零命中）。
  // 「not over-block」核心保留：超时不驱逐（不 markDown）、avoid 有记录。
  // ============================================================
  test(
    "seam guard does not over-block (重写): first-token timeout with NO emitted content soft-avoids — 单候选有界静默等待；b 零命中（fallback 废除）"
  ) {
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
        (result, monitor) <- runOneTurn(mkConfig(PortNever, PortOk), "seam-never")
        states <- monitor.getStates
        avoids <- monitor.getAvoidUntil
        _ <- IO.blocking { serverA.stop(0); serverB.stop(0) }.attempt.void
      yield (result, states, avoids)
      val (result, states, avoids) = program.unsafeRunSync()
      result match
        case Right(chunks) =>
          val text = chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString
          fail(s"唯一候选超时 ⇒ 不得有根交付（b 已随 fallback 退役），实际成功 = '$text'")
        case Left(_) => () // 终局 = 单候选静默等待（全灭闸/测试上界哨兵）
      // 「not over-block」核心（软回避面，与链深无关）：
      assert(
        !states.contains("a/m1"),
        s"first-token timeout must NOT mark the provider Down (慢≠死), got ${states.get("a/m1")}"
      )
      assert(avoids.contains("a/m1"), "avoid deadline must be recorded for a/m1")
      // fallback 废除断言：b 活着但零命中（不暗中承接）
      assertEquals(hitsB.get(), 0, "provider b must NOT be contacted (fallback abolished)")
    finally
      LlmInterface.streamInactivityOverride = prev
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
    end try
  }

end FallbackSeamSpec
