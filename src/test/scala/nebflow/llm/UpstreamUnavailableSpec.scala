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
 *   T7 403 ⇒ 不重试、配额软回避（不 markDown、不落 5xx 冷却）
 *   T8 429 + 上游 code 1310（zhipu 周/月额度实测）⇒ 配额族，不落 5xx 冷却
 *
 * **fallback 废除批改写（作者令 2026-11-06 20:36「不再支持模型fallback」，逐条申报）**：
 *   runTurn 链深恒 1（`fallbacks = Nil`，b/m1 不再被链引用）；单候选终局 = 全灭闸
 *   静默等待（预算 ProbeIntervalSec=120s），测试给 8s 有界上界取终局（哨兵以 Left
 *   捕获，不使 IO 失败）。T3/T4/T7 **重写**：「换 provider」交付面随 fallback 退役，
 *   判据收窄为「冷却/软回避 + 不 markDown + 有界静默等待 + 未引用 b 零命中」；
 *   T1/T2/T5/T6/T8 与链深无关，一字未动。原断言逐字内容见 git 历史。
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

  /** 一次真实流式 turn（a/m1 首选、链深恒 1）+ 健康态 + 冷却计数 + 软回避窗。 */
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
        // fallback 废除批（作者令 2026-11-06 20:36）：链深恒 1（b/m1 不再被链引用）
        agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = Nil))
      )
      // fallback 废除批申报：单候选失败 ⇒ 静默等待恢复面（全灭闸，预算
      // ProbeIntervalSec=120s）——给 8s 有界上界取终局，不无限等。哨兵以 **Left
      // 捕获**（raiseError 会让整个 IO 失败、下方断言面根本走不到）。
      chunks <- handle
        .sendStream(req)
        .collect { case StreamChunk.TextDelta(d) => d }
        .compile
        .toList
        .attempt
        .timeoutTo(
          8.seconds,
          IO.pure(
            Left(new RuntimeException("all-down gate exceeded test bound")): Either[Throwable, List[String]]
          )
        )
      states <- monitor.getStates
      strikes <- monitor.getCooldownStrikes
      avoids <- monitor.getAvoidUntil
      _ <- release
    yield (chunks.map(_.mkString), states, strikes, avoids)

  private def withServers[A](modes: List[(Int, String)])(body: => IO[A]): IO[A] =
    val hits = modes.map(_ => new java.util.concurrent.atomic.AtomicInteger(0))
    for
      servers <- modes.zip(hits).traverse { case ((port, mode), hit) => startMock(port, mode, hit) }
      a <- body.guarantee(IO.blocking(servers.foreach(_.stop(0))))
    yield a

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
    // 5xx + Permanent 超时：超时属网络类 attempt（isNetworkClassAttempt），且无明确拒绝
    // ⇒ 仍可等待（与 #692 等待语义一致：慢/不可用都可等，拒绝等不来）
    assert(
      Fallback.isUpstreamUnavailableExhaustion(timeoutAnd5xx),
      "5xx + a Permanent timeout stays waitable (timeout joins the network-class attempts)"
    )
    // 空链（无 attempt）不算可等待
    assert(!Fallback.isUpstreamUnavailableExhaustion(FallbackExhaustedError(Nil)))
    // agent 层重试面：全 5xx ⇒ retryable（flow 节点据此 Restart）
    assert(nebflow.agent.AgentActor.llmFailureRetryable(all5xx), "5xx exhaustion must be retryable at agent layer")
    assert(!nebflow.agent.AgentActor.llmFailureRetryable(mixed), "auth rejection stays non-retryable")
  }

  // ── T3 500 ⇒ 换 provider，不冻结（不 markDown） ──────────────────────────
  test("T3(重写): 500 ⇒ 冷却窗替代驱逐（本家不进 Down）+ 单候选静默等待（有界终局）；后备不承接（fallback 废除）") {
    withServers(List(Port500 -> "500", PortOk -> "ok")) {
      runTurn(mkConfig(Port500, PortOk)).map { case (chunks, states, strikes, avoids) =>
        chunks match
          case Right(text) =>
            fail(s"唯一候选 500 ⇒ 不得有根交付（后备已随 fallback 退役），实际成功 = $text")
          case Left(_) => () // 终局 = 静默等待（全灭闸/测试上界）——下方冷却面钉死
        assert(!states.contains("a/m1"), s"5xx must NOT markDown (frozen-agent root cause), got ${states.get("a/m1")}")
        // 同 provider 会先就地重试一次（Transient + retriesLeft>0）⇒ 两条失败各记一次
        assertEquals(strikes.get("a/m1"), Some(2), "5xx retried once in place ⇒ two strikes before the wait")
        assert(
          avoids.get("a/m1").exists(_ > System.currentTimeMillis()),
          "the provider must be cooled down (soft-avoid window is live)"
        )
      }
    }
  }

  // ── T4 550 + 空响应体 ⇒ 同族同处置 ──────────────────────────────────────
  test("T4(重写): 550 + 空响应体（现场形态）⇒ 冷却替代驱逐 + 单候选静默等待（有界终局）") {
    withServers(List(Port550 -> "550", PortOk -> "ok")) {
      runTurn(mkConfig(Port550, PortOk)).map { case (chunks, states, strikes, avoids) =>
        chunks match
          case Right(text) =>
            fail(s"唯一候选 550 ⇒ 不得有根交付（后备已随 fallback 退役），实际成功 = $text")
          case Left(_) => () // 终局 = 静默等待（全灭闸/测试上界）
        assert(!states.contains("a/m1"), s"550 must NOT markDown, got ${states.get("a/m1")}")
        assertEquals(strikes.get("a/m1"), Some(2), "one in-place retry ⇒ two strikes")
        assert(avoids.get("a/m1").exists(_ > System.currentTimeMillis()))
      }
    }
  }

  // ── T5 冷却指数退避 + 冷却期内跳过 ──────────────────────────────────────
  test("T5: 连续 3 次 5xx ⇒ 冷却窗 15s/30s/60s 指数增长，冷却期内候选被剔出链") {
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
        yield
          assertEquals(s1.get("a"), None, "guard: key is provider/model")
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

  // ── T7（重写，fallback 废除批）403 配额闸形态 ───────────────────────────
  // 原 T7：「不重试、直接换 provider、走配额软回避」——「直接换」面随 fallback
  // 退役（唯一候选后无下一根可换，后备不暗中承接）；「不重试 / 配额软回避 /
  // 不 markDown / 不落 5xx 冷却」判据原样钉死 + 新增 b 零命中。原断言逐字见 git 历史。
  test("T7(重写): 403（配额闸形态）⇒ 不重试、配额软回避而非 5xx 冷却、不 markDown；单候选静默等待（有界终局）；b 零命中（fallback 废除）") {
    val hits403 = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsOk = new java.util.concurrent.atomic.AtomicInteger(0)
    val program = for
      s403 <- startMock(Port403, "403", hits403)
      sOk <- startMock(PortOk, "ok", hitsOk)
      result <- runTurn(mkConfig(Port403, PortOk)).guarantee(IO.blocking { s403.stop(0); sOk.stop(0) })
    yield (result, hits403.get(), hitsOk.get())
    val ((chunks, states, strikes, avoids), hits, hitsOkN) = program.unsafeRunSync()
    chunks match
      case Right(text) =>
        fail(s"唯一候选 403 ⇒ 不得有根交付（无下一根可换，后备已随 fallback 退役），实际成功 = $text")
      case Left(_) => () // 终局 = 单候选静默等待（全灭闸/测试上界哨兵）
    // 403 = 配额闸形态（plan quota）⇒ 软回避窗 + **不重试**（Permanent 拒绝，无就地重试）
    assertEquals(hits, 1, "403 is not retried in place (permanent rejection)")
    assertEquals(hitsOkN, 0, "未引用的 b 必须零命中（fallback 废除：不暗中承接）")
    assert(
      avoids.get("a/m1").exists(_ > System.currentTimeMillis()),
      "403 lands in the quota soft-avoid window"
    )
    assertEquals(strikes.get("a/m1"), None, "403 is not a 5xx strike (no cool-down escalation)")
    assertEquals(states.get("a/m1"), None, "quota-gate 403 does not markDown (no probe burn)")
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
