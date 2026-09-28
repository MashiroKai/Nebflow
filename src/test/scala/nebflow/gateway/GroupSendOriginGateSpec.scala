package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.neblink.{AgentMessagingConfig, FriendService, NeblinkClient, NeblinkServerConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * 网关 `POST /api/groups/{groupId}/messages` 的 **origin 闸**（gmsgsend 批，
 * 2026-09-15 · 补充卡 §6.5 + §8.1 判红面 ①「标识伪造面」）。
 *
 * 判据（逐条）：
 *  ① **UI 面不可能产出 `origin='agent'`**：请求体带 `origin:"agent"`（或任何非 `user`
 *     值）⇒ 网关 `400` 显式拒绝，**零上游往返** ⇒ 服务端**结构上**不会落一条
 *     「非 agent 通道却标 agent」的消息（§8.1(a) 的判红信号不可达）；
 *  ② **逐字转发性质不因本闸退化**：合法体（无 origin / `origin:"user"` / `origin:null`）
 *     仍**逐字节**送达上游（未知键保留、键序不变）——保住 gwroutes 批在该路由上的
 *     「代理只搬字节、不 reshape」判据；
 *  ③ **共端点事实读数**：UI 腿与 agent 腿打的是**同一上游端点**
 *     `/api/groups/{id}/messages`（本 spec 记录上游收到的路径）。
 *
 * 与 `GroupApiRoutesSpec`（gwroutes 批）的分工：那条 spec 钉 12 条群路由的**对表/透传/
 * 出参形态**；本 spec 只钉**发送路由特有的身份闸**，不重复其判据。
 */
class GroupSendOriginGateSpec extends CatsEffectSuite:

  private val TestToken = "test-token-123"

  private val seen       = new ConcurrentLinkedQueue[String]()
  private val seenBodies = new ConcurrentLinkedQueue[String]()

  private def seenList: List[String] = seen.toArray(Array.empty[String]).toList

  private def startMockServer: (HttpServer, String) =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def respond(ex: HttpExchange, status: Int, body: String): Unit =
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      val os = ex.getResponseBody
      os.write(bytes)
      os.close()
    server.createContext(
      "/api/device/login",
      ex => respond(ex, 200, """{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}""")
    )
    server.createContext(
      "/api/groups",
      ex =>
        val reqBody = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        seen.add(s"${ex.getRequestMethod} ${ex.getRequestURI.getPath}")
        seenBodies.add(reqBody)
        // 冻结群发契约的成功态：201 + SendMessageResponse（含 selfUserId 的共享信封）
        respond(
          ex,
          201,
          """{"messageId":5,"conversationId":"grp-1","createdAt":1234567899,"existing":false,"selfUserId":"u1"}"""
        )
    )
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}")

  private def withMockServer[A](use: (String, NeblinkClient, FriendService) => IO[A]): IO[A] =
    IO.delay {
      seen.clear(); seenBodies.clear(); startMockServer
    }.flatMap { (server, url) =>
      val client = new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)
      use(url, client, new FriendService(IO.pure(Some(client)), AgentMessagingConfig()))
        .guarantee(IO.blocking(server.stop(0)))
    }

  private def mkResources(fs: Option[FriendService]): SharedResources =
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "gmsgsend-origin-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = fs
    )

  private def mkRoutes(fs: Option[FriendService]): RestApiRoutes =
    val config = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](config),
      sharedResources = mkResources(fs),
      sessionStore = null,
      wsRoutes = null,
      neblinkService = None
    )

  private def authed(req: Request[IO]): Request[IO] =
    req.withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  /** 逐字字节体（不经 Json AST 编码器）：逐字转发判据必须在**字节层**成立。 */
  private def rawBodyReq(method: Method, path: String, raw: String): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))
      .withBodyStream(fs2.Stream.emit(raw).through(fs2.text.utf8.encode))

  private def runWith(fs: Option[FriendService])(req: Request[IO]): IO[Response[IO]] =
    mkRoutes(fs).routes(req).value.map(_.getOrElse(fail(s"route fell through: ${req.method} ${req.uri}")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  private val SendPath = "/groups/grp-1/messages"

  // ═══════════ ① origin 闸：UI 面不可能产出 agent 标识 ═══════════

  test("§8.1(a)：UI 体带 origin:\"agent\" ⇒ 400 显式拒绝 + **零上游往返**") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(
          authed(rawBodyReq(Method.POST, SendPath, """{"body":"hi","origin":"agent"}"""))
        ).flatMap { resp =>
          IO {
            assertEquals(resp.status, Status.BadRequest, "🔴 越界自报必须显式拒绝（不是静默改写、不是放行）")
            assertEquals(seenList, Nil, "🔴 拒绝必须发生在**上游往返之前**")
          } *> resp.as[Json].map { body =>
            val msg = body.hcursor.get[String]("error").toOption.getOrElse("")
            assert(msg.contains("origin"), s"拒绝文案必须点名 origin: $msg")
            assert(msg.contains("cannot be set here"), s"拒绝文案必须说明本路由不得设 origin: $msg")
          }
        }
    }
  }

  test("§8.1(a)：同一闸覆盖其他任何非 \"user\" 值（含大小写变体与空串）") {
    withMockServer { (_, client, fs) =>
      val bodies = List(
        """{"body":"hi","origin":"Agent"}""",
        """{"body":"hi","origin":"user2"}""",
        """{"body":"hi","origin":""}"""
      )
      client.login("d1", "dev", "macos", Nil) *>
        bodies.traverse_(b =>
          runWith(Some(fs))(authed(rawBodyReq(Method.POST, SendPath, b))).map { resp =>
            assertEquals(resp.status, Status.BadRequest, s"$b 必须被拒")
          }
        ) *> IO {
          assertEquals(seenList, Nil, "三条拒绝全部零上游往返")
        }
    }
  }

  test("§8.1(a) 对照臂：非 JSON 体 / 空体**不**由本层拒（禁复制服务端校验）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(authed(rawBodyReq(Method.POST, SendPath, "not-json"))).flatMap { resp =>
          IO {
            assertEquals(resp.status, Status.Created, "非 JSON 体原样转发，由服务端自己的校验序判")
            assertEquals(seenList, List(s"POST /api/groups/grp-1/messages"))
            assertEquals(seenBodies.toArray(Array.empty[String]).toList, List("not-json"))
          }
        }
    }
  }

  // ═══════════ ② 逐字转发性质保住（gwroutes 批判据不退化） ═══════════

  test("② 合法体逐字节转发：未知键保留、键序不变（无 origin 的常见形态）") {
    val raw = """{"zzz_unknown":42,"body":"hi","attachments":["a1"]}"""
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(authed(rawBodyReq(Method.POST, SendPath, raw))).flatMap { resp =>
          IO {
            assertEquals(resp.status, Status.Created, "上游 201 逐字回传")
            assertEquals(seenBodies.toArray(Array.empty[String]).toList, List(raw), "上游字节必须与客户端发出的逐字相同")
          }
        }
    }
  }

  test("② origin:\"user\" / null 亦逐字转发（= 服务端缺省语义，闸不误伤）") {
    val raws = List("""{"body":"hi","origin":"user"}""", """{"body":"hi","origin":null}""")
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        raws.zipWithIndex.traverse_ { case (raw, i) =>
          runWith(Some(fs))(authed(rawBodyReq(Method.POST, SendPath, raw))).flatMap { resp =>
            IO {
              assertEquals(resp.status, Status.Created)
              assertEquals(
                seenBodies.toArray(Array.empty[String]).toList,
                raws.take(i + 1),
                s"第 ${i + 1} 条合法体必须逐字到达上游"
              )
            }
          }
        }
    }
  }

  // ═══════════ ③ 共端点事实 + 纯函数判据表 ═══════════

  test("③ 共端点：UI 腿代理到 POST /api/groups/{id}/messages（与 agent 腿同一上游端点）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(authed(rawBodyReq(Method.POST, SendPath, """{"body":"hi"}"""))).flatMap { resp =>
          IO {
            assertEquals(resp.status, Status.Created)
            assertEquals(seenList, List("POST /api/groups/grp-1/messages"))
          }
        }
    }
  }

  test("GroupSendOriginVerdict.check 判据表（纯函数）：只认逐字 \"user\"") {
    assertEquals(GroupSendOriginVerdict.check(""), Right(()))
    assertEquals(GroupSendOriginVerdict.check("""{"body":"hi"}"""), Right(()))
    assertEquals(GroupSendOriginVerdict.check("""{"body":"hi","origin":null}"""), Right(()))
    assertEquals(GroupSendOriginVerdict.check("""{"body":"hi","origin":"user"}"""), Right(()))
    assertEquals(GroupSendOriginVerdict.check("not json"), Right(()))
    assert(GroupSendOriginVerdict.check("""{"origin":"agent"}""").isLeft)
    assert(GroupSendOriginVerdict.check("""{"origin":"User"}""").isLeft, "大小写变体不是服务端枚举值 ⇒ 拒绝（更早更明确）")
    assert(GroupSendOriginVerdict.check("""{"origin":1}""").isLeft, "非字符串 origin 同样拒绝")
  }

end GroupSendOriginGateSpec
