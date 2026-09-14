package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.FunSuite

import java.util.regex.Pattern

/**
 * 好友消息**静默丢失**修复批 A ·「拉取即派发 + 静默面留痕 + 游标幂等」回归钉子。
 *
 * ## 本 spec 钉的四条机械判据（逐条对应用例）
 *  - **① 连续双消息**：两条 `message_new` 事件 ⇒ 两条补拉，且**每条补拉都带显式
 *    `trigger=` / `eventId=`** 并与 `friend_event processed … id=<eventId>` **配对**
 *    （不再靠「有没有 processed 行」反推）。用例 [[A2]]。
 *  - **② W 分支注入**：每条丢弃分支的 WARN **行内同时**含 `messageId`（或 `eventId`）
 *    + `conversationId` + `reason`。用例 [[A3]]（W1/W3/W4/W5/W6/W7/W8 逐分支注入）。
 *  - **③ 游标幂等**：`pullAnchor == dispatchedMax == maxId(第2条)`，且**逐行**断言
 *    `pullAnchor <= dispatchedMax`（🔴 禁 `pullAnchor > dispatchedMax`）。用例 [[A2]]/[[A4]]。
 *  - **④ 对账恒等式**：`pulled == dispatched + skipped`；`skipped > 0` ⇒ 同行含
 *    `reasons=[…]` **且另有** `friend_pull_skipped` WARN。用例 [[A1]]/[[A7]]。
 *
 * ## 为什么能断言行内容（本仓既有 spec 的已知缺口）
 * 修前日志行**不可断言**（既有 spec 自述「只断言副作用、不断言行」）。本批把
 * 「留痕行的发射」收成单点（`FriendService.emit`）：**同一行文本**既进日志、又进
 * `FriendMessagingGuard` 的有界 trace 环（`recordPullLine` / `recentPullLines`）。
 * 于是下面的断言打在**真调用链产出的行**上，而不是「测试里重建的一行文本」。
 *
 * ## 核心缺陷（P0，本 spec 的主靶）
 * 修前 `pullConversation` 是**纯 cursor 维护**：拉到消息只推进锚点、**一条都不派发**，
 * 而锚点照推 ⇒ 那条消息在本机**永久不可达**（既没进 UI，下次 `after=` 也已越过它）。
 * [[A1]] 的「帧数 == 拉取数」即该缺陷的正向钉子。
 *
 * ## 验红自证（供复核节点实证；本轮**未执行**——窗口纪律①今晚禁 sbt）
 * 反转修法（只改一处）：把 `dispatchPull` 里的
 * `dispatched.toList.traverse_(m => dispatchPulled(conversationId, m, oursHint))` 整段删掉
 * （恢复「只推进不派发」）。预期变红：[[A1]]（帧数 2 → 0）、[[A6]]（帧形状无从断言）、
 * [[A7]] 的部分（派发相关分支）；[[A2]]/[[A3]]/[[A4]] 应保持绿（它们钉的是
 * 显式字段与游标拆分，不是派发腿本身）——这是本 spec 的对偶，证明变红是钉子有效
 * 而非「什么都会红」。
 *
 * ## 明确未证
 *  - 日志**载体**（logback appender 层）未断言；断言的是进环的同一行文本（见上）。
 *  - 真浏览器渲染（`out` 气泡方向 / 徽标 DOM）未断言：属前端面，读数为
 *    `tests/friend-cache-latency.spec.mjs` 一族（本轮未跑，窗口纪律①）。
 *  - 本 spec 不触网、不起服务、不碰宿主 8080。
 */
