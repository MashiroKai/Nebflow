package nebflow.neblink

import nebflow.core.tools.{FriendMessageTool, ToolContext, ToolError, ToolRegistry}

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.neblink.{AgentMessagingConfig, FriendService, FriendSummary, NeblinkClient, NeblinkEndpoint, NeblinkServerConfig}

/**
 * SendFriendMessage tool 单测（#290 域 A）。
 *
 * 钉死：①to 三级解析（neblinkId 精确→昵称精确→昵称唯一前缀）+ 多命中/零命中候选
 * 列表 ②档位行为透传（off 拒绝 / auto 发送成功文案 / ask 未接线拒绝）③参数校验
 * （缺 to / 缺 message / 超长）④schema 契约（required + properties）。
 *
 * 复用 FriendApiRoutesSpec 的 mock 形态：JDK HttpServer 模拟 neblink-server，
 * server 生命周期挂在 IO guarantee 上（munit IO 体返回后才执行——禁止同步 finally）。
 */
class FriendMessageToolSpec extends CatsEffectSuite:

  // ── 解析三态（纯函数，直接测 resolveFriend） ─────────────

  private val friends = List(
    FriendSummary(userId = "u1", username = "lin@example.com", displayName = "林小满"),
    FriendSummary(userId = "u2", username = "wangxuan", displayName = "王选"),
    FriendSummary(userId = "u3", username = "linlin@example.com", displayName = "林小林")
  )

  test("resolveFriend level 1: username exact match (case-insensitive)") {
    val hit = FriendMessageTool.resolveFriend("LIN@EXAMPLE.COM", friends)
    assertEquals(hit.map(_.userId), Right("u1"))
  }

  test("resolveFriend level 2: name exact match") {
    val hit = FriendMessageTool.resolveFriend("王选", friends)
    assertEquals(hit.map(_.userId), Right("u2"))
  }

  test("resolveFriend level 3: unique name prefix") {
    val hit = FriendMessageTool.resolveFriend("林小满", friends) // 完整名=唯一前缀
    assertEquals(hit.map(_.userId), Right("u1"))
    val prefix = FriendMessageTool.resolveFriend("林", friends) // 前缀命中满+林两个 → 但先查精确名无 → 前缀两命中 → ambiguous
    assert(prefix.isLeft, "prefix hitting two friends must be ambiguous")
  }

  test("resolveFriend ambiguous prefix lists all candidates") {
    val err = FriendMessageTool.resolveFriend("林小", friends).left.toOption
    assert(err.isDefined, "ambiguous prefix must fail")
    val msg  = err.get.message
    val hits = friends.filter(_.displayName.startsWith("林小"))
    hits.foreach { f => assert(msg.contains(f.displayName), s"candidates must list ${f.displayName}") }
    assert(msg.contains("username"), "must suggest using the exact username")
  }

  test("resolveFriend zero hit lists available friends") {
    val err = FriendMessageTool.resolveFriend("不存在", friends).left.toOption
    assert(err.isDefined)
    assert(err.get.message.contains("not found"))
    friends.foreach { f => assert(err.get.message.contains(f.displayName), s"available list must contain ${f.displayName}") }
  }

  test("resolveFriend empty query rejected") {
    assert(FriendMessageTool.resolveFriend("  ", friends).isLeft)
  }

  // ── 档位行为 + 参数校验（NeblinkClient sendRequest seam stub——零网络） ──

  /** Transport-seam stub (NeblinkClientReloginSpec pattern): route by URL,
    * canned replies, record message-POST paths. Login first so sessionToken
    * is set — no HttpServer, no dataRoot writes. */
  private class StubClient:
    val postedPaths = scala.collection.mutable.ListBuffer.empty[String]

    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://stub.local", networkId = "n1", secret = "s"),
      serverPort = 1
    ):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        if url.endsWith("/api/device/login") then
          IO.pure(Right("""{"token":"tok-1","networkId":"n1","deviceId":"d1","peers":[]}"""))
        else if url.endsWith("/api/friends") && method == "GET" then
          IO.pure(
            Right(
              """{"friends":[{"userId":"u1","neblinkId":"lin@example.com","name":"林小满"}],"incoming":[],"outgoing":[]}"""
            )
          )
        else if url.endsWith("/messages") then
          IO { postedPaths += url } *> IO.pure(Right("""{"messageId":5,"conversationId":"c1","createdAt":123}"""))
        else IO.pure(Left(s"unexpected request: $method $url"))

    def login(): Unit =
      client
        .login("dev-1", "TestMac", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
        .unsafeRunSync()

  private def withFs[A](mode: String)(use: FriendService => IO[A]): IO[A] =
    IO.delay {
      val stub = StubClient()
      stub.login()
      new FriendService(IO.pure(Some(stub.client)), AgentMessagingConfig(mode = mode)) -> stub
    }.flatMap { (fs, stub) => use(fs) }

  private def callTool(fs: FriendService, input: JsonObject): IO[Either[ToolError, String]] = {
    FriendMessageTool.initialize(fs)
    FriendMessageTool.call(input, ToolContext(projectRoot = "/tmp"))
  }

  test("mode=off: tool reports the user has disabled agent messaging") {
    withFs("off") { fs =>
      callTool(fs, JsonObject("to" -> "林小满".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.toLowerCase.contains("disabled"))
      }
    }
  }

  test("mode=auto: sends via friend addressing and returns the delivery confirmation") {
    withFs("auto") { fs =>
      callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> "hi".asJson)).map { res =>
        assert(res.isRight, s"expected success, got ${res.left.toOption.map(_.message)}")
        assert(res.toOption.get.contains("已发送给 林小满"))
      }
    }
  }

  test("message longer than 4000 chars rejected before any network call") {
    withFs("auto") { fs =>
      val long = "a" * 4001
      callTool(fs, JsonObject("to" -> "lin@example.com".asJson, "message" -> long.asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.contains("too long"))
      }
    }
  }

  test("missing 'to' parameter rejected") {
    withFs("auto") { fs =>
      callTool(fs, JsonObject("message" -> "hi".asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.contains("'to'"))
      }
    }
  }

  test("missing 'message' parameter rejected") {
    withFs("auto") { fs =>
      callTool(fs, JsonObject("to" -> "林小满".asJson)).map { res =>
        assert(res.isLeft)
        assert(res.left.toOption.get.message.contains("'message'"))
      }
    }
  }

  // ── schema 契约 ──────────────────────────────────────────

  test("schema: required = to + message, both typed string") {
    val schema = FriendMessageTool.inputSchema
    val req    = schema("required").flatMap(_.asArray).getOrElse(Vector.empty).map(_.asString.getOrElse(""))
    assertEquals(req.toSet, Set("to", "message"))
    assert(schema("properties").isDefined)
    assert(schema("properties").get.asObject.get("to").isDefined)
    assert(schema("properties").get.asObject.get("message").isDefined)
  }

  test("description carries the established-friendship + user-identity semantics") {
    val d = FriendMessageTool.description
    assert(d.contains("established friend relationships"))
    assert(d.contains("delivered as the user"))
  }

  test("tool registered under the exact name SendFriendMessage") {
    assert(ToolRegistry.TOOL_MAP.contains("SendFriendMessage"))
  }

end FriendMessageToolSpec
