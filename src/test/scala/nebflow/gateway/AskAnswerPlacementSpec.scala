package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AskOrigin, ActorSystem, InteractionAnswered, InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.agent.InteractionHub
import nebflow.core.SessionStore
import nebflow.shared.UiMessage

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

/**
 * 作答落库归属批（2026-11-03，作者报障「回答后台智能体的提问泄漏进主会话」）。
 *
 * 缺陷定谳（作者 2026-11-03 13:07 原话）：「我回答后台智能体的问题时，回答的内容会
 * 泄露到前端的会话里，变成一条被记录的消息……它不会触发你的认知，它只是被记录到我
 * 们的这个对话消息里面了」。实证 = 根会话 `d7c3cb54-….ui.json` idx 790 的
 * `answerOf=asknb-ba738974fc8b4743` 全文行（提问源自后台委托会话）。
 *
 * 根因（代码判据）：hub 渲染卡片恒把 `sessionId` 改写成 `rootSessionId`
 * （`InteractionHub.renderAskUser`）⇒ 前端作答帧回的就是卡片会话（root）；网关
 * 作答落库腿把作答全文写进**帧内 sessionId** ⇒ 子代理/节点提问的作答全文落根会话
 * `.ui.json`（模型上下文里从来没有它 ——「看得见、认知不到」）。
 *
 * 修复口径（作者令）：「回答谁，记录在谁的会话」——
 *   · 子代理 / 项目节点 / 内核提问：作答**全文**只落提问会话（`sourceSession`）；
 *     根会话只留一条**不含内容**的代答痕迹行（`answerOf` 仍在 ⇒ 历史卡照旧渲染
 *     「已作答」，不会退化成永远未答的死卡）；
 *   · 根自问 / 归属读数缺席（槽位已消费 / hub 未装配）：改前路径**逐字不变**
 *     （全文落帧内 sessionId）；
 *   · 已落库的历史行不回溯清洗（作者令）——本批只剪新写的泄漏路径。
 *
 * 机械断言（作者令④）：「子智能体提问 → 作者应答 → 断言主会话 UiMessage 流中
 * 不出现应答全文、提问会话持久化中存在」——本 spec 用**生产本体**（hub 行为 +
 * 网关裁决单点 + 真实 SessionStore 落盘读盘）钉住整条链：
 *
 *   ① 归属读数：hub `AskOriginOf` 对委托会话的卡回 `isSelfAsk=false`、
 *      `sourceSession=delegate-…`、`askerLabel` 取 payload 标注（U3 内核标签链同源）；
 *   ② 落库裁决（委托提问）：plan = 两条写 —— 全文→提问会话、痕迹行→root，
 *      痕迹行**不含**作答全文且 `answerOf` 与全文行同 id；
 *   ③ 落库裁决（根自问）：单条写、全文在卡片会话、`delegated=false`（改前路径）；
 *   ④ 归属缺席回落：`origin=None` ⇒ 与③同形（宁可维持既有落库形态，不猜归属）；
 *   ⑤ 落盘互证（机械断言本体）：真实 SessionStore 双会话落盘 → flush → 读盘上
 *      字节——root 的 `.ui.json` **不包含**作答全文、提问会话的 `.ui.json` 包含
 *      全文与 `answerOf`；痕迹行解码读回同值（前端历史恢复拿到的同一形状）；
 *   ⑥ 翻面语义保全：痕迹行的 `answerOf` 使历史卡可被前端 `askUserAnswerText`
 *      ①精确腿取值 —— 文本即痕迹文案（已答态），而非「未作答」；
 *   ⑦ 秘密槽协同：裁决只搬运上游脱敏文本（拒收行按原样落提问会话，痕迹行恒零值）；
 *   ⑧ hub `complete` 不落库（防回归钉）：作答收口只 complete 桥 + 广播关卡帧，
 *      不产生任何 `appendUiMessages`（泄漏面只可能在网关落库腿）。
 */
