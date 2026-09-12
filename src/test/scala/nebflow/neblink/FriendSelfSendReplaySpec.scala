package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite

/**
 * K-3 钉子（段 B 2026-09-12，链 `chain-n-9287d2f0`）：**agent 代发的本机自播**。
 *
 * 被钉的是「`doSend` 成功分支 ⇒ 恰好一帧 `message_new_self` 经**既有装配缝**
 * 广播」这一条，及其四条红线：
 *
 *  1. **恰好一帧**、且帧形状与「真 push」（服务端 `push_to_user_excluding` 的
 *     self 帧）**同源同形**——`frontendFrame` 展平后 `message_new_self` /
 *     `conversationId` / `messageId` / `body` / `origin` 全在帧**顶层**
 *     （前端 `messages.js#onFriendEvent` 的 K-2 分支按顶层字段读）；
 *  2. **不计未读**（`unreadCounts` 前后逐字同值）；
 *  3. **次序**：广播在 `pullConversation` **之前**（同 K-1 的理由：串行 REST 不得
 *     挡在「通知 UI」前）——本 spec 用共用次序日志断言真实发生次序，不靠读代码推断；
 *  4. **负控**：投递失败（`Left`）⇒ **零帧**；ask 档拒绝 ⇒ 零 POST + 零帧
 *     （#147 的 fail-closed 语义一行不动）；响应缺 `messageId` ⇒ **零帧**且不炸
 *     （退化为改动前形态 + WARN，不产生 `undefined` 幽灵节点）。
 *
 * 与 K-2 的**互不干扰**也在此钉住：同一条服务端 self 帧（他机来源）仍走
 * `onFriendEvent` 原路；`message_new` 主路径的未读 +1 不受影响。
 *
 * 夹具零网络：`NeblinkClient.sendRequest` 是本 spec 唯一的传输缝（`NeblinkClient`
 * 的全部 REST 出口都经它），故「是否真的发了 POST」是可判定读数而非推断。
 */
