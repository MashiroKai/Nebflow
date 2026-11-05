package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 5xx 归批回归（2026-11-05，作者令逐字：「修复一下 deepseek 500 的问题」+
 * 「5xx 不升级为冻结理由」）。
 *
 * 现场：冻结回执 `deepseek/deepseek-flash: ProviderError — statusCode: 550,
 * response:`（**响应体为空**）。550 不是标准码，`classifyBase` 的 `case _`
 * 兜底把它归 `ProviderError`，于是：
 *   - `llmFailureRetryable` 不认它 ⇒ 链穷尽被判「不可重试」；
 *   - `shouldErrorFreeze=false` ⇒ 直接 fatal ⇒ delegate `FROZEN (reason=llm-failure)`。
 *
 * 本 spec 钉死修复后的六条契约（作者令点名的 6 例）：
 *   T1 归类：500/502/503/504/550 ⇒ `server` 族、Transient、`evict=false`（单测）
 *   T2 全 5xx 链 ⇒ 可等待（`isUpstreamUnavailableExhaustion`），混合 403 ⇒ 不可等待
 *   T3 500 ⇒ 换 provider 服务、本家不被 markDown（冷却而非驱逐）
 *   T4 550 + **空响应体** ⇒ 同 T3（非标准码走同一族）
 *   T5 连续 3 次 5xx ⇒ 冷却窗 15s→30s→60s（指数）+ 冷却期内候选被剔出链
 *   T6 冷却后一次成功 ⇒ strikes 清零（半开试探恢复）
 *   T7 403 ⇒ 不重试、直接换、markDown（确证死亡不回归）
 *   T8 429 + 上游 code 1310（zhipu 周/月额度实测）⇒ 配额族，不落 5xx 冷却
 */
class UpstreamUnavailableSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  // 4xx/5xx 响应体的取证面会落盘（LlmLogWriter）——重定向到临时目录，
  // 绝不写生产 home（与 FormatErrorNoEvictSpec 同款纪律）。
  private var logTmp: java.nio.file.Path = null

  override def beforeAll(): Unit =
    logTmp = java.nio.file.Files.createTempDirectory("upstream5xx-log-")
    nebflow.core.LlmLogWriter.setLogDirForTest(logTmp)

  override def afterAll(): Unit =
    nebflow.core.LlmLogWriter.resetLogDirForTest()
    if logTmp != null && java.nio.file.Files.exists(logTmp) then
      java.nio.file.Files
        .walk(logTmp)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(java.nio.file.Files.deleteIfExists)

  private val Port500 = 18651
  private val Port550 = 18653
  private val Port403 = 18655
  private val Port429 = 18657
  private val PortOk = 18659

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

  private def startMock(port: Int, mode: String, hits: java.util.concurrent.atomic.AtomicInteger): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          def json(status: Int, body: String): Unit =
            val bytes = body.getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
            exchange.close()
          mode match
            case "500" => json(500, """{"type":"error","error":{"type":"api_error","message":"internal server error"}}""")
            // 现场形态：550 + **完全空响应体**（冻结回执里 `response:` 后面什么都没有）
            case "550" =>
              exchange.sendResponseHeaders(550, -1)
              exchange.close()
            case "403" => json(403, """{"type":"error","error":{"type":"permission_error","message":"5-hour usage limit"}}""")
            case "429-quota" =>
              json(
                429,
                """{"type":"error","error":{"type":"rate_limit_error","code":"1310","message":"您已达到每周/每月使用上限，您的限额将在 2026-11-07 23:32:09 重置。"}}"""
              )
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

  private def mkConfig(failPort: Int, okPort: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$failPort",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1"))
          ),
          "b" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$okPort",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1"))
          )
        )
      )
    )

  /** 一次真实流式 turn（a/m1 首选、b/m1 兜底）+ 健康态 + 冷却计数 + 软回避窗。 */
  private def runTurn(
    config: NebflowServiceConfig
  ): IO[(Either[Throwable, String], Map[String, HealthState], Map[String, Int], Map[String, Long])] =
    for
      configRef <- Ref.of[IO, NebflowServiceConfig](config)
      sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
      (handle, _, monitor, release) = triple
      req = LlmRequest(
        messages = List(Message(MessageRole.User, Left("hi"))),
        sessionId = "upstream-5xx",
        agentId = "upstream-5xx-agent",
        agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = List("b/m1")))
      )
      chunks <- handle
        .sendStream(req)
        .collect { case StreamChunk.TextDelta(d) => d }
        .compile
        .toList
        .attempt
      states <- monitor.getStates
      strikes <- monitor.getCooldownStrikes
      avoids <- monitor.getAvoidUntil
      _ <- release
    yield (chunks.map(_.mkString), states, strikes, avoids)

  private def withServers[A](modes: List[(Int, String)])(body: => IO[A]): IO[A] =
    val hits = modes.map(_ => new java.util.concurrent.atomic.AtomicInteger(0))
    val program = for
      servers <- modes.zip(hits).traverse { case ((port, mode), hit) => startMock(port, mode, hit) }
      a <- body.guarantee(IO.blocking(servers.foreach(_.stop(0))))
    yield (a, hits)
    program

  // ── T1 归类单元面 ────────────────────────────────────────────────────────
  test("T1: 500/502/503/504/550 ⇒ server 族 + Transient + evict=false（非标准码同一族）") {
    List(500, 502, 503, 504, 550).foreach { code =>
      val cls = Fallback.classifyError(sttp.client4.HttpError("", sttp.model.StatusCode(code)))
      assertEquals(cls.reason, FailoverReason.ServerError, s"$code must join the server family")
      assertEquals(cls.permanence, ErrorPermanence.Transient, s"$code must be retryable")
      assertEquals(cls.evict, false, s"$code must not drive markDown (cool-down instead)")
      assertEquals(cls.statusCode, Some(code))
    }
    // 边界：auth / model / format 的归类与驱逐一字不动
    val auth = Fallback.classifyError(sttp.client4.HttpError("unauthorized", sttp.model.StatusCode(401)))
    assertEquals(auth.reason, FailoverReason.Auth)
    assertEquals(auth.evict, true)
    val notFound = Fallback.classifyError(sttp.client4.HttpError("gone", sttp.model.StatusCode(404)))
    assertEquals(notFound.reason, FailoverReason.ModelNotFound)
    // stringly 面（无结构化 HttpError）：`statusCode: 550` 文本同样进 server 族
    val stringly = Fallback.classifyError(new RuntimeException("upstream said statusCode: 550, response:"))
    assertEquals(stringly.reason, FailoverReason.ServerError)
    assertEquals(stringly.permanence, ErrorPermanence.Transient)
    assertEquals(stringly.evict, false)
  }

  // ── T2 链形状判据 ────────────────────────────────────────────────────────
  test("T2: 全 5xx 链 ⇒ 可等待；含 403 的混合链 ⇒ 不可等待（拒绝等不来）") {
    def attempt(reason: FailoverReason, perm: ErrorPermanence) =
      FallbackAttempt("p", "m", Some(reason), Some(perm), 1L, 0, "now")
    val all5xx = FallbackExhaustedError(
      List(
        attempt(FailoverReason.ServerError, ErrorPermanence.Transient),
        attempt(FailoverReason.ServerError, ErrorPermanence.Transient)
      )
    )
    assert(Fallback.isUpstreamUnavailableExhaustion(all5xx), "all-5xx chain is a waitable condition")
    assert(Fallback.isWaitingRetryableExhaustion(all5xx), "the agent-level single entry must agree")
    val mixed = FallbackExhaustedError(
      List(
        attempt(FailoverReason.ServerError, ErrorPermanence.Transient),
        attempt(FailoverReason.Auth, ErrorPermanence.Permanent)
      )
    )
    assert(!Fallback.isUpstreamUnavailableExhaustion(mixed), "mixed [5xx, auth] must NOT wait")
    val timeoutAnd5xx = FallbackExhaustedError(
      List(
        attempt(FailoverReason.ServerError, ErrorPermanence.Transient),
        attempt(FailoverReason.Timeout, ErrorPermanence.Permanent)
      )
    )
    assert(!Fallback.isUpstreamUnavailableExhaustion(timeoutAnd5xx), "a Permanent timeout is not a 5xx wait")
    // 空链（无 attempt）不算可等待
    assert(!Fallback.isUpstreamUnavailableExhaustion(FallbackExhaustedError(Nil)))
    // agent 层重试面：全 5xx ⇒ retryable（flow 节点据此 Restart）
    assert(nebflow.agent.AgentActor.llmFailureRetryable(all5xx), "5xx exhaustion must be retryable at agent layer")
    assert(!nebflow.agent.AgentActor.llmFailureRetryable(mixed), "auth rejection stays non-retryable")
  }

  // ── T3 500 ⇒ 换 provider，不冻结（不 markDown） ──────────────────────────
  test("T3: 500 ⇒ 兜底 provider 服务、本家不进 Down（冷却窗替代驱逐）") {
    withServers(List(Port500 -> "500", PortOk -> "ok")) {
      runTurn(mkConfig(Port500, PortOk)).map { case (chunks, states, strikes, avoids) =>
        assertEquals(chunks, Right("BB"), "the next provider must serve the turn")
        assert(!states.contains("a/m1"), s"5xx must NOT markDown (frozen-agent root cause), got ${states.get("a/m1")}")
        assertEquals(strikes.get("a/m1"), Some(1), "exactly one 5xx strike recorded")
        assert(
          avoids.get("a/m1").exists(_ > System.currentTimeMillis()),
          "the provider must be cooled down (soft-avoid window is live)"
        )
      }
    }
  }

  // ── T4 550 + 空响应体 ⇒ 同族同处置 ──────────────────────────────────────
  test("T4: 550 + 空响应体（现场形态）⇒ 兜底 provider 服务、本家不进 Down") {
    withServers(List(Port550 -> "550", PortOk -> "ok")) {
      runTurn(mkConfig(Port550, PortOk)).map { case (chunks, states, strikes, avoids) =>
        assertEquals(chunks, Right("BB"), "550 with an empty body must fall through, not abort")
        assert(!states.contains("a/m1"), s"550 must NOT markDown, got ${states.get("a/m1")}")
        assertEquals(strikes.get("a/m1"), Some(1))
        assert(avoids.get("a/m1").exists(_ > System.currentTimeMillis()))
      }
    }
  }

  // ── T5 冷却指数退避 + 冷却期内跳过 ──────────────────────────────────────
  test("T5: 连续 3 次 5xx ⇒ 冷却窗 15s/30s/60s 指数增长，冷却期内候选被剔出链") {
    val configRef = Ref.unsafe[IO, NebflowServiceConfig](mkConfig(PortOk, PortOk))
    LlmInterface.createLlm(Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty), None, Some(configRef)).flatMap {
      case (_, registry, monitor, release) =>
        val candidate = ModelCandidate(
          "a",
          ProviderConfig(
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1"))
          ),
          "m1"
        )
        val program = for
          _ <- monitor.coolDown("a", "m1", "strike 1")
          s1 <- monitor.getCooldownStrikes
          w1 <- monitor.getAvoidUntil
          _ <- monitor.coolDown("a", "m1", "strike 2")
          s2 <- monitor.getCooldownStrikes
          w2 <- monitor.getAvoidUntil
          _ <- monitor.coolDown("a", "m1", "strike 3")
          s3 <- monitor.getCooldownStrikes
          w3 <- monitor.getAvoidUntil
          (up, down) <- monitor.filterCandidates(List(candidate))
          _ <- (1 to 5).toList.traverse_(_ => monitor.coolDown("a", "m1", "cap"))
          capped <- monitor.getCooldownStrikes
          _ <- monitor.markUp("a", "m1")
          recovered <- monitor.getCooldownStrikes
          _ <- registry.getClass // keep registry referenced (no-op)
        yield
          assertEquals(s1.get("a", Int.MaxValue), None, "guard: key is provider/model")
          assertEquals(s1.get("a/m1"), Some(1))
          assertEquals(s2.get("a/m1"), Some(2))
          assertEquals(s3.get("a/m1"), Some(3))
          val now = System.currentTimeMillis()
          val d1 = w1.getOrElse("a/m1", 0L) - now
          val d2 = w2.getOrElse("a/m1", 0L) - now
          val d3 = w3.getOrElse("a/m1", 0L) - now
          assert(d1 > 10_000L && d1 <= UpstreamUnavailable.CooldownBaseMs + 2000L, s"first window ~15s, got ${d1}ms")
          assert(d2 > d1, s"second window must be longer than the first (${d2}ms vs ${d1}ms)")
          assert(d3 > d2, s"third window must be longer than the second (${d3}ms vs ${d2}ms)")
          // strikes 封顶 5：窗长恒为上限 300s
          assertEquals(capped.get("a/m1"), Some(UpstreamUnavailable.CooldownMaxStrikes))
          assertEquals(UpstreamUnavailable.cooldownMs(99), UpstreamUnavailable.CooldownMaxMs)
          // 冷却期内：不进 up、也不进 down（不进探测集）
          assertEquals(up.map(c => s"${c.providerId}/${c.model}"), Nil, "cooled-down candidate must be skipped")
          assertEquals(down, Nil, "cooled-down candidate must NOT enter the probe set")
          // 成功即恢复（半开试探通过的判据）
          assertEquals(recovered.get("a/m1"), None, "a successful call clears the strike counter")
        program.guarantee(release)
    }.unsafeRunSync()
  }

  // ── T6 冷却窗到期 ⇒ 候选自然回链（半开试探面） ──────────────────────────
  test("T6: 冷却窗到期后候选自动回链（无探测、无人工动作）") {
    val configRef = Ref.unsafe[IO, NebflowServiceConfig](mkConfig(PortOk, PortOk))
    LlmInterface.createLlm(Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty), None, Some(configRef)).flatMap {
      case (_, _, monitor, release) =>
        val candidate = ModelCandidate(
          "a",
          ProviderConfig(
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1"))
          ),
          "m1"
        )
        val program = for
          _ <- monitor.softAvoid("a", "m1", 1L, "unit-test 1ms window")
          _ <- IO.sleep(50.millis)
          (up, _) <- monitor.filterCandidates(List(candidate))
        yield assertEquals(up.map(c => s"${c.providerId}/${c.model}"), List("a/m1"))
        program.guarantee(release)
    }.unsafeRunSync()
  }

  // ── T7 403 回归：确证死亡仍驱逐 ─────────────────────────────────────────
  test("T7: 403 ⇒ 不重试、直接换 provider、本家 markDown（不回归）") {
    withServers(List(Port403 -> "403", PortOk -> "ok")) {
      runTurn(mkConfig(Port403, PortOk)).map { case (chunks, states, strikes, _) =>
        assertEquals(chunks, Right("BB"))
        states.get("a/m1") match
          case Some(HealthState.Down(_, _)) => ()
          case other => fail(s"403 must still mark the provider Down (confirmed death), got $other")
        assertEquals(strikes.get("a/m1"), None, "403 is not a 5xx strike")
      }
    }
  }

  // ── T8 429 + 上游额度码 ⇒ 配额族（不是 5xx） ────────────────────────────
  test("T8: 429 + code 1310（周/月额度实测）⇒ quota 族软回避，不落 5xx 冷却") {
    val cls = Fallback.classifyError(
      sttp.client4.HttpError(
        """{"type":"error","error":{"type":"rate_limit_error","code":"1310","message":"已达每周/每月使用上限"}}""",
        sttp.model.StatusCode(429)
      )
    )
    assertEquals(cls.reason, FailoverReason.RateLimit)
    assertEquals(cls.quota, true, "1310 is a planned quota, not a 60s rate window")
    assert(cls.reason != FailoverReason.ServerError, "quota must never be folded into the 5xx family")
    // 1302（频率类）保持 RateLimit 而非 quota——判据只增「确证是额度语义」的码
    val rate = Fallback.classifyError(
      sttp.client4.HttpError(
        """{"error":{"code":"1302","message":"您的账户已达到速率限制"}}""",
        sttp.model.StatusCode(429)
      )
    )
    assertEquals(rate.quota, false, "1302 stays a frequency gate (backoff, not chain switch)")
  }
end UpstreamUnavailableSpec
