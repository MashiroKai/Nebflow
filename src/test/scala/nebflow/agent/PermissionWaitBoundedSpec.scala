package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.*

import scala.concurrent.duration.*

/**
 * permission-landing 批 P1（2026-11-18 重派裁定「超时必到」）：权限等待
 * **有界**原语的钉子 spec——取代 WaitTimeoutR1PermissionSpec（R1「无超时」口径
 * 由本批推翻；R1 保住的半边——「用户能答时不被定时器抢先拒绝」——继续钉）。
 *
 * 三组覆盖（对应重派单测试组②③）：
 *  ① 超时路径不挂死、结论可读：到期 ⇒ TimedOut + withdraw 恰好执行一次；
 *  ② 等待期内作答 ⇒ Answered 如实返回（允许/拒绝都算用户真实操作）；
 *  ③ 超时撤回后迟到作答 ⇒ 槽位已被 CloseRequest 回收（unknown-request-id），
 *    deferred 不被二次消费，等待方不复活。
 *
 * 时间模拟手法（虚拟时钟，零真实等待）：cats-effect TestControl 把 IO.sleep /
 * timeoutTo 的时限虚拟推进到毫秒级真实时间。
 */
class PermissionWaitBoundedSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  test("P1 超时腿: 到期以 TimedOut 结算、withdraw 恰好执行一次、deferred 不被完成") {
    val program: IO[(AgentCore.PermissionWaitOutcome, Int, Option[Boolean])] = for
      d <- Deferred[IO, Boolean]
      withdrawals <- Ref.of[IO, Int](0)
      withdraw = withdrawals.update(_ + 1)
      fiber <- AgentCore.awaitPermissionDecisionBounded(d, 10_000L, withdraw).start
      // 虚拟时钟推进 10s（超时上限）+ 1s 余量 —— 无作答
      _ <- IO.sleep(11.seconds)
      outcome <- fiber.joinWithNever
      // 迟到完成：撤回已发生，槽位已由调用方回收 —— deferred 仍可完成但无人在等
      _ <- d.complete(true).void
      n <- withdrawals.get
      settled <- d.tryGet
    yield (outcome, n, settled)

