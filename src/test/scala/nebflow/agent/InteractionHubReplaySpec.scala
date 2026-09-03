package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 刷新存活（2026-09-03，#43 同任务域补充 case）— pending AskUser 在浏览器刷新
 * / WS 重连后恢复可答。
 *
 * 服务端权威重发设计：InteractionHub 是 pending 槽的唯一权威（D2/D4）。新增
 * ListPendingAsks 只读快照命令，gateway 在会话（重）订阅的初始 historyPage 之后
 * 把快照逐帧重发（形态 = 首次下发 renderAskUser + `replayed: true`）。前端按
 * requestId 重绑定卡片（历史还原卡不带 requestId——UiMessage.AskUser 只持久化
 * {type, items}），两条回答路径（卡片点击 / 输入框直通）随即重新接通。
 *
 * 本 spec 用真实 InteractionHub actor 钉住四条契约：
 *  1. (a) 快照形态：type/sessionId/requestId/items(问题内容)/agentName/
 *     sourceSession/replayed 逐字段对齐首次下发载荷；
 *  2. (b) 刷新后用户回答放行：快照 → 用户 Answered（带 requestId + answers）
 *     → Deferred 收到正确槽位值 —— 答案来源校验（answerCompletes 要求 answers
 *     形态）对重放后的用户路径照常放行；
 *  3. (c) 快照权威性：已消费的 ask 不再出现在快照（answered-before-replay 不
     * 可能复活卡片）；跨 root 会话的 pending 与 permission 卡不串扰；
 *  4. (d) 多卡排队：同 root 会话多个 pending ask 按创建序（最老在前）重发，
 *     与 AnswerViaChatInput 消费最老卡的既有语义一致。
 */
