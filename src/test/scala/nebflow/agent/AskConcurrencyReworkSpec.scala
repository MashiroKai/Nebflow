package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behaviors}

import scala.concurrent.duration.*

/** 多 AskUser 并发批（#250，2026-09-13 作者裁定「6 项全补」）——
  * hub 侧三项（②③⑥）的正负控。
  *
  *   ② turn 被用户中断后的 pending 槽回收：`CleanupForSession(sessionId, reason)`；
  *   ③ 全局快照 `ListAllPendingAsks` ↔ 按会话 `ListPendingAsks` 的口径（清空/重建同源）；
  *   ⑥ 形态不符 / requestId 未知的答复必须**用户可见**（广播 `interactionAnswerRejected`），
  *      同时保持 #12 既有语义（不消费卡片）。
  *
  * 证据级别：本 spec 是**单测级**证据（真 hub 实例 + 真消息 + 真回复端，无运行实例、
  * 无运行态观测）——链末报告按此口径标注，禁写成「已实测」。
  */
class AskConcurrencyReworkSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def mkHub(system: ActorSystem): IO[ActorRef[InteractionHubCommand]] =
    system.spawn(InteractionHub(), "hub-askconc-test")

  private def awaitCond(
    cond: IO[Boolean],
    label: String,
    timeout: FiniteDuration = 5.seconds,
    every: FiniteDuration = 25.millis
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

  private def isCard(tpe: String, requestId: String)(j: Json): Boolean =
    j.hcursor.downField("type").as[String].contains(tpe) &&
      j.hcursor.downField("requestId").as[String].contains(requestId)

  /** AskUser 请求（replyTo 为真回复端——回答落地可断言「谁收到了什么」）。 */
  private def askRequest(
    requestId: String,
    rootSid: String,
    sourceSession: String,
    gotAnswers: Ref[IO, Option[List[String]]],
    system: ActorSystem
  ): IO[InteractionRequest] =
    system
      .spawn(
        Behaviors.receiveMessage[List[String]] { answers =>
          gotAnswers.set(Some(answers)).as(Behaviors.stopped)
        },
        s"ask-sink-${requestId.replaceAll("[^A-Za-z0-9-]", "_")}"
      )
      .map { sink =>
        InteractionRequest(
          requestId = requestId,
          kind = InteractionKind.AskUser,
          payload = Json.obj(
            "items" -> Json.arr(Json.obj("question" -> Json.fromString("继续？"))),
            "agentName" -> Json.fromString("Nebula")
          ),
          reply = InteractionReply.AskUserReply(Some(sink)),
          rootSessionId = rootSid,
          sourceAgent = "Nebula",
          sourceSession = sourceSession
        )
      }

  private def snapshot(hub: ActorRef[InteractionHubCommand], rootSid: String): IO[List[Json]] =
    hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks(rootSid, reply))

  private def globalSnapshot(hub: ActorRef[InteractionHubCommand]): IO[List[Json]] =
    hub.?[List[Json]](reply => InteractionHubCommand.ListAllPendingAsks(reply))

  // ============================================================
  // ② turn 中断后的 pending 槽回收（+ 既有来源死亡路的零回归）
  // ============================================================

  test("② 正控：CleanupForSession(reason=turn-interrupted) 回收该 sourceSession 的槽并广播带 reason 的 askUserClosed") {
    val system = ActorSystem("askconc-cleanup-pos")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      got <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("ask-aaaa000000000000", "root-1", "root-1", got, system)
      _ <- hub ! InteractionHubCommand.Request(req)
      // 卡片可见 ⟹ 槽在册（handleRequest 先 update 再渲染）
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req.requestId))), "askUser 卡未渲染")
      _ <- hub ! InteractionHubCommand.CleanupForSession("root-1", reason = "turn-interrupted")
      _ <- awaitCond(
        sent.get.map(_.exists(isCard("askUserClosed", req.requestId))),
        "中断后未广播 askUserClosed（② 的槽位回收没接线）"
      )
      evs <- sent.get
      closed = evs.filter(isCard("askUserClosed", req.requestId)).last
      remaining <- snapshot(hub, "root-1")
      _ <- system.stopAll
    yield
      assertEquals(closed.hcursor.downField("reason").as[String], Right("turn-interrupted"))
      assertEquals(remaining.size, 0, "槽位必须被回收（否则是永久僵尸卡）")
    end for
  }

  test("② 负控：来源死亡路（reason 缺省）帧逐字节不变 + 只回收该 sourceSession，不误伤邻居") {
    val system = ActorSystem("askconc-cleanup-neg")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      gotA <- Ref.of[IO, Option[List[String]]](None)
      gotB <- Ref.of[IO, Option[List[String]]](None)
      reqA <- askRequest("ask-bbbb000000000000", "root-1", "node-a", gotA, system)
      reqB <- askRequest("ask-cccc000000000000", "root-1", "node-b", gotB, system)
      _ <- hub ! InteractionHubCommand.Request(reqA)
      _ <- hub ! InteractionHubCommand.Request(reqB)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", reqB.requestId))), "askUser 卡未渲染")
      // 缺省 reason = 既有来源死亡调用形态（NodeEngine / BackoffSupervisor 原样传参）
      _ <- hub ! InteractionHubCommand.CleanupForSession("node-a")
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUserClosed", reqA.requestId))), "未广播 askUserClosed")
      evs <- sent.get
      closed = evs.filter(isCard("askUserClosed", reqA.requestId)).last
      survivors <- snapshot(hub, "root-1")
      _ <- system.stopAll
    yield
      assert(
        closed.hcursor.downField("reason").succeeded == false,
        s"缺省 reason 不得新增帧字段（旧前端/旧断言零回归）: $closed"
      )
      assertEquals(survivors.size, 1, "只回收 node-a 的槽，node-b 的槽必须存活")
      assertEquals(survivors.head.hcursor.downField("requestId").as[String], Right(reqB.requestId))
    end for
  }

  // ============================================================
  // ③ 全局快照 vs 按会话快照
  // ============================================================

  test("③ 正控：ListAllPendingAsks 跨 root 一次性返回全部仍 pending 的 ask（按创建序）") {
    val system = ActorSystem("askconc-global-snap")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-2", (j: Json) => sent.update(_ :+ j))
      got1 <- Ref.of[IO, Option[List[String]]](None)
      got2 <- Ref.of[IO, Option[List[String]]](None)
      req1 <- askRequest("ask-dddd000000000000", "root-1", "node-a", got1, system)
      req2 <- askRequest("ask-eeee000000000000", "root-2", "node-a", got2, system)
      _ <- hub ! InteractionHubCommand.Request(req1)
      _ <- hub ! InteractionHubCommand.Request(req2)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req2.requestId))), "第二张卡未渲染")
      all <- globalSnapshot(hub)
      _ <- system.stopAll
    yield
      assertEquals(all.size, 2, "全局快照必须跨 root（前端重建待办条的唯一权威）")
      assertEquals(
        all.map(_.hcursor.downField("requestId").as[String].toOption.get),
        List(req1.requestId, req2.requestId),
        "按创建序（最老在前）"
      )
      assert(all.forall(_.hcursor.downField("replayed").as[Boolean].contains(true)), "重放标记必须逐帧带上")
    end for
  }

  test("③ 负控：按会话 ListPendingAsks 仍只返回本 root（不串扰、零回归）") {
    val system = ActorSystem("askconc-scoped-snap")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      got1 <- Ref.of[IO, Option[List[String]]](None)
      got2 <- Ref.of[IO, Option[List[String]]](None)
      req1 <- askRequest("ask-ffff000000000000", "root-1", "node-a", got1, system)
      req2 <- askRequest("ask-1111000000000000", "root-2", "node-a", got2, system)
      _ <- hub ! InteractionHubCommand.Request(req1)
      _ <- hub ! InteractionHubCommand.Request(req2)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req1.requestId))), "第一张卡未渲染")
      scoped1 <- snapshot(hub, "root-1")
      scoped2 <- snapshot(hub, "root-2")
      _ <- system.stopAll
    yield
      assertEquals(scoped1.map(_.hcursor.downField("requestId").as[String].toOption.get), List(req1.requestId))
      assertEquals(scoped2.map(_.hcursor.downField("requestId").as[String].toOption.get), List(req2.requestId))
    end for
  }

  // ============================================================
  // ⑥ 被丢弃的答复必须用户可见
  // ============================================================

  test("⑥ 正控：形态不符的答复 → 广播 interactionAnswerRejected(reason=shape-mismatch) 且卡片保留、随后仍可正常作答") {
    val system = ActorSystem("askconc-reject-shape")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      got <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("ask-2222000000000000", "root-1", "node-a", got, system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req.requestId))), "卡片未渲染")
      // 形态不符：ask 卡必须收 `answers`，这里给 permission 形态的 `approved`
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(req.requestId, "root-1", Json.obj("approved" -> Json.fromBoolean(true)))
      )
      _ <- awaitCond(
        sent.get.map(_.exists(isCard("interactionAnswerRejected", req.requestId))),
        "形态不符未广播用户可见反馈（⑥ 未接线）"
      )
      evs <- sent.get
      rejected = evs.filter(isCard("interactionAnswerRejected", req.requestId)).last
      stillPending <- snapshot(hub, "root-1")
      // 卡片保留 ⇒ 正常形态的答复仍能完成（#12 语义零回归）
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(req.requestId, "root-1", Json.obj("answers" -> Json.arr(Json.fromString("继续"))))
      )
      answered <- awaitCond(got.get.map(_.isDefined), "卡片保留后正常答复未完成") *> got.get
      _ <- system.stopAll
    yield
      assertEquals(rejected.hcursor.downField("reason").as[String], Right("shape-mismatch"))
      assertEquals(rejected.hcursor.downField("detail").as[String].isRight, true)
      assertEquals(rejected.hcursor.downField("sessionId").as[String], Right("root-1"))
      assertEquals(stillPending.size, 1, "形态不符不得消费卡片（#12）")
      assertEquals(answered, Some(List("继续")))
    end for
  }

  test("⑥ 负控：形态正确的答复不产生任何 rejected 帧（防「每次答复都弹提示」）") {
    val system = ActorSystem("askconc-reject-neg")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      got <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("ask-3333000000000000", "root-1", "node-a", got, system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req.requestId))), "卡片未渲染")
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(req.requestId, "root-1", Json.obj("answers" -> Json.arr(Json.fromString("继续"))))
      )
      _ <- awaitCond(got.get.map(_.isDefined), "正常答复未完成")
      _ <- IO.sleep(150.millis)
      evs <- sent.get
      remained <- snapshot(hub, "root-1")
      _ <- system.stopAll
    yield
      assertEquals(evs.count(j => j.hcursor.downField("type").as[String].contains("interactionAnswerRejected")), 0)
      assertEquals(remained.size, 0, "正常答复必须消费卡片")
    end for
  }

  test("⑥ 正控：未知 requestId 的答复 → 广播 interactionAnswerRejected(reason=unknown-request-id)") {
    val system = ActorSystem("askconc-reject-unknown")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      got <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("ask-4444000000000000", "root-1", "node-a", got, system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req.requestId))), "卡片未渲染")
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("ask-deadbeefdeadbeef", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("x"))))
      )
      _ <- awaitCond(
        sent.get.map(_.exists(isCard("interactionAnswerRejected", "ask-deadbeefdeadbeef"))),
        "未知 requestId 未广播用户可见反馈"
      )
      evs <- sent.get
      rejected = evs.filter(isCard("interactionAnswerRejected", "ask-deadbeefdeadbeef")).last
      _ <- system.stopAll
    yield assertEquals(rejected.hcursor.downField("reason").as[String], Right("unknown-request-id"))
    end for
  }
end AskConcurrencyReworkSpec
