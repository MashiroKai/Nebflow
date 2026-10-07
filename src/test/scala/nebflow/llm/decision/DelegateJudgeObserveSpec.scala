package nebflow.llm.decision

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.Ref as CatsRef
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateJudge
import nebflow.llm.ProviderRegistry
import nebflow.shared.{JevConfig, ModelConfig, NebflowServiceConfig, PathUtil, ProviderConfig, ServiceLlmConfig}
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * 四修③（2026-10-07 修复令「观测补洞」）判读：
 *
 *  - **腿生命周期挂 secrets 在位性**：`jev.keyRef` 指向的 secret 件缺失 ⇒
 *    `JevSettings.resolve` = None（腿自动关闭，不再构造注定 403 的 provider）；
 *    补上 secret 件 ⇒ 下一次热读自动恢复（Some＋token 在位）——开/关/开全程
 *    无重启；keyRef 未配置 = 不期望凭据，腿照常开（既有语义）。
 *  - **SmallJudge 两条静默 None 路径**：utilityCandidate=None（无候选）与
 *    parseVerdict 失败（回文无 JSON 判词）两条腿都 yield None——None 语义
 *    不变（兜底/回落接手，绝非误判），但不再无声（两条 WARN 位点随刀）。
 *    parse 腿走**真 adapter**（真 sttp backend ＋本地 HttpServer 回散文），
 *    断言 `hits==1` 钉死「None 出自解析失败而非调用失败」——WARN 位点被触达。
 *
 * 现场：鹦鹉骑自行车终态判定——jev.keyRef 无 secret 件 → 403 白烧一次调用；
 * SmallJudge 候选 None／回文解析失败静默 → 两腿全败 → 假 completed。
 * 全程封闭面：dataRoot 指向临时目录（jev 未配置 ⇒ Jev 腿恒关，走 SmallJudge），
 * HTTP 只打 127.0.0.1 随机端口的进程内 server——零外呼。
 */
class DelegateJudgeObserveSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-observe-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  // ── JevSettings：secrets 在位性 = 腿生命周期 ─────────────────────────────

  test("keyRef names an absent secret ⇒ the jev leg is CLOSED (resolve=None); restoring the secret re-opens it on the next hot read") {
    withTempDataRoot { tmp =>
      for
        closed <- IO(JevSettings.resolve(Some(JevConfig(enabled = Some(true), keyRef = Some("obs-key"))), health = None))
        _ <- IO {
          os.makeDir.all(tmp / "secrets")
          os.write(tmp / "secrets" / "obs-key", "sk-live")
        }
        reopened <- IO(JevSettings.resolve(Some(JevConfig(enabled = Some(true), keyRef = Some("obs-key"))), health = None))
        _ <- IO(os.remove(tmp / "secrets" / "obs-key"))
        reclosed <- IO(JevSettings.resolve(Some(JevConfig(enabled = Some(true), keyRef = Some("obs-key"))), health = None))
      yield
        assertEquals(closed, None, "secret absent ⇒ leg closed (no doomed provider, no 403 burn)")
        assert(reopened.isDefined, "restoring the secret re-opens the leg (hot read, no restart)")
        assertEquals(reopened.flatMap(_.token), Some("sk-live"), "the reopened leg carries the token")
        assertEquals(reclosed, None, "removing the secret closes the leg again (lifecycle rides presence)")
      end for
    }
  }

  test("no keyRef = no credential expected: the leg stays open (existing semantics); empty/unreadable secrets also close it") {
    withTempDataRoot { tmp =>
      for
        tokenless <- IO(JevSettings.resolve(Some(JevConfig(enabled = Some(true))), health = None))
        _ <- IO {
          os.makeDir.all(tmp / "secrets")
          os.write(tmp / "secrets" / "empty-key", "   ")
        }
        emptyClosed <- IO(JevSettings.resolve(Some(JevConfig(enabled = Some(true), keyRef = Some("empty-key"))), health = None))
        blankRef <- IO(JevSettings.resolve(Some(JevConfig(enabled = Some(true), keyRef = Some("  "))), health = None))
      yield
        assert(tokenless.isDefined, "keyRef absent ⇒ leg open (token None is a legal tokenless shape)")
        assertEquals(emptyClosed, None, "an empty secret file closes the leg (same presence discipline)")
        assert(blankRef.isDefined, "a blank keyRef = unconfigured ⇒ leg open")
      end for
    }
  }

  // ── SmallJudge 静默 None 两条腿 ─────────────────────────────────────────

  private val hits = new AtomicInteger(0)

  /** OpenAI 形状回文（content 固定散文——无 JSON 判词 ⇒ parseVerdict 失败腿）。 */
  private def startProseServer(): HttpServer =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions", (exchange: HttpExchange) =>
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

  private def judgeInput(id: String): DelegateJudge.TerminalInput =
    DelegateJudge.TerminalInput(
      delegateId = id,
      taskBrief = "brief",
      resultText = "result",
      gitDigest = "",
      selfReport = None,
      mergeRound = false
    )

  test("smallJudgeVerdict with no candidates anywhere ⇒ None (utilityCandidate=None WARN path), zero HTTP") {
    withTempDataRoot { _ =>
      val registry = new ProviderRegistry(
        CatsRef.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        backend = null
      )
      val out = DelegateJudgeMachine.face(registry).judgeTerminal(judgeInput("delegate-observe-1"))
      assertIO(out, None)
    }
  }

  test("smallJudgeVerdict with a real adapter returning non-JSON ⇒ None (parseVerdict-failure WARN path) — None semantics unchanged") {
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
        val registry = new ProviderRegistry(
          CatsRef.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = providers))),
          backend = backend
        )
        val call = DelegateJudgeMachine.face(registry).judgeTerminal(judgeInput("delegate-observe-2"))
        for
          out <- call
          _ <- IO(server.stop(0))
        yield
          assertEquals(out, None, "non-JSON reply ⇒ parse failure ⇒ None (fallback takes over, never a guess)")
          assertEquals(hits.get, 1, "the reply was really fetched over HTTP — the None came from the parse-failure WARN site, not a call failure")
        end for
      }
    }
  }

end DelegateJudgeObserveSpec