class InteractionHubReplaySpec extends CatsEffectSuite:

  private def askItems(question: String): Json =
    Json.arr(Json.obj("question" -> Json.fromString(question)))

  private def userAnswerPayload(answers: String*): Json =
    Json.obj("answers" -> Json.arr(answers.map(Json.fromString)*))

  /** Register an answer sink actor; returns the InteractionRequest whose
    * AskUserReply routes to it. */
  private def askRequest(
      requestId: String,
      gotAnswers: Ref[IO, Option[List[String]]],
      question: String,
      rootSid: String,
      sourceAgent: String = "Nebula",
      system: nebflow.actor.ActorSystem
  ): IO[InteractionRequest] =
    system
      .spawn(
        nebflow.actor.Behaviors.receiveMessage[List[String]] { answers =>
          gotAnswers.set(Some(answers)).as(nebflow.actor.Behaviors.stopped)
        },
        s"ask-sink-$requestId"
      )
      .map { sink =>
        InteractionRequest(
          requestId = requestId,
          kind = InteractionKind.AskUser,
          payload = Json.obj(
            "items" -> askItems(question),
            "agentName" -> Json.fromString(sourceAgent)
          ),
          reply = InteractionReply.AskUserReply(Some(sink)),
          rootSessionId = rootSid,
          sourceAgent = sourceAgent,
          sourceSession = rootSid
        )
      }

  // ============================================================
  // (a) 快照形态：逐字段对齐首次下发载荷 + replayed 标记
  // ============================================================

  test("(a) ListPendingAsks 返回首帧同构载荷 —— type/sessionId/requestId/items/agentName/sourceSession/replayed") {
    val system = nebflow.actor.ActorSystem("askuser-replay-a")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-a")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("replay-a", gotAnswers, "选择实现方案?", "root-1", system = system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- IO.sleep(50.millis) // hub forkTurn 异步登记 pending
      snap <- hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks("root-1", reply))
      _ <- system.stopAll
    yield
      assertEquals(snap.length, 1, "pending ask 应恰好重发一帧")
      val frame = snap.head
      assertEquals(frame.hcursor.downField("type").as[String].getOrElse(""), "askUser", "type 对齐首帧")
      assertEquals(frame.hcursor.downField("sessionId").as[String].getOrElse(""), "root-1", "sessionId 绑定 root 会话")
      assertEquals(frame.hcursor.downField("requestId").as[String].getOrElse(""), "replay-a", "requestId 重绑定答案链")
      assertEquals(
        frame.hcursor.downField("items").downN(0).downField("question").as[String].getOrElse(""),
        "选择实现方案?",
        "问题内容完整重发"
      )
      assertEquals(frame.hcursor.downField("agentName").as[String].getOrElse(""), "Nebula", "agentName 徽标信息保留")
      assertEquals(frame.hcursor.downField("sourceSession").as[String].getOrElse(""), "root-1", "sourceSession 保留")
      assertEquals(frame.hcursor.downField("replayed").as[Boolean].getOrElse(false), true, "replayed 标记供前端去重")
  }

  // ============================================================
  // (b) 刷新后用户回答放行：快照 → Answered(requestId, answers) → Deferred 收值
  // ============================================================

  test("(b) 快照重发后用户回答（带 requestId + answers 形态）放行 —— Deferred 收到正确槽位值") {
    val system = nebflow.actor.ActorSystem("askuser-replay-b")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-b")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      gotAnswers <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("replay-b", gotAnswers, "两问卡的槽位校验?", "root-1", system = system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- IO.sleep(50.millis)
      // 模拟刷新：快照（gateway 重发）本身不触碰 pending 槽
      snap <- hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks("root-1", reply))
      _ <- IO.sleep(50.millis)
      before <- gotAnswers.get
      // 刷新后的用户点击：requestId 精确路由，两槽答案（Q2 留空 = skip）
      _ <- hub ! InteractionHubCommand
        .Answered(InteractionAnswered("replay-b", "root-1", userAnswerPayload("方案 A", "")))
      _ <- IO.sleep(100.millis)
      after <- gotAnswers.get
      _ <- system.stopAll
    yield
      assertEquals(snap.length, 1, "pending 期间快照可见")
      assertEquals(before, None, "快照本身不解除 pending（只读）")
      assertEquals(after, Some(List("方案 A", "")), "刷新后回答放行且槽位值逐位正确")
  }

  // ============================================================
  // (c) 快照权威性：已答不复活；跨 root / permission 不串扰
  // ============================================================

  test("(c) 已消费的 ask 不再重发；其他 root 会话的 pending 与 permission 卡不进快照") {
    val system = nebflow.actor.ActorSystem("askuser-replay-c")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-c")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-2", (_: Json) => IO.unit)
      gotAnswers1 <- Ref.of[IO, Option[List[String]]](None)
      gotAnswers2 <- Ref.of[IO, Option[List[String]]](None)
      req1 <- askRequest("replay-c-1", gotAnswers1, "会被回答的卡?", "root-1", system = system)
      req2 <- askRequest("replay-c-2", gotAnswers2, "保持 pending 的卡?", "root-1", system = system)
      permReq = InteractionRequest(
        requestId = "perm-c",
        kind = InteractionKind.Permission,
        payload = Json.obj("toolName" -> Json.fromString("Bash"), "summary" -> Json.fromString("ls")),
        reply = InteractionReply.PermissionReply(cats.effect.Deferred.unsafe[IO, Boolean]),
        rootSessionId = "root-1",
        sourceAgent = "Nebula",
        sourceSession = "root-1"
      )
      reqOther <- askRequest("replay-c-other", Ref.unsafe[IO, Option[List[String]]](None), "别的会话的卡?", "root-2", system = system)
      _ <- hub ! InteractionHubCommand.Request(req1)
      _ <- hub ! InteractionHubCommand.Request(req2)
      _ <- hub ! InteractionHubCommand.Request(permReq)
      _ <- hub ! InteractionHubCommand.Request(reqOther)
      _ <- IO.sleep(80.millis)
      // 回答 req1 —— 槽位被消费
      _ <- hub ! InteractionHubCommand
        .Answered(InteractionAnswered("replay-c-1", "root-1", userAnswerPayload("已答")))
      _ <- IO.sleep(80.millis)
      snapRoot1 <- hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks("root-1", reply))
      snapRoot2 <- hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks("root-2", reply))
      _ <- system.stopAll
    yield
      assertEquals(
        snapRoot1.map(_.hcursor.downField("requestId").as[String].getOrElse("")),
        List("replay-c-2"),
        "root-1 快照：已答卡不复活、permission 卡不进、只余 pending ask"
      )
      assertEquals(
        snapRoot2.map(_.hcursor.downField("requestId").as[String].getOrElse("")),
        List("replay-c-other"),
        "跨 root 会话不串扰"
      )
  }

  // ============================================================
  // (d) 多卡排队：最老在前，与输入框直通消费最老卡语义一致
  // ============================================================

  test("(d) 同 root 会话多个 pending ask 按创建序重发（最老在前）") {
    val system = nebflow.actor.ActorSystem("askuser-replay-d")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-d")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      gotOlder <- Ref.of[IO, Option[List[String]]](None)
      gotNewer <- Ref.of[IO, Option[List[String]]](None)
      reqOlder <- askRequest("replay-d-old", gotOlder, "先问的卡?", "root-1", system = system)
      reqNewer <- askRequest("replay-d-new", gotNewer, "后问的卡?", "root-1", system = system)
      _ <- hub ! InteractionHubCommand.Request(reqOlder)
      _ <- IO.sleep(30.millis) // 保证 createdAt 严格递增（先登记 = 更老）
      _ <- hub ! InteractionHubCommand.Request(reqNewer)
      _ <- IO.sleep(80.millis)
      snap <- hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks("root-1", reply))
      // 输入框直通消费最老卡（既有语义）——快照顺序与此一致
      _ <- hub ! InteractionHubCommand.AnswerViaChatInput("root-1", "直通答案", cats.effect.Deferred.unsafe[IO, Boolean])
      _ <- IO.sleep(80.millis)
      afterOlder <- gotOlder.get
      afterNewer <- gotNewer.get
      _ <- system.stopAll
    yield
      assertEquals(
        snap.map(_.hcursor.downField("requestId").as[String].getOrElse("")),
        List("replay-d-old", "replay-d-new"),
        "快照按创建序（最老在前）"
      )
      assertEquals(afterOlder, Some(List("直通答案")), "直通消费最老卡 —— 与快照首帧一致")
      assertEquals(afterNewer, None, "后问的卡保持 pending")
  }
