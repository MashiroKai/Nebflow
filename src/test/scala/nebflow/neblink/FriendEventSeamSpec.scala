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

  /** 记录型 stub：捕获 (conversationId, after) 并回一条消息，驱动 cursor 前进。
    * `order`（段 A K-1）：把「补拉真的发生了」写进共用的次序日志，与广播侧标记
    * 合成同一条时间线 —— 顺序断言必须落在**真实发生次序**上（不靠读代码推断）。 */
  private final class RecordingClient(
    pulls: Ref[IO, List[(String, Long)]],
    order: Option[Ref[IO, List[String]]] = None
  ) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
      conversationId: String,
      after: Long,
      limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      order.fold(IO.unit)(_.update(_ :+ "pull")) *>
        pulls.update(_ :+ ((conversationId, after))).as(
          Right(List(MessageSummary(after + 1L, "u-peer", "text", "hi", 1700000000L)))
        )

  private def mkService(
    pulls: Ref[IO, List[(String, Long)]],
    frames: Ref[IO, List[Json]],
    guard: FriendMessagingGuard,
    order: Option[Ref[IO, List[String]]] = None
  ): FriendService =
    val client = new RecordingClient(pulls, order)
    new FriendService(
      IO.pure(Some(client)),
      AgentMessagingConfig(),
      guard = guard,
      onFriendEvent = Some(ev =>
        order.fold(IO.unit)(_.update(_ :+ "broadcast")) *>
          frames.update(_ :+ FriendEvent.frontendFrame(ev))
      )
    )

  /** 生产同形前置：boot / 重连的 `refreshAll` → `refreshConversations` 会给每个
    * 已知会话建 cursor 条目（`mergeUnread`）。本 spec 走这条**生产正常路径**
    * （cursor 先于推送就位），只钉 A2 接缝本身。
    *
    * 「cursor 缺席时首条推送的 +1」边界原为 `case None => s` no-op，已由 #309
    * 修复（`bumpUnread` 改为缺席 materialize 条目 + 返回回落信号），回归钉子见
    * `FriendUnreadCursorRebuildSpec`。 */
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
    // ⚠️ 判据更新（好友消息静默丢失修复批 A · 冷锚取数口径）：
    // 本 spec 的 `seedCursor` 只走 `mergeUnread` ⇒ **拉取锚点仍是 0（冷锚）**。
    // 修前冷锚一律 `after=0`（keyset 从**最旧**一页取），于是「取回的是历史最早一页、
    // 连事件自己那条都没取到」。本批改为：冷锚 + 事件自带 `messageId=hint` ⇒
    // `after = hint - 1`（取**恰好那一窗**）。这里 hint=7 ⇒ 6。
    assertEquals(pulled.map(_._2), List(6L), "冷锚 + 事件提示 ⇒ after = hint - 1（取恰好那一窗，不再从最旧一页取）")
    // 修前「恰好一帧」（只广播事件帧）；接入**拉取即派发**后 = 事件帧 + **补拉回放帧**
    // 各一 ⇒ 2 帧。这不是放宽断言：下面同时钉住了两帧各自的契约（事件帧 flat；
    // 回放帧带 `backfill: true` + `senderId`）。
    assertEquals(fr.size, 2, "事件帧 + 补拉回放帧（拉取即派发，§3.2①）")
    assertEquals(
      fr(1).hcursor.get[Boolean]("backfill").toOption,
      Some(true),
      "第 2 帧必须是**补拉回放**帧（前端据此不涨未读/不自动转发/不认领乐观项）"
    )
    assertEquals(fr(1).hcursor.get[String]("senderId").toOption, Some("u-peer"), "回放帧必须带 senderId（前端据此判气泡方向）")

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

  // ===== 段 A（msg-latency-client）K-1 / K-2 钉子，2026-09-12 =====

  test("K-1 广播先于补拉：一次事件里 broadcast 必须先于 pull（关键路径摘掉串行 REST）") {
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      g = new FriendMessagingGuard()
      _ <- seedCursor(g, "c-k1")
      svc = mkService(pulls, frames, g, Some(order))
      _ <- svc.onFriendEvent(envelope("e-k1", serverEvent("c-k1", 7L)))
      ord <- order.get
      unread <- svc.unreadCounts
      pulled <- pulls.get
      fr <- frames.get
    yield (ord, unread, pulled, fr)
    val (ord, unread, pulled, fr) = prog.unsafeRunSync()

    // 修前 = List("pull", "broadcast")（handleEvent *> 回调）⇒ 广播被一次 REST
    // 往返（本机实测 135.9–499.0 ms）挡在后面。本断言即「摘下来」的判据。
    //
    // ⚠️ 判据更新（批 A · 拉取即派发）：接管拉之后**补拉自己也会广播回放帧** ⇒
    // 时间线末尾多一条 "broadcast"。K-1 的判据是「**事件帧**的广播在补拉之前」，
    // 故断言取前两项；第 3 项是补拉回放帧（它按定义只能在补拉之后）。
    assertEquals(ord, List("broadcast", "pull", "broadcast"), s"事件广播必须先于补拉发生，实测次序：$ord")
    assertEquals(ord.take(2), List("broadcast", "pull"), "K-1 判据：事件帧广播先于补拉")
    // 语义不变：补拉与未读维护照旧发生（只是不再挡在广播前）。
    assertEquals(unread.get("c-k1"), Some(1), "K-1 不得改变未读口径")
    assertEquals(pulled.map(_._1), List("c-k1"))
    assertEquals(fr.size, 2, "事件帧 + 补拉回放帧（批 A 拉取即派发）")
  }

  test("K-1 广播回调抛错不得吃掉补拉（best-effort 广播 + 补拉照常）") {
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      order <- Ref.of[IO, List[String]](Nil)
      g = new FriendMessagingGuard()
      _ <- seedCursor(g, "c-k1e")
      svc = new FriendService(
        IO.pure(
          Some(new RecordingClient(pulls, Some(order)))
        ),
        AgentMessagingConfig(),
        guard = g,
        onFriendEvent = Some(_ => order.update(_ :+ "broadcast") *> IO.raiseError(new RuntimeException("hub down")))
      )
      _ <- svc.onFriendEvent(envelope("e-k1e", serverEvent("c-k1e", 1L)))
      unread <- svc.unreadCounts
      pulled <- pulls.get
      ord <- order.get
    yield (unread, pulled, ord)
    val (unread, pulled, ord) = prog.unsafeRunSync()
    assertEquals(ord, List("broadcast", "pull"), "广播异常被吞，流程继续到补拉")
    assertEquals(pulled.map(_._1), List("c-k1e"), "广播异常绝不得吃掉补拉（修前的落点，现由顺序保证）")
    assertEquals(unread.get("c-k1e"), Some(1))
  }

  test("K-2 真服务端信封 message_new_self ⇒ 补拉 + 透传，但**不得计未读**（cursor 在册）") {
    val selfEvent = Json.obj(
      "type" -> Json.fromString("message_new_self"),
      "payload" -> Json.obj(
        "messageId" -> Json.fromLong(21L),
        "conversationId" -> Json.fromString("c-self"),
        "sender" -> Json.obj("userId" -> Json.fromString("u-me"), "username" -> Json.fromString("me")),
        "kind" -> Json.fromString("text"),
        "body" -> Json.fromString("agent-sent"),
        "origin" -> Json.fromString("agent"),
        "createdAt" -> Json.fromLong(1700000000L)
      )
    )
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- seedCursor(g, "c-self")
      svc = mkService(pulls, frames, g)
      _ <- svc.onFriendEvent(envelope("e-self", selfEvent))
      unread <- svc.unreadCounts
      pulled <- pulls.get
      fr <- frames.get
    yield (unread, pulled, fr)
    val (unread, pulled, fr) = prog.unsafeRunSync()

    assertEquals(unread.get("c-self"), Some(0), "self 事件**不得**计未读（修前该类型被 case other 丢弃）")
    assertEquals(pulled.map(_._1), List("c-self"), "self 事件仍须补拉（REST 是事实来源）")
    // 事件帧 + 补拉回放帧（批 A 拉取即派发）；两帧事件名同为 self（oursHint=Some(true)）。
    assertEquals(fr.size, 2, "self 事件必须透传给前端（否则本机 UI 零事件）+ 补拉回放帧")
    assertEquals(fr.head.hcursor.get[String]("event").toOption, Some("message_new_self"))
    assertEquals(fr(1).hcursor.get[Boolean]("backfill").toOption, Some(true), "第 2 帧 = 补拉回放帧")
    assertEquals(
      fr.head.hcursor.get[String]("conversationId").toOption,
      Some("c-self"),
      s"帧必须扁平（前端按顶层 conversationId 命中会话）：${fr.head.noSpaces}"
    )
  }

  test("K-2 cursor 缺席时 self 事件不得 materialize 未读条目（+1 的更隐蔽形态）") {
    val selfEvent = Json.obj(
      "type" -> Json.fromString("message_new_self"),
      "payload" -> Json.obj(
        "messageId" -> Json.fromLong(22L),
        "conversationId" -> Json.fromString("c-self-new"),
        "sender" -> Json.obj("userId" -> Json.fromString("u-me")),
        "kind" -> Json.fromString("text"),
        "body" -> Json.fromString("agent-sent"),
        "createdAt" -> Json.fromLong(1700000001L)
      )
    )
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard() // 无 seedCursor：全新会话
      svc = mkService(pulls, frames, g)
      _ <- svc.onFriendEvent(envelope("e-self-new", selfEvent))
      unread <- svc.unreadCounts
      pulled <- pulls.get
    yield (unread, pulled)
    val (unread, pulled) = prog.unsafeRunSync()
    // 条目由**补拉**的 `advanceAnchor` 建（未读 0）；若走了 bumpUnread 的缺席回落
    // 分支（与 message_new 同路），这里会看到 `Some(1)` —— 故 Some(0) 正是
    // 「补拉了但一个未读都没加」的判据（对照见上一条 message_new 的 Some(1)）。
    assertEquals(unread.get("c-self-new"), Some(0), "self 事件不得计未读（不得走 bumpUnread 的 +1 回落分支）")
    assertEquals(pulled.map(_._1), List("c-self-new"))
  }

  test("K-2 self 事件缺 conversationId ⇒ 记 debug 且零副作用（不崩、不猜）") {
    val bad = Json.obj(
      "type" -> Json.fromString("message_new_self"),
      "payload" -> Json.obj("messageId" -> Json.fromLong(23L), "body" -> Json.fromString("x"))
    )
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(pulls, frames, g)
      _ <- svc.onFriendEvent(envelope("e-self-bad", bad))
      unread <- svc.unreadCounts
      pulled <- pulls.get
      fr <- frames.get
    yield (unread, pulled, fr)
    val (unread, pulled, fr) = prog.unsafeRunSync()
    assertEquals(unread, Map.empty[String, Int])
    assertEquals(pulled, Nil)
    assertEquals(fr.size, 1, "帧仍透传（前端自行判会话命中）")
  }

  test("frontendFrame 纯函数：嵌套与扁平两种入参产出同一扁平帧；畸形入参不抛") {    val nested = FriendEvent("message_new", serverEvent("c-x", 1L))
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
