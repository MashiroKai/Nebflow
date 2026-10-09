package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * DelegateBudget 拆除后的**墓碑契约**（2026-10-09 作者裁定：delegate 会话无时间预算）。
 *
 * 原 R11 第 4 层口径（U1=C-a「3600s 到点取消、人机等待期除外」）已整体拆除——
 * 本 spec 随之反转：
 *  - `register` 不装配任何计时器：无论声明多短的 `budget`、会话运行多久，
 *    `onTimeout` 永不触发（**无时间执行器**）；
 *  - `pause` / `resume` / `release` 对已「register」与未注册会话全部无害 no-op；
 *  - 长期运行的 delegate 会话不因时间被杀——活性治理只剩 `TaskStuckWatcher`
 *    的无进展判据（agent 侧事件停滞 + 工具相位卡死，`WaitingForUser` 豁免），
 *    与 node 节点「无超时、只治真卡死」同一哲学（stuck 恢复用例归
 *    `TaskStuckWatcherSpec` / `StuckRecovery*Spec`，保持绿）。
 */
class DelegateBudgetSpec extends CatsEffectSuite:

  test("tombstone: register arms nothing — onTimeout never fires, however short the declared budget"):
    for
      fired <- Deferred[IO, Unit]
      // 声明 50ms 预算：旧机制在 ~50ms 处必经 onTimeout 收割；拆除后必须无声。
      _ <- DelegateBudget.register("delegate-kernel-t1", budget = 50.millis)(fired.complete(()).void)
      _ <- IO.sleep(400.millis) // ≫ 声明预算 8 倍
      after <- fired.tryGet
    yield assert(after.isEmpty, "拆除后不存在时间执行器——预算声明一律惰性，不得触发取消")

  test("tombstone: a long-running delegate session is not killed by time (pause/resume/release are no-ops)"):
    for
      fired <- Deferred[IO, Unit]
      _ <- DelegateBudget.register("delegate-kernel-t2", budget = 100.millis)(fired.complete(()).void)
      // 全信号链打一遍（含「终态释放」）——之后长时间等待也绝不出现到点取消。
      _ <- DelegateBudget.pause("delegate-kernel-t2")
      _ <- DelegateBudget.resume("delegate-kernel-t2")
      _ <- DelegateBudget.release("delegate-kernel-t2")
      _ <- IO.sleep(300.millis)
      after <- fired.tryGet
      // 未注册（非 delegate 轨）会话的信号同样无害 no-op。
      _ <- DelegateBudget.pause("node-41-some-session")
      _ <- DelegateBudget.resume("node-41-some-session")
      _ <- DelegateBudget.release("node-41-some-session")
    yield assert(after.isEmpty, "长会话不因时间被杀；pause/resume/release 退化为无害 no-op")

  test("tombstone: no live budget channel ever exists (activeCount pinned at 0)"):
    assertEquals(DelegateBudget.activeCount, 0)

end DelegateBudgetSpec