class FriendPulledDispatchSpec extends FunSuite:

  // ── 构造夹具 ──────────────────────────────────────────────

  private def envelope(eventId: String, event: Json): Json =
    Json.obj("type" -> "friend_event".asJson, "eventId" -> eventId.asJson, "event" -> event)

  /** 真服务端信封形状：`payload` 下钻（A2 接缝口径）。`convId=None` = 缺会话键的注入形态。 */
  private def serverEvent(convId: Option[String], messageId: Long, eventType: String = "message_new"): Json =
    val base = Json.obj(
      "messageId" -> Json.fromLong(messageId),
      "senderId" -> "u-peer".asJson,
      "kind" -> "text".asJson,
      "body" -> "hi".asJson,
      "createdAt" -> Json.fromLong(1700000000L)
    )
    val payload = convId.fold(base)(c => base.deepMerge(Json.obj("conversationId" -> c.asJson)))
    Json.obj("type" -> eventType.asJson, "payload" -> payload)

  private def msg(id: Long, senderId: String = "u-peer"): MessageSummary =
    MessageSummary(id, senderId, "text", s"m$id", 1700000000L)

  /** 脚本化 stub：记录 `(conversationId, after, limit)` 并按 `after` 给页。 */
  private final class ScriptedClient(
      calls: Ref[IO, List[(String, Long, Int)]],
      script: Long => List[MessageSummary]
  ) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
        conversationId: String,
        after: Long,
        limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      calls.update(_ :+ ((conversationId, after, limit))) *> IO.pure(Right(script(after)))

  private final class FailingClient(calls: Ref[IO, List[(String, Long, Int)]]) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
        conversationId: String,
        after: Long,
        limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      calls.update(_ :+ ((conversationId, after, limit))) *> IO.pure(Left("boom"))

  private def mkService(
      calls: Ref[IO, List[(String, Long, Int)]],
      frames: Ref[IO, List[Json]],
      g: FriendMessagingGuard,
      script: Long => List[MessageSummary] = a => List(msg(a + 1L))
  ): FriendService =
    new FriendService(
      IO.pure(Some(new ScriptedClient(calls, script))),
      AgentMessagingConfig(),
      guard = g,
      onFriendEvent = Some(ev => frames.update(_ :+ FriendEvent.frontendFrame(ev)))
    )

  // ── 行内字段的机械读取（判据②③④的判据式都建立在这三个之上）──────

  /** `name=<value>` 取值（值不含空白；本批的行格式保证如此）。 */
  private def field(line: String, name: String): Option[String] =
    val m = Pattern.compile(s"\\b" + Pattern.quote(name) + "=(\\S+)").matcher(line)
    if m.find() then Some(m.group(1)) else None

  private def num(line: String, name: String): Option[Long] =
    field(line, name).flatMap(_.toLongOption)

  private def linesWith(ls: List[String], needle: String): List[String] = ls.filter(_.contains(needle))

  /** **判据③**（逐行不变式）：每一行对账/跳过行都必须满足
    * `pullAnchor <= dispatchedMax`（🔴 禁 `pullAnchor > dispatchedMax`）。 */
  private def assertAnchorInvariant(ls: List[String]): Unit =
    val checked = ls.filter(l => field(l, "pullAnchor").isDefined && field(l, "dispatchedMax").isDefined)
    assert(checked.nonEmpty, s"至少应有一行带 pullAnchor/dispatchedMax：${ls.mkString("\n")}")
    checked.foreach { l =>
      val pa = num(l, "pullAnchor").getOrElse(fail(s"pullAnchor 不可解析：$l"))
      val dm = num(l, "dispatchedMax").getOrElse(fail(s"dispatchedMax 不可解析：$l"))
      assert(pa <= dm, s"🔴 禁 pullAnchor > dispatchedMax（pa=$pa dm=$dm）：$l")
    }

  /** **判据④**（恒等式）：逐行 `pulled == dispatched + skipped`。 */
  private def assertIdentity(ls: List[String]): Unit =
    val checked = ls.filter(l => field(l, "pulled").isDefined)
    assert(checked.nonEmpty, s"至少应有一行带 pulled/dispatched/skipped：${ls.mkString("\n")}")
    checked.foreach { l =>
      val p = num(l, "pulled").getOrElse(fail(s"pulled 不可解析：$l"))
      val d = num(l, "dispatched").getOrElse(fail(s"dispatched 不可解析：$l"))
      val k = num(l, "skipped").getOrElse(fail(s"skipped 不可解析：$l"))
      assertEquals(p, d + k, s"恒等式 pulled == dispatched + skipped 不成立：$l")
    }

  /** **判据②**：一条 WARN 行必须**同时**含三字段（键名存在即算，值可为 `<none>`）。 */
  private def assertThreeFields(l: String): Unit =
    val hasId = l.contains("messageId=") || l.contains("eventId=")
    assert(hasId, s"WARN 行缺 messageId/eventId：$l")
    assert(l.contains("conversationId="), s"WARN 行缺 conversationId：$l")
    assert(l.contains("reason=") || l.contains("reasons="), s"WARN 行缺 reason：$l")

  private def warnLines(ls: List[String]): List[String] =
    ls.filter(l => l.contains("branch=") || l.startsWith("friend_pull_skipped") || l.startsWith("friend_pull_failed"))

  // ══ A1 · 判据④ + P0 主靶：拉取即派发，派发条数 == 拉取条数 ══════════
  test("A1 拉取即派发：拉取 2 条 ⇒ 派发 2 帧（backfill 形状），且 pulled==dispatched+skipped") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 112L) // 温锚：已派发到 112
      svc = mkService(calls, frames, g, _ => List(msg(113L), msg(114L)))
      _ <- svc.pullConversation("c", FriendPullTrigger.EventMessageNew, Some("e-2"), oursHint = Some(false))
      fs <- frames.get
      ls <- g.recentPullLines
      st <- g.pullStats
    yield (fs, ls, st)

    val (fs, ls, st) = prog.unsafeRunSync()
    assertEquals(fs.size, 2, s"🔴 P0 主靶：拉取 2 条必须派发 2 帧（修前恒 0 帧）：${fs.mkString("\n")}")
    assertEquals(fs.flatMap(_.hcursor.get[Long]("messageId").toOption), List(113L, 114L), "帧序 = 拉取序（ASC）")
    fs.foreach { f =>
      assertEquals(f.hcursor.get[Boolean]("backfill").toOption, Some(true), "补拉帧必须带 backfill 标记（前端据此不涨未读/不转发）")
      assertEquals(f.hcursor.get[String]("senderId").toOption, Some("u-peer"), "补拉帧必须带 senderId（前端据此判气泡方向，P5）")
      assertEquals(f.hcursor.get[String]("conversationId").toOption, Some("c"))
    }
    assertEquals(st.get("c").map(_.pulled), Some(2L), "计数：pulled=2")
    assertEquals(st.get("c").map(_.dispatched), Some(2L), "计数：dispatched=2")
    assertEquals(st.get("c").map(_.skipped), Some(0L), "计数：skipped=0")
    assertIdentity(ls)
    assertAnchorInvariant(ls)
    assert(warnLines(ls).isEmpty, s"无跳过 ⇒ 不得出现 W 行：${warnLines(ls).mkString("\n")}")
  }

  // ══ A2 · 判据① + 判据③：连续双消息，逐条配对，游标幂等 ══════════════
  test("A2 连续双消息：两条事件各配对一条带 eventId 的补拉；pullAnchor==dispatchedMax==maxId(第2条)") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(calls, frames, g, a => List(msg(a + 1L)))
      _ <- svc.onFriendEvent(envelope("e-113", serverEvent(Some("c"), 113L)))
      _ <- svc.onFriendEvent(envelope("e-114", serverEvent(Some("c"), 114L)))
      ls <- g.recentPullLines
      anchor <- g.localMaxId("c")
      dmax <- g.dispatchedMaxOf("c")
    yield (ls, anchor, dmax)

    val (ls, anchor, dmax) = prog.unsafeRunSync()

    // 判据①：两条补拉，各自带 trigger= 与 eventId=，且与 processed 行**逐条配对**。
    val pulls = linesWith(ls, "friend_pull ")
    assertEquals(pulls.size, 2, s"两条事件 ⇒ 两条补拉：${ls.mkString("\n")}")
    pulls.foreach { l =>
      assertEquals(field(l, "trigger"), Some(FriendPullTrigger.EventMessageNew), s"必须显式声明事件触发：$l")
    }
    val pullEventIds = pulls.flatMap(l => field(l, "eventId"))
    assertEquals(pullEventIds.sorted, List("e-113", "e-114"), s"每条补拉都带自己的 eventId：${pulls.mkString("\n")}")
    // 配对：∀ 事件触发的补拉，都存在同 eventId 的 `processed` 行（**直接对账**，不再反推）。
    val processedIds = linesWith(ls, "friend_event processed").flatMap(l => field(l, "id"))
    pullEventIds.foreach { eid =>
      assert(processedIds.contains(eid), s"补拉 eventId=$eid 无配对 processed 行（processed=$processedIds）")
    }

    // 判据③：pullAnchor == dispatchedMax == maxId(第2条) = 114。
    assertEquals(anchor, 114L, "pullAnchor 必须推进到第 2 条的 id")
    assertEquals(dmax, 114L, "dispatchedMax 必须与 pullAnchor 同值（同一次原子推进）")
    assertAnchorInvariant(ls)
    assertIdentity(ls)
  }

  // ══ A3 · 判据②：W 分支逐条注入，三字段同行 ═══════════════════════
  test("A3 判据② W5/W6/W7/W8/W3/W4 逐分支注入：每条 WARN 行内同时含 messageId|eventId + conversationId + reason") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(calls, frames, g)
      // W5：message_new 无 conversationId
      _ <- svc.onFriendEvent(envelope("e-w5", serverEvent(None, 5L)))
      // W6：message_new_self 无 conversationId
      _ <- svc.onFriendEvent(envelope("e-w6", serverEvent(None, 6L, "message_new_self")))
      // W7：未知事件类型
      _ <- svc.onFriendEvent(envelope("e-w7", Json.obj("type" -> "brand_new_thing".asJson, "payload" -> Json.obj("conversationId" -> "c".asJson))))
      // W8：畸形帧（缺 eventId/event.type）
      _ <- svc.onFriendEvent(Json.obj("type" -> "friend_event".asJson, "nope" -> Json.fromInt(1)))
      // W3：同 eventId 重放（去重路径，INFO 级但同样入环）
      _ <- svc.onFriendEvent(envelope("e-dup", serverEvent(Some("c"), 9L)))
      afterFirst <- calls.get
      _ <- svc.onFriendEvent(envelope("e-dup", serverEvent(Some("c"), 9L)))
      afterDup <- calls.get
      ls <- g.recentPullLines
    yield (ls, afterFirst.size, afterDup.size)

    val (ls, n1, n2) = prog.unsafeRunSync()

    List("W5", "W6", "W7", "W8").foreach { b =>
      val hit = linesWith(ls, s"branch=$b")
      assertEquals(hit.size, 1, s"$b 应恰好报 1 行（应报数 == 出现数）：\n${ls.mkString("\n")}")
      assertThreeFields(hit.head)
    }
    // W3：重放**不新增补拉**（显式偏离取证稿、理由见 FriendService.onFriendEvent 注释）——
    // 若在此处补拉，就会出现「没有配对 processed 行」的补拉 ⇒ 判据①的配对不变式不成立。
    assertEquals(n2, n1, "去重路径不得触发第二条补拉（判据①配对不变式）")
    val dup = linesWith(ls, "reason=duplicate_eventId")
    assertEquals(dup.size, 1, s"W3 必须留痕（修前 DEBUG 淹没）：\n${ls.mkString("\n")}")
    assertThreeFields(dup.head)
  }

  test("A3b 判据② W4 广播异常：留痕且**仍继续**补拉（best-effort 语义不变）") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      g = new FriendMessagingGuard()
      svc = new FriendService(
        IO.pure(Some(new ScriptedClient(calls, a => List(msg(a + 1L))))),
        AgentMessagingConfig(),
        guard = g,
        onFriendEvent = Some(_ => IO.raiseError(new RuntimeException("ws hub down")))
      )
      _ <- svc.onFriendEvent(envelope("e-w4", serverEvent(Some("c"), 7L)))
      ls <- g.recentPullLines
      pulled <- calls.get
    yield (ls, pulled)

    val (ls, pulled) = prog.unsafeRunSync()
    val w4 = linesWith(ls, "branch=W4")
    assertEquals(w4.size, 1, s"W4 应恰好报 1 行：\n${ls.mkString("\n")}")
    assertThreeFields(w4.head)
    assertEquals(pulled.map(_._1), List("c"), "广播炸了也**不得**吃掉补拉（best-effort 语义不变）")
  }

  test("A3c 判据② W1 跳过态：pulled>dispatched ⇒ 独立 WARN（messageId+conversationId+reason）且对账行含 reasons=[…]") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 112L)
      // 页里混入一条**已被越过**的 id（重叠重取形态）⇒ 必须被计入 skipped 且不重复派发。
      svc = mkService(calls, frames, g, _ => List(msg(111L), msg(113L)))
      _ <- svc.pullConversation("c", FriendPullTrigger.EventMessageNew, Some("e-overlap"), oursHint = Some(false))
      fs <- frames.get
      ls <- g.recentPullLines
      st <- g.pullStats
    yield (fs, ls, st)

    val (fs, ls, st) = prog.unsafeRunSync()
    assertEquals(fs.size, 1, "只派发真正在锚点之后的那条")
    assertEquals(fs.head.hcursor.get[Long]("messageId").toOption, Some(113L))
    val line = linesWith(ls, "friend_pull ").head
    assertEquals(num(line, "pulled"), Some(2L), s"对账行 pulled=2：$line")
    assertEquals(num(line, "dispatched"), Some(1L), s"对账行 dispatched=1：$line")
    assertEquals(num(line, "skipped"), Some(1L), s"对账行 skipped=1：$line")
    assert(line.contains("reasons=[") && !line.contains("reasons=[]"), s"skipped>0 ⇒ 同行必须含 reasons=[…]：$line")
    val warn = linesWith(ls, "friend_pull_skipped")
    assertEquals(warn.size, 1, s"skipped>0 ⇒ 必须**另有** WARN：\n${ls.mkString("\n")}")
    assertThreeFields(warn.head)
    assertEquals(st.get("c").map(_.skipped), Some(1L))
    assertIdentity(ls)
    assertAnchorInvariant(ls)
  }

  // ══ A4 · §3.5 拆字段：setRead 不得移动拉取锚点（静默丢失机制之一）══════
  test("A4 游标拆字段：setRead 只动已读水位，**不得**把补拉锚点一并推走（修前会把 113 推成永久不可达）") {
    val prog = for
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 112L)
      bAnchor <- g.localMaxId("c")
      bDmax <- g.dispatchedMaxOf("c")
      bRead <- g.readAnchor("c")
      _ <- g.setRead("c", 114L) // 前端开窗收帧即 markConversationRead
      aAnchor <- g.localMaxId("c")
      aDmax <- g.dispatchedMaxOf("c")
      aRead <- g.readAnchor("c")
      // 拆字段后 113 仍可被补拉取回：
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      svc = mkService(calls, frames, g, _ => List(msg(113L)))
      _ <- svc.pullConversation("c", FriendPullTrigger.EventMessageNew, Some("e-113"))
      fetched <- calls.get
      fs <- frames.get
      finalAnchor <- g.localMaxId("c")
    yield (bAnchor, bDmax, bRead, aAnchor, aDmax, aRead, fetched, fs.size, finalAnchor)

    val (bAnchor, bDmax, bRead, aAnchor, aDmax, aRead, fetched, frameN, finalAnchor) = prog.unsafeRunSync()
    assertEquals(bAnchor, 112L, "前置：拉取锚点 112")
    assertEquals(bDmax, 112L, "前置：已派发水位 112")
    assertEquals(bRead, 0L, "前置：已读水位 0")
    assertEquals(aRead, 114L, "setRead 推进**已读**水位")
    assertEquals(aAnchor, 112L, "🔴 setRead **不得**移动拉取锚点（修前会变成 114 ⇒ 113 永久不可达）")
    assertEquals(aDmax, 112L, "setRead **不得**移动已派发水位")
    assertEquals(fetched.map(_._2), List(112L), "补拉仍从 112 起（113 可取回）")
    assertEquals(frameN, 1, "取回的 113 必须派发")
    assertEquals(finalAnchor, 113L, "派发后锚点推进到 113")
  }

  test("A4b 判据③不变式：advanceAnchor 取 max 不回退，两格恒等") {
    val prog = for
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 114L)
      _ <- g.advanceAnchor("c", 113L) // 乱序/重放页带回更小值
      p1 <- g.localMaxId("c")
      p2 <- g.dispatchedMaxOf("c")
      _ <- g.setRead("c", 999L) // 已读不得代偿拉取
      q1 <- g.localMaxId("c")
      q2 <- g.dispatchedMaxOf("c")
    yield (p1, p2, q1, q2)
    val (p1, p2, q1, q2) = prog.unsafeRunSync()
    assertEquals((p1, p2), (114L, 114L), "取 max，不得回退")
    assertEquals((q1, q2), (114L, 114L), "setRead 不参与拉取/派发水位")
  }

  // ══ A5 · 冷锚：不拿「一页最旧历史」建锚 ════════════════════════════
  test("A5 冷锚（after==0）：无提示不取数；有事件提示则取恰好那一窗") {
    // 无提示（refresh_all 常态）⇒ 零取数、零派发、恒等式退化为 0==0+0。
    val cold = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(calls, frames, g)
      _ <- svc.pullConversation("c", FriendPullTrigger.RefreshAll)
      c1 <- calls.get
      f1 <- frames.get
      ls1 <- g.recentPullLines
      // 有提示（事件自带 messageId=113）⇒ after = 112。
      _ <- svc.pullConversation("c", FriendPullTrigger.EventMessageNew, Some("e-113"), afterHint = Some(113L))
      c2 <- calls.get
      f2 <- frames.get
      anchor <- g.localMaxId("c")
      dmax <- g.dispatchedMaxOf("c")
    yield (c1.size, f1.size, ls1, c2, f2.size, anchor, dmax)

    val (c1n, f1n, ls1, c2, f2n, anchor, dmax) = cold.unsafeRunSync()
    assertEquals(c1n, 0, "冷锚 + 无提示 ⇒ 不取数（历史渲染归 UI 自己的取数路径）")
    assertEquals(f1n, 0, "冷锚 + 无提示 ⇒ 不派发")
    assertIdentity(ls1)
    assertEquals(c2.map(_._2), List(112L), "冷锚 + 提示 ⇒ after = hint - 1（取恰好那一窗）")
    assertEquals(f2n, 1, "取到的那条必须派发")
    assertEquals(anchor, 113L, "派发后锚点 = 该条 id")
    assertEquals(dmax, 113L, "两格同值")
  }

  // ══ A6 · 帧形状与极性（与真 push 同形；自送走 self 事件名）═════════
  test("A6 补拉帧形状：oursHint=Some(true) ⇒ message_new_self；Some(false) ⇒ message_new；恒带 backfill") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(calls, frames, g, _ => List(msg(1L, "u-me")))
      // 冷锚 + afterHint：按发送响应自带的 messageId 取恰好那一窗，且极性 = 自送。
      _ <- svc.pullConversation(
        "c",
        FriendPullTrigger.SendAsUser,
        oursHint = Some(true),
        afterHint = Some(1L)
      )
      f1 <- frames.get
      g2 = new FriendMessagingGuard()
      calls2 <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames2 <- Ref.of[IO, List[Json]](Nil)
      svc2 = mkService(calls2, frames2, g2, _ => List(msg(2L, "u-peer")))
      _ <- svc2.pullConversation(
        "c",
        FriendPullTrigger.EventMessageNew,
        Some("e-2"),
        oursHint = Some(false),
        afterHint = Some(2L)
      )
      f2 <- frames2.get
    yield (f1, f2)

    val (f1, f2) = prog.unsafeRunSync()
    assertEquals(f1.head.hcursor.get[String]("event").toOption, Some("message_new_self"), "自送补拉的帧事件名 = self")
    assertEquals(f1.head.hcursor.get[Boolean]("backfill").toOption, Some(true))
    assertEquals(f2.head.hcursor.get[String]("event").toOption, Some("message_new"), "入站补拉的帧事件名 = inbound")
    assertEquals(f2.head.hcursor.get[Boolean]("backfill").toOption, Some(true))
  }

  test("A6b 触发源字面量契约：只有事件族触发携带 eventId（判据①的显式字段判据）") {
    assert(FriendPullTrigger.isEventTriggered(FriendPullTrigger.EventMessageNew))
    assert(FriendPullTrigger.isEventTriggered(FriendPullTrigger.EventMessageNewSelf))
    assert(!FriendPullTrigger.isEventTriggered(FriendPullTrigger.RefreshAll))
    assert(!FriendPullTrigger.isEventTriggered(FriendPullTrigger.SendAsAgent))
    assert(!FriendPullTrigger.isEventTriggered(FriendPullTrigger.SendAsUser))
  }

  // ══ A7 · 恒等式在空拍 / 失败拍上同样成立 ═══════════════════════════
  test("A7 空拉取与取数失败：恒等式都退化为 0==0+0，且空拍**不报** WARN、失败拍留痕") {
    val prog = for
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 5L)
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      svcEmpty = mkService(calls, frames, g, _ => Nil)
      _ <- svcEmpty.pullConversation("c", FriendPullTrigger.RefreshAll)
      emptyLines <- g.recentPullLines
      g2 = new FriendMessagingGuard()
      _ <- g2.advanceAnchor("c", 5L) // 温锚：取数路径可达（冷锚 + 无提示是**不取数**分支）
      calls2 <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      svcFail = new FriendService(
        IO.pure(Some(new FailingClient(calls2))),
        AgentMessagingConfig(),
        guard = g2
      )
      _ <- svcFail.pullConversation("c", FriendPullTrigger.RefreshAll)
      failLines <- g2.recentPullLines
    yield (emptyLines, failLines)

    val (emptyLines, failLines) = prog.unsafeRunSync()
    assertEquals(emptyLines.size, 1, s"空拍必须计入对账（修前零输出）：${emptyLines.mkString}")
    assertIdentity(emptyLines)
    assert(!warnLines(emptyLines).exists(_.contains("branch=W")), s"空拉取是常态，不得报 W 行：${emptyLines.mkString}")
    assertIdentity(failLines)
    val failed = linesWith(failLines, "friend_pull_failed")
    assertEquals(failed.size, 1, s"取数失败必须留痕：${failLines.mkString}")
    assertThreeFields(failed.head)
  }

end FriendPulledDispatchSpec
