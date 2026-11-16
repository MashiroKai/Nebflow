package nebflow.llm.decision

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.Ref as CatsRef
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateJudge
import nebflow.llm.ProviderRegistry
import nebflow.shared.{ModelConfig, NebflowServiceConfig, PathUtil, ProviderConfig, ServiceLlmConfig}
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * 四修③（2026-10-07 修复令「观测补洞」）判读（jev-retire 批 2026-11-16 起
 * JevSettings 腿生命周期用例随 JeV 设置面退役删除，只余 SmallJudge 面）：
 *
 *  - **SmallJudge 两条静默 None 路径**：候选链空（utilityCandidates=nil，无候选）
 *    与 parseVerdict 失败（回文无 JSON 判词）两条腿都 yield None——None 语义
 *    不变（兜底/回落接手，绝非误判），但不再无声（两条 WARN 位点随刀）。
 *    parse 腿走**真 adapter**（真 sttp backend ＋本地 HttpServer 回散文），
 *    断言 `hits==1` 钉死「None 出自解析失败而非调用失败」——WARN 位点被触达。
 *
 * 现场：鹦鹉骑自行车终态判定——SmallJudge 候选 None／回文解析失败静默 →
 * 两腿全败 → 假 completed。
 * 全程封闭面：dataRoot 指向临时目录，HTTP 只打 127.0.0.1 随机端口的进程内
 * server——零外呼。
 */
class DelegateJudgeObserveSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-judge-observe-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  // ── SmallJudge 静默 None 两条腿 ─────────────────────────────────────────

  private val hits = new AtomicInteger(0)

  /** OpenAI 形状回文（content 固定散文——无 JSON 判词 ⇒ parseVerdict 失败腿）。 */
  private def startProseServer(): HttpServer =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) =>
        val body =
          """{"choices":[{"message":{"role":"assistant","content":"I judged it mentally: all good."}}]}""".getBytes(
            StandardCharsets.UTF_8
          )
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length)
        exchange.getResponseBody.write(body)
        exchange.getResponseBody.close()
        hits.incrementAndGet()
        ()
    )
    server.start()
    server

  end startProseServer

  private def judgeInput(id: String): DelegateJudge.TerminalInput =
    DelegateJudge.TerminalInput(
      delegateId = id,
      taskBrief = "brief",
      resultText = "result",
      gitDigest = "",
      selfReport = None
    )

  test("smallJudgeVerdict with no candidates anywhere ⇒ None (empty candidate chain WARN path), zero HTTP") {
    withTempDataRoot { _ =>
      val registry = new ProviderRegistry(
        CatsRef.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        backend = null
      )
      val out = DelegateJudgeMachine.face(registry).judgeTerminal(judgeInput("delegate-observe-1"))
      assertIO(out, None)
    }
  }

  test(
    "smallJudgeVerdict with a real adapter returning non-JSON ⇒ None (parseVerdict-failure WARN path) — None semantics unchanged"
  ) {
    withTempDataRoot { _ =>
      Dispatcher.parallel[IO].allocated.flatMap { case (dispatcher, _) =>
        val client = java.net.http.HttpClient
          .newBuilder()
          .version(java.net.http.HttpClient.Version.HTTP_1_1)
          .connectTimeout(java.time.Duration.ofSeconds(5))
          .build()
        val backend = HttpClientFs2Backend.usingClient[IO](client, dispatcher)
        val server = startProseServer()
        val port = server.getAddress.getPort
        val providers = Map(
          "fakeprov" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$port/v1",
            apiKey = "test-key",
            protocol = nebflow.shared.LlmProtocol.OpenAI,
            models = List(ModelConfig("m1"))
          )
        )
        // 合并批（2026-11-07）：全局链必须显式声明——config-single-source 批之后
        // 「provider inventory 推导种子链」已废，无 `llm.fallback` 且临时 dataRoot
        // 无 Nebula 侧车 ⇒ 链 fail-closed 为空 ⇒ 判定机一次 HTTP 都不发（本 spec
        // 的 hits==1 断言即该空洞的钉子）。
        val registry = new ProviderRegistry(
          CatsRef.unsafe[IO, NebflowServiceConfig](
            NebflowServiceConfig(
              llm = ServiceLlmConfig(
                providers = providers,
                fallback = Some(nebflow.shared.AgentModelConfig(Some("fakeprov/m1"), Nil))
              )
            )
          ),
          backend = backend
        )
        val call = DelegateJudgeMachine.face(registry).judgeTerminal(judgeInput("delegate-observe-2"))
        for
          out <- call
          _ <- IO(server.stop(0))
        yield
          assertEquals(out, None, "non-JSON reply ⇒ parse failure ⇒ None (fallback takes over, never a guess)")
          assertEquals(
            hits.get,
            1,
            "the reply was really fetched over HTTP — the None came from the parse-failure WARN site, not a call failure"
          )
        end for
      }
    }
  }

end DelegateJudgeObserveSpec
