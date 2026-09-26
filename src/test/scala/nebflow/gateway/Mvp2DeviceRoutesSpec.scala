package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{ModelChainConfig, NebflowServiceConfig, ServiceLlmConfig, ThinkingConfig}
import nebflow.neblink.{AgentMessagingConfig, FriendService, NeblinkClient, NeblinkServerConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * MVP-2 设备会话域统一 · **客户端（网关侧）腿** 的路由契约测试。
 *
 * 端到端链路（真实 route → FriendService → NeblinkClient → JDK HttpServer 桩）：
 *   · `GET  /api/conversations/{id}/receipts`      → neblink-server `src/friends.rs:1712`
 *   · `POST /api/devices/{device_id}/messages`     → neblink-server `src/friends.rs:1300`
 *   · `GET  /api/conversations`（设备行 `kind`/`deviceId` 透传）
 *   · `GET  /api/conversations/{id}/messages`（`senderDeviceId` 透传）
 *
 * 为什么要有本 spec（判红②要求「写 → 读往返一致 + 原始请求/响应读数」）：桩**有状态**
 * ——`POST …/read` 落一次游标，`GET …/receipts` 的响应由该游标**派生**，两边都记原文。
 * 「用内存/本地桩自证而与新端点无关」被显式排除：本 spec 打的**就是**这两条新路由，
 * 且断言的是网关**出参形态**与**状态码透传**，不是桩的返回值。
 */
class Mvp2DeviceRoutesSpec extends CatsEffectSuite:

  private val TestToken = "mvp2-test-token"

  /** 上游请求台账（原始 method + path + body 逐字）——判红②的「原始请求读数」。 */
  private val upstreamLog = new ConcurrentLinkedQueue[String]()

  /** 桩侧设备维度状态（写 → 读往返的**状态承载**，不在被测进程里）。 */
  private val readCursor = new java.util.concurrent.atomic.AtomicLong(0L)
  private val receiptsState = new java.util.concurrent.ConcurrentHashMap[Long, String]()

  private def respond(ex: HttpExchange, status: Int, body: String): Unit =
    // JDK HttpServer keep-alive 要求请求体先被消费（否则残留字节污染同连接的下一次请求）。
    ex.getRequestBody.transferTo(java.io.OutputStream.nullOutputStream())
    ex.getRequestBody.close()
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  private def bodyOf(ex: HttpExchange): String =
    new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  /** 上游桩：**只实现契约 §8 里本腿消费的四个面**，其余 404。 */
  private def startStub: (HttpServer, String) =
    readCursor.set(0L)
    receiptsState.clear()
    upstreamLog.clear()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext(
      "/api/device/login",
      ex => respond(ex, 200, """{"token":"tok-d","networkId":"n1","deviceId":"dev-local","peers":[]}""")
    )

    server.createContext(
      "/api/conversations",
      ex =>
        val path = ex.getRequestURI.getPath
        val method = ex.getRequestMethod
        val reqBody = if method == "POST" then bodyOf(ex) else ""
        upstreamLog.add(s"$method $path $reqBody")
        if method == "GET" && path == "/api/conversations" then
          // 一行设备行（带 kind/deviceId）+ 一行 legacy 直聊（两键**缺席**）——出参形态见下。
          respond(
            ex,
            200,
            """[{"conversationId":"dev:dev-local","friend":{"userId":"me","username":"me","display_name":"Me","avatar":null},"lastMessage":{"id":11,"senderId":"me","kind":"text","body":"hi from me","createdAt":1757900000,"senderDeviceId":"dev-local"},"unreadCount":0,"kind":"device","deviceId":"dev-local"},
               {"conversationId":"c-legacy","friend":{"userId":"u1","username":"lin","display_name":"Lin","avatar":null},"lastMessage":null,"unreadCount":2}]""".stripMargin
              .replaceAll("\\n\\s*", "")
          )
        else if method == "GET" && path.endsWith("/messages") then
          val conv = path.stripPrefix("/api/conversations/").stripSuffix("/messages")
          // 契约 §8.7：行内 `senderDeviceId` **仅**在设备消息上出现 —— 桩按会话分支给出
          // 两种形态（设备行带键 / legacy 直聊行**不带键**），客户端腿的省键纪律才可判。
          if conv.startsWith("dev:") then
            val peer = if conv == "dev:dev-peer" then "dev-peer" else "dev-local"
            respond(
              ex,
              200,
              s"""[{"id":11,"senderId":"me","kind":"text","body":"from $peer","createdAt":1757900000,"senderDeviceId":"$peer"}]"""
            )
          else
            respond(ex, 200, """[{"id":7,"senderId":"u1","kind":"text","body":"legacy hi","createdAt":1757800000}]""")
        else if method == "POST" && path.endsWith("/read") then
          // 桩侧落游标（写面）+ 由游标派生回执行（读面）⇒ 两端同源，往返可判。
          val cur = io.circe.parser
            .parse(reqBody)
            .toOption
            .flatMap(_.hcursor.get[Long]("lastReadMessageId").toOption)
            .getOrElse(0L)
          readCursor.set(cur)
          // 读面：本设备读掉的「非自己发的」消息 ⇒ state=read（契约 §8.7「read 为终态」）。
          if cur >= 11L then receiptsState.put(11L, "read")
          respond(ex, 200, """{"ok":true}""")
        else if method == "GET" && path.endsWith("/receipts") then
          val rows = receiptsState.asScala.toList.sortBy(_._1)
          val lastRead = rows.filter(_._2 == "read").map(_._1).lastOption.getOrElse(0L)
          val lastSent = rows.map(_._1).lastOption.getOrElse(0L)
          respond(
            ex,
            200,
            Json
              .obj(
                "receipts" -> rows.map((id, st) => Json.obj("messageId" -> id.asJson, "state" -> st.asJson)).asJson,
                "lastSentMessageId" -> lastSent.asJson,
                "lastReadMessageId" -> lastRead.asJson
              )
              .noSpaces
          )
        else respond(ex, 404, """{"error":"not found"}""")
        end if
    )

    server.createContext(
      "/api/devices",
      ex =>
        val path = ex.getRequestURI.getPath
        val reqBody = bodyOf(ex)
        upstreamLog.add(s"${ex.getRequestMethod} $path $reqBody")
        if ex.getRequestMethod == "POST" && path.endsWith("/messages") then
          // 契约 §8.6：成功恒 201，`existing` 仅表示回放原行（幂等键同 clientMsgId）。
          val replay = reqBody.contains("dup-1")
          respond(
            ex,
            201,
            s"""{"messageId":11,"conversationId":"dev:dev-local","createdAt":1757900000,"createdAtMs":1757900000123,"existing":$replay,"selfUserId":"me"}"""
          )
        else respond(ex, 404, """{"error":"not found"}""")
    )
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}")

  end startStub

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
      historyArchiver = HistoryArchiver.fileSystem(os.temp.dir() / "mvp2-device-spec-archives"),
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

  private def run(fs: FriendService)(req: Request[IO]): IO[Response[IO]] =
    mkRoutes(Some(fs)).routes(req).value.map(_.getOrElse(fail("route fell through")))

  private def withStub[A](use: FriendService => IO[A]): IO[A] =
    IO.delay(startStub).flatMap { (server, url) =>
      val client = new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), 8080)
      val fs = new FriendService(IO.pure(Some(client)), AgentMessagingConfig())
      client.login("dev-local", "dev", "macos", Nil) *> use(fs).guarantee(IO.blocking(server.stop(0)))
    }

  override def munitIOTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  // ── ① 会话行：设备行带 kind/deviceId；legacy 直聊行两键**缺席** ─────────

  test("GET /conversations 透传设备行 kind/deviceId；legacy 行两键缺席（§8.1）") {
    withStub { fs =>
      run(fs)(authed(Request[IO](Method.GET, Uri.unsafeFromString("/conversations")))).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val rows = body.asArray.getOrElse(Vector.empty)
          assertEquals(rows.size, 2)
          val dev = rows(0)
          assertEquals(dev.hcursor.get[String]("kind").toOption, Some("device"))
          assertEquals(dev.hcursor.get[String]("deviceId").toOption, Some("dev-local"))
          // `lastMessage.senderDeviceId` 也必须活着穿过网关（§8.1/§8.3）
          assertEquals(
            dev.hcursor.downField("lastMessage").get[String]("senderDeviceId").toOption,
            Some("dev-local")
          )
          // 🔴 legacy 直聊行：两键**逐字缺席**（不是 null）——契约 §8.1「直聊/群聊行无
          // kind/deviceId 两个键」。`deriveEncoder` 会写成 `"kind":null` ⇒ 这条就是
          // 那个回归的钉子。
          val legacy = rows(1)
          assert(!legacy.asObject.exists(_.contains("kind")), s"legacy row gained a kind key: ${legacy.noSpaces}")
          assert(
            !legacy.asObject.exists(_.contains("deviceId")),
            s"legacy row gained a deviceId key: ${legacy.noSpaces}"
          )
          assert(!legacy.asObject.exists(_.contains("senderDeviceId")))
        }
      }
    }
  }

  test("GET /conversations/{id}/messages 透传 senderDeviceId；legacy 消息行不带该键（§8.7）") {
    withStub { fs =>
      run(fs)(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/conversations/dev:dev-peer/messages?after=0&limit=50")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val rows = body.asArray.getOrElse(Vector.empty)
          assertEquals(rows.size, 1)
          assertEquals(rows.head.hcursor.get[Long]("id").toOption, Some(11L))
          assertEquals(rows.head.hcursor.get[String]("senderDeviceId").toOption, Some("dev-peer"))
          // 既有五键仍在（形态不因加性字段而移动）
          assertEquals(rows.head.hcursor.get[String]("body").toOption, Some("from dev-peer"))
          assertEquals(rows.head.hcursor.get[Long]("createdAt").toOption, Some(1757900000L))
        }
      }
    }
  }

  test("legacy 直聊消息（上游无 senderDeviceId）出参**不含**该键（加性扩面不污染 legacy）") {
    withStub { fs =>
      run(fs)(
        authed(Request[IO](Method.GET, Uri.unsafeFromString("/conversations/c-legacy/messages?after=0&limit=50")))
      ).flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          val row = body.asArray.getOrElse(Vector.empty).head
          // 既有五键逐字在场（形态不移动）
          assertEquals(row.hcursor.get[Long]("id").toOption, Some(7L))
          assertEquals(row.hcursor.get[String]("body").toOption, Some("legacy hi"))
          // 🔴 省键而非 `null`：契约 §8.3「有 device sender 的消息才带该键」，legacy 行
          // 逐字节等于加性扩面之前（`deriveEncoder` 会写成 `"senderDeviceId":null`）。
          assert(
            !row.asObject.exists(_.contains("senderDeviceId")),
            s"legacy message row gained a senderDeviceId key: ${row.noSpaces}"
          )
        }
      }
    }
  }

  // ── ② 未读/回执：写 → 读**往返一致**（原始请求/响应读数） ─────────────

  test("回执面：POST /read 写游标 → GET /receipts 读到同源终态（§8.7）") {
    withStub { fs =>
      val conv = "dev:dev-peer"
      for
        // ── 写（原始请求） ──
        w <- run(fs)(
          authed(
            Request[IO](Method.POST, Uri.unsafeFromString(s"/conversations/$conv/read"))
              .withEntity(Json.obj("lastReadMessageId" -> 11L.asJson))
          )
        )
        wbody <- w.as[Json]
        _ = assertEquals(w.status, Status.Ok)
        _ = assertEquals(wbody.hcursor.get[Boolean]("ok").toOption, Some(true))
        // 桩侧游标确实被写入（原始上游请求读数）
        _ = assertEquals(readCursor.get(), 11L)
        // ── 读（原始响应） ──
        r <- run(fs)(authed(Request[IO](Method.GET, Uri.unsafeFromString(s"/conversations/$conv/receipts"))))
        rbody <- r.as[Json]
        _ = assertEquals(r.status, Status.Ok)
        _ = assertEquals(
          rbody.hcursor.downField("receipts").downArray.get[Long]("messageId").toOption,
          Some(11L)
        )
        _ = assertEquals(
          rbody.hcursor.downField("receipts").downArray.get[String]("state").toOption,
          Some("read")
        )
        _ = assertEquals(rbody.hcursor.get[Long]("lastReadMessageId").toOption, Some(11L))
        _ = assertEquals(rbody.hcursor.get[Long]("lastSentMessageId").toOption, Some(11L))
        // ── 往返一致性 ──
        _ = assertEquals(readCursor.get(), rbody.hcursor.get[Long]("lastReadMessageId").toOption.getOrElse(-1L))
      yield upstreamLog.asScala.toList
      end for
    }.map { log =>
      // 原始上游读数逐字落盘（判红②要求「给原始请求/响应读数」）
      println("[mvp2-device-spec] upstream requests:")
      log.foreach(l => println("  " + l))
      assert(log.exists(_.startsWith("POST /api/conversations/dev:dev-peer/read")), s"missing read write: $log")
      assert(log.exists(_.startsWith("GET /api/conversations/dev:dev-peer/receipts")), s"missing receipts read: $log")
      assert(log.exists(_.contains("""{"lastReadMessageId":11}""")), s"read body not verbatim: $log")
    }
  }

  // ── ③ 设备发送面：201 透传 / 幂等回放 / origin 闸 / 缺省 404 ────────

  test("POST /devices/{id}/messages 透传 201；幂等回放仍 201（§8.6）") {
    withStub { fs =>
      val path = "/devices/dev-local/messages"
      for
        first <- run(fs)(
          authed(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(Json.obj("body" -> "hello".asJson)))
        )
        fb <- first.as[Json]
        _ = assertEquals(first.status, Status.Created)
        _ = assertEquals(fb.hcursor.get[Boolean]("existing").toOption, Some(false))
        _ = assertEquals(fb.hcursor.get[Long]("createdAtMs").toOption, Some(1757900000123L))
        // 幂等重复同 clientMsgId ⇒ **仍是 201**（不许折成 200/409/500）
        second <- run(fs)(
          authed(
            Request[IO](Method.POST, Uri.unsafeFromString(path))
              .withEntity(Json.obj("body" -> "hello".asJson, "clientMsgId" -> "dup-1".asJson))
          )
        )
        sb <- second.as[Json]
        _ = assertEquals(second.status, Status.Created)
        _ = assertEquals(sb.hcursor.get[Boolean]("existing").toOption, Some(true))
        _ = assertEquals(sb.hcursor.get[Long]("messageId").toOption, Some(11L))
      yield upstreamLog.asScala.toList
      end for
    }.map { log =>
      println("[mvp2-device-spec] device send upstream requests:")
      log.filter(_.contains("/api/devices/")).foreach(l => println("  " + l))
      assert(log.exists(_.startsWith("POST /api/devices/dev-local/messages")), s"device send not forwarded: $log")
      // 请求体**逐字**转发（不重编码、不丢键）
      assert(log.exists(_.contains("""{"body":"hello","clientMsgId":"dup-1"}""")), s"body not verbatim: $log")
    }
  }

  test("POST /devices/{id}/messages：origin 非 user ⇒ 400 且零上游往返（P3 伪造面）") {
    withStub { fs =>
      val path = "/devices/dev-local/messages"
      run(fs)(
        authed(
          Request[IO](Method.POST, Uri.unsafeFromString(path))
            .withEntity(Json.obj("body" -> "x".asJson, "origin" -> "agent".asJson))
        )
      ).flatMap { resp =>
        assertEquals(resp.status, Status.BadRequest)
        IO.delay {
          assert(
            !upstreamLog.asScala.exists(_.contains("/api/devices/")),
            s"gated body still reached upstream: ${upstreamLog.asScala.toList}"
          )
        }
      }
    }
  }

  test("NebLink 未配置 ⇒ 两个新面均 404 NebLink not enabled（fail-closed，不改语义）") {
    val paths = List(
      (Method.GET, "/conversations/dev:dev-peer/receipts"),
      (Method.POST, "/devices/dev-local/messages")
    )
    paths.traverse { case (m, p) =>
      mkRoutes(None)
        .routes(authed(Request[IO](m, Uri.unsafeFromString(p))))
        .value
        .map(_.getOrElse(fail("fell through")))
        .flatMap { resp =>
          assertEquals(resp.status, Status.NotFound)
          resp.as[Json].map(b => assertEquals(b.hcursor.get[String]("error").toOption, Some("NebLink not enabled")))
        }
    }.void
  }

  test("两个新面均需 auth（无 token ⇒ 403，零上游往返）") {
    val paths = List(
      (Method.GET, "/conversations/dev:dev-peer/receipts"),
      (Method.POST, "/devices/dev-local/messages")
    )
    val fs = new FriendService(IO.pure(None), AgentMessagingConfig())
    paths.traverse { case (m, p) =>
      mkRoutes(Some(fs))
        .routes(Request[IO](m, Uri.unsafeFromString(p)))
        .value
        .map(_.getOrElse(fail("fell through")))
        .flatMap { resp =>
          IO(assertEquals(resp.status, Status.Forbidden))
        }
    }.void
  }
end Mvp2DeviceRoutesSpec
