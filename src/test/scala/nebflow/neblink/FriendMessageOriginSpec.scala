package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

/** #290 联调抓到的 origin 缺口回归钉（2026-08-28）：sendAsAgent 发的消息
  * wire 载荷缺 `origin`，服务器（neblink-server spec v1.1 契约：缺省 "user"）
  * 落库成 user——agent 代发语义（前端 origin 徽章/审计/§7.2 限速区分）整体
  * 失效。
  *
  * 两层钉，各抓一种回归向量：
  *  - wire 层（真实 payload 构造，protected sendRequest seam stub）：
  *    origin 字段透传 / 缺省不发键（旧 server byte-compatible）；
  *  - service 层（FriendService 调用点）：sendAsAgent → doSend 必须带
  *    Some("agent")（调用点常量回归 wire 层看不见），sendAsUser 必须不带
  *    （用户身份不越位）。
  */
class FriendMessageOriginSpec extends FunSuite:

  private val cfg = NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "net", secret = "s")

  private val LoginReply   = """{"token":"test-session-token","networkId":"net","deviceId":"dev","peers":[]}"""
  private val EmptyList    = "[]"

  /** transport seam stub：按 (method, url) 路由 canned 回复，记录全部 body。
    * login() 经同一 seam 置 sessionToken（private var 不可子类直接赋值）。 */
  private class StubClient extends NeblinkClient(cfg, serverPort = 1):
    var sent: List[(String, String, String)] = List.empty // (method, url, body)
    override protected def sendRequest(
      method: String,
      url: String,
      body: String,
      token: Option[String]
    ): IO[Either[String, String]] =
      IO { sent = sent :+ ((method, url, body)) } *>
        IO.pure {
          if url.endsWith("/login") || url.endsWith("/session") then Right(LoginReply)
          else if method == "GET" then Right(EmptyList)
          else Right(s"""{"conversationId":"c1"}""")
        }
    def messageBodies: List[String] =
      sent.collect { case (m, u, b) if u.endsWith("/messages") && m == "POST" => b }

  // ===== wire 层 =====

  test("wire: origin=Some(agent) -> /messages payload carries origin field") {
    val c = new StubClient
    c.login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan"))).unsafeRunSync()
    val out = c.sendFriendMessage("u1", "agent hello from #290 e2e", origin = Some("agent")).unsafeRunSync()
    assert(out.isRight, s"send failed: $out")
    val bodies = c.messageBodies
    assertEquals(bodies.size, 1, s"expected exactly one POST /messages: ${c.sent}")
    val json = parse(bodies.head) match
      case Right(j) => j
      case Left(e)  => fail(s"payload not JSON: ${bodies.head} ($e)")
    assertEquals(json.hcursor.downField("origin").as[String].toOption, Some("agent"))
    assertEquals(json.hcursor.downField("body").as[String].toOption, Some("agent hello from #290 e2e"))
  }

  test("wire: origin=None -> /messages payload has NO origin key (backward compatible)") {
    val c = new StubClient
    c.login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan"))).unsafeRunSync()
    val out = c.sendFriendMessage("u1", "user typed this").unsafeRunSync()
    assert(out.isRight, s"send failed: $out")
    val bodies = c.messageBodies
    assertEquals(bodies.size, 1, s"expected exactly one POST /messages: ${c.sent}")
    val json = parse(bodies.head) match
      case Right(j) => j
      case Left(e)  => fail(s"payload not JSON: ${bodies.head} ($e)")
    assert(!json.hcursor.downField("origin").succeeded, s"origin key must be ABSENT when None: ${bodies.head}")
    assertEquals(json.hcursor.downField("body").as[String].toOption, Some("user typed this"))
  }

  // ===== service 层（调用点钉） =====

  test("service: sendAsAgent carries origin=Some(agent) — the #290 contract") {
    val seen = List.newBuilder[Option[String]]
    val c = new StubClient:
      override def sendFriendMessage(friendUserId: String, body: String, origin: Option[String] = None) =
        IO { seen += origin }.as(Right(io.circe.Json.obj("conversationId" -> "c1".asJson)))
    c.login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan"))).unsafeRunSync()
    val svc = new FriendService(IO.pure(Some(c)), AgentMessagingConfig(mode = "auto"))
    val out = svc.sendAsAgent("friend-1", "agent hello from #290 e2e").unsafeRunSync()
    assert(out.isRight, s"sendAsAgent failed: $out")
    assertEquals(seen.result(), List(Some("agent")), "doSend call-site must pass origin=Some(agent)")
  }

  test("service: sendAsUser stays origin=None (user semantics, no overreach)") {
    val seen = List.newBuilder[Option[String]]
    val c = new StubClient:
      override def sendFriendMessage(friendUserId: String, body: String, origin: Option[String] = None) =
        IO { seen += origin }.as(Right(io.circe.Json.obj("conversationId" -> "c1".asJson)))
    c.login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan"))).unsafeRunSync()
    val svc = new FriendService(IO.pure(Some(c)), AgentMessagingConfig(mode = "auto"))
    val out = svc.sendAsUser("friend-1", "user typed this").unsafeRunSync()
    assert(out.isRight, s"sendAsUser failed: $out")
    assertEquals(seen.result(), List(None), "sendAsUser must NOT set origin=agent")
  }
