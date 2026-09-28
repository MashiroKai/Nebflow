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
 * 群组一期**网关代理腿**契约测试（gwroutes 批，2026-09-15）。
 *
 * 契约真源 = 跨仓 neblink-server `main`@`9e811ffc` `src/groups.rs:616-639`
 * （**11 条 `.route()` / 12 个 method+path 对**）。端到端链路与好友面同构：
 * RestApiRoutes `/api/groups*` → FriendService.groupProxy → NeblinkClient
 * .proxyWithStatus → JDK HttpServer mock（模拟 neblink-server）。
 *
 * 钉死四件事：
 *  1. **路由对表**：12 个 method+path 对逐条到达上游（方法与路径逐字），
 *     且静态段 `/groups/invites` 不被 `/groups/{groupId}` 形态吞掉；
 *  2. **鉴权透传**：网关 `withAuth` 先于上游往返（无 token ⇒ 403 零外发）；
 *     身份只由 `Authorization: Bearer <session token>` 承载（**零** sender/uid 自定义头）；
 *  3. **错误码逐字透传**：`404 group_not_found` / `403 group_disbanded` /
 *     `403 not_member` 原样到达客户端（**不**折成 502/500、**不**折成空成功）；
 *  4. **出参形态零改写**：`GET /api/groups` 裸数组、`GET /api/groups/invites`
 *     `{incoming:[…]}`、`{members:[…]}` 三形态逐字；请求体原样转发（未知键不丢、键不改名）。
 *
 * 时序注意（同 FriendApiRoutesSpec）：munit 的 IO 体在返回后才执行——mock server 的
 * stop 必须挂在 IO 的 guarantee 上，绝不能写在同步 finally 里。
 */
class GroupApiRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-123"

  // ── 上游观测点（mock 侧记录「网关到底发了什么」）─────────────
  private val seen = new ConcurrentLinkedQueue[String]()
  private val seenBodies = new ConcurrentLinkedQueue[String]()
  private val seenAuth = new AtomicReference[Option[String]](None)

  private def clearSeen(): Unit =
    seen.clear(); seenBodies.clear(); seenAuth.set(None)

  /** 上游收到的 `[METHOD path]` 清单（读数用）。 */
  private def seenList: List[String] = seen.toArray(Array.empty[String]).toList

  // ── mock neblink-server ─────────────────────────────────

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

    // 上游群路由表 —— 与 groups.rs 的 handler 出参形态逐字同形（camelCase 信封；
    // 成员行沿用 FriendPublic 的 snake_case 档案钉法）。
    val groupRow =
      """{"groupId":"grp-1","title":"团队","role":"owner","memberCount":2,"lastMessage":null,
         "unreadCount":0,"lastMessageId":0,"createdAt":1}""".stripMargin.replaceAll("\\n\\s*", "")
    val membersJson =
      """{"members":[{"userId":"u1","username":"lin","display_name":"林小满","avatar":null,
         "role":"owner","joinedAt":1}]}""".stripMargin.replaceAll("\\n\\s*", "")
    val invitesJson =
      """{"incoming":[{"inviteId":"inv-1","groupId":"grp-1","title":"团队",
         "inviter":{"userId":"u9","username":"boss","display_name":"老板","avatar":null},
         "createdAt":1}]}""".stripMargin.replaceAll("\\n\\s*", "")
    val sendJson =
      """{"messageId":5,"conversationId":"grp-1","createdAt":1234567899,"createdAtMs":1234567899000,
         "existing":false,"attachments":[]}""".stripMargin.replaceAll("\\n\\s*", "")

    server.createContext(
      "/api/groups",
      ex =>
        val path = ex.getRequestURI.getPath
        val method = ex.getRequestMethod
        // 先读体再应答（JDK HttpServer keep-alive 要求 handler 消费完请求体）。
        val reqBody = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        seen.add(s"$method $path")
        seenBodies.add(reqBody)
        seenAuth.set(Option(ex.getRequestHeaders.getFirst("Authorization")))

        // 错误码注入哨兵（三错误码各一例；路径含哨兵即回对应码）。
        if path.contains("gone-group") then respond(ex, 404, """{"error":"group_not_found"}""")
        else if path.contains("dead-group") then respond(ex, 403, """{"error":"group_disbanded"}""")
        else if path.contains("foreign-group") then respond(ex, 403, """{"error":"not_member"}""")
        else
          (method, path) match
            case ("GET", "/api/groups")                          => respond(ex, 200, s"[$groupRow]")
            case ("POST", "/api/groups")                         => respond(ex, 201, """{"groupId":"grp-1","title":"团队","createdAt":1}""")
            case ("GET", "/api/groups/invites")                  => respond(ex, 200, invitesJson)
            case ("POST", p) if p.endsWith("/invites")           => respond(ex, 201, """{"inviteId":"inv-1","groupId":"grp-1","inviteeUserId":"u2","status":"pending","createdAt":1}""")
            case ("POST", p) if p.endsWith("/accept")            => respond(ex, 200, """{"ok":true,"groupId":"grp-1","title":"团队"}""")
            case ("POST", p) if p.endsWith("/decline")           => respond(ex, 200, """{"ok":true,"groupId":"grp-1","title":null}""")
            case ("GET", p) if p.endsWith("/members")            => respond(ex, 200, membersJson)
            case ("POST", p) if p.endsWith("/kick")              => respond(ex, 200, """{"ok":true}""")
            case ("POST", p) if p.endsWith("/messages")          => respond(ex, 201, sendJson)
            case ("POST", p) if p.endsWith("/leave")             => respond(ex, 200, """{"ok":true}""")
            case ("PUT", p) if p.endsWith("/title")              => respond(ex, 200, """{"ok":true,"title":"新名字"}""")
            case ("DELETE", _)                                   => respond(ex, 200, """{"ok":true}""")
            case _                                               => respond(ex, 404, """{"error":"not found"}""")
    )
    server.start()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    (server, url)

  /** Server lifetime tied to the test IO（见类头时序注意）。 */
  private def withMockServer[A](use: (String, NeblinkClient, FriendService) => IO[A]): IO[A] =
    IO.delay { clearSeen(); startMockServer }.flatMap { (server, url) =>
      val client = new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)
      use(url, client, new FriendService(IO.pure(Some(client)), AgentMessagingConfig()))
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "group-spec-archives"),
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

  /** 逐字字节体（**不经** Json AST 编码器）：代理腿的「原样转发」判据必须在字节层
    * 成立（Json 编码会重排键 ⇒ 那就不叫逐字）。 */
  private def rawBodyReq(method: Method, path: String, raw: String): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))
      .withBodyStream(fs2.Stream.emit(raw).through(fs2.text.utf8.encode))

  private def runWith(fs: Option[FriendService])(req: Request[IO]): IO[Response[IO]] =
    mkRoutes(fs).routes(req).value.map(_.getOrElse(fail(s"route fell through: ${req.method} ${req.uri}")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  // ── 路由对表（server groups.rs:616-639 ⇄ 本层）────────────

  /** `(上游方法, 上游路径, 网关请求路径, 可选请求体)` —— 12 个 method+path 对，逐条来自
    * `groups.rs`：`.route("/api/groups", post(group_create).get(group_list))`
    * （一条注册两个方法）+ 其余 10 条各一方法。**顺序即 groups.rs 的声明顺序**。 */
  private val RouteTable: List[(Method, String, String, Option[String])] = List(
    (Method.POST, "/api/groups", "/groups", Some("""{"title":"团队"}""")),
    (Method.GET, "/api/groups", "/groups", None),
    (Method.GET, "/api/groups/invites", "/groups/invites", None),
    (Method.POST, "/api/groups/grp-1/invites", "/groups/grp-1/invites", Some("""{"userId":"u2"}""")),
    (Method.POST, "/api/groups/grp-1/invites/inv-1/accept", "/groups/grp-1/invites/inv-1/accept", None),
    (Method.POST, "/api/groups/grp-1/invites/inv-1/decline", "/groups/grp-1/invites/inv-1/decline", None),
    (Method.GET, "/api/groups/grp-1/members", "/groups/grp-1/members", None),
    (Method.POST, "/api/groups/grp-1/members/u2/kick", "/groups/grp-1/members/u2/kick", None),
    (Method.POST, "/api/groups/grp-1/messages", "/groups/grp-1/messages", Some("""{"body":"hi"}""")),
    (Method.POST, "/api/groups/grp-1/leave", "/groups/grp-1/leave", None),
    (Method.PUT, "/api/groups/grp-1/title", "/groups/grp-1/title", Some("""{"title":"新名字"}""")),
    (Method.DELETE, "/api/groups/grp-1", "/groups/grp-1", None)
  )

  test("路由对表：12 个 method+path 对逐条到达上游（方法 + 路径逐字）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        RouteTable.traverse_ { case (m, upPath, gwPath, body) =>
          IO(clearSeen()) *>
            runWith(Some(fs))(
              body match
                case Some(b) => authed(rawBodyReq(m, gwPath, b))
                case None    => authed(Request[IO](m, Uri.unsafeFromString(gwPath)))
            ).flatMap { resp =>
              IO {
                val got = seenList
                val want = s"${m.name} $upPath"
                if got != List(want) then
                  fail(
                    s"路由对表失配：网关 ${m.name} $gwPath ⇒ 期望上游收到 [$want]，实际收到 $got（status=${resp.status}）"
                  )
              }
            }
        }
    }
  }

  test("路由顺序：静态段 /groups/invites 不被 /groups/{groupId} 形态吞掉") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/groups/invites")))
      ).flatMap { resp =>
        IO {
          assertEquals(seenList, List("GET /api/groups/invites"))
          assertEquals(resp.status, Status.Ok)
        } *> resp.as[Json].map { body =>
          // 邀请发现形态（groups.rs:178-200 / model.rs:831-836）：{incoming:[…]} 逐字
          val inc = body.hcursor.downField("incoming").downArray
          assertEquals(inc.downField("inviteId").as[String].toOption, Some("inv-1"))
          assertEquals(inc.downField("groupId").as[String].toOption, Some("grp-1"))
          assertEquals(inc.downField("title").as[String].toOption, Some("团队"))
          val inviter = inc.downField("inviter")
          assertEquals(inviter.downField("userId").as[String].toOption, Some("u9"))
          assertEquals(inviter.downField("username").as[String].toOption, Some("boss"))
          assertEquals(inviter.downField("display_name").as[String].toOption, Some("老板"))
          assertEquals(inviter.downField("avatar").focus.map(_.isNull), Some(true))
          assertEquals(inc.downField("createdAt").as[Int].toOption, Some(1))
        }
      }
    }
  }

  // ── 出参形态逐字（禁网关 reshape）─────────────────────────

  test("GET /groups 裸数组形态逐字（GroupSummary camelCase 七字段；不得改成信封）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/groups")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assert(body.isArray, s"GET /api/groups 必须是裸数组（groups.rs:168-169 约定），实际 = $body")
          val row = body.asArray.get.head.hcursor
          assertEquals(row.downField("groupId").as[String].toOption, Some("grp-1"))
          assertEquals(row.downField("title").as[String].toOption, Some("团队"))
          assertEquals(row.downField("role").as[String].toOption, Some("owner"))
          assertEquals(row.downField("memberCount").as[Int].toOption, Some(2))
          assertEquals(row.downField("unreadCount").as[Int].toOption, Some(0))
          assertEquals(row.downField("lastMessageId").as[Int].toOption, Some(0))
          assertEquals(row.downField("createdAt").as[Int].toOption, Some(1))
          // 网关**不得**增删字段（无 invites/pendingInvites 之类自创信封键）
          assertEquals(body.asArray.get.head.asObject.map(_.keys.toSet),
            Some(Set("groupId", "title", "role", "memberCount", "lastMessage", "unreadCount", "lastMessageId", "createdAt")))
        }
      }
    }
  }

  test("GET /groups/{id}/members 信封形态逐字（{members:[…]}；档案字段沿用 FriendPublic 钉法）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/groups/grp-1/members")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val m = body.hcursor.downField("members").downArray
          assertEquals(m.downField("userId").as[String].toOption, Some("u1"))
          // 档案四字段 snake_case（FriendPublic 钉法）原样到达——网关零字段映射
          assertEquals(m.downField("username").as[String].toOption, Some("lin"))
          assertEquals(m.downField("display_name").as[String].toOption, Some("林小满"))
          assertEquals(m.downField("role").as[String].toOption, Some("owner"))
          assertEquals(m.downField("joinedAt").as[Int].toOption, Some(1))
        }
      }
    }
  }

  // ── 鉴权 ────────────────────────────────────────────────

  test("鉴权透传：Bearer session token 逐字上行（零 sender/uid 自定义头）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/groups")))
      ).map { resp =>
        assertEquals(resp.status, Status.Ok)
        assertEquals(seenAuth.get(), Some("Bearer tok-1"),
          "身份只由 session token 承载（与既有 friends/conversations 代理逐字一致）")
      }
    }
  }

  test("auth gate：无 token ⇒ 403 且零上游外发") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        Request[IO](Method.GET, Uri.unsafeFromString("/groups"))
      ).map { resp =>
        assertEquals(resp.status, Status.Forbidden)
        assertEquals(seenList, Nil, "withAuth 必须先于任何上游往返")
      }
    }
  }

  test("friendService 缺席 ⇒ 404 NebLink not enabled（fail-closed 承重墙：客户端读作 neblinkOff）") {
    runWith(None)(authed(Request[IO](Method.GET, Uri.unsafeFromString("/groups"))))
      .flatMap { resp =>
        assertEquals(resp.status, Status.NotFound)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("NebLink not enabled"))
        )
      }
  }

  // ── 错误码逐字透传（客户端按语义码分态）──────────────────

  test("三错误码逐字透传：404 group_not_found / 403 group_disbanded / 403 not_member") {
    withMockServer { (_, client, fs) =>
      val cases = List(
        ("gone-group", Status.NotFound, "group_not_found"),
        ("dead-group", Status.Forbidden, "group_disbanded"),
        ("foreign-group", Status.Forbidden, "not_member")
      )
      client.login("d1", "dev", "macos", Nil) *>
        cases.traverse_ { case (gid, wantStatus, wantCode) =>
          IO(clearSeen()) *>
            runWith(Some(fs))(
              authed(Request[IO](Method.GET, Uri.unsafeFromString(s"/groups/$gid/members")))
            ).flatMap { resp =>
              IO {
                assertEquals(resp.status, wantStatus, s"$gid 的状态码必须逐字透传（禁折成 502/500）")
              } *> resp.as[Json].map { body =>
                assertEquals(body.hcursor.downField("error").as[String].toOption, Some(wantCode),
                  s"$gid 的语义码必须原样到达（禁折成空成功/泛化错误）")
              }
            }
        }
    }
  }

  test("消息腿错误码：群发路径同样逐字透传（404/403 不折叠）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(
          authed(rawBodyReq(Method.POST, "/groups/dead-group/messages", """{"body":"hi"}"""))
        ).flatMap { resp =>
          assertEquals(resp.status, Status.Forbidden)
          resp.as[Json].map(body =>
            assertEquals(body.hcursor.downField("error").as[String].toOption, Some("group_disbanded"))
          )
        }
    }
  }

  // ── 请求体原样转发（代理不解析 ⇒ 未知键不丢、键不改名）────

  test("请求体逐字转发：未知键保留、键序不变、不解析不重编码") {
    val raw = """{"zzz_unknown":42,"body":"hi","attachments":["a1"]}"""
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(
          authed(rawBodyReq(Method.POST, "/groups/grp-1/messages", raw))
        ).flatMap { resp =>
          IO {
            assertEquals(resp.status, Status.Created)
            assertEquals(seenBodies.toArray(Array.empty[String]).toList, List(raw),
              "上游收到的字节必须与客户端发出的逐字相同（解析后重编码会重排键/丢未知键）")
          }
        }
    }
  }

  test("服务端 201 建群响应逐字回传（status 201 不折成 200）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(
          authed(rawBodyReq(Method.POST, "/groups", """{"title":"团队"}"""))
        ).flatMap { resp =>
          assertEquals(resp.status, Status.Created, "上游 201 必须保持 201")
          resp.as[Json].map(body =>
            assertEquals(body.hcursor.downField("groupId").as[String].toOption, Some("grp-1"))
          )
        }
    }
  }

end GroupApiRoutesSpec
