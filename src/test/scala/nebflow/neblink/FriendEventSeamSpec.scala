package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/**
 * ①opt-A2 接缝对齐 · 真通路钉子（2026-09-12 波3，方案 §2.1 / §5.1 P2）。
 *
 * 被钉的是**两层接缝同时成立**，而不是任何一层的孤立行为：
 *
 *  L2（服务端 → 客户端状态机）——`FriendService.onFriendEvent` 拿到的 `event`
 *  是服务端结构信封 `{"type": <name>, "payload": {conversationId, …}}`
 *  （neblink-server friends.rs `push_to_user_excluding`）。修前在 **event 顶层**
 *  读 `conversationId` ⇒ 恒 None ⇒ 未读 +1 与 keyset 增量补拉永不发生。
 *
 *  L3（客户端 → 浏览器帧）——同一个 `event` 交给 `FriendEvent.frontendFrame`
 *  展平后广播给前端；修前只 `remove("type")` 一层 ⇒ 帧里**多包一层 payload**，
 *  而 `messages.js` 按扁平读 ⇒ 打开的聊天窗永不 append（只命中「未知会话 →
 *  刷列表」早退）。
 *
 * 本 spec 用真 `FriendService`（真 guard / 真 cursor）+ 记录型 stub client，
 * 断言「payload 下钻 → 未读 +1 → `after=<水位>` 增量补拉 → 前端帧扁平」这四步
 * 在同一次事件里全部发生；并断言旧形状（扁平信封）仍走容错分支（不回退成静默）。
 */