class FriendSelfSendReplaySpec extends FunSuite:

  private val FriendsJson =
    """{"friends":[{"userId":"u1","username":"customNL1","display_name":"林小满"}],"incoming":[],"outgoing":[]}"""

  private val ConvsJson =
    """[{"conversationId":"c1","friend":{"userId":"u1","username":"customNL1","display_name":"林小满"},"lastMessage":null,"unreadCount":0}]"""

  /** 投递成功响应（服务端 `SendMessageResponse` 真形状：camelCase + 四个必填）。 */
  private val SendOk = """{"messageId":5,"conversationId":"c1","createdAt":1700000000,"createdAtMs":1700000000000,"existing":false}"""

  private final class Fixture(
    val order: Ref[IO, List[String]],
    val frames: Ref[IO, List[Json]],
    val posts: Ref[IO, List[String]],
    val svc: FriendService,
    val client: NeblinkClient
  )

  /** 传输缝 stub：逐 URL 应答；每条出口都把序号写进共用次序日志。 */
  private def mkFixture(
    sendResponse: String = SendOk,
    mode: String = "auto",
    askConfirm: Option[String => IO[Boolean]] = None
  ): IO[Fixture] =
    for
      order <- Ref.of[IO, List[String]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      posts <- Ref.of[IO, List[String]](Nil)
      respRef <- Ref.of[IO, String](sendResponse)
      client = new NeblinkClient(
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
          else if url.endsWith("/api/friends") && method == "GET" then IO.pure(Right(FriendsJson))
          else if url.endsWith("/api/conversations") && method == "GET" then IO.pure(Right(ConvsJson))
          else if url.contains("/api/conversations/c1/messages") && method == "GET" then
            order.update(_ :+ "pull") *> IO.pure(
              Right("""[{"id":9,"senderId":"u1","kind":"text","body":"x","createdAt":1700000000}]""")
            )
          else if url.endsWith("/messages") && method == "POST" then
            order.update(_ :+ "post") *> posts.update(_ :+ body) *> respRef.get.map(Right(_))
          else IO.pure(Left(s"unexpected request: $method $url"))
      _ <- client
        .login("dev-1", "TestMac", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
        .flatMap(r => IO(assert(r.isRight, s"夹具登录失败：$r")))
      // 装配缝（`GatewayMain` 传 `onFriendEvent` 的那同一个口）——生产接线即
      // `wsHub.broadcast(FriendEvent.frontendFrame(ev))`，此处记录同一帧形态。
      svc = NeblinkWiring.friendService(
        IO.pure(Some(client)),
        AgentMessagingConfig(mode = mode),
        onFriendEvent = Some(ev =>
          order.update(_ :+ "broadcast") *> frames.update(_ :+ FriendEvent.frontendFrame(ev))
        ),
        askConfirm = askConfirm
      )
    yield new Fixture(order, frames, posts, svc, client)

  // ═══════════ ① 正向：auto 档代发 ⇒ 恰好一帧、形状与真 push 同源 ═══════════

  test("① auto 档 agent 代发 ⇒ 恰好一帧 message_new_self（扁平同形），且不计未读") {
    val prog = for
      f <- mkFixture()
      before <- f.svc.unreadCounts
      res <- f.svc.sendAsAgent("customNL1", "hi-from-agent")
      frames <- f.frames.get
      after <- f.svc.unreadCounts
    yield (res, frames, before, after)

    val (res, frames, before, after) = prog.unsafeRunSync()

    assert(res.isRight, s"auto 档必须投递成功：$res")
    assertEquals(frames.size, 1, s"恰好一帧（多一帧 = 重复上屏，少一帧 = K-3 未修）：$frames")

    val frame = frames.head
    val obj = frame.asObject.getOrElse(fail(s"帧必须是对象：${frame.noSpaces}"))
    val h = frame.hcursor
    assertEquals(h.get[String]("type").toOption, Some("friend_event"), "帧信封 type")
    assertEquals(h.get[String]("event").toOption, Some("message_new_self"), "事件名 = K-2 前端分支的判据字面量")
    assertEquals(
      h.get[String]("conversationId").toOption,
      Some("c1"),
      s"conversationId 必须平铺在帧顶层（前端按顶层读）：${frame.noSpaces}"
    )
    assertEquals(h.get[Long]("messageId").toOption, Some(5L), "messageId = keyed diff 的身份键")
    assertEquals(h.get[String]("body").toOption, Some("hi-from-agent"), "正文必须随帧到达（前端不再问服务端）")
    assertEquals(h.get[String]("origin").toOption, Some("agent"), "origin 与服务端 push 同形")
    assertEquals(h.get[Long]("createdAt").toOption, Some(1700000000L))
    assert(!obj.contains("payload"), s"帧不得残留 payload 包裹层：${frame.noSpaces}")

    // 「不计未读」判据落在**未读值**上，不落在 cursor 行是否存在上：`doSend` 的
    // 补拉腿（`pullConversation → advanceAnchor`）会materialize 该会话的 cursor 行，
    // 其 `unreadCount` 恒 0（既有行为，与本批无关）；真正的红线是「不得 +1」。
    assertEquals(
      after.getOrElse("c1", 0),
      before.getOrElse("c1", 0),
      s"**不得计未读**：agent 代发（自己发的）不涨未读（before=$before after=$after）"
    )
    assertEquals(after.getOrElse("c1", 0), 0, "**不得计未读**：agent 代发（自己发的）不涨未读")
    assertEquals(after.values.sum, 0, s"全表未读总和必须为 0（$after）")
  }

  // ═══════════ ② 次序：广播先于补拉（K-1 同源理由）═══════════

  test("② 次序：post → broadcast → pull（串行 REST 不得挡在通知 UI 之前）") {
    val prog = for
      f <- mkFixture()
      _ <- f.svc.sendAsAgent("customNL1", "order-probe")
      order <- f.order.get
      posts <- f.posts.get
    yield (order, posts)

    val (order, posts) = prog.unsafeRunSync()
    assertEquals(posts.size, 1, "恰一次 POST 投递")
    assert(
      posts.head.contains("origin") && posts.head.contains("agent"),
      s"wire 必须带 origin=agent（#290）：${posts.head}"
    )
    assertEquals(
      order,
      List("post", "broadcast", "pull"),
      s"真实发生次序 = POST 投递 → 广播 → 补拉；实测 $order"
    )
  }

  // ═══════════ ③ 负控：投递失败 ⇒ 零帧 ═══════════

  test("③ 负控：投递失败（上游 Left）⇒ 零帧、零未读变化") {
    // 未登录（clientProvider = None）⇒ withClient 返回 Left("Not logged in") ⇒ 不得广播
    val prog = for
      order <- Ref.of[IO, List[String]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      svc = NeblinkWiring.friendService(
        IO.pure(None),
        AgentMessagingConfig(mode = "auto"),
        onFriendEvent = Some(ev =>
          order.update(_ :+ "broadcast") *> frames.update(_ :+ FriendEvent.frontendFrame(ev))
        )
      )
      res <- svc.sendAsAgent("customNL1", "never-sent")
      fs <- frames.get
      ord <- order.get
      us <- svc.unreadCounts
    yield (res, fs, ord, us)

    val (res, frames, order, unread) = prog.unsafeRunSync()
    assert(res.isLeft, s"未登录必须失败：$res")
    assertEquals(order, Nil, "投递未发生 ⇒ 本机自播腿一次都不许跑")
    assertEquals(frames, Nil, "投递未发生 ⇒ 绝不允许广播（禁「先广播后失败」的乐观幻觉）")
    assertEquals(unread, Map.empty[String, Int], "未读零变化")
  }

  // ═══════════ ④ 零回归：ask 档确认链语义一行不动（#147）═══════════

  test("④ ask 档：批准 ⇒ 一次 POST + 一帧；拒绝 ⇒ 零 POST + 零帧（fail-closed 不回归）") {
    val prog = for
      fOk <- mkFixture(mode = "ask", askConfirm = Some(_ => IO.pure(true)))
      resOk <- fOk.svc.sendAsAgent("customNL1", "approved")
      fOkPosts <- fOk.posts.get
      fOkFrames <- fOk.frames.get
      fNo <- mkFixture(mode = "ask", askConfirm = Some(_ => IO.pure(false)))
      resNo <- fNo.svc.sendAsAgent("customNL1", "declined")
      fNoPosts <- fNo.posts.get
      fNoFrames <- fNo.frames.get
      fErr <- mkFixture(mode = "ask", askConfirm = Some(_ => IO.raiseError(new RuntimeException("no surface"))))
      resErr <- fErr.svc.sendAsAgent("customNL1", "no-surface")
      fErrPosts <- fErr.posts.get
      fErrFrames <- fErr.frames.get
    yield (resOk, fOkPosts, fOkFrames, resNo, fNoPosts, fNoFrames, resErr, fErrPosts, fErrFrames)

    val (resOk, okPosts, okFrames, resNo, noPosts, noFrames, resErr, errPosts, errFrames) = prog.unsafeRunSync()

    assert(resOk.isRight, s"批准后必须投递：$resOk")
    assertEquals(okPosts.size, 1, "批准 ⇒ 恰一次 POST")
    assertEquals(okFrames.size, 1, "批准 ⇒ 恰一帧（自播只在真投递后发生）")

    assert(resNo.isLeft, "拒绝 ⇒ 失败")
    assertEquals(noPosts, Nil, "拒绝 ⇒ 零投递（#147 fail-closed 语义）")
    assertEquals(noFrames, Nil, "拒绝 ⇒ 零帧")

    assert(resErr.isLeft, "确认链失败 ⇒ 失败")
    assert(resErr.left.toOption.exists(_.contains("NOT sent")), s"文案必须可判定：$resErr")
    assertEquals(errPosts, Nil, "确认失败 ⇒ 零投递")
    assertEquals(errFrames, Nil, "确认失败 ⇒ 零帧")
  }

  // ═══════════ ⑤ 缺 messageId：零帧退化（不产幽灵节点）═══════════

  test("⑤ 响应缺 messageId ⇒ 零帧（显式退化，不广播 undefined 身份的脏帧）") {
    val prog = for
      f <- mkFixture(sendResponse = """{"conversationId":"c1","createdAt":1700000000}""")
      res <- f.svc.sendAsAgent("customNL1", "no-id")
      frames <- f.frames.get
      posts <- f.posts.get
    yield (res, frames, posts)

    val (res, frames, posts) = prog.unsafeRunSync()
    assert(res.isRight, "投递本身成功（响应形状异常不改判投递结果）")
    assertEquals(posts.size, 1, "POST 已发生")
    assertEquals(frames, Nil, "无消息身份 ⇒ 不广播（前端 keyed diff 会落 undefined 幽灵气泡）")
  }

  // ═══════════ ⑥ 与 K-2（他机 self 帧）互不干扰 + message_new 主路径不退化 ═══════════

  test("⑥ K-2 他机 self 帧仍走原路（一帧 / 不计未读）；message_new 主路径未读 +1 不退化") {
    val prog = for
      f <- mkFixture()
      _ <- f.svc.onFriendEvent(
        Json.obj(
          "type" -> Json.fromString("friend_event"),
          "eventId" -> Json.fromString("evt-self-1"),
          "event" -> Json.obj(
            "type" -> Json.fromString("message_new_self"),
            "payload" -> Json.obj(
              "messageId" -> Json.fromLong(7),
              "conversationId" -> Json.fromString("c1"),
              "sender" -> Json.obj("userId" -> Json.fromString("u-me")),
              "kind" -> Json.fromString("text"),
              "body" -> Json.fromString("from-another-device"),
              "createdAt" -> Json.fromLong(1700000001L)
            )
          )
        )
      )
      framesSelf <- f.frames.get
      unreadSelf <- f.svc.unreadCounts
      _ <- f.svc.onFriendEvent(
        Json.obj(
          "type" -> Json.fromString("friend_event"),
          "eventId" -> Json.fromString("evt-new-1"),
          "event" -> Json.obj(
            "type" -> Json.fromString("message_new"),
            "payload" -> Json.obj(
              "messageId" -> Json.fromLong(8),
              "conversationId" -> Json.fromString("c1"),
              "sender" -> Json.obj("userId" -> Json.fromString("u1")),
              "kind" -> Json.fromString("text"),
              "body" -> Json.fromString("from-peer"),
              "createdAt" -> Json.fromLong(1700000002L)
            )
          )
        )
      )
      framesAll <- f.frames.get
      unreadNew <- f.svc.unreadCounts
    yield (framesSelf, unreadSelf, framesAll, unreadNew)

    val (framesSelf, unreadSelf, framesAll, unreadNew) = prog.unsafeRunSync()

    assertEquals(framesSelf.size, 1, "他机 self 帧：恰一帧（K-2 分支）")
    assertEquals(framesSelf.head.hcursor.get[String]("event").toOption, Some("message_new_self"))
    assertEquals(framesSelf.head.hcursor.get[String]("conversationId").toOption, Some("c1"))
    assertEquals(unreadSelf.getOrElse("c1", 0), 0, "他机 self 帧亦不得计未读")

    assertEquals(framesAll.size, 2, "message_new 仍广播一帧（主路径不退化）")
    assertEquals(framesAll.last.hcursor.get[String]("event").toOption, Some("message_new"))
    assertEquals(unreadNew.getOrElse("c1", 0), 1, "message_new 未读 +1 语义未退化")
  }

  // ═══════════ ⑦ 幂等：同一调用路径重复触发 ⇒ 每条各自恰一帧 ═══════════

  test("⑦ 幂等：逐次代发各恰一帧（无重复广播腿）") {
    val prog = for
      f <- mkFixture()
      _ <- f.svc.sendAsAgent("customNL1", "m-1")
      _ <- f.svc.sendAsAgent("customNL1", "m-2")
      frames <- f.frames.get
      posts <- f.posts.get
    yield (frames, posts)

    val (frames, posts) = prog.unsafeRunSync()
    assertEquals(posts.size, 2, "两次投递")
    assertEquals(frames.size, 2, "两条消息 ⇒ 两帧（不是四帧：无重复广播腿）")
    assertEquals(frames.map(_.hcursor.get[String]("body").toOption), List(Some("m-1"), Some("m-2")))
  }
