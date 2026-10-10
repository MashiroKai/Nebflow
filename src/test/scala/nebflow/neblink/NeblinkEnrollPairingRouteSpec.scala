package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.RestApiRoutes
import nebflow.llm.ModelCandidate
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 门户 MVP E6/E7（2026-10-10）：配对码入网腿的路由级真验收。
 *
 * 契约（方案 §3.2 E6 + §7 契约冻结点）：
 *   · `POST /api/neblink/enroll` `{server?, pairCode}` —— server 解析降序 =
 *     请求显式 > live config > 生产缺省；缺省腿守 案 b① 单点闸（隔离 root 拿不到
 *     生产缺省 ⇒ 可见 400，**绝不静默继承**）；
 *   · 成功腿 = `NeblinkEnrollment.persist(explicitUserAction = true)`：凭据落
 *     config.json（**唯一权威写面**）+ discovery 客户端热换 + 即时重发现 ⇒
 *     **免重启上线**（旧实现手写 save 块 = 必须重启，本 spec 钉死差异）；
 *   · 响应 `{ok:true, message:"Enrolled. Connected.", networkId}`（不再提示重启）。
 *
 * 全程 mock neblink-server（127.0.0.1 临时端口），零生产出网。
 */
class NeblinkEnrollPairingRouteSpec extends CatsEffectSuite:

  private val TestToken = "test-token-pairing"
  private val EnrollResponse =
    """{"deviceToken":"tok-pair-1","networkId":"n1","deviceId":"d-local","avatarUrl":null,"githubUsername":null}"""

  private case class Recorded(method: String, path: String, body: String)

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-pair-route-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── mock neblink-server（仅 /api/device/enroll + /api/device/login）────────

  private def startMockServer: IO[(HttpServer, String, ConcurrentLinkedQueue[Recorded])] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val enrolls = new ConcurrentLinkedQueue[Recorded]()

      def respond(ex: HttpExchange, status: Int, body: String): Unit =
        ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
        ex.getRequestBody.close()
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        os.write(bytes)
        os.close()

      server.createContext(
        "/api/device/enroll",
        ex =>
          val bodyBytes = ex.getRequestBody.readAllBytes()
          enrolls.add(
            Recorded(ex.getRequestMethod, ex.getRequestURI.getPath, new String(bodyBytes, StandardCharsets.UTF_8))
          )
          respond(ex, 200, EnrollResponse)
      )
      server.createContext(
        "/api/device/login",
        ex => respond(ex, 200, """{"token":"tok-x","networkId":"n1","deviceId":"d1","peers":[]}""")
      )
      server.start()
      (server, s"http://127.0.0.1:${server.getAddress.getPort}", enrolls)
    }

  // ── routes under test（装配形态照抄 NeblinkLogoutRoutesSpec）──────────────

  private def mkResources: SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(os.Path(tmpDir, os.pwd) / "archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private def mkRoutes(ms: NeblinkService, discovery: NeblinkDiscovery): RestApiRoutes =
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = mkResources,
      sessionStore = null,
      wsRoutes = null,
      neblinkService = Some(ms),
      neblinkDiscovery = Some(discovery)
    )

  private def enrollRequest(server: Option[String], pairCode: String): Request[IO] =
    val fields = server.map(s => List("server" -> Json.fromString(s))).getOrElse(Nil)
    val json = Json.obj(("pairCode" -> Json.fromString(pairCode)) +: fields*)
    Request[IO](Method.POST, Uri.unsafeFromString("/neblink/enroll"))
      .withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))
      .withEntity(json)

  private case class Stack(ms: NeblinkService, discovery: NeblinkDiscovery, client: NeblinkClient, routes: RestApiRoutes)

  private def mkStack(serverUrl: String, dispatcher: Dispatcher[IO]): IO[Stack] =
    for
      ms <- NeblinkService.createForTest(0, dispatcher, 15.seconds)
      client = new NeblinkClient(NeblinkServerConfig(url = serverUrl, networkId = "seed", secret = "s"), 0)
      ps = new NeblinkPresenceService(ms, 0)(dispatcher)
      _ = ms.setRelayClient(Some(client))
      _ = ms.setPresenceService(ps)
      discovery = new NeblinkDiscovery(ms, 0, ps, Some(client))
    yield Stack(ms, discovery, client, mkRoutes(ms, discovery))

  private def configOnDisk: Json =
    parse(os.read(os.Path(tmpDir, os.pwd) / "neblink" / "config.json")).toOption
      .getOrElse(fail("config.json missing after enroll"))

  // ── tests ────────────────────────────────────────────────────────────────

  test("pairing flow: {server, pairCode} → 200 Enrolled. Connected. + authoritative write + discovery hot-swap (no restart)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, enrolls) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            resp <- st.routes.routes(enrollRequest(server = Some(url), pairCode = "123456")).value
              .map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
            liveCfg <- st.ms.neblinkConfig
            clientAfter <- st.discovery.currentClient
          yield
            // ① 响应契约：免重启文案 + networkId
            assertEquals(resp.status, Status.Ok)
            assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
            assertEquals(body.hcursor.downField("message").as[String].toOption, Some("Enrolled. Connected."))
            assertEquals(body.hcursor.downField("networkId").as[String].toOption, Some("n1"))
            // ② server 收到的 enroll 体带身份四元组
            val calls = enrolls.asScala.toList
            assertEquals(calls.length, 1, s"exactly one enroll call expected, got $calls")
            val sent = parse(calls.head.body).toOption.getOrElse(fail(s"non-JSON enroll body: ${calls.head.body}"))
            assertEquals(sent.hcursor.downField("pairCode").as[String].toOption, Some("123456"))
            assert(sent.hcursor.downField("deviceId").as[String].toOption.exists(_.nonEmpty), "deviceId must be sent")
            // ③ 权威写面：config.json 落 deviceToken（旧实现也写，但只写盘）
            val diskServer = configOnDisk.hcursor.downField("neblinkServer")
            assertEquals(diskServer.downField("deviceToken").as[String].toOption, Some("tok-pair-1"))
            assertEquals(diskServer.downField("url").as[String].toOption, Some(url))
            assertEquals(configOnDisk.hcursor.downField("enabled").as[Boolean].toOption, Some(true))
            // ④ 🔴 热换判据（旧实现的红差异）：live config ref 已更新（免重启可读）
            assertEquals(liveCfg.enabled, true)
            assertEquals(liveCfg.neblinkServer.flatMap(_.deviceToken), Some("tok-pair-1"))
            // ⑤ discovery 客户端已换新实例（旧实现不换 ⇒ 需重启才生效的那一半）
            assert(clientAfter.isDefined, "discovery client must be set after enroll")
            assert(!(clientAfter.get eq st.client), "discovery client must be HOT-SWAPPED to a fresh instance")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("server fallback leg: no server in body → the live config url is used") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, enrolls) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            // seed live config（模拟已配置 self-host server 的实例）
            _ <- st.ms.updateConfig(
              _.copy(neblinkServer = Some(NeblinkServerConfig(url = url, networkId = "seed-net", secret = "")))
            )
            resp <- st.routes.routes(enrollRequest(server = None, pairCode = "654321")).value
              .map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.Ok, s"config-fallback enroll must succeed: ${body}")
            assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
            val calls = enrolls.asScala.toList
            assertEquals(calls.length, 1, "exactly one enroll call expected")
            assertEquals(configOnDisk.hcursor.downField("neblinkServer").downField("url").as[String].toOption, Some(url))
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("案 b① red line: isolated root + no explicit server + no config ⇒ 400 refusal (the prod default is NEVER silently inherited)") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, enrolls) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            resp <- st.routes.routes(enrollRequest(server = None, pairCode = "123456")).value
              .map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.BadRequest)
            assertEquals(
              body.hcursor.downField("error").as[String].toOption,
              Some(EnrollGuard.prodFallbackRefusalReason),
              "the refusal must be the 案 b① single-point reason"
            )
            assertEquals(enrolls.asScala.toList, Nil, "no outbound enroll call may happen")
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }

  test("missing pairCode → 400 Missing pairCode") {
    Dispatcher.parallel[IO].use { dispatcher =>
      startMockServer.flatMap { (server, url, _) =>
        mkStack(url, dispatcher).flatMap { st =>
          for
            resp <- st.routes.routes(enrollRequest(server = Some(url), pairCode = "")).value
              .map(_.getOrElse(fail("route fell through")))
            body <- resp.as[Json]
          yield
            assertEquals(resp.status, Status.BadRequest)
            assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Missing pairCode"))
        }.guarantee(IO.blocking(server.stop(0)))
      }
    }
  }
end NeblinkEnrollPairingRouteSpec
