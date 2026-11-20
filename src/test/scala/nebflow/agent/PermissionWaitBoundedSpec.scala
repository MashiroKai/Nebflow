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
 * 旧语义 → 新语义（2026-11-18 逐条，供后续审计）：
 *   | 面 | 旧（R1，2026-09-03 作者裁定①） | 新（本批） |
 *   |---|---|---|
 *   | 等待原语 | `AgentCore.awaitPermissionDecision`（无界 `deferred.get`，
 *     「唯一出口 = Interrupt」） | `AgentCore.awaitPermissionDecisionBounded`
 *     （`timeoutTo(permissionWaitTimeoutMs)` + 超时先 `withdraw` 再结算） |
 *   | 阈值 | 无（旧 5min `PermissionTimeout` 由 R1 移除） | 缺省 600s，
 *     `NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC` 可配（>0 才生效） |
 *   | 用户能答时 | 绝不提前拒绝 | 逐字不变（等待期内作答照常生效） |
 *   | 用户答不了时（会话不可达 / 无人盯屏 / 卡从未渲染） | 无限挂起（现场：
 *     节点 WaitingForUser 40–60min、父链级联 Idle） | 到期失败关闭（按拒绝收口、
 *     可读结论、**不计入** #12 用户拒绝）+ 按 requestId 精确撤卡（CloseRequest） |
 *   旧断言不再成立的原因：`WaitTimeoutR1PermissionSpec` 直接调用已被删除的无界原语
 *   （并断言「6min 检查点仍挂起 ⟸ 根本没有超时」）——原语不复存在 ⇒ 编译即红。
 *   其「6min 检查点仍挂起、迟到 6min 回答生效」两条验收**已迁移**为本文件的
 *   「R1 保留半边」用例（钉在 10min 上限之下仍然成立）；「用户主动拒绝如实返回」
 *   由「作答腿」用例承接。全仓 `check-perm-presentation` 闸 B2 明令禁止无界 seam
 *   复活（无人作答时请求方无限挂起 = 本批要堵的死面），故不做「保留无界原语只为
 *   让旧 spec 编译通过」的迁就。
 *
 * 三组覆盖（对应重派单测试组②③）+ R1 保留半边：
 *  ① 超时路径不挂死、结论可读：到期 ⇒ TimedOut + withdraw 恰好执行一次；
 *  ② 等待期内作答 ⇒ Answered 如实返回（允许/拒绝都算用户真实操作）；
 *  ③ 超时撤回后迟到作答 ⇒ 槽位已被 CloseRequest 回收（unknown-request-id），
 *    deferred 不被二次消费，等待方不复活；
 *  ④ R1 保留半边：跨 6min（> 被移除的 5min 硬顶）仍挂起、不抢先自动拒绝。
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

  test("R1 保留半边（原 WaitTimeoutR1PermissionSpec 验收2 迁移）: 跨 6min 虚拟时间仍挂起、不抢先自动拒绝，迟到批准照常生效") {
    // 旧断言（无界原语，跨 6min ≫ 被移除的 5min PermissionTimeout）→ 新断言
    // （有界原语，阈值取产线缺省 600_000ms = 10min，由「缺省阈值」用例另钉）：
    //   - 「6min 检查点仍挂起」旧口径靠「没有超时」，新口径靠「10min > 6min」——
    //     被 R1 移除的那个 5min 硬顶仍被钉死（6min > 5min 却不结算）；
    //   - 「迟到 6min 的回答生效」两条口径逐字相同。
    // 阈值写字面量而非读 permissionWaitTimeoutMs：虚拟时钟用例要 env 无关的确定性
    // （环境变量若有覆盖，由「缺省阈值」用例的红来暴露，不在本用例叠红）。
    val program: IO[(Option[AgentCore.PermissionWaitOutcome], AgentCore.PermissionWaitOutcome, Int)] = for
      d <- Deferred[IO, Boolean]
      withdrawals <- Ref.of[IO, Int](0)
      withdraw = withdrawals.update(_ + 1)
      earlyOutcome <- Ref.of[IO, Option[AgentCore.PermissionWaitOutcome]](None)
      fiber <- AgentCore
        .awaitPermissionDecisionBounded(d, 600_000L, withdraw)
        .flatMap(r => earlyOutcome.set(Some(r)).as(r))
        .start
      // 用户迟到 6min 才回答（> 被移除的 5min 硬顶，< 10min 上限）。
      _ <- IO.sleep(6.minutes)
      earlyAtSixMin <- earlyOutcome.get
      _ <- d.complete(true).void
      result <- fiber.joinWithNever
      n <- withdrawals.get
    yield (earlyAtSixMin, result, n)

    TestControl.executeEmbed(program).map { case (earlyAtSixMin, result, n) =>
      assertEquals(
        earlyAtSixMin,
        None,
        "6min 检查点仍在等：不许存在「用户能答时提前结算」的自动拒绝路径（若还原 5min 硬顶，此处为 Some(TimedOut)）"
      )
      assertEquals(result, AgentCore.PermissionWaitOutcome.Answered(true), "迟到 6min 的批准必须生效")
      assertEquals(n, 0, "作答腿绝不触发超时撤回")
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
      // （渲染成对话内卡片）；随后调用方超时撤回：关卡帧必须广播到该窗口。
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
