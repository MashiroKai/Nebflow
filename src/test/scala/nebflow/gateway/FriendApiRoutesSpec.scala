package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelChainConfig, ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.neblink.{AgentMessagingConfig, FriendService, NeblinkClient, NeblinkServerConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A2A 好友/消息 REST 端点契约测试（#290 gateway 接线）。
 *
 * 端到端链路：RestApiRoutes /api/friends* → FriendService → NeblinkClient →
 * JDK HttpServer mock（模拟 neblink-server）。钉死：
 *  1. auth 门禁（无 token → 403）
 *  2. NebLink 未配置 → 404 "NebLink not enabled"
 *  3. 各端点把上游 JSON 透传给前端（friends/conversations/messages/send/read/lookup）
 *  4. 路由顺序：/friends/requests 不被 /friends/:id 吞掉
 *
 * 时序注意：munit 的 IO 体在返回后才执行——mock server 的 stop 必须挂在 IO 的
 * guarantee 上，绝不能写在同步 finally 里（否则 IO 运行时 server 已死）。
 */
class FriendApiRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-123"

  // ── mock neblink-server ─────────────────────────────────

  private def startMockServer: (HttpServer, String) =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val conversationsJson =
      """[{"conversationId":"c1","friend":{"userId":"u1","neblinkId":"lin@example.com","name":"林小满"},"lastMessage":null,"unreadCount":2}]"""
    val messagesJson =
      """[{"id":1,"senderId":"u1","kind":"text","body":"hi","createdAt":1234567890}]"""

    def respond(ex: HttpExchange, status: Int, body: String): Unit =
      // Drain the request body first — JDK HttpServer keep-alive requires the
      // handler to consume it, otherwise leftover bytes corrupt the next
      // request parsed on the same connection (login POST then listFriends GET).
      ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
      ex.getRequestBody.close()
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
    // Rust 现行 wire 形态（neblink-server model.rs）：请求条目 #[serde(flatten)]
    // 铺平（无 from/to 嵌套）、profile 字段可 null、拉黑行带 blocked 标志——
    // 网关必须容错解码并把 from/to 嵌套出参契约维持给 web（0904 审计修复）。
    val friendsJson =
      """{"friends":[{"userId":"u1","neblinkId":"lin@example.com","name":"林小满","blocked":false},
         {"userId":"u2","neblinkId":null,"name":null,"blocked":true}],
         "incoming":[{"requestId":"rq-9","userId":"u-new","neblinkId":"newbie42","name":"新同学","note":"你好","createdAt":1234560000}],
         "outgoing":[]}""".stripMargin.replaceAll("\\n\\s*", "")
    server.createContext(
      "/api/friends",
      ex =>
        val path = ex.getRequestURI.getPath
        val method = ex.getRequestMethod
        (method, path) match
          case ("GET", "/api/friends") | ("GET", "/api/friends/requests") =>
            respond(ex, 200, friendsJson)
          case ("POST", "/api/friends/requests") =>
            respond(ex, 200, """{"requestId":"rq-1"}""")
          case ("POST", p) if p.endsWith("/accept") =>
            respond(ex, 200, """{"friendshipId":"fs-1","conversationId":"c1"}""")
          case ("POST", p) if p.endsWith("/decline") =>
            respond(ex, 200, """{"ok":true}""")
          case ("POST", p) if p.endsWith("/unblock") && p.contains("forbidden-guy") =>
            // 非拉黑方上游拒绝形态（#290 域 A 错误透传路径）
            respond(ex, 403, """{"error":"not_blocker"}""")
          case ("POST", p) if p.endsWith("/block") || p.endsWith("/unblock") =>
            respond(ex, 200, """{"ok":true}""")
          case ("POST", p) if p.endsWith("/messages") =>
            respond(ex, 200, """{"messageId":5,"conversationId":"c1","createdAt":1234567899}""")
          case ("DELETE", _) => respond(ex, 200, """{"ok":true}""")
          case _ => respond(ex, 404, """{"error":"not found"}""")
    )
    server.createContext(
      "/api/conversations",
      ex =>
        val path = ex.getRequestURI.getPath
        val method = ex.getRequestMethod
        (method, path) match
          case ("GET", "/api/conversations") => respond(ex, 200, conversationsJson)
          case ("GET", p) if p.endsWith("/messages") => respond(ex, 200, messagesJson)
          case ("POST", p) if p.endsWith("/read") => respond(ex, 200, """{"ok":true}""")
          case _ => respond(ex, 404, """{"error":"not found"}""")
    )
    server.createContext(
      "/api/users/lookup",
      ex => respond(ex, 200, """{"found":true,"neblinkId":"lin@example.com","name":"林小满"}""")
    )
    // [U3] NL 号自定义 + 可用性检测（0904 批次新增代理路由的上游形态）
    server.createContext(
      "/api/users/me/neblink-id/available",
      ex =>
        val q = Option(ex.getRequestURI.getQuery).getOrElse("")
        if q.contains("takenid") then respond(ex, 200, """{"available":false,"reason":"taken"}""")
        else respond(ex, 200, """{"available":true}""")
    )
    server.createContext(
      "/api/users/me/neblink-id",
      ex =>
        if ex.getRequestMethod == "PUT" then respond(ex, 200, """{"neblinkId":"newid42"}""")
        else respond(ex, 404, """{"error":"not found"}""")
    )
    server.start()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    (server, url)

  /** Server lifetime tied to the test IO — see class doc for the timing trap. */
  private def withMockServer[A](use: (String, NeblinkClient, FriendService) => IO[A]): IO[A] =
    IO.delay(startMockServer).flatMap { (server, url) =>
      val client = new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)
      use(url, client, new FriendService(client, AgentMessagingConfig()))
        .guarantee(IO.blocking(server.stop(0)))
    }

  // ── routes under test ───────────────────────────────────

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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "friend-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = fs
    )

  private def mkRoutes(fs: Option[FriendService]): RestApiRoutes =
    val config = NebflowServiceConfig(
      llm = ServiceLlmConfig(providers = Map.empty) // #339：llm.model 已退役
    )
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](config),
      sharedResources = mkResources(fs),
      sessionStore = null,
      wsRoutes = null
    )

  private def authed(req: Request[IO]): Request[IO] =
    req.withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def runWith(fs: Option[FriendService])(req: Request[IO]): IO[Response[IO]] =
    mkRoutes(fs).routes(req).value.map(_.getOrElse(fail("route fell through")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  // ── tests ───────────────────────────────────────────────

  test("auth gate: no token -> 403") {
    // No server needed: withAuth rejects before the friendService is touched.
    val fs = new FriendService(mkClient("http://127.0.0.1:1"), AgentMessagingConfig())
    runWith(Some(fs))(Request[IO](Method.GET, Uri.unsafeFromString("/friends")))
      .map(resp => assertEquals(resp.status, Status.Forbidden))
  }

  test("friendService absent -> 404 NebLink not enabled") {
    runWith(None)(authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends"))))
      .flatMap { resp =>
        assertEquals(resp.status, Status.NotFound)
        resp.as[Json].map(body =>
          assert(body.hcursor.downField("error").as[String].exists(_ == "NebLink not enabled"))
        )
      }
  }

  test("GET /friends proxies upstream friend list") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body =>
          assertEquals(
            body.hcursor.downField("friends").downArray.downField("userId").as[String].toOption,
            Some("u1")
          )
        )
      }
    }
  }

  test("GET /friends/requests normalizes flat upstream into nested from-shape") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends/requests")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          // 路由顺序：/friends/requests 不被 /friends/:id 吞掉（同时验证
          // flat → from 嵌套的归一化——Rust flatten 形态不再毁掉解码）
          val incoming = body.hcursor.downField("incoming").as[Vector[Json]].getOrElse(Vector.empty)
          assertEquals(incoming.size, 1)
          assertEquals(incoming.head.hcursor.downField("from").downField("userId").as[String].toOption, Some("u-new"))
          assertEquals(incoming.head.hcursor.downField("createdAt").as[Long].toOption, Some(1234560000L))
        }
      }
    }
  }

  test("GET /friends passes blocked flag and tolerates null profile fields") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val friends = body.hcursor.downField("friends").as[Vector[Json]].getOrElse(Vector.empty)
          assertEquals(friends.size, 2)
          // null neblinkId/name 折叠为空串，不毁整表；blocked 透传给 web 灰显
          assertEquals(friends(1).hcursor.downField("neblinkId").as[String].toOption, Some(""))
          assertEquals(friends(1).hcursor.downField("blocked").as[Boolean].toOption, Some(true))
        }
      }
    }
  }

  test("GET /conversations proxies conversation summaries") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/conversations")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val convs = body.asArray.getOrElse(Vector.empty)
          assertEquals(convs.size, 1)
          assertEquals(convs.head.hcursor.downField("conversationId").as[String].toOption, Some("c1"))
          assertEquals(convs.head.hcursor.downField("unreadCount").as[Int].toOption, Some(2))
        }
      }
    }
  }

  test("POST /friends/:id/messages sends as user and returns upstream body") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
      runWith(Some(fs))(
        authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/u1/messages")))
          .withEntity(Json.obj("body" -> "hello".asJson))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("messageId").as[Long].toOption, Some(5L))
          assertEquals(body.hcursor.downField("conversationId").as[String].toOption, Some("c1"))
        }
      }
    }
  }

  test("GET /conversations/:id/messages passes after/limit and returns message array") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/conversations/c1/messages?after=0&limit=50")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val msgs = body.asArray.getOrElse(Vector.empty)
          assertEquals(msgs.size, 1)
          assertEquals(msgs.head.hcursor.downField("body").as[String].toOption, Some("hi"))
        }
      }
    }
  }

  test("POST /conversations/:id/read marks read") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
      runWith(Some(fs))(
        authed(Request[IO](Method.POST, Uri.unsafeFromString("/conversations/c1/read")))
          .withEntity(Json.obj("lastReadMessageId" -> Json.fromLong(1L)))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
        )
      }
    }
  }

  test("GET /users/lookup?q= proxies lookup result") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/lookup?q=lin%40example.com")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("found").as[Boolean].toOption, Some(true))
        )
      }
    }
  }

  test("lookup without q -> 400") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/lookup")))
      ).map(resp => assertEquals(resp.status, Status.BadRequest))
    }
  }

  test("PUT /users/me/neblink-id proxies custom id ([U3])") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
      runWith(Some(fs))(
        authed(Request[IO](Method.PUT, Uri.unsafeFromString("/users/me/neblink-id")))
          .withEntity(Json.obj("neblinkId" -> "newid42".asJson))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("neblinkId").as[String].toOption, Some("newid42"))
        )
      }
    }
  }

  test("PUT /users/me/neblink-id without neblinkId -> 400") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
      runWith(Some(fs))(
        authed(Request[IO](Method.PUT, Uri.unsafeFromString("/users/me/neblink-id")))
          .withEntity(Json.obj())
      ).map(resp => assertEquals(resp.status, Status.BadRequest))
    }
  }

  test("GET /users/me/neblink-id/available proxies availability ([U3])") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
      runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/me/neblink-id/available?q=takenid")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("available").as[Boolean].toOption, Some(false))
          assertEquals(body.hcursor.downField("reason").as[String].toOption, Some("taken"))
        }
      }
    }
  }

  test("POST /friends/:id/block proxies upstream ok") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/u1/block")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
        )
      }
    }
  }

  test("POST /friends/:id/unblock proxies upstream ok") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/u1/unblock")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true))
        )
      }
    }
  }

  test("POST /friends/:id/unblock upstream 403 not_blocker -> 502 with error passthrough") {
    // 既有代理模式（decline/remove 同族）：上游非 2xx 在 NeblinkClient 折叠为
    // Left("HTTP 403: ...")，网关层统一 502 + error 字符串——错误体内容随行透传。
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/forbidden-guy/unblock")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.BadGateway)
        resp.as[Json].map(body =>
          assert(body.hcursor.downField("error").as[String].exists(_.contains("not_blocker")))
        )
      }
    }
  }

  private def mkClient(url: String): NeblinkClient =
    new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)

end FriendApiRoutesSpec
