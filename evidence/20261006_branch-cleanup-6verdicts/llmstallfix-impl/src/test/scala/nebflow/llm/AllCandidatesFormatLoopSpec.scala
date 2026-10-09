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
 * 案① 红验锚点 1（LLM 硬杀波 · `chain-llmstall-fix`，2026-09-21 作者绿灯）——
 * **全链 400（Format）必须终止，不得无出口重投**。
 *
 * 缺口原文（定谳报告 `20260921_182544_llmstall-diag` §一.4）：`fallback.scala:92`
 * 把 400 归 `Format`、`:105` 定 `Permanent`、`:107` 定 `evict=false` ⇒ 无人被
 * markDown ⇒ `interface.scala:660-663 case Nil => attemptWithHealthCheck` 重新
 * filterCandidates 得到**同一全链** ⇒ `:650-651` 重投 ⇒ 无计数 / 无退避 / 无终态的
 * 闭环。既有 `FormatErrorNoEvictSpec` T1 只覆盖「400 后跳到**能成功的**下一候选」，
 * **全链 400 这一支零覆盖**——本 spec 就是这一支的门。
 *
 * 断言（三条，全部二值）：
 *   ① `sendStream` 在有限轮次内**终止**（以 `FallbackExhaustedError` 结束，而不是
 *      挂着不产 chunk 也不抛错）；
 *   ② 轮次数 ≤ `Fallback.MaxChainRounds`（用失败候选的 attempt 计数反推：每个候选
 *      每轮恰好一次命中 ⇒ 总 attempt 数 = 候选数 × 轮数）；
 *   ③ 每个候选的命中数 == `Fallback.MaxChainRounds`（证明确实**循环过**——不是
 *      「一次都没试」就终局）。
 *
 * 变异验红（读数见收尾报告）：把 A1/A2 的轮次上限还原（`case Nil` 无条件回落
 * `attemptWithHealthCheck`）⇒ 本 spec 按 munitIOTimeout **超时红**（闭环不产 chunk、
 * 不抛错，正是现场把 agent 拖到看门狗 600s 硬杀的形态）。
 */
class AllCandidatesFormatLoopSpec extends CatsEffectSuite:

  override val munitIOTimeout = 30.seconds

  private val PortLoopA = 18611
  private val PortLoopB = 18613

  private val badRequestBody =
    """{"type":"error","error":{"type":"invalid_request_error","message":"enable_search is not supported by this endpoint"}}"""

  /** 400 常驻 mock（每个请求都回 400，并计命中数）。 */
  private def startMock400(
      port: Int,
      hits: java.util.concurrent.atomic.AtomicInteger
  ): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          val _ = exchange.getRequestBody.readAllBytes()
          hits.incrementAndGet()
          val body = badRequestBody.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(400, body.length.toLong)
          exchange.getResponseBody.write(body)
          exchange.close()
      )
      server.start()
      server
    }

  private def mkConfig: NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "a" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$PortLoopA",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", vision = Some(false)))
          ),
          "b" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$PortLoopB",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", vision = Some(false)))
          )
        )
      )
    )

  test("T1: 全链 400（evict=false ⇒ 无人被驱逐）在有界轮次内以 FallbackExhaustedError 终止") {
    val hitsA = new java.util.concurrent.atomic.AtomicInteger(0)
    val hitsB = new java.util.concurrent.atomic.AtomicInteger(0)
    var serverA: HttpServer = null
    var serverB: HttpServer = null
    // A5 取证面是新开的常驻写入腿（`logs/router/*_httperror.jsonl`，豁免 enabled）
    // ⇒ 本 spec 必须把它重定向到临时目录，否则 sbt test 会把 400 响应体写进生产
    // home（与 TaskStuckWatcherSpec / LogWriterHomeIsolationSpec 同款纪律）。
    val logTmp = java.nio.file.Files.createTempDirectory("format-loop-log-")
    nebflow.core.LlmLogWriter.setLogDirForTest(logTmp)
    try
      val program = for
        a <- startMock400(PortLoopA, hitsA)
        _ = serverA = a
        b <- startMock400(PortLoopB, hitsB)
        _ = serverB = b
        configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig)
        sessionOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        triple <- LlmInterface.createLlm(sessionOverrides, None, Some(configRef))
        (handle, _, _, release) = triple
        req = LlmRequest(
          messages = List(Message(MessageRole.User, Left("hi"))),
          sessionId = "format-loop",
          agentId = "format-loop-agent",
          agentModel = Some(AgentModelConfig(preferred = Some("a/m1"), fallbacks = List("b/m1")))
        )
        // 关键：**不给外部中断兜底**——这一步要么由被测代码自己终止（绿），
        // 要么挂到 munitIOTimeout（红）。任何「顺手加个 timeout」都会掩盖缺口。
        chunks <- handle.sendStream(req).compile.toList.attempt
        _ <- release
      yield chunks
      val chunks = program.guarantee(IO.blocking { serverA.stop(0); serverB.stop(0) }).unsafeRunSync()

      chunks match
        case Right(list) =>
          fail(
            s"全链 400 必须失败退出（FallbackExhaustedError），却成功返回 ${list.size} chunk —— " +
              "轮次上限未生效或候选被误判为成功"
          )
        case Left(e) =>
          // ① 终止类型：必须是显式的「全链耗尽」，不是任意异常。
          assert(
            e.isInstanceOf[FallbackExhaustedError],
            s"终止错误必须是 FallbackExhaustedError（显式终局），实际 ${e.getClass.getName}: ${e.getMessage}"
          )
          val attempts = e.asInstanceOf[FallbackExhaustedError].attempts
          // ② 轮次数 ≤ 上限（attempt 计数 = 候选数 × 轮数，每个候选每轮恰好一次命中）。
          val candidates = 2 // a/m1 + b/m1
          assert(
            attempts.size <= candidates * Fallback.MaxChainRounds,
            s"attempt 数 ${attempts.size} 超过 候选数($candidates) × 轮次上限(${Fallback.MaxChainRounds})"
          )
          assert(
            attempts.forall(_.reason.contains(FailoverReason.Format)),
            s"每个 attempt 都应是 Format（400），实际 ${attempts.map(_.reason)}"
          )
          // ③ 每候选命中数 == 上限 —— 证明确实循环过（不是「根本没试」）。
          assertEquals(hitsA.get(), Fallback.MaxChainRounds,
            s"a/m1 命中数应恰好等于轮次上限（证明循环过且被上限截断）")
          assertEquals(hitsB.get(), Fallback.MaxChainRounds,
            s"b/m1 命中数应恰好等于轮次上限（证明循环过且被上限截断）")
          assertEquals(attempts.size, candidates * Fallback.MaxChainRounds,
            "attempt 记录数应等于 候选数 × 轮次上限（每候选每轮一条）")
    finally
      nebflow.core.LlmLogWriter.resetLogDirForTest()
      if serverA != null then serverA.stop(0)
      if serverB != null then serverB.stop(0)
  }

  test("T2: 上限常量本身 ≥ 2（既有 FormatErrorNoEvictSpec T1 的轮内语义共存硬约束）") {
    assert(
      Fallback.MaxChainRounds >= 2,
      s"轮次上限必须 ≥ 2：T1 断言「a/m1 命中恰好 1 次 + b 成功」依赖「整链试完才推进轮次」，" +
        s"上限 1 会把正常候选遍历也截断，实际 ${Fallback.MaxChainRounds}"
    )
  }
end AllCandidatesFormatLoopSpec
