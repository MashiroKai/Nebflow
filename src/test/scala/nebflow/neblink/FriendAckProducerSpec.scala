package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite

/**
 * D-B ack 生产者（2026-09-13 好友推送修复批）· 真通路钉子。
 *
 * 被钉的是三件独立的事（缺一即「接了但走不到」）：
 *  1. **帧形状冻结**：`{"type":"ack","eventId":"…"}`（`NeblinkRelayTunnel.ackFrame`）
 *     —— 与服务端 `ClientToServer::Ack` 的 `type`/`eventId` 键名逐字对齐；
 *  2. **ack 的时机与范围**：只在**处理完成后**、只在 `message-<id>` 面下发；
 *     重复帧（at-least-once 重放）**也要 ack**，否则服务端重放永远退不掉；
 *  3. **失败方向**：ack 异常/未接线**绝不**吃掉消费与广播（那才是会丢消息的方向）。
 *
 * 生产接线（`GatewayMain` → `neblinkService.relayTunnelOpt` → `NeblinkRelayTunnel.sendAck`）
 * 不在此覆盖：它由隔离实例的 E2E（真 mock 上游收到 ack 帧）覆盖，本 spec 只管决策面。
 */
class FriendAckProducerSpec extends FunSuite:

  private def envelope(eventId: String, event: Json): Json =
    Json.obj("type" -> Json.fromString("friend_event"),
      "eventId" -> Json.fromString(eventId),
      "event" -> event)

  private def serverEvent(convId: String, messageId: Long, eventType: String = "message_new"): Json =
    Json.obj(
      "type" -> Json.fromString(eventType),
      "payload" -> Json.obj(
        "messageId" -> Json.fromLong(messageId),
        "conversationId" -> Json.fromString(convId),
        "sender" -> Json.obj("userId" -> Json.fromString("u-peer")),
        "kind" -> Json.fromString("text"),
        "body" -> Json.fromString("hi"),
        "createdAt" -> Json.fromLong(1700000000L)
      )
    )

  private final class StubClient(pulls: Ref[IO, List[String]]) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
      conversationId: String,
      after: Long,
      limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      pulls.update(_ :+ conversationId).as(
        Right(List(MessageSummary(after + 1L, "u-peer", "text", "hi", 1700000000L)))
      )

  private def mkService(
    pulls: Ref[IO, List[String]],
    order: Ref[IO, List[String]],
    ackSender: Option[String => IO[Unit]]
  ): FriendService =
    new FriendService(
      IO.pure(Some(new StubClient(pulls))),
      AgentMessagingConfig(),
      guard = new FriendMessagingGuard(),
      onFriendEvent = Some(_ => order.update(_ :+ "broadcast")),
      ackSender = ackSender
    )

  private def seed(conv: String): IO[FriendMessagingGuard] =
    val g = new FriendMessagingGuard()
    g.mergeUnread(ConversationSummary(conv, FriendSummary("u-peer", "peer", "Peer"), None, 0)).as(g)

  // ── 1. 帧形状冻结 ──────────────────────────────────────────
  test("ackFrame：冻结形状 {\"type\":\"ack\",\"eventId\":\"message-<id>\"}（键名/取值即协议）") {
    val f = NeblinkRelayTunnel.ackFrame("message-41")
    assertEquals(f.hcursor.get[String]("type").toOption, Some("ack"))
    assertEquals(f.hcursor.get[String]("eventId").toOption, Some("message-41"))
    assertEquals(
      f.noSpaces,
      """{"type":"ack","eventId":"message-41"}""",
      "线上形状逐字节冻结（服务端 serde：tag=type, rename_all=snake_case, eventId）"
    )
    assertEquals(f.asObject.map(_.keys.toList), Some(List("type", "eventId")))
  }

  // ── 2. 时机 + 范围 ─────────────────────────────────────────
  test("message-<id> 帧处理完成后 ack 一次，且位于广播/补拉之后") {
    val prog = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      acks <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-ack")
      svc = mkService(pulls, order, Some(id => acks.update(_ :+ id)))
      _ <- svc.onFriendEvent(envelope("message-41", serverEvent("c-ack", 41L)))
      a <- acks.get
      ord <- order.get
    yield (a, ord)
    val (acks, ord) = prog.unsafeRunSync()
    assertEquals(acks, List("message-41"), "必须且只 ack 一次，eventId 原样透传")
    // ⚠️ 判据更新（好友消息静默丢失修复批 A · §3.2①「拉取即派发」）：
    // 本 spec 的 `seed` 只走 `mergeUnread` ⇒ 拉取锚点为 0（冷锚），而事件自带
    // `messageId=41` ⇒ 补拉取**恰好那一窗**并**派发一帧** ⇒ 时间线里多一条 "broadcast"。
    // 本 spec 关心的是「ack 只一次 + 消费链照旧发生」，故计数口径 = broadcast 恰好 2 条
    // （事件帧 + 补拉回放帧）且 ack 恰好 1 条。
    assertEquals(ord, List("broadcast", "broadcast"), "消费/广播链路照旧发生（事件帧 + 补拉回放帧）")
    // 时机：ack 在广播之后（本 spec 把 ack 记进同一个次序日志再断言一次）
    val prog2 = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-ack2")
      svc = mkService(pulls, order, Some(id => order.update(_ :+ s"ack:$id")))
      _ <- svc.onFriendEvent(envelope("message-7", serverEvent("c-ack2", 7L)))
      ord2 <- order.get
    yield ord2
    assertEquals(
      prog2.unsafeRunSync(),
      List("broadcast", "broadcast", "ack:message-7"),
      "处理完成后才 ack（批 A 后 broadcast 有两条：事件帧 + 补拉回放帧；ack 仍恒在最后）"
    )
  }

  test("重复帧（同 eventId）仍须 ack —— at-least-once 重放靠 ack 才退得掉") {
    val prog = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      acks <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-dup")
      svc = mkService(pulls, order, Some(id => acks.update(_ :+ id)))
      _ <- svc.onFriendEvent(envelope("message-9", serverEvent("c-dup", 9L)))
      _ <- svc.onFriendEvent(envelope("message-9", serverEvent("c-dup", 9L)))
      a <- acks.get
      p <- pulls.get
    yield (a, p)
    val (acks, pulls) = prog.unsafeRunSync()
    assertEquals(acks, List("message-9", "message-9"), "两次到达 = 两次 ack（幂等、服务端游标单调）")
    assertEquals(pulls.size, 1, "重复帧不得二次补拉（既有 eventId 去重语义不动）")
  }

  test("非消息面 eventId（friend-evt-<rowId> / 无前缀 uuid）一律不 ack（本批靶只有 message-<id>）") {
    val prog = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      acks <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-nomessage")
      svc = mkService(pulls, order, Some(id => acks.update(_ :+ id)))
      _ <- svc.onFriendEvent(envelope("friend-evt-12", Json.obj(
        "type" -> Json.fromString("friend_request"), "payload" -> Json.obj()
      )))
      _ <- svc.onFriendEvent(envelope("8c1f-uuid", serverEvent("c-nomessage", 13L, "message_new_self")))
      a <- acks.get
    yield a
    assertEquals(prog.unsafeRunSync(), Nil, "消息面之外不越界（服务端游标支属 S2 服务端批）")
  }

  test("message_new_self 若带 message-<id> 形 eventId ⇒ 仍 ack（判据是 eventId 形状，不是事件名）") {
    val prog = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      acks <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-self")
      svc = mkService(pulls, order, Some(id => acks.update(_ :+ id)))
      _ <- svc.onFriendEvent(envelope("message-77", serverEvent("c-self", 77L, "message_new_self")))
      a <- acks.get
    yield a
    assertEquals(prog.unsafeRunSync(), List("message-77"))
  }

  // ── 3. 失败方向 ────────────────────────────────────────────
  test("未接线（ackSender=None）⇒ 显式 no-op，消费链不受影响") {
    val prog = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-none")
      svc = mkService(pulls, order, None)
      _ <- svc.onFriendEvent(envelope("message-5", serverEvent("c-none", 5L)))
      p <- pulls.get
      u <- svc.unreadCounts
    yield (p, u)
    val (pulls, unread) = prog.unsafeRunSync()
    assertEquals(pulls, List("c-none"))
    assertEquals(unread.get("c-none"), Some(1))
  }

  test("ackSender 抛错 ⇒ 绝不吃掉处理（未读/补拉照常，且不冒泡）") {
    val prog = for
      pulls <- Ref.of[IO, List[String]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      g <- seed("c-boom")
      svc = mkService(pulls, order, Some(_ => IO.raiseError(new RuntimeException("relay down"))))
      _ <- svc.onFriendEvent(envelope("message-6", serverEvent("c-boom", 6L)))
      p <- pulls.get
      u <- svc.unreadCounts
    yield (p, u)
    val (pulls, unread) = prog.unsafeRunSync()
    assertEquals(pulls, List("c-boom"), "ack 失败不得回滚/吞掉补拉")
    assertEquals(unread.get("c-boom"), Some(1))
  }

end FriendAckProducerSpec
