package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * R1（wait-timeout-fix，2026-09-03 作者裁定①）：权限确认等待 = 人在环等待，
 * 无超时——与 AskUser 同构（actor ask timeout=None）。
 *
 * 审计 20260903 根因：AgentCore.PermissionTimeout（5min）+ `.timeoutTo` 到期
 * 自动拒绝 + permissionExpired 撤卡——用户 4min59s 回答有效、5min01s 回答时
 * 卡已被撤、工具已按拒绝处理，直接违反裁定①。
 *
 * 时间模拟手法（参数化阈值/虚拟时钟，零真实等待）：cats-effect TestControl
 * 把 IO.sleep 的 6 分钟虚拟推进到毫秒级真实时间——挂起跨过「6min ≫ 被移除的
 * 5min PermissionTimeout」仍然挂起、迟到回答照常生效。
 *
 * 验红变异：把 AgentCore.awaitPermissionDecision 还原为
 * `deferred.get.map(_ => true).timeoutTo(5.minutes, IO.pure(false))` 的语义
 * （5min 时提前以「自动拒绝」结算）→ 用例红（6min 检查点观测到提前结算 =
 * 复现超时自动拒绝）。恢复后绿。
 */
class WaitTimeoutR1PermissionSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  // ============================================================
  // 验收 2：权限卡挂起 6min+（虚拟时钟）→ 卡仍等、不自动拒绝、迟到回答生效
  // ============================================================

  test("R1 验收2: 权限决策等待跨 6min 虚拟时间仍挂起——无自动拒绝，迟到批准生效") {
    val program: IO[(Option[Boolean], Boolean)] = for
      d <- Deferred[IO, Boolean]
      // 观测点：等待一旦提前结算（= 存在超时自动拒绝路径）立即记录其结果。
      earlyOutcome <- Ref.of[IO, Option[Boolean]](None)
      fiber <- AgentCore.awaitPermissionDecision(d)
        .flatMap(r => earlyOutcome.set(Some(r)).as(r)).start
      // 用户迟到 6min 才回答（> 被移除的 5min PermissionTimeout）。
      _ <- IO.sleep(6.minutes)
      // 6min 检查点：等待必须仍挂起（R1 后无任何提前结算路径；
      // 变异下 5min 时已以 false「自动拒绝」结算 → 观测到 Some(false) → 红）。
      earlyAtSixMin <- earlyOutcome.get
      _ <- d.complete(true).void
      result <- fiber.joinWithNever // 迟到回答落地 → 等待以用户决定收尾
    yield (earlyAtSixMin, result)

    TestControl.executeEmbed(program).map { case (earlyAtSixMin, approved) =>
      assertEquals(earlyAtSixMin, None, "等待在用户回答前就提前结算 = 存在自动拒绝路径（R1 禁止；若还原 5min 硬顶，此处为 Some(false)）")
      assertEquals(approved, true, "迟到 6min 的回答必须生效（不自动拒绝）")
    }
  }

  test("R1 语义保留: 用户主动拒绝（approved=false）如实返回——拒绝是真实用户操作，非超时") {
    val program: IO[Boolean] = for
      d <- Deferred[IO, Boolean]
      fiber <- AgentCore.awaitPermissionDecision(d).start
      _ <- IO.sleep(50.millis) *> d.complete(false).void
      result <- fiber.joinWithNever
    yield result

    TestControl.executeEmbed(program).map { approved =>
      assertEquals(approved, false, "用户主动拒绝必须如实传播（#12 劝停计数语义不变）")
    }
  }

end WaitTimeoutR1PermissionSpec
