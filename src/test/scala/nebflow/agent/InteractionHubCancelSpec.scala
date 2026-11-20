package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*
import nebflow.actor.{
  ActorSystem,
  AskCancelOutcome,
  Behaviors,
  InteractionAnswered,
  InteractionHubCommand,
  InteractionKind,
  InteractionReply,
  InteractionRequest
}
import nebflow.shared.AskUserCancelled

/**
 * askucancel 批（2026-10-08）—— hub `CancelRequest` 的核心语义（C1 引擎证据，
 * 单测级：真 hub 实例 + 真消息 + 真回复端，无运行实例、无运行态观测）。
 *
 * 钉住四件事：
 *   ① 正控：取消在册 AskUser 槽 ⇒ 裁决 Cancelled + 广播 askUserClosed{reason:
 *      "user-cancelled"} + 发起方答复端收到 AskUserCancelled 取消信号（等待终结）
 *      + 槽位从快照消失；
 *   ② 幂等：重复取消 / 取消已答问题 / 取消从未存在的 id ⇒ 同一 UnknownRequestId
 *      终态（明确语义，不是错误面）；
 *   ③ kind 闸：permission 卡不进本取消面 ⇒ NotAskUser + 槽位保留 + 零关卡广播 +
 *      既有 approve 生命周期不受影响；
 *   ④ 邻居不受误伤 + 自定义 reason 透传。
 */
class InteractionHubCancelSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def mkHub(system: ActorSystem): IO[nebflow.actor.ActorRef[InteractionHubCommand]] =
    system.spawn(InteractionHub(), "hub-cancel-test")

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
  end awaitCond

  private def isCard(tpe: String, requestId: String)(j: Json): Boolean =
    j.hcursor.downField("type").as[String].contains(tpe) &&
      j.hcursor.downField("requestId").as[String].contains(requestId)

  /** AskUser 请求（replyTo 为真答复端——取消信号落地可断言）。 */
  private def askRequest(
    requestId: String,
    gotAnswers: Ref[IO, Option[List[String]]],
    system: ActorSystem,
    rootSid: String = "root-1",
    sourceSession: String = "src-1"
  ): IO[InteractionRequest] =
    system
      .spawn(
        Behaviors.receiveMessage[List[String]] { answers =>
          gotAnswers.set(Some(answers)).as(Behaviors.stopped)
        },
        s"ask-sink-$requestId"
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

  private def permRequest(requestId: String, deferred: Deferred[IO, Boolean]): InteractionRequest =
    InteractionRequest(
      requestId = requestId,
      kind = InteractionKind.Permission,
      payload = Json.obj("type" -> "askPermission".asJson, "toolName" -> "Edit".asJson),
      reply = InteractionReply.PermissionReply(deferred),
      rootSessionId = "root-1",
      sourceAgent = "Frontend",
      sourceSession = "src-1"
    )

  /** CancelRequest 的裁决腿：带裁决 reply 端（ask 单点形态，hub.? 同款）。 */
  private def cancel(
    hub: nebflow.actor.ActorRef[InteractionHubCommand],
    system: ActorSystem,
    requestId: String,
    reason: String = "",
    by: String = "web"
  ): IO[AskCancelOutcome] =
    for
      d <- Deferred[IO, AskCancelOutcome]
      replyRef <- system.spawn(
        Behaviors.receiveMessage[AskCancelOutcome] { outcome =>
          d.complete(outcome).void.as(Behaviors.stopped)
        },
        s"cancel-outcome-${requestId.replaceAll("[^A-Za-z0-9-]", "_")}-${System.nanoTime()}"
      )
      _ <- hub ! InteractionHubCommand.CancelRequest(requestId, reason, by, Some(replyRef))
      outcome <- d.get.timeout(5.seconds)
    yield outcome

  private def snapshot(hub: nebflow.actor.ActorRef[InteractionHubCommand], rootSid: String): IO[List[Json]] =
    hub.?[List[Json]](reply => InteractionHubCommand.ListPendingAsks(rootSid, reply))

  // ───────────────────────────── ① 正控 ─────────────────────────────

  test("① 取消在册 AskUser 槽：裁决 Cancelled + 广播 user-cancelled 关卡帧 + 发起方收到取消信号 + 槽位终结") {
    val system = ActorSystem("hub-cancel-pos")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      got <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("asknb-cancel01", got, system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", req.requestId))), "askUser 卡未渲染")
      outcome <- cancel(hub, system, req.requestId, by = "rest")
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUserClosed", req.requestId))), "未广播 askUserClosed")
      signal <- got.get
      remaining <- snapshot(hub, "root-1")
      _ <- system.stopAll
    yield
      val closed = sent.get.unsafeRunSync().filter(isCard("askUserClosed", req.requestId)).last
      assertEquals(outcome, AskCancelOutcome.Cancelled(req.requestId, InteractionKind.AskUser, "root-1", "src-1"))
      // 关卡帧：同族形状（sessionId=接收窗口 / requestId / sourceSession / reason 缺省补 user-cancelled）
      assertEquals(closed.hcursor.downField("sessionId").as[String], Right("root-1"))
      assertEquals(closed.hcursor.downField("sourceSession").as[String], Right("src-1"))
      assertEquals(closed.hcursor.downField("reason").as[String], Right("user-cancelled"))
      // 发起方终结通知：答复端收到的是取消信号，不是答案
      assert(signal.isDefined, "发起方答复端未收到任何终结通知")
      assert(AskUserCancelled.isCancelled(signal.get), s"答复端收到的东西不是取消信号：${signal.get}")
      assert(
        signal.get.head.contains(req.requestId) && signal.get.head.contains(AskUserCancelled.Code),
        s"取消信号缺 requestId/机器码：${signal.get.head}"
      )
      // 等待状态终结：槽位从快照消失（不再是可作答的僵尸卡）
      assertEquals(remaining.size, 0, "取消后槽位仍在册（等待未终结）")
    end for
  }

  // ───────────────────────────── ② 幂等 ─────────────────────────────

  test("② 幂等：重复取消 / 取消已答问题 / 取消未知 id ⇒ 同一 UnknownRequestId 终态") {
    val system = ActorSystem("hub-cancel-idem")
    for
      hub <- mkHub(system)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      got <- Ref.of[IO, Option[List[String]]](None)
      req <- askRequest("asknb-cancel02", got, system)
      _ <- hub ! InteractionHubCommand.Request(req)
      _ <- awaitCond(hub.?[List[Json]](r => InteractionHubCommand.ListPendingAsks("root-1", r)).map(_.nonEmpty), "槽未在册")
      first <- cancel(hub, system, req.requestId)
      again <- cancel(hub, system, req.requestId)
      never <- cancel(hub, system, "asknb-never-existed")
      // 已答问题：换一个新槽，先正常作答再取消 ⇒ 同样回 Unknown
      got2 <- Ref.of[IO, Option[List[String]]](None)
      req2 <- askRequest("asknb-cancel03", got2, system)
      _ <- hub ! InteractionHubCommand.Request(req2)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered(req2.requestId, "root-1", Json.obj("answers" -> Json.arr("ok".asJson)))
      )
      // hub 各命令走 forkTurn 异步 fiber——答复端收到 ⟹ 同一原子事务里的槽位移除已提交，
      // 之后的取消才必然命中幂等腿（否则与 Answered fiber 竞态，测试自身不确定）。
      _ <- awaitCond(got2.get.map(_.isDefined), "答复未落地")
      afterAnswer <- cancel(hub, system, req2.requestId)
      _ <- system.stopAll
    yield
      assertEquals(first, AskCancelOutcome.Cancelled(req.requestId, InteractionKind.AskUser, "root-1", "src-1"))
      assertEquals(again, AskCancelOutcome.UnknownRequestId, "重复取消未回幂等终态")
      assertEquals(never, AskCancelOutcome.UnknownRequestId, "未知 id 未回幂等终态")
      assertEquals(afterAnswer, AskCancelOutcome.UnknownRequestId, "取消已答问题未回幂等终态")
    end for
  }

  // ───────────────────────────── ③ kind 闸 ─────────────────────────────

  test("③ kind 闸：permission 卡取消被拒（NotAskUser）且槽位保留、零关卡广播、approve 生命周期不受影响") {
    val system = ActorSystem("hub-cancel-kind")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      d <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.Request(permRequest("perm-cancel01", d))
      _ <- awaitCond(sent.get.map(_.exists(isCard("askPermission", "perm-cancel01"))), "permission 卡未渲染")
      before <- sent.get.map(_.length)
      outcome <- cancel(hub, system, "perm-cancel01")
      _ <- IO.sleep(100.millis)
      after <- sent.get.map(_.length)
      // 闸没有误伤生命周期：正确的 approve 答复仍可达同一张卡（槽位在册的确定性证据
      // ——ListPendingAsks 快照只含 AskUser/McpPermission，permission 卡**本来**就不进
      // 快照，不能拿它当保留判据）。
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("perm-cancel01", "root-1", Json.obj("approved" -> true.asJson))
      )
      approved <- d.get.timeout(5.seconds)
      _ <- system.stopAll
    yield
      assertEquals(outcome, AskCancelOutcome.NotAskUser(InteractionKind.Permission))
      assertEquals(after, before, "被拒取消不该产生任何 askUserClosed 关卡帧")
      assertEquals(approved, true, "被拒取消误删了 permission 槽位（approve 不可达）")
    end for
  }

  // ───────────────────────────── ④ 邻居 + reason 透传 ─────────────────────────────

  test("④ 同根双问只取消指名的一张；自定义 reason 原样进关卡帧") {
    val system = ActorSystem("hub-cancel-neighbor")
    for
      hub <- mkHub(system)
      sent <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (j: Json) => sent.update(_ :+ j))
      gotA <- Ref.of[IO, Option[List[String]]](None)
      gotB <- Ref.of[IO, Option[List[String]]](None)
      reqA <- askRequest("asknb-cancel04", gotA, system)
      reqB <- askRequest("asknb-cancel05", gotB, system)
      _ <- hub ! InteractionHubCommand.Request(reqA)
      _ <- hub ! InteractionHubCommand.Request(reqB)
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUser", reqA.requestId))), "A 卡未渲染")
      outcome <- cancel(hub, system, reqA.requestId, reason = "asker-withdrew")
      _ <- awaitCond(sent.get.map(_.exists(isCard("askUserClosed", reqA.requestId))), "A 未广播关卡帧")
      closed <- IO(sent.get.unsafeRunSync().filter(isCard("askUserClosed", reqA.requestId)).last)
      remaining <- snapshot(hub, "root-1")
      signalA <- gotA.get
      signalB <- gotB.get
      _ <- system.stopAll
    yield
      assertEquals(outcome, AskCancelOutcome.Cancelled(reqA.requestId, InteractionKind.AskUser, "root-1", "src-1"))
      assertEquals(closed.hcursor.downField("reason").as[String], Right("asker-withdrew"), "自定义 reason 未透传")
      // 邻居 B 完好：仍在册、收不到任何取消信号、关卡帧不指名 B
      assertEquals(remaining.map(_.hcursor.downField("requestId").as[String].toOption).flatten, List(reqB.requestId))
      assertEquals(signalA.isDefined, true, "A 的答复端未收到取消信号")
      assertEquals(signalB, None, "邻居 B 被误伤（收到了取消信号）")
      assert(!sent.get.unsafeRunSync().exists(isCard("askUserClosed", reqB.requestId)), "邻居 B 被广播了关卡帧")
    end for
  }

end InteractionHubCancelSpec