class FriendEventSeamSpec extends FunSuite:

  private def envelope(eventId: String, event: Json): Json =
    Json.obj("type" -> Json.fromString("friend_event"),
      "eventId" -> Json.fromString(eventId),
      "event" -> event)

  /** 真服务端信封形状：`{"type":"message_new","payload":{…}}`。 */
  private def serverEvent(convId: String, messageId: Long): Json =
    Json.obj(
      "type" -> Json.fromString("message_new"),
      "payload" -> Json.obj(
        "messageId" -> Json.fromLong(messageId),
        "conversationId" -> Json.fromString(convId),
        "sender" -> Json.obj("userId" -> Json.fromString("u-peer"), "username" -> Json.fromString("peer")),
        "kind" -> Json.fromString("text"),
        "body" -> Json.fromString("hi"),
        "createdAt" -> Json.fromLong(1700000000L)
      )
    )

  /** 记录型 stub：捕获 (conversationId, after) 并回一条消息，驱动 cursor 前进。 */
  private final class RecordingClient(pulls: Ref[IO, List[(String, Long)]]) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
      conversationId: String,
      after: Long,
      limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      pulls.update(_ :+ ((conversationId, after))).as(
        Right(List(MessageSummary(after + 1L, "u-peer", "text", "hi", 1700000000L)))
      )

  private def mkService(
    pulls: Ref[IO, List[(String, Long)]],
    frames: Ref[IO, List[Json]],
    guard: FriendMessagingGuard
  ): FriendService =
    val client = new RecordingClient(pulls)
    new FriendService(
      IO.pure(Some(client)),
      AgentMessagingConfig(),
      guard = guard,
      onFriendEvent = Some(ev => frames.update(_ :+ FriendEvent.frontendFrame(ev)))
    )

  /** 生产同形前置：boot / 重连的 `refreshAll` → `refreshConversations` 会给每个
    * 已知会话建 cursor 条目（`mergeUnread`）。**没有 cursor 的会话**下
    * `guard.bumpUnread` 是 no-op（既有行为：`case None => s`）—— 这是本批之外的
    * 既有边界，已在交付报告「未及事项」中登记；本 spec 走生产正常路径。 */
  private def seedCursor(guard: FriendMessagingGuard, convId: String): IO[Unit] =
    guard.mergeUnread(ConversationSummary(convId, FriendSummary("u-peer", "peer", "Peer"), None, 0)).void

  test("A2-L2 真服务端信封（conversationId 在 payload 内）⇒ 未读 +1 且按水位增量补拉") {
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- seedCursor(g, "c-q")
      svc = mkService(pulls, frames, g)
      _ <- svc.onFriendEvent(envelope("e-1", serverEvent("c-q", 7L)))
      unread <- svc.unreadCounts
      pulled <- pulls.get
      fr <- frames.get
    yield (unread, pulled, fr)
    val (unread, pulled, fr) = prog.unsafeRunSync()

    assertEquals(unread.get("c-q"), Some(1), "payload 下钻后必须发生未读 +1（修前恒 None ⇒ 静默无操作）")
    assertEquals(pulled.map(_._1), List("c-q"), "必须触发该会话的增量补拉")
    assertEquals(pulled.map(_._2), List(0L), "首次补拉的 keyset 锚 = 本地 cursor（0 = 尚未见过消息）")
    assertEquals(fr.size, 1, "必须恰好广播一帧")

    // ── ①opt-A2 / L3：帧必须扁平（前端 messages.js 按顶层字段读）──
    val frame = fr.head
    val obj = frame.asObject.getOrElse(fail(s"帧必须是对象：${frame.noSpaces}"))
    assertEquals(frame.hcursor.get[String]("event").toOption, Some("message_new"))
    assertEquals(frame.hcursor.get[String]("type").toOption, Some("friend_event"))
    assertEquals(
      frame.hcursor.get[String]("conversationId").toOption,
      Some("c-q"),
      s"conversationId 必须平铺在帧顶层（修前被包在 payload 里 ⇒ 前端恒 undefined）：${frame.noSpaces}"
    )
    assertEquals(frame.hcursor.get[Long]("messageId").toOption, Some(7L))
    assertEquals(frame.hcursor.get[String]("body").toOption, Some("hi"))
    assertEquals(frame.hcursor.downField("sender").get[String]("userId").toOption, Some("u-peer"))
    assert(!obj.contains("payload"), s"帧不得残留 payload 包裹层：${frame.noSpaces}")
    assert(!obj.contains("type2"), "哨兵：帧内不得出现嵌套事件类型键")
  }

  test("A2-L2 旧形状容错（conversationId 在 event 顶层）不得回退成静默无操作") {
    val legacy = Json.obj(
      "type" -> Json.fromString("message_new"),
      "conversationId" -> Json.fromString("c-legacy"),
      "messageId" -> Json.fromLong(3L),
      "body" -> Json.fromString("old-shape")
    )
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- seedCursor(g, "c-legacy")
      svc = mkService(pulls, frames, g)
      _ <- svc.onFriendEvent(envelope("e-legacy", legacy))
      unread <- svc.unreadCounts
      pulled <- pulls.get
      fr <- frames.get
    yield (unread, pulled, fr)
    val (unread, pulled, fr) = prog.unsafeRunSync()

    assertEquals(unread.get("c-legacy"), Some(1), "旧形状必须仍能落到 not-None 分支（容错而非静默）")
    assertEquals(pulled.map(_._1), List("c-legacy"))
    assertEquals(fr.head.hcursor.get[String]("conversationId").toOption, Some("c-legacy"))
    assert(!fr.head.asObject.exists(_.contains("payload")))
  }

  test("A2 去重：同一 eventId 第二次到达不得重复 +1 或重复补拉") {
    val ev = envelope("e-dup", serverEvent("c-dup", 9L))
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- seedCursor(g, "c-dup")
      svc = mkService(pulls, frames, g)
      _ <- svc.onFriendEvent(ev)
      _ <- svc.onFriendEvent(ev)
      unread <- svc.unreadCounts
      pulled <- pulls.get
    yield (unread, pulled)
    val (unread, pulled) = prog.unsafeRunSync()
    assertEquals(unread.get("c-dup"), Some(1), "eventId 去重（既有语义不得被 seam 修复破坏）")
    assertEquals(pulled.size, 1)
  }

  test("frontendFrame 纯函数：嵌套与扁平两种入参产出同一扁平帧；畸形入参不抛") {
    val nested = FriendEvent("message_new", serverEvent("c-x", 1L))
    val flat = FriendEvent("message_new", Json.obj(
      "type" -> Json.fromString("message_new"),
      "messageId" -> Json.fromLong(1L),
      "conversationId" -> Json.fromString("c-x"),
      "sender" -> Json.obj("userId" -> Json.fromString("u-peer"), "username" -> Json.fromString("peer")),
      "kind" -> Json.fromString("text"),
      "body" -> Json.fromString("hi"),
      "createdAt" -> Json.fromLong(1700000000L)
    ))
    val a = FriendEvent.frontendFrame(nested)
    val b = FriendEvent.frontendFrame(flat)
    assertEquals(a, b, "嵌套/扁平两种信封必须折叠成同一帧（旧形状容错分支）")
    assertEquals(a.hcursor.get[String]("conversationId").toOption, Some("c-x"))
    val malformed = FriendEvent.frontendFrame(FriendEvent("message_new", Json.Null))
    assertEquals(malformed.hcursor.get[String]("event").toOption, Some("message_new"))
  }

end FriendEventSeamSpec
