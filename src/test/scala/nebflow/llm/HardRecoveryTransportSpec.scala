package nebflow.llm

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*

/**
 * Hard-recovery P1/P6 (2026-09-07 设计 §2.2/§2.7/§9)：transport 级强断反事实。
 *
 * 复现台（T1 的 JVM 内形态）：本地 SSE 黑洞服务——先流出 2 个 chunk 复刻
 * 「流已开始」，然后 handler 线程 park 住持有 socket 不再发字节也不关连接，
 * 精确复刻 VPN 切换后半开连接的 body read 阻塞形态（取证 §1.2：4552 chunk
 * 后瞬间死寂、零错误、看门狗与 interruptWhen 全部无法在步骤边界生效）。
 *
 * 断言：
 *  1. 楔死成立（对照）：读 fiber 在 bound 内不完成；
 *  2. transportAbortFor 命中该会话的 1 个在飞请求；
 *  3. 楔死的读在 transport abort 后 bound 内解开（fiber 终态可观察）；
 *  4. abortedRef 已翻转（sendStream 内的确定性映射条件：任何浮出错误重抛为
 *     RecoverableAbort，见 interface.scala handleErrorWith 映射）；
 *  5. halt 以 RecoverableAbort 完成（belt 通道）；
 *  6. 分类与重试语义：RecoverableAbort stream 层 Fatal+不驱逐（与 StuckAbort
 *     同——禁止 provider fallback 拼接部分内容），agent 层可重试（区别于
 *     StuckAbort 的不可重试）。
 */
class HardRecoveryTransportSpec extends CatsEffectSuite:

  private def startBlackholeSse(): (java.util.concurrent.ExecutorService, com.sun.net.httpserver.HttpServer, CountDownLatch, Int) =
    val latch = CountDownLatch(1)
    val pool = java.util.concurrent.Executors.newCachedThreadPool { r =>
      val t = new Thread(r, "blackhole-sse")
      t.setDaemon(true)
      t
    }
    val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(pool)
    server.createContext("/sse", { ex =>
      val resp = ex.getResponseBody
      ex.getResponseHeaders.add("Content-Type", "text/event-stream")
      ex.sendResponseHeaders(200, 0)
      resp.write("data: {\"x\":1}\n\n".getBytes("UTF-8"))
      resp.flush()
      resp.write("data: {\"x\":2}\n\n".getBytes("UTF-8"))
      resp.flush()
      // 流中黑洞：持有 socket，永不发 FIN/RST，永不返回——半开连接复刻。
      latch.await()
      resp.close()
    })
    server.start()
    (pool, server, latch, server.getAddress.getPort)

  private def sseBody(backend: StreamBackend[IO, Fs2Streams[IO]], url: String): fs2.Stream[IO, Byte] =
    fs2.Stream.force(
      basicRequest
        .get(uri"$url")
        .response(asStreamUnsafe(Fs2Streams[IO]))
        .readTimeout(600.seconds)
        .send(backend)
        .flatMap { resp =>
          resp.body match
            case Right(bs) => IO.pure(bs)
            case Left(e)   => IO.raiseError(new RuntimeException(e))
        }
    )

  test("transport abort unwedges a parked body read (T1 counterfactual)") {
    val (pool, server, latch, port) = startBlackholeSse()
    val sess = s"hardrec-${java.util.UUID.randomUUID().toString.take(8)}"
    val url = s"http://127.0.0.1:$port/sse"
    (for
      (key, halt) <- LlmInterface.registerInflight(Some(sess))
      abortedRef <- cats.effect.Ref.of[IO, Boolean](false)
      transportOpt <- LlmInterface.makeAttemptTransport(key, sess, abortedRef)
      transport = transportOpt.getOrElse(fail("PerRequestTransport disabled — makeAttemptTransport returned None"))
      // 楔死：读 fiber 流出 2 chunk 后 park 在 body read 上。
      readFiber <- sseBody(transport.backend, url).compile.toList.start
      _ <- IO.sleep(700.millis)
      stillParked <- readFiber.join.timeout(300.millis).attempt
      _ = assert(stillParked.isLeft, "fiber must be parked on the body read (wedge precondition)")
      // 打破：L2 transport abort（唯一实证有效的原语）。
      aborted <- LlmInterface.transportAbortFor(sess)
      _ = assertEquals(aborted, 1, "exactly one in-flight request must be transport-aborted")
      outcome <- readFiber.join.timeout(8.seconds)
      abortedFlag <- abortedRef.get
      haltOutcome <- halt.get.timeout(2.seconds).attempt
      _ <- transport.release
      terminated = outcome match
        case cats.effect.Outcome.Succeeded(_) => false
        case _                                => true
    yield
      // 楔死解开：fiber 到达终态（错误完成），不再悬挂。
      assert(terminated,
        s"parked read must terminate after transport abort, got $outcome")
      // 确定性映射条件成立（sendStream 内 handleErrorWith 据 abortedRef 重抛
      // RecoverableAbort——本 spec 不经 sendStream，此处断言其触发条件）。
      assert(abortedFlag, "abortedRef must flip so the surfaced error maps to RecoverableAbort")
      // belt 通道：halt 已被 transportAbortFor 以 RecoverableAbort 完成。
      haltOutcome match
        case Right(Left(e: RecoverableAbort)) => // expected
        case other                            => fail(s"halt must complete with RecoverableAbort, got $other")
    )
      .guarantee(IO {
        latch.countDown()
        server.stop(0)
        pool.shutdownNow()
      })
      .timeout(60.seconds)
  }

  test("transportAbortFor for an unknown session is a no-op returning 0") {
    LlmInterface.transportAbortFor("no-such-session").map(n => assertEquals(n, 0))
  }

  test("RecoverableAbort: stream-level Fatal + no evict; agent-level retryable (P6 dual semantics)") {
    val cls = Fallback.classifyError(new RecoverableAbort("s1"))
    assertEquals(cls.permanence, ErrorPermanence.Fatal, "no provider fallback after a transport abort (seam guard semantics)")
    assertEquals(cls.evict, false, "the provider is innocent — never marked down")
    assert(nebflow.agent.AgentActor.llmFailureRetryable(new RecoverableAbort("s1")),
      "agent layer must retry (re-send whole turn) after a recoverable abort")
    assert(!nebflow.agent.AgentActor.llmFailureRetryable(new StuckAbort("s1")),
      "StuckAbort stays non-retryable (watcher kill semantics) — the two must never regress into each other")
    IO.unit
  }
end HardRecoveryTransportSpec
