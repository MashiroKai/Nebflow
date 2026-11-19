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
 *
 * **model-404-freeze 批改写（2026-11-07，逐条申报）**：T2 里「404 remains a
 * confirmed-death eviction」一条**反转**为 `evict=false`——404 model_not_found 是
 * **配置失效**（模型对该供应商/分组不存在），provider 活着；旧语义把它当「确证死亡」
 * 驱逐 ⇒ 下一次请求落进全灭闸 ⇒ `AllProvidersDownTimeout` ⇒ `FreezeReason.ProviderDown`
 * ⇒ 冻结后无限自动重试一个不存在的模型（现网 `delegate-nebflow-5c905a1d` 实证）。
 * 新增判据与两侧用例见 `ModelNotFoundNoEvictSpec`。其余断言逐字未改。
 *
 * **fallback 废除批改写（作者令 2026-11-06 20:36「不再支持模型fallback」，逐条申报）**：
 *   - T1 **重写**为单候选语义：链深恒 1（b/m1 不进链）⇒ 400 Format 的就地降级重试
 *     面原样保留（恰好 2 次命中后），终局 = FallbackExhaustedError（无下一根可换）；
 *     「不驱逐」核心断言原样保留；b 零命中为新增废除断言；
 *   - T3 **重写**：401 驱逐面（markDown）原样钉死，但单候选链驱逐后进入「全灭闸
 *     等待恢复」（作者令「静默等待」语义面）——改给**有界上界**取终局，不再无限等；
 *     「401 恰 1 次命中」与「b 零命中」新增；
 *   - T2 / T4 断言原样保留（与链深无关）。
 * 原断言逐字内容见 git 历史。
 */
class FormatErrorNoEvictSpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  // 案① A5（chain-llmstall-fix）：4xx 响应体取证面**豁免 `enabled`** ⇒ 本 spec 造的
  // 400/401 会落盘。不重定向就会写进生产 home 的 logs/router（与
  // TaskStuckWatcherSpec / LogWriterHomeIsolationSpec 同款纪律）。
  private var logTmp: java.nio.file.Path = null

  override def beforeAll(): Unit =
    logTmp = java.nio.file.Files.createTempDirectory("format-noevict-log-")
    nebflow.core.LlmLogWriter.setLogDirForTest(logTmp)

  override def afterAll(): Unit =
    nebflow.core.LlmLogWriter.resetLogDirForTest()
    // 倒序 walk 删除（不逐个点名文件）：本 spec 在 enabled 被前置 suite 置真时会同时
    // 落 summary/full 等多条腿，点名删 `_httperror.jsonl` 后 `deleteIfExists(tmp)`
    // 遇到非空目录会抛 DirectoryNotEmptyException ⇒ 假失败。与
    // LogWriterHomeIsolationSpec:63-69 同款形态。
    if logTmp != null && java.nio.file.Files.exists(logTmp) then
      java.nio.file.Files
        .walk(logTmp)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(java.nio.file.Files.deleteIfExists)

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
              val body =
                """{"type":"error","error":{"type":"authentication_error","message":"invalid api-key"}}""".getBytes(
                  StandardCharsets.UTF_8
                )
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
          end match
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
        agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = Nil)) // 链深恒 1（fallback 废除）
      )
      chunks <- handle.sendStream(req).compile.toList.attempt
      states <- monitor.getStates
      _ <- release
    yield (chunks, states, 0)

  test("T1(重写): 400 (thinking-passback shape) gets ONE in-place degrade retry then exhausted — provider NOT evicted") {
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
        case Left(e: FallbackExhaustedError) =>
          val atts = e.attempts
          // fallback 废除批：单候选链（b/m1 不进链）⇒ 所有 attempt 都必须落在 a/m1
          // 本身（就地降级重试面原样保留），终局 = 显式耗尽。
          assert(
            atts.nonEmpty && atts.forall(x => x.providerId == "a" && x.model == "m1"),
            s"单候选链的 attempt 必须都在 a/m1（就地降级重试），实际 ${atts.map(x => s"${x.providerId}/${x.model}")}"
          )
          assert(
            atts.forall(_.reason.contains(FailoverReason.Format)),
            s"每个 attempt 都应是 Format（400），实际 ${atts.map(_.reason)}"
          )
        case Left(e) => fail(s"400 with evict=false must exhaust the single-candidate chain, got $e")
        case Right(list) =>
          fail(s"唯一候选恒 400 ⇒ 必须失败退出（无下一根可换），实际成功 ${list.size} chunk")
      // 核心断言（旧行为：a/m1 Down("...thinking must be passed back...")）：
      assert(
        !states.contains("a/m1"),
        s"400 Format must NOT evict the provider (audit #2), got ${states.get("a/m1")}"
      )
      // protocol-compat 批 2026-11-01 口径变更（降级旁路 §4.4）：passback 形状 400
      // 先就地降级重试一次（thinking 显式关态），换腿前零块已发（接缝安全）。
      // **fallback 废除批（单候选）读数修正**：「恰 1 次就地降级重试」面保留在**每轮**
      // 内，但换腿面退役 ⇒ 有界循环由 MaxChainRounds=2 封顶（AllCandidatesFormatLoopSpec
      // 同款），总命中 = 2 轮 ×（1 试 + 1 降级重试）= 4。仍钉死「每轮恰 2 发、有界不循环」。
      assertEquals(
        hitsA.get(),
        2 * Fallback.MaxChainRounds,
        "per round: 1 try + 1 in-place degrade retry, capped by MaxChainRounds rounds (bounded, never 3+ per round)"
      )
      assertEquals(
        hitsB.get(),
        0,
        "b/m1 未被链引用 ⇒ 零命中（fallback 废除：不暗中回补）"
      )
    finally
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
    end try
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
    // model-404-freeze 批改写（2026-11-07）：404 不再是「确证死亡」——它说的是
    // **我们指的模型不存在/不属于该分组**（配置失效），provider 一直活着。旧断言
    // `evict=true` 正是现网 `reason=provider-down` 无限自动重试的入口：markDown ⇒
    // 全灭闸等 120s ⇒ AllProvidersDownTimeout ⇒ 冻结可自动恢复。判据与现场取证见
    // `ModelNotFoundNoEvictSpec`（本批新增）。
    assertEquals(notFound.evict, false, "404 = 配置失效（模型名/分组不对），不驱逐 provider")
  }

  test("T3(重写): 401 Auth still evicts via markDown (确证死亡不回归；单候选链终局 = 有界全灭闸)") {
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
        configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(Port401, "401", PortOk))
        sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
        (handle, _, monitor, release) = triple
        req = LlmRequest(
          messages = List(Message(MessageRole.User, Left("hi"))),
          sessionId = "format-e2e-401",
          agentId = "format-spec-agent",
          agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = Nil)) // 链深恒 1（fallback 废除）
        )
        // fallback 废除批申报：单候选链 401 驱逐后链空 ⇒ 进入「全灭闸等待恢复」
        //（作者令「静默等待」语义面）——本测试给 8s 有界上界取终局，不无限等探测。
        chunks <- handle
          .sendStream(req)
          .compile
          .toList
          .attempt
          .timeoutTo(
            8.seconds,
            // 哨兵以 Left 捕获（raiseError 会让 IO 失败、下方断言面走不到）
            IO.pure(
              Left(new RuntimeException("all-down gate exceeded test bound")): Either[Throwable, List[StreamChunk]]
            )
          )
        states <- monitor.getStates
        _ <- release
      yield (chunks, states)
      val (chunks, states) = program.guarantee(IO.blocking { serverA.stop(0); serverB.stop(0) }).unsafeRunSync()
      chunks match
        case Right(list) =>
          fail(s"唯一候选 401 驱逐后必须终局（无下一根可换），实际成功 ${list.size} chunk")
        case Left(e) =>
          // 终局形态 = 全灭闸的探测超时（AllProvidersDownTimeout）或本测试上界哨兵；
          // 无论哪条，驱逐面必须已发生（下方 states 断言钉死）。
          assert(
            e.isInstanceOf[AllProvidersDownTimeout] || e.getMessage.contains("all-down gate"),
            s"终局应为全灭闸超时（探测窗），实际 ${e.getClass.getName}: ${e.getMessage}"
          )
      // 旧行为保持（本测试的钉版点）：Auth → markDown。
      states.get("a/m1") match
        case Some(HealthState.Down(_, _)) => () // evicted as before
        case other => fail(s"401 must still mark the provider Down (confirmed death), got $other")
      // **fallback 废除批（单候选）读数修正**：401「恰 1 次命中、不重试同一根」原样
      // 成立（Permanent 无就地重试）；驱逐后链空 ⇒ 全灭闸先 probeNow(dead) 烧一发
      // 探测（401 仍失败 ⇒ Down 保持），故 mock 总命中 = 1 试 + 1 探测 = 2。
      assertEquals(hitsA.get(), 2, "1 initial try (Permanent never retried in place) + 1 gate probe burn")
      assertEquals(hitsB.get(), 0, "b/m1 未被链引用 ⇒ 零命中（fallback 废除）")
    finally
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
    end try
  }
end FormatErrorNoEvictSpec