class AskAnswerPlacementSpec extends CatsEffectSuite:

  private val askItems: List[Json] =
    List(Json.obj("question" -> Json.fromString("作答落库归属批：委托会话的提问")))

  private def withStore(test: SessionStore => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-askplace-test")
    val store = SessionStore(os.Path(tmp.resolve("sessions")), os.Path(tmp.resolve("tasks")))
    try
      store.load.unsafeRunSync()
      test(store).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  /** hub 的 Request/Answered 都经 `forkTurn` 异步落地（`!` 返回的 IO 只负责入队），
    * 故读数面一律轮询到条件成立（AskConcurrencyReworkSpec.awaitCond 同款）。 */
  private def awaitCond(
    cond: IO[Boolean],
    label: String,
    timeout: FiniteDuration = 5.seconds,
    every: FiniteDuration = 20.millis
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"awaitCond: $label not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  // ============================================================
  // ① 归属读数：hub AskOriginOf（生产行为，forkTurn 路）
  // ============================================================
  test("① hub AskOriginOf：委托会话的卡回 isSelfAsk=false + sourceSession + askerLabel（payload 标注优先）") {
    val system = ActorSystem("askplace-origin")
    try
      (for
        hub <- system.spawn(InteractionHub(), "hub-askplace-origin")
        _ <- hub ! InteractionHubCommand.Request(
          InteractionRequest(
            requestId = "asknb-place000000001",
            kind = InteractionKind.AskUser,
            payload = Json.obj("type" -> "askUser".asJson, "items" -> askItems.asJson)
              .deepMerge(Json.obj("agentName" -> "subagent · 修落库归属".asJson)),
            reply = InteractionReply.AskUserReply(None),
            rootSessionId = "root-1",
            sourceAgent = "delegate",
            sourceSession = "delegate-nebflow-abc"
          )
        )
        _ <- awaitCond(
          hub.?[Option[AskOrigin]](r => InteractionHubCommand.AskOriginOf("asknb-place000000001", r)).map(_.nonEmpty),
          "hub 槽位注册（AskOriginOf 可读）"
        )
        origin <- hub.?[Option[AskOrigin]](r => InteractionHubCommand.AskOriginOf("asknb-place000000001", r))
        gone <- hub.?[Option[AskOrigin]](r => InteractionHubCommand.AskOriginOf("asknb-missing0000001", r))
      yield (origin, gone)
      ).unsafeRunSync() match
        case (origin, gone) =>
          val o = origin.getOrElse(fail("AskOriginOf 必须回在册委托卡"))
          assertEquals(o.isSelfAsk, false, "委托提问绝不是自问")
          assertEquals(o.sourceSession, "delegate-nebflow-abc")
          assertEquals(o.rootSessionId, "root-1")
          assertEquals(o.askerLabel, "subagent · 修落库归属", "payload 显式标注优先（U3 内核标签链同源）")
          assertEquals(gone, None, "不在册的 requestId ⇒ None（网关回落改前路径）")
    finally system.stopAll
  }

  test("①b AskOrigin.isSelfAsk：根自问（sourceSession=root）/ 空归属 ⇒ true；委托 ⇒ false") {
    assertEquals(AskOrigin("root-1", "root-1", "Nebula", "Nebula").isSelfAsk, true)
    assertEquals(AskOrigin("root-1", "", "Nebula", "Nebula").isSelfAsk, true, "空 sourceSession = 旧槽位自问")
    assertEquals(AskOrigin("root-1", "delegate-x", "delegate", "delegate").isSelfAsk, false)
  }

  // ============================================================
  // ②③④ 纯裁决 plan
  // ============================================================
  private val FullText = "收货，无需再改\n授权一次性回改历史文档\n授权加放大面板"

  test("② 委托提问的裁决：两条写 —— 全文→提问会话、痕迹行→root；痕迹行不含全文") {
    val origin = Some(AskOrigin("root-1", "delegate-nebflow-abc", "delegate", "subagent · 修落库归属"))
    val p = AskAnswerPlacement.plan(origin, "root-1", FullText, "asknb-place000000002", 1757000000000L)
    assert(p.delegated, "委托提问必须判 delegated")
    assertEquals(p.ownerSessionId, "delegate-nebflow-abc", "作答全文归属 = 提问会话")
    assertEquals(p.writes.size, 2, s"两条写（全文 + 痕迹），实际：${p.writes.map(_._1)}")
    val (fullSid, fullRows) = p.writes.head
    val (traceSid, traceRows) = p.writes(1)
    assertEquals(fullSid, "delegate-nebflow-abc", "① 全文 → 提问会话")
    assertEquals(traceSid, "root-1", "② 痕迹行 → 根会话（卡片所在）")
    assertEquals(fullRows.length, 1)
    assertEquals(traceRows.length, 1)
    val fullRow = fullRows.head.asInstanceOf[UiMessage.User]
    val traceRow = traceRows.head.asInstanceOf[UiMessage.User]
    assertEquals(fullRow.text, FullText, "全文行逐字 = 作答文本")
    assertEquals(fullRow.answerOf, Some("asknb-place000000002"))
    assert(!traceRow.text.contains(FullText), s"🔴 痕迹行不得携带作答全文：${traceRow.text}")
    assert(!traceRow.text.contains("授权"), s"🔴 痕迹行不得泄漏作答片段：${traceRow.text}")
    assertEquals(traceRow.answerOf, fullRow.answerOf, "痕迹行与全文行同 answerOf（历史卡按 id 取值）")
    assertEquals(traceRow.text, "已代答（提问会话：subagent · 修落库归属）")
  }

  test("③ 根自问的裁决：单条写、全文在卡片会话、delegated=false（改前路径逐字不变）") {
    val origin = Some(AskOrigin("root-1", "root-1", "Nebula", "Nebula"))
    val p = AskAnswerPlacement.plan(origin, "root-1", FullText, "asknb-place000000003", 1757000000000L)
    assertEquals(p.delegated, false)
    assertEquals(
      p.writes,
      List("root-1" -> List(UiMessage.askUserAnswer(FullText, "asknb-place000000003", 1757000000000L)))
    )
  }

  test("④ 归属缺席回落：origin=None ⇒ 与改前同形（全文落帧内 sessionId）") {
    val p = AskAnswerPlacement.plan(None, "root-1", FullText, "asknb-place000000004", 1757000000000L)
    assertEquals(p.delegated, false)
    assertEquals(p.writes.map(_._1), List("root-1"))
    assertEquals(p.writes.head._2.head.asInstanceOf[UiMessage.User].text, FullText)
  }

  test("④b 痕迹行兜底标注：askerLabel 空白 ⇒ 中性文案（不含任何作答内容）") {
    assertEquals(AskAnswerPlacement.traceText(""), s"已代答（提问会话：${AskAnswerPlacement.UnknownAskerLabel}）")
    assertEquals(AskAnswerPlacement.traceText("   "), s"已代答（提问会话：${AskAnswerPlacement.UnknownAskerLabel}）")
  }

  // ============================================================
  // ⑤ 机械断言本体：真实落盘互证（改前红 / 改后绿）
  // ============================================================
  test("⑤ 落盘字节互证：root 流无全文；提问会话流有全文 + answerOf；痕迹行解码读回同值") {
    val tmp = Files.createTempDirectory("nebflow-askplace-disk")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    val root = "root-main"
    val delegate = "delegate-nebflow-abc"
    val rid = "asknb-place000000006"
    try
      (for
        _ <- store.load
        // 提问行恒落卡片会话（root 渲染腿，改前改后同一条）
        _ <- store.appendUiMessages(root, List(UiMessage.AskUser(askItems, Some(rid))))
        origin = Some(AskOrigin(root, delegate, "delegate", "subagent · 修落库归属"))
        plan = AskAnswerPlacement.plan(origin, root, FullText, rid, 1757000000000L)
        _ <- AskAnswerPlacement.persist(store, rid, plan)
        _ <- store.flushPendingUiWrites
        rawRoot <- IO.delay(os.read.lines(sessionsDir / s"$root.ui.json").mkString("\n"))
        rawDelegate <- IO.delay(os.read.lines(sessionsDir / s"$delegate.ui.json").mkString("\n"))
        rootRows <- store.getUiMessages(root, 0, 100).map(_._1)
        delegateRows <- store.getUiMessages(delegate, 0, 100).map(_._1)
      yield
        // 🔴 机械断言（作者令④）——主会话 UiMessage 流不出现应答全文：
        assert(!rawRoot.contains("收货，无需再改"), "🔴 root .ui.json 泄漏作答全文（改前红的形状）")
        assert(!rawRoot.contains("授权一次性回改历史文档"), "🔴 root .ui.json 泄漏作答片段")
        assert(!rawRoot.contains("授权加放大面板"), "🔴 root .ui.json 泄漏作答片段")
        // 提问会话持久化存在（全文 + 标记都在）
        assert(rawDelegate.contains("收货，无需再改"), "提问会话缺作答全文")
        assert(rawDelegate.contains(s""""answerOf":"$rid""""), "提问会话的全文行缺 answerOf 标记")
        // root 流仍有：提问行 + 痕迹行（answerOf 在 ⇒ 历史卡渲染已作答）
        assert(rawRoot.contains(s""""requestId":"$rid""""), "root 的提问行必须仍在（卡片渲染依据）")
        assert(rawRoot.contains(s""""answerOf":"$rid""""), "root 的痕迹行缺 answerOf（历史卡会退化成未答死卡）")
        // 解码面读回：root 的作答行 = 痕迹文案；提问会话的作答行 = 全文
        val rootAns = rootRows.collectFirst { case u: UiMessage.User if u.answerOf.contains(rid) => u }.get
        val delAns = delegateRows.collectFirst { case u: UiMessage.User if u.answerOf.contains(rid) => u }.get
        assert(!rootAns.text.contains("收货"), s"🔴 root 解码面泄漏：${rootAns.text}")
        assertEquals(rootAns.text, "已代答（提问会话：subagent · 修落库归属）")
        assertEquals(delAns.text, FullText)
      ).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
  }

  test("⑤b 根自问的落盘形态与改前逐字同路径（单会话单行，无痕迹行）") {
    val tmp = Files.createTempDirectory("nebflow-askplace-self")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    val root = "root-self"
    val rid = "asknb-place000000007"
    try
      (for
        _ <- store.load
        _ <- store.appendUiMessages(root, List(UiMessage.AskUser(askItems, Some(rid))))
        origin = Some(AskOrigin(root, root, "Nebula", "Nebula"))
        plan = AskAnswerPlacement.plan(origin, root, FullText, rid, 1757000000000L)
        _ <- AskAnswerPlacement.persist(store, rid, plan)
        _ <- store.flushPendingUiWrites
        (rows, total) <- store.getUiMessages(root, 0, 100)
      yield
        assertEquals(total, 2, "自问路径 = 提问行 + 全文作答行，一行不多")
        val ans = rows.collectFirst { case u: UiMessage.User => u }.get
        assertEquals(ans.text, FullText)
      ).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
  }

  // ============================================================
  // ⑥ 翻面语义保全：痕迹行可被前端精确腿取值（历史卡渲染已作答）
  // ============================================================
  test("⑥ 痕迹行走单一构造点：answerOf 在、injected=false（前端历史恢复的已答判据不变）") {
    val trace = AskAnswerPlacement.traceText("subagent · x")
    val row = UiMessage.askUserAnswer(trace, "asknb-place000000008", 1757000000000L)
    assertEquals(row.answerOf, Some("asknb-place000000008"))
    assertEquals(row.injected, false, "痕迹行不是注入行（作者令：普通消息面语义）")
    assertEquals(row.text, "已代答（提问会话：subagent · x）")
  }

  // ============================================================
  // ⑦ 秘密槽协同：裁决只搬运上游脱敏文本，痕迹行恒零值零明文
  // ============================================================
  test("⑦ 秘密槽拒收行进裁决：全文=拒收文案落提问会话；痕迹行恒不含 keyRef/明文") {
    val refused = "该题为安全输入题：明文作答未接收——请刷新页面后在桌面面板作答"
    val origin = Some(AskOrigin("root-1", "delegate-nebflow-abc", "delegate", "subagent · 修落库归属"))
    val p = AskAnswerPlacement.plan(origin, "root-1", refused, "asknb-place000000009", 1757000000000L)
    assertEquals(p.writes.head._1, "delegate-nebflow-abc")
    assertEquals(p.writes(1)._2.head.asInstanceOf[UiMessage.User].text, "已代答（提问会话：subagent · 修落库归属）")
    assert(!p.writes(1)._2.head.asInstanceOf[UiMessage.User].text.contains("keyRef"))
  }

  // ============================================================
  // ⑧ 防回归钉：hub complete 不产生任何落库（泄漏面只在网关落库腿）
  // ============================================================
  test("⑧ hub complete：作答收口零 appendUiMessages（关卡帧广播 + 桥投递而已）") {
    val tmp = Files.createTempDirectory("nebflow-askplace-complete")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    store.load.unsafeRunSync()
    try
      val frames = new java.util.concurrent.atomic.AtomicReference[List[Json]](Nil)
      val system = ActorSystem("askplace-complete")
      try
        (for
          hub <- system.spawn(InteractionHub(), "hub-askplace-complete")
          _ <- hub ! InteractionHubCommand.RegisterRoot("root-c", (j: Json) =>
            IO.delay(frames.updateAndGet(_ :+ j)).void
          )
          // 先注册**在册槽位**（Request 腿——没有槽位的 Answered 走的是 unknown-reject 路，
          // 钉不到 complete），等槽位可读再作答。
          _ <- hub ! InteractionHubCommand.Request(
            InteractionRequest(
              requestId = "asknb-place0000000a",
              kind = InteractionKind.AskUser,
              payload = Json.obj("type" -> "askUser".asJson, "items" -> askItems.asJson),
              reply = InteractionReply.AskUserReply(None),
              rootSessionId = "root-c",
              sourceAgent = "Nebula",
              sourceSession = "root-c"
            )
          )
          _ <- awaitCond(
            hub.?[Option[AskOrigin]](r => InteractionHubCommand.AskOriginOf("asknb-place0000000a", r)).map(_.nonEmpty),
            "hub 槽位注册（Request 腿落地）"
          )
          _ <- store.appendUiMessages("root-c", List(UiMessage.AskUser(askItems, Some("asknb-place0000000a"))))
          _ <- store.flushPendingUiWrites
          before <- store.getUiMessages("root-c", 0, 100)
          _ <- hub ! InteractionHubCommand.Answered(
            InteractionAnswered(
              requestId = "asknb-place0000000a",
              rootSessionId = "root-c",
              payload = Json.obj("answers" -> List(FullText).asJson)
            )
          )
          // complete 是 forkTurn fiber —— 轮询到关卡帧广播落地（同时给落库面留了窗口：
          // 若 complete 真有落库，此刻必然已经写进 .ui.json）。
          _ <- awaitCond(
            IO.delay(frames.get.exists(_.hcursor.downField("type").as[String].toOption.contains("askUserClosed"))),
            "complete 关卡帧广播落地"
          )
          after <- store.getUiMessages("root-c", 0, 100)
        yield (before, after, frames.get)
        ).unsafeRunSync() match
          case (before, after, framesSent) =>
            assertEquals(after._2, before._2, "🔴 hub complete 不得产生任何新 UiMessage 行（作答全文落库只在网关裁决腿）")
            assert(
              framesSent.exists(_.hcursor.downField("type").as[String].toOption.contains("askUserClosed")),
              "complete 仍广播关卡帧（前端翻面语义保全）"
            )
      finally system.stopAll
      ()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)
  }

  // ============================================================
  // P1 防复活钉（源码级，ProcessRecorderSpec 同款）：三条作答落库腿必须走
  // AskAnswerPlacement 裁决单点；网关腿不得再直接把作答全文 appendUiMessages
  // 进帧内 sessionId（回潮 = 泄漏复活）。
  // ============================================================
  test("P1 源码钉：三条作答落库腿必须经 AskAnswerPlacement.persist（裁决单点），禁裸 append 全文行回潮") {
    val handlers = Files.readString(Paths.get("src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala"))
    val routes = Files.readString(Paths.get("src/main/scala/nebflow/gateway/WebSocketRoutes.scala"))
    // web 腿两条（legacy + secret-aware）都必须走裁决单点
    assertEquals(
      handlers.split("AskAnswerPlacement.persist").length - 1,
      2,
      "WsSessionChatHandlers 的作答落库腿（legacy + secret-aware）必须各走一次 AskAnswerPlacement.persist"
    )
    assert(handlers.contains("AskAnswerPlacement.originOf"), "web 腿必须先查 hub 归属读数")
    // 桥腿必须走裁决单点（rowFor 之后不再直接 appendUiMessages(rootSid, List(row))）
    assert(routes.contains("AskAnswerPlacement.persist"), "桥腿（forwardBridgeInteractionAnswer）必须走 AskAnswerPlacement.persist")
    assert(
      !routes.contains(".appendUiMessages(rootSid, List(row))"),
      "桥腿裸 append 回潮 = 泄漏复活（作答全文直接落 root）"
    )
  }

end AskAnswerPlacementSpec
