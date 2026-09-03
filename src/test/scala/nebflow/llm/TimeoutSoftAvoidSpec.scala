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
 * 审计 20260903 子项③——Timeout 类降级软下线（「慢 ≠ 死」）：
 *
 * 旧行为：首 token（phase-1 TimeoutException → Permanent/Timeout）与流间隙
 * （phase-2 StreamInactivityTimeout → Transient 但 isTimeout 恒真）超时
 * 全部 markDown → provider 移出所有并发候选链，恢复只能靠 120s 周期探测。
 * 审计实锤：markDown 40 次里 25 次是「provider 健康、失败=客户端重放形状」，
 * deepseek DOWN→probe→UP p50 仅 0.9s——被驱逐的 provider 根本没病。
 *
 * 新行为：超时只跳过本次请求 + 软回避窗（Defaults.TimeoutAvoidWindowMs=45s）
 * ——不写健康状态、不进探测集、窗口到期 filterCandidates 自然放回（all-Down
 * 门的 5s tick 兜底，无额外信号需求）。markDown 保留给 Auth/404/配额等确证
 * 死亡（T4 回归）。
 *
 * 缩窗模拟：watchdog 窗口经 streamInactivityOverride 注入（800ms 首token），
 * 软回避窗经 HealthMonitor 单元测试直接传参（300ms）——严禁真实等待 45s。
 * 变异验红：interface.scala 超时分支还原为 markDown → T1 红（a/m1 变 Down）。
 */
class TimeoutSoftAvoidSpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  // Distinct ports (FallbackSeam 18501/3/5, GateWedge 18341/2, NoProgress 18401/3).
  private val PortNever = 18561
  private val PortOk = 18563

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

  /** mode: "never"（park 在响应头前——首 token 超时，locked=false）
    * / "ok"（完整正常流）。 */
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
            case "never" =>
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

  private def mkConfig(never: Int, ok: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$never",
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

  // ============================================================
  // HealthMonitor 单元层：softAvoid 与 markDown 严格分层（缩窗模拟）
  // ============================================================

  private def candidate(pid: String, model: String) =
    ModelCandidate(pid, ProviderConfig("http://localhost", "k", LlmProtocol.OpenAI), model)

  test("T1u: softAvoid excludes the candidate from up WITHOUT marking it Down") {
    val monitor = ProviderHealthMonitor(null)
    val cs = List(candidate("glm", "glm-5"), candidate("kimi", "moonshot-v1"))

    for
      _ <- monitor.softAvoid("glm", "glm-5", windowMs = 60_000)
      (up, down) <- monitor.filterCandidates(cs)
      states <- monitor.getStates
      avoids <- monitor.getAvoidUntil
    yield
      assertEquals(up.map(_.providerId), List("kimi"), "avoided candidate must be skipped this request")
      assertEquals(down, List.empty[ModelCandidate], "soft-avoided must NOT enter the probe set (down)")
      assertEquals(states.get("glm/glm-5"), None, "soft-avoid must NOT write a Down health state")
      assert(avoids.contains("glm/glm-5"), "avoid deadline must be recorded")
  }

  test("T2u: avoided candidate returns to the candidate chain after the window expires") {
    val monitor = ProviderHealthMonitor(null)
    val cs = List(candidate("glm", "glm-5"))

    for
      _ <- monitor.softAvoid("glm", "glm-5", windowMs = 300.millis.toMillis)
      (upDuring, _) <- monitor.filterCandidates(cs)
      _ <- IO.sleep(400.millis)
      (upAfter, _) <- monitor.filterCandidates(cs)
    yield
      assertEquals(upDuring, List.empty[ModelCandidate], "within the window the candidate is skipped")
      assertEquals(upAfter.map(_.providerId), List("glm"), "after expiry it is eligible again — no probe needed")
  }

  test("T3u: softAvoid on an already-Down candidate keeps it Down (eviction is stronger)") {
    val monitor = ProviderHealthMonitor(null)
    val cs = List(candidate("glm", "glm-5"))

    for
      _ <- monitor.markDown("glm", "glm-5", "auth failed")
      _ <- monitor.softAvoid("glm", "glm-5", windowMs = 60_000)
      (upInWindow, downInWindow) <- monitor.filterCandidates(cs)
      states <- monitor.getStates
      // 窗口过期后：avoid 消失，Down 状态重新出现在 down（探测）集
      _ <- IO.sleep(100.millis) // 不可能过期（60s 窗口）——用短窗口走第二条断言
      monitor2 = ProviderHealthMonitor(null)
      _ <- monitor2.markDown("glm", "glm-5", "auth failed")
      _ <- monitor2.softAvoid("glm", "glm-5", windowMs = 50.millis.toMillis)
      _ <- IO.sleep(80.millis)
      (upExpired, downExpired) <- monitor2.filterCandidates(cs)
    yield
      // 窗口内：health Down 仍在（getStates 是驱逐语义载体），候选不进 up，
      // 也不进 down（不烧一个刚超时又被判死的 provider 的探测）
      states.get("glm/glm-5") match
        case Some(HealthState.Down(_, _)) => ()
        case other => fail(s"health Down must persist regardless of soft avoid, got $other")
      assertEquals(upInWindow, List.empty[ModelCandidate])
      assertEquals(downInWindow, List.empty[ModelCandidate])
      // 窗口过期：Down（驱逐）语义恢复主导——仍在 down（探测）集，等探测救活
      assertEquals(upExpired, List.empty[ModelCandidate])
      assertEquals(downExpired.map(_.providerId), List("glm"))
  }

  // ============================================================
  // interface E2E：首 token 超时 → 软下线（不驱逐）+ fallback 成功
  // ============================================================

  test("T1: first-token timeout soft-avoids provider a (NOT Down) and falls back to b") {
    val prev = LlmInterface.streamInactivityOverride
    // first-token 800ms < 120s header park；nothing emitted → locked=false.
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
        result <- (for
          configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(PortNever, PortOk))
          sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
          (handle, _, monitor, release) = triple
          req = LlmRequest(
            messages = List(Message(MessageRole.User, Left("hi"))),
            sessionId = "softavoid-e2e",
            agentId = "softavoid-spec-agent",
            agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = List("b/m1")))
          )
          chunks <- handle.sendStream(req).compile.toList.attempt
          statesAfter <- monitor.getStates
          (upA, downA) <- monitor.filterCandidates(
            List(ModelCandidate("a", ProviderConfig("http://x", "k", LlmProtocol.Anthropic), "m1"))
          )
          avoids <- monitor.getAvoidUntil
        yield (chunks, statesAfter, upA, downA, avoids)).guarantee(IO.blocking { serverA.stop(0); serverB.stop(0) })
      yield result
      val (chunks, statesAfter, upA, downA, avoids) = program.unsafeRunSync()
      // Fallback succeeded via b.
      chunks match
        case Right(list) =>
          val text = list.collect { case StreamChunk.TextDelta(d) => d }.mkString
          assertEquals(text, "BB", s"fallback output must be provider b's answer, got '$text'")
        case Left(e) => fail(s"timeout with locked=false must fall back and succeed, got $e")
      // 核心断言（旧行为：a/m1 会出现在 states 里 Down(...)）：
      assert(
        !statesAfter.contains("a/m1"),
        s"timeout must NOT mark the provider Down (audit #3 慢≠死), got ${statesAfter.get("a/m1")}"
      )
      // 软回避窗内：a 不在 up、也不在 down（不烧探测）。
      assertEquals(upA, List.empty[ModelCandidate], "within the avoid window provider a is skipped")
      assertEquals(downA, List.empty[ModelCandidate], "soft-avoided provider must not enter the probe set")
      assert(avoids.contains("a/m1"), "avoid deadline must be recorded for a/m1")
    finally
      LlmInterface.streamInactivityOverride = prev
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
  }

  test("T4-regression: Auth-class failure (401) still marks Down (确证死亡驱逐不回归)") {
    val monitor = ProviderHealthMonitor(null)
    // classifyError 结构化 401 → Auth/Permanent/evict=true → interface markDown。
    // 直接断言分类 + markDown 语义（401 E2E 见 FormatErrorNoEvictSpec T3 对比面）。
    val classification = Fallback.classifyError(sttp.client4.HttpError("unauthorized", sttp.model.StatusCode(401)))
    assertEquals(classification.reason, FailoverReason.Auth)
    assertEquals(classification.evict, true)
    val cs = List(candidate("glm", "glm-5"))
    for
      // interface 对 evict=true Permanent 的动作就是 markDown —— 语义保持：
      _ <- monitor.markDown("glm", "glm-5", "auth failed")
      (up, down) <- monitor.filterCandidates(cs)
    yield
      assertEquals(up, List.empty[ModelCandidate])
      assertEquals(down.map(_.providerId), List("glm"))
  }
end TimeoutSoftAvoidSpec
