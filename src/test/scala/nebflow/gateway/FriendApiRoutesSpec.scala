package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelChainConfig, ModelCandidate, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.neblink.{AgentMessagingConfig, FriendService, NeblinkClient, NeblinkServerConfig, NeblinkService}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * A2A 好友/消息 REST 端点契约测试（#290 gateway 接线）。
 *
 * 端到端链路：RestApiRoutes /api/friends* → FriendService → NeblinkClient →
 * JDK HttpServer mock（模拟 neblink-server）。钉死：
 *  1. auth 门禁（无 token → 403）
 *  2. NebLink 未配置 → 404 "NebLink not enabled"
 *  3. friends/conversations 出参 = 契约档案四字段 snake_case（username /
 *     display_name / avatar，信封 camelCase 维持——friend-search-contract
 *     v1.0 §4.5 切换面）；messages/send/read/lookup 仍为上游透传
 *  4. 路由顺序：/friends/requests 不被 /friends/:id 吞掉
 *
 * 时序注意：munit 的 IO 体在返回后才执行——mock server 的 stop 必须挂在 IO 的
 * guarantee 上，绝不能写在同步 finally 里（否则 IO 运行时 server 已死）。
 */
class FriendApiRoutesSpec extends CatsEffectSuite:

  private val TestToken = "test-token-123"

  // ── P2-b 读数面（幂等键加性透传）─────────────────────────────────────
  // 上游侧**收到的原始请求体**（逐字，不解析后再编码——加性判据要按字节比）。
  private val sentBodies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  // 模拟服务端的幂等台账：`clientMsgId` → 首次落行时回给客户端的响应体。
  private val idemRows = new java.util.concurrent.ConcurrentHashMap[String, String]()
  // 模拟服务端的自增 messageId（首行 = 5，与既有断言同锚）。
  private val mockRowSeq = new java.util.concurrent.atomic.AtomicInteger(5)

  // 401/404 配置判据需要真实的 NeblinkService（live config ref）⇒ 隔离 dataRoot，
  // 否则 `NeblinkService.create` 会读写真实 ~/.nebflow（device.json / config.json）。
  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("friend-api-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    sentBodies.clear()
    idemRows.clear()
    mockRowSeq.set(5)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  /** 隔离 home 里的真实 NeblinkService（不联网：只建 config ref + identity）。
    * `configured = true` 表示用户配置过 / 登录过 NebLink（server 址在 live
    * config 里），`false` = 全新 home。 */
  private def withService[A](configured: Boolean)(body: NeblinkService => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      NeblinkService.create(0, dispatcher).flatMap { ms =>
        val seed =
          if configured then
            ms.updateConfig(_.copy(
              enabled = true,
              neblinkServer = Some(NeblinkServerConfig(url = "http://127.0.0.1:9", networkId = "n1", secret = "s"))
            ))
          else IO.unit
        seed *> body(ms)
      }
    }

  // ── mock neblink-server ─────────────────────────────────

  private def startMockServer: (HttpServer, String) =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    // 上游 wire = friend-search-contract v1.0（§4.5 统一切换）：档案四字段
    // 字面 snake_case（username 可 null / display_name 永不为 null / avatar
    // 可 null），信封字段 camelCase。契约切换非回归（§4.7）。
    val conversationsJson =
      """[{"conversationId":"c1","friend":{"userId":"u1","username":"lin","display_name":"林小满","avatar":null},"lastMessage":null,"unreadCount":2}]"""
    val messagesJson =
      """[{"id":1,"senderId":"u1","kind":"text","body":"hi","createdAt":1234567890}]"""

    /** 写响应（**不碰请求体**——请求体已由 [[readSentBody]] 读完的路径必须用它，
      *  否则二次 drain 已关闭的流会抛 IOException、handler 直接死掉不写响应，
      *  上游侧表现为 `header parser received no bytes`）。 */
    def writeJson(ex: HttpExchange, status: Int, body: String): Unit =
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      val os = ex.getResponseBody
      os.write(bytes)
      os.close()

    def respond(ex: HttpExchange, status: Int, body: String): Unit =
      // Drain the request body first — JDK HttpServer keep-alive requires the
      // handler to consume it, otherwise leftover bytes corrupt the next
      // request parsed on the same connection (login POST then listFriends GET).
      ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
      ex.getRequestBody.close()
      writeJson(ex, status, body)

    /** 读走并**记下**上游收到的请求体原文（P2-b 读数面）：先读后回（keep-alive 要求，
      *  见 respond 注），且**不**解析后再编码 —— 加性判据按**字节**比，重编码会掩盖
      *  键序 / 键集漂移（正是本批要钉的东西）。 */
    def readSentBody(ex: HttpExchange): String =
      val raw = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      ex.getRequestBody.close()
      sentBodies.add(raw)
      raw

    /** 服务端幂等语义镜像（§8.6：同 `clientMsgId` 重复 ⇒ 仍是成功、`existing:true`
      *  仅表示回放原行、**不新增行**）。带键且该键已落行 ⇒ 回放原行（同 messageId）；
      *  否则真落一行（自增 messageId）。 */
    def sendRow(raw: String): String =
      val key = io.circe.parser
        .parse(raw)
        .toOption
        .flatMap(_.hcursor.downField("clientMsgId").as[String].toOption)
        .filter(_.nonEmpty)
      key.flatMap(k => Option(idemRows.get(k))) match
        case Some(replayed) => replayed
        case None =>
          val out =
            s"""{"messageId":${mockRowSeq.getAndIncrement()},"conversationId":"c1","createdAt":1234567899}"""
          key.foreach(k => idemRows.put(k, out))
          out

    server.createContext(
      "/api/device/login",
      ex => respond(ex, 200, """{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}""")
    )
    // Rust 契约 wire 形态（neblink-server，friend-search-contract v1.0 §4.5）：
    // 请求条目 #[serde(flatten)] 铺平（无 from/to 嵌套）、档案字段 username 可
    // null / display_name 永不为 null、拉黑行带 blocked 标志——网关必须容错
    // 解码并把 from/to 嵌套 + 档案四字段 snake_case 出参契约维持给 web。
    val friendsJson =
      """{"friends":[{"userId":"u1","username":"lin","display_name":"林小满","avatar":"https://example.com/a.png","blocked":false},
         {"userId":"u2","username":null,"display_name":null,"avatar":null,"blocked":true,"remark":"上游不该出现的键"},
         {"userId":"u3","username":"customnl3","display_name":"三号","avatar":null}],
         "incoming":[{"requestId":"rq-9","userId":"u-new","username":"newbie42","display_name":"新同学","avatar":null,"note":"你好","createdAt":1234560000}],
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
            // P2-b：记原文 + 按 `clientMsgId` 幂等回放（服务端语义镜像）。
            // rcptcode 批（2026-09-20）：按**被寻址的 uid** 分派「上游拒绝 / 上游成功码」
            // 形态（一码一位）；其余 uid 保持恒 200 ⇒ 既有用例读数**零变化**。
            val raw = readSentBody(ex)
            p.stripPrefix("/api/friends/").stripSuffix("/messages") match
              case "up-403-notfriends" =>
                writeJson(ex, 403, """{"error":"not_friends"}""")
              case "up-403-notblocker" =>
                // 同域**另一枚**真实语义码：证明透传与码集无关（不是只放行 not_friends）。
                writeJson(ex, 403, """{"error":"not_blocker"}""")
              case "up-400-replyinvalid" =>
                writeJson(ex, 400, """{"error":"REPLY_TARGET_INVALID"}""")
              case "up-429" =>
                // 客户端**未知**码（走 fail-visible 回退）：透传面同样逐字。
                writeJson(ex, 429, """{"error":"upstream_rate_limited"}""")
              case "up-500" =>
                writeJson(ex, 500, """{"error":"server_error"}""")
              case "up-201" =>
                writeJson(ex, 201, sendRow(raw))
              case _ =>
                writeJson(ex, 200, sendRow(raw))
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
    // 群面（P2-b 读数用）：群发路由是**原文转发**腿（`groupSendProxy` 只判 origin），
    // 因此这里收到的请求体就是 web 客户端发的那串字节 —— 用来钉「加性键直达上游」。
    server.createContext(
      "/api/groups",
      ex =>
        val path = ex.getRequestURI.getPath
        val method = ex.getRequestMethod
        (method, path) match
          case ("POST", p) if p.endsWith("/messages") =>
            writeJson(ex, 200, sendRow(readSentBody(ex)))
          case _ => respond(ex, 404, """{"error":"not found"}""")
    )
    server.createContext(
      "/api/users/lookup",
      ex =>
        // Username 统一（作者 2026-09-05 裁定）契约钉点：q 含 "newform" 时上游
        // 返回新契约形态（username/displayName/avatar），验证网关纯透传不变；
        // 其余 q 走旧形态（neblink-server 新 lookup 端点就绪前的兼容锚）。
        val q = Option(ex.getRequestURI.getQuery).getOrElse("")
        if q.contains("newform") then
          respond(ex, 200, """{"found":true,"userId":"u-nf","username":"newform","displayName":"新形态","avatar":"https://example.com/a.png","email":"nf@example.com"}""")
        else respond(ex, 200, """{"found":true,"neblinkId":"lin@example.com","name":"林小满"}""")
    )
    // 搜索（friend-search-contract §4.1 唯一入口）：hit → 契约命中形态（user 卡为
    // {username, display_name, avatar}，无 userId——按 §4.1 & neblink-server
    // SearchUserCard 字段集）；"ghost" → miss 恒 {found:false}。网关纯透传验证。
    server.createContext(
      "/api/users/search",
      ex =>
        val q = Option(ex.getRequestURI.getQuery).getOrElse("")
        if q.contains("ghost") then
          respond(ex, 200, """{"found":false}""")
        else
          respond(ex, 200, """{"found":true,"user":{"username":"alice42","display_name":"Alice","avatar":"https://example.com/a.png"},"relation_status":"addable"}""")
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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "friend-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = fs
    )

  private def mkRoutes(fs: Option[FriendService], ms: Option[NeblinkService] = None): RestApiRoutes =
    val config = NebflowServiceConfig(
      llm = ServiceLlmConfig(providers = Map.empty) // #339：llm.model 已退役
    )
    new RestApiRoutes(
      token = TestToken,
      configRef = cats.effect.Ref.unsafe[IO, NebflowServiceConfig](config),
      sharedResources = mkResources(fs),
      sessionStore = null,
      wsRoutes = null,
      neblinkService = ms
    )

  private def authed(req: Request[IO]): Request[IO] =
    req.withHeaders(Headers("Authorization" -> s"Bearer $TestToken"))

  private def runWith(fs: Option[FriendService], ms: Option[NeblinkService] = None)(
    req: Request[IO]
  ): IO[Response[IO]] =
    mkRoutes(fs, ms).routes(req).value.map(_.getOrElse(fail("route fell through")))

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  // ── tests ───────────────────────────────────────────────

  test("auth gate: no token -> 403") {
    // No server needed: withAuth rejects before the friendService is touched.
    val fs = new FriendService(IO.pure(Some(mkClient("http://127.0.0.1:1"))), AgentMessagingConfig())
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

  // ── 2026-09-11 boot 快照修复：未登录应答语义（R3(a)） ──────────────────

  test("configured + no session -> 401 neblink_not_logged_in (未登录不再与上游错共 502 面)") {
    // 已配置（server 址在 live config）但无会话 = 登出后 / 从未 enroll：
    // 「Not logged in」必须与「上游错」分态。401 与 withAuth 的 403 是有意的
    // 契约分化（同一端点族两种「未认证」），靠 code 字段消歧。
    withService(configured = true) { ms =>
      val fs = new FriendService(IO.pure(None), AgentMessagingConfig())
      runWith(Some(fs), Some(ms))(authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends"))))
        .flatMap { resp =>
          assertEquals(resp.status, Status.Unauthorized)
          resp.as[Json].map { body =>
            assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Not logged in"))
            assertEquals(body.hcursor.downField("code").as[String].toOption, Some("neblink_not_logged_in"))
          }
        }
    }
  }

  test("unconfigured + no session -> still 404 NebLink not enabled (neblinkOff 保持可表达)") {
    // blocking-1 回归闸：A 案（friendService 恒 Some）单独落地时，若没有这条
    // 配置判据，全新 home 会从 404 neblinkOff 变成 502 retryable（重试恒无效）
    // = 净回归。
    withService(configured = false) { ms =>
      val fs = new FriendService(IO.pure(None), AgentMessagingConfig())
      runWith(Some(fs), Some(ms))(authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends"))))
        .flatMap { resp =>
          assertEquals(resp.status, Status.NotFound)
          resp.as[Json].map(body =>
            assertEquals(body.hcursor.downField("error").as[String].toOption, Some("NebLink not enabled"))
          )
        }
    }
  }

  test("GET /friends proxies upstream friend list") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val row = body.hcursor.downField("friends").downArray
          assertEquals(row.downField("userId").as[String].toOption, Some("u1"))
          // 契约出参：档案四字段 snake_case 字面（friend-search-contract §4.5）
          assertEquals(row.downField("username").as[String].toOption, Some("lin"))
          assertEquals(row.downField("display_name").as[String].toOption, Some("林小满"))
          assertEquals(row.downField("avatar").as[String].toOption, Some("https://example.com/a.png"))
          // 旧 camelCase 档案字段零残留（统一切换，无双写别名期 §4.7）
          assertEquals(row.downField("name").as[Json].toOption, None)
          assertEquals(row.downField("avatarUrl").as[Json].toOption, None)
        }
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
          // flat → from 嵌套的归一化——Rust flatten 形态不再毁掉解码）；
          // 信封字段 camelCase 维持（requestId/note/createdAt）
          val incoming = body.hcursor.downField("incoming").as[Vector[Json]].getOrElse(Vector.empty)
          assertEquals(incoming.size, 1)
          assertEquals(incoming.head.hcursor.downField("from").downField("userId").as[String].toOption, Some("u-new"))
          assertEquals(incoming.head.hcursor.downField("from").downField("username").as[String].toOption, Some("newbie42"))
          assertEquals(incoming.head.hcursor.downField("from").downField("display_name").as[String].toOption, Some("新同学"))
          assertEquals(incoming.head.hcursor.downField("requestId").as[String].toOption, Some("rq-9"))
          assertEquals(incoming.head.hcursor.downField("note").as[String].toOption, Some("你好"))
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
          assertEquals(friends.size, 3)
          // null username 折叠为空串；display_name 必填语义——镜像服务端
          // fallback 链（name→username→user_id）折到 userId "u2"，不毁整表；
          // null avatar 保持 null（§4.0 可 null）；blocked 透传给 web 灰显
          assertEquals(friends(1).hcursor.downField("username").as[String].toOption, Some(""))
          assertEquals(friends(1).hcursor.downField("display_name").as[String].toOption, Some("u2"))
          assertEquals(friends(1).hcursor.downField("avatar").as[Option[String]].toOption, Some(None))
          assertEquals(friends(1).hcursor.downField("blocked").as[Boolean].toOption, Some(true))
          // ⑦（2026-09-12）：`remark` 键**恒在**（未设 ⇒ null，不是省略键），
          // 且 **Decoder 不读上游同名键**（u2 的上游 wire 带了 remark，出参必须仍是 null）
          assertEquals(friends(1).hcursor.downField("remark").focus.map(_.isNull), Some(true),
            "remark 键恒在（null 形态；focus=None 即键缺席=红）+ 上游同名键被忽略（备注是本地态）")
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
          // 内嵌 friend 档案 = 契约四字段 snake_case（§4.5 切换面）
          val friend = convs.head.hcursor.downField("friend")
          assertEquals(friend.downField("username").as[String].toOption, Some("lin"))
          assertEquals(friend.downField("display_name").as[String].toOption, Some("林小满"))
          assertEquals(friend.downField("avatar").as[Option[String]].toOption, Some(None))
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

  // ── P2-b：幂等键 `clientMsgId`（加性透传 + 服务端幂等镜像）────────────

  /** 好友发送面单次调用：**先钉响应状态**（200）再交响应体 —— 上游 mock 的 handler
    * 若抛掉不写响应，网关会折叠成 502，此时只比「响应体里的字段」会拿到 None == None
    * 的**假绿**；状态断言是唯一能把它挡在外面的那一环。 */
  private def postFriendSend(fs: FriendService, json: Json): IO[Json] =
    runWith(Some(fs))(
      authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/u1/messages"))).withEntity(json)
    ).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json]
    }

  /** 上游**实际收到的请求体原文**（逐字；加性判据按字节比）。 */
  private def upstreamBodies: List[String] = sentBodies.toArray(Array.empty[String]).toList

  test("P2-b 加性①：带 clientMsgId ⇒ 网关把该键透传到上游（原文含键、body 原样）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        postFriendSend(fs, Json.obj("body" -> "hi".asJson, "clientMsgId" -> "K-1".asJson)).map { _ =>
          val sent = upstreamBodies
          assertEquals(sent.size, 1)
          val hc = io.circe.parser.parse(sent.head).toOption.map(_.hcursor)
          assertEquals(hc.flatMap(_.downField("clientMsgId").as[String].toOption), Some("K-1"))
          assertEquals(hc.flatMap(_.downField("body").as[String].toOption), Some("hi"))
        }
    }
  }

  test("P2-b 加性②：无键（旧客户端）⇒ 上游请求体逐字节同形（键集/键序零变化）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        postFriendSend(fs, Json.obj("body" -> "hello".asJson, "clientMsgId" -> "".asJson)).map { _ =>
          // 空串键 = 无键（与缺席同形）⇒ 上游只看到 body 一个键，逐字节等于今天。
          assertEquals(upstreamBodies, List("""{"body":"hello"}"""))
        }
    }
  }

  test("P2-b 幂等：同键重复 ⇒ 回放同一行；无键重复 ⇒ 各落新行（服务端语义镜像）") {
    withMockServer { (_, client, fs) =>
      val keyed = Json.obj("body" -> "dup".asJson, "clientMsgId" -> "K-2".asJson)
      val bare  = Json.obj("body" -> "dup".asJson)
      def mid(j: Json) = j.hcursor.downField("messageId").as[Long].toOption
      for
        _ <- client.login("d1", "dev", "macos", Nil)
        a <- postFriendSend(fs, keyed)
        b <- postFriendSend(fs, keyed)
        c <- postFriendSend(fs, bare)
        d <- postFriendSend(fs, bare)
      yield
        assertEquals(mid(a), mid(b), "同键两次应回放同一行")
        assertEquals(mid(c).isDefined, true)
        assertNotEquals(mid(c), mid(d), "无键两次应各落一行")
        assertEquals(mockRowSeq.get(), 8, "4 次 POST 只真落 3 行（5→8）")
        assertEquals(upstreamBodies.size, 4, "网关不折叠请求：判重在服务端，不在转发层")
    }
  }

  test("P2-b 群腿：原文转发 ⇒ clientMsgId 直达上游，且同键两次只落一行") {
    withMockServer { (_, client, fs) =>
      def postGroup(k: String): IO[Json] =
        runWith(Some(fs))(
          authed(Request[IO](Method.POST, Uri.unsafeFromString("/groups/g1/messages")))
            .withEntity(Json.obj("body" -> "gmsg".asJson, "clientMsgId" -> k.asJson))
        ).flatMap { resp =>
          assertEquals(resp.status, Status.Ok) // 同 postFriendSend：状态先钉，防 502 假绿
          resp.as[Json]
        }
      def mid(j: Json) = j.hcursor.downField("messageId").as[Long].toOption
      for
        _ <- client.login("d1", "dev", "macos", Nil)
        a <- postGroup("G-1")
        b <- postGroup("G-1")
      yield
        assertEquals(upstreamBodies, List("""{"body":"gmsg","clientMsgId":"G-1"}""", """{"body":"gmsg","clientMsgId":"G-1"}"""))
        assertEquals(mid(a), mid(b), "群腿同键应回放同一行")
        assertEquals(mockRowSeq.get(), 6, "群腿同键两次只真落 1 行（5→6）")
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

  test("GET /users/lookup passes through new-form (username/displayName/avatar) payload unchanged") {
    // Username 统一形态 A：网关对 lookup 纯透传——上游新契约字段原样到达 web，
    // 网关不做字段映射/丢弃（双识别语义在 neblink-server，归一在 JS 侧）。
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/lookup?q=newform")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("username").as[String].toOption, Some("newform"))
          assertEquals(body.hcursor.downField("displayName").as[String].toOption, Some("新形态"))
          assertEquals(body.hcursor.downField("avatar").as[String].toOption, Some("https://example.com/a.png"))
          assertEquals(body.hcursor.downField("found").as[Boolean].toOption, Some(true))
        }
      }
    }
  }

  test("GET /users/search?q= proxies search result (contract hit shape)") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/search?q=alice42")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          // 契约命中形态（§4.1）：found + user 卡（username/display_name/avatar）+ relation_status
          assertEquals(body.hcursor.downField("found").as[Boolean].toOption, Some(true))
          val user = body.hcursor.downField("user")
          assertEquals(user.downField("username").as[String].toOption, Some("alice42"))
          assertEquals(user.downField("display_name").as[String].toOption, Some("Alice"))
          assertEquals(user.downField("avatar").as[String].toOption, Some("https://example.com/a.png"))
          assertEquals(body.hcursor.downField("relation_status").as[String].toOption, Some("addable"))
        }
      }
    }
  }

  test("GET /users/search passes through miss shape unchanged") {
    // 未命中恒 {found:false}（防枚举 §5.1）——网关纯透传，无 user/relation_status 冗余键。
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/search?q=ghost")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("found").as[Boolean].toOption, Some(false))
          assertEquals(body.hcursor.downField("user").as[Json].toOption, None)
          assertEquals(body.hcursor.downField("relation_status").as[Json].toOption, None)
        }
      }
    }
  }

  test("search without q -> 400") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/users/search")))
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

  test("GET /friends upstream auth-rejection 403 -> 502 (F4: load-failure ≠ empty list)") {
    // F4（20260910 好友搜索批）：好友列表 REST 直通面——上游会话失效形态
    // （403 Missing or invalid token，one-live-session kick 后的持续 403）
    // 必须以 502 到达前端，前端才能区分「空列表」与「加载失败」；此前的
    // 折叠空列表语义只剩后台刷新链（FriendService.refreshFriends）。
    // 本 client 不带 identity（自愈关闭）——F4 穿透与 F2 自愈分别钉。
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    def respond(ex: HttpExchange, status: Int, body: String): Unit =
      ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
      ex.getRequestBody.close()
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      ex.getResponseBody.write(bytes)
      ex.getResponseBody.close()

    server.createContext(
      "/api/device/login",
      ex => respond(ex, 200, """{"token":"tok-kicked","networkId":"n1","deviceId":"d1","peers":[]}""")
    )
    server.createContext(
      "/api/friends",
      ex => respond(ex, 403, """{"error":"Missing or invalid token"}""")
    )
    server.start()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    val client  = new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)
    val fs      = new FriendService(IO.pure(Some(client)), AgentMessagingConfig())
    val checked = client.login("d1", "dev", "macos", Nil) *> runWith(Some(fs))(
      authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends")))
    ).guarantee(IO.blocking(server.stop(0)))

    checked.flatMap { resp =>
      assertEquals(resp.status, Status.BadGateway)
      resp.as[Json].map(body =>
        assert(body.hcursor.downField("error").as[String].exists(_.contains("Missing or invalid token")))
      )
    }
  }

  // ══════════ rcptcode 批（2026-09-20）：好友发送腿「保留状态码」逐字透传 ══════════
  //
  // 折叠点 ①（`NeblinkClient.sendRequestTimed`：非 2xx ⇒ `Left("HTTP <code>: <body>")`，
  // 状态码降级成文本）与折叠点 ②（`friendErr`：非「Not logged in」一律 502）在本路由上
  // 双双消除 ⇒ 上游 4xx/5xx 与成功码**逐字**到达客户端，客户端才能把 `not_friends` 这类
  // **语义终态码**与「可重试」分开（8 态机 S7 的进入条件）。
  //
  // 改前形态 = 一律 502 + `{"error":"HTTP <code>: {...}"}` 文本；本组逐码钉「逐字」。
  // 🔴 本组只覆盖**好友发送路由**：上述两条既有 502 用例（unblock / `GET /friends`）是
  // **有意保留**的折叠护栏，勿随本批混改。

  /** 单次好友发送（指定被寻址 uid）：返回（网关状态、网关响应体）。 */
  private def postSendTo(fs: FriendService, uid: String, body: Json): IO[(Status, Json)] =
    runWith(Some(fs))(
      authed(Request[IO](Method.POST, Uri.unsafeFromString(s"/friends/$uid/messages"))).withEntity(body)
    ).flatMap(resp => resp.as[Json].map(b => (resp.status, b)))

  /** 折叠形态护栏：折叠通道的产物**恒**以 `HTTP ` 起头（`"HTTP 403: {...}"`）⇒ 任一条
    * 透传用例若被撤回到折叠实现，本断言必红（判红形态，`verification-rigor` §1）。 */
  private def assertNotFolded(json: Json): Unit =
    val err = json.hcursor.downField("error").as[String].toOption
    assert(!err.exists(_.startsWith("HTTP ")), s"错误体仍是被折叠的文本形态: $err")

  private def sendOne(fs: FriendService, uid: String): IO[(Status, Json)] =
    postSendTo(fs, uid, Json.obj("body" -> "hi".asJson))

  test("rcptcode A1：上游 403 not_friends ⇒ 网关 403 + 体逐字（禁折 502）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        sendOne(fs, "up-403-notfriends").map { case (status, body) =>
          assertEquals(status, Status.Forbidden)
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("not_friends"))
          assertNotFolded(body)
        }
    }
  }

  test("rcptcode A2：上游 403 not_blocker（同域另一枚语义码）⇒ 403 + 体逐字") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        sendOne(fs, "up-403-notblocker").map { case (status, body) =>
          assertEquals(status, Status.Forbidden)
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("not_blocker"))
          assertNotFolded(body)
        }
    }
  }

  test("rcptcode A3：上游 400 REPLY_TARGET_INVALID ⇒ 400 + 体逐字（非 502、非 200）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        sendOne(fs, "up-400-replyinvalid").map { case (status, body) =>
          assertEquals(status, Status.BadRequest)
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("REPLY_TARGET_INVALID"))
          assertNotFolded(body)
        }
    }
  }

  test("rcptcode A4：上游 429（客户端未知码）⇒ 429 逐字（禁折算成 502/200）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        sendOne(fs, "up-429").map { case (status, body) =>
          assertEquals(status, Status.TooManyRequests)
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("upstream_rate_limited"))
          assertNotFolded(body)
        }
    }
  }

  test("rcptcode A5：上游 500 ⇒ 500 逐字（禁折 502，禁伪装成功）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        sendOne(fs, "up-500").map { case (status, body) =>
          assertEquals(status, Status.InternalServerError)
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("server_error"))
          assertNotFolded(body)
        }
    }
  }

  test("rcptcode A6：上游 201（真实成功码）⇒ 201 逐字 + 体字段保持（行为变化 ①读数）") {
    // 改前：成功一律被归一为 200（`friendResult` = `Ok(json)`）；改后 = 上游真实码。
    // 客户端只判 `resp.ok` 与 `messageId`（`web/js/messages.js`）⇒ 兼容，本用例即其读数。
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        sendOne(fs, "up-201").map { case (status, body) =>
          assertEquals(status, Status.Created)
          assertEquals(body.hcursor.downField("messageId").as[Long].toOption, Some(5L))
          assertEquals(body.hcursor.downField("conversationId").as[String].toOption, Some("c1"))
        }
    }
  }

  test("rcptcode A7：传输失败（上游不可达）⇒ 仍 502（`Left` 通道语义逐字不变）") {
    // 保留面：折叠点 ①②消除的只是「**上游有响应**」的透传；无响应（连接失败/超时）仍走
    // `Left` ⇒ `friendErr` ⇒ 502（客户端读作可重试）。会话在停服前建立，故走的是真连接失败。
    val (server, url) = startMockServer
    val client        = mkClient(url)
    val fs            = new FriendService(IO.pure(Some(client)), AgentMessagingConfig())
    (client.login("d1", "dev", "macos", Nil) *>
      IO.blocking(server.stop(0)) *>
      sendOne(fs, "u1")
    ).guarantee(IO.blocking(server.stop(0))).map { case (status, body) =>
      assertEquals(status, Status.BadGateway)
      assert(body.hcursor.downField("error").as[String].isRight, "502 体应带可判读原因")
    }
  }

  // A8 的两半必须是**两条独立 test**：隔离 home 的粒度是整条 test（beforeEach 建
  // tmpDir + setDataRoot，afterEach 才删，见 :57-69），而 `withService(configured=false)`
  // 不清空 home、只是「不播种」（:74-86）。两半写在同一条 test 内以 `*>` 串接时，
  // 第一半的 `updateConfig(neblinkServer = …)` 已把配置写进这份 home ⇒ 第二半新起的
  // NeblinkService 读回 neblinkConfigured=true ⇒ 走 401 而非 404（前置条件自毁）。
  // 拆开后两半各得全新 home，两条断言各自独立成立。

  test("rcptcode A8a：Not logged in 特判保留（已配置 ⇒ 401+code）") {
    val post = authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/u1/messages")))
      .withEntity(Json.obj("body" -> "hi".asJson))
    withService(configured = true) { ms =>
      val fs = new FriendService(IO.pure(None), AgentMessagingConfig())
      runWith(Some(fs), Some(ms))(post).flatMap { resp =>
        assertEquals(resp.status, Status.Unauthorized)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("Not logged in"))
          assertEquals(body.hcursor.downField("code").as[String].toOption, Some("neblink_not_logged_in"))
        }
      }
    }
  }

  test("rcptcode A8b：未配置的**发送腿** ⇒ 404 NebLink not enabled（neblinkOff 保持可表达）") {
    // 覆盖格：「POST /friends/{id}/messages × 未配置 ⇒ 404」——既有 `unconfigured +
    // no session -> still 404`（:361）覆盖的是 GET /friends 列表腿，发送腿唯此一格。
    val post = authed(Request[IO](Method.POST, Uri.unsafeFromString("/friends/u1/messages")))
      .withEntity(Json.obj("body" -> "hi".asJson))
    withService(configured = false) { ms =>
      val fs = new FriendService(IO.pure(None), AgentMessagingConfig())
      runWith(Some(fs), Some(ms))(post).flatMap { resp =>
        assertEquals(resp.status, Status.NotFound)
        resp.as[Json].map(body =>
          assertEquals(body.hcursor.downField("error").as[String].toOption, Some("NebLink not enabled"))
        )
      }
    }
  }

  private def mkClient(url: String): NeblinkClient =
    new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)

  // ══════════ ⑦（2026-09-12）：备注端点 / 备注出参 / conversations 档案 ══════════

  private def putRemark(fs: Option[FriendService], friendUserId: String, body: Json): IO[Response[IO]] =
    runWith(fs)(
      authed(Request[IO](Method.PUT, Uri.unsafeFromString(s"/friends/$friendUserId/remark"))).withEntity(body)
    )

  private def getFriends(fs: Option[FriendService]): IO[Response[IO]] =
    runWith(fs)(authed(Request[IO](Method.GET, Uri.unsafeFromString("/friends"))))

  /** `friends[idx].remark` 三态读：`None` = **键缺席**（= 红，键必须恒在）；
    * `Some(None)` = 键在、值 `null`；`Some(Some(v))` = 有值。
    *
    * 用 `focus` 而非 `as[Option[String]]`：circe 的 `Decoder[Option[A]]` 对「键缺席」
    * 也成功返回 `None`，两种形态不可区分（键恒在的断言会变成空转）。 */
  private def remarkAt(resp: Response[IO], idx: Int): IO[Option[Option[String]]] =
    resp.as[Json].map { body =>
      body.hcursor.downField("friends").as[Vector[Json]].toOption
        .flatMap(_.lift(idx))
        .flatMap(_.hcursor.downField("remark").focus)
        .map(v => if v.isNull then None else v.asString)
    }

  test("PUT /friends/:id/remark → 200 {ok:true}（本地态、零上游往返）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        putRemark(Some(fs), "u1", Json.obj("remark" -> "老林".asJson)).flatMap { resp =>
          assertEquals(resp.status, Status.Ok)
          resp.as[Json].map(body => assertEquals(body.hcursor.downField("ok").as[Boolean].toOption, Some(true)))
        }
    }
  }

  test("PUT /friends/:id/remark 缺参 → 400（键缺席 / null / 非字符串都算缺参）；空串 = 清除") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        putRemark(Some(fs), "u1", Json.obj()).flatMap { missing =>
          assertEquals(missing.status, Status.BadRequest, "键缺席 = 缺参 400")
          putRemark(Some(fs), "u1", Json.obj("remark" -> Json.Null)).flatMap { nulled =>
            assertEquals(nulled.status, Status.BadRequest, "null = 缺参 400（清备注走空串）")
            putRemark(Some(fs), "u1", Json.obj("remark" -> Json.fromInt(7))).map { wrongType =>
              assertEquals(wrongType.status, Status.BadRequest, "非字符串 = 缺参 400")
            }
          }
        }
    }
  }

  test("PUT /friends/:id/remark 未认证 → 403（withAuth 先于任何备注写面）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        runWith(Some(fs))(
          Request[IO](Method.PUT, Uri.unsafeFromString("/friends/u1/remark"))
            .withEntity(Json.obj("remark" -> "老林".asJson))
        ).flatMap { resp =>
          assertEquals(resp.status, Status.Forbidden)
          // 未认证 ⇒ 零写入：随后带上 token 读，remark 仍是 null
          getFriends(Some(fs)).flatMap(g => remarkAt(g, 0).map(r => assertEquals(r, Some(None),
            "403 路径不得产生副作用")))
        }
    }
  }

  test("备注端到端：PUT → GET /friends 出参 remark 变值；空串再清回 null（键恒在）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        getFriends(Some(fs)).flatMap(r => remarkAt(r, 0)).flatMap { before =>
          assertEquals(before, Some(None), "未设备注：键在、值为 null")
          putRemark(Some(fs), "u1", Json.obj("remark" -> "  老林  ".asJson)).flatMap { put =>
            assertEquals(put.status, Status.Ok)
            getFriends(Some(fs)).flatMap(r => remarkAt(r, 0)).flatMap { set =>
              assertEquals(set, Some(Some("老林")), "trim 后入库，出参即备注（applyRemarks 合并点）")
              putRemark(Some(fs), "u1", Json.obj("remark" -> "".asJson)).flatMap { clear =>
                assertEquals(clear.status, Status.Ok)
                getFriends(Some(fs)).flatMap(r => remarkAt(r, 0)).map { cleared =>
                  assertEquals(cleared, Some(None), "空串 = 清除（键仍在、值回 null）")
                }
              }
            }
          }
        }
    }
  }

  test("备注只按 userId 命中：好友档案 remark 不串行（u3 未设 ⇒ null）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        putRemark(Some(fs), "u1", Json.obj("remark" -> "老林".asJson)) *>
        getFriends(Some(fs)).flatMap(r => remarkAt(r, 2)).map { third =>
          assertEquals(third, Some(None), "只有被设置的那一行带值")
        }
    }
  }

  test("conversations 内嵌 friend 档案同样带备注值（冻结契约 2：两处下发口径一致）") {
    withMockServer { (_, client, fs) =>
      client.login("d1", "dev", "macos", Nil) *>
        putRemark(Some(fs), "u1", Json.obj("remark" -> "老林".asJson)) *>
        runWith(Some(fs))(authed(Request[IO](Method.GET, Uri.unsafeFromString("/conversations")))).flatMap { resp =>
          assertEquals(resp.status, Status.Ok)
          resp.as[Json].map { body =>
            val convs = body.asArray.getOrElse(Vector.empty)
            assertEquals(convs.size, 1)
            val f = convs.head.hcursor.downField("friend")
            assertEquals(f.downField("remark").focus.flatMap(_.asString), Some("老林"),
              "会话行的 friend 档案必须带备注值（前端消息列表「备注 > 显示名」读这里）")
            assertEquals(f.downField("display_name").as[String].toOption, Some("林小满"),
              "显示名原样保留（备注是追加键，不顶替显示名）")
          }
        }
    }
  }

end FriendApiRoutesSpec
