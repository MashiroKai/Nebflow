package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * 时段闸端到端 spec（model-schedule-unavailable 批，2026-10-29）——作者实测令
 * 的对价（任务书「实测：把窗口设成「当前时刻 ±10 分钟」跑一次真实请求看是否
 * 绕过受限 provider」）：
 *
 * 复现台 = 真实 `LlmInterface.sendStream` + 真 Anthropic 协议 mock（QuotaChainSwitchSpec
 * 同台同形）。窗口在**运行时按当前时刻算**（now-10min .. now+10min）⇒ 受限方
 * 确定在窗内，且判据不依赖跑测时刻。
 *
 * 判据（三条）：
 *   T1 窗内 + 链 = [受限, 不受限] ⇒ 已运行 mock 的 **HTTP 命中数 = 0**（决定性
 *      读数：绕过而非「打了再失败」），答案由不受限方交付；
 *   T2 全受限 ⇒ <3s 快失败（不进 120s 全灭闸），错误消息带原因（窗口 + 受限清单，
 *      不静默）；
 *   T3 窗口设成窗外（now+2h）⇒ 受限方照常承接（零行为变化腿 / 负控：证明 T1 的
 *      绿来自时段闸而非别的过滤）。
 */
class ScheduleChainSwitchSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val PortRestricted = 18591
  private val PortOpen = 18593
  private val PortAllRestrictedA = 18595
  private val PortAllRestrictedB = 18597
  private val PortOutsideWindow = 18599

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

  private def startMock(port: Int, label: String, hits: AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          val body = (messageStart + textDelta(label) + okTail).getBytes(StandardCharsets.UTF_8)
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

  private def hhmm(instant: java.time.Instant): String =
    instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

  /** 「当前时刻 ±10 分钟」窗口（作者实测令形态）。 */
  private def windowAroundNow(): UnavailableWindow =
    UnavailableWindow(hhmm(java.time.Instant.now().minusSeconds(600)), hhmm(java.time.Instant.now().plusSeconds(600)))

  /** 「当前时刻 +2h 起」窗外窗口。 */
  private def windowStartingIn2h(): UnavailableWindow =
    UnavailableWindow(hhmm(java.time.Instant.now().plusSeconds(7200)), hhmm(java.time.Instant.now().plusSeconds(10800)))

  private def mkConfig(spec: (String, Int, List[String], List[UnavailableWindow])*): NebflowServiceConfig =
    NebflowServiceConfig(llm = ServiceLlmConfig(providers = spec.map { case (pid, port, models, windows) =>
      pid -> ProviderConfig(
        baseUrl = s"http://127.0.0.1:$port",
        apiKey = "test",
        protocol = LlmProtocol.Anthropic,
        models = models.map(m => ModelConfig(m)),
        unavailableWindows = if windows.isEmpty then None else Some(windows)
      )
    }.toMap))

  private def textOf(chunks: List[StreamChunk]): String =
    chunks.collect { case StreamChunk.TextDelta(d) => d }.mkString

  private def stopAll(servers: List[HttpServer]): IO[Unit] =
    IO.blocking(servers.filter(_ != null).foreach(_.stop(0)))

  // ============================================================
  // T1：窗内 ⇒ 受限 provider **零 HTTP 命中**，答案由未受限方交付
  // ============================================================

  test("T1 时段闸绕过：窗口 = 当前时刻±10 分钟 ⇒ 受限 provider 命中数 = 0，答案由未受限方交付") {
    val hitsRestricted = new AtomicInteger(0)
    val hitsOpen = new AtomicInteger(0)
    var s1: HttpServer = null
    var s2: HttpServer = null
    val win = windowAroundNow()
    val cfg = mkConfig(
      ("zhipu", PortRestricted, List("glm"), List(win)),
      ("cmdcode", PortOpen, List("deepseek-flash"), Nil)
    )
    val chain = AgentModelConfig(preferred = Some("zhipu/glm"), fallbacks = List("cmdcode/deepseek-flash"))
    val program =
      for
        a <- startMock(PortRestricted, "RESTRICTED-SHOULD-NOT-RUN", hitsRestricted)
        _ = s1 = a
        b <- startMock(PortOpen, "OPEN", hitsOpen)
        _ = s2 = b
        out <- (for
          cfgRef <- Ref.of[IO, NebflowServiceConfig](cfg)
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
          (handle, _, _, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "sched-gate-t1",
            agentId = "sched-gate-t1-agent",
            agentModel = Some(chain)
          )
          res <- handle.sendStream(req).compile.toList.attempt
          _ <- release
        yield res)
          .guarantee(stopAll(List(s1, s2)))
      yield out

    program.flatMap {
      case Right(chunks) =>
        IO {
          assertEquals(textOf(chunks), "OPEN", "必须由未受限的 cmdcode 交付答案")
          // 决定性读数：受限方连一个请求都不该收到（绕过，而非「打了再失败」）
          assertEquals(hitsRestricted.get(), 0, s"受限 provider 必须零命中（实际 ${hitsRestricted.get()} 次）")
          assert(hitsOpen.get() >= 1, s"未受限方必须承接（实际 ${hitsOpen.get()} 次）")
          assert(ModelAvailability.isUnavailable(List(win), System.currentTimeMillis()))
        }
      case Left(e) => IO(fail(s"窗内必须绕过受限 provider 并成功；实际失败 = ${e.getClass.getName}: ${e.getMessage}"))
    }
  }

  // ============================================================
  // T2：全受限 ⇒ 快失败 + 明确原因（不静默、不进 120s 全灭闸）
  // ============================================================

  test("T2 全受限：<3s 快失败（不进 120s 全灭闸）+ 错误带「受限」原因与窗口") {
    val hitsA = new AtomicInteger(0)
    val hitsB = new AtomicInteger(0)
    var s1: HttpServer = null
    var s2: HttpServer = null
    val win = windowAroundNow()
    val cfg = mkConfig(
      ("zhipu", PortAllRestrictedA, List("glm"), List(win)),
      ("kimi", PortAllRestrictedB, List("kimi-for-coding"), List(win))
    )
    val chain = AgentModelConfig(preferred = Some("zhipu/glm"), fallbacks = List("kimi/kimi-for-coding"))
    val program =
      for
        a <- startMock(PortAllRestrictedA, "A", hitsA)
        _ = s1 = a
        b <- startMock(PortAllRestrictedB, "B", hitsB)
        _ = s2 = b
        out <- (for
          cfgRef <- Ref.of[IO, NebflowServiceConfig](cfg)
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
          (handle, _, _, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "sched-gate-t2",
            agentId = "sched-gate-t2-agent",
            agentModel = Some(chain)
          )
          t0 <- IO.monotonic
          res <- handle.sendStream(req).compile.toList.attempt
          t1 <- IO.monotonic
          _ <- release
        yield (t1.toMillis - t0.toMillis, res))
          .guarantee(stopAll(List(s1, s2)))
      yield out

    program.flatMap { case (elapsedMs, res) =>
      IO {
        res match
          case Left(e: ModelScheduleRestrictedError) =>
            assert(e.getMessage.contains("受限"), s"错误消息必须说明「当前时段受限」：${e.getMessage}")
            assert(e.getMessage.contains("zhipu/glm"), s"错误必须列出受限候选：${e.getMessage}")
            assert(e.windowsDesc.nonEmpty, s"错误必须带窗口描述：${e.windowsDesc}")
          case Left(e) =>
            fail(s"全受限必须以 ModelScheduleRestrictedError 终局；实际 = ${e.getClass.getName}: ${e.getMessage}")
          case Right(_) => fail("全受限不得成功——不静默降级")
        assert(
          elapsedMs < 3_000L,
          s"全受限必须快失败（不进 120s 全灭闸等待）；实际 elapsed=${elapsedMs}ms"
        )
        assertEquals(hitsA.get() + hitsB.get(), 0, "全受限时两个受限 provider 都必须零命中")
      }
    }
  }

  // ============================================================
  // T3：窗外 ⇒ 受限方照常承接（负控：T1 的绿来自时段闸，不是别的过滤）
  // ============================================================

  test("T3 窗外负控：窗口 = 当前 +2h 起 ⇒ 同一根的 provider 照常承接（零行为变化）") {
    val hitsFirst = new AtomicInteger(0)
    val hitsSecond = new AtomicInteger(0)
    var s1: HttpServer = null
    var s2: HttpServer = null
    val win = windowStartingIn2h()
    val cfg = mkConfig(
      ("zhipu", PortOutsideWindow, List("glm"), List(win)),
      ("cmdcode", PortOpen, List("deepseek-flash"), Nil)
    )
    val chain = AgentModelConfig(preferred = Some("zhipu/glm"), fallbacks = List("cmdcode/deepseek-flash"))
    val program =
      for
        a <- startMock(PortOutsideWindow, "FIRST", hitsFirst)
        _ = s1 = a
        b <- startMock(PortOpen, "SECOND", hitsSecond)
        _ = s2 = b
        out <- (for
          cfgRef <- Ref.of[IO, NebflowServiceConfig](cfg)
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(overrides, None, Some(cfgRef))
          (handle, _, _, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "sched-gate-t3",
            agentId = "sched-gate-t3-agent",
            agentModel = Some(chain)
          )
          res <- handle.sendStream(req).compile.toList.attempt
          _ <- release
        yield res)
          .guarantee(stopAll(List(s1, s2)))
      yield out

    program.flatMap {
      case Right(chunks) =>
        IO {
          assertEquals(textOf(chunks), "FIRST", "窗外：受限方（链首）照常承接")
          assertEquals(hitsFirst.get(), 1, "窗外链首必须收到请求（负控）")
          assertEquals(hitsSecond.get(), 0, "窗外不得换链到第二根")
        }
      case Left(e) => IO(fail(s"窗外必须零回归地照常工作；实际失败 = ${e.getClass.getName}: ${e.getMessage}"))
    }
  }
end ScheduleChainSwitchSpec