    TestControl.executeEmbed(program).map { case (outcome, withdrawals, settled) =>
      assertEquals(outcome, AgentCore.PermissionWaitOutcome.TimedOut(10_000L), "超时必须以显式 TimedOut 失败关闭——绝不在无作答时挂死")
      assertEquals(withdrawals, 1, "超时撤回（CloseRequest）必须恰好执行一次")
      assertEquals(settled, Some(true), "迟到完成落在等待方已离场之后——不复活、不报错")
    }
  }

  test("P1 作答腿: 等待期内用户批准/拒绝都如实返回（不被定时器抢先）") {
    val program: IO[(AgentCore.PermissionWaitOutcome, AgentCore.PermissionWaitOutcome)] = for
      dApprove <- Deferred[IO, Boolean]
      dDeny <- Deferred[IO, Boolean]
      withdrawals <- Ref.of[IO, Int](0)
      withdraw = withdrawals.update(_ + 1)
      approveFiber <- AgentCore.awaitPermissionDecisionBounded(dApprove, 600_000L, withdraw).start
      // 9min59s 作答（< 10min 上限；虚拟时钟）—— R1 保住的半边：能答时不抢先拒
      _ <- IO.sleep(599.seconds) *> dApprove.complete(true).void
      approved <- approveFiber.joinWithNever
      denyFiber <- AgentCore.awaitPermissionDecisionBounded(dDeny, 600_000L, withdraw).start
      _ <- dDeny.complete(false).void
      denied <- denyFiber.joinWithNever
      n <- withdrawals.get
      _ = assertEquals(n, 0, "作答腿绝不触发超时撤回")
    yield (approved, denied)

    TestControl.executeEmbed(program).map { case (approved, denied) =>
      assertEquals(approved, AgentCore.PermissionWaitOutcome.Answered(true))
      assertEquals(denied, AgentCore.PermissionWaitOutcome.Answered(false), "用户主动拒绝如实传播（#12 劝停计数语义留给调用方）")
    }
  }

  test("P1 mcpPermission 腿: 同款有界——到期 Left(ms)、作答 Right 如实") {
    val program: IO[(Either[Long, String], Either[Long, String])] = for
      dTimeout <- Deferred[IO, nebflow.shared.McpPermissionAnswer]
      dAnswer <- Deferred[IO, nebflow.shared.McpPermissionAnswer]
      timeoutFiber <- AgentCore.awaitMcpPermissionDecisionBounded(dTimeout, 5_000L, IO.unit).start
      _ <- IO.sleep(6.seconds)
      timedOut <- timeoutFiber.joinWithNever
      answerFiber <- AgentCore.awaitMcpPermissionDecisionBounded(dAnswer, 600_000L, IO.unit).start
      _ <- dAnswer.complete(nebflow.shared.McpPermissionAnswer(approved = true, scope = None, upgradeMode = None)).void
      answered <- answerFiber.joinWithNever
    yield (timedOut.map(_ => "unreachable"), answered.map(_ => "approved"))

    TestControl.executeEmbed(program).map { case (timedOut, answered) =>
      assertEquals(timedOut, Left(5_000L))
      assertEquals(answered, Right("approved"))
    }
  }

  test("P1 缺省阈值: 无环境覆盖时 = 600s（10min——宽松于被移除的 5min 一倍，远小于卡死现场 40-60min）") {
    // 测试 JVM 不设 NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC ⇒ 断言缺省值本体。
    assertEquals(AgentCore.permissionWaitTimeoutMs, 600_000L)
  }

  test("P1 撤回收口: 超时撤回（CloseRequest）后迟到作答被拒——等待方不复活、卡已关") {
    val system = nebflow.actor.ActorSystem("perm-bounded-withdraw")
    val prog: IO[Boolean] = for
      hub <- system.spawn(InteractionHub(), "hub-bounded-withdraw")
      sent <- Ref.of[IO, List[Json]](Nil)
      // live-root 先注册、请求却指向 zombie root——P0 批会把卡重定锚到 live-root
      //（渲染成对话内卡片）；随后调用方超时撤回：关卡帧必须广播到该窗口。
      d <- Deferred[IO, Boolean]
      _ <- hub ! InteractionHubCommand.RegisterRoot("live-root", (j: Json) => sent.update(_ :+ j))
      _ <- hub ! InteractionHubCommand.Request(
        InteractionRequest(
          requestId = "perm-t1",
          kind = InteractionKind.Permission,
          payload = Json.obj("type" -> Json.fromString("askPermission"), "toolName" -> Json.fromString("Bash")),
          reply = InteractionReply.PermissionReply(d),
          rootSessionId = "zombie",
          sourceAgent = "node-review",
          sourceSession = "node-session"
        )
      )
      _ <- IO.sleep(50.millis)
      // 调用方超时：先撤回（关卡帧广播 + 槽位回收），再本地失败关闭
      _ <- hub ! InteractionHubCommand.CloseRequest("perm-t1")
      _ <- IO.sleep(100.millis)
      closedFrames <- sent.get.map(_.filter(_.hcursor.downField("type").as[String].contains("askUserClosed")))
      // 迟到作答：槽位已回收 ⇒ 拒收（unknown-request-id），deferred 不被消费
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("perm-t1", "live-root", Json.obj("approved" -> Json.fromBoolean(true)))
      )
      _ <- IO.sleep(100.millis)
      settled <- d.tryGet
      _ <- system.stopAll
    yield closedFrames.nonEmpty && settled.isEmpty

    prog.map { ok =>
      assert(ok, "撤回必须广播关卡帧（卡从所有窗口退役）且迟到作答不得完成已撤回的等待")
    }
  }

end PermissionWaitBoundedSpec
